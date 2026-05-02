package ru.hse.jblockstorage.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Интеграционный тест: реальный {@link NodeServer} и {@link NodeClient}
 * соединяются на localhost и обмениваются сообщениями.
 * <p>
 * Тесты используют порт 0 при создании сервера — ОС выдаст свободный порт,
 * параллельные запуски в CI не будут конфликтовать. Реальный порт получаем
 * через {@link NodeServer#boundPort()} и передаём клиенту.
 * </p>
 *
 * <h3>Таймауты</h3>
 * Каждый тест ограничен по времени, чтобы зависшее соединение не повесило
 * весь прогон. Внутри используем {@link CountDownLatch} — стандартный паттерн
 * для синхронизации с асинхронным колбэком Netty.
 */
class NodeServerClientTest {

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void clientConnectsAndExchangesPingPong() throws Exception {
        // --- Серверная сторона: на каждый PingMessage отвечает Pong с теми же timestamps ---
        MessageHandler serverHandler = new MessageHandler() {
            @Override
            public void onMessage(PeerSession peer, Message message) {
                if (message instanceof PingMessage ping) {
                    peer.send(new PongMessage(ping.getTimestamp(), 999_999L));
                }
            }
        };

        try (NodeServer server = new NodeServer("127.0.0.1", 0, serverHandler)) {
            server.start();
            int port = server.boundPort();
            assertTrue(port > 0, "Сервер должен выдать ненулевой порт");

            // --- Клиентская сторона: ловит входящий Pong и сохраняет в AtomicReference ---
            CountDownLatch pongReceived = new CountDownLatch(1);
            AtomicReference<PongMessage> received = new AtomicReference<>();
            MessageHandler clientHandler = new MessageHandler() {
                @Override
                public void onMessage(PeerSession peer, Message message) {
                    if (message instanceof PongMessage pong) {
                        received.set(pong);
                        pongReceived.countDown();
                    }
                }
            };

            try (NodeClient client = new NodeClient(clientHandler)) {
                PeerSession peer = client.connect("127.0.0.1", port);
                assertTrue(peer.isActive(), "Клиент должен быть подключён");

                peer.send(new PingMessage(123_456L));

                assertTrue(pongReceived.await(5, TimeUnit.SECONDS),
                        "Pong должен прийти в течение 5 секунд");
                PongMessage pong = received.get();
                assertNotNull(pong);
                assertEquals(123_456L, pong.getPingTimestamp(),
                        "Pong должен зеркалить timestamp ping");
                assertEquals(999_999L, pong.getPongTimestamp());
            }
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void onConnectedFiresOnBothSides() throws Exception {
        CountDownLatch serverConnected = new CountDownLatch(1);
        CountDownLatch clientConnected = new CountDownLatch(1);

        MessageHandler serverHandler = new MessageHandler() {
            @Override public void onMessage(PeerSession peer, Message message) {}
            @Override public void onConnected(PeerSession peer) { serverConnected.countDown(); }
        };
        MessageHandler clientHandler = new MessageHandler() {
            @Override public void onMessage(PeerSession peer, Message message) {}
            @Override public void onConnected(PeerSession peer) { clientConnected.countDown(); }
        };

        try (NodeServer server = new NodeServer("127.0.0.1", 0, serverHandler)) {
            server.start();
            try (NodeClient client = new NodeClient(clientHandler)) {
                client.connect("127.0.0.1", server.boundPort());
                assertTrue(serverConnected.await(5, TimeUnit.SECONDS),
                        "onConnected должен сработать на сервере");
                assertTrue(clientConnected.await(5, TimeUnit.SECONDS),
                        "onConnected должен сработать на клиенте");
            }
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void handshakeMessageDeliveredCorrectly() throws Exception {
        // Простой хендшейк: клиент шлёт свой Handshake, сервер отвечает своим
        AtomicReference<HandshakeMessage> serverGotHandshake = new AtomicReference<>();
        AtomicReference<HandshakeMessage> clientGotHandshake = new AtomicReference<>();
        CountDownLatch serverDone = new CountDownLatch(1);
        CountDownLatch clientDone = new CountDownLatch(1);

        MessageHandler serverHandler = new MessageHandler() {
            @Override
            public void onMessage(PeerSession peer, Message message) {
                if (message instanceof HandshakeMessage hs) {
                    serverGotHandshake.set(hs);
                    peer.send(new HandshakeMessage("server-id", 7000, 1, 5, 0L));
                    serverDone.countDown();
                }
            }
        };
        MessageHandler clientHandler = new MessageHandler() {
            @Override
            public void onMessage(PeerSession peer, Message message) {
                if (message instanceof HandshakeMessage hs) {
                    clientGotHandshake.set(hs);
                    clientDone.countDown();
                }
            }
        };

        try (NodeServer server = new NodeServer("127.0.0.1", 0, serverHandler)) {
            server.start();
            try (NodeClient client = new NodeClient(clientHandler)) {
                PeerSession peer = client.connect("127.0.0.1", server.boundPort());
                peer.send(new HandshakeMessage("client-id", 6000, 1, 1, 42L));

                assertTrue(serverDone.await(5, TimeUnit.SECONDS),
                        "Сервер должен получить handshake от клиента");
                assertTrue(clientDone.await(5, TimeUnit.SECONDS),
                        "Клиент должен получить handshake от сервера");

                assertEquals("client-id", serverGotHandshake.get().getNodeId());
                assertEquals(6000, serverGotHandshake.get().getListenPort());
                assertEquals(1, serverGotHandshake.get().getBlockchainHeight());

                assertEquals("server-id", clientGotHandshake.get().getNodeId());
                assertEquals(7000, clientGotHandshake.get().getListenPort());
                assertEquals(5, clientGotHandshake.get().getBlockchainHeight());
            }
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void onDisconnectedFiresWhenClientCloses() throws Exception {
        CountDownLatch serverDisconnected = new CountDownLatch(1);

        MessageHandler serverHandler = new MessageHandler() {
            @Override public void onMessage(PeerSession peer, Message message) {}
            @Override public void onDisconnected(PeerSession peer) { serverDisconnected.countDown(); }
        };
        MessageHandler clientHandler = new MessageHandler() {
            @Override public void onMessage(PeerSession peer, Message message) {}
        };

        try (NodeServer server = new NodeServer("127.0.0.1", 0, serverHandler)) {
            server.start();
            NodeClient client = new NodeClient(clientHandler);
            try {
                PeerSession peer = client.connect("127.0.0.1", server.boundPort());
                peer.close();
                assertTrue(serverDisconnected.await(5, TimeUnit.SECONDS),
                        "Сервер должен заметить, что клиент отвалился");
            } finally {
                client.close();
            }
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void multiplePingsExchangedSequentially() throws Exception {
        // Шлём 10 ping подряд и считаем pong — проверяем, что пайплайн
        // не теряет сообщения и не путает их порядок.
        int total = 10;
        MessageHandler serverHandler = new MessageHandler() {
            @Override
            public void onMessage(PeerSession peer, Message message) {
                if (message instanceof PingMessage p) {
                    peer.send(new PongMessage(p.getTimestamp(), p.getTimestamp() * 2));
                }
            }
        };

        CountDownLatch allPongs = new CountDownLatch(total);
        long[] receivedOrder = new long[total];
        int[] cursor = new int[]{0};

        MessageHandler clientHandler = new MessageHandler() {
            @Override
            public void onMessage(PeerSession peer, Message message) {
                if (message instanceof PongMessage pong) {
                    synchronized (receivedOrder) {
                        receivedOrder[cursor[0]++] = pong.getPingTimestamp();
                    }
                    allPongs.countDown();
                }
            }
        };

        try (NodeServer server = new NodeServer("127.0.0.1", 0, serverHandler)) {
            server.start();
            try (NodeClient client = new NodeClient(clientHandler)) {
                PeerSession peer = client.connect("127.0.0.1", server.boundPort());
                for (long i = 1; i <= total; i++) {
                    peer.send(new PingMessage(i));
                }
                assertTrue(allPongs.await(5, TimeUnit.SECONDS),
                        "Все 10 pong должны прийти за 5 секунд");

                // Проверяем порядок: сообщения по одному соединению должны прийти упорядоченно
                synchronized (receivedOrder) {
                    for (int i = 0; i < total; i++) {
                        assertEquals(i + 1, receivedOrder[i],
                                "Pong #" + i + " должен соответствовать ping #" + (i + 1));
                    }
                }
            }
        }
    }
}
