package ru.hse.jblockstorage.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Конфигурация узла — параметры из {@code config.yaml} плюс дефолты.
 * <p>
 * Согласно ТЗ (п. 4.1.1.1.1) узел при первом запуске берёт список seed nodes
 * из конфигурационного файла. Сюда же положены параметры таймингов
 * сетевого слоя — чтобы тесты могли подсунуть короткие интервалы и
 * не ждать минутами реальных production-значений.
 * </p>
 *
 * <h3>Расширения дня 7 (CLI)</h3>
 * Добавлены параметры файлового хранилища и идентификации:
 * <ul>
 *   <li>{@link #getKeysFile()} — путь к зашифрованному файлу приватного ключа
 *       (создаётся командой {@code generate-keys});</li>
 *   <li>{@link #getKeysPassword()} — пароль к этому файлу. Может быть {@code null} —
 *       тогда CLI попросит ввести в stdin. В тестах удобно положить прямо в конфиг;</li>
 *   <li>{@link #getShardsDir()} — директория для локальных шардов
 *       (используется {@code ShardStorage});</li>
 *   <li>{@link #getBlockchainDir()} — директория для RocksDB блокчейна.</li>
 * </ul>
 * Все они опциональны (могут быть {@code null}); CLI при необходимости
 * проверяет, заполнены ли нужные ему параметры.
 *
 * <h3>Дефолты сетевых параметров</h3>
 * <ul>
 *   <li>{@code listenPort = 8080};</li>
 *   <li>{@code pingInterval = 10с}, {@code peerTimeout = 30с} — Keep-Alive (ТЗ п. 4.1.1.1.3);</li>
 *   <li>{@code gossipInterval = 15с} — обмен таблицей пиров;</li>
 *   <li>{@code maxPeers = 16}.</li>
 * </ul>
 *
 * <h3>Иммутабельность</h3>
 * Все поля {@code final}, поля коллекций оборачиваются в unmodifiable копии.
 * Создаём через {@link Builder} — это чище, чем длинный конструктор
 * с десятком параметров, и удобно для тестов (меняем только то, что нужно).
 */
public final class NodeConfig {

    private final int listenPort;
    private final List<SeedNode> seedNodes;
    private final Duration pingInterval;
    private final Duration peerTimeout;
    private final Duration gossipInterval;
    private final int maxPeers;
    private final int protocolVersion;

    // Поля дня 7 (CLI), все опциональны:
    private final String keysFile;
    private final String keysPassword;
    private final String shardsDir;
    private final String blockchainDir;

    private NodeConfig(Builder b) {
        if (b.listenPort < 0 || b.listenPort > 65535) {
            throw new IllegalArgumentException("listenPort вне диапазона: " + b.listenPort);
        }
        if (b.pingInterval == null || b.pingInterval.isNegative() || b.pingInterval.isZero()) {
            throw new IllegalArgumentException("pingInterval должен быть > 0");
        }
        if (b.peerTimeout == null || b.peerTimeout.isNegative() || b.peerTimeout.isZero()) {
            throw new IllegalArgumentException("peerTimeout должен быть > 0");
        }
        if (b.gossipInterval == null || b.gossipInterval.isNegative() || b.gossipInterval.isZero()) {
            throw new IllegalArgumentException("gossipInterval должен быть > 0");
        }
        if (b.peerTimeout.compareTo(b.pingInterval) <= 0) {
            // Если peerTimeout <= pingInterval, мы выкинем пира раньше,
            // чем у него будет шанс ответить на следующий ping.
            throw new IllegalArgumentException(
                    "peerTimeout (" + b.peerTimeout + ") должен быть больше pingInterval ("
                            + b.pingInterval + ")");
        }
        if (b.maxPeers <= 0) {
            throw new IllegalArgumentException("maxPeers должен быть > 0");
        }

        this.listenPort = b.listenPort;
        this.seedNodes = List.copyOf(b.seedNodes);
        this.pingInterval = b.pingInterval;
        this.peerTimeout = b.peerTimeout;
        this.gossipInterval = b.gossipInterval;
        this.maxPeers = b.maxPeers;
        this.protocolVersion = b.protocolVersion;

        this.keysFile = b.keysFile;
        this.keysPassword = b.keysPassword;
        this.shardsDir = b.shardsDir;
        this.blockchainDir = b.blockchainDir;
    }

    public int getListenPort() { return listenPort; }
    public List<SeedNode> getSeedNodes() { return seedNodes; }
    public Duration getPingInterval() { return pingInterval; }
    public Duration getPeerTimeout() { return peerTimeout; }
    public Duration getGossipInterval() { return gossipInterval; }
    public int getMaxPeers() { return maxPeers; }
    public int getProtocolVersion() { return protocolVersion; }

    public String getKeysFile() { return keysFile; }
    public String getKeysPassword() { return keysPassword; }
    public String getShardsDir() { return shardsDir; }
    public String getBlockchainDir() { return blockchainDir; }

    /** Дефолты для production: ping каждые 10с, dead через 30с, gossip каждые 15с. */
    public static NodeConfig defaults() {
        return new Builder().build();
    }

    /** Агрессивные тайминги для тестов — всё в районе ~1 секунды. */
    public static NodeConfig testDefaults() {
        return new Builder()
                .pingInterval(Duration.ofMillis(500))
                .peerTimeout(Duration.ofSeconds(2))
                .gossipInterval(Duration.ofSeconds(1))
                .build();
    }

    public Builder toBuilder() {
        return new Builder()
                .listenPort(listenPort)
                .seedNodes(seedNodes)
                .pingInterval(pingInterval)
                .peerTimeout(peerTimeout)
                .gossipInterval(gossipInterval)
                .maxPeers(maxPeers)
                .protocolVersion(protocolVersion)
                .keysFile(keysFile)
                .keysPassword(keysPassword)
                .shardsDir(shardsDir)
                .blockchainDir(blockchainDir);
    }

    public static final class Builder {
        private int listenPort = 8080;
        private List<SeedNode> seedNodes = Collections.emptyList();
        private Duration pingInterval = Duration.ofSeconds(10);
        private Duration peerTimeout = Duration.ofSeconds(30);
        private Duration gossipInterval = Duration.ofSeconds(15);
        private int maxPeers = 16;
        private int protocolVersion = 1;

        private String keysFile;
        private String keysPassword;
        private String shardsDir;
        private String blockchainDir;

        public Builder listenPort(int v) { this.listenPort = v; return this; }
        public Builder seedNodes(List<SeedNode> v) {
            this.seedNodes = new ArrayList<>(Objects.requireNonNull(v, "seedNodes"));
            return this;
        }
        public Builder pingInterval(Duration v) { this.pingInterval = v; return this; }
        public Builder peerTimeout(Duration v) { this.peerTimeout = v; return this; }
        public Builder gossipInterval(Duration v) { this.gossipInterval = v; return this; }
        public Builder maxPeers(int v) { this.maxPeers = v; return this; }
        public Builder protocolVersion(int v) { this.protocolVersion = v; return this; }

        public Builder keysFile(String v) { this.keysFile = v; return this; }
        public Builder keysPassword(String v) { this.keysPassword = v; return this; }
        public Builder shardsDir(String v) { this.shardsDir = v; return this; }
        public Builder blockchainDir(String v) { this.blockchainDir = v; return this; }

        public NodeConfig build() { return new NodeConfig(this); }
    }
}
