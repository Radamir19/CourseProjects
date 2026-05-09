package ru.hse.jblockstorage.gui.components;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

import java.util.Locale;

/**
 * Маленький бейдж 32×36 с типом файла (PDF / DOC / IMG / ZIP / FILE).
 *
 * <p>Использует CSS-классы {@code .file-badge--*} и {@code .file-badge-label--*},
 * которые уже определены в {@code base.css} в обеих темах. Это единое
 * место выбора иконки по расширению — используется и в UploadDialog
 * (превью выбранного файла), и потом в таблице «Мои файлы».
 *
 * <p>Маппинг расширения → категория консервативный: всё неизвестное
 * попадает в нейтральный «FILE» с серым бейджем.
 */
public final class FileTypeBadge {

    private FileTypeBadge() {}

    public enum Kind {
        PDF("PDF", "pdf"),
        DOC("DOC", "doc"),
        IMG("IMG", "img"),
        ZIP("ZIP", "zip"),
        FILE("FILE", "zip"); // нейтральный — используем серый zip-стиль

        public final String label;
        public final String cssVariant; // pdf | doc | img | zip
        Kind(String label, String cssVariant) {
            this.label = label;
            this.cssVariant = cssVariant;
        }
    }

    /** Определяет тип бейджа по имени файла. */
    public static Kind fromFileName(String fileName) {
        if (fileName == null) return Kind.FILE;
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return Kind.FILE;
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return switch (ext) {
            case "pdf" -> Kind.PDF;
            case "doc", "docx", "rtf", "odt", "txt", "md" -> Kind.DOC;
            case "jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "tiff" -> Kind.IMG;
            case "zip", "rar", "7z", "tar", "gz", "bz2", "xz" -> Kind.ZIP;
            default -> Kind.FILE;
        };
    }

    /**
     * Создаёт визуальный бейдж 32×36 для указанного типа.
     */
    public static StackPane create(Kind kind) {
        Region bg = new Region();
        bg.getStyleClass().addAll("file-badge", "file-badge--" + kind.cssVariant);
        bg.setMinSize(32, 36);
        bg.setPrefSize(32, 36);
        bg.setMaxSize(32, 36);

        Label label = new Label(kind.label);
        label.getStyleClass().addAll("file-badge-label", "file-badge-label--" + kind.cssVariant);

        StackPane stack = new StackPane(bg, label);
        stack.setMinSize(32, 36);
        stack.setPrefSize(32, 36);
        stack.setMaxSize(32, 36);
        stack.setAlignment(Pos.CENTER);
        return stack;
    }

    /** Удобный комбинированный конструктор: имя файла → бейдж. */
    public static StackPane forFile(String fileName) {
        return create(fromFileName(fileName));
    }
}
