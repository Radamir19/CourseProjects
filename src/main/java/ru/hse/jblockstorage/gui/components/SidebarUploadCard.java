package ru.hse.jblockstorage.gui.components;

import javafx.animation.Interpolator;
import javafx.animation.RotateTransition;
import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.util.Duration;
import ru.hse.jblockstorage.gui.upload.UploadProgressState;

import java.util.Objects;

/**
 * Компактная карточка прогресса загрузки в sidebar — по мокапу
 * {@code upload_progress.html}.
 *
 * <p>Структура (по мокапу):
 * <pre>
 *   ┌─────────────────────────────┐
 *   │ ⟳  Идёт загрузка        62% │
 *   │ диплом_финальная.pdf        │
 *   │ ▓▓▓▓▓▓▓▓░░░░░░░             │  3px тонкий progress-bar
 *   └─────────────────────────────┘
 * </pre>
 *
 * <p><b>Реализация прогресс-бара</b>: track — это {@code HBox(fill, spacer)}.
 * Spacer имеет {@code HGrow.ALWAYS} и забирает всё свободное место. Ширина
 * {@code fill} биндится через {@code track.width × progress} — изменение
 * {@code prefWidth} у child'а с {@code HGrow.NEVER} не приводит к сужению
 * родителя, поэтому layout-цикла не возникает. Аналогичный подход в
 * {@link MyFilesUploadCard}.
 *
 * <p>Видимость карточки управляется через {@link Region#managedProperty()} —
 * связан с {@link UploadProgressState#isVisible()}: когда стейдж IDLE,
 * карточка не занимает место в layout.
 */
public final class SidebarUploadCard {

    private final UploadProgressState state;
    private final VBox root;

    private final Label percent = new Label("0%");
    private final Label fileName = new Label("");
    private final Region progressFill = new Region();
    private final Label statusTitle = new Label("Идёт загрузка");

    /** Сохраняем ссылку, чтобы анимация не была потеряна сборщиком мусора. */
    private RotateTransition spinnerRotate;

    public SidebarUploadCard(UploadProgressState state) {
        this.state = Objects.requireNonNull(state, "state");
        this.root = build();
        bind();
        // Изначально скрыта — пока IDLE
        applyVisibility(state.isVisible());
    }

    public Node getRoot() {
        return root;
    }

    private VBox build() {
        // ===== верхняя строка: spinner-icon + title + percent =====
        Node spinner = buildSpinnerIcon();

        statusTitle.getStyleClass().add("sidebar-upload-title");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        percent.getStyleClass().add("sidebar-upload-percent");

        HBox topRow = new HBox(7, spinner, statusTitle, spacer, percent);
        topRow.setAlignment(Pos.CENTER_LEFT);

        // ===== имя файла (одна строка, обрезается многоточием) =====
        fileName.getStyleClass().add("sidebar-upload-filename");
        fileName.setMaxWidth(Double.MAX_VALUE);

        // ===== полоса прогресса 3px (HBox с fill + spacer, см. javadoc класса) =====
        progressFill.getStyleClass().add("sidebar-upload-progress-fill");
        progressFill.setMinHeight(3);
        progressFill.setPrefHeight(3);
        progressFill.setMaxHeight(3);
        HBox.setHgrow(progressFill, Priority.NEVER);

        Region trackTail = new Region();
        HBox.setHgrow(trackTail, Priority.ALWAYS);
        trackTail.setMinWidth(0);

        HBox track = new HBox(progressFill, trackTail);
        track.getStyleClass().add("sidebar-upload-progress-track");
        track.setAlignment(Pos.CENTER_LEFT);
        track.setMinHeight(3);
        track.setPrefHeight(3);
        track.setMaxHeight(3);
        track.setMaxWidth(Double.MAX_VALUE);

        // Bindings: ширина заливки = ширина track × прогресс. JavaFX сам
        // обработает invalidation корректно, без ручных listener'ов и
        // layout-цикла.
        progressFill.prefWidthProperty().bind(
                track.widthProperty().multiply(state.progressProperty()));

        VBox card = new VBox(6, topRow, fileName, track);
        card.getStyleClass().add("sidebar-upload-card");
        return card;
    }

    /** Маленький круговой spinner — стилизованный под мокап (12×12, синий). */
    private Node buildSpinnerIcon() {
        // Окружность-фон (полупрозрачная) + дуга, которая вращается.
        SVGPath ring = new SVGPath();
        ring.setContent("M6 1A5 5 0 1 1 6 11 A5 5 0 1 1 6 1 Z");
        ring.setStroke(Color.web("#007aff"));
        ring.setFill(Color.TRANSPARENT);
        ring.setStrokeWidth(1.3);
        ring.setOpacity(0.30);

        SVGPath arc = new SVGPath();
        arc.setContent("M6 1 A5 5 0 0 1 11 6");
        arc.setStroke(Color.web("#007aff"));
        arc.setFill(Color.TRANSPARENT);
        arc.setStrokeWidth(1.3);
        arc.setStrokeLineCap(StrokeLineCap.ROUND);

        StackPane wrap = new StackPane(ring, arc);
        wrap.setMinSize(12, 12);
        wrap.setPrefSize(12, 12);
        wrap.setMaxSize(12, 12);

        // Вращение arc'а — 1.2 с цикл. Сохраняем ссылку (важно: иначе
        // RotateTransition может быть собран GC и анимация замрёт).
        spinnerRotate = new RotateTransition(Duration.seconds(1.2), arc);
        spinnerRotate.setByAngle(360);
        spinnerRotate.setCycleCount(RotateTransition.INDEFINITE);
        spinnerRotate.setInterpolator(Interpolator.LINEAR);
        spinnerRotate.play();

        return wrap;
    }

    /** Подписка на observable-свойства модели. */
    private void bind() {
        // Имя файла — простой text bind.
        fileName.textProperty().bind(state.fileNameProperty());

        // Процент: «62%» — через StringBinding.
        percent.textProperty().bind(Bindings.createStringBinding(
                () -> ((int) Math.round(state.getProgress() * 100)) + "%",
                state.progressProperty()));

        // Заголовок и цвет полосы зависят от стадии.
        state.stageProperty().addListener((obs, old, val) -> {
            updateStatusTitle(val);
            updateFillColor(val);
            applyVisibility(state.isVisible());
        });
        // Применяем разово для начального стейджа.
        updateStatusTitle(state.getStage());
        updateFillColor(state.getStage());
    }

    private void updateStatusTitle(UploadProgressState.Stage s) {
        if (s == null) return;
        switch (s) {
            case ENCRYPT -> statusTitle.setText("Шифруем…");
            case SHARD -> statusTitle.setText("Делим на шарды…");
            case TRANSFER -> statusTitle.setText("Идёт загрузка");
            case CONFIRM -> statusTitle.setText("Подтверждаем…");
            case DONE -> statusTitle.setText("Готово");
            case FAILED -> statusTitle.setText("Ошибка");
            case IDLE -> statusTitle.setText("");
        }
    }

    private void updateFillColor(UploadProgressState.Stage s) {
        progressFill.getStyleClass().removeAll(
                "sidebar-upload-progress-fill--success",
                "sidebar-upload-progress-fill--danger");
        if (s == UploadProgressState.Stage.DONE) {
            progressFill.getStyleClass().add("sidebar-upload-progress-fill--success");
        } else if (s == UploadProgressState.Stage.FAILED) {
            progressFill.getStyleClass().add("sidebar-upload-progress-fill--danger");
        }
    }

    private void applyVisibility(boolean visible) {
        root.setVisible(visible);
        root.setManaged(visible);
    }
}
