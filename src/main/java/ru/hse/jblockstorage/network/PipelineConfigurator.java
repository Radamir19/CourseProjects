package ru.hse.jblockstorage.network;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;

import java.util.Objects;

/**
 * Один общий {@link ChannelInitializer} для серверной и клиентской сторон.
 * <p>
 * Структура пайплайна (с головы к хвосту):
 * <pre>
 *   frameDecoder  (LengthFieldBasedFrameDecoder) — режет TCP-поток на кадры по 4-байтному префиксу длины
 *   frameEncoder  (LengthFieldPrepender)         — добавляет префикс длины к исходящим
 *   messageDecoder                                — JSON-байты → Message
 *   messageEncoder                                — Message → JSON-байты
 *   bridge        (NettyMessageBridge)            — диспатчит на пользовательский MessageHandler
 * </pre>
 * Поведение зеркальное: что один узел шлёт энкодером — другой читает декодером,
 * поэтому хватает одной фабрики на обе стороны.
 *
 * <h3>Sharable хендлеры</h3>
 * {@link MessageEncoder} и {@link MessageDecoder} помечены {@code @Sharable},
 * но мы для простоты создаём по новому экземпляру на каждое соединение —
 * это дешевле, чем разбираться с общим состоянием при необходимости его добавить.
 */
final class PipelineConfigurator extends ChannelInitializer<SocketChannel> {

    private final MessageHandler handler;

    PipelineConfigurator(MessageHandler handler) {
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ch.pipeline().addLast(
                new LengthFieldBasedFrameDecoder(
                        MessageCodec.MAX_FRAME_LENGTH,
                        /* lengthFieldOffset */ 0,
                        /* lengthFieldLength */ 4,
                        /* lengthAdjustment   */ 0,
                        /* initialBytesToStrip*/ 4),
                new LengthFieldPrepender(/* lengthFieldLength */ 4),
                new MessageDecoder(),
                new MessageEncoder(),
                new NettyMessageBridge(handler)
        );
    }
}
