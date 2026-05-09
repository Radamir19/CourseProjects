package ru.hse.jblockstorage.gui.dialogs;

import javafx.beans.binding.Bindings;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.hse.jblockstorage.gui.AppContext;
import ru.hse.jblockstorage.gui.components.ContactAvatar;
import ru.hse.jblockstorage.gui.components.FormatUtils;
import ru.hse.jblockstorage.gui.contacts.Contact;
import ru.hse.jblockstorage.gui.contacts.ContactStore;
import ru.hse.jblockstorage.gui.download.DownloadProgressState;

import java.util.Objects;

/**
 * Модальный диалог прогресса скачивания — день 15, ТЗ п. 4.1.5.3
 * («*Менеджер загрузок: визуализация процесса, скорость, список
 * узлов, с которых идёт скачивание*»).
 *
 * <p>Структура (по аналогии с UploadDialog, но проще — без этапов):
 * <pre>
 *   ┌──── Скачивание ────────────────────┐
 *   │  important.bin · 5,0 МБ            │
 *   │  ─────────────────────────         │
 *   │  ████████░░░░  67%   2,3 МБ/с      │
 *   │  Шард 4 из 6                       │
 *   │                                    │
 *   │  С УЗЛОВ                           │
 *   │  • Боб (3 шарда · 1,5 МБ)          │
 *   │  • Алиса (1 шард · 0,5 МБ)         │
 *   │                                    │
 *   │              [Закрыть]             │
 *   └────────────────────────────────────┘
 * </pre>
 *
 * <p>Кнопка [Закрыть] активна только в DONE/FAILED — во время
 * DOWNLOADING её нет (показываем только дисэйбленный прогресс). Это
 * предотвращает «закрыл — пользователь не понял, отменилось ли».
 *
 * <p>Имена узлов: если nodeId матчится с контактом из адресной книги,
 * показываем имя контакта; если это self — показываем «Локальное
 * хранилище»; иначе короткий публичный ключ.
 */
public final class DownloadProgressDialog {

    private static final Logger log = LoggerFactory.getLogger(DownloadProgressDialog.class);

    private static final double INITIAL_WIDTH = 460;
    private static final double MIN_WIDTH = 420;
    private static final double MIN_HEIGHT = 360;

    private final AppContext context;
    private final DownloadProgressState state;

    private VBox bodyNode;
    private VBox footerNode;
    private VBox storersList;
    private Button closeBtn;
    private Stage window;

    /** Listeners, которые нужно отписать на close, чтобы не держать утечку. */
    private ListChangeListener<DownloadProgressState.StorerStat> storersListener;

    public DownloadProgressDialog(AppContext context, DownloadProgressState state) {
        this.context = Objects.requireNonNull(context, "context");
        this.state = Objects.requireNonNull(state, "state");
        build();
    }

    public Parent getRoot() {
        VBox card = new VBox(bodyNode, footerNode);
        card.getStyleClass().add("modal-card");
        return card;
    }

