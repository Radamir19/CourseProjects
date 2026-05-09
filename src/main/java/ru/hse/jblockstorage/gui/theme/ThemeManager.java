package ru.hse.jblockstorage.gui.theme;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.Scene;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Управляет темой оформления GUI.
 *
 * <p>На сцену всегда подгружается {@code base.css} (структура: типографика,
 * раскладка, размеры, формы кнопок) плюс одна из двух тем-листов
 * ({@code light.css} или {@code dark.css}, которые задают looked-up colors).
 * Это разделение позволяет переключать тему без полной перерисовки и без
 * дублирования структурных правил в каждой теме.
 *
 * <p>Системная тема детектируется через нативные команды:
 * <ul>
 *   <li><b>macOS:</b> {@code defaults read -g AppleInterfaceStyle} →
 *       выводит {@code Dark} в тёмном режиме, ошибку в светлом</li>
 *   <li><b>Windows:</b> {@code reg query} ключа
 *       {@code HKCU\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize\AppsUseLightTheme}</li>
 *   <li><b>Linux (GNOME):</b> {@code gsettings get org.gnome.desktop.interface color-scheme}</li>
 * </ul>
 * Если ничего не получилось — fallback {@link AppTheme#LIGHT}. Никаких
 * системных изменений не делаем.
 */
public final class ThemeManager {

    private static final Logger log = LoggerFactory.getLogger(ThemeManager.class);

    /** Таймаут для subprocess-детекции системной темы. */
    private static final long DETECT_TIMEOUT_MS = 1500;

    private final ObjectProperty<AppTheme> theme =
            new SimpleObjectProperty<>(this, "theme", AppTheme.SYSTEM);

    /**
     * Применяет текущую тему к указанной сцене: чистит её stylesheets и
     * добавляет {@code base.css} + соответствующий цветовой лист.
     *
     * <p>Метод идемпотентен — можно вызывать сколько угодно раз; на каждом
     * вызове сцена получает актуальный набор CSS.
     */
    public void applyTo(Scene scene) {
        Objects.requireNonNull(scene, "scene");

        AppTheme resolved = getResolvedTheme();
        String baseCss = resourceUrl("/css/base.css");
        String themeCss = resourceUrl(resolved == AppTheme.DARK ? "/css/dark.css" : "/css/light.css");

        scene.getStylesheets().clear();
        scene.getStylesheets().add(baseCss);
        scene.getStylesheets().add(themeCss);

        // Класс-маркер на корне — на случай, если потребуется условный селектор
        // (.theme-dark .some-component { ... }) без отдельного CSS-файла.
        scene.getRoot().getStyleClass().removeAll("theme-light", "theme-dark");
        scene.getRoot().getStyleClass().add(resolved == AppTheme.DARK ? "theme-dark" : "theme-light");

        log.debug("Тема применена: выбрано={}, разрешено={}, base={}, theme={}",
                theme.get(), resolved, baseCss, themeCss);
    }

    private static String resourceUrl(String path) {
        URL url = ThemeManager.class.getResource(path);
        if (url == null) {
            throw new IllegalStateException("CSS-ресурс не найден на classpath: " + path);
        }
        return url.toExternalForm();
    }

    // ------------------------------------------------------------------
    // Property API — для биндинга с UI-компонентом «Настройки → Тема»
    // ------------------------------------------------------------------

    public ObjectProperty<AppTheme> themeProperty() {
        return theme;
    }

    public AppTheme getTheme() {
        return theme.get();
    }

    public void setTheme(AppTheme value) {
        theme.set(Objects.requireNonNull(value, "theme"));
    }

    /**
     * Возвращает актуально применяемую тему: для {@link AppTheme#SYSTEM}
     * выполняет детекцию ОС и резолвит в LIGHT или DARK.
     */
    public AppTheme getResolvedTheme() {
        AppTheme t = theme.get();
        if (t == AppTheme.SYSTEM) {
            return detectSystemTheme();
        }
        return t;
    }

    // ------------------------------------------------------------------
    // Детекция системной темы
    // ------------------------------------------------------------------

    /**
     * Определяет текущую тему ОС.
     *
     * <p>В худшем случае (нет нативной утилиты, неправильный вывод,
     * таймаут) возвращает {@link AppTheme#LIGHT} — это безопасный
     * дефолт, который выглядит привычно почти везде.
     */
    public static AppTheme detectSystemTheme() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("mac")) {
                return detectMac();
            }
            if (os.contains("win")) {
                return detectWindows();
            }
            // Прочее — пытаемся как Linux/GNOME
            return detectLinux();
        } catch (Exception e) {
            log.debug("Не удалось определить системную тему ({}): {}", os, e.toString());
            return AppTheme.LIGHT;
        }
    }

    private static AppTheme detectMac() throws Exception {
        // Если ключ AppleInterfaceStyle отсутствует, defaults вернёт ненулевой код —
        // это значит «светлая тема». Если есть со значением Dark — тёмная.
        String out = runAndCapture("defaults", "read", "-g", "AppleInterfaceStyle");
        if (out != null && out.toLowerCase(Locale.ROOT).contains("dark")) {
            return AppTheme.DARK;
        }
        return AppTheme.LIGHT;
    }

    private static AppTheme detectWindows() throws Exception {
        // reg query ... /v AppsUseLightTheme
        // Пример вывода:
        //   AppsUseLightTheme    REG_DWORD    0x1     (light)
        //   AppsUseLightTheme    REG_DWORD    0x0     (dark)
        String out = runAndCapture(
                "reg", "query",
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                "/v", "AppsUseLightTheme"
        );
        if (out == null) return AppTheme.LIGHT;
        if (out.contains("0x0")) return AppTheme.DARK;
        return AppTheme.LIGHT;
    }

    private static AppTheme detectLinux() throws Exception {
        // GNOME 42+: color-scheme = 'prefer-dark' / 'default' / 'prefer-light'
        String out = runAndCapture("gsettings", "get", "org.gnome.desktop.interface", "color-scheme");
        if (out != null && out.toLowerCase(Locale.ROOT).contains("dark")) {
            return AppTheme.DARK;
        }
        return AppTheme.LIGHT;
    }

    /**
     * Запускает внешний процесс, ждёт его не дольше {@link #DETECT_TIMEOUT_MS}
     * и возвращает stdout как строку. {@code null} — если процесс упал, не
     * успел или вернул пустой вывод.
     */
    private static String runAndCapture(String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            Process p = pb.start();
            String stdout;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                stdout = r.lines().collect(Collectors.joining("\n")).trim();
            }
            boolean done = p.waitFor(DETECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!done) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0) {
                // Не ошибка с нашей стороны — у macOS это нормальный путь для
                // светлой темы (ключ AppleInterfaceStyle отсутствует).
                return null;
            }
            return stdout.isEmpty() ? null : stdout;
        } catch (Exception ignored) {
            return null;
        }
    }
}
