package ru.hse.jblockstorage.gui;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;

import java.util.Objects;

/**
 * Управляет модальными оверлеями (диалогами поверх основного экрана).
 *
 * <p>Архитектурный приём: корневая нода сцены — это {@link StackPane}.
 * Основной контент кладётся первым элементом, а модалка — поверх него
 * (последним), занимая всю площадь. Полупрозрачная подложка
 * (CSS-класс {@code .modal-scrim}) перехватывает клики и события
 * клавиатуры, чтобы юзер не мог взаимодействовать с фоном.
 *
 * <p>Использование:
 * <pre>
 *   OverlayHost host = new OverlayHost(sceneRoot);
 *   host.show(myDialogContent, () -&gt; doWhenClosed());
 *   ...
 *   host.dismiss();
 * </pre>
 */
public final class OverlayHost {

    private final StackPane sceneRoot;
    private StackPane currentScrim;
    private Runnable onDismiss;

    public OverlayHost(StackPane sceneRoot) {
        this.sceneRoot = Objects.requireNonNull(sceneRoot, "sceneRoot");
    }

    /**
     * Показывает указанный контент как модальный оверлей.
     */
    public void show(Node content, Runnable onDismiss) {
        Objects.requireNonNull(content, "content");
        if (currentScrim != null) {
            sceneRoot.getChildren().remove(currentScrim);
        }
        StackPane scrim = new StackPane();
        scrim.getStyleClass().add("modal-scrim");
        // Растягиваем scrim на ВСЮ площадь корневого StackPane —
        // без этого он схлопывается до размеров самой узкой карточки.
        scrim.setMinSize(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY);
        scrim.setPrefSize(Double.MAX_VALUE, Double.MAX_VALUE);
        scrim.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        // Контент модалки центрируется внутри scrim.
        StackPane.setAlignment(content, Pos.CENTER);
        scrim.setAlignment(Pos.CENTER);
        scrim.getChildren().add(content);

        // Блокируем мышиные события только на самой подложке —
        // чтобы клики по карточке (кнопки, поля) работали нормально.
        scrim.setOnMouseClicked(MouseEvent::consume);
        scrim.setOnMousePressed(MouseEvent::consume);
        scrim.setOnMouseReleased(MouseEvent::consume);

        // Esc → закрыть.
        scrim.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                e.consume();
                dismiss();
            }
        });
        scrim.setFocusTraversable(true);

        sceneRoot.getChildren().add(scrim);
        this.currentScrim = scrim;
        this.onDismiss = onDismiss;

        scrim.requestFocus();
    }

    /** Закрывает текущую модалку и вызывает её onDismiss. */
    public void dismiss() {
        if (currentScrim == null) return;
        sceneRoot.getChildren().remove(currentScrim);
        currentScrim = null;
        Runnable cb = onDismiss;
        onDismiss = null;
        if (cb != null) cb.run();
    }

    public boolean isShowing() {
        return currentScrim != null;
    }
}
