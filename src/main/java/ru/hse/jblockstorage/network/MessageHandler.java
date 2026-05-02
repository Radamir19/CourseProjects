package ru.hse.jblockstorage.network;

/**
 * Колбэк-интерфейс для обработки событий от Netty-канала.
 * <p>
 * Реализуется пользовательской логикой узла ({@code PeerManager}, тестовый код
 * и т.п.). Все методы вызываются на потоке Netty event loop соответствующего
 * канала, поэтому в них нельзя делать блокирующие операции.
 * </p>
 *
 * <h3>Lifecycle</h3>
 * Для одного соединения колбэки вызываются в таком порядке:
 * <pre>
 *   onConnected(peer)            // канал стал active
 *   onMessage(peer, msg) ...     // 0..N сообщений
 *   onError(peer, cause)?        // опционально, при исключении
 *   onDisconnected(peer)         // канал стал inactive
 * </pre>
 */
public interface MessageHandler {

    /** Пришло сообщение от пира. */
    void onMessage(PeerSession peer, Message message);

    /** Соединение установлено (channelActive). */
    default void onConnected(PeerSession peer) {}

    /** Соединение закрыто (channelInactive). */
    default void onDisconnected(PeerSession peer) {}

    /**
     * В пайплайне произошла ошибка (исключение в декодере, разрыв соединения и т.п.).
     * После этого Netty-канал обычно нужно закрывать (это делает уже сам бридж).
     */
    default void onError(PeerSession peer, Throwable cause) {}
}
