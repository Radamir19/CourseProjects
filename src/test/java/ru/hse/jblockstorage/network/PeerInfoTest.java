package ru.hse.jblockstorage.network;

import org.junit.jupiter.api.Test;
import ru.hse.jblockstorage.config.NodeConfig;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тесты {@link PeerInfo} и {@link PeerListMessage} — value-объектов и
 * связанной с ними сериализации.
 */
class PeerInfoTest {

    @Test
    void constructorAcceptsValidValues() {
        PeerInfo p = new PeerInfo("nodeA", "127.0.0.1", 8080, 12345L);
        assertEquals("nodeA", p.getNodeId());
        assertEquals("127.0.0.1", p.getHost());
        assertEquals(8080, p.getListenPort());
        assertEquals(12345L, p.getLastSeenMillis());
    }

    @Test
    void constructorRejectsInvalidPort() {
        assertThrows(IllegalArgumentException.class,
                () -> new PeerInfo("n", "h", 0, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> new PeerInfo("n", "h", 70_000, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> new PeerInfo("n", "h", -1, 1L));
    }

    @Test
    void constructorRejectsNullNodeIdOrHost() {
        assertThrows(NullPointerException.class,
                () -> new PeerInfo(null, "h", 8080, 1L));
        assertThrows(NullPointerException.class,
                () -> new PeerInfo("n", null, 8080, 1L));
    }

    @Test
    void withLastSeenReturnsNewInstance() {
        PeerInfo p1 = new PeerInfo("n", "h", 8080, 100L);
        PeerInfo p2 = p1.withLastSeen(200L);

        assertNotSame(p1, p2);
        assertEquals(100L, p1.getLastSeenMillis(), "оригинал не меняется");
        assertEquals(200L, p2.getLastSeenMillis());
        assertEquals(p1.getNodeId(), p2.getNodeId());
        assertEquals(p1.getHost(), p2.getHost());
        assertEquals(p1.getListenPort(), p2.getListenPort());
    }

    @Test
    void equalsAndHashCodeBasedOnAllFields() {
        PeerInfo p1 = new PeerInfo("n", "h", 8080, 100L);
        PeerInfo p2 = new PeerInfo("n", "h", 8080, 100L);
        PeerInfo p3 = new PeerInfo("n", "h", 8080, 200L); // другой lastSeen

        assertEquals(p1, p2);
        assertEquals(p1.hashCode(), p2.hashCode());
        assertNotEquals(p1, p3);
    }

    @Test
    void peerListMessageRoundTrip() {
        PeerInfo p1 = new PeerInfo("nodeA", "10.0.0.1", 8080, 1000L);
        PeerInfo p2 = new PeerInfo("nodeB", "10.0.0.2", 8081, 2000L);
        PeerListMessage original = new PeerListMessage(java.util.List.of(p1, p2));

        Message decoded = MessageCodec.decode(MessageCodec.encode(original));

        assertInstanceOf(PeerListMessage.class, decoded);
        PeerListMessage list = (PeerListMessage) decoded;
        assertEquals(2, list.getPeers().size());
        assertEquals(p1, list.getPeers().get(0));
        assertEquals(p2, list.getPeers().get(1));
    }

    @Test
    void emptyPeerListMessageRoundTrip() {
        PeerListMessage original = new PeerListMessage();
        Message decoded = MessageCodec.decode(MessageCodec.encode(original));

        assertInstanceOf(PeerListMessage.class, decoded);
        assertTrue(((PeerListMessage) decoded).getPeers().isEmpty());
    }

    @Test
    void nodeConfigDefaultsAreSane() {
        NodeConfig cfg = NodeConfig.defaults();
        assertTrue(cfg.getPingInterval().toMillis() > 0);
        assertTrue(cfg.getPeerTimeout().compareTo(cfg.getPingInterval()) > 0,
                "peerTimeout должен быть больше pingInterval");
        assertTrue(cfg.getMaxPeers() > 0);
    }

    @Test
    void nodeConfigRejectsNonsensicalTimings() {
        // peerTimeout <= pingInterval — гарантированный выкид по таймауту
        // даже если пир мгновенно отвечает.
        assertThrows(IllegalArgumentException.class, () -> NodeConfig.defaults()
                .toBuilder()
                .pingInterval(java.time.Duration.ofSeconds(10))
                .peerTimeout(java.time.Duration.ofSeconds(5))
                .build());
    }
}
