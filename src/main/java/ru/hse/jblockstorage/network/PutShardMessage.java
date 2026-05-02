package ru.hse.jblockstorage.network;

/**
 * Push-сообщение: uploader отправляет шард выбранному хранителю.
 * <p>
 * В отличие от {@link GetShardMessage}/{@link ShardResponseMessage} (pull-семантика —
 * запросчик инициатор), здесь инициатор — отправитель данных. Используется
 * на этапе upload, когда uploader явно решает, какие K=3 узла будут хранить
 * каждый шард (см. вариант Е, leaderless replication, Клеппман гл. 5).
 * </p>
 *
 * <h3>Содержимое</h3>
 * <ul>
 *   <li>{@code transactionId} — ID транзакции, к которой относится этот шард.
 *       Хранитель использует его при подписании receipt'а.</li>
 *   <li>{@code shardHashHex} — SHA-256 хеш шарда (64 hex-символа). Адрес шарда
 *       и одновременно проверка целостности.</li>
 *   <li>{@code data} — собственно байты шарда.</li>
 * </ul>
 *
 * <h3>Ожидаемый ответ</h3>
 * Хранитель должен ответить {@link PutShardAckMessage} — с подписанным
 * receipt'ом (если шард принят) или с пометкой {@code accepted=false}
 * (если отказался: нет места, неверный хеш и т.п.).
 */
public class PutShardMessage extends Message {

    private String transactionId;
    private String shardHashHex;
    private byte[] data;

    public PutShardMessage() {}

    public PutShardMessage(String transactionId, String shardHashHex, byte[] data) {
        this.transactionId = transactionId;
        this.shardHashHex = shardHashHex;
        this.data = data;
    }

    public String getTransactionId() { return transactionId; }
    public String getShardHashHex() { return shardHashHex; }
    public byte[] getData() { return data; }

    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }
    public void setShardHashHex(String shardHashHex) { this.shardHashHex = shardHashHex; }
    public void setData(byte[] data) { this.data = data; }

    @Override
    public String toString() {
        String shortHash = shardHashHex == null ? "?"
                : shardHashHex.substring(0, Math.min(8, shardHashHex.length()));
        return "PutShardMessage{tx=" + (transactionId == null ? "?"
                : transactionId.substring(0, Math.min(8, transactionId.length())))
                + "…, hash=" + shortHash + "…, size=" + (data == null ? "null" : data.length) + "}";
    }
}
