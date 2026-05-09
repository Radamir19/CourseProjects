package ru.hse.jblockstorage.gui.dialogs;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
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
import ru.hse.jblockstorage.config.NodeConfig;
import ru.hse.jblockstorage.config.SeedNode;
import ru.hse.jblockstorage.gui.AppContext;
import ru.hse.jblockstorage.gui.config.NodeConfigStore;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Модальный диалог «Добавить seed-узел» — день 15 финал.
 *
 * <p>Заменяет стандартный {@code TextInputDialog}, который не
 * стилизуется под нашу тёмную тему: лейблы плохо читаются, иконка
 * системная, скругления macOS-нативные. Этот диалог — в едином стиле
 * с {@code AddContactDialog}/{@code ShareFileDialog}: один TextField,
 * info-баннер с подсказкой, кнопки Отмена/Добавить, inline-ошибки.
 *
 * <p><b>UX-послабление дня 15:</b> пользователь может ввести только
 * {@code host} без двоеточия и порта — подставится дефолтный
 * {@code 8080} (то же значение, что в {@link NodeConfig#defaults()}).
 * Это закрывает наиболее частый сценарий «Алиса дала мне свой адрес
 * 192.168.1.5» — Боб вводит как есть, и всё работает.
 */
public final class AddSeedDialog {

    private static final Logger log = LoggerFactory.getLogger(AddSeedDialog.class);

    private static final double INITIAL_WIDTH = 460;
    private static final double MIN_WIDTH = 420;
    private static final double MIN_HEIGHT = 300;

    private final AppContext context;
    private final Consumer<SeedNode> onAdded;

    private VBox bodyNode;
    private VBox footerNode;
    private TextField addressField;
    private Label addressError;
    private Button addBtn;
    private Stage window;

    /**
     * @param context контекст с {@link NodeConfigStore}
     * @param onAdded колбек после успешного добавления (Settings перерисует список)
     */
    public AddSeedDialog(AppContext context, Consumer<SeedNode> onAdded) {
        this.context = Objects.requireNonNull(context, "context");
        this.onAdded = Objects.requireNonNull(onAdded, "onAdded");
        if (context.configStore() == null) {
            throw new IllegalStateException(
                    "AddSeedDialog требует открытый config.properties");
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
        Label title = new Label("Добавить seed-узел");
        title.getStyleClass().add("modal-title");

        Label subtitle = new Label(
                "Адрес узла, к которому приложение будет подключаться "
                        + "при старте. Изменения применяются после перезапуска.");
        subtitle.getStyleClass().add("modal-subtitle");
        subtitle.setWrapText(true);

        VBox header = new VBox(6, title, subtitle);
        header.setPadding(new Insets(20, 20, 12, 20));

        // Поле адреса
        Label addressLabel = new Label("АДРЕС");
        addressLabel.getStyleClass().add("props-section-label");

        addressField = new TextField();
        addressField.setPromptText("например, 192.168.1.5  или  192.168.1.5:8080");
        addressField.getStyleClass().add("text-field");
        addressField.textProperty().addListener((obs, old, val) -> refreshState());

        Button pasteBtn = new Button("Вставить");
        pasteBtn.getStyleClass().add("btn-secondary");
        pasteBtn.setOnAction(e -> {
            String s = Clipboard.getSystemClipboard().getString();
            if (s != null) {
                addressField.setText(s.trim());
                addressField.requestFocus();
                addressField.positionCaret(addressField.getText().length());
            }
        });

        HBox.setHgrow(addressField, Priority.ALWAYS);
        HBox addressRow = new HBox(8, addressField, pasteBtn);
        addressRow.setAlignment(Pos.CENTER_LEFT);

        addressError = new Label();
        addressError.getStyleClass().add("form-error");
        addressError.setVisible(false);
        addressError.setManaged(false);
        addressError.setWrapText(true);

        VBox addressWrap = new VBox(6, addressLabel, addressRow, addressError);
        addressWrap.setPadding(new Insets(0, 20, 12, 20));

        // Подсказка
        VBox infoWrap = new VBox(buildInfoBanner());
        infoWrap.setPadding(new Insets(4, 20, 14, 20));

        bodyNode = new VBox(header, addressWrap, infoWrap);
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

        int defaultPort = NodeConfig.defaults().getListenPort();
        Label text = new Label(
                "Можно ввести только адрес — порт по умолчанию " + defaultPort
                        + ". Например, 192.168.1.5 или friend.example.com. "
                        + "Если у узла другой порт, укажите его через двоеточие: "
                        + "192.168.1.5:9090.");
        text.getStyleClass().add("info-banner-text");
        text.setWrapText(true);
        HBox.setHgrow(text, Priority.ALWAYS);

        HBox row = new HBox(10, icon, text);
        row.setAlignment(Pos.TOP_LEFT);
        HBox.setMargin(icon, new Insets(2, 0, 0, 0));
        row.getStyleClass().add("info-banner");
        return row;
    }

    private void refreshState() {
        addressError.setVisible(false);
        addressError.setManaged(false);
        addBtn.setDisable(parseAddress(addressField.getText()).isEmpty());
    }

    /**
     * Парсит ввод. Если есть двоеточие — host:port. Если нет — host
     * + дефолтный порт {@link NodeConfig#defaults()}. Empty/невалидно →
     * {@link Optional#empty()}.
     */
    private Optional<SeedNode> parseAddress(String raw) {
        if (raw == null) return Optional.empty();
        String s = raw.trim();
        if (s.isEmpty()) return Optional.empty();

        // Если есть двоеточие — пробуем как host:port
        if (s.contains(":")) {
            return NodeConfigStore.parseSingle(s);
        }

        // Только host — подставляем дефолтный порт
        try {
            int defaultPort = NodeConfig.defaults().getListenPort();
            return Optional.of(new SeedNode(s, defaultPort));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private void tryAdd() {
        Optional<SeedNode> parsed = parseAddress(addressField.getText());
        if (parsed.isEmpty()) {
            showError("Не удалось разобрать адрес. Введите host или host:port.");
            return;
        }

        SeedNode seed = parsed.get();
        try {
            boolean added = context.configStore().addSeed(seed);
            if (!added) {
                showError("Этот seed уже добавлен.");
                return;
            }
            log.info("Добавлен seed: {}", seed);
            onAdded.accept(seed);
            dismissWindow();
        } catch (Exception ex) {
            log.warn("Не удалось сохранить config.properties", ex);
            showError("Не удалось сохранить: "
                    + (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()));
        }
    }

    private void showError(String msg) {
        addressError.setText(msg);
        addressError.setVisible(true);
        addressError.setManaged(true);
    }

    public void showAsWindow(Stage ownerStage) {
        Stage stage = new Stage();
        stage.initOwner(ownerStage);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.initStyle(StageStyle.UNIFIED);
        stage.setTitle("Добавить seed-узел");
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
        addressField.requestFocus();

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
