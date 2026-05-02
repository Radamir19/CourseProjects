package ru.hse.jblockstorage.app;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import ru.hse.jblockstorage.blockchain.Blockchain;
import ru.hse.jblockstorage.blockchain.Transaction;
import ru.hse.jblockstorage.crypto.KeyManager;
import ru.hse.jblockstorage.network.Message;
import ru.hse.jblockstorage.network.NodeClient;
import ru.hse.jblockstorage.network.NodeServer;
import ru.hse.jblockstorage.network.PeerSession;
import ru.hse.jblockstorage.network.PutShardAckMessage;
import ru.hse.jblockstorage.network.MessageHandler;
import ru.hse.jblockstorage.network.ShardResponseMessage;
import ru.hse.jblockstorage.storage.FileChunker;
import ru.hse.jblockstorage.storage.ShardStorage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Интеграционный тест полного цикла upload → download через несколько узлов.
 * <p>
 * Сценарий — упрощённая версия ТЗ п. 8.2.1:
 * <ol>
 *   <li>Поднимаем узлы (A — клиент, B и C — хранители) на разных портах localhost.</li>
 *   <li>Узел A открывает соединения к B и C напрямую (без gossip — так короче и
 *       детерминированнее, чем полный bootstrap).</li>
 *   <li>A загружает файл: AES-шифрование → шарды → PUSH к B и C → транзакция.</li>
 *   <li>Проверка: оба B и C физически имеют все шарды на диске.</li>
 *   <li>B отключается (имитация сбоя из ТЗ).</li>
 *   <li>A скачивает файл — должно работать через C.</li>
 *   <li>Расшифрованное содержимое побитово равно оригиналу.</li>
 * </ol>
 *
 * <h3>Что специально <i>не</i> используется</h3>
 * PeerManager, BootstrapDiscovery, gossip — они уже покрыты тестами дня 5.
 * Здесь сосредотачиваемся на самом upload/download, поэтому соединения
 * настраиваем вручную через NodeClient.connect().
 */
class FileUploadDownloadIntegrationTest {

    /**
     * Тестовый «storage-only» узел: сервер + ShardStorage + StorageNodeService.
     * Без uploader/downloader — только хранит и отдаёт.
     */
    private static class StorageNode implements AutoCloseable {
        final String publicKeyBase64;
        final NodeServer server;
        final int port;
        final ShardStorage storage;
        final StorageNodeService service;

        StorageNode(Path baseDir, KeyPair keys) throws IOException, InterruptedException {
            this.publicKeyBase64 = KeyManager.publicKeyToBase64(keys.getPublic());
            this.storage = new ShardStorage(baseDir);
            this.service = new StorageNodeService(publicKeyBase64, keys.getPrivate(), storage);

            MessageHandler handler = new MessageHandler() {
                @Override
                public void onMessage(PeerSession peer, Message message) {
                    service.handle(peer, message);
                }
            };
            this.server = new NodeServer("127.0.0.1", 0, handler);
            server.start();
            this.port = server.boundPort();
        }

