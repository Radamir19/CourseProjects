package ru.hse.jblockstorage.network;

/**
 * Рукопожатие при подключении узла к узлу.
 * <p>
 * Каждый узел представляется своим идентификатором (Base64 публичного ключа,
 * см. {@code KeyManager}), сообщает порт, на котором слушает входящие соединения,
 * и текущую высоту своего блокчейна. По разнице высот можно понять, что нужно
 * запросить у соседа недостающие блоки.
 * </p>
 *
 * <h3>Замечание по безопасности</h3>
 * На текущем этапе handshake не подтверждает владение приватным ключом
 * (нет challenge-response). В пояснительной записке это зафиксировано как
 * известное ограничение MVP — узел может «представиться» чужим публичным ключом.
 * В финальной доработке после 12 мая будет добавлен обмен случайным челленджем.
 */
public class HandshakeMessage extends Message {

    /** Идентификатор узла — Base64 X.509 публичного ключа владельца. */
    private String nodeId;

    /** TCP-порт, на котором узел слушает входящие соединения. */
    private int listenPort;

    /** Версия протокола — на случай несовместимых изменений в будущем. */
    private int protocolVersion;

    /** Текущая высота блокчейна (количество блоков в цепи, включая genesis). */
    private int blockchainHeight;

    /** Произвольный nonce для отладки и будущей защиты от replay-атак. */
    private long nonce;

    public HandshakeMessage() {}

    public HandshakeMessage(String nodeId, int listenPort, int protocolVersion,
                            int blockchainHeight, long nonce) {
        this.nodeId = nodeId;
        this.listenPort = listenPort;
        this.protocolVersion = protocolVersion;
        this.blockchainHeight = blockchainHeight;
        this.nonce = nonce;
    }

    public String getNodeId() { return nodeId; }
    public int getListenPort() { return listenPort; }
    public int getProtocolVersion() { return protocolVersion; }
    public int getBlockchainHeight() { return blockchainHeight; }
    public long getNonce() { return nonce; }

    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public void setListenPort(int listenPort) { this.listenPort = listenPort; }
    public void setProtocolVersion(int protocolVersion) { this.protocolVersion = protocolVersion; }
    public void setBlockchainHeight(int blockchainHeight) { this.blockchainHeight = blockchainHeight; }
    public void setNonce(long nonce) { this.nonce = nonce; }

    @Override
    public String toString() {
        String shortId = nodeId == null ? "?" : nodeId.substring(0, Math.min(12, nodeId.length()));
        return "HandshakeMessage{nodeId=" + shortId + "..., port=" + listenPort
                + ", height=" + blockchainHeight + ", protocol=v" + protocolVersion + "}";
    }
}
