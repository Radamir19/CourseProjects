package ru.hse.jblockstorage.network;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.hse.jblockstorage.config.NodeConfig;
import ru.hse.jblockstorage.config.SeedNode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Интеграционные тесты {@link PeerManager} + {@link BootstrapDiscovery}
 * на реальных сокетах.
 * <p>
 * Используем агрессивные тайминги ({@link NodeConfig#testDefaults()}) —
 * ping раз в 500мс, peerTimeout 2с, gossip раз в секунду — чтобы тесты
 * пробегали за единицы секунд, а не минуты.
 * </p>
 *
 * <h3>Порядок инициализации одного узла</h3>
 * Между {@link PeerManager} и {@link NodeClient} есть циклическая
 * зависимость: клиент в конструкторе требует {@link MessageHandler}
 * (т.е. PeerManager), а PeerManager хочет ссылку на клиента для активных
 * gossip-коннектов. Разрешаем порядком:
 * <ol>
 *   <li>создаём PeerManager (без клиента — пассивный режим);</li>
 *   <li>создаём NodeClient(handler = manager) и NodeServer(handler = manager);</li>
 *   <li>через {@code manager.setOutgoingClient(client)} включаем активный режим;</li>
 *   <li>запускаем bootstrap к seed-узлам;</li>
 *   <li>{@code manager.start()} — поехали ping-таймеры.</li>
 * </ol>
 */
class PeerManagerIntegrationTest {

    /** Один тестовый узел: server + client + manager. */
    private static class TestNode implements AutoCloseable {
        final String nodeId;
        final NodeServer server;
        final NodeClient client;
        final PeerManager manager;
        final int port;

        TestNode(String nodeId, List<SeedNode> seeds) throws InterruptedException {
            this.nodeId = nodeId;

            // listenPort=0 в конфиге означает «ОС, выбери свободный порт».
            // Реальный порт известен только после server.start(), поэтому ниже
            // мы перепрописываем его в manager через setListenPort.
            NodeConfig config = NodeConfig.testDefaults().toBuilder()
                    .listenPort(0)
                    .seedNodes(seeds)
                    .protocolVersion(1)
                    .build();

            this.manager = new PeerManager(config, nodeId, () -> 0);
            this.client = new NodeClient(manager);
            this.server = new NodeServer("127.0.0.1", config.getListenPort(), manager);
            server.start();
            this.port = server.boundPort();

            // Перезаписываем listenPort в manager на реально привязанный.
            // Без этого исходящие handshake уходили бы с listenPort=0, и принимающая
            // сторона не смогла бы создать PeerInfo (валидация требует port > 0).
            manager.setListenPort(this.port);

            manager.setOutgoingClient(client);

            // BootstrapDiscovery читает listenPort из переданного config — поэтому
            // пересобираем config с правильным портом для bootstrap.
            NodeConfig bootstrapConfig = config.toBuilder().listenPort(this.port).build();
            BootstrapDiscovery bootstrap = new BootstrapDiscovery(bootstrapConfig, client, manager);
            bootstrap.bootstrap(0);

            manager.start();
        }

        @Override
        public void close() {
            try { manager.close(); } catch (Exception ignored) {}
            try { client.close(); } catch (Exception ignored) {}
            try { server.close(); } catch (Exception ignored) {}
        }
    }

    private final List<TestNode> nodes = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (TestNode n : nodes) {
            n.close();
        }
        nodes.clear();
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void twoNodesDiscoverEachOtherViaBootstrap() throws Exception {
        TestNode a = new TestNode("nodeA", List.of());
        nodes.add(a);

        TestNode b = new TestNode("nodeB", List.of(new SeedNode("127.0.0.1", a.port)));
        nodes.add(b);

        // Через 1-2 ping/gossip-цикла оба должны увидеть друг друга
        waitUntil(() -> a.manager.size() >= 1 && b.manager.size() >= 1, 5_000);

        assertEquals(1, a.manager.size(), "A должен видеть B");
        assertEquals(1, b.manager.size(), "B должен видеть A");
        assertTrue(a.manager.knows("nodeB"));
        assertTrue(b.manager.knows("nodeA"));
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void threeNodesAllSeeEachOtherViaGossip() throws Exception {
        // A — seed-узел, стартует пустым
        TestNode a = new TestNode("nodeA", List.of());
        nodes.add(a);

        // B и C оба используют A как seed.
        // Они НЕ знают друг про друга через конфиг — узнают только через gossip от A.
        TestNode b = new TestNode("nodeB", List.of(new SeedNode("127.0.0.1", a.port)));
        nodes.add(b);
        TestNode c = new TestNode("nodeC", List.of(new SeedNode("127.0.0.1", a.port)));
        nodes.add(c);

        waitUntil(() ->
                        a.manager.size() == 2
                                && b.manager.size() == 2
                                && c.manager.size() == 2,
                15_000);

        assertEquals(2, a.manager.size(), "A видит B и C");
        assertEquals(2, b.manager.size(), "B видит A и C");
        assertEquals(2, c.manager.size(), "C видит A и B");

        assertTrue(b.manager.knows("nodeC"), "B должен узнать про C через gossip от A");
        assertTrue(c.manager.knows("nodeB"), "C должен узнать про B через gossip от A");
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void deadPeerIsRemovedAfterTimeout() throws Exception {
        TestNode a = new TestNode("nodeA", List.of());
        nodes.add(a);
        TestNode b = new TestNode("nodeB", List.of(new SeedNode("127.0.0.1", a.port)));
        nodes.add(b);

        waitUntil(() -> a.manager.size() == 1 && b.manager.size() == 1, 5_000);

        // Принудительно закрываем B — A должен через peerTimeout (2с) выкинуть его
        b.close();

        waitUntil(() -> a.manager.size() == 0, 8_000);
        assertEquals(0, a.manager.size(), "A должен выкинуть мёртвого B по таймауту");
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void selfHandshakeIsRejected() throws Exception {
        // Узел указывает сам себя в seeds — это легко получить случайно при копипасте конфига.
        // Менеджер должен заметить self-handshake и не добавить себя в таблицу.
        TestNode a = new TestNode("nodeA", List.of());
        nodes.add(a);

        // Имитация ошибки конфига: bootstrap по своему же адресу.
        BootstrapDiscovery selfBootstrap = new BootstrapDiscovery(
                NodeConfig.testDefaults().toBuilder()
                        .listenPort(a.port)
                        .seedNodes(List.of(new SeedNode("127.0.0.1", a.port)))
                        .build(),
                a.client, a.manager);
        selfBootstrap.bootstrap(0);

        // Ждём пару циклов и проверяем, что таблица всё ещё пустая
        Thread.sleep(2_000);
        assertEquals(0, a.manager.size(),
                "Узел не должен добавить сам себя в таблицу пиров");
    }

    /** Активное ожидание условия с фиксированным таймаутом; кидает AssertionError при истечении. */
    private static void waitUntil(java.util.function.BooleanSupplier cond, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            Thread.sleep(50);
        }
        // Кидаем явный fail вместо тихого возврата — иначе тесты могут проходить
        // «случайно», когда условие так и не выполнилось.
        fail("waitUntil не дождался условия за " + timeoutMs + "мс");
    }
}