package ru.hse.jblockstorage.network;

/**
 * Ответ на {@link GetShardMessage}.
 * <p>
 * Если шард есть — {@link #data} содержит сырые байты (Jackson сериализует
 * {@code byte[]} в Base64). Если нет — {@code data == null}, узел-запросчик
 * пойдёт спрашивать у следующего соседа.
 * </p>
 * <p>
 * Получатель обязан сверить SHA-256 от {@link #data} с {@link #shardHashHex}
 * перед использованием — это и есть тот контроль целостности, ради которого
 * в P2P-сетях шарды адресуются по хешу.
 * </p>
 */
public class ShardResponseMessage extends Message {

    /** Хеш шарда (зеркалится из запроса). */
    private String shardHashHex;

    /** Сырые байты шарда либо {@code null}, если узел его не хранит. */
    private byte[] data;

    public ShardResponseMessage() {}

    public ShardResponseMessage(String shardHashHex, byte[] data) {
        this.shardHashHex = shardHashHex;
        this.data = data;
    }

    public String getShardHashHex() { return shardHashHex; }
    public byte[] getData() { return data; }

    public void setShardHashHex(String shardHashHex) { this.shardHashHex = shardHashHex; }
    public void setData(byte[] data) { this.data = data; }

    @Override
    public String toString() {
        String shortHash = shardHashHex == null ? "?"
                : shardHashHex.substring(0, Math.min(8, shardHashHex.length()));
        return "ShardResponseMessage{hash=" + shortHash + "..., size="
                + (data == null ? "null" : data.length) + "}";
    }
}
