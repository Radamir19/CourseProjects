package ru.hse.jblockstorage.gui.views;

import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.text.TextAlignment;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import ru.hse.jblockstorage.gui.AppContext;
import ru.hse.jblockstorage.gui.components.OnboardingSidebar;

import java.util.Objects;

/**
 * Универсальная заглушка для экранов, которые ещё не реализованы.
 *
 * <p>Структура: тот же {@link OnboardingSidebar} (чтобы каркас приложения
 * выглядел цельным) + правая часть с заголовком, описанием и кнопкой «Назад».
 *
 * <p>Используется как success-экран после создания профиля (день 12) и
 * для других экранов, которые ещё не имеют реализации. Принимает
 * {@link AppContext} — единое состояние GUI с router'ом и (после Login)
 * запущенным узлом.
 */
public final class PlaceholderView {

    private final Parent root;

    public PlaceholderView(AppContext context, String title, String message, Runnable onBack) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(onBack, "onBack");

        OnboardingSidebar sidebar = new OnboardingSidebar();
        sidebar.setCenter(OnboardingSidebar.buildNetworkAndDiagram());

        Label h1 = new Label(title);
        h1.getStyleClass().add("h1");

        Label body = new Label(message);
        body.getStyleClass().add("welcome-subtitle");
        body.setWrapText(true);
        body.setTextAlignment(TextAlignment.CENTER);

        Button back = new Button("← Назад");
        back.getStyleClass().addAll("btn-ghost", "btn-large");
        back.setOnAction(e -> onBack.run());

        VBox stack = new VBox(20, h1, body, back);
        stack.setAlignment(Pos.CENTER);
        stack.setMaxWidth(420);

        StackPane wrap = new StackPane(stack);
        wrap.getStyleClass().add("onboarding-content");

        HBox layout = new HBox(sidebar.getRoot(), wrap);
        HBox.setHgrow(wrap, Priority.ALWAYS);
        layout.getStyleClass().add("onboarding-root");

        this.root = layout;
    }

    public Parent getRoot() {
        return root;
    }
}
