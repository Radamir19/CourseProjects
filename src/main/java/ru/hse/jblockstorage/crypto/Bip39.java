package ru.hse.jblockstorage.crypto;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Реализация BIP-39 (mnemonic для seed-фразы).
 * <p>
 * Соответствует спецификации
 * <a href="https://github.com/bitcoin/bips/blob/master/bip-0039.mediawiki">BIP-39</a>:
 * стандартный английский wordlist (2048 слов), 128-битная энтропия → 12 слов
 * (4 бита checksum), нормализация {@link Normalizer.Form#NFKD}, derive seed
 * через PBKDF2-HMAC-SHA512 (2048 итераций, salt = "mnemonic" + passphrase).
 *
 * <h3>Зачем в JBlockStorage</h3>
 * Закрывает требование ТЗ п. 4.2.4 «Защита от потери ключей»:
 * пользователь при создании профиля получает 12 слов как резервный способ
 * вернуться к своему зашифрованному {@code .keys} файлу. Если файл утерян,
 * но фраза сохранена — её можно ввести и расшифровать другую копию keystore'а.
 *
 * <h3>Wrapper-подход</h3>
 * <b>Важно:</b> мнемоническая фраза НЕ используется как источник самих
 * RSA-ключей (для RSA нет стандарта детерминированной генерации из seed'а
 * в отличие от secp256k1/ed25519). Вместо этого мнемоника детерминированно
 * порождает <i>пароль</i>, которым шифруется keystore-файл. То есть
 * фраза = «мастер-пароль», keystore = «зашифрованный приватный ключ».
 * Эту тонкость следует упомянуть в пояснительной записке.
 *
 * <h3>Безопасность</h3>
 * BIP-39 рассчитан на 128-битную энтропию (2^128 вариантов фраз) — это
 * на 30 порядков больше, чем брутфорс PBKDF2 потянет на современном железе.
 * При сравнении: PBKDF2-HMAC-SHA512(2048 iter) даёт около 2^21 хешей/сек
 * на CPU, или ~2^32 в год — даже если бы мы хешировали ВСЁ время Вселенной,
 * перебрать 2^128 невозможно.
 */
public final class Bip39 {

    /** Стандарт BIP-39 — фиксировано. */
    private static final int PBKDF2_ITERATIONS = 2048;
    /** Длина seed по стандарту (512 бит). */
    private static final int SEED_BYTES = 64;

    /** Загруженный wordlist — словарь из 2048 слов. */
    private static final List<String> WORDLIST;
    /** Обратный индекс: слово → индекс (0..2047). */
    private static final Map<String, Integer> WORD_INDEX;

    static {
        WORDLIST = loadWordlist();
        if (WORDLIST.size() != 2048) {
            throw new IllegalStateException(
                    "BIP-39 wordlist должен содержать 2048 слов, а содержит "
                            + WORDLIST.size());
        }
        WORD_INDEX = new HashMap<>(2048);
        for (int i = 0; i < WORDLIST.size(); i++) {
            WORD_INDEX.put(WORDLIST.get(i), i);
        }
    }

    private Bip39() {}

    /**
     * Генерирует новую 12-словную мнемоническую фразу.
     * <p>
     * Используется 128 бит энтропии + 4 бита SHA-256 checksum → 132 бита →
     * 12 слов по 11 бит каждое. Это «начальная сложность» BIP-39 —
     * подходит для большинства задач и даёт стандартное число слов.
     */
    public static String generateMnemonic12() {
        return generateMnemonic(128);
    }

    /**
     * Генерирует мнемоническую фразу указанной длины в битах энтропии.
     *
     * @param entropyBits должно быть кратно 32, в диапазоне 128..256
     *                    (12, 15, 18, 21, 24 слов соответственно)
     */
    public static String generateMnemonic(int entropyBits) {
        if (entropyBits < 128 || entropyBits > 256 || entropyBits % 32 != 0) {
            throw new IllegalArgumentException(
                    "entropyBits должно быть из {128,160,192,224,256}, дано: " + entropyBits);
        }
        byte[] entropy = new byte[entropyBits / 8];
        new SecureRandom().nextBytes(entropy);
        return entropyToMnemonic(entropy);
    }

    /**
     * Кодирует произвольную энтропию в мнемоническую фразу
     * (для unit-тестов и known-answer векторов).
     */
    public static String entropyToMnemonic(byte[] entropy) {
        Objects.requireNonNull(entropy, "entropy");
        int entropyBits = entropy.length * 8;
        if (entropyBits < 128 || entropyBits > 256 || entropyBits % 32 != 0) {
            throw new IllegalArgumentException(
                    "entropy.length должна давать 128..256 бит, кратно 32");
        }
        int checksumBits = entropyBits / 32; // 4 бита для 128-бит, 8 для 256-бит
        byte[] hash = sha256(entropy);

        // Собираем bit-stream: entropy bits + первые checksumBits хеша.
        boolean[] bits = new boolean[entropyBits + checksumBits];
        for (int i = 0; i < entropyBits; i++) {
            bits[i] = ((entropy[i / 8] >> (7 - (i % 8))) & 1) == 1;
        }
        for (int i = 0; i < checksumBits; i++) {
            bits[entropyBits + i] = ((hash[i / 8] >> (7 - (i % 8))) & 1) == 1;
        }

        // Каждые 11 бит — один индекс слова.
        int wordCount = bits.length / 11;
        List<String> words = new ArrayList<>(wordCount);
        for (int w = 0; w < wordCount; w++) {
            int idx = 0;
            for (int b = 0; b < 11; b++) {
                idx = (idx << 1) | (bits[w * 11 + b] ? 1 : 0);
            }
            words.add(WORDLIST.get(idx));
        }
        return String.join(" ", words);
    }

    /**
     * Проверяет валидность мнемонической фразы: каждое слово должно быть
     * в wordlist, и контрольная сумма должна сходиться.
     *
     * @return {@code true} если фраза валидна
     */
    public static boolean isValidMnemonic(String mnemonic) {
        if (mnemonic == null || mnemonic.isBlank()) return false;
        String normalized = normalizeMnemonic(mnemonic);
        String[] words = normalized.split(" ");
        // Допустимы 12, 15, 18, 21, 24 слова.
        if (words.length < 12 || words.length > 24 || words.length % 3 != 0) {
            return false;
        }

        int totalBits = words.length * 11;
        int checksumBits = totalBits / 33; // обратно из формулы выше
        int entropyBits = totalBits - checksumBits;

        boolean[] bits = new boolean[totalBits];
        for (int w = 0; w < words.length; w++) {
            Integer idx = WORD_INDEX.get(words[w]);
            if (idx == null) return false;
            for (int b = 0; b < 11; b++) {
                bits[w * 11 + b] = ((idx >> (10 - b)) & 1) == 1;
            }
        }
        // Извлекаем энтропию.
        byte[] entropy = new byte[entropyBits / 8];
        for (int i = 0; i < entropyBits; i++) {
            if (bits[i]) entropy[i / 8] |= (byte) (1 << (7 - (i % 8)));
        }
        // Считаем checksum, сравниваем.
        byte[] hash = sha256(entropy);
        for (int i = 0; i < checksumBits; i++) {
            boolean expected = ((hash[i / 8] >> (7 - (i % 8))) & 1) == 1;
            if (bits[entropyBits + i] != expected) return false;
        }
        return true;
    }

    /**
     * Получает 64-байт seed из мнемонической фразы по стандарту BIP-39.
     * <p>
     * Алгоритм: PBKDF2-HMAC-SHA512(password = NFKD(mnemonic),
     * salt = "mnemonic" + NFKD(passphrase), iterations = 2048,
     * keyLength = 512 бит).
     * <p>
     * Этот seed в нашей системе используется как высокоэнтропийный пароль
     * для шифрования keystore-файла (см. {@link KeyManager}).
     *
     * <h3>Тонкость нормализации</h3>
     * BIP-39 reference (Trezor python-mnemonic) применяет к passphrase
     * <b>только</b> {@code unicodedata.normalize("NFKD", passphrase)} — никакого
     * lowercase, никакого trim. Иначе passphrase {@code "TREZOR"} даёт
     * не тот salt, и seed не совпадёт с known-answer векторами.
     * <p>
     * Для самого mnemonic мы применяем чуть более мягкую нормализацию
     * (lowercase + collapse whitespace + trim) — это UX-удобство для
     * пользователей, которые ввели фразу заглавными или с лишними
     * пробелами. Все wordlist-слова и так в нижнем регистре, так что
     * для валидной фразы дополнительная нормализация — no-op.
     *
     * @param mnemonic   мнемоническая фраза (валидная)
     * @param passphrase дополнительный пароль (может быть пустой строкой)
     * @return 64 байта seed
     */
    public static byte[] mnemonicToSeed(String mnemonic, String passphrase) {
        Objects.requireNonNull(mnemonic, "mnemonic");
        if (passphrase == null) passphrase = "";

        char[] password = normalizeMnemonic(mnemonic).toCharArray();
        // Passphrase — ТОЛЬКО NFKD, без lowercase/trim/collapse, чтобы
        // совпасть с известными BIP-39 test vectors.
        String normPassphrase = Normalizer.normalize(passphrase, Normalizer.Form.NFKD);
        byte[] salt = ("mnemonic" + normPassphrase).getBytes(StandardCharsets.UTF_8);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512");
            PBEKeySpec spec = new PBEKeySpec(password, salt, PBKDF2_ITERATIONS, SEED_BYTES * 8);
            byte[] seed = factory.generateSecret(spec).getEncoded();
            spec.clearPassword();
            return seed;
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("PBKDF2WithHmacSHA512 недоступен", e);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    /**
     * Получает удобный <i>пароль</i> для шифрования keystore-файла из
     * мнемонической фразы. Это просто hex-кодирование первых 32 байт seed'а
     * (256 бит — более чем достаточно для AES-256-GCM с PBKDF2 в KeyManager).
     * <p>
     * Делается ДЕТЕРМИНИРОВАННО — одна и та же мнемоника всегда даёт
     * один и тот же пароль. Это и есть смысл «recovery»: пользователь
     * вводит фразу → получает тот же пароль → расшифровывает keystore.
     */
    public static char[] mnemonicToKeystorePassword(String mnemonic) {
        byte[] seed = mnemonicToSeed(mnemonic, "");
        // Берём первые 32 байта seed'а как пароль (его длина 64 байта избыточна).
        StringBuilder hex = new StringBuilder(64);
        for (int i = 0; i < 32; i++) {
            hex.append(String.format("%02x", seed[i]));
        }
        Arrays.fill(seed, (byte) 0);
        char[] result = new char[64];
        hex.getChars(0, 64, result, 0);
        // Затираем StringBuilder (Java не даёт прямого доступа, но обнуляем
        // через replace — best-effort).
        for (int i = 0; i < 64; i++) hex.setCharAt(i, '\0');
        return result;
    }

    // ---------- internal ----------

    /**
     * Нормализация мнемонической фразы для derive и валидации.
     * <p>
     * Делает: trim + lowercase + NFKD + collapse whitespace.
     * <p>
     * Lowercase и collapse — это UX-удобство, не часть строгого BIP-39.
     * Для валидных фраз из wordlist они no-op (все слова уже в нижнем
     * регистре, разделены одиночными пробелами). Для passphrase эту
     * функцию использовать НЕЛЬЗЯ — passphrase делает только NFKD,
     * см. {@link #mnemonicToSeed}.
     */
    private static String normalizeMnemonic(String s) {
        return Normalizer.normalize(s.trim().toLowerCase(), Normalizer.Form.NFKD)
                .replaceAll("\\s+", " ");
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }

    private static List<String> loadWordlist() {
        ClassLoader cl = Bip39.class.getClassLoader();
        try (InputStream is = cl.getResourceAsStream("bip39-english.txt")) {
            if (is == null) {
                throw new IllegalStateException(
                        "Не найден ресурс bip39-english.txt в classpath");
            }
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))) {
                List<String> words = new ArrayList<>(2048);
                String line;
                while ((line = br.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        words.add(trimmed);
                    }
                }
                return words;
            }
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось прочитать BIP-39 wordlist", e);
        }
    }
}