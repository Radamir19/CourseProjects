package ru.hse.jblockstorage.gui.views.createprofile;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
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
import ru.hse.jblockstorage.gui.views.PlaceholderView;
import ru.hse.jblockstorage.gui.views.WelcomeView;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

/**
 * Шаг 3: проверка фразы.
 *
 * <p>Дизайн (по итоговым мокапам):
 * <ul>
 *   <li>Sidebar — ✓ для шагов 1 и 2, активный шаг 3</li>
 *   <li>Колонка 400px: H1 «Проверим фразу», подзаголовок, единая form-card
 *       с тремя полями (Слово №3, №7, №11), две кнопки внизу
 *       «‹ Назад» (1) и «Завершить» (1.6)</li>
 * </ul>
 *
 * <p>При успехе сохраняем keystore через
 * {@link KeyManager#saveEncryptedPrivateKey} и переходим на success-экран
 * (заглушка до настоящего Dashboard).
 */
public final class CreateProfileStep3View {

    private static final Logger log = LoggerFactory.getLogger(CreateProfileStep3View.class);

    private static final double COLUMN_WIDTH = 400;

    /** Позиции слов для проверки (1-indexed). */
    private static final int[] CHECK_POSITIONS = {3, 7, 11};

    private final AppContext context;
    private final ProfileCreationState state;
    private final Parent root;

    private TextField word3Field;
    private TextField word7Field;
    private TextField word11Field;
    private Label errorLabel;
    private Button finishBtn;

    public CreateProfileStep3View(AppContext context, ProfileCreationState state) {
        this.context = Objects.requireNonNull(context, "context");
        this.state = Objects.requireNonNull(state, "state");
        this.root = build();
    }

    public Parent getRoot() {
        return root;
    }

