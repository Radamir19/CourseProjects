package ru.hse.jblockstorage.gui.components;

import javafx.beans.binding.Bindings;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import ru.hse.jblockstorage.gui.upload.UploadProgressState;
import ru.hse.jblockstorage.gui.upload.UploadProgressState.Stage;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Большая карточка прогресса загрузки в разделе «Мои файлы» —
 * по мокапу {@code upload_progress.html}.
 *
 * <p>Структура (по мокапу):
 * <pre>
 *  ┌──────────────────────────────────────────────────────────┐
 *  │ [PDF]  диплом_финальная.pdf                          62% │
 *  │        15 из 25 шардов · 4.8 / 12.4 МБ          ~7 секунд │
 *  │ ▓▓▓▓▓▓▓▓▓▓▓▓░░░░░░░░░░  4px progress-bar               │
 *  │                                                          │
 *  │ ●Шифрование  ●Шардирование  ●Передача  ○Подтверждение  ○Готово
 *  └──────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>Чекпоинты этапов:
 * <ul>
 *   <li>● <b>зелёная</b> точка + цвет текста primary — этап завершён</li>
 *   <li>● <b>синяя</b> точка + цвет текста primary — текущий этап</li>
 *   <li>○ <b>серая</b> точка + цвет текста hint — будущий этап</li>
 * </ul>
 *
 * <p><b>Реализация прогресс-бара</b>: track — это {@code HBox(fill, spacer)}.
 * Spacer имеет {@code HGrow.ALWAYS} и забирает остаток места. Ширина
 * {@code fill} биндится через {@code track.width × progress}. Никаких
 * ручных listener'ов на widthProperty — раньше они приводили к
 * layout-циклу.
 *
 * <p>Карточка автоматически скрывается, когда {@code state.isVisible()}
 * становится false (через несколько секунд после завершения).
 */
public final class MyFilesUploadCard {

    private final UploadProgressState state;
    private final VBox root;

    private final StackPane badgeSlot = new StackPane();
    private final Label fileName = new Label("");
    private final Label progressMeta = new Label("");
    private final Label percent = new Label("0%");
    private final Label eta = new Label("");
    private final Region progressFill = new Region();

    private final Map<Stage, Checkpoint> checkpoints = new EnumMap<>(Stage.class);

    public MyFilesUploadCard(UploadProgressState state) {
        this.state = Objects.requireNonNull(state, "state");
        this.root = build();
        bind();
        applyVisibility(state.isVisible());
    }

