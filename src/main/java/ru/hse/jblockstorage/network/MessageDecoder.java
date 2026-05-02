package ru.hse.jblockstorage.network;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;

import java.util.List;

/**
 * Netty-decoder: {@code ByteBuf} (JSON-байты) → {@link Message}.
 * <p>
 * До этого хендлера в inbound-пайплайне обязательно должен стоять
 * {@link io.netty.handler.codec.LengthFieldBasedFrameDecoder}, который
 * разрезает входной поток TCP на полные кадры по 4-байтному префиксу длины.
 * Поток событий:
 * <pre>
 *   raw bytes → LengthFieldBasedFrameDecoder → JSON-байты → MessageDecoder → Message
 * </pre>
 * </p>
 *
 * <h3>Sharable</h3>
 * Хендлер не хранит состояния, помечен {@link Sharable}.
 */
@ChannelHandler.Sharable
public class MessageDecoder extends MessageToMessageDecoder<ByteBuf> {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        // ByteBuf может ссылаться на пул — копируем в обычный массив,
        // чтобы дальнейший код не зависел от Netty-буферов.
        byte[] bytes = new byte[in.readableBytes()];
        in.readBytes(bytes);
        Message message = MessageCodec.decode(bytes);
        out.add(message);
    }
}
