package ru.hse.jblockstorage.blockchain;

import org.junit.jupiter.api.Test;
import ru.hse.jblockstorage.crypto.KeyManager;

import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockchainTest {

    private static final KeyPair KP = KeyManager.generateRsaKeyPair();

    private static Transaction signedTx(String fileName) {
        Transaction tx = new Transaction(
                KeyManager.publicKeyToBase64(KP.getPublic()),
                fileName, 100L, "r".repeat(64));
        tx.sign(KP.getPrivate());
        return tx;
    }

    @Test
    void newBlockchainHasOnlyGenesisBlock() {
        Blockchain bc = new Blockchain();
        assertEquals(1, bc.height());
        assertEquals(0, bc.getLatestBlock().getIndex());
        assertTrue(bc.validate());
    }

    @Test
    void addingBlockIncreasesHeight() {
        Blockchain bc = new Blockchain();
        bc.addBlock(List.of(signedTx("a.txt")));
        bc.addBlock(List.of(signedTx("b.txt")));
        assertEquals(3, bc.height());
        assertTrue(bc.validate());
    }

    @Test
    void eachBlockReferencesPreviousByHash() {
        Blockchain bc = new Blockchain();
        Block b1 = bc.addBlock(List.of(signedTx("a.txt")));
        Block b2 = bc.addBlock(List.of(signedTx("b.txt")));

        assertEquals(bc.getBlock(0).getHash(), b1.getPrevHash());
        assertEquals(b1.getHash(), b2.getPrevHash());
    }

    @Test
    void addingUnsignedTransactionRejected() {
        Blockchain bc = new Blockchain();
        Transaction unsigned = new Transaction(
                KeyManager.publicKeyToBase64(KP.getPublic()),
                "x.txt", 1L, "r".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> bc.addBlock(List.of(unsigned)));
    }

    @Test
    void tamperingWithMiddleBlockBreaksValidation() {
        Blockchain bc = new Blockchain();
        bc.addBlock(List.of(signedTx("a.txt")));
        bc.addBlock(List.of(signedTx("b.txt")));
        bc.addBlock(List.of(signedTx("c.txt")));

        // Меняем индекс среднего блока — хеш блока перестанет сходиться,
        // и связь со следующим блоком разорвётся.
        bc.getBlock(2).setIndex(99);
        assertFalse(bc.validate());
    }

    @Test
    void replaceIfLongerAcceptsLongerValidChain() {
        Blockchain ours = new Blockchain();
        ours.addBlock(List.of(signedTx("a.txt")));
        // Высота 2

        // Параллельно строим более длинную цепь
        Blockchain theirs = new Blockchain();
        theirs.addBlock(List.of(signedTx("x.txt")));
        theirs.addBlock(List.of(signedTx("y.txt")));
        theirs.addBlock(List.of(signedTx("z.txt")));
        // Высота 4

        assertTrue(ours.replaceIfLonger(theirs.getBlocks()));
        assertEquals(4, ours.height());
        assertTrue(ours.validate());
    }

    @Test
    void replaceIfLongerRejectsShorterChain() {
        Blockchain ours = new Blockchain();
        ours.addBlock(List.of(signedTx("a.txt")));
        ours.addBlock(List.of(signedTx("b.txt")));

        Blockchain shorter = new Blockchain();
        shorter.addBlock(List.of(signedTx("c.txt")));

        assertFalse(ours.replaceIfLonger(shorter.getBlocks()));
        assertEquals(3, ours.height());
    }

    @Test
    void replaceIfLongerRejectsEqualLengthChain() {
        Blockchain ours = new Blockchain();
        ours.addBlock(List.of(signedTx("a.txt")));

        Blockchain same = new Blockchain();
        same.addBlock(List.of(signedTx("z.txt")));

        assertFalse(ours.replaceIfLonger(same.getBlocks()));
    }

    @Test
    void replaceIfLongerRejectsInvalidChain() {
        Blockchain ours = new Blockchain();
        ours.addBlock(List.of(signedTx("a.txt")));

        // Строим длинную, но битую цепь
        Blockchain badSource = new Blockchain();
        badSource.addBlock(List.of(signedTx("x.txt")));
        badSource.addBlock(List.of(signedTx("y.txt")));
        badSource.addBlock(List.of(signedTx("z.txt")));
        List<Block> bad = new ArrayList<>(badSource.getBlocks());
        bad.get(2).setIndex(999); // ломаем хеш

        assertFalse(ours.replaceIfLonger(bad));
        assertEquals(2, ours.height());
    }

    @Test
    void canRestoreBlockchainFromBlockList() {
        Blockchain original = new Blockchain();
        original.addBlock(List.of(signedTx("a.txt")));
        original.addBlock(List.of(signedTx("b.txt")));

        Blockchain restored = new Blockchain(new ArrayList<>(original.getBlocks()));
        assertEquals(original.height(), restored.height());
        assertTrue(restored.validate());
    }
}
