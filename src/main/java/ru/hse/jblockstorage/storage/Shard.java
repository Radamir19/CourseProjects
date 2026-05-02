package ru.hse.jblockstorage.storage;

import ru.hse.jblockstorage.crypto.CryptoUtils;

import java.util.Arrays;
import java.util.Objects;

/**
 * Один блок (шард) разбитого файла.
 * <p>
 * Согласно ТЗ (п. 4.1.1.2.3) файл разбивается на блоки фиксированного
 * размера и распределяется по узлам сети. Каждый шард в P2P-сети
 * адресуется не именем файла, а своим SHA-256 хешем — поэтому хеш
 * вычисляется один раз при создании и хранится вместе с данными.
 * </p>
 */
public final class Shard {

    private final int index;
    private final byte[] data;
    private final byte[] hash;

    /** Создаёт шард, вычисляя хеш от данных. */
    public Shard(int index, byte[] data) {
        if (index < 0) {
            throw new IllegalArgumentException("index должен быть >= 0, получено " + index);
        }
        Objects.requireNonNull(data, "data");
        this.index = index;
        this.data = data;
        this.hash = CryptoUtils.applySha256(data);
    }

    /**
     * Создаёт шард с уже известным хешем — например, при загрузке шарда
     * с диска или из сети, когда хеш мы получили из метаданных.
     * Целостность можно проверить через {@link #isIntegrityValid()}.
     */
    public Shard(int index, byte[] data, byte[] hash) {
        if (index < 0) {
            throw new IllegalArgumentException("index должен быть >= 0");
        }
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(hash, "hash");
        if (hash.length != 32) {
            throw new IllegalArgumentException("SHA-256 хеш должен быть 32 байта, получено " + hash.length);
        }
        this.index = index;
        this.data = data;
        this.hash = hash;
    }

    public int index() {
        return index;
    }

    public byte[] data() {
        return data;
    }

    public byte[] hash() {
        return hash;
    }

    public String hashHex() {
        return CryptoUtils.toHex(hash);
    }

    public int size() {
        return data.length;
    }

    /**
     * Перепроверяет хеш — пригодится при получении шарда от другого узла,
     * чтобы убедиться, что данные не были подменены в пути.
     */
    public boolean isIntegrityValid() {
        return Arrays.equals(hash, CryptoUtils.applySha256(data));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Shard other)) return false;
        return index == other.index && Arrays.equals(hash, other.hash);
    }

    @Override
    public int hashCode() {
        return 31 * index + Arrays.hashCode(hash);
    }

    @Override
    public String toString() {
        String shortHash = hashHex().substring(0, Math.min(8, hashHex().length()));
        return "Shard{index=" + index + ", size=" + data.length + ", hash=" + shortHash + "...}";
    }
}