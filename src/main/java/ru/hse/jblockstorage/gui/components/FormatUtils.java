package ru.hse.jblockstorage.gui.components;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Маленькие утилиты форматирования для UI: размер, дата, обрезанный хеш.
 * <p>
 * Все форматы согласованы с мокапами и сводками дня 12:
 * <ul>
 *   <li>Размер: «12.4 МБ», «487 КБ», «8.2 ГБ»</li>
 *   <li>Дата: «3 мая, 14:22» (если в этом году),
 *       «3 мая 2025» (если в прошлом году)</li>
 *   <li>Хеш/публичный ключ — обрезка по середине: «MIIBIjA…ymq8»</li>
 * </ul>
 *
 * <p>Локаль ru-RU вшита целенаправленно: в проекте русский интерфейс,
 * и формат дат локалезависим. {@code DateTimeFormatter} использует
 * именно ru-локаль для названий месяцев («мая», а не «May»).
 */
public final class FormatUtils {

    private static final Locale RU = new Locale("ru", "RU");

    private static final DateTimeFormatter DAY_MONTH_TIME =
            DateTimeFormatter.ofPattern("d MMMM, HH:mm", RU);
    private static final DateTimeFormatter DAY_MONTH_YEAR =
            DateTimeFormatter.ofPattern("d MMMM yyyy", RU);

    private FormatUtils() {}

    // ------------------------------------------------------------------
    // Размер файла
    // ------------------------------------------------------------------

    /** «12.4 МБ», «487 КБ», «8.2 ГБ». Дублируется в UploadDialog для совместимости. */
    public static String humanSize(long bytes) {
        if (bytes < 0) return "—";
        if (bytes < 1024) return bytes + " Б";
        double kb = bytes / 1024.0;
        if (kb < 1024) return formatTwoDigits(kb) + " КБ";
        double mb = kb / 1024.0;
        if (mb < 1024) return formatTwoDigits(mb) + " МБ";
        double gb = mb / 1024.0;
        return formatTwoDigits(gb) + " ГБ";
    }

    private static String formatTwoDigits(double v) {
        // Русская локаль использует запятую как разделитель — но в мокапах
        // принят формат с точкой («12.4 МБ»). Поэтому форматируем явно.
        if (v >= 100) return String.format(Locale.US, "%.0f", v);
        if (v >= 10)  return String.format(Locale.US, "%.1f", v);
        return String.format(Locale.US, "%.2f", v);
    }

    // ------------------------------------------------------------------
    // Дата
    // ------------------------------------------------------------------

    /**
     * Форматирует unix-время как «3 мая, 14:22» (для текущего года) или
     * «3 мая 2024» (для прошлых лет — там не важна точная минута).
     *
     * @param epochMillis миллисекунды с эпохи Unix (как
     *                    {@code System.currentTimeMillis()}).
     */
    public static String relativeDate(long epochMillis) {
        if (epochMillis <= 0) return "—";
        try {
            LocalDateTime dt = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
            int currentYear = LocalDate.now().getYear();
            if (dt.getYear() == currentYear) {
                return dt.format(DAY_MONTH_TIME);
            }
            return dt.format(DAY_MONTH_YEAR);
        } catch (Exception e) {
            return "—";
        }
    }

    /**
     * Полная дата + время до секунд: «3 мая 2024, 14:22:08».
     * Используется в Свойствах файла (точная отметка загрузки).
     */
    public static String fullDateTime(long epochMillis) {
        if (epochMillis <= 0) return "—";
        try {
            DateTimeFormatter f = DateTimeFormatter.ofPattern(
                    "d MMMM yyyy, HH:mm:ss", RU);
            return LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault()).format(f);
        } catch (Exception e) {
            return "—";
        }
    }

    // ------------------------------------------------------------------
    // Хеш / ключ
    // ------------------------------------------------------------------

    /**
     * «Усечённое представление» длинной строки (txId, base64-ключ): первые
     * {@code prefix} символов, многоточие, последние {@code suffix} символов.
     *
     * <p>Для коротких строк (короче, чем prefix+suffix+1) возвращает
     * исходную строку без изменений.
     */
    public static String shortenMid(String s, int prefix, int suffix) {
        if (s == null) return "—";
        if (s.length() <= prefix + suffix + 1) return s;
        return s.substring(0, prefix) + "…" + s.substring(s.length() - suffix);
    }

    /** Дефолтное усечение для txId: 8…8. */
    public static String shortenTxId(String txId) {
        return shortenMid(txId, 8, 8);
    }

    /** Дефолтное усечение для публичного ключа: 14…4. */
    public static String shortenPubKey(String pubKey) {
        return shortenMid(pubKey, 14, 4);
    }

    // ------------------------------------------------------------------
    // Падежи русского
    // ------------------------------------------------------------------

    /** «1 файл / 2 файла / 5 файлов». */
    public static String pluralFiles(int n) {
        int mod10 = Math.abs(n) % 10;
        int mod100 = Math.abs(n) % 100;
        if (mod10 == 1 && mod100 != 11) return "файл";
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return "файла";
        return "файлов";
    }

    /** «1 контакт / 2 контакта / 5 контактов». */
    public static String pluralContacts(int n) {
        int mod10 = Math.abs(n) % 10;
        int mod100 = Math.abs(n) % 100;
        if (mod10 == 1 && mod100 != 11) return "контакт";
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return "контакта";
        return "контактов";
    }

    /** «1 шард / 2 шарда / 5 шардов». */
    public static String pluralShards(int n) {
        int mod10 = Math.abs(n) % 10;
        int mod100 = Math.abs(n) % 100;
        if (mod10 == 1 && mod100 != 11) return "шард";
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return "шарда";
        return "шардов";
    }

    /** «1 человек / 2 человека / 5 человек». */
    public static String pluralPeople(int n) {
        int mod10 = Math.abs(n) % 10;
        int mod100 = Math.abs(n) % 100;
        if (mod10 == 1 && mod100 != 11) return "человек";
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return "человека";
        return "человек";
    }
}
