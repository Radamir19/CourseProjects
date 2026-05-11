package ru.hse.jblockstorage.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;

/**
 * RSA-OAEP — асимметричное шифрование коротких сообщений (до ~190 байт для RSA-2048).
 * <p>
 * В нашей системе используется для гибридного шифрования файлов:
 * <ol>
 *   <li>Файл шифруется случайным симметричным ключом AES-256
 *       (см. {@link AesGcm}) — это быстро и работает для любого размера файла.</li>
 *   <li>Сам AES-ключ (32 байта) шифруется публичным ключом владельца
 *       через RSA-OAEP — этот класс — и записывается в транзакцию.</li>
 *   <li>При скачивании владелец расшифровывает AES-ключ своим приватным
 *       RSA-ключом, потом расшифровывает им сам файл.</li>
 * </ol>
 * Такая схема — стандартная гибридная криптосистема (см. Шнайер,
 * «Прикладная криптография», гл. 19): RSA дорогой, поэтому им шифруют
 * только короткий ключ, а не весь файл.
 *
 * <h3>Почему OAEP, а не PKCS#1 v1.5</h3>
 * RSA-OAEP — современный стандарт асимметричного шифрования (PKCS#1 v2.x).
 * Старый «голый» PKCS#1 v1.5 уязвим к атакам Bleichenbacher (1998) при
 * определённых сценариях. OAEP добавляет рандомизированный padding,
 * делая такие атаки неэффективными. В JDK 11+ OAEP полностью поддерживается
 * без сторонних библиотек.
 *
 * <h3>Параметры</h3>
 * Используем стандартное сочетание {@code RSA/ECB/OAEPWithSHA-256AndMGF1Padding}
 * — SHA-256 для хеша сообщения и для MGF1, без label. Это дефолт
 * для большинства современных систем.
 */
public final class RsaOaep {

    /** JCE-имя трансформации. {@code ECB} здесь номинальное — при OAEP-padding
     *  блочный режим не используется (RSA шифрует одно сообщение, не поток). */
    private static final String TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";

    static {
        // ТЗ 4.5.3: используем Bouncy Castle для RSA-OAEP.
        CryptoProviders.register();
    }

    private RsaOaep() {}

    /**
     * Зашифровать массив байт публичным ключом получателя.
     * Размер plaintext не должен превышать ~190 байт для RSA-2048
     * (точнее: keySize/8 - 2*hashSize - 2 = 256 - 64 - 2 = 190 байт).
     */
    public static byte[] encrypt(byte[] plaintext, PublicKey publicKey) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION, CryptoProviders.BC);
            cipher.init(Cipher.ENCRYPT_MODE, publicKey, oaepSpec());
            return cipher.doFinal(plaintext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("RSA-OAEP шифрование сломалось", e);
        }
    }

    /**
     * Расшифровать массив байт приватным ключом получателя.
     * Возвращает исходный plaintext, переданный в {@link #encrypt}.
     */
    public static byte[] decrypt(byte[] ciphertext, PrivateKey privateKey) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION, CryptoProviders.BC);
            cipher.init(Cipher.DECRYPT_MODE, privateKey, oaepSpec());
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("RSA-OAEP расшифровка сломалась", e);
        }
    }

    /**
     * OAEPParameterSpec явно задаёт SHA-256 + MGF1(SHA-256). Это нужно,
     * потому что у некоторых JDK дефолт по строке трансформации читается
     * неоднозначно (особенно у IBM JDK, который трактует MGF1 как SHA-1).
     * С явной спецификацией параметры всегда одинаковые на encrypt и decrypt.
     */
    private static OAEPParameterSpec oaepSpec() {
        return new OAEPParameterSpec(
                "SHA-256",
                "MGF1",
                MGF1ParameterSpec.SHA256,
                PSource.PSpecified.DEFAULT);
    }
}
