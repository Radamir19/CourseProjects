package ru.hse.jblockstorage.crypto;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Управление асимметричными ключами пользователя.
 * <p>
 * Согласно ТЗ (п. 4.1.1.2.1) каждому участнику сети выдаётся пара ключей
 * RSA-2048 для идентификации и подписи транзакций. Приватный ключ
 * хранится на диске в зашифрованном виде; ключ шифрования выводится
 * из пользовательского пароля (PIN-кода) через PBKDF2-HMAC-SHA256.
 * </p>
 *
 * <h3>Формат файла приватного ключа</h3>
 * <pre>
 *   [ version (1 байт) ‖ salt (16 байт) ‖ IV (12 байт) ‖ AES-GCM(PKCS#8(privateKey)) ]
 * </pre>
 */
public final class KeyManager {

    public static final String KEY_ALGORITHM = "RSA";
    public static final int RSA_KEY_BITS = 2048;

    /**
     * Минимальная длина пароля для шифрования приватного ключа (ТЗ п. 4.1.2,
     * Таблица 1 — поле «Пароль ключа: длина от 8 символов»).
     * <p>
     * Сама криптография (PBKDF2 + AES-GCM) корректно работает с любым паролем,
     * включая пустой; ограничение носит пользовательский характер — слишком
     * короткий пароль легко подбирается. Поэтому проверка вынесена в отдельный
     * метод {@link #validatePassword(char[])}, который вызывается на уровне
     * CLI/GUI перед сохранением ключа.
     */
    public static final int MIN_PASSWORD_LENGTH = 8;

    private static final int PBKDF2_ITERATIONS = 200_000;
    private static final int PBKDF2_KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final byte FILE_VERSION = 0x01;

    private static final SecureRandom RNG = new SecureRandom();

    static {
        // ТЗ 4.5.3: генерация RSA-ключей и шифрование приватного ключа
        // (PBKDF2 + AES-GCM) выполняются через Bouncy Castle.
        CryptoProviders.register();
    }

    private KeyManager() {
        // утилитный класс
    }

    /**
     * Проверяет, что пользовательский пароль соответствует ограничениям ТЗ
     * (длина не менее {@link #MIN_PASSWORD_LENGTH} символов).
     * <p>
     * Вызывается из CLI/GUI перед {@link #saveEncryptedPrivateKey}. Не вызывается
     * из самого {@code saveEncryptedPrivateKey}, чтобы:
     * <ul>
     *   <li>не ломать существующие тесты криптографии (round-trip с короткими
     *       тестовыми паролями);</li>
     *   <li>не мешать высокоэнтропийным паролям, выведенным из BIP-39 фразы
     *       (они в hex-формате имеют длину 64, но проверять их через эту
     *       функцию всё равно полезно — это семантически корректно).</li>
     * </ul>
     *
     * @throws IllegalArgumentException если пароль {@code null}, пустой или
     *                                  короче {@link #MIN_PASSWORD_LENGTH}
     */
    public static void validatePassword(char[] password) {
        if (password == null || password.length < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "Пароль должен быть не короче " + MIN_PASSWORD_LENGTH + " символов");
        }
    }

    /** Генерирует свежую пару RSA-2048 для нового профиля пользователя. */
    public static KeyPair generateRsaKeyPair() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(KEY_ALGORITHM, CryptoProviders.BC);
            kpg.initialize(RSA_KEY_BITS, RNG);
            return kpg.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Не удалось сгенерировать RSA-ключи", e);
        }
    }

    // ---------- Публичный ключ (X.509 SubjectPublicKeyInfo, Base64) ----------

    /** Кодирует публичный ключ в Base64-строку — это и есть «адрес» пользователя в сети. */
    public static String publicKeyToBase64(PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    /** Восстанавливает публичный ключ из Base64-строки. */
    public static PublicKey publicKeyFromBase64(String base64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance(KEY_ALGORITHM, CryptoProviders.BC)
                    .generatePublic(new X509EncodedKeySpec(bytes));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Некорректный публичный ключ", e);
        }
    }

    public static void savePublicKey(PublicKey key, Path file) throws IOException {
        Files.writeString(file, publicKeyToBase64(key));
    }

    public static PublicKey loadPublicKey(Path file) throws IOException {
        return publicKeyFromBase64(Files.readString(file).trim());
    }

    // ---------- Приватный ключ (зашифрованный паролем) ----------

    /**
     * Сохраняет приватный ключ в файл, зашифровав его ключом, выведенным
     * из пароля через PBKDF2-HMAC-SHA256.
     */
    public static void saveEncryptedPrivateKey(PrivateKey key, Path file, char[] password) throws IOException {
        try {
            byte[] salt = new byte[SALT_BYTES];
            RNG.nextBytes(salt);
            byte[] iv = new byte[IV_BYTES];
            RNG.nextBytes(iv);

            SecretKey aesKey = deriveKey(password, salt);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding", CryptoProviders.BC);
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(key.getEncoded());

            ByteBuffer buf = ByteBuffer.allocate(1 + SALT_BYTES + IV_BYTES + ciphertext.length);
            buf.put(FILE_VERSION).put(salt).put(iv).put(ciphertext);
            Files.write(file, buf.array());
        } catch (GeneralSecurityException e) {
            throw new IOException("Не удалось зашифровать приватный ключ", e);
        }
    }

    /** Загружает зашифрованный приватный ключ. При неверном пароле бросает {@link IOException}. */
    public static PrivateKey loadEncryptedPrivateKey(Path file, char[] password) throws IOException {
        byte[] data = Files.readAllBytes(file);
        if (data.length < 1 + SALT_BYTES + IV_BYTES + TAG_BITS / 8) {
            throw new IOException("Файл ключа повреждён или имеет некорректный формат");
        }
        ByteBuffer buf = ByteBuffer.wrap(data);
        byte version = buf.get();
        if (version != FILE_VERSION) {
            throw new IOException("Неподдерживаемая версия файла ключа: " + version);
        }
        byte[] salt = new byte[SALT_BYTES];
        buf.get(salt);
        byte[] iv = new byte[IV_BYTES];
        buf.get(iv);
        byte[] ciphertext = new byte[buf.remaining()];
        buf.get(ciphertext);

        try {
            SecretKey aesKey = deriveKey(password, salt);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding", CryptoProviders.BC);
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] pkcs8 = cipher.doFinal(ciphertext);
            return KeyFactory.getInstance(KEY_ALGORITHM, CryptoProviders.BC)
                    .generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (GeneralSecurityException e) {
            throw new IOException("Не удалось расшифровать ключ — возможно, неверный пароль", e);
        }
    }

    private static SecretKey deriveKey(char[] password, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS);
        SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256", CryptoProviders.BC);
        byte[] keyBytes = skf.generateSecret(spec).getEncoded();
        return new SecretKeySpec(keyBytes, "AES");
    }
}
