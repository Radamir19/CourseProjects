package ru.hse.jblockstorage.storage;

import org.junit.jupiter.api.Test;
import ru.hse.jblockstorage.crypto.CryptoUtils;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShardTest {

    @Test
    void hashIsComputedFromDataInConstructor() {
        byte[] data = "hello".getBytes();
        Shard shard = new Shard(0, data);
        assertArrayEquals(CryptoUtils.applySha256(data), shard.hash());
        assertEquals(32, shard.hash().length);
    }

    @Test
    void integrityCheckPassesForUntouchedShard() {
        Shard shard = new Shard(7, "untouched data".getBytes());
        assertTrue(shard.isIntegrityValid());
    }

    @Test
    void integrityCheckFailsWhenHashDoesNotMatchData() {
        // Имитируем шард, пришедший по сети с подменёнными данными:
        // хеш ожидается старый, а данные уже другие.
        byte[] originalHash = CryptoUtils.applySha256("original".getBytes());
        Shard tampered = new Shard(0, "tampered".getBytes(), originalHash);
        assertFalse(tampered.isIntegrityValid());
    }

    @Test
    void negativeIndexRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Shard(-1, new byte[10]));
    }

    @Test
    void wrongHashLengthRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new Shard(0, new byte[10], new byte[16]));
    }

    @Test
    void differentDataProducesDifferentHash() {
        Shard a = new Shard(0, "a".getBytes());
        Shard b = new Shard(0, "b".getBytes());
        assertNotEquals(a, b);
    }

    @Test
    void equalsBasedOnIndexAndHashNotOnDataReference() {
        byte[] data = "same".getBytes();
        Shard a = new Shard(5, data);
        Shard b = new Shard(5, data.clone());
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}