package ru.hse.jblockstorage.network;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.hse.jblockstorage.config.NodeConfig;
import ru.hse.jblockstorage.config.SeedNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * День 15 fix: проверка авто-ретрая bootstrap'а. Сценарий — узел B
 * стартует с {@code seed=A}, но {@code A ещё не существует}. Без retry
 * B так и остался бы один. С retry — после старта A в течение
 * нескольких секунд B должен подключиться к нему через периодический
 * повторный bootstrap.
 *
 * <p>Используется агрессивный {@link NodeConfig#testDefaults()} —
 * ping/gossip быстрее обычного — плюс короткий retry-интервал, чтобы
 * тест пробегал за единицы секунд.
 */
class BootstrapRetryTest {

    /** Хелпер: узел, у которого retry можно настроить отдельно. */
    private static class TestNode implements AutoCloseable {
        final String nodeId;
        final NodeServer server;
        final NodeClient client;
        final PeerManager manager;
        final BootstrapDiscovery bootstrap;
        final int port;

        TestNode(String nodeId, List<SeedNode> seeds, Duration retryInterval)
                throws InterruptedException {
            this(nodeId, seeds, retryInterval, 0);
        }

        TestNode(String nodeId, List<SeedNode> seeds, Duration retryInterval, int listenPort)
                throws InterruptedException {
            this.nodeId = nodeId;

            NodeConfig config = NodeConfig.testDefaults().toBuilder()
                    .listenPort(listenPort)
                    .seedNodes(seeds)
                    .protocolVersion(1)
                    .build();

            this.manager = new PeerManager(config, nodeId, () -> 0);
            this.client = new NodeClient(manager);
            this.server = new NodeServer("127.0.0.1", config.getListenPort(), manager);
            server.start();
            this.port = server.boundPort();

            manager.setListenPort(this.port);
            manager.setOutgoingClient(client);

            NodeConfig bootstrapConfig = config.toBuilder().listenPort(this.port).build();
            this.bootstrap = new BootstrapDiscovery(bootstrapConfig, client, manager);

            // Первый bootstrap может быть пустым (seed ещё не запущен) — это норма.
            this.bootstrap.bootstrap(0);

            // Ключевое отличие от PeerManagerIntegrationTest — включаем retry.
            if (retryInterval != null) {
                this.bootstrap.startPeriodicRetry(retryInterval, () -> 0);
            }

            manager.start();
        }

        @Override
        public void close() {
            try { bootstrap.close(); } catch (Exception ignored) {}
            try { manager.close();   } catch (Exception ignored) {}
            try { client.close();    } catch (Exception ignored) {}
            try { server.close();    } catch (Exception ignored) {}
        }
    }

    private final List<TestNode> nodes = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (TestNode n : nodes) n.close();
        nodes.clear();
    }

    /**
     * Главный тест: B стартует первым (с seed=несуществующего A),
     * через секунду стартует A на том же порту, через ещё секунду
     * (после очередного retry-тика) B должен увидеть A.
     */
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void bootstrapRetryConnectsAfterSeedAppears() throws Exception {
        // Сначала — занимаем порт и сразу освобождаем, чтобы знать,
        // на каком порту потом поднимется A. Если просто использовать
        // listenPort=0, мы не знаем заранее, на каком порту будет A.
        int seedPort;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            seedPort = probe.getLocalPort();
        }

        // B стартует с seed=A, но A ещё нет на этом порту.
        // Retry-интервал короткий — 500мс, чтобы тест пробегал быстро.
        TestNode b = new TestNode("nodeB",
                List.of(new SeedNode("127.0.0.1", seedPort)),
                Duration.ofMillis(500));
        nodes.add(b);

        // Сразу после старта B — пиров 0.
        assertEquals(0, b.manager.size(),
                "B сразу после старта без живых seed'ов должен быть один");

        // Поднимаем A на том же порту.
        TestNode a = new TestNode("nodeA", List.of(), null, seedPort);
        nodes.add(a);

        // Ждём, пока B через retry увидит A.
        // Один retry-тик 500мс + handshake — хватит ~2 секунд с запасом.
        waitUntil(() -> b.manager.size() >= 1, 5_000);

        assertTrue(b.manager.size() >= 1,
                "B должен увидеть A после старта A через периодический retry");
    }

    /**
     * Контрольный сценарий: если seeds пусты, retry вообще не должен
     * запускаться (нечего ретраить), и узел остаётся изолированным
     * без вреда для системы.
     */
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void retryIsNoOpWhenNoSeeds() throws Exception {
        TestNode solo = new TestNode("solo", List.of(), Duration.ofMillis(100));
        nodes.add(solo);

        // Подождём 600мс — за это время сработало бы 6 тиков, если бы
        // retry запустился. Но раз seeds пусты, retry должен сразу
        // вернуться без планирования; 0 пиров — стабильно.
        Thread.sleep(600);
        assertEquals(0, solo.manager.size(), "Без seeds узел остаётся один");
    }

    /** Хелпер: ждём, пока условие станет true, не дольше {@code timeoutMs}. */
    private static void waitUntil(java.util.function.BooleanSupplier cond, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            Thread.sleep(50);
        }
    }
}
