package ru.hse.jblockstorage.gui.views.createprofile;

import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.hse.jblockstorage.crypto.Bip39;
import ru.hse.jblockstorage.crypto.KeyManager;
import ru.hse.jblockstorage.gui.AppContext;
import ru.hse.jblockstorage.gui.components.OnboardingSidebar;
import ru.hse.jblockstorage.gui.views.WelcomeView;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.Objects;

/**
 * Шаг 1: параметры профиля.
 *
 * <p>Дизайн (по итоговым мокапам):
 * <ul>
 *   <li>Sidebar — нумерованные шаги 1/2/3 с активным первым</li>
 *   <li>Колонка 380px: «← Назад», H1, описание, единая form-card с тремя
 *       inline-полями (имя / пароль / подтверждение), пояснение про
 *       пароль и фразу, две full-width кнопки «Отмена» и «Создать профиль»</li>
 * </ul>
 *
 * <p>Логика та же, что в первой версии: валидация → асинхронная RSA-2048
 * генерация в {@link Task} → переход на {@link CreateProfileStep2View}.
 */
public final class CreateProfileStep1View {

    private static final Logger log = LoggerFactory.getLogger(CreateProfileStep1View.class);

    static final double COLUMN_WIDTH = 380;

    private final AppContext context;
    private final Parent root;

    private TextField nameField;
    private PasswordField passwordField;
    private PasswordField confirmField;
    private Label errorLabel;
    private Button createBtn;

    public CreateProfileStep1View(AppContext context) {
        this.context = Objects.requireNonNull(context, "context");
        this.root = build();
    }

    public Parent getRoot() {
        return root;
    }

    private Parent build() {
        OnboardingSidebar sidebar = new OnboardingSidebar();
        sidebar.setCenter(OnboardingSidebar.buildStepsIndicatorNumbered(1));

        Hyperlink back = new Hyperlink("‹ Назад");
        back.getStyleClass().add("back-link");
        back.setOnAction(e -> onCancel());

        Label h1 = new Label("Параметры профиля");
        h1.getStyleClass().add("h1");

        Label subtitle = new Label("После создания вы увидите фразу из 12 слов "
                + "для восстановления.");
        subtitle.getStyleClass().add("welcome-subtitle");
        subtitle.setWrapText(true);

        // Form-card: имя / пароль / подтверждение
        nameField = new TextField();
        nameField.setPromptText("Например: Радамир");
        nameField.getStyleClass().add("text-field-inline");

        passwordField = new PasswordField();
        passwordField.setPromptText("Не менее " + KeyManager.MIN_PASSWORD_LENGTH + " символов");
        passwordField.getStyleClass().add("password-field-inline");

        confirmField = new PasswordField();
        confirmField.setPromptText("Повторите пароль");
        confirmField.getStyleClass().add("password-field-inline");

        VBox formCard = new VBox(
                fieldRow("ИМЯ ПРОФИЛЯ", nameField, false),
                fieldRow("ПАРОЛЬ", passwordField, false),
                fieldRow("ПОДТВЕРЖДЕНИЕ ПАРОЛЯ", confirmField, true)
        );
        formCard.getStyleClass().add("form-card");

        Label hint = new Label("Пароль шифрует приватный ключ на этом устройстве.\n"
                + "Если вы его забудете, профиль можно восстановить только по фразе.");
        hint.getStyleClass().add("text-secondary");
        hint.setWrapText(true);

        errorLabel = new Label();
        errorLabel.getStyleClass().add("form-error");
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
        errorLabel.setWrapText(true);

        Button cancelBtn = new Button("Отмена");
        cancelBtn.getStyleClass().addAll("btn-secondary", "btn-full");
        cancelBtn.setOnAction(e -> onCancel());
        HBox.setHgrow(cancelBtn, Priority.ALWAYS);
        cancelBtn.setMaxWidth(Double.MAX_VALUE);

        createBtn = new Button("Создать профиль");
        createBtn.getStyleClass().addAll("btn-primary", "btn-full");
        createBtn.setDefaultButton(true);
        createBtn.setOnAction(e -> onCreate());
        HBox.setHgrow(createBtn, Priority.ALWAYS);
        createBtn.setMaxWidth(Double.MAX_VALUE);

        // По мокапу: «Отмена» 1, «Создать профиль» 1.4
        HBox actions = new HBox(8, cancelBtn, createBtn);
        actions.setAlignment(Pos.CENTER);
        // Эмулируем flex-grow 1 / 1.4
        cancelBtn.setPrefWidth(1);
        createBtn.setPrefWidth(1.4);

        VBox stack = new VBox(0, back, h1, subtitle, formCard, hint, errorLabel, actions);
        VBox.setMargin(h1,        new Insets(8,  0, 0, 0));
        VBox.setMargin(subtitle,  new Insets(6,  0, 0, 0));
        VBox.setMargin(formCard,  new Insets(20, 0, 0, 0));
        VBox.setMargin(hint,      new Insets(10, 0, 0, 0));
        VBox.setMargin(errorLabel, new Insets(8, 0, 0, 0));
        VBox.setMargin(actions,   new Insets(20, 0, 0, 0));

        stack.setMaxWidth(COLUMN_WIDTH);
        stack.setMinWidth(COLUMN_WIDTH);

        StackPane wrap = new StackPane(stack);
        StackPane.setAlignment(stack, Pos.CENTER);
        wrap.getStyleClass().add("onboarding-content");

        HBox layout = new HBox(sidebar.getRoot(), wrap);
        HBox.setHgrow(wrap, Priority.ALWAYS);
        layout.getStyleClass().add("onboarding-root");
        return layout;
    }

