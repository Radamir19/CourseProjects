package ru.hse.jblockstorage.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тесты Netty-пайплайна на {@link EmbeddedChannel} — без реальной сети.
 * <p>
 * Проверяем три критичных свойства:
 * <ol>
 *   <li>исходящий {@link Message} превращается в кадр с префиксом длины и
 *       обратно в исходный объект, если кормить полученные байты обратно;</li>
 *   <li>несколько подряд идущих сообщений в одном TCP-сегменте корректно
 *       разделяются (frame splitting);</li>
 *   <li>фрагментированный TCP-поток (байты приходят по чуть-чуть) тоже
 *       собирается в полные сообщения (frame reassembly).</li>
 * </ol>
 * </p>
 *
 * <h3>Особенность EmbeddedChannel и LengthFieldPrepender</h3>
 * {@link LengthFieldPrepender} в outbound выдаёт <i>два отдельных</i>
 * {@link ByteBuf}: первый содержит 4-байтный префикс длины, второй — само тело.
 * В реальном TCP они склеиваются на уровне сокета и приходят как поток байт,
 * но {@code EmbeddedChannel} хранит исходящие как отдельные элементы очереди.
 * Поэтому здесь все исходящие буферы сливаем в один через {@link #drainOutbound}.
 */
class MessagePipelineTest {

    /** Свежая копия пайплайна — отдельная для каждого теста, чтобы тесты не пересекались. */
    private EmbeddedChannel newPipeline() {
        return new EmbeddedChannel(
                new LengthFieldBasedFrameDecoder(MessageCodec.MAX_FRAME_LENGTH, 0, 4, 0, 4),
                new LengthFieldPrepender(4),
                new MessageDecoder(),
                new MessageEncoder()
        );
    }

    /**
     * Забирает все исходящие {@link ByteBuf} из канала и склеивает в один
     * — это ровно то, что произошло бы на принимающей стороне TCP-соединения,
     * где байты приходят сплошным потоком.
     */
    private static ByteBuf drainOutbound(EmbeddedChannel ch) {
        ByteBuf accumulator = Unpooled.buffer();
        Object obj;
        while ((obj = ch.readOutbound()) != null) {
            if (obj instanceof ByteBuf buf) {
                accumulator.writeBytes(buf);
                buf.release();
            } else {
                fail("Ожидали только ByteBuf в outbound, получили " + obj.getClass());
            }
        }
        return accumulator;
    }

    @Test
    void encodeProducesLengthPrefixedFrame() {
        EmbeddedChannel ch = newPipeline();
        PingMessage ping = new PingMessage(777);

        assertTrue(ch.writeOutbound(ping));

        ByteBuf encoded = drainOutbound(ch);

        // Первые 4 байта — длина, дальше — JSON
        assertTrue(encoded.readableBytes() > 4, "Должны быть байты длины + полезная нагрузка");
        int declaredLength = encoded.readInt();
        assertEquals(encoded.readableBytes(), declaredLength,
                "Декларированная длина должна совпадать с реально оставшимися байтами");

        encoded.release();
        ch.finishAndReleaseAll();
    }

    @Test
    void roundTripThroughPipeline() {
        EmbeddedChannel ch = newPipeline();
        PingMessage ping = new PingMessage(42);

        // Пишем наружу: получаем сериализованные байты
        ch.writeOutbound(ping);
        ByteBuf encoded = drainOutbound(ch);

        // Кормим те же байты внутрь — должны получить исходное сообщение.
        // writeInbound забирает владение буфером, release не нужен.
        assertTrue(ch.writeInbound(encoded));

        Message decoded = ch.readInbound();
        assertInstanceOf(PingMessage.class, decoded);
        assertEquals(42L, ((PingMessage) decoded).getTimestamp());

        ch.finishAndReleaseAll();
    }

    @Test
    void twoMessagesInOneTcpSegmentAreSplit() {
        EmbeddedChannel ch = newPipeline();

        // Кодируем два сообщения подряд — имитируем, что TCP отдал нам один
        // сегмент с двумя кадрами. drainOutbound уже склеивает обе записи.
        ch.writeOutbound(new PingMessage(1));
        ch.writeOutbound(new PingMessage(2));
        ByteBuf glued = drainOutbound(ch);

        assertTrue(ch.writeInbound(glued));

        Message m1 = ch.readInbound();
        Message m2 = ch.readInbound();
        assertInstanceOf(PingMessage.class, m1);
        assertInstanceOf(PingMessage.class, m2);
        assertEquals(1L, ((PingMessage) m1).getTimestamp());
        assertEquals(2L, ((PingMessage) m2).getTimestamp());

        ch.finishAndReleaseAll();
    }

    @Test
    void fragmentedTcpStreamIsReassembled() {
        EmbeddedChannel ch = newPipeline();

        ch.writeOutbound(new PongMessage(10, 20));
        ByteBuf encoded = drainOutbound(ch);

        // Имитируем фрагментацию: режем байты пополам и подаём по частям.
        // LengthFieldBasedFrameDecoder должен дождаться полного кадра.
        int total = encoded.readableBytes();
        int half = total / 2;
        ByteBuf part1 = encoded.copy(0, half);
        ByteBuf part2 = encoded.copy(half, total - half);
        encoded.release();

        // После первой части ничего не должно прилететь как inbound message
        ch.writeInbound(part1);
        assertNull(ch.readInbound(), "Половина кадра — не должно быть готового сообщения");

        // После второй части должен появиться полный объект
        ch.writeInbound(part2);
        Message decoded = ch.readInbound();
        assertInstanceOf(PongMessage.class, decoded);
        assertEquals(20L, ((PongMessage) decoded).getPongTimestamp());

        ch.finishAndReleaseAll();
    }
}