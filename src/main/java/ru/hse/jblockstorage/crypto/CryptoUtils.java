package ru.hse.jblockstorage.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Утилиты хеширования и кодирования.
 * <p>
 * Согласно ТЗ (п. 4.1.1.2.4) для построения дерева Меркла и контроля
 * целостности используется алгоритм SHA-256.
 * </p>
 */
public final class CryptoUtils {

    private static final HexFormat HEX = HexFormat.of();

    private CryptoUtils() {
        // утилитный класс
    }

    /**
     * Возвращает SHA-256 хэш строки в шестнадцатеричном представлении.
     * Сохранён для обратной совместимости с уже существующим кодом
     * (см. {@link ru.hse.jblockstorage.blockchain.Transaction#calculateTxId()}).
     */
    public static String applySha256(String input) {
        return toHex(applySha256(input.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Возвращает SHA-256 хэш произвольного набора байт (32 байта).
     * Используется для хэширования блоков файлов и узлов дерева Меркла.
     */
    public static byte[] applySha256(byte[] input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(input);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 гарантированно есть в любой стандартной JRE.
            throw new IllegalStateException("SHA-256 не доступен в данной JRE", e);
        }
    }

    /** Шестнадцатеричное представление SHA-256 от байт. */
    public static String applySha256Hex(byte[] input) {
        return toHex(applySha256(input));
    }

    /** Конкатенирует два массива байт и считает SHA-256 — удобно для дерева Меркла. */
    public static byte[] applySha256(byte[] left, byte[] right) {
        byte[] joined = new byte[left.length + right.length];
        System.arraycopy(left, 0, joined, 0, left.length);
        System.arraycopy(right, 0, joined, left.length, right.length);
        return applySha256(joined);
    }

    /** Преобразование байтов в нижнерегистровую hex-строку. */
    public static String toHex(byte[] data) {
        return HEX.formatHex(data);
    }

    /** Парсинг hex-строки в массив байт. */
    public static byte[] fromHex(String hex) {
        return HEX.parseHex(hex);
    }
}