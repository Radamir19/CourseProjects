package ru.hse.jblockstorage.gui.dialogs;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.hse.jblockstorage.gui.AppContext;
import ru.hse.jblockstorage.gui.contacts.Contact;
import ru.hse.jblockstorage.gui.contacts.ContactStore;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Модальный диалог «Добавить контакт» — день 14.
 *
 * <p>Структура (по мокапу {@code add_contact_dialog.html}):
 * <pre>
 *   ┌──── Добавить контакт ────────────────────────────────┐
 *   │ Имя контакта                                         │
 *   │ [_______________________________]                    │
 *   │                                                      │
 *   │ Публичный ключ          [Вставить из буфера]         │
 *   │ ┌──────────────────────────────────────────────┐     │
 *   │ │ MIIBIjANBgkqhkiG9w0BAQEFAAOC...              │     │
 *   │ └──────────────────────────────────────────────┘     │
 *   │                                                      │
 *   │ ⓘ Попросите получателя поделиться своим публичным   │
 *   │   ключом из раздела «Настройки → Безопасность».      │
 *   │                                                      │
 *   │              [Отмена]  [Добавить]                    │
 *   └──────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>Структура окна — как в {@link ShareFileDialog} и
 * {@link FilePropertiesDialog}: отдельный {@link Stage} с
 * {@link StageStyle#UNIFIED}, body в {@link ScrollPane}, footer
 * (Отмена/Добавить) фиксирован.
 *
 * <p>Валидация дублирует {@link ContactStore#add}: имя 1..64, ключ ≥200
 * символов в Base64-алфавите. Дубликаты по ключу ловятся
 * {@link ContactStore.DuplicateKeyException} и показываются как
 * сообщение «Этот ключ уже есть под именем X». Окно при ошибке
 * остаётся открытым, чтобы пользователь мог поправить ввод.
 */
public final class AddContactDialog {

    private static final Logger log = LoggerFactory.getLogger(AddContactDialog.class);

    private static final double INITIAL_WIDTH = 460;
    private static final double MIN_WIDTH = 420;
    private static final double MIN_HEIGHT = 380;

    private final AppContext context;
    private final Consumer<Contact> onAdded;

    private VBox bodyNode;
    private VBox footerNode;
    private TextField nameField;
    private TextArea keyArea;
    private Label nameError;
    private Label keyError;
    private Button addBtn;
    private Stage window;

    /**
     * @param context контекст с {@link ContactStore}
     * @param onAdded колбек после успешного добавления (UI Contact-list
     *                перерисовывается через него)
     */
    public AddContactDialog(AppContext context, Consumer<Contact> onAdded) {
        this.context = Objects.requireNonNull(context, "context");
        this.onAdded = Objects.requireNonNull(onAdded, "onAdded");
        if (context.contactStore() == null) {
            throw new IllegalStateException(
                    "AddContactDialog требует открытую адресную книгу");
        }
        build();
    }

    public Parent getRoot() {
        VBox card = new VBox(bodyNode, footerNode);
        card.getStyleClass().add("modal-card");
        return card;
    }

    private void build() {
        // Заголовок
        Label title = new Label("Добавить контакт");
        title.getStyleClass().add("modal-title");

        Label subtitle = new Label(
                "Контакты — локальная адресная книга. "
                        + "Сохраняется только на этом узле.");
        subtitle.getStyleClass().add("modal-subtitle");
        subtitle.setWrapText(true);

        VBox header = new VBox(6, title, subtitle);
        header.setPadding(new Insets(20, 20, 12, 20));

        // Поле «Имя»
        Label nameLabel = new Label("ИМЯ КОНТАКТА");
        nameLabel.getStyleClass().add("props-section-label");

        nameField = new TextField();
        nameField.setPromptText("Например: Боб");
        nameField.getStyleClass().add("text-field");
        nameField.textProperty().addListener((obs, old, val) -> refreshState());

        nameError = new Label();
        nameError.getStyleClass().add("form-error");
        nameError.setVisible(false);
        nameError.setManaged(false);
        nameError.setWrapText(true);

        VBox nameWrap = new VBox(6, nameLabel, nameField, nameError);
        nameWrap.setPadding(new Insets(0, 20, 12, 20));

        // Поле «Публичный ключ»
        Label keyLabel = new Label("ПУБЛИЧНЫЙ КЛЮЧ");
        keyLabel.getStyleClass().add("props-section-label");

        Button pasteBtn = new Button("Вставить из буфера");
        pasteBtn.getStyleClass().add("btn-secondary");
        pasteBtn.setOnAction(e -> {
            String s = Clipboard.getSystemClipboard().getString();
            if (s != null) {
                keyArea.setText(s.trim());
                keyArea.requestFocus();
                keyArea.positionCaret(keyArea.getText().length());
            }
        });

        Region spacer1 = new Region();
        HBox.setHgrow(spacer1, Priority.ALWAYS);

        HBox keyTopRow = new HBox(8, keyLabel, spacer1, pasteBtn);
        keyTopRow.setAlignment(Pos.CENTER_LEFT);

        keyArea = new TextArea();
        keyArea.setPromptText("MIIBIjANBgkqhkiG9w0BAQEFAAOC…");
        keyArea.setWrapText(true);
        keyArea.setPrefRowCount(4);
        keyArea.getStyleClass().add("share-paste-area");
        keyArea.textProperty().addListener((obs, old, val) -> refreshState());

        keyError = new Label();
        keyError.getStyleClass().add("form-error");
        keyError.setVisible(false);
        keyError.setManaged(false);
        keyError.setWrapText(true);

        VBox keyWrap = new VBox(8, keyTopRow, keyArea, keyError);
        keyWrap.setPadding(new Insets(0, 20, 12, 20));

        // Подсказка
        VBox infoWrap = new VBox(buildInfoBanner());
        infoWrap.setPadding(new Insets(4, 20, 14, 20));

        bodyNode = new VBox(header, nameWrap, keyWrap, infoWrap);
        bodyNode.setFillWidth(true);

        // Footer
        Region divider = new Region();
        divider.getStyleClass().add("modal-section-divider");

        Button cancel = new Button("Отмена");
        cancel.getStyleClass().add("btn-secondary");
        cancel.setOnAction(e -> dismissWindow());

        addBtn = new Button("Добавить");
        addBtn.getStyleClass().add("btn-primary");
        addBtn.setDefaultButton(true);
        addBtn.setDisable(true);
        addBtn.setOnAction(e -> tryAdd());

        Region spacer2 = new Region();
        HBox.setHgrow(spacer2, Priority.ALWAYS);

        HBox btnRow = new HBox(8, spacer2, cancel, addBtn);
        btnRow.setAlignment(Pos.CENTER_RIGHT);
        btnRow.setPadding(new Insets(14, 20, 16, 20));

        footerNode = new VBox(divider, btnRow);
    }

    /** Синий info-баннер с подсказкой откуда взять публичный ключ получателя. */
    private HBox buildInfoBanner() {
        Label icon = new Label("i");
        icon.setStyle(
                "-fx-min-width: 16; -fx-min-height: 16;"
                        + "-fx-pref-width: 16; -fx-pref-height: 16;"
                        + "-fx-max-width: 16; -fx-max-height: 16;"
                        + "-fx-background-color: -color-accent;"
                        + "-fx-text-fill: white;"
                        + "-fx-background-radius: 8;"
                        + "-fx-alignment: center;"
                        + "-fx-font-size: 11px; -fx-font-weight: 700;"
        );

        Label text = new Label(
                "Попросите получателя поделиться своим публичным ключом — "
                        + "его можно скопировать в разделе «Настройки → "
                        + "Безопасность». Это безопасно: публичный ключ "
                        + "не открывает доступ к чужим файлам.");
        text.getStyleClass().add("info-banner-text");
        text.setWrapText(true);
        HBox.setHgrow(text, Priority.ALWAYS);

        HBox row = new HBox(10, icon, text);
        row.setAlignment(Pos.TOP_LEFT);
        HBox.setMargin(icon, new Insets(2, 0, 0, 0));
        row.getStyleClass().add("info-banner");
        return row;
    }

    /** Обновляет {@code disabled}-состояние «Добавить» по содержимому полей. */
    private void refreshState() {
        // Очищаем сообщения при правке — пользователь видит:
        // «нажал Добавить → ошибка → правлю → ошибка пропадает».
        hideErrors();

        boolean nameOk = nameField.getText() != null
                && !nameField.getText().trim().isEmpty();
        boolean keyOk = sanitizedKey() != null;
        addBtn.setDisable(!(nameOk && keyOk));
    }

    /**
     * Очищенный ключ: без пробелов, прошедший базовую валидацию, или
     * {@code null}, если ввод не похож на ключ.
     */
    private String sanitizedKey() {
        String raw = keyArea.getText();
        if (raw == null) return null;
        String s = raw.replaceAll("\\s+", "");
        if (s.length() < ContactStore.MIN_KEY_LENGTH) return null;
        if (!s.matches("^[A-Za-z0-9+/=]+$")) return null;
        return s;
    }

    /** Пытается добавить контакт; на ошибке оставляет окно открытым. */
    private void tryAdd() {
        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        String key = sanitizedKey();
        if (name.isEmpty()) {
            showNameError("Введите имя контакта");
            return;
        }
        if (key == null) {
            showKeyError("Публичный ключ должен быть в Base64 и не короче "
                    + ContactStore.MIN_KEY_LENGTH + " символов");
            return;
        }

        // Подсказка: если пользователь пытается добавить свой собственный
        // ключ — это явно ошибка. Бэкенд бы это переварил, но в адресной
        // книге своего собственного аккаунта не должно быть.
        String selfId = context.node().selfNodeId();
        if (key.equals(selfId)) {
            showKeyError("Это ваш собственный публичный ключ. "
                    + "Адресная книга — для других участников сети.");
            return;
        }

        try {
            Contact added = context.contactStore().add(name, key);
            log.info("Добавлен контакт: «{}»", added.getName());
            onAdded.accept(added);
            dismissWindow();
        } catch (ContactStore.DuplicateKeyException e) {
            // Очень частый случай — пользователь повторно копирует чей-то ключ.
            // Показываем явное сообщение «уже есть под именем X».
            showKeyError(e.getMessage());
        } catch (IllegalArgumentException e) {
            // Что-то с валидацией — относим к полю ключа (имя мы уже
            // проверили выше, остальное — про ключ).
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("им")) {
                showNameError(e.getMessage());
            } else {
                showKeyError(e.getMessage());
            }
        } catch (Exception e) {
            log.warn("Не удалось добавить контакт", e);
            Alert a = new Alert(Alert.AlertType.ERROR);
            a.initOwner(window);
            a.setHeaderText("Не удалось добавить контакт");
            a.setContentText(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            a.showAndWait();
        }
    }

    private void showNameError(String msg) {
        nameError.setText(msg);
        nameError.setVisible(true);
        nameError.setManaged(true);
    }

    private void showKeyError(String msg) {
        keyError.setText(msg);
        keyError.setVisible(true);
        keyError.setManaged(true);
    }

    private void hideErrors() {
        nameError.setVisible(false);
        nameError.setManaged(false);
        keyError.setVisible(false);
        keyError.setManaged(false);
    }

    // ------------------------------------------------------------------
    // Окно
    // ------------------------------------------------------------------

    /** Показывает диалог как отдельное нативное окно (см. UploadDialog). */
    public void showAsWindow(Stage ownerStage) {
        Stage stage = new Stage();
        stage.initOwner(ownerStage);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.initStyle(StageStyle.UNIFIED);
        stage.setTitle("Добавить контакт");
        stage.setResizable(true);
        stage.setMinWidth(MIN_WIDTH);
        stage.setMinHeight(MIN_HEIGHT);

        ScrollPane scroll = new ScrollPane(bodyNode);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.getStyleClass().add("modal-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox card = new VBox(scroll, footerNode);
        card.getStyleClass().add("modal-card");

        Scene scene = new Scene(card);
        if (ownerStage.getScene() != null) {
            scene.getStylesheets().setAll(ownerStage.getScene().getStylesheets());
            for (String cls : ownerStage.getScene().getRoot().getStyleClass()) {
                if (!scene.getRoot().getStyleClass().contains(cls)) {
                    scene.getRoot().getStyleClass().add(cls);
                }
            }
        }

        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) stage.close();
        });

        stage.setScene(scene);
        stage.setWidth(INITIAL_WIDTH);
        autoFitInitialHeight(stage, scene);
        // Ставим фокус на имя — это первое, что заполняет пользователь.
        nameField.requestFocus();

        this.window = stage;
        stage.showAndWait();
    }

    private static void autoFitInitialHeight(Stage stage, Scene scene) {
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        double naturalHeight = scene.getRoot().prefHeight(INITIAL_WIDTH);
        double maxByScreen = Screen.getPrimary().getVisualBounds().getHeight() * 0.8;
        double finalHeight = Math.max(MIN_HEIGHT, Math.min(naturalHeight, maxByScreen));
        stage.setHeight(finalHeight + 28);
    }

    public void dismissWindow() {
        if (window != null) {
            window.close();
            window = null;
        }
    }
}
