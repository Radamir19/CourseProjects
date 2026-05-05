package ru.hse.jblockstorage.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.hse.jblockstorage.blockchain.Block;
import ru.hse.jblockstorage.blockchain.Blockchain;
import ru.hse.jblockstorage.blockchain.StorageReceipt;
import ru.hse.jblockstorage.blockchain.Transaction;
import ru.hse.jblockstorage.network.PeerSession;
import ru.hse.jblockstorage.storage.Shard;
import ru.hse.jblockstorage.storage.ShardStorage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Сервис автоматической re-репликации файлов при выходе узлов из сети
 * (ТЗ п. 4.2.2 «Устойчивость к отказам узлов»).
 * <p>
 * Раз в {@link #checkInterval} секунд проходит по всем UPLOAD-транзакциям
 * текущего пользователя и для каждой проверяет, сколько живых хранителей
 * осталось. Если число живых реплик меньше {@code replicationFactor}, сервис
 * выбирает дополнительных пиров (которые ещё не хранят этот файл),
 * скачивает шарды (если у нас их нет локально) и пушит к новым хранителям.
 * <p>
 * После успешной re-репликации формирует {@link Transaction.Kind#REPAIR}
 * с полным актуальным списком реплик (старые живые + новые), подписывает
 * её приватным ключом владельца, добавляет в блокчейн и broadcast'ит.
 *
 * <h3>Источник шардов для repair'а</h3>
 * Сначала проверяется локальный {@link ShardStorage} — если шард там есть
 * (мы сами один из storers, либо репэрили этот файл раньше), используем
 * его. Иначе скачиваем из сети, перебирая живых хранителей через
 * {@link FileDownloader#fetchShard}. Это симметрично с тем, как работает
 * download — те же таймауты, те же retry-семантика по списку реплик.
 *
 * <h3>Threading</h3>
 * Запускается на однопоточном {@link ScheduledExecutorService}. Один tick —
 * полностью синхронный обход. Если tick не успел отработать до следующего
 * — следующий просто подождёт (мы используем
 * {@code scheduleWithFixedDelay}, а не {@code scheduleAtFixedRate}).
 *
 * <h3>Что сервис НЕ делает (ограничения дня 10)</h3>
 * <ul>
 *   <li>Не пытается восстановить шарды, которые потеряны у ВСЕХ хранителей —
 *       это невозможно без локальной копии. Тест fault-tolerance из
 *       ТЗ п. 4.2.2 выполняется при «до 30% узлов out» — то есть всегда
 *       остаётся хотя бы одна живая реплика для скачивания.</li>
 *   <li>Не делает proof-of-storage challenge — узел может подписать receipt
 *       и удалить шард с диска. Это вынесено в отдельное ограничение
 *       пояснительной записки.</li>
 *   <li>Не ограничивает суммарный трафик — если сеть очень нестабильна,
 *       repair может крутиться постоянно. На практике {@code checkInterval}
 *       ≥ 30с делает это незаметным.</li>
 * </ul>
 */
public final class RepairService implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(RepairService.class);

    /** Тайм-аут на скачивание одного шарда из сети при repair. */
    private static final long FETCH_TIMEOUT_MILLIS = 5_000;

    private final String selfNodeId;
    private final Blockchain blockchain;
    private final ShardStorage shardStorage;
    private final FileUploader uploader;
    private final FileDownloader downloader;
    private final java.util.function.Supplier<Map<String, PeerSession>> sessionsSupplier;
    private final int replicationFactor;
    private final java.time.Duration checkInterval;

    /** Persistence-хук для сохранения нового блока с REPAIR-транзакцией. */
    private final Consumer<Block> onBlockAppended;

    /** Колбэк для broadcast'а нового блока (RepairService -> BlockSyncService). */
    private final Consumer<Block> onBlockBroadcast;

    private ScheduledExecutorService scheduler;
    private boolean started;
    private boolean closed;

    /**
     * @param selfNodeId         id текущего узла (Base64 публичного ключа)
     * @param blockchain         локальный блокчейн
     * @param shardStorage       локальное хранилище шардов
     * @param uploader           {@link FileUploader} — нужен его метод
     *                           {@code pushShardsToStorers}
     * @param downloader         {@link FileDownloader} — нужен его метод
     *                           {@code fetchShard} для случая, когда
     *                           шарда у нас локально нет
     * @param sessionsSupplier   поставщик активных сессий (как в BlockSyncService)
     * @param replicationFactor  целевое K — желаемое число реплик
     * @param checkInterval      период между проверками
     * @param onBlockAppended    хук persistence (вызывается после addBlock; может быть null)
     * @param onBlockBroadcast   хук broadcast (вызывается после addBlock; может быть null)
     */
    public RepairService(String selfNodeId,
                         Blockchain blockchain,
                         ShardStorage shardStorage,
                         FileUploader uploader,
                         FileDownloader downloader,
                         java.util.function.Supplier<Map<String, PeerSession>> sessionsSupplier,
                         int replicationFactor,
                         java.time.Duration checkInterval,
                         Consumer<Block> onBlockAppended,
                         Consumer<Block> onBlockBroadcast) {
        this.selfNodeId = Objects.requireNonNull(selfNodeId, "selfNodeId");
        this.blockchain = Objects.requireNonNull(blockchain, "blockchain");
        this.shardStorage = Objects.requireNonNull(shardStorage, "shardStorage");
        this.uploader = Objects.requireNonNull(uploader, "uploader");
        this.downloader = Objects.requireNonNull(downloader, "downloader");
        this.sessionsSupplier = Objects.requireNonNull(sessionsSupplier, "sessionsSupplier");
        if (replicationFactor < 1) {
            throw new IllegalArgumentException("replicationFactor должен быть ≥1");
        }
        this.replicationFactor = replicationFactor;
        this.checkInterval = Objects.requireNonNull(checkInterval, "checkInterval");
        this.onBlockAppended = onBlockAppended;
        this.onBlockBroadcast = onBlockBroadcast;
    }

    /** Запускает фоновую периодическую задачу. Идемпотентен. */
    public synchronized void start() {
        if (closed) throw new IllegalStateException("RepairService уже закрыт");
        if (started) return;
        started = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "jblockstorage-repair");
            t.setDaemon(true);
            return t;
        });
        long interval = Math.max(1, checkInterval.toMillis());
        scheduler.scheduleWithFixedDelay(this::tick,
                interval, interval, TimeUnit.MILLISECONDS);
        LOG.info("RepairService запущен (interval={}мс, K={})",
                interval, replicationFactor);
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
     * Один проход repair-логики. Доступен пакету для тестов — можно
     * вызвать вручную, не запуская планировщик. Сразу после возврата
     * можно проверить состояние блокчейна и таблицы реплик.
     */
    void tick() {
        if (closed) return;
        try {
            List<Transaction> myFiles = blockchain.listByOwner(selfNodeId);
            for (Transaction upload : myFiles) {
                try {
                    repairOne(upload);
                } catch (Exception e) {
                    LOG.warn("Repair для tx={} упал: {}",
                            shortHash(upload.getId()), e.toString());
                }
            }
        } catch (Throwable t) {
            // Никогда не давать исключению в tick'е убить весь scheduler.
            LOG.error("Repair tick упал с непредвиденной ошибкой", t);
        }
    }

    /**
     * Repair одного файла: считает живых storers, при необходимости
     * добавляет новых, формирует REPAIR-транзакцию.
     * <p>
     * Возвращает {@code true} если был создан новый REPAIR-блок.
     */
    boolean repairOne(Transaction upload) throws IOException, InterruptedException {
        // 1. Берём актуальный список реплик: либо последний REPAIR, либо
        //    оригинальный UPLOAD.
        Transaction latest = blockchain.findLatestRepairFor(upload.getId()).orElse(upload);
        List<StorageReceipt> currentReplicas = latest.getReplicas() == null
                ? List.of() : latest.getReplicas();

        Map<String, PeerSession> sessions = sessionsSupplier.get();

        // 2. Считаем живых storers (для каждого шарда).
        // Группируем receipts: shardHash -> List<storerNodeId>.
        // shard считается достаточно реплицированным, если у него ≥K живых.
        // Для простоты MVP — общее число storers одинаково для всех шардов
        // (uploader всегда пушит ВСЕ шарды каждому из K). Поэтому считаем по
        // числу уникальных storers с активной сессией ИЛИ совпадающих с нами.
        Set<String> liveStorerIds = new HashSet<>();
        Set<String> allStorerIds = new HashSet<>();
        for (StorageReceipt r : currentReplicas) {
            String storer = r.getStorerPublicKey();
            allStorerIds.add(storer);
            if (storer.equals(selfNodeId)) {
                // Сами себя считаем живыми (мы хранители для своего же файла).
                liveStorerIds.add(storer);
                continue;
            }
            PeerSession s = sessions.get(storer);
            if (s != null && s.isActive()) {
                liveStorerIds.add(storer);
            }
        }

        if (liveStorerIds.size() >= replicationFactor) {
            // Всё ок, ничего делать не надо.
            return false;
        }

        int needNew = replicationFactor - liveStorerIds.size();
        LOG.info("Repair tx={}: живых {}, нужно {}, добираем {}",
                shortHash(upload.getId()), liveStorerIds.size(),
                replicationFactor, needNew);

        // 3. Выбираем кандидатов в новые storers — пиры, которых ЕЩЁ нет в
        // allStorerIds (включая мёртвых, чтобы не пытаться "переселить" в того,
        // чей receipt уже подписан, но кто в данный момент не отвечает).
        List<String> candidates = new ArrayList<>();
        for (Map.Entry<String, PeerSession> e : sessions.entrySet()) {
            String nodeId = e.getKey();
            if (nodeId.equals(selfNodeId)) continue;
            if (allStorerIds.contains(nodeId)) continue;
            if (!e.getValue().isActive()) continue;
            candidates.add(nodeId);
            if (candidates.size() >= needNew) break;
        }
        if (candidates.size() < needNew) {
            LOG.warn("Repair tx={}: недостаточно новых кандидатов ({} вместо {}). " +
                            "Пропускаем — попробуем в следующий tick",
                    shortHash(upload.getId()), candidates.size(), needNew);
            return false;
        }

        // 4. Получаем шарды — либо из локального ShardStorage, либо из сети.
        List<String> shardHashes = upload.getShardHashes();
        if (shardHashes == null || shardHashes.isEmpty()) {
            LOG.warn("Repair tx={}: в UPLOAD нет shardHashes — нечего репэрить",
                    shortHash(upload.getId()));
            return false;
        }
        List<Shard> shards = collectShards(latest, shardHashes, sessions);
        if (shards == null) {
            LOG.warn("Repair tx={}: не удалось собрать все шарды — ни локально, ни из сети",
                    shortHash(upload.getId()));
            return false;
        }

        // 5. Формируем preliminary REPAIR-TX (нужен для receipts).
        Transaction repair = Transaction.newRepair(selfNodeId, upload.getId(), shardHashes);
        String preliminaryRepairId = repair.calculatePreliminaryId();

        // 6. Пушим шарды кандидатам и собираем новые receipts.
        List<FileUploader.StorerHandle> chosen = new ArrayList<>();
        for (String nodeId : candidates) {
            PeerSession s = sessions.get(nodeId);
            if (s != null && s.isActive()) {
                chosen.add(new FileUploader.StorerHandle(nodeId, s));
            }
        }
        List<StorageReceipt> newReceipts;
        try {
            newReceipts = uploader.pushShardsToStorers(chosen, preliminaryRepairId, shards);
        } catch (java.util.concurrent.TimeoutException e) {
            LOG.warn("Repair tx={}: новые хранители не подтвердили приёмку: {}",
                    shortHash(upload.getId()), e.toString());
            return false;
        }

        // 7. Соединяем старые ЖИВЫЕ receipts (сохраняем proof of placement
        // для тех, кто всё ещё хранит) и новые. Мёртвые receipts НЕ
        // переносим — на момент REPAIR они уже не валидны как доказательство.
        List<StorageReceipt> finalReplicas = new ArrayList<>();
        for (StorageReceipt r : currentReplicas) {
            if (liveStorerIds.contains(r.getStorerPublicKey())) {
                finalReplicas.add(r);
            }
        }
        finalReplicas.addAll(newReceipts);

        // 8. Завершаем REPAIR-TX, подписываем, добавляем в блокчейн.
        repair.setReplicas(finalReplicas);
        // Подпись от имени владельца — это всегда selfNodeId (только владелец
        // имеет право репэрить свои файлы). Приватный ключ передаём через
        // uploader, у которого он уже есть.
        uploader.signTransactionAsOwner(repair);
        blockchain.addBlock(List.of(repair));
        Block newBlock = blockchain.getLatestBlock();

        if (onBlockAppended != null) {
            try { onBlockAppended.accept(newBlock); }
            catch (Exception e) { LOG.warn("Persist repair-блока упал: {}", e.toString()); }
        }
        if (onBlockBroadcast != null) {
            try { onBlockBroadcast.accept(newBlock); }
            catch (Exception e) { LOG.warn("Broadcast repair-блока упал: {}", e.toString()); }
        }

        LOG.info("Repair tx={} успешен: было живых {}, стало {} (добавлено {})",
                shortHash(upload.getId()),
                liveStorerIds.size(),
                finalReplicas.size(),
                newReceipts.size() / Math.max(1, shardHashes.size()));
        return true;
    }

    /**
     * Собирает все шарды файла — сначала из локального ShardStorage,
     * затем для отсутствующих качает из сети. Возвращает {@code null}
     * если хотя бы один шард не удалось собрать.
     */
    private List<Shard> collectShards(Transaction latest,
                                      List<String> shardHashes,
                                      Map<String, PeerSession> sessions) throws IOException, InterruptedException {
        // Индекс: shardHash → список nodeId хранителей (для fetch из сети).
        Map<String, List<String>> shardToStorers = new java.util.HashMap<>();
        for (StorageReceipt r : latest.getReplicas()) {
            shardToStorers.computeIfAbsent(r.getShardHashHex(),
                    k -> new ArrayList<>()).add(r.getStorerPublicKey());
        }

        List<Shard> result = new ArrayList<>(shardHashes.size());
        for (int i = 0; i < shardHashes.size(); i++) {
            String hash = shardHashes.get(i);

            // 1. Сначала ищем у себя.
            Optional<byte[]> local = shardStorage.load(hash);
            if (local.isPresent()) {
                result.add(new Shard(i, local.get()));
                continue;
            }

            // 2. Иначе качаем из сети.
            List<String> storers = shardToStorers.getOrDefault(hash, List.of());
            byte[] data = downloader.fetchShard(hash, storers, sessions);
            if (data == null) {
                LOG.debug("collectShards: шард {} ни локально, ни в сети",
                        shortHash(hash));
                return null;
            }
            result.add(new Shard(i, data));
        }
        return result;
    }

    private static String shortHash(String h) {
        if (h == null) return "?";
        return h.length() > 8 ? h.substring(0, 8) + "…" : h;
    }
}
