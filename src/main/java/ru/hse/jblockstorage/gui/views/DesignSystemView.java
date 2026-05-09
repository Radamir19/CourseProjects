package ru.hse.jblockstorage.gui.views;

import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import ru.hse.jblockstorage.gui.theme.AppTheme;
import ru.hse.jblockstorage.gui.theme.ThemeManager;

import java.util.Objects;

/**
 * Временный showcase дня 12: проверяет, что обе темы корректно
 * применяются ко всем дизайн-токенам.
 *
 * <p><b>Не финальный экран!</b> Завтра (день 13) этот класс удаляется,
 * на его место становится настоящий {@code WelcomeView}. Сейчас он нужен
 * только для одного: глазами оценить, что палитра, типографика, кнопки и
 * статусы действительно соответствуют дизайн-решениям из summary,
 * и убедиться, что переключение Light → Dark → System работает на лету.
 *
 * <p>Намеренно собран программно (без FXML) — FXML-файлы добавим завтра
 * вместе с настоящими экранами Onboarding.
 */
public final class DesignSystemView {

    private final ThemeManager themeManager;
    private final Parent root;

    public DesignSystemView(ThemeManager themeManager) {
        this.themeManager = Objects.requireNonNull(themeManager, "themeManager");
        this.root = build();
    }

    public Parent getRoot() {
        return root;
    }

    private Parent build() {
        VBox content = new VBox(28);
        content.setPadding(new Insets(40, 48, 48, 48));
        content.getChildren().addAll(
                buildHeader(),
                buildSection("ПАЛИТРА", buildPalette()),
                buildSection("ТИПОГРАФИКА", buildTypography()),
                buildSection("КНОПКИ", buildButtons()),
                buildSection("СТАТУСЫ", buildStatuses()),
                buildSection("КАРТОЧКА", buildCardSample()),
                buildFooter()
        );

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("app-scroll");

        StackPane wrapper = new StackPane(scroll);
        wrapper.getStyleClass().add("app-background");
        return wrapper;
    }

    // ------------------------------------------------------------------
    // Header: логотип-градиент + заголовок + переключатель темы
    // ------------------------------------------------------------------

