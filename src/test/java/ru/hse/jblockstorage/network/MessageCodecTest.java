package ru.hse.jblockstorage.network;

import org.junit.jupiter.api.Test;
import ru.hse.jblockstorage.blockchain.Block;
import ru.hse.jblockstorage.blockchain.Transaction;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тесты JSON-сериализации сообщений.
 * <p>
 * Главная проверяемая инвариантность: кодируем → декодируем → исходный объект
 * по полям совпадает. Заодно проверяем, что Jackson правильно выбирает
 * подкласс по полю {@code "type"}.
 * </p>
 */
class MessageCodecTest {

    @Test
    void pingRoundTrip() {
        PingMessage original = new PingMessage(123_456_789L);
        byte[] bytes = MessageCodec.encode(original);
        Message decoded = MessageCodec.decode(bytes);

        assertInstanceOf(PingMessage.class, decoded);
        assertEquals(123_456_789L, ((PingMessage) decoded).getTimestamp());
    }

    @Test
    void pongRoundTrip() {
        PongMessage original = new PongMessage(100L, 200L);
        Message decoded = MessageCodec.decode(MessageCodec.encode(original));

        assertInstanceOf(PongMessage.class, decoded);
        PongMessage pong = (PongMessage) decoded;
        assertEquals(100L, pong.getPingTimestamp());
        assertEquals(200L, pong.getPongTimestamp());
    }

    @Test
    void handshakeRoundTrip() {
        HandshakeMessage original = new HandshakeMessage(
                "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8...", 8080, 1, 42, 0xDEADBEEFL);
        Message decoded = MessageCodec.decode(MessageCodec.encode(original));

        assertInstanceOf(HandshakeMessage.class, decoded);
        HandshakeMessage hs = (HandshakeMessage) decoded;
        assertEquals("MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8...", hs.getNodeId());
        assertEquals(8080, hs.getListenPort());
        assertEquals(1, hs.getProtocolVersion());
        assertEquals(42, hs.getBlockchainHeight());
        assertEquals(0xDEADBEEFL, hs.getNonce());
    }

    @Test
    void getBlockRoundTrip() {
        GetBlockMessage original = new GetBlockMessage(7);
        Message decoded = MessageCodec.decode(MessageCodec.encode(original));

        assertInstanceOf(GetBlockMessage.class, decoded);
        assertEquals(7, ((GetBlockMessage) decoded).getIndex());
    }

    @Test
    void blockResponseWithGenesisBlockRoundTrip() {
        Block genesis = Block.genesis();
        BlockResponseMessage original = new BlockResponseMessage(0, genesis);

        byte[] bytes = MessageCodec.encode(original);
        Message decoded = MessageCodec.decode(bytes);

        assertInstanceOf(BlockResponseMessage.class, decoded);
        BlockResponseMessage resp = (BlockResponseMessage) decoded;
        assertEquals(0, resp.getRequestedIndex());
        assertNotNull(resp.getBlock());
        assertEquals(genesis.getHash(), resp.getBlock().getHash());
        assertEquals(genesis.getIndex(), resp.getBlock().getIndex());
        assertTrue(resp.getBlock().validateHash(),
                "Восстановленный блок должен иметь валидный хеш");
    }

    @Test
    void blockResponseWithNullBlockRoundTrip() {
        // null блок означает «не нашлось» — этот сценарий тоже должен работать
        BlockResponseMessage original = new BlockResponseMessage(99, null);
        Message decoded = MessageCodec.decode(MessageCodec.encode(original));

        assertInstanceOf(BlockResponseMessage.class, decoded);
        BlockResponseMessage resp = (BlockResponseMessage) decoded;
        assertEquals(99, resp.getRequestedIndex());
        assertNull(resp.getBlock());
    }

    @Test
    void getShardRoundTrip() {
        String hash = "a".repeat(64);
        GetShardMessage original = new GetShardMessage(hash);
        Message decoded = MessageCodec.decode(MessageCodec.encode(original));

        assertInstanceOf(GetShardMessage.class, decoded);
        assertEquals(hash, ((GetShardMessage) decoded).getShardHashHex());
    }

    @Test
    void shardResponseWithDataRoundTrip() {
        byte[] payload = new byte[1024];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i & 0xFF);