    private void build() {
        // ----- Заголовок -----
        Label title = new Label();
        title.getStyleClass().add("modal-title");
        title.textProperty().bind(Bindings.createStringBinding(() -> {
            switch (state.getStage()) {
                case DONE:        return "Файл скачан";
                case FAILED:      return "Не удалось скачать";
                default:          return "Скачивание файла";
            }
        }, state.stageProperty()));

        Label subtitle = new Label();
        subtitle.getStyleClass().add("modal-subtitle");
        subtitle.setWrapText(true);
        subtitle.textProperty().bind(Bindings.createStringBinding(() -> {
            String name = state.getFileName();
            long size = state.getFileSize();
            if (size > 0) {
                return name + " · " + FormatUtils.humanSize(size);
            }
            return name;
        }, state.fileNameProperty(), state.fileSizeProperty()));

        VBox header = new VBox(6, title, subtitle);
        header.setPadding(new Insets(20, 20, 12, 20));

        // ----- Прогресс-бар + проценты + скорость -----
        ProgressBar progressBar = new ProgressBar();
        progressBar.progressProperty().bind(state.progressProperty());
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setPrefHeight(8);
        HBox.setHgrow(progressBar, Priority.ALWAYS);

        Label percentLabel = new Label();
        percentLabel.getStyleClass().add("props-row-value");
        percentLabel.textProperty().bind(Bindings.createStringBinding(
                () -> Math.round(state.getProgress() * 100) + " %",
                state.progressProperty()));

        Label speedLabel = new Label();
        speedLabel.getStyleClass().add("props-row-value-mono");
        speedLabel.textProperty().bind(Bindings.createStringBinding(() -> {
            double bps = state.getSpeedBps();
            if (state.getStage() != DownloadProgressState.Stage.DOWNLOADING) {
                return "";
            }
            return FormatUtils.humanSize((long) bps) + "/с";
        }, state.speedBpsProperty(), state.stageProperty()));

        HBox progressRow = new HBox(10, progressBar, percentLabel, speedLabel);
        progressRow.setAlignment(Pos.CENTER_LEFT);

        Label shardLabel = new Label();
        shardLabel.getStyleClass().add("section-subtitle");
        shardLabel.textProperty().bind(Bindings.createStringBinding(() -> {
            int total = state.getTotalShards();
            int cur = state.getCurrentShard();
            DownloadProgressState.Stage st = state.getStage();
            if (total == 0) return "";
            if (st == DownloadProgressState.Stage.DONE) {
                return "Готово · " + total + " " + FormatUtils.pluralShards(total);
            }
            if (st == DownloadProgressState.Stage.FAILED) {
                return "";
            }
            return "Шард " + Math.max(1, cur) + " из " + total;
        }, state.currentShardProperty(),
                state.totalShardsProperty(), state.stageProperty()));

        VBox progressWrap = new VBox(8, progressRow, shardLabel);
        progressWrap.setPadding(new Insets(0, 20, 12, 20));

        // ----- Список узлов -----
        Label storersLabel = new Label("С УЗЛОВ");
        storersLabel.getStyleClass().add("props-section-label");

        storersList = new VBox(4);
        rerenderStorers(); // первый вызов — пустой список → плейсхолдер

        // Подписка на список — обновляем при каждом изменении.
        storersListener = c -> rerenderStorers();
        state.getStorers().addListener(storersListener);

        VBox storersWrap = new VBox(8, storersLabel, storersList);
        storersWrap.setPadding(new Insets(0, 20, 12, 20));

        // ----- Сообщение об ошибке (если есть) -----
        Label errorLabel = new Label();
        errorLabel.getStyleClass().add("form-error");
        errorLabel.setWrapText(true);
        errorLabel.textProperty().bind(state.errorTextProperty());
        errorLabel.visibleProperty().bind(
                state.stageProperty().isEqualTo(DownloadProgressState.Stage.FAILED));
        errorLabel.managedProperty().bind(errorLabel.visibleProperty());

        VBox errorWrap = new VBox(errorLabel);
        errorWrap.setPadding(new Insets(0, 20, 12, 20));

        bodyNode = new VBox(header, progressWrap, storersWrap, errorWrap);
        bodyNode.setFillWidth(true);

        // ----- Footer -----
        Region divider = new Region();
        divider.getStyleClass().add("modal-section-divider");

        closeBtn = new Button("Закрыть");
        closeBtn.getStyleClass().add("btn-secondary");
        closeBtn.setOnAction(e -> dismissWindow());
        // Кнопка скрыта во время DOWNLOADING.
        closeBtn.visibleProperty().bind(
                state.stageProperty().isNotEqualTo(DownloadProgressState.Stage.DOWNLOADING));
        closeBtn.managedProperty().bind(closeBtn.visibleProperty());
        closeBtn.textProperty().bind(Bindings.createStringBinding(() ->
                        state.getStage() == DownloadProgressState.Stage.DONE ? "OK" : "Закрыть",
                state.stageProperty()));

        Label busyLabel = new Label("Идёт скачивание…");
        busyLabel.getStyleClass().add("section-subtitle");
        busyLabel.visibleProperty().bind(
                state.stageProperty().isEqualTo(DownloadProgressState.Stage.DOWNLOADING));
        busyLabel.managedProperty().bind(busyLabel.visibleProperty());

        Region spacer2 = new Region();
        HBox.setHgrow(spacer2, Priority.ALWAYS);

        HBox btnRow = new HBox(8, busyLabel, spacer2, closeBtn);
        btnRow.setAlignment(Pos.CENTER_RIGHT);
        btnRow.setPadding(new Insets(14, 20, 16, 20));

        footerNode = new VBox(divider, btnRow);
    }

