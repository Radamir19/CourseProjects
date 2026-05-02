package ru.hse.jblockstorage.network;

/**
 * Ответ на {@link PingMessage}.
 * <p>
 * В {@link #pingTimestamp} принимающая сторона возвращает timestamp из
 * исходного ping — отправитель ping считает RTT как
 * {@code now - pingTimestamp}.
 * В {@link #pongTimestamp} — собственное время отправки pong.
 * </p>
 */
public class PongMessage extends Message {

    /** Timestamp из ping (зеркалится без изменений). */
    private long pingTimestamp;

    /** Время отправки pong по часам отвечающего узла. */
    private long pongTimestamp;

    public PongMessage() {}

    public PongMessage(long pingTimestamp, long pongTimestamp) {
        this.pingTimestamp = pingTimestamp;
        this.pongTimestamp = pongTimestamp;
    }

    public long getPingTimestamp() { return pingTimestamp; }
    public long getPongTimestamp() { return pongTimestamp; }

    public void setPingTimestamp(long pingTimestamp) { this.pingTimestamp = pingTimestamp; }
    public void setPongTimestamp(long pongTimestamp) { this.pongTimestamp = pongTimestamp; }

    @Override
    public String toString() {
        return "PongMessage{pingTs=" + pingTimestamp + ", pongTs=" + pongTimestamp + "}";
    }
}
