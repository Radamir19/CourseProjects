package ru.hse.jblockstorage.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.hse.jblockstorage.blockchain.Blockchain;
import ru.hse.jblockstorage.blockchain.Transaction;
import ru.hse.jblockstorage.crypto.CryptoUtils;
import ru.hse.jblockstorage.crypto.KeyManager;
import ru.hse.jblockstorage.storage.ShardStorage;

import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты дня 11 для {@link ShardGcService} — физического сборщика мусора шардов
 * (ТЗ п. 4.1.1.4.3).
 *
 * <h3>Сценарии</h3>
 * <ol>
 *   <li>{@link #removesShardsOfDeletedFile} — после DELETE-транзакции шарды
 *       соответствующего UPLOAD'а физически удаляются с диска;</li>
 *   <li>{@link #keepsShardsOfLiveFile} — шарды живого UPLOAD'а не трогаем;</li>
 *   <li>{@link #keepsSharedShardsBetweenLiveAndDeletedFile} — если один и тот же
 *       шард встречается и в живом, и в удалённом UPLOAD'е (дедупликация),
 *       приоритет у живого — шард остаётся;</li>
 *   <li>{@link #keepsOrphanShards} — шард, не упомянутый ни в одной транзакции,
 *       НЕ удаляется (это могут быть pending uploads);</li>
 *   <li>{@link #respectsRepairTransactionForLiveFile} — если есть REPAIR на
 *       живой UPLOAD, шарды REPAIR'а тоже считаются живыми;</li>
 *   <li>{@link #foreignDeleteIsIgnored} — DELETE от чужого пользователя на
 *       чужой UPLOAD не приводит к удалению шардов (защита от вандализма);</li>
 *   <li>{@link #constructorRejectsZeroInterval} — sanity-check на валидацию
 *       параметров.</li>
 * </ol>
 */
class ShardGcServiceTest {

    @Test
    void removesShardsOfDeletedFile(@TempDir Path tmp) throws Exception {
        KeyPair alice = KeyManager.generateRsaKeyPair();
        String aliceKey = KeyManager.publicKeyToBase64(alice.getPublic());
        ShardStorage storage = new ShardStorage(tmp.resolve("shards"));

        // Кладём на диск 3 «шарда».
        String h1 = saveShard(storage, "shard-A");
        String h2 = saveShard(storage, "shard-B");
        String h3 = saveShard(storage, "shard-C");

        // UPLOAD-tx упоминает все 3, потом DELETE её удаляет.
        Blockchain chain = new Blockchain();
        Transaction upload = new Transaction(aliceKey, "f.txt", 1500, "m");
        upload.setShardHashes(List.of(h1, h2, h3));
        upload.sign(alice.getPrivate());
        chain.addBlock(List.of(upload));

        Transaction delete = Transaction.newDelete(aliceKey, upload.getId());
        delete.sign(alice.getPrivate());
        chain.addBlock(List.of(delete));

        ShardGcService gc = new ShardGcService(chain, storage, Duration.ofMillis(100));
        int removed = gc.runOnce();

        assertEquals(3, removed, "Все три шарда должны быть удалены");
        assertFalse(storage.has(h1));
        assertFalse(storage.has(h2));
        assertFalse(storage.has(h3));
    }

    @Test
    void keepsShardsOfLiveFile(@TempDir Path tmp) throws Exception {
        KeyPair alice = KeyManager.generateRsaKeyPair();
        String aliceKey = KeyManager.publicKeyToBase64(alice.getPublic());
        ShardStorage storage = new ShardStorage(tmp.resolve("shards"));

        String h1 = saveShard(storage, "alive-1");
        String h2 = saveShard(storage, "alive-2");

        Blockchain chain = new Blockchain();
        Transaction upload = new Transaction(aliceKey, "live.txt", 1000, "m");
        upload.setShardHashes(List.of(h1, h2));
        upload.sign(alice.getPrivate());
        chain.addBlock(List.of(upload));

        ShardGcService gc = new ShardGcService(chain, storage, Duration.ofMillis(100));
        int removed = gc.runOnce();

        assertEquals(0, removed);
        assertTrue(storage.has(h1));
        assertTrue(storage.has(h2));
    }

    @Test
    void keepsSharedShardsBetweenLiveAndDeletedFile(@TempDir Path tmp) throws Exception {
        // Два файла начинаются с одного и того же 512-КБ блока — один шард
        // встречается и в живом UPLOAD'е, и в удалённом. Приоритет у живого.
        KeyPair alice = KeyManager.generateRsaKeyPair();
        String aliceKey = KeyManager.publicKeyToBase64(alice.getPublic());
        ShardStorage storage = new ShardStorage(tmp.resolve("shards"));

        String shared    = saveShard(storage, "common-prefix");
        String onlyDel   = saveShard(storage, "only-in-deleted");
        String onlyLive  = saveShard(storage, "only-in-live");

        Blockchain chain = new Blockchain();

        // Удалённый файл: содержит shared + onlyDel.
        Transaction deletedUpload = new Transaction(aliceKey, "deleted.txt", 1024, "m1");
        deletedUpload.setShardHashes(List.of(shared, onlyDel));
        deletedUpload.sign(alice.getPrivate());
        chain.addBlock(List.of(deletedUpload));

        Transaction delete = Transaction.newDelete(aliceKey, deletedUpload.getId());
        delete.sign(alice.getPrivate());
        chain.addBlock(List.of(delete));

        // Живой файл: содержит shared + onlyLive.
        Transaction liveUpload = new Transaction(aliceKey, "live.txt", 1024, "m2");
        liveUpload.setShardHashes(List.of(shared, onlyLive));
        liveUpload.sign(alice.getPrivate());
        chain.addBlock(List.of(liveUpload));

        ShardGcService gc = new ShardGcService(chain, storage, Duration.ofMillis(100));
        int removed = gc.runOnce();

        assertEquals(1, removed, "Удалить должны только onlyDel");
        assertTrue(storage.has(shared),   "shared шард должен остаться (нужен живому)");
        assertFalse(storage.has(onlyDel), "onlyDel — удалить");
        assertTrue(storage.has(onlyLive), "onlyLive — оставить");
    }

    @Test
    void keepsOrphanShards(@TempDir Path tmp) throws Exception {
        // На диске лежит шард, который не упомянут ни в одной транзакции
        // (например, это шард pending upload'а, блок ещё не дошёл по сети).
        // GC должен оставить его в покое.
        ShardStorage storage = new ShardStorage(tmp.resolve("shards"));
        String orphan = saveShard(storage, "orphan-shard");

        Blockchain chain = new Blockchain(); // пустая (только genesis)

        ShardGcService gc = new ShardGcService(chain, storage, Duration.ofMillis(100));
        int removed = gc.runOnce();

        assertEquals(0, removed);
        assertTrue(storage.has(orphan), "Сирота должен остаться");
    }

    @Test
    void respectsRepairTransactionForLiveFile(@TempDir Path tmp) throws Exception {
        // REPAIR-транзакция от того же владельца указывает на UPLOAD,
        // который ещё жив — шарды REPAIR (которые могут отличаться от
        // оригинала после авто-репликации) должны считаться живыми.
        KeyPair alice = KeyManager.generateRsaKeyPair();
        String aliceKey = KeyManager.publicKeyToBase64(alice.getPublic());
        ShardStorage storage = new ShardStorage(tmp.resolve("shards"));

        String h1 = saveShard(storage, "shard-X");
        String h2 = saveShard(storage, "shard-Y");

        Blockchain chain = new Blockchain();
        Transaction upload = new Transaction(aliceKey, "f.txt", 1000, "m");
        upload.setShardHashes(List.of(h1, h2));
        upload.sign(alice.getPrivate());
        chain.addBlock(List.of(upload));

        // REPAIR с теми же шардами — типичная картина после авто-репликации
        // (shardHashes дублируются из оригинала).
        Transaction repair = Transaction.newRepair(aliceKey, upload.getId(), List.of(h1, h2));
        repair.sign(alice.getPrivate());
        chain.addBlock(List.of(repair));

        ShardGcService gc = new ShardGcService(chain, storage, Duration.ofMillis(100));
        int removed = gc.runOnce();

        assertEquals(0, removed);
        assertTrue(storage.has(h1));
        assertTrue(storage.has(h2));
    }

    @Test
    void foreignDeleteIsIgnored(@TempDir Path tmp) throws Exception {
        // Боб подписывает DELETE на файл Алисы. Blockchain.isDeleted
        // игнорирует чужой DELETE — GC тоже не должен ничего удалять.
        KeyPair alice = KeyManager.generateRsaKeyPair();
        KeyPair bob   = KeyManager.generateRsaKeyPair();
        String aliceKey = KeyManager.publicKeyToBase64(alice.getPublic());
        String bobKey   = KeyManager.publicKeyToBase64(bob.getPublic());
        ShardStorage storage = new ShardStorage(tmp.resolve("shards"));

        String h1 = saveShard(storage, "alice-shard");

        Blockchain chain = new Blockchain();
        Transaction upload = new Transaction(aliceKey, "alice.txt", 500, "m");
        upload.setShardHashes(List.of(h1));
        upload.sign(alice.getPrivate());
        chain.addBlock(List.of(upload));

        // Боб пытается удалить чужой файл — DELETE в блокчейне есть,
        // но семантически это игнорируется.
        Transaction maliciousDelete = Transaction.newDelete(bobKey, upload.getId());
        maliciousDelete.sign(bob.getPrivate());
        chain.addBlock(List.of(maliciousDelete));

        ShardGcService gc = new ShardGcService(chain, storage, Duration.ofMillis(100));
        int removed = gc.runOnce();

        assertEquals(0, removed);
        assertTrue(storage.has(h1), "Шард Алисы не должен быть тронут чужим DELETE");
    }

    @Test
    void constructorRejectsZeroInterval(@TempDir Path tmp) throws Exception {
        ShardStorage storage = new ShardStorage(tmp.resolve("shards"));
        Blockchain chain = new Blockchain();
        assertThrows(IllegalArgumentException.class,
                () -> new ShardGcService(chain, storage, Duration.ZERO));
        assertThrows(NullPointerException.class,
                () -> new ShardGcService(null, storage, Duration.ofSeconds(1)));
    }

    /** Кладёт «шард» на диск, имя файла = SHA-256 содержимого. Возвращает hex hash. */
    private static String saveShard(ShardStorage storage, String content) throws Exception {
        byte[] data = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash = CryptoUtils.toHex(CryptoUtils.applySha256(data));
        storage.save(hash, data);
        return hash;
    }
}
