package ru.hse.jblockstorage.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.hse.jblockstorage.blockchain.Block;
import ru.hse.jblockstorage.blockchain.Blockchain;
import ru.hse.jblockstorage.blockchain.Transaction;
import ru.hse.jblockstorage.storage.ShardStorage;

import java.io.IOException;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Физический сборщик мусора шардов (Garbage Collection).
 * <p>
 * Закрывает ТЗ п. 4.1.1.4.3:
 * <blockquote>
 * «Удаление файла (логическое): отправка транзакции, помечающей файл как
 * "удаленный". Физическое удаление данных с узлов происходит при сборке мусора
 * (Garbage Collection).»
 * </blockquote>
 *
 * <h3>Алгоритм</h3>
 * Один проход GC:
 * <ol>
 *   <li>Собираем «живой набор» хешей — шарды всех UPLOAD/REPAIR транзакций,
 *       которые НЕ помечены как удалённые;</li>
 *   <li>Собираем «удалимый набор» — шарды UPLOAD'ов, у которых есть валидный
 *       DELETE от того же владельца;</li>
 *   <li>Перебираем локальные файлы шардов через {@link ShardStorage#list()}
 *       и удаляем те, что присутствуют в удалимом наборе и отсутствуют в живом.
 *       Шарды, не упомянутые ни в одной транзакции, НЕ удаляем — это могут быть
 *       pending uploads ещё не дошедших до этого узла блоков, или
 *       тестовые/восстановительные данные.</li>
 * </ol>
 *
 * <h3>Дедупликация</h3>
 * Один и тот же шард может встречаться в shardHashes разных файлов
 * (если, например, два файла начинаются с одинаковых 512 КБ). В этом случае
 * шард попадёт И в удалимый набор (через DELETED файл), И в живой —
 * приоритет у живого, мусор не удаляется. Это safety-net — лучше оставить
 * шард, который в одном файле «удалён», но в другом ещё нужен.
 *
 * <h3>Threading</h3>
 * Тики выполняются на отдельном single-thread scheduler'е. Метод
 * {@link #runOnce()} доступен пакету (тестам) для синхронного вызова без
 * запуска scheduler'а — удобно проверять детерминированно.
 *
 * <h3>Дизайн-замечание</h3>
 * GC принципиально не пишет ничего в блокчейн — это локальная операция
 * каждого узла-хранителя. Если узел A удалил у себя шард, а узел B нет —
 * downloader всё равно сможет скачать с B. Это снимает требование «все узлы
 * должны быть в синхронизированном состоянии при GC», что иначе было бы
 * сложно гарантировать в P2P без лидера.
 */
public final class ShardGcService implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ShardGcService.class);

    private final Blockchain blockchain;
    private final ShardStorage shardStorage;
    private final Duration interval;

    private ScheduledExecutorService scheduler;
    private boolean started;
    private boolean closed;

    /**
     * @param blockchain   ссылка на блокчейн (читается без блокировок,
     *                     {@link Blockchain#getBlocks()} возвращает unmodifiable)
     * @param shardStorage локальное хранилище шардов
     * @param interval     период между проходами GC (например, 60с в production,
     *                     1с в тестах)
     */
    public ShardGcService(Blockchain blockchain,
                          ShardStorage shardStorage,
                          Duration interval) {
        this.blockchain = Objects.requireNonNull(blockchain, "blockchain");
        this.shardStorage = Objects.requireNonNull(shardStorage, "shardStorage");
        this.interval = Objects.requireNonNull(interval, "interval");
        if (interval.toMillis() < 1) {
            throw new IllegalArgumentException("interval должен быть положительным");
        }
    }

    /** Запускает фоновую периодическую задачу. Идемпотентен — повторный вызов игнорируется. */
    public synchronized void start() {
        if (closed) throw new IllegalStateException("ShardGcService уже закрыт");
        if (started) return;
        started = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "jblockstorage-shard-gc");
            t.setDaemon(true);
            return t;
        });
        long ms = Math.max(1, interval.toMillis());
        scheduler.scheduleWithFixedDelay(this::tick, ms, ms, TimeUnit.MILLISECONDS);
        LOG.info("ShardGcService запущен (interval={}мс)", ms);
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (scheduler != null) {
            scheduler.shutdownNow();
            try {
                scheduler.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Один тик GC. Завёрнут в try/catch на верхнем уровне: исключение в одном
     * проходе не должно прибивать scheduler — следующий тик попробует ещё раз.
     */
    private void tick() {
        try {
            int removed = runOnce();
            if (removed > 0) {
                LOG.info("GC удалил {} шардов", removed);
            }
        } catch (Throwable t) {
            LOG.error("GC tick упал с непредвиденной ошибкой", t);
        }
    }

    /**
     * Один проход GC, синхронный. Возвращает число удалённых шардов.
     * Доступен пакету для детерминированных тестов.
     */
    int runOnce() throws IOException {
        Set<String> alive = new HashSet<>();
        Set<String> deletable = new HashSet<>();

        // Один проход по цепочке. Для каждой UPLOAD выясняем, удалена ли
        // она; в зависимости от ответа отправляем её shardHashes в один из
        // двух наборов. REPAIR-транзакции рассматриваем как продолжение
        // UPLOAD'а (тот же владелец, тот же файл), поэтому их шарды живые
        // только если оригинальный UPLOAD не помечен DELETE.
        for (Block block : blockchain.getBlocks()) {
            List<Transaction> txs = block.getTransactions();
            if (txs == null) continue;
            for (Transaction tx : txs) {
                if (tx.getKind() == Transaction.Kind.UPLOAD) {
                    if (blockchain.isDeleted(tx.getId())) {
                        if (tx.getShardHashes() != null) {
                            deletable.addAll(tx.getShardHashes());
                        }
                    } else {
                        if (tx.getShardHashes() != null) {
                            alive.addAll(tx.getShardHashes());
                        }
                    }
                } else if (tx.getKind() == Transaction.Kind.REPAIR) {
                    String origId = tx.getReferencedTxId();
                    if (origId == null) continue;
                    boolean origDeleted = blockchain.isDeleted(origId);
                    if (origDeleted) {
                        if (tx.getShardHashes() != null) {
                            deletable.addAll(tx.getShardHashes());
                        }
                    } else {
                        if (tx.getShardHashes() != null) {
                            alive.addAll(tx.getShardHashes());
                        }
                    }
                }
                // ACL и DELETE сами не несут shardHashes — они влияют только
                // через isDeleted на семантику UPLOAD'ов выше.
            }
        }

        // Удаляем файлы, которые попали в "удалимый, но не живой" набор.
        // Шарды, не упомянутые ни в одной транзакции, оставляем как есть —
        // см. javadoc класса.
        int removed = 0;
        for (String hash : shardStorage.list()) {
            if (deletable.contains(hash) && !alive.contains(hash)) {
                if (shardStorage.delete(hash)) {
                    removed++;
                    LOG.debug("GC: удалён шард {}", shortHash(hash));
                }
            }
        }
        return removed;
    }

    private static String shortHash(String hash) {
        if (hash == null) return "?";
        return hash.length() > 8 ? hash.substring(0, 8) + "…" : hash;
    }
}
