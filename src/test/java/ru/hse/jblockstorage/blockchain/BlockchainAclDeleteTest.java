package ru.hse.jblockstorage.blockchain;

import org.junit.jupiter.api.Test;
import ru.hse.jblockstorage.crypto.KeyManager;

import java.security.KeyPair;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тесты методов Blockchain дня 9: {@link Blockchain#findAclFor},
 * {@link Blockchain#isDeleted}, {@link Blockchain#listByRecipient},
 * а также проверка, что {@link Blockchain#listByOwner} корректно
 * фильтрует удалённые файлы.
 */
class BlockchainAclDeleteTest {

    @Test
    void findAclForReturnsTransactionWhenPresent() {
        KeyPair alice = KeyManager.generateRsaKeyPair();
        KeyPair bob   = KeyManager.generateRsaKeyPair();
        String aliceKey = KeyManager.publicKeyToBase64(alice.getPublic());
        String bobKey   = KeyManager.publicKeyToBase64(bob.getPublic());

        Blockchain chain = new Blockchain();
        Transaction upload = new Transaction(aliceKey, "secret.txt", 100, "merkle");
        upload.sign(alice.getPrivate());
        chain.addBlock(List.of(upload));

        Transaction acl = Transaction.newAcl(aliceKey, upload.getId(), bobKey, "encrypted");
        acl.sign(alice.getPrivate());
        chain.addBlock(List.of(acl));

        Optional<Transaction> found = chain.findAclFor(upload.getId(), bobKey);
        assertTrue(found.isPresent());
        assertEquals(acl.getId(), found.get().getId());
    }

    @Test
    void findAclForReturnsEmptyWhenAbsent() {
        KeyPair alice = KeyManager.generateRsaKeyPair();
        String aliceKey = KeyManager.publicKeyToBase64(alice.getPublic());
        Blockchain chain = new Blockchain();
        assertTrue(chain.findAclFor("any-tx", aliceKey).isEmpty());
    }

    @Test
    void findAclForDistinguishesRecipients() {
        // Алиса расшарила Бобу, но не Чарли. Запрос для Чарли должен быть пустым.
        KeyPair alice   = KeyManager.generateRsaKeyPair();
        KeyPair bob     = KeyManager.generateRsaKeyPair();
        KeyPair charlie = KeyManager.generateRsaKeyPair();
        String aliceKey   = KeyManager.publicKeyToBase64(alice.getPublic());
        String bobKey     = KeyManager.publicKeyToBase64(bob.getPublic());
        String charlieKey = KeyManager.publicKeyToBase64(charlie.getPublic());

        Blockchain chain = new Blockchain();
        Transaction upload = new Transaction(aliceKey, "doc.txt", 50, "m");
        upload.sign(alice.getPrivate());
        chain.addBlock(List.of(upload));

        Transaction aclForBob = Transaction.newAcl(aliceKey, upload.getId(), bobKey, "enc");
        aclForBob.sign(alice.getPrivate());
        chain.addBlock(List.of(aclForBob));

        assertTrue(chain.findAclFor(upload.getId(), bobKey).isPresent(),
                "Боб должен видеть свой ACL");
        assertTrue(chain.findAclFor(upload.getId(), charlieKey).isEmpty(),
                "Чарли — не должен");
    }

    @Test
    void findAclForReturnsLatestWhenMultiple() {
        KeyPair alice = KeyManager.generateRsaKeyPair();
        KeyPair bob   = KeyManager.generateRsaKeyPair();
        String aliceKey = KeyManager.publicKeyToBase64(alice.getPublic());
        String bobKey   = KeyManager.publicKeyToBase64(bob.getPublic());

        Blockchain chain = new Blockchain();
        Transaction upload = new Transaction(aliceKey, "doc.txt", 50, "m");
        upload.sign(alice.getPrivate());
        chain.addBlock(List.of(upload));

        // Две разных ACL — например, при будущей "повторной выдаче" с обновлённым ключом.
        Transaction acl1 = Transaction.newAcl(aliceKey, upload.getId(), bobKey, "enc-old");
        acl1.sign(alice.getPrivate());
        chain.addBlock(List.of(acl1));

        Transaction acl2 = Transaction.newAcl(aliceKey, upload.getId(), bobKey, "enc-new");
        acl2.sign(alice.getPrivate());
        chain.addBlock(List.of(acl2));

        Transaction found = chain.findAclFor(upload.getId(), bobKey).orElseThrow();
        assertEquals("enc-new", found.getEncryptedAesKeyForRecipient(),
                "При нескольких ACL должна возвращаться самая свежая (по позиции в цепи)");
    }

    @Test
    void isDeletedTrueAfterOwnerDelete() {
        KeyPair alice = KeyManager.generateRsaKeyPair();
        String aliceKey = KeyManager.publicKeyToBase64(alice.getPublic());

        Blockchain chain = new Blockchain();
        Transaction upload = new Transaction(aliceKey, "f.txt", 100, "m");
        upload.sign(alice.getPrivate());
        chain.addBlock(List.of(upload));

        assertFalse(chain.isDeleted(upload.getId()), "До DELETE должно быть false");

        Transaction del = Transaction.newDelete(aliceKey, upload.getId());
        del.sign(alice.getPrivate());
        chain.addBlock(List.of(del));

        assertTrue(chain.isDeleted(upload.getId()), "После DELETE должно стать true");
    }

    @Test
    void isDeletedFalseForUnknownTxId() {
        Blockchain chain = new Blockchain();
        assertFalse(chain.isDeleted("nonexistent-tx-id"));
    }

    @Test
    void deleteFromNonOwnerIsIgnored() {
        // Если кто-то посторонний (не владелец) положил DELETE-транзакцию
        // в цепь — она должна игнорироваться. Семантически только владелец
        // имеет право удалять.
        KeyPair alice  = KeyManager.generateRsaKeyPair();
        KeyPair attacker = KeyManager.generateRsaKeyPair();
        String aliceKey   = KeyManager.publicKeyToBase64(alice.getPublic());
        String attackerKey = KeyManager.publicKeyToBase64(attacker.getPublic());

        Blockchain chain = new Blockchain();
        Transaction upload = new Transaction(aliceKey, "f.txt", 100, "m");
        upload.sign(alice.getPrivate());
        chain.addBlock(List.of(upload));

        // attacker подписывает DELETE на чужой файл (от своего имени) и шлёт.
        Transaction fakeDelete = Transaction.newDelete(attackerKey, upload.getId());
        fakeDelete.sign(attacker.getPrivate());
        // Даже если этот DELETE прошёл валидацию подписи (он валиден сам по себе)
        // — Blockchain.isDeleted должен его проигнорировать на уровне семантики.
        chain.addBlock(List.of(fakeDelete));

        assertFalse(chain.isDeleted(upload.getId()),
                "DELETE от не-владельца не должен помечать файл как удалённый");
    }

    @Test
    void listByOwnerHidesDeleted() {
        KeyPair alice = KeyManager.generateRsaKeyPair();
        String aliceKey = KeyManager.publicKeyToBase64(alice.getPublic());

        Blockchain chain = new Blockchain();
        Transaction upload1 = new Transaction(aliceKey, "alive.txt", 10, "m1");
        upload1.sign(alice.getPrivate());
        Transaction upload2 = new Transaction(aliceKey, "deleted.txt", 20, "m2");
        upload2.sign(alice.getPrivate());
        chain.addBlock(List.of(upload1, upload2));

        Transaction del = Transaction.newDelete(aliceKey, upload2.getId());
        del.sign(alice.getPrivate());
        chain.addBlock(List.of(del));

        List<Transaction> alive = chain.listByOwner(aliceKey);
        assertEquals(1, alive.size(), "Должна остаться только одна (не удалённая) запись");
        assertEquals("alive.txt", alive.get(0).getFileName());
    }

    @Test
    void listByOwnerExcludesAclTransactions() {
        // ACL-транзакции — формально записи в блокчейне, но в "Мои файлы"
        // они не должны попадать. Это файлы, которые я расшарил, не
        // которыми я владею.
        KeyPair alice = KeyManager.generateRsaKeyPair();
        KeyPair bob   = KeyManager.generateRsaKeyPair();
        String aliceKey = KeyManager.publicKeyToBase64(alice.getPublic());
        String bobKey   = KeyManager.publicKeyToBase64(bob.getPublic());

        Blockchain chain = new Blockchain();
        Transaction upload = new Transaction(aliceKey, "doc.txt", 50, "m");
        upload.sign(alice.getPrivate());
        chain.addBlock(List.of(upload));

        Transaction acl = Transaction.newAcl(aliceKey, upload.getId(), bobKey, "enc");
        acl.sign(alice.getPrivate());
        chain.addBlock(List.of(acl));

        List<Transaction> mine = chain.listByOwner(aliceKey);
        assertEquals(1, mine.size(), "В 'Мои файлы' должна быть только UPLOAD-TX");
        assertEquals(Transaction.Kind.UPLOAD, mine.get(0).getKind());
    }

    @Test
    void listByRecipientReturnsSharedFiles() {
        KeyPair alice = KeyManager.generateRsaKeyPair();
        KeyPair bob   = KeyManager.generateRsaKeyPair();
        String aliceKey = KeyManager.publicKeyToBase64(alice.getPublic());
        String bobKey   = KeyManager.publicKeyToBase64(bob.getPublic());

        Blockchain chain = new Blockchain();
        Transaction upload1 = new Transaction(aliceKey, "shared.txt", 10, "m1");
        upload1.sign(alice.getPrivate());
        Transaction upload2 = new Transaction(aliceKey, "private.txt", 20, "m2");
        upload2.sign(alice.getPrivate());
        chain.addBlock(List.of(upload1, upload2));

        Transaction acl = Transaction.newAcl(aliceKey, upload1.getId(), bobKey, "enc");
        acl.sign(alice.getPrivate());
        chain.addBlock(List.of(acl));

        List<Transaction> bobsAccessible = chain.listByRecipient(bobKey);
        assertEquals(1, bobsAccessible.size(), "Бобу расшарили один файл");
        assertEquals("shared.txt", bobsAccessible.get(0).getFileName());
    }

    @Test
    void listByRecipientHidesDeletedFiles() {
        KeyPair alice = KeyManager.generateRsaKeyPair();
        KeyPair bob   = KeyManager.generateRsaKeyPair();
        String aliceKey = KeyManager.publicKeyToBase64(alice.getPublic());
        String bobKey   = KeyManager.publicKeyToBase64(bob.getPublic());

        Blockchain chain = new Blockchain();
        Transaction upload = new Transaction(aliceKey, "f.txt", 10, "m");
        upload.sign(alice.getPrivate());
        chain.addBlock(List.of(upload));

        Transaction acl = Transaction.newAcl(aliceKey, upload.getId(), bobKey, "enc");
        acl.sign(alice.getPrivate());
        chain.addBlock(List.of(acl));

        Transaction del = Transaction.newDelete(aliceKey, upload.getId());
        del.sign(alice.getPrivate());
        chain.addBlock(List.of(del));

        // У Боба остаётся ACL, но файл удалён — listByRecipient должен скрыть.
        assertTrue(chain.listByRecipient(bobKey).isEmpty(),
                "Удалённые файлы не должны попадать в 'расшаренное со мной'");
    }
}
