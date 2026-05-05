package ru.hse.jblockstorage.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.hse.jblockstorage.blockchain.Block;
import ru.hse.jblockstorage.blockchain.Blockchain;
import ru.hse.jblockstorage.network.BlockResponseMessage;
import ru.hse.jblockstorage.network.BroadcastBlockMessage;
import ru.hse.jblockstorage.network.GetBlockMessage;
import ru.hse.jblockstorage.network.PeerSession;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Сервис, отвечающий за синхронизацию блокчейна между узлами.
 * <p>
 * Закрывает три аспекта ТЗ:
 * <ul>
 *   <li><b>4.1.1.3.3 — Валидация блоков:</b> блоки, приходящие из сети,
 *       проверяются через {@link Block#validate()} перед добавлением;</li>
 *   <li><b>4.1.1.3.4 — Longest Chain Rule:</b> если у пира более длинная
 *       цепь, мы запрашиваем недостающие блоки;</li>
 *   <li><b>8.1.2 — Интеграционное испытание:</b> новый узел догоняет
 *       существующую сеть при подключении.</li>
 * </ul>
 *
 * <h3>Два сценария работы</h3>
 * <ol>
 *   <li><b>Push (broadcast):</b> узел A создал блок и сразу разослал его
 *       соседям через {@link BroadcastBlockMessage}. Получатели валидируют
 *       и добавляют — самый быстрый путь до распространения новой
 *       транзакции по сети.</li>
 *   <li><b>Pull (sync on handshake):</b> новый узел подключается к сети,
 *       видит в handshake'е, что у пира больше блоков, и запрашивает
 *       недостающие через {@link GetBlockMessage} → {@link BlockResponseMessage}.
 *       Этот путь покрывает случаи, когда узел был офлайн или только что
 *       создан.</li>
 * </ol>
 *
 * <h3>Threading</h3>
 * Все обработчики вызываются на потоках Netty. Мутации блокчейна
 * сериализуются через {@code synchronized} в {@link Blockchain#appendBlock}.
 * Множества «уже виденных блоков» — на {@link ConcurrentHashMap}, чтобы
 * параллельные broadcast'ы не дублировались.
 *
 * <h3>Защита от циклов re-broadcast'а</h3>
 * Каждый принятый блок добавляется в {@link #seenBlockHashes}. Если этот же
 * блок придёт повторно — он не пересылается, что предотвращает бесконечный
 * флуд в сети с замкнутыми связями.
 */
public final class BlockSyncService {

    private static final Logger LOG = LoggerFactory.getLogger(BlockSyncService.class);

    /**
     * Лимит на количество блоков, которые мы запрашиваем за один sync.
     * Защищает от ситуации, когда злонамеренный пир заявил huge height —
     * мы не будем тонуть в сотнях запросов. Для учебной системы этого
     * лимита более чем достаточно.
     */
    public static final int MAX_SYNC_BLOCKS_PER_HANDSHAKE = 64;

    private final Blockchain blockchain;
    private final java.util.function.Supplier<Map<String, PeerSession>> sessionsSupplier;

    /**
     * Колбэк persistence: вызывается ПОСЛЕ успешного добавления блока
     * в локальную цепь. Если null — без persistence (in-memory режим).
     */
    private final Consumer<Block> onBlockAppended;

    /**
     * Хеши блоков, которые мы уже видели и обработали — для защиты от
     * повторных broadcast'ов в сети с циклами.
     */
    private final Set<String> seenBlockHashes = ConcurrentHashMap.newKeySet();

    public BlockSyncService(Blockchain blockchain,
                            java.util.function.Supplier<Map<String, PeerSession>> sessionsSupplier,
                            Consumer<Block> onBlockAppended) {
        this.blockchain = Objects.requireNonNull(blockchain, "blockchain");
        this.sessionsSupplier = Objects.requireNonNull(sessionsSupplier, "sessionsSupplier");
        this.onBlockAppended = onBlockAppended; // может быть null
        // Все блоки, которые уже есть в цепи (включая genesis), считаем
        // "виденными" — защищает от повторного broadcast'а нашего же блока.
        for (Block b : blockchain.getBlocks()) {
            seenBlockHashes.add(b.getHash());
        }
    }

    // ================================================================
    // Подписка на handshake — запуск sync если нужно
    // ================================================================

    /**
     * Слушатель {@code PeerManager.HandshakeListener}. Вызывается из
     * {@link ru.hse.jblockstorage.network.PeerManager} после регистрации
     * сессии нового пира.
     * <p>
     * Если у пира больше блоков, чем у нас — последовательно запрашиваем
     * недостающие. Запрашиваем все индексы сразу (Netty pipeline сериализует
     * их в порядке отправки) — ответы будут приходить независимо.
     */
    public void onHandshake(PeerSession peer, String peerNodeId, int peerHeight) {
        int myHeight = blockchain.height();
        if (peerHeight <= myHeight) {
            return; // ничего не нужно — мы не отстаём
        }
        int gap = peerHeight - myHeight;
        if (gap > MAX_SYNC_BLOCKS_PER_HANDSHAKE) {
            LOG.warn("Пир {} заявил height={}, у нас {} — ограничиваем sync до {} блоков",
                    shortNode(peerNodeId), peerHeight, myHeight, MAX_SYNC_BLOCKS_PER_HANDSHAKE);
            gap = MAX_SYNC_BLOCKS_PER_HANDSHAKE;
        }
        LOG.info("Sync с {} — запрашиваем {} блоков ({}..{})",
                shortNode(peerNodeId), gap, myHeight, myHeight + gap - 1);
        // Запрашиваем индексы myHeight, myHeight+1, ..., myHeight+gap-1.
        // Индекс N — это (N+1)-й блок в цепи, нумерация с 0 от genesis.
        for (int i = 0; i < gap; i++) {
            int wanted = myHeight + i;
            peer.send(new GetBlockMessage(wanted));
        }
    }

    // ================================================================
    // Обработка входящих сообщений
    // ================================================================

    /**
     * Обработка запроса блока: отдаём блок из локальной цепи, либо
     * {@code null} в ответе если такого индекса нет.
     */
    public void handleGetBlock(PeerSession peer, GetBlockMessage msg) {
        int index = msg.getIndex();
        Block block = blockchain.hasBlock(index) ? blockchain.getBlock(index) : null;
        peer.send(new BlockResponseMessage(index, block));
        if (block == null) {
            LOG.debug("GetBlock(index={}) — у нас нет такого, отвечаем null", index);
        }
    }

    /**
     * Обработка ответа на наш {@link GetBlockMessage}. Если блок валидный
     * и продолжает нашу цепь — добавляем его. Если блок пришёл «вне порядка»
     * (например, мы запросили 5..10, а 5 ещё не пришёл) — игнорируем,
     * потому что {@link Blockchain#appendBlock} требует строгой
     * последовательности. Это нормально: при следующем broadcast'е или
     * следующем handshake'е sync продолжится.
     */
    public void handleBlockResponse(PeerSession peer, BlockResponseMessage resp) {
        Block block = resp.getBlock();
        if (block == null) {
            LOG.debug("BlockResponse(index={}) — пир не имеет такого блока",
                    resp.getRequestedIndex());
            return;
        }
        applyIncomingBlock(block, /* rebroadcastFromPeer */ null,
                "BlockResponse(index=" + resp.getRequestedIndex() + ")");
    }

    /**
     * Обработка broadcast'а нового блока. Валидируем, добавляем, при
     * успехе — re-broadcast соседям (кроме отправителя).
     */
    public void handleBroadcastBlock(PeerSession peer, BroadcastBlockMessage msg) {
        Block block = msg.getBlock();
        if (block == null) {
            LOG.debug("BroadcastBlock с null блоком — игнор");
            return;
        }
        applyIncomingBlock(block, peer, "BroadcastBlock");
    }

    // ================================================================
    // Исходящий broadcast (вызывается uploader/NodeApplication после
    // создания нового блока)
    // ================================================================

    /**
     * Рассылает только что созданный блок всем активным пирам.
     * <p>
     * Помечает блок как виденный — чтобы при возможном re-broadcast'е
     * он не вызвал повторную обработку у нас же.
     */
    public void broadcastNewBlock(Block block) {
        Objects.requireNonNull(block, "block");
        seenBlockHashes.add(block.getHash());

        Map<String, PeerSession> sessions = sessionsSupplier.get();
        if (sessions.isEmpty()) {
            LOG.debug("broadcastNewBlock: пиров нет, рассылка пропущена");
            return;
        }
        BroadcastBlockMessage msg = new BroadcastBlockMessage(block);
        int sent = 0;
        for (PeerSession peer : sessions.values()) {
            if (peer.isActive()) {
                peer.send(msg);
                sent++;
            }
        }
        LOG.info("Broadcast блока index={} разослан {} пирам",
                block.getIndex(), sent);
    }

    // ================================================================
    // Внутренние утилиты
    // ================================================================

    /**
     * Общая логика для приёма блока из любого источника (broadcast или
     * sync-response). Возвращает {@code true} если блок был успешно
     * применён к цепи.
     */
    private boolean applyIncomingBlock(Block block, PeerSession originator, String source) {
        // 1. Дедупликация: если уже видели — пропускаем.
        if (!seenBlockHashes.add(block.getHash())) {
            LOG.debug("{}: блок hash={} уже обработан — пропускаем",
                    source, shortHash(block.getHash()));
            return false;
        }

        // 2. Пытаемся добавить.
        boolean added = blockchain.appendBlock(block);
        if (!added) {
            LOG.debug("{}: appendBlock отклонил блок index={} (height={}, prev={})",
                    source, block.getIndex(), blockchain.height(),
                    shortHash(block.getPrevHash()));
            return false;
        }

        LOG.info("{}: добавлен блок index={} (новая высота {})",
                source, block.getIndex(), blockchain.height());

        // 3. Persistence — если есть hook.
        if (onBlockAppended != null) {
            try {
                onBlockAppended.accept(block);
            } catch (Exception e) {
                LOG.warn("Persistence-хук бросил исключение для блока {}: {}",
                        block.getIndex(), e.toString());
            }
        }

        // 4. Re-broadcast — всем, кроме отправителя.
        rebroadcast(block, originator);
        return true;
    }

    private void rebroadcast(Block block, PeerSession exclude) {
        Map<String, PeerSession> sessions = sessionsSupplier.get();
        if (sessions.isEmpty()) return;

        // Собираем сессии, которым НЕ отправляем (источник + он сам уже знает).
        Set<PeerSession> excluded = new HashSet<>();
        if (exclude != null) excluded.add(exclude);

        BroadcastBlockMessage msg = new BroadcastBlockMessage(block);
        int sent = 0;
        for (PeerSession peer : sessions.values()) {
            if (!peer.isActive() || excluded.contains(peer)) continue;
            peer.send(msg);
            sent++;
        }
        if (sent > 0) {
            LOG.debug("Re-broadcast блока index={} разослан {} пирам",
                    block.getIndex(), sent);
        }
    }

    private static String shortHash(String hash) {
        if (hash == null) return "?";
        return hash.length() > 8 ? hash.substring(0, 8) + "…" : hash;
    }

    private static String shortNode(String nodeId) {
        if (nodeId == null) return "?";
        return nodeId.length() > 8 ? nodeId.substring(0, 8) + "…" : nodeId;
    }
}
