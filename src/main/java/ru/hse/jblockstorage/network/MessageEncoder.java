package ru.hse.jblockstorage.network;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * Netty-encoder: {@link Message} → {@code ByteBuf} (JSON-байты).
 * <p>
 * Длину кадра прибавляет {@link io.netty.handler.codec.LengthFieldPrepender},
 * который должен стоять <i>после</i> этого энкодера в outbound-пайплайне
 * (то есть {@code addLast(prepender, encoder, ...)}). Тогда поток событий:
 * <pre>
 *   Message → MessageEncoder → bytes → LengthFieldPrepender → [len][bytes]
 * </pre>
 * </p>
 *
 * <h3>Sharable</h3>
 * Хендлер не хранит состояния, поэтому помечен {@link Sharable}
 * и может быть переиспользован между каналами.
 */
@ChannelHandler.Sharable
public class MessageEncoder extends MessageToByteEncoder<Message> {

    @Override
    protected void encode(ChannelHandlerContext ctx, Message msg, ByteBuf out) {
        byte[] bytes = MessageCodec.encode(msg);
        out.writeBytes(bytes);
    }
}
