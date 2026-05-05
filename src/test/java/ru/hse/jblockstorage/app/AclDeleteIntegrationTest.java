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
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end тесты ACL и DELETE через реальные {@link NodeApplication}.
 * <p>
 * Сценарии:
 * <ol>
 *   <li>{@link #aliceSharesFileWithBob} — Алиса заливает, шарит Бобу,
 *       Боб скачивает успешно через свой ACL-ключ.</li>
 *   <li>{@link #charlieWithoutAclCannotDownload} — посторонний пользователь
 *       без ACL получает отказ.</li>
 *   <li>{@link #aliceCanDeleteFile} — после DELETE файл исчезает из
 *       listMyFiles и попытка скачать падает.</li>
 *   <li>{@link #aclPropagatesAcrossNetwork} — ACL рассылается через
 *       broadcast и виден другим узлам.</li>
 * </ol>
 *
 * <h3>Архитектура</h3>
 * Каждый "пользователь" — отдельный {@link NodeApplication} с собственными
 * ключами и блокчейном. Они одновременно играют роль хранителей друг для
 * друга — это работает, как мы уже проверили в день 7 / день 8.
 */
class AclDeleteIntegrationTest {

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
    void aliceSharesFileWithBob(@TempDir Path tmp) throws Exception {
        // Сценарий: Алиса заливает файл, шарит Бобу, Боб скачивает.
        //
        // Чтобы Боб мог СКАЧАТЬ, шарды должны быть у кого-то, кроме него
        // (downloader не качает с самого себя — он не видит свою же сессию).
        // Поэтому в сети 3 узла: Alice (uploader+sharer), Bob (recipient),
        // Charlie (просто хранитель). replicationFactor=2 — оба пира Алисы
        // (Bob и Charlie) становятся хранителями.
        NodeApplication alice = startNode("alice", tmp.resolve("alice"), 0, List.of(), 2);
        NodeApplication charlie = startNode("charlie", tmp.resolve("charlie"), 0,
                List.of(new SeedNode("127.0.0.1", alice.listenPort())), 2);
        NodeApplication bob = startNode("bob", tmp.resolve("bob"), 0,
                List.of(new SeedNode("127.0.0.1", alice.listenPort())), 2);

        waitUntil(() -> alice.peerManager().size() == 2
                        && bob.peerManager().size() == 2
                        && charlie.peerManager().size() == 2,
                30_000, () -> "alice/bob/charlie не нашли друг друга");

        // ---- Алиса заливает файл ----
        Path inputFile = tmp.resolve("secret.bin");
        byte[] originalContent = generatePayload(2_000);
        Files.write(inputFile, originalContent);
        Transaction upload = alice.uploadFile(inputFile);

        // Broadcast'ом блок должен дойти и до Боба, и до Чарли.
        waitUntil(() -> bob.blockchain().findByTxId(upload.getId()).isPresent()
                        && charlie.blockchain().findByTxId(upload.getId()).isPresent(),
                10_000, () -> "Bob/Charlie не получили UPLOAD-блок");

        // ---- Алиса расшаривает файл с Бобом ----
        String bobPublicKey = bob.selfNodeId();
        Transaction acl = alice.shareFile(upload.getId(), bobPublicKey);
        assertEquals(Transaction.Kind.ACL, acl.getKind());

        waitUntil(() -> bob.blockchain().findByTxId(acl.getId()).isPresent(),
                10_000, () -> "Bob не получил ACL-блок");

        // ---- Боб скачивает — должен успешно ----
        // У Боба в peerManager есть сессия к Charlie (помимо Alice).
        // resolveAesKey пойдёт через ACL — расшифрует Бобовым приватным ключом
        // перешифрованный AES-ключ.
        Path bobOutput = tmp.resolve("bob-output.bin");
        bob.downloadFile(upload.getId(), bobOutput);
        assertArrayEquals(originalContent, Files.readAllBytes(bobOutput),
                "Боб должен расшифровать файл по своему ACL-ключу");
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void charlieWithoutAclCannotDownload(@TempDir Path tmp) throws Exception {
        NodeApplication alice = startNode("alice", tmp.resolve("alice"), 0, List.of(), 1);
        NodeApplication bob = startNode("bob", tmp.resolve("bob"), 0,
                List.of(new SeedNode("127.0.0.1", alice.listenPort())), 1);
        NodeApplication charlie = startNode("charlie", tmp.resolve("charlie"), 0,
                List.of(new SeedNode("127.0.0.1", alice.listenPort())), 1);

        waitUntil(() -> alice.peerManager().size() == 2
                        && bob.peerManager().size() == 2
                        && charlie.peerManager().size() == 2,
                30_000, () -> "Узлы не обнаружили друг друга");

        Path inputFile = tmp.resolve("secret.bin");
        Files.write(inputFile, generatePayload(1_500));
        Transaction upload = alice.uploadFile(inputFile);

        waitUntil(() -> charlie.blockchain().findByTxId(upload.getId()).isPresent(),
                10_000, () -> "Charlie не получил UPLOAD-блок");

        // Чарли пытается скачать без ACL — должен получить отказ на этапе
        // расшифровки AES-ключа, ДО реальной попытки сбора шардов.
        Path charlieOutput = tmp.resolve("charlie-output.bin");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> charlie.downloadFile(upload.getId(), charlieOutput),
                "Charlie не должен мочь скачать чужой файл без ACL");
        // Проверяем формулировку — должна быть осмысленной.
        assertTrue(ex.getMessage().toLowerCase().contains("acl")
                        || ex.getMessage().toLowerCase().contains("доступ"),
                "Сообщение об ошибке должно упоминать ACL или доступ: " + ex.getMessage());
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void aliceCanDeleteFile(@TempDir Path tmp) throws Exception {
        NodeApplication alice = startNode("alice", tmp.resolve("alice"), 0, List.of(), 1);
        NodeApplication bob = startNode("bob", tmp.resolve("bob"), 0,
                List.of(new SeedNode("127.0.0.1", alice.listenPort())), 1);

        waitUntil(() -> alice.peerManager().size() == 1 && bob.peerManager().size() == 1,
                15_000, () -> "alice/bob не нашли друг друга");

        Path inputFile = tmp.resolve("file.bin");
        Files.write(inputFile, generatePayload(800));
        Transaction upload = alice.uploadFile(inputFile);

        // До DELETE файл виден.
        assertEquals(1, alice.listMyFiles().size());

        // ---- DELETE ----
        alice.deleteFile(upload.getId());

        // После DELETE файл уходит из listMyFiles.
        assertTrue(alice.listMyFiles().isEmpty(),
                "После DELETE файл должен исчезнуть из listMyFiles");

        // Скачивание собственного удалённого файла — отказ.
        Path output = tmp.resolve("output.bin");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> alice.downloadFile(upload.getId(), output),
                "Скачивать удалённый файл нельзя");
        assertTrue(ex.getMessage().toLowerCase().contains("удал"),
                "Сообщение должно упоминать удаление: " + ex.getMessage());
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void aclPropagatesAcrossNetwork(@TempDir Path tmp) throws Exception {
        // Проверяем, что ACL разносится broadcast'ом — для GUI-сценария
        // «Боб в реальном времени видит, что ему расшарили файл».
        NodeApplication alice = startNode("alice", tmp.resolve("alice"), 0, List.of(), 1);
        NodeApplication bob = startNode("bob", tmp.resolve("bob"), 0,
                List.of(new SeedNode("127.0.0.1", alice.listenPort())), 1);

        waitUntil(() -> alice.peerManager().size() == 1 && bob.peerManager().size() == 1,
                15_000, () -> "alice/bob не нашли друг друга");

        Path inputFile = tmp.resolve("doc.bin");
        Files.write(inputFile, generatePayload(500));
        Transaction upload = alice.uploadFile(inputFile);

        waitUntil(() -> bob.blockchain().findByTxId(upload.getId()).isPresent(),
                10_000, () -> "Bob не получил UPLOAD");

        // До share — у Боба listAccessibleFiles пустой.
        assertTrue(bob.listAccessibleFiles().isEmpty(),
                "До share Боб не должен видеть расшаренного");

        Transaction acl = alice.shareFile(upload.getId(), bob.selfNodeId());

        // Ждём, пока ACL приедет к Бобу через broadcast.
        waitUntil(() -> !bob.listAccessibleFiles().isEmpty(),
                10_000, () -> "Bob не получил ACL через broadcast: heights = "
                        + alice.blockchain().height() + "/" + bob.blockchain().height());

        List<Transaction> bobsShared = bob.listAccessibleFiles();
        assertEquals(1, bobsShared.size());
        assertEquals(upload.getId(), bobsShared.get(0).getId(),
                "Боб должен видеть расшаренный ему UPLOAD-файл");
    }

    // ----------------------------------------------------------------
    // Хелперы — те же тайминги, что в предыдущих интеграционных тестах
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
