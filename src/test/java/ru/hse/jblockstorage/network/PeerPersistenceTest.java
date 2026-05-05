package ru.hse.jblockstorage.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.hse.jblockstorage.config.NodeConfig;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тесты {@link PeerManager#savePeers}/{@link PeerManager#loadPeers} —
 * persistent-таблица пиров (день 10).
 */
class PeerPersistenceTest {

    @Test
    void roundTripSaveAndLoad(@TempDir Path tmp) throws Exception {
        NodeConfig config = NodeConfig.testDefaults();

        // Сценарий: первый PeerManager получает пиров через handshake,
        // сохраняет на диск; второй PeerManager (с тем же selfNodeId)
        // загружает с диска и должен иметь ту же таблицу.
        //
        // Чтобы не поднимать сеть — сериализуем JSON руками: у нас тогда
        // всё под контролем, и мы реально проверяем именно save+load как
        // round-trip JSON.
        Path peersFile = tmp.resolve("peers.json");
        String json = """
                [
                  {"nodeId":"nodeB","host":"192.168.1.10","listenPort":9001,"lastSeenMillis":111},
                  {"nodeId":"nodeC","host":"192.168.1.11","listenPort":9002,"lastSeenMillis":222}
                ]
                """;
        Files.writeString(peersFile, json);

        PeerManager mgr1 = new PeerManager(config, "selfA", () -> 1);
        mgr1.setListenPort(9000);
        int loaded = mgr1.loadPeers(peersFile);
        assertEquals(2, loaded);
        assertEquals(2, mgr1.size());
        assertTrue(mgr1.knows("nodeB"));
        assertTrue(mgr1.knows("nodeC"));

        // Теперь сохраняем и загружаем в новый PeerManager.
        Path peersFile2 = tmp.resolve("peers2.json");
        mgr1.savePeers(peersFile2);
        assertTrue(Files.isRegularFile(peersFile2));
        // Файл должен быть валидным JSON — попробуем перечитать его в третьем
        // менеджере.
        PeerManager mgr2 = new PeerManager(config, "selfA", () -> 1);
        mgr2.setListenPort(9000);
        int loaded2 = mgr2.loadPeers(peersFile2);
        assertEquals(2, loaded2);
        assertTrue(mgr2.knows("nodeB"));
        assertTrue(mgr2.knows("nodeC"));
    }

    @Test
    void loadFromMissingFileIsNoOp(@TempDir Path tmp) throws Exception {
        NodeConfig config = NodeConfig.testDefaults();
        PeerManager mgr = new PeerManager(config, "selfA", () -> 1);
        mgr.setListenPort(9001);

        int loaded = mgr.loadPeers(tmp.resolve("nonexistent.json"));
        assertEquals(0, loaded);
        assertEquals(0, mgr.size(),
                "Загрузка из отсутствующего файла не должна добавлять записей");
    }

    @Test
    void loadIgnoresOwnNodeId(@TempDir Path tmp) throws Exception {
        // Защита от ситуации, когда в peers.json случайно попал наш собственный
        // nodeId — мы не должны добавлять самих себя в таблицу.
        Path peersFile = tmp.resolve("peers.json");

        // Ручной JSON с "self" внутри.
        String json = """
                [
                  {"nodeId":"selfA","host":"127.0.0.1","listenPort":9001,"lastSeenMillis":1000},
                  {"nodeId":"otherB","host":"127.0.0.1","listenPort":9002,"lastSeenMillis":2000}
                ]
                """;
        Files.writeString(peersFile, json);

        NodeConfig config = NodeConfig.testDefaults();
        PeerManager mgr = new PeerManager(config, "selfA", () -> 1);
        mgr.setListenPort(9000);

        int loaded = mgr.loadPeers(peersFile);
        assertEquals(1, loaded, "Должна быть загружена только запись otherB");
        assertEquals(1, mgr.size());
        assertTrue(mgr.knows("otherB"));
        assertFalse(mgr.knows("selfA"),
                "Загрузка не должна добавлять собственный nodeId в таблицу");
    }

    @Test
    void loadRespectsMaxPeersLimit(@TempDir Path tmp) throws Exception {
        // Если в файле больше пиров, чем maxPeers — лишние отбрасываются.
        Path peersFile = tmp.resolve("peers.json");
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 5; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"nodeId\":\"node").append(i)
                    .append("\",\"host\":\"127.0.0.1\",\"listenPort\":")
                    .append(9001 + i).append(",\"lastSeenMillis\":")
                    .append(1000 + i).append("}");
        }
        sb.append("]");
        Files.writeString(peersFile, sb.toString());

        NodeConfig config = NodeConfig.defaults().toBuilder().maxPeers(3).build();
        PeerManager mgr = new PeerManager(config, "selfA", () -> 1);
        mgr.setListenPort(9000);

        int loaded = mgr.loadPeers(peersFile);
        assertEquals(3, loaded);
        assertEquals(3, mgr.size());
    }

    @Test
    void corruptedFileFailsCleanlyWithoutCrashing(@TempDir Path tmp) throws Exception {
        // Если кто-то испортил peers.json вручную, loadPeers должен
        // бросить IOException, а не SilentSwallow.
        Path peersFile = tmp.resolve("peers.json");
        Files.writeString(peersFile, "{this is not valid JSON");

        NodeConfig config = NodeConfig.testDefaults();
        PeerManager mgr = new PeerManager(config, "selfA", () -> 1);
        mgr.setListenPort(9000);

        assertThrows(java.io.IOException.class, () -> mgr.loadPeers(peersFile));
    }

    // ---------- День 11: периодический автосейв ----------

    @Test
    void periodicSaveWritesFileWithoutExplicitClose(@TempDir Path tmp) throws Exception {
        // Сценарий, который надо защитить: процесс падает по SIGKILL — close()
        // не успевает вызваться. Между save'ами на диске должен быть актуальный
        // снимок таблицы, не старше interval'а.
        Path peersFile = tmp.resolve("peers.json");

        // Готовим начальный файл с одной записью — это даст нам непустую
        // таблицу после loadPeers, чтобы было что сохранять.
        Files.writeString(peersFile, """
                [{"nodeId":"nodeX","host":"10.0.0.1","listenPort":7777,"lastSeenMillis":42}]
                """);

        NodeConfig config = NodeConfig.testDefaults();
        PeerManager mgr = new PeerManager(config, "selfA", () -> 1);
        mgr.setListenPort(9000);
        assertEquals(1, mgr.loadPeers(peersFile));

        // Удаляем файл, чтобы доказать, что он будет ПЕРЕсоздан фоновой задачей.
        Files.delete(peersFile);
        assertFalse(Files.exists(peersFile));

        mgr.enablePeriodicPeersSave(peersFile, java.time.Duration.ofMillis(200));
        mgr.start();

        try {
            // Ждём первый save tick (initialDelay = 200мс).
            long deadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline && !Files.exists(peersFile)) {
                Thread.sleep(50);
            }
            assertTrue(Files.exists(peersFile),
                    "Periodic save должен пересоздать peers.json в течение интервала");
            // И файл должен быть валидным JSON с нашей записью.
            String json = Files.readString(peersFile);
            assertTrue(json.contains("nodeX"),
                    "Сохранённый файл должен содержать запись из таблицы: " + json);
        } finally {
            mgr.close();
        }
    }

    @Test
    void enablePeriodicSaveAfterStartIsRejected(@TempDir Path tmp) throws Exception {
        // Контракт: enablePeriodicPeersSave должен вызываться ДО start(),
        // потому что start() регистрирует все scheduled-задачи разом и
        // не имеет API для добавления новых.
        NodeConfig config = NodeConfig.testDefaults();
        PeerManager mgr = new PeerManager(config, "selfA", () -> 1);
        mgr.setListenPort(9000);
        mgr.start();
        try {
            assertThrows(IllegalStateException.class,
                    () -> mgr.enablePeriodicPeersSave(
                            tmp.resolve("peers.json"),
                            java.time.Duration.ofSeconds(1)));
        } finally {
            mgr.close();
        }
    }
}
