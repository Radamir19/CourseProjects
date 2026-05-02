package ru.hse.jblockstorage.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.hse.jblockstorage.blockchain.StorageReceipt;
import ru.hse.jblockstorage.blockchain.Transaction;
import ru.hse.jblockstorage.crypto.AesGcm;
import ru.hse.jblockstorage.crypto.CryptoUtils;
import ru.hse.jblockstorage.crypto.RsaOaep;
import ru.hse.jblockstorage.network.GetShardMessage;
import ru.hse.jblockstorage.network.PeerSession;
import ru.hse.jblockstorage.network.ShardResponseMessage;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Оркестратор скачивания файла из распределённой сети.
 * <p>
 * Алгоритм одного download:
 * <ol>
 *   <li>Получить транзакцию из локального блокчейна (по txId или fileName).</li>
 *   <li>Из {@code transaction.replicas} извлечь список (storerNodeId → shardHash)
 *       — каждый receipt говорит, что узел X согласился хранить шард Y.</li>
 *   <li>Для каждого нужного шарда выбрать живого хранителя (по
 *       {@code availableSessions}) и послать ему {@link GetShardMessage}.</li>
 *   <li>Получить ответ {@link ShardResponseMessage}, проверить SHA-256 шарда.</li>
 *   <li>Когда все шарды собраны — склеить шифротекст по индексам.</li>
 *   <li>Расшифровать AES-ключ из транзакции своим приватным RSA-ключом
 *       (RSA-OAEP).</li>
 *   <li>Расшифровать шифротекст AES-ключом (AES-GCM).</li>
 *   <li>Записать plaintext в файл назначения.</li>
 * </ol>
 *
 * <h3>Устойчивость к отключению узлов</h3>
 * Если первый выбранный хранитель не отвечает за {@link #SHARD_TIMEOUT_MILLIS},
 * downloader пробует следующего из списка реплик. Это и есть сценарий ТЗ
 * п. 8.2.1: «Узел Б выключен — скачать файл с оставшихся узлов».
 */
public final class FileDownloader {

    private static final Logger LOG = LoggerFactory.getLogger(FileDownloader.class);

    /** Тайм-аут на ответ одной реплики. */
    public static final long SHARD_TIMEOUT_MILLIS = 5_000;

    private final PrivateKey ownerPrivateKey;

    /** Pending shard requests: shardHashHex → future с ответом. */
    private final Map<String, CompletableFuture<ShardResponseMessage>> pendingShards = new ConcurrentHashMap<>();

    public FileDownloader(PrivateKey ownerPrivateKey) {
        this.ownerPrivateKey = Objects.requireNonNull(ownerPrivateKey, "ownerPrivateKey");
    }

    /**
     * Главная точка входа.
     *
     * @param tx                  транзакция файла из локального блокчейна
     * @param storerSessions      сессии к доступным хранителям (nodeId → session).
     *                            Должны включать как минимум одного из реплик
     *                            каждого шарда, иначе скачать не получится.
     * @param outputFile          куда записать восстановленный файл
     */
    public void downloadFile(Transaction tx,
                             Map<String, PeerSession> storerSessions,
                             Path outputFile)
            throws IOException, InterruptedException, TimeoutException {

        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(storerSessions, "storerSessions");
        Objects.requireNonNull(outputFile, "outputFile");

        // 1. Подготовка: hashHex → список nodeId, которые его хранят
        Map<String, List<String>> shardToStorers = buildShardToStorerIndex(tx);
        List<String> orderedShards = tx.getShardHashes();
        if (orderedShards == null || orderedShards.isEmpty()) {
            throw new IllegalArgumentException("В транзакции нет списка шардов");
        }

        LOG.info("Начинаем download '{}' ({} шардов, {} реплик)",
                tx.getFileName(), orderedShards.size(), tx.getReplicas().size());

        // 2. Скачиваем каждый шард, перебирая хранителей
        byte[][] collectedShards = new byte[orderedShards.size()][];
        for (int i = 0; i < orderedShards.size(); i++) {
            String hash = orderedShards.get(i);
            List<String> storers = shardToStorers.getOrDefault(hash, List.of());
            byte[] data = fetchShard(hash, storers, storerSessions);
            if (data == null) {
                throw new IllegalStateException(
                        "Не удалось скачать шард " + hash.substring(0, 16)
                                + "… ни от одного из " + storers.size() + " известных хранителей");
            }
            collectedShards[i] = data;
        }

        // 3. Склейка шифротекста
        int totalLen = 0;
        for (byte[] s : collectedShards) totalLen += s.length;
        byte[] ciphertext = new byte[totalLen];
        int offset = 0;
        for (byte[] s : collectedShards) {
            System.arraycopy(s, 0, ciphertext, offset, s.length);
            offset += s.length;
        }

        // 4. Расшифровка AES-ключа RSA-OAEP'ом
        if (tx.getEncryptedAesKey() == null) {
            throw new IllegalStateException("В транзакции нет encryptedAesKey");
        }
        byte[] encryptedAesKey = Base64.getDecoder().decode(tx.getEncryptedAesKey());
        byte[] rawAesKey = RsaOaep.decrypt(encryptedAesKey, ownerPrivateKey);
        SecretKey aesKey = AesGcm.keyFromBytes(rawAesKey);

        // 5. Расшифровка содержимого AES-GCM
        byte[] plaintext = AesGcm.decrypt(ciphertext, aesKey);

        // 6. Sanity check — размер должен совпадать с записанным в транзакции
        if (plaintext.length != tx.getFileSize()) {
            throw new IllegalStateException(
                    "Размер расшифрованного файла " + plaintext.length
                            + " не совпадает с заявленным в транзакции " + tx.getFileSize());
        }

        Files.write(outputFile, plaintext);
        LOG.info("Файл успешно восстановлен: {} ({} байт)", outputFile, plaintext.length);
    }

    /**
     * Скачивает один шард, перебирая хранителей по списку. Возвращает {@code null},
     * если ни один не ответил или все ответили мусором.
     */
    private byte[] fetchShard(String hashHex, List<String> storerNodeIds,
                              Map<String, PeerSession> sessions)
            throws InterruptedException {
        for (String nodeId : storerNodeIds) {
            PeerSession session = sessions.get(nodeId);
            if (session == null || !session.isActive()) {
                LOG.debug("Хранитель {} недоступен, пробуем следующего", nodeId);
                continue;
            }

            CompletableFuture<ShardResponseMessage> future = new CompletableFuture<>();
            pendingShards.put(hashHex, future);
            session.send(new GetShardMessage(hashHex));

            ShardResponseMessage resp;
            try {
                resp = future.get(SHARD_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                LOG.debug("Хранитель {} не ответил за {}мс — пробуем следующего",
                        nodeId, SHARD_TIMEOUT_MILLIS);
                continue;
            } catch (java.util.concurrent.ExecutionException e) {
                LOG.debug("Сбой при ожидании шарда от {}: {}", nodeId, e.toString());
                continue;
            } finally {
                pendingShards.remove(hashHex);
            }

            byte[] data = resp.getData();
            if (data == null) {
                LOG.debug("Хранитель {} ответил, но шарда не имеет — следующий", nodeId);
                continue;
            }

            // Целостность: hash полученных данных должен совпасть с запрошенным.
            String actualHash = CryptoUtils.toHex(CryptoUtils.applySha256(data));
            if (!actualHash.equals(hashHex)) {
                LOG.warn("Хранитель {} прислал испорченный шард (хеш не сходится)", nodeId);
                continue;
            }

            return data;
        }
        return null;
    }

    /**
     * Построить индекс «хеш шарда → список nodeId хранителей» из транзакции.
     */
    private Map<String, List<String>> buildShardToStorerIndex(Transaction tx) {
        Map<String, List<String>> result = new HashMap<>();
        for (StorageReceipt r : tx.getReplicas()) {
            result.computeIfAbsent(r.getShardHashHex(), k -> new ArrayList<>())
                  .add(r.getStorerPublicKey()); // мы используем publicKey как nodeId
        }
        return result;
    }

    /**
     * Колбэк для роутера сообщений: внешняя логика (узел) при получении
     * {@link ShardResponseMessage} должна вызвать этот метод. Метод завершает
     * соответствующий future, на котором ждёт {@link #fetchShard}.
     *
     * @return {@code true} если ответ был ожидаем
     */
    public boolean handleShardResponse(ShardResponseMessage resp) {
        CompletableFuture<ShardResponseMessage> future = pendingShards.remove(resp.getShardHashHex());
        if (future == null) return false;
        future.complete(resp);
        return true;
    }
}
