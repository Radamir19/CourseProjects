package ru.hse.jblockstorage.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CryptoUtilsTest {

    // Известные контрольные векторы SHA-256 (FIPS 180-4 / NIST CAVP).
    private static final String SHA256_OF_ABC =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    private static final String SHA256_OF_EMPTY =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Test
    void sha256OfStringMatchesKnownVector() {
        assertEquals(SHA256_OF_ABC, CryptoUtils.applySha256("abc"));
    }

    @Test
    void sha256OfEmptyStringMatchesKnownVector() {
        assertEquals(SHA256_OF_EMPTY, CryptoUtils.applySha256(""));
    }

    @Test
    void sha256OfBytesIsConsistentWithStringVersion() {
        byte[] hash = CryptoUtils.applySha256("abc".getBytes(StandardCharsets.UTF_8));
        assertEquals(SHA256_OF_ABC, CryptoUtils.toHex(hash));
        assertEquals(32, hash.length);
    }

    @Test
    void sha256ConcatenationHelperEqualsManualConcat() {
        byte[] a = "left".getBytes();
        byte[] b = "right".getBytes();
        byte[] manual = new byte[a.length + b.length];
        System.arraycopy(a, 0, manual, 0, a.length);
        System.arraycopy(b, 0, manual, a.length, b.length);
        assertArrayEquals(CryptoUtils.applySha256(manual), CryptoUtils.applySha256(a, b));
    }

    @Test
    void hexRoundTrip() {
        byte[] data = {0x00, 0x01, (byte) 0xff, (byte) 0xab, 0x10};
        String hex = CryptoUtils.toHex(data);
        assertEquals("0001ffab10", hex);
        assertArrayEquals(data, CryptoUtils.fromHex(hex));
    }

    @Test
    void fromHexRejectsInvalidString() {
        assertThrows(IllegalArgumentException.class, () -> CryptoUtils.fromHex("xyz"));
    }
}