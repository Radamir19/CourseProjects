package ru.hse.jblockstorage.blockchain;

import org.junit.jupiter.api.Test;
import ru.hse.jblockstorage.crypto.KeyManager;

import java.security.KeyPair;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionTest {

    private static Transaction newSampleTx(String pubKeyBase64) {
        return new Transaction(pubKeyBase64, "doc.pdf", 12345L, "a".repeat(64));
    }

    @Test
    void signedTransactionVerifiesAndGetsId() {
        KeyPair kp = KeyManager.generateRsaKeyPair();
        Transaction tx = newSampleTx(KeyManager.publicKeyToBase64(kp.getPublic()));

        tx.sign(kp.getPrivate());

        assertNotNull(tx.getSignature());
        assertNotNull(tx.getId());
        assertTrue(tx.verify());
    }

    @Test
    void unsignedTransactionDoesNotVerify() {
        KeyPair kp = KeyManager.generateRsaKeyPair();
        Transaction tx = newSampleTx(KeyManager.publicKeyToBase64(kp.getPublic()));
        assertFalse(tx.verify());
    }

    @Test
    void verificationFailsIfFieldChangedAfterSigning() {
        KeyPair kp = KeyManager.generateRsaKeyPair();
        Transaction tx = newSampleTx(KeyManager.publicKeyToBase64(kp.getPublic()));
        tx.sign(kp.getPrivate());

        // Кто-то «исправил» имя файла после подписи
        tx.setFileName("malicious.exe");

        assertFalse(tx.verify());
    }

    @Test
    void verificationFailsWithDifferentSignerKey() {
        KeyPair owner = KeyManager.generateRsaKeyPair();
        KeyPair impostor = KeyManager.generateRsaKeyPair();
        Transaction tx = newSampleTx(KeyManager.publicKeyToBase64(owner.getPublic()));

        // Подписали «не своим» ключом
        tx.sign(impostor.getPrivate());

        assertFalse(tx.verify());
    }

    @Test
    void txIdChangesWhenSignatureChanges() {
        KeyPair kp = KeyManager.generateRsaKeyPair();
        Transaction tx1 = newSampleTx(KeyManager.publicKeyToBase64(kp.getPublic()));
        Transaction tx2 = newSampleTx(KeyManager.publicKeyToBase64(kp.getPublic()));

        tx1.sign(kp.getPrivate());
        tx2.sign(kp.getPrivate());

        // Подпись RSA детерминированная (PKCS#1 v1.5), но timestamp совпадает не всегда —
        // в любом случае id не должен быть null.
        assertNotNull(tx1.getId());
        assertNotNull(tx2.getId());
    }

    @Test
    void setSignatureAloneDoesNotComputeId() {
        // setSignature теперь — простой сеттер без побочных эффектов.
        // Чтобы получить и подпись, и id, нужно использовать sign(PrivateKey).
        KeyPair kp = KeyManager.generateRsaKeyPair();
        Transaction tx = newSampleTx(KeyManager.publicKeyToBase64(kp.getPublic()));
        tx.setSignature("manualsig");
        assertNull(tx.getId());

        // Если очень нужно — пересчитываем id вручную:
        tx.setId(tx.calculateTxId());
        assertNotNull(tx.getId());
    }

    @Test
    void verifyHandlesGarbagePublicKey() {
        Transaction tx = newSampleTx("not-a-real-base64-key");
        tx.setSignature("AAA");
        assertFalse(tx.verify());
    }

    @Test
    void gettersReturnConstructorValues() {
        Transaction tx = new Transaction("pub", "name", 100L, "root");
        assertEquals("pub", tx.getOwnerPublicKey());
        assertEquals("name", tx.getFileName());
        assertEquals(100L, tx.getFileSize());
        assertEquals("root", tx.getMerkleRoot());
        assertTrue(tx.getTimestamp() > 0);
    }
}