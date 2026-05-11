package ru.hse.jblockstorage.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Покрытие сценария восстановления keystore по BIP-39 seed-фразе
 * (ТЗ п. 4.1.5.1). UI-независимая проверка контракта, на котором
 * построен {@code RestoreView}: при создании профиля пишутся два
 * keystore-файла ({@code .keys} c user-password и {@code .recovery}
 * c mnemonic-derived password); при «забытом пароле» {@code .recovery}
 * расшифровывается фразой и используется для перезаписи {@code .keys}.
 */
class KeystoreRecoveryTest {

    @Test
    @DisplayName("Создание профиля с двумя keystore-файлами и восстановление по фразе")
    void recoveryRoundTrip(@TempDir Path tmp) throws IOException {
        // Создаём профиль: пара RSA + мнемоника.
        KeyPair kp = KeyManager.generateRsaKeyPair();
        String mnemonic = Bip39.generateMnemonic12();
        char[] userPassword = "user-password-1".toCharArray();

        Path keysFile = tmp.resolve("alice.keys");
        Path recoveryFile = tmp.resolve("alice.recovery");

        // Сохранение, как в Step3.
        char[] recoveryPwd = Bip39.mnemonicToKeystorePassword(mnemonic);
        try {
            KeyManager.saveEncryptedPrivateKey(kp.getPrivate(), keysFile, userPassword.clone());
            KeyManager.saveEncryptedPrivateKey(kp.getPrivate(), recoveryFile, recoveryPwd);
        } finally {
            Arrays.fill(recoveryPwd, '\0');
        }
        assertTrue(Files.exists(keysFile));
        assertTrue(Files.exists(recoveryFile));

        // Симулируем «пользователь забыл пароль» и восстанавливает по фразе.
        char[] newPassword = "user-password-2".toCharArray();
        char[] recoveryPwd2 = Bip39.mnemonicToKeystorePassword(mnemonic);
        PrivateKey restored;
        try {
            restored = KeyManager.loadEncryptedPrivateKey(recoveryFile, recoveryPwd2);
        } finally {
            Arrays.fill(recoveryPwd2, '\0');
        }

        // Перезаписываем .keys новым паролем.
        KeyManager.saveEncryptedPrivateKey(restored, keysFile, newPassword.clone());

        // Старый пароль больше не подходит.
        assertThrows(IOException.class,
                () -> KeyManager.loadEncryptedPrivateKey(keysFile, userPassword.clone()));

        // Новый пароль работает, и приватный ключ — тот же, что был исходно.
        PrivateKey reloaded = KeyManager.loadEncryptedPrivateKey(keysFile, newPassword.clone());
        assertArrayEquals(kp.getPrivate().getEncoded(), reloaded.getEncoded(),
                "После восстановления приватный ключ должен совпадать с исходным");

        // .recovery всё ещё работает с той же фразой — повторное восстановление возможно.
        char[] recoveryPwd3 = Bip39.mnemonicToKeystorePassword(mnemonic);
        try {
            PrivateKey againFromRecovery = KeyManager.loadEncryptedPrivateKey(recoveryFile, recoveryPwd3);
            assertArrayEquals(kp.getPrivate().getEncoded(), againFromRecovery.getEncoded());
        } finally {
            Arrays.fill(recoveryPwd3, '\0');
        }
    }

    @Test
    @DisplayName("Чужая фраза не открывает .recovery — расшифровка падает")
    void wrongMnemonicFails(@TempDir Path tmp) throws IOException {
        KeyPair kp = KeyManager.generateRsaKeyPair();
        String correct = Bip39.generateMnemonic12();
        String wrong = Bip39.generateMnemonic12();
        // Маловероятно, но защитимся от случайного совпадения генераций.
        assertNotEquals(correct, wrong, "Сгенерированные фразы должны отличаться");

        Path recoveryFile = tmp.resolve("bob.recovery");
        char[] correctPwd = Bip39.mnemonicToKeystorePassword(correct);
        try {
            KeyManager.saveEncryptedPrivateKey(kp.getPrivate(), recoveryFile, correctPwd);
        } finally {
            Arrays.fill(correctPwd, '\0');
        }

        char[] wrongPwd = Bip39.mnemonicToKeystorePassword(wrong);
        try {
            assertThrows(IOException.class,
                    () -> KeyManager.loadEncryptedPrivateKey(recoveryFile, wrongPwd.clone()));
        } finally {
            Arrays.fill(wrongPwd, '\0');
        }
    }

    @Test
    @DisplayName("mnemonicToKeystorePassword детерминирован — одна фраза → один и тот же пароль")
    void mnemonicPasswordDeterministic() {
        String mnemonic = Bip39.generateMnemonic12();
        char[] p1 = Bip39.mnemonicToKeystorePassword(mnemonic);
        char[] p2 = Bip39.mnemonicToKeystorePassword(mnemonic);
        try {
            assertArrayEquals(p1, p2,
                    "Производный пароль должен быть детерминированным от фразы");
        } finally {
            Arrays.fill(p1, '\0');
            Arrays.fill(p2, '\0');
        }
    }
}
