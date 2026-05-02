package ru.hse.jblockstorage.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тесты {@link ConfigLoader} — простого YAML-парсера.
 */
class ConfigLoaderTest {

    @Test
    void parsesAllScalars() {
        String yaml = """
                listenPort: 9000
                pingIntervalSeconds: 5
                peerTimeoutSeconds: 20
                gossipIntervalSeconds: 7
                maxPeers: 32
                protocolVersion: 2
                """;

        NodeConfig cfg = ConfigLoader.fromString(yaml);
        assertEquals(9000, cfg.getListenPort());
        assertEquals(Duration.ofSeconds(5), cfg.getPingInterval());
        assertEquals(Duration.ofSeconds(20), cfg.getPeerTimeout());
        assertEquals(Duration.ofSeconds(7), cfg.getGossipInterval());
        assertEquals(32, cfg.getMaxPeers());
        assertEquals(2, cfg.getProtocolVersion());
    }

    @Test
    void parsesSeedNodeList() {
        String yaml = """
                listenPort: 8080
                seedNodes:
                  - 127.0.0.1:8081
                  - 192.168.1.5:9000
                """;

        NodeConfig cfg = ConfigLoader.fromString(yaml);
        List<SeedNode> seeds = cfg.getSeedNodes();
        assertEquals(2, seeds.size());
        assertEquals(new SeedNode("127.0.0.1", 8081), seeds.get(0));
        assertEquals(new SeedNode("192.168.1.5", 9000), seeds.get(1));
    }

    @Test
    void emptyConfigYieldsDefaults() {
        NodeConfig cfg = ConfigLoader.fromString("");
        NodeConfig expected = NodeConfig.defaults();
        assertEquals(expected.getListenPort(), cfg.getListenPort());
        assertEquals(expected.getPingInterval(), cfg.getPingInterval());
        assertEquals(expected.getMaxPeers(), cfg.getMaxPeers());
        assertTrue(cfg.getSeedNodes().isEmpty());
    }

    @Test
    void commentsAndBlankLinesAreIgnored() {
        String yaml = """
                # это узел с кастомным портом
                listenPort: 7777     # inline-комментарий

                # пустые строки сверху и снизу
                maxPeers: 8

                """;

        NodeConfig cfg = ConfigLoader.fromString(yaml);
        assertEquals(7777, cfg.getListenPort());
        assertEquals(8, cfg.getMaxPeers());
    }

    @Test
    void emptySeedNodesListIsAllowed() {
        String yaml = """
                listenPort: 8080
                seedNodes:
                """;
        NodeConfig cfg = ConfigLoader.fromString(yaml);
        assertTrue(cfg.getSeedNodes().isEmpty());
    }

    @Test
    void invalidIntegerThrows() {
        String yaml = "listenPort: notanumber\n";
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.fromString(yaml));
    }

    @Test
    void seedWithoutPortThrows() {
        String yaml = """
                seedNodes:
                  - 127.0.0.1
                """;
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.fromString(yaml));
    }

    @Test
    void missingColonThrows() {
        String yaml = "listenPort 8080\n"; // нет двоеточия
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.fromString(yaml));
    }

    @Test
    void readsFromFile(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("config.yaml");
        Files.writeString(file, """
                listenPort: 5555
                seedNodes:
                  - localhost:6000
                """);
        NodeConfig cfg = ConfigLoader.fromFile(file);
        assertEquals(5555, cfg.getListenPort());
        assertEquals(1, cfg.getSeedNodes().size());
    }

    @Test
    void invalidPortInScalarFails() {
        String yaml = "listenPort: 99999\n";
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.fromString(yaml));
    }

    @Test
    void seedHostPortRejectsBadPort() {
        String yaml = """
                seedNodes:
                  - host:99999
                """;
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.fromString(yaml));
    }
}
