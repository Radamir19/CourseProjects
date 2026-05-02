package ru.hse.jblockstorage.network;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Описание известного пира — то, что мы храним в таблице {@code PeerManager}
 * и чем делимся через {@link PeerListMessage}.
 * <p>
 * Намеренно сделан иммутабельным (сериализатор Jackson требует пустого
 * конструктора с {@code @JsonCreator} или геттеров — мы используем второе,
 * через {@code @JsonProperty} в полях). Когда состояние пира меняется
 * (обновляется {@code lastSeen}), {@code PeerManager} создаёт новый объект.
 * </p>
 *
 * <h3>Зачем listenPort отдельно от адреса соединения</h3>
 * Узел A подключился к узлу Б — у соединения есть удалённый адрес,
 * но это случайный исходящий порт А, а не порт, на котором Б слушает.
 * Чтобы Б смог принять входящие от других, нужно отдельно знать
 * <i>его</i> listenPort, который Б сообщает в handshake.
 */
public final class PeerInfo {

    private final String nodeId;
    private final String host;
    private final int listenPort;
    private final long lastSeenMillis;

    @JsonCreator
    public PeerInfo(
            @JsonProperty("nodeId") String nodeId,
            @JsonProperty("host") String host,
            @JsonProperty("listenPort") int listenPort,
            @JsonProperty("lastSeenMillis") long lastSeenMillis) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.host = Objects.requireNonNull(host, "host");
        if (listenPort < 1 || listenPort > 65535) {
            throw new IllegalArgumentException("listenPort вне диапазона: " + listenPort);
        }
        this.listenPort = listenPort;
        this.lastSeenMillis = lastSeenMillis;
    }

    public String getNodeId() { return nodeId; }
    public String getHost() { return host; }
    public int getListenPort() { return listenPort; }
    public long getLastSeenMillis() { return lastSeenMillis; }

    /** Возвращает копию с обновлённым {@code lastSeenMillis}. */
    public PeerInfo withLastSeen(long newLastSeenMillis) {
        return new PeerInfo(nodeId, host, listenPort, newLastSeenMillis);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PeerInfo other)) return false;
        return listenPort == other.listenPort
                && lastSeenMillis == other.lastSeenMillis
                && nodeId.equals(other.nodeId)
                && host.equals(other.host);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodeId, host, listenPort, lastSeenMillis);
    }

    @Override
    public String toString() {
        String shortId = nodeId.length() > 8 ? nodeId.substring(0, 8) + "…" : nodeId;
        return "PeerInfo{" + shortId + " " + host + ":" + listenPort + "}";
    }
}
