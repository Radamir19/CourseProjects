package ru.hse.jblockstorage.app;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.hse.jblockstorage.blockchain.StorageReceipt;
import ru.hse.jblockstorage.blockchain.Transaction;
import ru.hse.jblockstorage.crypto.AesGcm;
import ru.hse.jblockstorage.crypto.CryptoUtils;
import ru.hse.jblockstorage.crypto.KeyManager;
import ru.hse.jblockstorage.crypto.RsaOaep;
import ru.hse.jblockstorage.network.PeerSession;

import javax.crypto.SecretKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ТЗ п. 4.1.1.4.1 — «параллельная загрузка с нескольких узлов».
 *
 * <p>Проверяем, что {@link FileDownloader} запускает {@code fetchShard}
 * для всех шардов одновременно, а не последовательно. Используем
 * подкласс с искусственной задержкой ответа {@code D} миллисекунд
 * на каждый шард: при {@code N} шардах sequential выполнение заняло бы
 * не меньше {@code N × D}, а параллельное — близко к {@code D}.
 */
class FileDownloaderParallelTest {

    /** Количество шардов в фейковом файле. */
    private static final int N_SHARDS = 8;
    /** Искусственная задержка ответа на один шард. */
    private static final long DELAY_MS = 200;

    @Test
    @DisplayName("8 шардов загружаются параллельно (общее время ≪ N×задержка)")
    void shardsAreFetchedInParallel(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        KeyPair owner = KeyManager.generateRsaKeyPair();

        // Готовим plaintext, делим на N_SHARDS равных частей, шифруем каждую
        // как «шард» (на самом деле просто кусок зашифрованного целого:
        // в реальной системе шардирование идёт на ciphertext, делаем то же).
        byte[] plaintext = new byte[N_SHARDS * 1024];
        for (int i = 0; i < plaintext.length; i++) plaintext[i] = (byte) (i & 0xff);

        SecretKey aes = AesGcm.generateKey();
        byte[] ciphertext = AesGcm.encrypt(plaintext, aes);

        int chunk = ciphertext.length / N_SHARDS;
        // Последний шард забирает остаток, чтобы сумма точно равнялась длине ciphertext.
        List<byte[]> shardData = new ArrayList<>(N_SHARDS);
        for (int i = 0; i < N_SHARDS; i++) {
            int start = i * chunk;
            int end = (i == N_SHARDS - 1) ? ciphertext.length : start + chunk;
            byte[] s = new byte[end - start];
            System.arraycopy(ciphertext, start, s, 0, s.length);
            shardData.add(s);
        }
        List<String> shardHashes = new ArrayList<>(N_SHARDS);
        Map<String, byte[]> shardByHash = new HashMap<>();
        for (byte[] s : shardData) {
            String h = CryptoUtils.toHex(CryptoUtils.applySha256(s));
            shardHashes.add(h);
            shardByHash.put(h, s);
        }

        // encryptedAesKey — зашифрован публичным ключом владельца.
        byte[] encAes = RsaOaep.encrypt(AesGcm.keyToBytes(aes), owner.getPublic());

        // Транзакция с одним фейковым хранителем "X" для каждого шарда.
        // Сначала подписываем, чтобы получить txId, потом строим receipts с этим txId.
        String ownerPub = KeyManager.publicKeyToBase64(owner.getPublic());
        Transaction tx = new Transaction(ownerPub, "test.bin", plaintext.length, "fake-merkle-root");
        tx.setEncryptedAesKey(Base64.getEncoder().encodeToString(encAes));
        tx.setShardHashes(shardHashes);
        tx.sign(owner.getPrivate());

        // fetchShard замокан — реальная подпись receipt'ов не проверяется.
        // Но конструктор StorageReceipt требует non-null signature.
        String storerId = "fake-storer";
        List<StorageReceipt> replicas = new ArrayList<>();
        for (String h : shardHashes) {
            replicas.add(new StorageReceipt(tx.getId(), storerId, h, "fake-signature"));
        }
        tx.setReplicas(replicas);

        // Подсчитываем, сколько раз fetchShard вызывался: должно быть N_SHARDS,
        // и пиковая параллельность — больше 1 (иначе sequential).
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger peakConcurrent = new AtomicInteger();

        // Подкласс FileDownloader с подменённым fetchShard — спим DELAY_MS,
        // возвращаем заранее подготовленные данные шарда.
        FileDownloader downloader = new FileDownloader(owner.getPrivate()) {
            @Override
            byte[] fetchShard(String hashHex, List<String> storerNodeIds,
                              Map<String, PeerSession> sessions,
                              int shardIndex,
                              DownloadProgressListener listener) throws InterruptedException {
                int now = concurrent.incrementAndGet();
                peakConcurrent.updateAndGet(prev -> Math.max(prev, now));
                try {
                    Thread.sleep(DELAY_MS);
                    return shardByHash.get(hashHex);
                } finally {
                    concurrent.decrementAndGet();
                }
            }
        };

        // Поскольку fetchShard замокан — sessions не используется. Передаём
        // что-нибудь не-null, чтобы пройти Objects.requireNonNull.
        Map<String, PeerSession> sessions = Collections.emptyMap();

        Path output = tmp.resolve("restored.bin");
        long t0 = System.currentTimeMillis();
        downloader.downloadFile(tx, sessions, output);
        long elapsed = System.currentTimeMillis() - t0;

        // Контракт корректности: контент совпал.
        assertArrayEquals(plaintext, Files.readAllBytes(output),
                "Восстановленный файл должен совпадать с исходным");

        // Контракт параллельности:
        // 1) пиковое число одновременных fetchShard > 1
        assertTrue(peakConcurrent.get() > 1,
                "Шарды должны качаться параллельно; peak=" + peakConcurrent.get());
        // 2) общее время существенно меньше sequential N×D.
        //    Дадим запас: parallel ≈ D + overhead, sequential = N×D.
        //    Утверждаем elapsed < N×D / 2 — этого достаточно, чтобы поймать регресс,
        //    но не падать на медленной CI.
        long sequentialEstimate = (long) N_SHARDS * DELAY_MS;
        assertTrue(elapsed < sequentialEstimate / 2,
                "Параллельная загрузка должна быть быстрее sequential; "
                        + "elapsed=" + elapsed + "ms, sequential≈" + sequentialEstimate + "ms");
    }
}
