package ru.hse.jblockstorage.config;

import java.util.Objects;

/**
 * Адрес seed-узла из конфига — хост и порт.
 * <p>
 * Семантически отличается от {@code PeerInfo}: у seed-узла нет nodeId
 * и нет статуса, мы знаем про него только то, что когда-то к этому
 * адресу можно было подключиться. Реальный nodeId узнаём из handshake.
 * </p>
 */
public record SeedNode(String host, int port) {
    public SeedNode {
        Objects.requireNonNull(host, "host");
        if (host.isBlank()) throw new IllegalArgumentException("host не может быть пустым");
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port вне диапазона 1..65535: " + port);
        }
    }

    @Override
    public String toString() {
        return host + ":" + port;
    }
}
