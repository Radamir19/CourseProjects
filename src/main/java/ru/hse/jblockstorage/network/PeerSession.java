package ru.hse.jblockstorage.network;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;

import java.net.SocketAddress;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Тонкая обёртка над Netty {@link Channel} — представляет одно соединение
 * с удалённым пиром.
 * <p>
 * Цель этого класса — изолировать пользовательскую логику ({@code MessageHandler})
 * от прямого использования Netty API. Здесь только то, что реально нужно:
 * послать сообщение, закрыть, получить адрес.
 * </p>
 *
 * <h3>Threading</h3>
 * Все методы потокобезопасны (все операции через Channel внутри Netty
 * сериализуются на event loop этого канала).
 */
public final class PeerSession {

    private final Channel channel;

    public PeerSession(Channel channel) {
        this.channel = Objects.requireNonNull(channel, "channel");
    }

    /**
     * Отправляет сообщение пиру (fire-and-forget).
     * Ошибки записи приходят в {@link MessageHandler#onError}.
     */
    public void send(Message message) {
        channel.writeAndFlush(message);
    }

    /**
     * Отправляет сообщение и возвращает future, завершающийся
     * когда байты ушли в сеть (или с ошибкой).
     * Удобно для тестов и для критичных к доставке операций.
     */
    public CompletableFuture<Void> sendAsync(Message message) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        ChannelFuture cf = channel.writeAndFlush(message);
        cf.addListener(f -> {
            if (f.isSuccess()) {
                future.complete(null);
            } else {
                future.completeExceptionally(f.cause());
            }
        });
        return future;
    }

    /** Закрывает соединение. */
    public void close() {
        channel.close();
    }

    /** Удалённый адрес пира (или {@code null}, если канал ещё не connected). */
    public SocketAddress remoteAddress() {
        return channel.remoteAddress();
    }

    /** Локальный адрес (для отладки и логов). */
    public SocketAddress localAddress() {
        return channel.localAddress();
    }

    /** {@code true}, пока соединение установлено. */
    public boolean isActive() {
        return channel.isActive();
    }

    /** Внутренний {@link Channel} — на случай, если нужны продвинутые операции Netty. */
    public Channel channel() {
        return channel;
    }

    @Override
    public String toString() {
        return "PeerSession{" + channel.remoteAddress() + ", active=" + channel.isActive() + "}";
    }
}
