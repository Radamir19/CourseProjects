package ru.hse.jblockstorage.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.hse.jblockstorage.crypto.CryptoUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тесты {@link ShardStorage} — локального файлового хранилища шардов.
 */
class ShardStorageTest {

    private static String hashOf(byte[] data) {
        return CryptoUtils.toHex(CryptoUtils.applySha256(data));
    }

    @Test
    void saveAndLoadRoundTrip(@TempDir Path tmp) throws IOException {
        ShardStorage storage = new ShardStorage(tmp);
        byte[] data = "hello shard".getBytes();
        String hash = hashOf(data);

        boolean saved = storage.save(hash, data);
        assertTrue(saved, "Первое сохранение должно вернуть true");

        Optional<byte[]> loaded = storage.load(hash);
        assertTrue(loaded.isPresent());
        assertArrayEquals(data, loaded.get());
    }

    @Test
    void hasReportsCorrectly(@TempDir Path tmp) throws IOException {
        ShardStorage storage = new ShardStorage(tmp);
        byte[] data = "x".getBytes();
        String hash = hashOf(data);

        assertFalse(storage.has(hash));
        storage.save(hash, data);
        assertTrue(storage.has(hash));
    }

    @Test
    void duplicateSaveIsIdempotent(@TempDir Path tmp) throws IOException {
        ShardStorage storage = new ShardStorage(tmp);
        byte[] data = "duplicate".getBytes();
        String hash = hashOf(data);

        assertTrue(storage.save(hash, data), "первое сохранение → true");
        assertFalse(storage.save(hash, data), "второе сохранение → false (уже есть)");

        // Содержимое не должно измениться
        assertArrayEquals(data, storage.load(hash).orElseThrow());
    }

    @Test
    void saveRejectsHashMismatch(@TempDir Path tmp) throws IOException {
        ShardStorage storage = new ShardStorage(tmp);
        byte[] data = "real data".getBytes();
        String wrongHash = hashOf("other data".getBytes());

        assertThrows(IllegalArgumentException.class,
                () -> storage.save(wrongHash, data));

        // На диск ничего не должно попасть
        assertFalse(storage.has(wrongHash));
    }

    @Test
    void loadOfMissingShardReturnsEmpty(@TempDir Path tmp) throws IOException {
        ShardStorage storage = new ShardStorage(tmp);
        String hash = hashOf("never saved".getBytes());
        assertTrue(storage.load(hash).isEmpty());
    }

    @Test
    void deleteRemovesShard(@TempDir Path tmp) throws IOException {
        ShardStorage storage = new ShardStorage(tmp);
        byte[] data = "to delete".getBytes();
        String hash = hashOf(data);

        storage.save(hash, data);
        assertTrue(storage.has(hash));

        assertTrue(storage.delete(hash), "первое удаление → true");
        assertFalse(storage.has(hash));
        assertFalse(storage.delete(hash), "повторное удаление → false");
    }

    @Test
    void invalidHashFormatThrows(@TempDir Path tmp) throws IOException {
        ShardStorage storage = new ShardStorage(tmp);
        byte[] data = new byte[]{1};

        assertThrows(IllegalArgumentException.class,
                () -> storage.save("not-a-hash", data));
        assertThrows(IllegalArgumentException.class,
                () -> storage.save("abcd", data));
        assertThrows(IllegalArgumentException.class,
                () -> storage.save("X".repeat(64), data)); // не hex
    }

    @Test
    void pathTraversalAttemptIsBlocked(@TempDir Path tmp) throws IOException {
        ShardStorage storage = new ShardStorage(tmp);
        // Попытка пробить кодировку через '..' в hash
        assertThrows(IllegalArgumentException.class,
                () -> storage.save("../../../etc/passwd" + "0".repeat(46), new byte[]{1}));
    }

    @Test
    void corruptedFileLoadsAsEmpty(@TempDir Path tmp) throws IOException {
        ShardStorage storage = new ShardStorage(tmp);
        byte[] data = "good data".getBytes();
        String hash = hashOf(data);

        storage.save(hash, data);

        // Симулируем повреждение файла извне процесса
        Files.write(tmp.resolve(hash), "corrupted!".getBytes());

        // load должен вернуть Optional.empty(), а не мусорные байты
        assertTrue(storage.load(hash).isEmpty(),
                "При несовпадении хеша load должен вернуть empty");
    }

    @Test
    void worksWithLargeShards(@TempDir Path tmp) throws IOException {
        ShardStorage storage = new ShardStorage(tmp);
        byte[] data = new byte[512 * 1024]; // 512 KB как шард по умолчанию
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i & 0xFF);
        String hash = hashOf(data);

        storage.save(hash, data);
        assertArrayEquals(data, storage.load(hash).orElseThrow());
    }

    // ---------- День 11: list() для GC шардов ----------

    @Test
    void listEnumeratesAllSavedShards(@TempDir Path tmp) throws IOException {
        ShardStorage storage = new ShardStorage(tmp);
        byte[] a = "a".getBytes();
        byte[] b = "b".getBytes();
        byte[] c = "c".getBytes();
        storage.save(hashOf(a), a);
        storage.save(hashOf(b), b);
        storage.save(hashOf(c), c);

        var list = storage.list();
        assertEquals(3, list.size());
        assertTrue(list.contains(hashOf(a)));
        assertTrue(list.contains(hashOf(b)));
        assertTrue(list.contains(hashOf(c)));
    }

    @Test
    void listIgnoresNonHashFiles(@TempDir Path tmp) throws IOException {
        // Если в директории завелись посторонние файлы (например, .tmp от
        // прерванной записи или README, забытый разработчиком), list()
        // их не должен возвращать — иначе GC попытался бы их обработать.
        ShardStorage storage = new ShardStorage(tmp);
        byte[] data = "real-shard".getBytes();
        storage.save(hashOf(data), data);

        Files.writeString(tmp.resolve("README.txt"), "not a shard");
        Files.writeString(tmp.resolve("0123abc.tmp"), "leftover");

        var list = storage.list();
        assertEquals(1, list.size());
        assertEquals(hashOf(data), list.get(0));
    }

    @Test
    void listOnEmptyDirReturnsEmpty(@TempDir Path tmp) throws IOException {
        ShardStorage storage = new ShardStorage(tmp);
        assertTrue(storage.list().isEmpty());
    }
}
