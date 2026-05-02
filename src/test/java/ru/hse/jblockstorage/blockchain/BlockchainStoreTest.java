package ru.hse.jblockstorage.blockchain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.hse.jblockstorage.crypto.KeyManager;

import java.nio.file.Path;
import java.security.KeyPair;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockchainStoreTest {

    private static final KeyPair KP = KeyManager.generateRsaKeyPair();

    private static Transaction signedTx(String fileName) {
        Transaction tx = new Transaction(
                KeyManager.publicKeyToBase64(KP.getPublic()),
                fileName, 100L, "r".repeat(64));
        tx.sign(KP.getPrivate());
        return tx;
    }

    @Test
    void singleBlockRoundTrip(@TempDir Path tempDir) {
        Block original = Block.create(0, Block.GENESIS_PREV_HASH, List.of());
        try (BlockchainStore store = BlockchainStore.open(tempDir.resolve("db"))) {
            store.saveBlock(original);
            Block loaded = store.loadBlock(0);
            assertNotNull(loaded);
            assertEquals(original.getHash(), loaded.getHash());
            assertEquals(original.getIndex(), loaded.getIndex());
            assertEquals(original.getPrevHash(), loaded.getPrevHash());
            assertTrue(loaded.validateHash());
        }
    }

    @Test
    void loadingMissingBlockReturnsNull(@TempDir Path tempDir) {
        try (BlockchainStore store = BlockchainStore.open(tempDir.resolve("db"))) {
            assertNull(store.loadBlock(42));
        }
    }

    @Test
    void chainSurvivesReopenWithFullValidation(@TempDir Path tempDir) {
        Path dbDir = tempDir.resolve("db");

        Blockchain bc = new Blockchain();
        bc.addBlock(List.of(signedTx("a.txt")));
        bc.addBlock(List.of(signedTx("b.txt")));
        bc.addBlock(List.of(signedTx("c.txt")));

        try (BlockchainStore store = BlockchainStore.open(dbDir)) {
            store.saveChain(bc.getBlocks());
        }

        // Переоткрываем — данные должны сохраниться
        try (BlockchainStore store = BlockchainStore.open(dbDir)) {
            List<Block> loaded = store.loadAll();
            assertEquals(bc.height(), loaded.size());
            for (int i = 0; i < bc.height(); i++) {
                assertEquals(bc.getBlock(i).getHash(), loaded.get(i).getHash());
            }
            // Восстановленная цепь должна быть валидной
            Blockchain restored = new Blockchain(loaded);
            assertTrue(restored.validate());
        }
    }

    @Test
    void blocksReturnInIndexOrderEvenWhenSavedOutOfOrder(@TempDir Path tempDir) {
        try (BlockchainStore store = BlockchainStore.open(tempDir.resolve("db"))) {
            // Сохраняем в обратном порядке
            store.saveBlock(new Block(2, 200L, "x".repeat(64), List.of(), 0L));
            store.saveBlock(new Block(0, 0L, Block.GENESIS_PREV_HASH, List.of(), 0L));
            store.saveBlock(new Block(1, 100L, "y".repeat(64), List.of(), 0L));

            List<Block> loaded = store.loadAll();
            assertEquals(3, loaded.size());
            assertEquals(0, loaded.get(0).getIndex());
            assertEquals(1, loaded.get(1).getIndex());
            assertEquals(2, loaded.get(2).getIndex());
        }
    }

    @Test
    void overwritingBlockKeepsLatestVersion(@TempDir Path tempDir) {
        try (BlockchainStore store = BlockchainStore.open(tempDir.resolve("db"))) {
            Block first = new Block(0, 100L, Block.GENESIS_PREV_HASH, List.of(), 0L);
            Block second = new Block(0, 200L, Block.GENESIS_PREV_HASH, List.of(), 0L);
            store.saveBlock(first);
            store.saveBlock(second);

            Block loaded = store.loadBlock(0);
            assertEquals(200L, loaded.getTimestamp());
        }
    }
}