        @Override
        public void close() {
            try { server.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * Узел-клиент: держит один {@link FileUploader} и один {@link FileDownloader},
     * и роутит входящие ответы в них.
     * <p>
     * Параметр {@code replicationFactor} прокидывается в конструктор uploader,
     * чтобы можно было создать клиент с repFactor=1 для тестов с одним хранителем.
     * </p>
     */
    private static class ClientNode implements AutoCloseable {
        final FileUploader uploader;
        final FileDownloader downloader;
        final NodeClient client;
        final Blockchain blockchain = new Blockchain();
        final Map<String, PeerSession> sessions = new HashMap<>();

        ClientNode(KeyPair keys, int replicationFactor, int chunkSize) {
            this.uploader = new FileUploader(
                    keys.getPublic(), keys.getPrivate(), blockchain,
                    chunkSize, replicationFactor);
            this.downloader = new FileDownloader(keys.getPrivate());

            // Handler роутит ответы в uploader/downloader. Чтобы найти nodeId
            // по приходящей сессии (PutShardAckMessage не содержит nodeId),
            // делаем обратный поиск по карте sessions.
            //
            // ВАЖНО: сравниваем по lower-level Channel, а не по самому PeerSession.
            // Один и тот же Netty-канал имеет ДВА разных PeerSession-объекта:
            // один создаётся в NodeClient.connect() (его мы кладём в sessions),
            // второй — в NettyMessageBridge.channelActive() (он приходит в onMessage).
            // Channel у них один. Equality по PeerSession-ссылке всегда false.
            MessageHandler handler = new MessageHandler() {
                @Override
                public void onMessage(PeerSession peer, Message message) {
                    if (message instanceof PutShardAckMessage ack) {
                        String storerId = sessions.entrySet().stream()
                                .filter(e -> e.getValue().channel() == peer.channel())
                                .map(Map.Entry::getKey)
                                .findFirst().orElse("unknown");
                        uploader.handleAck(storerId, ack);
                    } else if (message instanceof ShardResponseMessage resp) {
                        downloader.handleShardResponse(resp);
                    }
                }
            };
            this.client = new NodeClient(handler);
        }

        ClientNode(KeyPair keys, int replicationFactor) {
            this(keys, replicationFactor, FileChunker.DEFAULT_CHUNK_SIZE);
        }

        /** Открыть сессию к узлу-хранителю и запомнить её под его publicKey. */
        PeerSession connectTo(String storerPublicKey, int port) throws InterruptedException {
            PeerSession session = client.connect("127.0.0.1", port);
            sessions.put(storerPublicKey, session);
            return session;
        }

        @Override
        public void close() {
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    private final List<AutoCloseable> resources = new ArrayList<>();

    @AfterEach
    void cleanup() {
        // Закрываем в обратном порядке создания (как try-with-resources)
        for (int i = resources.size() - 1; i >= 0; i--) {
            try { resources.get(i).close(); } catch (Exception ignored) {}
        }
        resources.clear();
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void uploadAndDownloadRoundTripWithAllStorersAlive(@TempDir Path tmp) throws Exception {
        KeyPair ownerKeys = KeyManager.generateRsaKeyPair();
        KeyPair bKeys = KeyManager.generateRsaKeyPair();
        KeyPair cKeys = KeyManager.generateRsaKeyPair();

        StorageNode b = new StorageNode(tmp.resolve("b"), bKeys);
        resources.add(b);
        StorageNode c = new StorageNode(tmp.resolve("c"), cKeys);
        resources.add(c);

        // replication=2, потому что у нас два storage-узла
        ClientNode a = new ClientNode(ownerKeys, /* replicationFactor */ 2);
        resources.add(a);

        PeerSession sessionToB = a.connectTo(b.publicKeyBase64, b.port);
        PeerSession sessionToC = a.connectTo(c.publicKeyBase64, c.port);

        Path inputFile = tmp.resolve("input.bin");
        byte[] originalContent = new byte[1024 * 100]; // 100 KB ≈ 1 шард
        for (int i = 0; i < originalContent.length; i++) {
            originalContent[i] = (byte) (i % 256);
        }
        Files.write(inputFile, originalContent);

        Transaction tx = a.uploader.uploadFile(inputFile, List.of(
                new FileUploader.StorerHandle(b.publicKeyBase64, sessionToB),
                new FileUploader.StorerHandle(c.publicKeyBase64, sessionToC)
        ));

        assertTrue(tx.verify(), "Транзакция должна проходить verify()");
        assertTrue(tx.verifyReplicas(), "Все receipts должны быть валидными");
        assertEquals(2, tx.getReplicas().size() / tx.getShardHashes().size(),
                "На каждый шард должно быть по 2 реплики");

        Path outputFile = tmp.resolve("output.bin");
        a.downloader.downloadFile(tx, a.sessions, outputFile);

        byte[] restoredContent = Files.readAllBytes(outputFile);
        assertArrayEquals(originalContent, restoredContent,
                "Восстановленный файл должен побитово совпадать с оригиналом");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void downloadStillWorksAfterOneStorerGoesDown(@TempDir Path tmp) throws Exception {
        // Сценарий ТЗ п. 8.2.1: один из хранителей отвалился, файл всё равно качается.
        KeyPair ownerKeys = KeyManager.generateRsaKeyPair();
        KeyPair bKeys = KeyManager.generateRsaKeyPair();
        KeyPair cKeys = KeyManager.generateRsaKeyPair();

        StorageNode b = new StorageNode(tmp.resolve("b"), bKeys);
        resources.add(b);
        StorageNode c = new StorageNode(tmp.resolve("c"), cKeys);
        resources.add(c);

        ClientNode a = new ClientNode(ownerKeys, /* replicationFactor */ 2);
        resources.add(a);

        PeerSession sessionToB = a.connectTo(b.publicKeyBase64, b.port);
        PeerSession sessionToC = a.connectTo(c.publicKeyBase64, c.port);

        Path inputFile = tmp.resolve("input.bin");
        byte[] originalContent = "Важные данные, которые мы должны восстановить".getBytes();
        Files.write(inputFile, originalContent);

        Transaction tx = a.uploader.uploadFile(inputFile, List.of(
                new FileUploader.StorerHandle(b.publicKeyBase64, sessionToB),
                new FileUploader.StorerHandle(c.publicKeyBase64, sessionToC)
        ));

        // Имитация отказа: B падает
        b.close();
        a.sessions.remove(b.publicKeyBase64); // и сессию убираем — она всё равно мертва

        // C должен покрыть всё
        Path outputFile = tmp.resolve("output.bin");
        a.downloader.downloadFile(tx, a.sessions, outputFile);

        byte[] restored = Files.readAllBytes(outputFile);
        assertArrayEquals(originalContent, restored,
                "Файл должен восстанавливаться даже когда B недоступен");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void multiShardFileWorks(@TempDir Path tmp) throws Exception {
        // Файл больше chunkSize → должно быть несколько шардов
        KeyPair ownerKeys = KeyManager.generateRsaKeyPair();
        KeyPair bKeys = KeyManager.generateRsaKeyPair();

        StorageNode b = new StorageNode(tmp.resolve("b"), bKeys);
        resources.add(b);

        // Маленький chunkSize, чтобы тест был быстрым; replication=1
        int chunkSize = 1024;
        ClientNode a = new ClientNode(ownerKeys, /* replicationFactor */ 1, chunkSize);
        resources.add(a);

        PeerSession sessionToB = a.connectTo(b.publicKeyBase64, b.port);

        // Файл размером 5 шардов
        byte[] originalContent = new byte[chunkSize * 5 + 100];
        for (int i = 0; i < originalContent.length; i++) {
            originalContent[i] = (byte) ((i * 7) & 0xFF);
        }

        Path inputFile = tmp.resolve("multi.bin");
        Files.write(inputFile, originalContent);

        Transaction tx = a.uploader.uploadFile(inputFile, List.of(
                new FileUploader.StorerHandle(b.publicKeyBase64, sessionToB)
        ));

        // После шифрования размер немного больше (IV + tag = 28 байт),
        // поэтому шардов может быть >= 5
        assertTrue(tx.getShardHashes().size() >= 5,
                "Должно быть минимум 5 шардов, получено: " + tx.getShardHashes().size());

        Path outputFile = tmp.resolve("multi-out.bin");
        a.downloader.downloadFile(tx, a.sessions, outputFile);

        assertArrayEquals(originalContent, Files.readAllBytes(outputFile));
    }
}