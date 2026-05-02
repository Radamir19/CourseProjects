package ru.hse.jblockstorage.blockchain;

import org.junit.jupiter.api.Test;
import ru.hse.jblockstorage.crypto.KeyManager;

import java.security.KeyPair;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тесты методов поиска транзакций, добавленных в день 7.
 * Существующие тесты {@code BlockchainTest} остаются валидными — здесь
 * только дополнения для {@code findByTxId}, {@code listByOwner},
 * {@code allTransactions}.
 */
class BlockchainQueryTest {

    @Test
    void findByTxIdReturnsTransactionWhenPresent() {
        KeyPair owner = KeyManager.generateRsaKeyPair();
        String ownerKey = KeyManager.publicKeyToBase64(owner.getPublic());

        Blockchain chain = new Blockchain();
        Transaction tx = new Transaction(ownerKey, "doc.txt", 100, "merkle-root-1");
        tx.sign(owner.getPrivate());
        chain.addBlock(List.of(tx));

        Optional<Transaction> found = chain.findByTxId(tx.getId());
        assertTrue(found.isPresent());
        assertEquals(tx.getId(), found.get().getId());
        assertEquals("doc.txt", found.get().getFileName());
    }

    @Test
    void findByTxIdReturnsEmptyForUnknownId() {
        Blockchain chain = new Blockchain();
        assertTrue(chain.findByTxId("nonexistent-id").isEmpty());
    }

    @Test
    void listByOwnerFiltersCorrectly() {
        KeyPair alice = KeyManager.generateRsaKeyPair();
        KeyPair bob   = KeyManager.generateRsaKeyPair();
        String aliceKey = KeyManager.publicKeyToBase64(alice.getPublic());
        String bobKey   = KeyManager.publicKeyToBase64(bob.getPublic());

        Blockchain chain = new Blockchain();

        Transaction aliceTx1 = new Transaction(aliceKey, "alice1.txt", 10, "m1");
        aliceTx1.sign(alice.getPrivate());
        Transaction bobTx = new Transaction(bobKey, "bob.txt", 20, "m2");
        bobTx.sign(bob.getPrivate());
        chain.addBlock(List.of(aliceTx1, bobTx));

        Transaction aliceTx2 = new Transaction(aliceKey, "alice2.txt", 30, "m3");
        aliceTx2.sign(alice.getPrivate());
        chain.addBlock(List.of(aliceTx2));

        List<Transaction> aliceFiles = chain.listByOwner(aliceKey);
        assertEquals(2, aliceFiles.size(), "У Alice 2 файла");
        assertTrue(aliceFiles.stream().anyMatch(t -> "alice1.txt".equals(t.getFileName())));
        assertTrue(aliceFiles.stream().anyMatch(t -> "alice2.txt".equals(t.getFileName())));

        List<Transaction> bobFiles = chain.listByOwner(bobKey);
        assertEquals(1, bobFiles.size());
        assertEquals("bob.txt", bobFiles.get(0).getFileName());
    }

    @Test
    void listByOwnerReturnsEmptyForUnknownOwner() {
        Blockchain chain = new Blockchain();
        assertTrue(chain.listByOwner("unknown-key").isEmpty());
    }

    @Test
    void allTransactionsAggregatesAcrossBlocks() {
        KeyPair owner = KeyManager.generateRsaKeyPair();
        String ownerKey = KeyManager.publicKeyToBase64(owner.getPublic());

        Blockchain chain = new Blockchain();
        for (int i = 0; i < 3; i++) {
            Transaction tx = new Transaction(ownerKey, "f" + i + ".bin", i * 10, "m" + i);
            tx.sign(owner.getPrivate());
            chain.addBlock(List.of(tx));
        }

        List<Transaction> all = chain.allTransactions();
        assertEquals(3, all.size(),
                "Должны быть 3 транзакции (genesis-блок не содержит транзакций)");
    }
}
