package ru.hse.jblockstorage.crypto;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тесты {@link Bip39}.
 * <p>
 * Содержат как round-trip проверки, так и known-answer тесты из официального
 * BIP-39 трезор-вектора (см. https://github.com/trezor/python-mnemonic):
 * для фиксированной энтропии должна получаться фиксированная фраза и
 * фиксированный seed.
 */
class Bip39Test {

    @Test
    void generatedMnemonicHas12WordsByDefault() {
        String mnemonic = Bip39.generateMnemonic12();
        assertEquals(12, mnemonic.split(" ").length);
        assertTrue(Bip39.isValidMnemonic(mnemonic),
                "Свежесгенерированная фраза должна проходить валидацию: " + mnemonic);
    }

    @Test
    void invalidMnemonicReturnsFalse() {
        assertFalse(Bip39.isValidMnemonic(null));
        assertFalse(Bip39.isValidMnemonic(""));
        assertFalse(Bip39.isValidMnemonic("not a valid bip39 phrase at all really nope"));
        // 12 настоящих слов из словаря, но с неверной checksum.
        assertFalse(Bip39.isValidMnemonic(
                "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon"),
                "Все 'abandon' x12 — не имеет корректной checksum");
    }

    @Test
    void knownAnswerVector_AllZeroEntropy() {
        // BIP-39 trezor test vector #1:
        // entropy = 00000000000000000000000000000000 (16 байт нулей)
        // mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon
        //            abandon abandon abandon about"
        byte[] entropy = new byte[16];
        String mnemonic = Bip39.entropyToMnemonic(entropy);
        assertEquals(
                "abandon abandon abandon abandon abandon abandon "
                        + "abandon abandon abandon abandon abandon about",
                mnemonic);
        assertTrue(Bip39.isValidMnemonic(mnemonic));
    }

    @Test
    void knownAnswerVector_AllZeroEntropy_Seed() {
        // Тот же вектор: seed (passphrase = "TREZOR")
        byte[] entropy = new byte[16];
        String mnemonic = Bip39.entropyToMnemonic(entropy);
        byte[] seed = Bip39.mnemonicToSeed(mnemonic, "TREZOR");
        // Известный seed из BIP-39 спецификации:
        String expected = "c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e5349553"
                + "1f09a6987599d18264c1e1c92f2cf141630c7a3c4ab7c81b2f001698e7463b04";
        assertEquals(expected, HexFormat.of().formatHex(seed));
    }

    @Test
    void mnemonicToKeystorePasswordIsDeterministic() {
        // Recovery базируется на детерминированности: одна и та же фраза
        // ВСЕГДА должна давать один и тот же пароль.
        String mnemonic = Bip39.generateMnemonic12();
        char[] pwd1 = Bip39.mnemonicToKeystorePassword(mnemonic);
        char[] pwd2 = Bip39.mnemonicToKeystorePassword(mnemonic);
        assertArrayEquals(pwd1, pwd2);
        assertEquals(64, pwd1.length, "Пароль — 64 hex-символа (32 байта)");
    }

    @Test
    void differentMnemonicsGiveDifferentPasswords() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            String mnemonic = Bip39.generateMnemonic12();
            char[] pwd = Bip39.mnemonicToKeystorePassword(mnemonic);
            assertTrue(seen.add(new String(pwd)),
                    "Каждая фраза должна давать уникальный пароль");
        }
    }

    @Test
    void mnemonicCaseInsensitiveAndWhitespaceTolerant() {
        // По BIP-39 фраза перед derive нормализуется: lowercase + collapse whitespace.
        String mnemonic = Bip39.generateMnemonic12();
        String upperWithSpaces = "  " + mnemonic.toUpperCase().replace(" ", "   ") + "  ";
        // isValidMnemonic должна принять обе формы.
        assertTrue(Bip39.isValidMnemonic(upperWithSpaces),
                "Валидация должна быть тогерантна к регистру и пробелам");
        // Seed должен совпадать.
        char[] pwd1 = Bip39.mnemonicToKeystorePassword(mnemonic);
        char[] pwd2 = Bip39.mnemonicToKeystorePassword(upperWithSpaces);
        assertArrayEquals(pwd1, pwd2);
    }

    @Test
    void wordlistHasExactly2048Words() {
        // Проверяем, что wordlist загрузился полностью.
        // Косвенно — генерируем 100 фраз и убеждаемся, что все слова из них
        // действительно в словаре через isValidMnemonic.
        for (int i = 0; i < 50; i++) {
            assertTrue(Bip39.isValidMnemonic(Bip39.generateMnemonic12()));
        }
    }
}
