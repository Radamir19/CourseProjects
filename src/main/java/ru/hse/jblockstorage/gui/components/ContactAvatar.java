package ru.hse.jblockstorage.gui.components;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;

/**
 * Цветной кружок с инициалом — аватарка контакта/хранителя.
 *
 * <p>Цвет выбирается детерминированно по хешу имени: одинаковое имя
 * всегда получает один и тот же цвет в любой части UI (это важно для
 * восприятия: «у Боба синий аватар» — узнаваемый признак).
 *
 * <p>Палитра ровно 5 оттенков (blue / orange / purple / green / pink),
 * как в мокапах {@code dashboard_contacts.html} (Б = синий, А =
 * оранжевый, К = фиолетовый, Д = зелёный, Е = розовый).
 *
 * <p>Используется в трёх местах:
 * <ul>
 *   <li>Список контактов</li>
 *   <li>Хранители в {@code FilePropertiesDialog} (по nodeId, не по
 *       имени, — но визуально тот же стиль)</li>
 *   <li>Поделившийся в разделе «Расшаренные мне»</li>
 * </ul>
 */
public final class ContactAvatar {

    /** Размер для списков (контакты, хранители) — 36×36. */
    public static final int SIZE_LARGE = 36;
    /** Размер для плотных списков (поделившийся в строке таблицы) — 22×22. */
    public static final int SIZE_SMALL = 22;

    private ContactAvatar() {}

    /** Создаёт аватар указанного размера. {@code key} — имя или nodeId, по нему хешируется цвет. */
    public static StackPane create(String key, int size) {
        String initial = initialOf(key);
        String variant = variantFor(key);

        Label label = new Label(initial);
        label.getStyleClass().addAll("contact-avatar-label", "contact-avatar-label--" + variant);
        if (size <= SIZE_SMALL) {
            label.setStyle("-fx-font-size: 10px;");
        }

        StackPane pane = new StackPane(label);
        pane.getStyleClass().addAll("contact-avatar", "contact-avatar--" + variant);
        pane.setMinSize(size, size);
        pane.setPrefSize(size, size);
        pane.setMaxSize(size, size);
        pane.setAlignment(Pos.CENTER);
        return pane;
    }

    /** Удобный шорткат для аватара 36×36. */
    public static StackPane large(String key) {
        return create(key, SIZE_LARGE);
    }

    /** Удобный шорткат для аватара 22×22 (для плотных списков). */
    public static StackPane small(String key) {
        return create(key, SIZE_SMALL);
    }

    /**
     * Первая буква ключа в верхнем регистре. Для пустого/null —
     * возвращаем «?» (никогда не нужно, но защита от падения).
     */
    private static String initialOf(String key) {
        if (key == null || key.isBlank()) return "?";
        // Берём первую "значимую" букву (skip whitespace)
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (!Character.isWhitespace(c)) {
                return String.valueOf(Character.toUpperCase(c));
            }
        }
        return "?";
    }

    /**
     * Детерминированный выбор одного из 5 вариантов по хешу строки.
     * <p>Согласовано с мокапами:
     * Б→blue, А→orange, К→purple, Д→green, Е→pink.
     * Для других имён цвет получается из (Math.floorMod(hash, 5)).
     */
    private static String variantFor(String key) {
        if (key == null || key.isBlank()) return "blue";
        // Спецпривязка к мокапам — чтобы при демонстрации выглядело
        // привычно, как в HTML-мокапах.
        char first = Character.toUpperCase(key.charAt(0));
        switch (first) {
            case 'Б': case 'B': return "blue";
            case 'А': case 'A': return "orange";
            case 'К': case 'K': return "purple";
            case 'Д': case 'D': return "green";
            case 'Е': case 'E': return "pink";
        }
        // Иначе — по хешу
        int idx = Math.floorMod(key.hashCode(), 5);
        return switch (idx) {
            case 0 -> "blue";
            case 1 -> "orange";
            case 2 -> "purple";
            case 3 -> "green";
            default -> "pink";
        };
    }
}
