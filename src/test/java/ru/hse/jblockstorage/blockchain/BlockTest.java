package ru.hse.jblockstorage.blockchain;

import org.junit.jupiter.api.Test;
import ru.hse.jblockstorage.crypto.KeyManager;

import java.security.KeyPair;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockTest {

    private static Transaction signedTx(KeyPair kp, String fileName) {
        Transaction tx = new Transaction(
                KeyManager.publicKeyToBase64(kp.getPublic()),
                fileName, 100L, "r".repeat(64));
        tx.sign(kp.getPrivate());
        return tx;
    }

    @Test
    void genesisBlockHasIndexZeroAndZeroPrevHash() {
        Block genesis = Block.genesis();
        assertEquals(0, genesis.getIndex());
        assertEquals(Block.GENESIS_PREV_HASH, genesis.getPrevHash());
        assertTrue(genesis.getTransactions().isEmpty());
        assertTrue(genesis.validateHash());
    }

    @Test
    void newBlockHashIsComputedAutomatically() {
        Block b = Block.create(1, "a".repeat(64), List.of());
        assertNotNull(b.getHash());
        assertEquals(64, b.getHash().length()); // hex SHA-256
        assertTrue(b.validateHash());
    }

    @Test
    void modifyingFieldAfterCreationBreaksHash() {
        Block b = Block.create(1, "a".repeat(64), List.of());
        b.setIndex(99);
        assertFalse(b.validateHash());
    }

    @Test
    void blockWithSignedTransactionsValidates() {
        KeyPair kp = KeyManager.generateRsaKeyPair();
        Block b = Block.create(1, "a".repeat(64),
                List.of(signedTx(kp, "a.txt"), signedTx(kp, "b.txt")));
        assertTrue(b.validate());
    }

    @Test
    void blockWithUnsignedTransactionDoesNotValidate() {
        KeyPair kp = KeyManager.generateRsaKeyPair();
        Transaction unsigned = new Transaction(
                KeyManager.publicKeyToBase64(kp.getPublic()),
                "x.txt", 1L, "r".repeat(64));
        Block b = Block.create(1, "a".repeat(64), List.of(unsigned));
        assertFalse(b.validate());
    }

    @Test
    void identicalContentProducesIdenticalHash() {
        long ts = 1234567890L;
        Block b1 = new Block(1, ts, "a".repeat(64), List.of(), 0L);
        Block b2 = new Block(1, ts, "a".repeat(64), List.of(), 0L);
        assertEquals(b1.getHash(), b2.getHash());
    }

    @Test
    void differentPrevHashProducesDifferentHash() {
        long ts = 1L;
        Block b1 = new Block(1, ts, "a".repeat(64), List.of(), 0L);
        Block b2 = new Block(1, ts, "b".repeat(64), List.of(), 0L);
        assertNotEquals(b1.getHash(), b2.getHash());
    }
}
