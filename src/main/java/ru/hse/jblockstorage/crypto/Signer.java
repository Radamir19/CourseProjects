package ru.hse.jblockstorage.crypto;

import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;

/**
 * Цифровые подписи RSA по схеме SHA256withRSA (PKCS#1 v1.5).
 * <p>
 * Согласно ТЗ (п. 4.1.1.3.1) каждая транзакция в блокчейне должна быть
 * подписана владельцем — публичный ключ из транзакции выступает
 * идентификатором, а подпись доказывает авторство.
 * </p>
 */
public final class Signer {

    public static final String SIGNATURE_ALGORITHM = "SHA256withRSA";

    static {
        // ТЗ 4.5.3: подпись блокчейна выполняется через Bouncy Castle.
        CryptoProviders.register();
    }

    private Signer() {
        // утилитный класс
    }

    /** Подписывает данные приватным ключом и возвращает «сырую» подпись. */
    public static byte[] sign(byte[] data, PrivateKey privateKey) {
        try {
            Signature sig = Signature.getInstance(SIGNATURE_ALGORITHM, CryptoProviders.BC);
            sig.initSign(privateKey);
            sig.update(data);
            return sig.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Не удалось подписать данные", e);
        }
    }

    /**
     * Проверяет подпись. Возвращает {@code false} как при неверной подписи,
     * так и при любой ошибке проверки (неверный формат и т. п.) — это
     * безопаснее, чем выбрасывать исключение в коде валидации блока.
     */
    public static boolean verify(byte[] data, byte[] signature, PublicKey publicKey) {
        try {
            Signature sig = Signature.getInstance(SIGNATURE_ALGORITHM, CryptoProviders.BC);
            sig.initVerify(publicKey);
            sig.update(data);
            return sig.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }
}
