package ru.hse.jblockstorage.blockchain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.hse.jblockstorage.crypto.KeyManager;

import java.security.KeyPair;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit-тесты для расширений {@link Transaction} в дне 9:
 * новые типы транзакций (ACL, DELETE), их подписи и сериализация.
 */
class TransactionAclDeleteTest {

    @Test
    void uploadKindByDefault() {
        Transaction tx = new Transaction("owner-key", "f.txt", 100, "merkle");
        assertEquals(Transaction.Kind.UPLOAD, tx.getKind(),
                "Конструктор по умолчанию должен давать UPLOAD-Kind");
    }

    @Test
    void aclTransactionFactoryFillsFields() {
        Transaction acl = Transaction.newAcl(
                "owner-key", "original-tx-id", "recipient-key", "encrypted-aes");
        assertEquals(Transaction.Kind.ACL, acl.getKind());
        assertEquals("owner-key", acl.getOwnerPublicKey());
        assertEquals("original-tx-id", acl.getReferencedTxId());
        assertEquals("recipient-key", acl.getRecipientPublicKey());
        assertEquals("encrypted-aes", acl.getEncryptedAesKeyForRecipient());
        // Поля UPLOAD'а не релевантны и должны быть пустыми/нулевыми.
        assertEquals(0L, acl.getFileSize());
    }

    @Test
    void deleteTransactionFactoryFillsFields() {
        Transaction del = Transaction.newDelete("owner-key", "original-tx-id");
        assertEquals(Transaction.Kind.DELETE, del.getKind());
        assertEquals("owner-key", del.getOwnerPublicKey());
        assertEquals("original-tx-id", del.getReferencedTxId());
        assertNull(del.getRecipientPublicKey());
        assertNull(del.getEncryptedAesKeyForRecipient());
    }

    @Test
    void aclTransactionSignAndVerify() {
        KeyPair owner = KeyManager.generateRsaKeyPair();
        String ownerKey = KeyManager.publicKeyToBase64(owner.getPublic());
        Transaction acl = Transaction.newAcl(ownerKey, "tx-id", "recip-key", "enc-aes");
        acl.sign(owner.getPrivate());

        assertNotNull(acl.getSignature());
        assertNotNull(acl.getId());
        assertTrue(acl.verify(), "Свежеподписанная ACL должна верифицироваться");
    }

    @Test
    void deleteTransactionSignAndVerify() {
        KeyPair owner = KeyManager.generateRsaKeyPair();
        String ownerKey = KeyManager.publicKeyToBase64(owner.getPublic());
        Transaction del = Transaction.newDelete(ownerKey, "tx-id");
        del.sign(owner.getPrivate());

        assertTrue(del.verify());
    }

    @Test
    void tamperedAclFailsVerification() {
        KeyPair owner = KeyManager.generateRsaKeyPair();
        String ownerKey = KeyManager.publicKeyToBase64(owner.getPublic());
        Transaction acl = Transaction.newAcl(ownerKey, "tx-id", "recip", "enc-aes");
        acl.sign(owner.getPrivate());

        // Меняем recipient — подпись должна перестать сходиться.
        acl.setRecipientPublicKey("different-recipient");
        assertFalse(acl.verify(),
                "После подмены recipient'а подпись не должна верифицироваться");
    }

    @Test
    void tamperedEncryptedAesKeyFailsVerification() {
        KeyPair owner = KeyManager.generateRsaKeyPair();
        String ownerKey = KeyManager.publicKeyToBase64(owner.getPublic());
        Transaction acl = Transaction.newAcl(ownerKey, "tx-id", "recip", "enc-aes-original");
        acl.sign(owner.getPrivate());

        acl.setEncryptedAesKeyForRecipient("enc-aes-tampered");
        assertFalse(acl.verify(),
                "Подмена зашифрованного AES-ключа должна ломать подпись");
    }

    @Test
    void tamperedDeleteReferenceFailsVerification() {
        KeyPair owner = KeyManager.generateRsaKeyPair();
        String ownerKey = KeyManager.publicKeyToBase64(owner.getPublic());
        Transaction del = Transaction.newDelete(ownerKey, "tx-id-A");
        del.sign(owner.getPrivate());

        del.setReferencedTxId("tx-id-B");
        assertFalse(del.verify(),
                "Подмена referencedTxId должна ломать подпись DELETE");
    }

    @Test
    void uploadTransactionDataToSignUnchangedDespiteNewFields() {
        // Защита от регрессии: UPLOAD-транзакция, у которой не установлены
        // ACL/DELETE-поля, должна давать ровно ту же строку для подписи,
        // что и раньше — иначе сломаются все блоки в RocksDB.
        Transaction tx = new Transaction("owner", "f.txt", 100L, "merkle-root");
        // timestamp фиксируем — иначе getDataToSign недетерминирован.
        tx.setTimestamp(1_700_000_000_000L);

        String dataToSign = tx.getDataToSign();
        assertEquals("owner|f.txt|100|merkle-root|1700000000000", dataToSign,
                "UPLOAD-транзакция без полей дня 6/9 должна давать минимальную подпись");
    }

    @Test
    void aclSerializesAndDeserializesViaJackson() throws Exception {
        // Жизненный цикл ACL в RocksDB-блокчейне: сериализация в JSON и обратно.
        KeyPair owner = KeyManager.generateRsaKeyPair();
        String ownerKey = KeyManager.publicKeyToBase64(owner.getPublic());
        Transaction acl = Transaction.newAcl(ownerKey, "tx-id", "recip", "enc-aes");
        acl.sign(owner.getPrivate());

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(acl);
        Transaction roundTrip = mapper.readValue(json, Transaction.class);

        assertEquals(Transaction.Kind.ACL, roundTrip.getKind());
        assertEquals(acl.getId(), roundTrip.getId());
        assertEquals(acl.getReferencedTxId(), roundTrip.getReferencedTxId());
        assertEquals(acl.getRecipientPublicKey(), roundTrip.getRecipientPublicKey());
        assertEquals(acl.getEncryptedAesKeyForRecipient(),
                roundTrip.getEncryptedAesKeyForRecipient());
        assertTrue(roundTrip.verify(), "После round-trip подпись должна сходиться");
    }

    @Test
    void deserializingOldUploadJsonWithoutKindStillWorks() throws Exception {
        // Защита от поломки: блоки, записанные до дня 9, не имели поля "kind".
        // При десериализации Jackson должен подставить UPLOAD.
        String oldStyleJson = """
                {
                  "id": "abc",
                  "ownerPublicKey": "owner",
                  "fileName": "f.txt",
                  "fileSize": 100,
                  "merkleRoot": "merkle",
                  "timestamp": 1700000000000,
                  "signature": "sig",
                  "shardHashes": [],
                  "replicas": []
                }""";
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature
                .FAIL_ON_UNKNOWN_PROPERTIES, false);
        Transaction tx = mapper.readValue(oldStyleJson, Transaction.class);
        assertEquals(Transaction.Kind.UPLOAD, tx.getKind(),
                "Старый JSON без kind должен десериализоваться как UPLOAD");
    }
}