        ShardResponseMessage original = new ShardResponseMessage("a".repeat(64), payload);
        Message decoded = MessageCodec.decode(MessageCodec.encode(original));

        assertInstanceOf(ShardResponseMessage.class, decoded);
        ShardResponseMessage resp = (ShardResponseMessage) decoded;
        assertEquals("a".repeat(64), resp.getShardHashHex());
        assertNotNull(resp.getData());
        assertArrayEquals(payload, resp.getData(),
                "Бинарные данные должны выживать после Base64 round-trip");
    }

    @Test
    void shardResponseWithNullDataRoundTrip() {
        ShardResponseMessage original = new ShardResponseMessage("b".repeat(64), null);
        Message decoded = MessageCodec.decode(MessageCodec.encode(original));

        assertInstanceOf(ShardResponseMessage.class, decoded);
        ShardResponseMessage resp = (ShardResponseMessage) decoded;
        assertEquals("b".repeat(64), resp.getShardHashHex());
        assertNull(resp.getData());
    }

    @Test
    void broadcastTxRoundTrip() {
        Transaction tx = new Transaction("dummy-pubkey", "file.bin", 12345L, "merkle-root");
        // Не подписываем — проверяем именно сериализацию полей
        BroadcastTxMessage original = new BroadcastTxMessage(tx);
        Message decoded = MessageCodec.decode(MessageCodec.encode(original));

        assertInstanceOf(BroadcastTxMessage.class, decoded);
        BroadcastTxMessage msg = (BroadcastTxMessage) decoded;
        assertNotNull(msg.getTransaction());
        assertEquals("file.bin", msg.getTransaction().getFileName());
        assertEquals(12345L, msg.getTransaction().getFileSize());
        assertEquals("merkle-root", msg.getTransaction().getMerkleRoot());
    }

    @Test
    void broadcastBlockRoundTrip() {
        Block original = Block.genesis();
        BroadcastBlockMessage msg = new BroadcastBlockMessage(original);

        Message decoded = MessageCodec.decode(MessageCodec.encode(msg));
        assertInstanceOf(BroadcastBlockMessage.class, decoded);
        Block decodedBlock = ((BroadcastBlockMessage) decoded).getBlock();
        assertNotNull(decodedBlock);
        assertEquals(original.getHash(), decodedBlock.getHash());
    }

    @Test
    void typeFieldIsPresentInJson() {
        PingMessage ping = new PingMessage(1L);
        String json = MessageCodec.encodeAsString(ping);
        assertTrue(json.contains("\"type\":\"PING\""),
                "В JSON должно быть поле \"type\":\"PING\", получено: " + json);
    }

    @Test
    void unknownTypeFieldFailsToDecode() {
        // Подменяем type на несуществующий — Jackson должен ругнуться
        byte[] bytes = "{\"type\":\"DOES_NOT_EXIST\"}".getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> MessageCodec.decode(bytes));
    }

    @Test
    void brokenJsonFailsToDecode() {
        byte[] bytes = "это не JSON".getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> MessageCodec.decode(bytes));
    }

    @Test
    void emptyBytesFailToDecode() {
        assertThrows(IllegalArgumentException.class, () -> MessageCodec.decode(new byte[0]));
    }

    @Test
    void unknownExtraFieldsAreIgnored() {
        // Старый клиент получил JSON от нового — там есть лишние поля.
        // Декодер не должен ронять (FAIL_ON_UNKNOWN_PROPERTIES = false).
        String json = "{\"type\":\"PING\",\"timestamp\":42,\"futureField\":\"hello\"}";
        Message msg = MessageCodec.decode(json.getBytes(StandardCharsets.UTF_8));
        assertInstanceOf(PingMessage.class, msg);
        assertEquals(42L, ((PingMessage) msg).getTimestamp());
    }

    @Test
    void manyMessagesProduceManyJsonObjects() {
        // Sanity-check: длинный список сообщений сериализуется/десериализуется без побочных эффектов
        List<Message> messages = List.of(
                new PingMessage(1),
                new PongMessage(1, 2),
                new GetBlockMessage(0),
                new GetShardMessage("c".repeat(64))
        );
        for (Message m : messages) {
            Message back = MessageCodec.decode(MessageCodec.encode(m));
            assertEquals(m.getClass(), back.getClass(),
                    "Тип после round-trip должен совпадать для " + m);
        }
    }
}
