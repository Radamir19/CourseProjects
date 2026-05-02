package ru.hse.jblockstorage.network;

import ru.hse.jblockstorage.blockchain.StorageReceipt;

/**
 * Ответ хранителя на {@link PutShardMessage}.
 * <p>
 * Если хранитель согласен и успешно сохранил шард — поле {@link #receipt}
 * содержит подписанную им квитанцию. Uploader включает все полученные
 * квитанции в транзакцию (см. {@code Transaction.replicas}), что даёт
 * proof-of-placement, фиксированный в блокчейне.
 * </p>
 * <p>
 * Если по какой-то причине отказал — {@link #accepted} = false, {@code receipt}
 * = null, и в {@link #reason} может быть текстовое объяснение для лога.
 * </p>
 */
public class PutShardAckMessage extends Message {

    /** Хеш шарда, к которому относится ответ (зеркалится из запроса). */
    private String shardHashHex;

    /** {@code true} если шард сохранён и {@link #receipt} валидный. */
    private boolean accepted;

    /** Подписанная квитанция; не null только при {@link #accepted} == true. */
    private StorageReceipt receipt;

    /** Опциональное текстовое объяснение для логов. */
    private String reason;

    public PutShardAckMessage() {}

    public PutShardAckMessage(String shardHashHex, boolean accepted,
                              StorageReceipt receipt, String reason) {
        this.shardHashHex = shardHashHex;
        this.accepted = accepted;
        this.receipt = receipt;
        this.reason = reason;
    }

    /** Удобная фабрика для отказа. */
    public static PutShardAckMessage rejected(String shardHashHex, String reason) {
        return new PutShardAckMessage(shardHashHex, false, null, reason);
    }

    /** Удобная фабрика для принятия. */
    public static PutShardAckMessage accepted(String shardHashHex, StorageReceipt receipt) {
        return new PutShardAckMessage(shardHashHex, true, receipt, null);
    }

    public String getShardHashHex() { return shardHashHex; }
    public boolean isAccepted() { return accepted; }
    public StorageReceipt getReceipt() { return receipt; }
    public String getReason() { return reason; }

    public void setShardHashHex(String shardHashHex) { this.shardHashHex = shardHashHex; }
    public void setAccepted(boolean accepted) { this.accepted = accepted; }
    public void setReceipt(StorageReceipt receipt) { this.receipt = receipt; }
    public void setReason(String reason) { this.reason = reason; }

    @Override
    public String toString() {
        String shortHash = shardHashHex == null ? "?"
                : shardHashHex.substring(0, Math.min(8, shardHashHex.length()));
        return "PutShardAckMessage{hash=" + shortHash + "…, accepted=" + accepted
                + (reason != null ? ", reason=" + reason : "") + "}";
    }
}
