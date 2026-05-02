package ru.hse.jblockstorage.blockchain;

import ru.hse.jblockstorage.crypto.CryptoUtils;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Один блок цепочки.
 * <p>
 * Согласно ТЗ (п. 4.1.1.3.3) каждый блок содержит ссылку на хеш предыдущего блока,
 * список транзакций и собственный хеш. Связь блоков по {@code prevHash} — основное
 * свойство, делающее историю неподделываемой: подмена любого старого блока меняет
 * его хеш, что разрывает цепочку для всех последующих блоков.
 * </p>
 */
public class Block implements Serializable {

    /** «Нулевой» хеш — указывается в genesis-блоке как prevHash. */
    public static final String GENESIS_PREV_HASH = "0".repeat(64);

    private int index;
    private long timestamp;
    private String prevHash;
    private List<Transaction> transactions;
    /** Поле для будущего PoW; сейчас всегда 0. */
    private long nonce;
    private String hash;

    /** Пустой конструктор для Jackson. */
    public Block() {}

    /** Конструктор для нового блока — хеш считается автоматически. */
    public Block(int index, long timestamp, String prevHash, List<Transaction> transactions, long nonce) {
        this.index = index;
        this.timestamp = timestamp;
        this.prevHash = Objects.requireNonNull(prevHash, "prevHash");
        this.transactions = transactions == null ? new ArrayList<>() : new ArrayList<>(transactions);
        this.nonce = nonce;
        this.hash = computeHash(this.index, this.timestamp, this.prevHash, this.transactions, this.nonce);
    }

    /** Создаёт genesis-блок (index=0, prevHash из нулей, нет транзакций). */
    public static Block genesis() {
        return new Block(0, 0L, GENESIS_PREV_HASH, List.of(), 0L);
    }

    /** Собирает новый блок (timestamp = now, nonce = 0). */
    public static Block create(int index, String prevHash, List<Transaction> transactions) {
        return new Block(index, System.currentTimeMillis(), prevHash, transactions, 0L);
    }

    public int getIndex() { return index; }
    public long getTimestamp() { return timestamp; }
    public String getPrevHash() { return prevHash; }
    public List<Transaction> getTransactions() {
        return transactions == null ? List.of() : Collections.unmodifiableList(transactions);
    }
    public long getNonce() { return nonce; }
    public String getHash() { return hash; }

    // Сеттеры — для десериализации Jackson. В коде не используем.
    public void setIndex(int index) { this.index = index; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    public void setPrevHash(String prevHash) { this.prevHash = prevHash; }
    public void setTransactions(List<Transaction> transactions) { this.transactions = transactions; }
    public void setNonce(long nonce) { this.nonce = nonce; }
    public void setHash(String hash) { this.hash = hash; }

    /**
     * Проверяет, что хеш блока соответствует его полям —
     * то есть блок не был подделан после создания.
     */
    public boolean validateHash() {
        if (hash == null) return false;
        String recomputed = computeHash(index, timestamp, prevHash,
                transactions == null ? List.of() : transactions, nonce);
        return hash.equals(recomputed);
    }

    /**
     * Полная валидация блока:
     * 1) хеш совпадает с пересчитанным;
     * 2) все транзакции имеют корректную подпись (см. {@link Transaction#verify()}).
     */
    public boolean validate() {
        if (!validateHash()) return false;
        if (transactions != null) {
            for (Transaction tx : transactions) {
                if (!tx.verify()) return false;
            }
        }
        return true;
    }

    private static String computeHash(int index, long timestamp, String prevHash,
                                      List<Transaction> transactions, long nonce) {
        StringBuilder sb = new StringBuilder();
        sb.append(index).append('|')
          .append(timestamp).append('|')
          .append(prevHash).append('|')
          .append(nonce).append('|');
        for (Transaction tx : transactions) {
            // Используем уже посчитанный id транзакции (либо пересчитываем, если null).
            sb.append(tx.getId() != null ? tx.getId() : tx.calculateTxId()).append(',');
        }
        return CryptoUtils.applySha256(sb.toString());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Block other)) return false;
        return index == other.index && Objects.equals(hash, other.hash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(index, hash);
    }

    @Override
    public String toString() {
        String shortHash = hash == null ? "?" : hash.substring(0, Math.min(8, hash.length()));
        String shortPrev = prevHash == null ? "?" : prevHash.substring(0, Math.min(8, prevHash.length()));
        int txCount = transactions == null ? 0 : transactions.size();
        return "Block{#" + index + ", txs=" + txCount + ", hash=" + shortHash + "..., prev=" + shortPrev + "...}";
    }
}
