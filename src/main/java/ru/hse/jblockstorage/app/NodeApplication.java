package ru.hse.jblockstorage.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.hse.jblockstorage.blockchain.Block;
import ru.hse.jblockstorage.blockchain.Blockchain;
import ru.hse.jblockstorage.blockchain.BlockchainStore;
import ru.hse.jblockstorage.blockchain.Transaction;
import ru.hse.jblockstorage.config.NodeConfig;
import ru.hse.jblockstorage.crypto.KeyManager;
import ru.hse.jblockstorage.network.BootstrapDiscovery;
import ru.hse.jblockstorage.network.NodeClient;
import ru.hse.jblockstorage.network.NodeServer;
import ru.hse.jblockstorage.network.PeerInfo;
import ru.hse.jblockstorage.network.PeerManager;
import ru.hse.jblockstorage.network.PeerSession;
import ru.hse.jblockstorage.storage.FileChunker;
import ru.hse.jblockstorage.storage.ShardStorage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Главный объект-оркестратор узла. Собирает в одном месте все компоненты
 * системы: ключи, блокчейн, сетевые слои, файловые сервисы, маршрутизатор
 * сообщений — и предоставляет высокоуровневый API для CLI и тестов.
 * <p>
 * Жизненный цикл:
 * <ol>
 *   <li>{@link Builder} собирает все нужные компоненты и зависимости;</li>
 *   <li>{@link #start()} запускает сервер, клиент, peer manager и (если есть seeds)
 *       выполняет bootstrap-дискавери;</li>
 *   <li>пользователь вызывает {@link #uploadFile}/{@link #downloadFile}/
 *       {@link #listMyFiles};</li>
 *   <li>{@link #close()} останавливает всё в обратном порядке создания.</li>
 * </ol>
 *
 * <h3>Persistence блокчейна</h3>
 * Если в {@link NodeConfig#getBlockchainDir()} указана директория, при старте
 * блокчейн загружается из RocksDB. После каждого upload-а добавленный блок
 * сохраняется на диск. Если директория не задана — работа идёт чисто в памяти
 * (полезно для тестов и transient-клиентов).
 *
 * <h3>Роли узла</h3>
 * Один и тот же узел может быть и хранителем (storage), и клиентом (uploader/
 * downloader). Все три роли всегда созданы, и {@link MessageRouter} раздаёт
 * сообщения между ними. Это упрощает конфигурацию: storage-only daemon
 * просто никогда не вызывает {@link #uploadFile}, а transient-клиент после
 * {@link #uploadFile} тут же {@link #close()} себя.
 *
 * <h3>Threading</h3>
 * Запуск/останов однопоточный (synchronized). Методы upload/download
 * блокируют вызывающий поток до завершения соответствующей операции;
 * приём сообщений идёт на потоках Netty.
 */
public final class NodeApplication implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(NodeApplication.class);

    private final NodeConfig config;
    private final KeyPair keys;
    private final String selfNodeId;

    private final Blockchain blockchain;
    private final BlockchainStore blockchainStore; // может быть null (in-memory режим)
    private final ShardStorage shardStorage;

    private final PeerManager peerManager;
    private final BootstrapDiscovery bootstrap;
    private final StorageNodeService storageService;
    private final FileUploader uploader;
    private final FileDownloader downloader;
    private final BlockSyncService blockSync;
    private final RepairService repairService;
    private final ShardGcService gcService;
    private final MessageRouter router;

    private final NodeServer server;
    private final NodeClient client;

    private final boolean repairEnabled;
    private final boolean gcEnabled;

    /**
     * Путь к JSON-файлу с persistent-таблицей пиров (день 10).
     * Если родительская директория существует — файл будет создан/обновлён
     * на close(). Если null — persistence отключён (in-memory режим).
     */
    private final Path peersFile;

    /**
     * Интервал периодического автосохранения {@link #peersFile} (день 11).
     * Если null — автосохранение отключено (только save на close). По
     * умолчанию для daemon-узла = 60с.
     */
    private final java.time.Duration peersAutoSaveInterval;

    private boolean started;
    private boolean closed;

    private NodeApplication(Builder b) throws IOException {
        this.config = Objects.requireNonNull(b.config, "config");
        this.keys = Objects.requireNonNull(b.keys, "keys");
        PublicKey publicKey = keys.getPublic();
        PrivateKey privateKey = keys.getPrivate();
        this.selfNodeId = KeyManager.publicKeyToBase64(publicKey);

        // 1. Хранилище шардов — обязательно (storageDir либо из конфига, либо дефолт).
        Path shardsDir = b.shardsDir != null
                ? b.shardsDir
                : Path.of(config.getShardsDir() != null ? config.getShardsDir() : "data/shards");
        Files.createDirectories(shardsDir);
        this.shardStorage = new ShardStorage(shardsDir);

        // 2. Блокчейн — либо в памяти, либо на диске.
        Path blockchainDir = b.blockchainDir != null
                ? b.blockchainDir
                : (config.getBlockchainDir() != null ? Path.of(config.getBlockchainDir()) : null);
        if (blockchainDir != null) {
            Files.createDirectories(blockchainDir);
            this.blockchainStore = BlockchainStore.open(blockchainDir);
            List<Block> existing = blockchainStore.loadAll();
            if (existing.isEmpty()) {
                this.blockchain = new Blockchain();
                blockchainStore.saveBlock(blockchain.getLatestBlock()); // genesis
            } else {
                this.blockchain = new Blockchain(existing);
            }
            LOG.info("Блокчейн загружен из {} (высота {})", blockchainDir, blockchain.height());
        } else {
            this.blockchainStore = null;
            this.blockchain = new Blockchain();
            LOG.info("Блокчейн запущен в режиме in-memory");
        }

        // 3. Сетевые компоненты.
        this.peerManager = new PeerManager(config, selfNodeId, blockchain::height);

        this.storageService = new StorageNodeService(selfNodeId, privateKey, shardStorage);
        this.uploader = new FileUploader(publicKey, privateKey, blockchain,
                FileChunker.DEFAULT_CHUNK_SIZE,
                b.replicationFactor > 0 ? b.replicationFactor : FileUploader.DEFAULT_REPLICATION_FACTOR);
        this.downloader = new FileDownloader(privateKey, selfNodeId, blockchain);

        // День 8: блок-синхронизация. Persistence-хук на BlockchainStore,
        // если он есть — это закрывает требование «блок, полученный от
        // другого узла, должен переживать рестарт» (ТЗ п. 4.2.1 + 4.1.1.3.4).
        Consumer<Block> persistHook = blockchainStore == null
                ? null
                : block -> {
                    try {
                        blockchainStore.saveBlock(block);
                    } catch (Exception e) {
                        LOG.warn("Не удалось persist'нуть блок index={}: {}",
                                block.getIndex(), e.toString());
                    }
                };
        this.blockSync = new BlockSyncService(blockchain,
                () -> peerManager.snapshotSessions(),
                persistHook);

        // День 10: автоматическая re-репликация (ТЗ п. 4.2.2).
        // checkInterval — берём из конфига если задан, иначе 30с.
        java.time.Duration repairInterval = b.repairInterval != null
                ? b.repairInterval : java.time.Duration.ofSeconds(30);
        int rfForRepair = b.replicationFactor > 0
                ? b.replicationFactor : FileUploader.DEFAULT_REPLICATION_FACTOR;
        this.repairService = new RepairService(
                selfNodeId, blockchain, shardStorage, uploader, downloader,
                () -> peerManager.snapshotSessions(),
                rfForRepair, repairInterval,
                persistHook,
                blockSync::broadcastNewBlock);

        // День 11: физический GC шардов (ТЗ п. 4.1.1.4.3).
        // По умолчанию interval = 60с — в production достаточно. Тесты
        // подкручивают через builder.gcInterval() до 100мс.
        java.time.Duration gcInterval = b.gcInterval != null
                ? b.gcInterval : java.time.Duration.ofSeconds(60);
        this.gcService = new ShardGcService(blockchain, shardStorage, gcInterval);

        this.router = new MessageRouter(peerManager, storageService, uploader, downloader, blockSync);

        // PeerManager шлёт уведомления о handshake'ах в blockSync — он
        // решает, нужен ли pull-sync.
        peerManager.setHandshakeListener(blockSync::onHandshake);

        // NodeClient/NodeServer создаём с router в качестве handler — оба используют
        // одну и ту же pipeline-конфигурацию.
        this.client = new NodeClient(router);
        this.server = new NodeServer(b.bindHost != null ? b.bindHost : "0.0.0.0",
                config.getListenPort(), router);
        this.bootstrap = new BootstrapDiscovery(config, client, peerManager);

        this.repairEnabled = b.repairEnabled;
        this.gcEnabled = b.gcEnabled;

        // День 10: persistent-таблица пиров.
        // По умолчанию — рядом с shardsDir в файле peers.json. Это удобно,
        // потому что shardsDir есть всегда (в отличие от blockchainDir).
        // Через builder можно явно отключить (для тестов) или задать другой путь.
        if (b.peersFileExplicit != null) {
            this.peersFile = b.peersFileExplicit;
        } else if (b.peersFileDisabled) {
            this.peersFile = null;
        } else {
            Path parent = shardsDir.getParent();
            this.peersFile = (parent != null ? parent : shardsDir).resolve("peers.json");
        }

        // День 11: интервал автосохранения peers.json. По умолчанию 60с
        // у daemon-узла. Если файл отключён вообще, интервал ни на что не
        // влияет — start() проверит peersFile != null.
        this.peersAutoSaveInterval = b.peersAutoSaveDisabled
                ? null
                : (b.peersAutoSaveInterval != null
                        ? b.peersAutoSaveInterval
                        : java.time.Duration.ofSeconds(60));
    }

    /** Запускает сервер, peer manager, выполняет bootstrap. */
    public synchronized void start() throws InterruptedException {
        if (closed) throw new IllegalStateException("NodeApplication уже закрыт");
        if (started) throw new IllegalStateException("NodeApplication уже запущен");
        started = true;

        // 1. Сервер — узнаём реальный порт, если был 0.
        server.start();
        int realPort = server.boundPort();
        peerManager.setListenPort(realPort);

        // 2. Peer manager + outgoing client.
        peerManager.setOutgoingClient(client);

        // День 11: периодический save peers.json защищает от SIGKILL.
        // Должен быть настроен ДО peerManager.start(), потому что start()
        // регистрирует все scheduled-задачи разом.
        if (peersFile != null && peersAutoSaveInterval != null) {
            try {
                peerManager.enablePeriodicPeersSave(peersFile, peersAutoSaveInterval);
            } catch (Exception e) {
                LOG.warn("Не удалось включить periodic peers save: {}", e.toString());
            }
        }

        peerManager.start();

        // День 10: загружаем persistent-таблицу пиров. Это даёт «память»
        // между запусками — узел не будет каждый раз с нуля бутстрапиться.
        // Загруженные пиры будут проверены ping'ом фоновым таймером
        // (мёртвые выкинутся через peerTimeout).
        if (peersFile != null) {
            try {
                peerManager.loadPeers(peersFile);
            } catch (Exception e) {
                LOG.warn("Не удалось загрузить peers из {}: {}", peersFile, e.toString());
            }
        }

        LOG.info("Узел {} стартовал на порту {} (selfNodeId={})",
                shortNode(selfNodeId), realPort, shortNode(selfNodeId));

        // 3. Bootstrap-дискавери — асинхронен в том смысле, что мы шлём
        //    инициирующие handshake и не ждём ответов; реакция придёт через
        //    PeerManager позднее.
        if (!config.getSeedNodes().isEmpty()) {
            bootstrap.bootstrap(blockchain.height());
        }

        // 4. Auto re-replication (день 10). По умолчанию запускаем —
        //    daemon-узлы должны обслуживать свои файлы автоматически.
        //    Можно отключить через builder().disableRepair() (для тестов).
        if (repairEnabled) {
            repairService.start();
        }

        // 5. Физический GC шардов (день 11, ТЗ п. 4.1.1.4.3). Тоже включён
        //    по умолчанию для daemon-узла. Можно выключить через
        //    builder().disableGc() (некоторые тесты не должны видеть
        //    непредсказуемого удаления шардов).
        if (gcEnabled) {
            gcService.start();
        }
    }

    /** Текущий собственный nodeId (Base64 публичного ключа). */
    public String selfNodeId() {
        return selfNodeId;
    }

    /** Реальный порт, на котором слушает узел (после {@link #start()}). */
    public int listenPort() {
        if (!started) throw new IllegalStateException("Узел не запущен");
        return server.boundPort();
    }

    /** Доступ к блокчейну — для CLI {@code list} и тестов. */
    public Blockchain blockchain() {
        return blockchain;
    }

    /** Доступ к peer manager — для тестов и диагностики (вывод количества пиров). */
    public PeerManager peerManager() {
        return peerManager;
    }

    /** Доступ к сервису синхронизации блоков — для тестов и диагностики. */
    public BlockSyncService blockSync() {
        return blockSync;
    }

    /** Доступ к сервису авто-репликации — для тестов и диагностики. */
    public RepairService repairService() {
        return repairService;
    }

    /** Доступ к сервису сборки мусора шардов — для тестов и диагностики. */
    public ShardGcService gcService() {
        return gcService;
    }

    /** Снимок известных пиров (только с настоящими nodeId). */
    public List<PeerInfo> knownPeers() {
        return peerManager.snapshot();
    }

    /**
     * Загружает файл в сеть.
     * <p>
     * Хранителями выбираются первые {@code K = replicationFactor} пиров
     * с активной сессией (см. {@link FileUploader} — стратегия "первые K").
     * Если живых пиров меньше {@code K}, операция падает с
     * {@link IllegalStateException}.
     * </p>
     *
     * @return подписанная транзакция (уже в локальном блокчейне)
     */
    public Transaction uploadFile(Path file)
            throws IOException, InterruptedException, TimeoutException {
        if (!started) throw new IllegalStateException("Узел не запущен");

        Map<String, PeerSession> sessions = peerManager.snapshotSessions();
        List<FileUploader.StorerHandle> storers = new ArrayList<>();
        for (Map.Entry<String, PeerSession> e : sessions.entrySet()) {
            if (e.getValue().isActive()) {
                storers.add(new FileUploader.StorerHandle(e.getKey(), e.getValue()));
            }
        }

        Transaction tx = uploader.uploadFile(file, storers);

        // Persist новый блок, если есть persistence.
        Block newBlock = blockchain.getLatestBlock();
        if (blockchainStore != null) {
            blockchainStore.saveBlock(newBlock);
        }

        // День 8: рассылаем новый блок всем активным пирам, чтобы транзакция
        // распространилась по сети. Это закрывает ТЗ п. 4.1.1.3.4 (longest
        // chain rule) — после broadcast'а у всех соседей цепи равной длины.
        blockSync.broadcastNewBlock(newBlock);
        return tx;
    }

    /**
     * Скачивает файл по идентификатору транзакции.
     * <p>
     * Транзакция должна быть в локальном блокчейне (после upload или
     * после получения broadcast-блока, который пока не реализован — на
     * этом этапе предполагается, что один и тот же узел и грузил, и качает).
     * </p>
     */
    public void downloadFile(String txId, Path output)
            throws IOException, InterruptedException, TimeoutException {
        if (!started) throw new IllegalStateException("Узел не запущен");

        Optional<Transaction> txOpt = blockchain.findByTxId(txId);
        if (txOpt.isEmpty()) {
            throw new IllegalArgumentException("Транзакция " + txId + " не найдена в локальном блокчейне");
        }
        Transaction tx = txOpt.get();
        Map<String, PeerSession> sessions = peerManager.snapshotSessions();

        // Если у нас есть локальная копия шардов (мы сами были одним из storers),
        // FileDownloader всё равно сходит в сеть — на этом MVP не оптимизируем,
        // зато проще и единообразнее.
        downloader.downloadFile(tx, sessions, output);
    }

    /** Список транзакций текущего пользователя из локального блокчейна. */
    public List<Transaction> listMyFiles() {
        return blockchain.listByOwner(selfNodeId);
    }

    // ================================================================
    // День 9: ACL и DELETE — операции в блокчейне без сетевого I/O
    // ================================================================

    /**
     * Расшаривает файл с другим пользователем.
     * <p>
     * После добавления ACL-транзакции в локальный блокчейн broadcast'им
     * новый блок всем активным пирам — чтобы recipient (если он сейчас
     * онлайн) увидел свой ACL и мог скачать файл.
     *
     * @param originalTxId          id оригинальной UPLOAD-транзакции
     * @param recipientPublicKeyB64 публичный ключ recipient'а (Base64 X.509)
     * @return ACL-транзакция (уже в локальном блокчейне)
     * @see ru.hse.jblockstorage.blockchain.Blockchain#findAclFor
     */
    public Transaction shareFile(String originalTxId, String recipientPublicKeyB64) {
        if (!started) throw new IllegalStateException("Узел не запущен");

        Transaction acl = uploader.shareFile(originalTxId, recipientPublicKeyB64);
        Block newBlock = blockchain.getLatestBlock();
        if (blockchainStore != null) {
            blockchainStore.saveBlock(newBlock);
        }
        blockSync.broadcastNewBlock(newBlock);
        return acl;
    }

    /**
     * Логически удаляет файл (ТЗ п. 4.1.1.4.3).
     * <p>
     * Физически шарды на узлах-хранителях не стираются — это была бы
     * отдельная задача Garbage Collection. После DELETE файл исчезает
     * из {@link #listMyFiles}, и попытка {@link #downloadFile} вернёт
     * ошибку «файл удалён».
     *
     * @return DELETE-транзакция (уже в локальном блокчейне)
     */
    public Transaction deleteFile(String originalTxId) {
        if (!started) throw new IllegalStateException("Узел не запущен");

        Transaction del = uploader.deleteFile(originalTxId);
        Block newBlock = blockchain.getLatestBlock();
        if (blockchainStore != null) {
            blockchainStore.saveBlock(newBlock);
        }
        blockSync.broadcastNewBlock(newBlock);
        return del;
    }

    /**
     * Список файлов, которые мне расшарили другие пользователи (ACL).
     * Это «вторая половина» {@link #listMyFiles} — позволяет в GUI
     * показать раздел «Расшаренное со мной».
     */
    public List<Transaction> listAccessibleFiles() {
        return blockchain.listByRecipient(selfNodeId);
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;

        // День 10: сохраняем таблицу пиров, ПОКА peerManager ещё имеет состояние.
        // Это первое, что делаем при close — чтобы данные не потерять, даже если
        // что-то ниже бросит исключение.
        if (peersFile != null) {
            try {
                Path parent = peersFile.toAbsolutePath().getParent();
                if (parent != null) Files.createDirectories(parent);
                peerManager.savePeers(peersFile);
            } catch (Exception e) {
                LOG.warn("Не удалось сохранить peers в {}: {}", peersFile, e.toString());
            }
        }

        // Останавливаем в обратном порядке создания.
        try { gcService.close();        } catch (Exception ignored) {}
        try { repairService.close();   } catch (Exception ignored) {}
        try { peerManager.close();      } catch (Exception ignored) {}
        try { client.close();           } catch (Exception ignored) {}
        try { server.close();           } catch (Exception ignored) {}
        if (blockchainStore != null) {
            try { blockchainStore.close(); } catch (Exception ignored) {}
        }
        LOG.info("Узел {} остановлен", shortNode(selfNodeId));
    }

    private static String shortNode(String id) {
        if (id == null) return "?";
        return id.length() > 8 ? id.substring(0, 8) + "…" : id;
    }

    // ================================================================
    // Builder
    // ================================================================

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private NodeConfig config;
        private KeyPair keys;
        private Path shardsDir;
        private Path blockchainDir;
        private String bindHost;
        private int replicationFactor = -1;
        private boolean repairEnabled = true;
        private java.time.Duration repairInterval;
        private boolean gcEnabled = true;
        private java.time.Duration gcInterval;
        private Path peersFileExplicit;
        private boolean peersFileDisabled = false;
        private java.time.Duration peersAutoSaveInterval;
        private boolean peersAutoSaveDisabled = false;

        public Builder config(NodeConfig v) { this.config = v; return this; }
        public Builder keys(KeyPair v) { this.keys = v; return this; }

        /** Если задан — переопределяет {@code NodeConfig#getShardsDir()}. */
        public Builder shardsDir(Path v) { this.shardsDir = v; return this; }
        /** Если задан — переопределяет {@code NodeConfig#getBlockchainDir()}. */
        public Builder blockchainDir(Path v) { this.blockchainDir = v; return this; }
        /** Хост для bind. По умолчанию 0.0.0.0; в тестах удобно 127.0.0.1. */
        public Builder bindHost(String v) { this.bindHost = v; return this; }
        /** Если &gt; 0 — переопределяет {@code FileUploader.DEFAULT_REPLICATION_FACTOR}. */
        public Builder replicationFactor(int v) { this.replicationFactor = v; return this; }

        /**
         * Включить/выключить автоматическую re-репликацию (день 10).
         * По умолчанию включена. Тесты, которым важна стабильность счёта
         * блоков, могут выключить через {@code disableRepair()}.
         */
        public Builder disableRepair() { this.repairEnabled = false; return this; }

        /**
         * Период проверки re-репликации. По умолчанию 30с — для daemon-узла.
         * В интеграционных тестах ставится 1-2с, чтобы repair успевал отработать
         * за разумное время.
         */
        public Builder repairInterval(java.time.Duration v) { this.repairInterval = v; return this; }

        /**
         * Явный путь к файлу persistent-таблицы пиров (день 10).
         * По умолчанию — {@code <parent of shardsDir>/peers.json}.
         */
        public Builder peersFile(Path v) { this.peersFileExplicit = v; return this; }

        /**
         * Полностью отключить persistence пиров — таблица не сохраняется
         * и не загружается. Удобно для тестов с {@code @TempDir} или
         * случаев, когда нужна гарантированно «чистая» сеть.
         */
        public Builder disablePeersPersistence() { this.peersFileDisabled = true; return this; }

        /**
         * Включить/выключить физический GC шардов (день 11, ТЗ п. 4.1.1.4.3).
         * По умолчанию включён. Тесты, которым важно сохранение шардов
         * (например, repair-тесты с DELETE) могут выключить через
         * {@code disableGc()}.
         */
        public Builder disableGc() { this.gcEnabled = false; return this; }

        /**
         * Период проверки GC. По умолчанию 60с — для daemon-узла.
         * В тестах ставится 100мс–1с, чтобы GC успевал отработать
         * за разумное время.
         */
        public Builder gcInterval(java.time.Duration v) { this.gcInterval = v; return this; }

        /**
         * Интервал периодического автосохранения {@code peers.json} (день 11).
         * По умолчанию 60с. Защищает от потери таблицы при SIGKILL.
         */
        public Builder peersAutoSaveInterval(java.time.Duration v) {
            this.peersAutoSaveInterval = v; return this;
        }

        /**
         * Отключить периодическое автосохранение {@code peers.json}. Save
         * на close() остаётся (пока не отключён через
         * {@link #disablePeersPersistence()}).
         */
        public Builder disablePeersAutoSave() {
            this.peersAutoSaveDisabled = true; return this;
        }

        public NodeApplication build() throws IOException {
            return new NodeApplication(this);
        }
    }
}
