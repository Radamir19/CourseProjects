package ru.hse.jblockstorage.crypto;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.Provider;
import java.security.Security;

/**
 * Единая точка регистрации криптографического провайдера Bouncy Castle
 * (ТЗ п. 4.5.3 — «применяются лицензионно чистые криптографические
 * библиотеки, в частности Bouncy Castle»).
 * <p>
 * BC регистрируется глобально в {@link Security}, после чего все JCA-вызовы
 * в проекте — {@code Cipher.getInstance}, {@code Signature.getInstance},
 * {@code KeyPairGenerator.getInstance}, {@code SecretKeyFactory.getInstance},
 * {@code MessageDigest.getInstance} — выбирают BC через
 * {@link #BC} в качестве явного имени провайдера (см. использование
 * в {@link AesGcm}, {@link RsaOaep}, {@link Signer}, {@link KeyManager},
 * {@link Bip39}, {@link CryptoUtils}).
 * <p>
 * Метод {@link #register()} идемпотентен: повторный вызов из любого
 * crypto-класса (через статический инициализатор) безопасен. Это нужно,
 * чтобы провайдер был доступен и в production-сценарии (вход через
 * {@link ru.hse.jblockstorage.gui.Launcher}), и в JUnit-тестах
 * (минующих Launcher).
 */
public final class CryptoProviders {

    /**
     * Имя провайдера Bouncy Castle, как оно зарегистрировано в JCA.
     * Передаётся вторым параметром в {@code getInstance(...)} —
     * это гарантирует, что выбран именно BC, а не SunJCE/SunRsaSign.
     */
    public static final String BC = BouncyCastleProvider.PROVIDER_NAME;

    private static final Logger log = LoggerFactory.getLogger(CryptoProviders.class);

    private static volatile boolean registered = false;

    static {
        register();
    }

    private CryptoProviders() {
        // утилитный класс
    }

    /**
     * Регистрирует Bouncy Castle в {@link Security}, если он ещё не зарегистрирован.
     * Идемпотентен и потокобезопасен — повторные вызовы безвредны.
     */
    public static synchronized void register() {
        if (registered) {
            return;
        }
        Provider existing = Security.getProvider(BC);
        if (existing == null) {
            int position = Security.addProvider(new BouncyCastleProvider());
            log.info("Bouncy Castle зарегистрирован как провайдер JCA на позиции {}", position);
        } else {
            log.debug("Bouncy Castle уже был зарегистрирован: {}", existing.getInfo());
        }
        registered = true;
    }
}