    /**
     * Перерисовывает список узлов. Вызывается при каждом изменении
     * {@code state.getStorers()}. Простой подход — clear+add — нормален
     * для типичного N=2..6 хранителей.
     */
    private void rerenderStorers() {
        storersList.getChildren().clear();
        if (state.getStorers().isEmpty()) {
            Label empty = new Label("Пока нет данных…");
            empty.getStyleClass().add("section-subtitle");
            storersList.getChildren().add(empty);
            return;
        }
        for (DownloadProgressState.StorerStat stat : state.getStorers()) {
            storersList.getChildren().add(buildStorerRow(stat));
        }
    }

    private HBox buildStorerRow(DownloadProgressState.StorerStat stat) {
        String displayName = displayNameOf(stat.getNodeId());
        StackPane avatar = ContactAvatar.create(displayName, ContactAvatar.SIZE_SMALL);

        Label name = new Label(displayName);
        name.getStyleClass().add("props-row-value");

        Label meta = new Label(stat.getShardCount() + " "
                + FormatUtils.pluralShards(stat.getShardCount())
                + " · " + FormatUtils.humanSize(stat.getTotalBytes()));
        meta.getStyleClass().add("props-row-value-mono");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(8, avatar, name, spacer, meta);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** Имя для UI: контакт → self → короткий ключ. */
    private String displayNameOf(String nodeId) {
        if (nodeId == null) return "?";
        // 1. Self?
        try {
            String selfId = context.node().selfNodeId();
            if (nodeId.equals(selfId)) return "Локальное хранилище";
        } catch (Exception ignored) {}
        // 2. Контакт?
        ContactStore cs = context.contactStore();
        if (cs != null) {
            Contact c = cs.findByKey(nodeId);
            if (c != null) return c.getName();
        }
        // 3. Короткий ключ
        return FormatUtils.shortenPubKey(nodeId);
    }

    // ------------------------------------------------------------------
    // Окно
    // ------------------------------------------------------------------

    public void showAsWindow(Stage ownerStage) {
        Stage stage = new Stage();
        stage.initOwner(ownerStage);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.initStyle(StageStyle.UNIFIED);
        stage.setTitle("Скачивание");
        stage.setResizable(true);
        stage.setMinWidth(MIN_WIDTH);
        stage.setMinHeight(MIN_HEIGHT);

        VBox card = new VBox(bodyNode, footerNode);
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

        // ESC закрывает только в финальных состояниях, иначе игнорируется.
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE
                    && state.getStage() != DownloadProgressState.Stage.DOWNLOADING) {
                stage.close();
            }
        });

        // Если пользователь жмёт «крестик» окна, не разрешаем во время
        // активной фазы — иначе остается фоновый Task, а UI пропадает.
        stage.setOnCloseRequest(ev -> {
            if (state.getStage() == DownloadProgressState.Stage.DOWNLOADING) {
                ev.consume();
            } else {
                cleanupListeners();
            }
        });

        stage.setScene(scene);
        stage.setWidth(INITIAL_WIDTH);
        autoFitInitialHeight(stage, scene);

        this.window = stage;
        stage.show(); // не showAndWait — запуск Task'а блокировать не надо
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
        cleanupListeners();
        if (window != null) {
            window.close();
            window = null;
        }
        // Сбрасываем state, чтобы следующий download стартовал с чистого листа.
        state.reset();
    }

    private void cleanupListeners() {
        if (storersListener != null) {
            try {
                state.getStorers().removeListener(storersListener);
            } catch (Exception ignore) {}
            storersListener = null;
        }
    }
}
