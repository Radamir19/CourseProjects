package ru.hse.jblockstorage.network;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * TCP-сервер узла.
 * <p>
 * Согласно ТЗ (п. 4.1.1.1.4) сервер использует неблокирующий ввод-вывод
 * (Netty NIO). Внутри устроено стандартно:
 * <ul>
 *   <li>boss-группа из 1 потока — принимает входящие соединения;</li>
 *   <li>worker-группа — обрабатывает уже принятые;</li>
 *   <li>пайплайн на каждое соединение настраивается через {@link PipelineConfigurator}.</li>
 * </ul>
 * </p>
 *
 * <h3>Использование</h3>
 * <pre>
 *   try (NodeServer server = new NodeServer(8080, handler)) {
 *       server.start();
 *       // ... сервер живёт в фоне ...
 *   } // close() остановит и группы тоже
 * </pre>
 *
 * <h3>Жизненный цикл</h3>
 * Один экземпляр рассчитан на одну пару start()/close(). Повторный старт
 * после close() не поддерживается — создавайте новый объект.
 */
public final class NodeServer implements AutoCloseable {

    private final String bindHost;
    private final int port;
    private final MessageHandler handler;

    private EventLoopGroup boss;
    private EventLoopGroup worker;
    private Channel serverChannel;
    private boolean started;
    private boolean closed;

    /** Сервер, слушающий все интерфейсы (0.0.0.0). */
    public NodeServer(int port, MessageHandler handler) {
        this("0.0.0.0", port, handler);
    }

    /**
     * Сервер на конкретном хосте.
     * Передайте {@code port = 0}, чтобы ОС выбрала свободный порт сама
     * (полезно в тестах — избегает конфликтов).
     */
    public NodeServer(String bindHost, int port, MessageHandler handler) {
        this.bindHost = Objects.requireNonNull(bindHost, "bindHost");
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port вне допустимого диапазона: " + port);
        }
        this.port = port;
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    /**
     * Запускает сервер. Метод блокируется до момента, когда серверный сокет
     * готов принимать соединения, после чего возвращает управление.
     * После возврата реальный порт можно получить через {@link #boundPort()}.
     */
    public synchronized void start() throws InterruptedException {
        if (closed) {
            throw new IllegalStateException("NodeServer уже был закрыт, повторный запуск не поддерживается");
        }
        if (started) {
            throw new IllegalStateException("NodeServer уже запущен");
        }

        boss = new NioEventLoopGroup(1);
        worker = new NioEventLoopGroup();

        ServerBootstrap b = new ServerBootstrap();
        b.group(boss, worker)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new PipelineConfigurator(handler));

        serverChannel = b.bind(bindHost, port).sync().channel();
        started = true;
    }

    /**
     * Возвращает реальный порт, на котором слушает сервер.
     * Полезно, когда конструктор был вызван с {@code port = 0}.
     */
    public int boundPort() {
        if (!started) throw new IllegalStateException("NodeServer не запущен");
        InetSocketAddress addr = (InetSocketAddress) serverChannel.localAddress();
        return addr.getPort();
    }

    /** {@code true}, пока сервер запущен и слушает. */
    public boolean isRunning() {
        return started && !closed && serverChannel != null && serverChannel.isOpen();
    }

    /**
     * Останавливает сервер и освобождает ресурсы.
     * Идемпотентен — повторный вызов безопасен.
     */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            if (serverChannel != null) {
                serverChannel.close().sync();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            // Короткие тайм-ауты, чтобы тесты не висели лишние 15 секунд.
            if (worker != null) worker.shutdownGracefully(100, 2000, TimeUnit.MILLISECONDS);
            if (boss != null) boss.shutdownGracefully(100, 2000, TimeUnit.MILLISECONDS);
        }
    }
}
