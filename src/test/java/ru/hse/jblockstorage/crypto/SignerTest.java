package ru.hse.jblockstorage.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignerTest {

    @Test
    void signedDataVerifiesWithMatchingPublicKey() {
        KeyPair kp = KeyManager.generateRsaKeyPair();
        byte[] data = "transaction payload".getBytes(StandardCharsets.UTF_8);

        byte[] signature = Signer.sign(data, kp.getPrivate());
        assertTrue(Signer.verify(data, signature, kp.getPublic()));
    }

    @Test
    void verificationFailsForTamperedData() {
        KeyPair kp = KeyManager.generateRsaKeyPair();
        byte[] original = "original message".getBytes();
        byte[] signature = Signer.sign(original, kp.getPrivate());

        byte[] tampered = "tampered message".getBytes();
        assertFalse(Signer.verify(tampered, signature, kp.getPublic()));
    }

    @Test
    void verificationFailsWithDifferentPublicKey() {
        KeyPair signer = KeyManager.generateRsaKeyPair();
        KeyPair other = KeyManager.generateRsaKeyPair();
        byte[] data = "data".getBytes();
        byte[] signature = Signer.sign(data, signer.getPrivate());
        assertFalse(Signer.verify(data, signature, other.getPublic()));
    }

    @Test
    void verificationFailsForCorruptedSignature() {
        KeyPair kp = KeyManager.generateRsaKeyPair();
        byte[] data = "payload".getBytes();
        byte[] signature = Signer.sign(data, kp.getPrivate());
        signature[0] ^= 0x01;
        assertFalse(Signer.verify(data, signature, kp.getPublic()));
    }
}