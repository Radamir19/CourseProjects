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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Интеграционные тесты автоматической re-репликации (ТЗ п. 4.2.2).
 * <p>
 * Сценарии:
 * <ol>
 *   <li>{@link #repairTriggersWhenStorerLeaves} — 4 узла, replicationFactor=2.
 *       A заливает → шарды на B и C → B падает → срабатывает repair-tick →
 *       шарды добираются на D → в блокчейне появляется REPAIR-транзакция,
 *       и количество живых хранителей возвращается к 2.</li>
 *   <li>{@link #downloadUsesRepairedReplicaList} — после repair'а downloader
 *       должен использовать новый список реплик: после отключения B файл
 *       по-прежнему скачивается через C или D.</li>
 *   <li>{@link #noRepairWhenAllReplicasAreLive} — если все живы, repair-tick
 *       не должен ничего делать (важно для cost-control).</li>
 * </ol>
 */
class RepairIntegrationTest {

    private final List<NodeApplication> apps = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (int i = apps.size() - 1; i >= 0; i--) {
            try { apps.get(i).close(); } catch (Exception ignored) {}
        }
        apps.clear();
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void repairTriggersWhenStorerLeaves(@TempDir Path tmp) throws Exception {
        // 4 узла. A — uploader/owner, B/C/D — потенциальные хранители.
        // replicationFactor=2 → uploader выберет первых двух пиров (B и C).
        // После убийства B repair должен добрать D.
        NodeApplication a = startNode("a", tmp.resolve("a"), 0, List.of(), 2);
        NodeApplication b = startNode("b", tmp.resolve("b"), 0,
                List.of(new SeedNode("127.0.0.1", a.listenPort())), 2);
        NodeApplication c = startNode("c", tmp.resolve("c"), 0,
                List.of(new SeedNode("127.0.0.1", a.listenPort())), 2);
        NodeApplication d = startNode("d", tmp.resolve("d"), 0,
                List.of(new SeedNode("127.0.0.1", a.listenPort())), 2);

        waitUntil(() -> a.peerManager().size() == 3
                        && b.peerManager().size() == 3
                        && c.peerManager().size() == 3
                        && d.peerManager().size() == 3,
                30_000, () -> "узлы не нашли друг друга");

        Path inputFile = tmp.resolve("input.bin");
        Files.write(inputFile, generatePayload(1500));
        Transaction upload = a.uploadFile(inputFile);

        // Список оригинальных storers — это nodeId двух хранителей.
        Set<String> originalStorers = new HashSet<>();
        upload.getReplicas().forEach(r -> originalStorers.add(r.getStorerPublicKey()));
        assertEquals(2, originalStorers.size(),
                "После upload должно быть 2 уникальных хранителя");

        // ---- Убиваем одного из storers (B, по нашему контракту он первый) ----
        // Чтобы тест не зависел от порядка, выбираем того storer'а, который B/C/D
        // совпадает по nodeId.
        NodeApplication victim = null;
        for (NodeApplication candidate : List.of(b, c, d)) {
            if (originalStorers.contains(candidate.selfNodeId())) {
                victim = candidate;
                break;
            }
        }
        assertNotNull(victim, "Должен найтись узел-storer среди b/c/d");
        String victimId = victim.selfNodeId();
        victim.close();
        apps.remove(victim);

        // Ждём, пока остальные узлы заметят, что B мёртв (peerTimeout=6с).
        // У A в snapshotSessions не должно быть victim'а.
        final NodeApplication aFinal = a;
        waitUntil(() -> !aFinal.peerManager().snapshotSessions().containsKey(victimId),
                15_000, () -> "A не выкинул убитого storer'а из таблицы пиров");

        // ---- Запускаем repair-tick вручную ----
        // У нас в репозитории repair выключен через .disableRepair() для
        // предсказуемости — а здесь мы хотим контроля. Дёргаем напрямую.
        boolean repaired = a.repairService().repairOne(upload);
        assertTrue(repaired,
                "repairOne должен вернуть true — есть к чему добрать реплики");

        // Высота A должна вырасти: появился REPAIR-блок.
        assertEquals(3, a.blockchain().height(),
                "После upload+repair высота должна стать 3 (genesis + UPLOAD + REPAIR)");

        // В блокчейне теперь есть REPAIR-транзакция, и в ней storers — это
        // живые из оригинала + новый.
        Transaction repair = a.blockchain().findLatestRepairFor(upload.getId()).orElseThrow();
        assertEquals(Transaction.Kind.REPAIR, repair.getKind());
        Set<String> newStorers = new HashSet<>();
        repair.getReplicas().forEach(r -> newStorers.add(r.getStorerPublicKey()));
        assertFalse(newStorers.contains(victimId),
                "Убитый storer не должен присутствовать в новом списке реплик");
        assertTrue(newStorers.size() >= 2,
                "После repair количество живых реплик должно быть ≥ replicationFactor: было "
                        + newStorers);
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void downloadUsesRepairedReplicaList(@TempDir Path tmp) throws Exception {
        // Тот же сценарий, что выше, плюс проверка скачивания после repair.
        NodeApplication a = startNode("a", tmp.resolve("a"), 0, List.of(), 2);
        NodeApplication b = startNode("b", tmp.resolve("b"), 0,
                List.of(new SeedNode("127.0.0.1", a.listenPort())), 2);
        NodeApplication c = startNode("c", tmp.resolve("c"), 0,
                List.of(new SeedNode("127.0.0.1", a.listenPort())), 2);
        NodeApplication d = startNode("d", tmp.resolve("d"), 0,
                List.of(new SeedNode("127.0.0.1", a.listenPort())), 2);

        waitUntil(() -> a.peerManager().size() == 3
                        && b.peerManager().size() == 3
                        && c.peerManager().size() == 3
                        && d.peerManager().size() == 3,
                30_000, () -> "узлы не нашли друг друга");

        byte[] originalContent = generatePayload(1500);
        Path inputFile = tmp.resolve("input.bin");
        Files.write(inputFile, originalContent);
        Transaction upload = a.uploadFile(inputFile);

        // Находим одного из storers и убиваем его.
        Set<String> originalStorers = new HashSet<>();
        upload.getReplicas().forEach(r -> originalStorers.add(r.getStorerPublicKey()));
        NodeApplication victim = null;
        for (NodeApplication candidate : List.of(b, c, d)) {
            if (originalStorers.contains(candidate.selfNodeId())) {
                victim = candidate;
                break;
            }
        }
        assertNotNull(victim);
        String victimId = victim.selfNodeId();
        victim.close();
        apps.remove(victim);

        final NodeApplication aFinal = a;
        waitUntil(() -> !aFinal.peerManager().snapshotSessions().containsKey(victimId),
                15_000, () -> "A не выкинул victim из таблицы");

        // Repair вручную.
        a.repairService().repairOne(upload);

        // Скачиваем — A использует свежий список реплик из REPAIR-TX.
        Path outputFile = tmp.resolve("output.bin");
        a.downloadFile(upload.getId(), outputFile);
        assertArrayEquals(originalContent, Files.readAllBytes(outputFile));
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void noRepairWhenAllReplicasAreLive(@TempDir Path tmp) throws Exception {
        NodeApplication a = startNode("a", tmp.resolve("a"), 0, List.of(), 2);
        NodeApplication b = startNode("b", tmp.resolve("b"), 0,
                List.of(new SeedNode("127.0.0.1", a.listenPort())), 2);
        NodeApplication c = startNode("c", tmp.resolve("c"), 0,
                List.of(new SeedNode("127.0.0.1", a.listenPort())), 2);

        waitUntil(() -> a.peerManager().size() == 2
                        && b.peerManager().size() == 2
                        && c.peerManager().size() == 2,
                30_000, () -> "узлы не нашли друг друга");

        Path inputFile = tmp.resolve("input.bin");
        Files.write(inputFile, generatePayload(800));
        Transaction upload = a.uploadFile(inputFile);

        int heightBefore = a.blockchain().height();
        boolean repaired = a.repairService().repairOne(upload);
        assertFalse(repaired, "Если все живы — repair не должен срабатывать");
        assertEquals(heightBefore, a.blockchain().height(),
                "Высота не должна меняться при noop repair");
    }

    // ----------------------------------------------------------------
    // Хелперы
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

        // ВАЖНО: repair тут запускаем ВРУЧНУЮ через .repairOne() — фоновый
        // тик отключаем, чтобы не было гонок и тест был детерминирован.
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
        return app;
    }

    private static byte[] generatePayload(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) ((i * 31 + 7) & 0xFF);
        }
        return data;
    }

    private static void waitUntil(BooleanSupplier cond, long timeoutMs, Supplier<String> diag)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            Thread.sleep(100);
        }
        if (cond.getAsBoolean()) return;
        fail("Не дождались за " + timeoutMs + "мс. " + diag.get());
    }
}
