package ru.hse.jblockstorage.network;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

/**
 * Внутренний Netty-хендлер, преобразующий события канала в вызовы
 * пользовательского {@link MessageHandler}.
 * <p>
 * Stateful (хранит {@link PeerSession} конкретного канала),
 * поэтому <b>не</b> {@code Sharable} — на каждое соединение должен
 * создаваться отдельный экземпляр.
 * </p>
 */
final class NettyMessageBridge extends SimpleChannelInboundHandler<Message> {

    private final MessageHandler delegate;
    private PeerSession session;

    NettyMessageBridge(MessageHandler delegate) {
        this.delegate = delegate;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        session = new PeerSession(ctx.channel());
        try {
            delegate.onConnected(session);
        } finally {
            ctx.fireChannelActive();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (session != null) {
            try {
                delegate.onDisconnected(session);
            } finally {
                ctx.fireChannelInactive();
            }
        } else {
            ctx.fireChannelInactive();
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Message msg) {
        // session != null здесь всегда, потому что channelActive уже отработал.
        delegate.onMessage(session, msg);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (session != null) {
            delegate.onError(session, cause);
        }
        // Закрываем сломанное соединение — продолжать обмен после исключения
        // (битый JSON, переполнение фрейма и т.п.) опасно.
        ctx.close();
    }
}
