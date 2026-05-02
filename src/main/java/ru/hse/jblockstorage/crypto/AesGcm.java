package ru.hse.jblockstorage.crypto;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

/**
 * Симметричное шифрование AES-256 в режиме GCM.
 * <p>
 * Согласно ТЗ (п. 4.1.1.2.2) каждый файл перед загрузкой должен шифроваться
 * на стороне клиента случайным сессионным ключом алгоритмом AES-256
 * в режиме GCM, обеспечивающем как конфиденциальность, так и контроль
 * целостности (через тег аутентификации).
 * </p>
 *
 * <h3>Формат шифротекста</h3>
 * <pre>
 *   [ IV (12 байт) ‖ ciphertext ‖ GCM tag (16 байт) ]
 * </pre>
 * IV генерируется случайно на каждое шифрование, поэтому одинаковый
 * plaintext под одним и тем же ключом даёт разные ciphertext.
 */
public final class AesGcm {

    public static final int KEY_BITS = 256;
    public static final int IV_BYTES = 12;
    public static final int TAG_BITS = 128;

    private static final SecureRandom RNG = new SecureRandom();
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private AesGcm() {
        // утилитный класс
    }

    /** Генерирует случайный 256-битный ключ AES. */
    public static SecretKey generateKey() {
        try {
            KeyGenerator kg = KeyGenerator.getInstance("AES");
            kg.init(KEY_BITS, RNG);
            return kg.generateKey();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Не удалось сгенерировать AES-ключ", e);
        }
    }

    /** Восстанавливает {@link SecretKey} из «сырых» 32 байт. */
    public static SecretKey keyFromBytes(byte[] keyBytes) {
        if (keyBytes.length != KEY_BITS / 8) {
            throw new IllegalArgumentException(
                    "Длина AES-ключа должна быть " + (KEY_BITS / 8) + " байт, получено " + keyBytes.length);
        }
        return new SecretKeySpec(keyBytes, "AES");
    }

    /** Извлекает «сырые» байты ключа (для последующего сохранения / шифрования под публичным ключом получателя). */
    public static byte[] keyToBytes(SecretKey key) {
        return key.getEncoded();
    }

    /**
     * Шифрует {@code plaintext} ключом {@code key} и возвращает массив
     * формата {@code IV‖ciphertext‖tag}.
     */
    public static byte[] encrypt(byte[] plaintext, SecretKey key) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RNG.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext);

            return ByteBuffer.allocate(IV_BYTES + ct.length)
                    .put(iv)
                    .put(ct)
                    .array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Шифрование AES-GCM завершилось ошибкой", e);
        }
    }

    /**
     * Расшифровывает массив, полученный от {@link #encrypt(byte[], SecretKey)}.
     * Бросает исключение, если данные были изменены или ключ неверен.
     */
    public static byte[] decrypt(byte[] data, SecretKey key) {
        if (data.length < IV_BYTES + TAG_BITS / 8) {
            throw new IllegalArgumentException("Слишком короткий шифротекст");
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            byte[] ct = new byte[data.length - IV_BYTES];
            System.arraycopy(data, 0, iv, 0, IV_BYTES);
            System.arraycopy(data, IV_BYTES, ct, 0, ct.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(ct);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(
                    "Расшифровка AES-GCM не удалась (повреждённые данные или неверный ключ)", e);
        }
    }
}
