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
    private final MessageRouter router;

    private final NodeServer server;
    private final NodeClient client;

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
        this.downloader = new FileDownloader(privateKey);
        this.router = new MessageRouter(peerManager, storageService, uploader, downloader);

        // NodeClient/NodeServer создаём с router в качестве handler — оба используют
        // одну и ту же pipeline-конфигурацию.
        this.client = new NodeClient(router);
        this.server = new NodeServer(b.bindHost != null ? b.bindHost : "0.0.0.0",
                config.getListenPort(), router);
        this.bootstrap = new BootstrapDiscovery(config, client, peerManager);
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
        peerManager.start();

        LOG.info("Узел {} стартовал на порту {} (selfNodeId={})",
                shortNode(selfNodeId), realPort, shortNode(selfNodeId));

        // 3. Bootstrap-дискавери — асинхронен в том смысле, что мы шлём
        //    инициирующие handshake и не ждём ответов; реакция придёт через
        //    PeerManager позднее.
        if (!config.getSeedNodes().isEmpty()) {
            bootstrap.bootstrap(blockchain.height());
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
        if (blockchainStore != null) {
            blockchainStore.saveBlock(blockchain.getLatestBlock());
        }
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

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        // Останавливаем в обратном порядке создания.
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

        public NodeApplication build() throws IOException {
            return new NodeApplication(this);
        }
    }
}
