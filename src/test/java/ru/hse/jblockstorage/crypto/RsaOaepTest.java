package ru.hse.jblockstorage.crypto;

import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тесты RSA-OAEP — асимметричного шифрования AES-ключа.
 */
class RsaOaepTest {

    @Test
    void roundTripWithRandomBytes() {
        KeyPair kp = KeyManager.generateRsaKeyPair();
        byte[] secret = new byte[32]; // как раз размер AES-256 ключа
        new java.security.SecureRandom().nextBytes(secret);

        byte[] ciphertext = RsaOaep.encrypt(secret, kp.getPublic());
        byte[] recovered = RsaOaep.decrypt(ciphertext, kp.getPrivate());

        assertArrayEquals(secret, recovered);
    }

    @Test
    void roundTripWithRealAesKey() {
        // Реальный сценарий: шифруем сериализованный AES-ключ
        KeyPair rsaPair = KeyManager.generateRsaKeyPair();
        SecretKey aesKey = AesGcm.generateKey();
        byte[] rawAesKey = AesGcm.keyToBytes(aesKey);

        byte[] encrypted = RsaOaep.encrypt(rawAesKey, rsaPair.getPublic());
        byte[] decrypted = RsaOaep.decrypt(encrypted, rsaPair.getPrivate());

        SecretKey recoveredKey = AesGcm.keyFromBytes(decrypted);
        // Проверяем, что восстановленный ключ функционально идентичен
        byte[] plaintext = "Hello, world!".getBytes(StandardCharsets.UTF_8);
        byte[] cipher1 = AesGcm.encrypt(plaintext, aesKey);
        byte[] back1 = AesGcm.decrypt(cipher1, recoveredKey);
        assertArrayEquals(plaintext, back1);
    }

    @Test
    void ciphertextDifferentEachTime() {
        // RSA-OAEP рандомизирован — два шифрования одного и того же plaintext
        // должны давать разный ciphertext.
        KeyPair kp = KeyManager.generateRsaKeyPair();
        byte[] data = new byte[]{1, 2, 3, 4, 5};

        byte[] c1 = RsaOaep.encrypt(data, kp.getPublic());
        byte[] c2 = RsaOaep.encrypt(data, kp.getPublic());

        assertFalse(Arrays.equals(c1, c2),
                "OAEP-padding должен давать разный ciphertext каждый раз");

        // Но оба расшифровываются в один и тот же plaintext
        assertArrayEquals(data, RsaOaep.decrypt(c1, kp.getPrivate()));
        assertArrayEquals(data, RsaOaep.decrypt(c2, kp.getPrivate()));
    }

    @Test
    void tamperedCiphertextFailsToDecrypt() {
        KeyPair kp = KeyManager.generateRsaKeyPair();
        byte[] data = new byte[]{10, 20, 30};

        byte[] ciphertext = RsaOaep.encrypt(data, kp.getPublic());
        ciphertext[5] ^= 0x01; // flip 1 bit

        assertThrows(IllegalStateException.class,
                () -> RsaOaep.decrypt(ciphertext, kp.getPrivate()));
    }

    @Test
    void wrongKeyFailsToDecrypt() {
        KeyPair k1 = KeyManager.generateRsaKeyPair();
        KeyPair k2 = KeyManager.generateRsaKeyPair();

        byte[] data = "secret".getBytes(StandardCharsets.UTF_8);
        byte[] ciphertext = RsaOaep.encrypt(data, k1.getPublic());

        // Расшифровка чужим приватным ключом должна провалиться
        assertThrows(IllegalStateException.class,
                () -> RsaOaep.decrypt(ciphertext, k2.getPrivate()));
    }

    @Test
    void emptyPlaintextRoundTrip() {
        KeyPair kp = KeyManager.generateRsaKeyPair();
        byte[] empty = new byte[0];

        byte[] ciphertext = RsaOaep.encrypt(empty, kp.getPublic());
        byte[] recovered = RsaOaep.decrypt(ciphertext, kp.getPrivate());

        assertArrayEquals(empty, recovered);
    }
}
