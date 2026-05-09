package ru.hse.jblockstorage.gui.theme;

/**
 * Возможные значения темы оформления приложения.
 *
 * <p>{@link #SYSTEM} — следовать за настройкой ОС (детектируется
 * {@link ThemeManager#detectSystemTheme()}). Является дефолтом для нового
 * пользователя — это поведение, к которому привыкли пользователи macOS и
 * современных Windows/Linux.
 *
 * <p>{@link #LIGHT} и {@link #DARK} — явный фиксированный выбор. Полезен,
 * если пользователю не нравится автопереключение или ОС детектируется криво.
 */
public enum AppTheme {

    LIGHT("Светлая"),
    DARK("Тёмная"),
    SYSTEM("Системная");

    private final String displayName;

    AppTheme(String displayName) {
        this.displayName = displayName;
    }

    /** Подпись для отображения в UI (русский, для интерфейса в Настройках). */
    public String displayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
