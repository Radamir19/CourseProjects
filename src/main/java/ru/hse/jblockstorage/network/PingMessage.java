package ru.hse.jblockstorage.network;

/**
 * Ping-сообщение для проверки доступности соседа (Keep-Alive, ТЗ п. 4.1.1.1.3).
 * <p>
 * Принимающая сторона должна ответить {@link PongMessage} с тем же {@link #timestamp}
 * — это позволяет измерить RTT и понять, что узел жив.
 * </p>
 */
public class PingMessage extends Message {

    /** Время отправки ping в миллисекундах с эпохи (System.currentTimeMillis). */
    private long timestamp;

    public PingMessage() {}

    public PingMessage(long timestamp) {
        this.timestamp = timestamp;
    }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    @Override
    public String toString() {
        return "PingMessage{timestamp=" + timestamp + "}";
    }
}
