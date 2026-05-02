package ru.hse.jblockstorage.network;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * TCP-клиент для исходящих соединений к другим узлам.
 * <p>
 * Один экземпляр держит свою {@link EventLoopGroup} и может последовательно
 * (а технически — и параллельно) открывать несколько соединений через
 * {@link #connect(String, int)}. На практике в P2P-сети узлу достаточно одного
 * клиента — все исходящие коннекты делятся им.
 * </p>
 *
 * <h3>Использование</h3>
 * <pre>
 *   try (NodeClient client = new NodeClient(handler)) {
 *       PeerSession peer = client.connect("seed.example.com", 8080);
 *       peer.send(new HandshakeMessage(...));
 *       // ... peer.send(...) сколько нужно ...
 *   }
 * </pre>
 *
 * <h3>Жизненный цикл</h3>
 * Один экземпляр — один пул event loop. После {@link #close()} использовать нельзя.
 */
public final class NodeClient implements AutoCloseable {

    private final MessageHandler handler;
    private final long connectTimeoutMillis;

    private EventLoopGroup group;
    private boolean closed;

    public NodeClient(MessageHandler handler) {
        this(handler, 5_000L);
    }

    public NodeClient(MessageHandler handler, long connectTimeoutMillis) {
        this.handler = Objects.requireNonNull(handler, "handler");
        if (connectTimeoutMillis <= 0) {
            throw new IllegalArgumentException("connectTimeoutMillis должен быть > 0");
        }
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    /**
     * Открывает TCP-соединение и возвращает {@link PeerSession}.
     * Блокируется до завершения хендшейка TCP (или таймаута, или ошибки).
     */
    public synchronized PeerSession connect(String host, int port) throws InterruptedException {
        if (closed) {
            throw new IllegalStateException("NodeClient закрыт, нельзя устанавливать новые соединения");
        }
        if (group == null) {
            group = new NioEventLoopGroup();
        }

        Bootstrap b = new Bootstrap();
        b.group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) connectTimeoutMillis)
                .handler(new PipelineConfigurator(handler));

        Channel channel = b.connect(host, port).sync().channel();
        return new PeerSession(channel);
    }

    /**
     * Закрывает event loop и все его соединения.
     * Идемпотентен.
     */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (group != null) {
            group.shutdownGracefully(100, 2000, TimeUnit.MILLISECONDS);
            group = null;
        }
    }
}
