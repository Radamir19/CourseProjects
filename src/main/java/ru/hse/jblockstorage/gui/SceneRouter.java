package ru.hse.jblockstorage.gui;

import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Управляет переключением экранов в одном Stage.
 *
 * <p>В JavaFX-приложениях бывают разные стили навигации: подменять Scene
 * целиком, использовать {@code TabPane}, или хранить корневой
 * {@link StackPane} и через {@code setRoot} подменять его содержимое.
 * Мы выбрали последнее, потому что это:
 * <ul>
 *   <li>сохраняет CSS на сцене — при смене экрана не нужно перепривязывать стили</li>
 *   <li>лёгкая будущая интеграция модальных оверлеев (если корень — StackPane,
 *       можно класть {@code overlay.toFront()})</li>
 *   <li>позволяет роутеру ничего не знать про размеры и тему</li>
 * </ul>
 *
 * <p>На день 12 у роутера один метод {@link #show}. В днях 13+ добавим
 * стек истории (для «назад»), модальные диалоги и т.п.
 */
public final class SceneRouter {

    private static final Logger log = LoggerFactory.getLogger(SceneRouter.class);

    private final Stage stage;
    private final Scene scene;
    private final StackPane root;

    /**
     * @param stage главный Stage (создан в {@link JBlockStorageApp#start})
     * @param scene его сцена (хранит ссылки на CSS-стили — должна быть единой)
     */
    public SceneRouter(Stage stage, Scene scene) {
        this.stage = Objects.requireNonNull(stage, "stage");
        this.scene = Objects.requireNonNull(scene, "scene");
        Parent existing = scene.getRoot();
        if (!(existing instanceof StackPane)) {
            throw new IllegalArgumentException(
                    "Scene root должен быть StackPane (используется для setRoot и оверлеев)");
        }
        this.root = (StackPane) existing;
        // Сцена ещё не установлена в Stage — это делаем при первом show().
    }

    /**
     * Заменяет содержимое корневого StackPane новым экраном.
     *
     * @param view  корень нового экрана (FXML root или программная иерархия)
     * @param title заголовок окна (отображается в строке окна macOS/Windows)
     */
    public void show(Parent view, String title) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(title, "title");

        log.debug("SceneRouter.show: {}", title);
        root.getChildren().setAll(view);
        // Якорим на полную ширину/высоту — дочерний экран сам управляет своей версткой.
        StackPane.setAlignment(view, javafx.geometry.Pos.TOP_LEFT);

        if (stage.getScene() != scene) {
            stage.setScene(scene);
        }
        stage.setTitle(title);
    }

    /** Прямой доступ к Stage для редких случаев (например, modal owner). */
    public Stage stage() {
        return stage;
    }
}