    public Node getRoot() {
        return root;
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    private VBox build() {
        // ----- Верхняя строка: бейдж + (имя/мета)  +  (процент/eta) -----
        badgeSlot.setMinSize(32, 36);
        badgeSlot.setPrefSize(32, 36);
        badgeSlot.setMaxSize(32, 36);

        fileName.getStyleClass().add("upload-card-filename");
        fileName.setMaxWidth(Double.MAX_VALUE);

        progressMeta.getStyleClass().add("upload-card-meta");
        progressMeta.setMaxWidth(Double.MAX_VALUE);

        VBox texts = new VBox(2, fileName, progressMeta);
        HBox.setHgrow(texts, Priority.ALWAYS);
        texts.setMaxWidth(Double.MAX_VALUE);

        percent.getStyleClass().add("upload-card-percent");
        eta.getStyleClass().add("upload-card-eta");
        VBox right = new VBox(2, percent, eta);
        right.setAlignment(Pos.CENTER_RIGHT);

        HBox topRow = new HBox(12, badgeSlot, texts, right);
        topRow.setAlignment(Pos.CENTER_LEFT);

        // ----- Полоса прогресса 4px ------
        progressFill.getStyleClass().add("upload-card-progress-fill");
        progressFill.setMinHeight(4);
        progressFill.setPrefHeight(4);
        progressFill.setMaxHeight(4);
        HBox.setHgrow(progressFill, Priority.NEVER);

        Region trackTail = new Region();
        HBox.setHgrow(trackTail, Priority.ALWAYS);
        trackTail.setMinWidth(0);

        HBox track = new HBox(progressFill, trackTail);
        track.getStyleClass().add("upload-card-progress-track");
        track.setAlignment(Pos.CENTER_LEFT);
        track.setMinHeight(4);
        track.setPrefHeight(4);
        track.setMaxHeight(4);
        track.setMaxWidth(Double.MAX_VALUE);

        // Ширина заливки = ширина track × прогресс. Bindings + HGrow.ALWAYS
        // у tail'а гарантируют отсутствие layout-цикла.
        progressFill.prefWidthProperty().bind(
                track.widthProperty().multiply(state.progressProperty()));

        // ----- Чекпоинты этапов: 5 равных колонок ------
        GridPane stagesRow = new GridPane();
        stagesRow.setHgap(6);
        for (int i = 0; i < 5; i++) {
            javafx.scene.layout.ColumnConstraints cc = new javafx.scene.layout.ColumnConstraints();
            cc.setPercentWidth(20);
            cc.setHgrow(Priority.SOMETIMES);
            stagesRow.getColumnConstraints().add(cc);
        }
        stagesRow.add(addCheckpoint(Stage.ENCRYPT,  "Шифрование").root,    0, 0);
        stagesRow.add(addCheckpoint(Stage.SHARD,    "Шардирование").root,  1, 0);
        stagesRow.add(addCheckpoint(Stage.TRANSFER, "Передача").root,      2, 0);
        stagesRow.add(addCheckpoint(Stage.CONFIRM,  "Подтверждение").root, 3, 0);
        stagesRow.add(addCheckpoint(Stage.DONE,     "Готово").root,        4, 0);

        VBox card = new VBox(10, topRow, track, stagesRow);
        card.getStyleClass().add("upload-card");
        card.setPadding(new Insets(14, 16, 14, 16));

        // Внешняя обёртка с отступами раздела (28px по горизонтали, как у toolbar)
        VBox wrap = new VBox(card);
        wrap.setPadding(new Insets(14, 28, 0, 28));
        return wrap;
    }

    private Checkpoint addCheckpoint(Stage stage, String label) {
        Checkpoint cp = new Checkpoint(label);
        checkpoints.put(stage, cp);
        return cp;
    }

    /** Один чекпоинт: цветная точка + надпись. */
    private static final class Checkpoint {
        final HBox root;
        final Region dot;
        final Label text;

        Checkpoint(String label) {
            dot = new Region();
            dot.getStyleClass().add("upload-checkpoint-dot");
            dot.setMinSize(6, 6);
            dot.setPrefSize(6, 6);
            dot.setMaxSize(6, 6);

            text = new Label(label);
            text.getStyleClass().add("upload-checkpoint-text");

            root = new HBox(5, dot, text);
            root.setAlignment(Pos.CENTER_LEFT);
            applyState(CheckpointState.PENDING);
        }

        void applyState(CheckpointState s) {
            dot.getStyleClass().removeAll(
                    "upload-checkpoint-dot--pending",
                    "upload-checkpoint-dot--active",
                    "upload-checkpoint-dot--done",
                    "upload-checkpoint-dot--failed");
            text.getStyleClass().removeAll(
                    "upload-checkpoint-text--pending",
                    "upload-checkpoint-text--active",
                    "upload-checkpoint-text--done",
                    "upload-checkpoint-text--failed");
            switch (s) {
                case PENDING -> {
                    dot.getStyleClass().add("upload-checkpoint-dot--pending");
                    text.getStyleClass().add("upload-checkpoint-text--pending");
                }
                case ACTIVE -> {
                    dot.getStyleClass().add("upload-checkpoint-dot--active");
                    text.getStyleClass().add("upload-checkpoint-text--active");
                }
                case DONE -> {
                    dot.getStyleClass().add("upload-checkpoint-dot--done");
                    text.getStyleClass().add("upload-checkpoint-text--done");
                }
                case FAILED -> {
                    dot.getStyleClass().add("upload-checkpoint-dot--failed");
                    text.getStyleClass().add("upload-checkpoint-text--failed");
                }
            }
        }
    }

    private enum CheckpointState { PENDING, ACTIVE, DONE, FAILED }

    // ------------------------------------------------------------------
    // Bindings
    // ------------------------------------------------------------------

    private void bind() {
        fileName.textProperty().bind(state.fileNameProperty());

        // Процент: «62%». На FAILED перекрашиваем и пишем «Ошибка».
        percent.textProperty().bind(Bindings.createStringBinding(
                () -> {
                    if (state.getStage() == Stage.FAILED) return "Ошибка";
                    return ((int) Math.round(state.getProgress() * 100)) + "%";
                },
                state.progressProperty(),
                state.stageProperty()));

        // Мета «X из Y шардов · sentMB / totalMB».
        progressMeta.textProperty().bind(Bindings.createStringBinding(
                this::computeMeta,
                state.fileSizeProperty(),
                state.totalShardsProperty(),
                state.currentShardProperty(),
                state.progressProperty()));

        // ETA — на FAILED показываем текст ошибки.
        eta.textProperty().bind(Bindings.createStringBinding(
                () -> state.getStage() == Stage.FAILED
                        ? state.getErrorText()
                        : state.getEtaText(),
                state.etaTextProperty(),
                state.errorTextProperty(),
                state.stageProperty()));

        // Бейдж типа файла обновляется при смене имени.
        ChangeListener<String> badgeRefresh = (obs, oldName, newName) -> updateBadge(newName);
        state.fileNameProperty().addListener(badgeRefresh);
        updateBadge(state.getFileName());

        state.stageProperty().addListener((obs, old, val) -> {
            updateCheckpoints(val);
            applyVisibility(state.isVisible());
            updateProgressColor(val);
            updatePercentColor(val);
        });
        updateCheckpoints(state.getStage());
    }

    private String computeMeta() {
        int total = state.getTotalShards();
        int cur = Math.min(state.getCurrentShard(), total);
        long size = state.getFileSize();
        double ratio = total == 0 ? state.getProgress() : (double) cur / Math.max(1, total);
        long sentBytes = (long) (size * ratio);
        StringBuilder sb = new StringBuilder();
        if (total > 0) {
            sb.append(cur).append(" из ").append(total).append(" шардов · ");
        }
        sb.append(FormatUtils.humanSize(sentBytes))
          .append(" / ")
          .append(FormatUtils.humanSize(size));
        return sb.toString();
    }

    private void updateBadge(String fname) {
        if (fname == null || fname.isBlank()) {
            badgeSlot.getChildren().clear();
            return;
        }
        StackPane badge = FileTypeBadge.forFile(fname);
        badgeSlot.getChildren().setAll(badge);
    }

    private void updateCheckpoints(Stage current) {
        if (current == null) current = Stage.IDLE;
        Stage[] order = { Stage.ENCRYPT, Stage.SHARD, Stage.TRANSFER, Stage.CONFIRM, Stage.DONE };
        if (current == Stage.DONE) {
            for (Stage st : order) {
                Checkpoint cp = checkpoints.get(st);
                if (cp != null) cp.applyState(CheckpointState.DONE);
            }
            return;
        }
        if (current == Stage.FAILED) {
            // Этапы до transfer — done; transfer — failed; остальные pending
            for (Stage st : order) {
                Checkpoint cp = checkpoints.get(st);
                if (cp == null) continue;
                if (st == Stage.ENCRYPT || st == Stage.SHARD) {
                    cp.applyState(CheckpointState.DONE);
                } else if (st == Stage.TRANSFER) {
                    cp.applyState(CheckpointState.FAILED);
                } else {
                    cp.applyState(CheckpointState.PENDING);
                }
            }
            return;
        }
        boolean afterCurrent = false;
        for (Stage st : order) {
            Checkpoint cp = checkpoints.get(st);
            if (cp == null) continue;
            if (st == current) {
                cp.applyState(CheckpointState.ACTIVE);
                afterCurrent = true;
            } else if (!afterCurrent) {
                cp.applyState(CheckpointState.DONE);
            } else {
                cp.applyState(CheckpointState.PENDING);
            }
        }
    }

    private void updateProgressColor(Stage s) {
        progressFill.getStyleClass().removeAll(
                "upload-card-progress-fill--success",
                "upload-card-progress-fill--danger");
        if (s == Stage.DONE) {
            progressFill.getStyleClass().add("upload-card-progress-fill--success");
        } else if (s == Stage.FAILED) {
            progressFill.getStyleClass().add("upload-card-progress-fill--danger");
        }
    }

    private void updatePercentColor(Stage s) {
        percent.getStyleClass().removeAll("upload-card-percent--danger");
        if (s == Stage.FAILED) {
            percent.getStyleClass().add("upload-card-percent--danger");
        }
    }

    private void applyVisibility(boolean visible) {
        root.setVisible(visible);
        root.setManaged(visible);
    }
}
