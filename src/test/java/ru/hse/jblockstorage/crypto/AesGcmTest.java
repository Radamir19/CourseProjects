package ru.hse.jblockstorage.crypto;

import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AesGcmTest {

    @Test
    void encryptDecryptRoundTrip() {
        SecretKey key = AesGcm.generateKey();
        byte[] plaintext = "Hello, JBlockStorage!".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = AesGcm.encrypt(plaintext, key);
        byte[] decrypted = AesGcm.decrypt(ciphertext, key);

        assertArrayEquals(plaintext, decrypted);
    }

    @Test
    void ciphertextIsLongerThanPlaintextDueToIvAndTag() {
        SecretKey key = AesGcm.generateKey();
        byte[] plaintext = new byte[100];
        byte[] ciphertext = AesGcm.encrypt(plaintext, key);
        // +12 IV, +16 GCM tag
        assertEquals(plaintext.length + AesGcm.IV_BYTES + AesGcm.TAG_BITS / 8, ciphertext.length);
    }

    @Test
    void sameKeySameDataProducesDifferentCiphertextsBecauseOfRandomIv() {
        SecretKey key = AesGcm.generateKey();
        byte[] plaintext = "repeat".getBytes();
        byte[] c1 = AesGcm.encrypt(plaintext, key);
        byte[] c2 = AesGcm.encrypt(plaintext, key);
        assertFalse(Arrays.equals(c1, c2), "IV должен быть случайным на каждое шифрование");
    }

    @Test
    void tamperedCiphertextFailsAuthentication() {
        SecretKey key = AesGcm.generateKey();
        byte[] ciphertext = AesGcm.encrypt("secret payload".getBytes(), key);
        // Меняем последний байт (внутри GCM tag) — расшифровка должна упасть
        ciphertext[ciphertext.length - 1] ^= 0x01;
        assertThrows(IllegalStateException.class, () -> AesGcm.decrypt(ciphertext, key));
    }

    @Test
    void wrongKeyFailsDecryption() {
        SecretKey k1 = AesGcm.generateKey();
        SecretKey k2 = AesGcm.generateKey();
        byte[] ciphertext = AesGcm.encrypt("secret".getBytes(), k1);
        assertThrows(IllegalStateException.class, () -> AesGcm.decrypt(ciphertext, k2));
    }

    @Test
    void keyToBytesAndBackPreservesEncryptionCapability() {
        SecretKey original = AesGcm.generateKey();
        byte[] raw = AesGcm.keyToBytes(original);
        assertEquals(AesGcm.KEY_BITS / 8, raw.length);

        SecretKey restored = AesGcm.keyFromBytes(raw);
        byte[] plaintext = "round-trip".getBytes();
        byte[] ciphertext = AesGcm.encrypt(plaintext, original);
        assertArrayEquals(plaintext, AesGcm.decrypt(ciphertext, restored));
    }

    @Test
    void keyFromBytesRejectsWrongLength() {
        assertThrows(IllegalArgumentException.class, () -> AesGcm.keyFromBytes(new byte[16]));
    }
}