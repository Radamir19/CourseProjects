package ru.hse.jblockstorage.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileChunkerTest {

    @Test
    void chunkExactMultipleProducesEqualSizedShards() {
        byte[] data = new byte[10];
        for (int i = 0; i < 10; i++) data[i] = (byte) i;

        List<Shard> shards = FileChunker.chunk(data, 5);
        assertEquals(2, shards.size());
        assertEquals(5, shards.get(0).size());
        assertEquals(5, shards.get(1).size());
        assertEquals(0, shards.get(0).index());
        assertEquals(1, shards.get(1).index());
    }

    @Test
    void chunkLastShardIsShorterThanTheRest() {
        byte[] data = new byte[12];
        List<Shard> shards = FileChunker.chunk(data, 5);
        assertEquals(3, shards.size());
        assertEquals(5, shards.get(0).size());
        assertEquals(5, shards.get(1).size());
        assertEquals(2, shards.get(2).size());
    }

    @Test
    void chunkSmallerThanChunkSizeReturnsSingleShard() {
        byte[] data = new byte[3];
        List<Shard> shards = FileChunker.chunk(data, 5);
        assertEquals(1, shards.size());
        assertEquals(3, shards.get(0).size());
    }

    @Test
    void chunkEmptyDataReturnsEmptyList() {
        assertTrue(FileChunker.chunk(new byte[0], 5).isEmpty());
    }

    @Test
    void chunkAndAssembleRoundTripPreservesAllBytes() {
        byte[] data = new byte[12345];
        new SecureRandom().nextBytes(data);

        List<Shard> shards = FileChunker.chunk(data, 1024);
        assertArrayEquals(data, FileChunker.assemble(shards));
    }

    @Test
    void assembleHandlesShardsInArbitraryOrder() {
        byte[] data = new byte[1000];
        new SecureRandom().nextBytes(data);

        List<Shard> shards = new ArrayList<>(FileChunker.chunk(data, 100));
        Collections.shuffle(shards);

        assertArrayEquals(data, FileChunker.assemble(shards));
    }

    @Test
    void assembleDetectsMissingShard() {
        byte[] data = new byte[1000];
        new SecureRandom().nextBytes(data);

        List<Shard> shards = new ArrayList<>(FileChunker.chunk(data, 100));
        shards.remove(3);

        assertThrows(IllegalStateException.class, () -> FileChunker.assemble(shards));
    }

    @Test
    void chunkFromFileMatchesChunkFromBytes(@TempDir Path tempDir) throws IOException {
        byte[] data = new byte[8000];
        new SecureRandom().nextBytes(data);
        Path file = tempDir.resolve("test.bin");
        Files.write(file, data);

        List<Shard> fromBytes = FileChunker.chunk(data, 1024);
        List<Shard> fromFile = FileChunker.chunk(file, 1024);

        assertEquals(fromBytes.size(), fromFile.size());
        for (int i = 0; i < fromBytes.size(); i++) {
            assertArrayEquals(fromBytes.get(i).data(), fromFile.get(i).data());
            assertArrayEquals(fromBytes.get(i).hash(), fromFile.get(i).hash());
            assertEquals(fromBytes.get(i).index(), fromFile.get(i).index());
        }
    }

    @Test
    void chunkAndAssembleFileRoundTrip(@TempDir Path tempDir) throws IOException {
        byte[] data = new byte[5000];
        new SecureRandom().nextBytes(data);
        Path input = tempDir.resolve("input.bin");
        Path output = tempDir.resolve("output.bin");
        Files.write(input, data);

        List<Shard> shards = FileChunker.chunk(input, 700);
        FileChunker.assemble(shards, output);

        assertArrayEquals(data, Files.readAllBytes(output));
    }

    @Test
    void chunkSizeMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> FileChunker.chunk(new byte[10], 0));
        assertThrows(IllegalArgumentException.class, () -> FileChunker.chunk(new byte[10], -1));
    }

    @Test
    void defaultChunkSizeIs512KiB() {
        assertEquals(512 * 1024, FileChunker.DEFAULT_CHUNK_SIZE);
    }
}