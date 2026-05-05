package ru.hse.jblockstorage.app;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
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
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Тест дня 11, закрывающий ТЗ п. 8.1.2 по букве:
 * <blockquote>
 * «Тестирование процесса синхронизации базы данных блокчейна (RocksDB)
 * при подключении нового узла к существующей сети.»
 * </blockquote>
 *
 * <p>{@link BlockSyncIntegrationTest} в дне 8 проверял sync на in-memory
 * блокчейне — этого достаточно для логики Longest Chain Rule, но ТЗ говорит
 * именно про <i>RocksDB</i>. Этот тест дополняет картину:
 * <ol>
 *   <li>A и B стартуют с persistent {@code blockchainDir} (RocksDB);</li>
 *   <li>A заливает 2 файла, B получает блоки через broadcast и persist'ит
 *       их в свой RocksDB через {@link BlockSyncService} → persistence-хук;</li>
 *   <li>подключается <b>новый</b> узел C с пустым {@code blockchainDir} —
 *       догоняет высоту через handshake-pull (через {@code GetBlockMessage}
 *       и {@code BlockResponseMessage});</li>
 *   <li>C закрывается, открывается заново с тем же {@code blockchainDir} —
 *       блоки на месте сразу после старта (durable read через RocksDB);</li>
 *   <li>хеш последнего блока совпадает у A и C после рестарта.</li>
 * </ol>
 *
 * <p>Это покрывает не только sync, но и persistence-хук
 * {@code BlockSyncService → BlockchainStore.saveBlock}: полученные через
 * {@code BlockResponseMessage} блоки persist'ятся в RocksDB и переживают
 * рестарт узла.
 */
class BlockchainRocksDbSyncTest {

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
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void newPeerSyncsBlocksIntoRocksDbAndSurvivesRestart(@TempDir Path tmp) throws Exception {
        // ---- Поднимаем A и B (для возможности upload — A нужен storer) ----
        Path baseA = tmp.resolve("a");
        Path baseB = tmp.resolve("b");
        Path baseC = tmp.resolve("c");
        KeyPair keysA = KeyManager.generateRsaKeyPair();
        KeyPair keysB = KeyManager.generateRsaKeyPair();
        KeyPair keysC = KeyManager.generateRsaKeyPair();

        NodeApplication a = startNode("A", baseA, keysA, 0, List.of(), 1);
        NodeApplication b = startNode("B", baseB, keysB, 0,
                List.of(new SeedNode("127.0.0.1", a.listenPort())), 1);

        // Ждём, пока A и B увидят друг друга — иначе uploadFile упадёт
        // с "Недостаточно хранителей".
        waitUntil(() -> a.peerManager().snapshotSessions().size() >= 1
                        && b.peerManager().snapshotSessions().size() >= 1,
                15_000,
                () -> "A и B не нашли друг друга: " + dumpAll(a, b));

        // A заливает 2 файла → высота 3 (genesis + 2 блока). B получает блоки
        // через broadcast и persist'ит в свой RocksDB.
        Path file1 = tmp.resolve("file1.bin");
        Files.write(file1, generatePayload(500));
        a.uploadFile(file1);
        // Ждём пока B применит первый блок — иначе второй приедет с index=2,
        // appendBlock его отклонит (нужен индекс = latest+1).
        waitUntil(() -> b.blockchain().height() == 2, 10_000,
                () -> "B не получил первый блок: " + dumpAll(a, b));

        Path file2 = tmp.resolve("file2.bin");
        Files.write(file2, generatePayload(500));
        a.uploadFile(file2);
        waitUntil(() -> b.blockchain().height() == 3, 10_000,
                () -> "B не получил второй блок: " + dumpAll(a, b));

        assertEquals(3, a.blockchain().height());
        assertEquals(3, b.blockchain().height());
        String aLastHash = a.blockchain().getLatestBlock().getHash();

        // ---- Поднимаем НОВЫЙ узел C: догоняет через handshake-pull ----
        NodeApplication c = startNode("C", baseC, keysC, 0,
                List.of(new SeedNode("127.0.0.1", a.listenPort())), 1);

        // Через handshake-pull C запросит недостающие блоки и применит их.
        // BlockSyncService.persistHook → BlockchainStore.saveBlock — блоки
        // окажутся в RocksDB у C. Это и есть «синхронизация БД блокчейна
        // при подключении нового узла» из ТЗ 8.1.2.
        waitUntil(() -> c.blockchain().height() == 3, 20_000,
                () -> "C не догнал A через RocksDB-sync: " + dumpAll(a, b, c));

        assertEquals(aLastHash, c.blockchain().getLatestBlock().getHash(),
                "После sync хеш последнего блока должен совпадать у A и C");

        // ---- Закрываем C и поднимаем заново. Тот же blockchainDir. ----
        c.close();
        // Удаляем закрытый экземпляр из списка cleanup — иначе попытаемся
        // дважды close() в @AfterEach.
        apps.remove(c);

        NodeApplication cRestart = startNode("C'", baseC, keysC, 0,
                List.of(new SeedNode("127.0.0.1", a.listenPort())), 1);

        // На старте C' читает все блоки из RocksDB — высота должна быть
        // сразу 3, ещё до начала любого sync'а с A. Это и есть проверка
        // «блоки в RocksDB переживают рестарт».
        assertEquals(3, cRestart.blockchain().height(),
                "После рестарта C должен загрузить 3 блока из RocksDB");
        assertEquals(aLastHash, cRestart.blockchain().getLatestBlock().getHash(),
                "Хеш последнего блока после рестарта должен совпадать с A");
    }

    // ----------------------------------------------------------------
    // Хелперы — те же тайминги, что в BlockSyncIntegrationTest.
    // ----------------------------------------------------------------

    private NodeApplication startNode(String label, Path baseDir, KeyPair keys, int port,
                                      List<SeedNode> seeds, int replicationFactor)
            throws Exception {
        Files.createDirectories(baseDir);

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
                .blockchainDir(baseDir.resolve("blockchain"))
                .replicationFactor(replicationFactor)
                .disableRepair()
                .disablePeersPersistence()
                .disableGc()
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