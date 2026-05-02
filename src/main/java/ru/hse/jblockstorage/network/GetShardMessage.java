package ru.hse.jblockstorage.network;

/**
 * Запрос шарда (фрагмента файла) по его SHA-256 хешу.
 * <p>
 * В P2P-сети шард адресуется не индексом или именем, а своим хешем —
 * это позволяет любому узлу проверить, что полученные данные
 * не были подменены (см. {@code Shard#isIntegrityValid()}).
 * </p>
 * <p>
 * Хеш передаётся в hex-формате (64 символа), потому что JSON не любит
 * сырые байты — Jackson по умолчанию закодирует их в Base64, а hex
 * понятнее для отладки и прямого сравнения с метаданными в блоке.
 * </p>
 */
public class GetShardMessage extends Message {

    /** SHA-256 хеш шарда в hex (64 символа). */
    private String shardHashHex;

    public GetShardMessage() {}

    public GetShardMessage(String shardHashHex) {
        this.shardHashHex = shardHashHex;
    }

    public String getShardHashHex() { return shardHashHex; }
    public void setShardHashHex(String shardHashHex) { this.shardHashHex = shardHashHex; }

    @Override
    public String toString() {
        String shortHash = shardHashHex == null ? "?"
                : shardHashHex.substring(0, Math.min(8, shardHashHex.length()));
        return "GetShardMessage{hash=" + shortHash + "...}";
    }
}
