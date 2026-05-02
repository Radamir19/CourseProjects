package ru.hse.jblockstorage.app;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import ru.hse.jblockstorage.blockchain.Transaction;
import ru.hse.jblockstorage.config.NodeConfig;
import ru.hse.jblockstorage.config.SeedNode;
import ru.hse.jblockstorage.crypto.CryptoUtils;
import ru.hse.jblockstorage.crypto.KeyManager;
import ru.hse.jblockstorage.network.PeerInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Финальный сценарий приёмки из ТЗ п. 8.2.1.
 * <p>
 * В отличие от теста дня 6 ({@code FileUploadDownloadIntegrationTest}), который
 * настраивал соединения вручную через {@code NodeClient.connect()}, этот тест
 * поднимает три полноценных узла через {@link NodeApplication} — со всеми
 * компонентами реальной системы: сервером Netty, peer manager + bootstrap
 * discovery, storage service, file uploader/downloader, message router.
 *
 * <h3>Тайминги</h3>
 * Используем НЕ {@link NodeConfig#testDefaults()} (там peerTimeout=2с — на
 * холодном старте JVM первый pingTick может опоздать, и пиры выкидываются
 * раньше времени), а собственный набор: ping 500мс, peerTimeout 6с,
 * gossip 700мс.
 */
class End2EndScenarioTest {

    private final List<NodeApplication> apps = new ArrayList<>();

    /** Лейблы узлов для красивых диагностических сообщений ("A", "B", "C"). */
    private final Map<String, String> labels = new LinkedHashMap<>();

    @AfterEach
    void cleanup() {
        for (int i = apps.size() - 1; i >= 0; i--) {
            try { apps.get(i).close(); } catch (Exception ignored) {}
        }
        apps.clear();
        labels.clear();
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void fullAcceptanceScenarioFromSpec(@TempDir Path tmp) throws Exception {
        // ---- 1. Запуск сети ----
        NodeApplication a = startNode("A", tmp.resolve("a"), 0, List.of(), 2);
        NodeApplication b = startNode("B", tmp.resolve("b"), 0,
                List.of(new SeedNode("127.0.0.1", a.listenPort())), 1);
        NodeApplication c = startNode("C", tmp.resolve("c"), 0,
                List.of(new SeedNode("127.0.0.1", a.listenPort())), 1);

        // ---- 2. Подключение ----
        // Через несколько gossip-циклов все три должны увидеть друг друга.
        waitUntil(
                () -> a.peerManager().size() == 2
                        && b.peerManager().size() == 2
                        && c.peerManager().size() == 2,
                30_000,
                () -> "узлы не обнаружили друг друга:" + dumpAll(a, b, c));
        System.err.println("[diag] peers ok: " + dumpAll(a, b, c));

        // А ещё нам нужны открытые сессии (snapshotSessions с реальными nodeId).
        waitUntil(
                () -> a.peerManager().snapshotSessions().size() == 2,
                15_000,
                () -> "у A нет открытых сессий к обоим хранителям:" + dumpAll(a, b, c));
        System.err.println("[diag] sessions ok: " + dumpNode("A", a));

        // ---- 3. Загрузка ----
        Path inputFile = tmp.resolve("important.bin");
        byte[] originalContent = generatePayload(50 * 1024); // 50 КБ — один шард
        Files.write(inputFile, originalContent);

        Transaction tx = a.uploadFile(inputFile);
        assertNotNull(tx, "uploadFile должен вернуть транзакцию");
        assertTrue(tx.verify(), "Транзакция должна проходить verify()");
        assertTrue(tx.verifyReplicas(), "Все receipts должны быть валидны");
        assertEquals(2,
                tx.getReplicas().size() / Math.max(1, tx.getShardHashes().size()),
                "На каждый шард должно быть по 2 реплики (B + C)");

        // ---- 4. Имитация сбоя: B падает ----
        b.close();

        // ---- 5. Скачивание ----
        Path outputFile = tmp.resolve("restored.bin");
        a.downloadFile(tx.getId(), outputFile);

        // ---- 6. Проверка целостности ----
        byte[] restored = Files.readAllBytes(outputFile);
        assertArrayEquals(originalContent, restored,
                "Восстановленный файл должен побитово совпадать с оригиналом");

        // Сверка SHA-256, как требует ТЗ п. 8.2.1.6.
        String origHash = CryptoUtils.toHex(CryptoUtils.applySha256(originalContent));
        String restoredHash = CryptoUtils.toHex(CryptoUtils.applySha256(restored));
        assertEquals(origHash, restoredHash, "SHA-256 хеши должны совпадать");
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void downloadStillWorksWhenStorerLeavesAfterPeerTimeout(@TempDir Path tmp) throws Exception {
        NodeApplication a = startNode("A", tmp.resolve("a"), 0, List.of(), 2);
        NodeApplication b = startNode("B", tmp.resolve("b"), 0,
                List.of(new SeedNode("127.0.0.1", a.listenPort())), 1);
        NodeApplication c = startNode("C", tmp.resolve("c"), 0,
                List.of(new SeedNode("127.0.0.1", a.listenPort())), 1);

        waitUntil(
                () -> a.peerManager().snapshotSessions().size() == 2,
                30_000,
                () -> "A не подключился к обоим хранителям:" + dumpAll(a, b, c));

        Path inputFile = tmp.resolve("data.bin");
        byte[] originalContent = generatePayload(20_000);
        Files.write(inputFile, originalContent);
        Transaction tx = a.uploadFile(inputFile);

        // Закрываем B и ждём peerTimeout — A должен выкинуть его из таблицы.
        b.close();
        waitUntil(
                () -> !a.peerManager().knows(b.selfNodeId()),
                15_000,
                () -> "A не выкинул B по timeout. " + dumpNode("A", a));

        assertEquals(1, a.peerManager().snapshotSessions().size(),
                "После выкидывания B у A должна остаться одна сессия");

        Path outputFile = tmp.resolve("output.bin");
        a.downloadFile(tx.getId(), outputFile);
        assertArrayEquals(originalContent, Files.readAllBytes(outputFile));
    }

    // ----------------------------------------------------------------
    // Хелперы
    // ----------------------------------------------------------------

    private NodeApplication startNode(String label, Path baseDir, int port,
                                      List<SeedNode> seeds, int replicationFactor)
            throws Exception {
        Files.createDirectories(baseDir);
        KeyPair keys = KeyManager.generateRsaKeyPair();

        // Свои тайминги: ping 500мс, peerTimeout 6с, gossip 700мс.
        NodeConfig config = NodeConfig.defaults().toBuilder()
                .listenPort(port)
                .seedNodes(seeds)
                .protocolVersion(1)
                .pingInterval(Duration.ofMillis(500))
                .peerTimeout(Duration.ofSeconds(6))
                .gossipInterval(Duration.ofMillis(700))
                .build();

        NodeApplication app = NodeApplication.builder()
                .config(config)
                .keys(keys)
                .bindHost("127.0.0.1")
                .shardsDir(baseDir.resolve("shards"))
                .replicationFactor(replicationFactor)
                .build();
        app.start();
        apps.add(app);
        labels.put(app.selfNodeId(), label);
        return app;
    }

    private static byte[] generatePayload(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) ((i * 31 + 7) & 0xFF);
        }
        return data;
    }