    private Parent buildHeader() {
        Region logo = new Region();
        logo.getStyleClass().add("brand-logo");
        logo.setPrefSize(48, 48);
        logo.setMinSize(48, 48);

        Label title = new Label("JBlockStorage");
        title.getStyleClass().add("h1");

        Label subtitle = new Label("День 12 · каркас GUI · проверка тем");
        subtitle.getStyleClass().add("text-secondary");

        VBox titles = new VBox(2, title, subtitle);

        HBox left = new HBox(16, logo, titles);
        left.setAlignment(Pos.CENTER_LEFT);

        // Переключатель темы
        Label themeLabel = new Label("Тема");
        themeLabel.getStyleClass().add("label-section");

        ComboBox<AppTheme> themeBox = new ComboBox<>();
        themeBox.getItems().setAll(AppTheme.values());
        themeBox.valueProperty().bindBidirectional(themeManager.themeProperty());
        themeBox.getStyleClass().add("theme-picker");

        VBox themeSwitcher = new VBox(6, themeLabel, themeBox);
        themeSwitcher.setAlignment(Pos.CENTER_RIGHT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox header = new HBox(left, spacer, themeSwitcher);
        header.setAlignment(Pos.CENTER_LEFT);
        return header;
    }

    // ------------------------------------------------------------------
    // Палитра: цветные плитки + hex-подписи
    // ------------------------------------------------------------------

    private Parent buildPalette() {
        FlowPane pane = new FlowPane(12, 12);

        pane.getChildren().addAll(
                swatch("Фон primary",   "color-bg-primary"),
                swatch("Фон secondary", "color-bg-secondary"),
                swatch("Карточка",      "color-card-bg"),
                swatch("Бордер",        "color-border"),

                swatch("Текст primary",   "color-text-primary"),
                swatch("Текст secondary", "color-text-secondary"),
                swatch("Текст hint",      "color-text-hint"),
                swatch("Акцент",          "color-accent"),

                swatch("Success", "color-success"),
                swatch("Warning", "color-warning"),
                swatch("Danger",  "color-danger")
        );
        return pane;
    }

    /** Цветной квадрат с подписью. Цвет подгружается из CSS через looked-up color. */
    private Parent swatch(String label, String colorVar) {
        Region tile = new Region();
        tile.setPrefSize(72, 72);
        tile.setMinSize(72, 72);
        tile.getStyleClass().addAll("swatch", "swatch--" + colorVar);

        Label name = new Label(label);
        name.getStyleClass().add("text-secondary");

        Label hint = new Label("-" + colorVar);
        hint.getStyleClass().add("mono-hint");

        VBox box = new VBox(6, tile, name, hint);
        box.setAlignment(Pos.TOP_LEFT);
        box.setPrefWidth(140);
        return box;
    }

    // ------------------------------------------------------------------
    // Типографика
    // ------------------------------------------------------------------

    private Parent buildTypography() {
        Label h1 = new Label("Заголовок H1 — 28px, weight 500");
        h1.getStyleClass().add("h1");

        Label h2 = new Label("Заголовок H2 — 20px, weight 500");
        h2.getStyleClass().add("h2");

        Label body = new Label("Основной текст 14px regular — здесь обычные предложения, "
                + "формы и таблицы. Должен читаться комфортно на обоих фонах.");
        body.getStyleClass().add("body");
        body.setWrapText(true);

        Label secondary = new Label("Вспомогательный текст 12px (text-secondary)");
        secondary.getStyleClass().add("text-secondary");

        Label section = new Label("ЛЕЙБЛ СЕКЦИИ · 11PX · UPPERCASE");
        section.getStyleClass().add("label-section");

        Label mono = new Label("Mono · хеши · ID транзакций · 7f3a-2c1d-9e8b-bb40");
        mono.getStyleClass().add("mono-hint");

        VBox box = new VBox(10, h1, h2, body, secondary, section, mono);
        return box;
    }

    // ------------------------------------------------------------------
    // Кнопки
    // ------------------------------------------------------------------

    private Parent buildButtons() {
        Button primary = new Button("Создать профиль");
        primary.getStyleClass().add("btn-primary");

        Button secondary = new Button("Войти");
        secondary.getStyleClass().add("btn-secondary");

        Button ghost = new Button("Подробнее");
        ghost.getStyleClass().add("btn-ghost");

        Button danger = new Button("Удалить");
        danger.getStyleClass().add("btn-danger");

        Button disabled = new Button("Загрузить");
        disabled.getStyleClass().add("btn-primary");
        disabled.setDisable(true);

        FlowPane pane = new FlowPane(12, 12, primary, secondary, ghost, danger, disabled);
        return pane;
    }

    // ------------------------------------------------------------------
    // Статусы (точки + текст) — пригодятся для «Реплик 3/3», «Сеть стабильна»
    // ------------------------------------------------------------------

    private Parent buildStatuses() {
        VBox box = new VBox(8,
                statusRow("status-success", "Сеть стабильна · 12 подключений"),
                statusRow("status-warning", "Слабая связь · 2 подключения"),
                statusRow("status-danger",  "Нет связи"),
                statusRow("status-success", "Реплик 3/3 · файл доступен"),
                statusRow("status-warning", "Реплик 1/3 · нужна репарация")
        );
        return box;
    }

    private Parent statusRow(String dotClass, String text) {
        Region dot = new Region();
        dot.getStyleClass().addAll("status-dot", dotClass);
        dot.setPrefSize(8, 8);
        dot.setMinSize(8, 8);

        Label label = new Label(text);
        label.getStyleClass().add("body");

        HBox row = new HBox(10, dot, label);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    // ------------------------------------------------------------------
    // Карточка — образец для плиток в «Мои файлы», «Свойства», «Контакты»
    // ------------------------------------------------------------------

    private Parent buildCardSample() {
        Label title = new Label("Контракт_2026.pdf");
        title.getStyleClass().add("h2");

        Label meta = new Label("12 МБ · загружен сегодня в 14:32 · 25 шардов · реплик 3/3");
        meta.getStyleClass().add("text-secondary");

        Region pdfBadge = new Region();
        pdfBadge.getStyleClass().addAll("file-badge", "file-badge--pdf");
        pdfBadge.setPrefSize(40, 48);
        pdfBadge.setMinSize(40, 48);

        Label pdfLabel = new Label("PDF");
        pdfLabel.getStyleClass().addAll("file-badge-label", "file-badge-label--pdf");

        StackPane badgeStack = new StackPane(pdfBadge, pdfLabel);
        badgeStack.setMinSize(40, 48);
        badgeStack.setPrefSize(40, 48);

        VBox titleBlock = new VBox(2, title, meta);

        HBox row = new HBox(16, badgeStack, titleBlock);
        row.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(row);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(16));
        card.setMaxWidth(560);
        return card;
    }

    // ------------------------------------------------------------------
    // Footer — резолвенная тема (для отладки)
    // ------------------------------------------------------------------

    private Parent buildFooter() {
        Label resolved = new Label();
        resolved.getStyleClass().add("mono-hint");
        resolved.textProperty().bind(Bindings.createStringBinding(
                () -> "Текущая тема: " + themeManager.getTheme()
                        + " (применяется как " + themeManager.getResolvedTheme() + ")",
                themeManager.themeProperty()
        ));
        return resolved;
    }

    // ------------------------------------------------------------------
    // Helper: секция с лейблом сверху
    // ------------------------------------------------------------------

    private Parent buildSection(String title, Parent body) {
        Label section = new Label(title);
        section.getStyleClass().add("label-section");

        VBox box = new VBox(12, section, body);
        return box;
    }
}
