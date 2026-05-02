package ru.hse.jblockstorage.crypto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyManagerTest {

    @Test
    void generatedKeyPairUsesRsaWith2048Bits() {
        KeyPair kp = KeyManager.generateRsaKeyPair();
        assertEquals("RSA", kp.getPublic().getAlgorithm());
        assertEquals("RSA", kp.getPrivate().getAlgorithm());
        // X.509-кодировка RSA-2048 публичного ключа ≈ 294 байт
        assertTrue(kp.getPublic().getEncoded().length > 250);
    }

    @Test
    void publicKeyBase64RoundTrip() {
        KeyPair kp = KeyManager.generateRsaKeyPair();
        String base64 = KeyManager.publicKeyToBase64(kp.getPublic());
        assertNotNull(base64);
        PublicKey restored = KeyManager.publicKeyFromBase64(base64);
        assertEquals(kp.getPublic(), restored);
    }

    @Test
    void publicKeyFileRoundTrip(@TempDir Path tempDir) throws IOException {
        KeyPair kp = KeyManager.generateRsaKeyPair();
        Path file = tempDir.resolve("pub.key");
        KeyManager.savePublicKey(kp.getPublic(), file);
        assertEquals(kp.getPublic(), KeyManager.loadPublicKey(file));
    }

    @Test
    void encryptedPrivateKeyRoundTrip(@TempDir Path tempDir) throws IOException {
        KeyPair kp = KeyManager.generateRsaKeyPair();
        Path file = tempDir.resolve("priv.key");
        char[] password = "correct-horse-battery-staple".toCharArray();

        KeyManager.saveEncryptedPrivateKey(kp.getPrivate(), file, password);
        PrivateKey restored = KeyManager.loadEncryptedPrivateKey(file, password);

        assertEquals(kp.getPrivate(), restored);
    }

    @Test
    void wrongPasswordFailsToLoadPrivateKey(@TempDir Path tempDir) throws IOException {
        KeyPair kp = KeyManager.generateRsaKeyPair();
        Path file = tempDir.resolve("priv.key");

        KeyManager.saveEncryptedPrivateKey(kp.getPrivate(), file, "right".toCharArray());
        assertThrows(IOException.class,
                () -> KeyManager.loadEncryptedPrivateKey(file, "wrong".toCharArray()));
    }

    @Test
    void publicKeyFromBase64RejectsGarbage() {
        assertThrows(IllegalArgumentException.class,
                () -> KeyManager.publicKeyFromBase64("not-a-real-key"));
    }
}