    /** Активное ожидание условия. Кидает явный fail при истечении таймаута. */
    private static void waitUntil(BooleanSupplier cond,
                                  long timeoutMs,
                                  Supplier<String> diagOnFail) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            Thread.sleep(100);
        }
        if (cond.getAsBoolean()) return;
        fail("Не дождались за " + timeoutMs + "мс. " + diagOnFail.get());
    }

    private String dumpAll(NodeApplication... nodes) {
        StringBuilder sb = new StringBuilder("\n");
        for (NodeApplication n : nodes) {
            String label = labels.getOrDefault(n.selfNodeId(), "?");
            sb.append("  ").append(dumpNode(label, n)).append("\n");
        }
        return sb.toString();
    }

    private String dumpNode(String label, NodeApplication n) {
        StringBuilder sb = new StringBuilder();
        sb.append(label).append("[").append(shortId(n.selfNodeId()))
                .append(" :").append(n.listenPort()).append("]");

        List<PeerInfo> peers = n.peerManager().snapshot();
        sb.append(" peers(").append(peers.size()).append(")=[");
        boolean first = true;
        for (PeerInfo p : peers) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(labels.getOrDefault(p.getNodeId(), "?"))
                    .append(":").append(p.getListenPort());
        }
        sb.append("]");

        Map<String, ?> sess = n.peerManager().snapshotSessions();
        sb.append(" sessions(").append(sess.size()).append(")=[");
        first = true;
        for (String id : sess.keySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(labels.getOrDefault(id, "?"));
        }
        sb.append("]");
        return sb.toString();
    }

    private static String shortId(String nodeId) {
        if (nodeId == null) return "?";
        return nodeId.length() > 6 ? nodeId.substring(0, 6) + "…" : nodeId;
    }
}