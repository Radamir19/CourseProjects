package ru.hse.jblockstorage.gui.views;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.text.TextAlignment;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.hse.jblockstorage.gui.AppContext;
import ru.hse.jblockstorage.gui.components.OnboardingSidebar;
import ru.hse.jblockstorage.gui.views.createprofile.CreateProfileStep1View;

import java.util.Objects;

/**
 * Экран приветствия.
 *
 * <p>Дизайн (по итоговым мокапам):
 * <ul>
 *   <li>Слева — {@link OnboardingSidebar} с блоком СЕТЬ и SVG-схемой узлов</li>
 *   <li>Справа по центру — узкая колонка 340px: иконка 64×64,
 *       H1 «Добро пожаловать», подзаголовок и две full-width кнопки</li>
 * </ul>
 *
 * <p>Реализует ТЗ п. 4.1.5.1.
 */
public final class WelcomeView {

    private static final Logger log = LoggerFactory.getLogger(WelcomeView.class);

    /** Ширина центральной колонки контента — общая для Welcome/Login/CreateProfile. */
    static final double CONTENT_COLUMN_WIDTH = 340;

    private final AppContext context;
    private final Parent root;

    public WelcomeView(AppContext context) {
        this.context = Objects.requireNonNull(context, "context");
        this.root = build();
    }

    public Parent getRoot() {
        return root;
    }

    private Parent build() {
        OnboardingSidebar sidebar = new OnboardingSidebar();
        sidebar.setCenter(OnboardingSidebar.buildNetworkAndDiagram());

        Parent content = buildContent();

        HBox layout = new HBox(sidebar.getRoot(), content);
        HBox.setHgrow(content, Priority.ALWAYS);
        layout.getStyleClass().add("onboarding-root");
        return layout;
    }

    private Parent buildContent() {
        // Иконка-градиент 64×64
        Region logo = new Region();
        logo.getStyleClass().add("brand-logo-large");
        logo.setPrefSize(64, 64);
        logo.setMinSize(64, 64);
        logo.setMaxSize(64, 64);

        Label title = new Label("Добро пожаловать");
        title.getStyleClass().add("h1");
        title.setTextAlignment(TextAlignment.CENTER);

        Label subtitle = new Label("Создайте профиль или войдите\nв существующий.");
        subtitle.getStyleClass().add("welcome-subtitle");
        subtitle.setTextAlignment(TextAlignment.CENTER);
        subtitle.setWrapText(true);

        // Full-width кнопки на 340px
        Button createBtn = new Button("Создать профиль");
        createBtn.getStyleClass().addAll("btn-primary", "btn-full");
        createBtn.setMaxWidth(Double.MAX_VALUE);
        createBtn.setDefaultButton(true);
        createBtn.setOnAction(e -> goCreateProfile());

        Button loginBtn = new Button("Войти");
        loginBtn.getStyleClass().addAll("btn-secondary", "btn-full");
        loginBtn.setMaxWidth(Double.MAX_VALUE);
        loginBtn.setOnAction(e -> goLogin());

        VBox buttons = new VBox(8, createBtn, loginBtn);
        buttons.setAlignment(Pos.CENTER);
        buttons.setMaxWidth(Double.MAX_VALUE);

        // Отступы между блоками: logo→title 20, title→subtitle 8, subtitle→buttons 32
        VBox.setMargin(title, new Insets(20, 0, 0, 0));
        VBox.setMargin(subtitle, new Insets(8, 0, 0, 0));
        VBox.setMargin(buttons, new Insets(32, 0, 0, 0));

        VBox stack = new VBox(0, logo, title, subtitle, buttons);
        stack.setAlignment(Pos.CENTER);
        stack.setMaxWidth(CONTENT_COLUMN_WIDTH);
        stack.setMinWidth(CONTENT_COLUMN_WIDTH);

        StackPane wrap = new StackPane(stack);
        wrap.getStyleClass().add("onboarding-content");
        return wrap;
    }

    // ------------------------------------------------------------------
    // Навигация
    // ------------------------------------------------------------------

    private void goCreateProfile() {
        log.info("Welcome → Создать профиль");
        CreateProfileStep1View step1 = new CreateProfileStep1View(context);
        context.router().show(step1.getRoot(), "JBlockStorage — Создание профиля");
    }

    private void goLogin() {
        log.info("Welcome → Войти");
        LoginView login = new LoginView(context);
        context.router().show(login.getRoot(), "JBlockStorage — Войти");
    }
}