    private Parent build() {
        OnboardingSidebar sidebar = new OnboardingSidebar();
        sidebar.setCenter(OnboardingSidebar.buildStepsIndicatorNumbered(3));

        Label h1 = new Label("Проверим фразу");
        h1.getStyleClass().add("h1");

        Label subtitle = new Label("Введите три слова из вашей фразы — это убедит "
                + "нас, что вы её записали.");
        subtitle.getStyleClass().add("welcome-subtitle");
        subtitle.setWrapText(true);

        word3Field = wordField();
        word7Field = wordField();
        word11Field = wordField();

        VBox formCard = new VBox(
                fieldRow("СЛОВО №" + CHECK_POSITIONS[0], word3Field, false),
                fieldRow("СЛОВО №" + CHECK_POSITIONS[1], word7Field, false),
                fieldRow("СЛОВО №" + CHECK_POSITIONS[2], word11Field, true)
        );
        formCard.getStyleClass().add("form-card");

        errorLabel = new Label();
        errorLabel.getStyleClass().add("form-error");
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
        errorLabel.setWrapText(true);

        Button backBtn = new Button("‹ Назад");
        backBtn.getStyleClass().addAll("btn-secondary", "btn-full");
        backBtn.setOnAction(e -> onBack());
        backBtn.setMaxWidth(Double.MAX_VALUE);
        backBtn.setPrefWidth(1);
        HBox.setHgrow(backBtn, Priority.ALWAYS);

        finishBtn = new Button("Завершить");
        finishBtn.getStyleClass().addAll("btn-primary", "btn-full");
        finishBtn.setDefaultButton(true);
        finishBtn.setOnAction(e -> onFinish());
        finishBtn.setMaxWidth(Double.MAX_VALUE);
        finishBtn.setPrefWidth(1.6);
        HBox.setHgrow(finishBtn, Priority.ALWAYS);

        HBox actions = new HBox(8, backBtn, finishBtn);
        actions.setAlignment(Pos.CENTER);

        Button cancelBtn = new Button("Отмена");
        cancelBtn.getStyleClass().add("btn-ghost");
        cancelBtn.setOnAction(e -> onCancel());

        VBox stack = new VBox(0, h1, subtitle, formCard, errorLabel, actions, cancelBtn);
        VBox.setMargin(subtitle, new Insets(8,  0, 0, 0));
        VBox.setMargin(formCard, new Insets(20, 0, 0, 0));
        VBox.setMargin(errorLabel, new Insets(8, 0, 0, 0));
        VBox.setMargin(actions,  new Insets(20, 0, 0, 0));
        VBox.setMargin(cancelBtn,new Insets(8, 0, 0, 0));

        stack.setAlignment(Pos.TOP_LEFT);
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

    private TextField wordField() {
        TextField tf = new TextField();
        tf.setPromptText("введите слово");
        tf.getStyleClass().add("text-field-inline");
        return tf;
    }

    private static VBox fieldRow(String label, javafx.scene.Node field, boolean isLast) {
        Label l = new Label(label);
        l.getStyleClass().add("form-card-label");
        VBox box = new VBox(3, l, field);
        box.getStyleClass().add("form-card-row");
        if (isLast) box.getStyleClass().add("form-card-row--last");
        return box;
    }

    // ------------------------------------------------------------------
    // Действия
    // ------------------------------------------------------------------

    private void onFinish() {
        String[] words = state.mnemonicWords();
        String expected3 = words[CHECK_POSITIONS[0] - 1];
        String expected7 = words[CHECK_POSITIONS[1] - 1];
        String expected11 = words[CHECK_POSITIONS[2] - 1];

        String got3 = word3Field.getText().trim().toLowerCase();
        String got7 = word7Field.getText().trim().toLowerCase();
        String got11 = word11Field.getText().trim().toLowerCase();

        if (!got3.equals(expected3) || !got7.equals(expected7) || !got11.equals(expected11)) {
            showError("Слова не совпадают. Проверьте свою запись и попробуйте ещё раз.");
            return;
        }

        hideError();
        setSaving(true);

        Task<Path> task = new Task<>() {
            @Override
            protected Path call() throws IOException {
                Path profilesDir = profilesDir();
                Files.createDirectories(profilesDir);

                String safeName = sanitize(state.profileName());
                Path keystorePath = profilesDir.resolve(safeName + ".keys");
                Path pubKeyPath = profilesDir.resolve(safeName + ".pub");
                Path recoveryPath = profilesDir.resolve(safeName + ".recovery");

                if (Files.exists(keystorePath)) {
                    throw new IOException("Файл keystore уже существует: " + keystorePath);
                }

                char[] password = state.password();
                char[] recoveryPwd = Bip39.mnemonicToKeystorePassword(state.mnemonic());
                try {
                    KeyManager.saveEncryptedPrivateKey(
                            state.keyPair().getPrivate(), keystorePath, password);
                    KeyManager.savePublicKey(state.keyPair().getPublic(), pubKeyPath);

                    // ТЗ 4.1.5.1: дополнительно сохраняем «recovery»-копию приватного
                    // ключа, зашифрованную паролем, выведенным детерминированно из
                    // BIP-39 фразы. Если пользователь забудет основной пароль, он
                    // вводит фразу — мы расшифровываем .recovery, выводим тот же
                    // приватный ключ и сохраняем новый .keys с новым паролем.
                    KeyManager.saveEncryptedPrivateKey(
                            state.keyPair().getPrivate(), recoveryPath, recoveryPwd);
                } finally {
                    java.util.Arrays.fill(password, '\0');
                    java.util.Arrays.fill(recoveryPwd, '\0');
                }
                return keystorePath;
            }
        };

        task.setOnSucceeded(e -> {
            Path saved = task.getValue();
            log.info("Профиль сохранён: {}", saved);
            state.clearPassword();
            showSuccess(saved);
        });

        task.setOnFailed(e -> {
            Throwable ex = task.getException();
            log.warn("Ошибка сохранения профиля", ex);
            setSaving(false);
            showError("Не удалось сохранить keystore: " + ex.getMessage());
        });

        Thread t = new Thread(task, "keystore-save");
        t.setDaemon(true);
        t.start();
    }

    private void onBack() {
        // Возврат на Step 2 — пересоздаём (мнемоника та же, лежит в state)
        CreateProfileStep2View step2 = new CreateProfileStep2View(context, state);
        context.router().show(step2.getRoot(), "JBlockStorage — Запишите фразу");
    }

    private void onCancel() {
        state.clearPassword();
        WelcomeView welcome = new WelcomeView(context);
        context.router().show(welcome.getRoot(), "JBlockStorage — Добро пожаловать");
    }

    private void showSuccess(Path keystorePath) {
        Platform.runLater(() -> {
            PlaceholderView success = new PlaceholderView(
                    context,
                    "Профиль создан",
                    "Зашифрованный keystore сохранён.\n\n"
                            + keystorePath.toString() + "\n\n"
                            + "Главное окно (Dashboard) появится в следующей итерации.",
                    () -> {
                        WelcomeView welcome = new WelcomeView(context);
                        context.router().show(welcome.getRoot(), "JBlockStorage — Добро пожаловать");
                    }
            );
            context.router().show(success.getRoot(), "JBlockStorage — Профиль создан");
        });
    }

    private void setSaving(boolean saving) {
        finishBtn.setDisable(saving);
        finishBtn.setText(saving ? "Сохраняем…" : "Завершить");
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

    // ------------------------------------------------------------------
    // Утилиты для пути keystore (используются также из Step1)
    // ------------------------------------------------------------------

    /**
     * Каталог хранения keystore-файлов: {@code ~/.jblockstorage/profiles}
     * по умолчанию, но переопределяется через переменную окружения
     * {@code JBS_HOME} (см. {@link LoginView} — ту же логику нужно
     * соблюдать и при создании профиля, чтобы сразу записать keystore
     * в правильную папку для конкретного узла).
     */
    static Path profilesDir() {
        String envHome = System.getenv("JBS_HOME");
        Path base = (envHome != null && !envHome.isBlank())
                ? Paths.get(envHome)
                : Paths.get(System.getProperty("user.home"), ".jblockstorage");
        return base.resolve("profiles");
    }

    /**
     * Имя профиля → безопасное имя файла. Сохраняем буквы (Unicode),
     * цифры, подчёркивание и дефис. Всё остальное → '_'.
     */
    static String sanitize(String name) {
        String result = name.trim().replaceAll("[^\\p{L}\\p{N}_-]", "_");
        return result.isEmpty() ? "profile" : result;
    }
}
