package ru.hse.jblockstorage.app;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import ru.hse.jblockstorage.blockchain.Transaction;
import ru.hse.jblockstorage.config.NodeConfig;
import ru.hse.jblockstorage.config.SeedNode;
import ru.hse.jblockstorage.crypto.KeyManager;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Тесты дня 8: broadcast нового блока + pull-sync при подключении нового узла.
 * <p>
 * Закрывают:
 * <ul>
 *   <li>ТЗ п. 4.1.1.3.4 — Longest Chain Rule (новый узел догоняет существующую сеть);</li>
 *   <li>ТЗ п. 4.1.1.3.3 — Валидация блоков на стороне получателя;</li>
 *   <li>ТЗ п. 8.1.2 — Интеграционное испытание синхронизации БД блокчейна
 *       при подключении нового узла к существующей сети.</li>
 * </ul>
 *
 * <h3>Сценарии</h3>
 * <ol>
 *   <li>{@link #broadcastBlockReachesAllPeers} — три узла подключены, узел A
 *       загружает файл (в его блокчейне появляется новый блок), B и C получают
 *       тот же блок через {@code BroadcastBlockMessage}.</li>
 *   <li>{@link #newPeerCatchesUpOnHandshake} — A создаёт 2 блока в одиночку,
 *       потом подключается B; B догоняет высоту A через GetBlock/BlockResponse.</li>
 * </ol>
 */
class BlockSyncIntegrationTest {

    private final List<NodeApplication> apps = new ArrayList<>();
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
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void broadcastBlockReachesAllPeers(@TempDir Path tmp) throws Exception {
        // Поднимаем 3 узла, A — seed.
        NodeApplication a = startNode("A", tmp.resolve("a"), 0, List.of(), 2);
        NodeApplication b = startNode("B", tmp.resolve("b"), 0,
                List.of(new SeedNode("127.0.0.1", a.listenPort())), 2);
        NodeApplication c = startNode("C", tmp.resolve("c"), 0,
                List.of(new SeedNode("127.0.0.1", a.listenPort())), 2);

        // Ждём, пока все увидят друг друга.
        waitUntil(
                () -> a.peerManager().size() == 2
                        && b.peerManager().size() == 2
                        && c.peerManager().size() == 2,
                30_000,
                () -> "узлы не обнаружили друг друга:" + dumpAll(a, b, c));

        // Все три должны быть на одной (genesis) высоте.
        assertEquals(1, a.blockchain().height());
        assertEquals(1, b.blockchain().height());
        assertEquals(1, c.blockchain().height());

        // ---- A загружает файл — формируется новый блок и broadcast'ится ----
        Path inputFile = tmp.resolve("input.bin");
        byte[] originalContent = generatePayload(2_000);
        Files.write(inputFile, originalContent);
        Transaction tx = a.uploadFile(inputFile);

        // У A блок уже добавлен (это делает FileUploader локально).
        assertEquals(2, a.blockchain().height(),
                "У A после upload высота должна быть 2");

        // B и C получают broadcast-сообщение асинхронно. Ждём, пока их
        // высота не станет 2 — это значит, что они применили блок.
        waitUntil(
                () -> b.blockchain().height() == 2 && c.blockchain().height() == 2,
                15_000,
                () -> "B и C не получили блок: heights = "
                        + a.blockchain().height() + "/"
                        + b.blockchain().height() + "/"
                        + c.blockchain().height());

        // Транзакция должна быть видна на всех трёх узлах.
        assertTrue(b.blockchain().findByTxId(tx.getId()).isPresent(),
                "B должен видеть транзакцию " + tx.getId());
        assertTrue(c.blockchain().findByTxId(tx.getId()).isPresent(),
                "C должен видеть транзакцию " + tx.getId());

        // Хеши последних блоков должны совпадать — это значит, что у нас
        // действительно один и тот же блок, а не «случайно» одинаковая высота.
        assertEquals(a.blockchain().getLatestBlock().getHash(),
                b.blockchain().getLatestBlock().getHash(),
                "Хеш последнего блока у A и B должны совпадать");
        assertEquals(a.blockchain().getLatestBlock().getHash(),
                c.blockchain().getLatestBlock().getHash(),
                "Хеш последнего блока у A и C должны совпадать");
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void newPeerCatchesUpOnHandshake(@TempDir Path tmp) throws Exception {
        // Поднимаем A и B, делаем upload (A → B и обратно — оба знают друг друга).
        NodeApplication a = startNode("A", tmp.resolve("a"), 0, List.of(), 1);
        NodeApplication b = startNode("B", tmp.resolve("b"), 0,
                List.of(new SeedNode("127.0.0.1", a.listenPort())), 1);

        waitUntil(() -> a.peerManager().size() == 1 && b.peerManager().size() == 1,
                15_000,
                () -> "A и B не нашли друг друга: " + dumpAll(a, b));

        // A заливает 2 файла — у него высота станет 3 (genesis + 2).
        Path file1 = tmp.resolve("file1.bin");
        Files.write(file1, generatePayload(500));
        a.uploadFile(file1);
        // Перед вторым upload-ом — даём первому broadcast'у дойти, иначе
        // у B может не быть первого блока, и второй приедет с index=2,
        // appendBlock его отклонит, и тест станет flaky.
        waitUntil(() -> b.blockchain().height() == 2, 10_000,
                () -> "B не получил первый блок: " + dumpAll(a, b));

        Path file2 = tmp.resolve("file2.bin");
        Files.write(file2, generatePayload(500));
        a.uploadFile(file2);
        waitUntil(() -> b.blockchain().height() == 3, 10_000,
                () -> "B не получил второй блок: " + dumpAll(a, b));

        assertEquals(3, a.blockchain().height());
        assertEquals(3, b.blockchain().height());

        // Теперь поднимаем третий узел C — он догонит A и B через sync.
        NodeApplication c = startNode("C", tmp.resolve("c"), 0,
                List.of(new SeedNode("127.0.0.1", a.listenPort())), 1);

        // C должен получить все 2 «настоящих» блока через GetBlock/BlockResponse
        // в ответ на handshake. Это покрывает ТЗ п. 8.1.2 «Тестирование процесса
        // синхронизации базы данных блокчейна (RocksDB) при подключении нового
        // узла к существующей сети».
        waitUntil(() -> c.blockchain().height() == 3, 20_000,
                () -> "C не догнал высоту A: " + dumpAll(a, b, c));

        assertEquals(a.blockchain().getLatestBlock().getHash(),
                c.blockchain().getLatestBlock().getHash(),
                "У C хеш последнего блока должен совпадать с A");
    }

    // ----------------------------------------------------------------
    // Хелперы — копия из End2EndScenarioTest, синхронизирована по
    // таймингам (ping 500мс, peerTimeout 6с, gossip 700мс).
    // ----------------------------------------------------------------

    private NodeApplication startNode(String label, Path baseDir, int port,
                                      List<SeedNode> seeds, int replicationFactor)
            throws Exception {
        Files.createDirectories(baseDir);
        KeyPair keys = KeyManager.generateRsaKeyPair();

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
                .disableRepair()
                .disablePeersPersistence()
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
            sb.append("  ").append(label)
                    .append(" height=").append(n.blockchain().height())
                    .append(" peers=").append(n.peerManager().size())
                    .append(" sessions=").append(n.peerManager().snapshotSessions().size())
                    .append("\n");
        }
        return sb.toString();
    }
}