    /**
     * Строка form-card: маленький лейбл сверху + inline-поле.
     */
    private static VBox fieldRow(String label, Node field, boolean isLast) {
        Label l = new Label(label);
        l.getStyleClass().add("form-card-label");

        VBox box = new VBox(3, l, field);
        box.getStyleClass().add("form-card-row");
        if (isLast) {
            box.getStyleClass().add("form-card-row--last");
        }
        return box;
    }

    // ------------------------------------------------------------------
    // Действия
    // ------------------------------------------------------------------

    private void onCreate() {
        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        char[] password = passwordField.getText().toCharArray();
        char[] confirm = confirmField.getText().toCharArray();

        try {
            if (name.isEmpty()) {
                showError("Введите имя профиля");
                return;
            }
            if (password.length < KeyManager.MIN_PASSWORD_LENGTH) {
                showError("Пароль должен быть не менее "
                        + KeyManager.MIN_PASSWORD_LENGTH + " символов");
                return;
            }
            if (!Arrays.equals(password, confirm)) {
                showError("Пароли не совпадают");
                return;
            }
            Path candidate = CreateProfileStep3View.profilesDir()
                    .resolve(CreateProfileStep3View.sanitize(name) + ".keys");
            if (Files.exists(candidate)) {
                showError("Профиль с именем «" + name + "» уже существует на этом устройстве");
                return;
            }

            hideError();
            startKeyGeneration(name, password);
        } finally {
            Arrays.fill(confirm, '\0');
        }
    }

    private void startKeyGeneration(String name, char[] password) {
        setGenerating(true);

        Task<ProfileCreationState> task = new Task<>() {
            @Override
            protected ProfileCreationState call() {
                log.info("Генерация ключевой пары RSA-2048 для профиля «{}»", name);
                long t0 = System.currentTimeMillis();
                KeyPair kp = KeyManager.generateRsaKeyPair();
                String mnemonic = Bip39.generateMnemonic12();
                log.info("Ключевая пара сгенерирована за {} мс", System.currentTimeMillis() - t0);
                return new ProfileCreationState(name, password, kp, mnemonic);
            }
        };

        task.setOnSucceeded(e -> {
            ProfileCreationState state = task.getValue();
            Arrays.fill(password, '\0');
            CreateProfileStep2View step2 = new CreateProfileStep2View(context, state);
            context.router().show(step2.getRoot(), "JBlockStorage — Запишите фразу");
        });

        task.setOnFailed(e -> {
            Arrays.fill(password, '\0');
            Throwable ex = task.getException();
            log.warn("Ошибка генерации ключевой пары", ex);
            setGenerating(false);
            showError("Не удалось сгенерировать ключи: " + ex.getMessage());
        });

        Thread t = new Thread(task, "key-generation");
        t.setDaemon(true);
        t.start();
    }

    private void onCancel() {
        WelcomeView welcome = new WelcomeView(context);
        context.router().show(welcome.getRoot(), "JBlockStorage — Добро пожаловать");
    }

    private void setGenerating(boolean generating) {
        createBtn.setDisable(generating);
        nameField.setDisable(generating);
        passwordField.setDisable(generating);
        confirmField.setDisable(generating);
        createBtn.setText(generating ? "Генерируем ключи…" : "Создать профиль");
    }

    private void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }

    private void hideError() {
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
    }
}
