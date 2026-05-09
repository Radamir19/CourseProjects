package ru.hse.jblockstorage.gui.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.hse.jblockstorage.config.SeedNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тесты {@link NodeConfigStore} — день 15.
 *
 * <p>Покрывают: пустой/отсутствующий файл, парсинг seeds/port/rf,
 * round-trip через файл, валидацию, идемпотентность add/remove,
 * битый файл, parseSingle для UI, атомарную запись.
 */
class NodeConfigStoreTest {

    @Test
    void missingFileGivesEmptyStore(@TempDir Path tmp) {
        NodeConfigStore store = NodeConfigStore.load(tmp.resolve("config.properties"));
        assertTrue(store.getSeeds().isEmpty());
        assertTrue(store.getListenPort().isEmpty());
        assertTrue(store.getReplicationFactor().isEmpty());
    }

    @Test
    void addSeedThenSeeIt(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("config.properties");
        NodeConfigStore store = NodeConfigStore.load(file);

        SeedNode s = new SeedNode("127.0.0.1", 8081);
        assertTrue(store.addSeed(s));
        assertEquals(List.of(s), store.getSeeds());
        // файл создан
        assertTrue(Files.isRegularFile(file));
    }

    @Test
    void addSeedIsIdempotent(@TempDir Path tmp) throws IOException {
        NodeConfigStore store = NodeConfigStore.load(tmp.resolve("config.properties"));
        SeedNode s = new SeedNode("127.0.0.1", 8081);
        assertTrue(store.addSeed(s));
        assertFalse(store.addSeed(s), "повторный add → false");
        assertEquals(1, store.getSeeds().size());
    }

    @Test
    void removeSeedWorks(@TempDir Path tmp) throws IOException {
        NodeConfigStore store = NodeConfigStore.load(tmp.resolve("config.properties"));
        SeedNode a = new SeedNode("a.example", 8080);
        SeedNode b = new SeedNode("b.example", 8080);
        store.addSeed(a);
        store.addSeed(b);

        assertTrue(store.removeSeed(a));
        assertEquals(List.of(b), store.getSeeds());
        // повторный remove → false
        assertFalse(store.removeSeed(a));
    }

    @Test
    void roundTripThroughFile(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("config.properties");
        NodeConfigStore s1 = NodeConfigStore.load(file);
        s1.addSeed(new SeedNode("alpha.example", 8081));
        s1.addSeed(new SeedNode("beta.example",  8082));
        s1.setListenPort(OptionalInt.of(9090));
        s1.setReplicationFactor(OptionalInt.of(5));

        NodeConfigStore s2 = NodeConfigStore.load(file);
        assertEquals(List.of(
                new SeedNode("alpha.example", 8081),
                new SeedNode("beta.example",  8082)
        ), s2.getSeeds());
        assertEquals(OptionalInt.of(9090), s2.getListenPort());
        assertEquals(OptionalInt.of(5),    s2.getReplicationFactor());
    }

    @Test
    void setSeedsReplacesEverythingAndDedupes(@TempDir Path tmp) throws IOException {
        NodeConfigStore store = NodeConfigStore.load(tmp.resolve("config.properties"));
        store.addSeed(new SeedNode("old.example", 1234));

        SeedNode dup = new SeedNode("x.example", 8080);
        store.setSeeds(List.of(dup, dup, new SeedNode("y.example", 8080)));

        // старый seed снесён, дубликат схлопнут
        assertEquals(2, store.getSeeds().size());
        assertEquals("x.example", store.getSeeds().get(0).host());
        assertEquals("y.example", store.getSeeds().get(1).host());
    }

    @Test
    void corruptedFileGivesEmptyStoreNotCrash(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("config.properties");
        // \uFFFE — не валидный для Properties в ISO-8859-1 без эскейпа,
        // но Properties достаточно толерантен; делаем просто бессмысленный байт-мусор
        Files.write(file, new byte[]{(byte)0x00, (byte)0x01, (byte)0x02});
        // Properties это разберёт как пустые ключи — но не упадёт.
        // Главное: не валим приложение, отдаём пустой стор.
        NodeConfigStore store = NodeConfigStore.load(file);
        assertNotNull(store);
        assertTrue(store.getSeeds().isEmpty());
    }

    @Test
    void invalidSeedsAreSilentlySkipped(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("config.properties");
        Files.writeString(file,
                "seeds=valid.example:8080,no-port,bad:port,127.0.0.1:99999,ok.example:9090\n");
        NodeConfigStore store = NodeConfigStore.load(file);
        // valid + ok = 2 штуки; остальные битые — пропущены
        assertEquals(2, store.getSeeds().size());
        assertEquals("valid.example", store.getSeeds().get(0).host());
        assertEquals("ok.example",    store.getSeeds().get(1).host());
    }

    @Test
    void invalidPortInConfigIsIgnored(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("config.properties");
        Files.writeString(file, "listenPort=99999\n");
        NodeConfigStore store = NodeConfigStore.load(file);
        assertTrue(store.getListenPort().isEmpty());

        Files.writeString(file, "listenPort=not-a-number\n");
        store = NodeConfigStore.load(file);
        assertTrue(store.getListenPort().isEmpty());
    }

    @Test
    void parseSinglePositive() {
        assertEquals("127.0.0.1",
                NodeConfigStore.parseSingle("127.0.0.1:8080").orElseThrow().host());
        assertEquals(8080,
                NodeConfigStore.parseSingle("127.0.0.1:8080").orElseThrow().port());
        // С пробелами — тоже парсится
        assertTrue(NodeConfigStore.parseSingle("  example.com:9000  ").isPresent());
    }

    @Test
    void parseSingleNegative() {
        assertTrue(NodeConfigStore.parseSingle(null).isEmpty());
        assertTrue(NodeConfigStore.parseSingle("").isEmpty());
        assertTrue(NodeConfigStore.parseSingle("no-port-here").isEmpty());
        assertTrue(NodeConfigStore.parseSingle("host:not-a-number").isEmpty());
        assertTrue(NodeConfigStore.parseSingle("host:99999").isEmpty()); // out of range
    }

    @Test
    void atomicWriteDoesNotLeaveTmpFile(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("config.properties");
        NodeConfigStore store = NodeConfigStore.load(file);
        store.addSeed(new SeedNode("127.0.0.1", 8080));

        Path tmpFile = tmp.resolve("config.properties.tmp");
        assertFalse(Files.exists(tmpFile),
                "tmp-файл должен быть удалён после атомарной записи");
    }

    @Test
    void setListenPortValidates(@TempDir Path tmp) throws IOException {
        NodeConfigStore store = NodeConfigStore.load(tmp.resolve("config.properties"));
        assertThrows(IllegalArgumentException.class,
                () -> store.setListenPort(OptionalInt.of(0)));
        assertThrows(IllegalArgumentException.class,
                () -> store.setListenPort(OptionalInt.of(70000)));
        // empty — OK
        store.setListenPort(OptionalInt.empty());
        assertTrue(store.getListenPort().isEmpty());
    }
}
