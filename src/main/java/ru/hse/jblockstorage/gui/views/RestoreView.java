package ru.hse.jblockstorage.gui.views;

import javafx.application.Platform;
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
import javafx.scene.layout.GridPane;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.PrivateKey;
import java.util.Arrays;
import java.util.Objects;

/**
 * Экран восстановления профиля по BIP-39 seed-фразе (ТЗ п. 4.1.5.1).
 *
 * <p><b>Архитектура восстановления.</b> При создании профиля
 * (см. {@link ru.hse.jblockstorage.gui.views.createprofile.CreateProfileStep3View})
 * сохраняются два keystore-файла:
 * <ul>
 *   <li>{@code <name>.keys} — зашифрован пользовательским паролем (повседневный
 *       вход через {@link LoginView});</li>
 *   <li>{@code <name>.recovery} — зашифрован паролем, выведенным детерминированно
 *       из самой seed-фразы через {@link Bip39#mnemonicToKeystorePassword(String)}.</li>
 * </ul>
 *
 * <p>Таким образом, если пользователь забывает основной пароль, он вводит
 * 12 слов фразы — система выводит из неё {@code recoveryPwd}, расшифровывает
 * {@code .recovery}, получает приватный ключ и переписывает {@code .keys}
 * с новым пользовательским паролем. Фраза остаётся неизменной — повторное
 * восстановление с той же фразы будет работать сколько угодно раз.
 *
 * <p>RSA не поддерживает детерминированное порождение ключей из seed
 * (в отличие от secp256k1/ed25519), поэтому фраза не «порождает ключ»,
 * а служит мастер-паролем для альтернативной зашифрованной копии.
 * Эту особенность нужно отразить в пояснительной записке.
 *
 * <p>Дизайн соответствует Step1/Step3 из мастера создания профиля:
 * левый Onboarding-sidebar, правая колонка 460px с form-card,
 * 12-сетка ввода слов 3×4, новый пароль и подтверждение, две кнопки.
 */
public final class RestoreView {

    private static final Logger log = LoggerFactory.getLogger(RestoreView.class);

    private static final double COLUMN_WIDTH = 460;
    private static final int MNEMONIC_WORDS = 12;

    private final AppContext context;
    private final Parent root;

    private TextField nameField;
    private final TextField[] wordFields = new TextField[MNEMONIC_WORDS];
    private PasswordField passwordField;
    private PasswordField confirmField;
    private Label errorLabel;
    private Button restoreBtn;
    private Button cancelBtn;

    public RestoreView(AppContext context) {
        this.context = Objects.requireNonNull(context, "context");
        this.root = build();
    }

    public Parent getRoot() {
        return root;
    }

    private Parent build() {
        OnboardingSidebar sidebar = new OnboardingSidebar();
        sidebar.setCenter(OnboardingSidebar.buildNetworkAndDiagram());

        Hyperlink back = new Hyperlink("‹ Назад");
        back.getStyleClass().add("back-link");
        back.setOnAction(e -> backToWelcome());

        Label h1 = new Label("Восстановление по фразе");
        h1.getStyleClass().add("h1");

        Label subtitle = new Label("Введите 12 слов вашей фразы и задайте новый пароль "
                + "для keystore. Фраза не покидает это устройство.");
        subtitle.getStyleClass().add("welcome-subtitle");
        subtitle.setWrapText(true);

        // Form-card: имя профиля
        nameField = new TextField();
        nameField.setPromptText("Например: alice");
        nameField.getStyleClass().add("text-field-inline");

        VBox nameRow = fieldRow("ИМЯ ПРОФИЛЯ", nameField, false);

        // 12 полей под слова — 3×4 сетка
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        for (int i = 0; i < MNEMONIC_WORDS; i++) {
            wordFields[i] = new TextField();
            wordFields[i].setPromptText((i + 1) + ".");
            wordFields[i].getStyleClass().add("text-field-inline");
            // По 4 в ряд
            grid.add(wordFields[i], i % 4, i / 4);
        }
        // Слева→направо равномерное растяжение
        for (int c = 0; c < 4; c++) {
            javafx.scene.layout.ColumnConstraints cc = new javafx.scene.layout.ColumnConstraints();
            cc.setPercentWidth(25);
            grid.getColumnConstraints().add(cc);
        }

        VBox wordsRow = new VBox(6, smallLabel("SEED-ФРАЗА (12 СЛОВ)"), grid);
        wordsRow.getStyleClass().add("form-card-row");

        // Новый пароль / подтверждение
        passwordField = new PasswordField();
        passwordField.setPromptText("Не менее " + KeyManager.MIN_PASSWORD_LENGTH + " символов");
        passwordField.getStyleClass().add("password-field-inline");

        confirmField = new PasswordField();
        confirmField.setPromptText("Повторите пароль");
        confirmField.getStyleClass().add("password-field-inline");

        VBox passwordRow = fieldRow("НОВЫЙ ПАРОЛЬ", passwordField, false);
        VBox confirmRow = fieldRow("ПОДТВЕРЖДЕНИЕ ПАРОЛЯ", confirmField, true);

        VBox formCard = new VBox(nameRow, wordsRow, passwordRow, confirmRow);
        formCard.getStyleClass().add("form-card");

        Label hint = new Label("Фраза проверяется по контрольной сумме BIP-39 — опечатка "
                + "в любом слове сразу будет выявлена.");
        hint.getStyleClass().add("text-secondary");
        hint.setWrapText(true);

        errorLabel = new Label();
        errorLabel.getStyleClass().add("form-error");
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
        errorLabel.setWrapText(true);

        cancelBtn = new Button("Отмена");
        cancelBtn.getStyleClass().addAll("btn-secondary", "btn-full");
        cancelBtn.setOnAction(e -> backToWelcome());
        HBox.setHgrow(cancelBtn, Priority.ALWAYS);
        cancelBtn.setMaxWidth(Double.MAX_VALUE);

        restoreBtn = new Button("Восстановить");
        restoreBtn.getStyleClass().addAll("btn-primary", "btn-full");
        restoreBtn.setDefaultButton(true);
        restoreBtn.setOnAction(e -> onRestore());
        HBox.setHgrow(restoreBtn, Priority.ALWAYS);
        restoreBtn.setMaxWidth(Double.MAX_VALUE);

        HBox actions = new HBox(8, cancelBtn, restoreBtn);
        actions.setAlignment(Pos.CENTER);
        cancelBtn.setPrefWidth(1);
        restoreBtn.setPrefWidth(1.4);

        VBox stack = new VBox(0, back, h1, subtitle, formCard, hint, errorLabel, actions);
        VBox.setMargin(h1,         new Insets(8,  0, 0, 0));
        VBox.setMargin(subtitle,   new Insets(6,  0, 0, 0));
        VBox.setMargin(formCard,   new Insets(20, 0, 0, 0));
        VBox.setMargin(hint,       new Insets(10, 0, 0, 0));
        VBox.setMargin(errorLabel, new Insets(8,  0, 0, 0));
        VBox.setMargin(actions,    new Insets(20, 0, 0, 0));

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

    /** Строка form-card: маленький лейбл + inline-поле (как в Step1). */
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

    private static Label smallLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("form-card-label");
        return l;
    }

    // ------------------------------------------------------------------
    // Действия
    // ------------------------------------------------------------------

    private void onRestore() {
        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        char[] password = passwordField.getText().toCharArray();
        char[] confirm = confirmField.getText().toCharArray();

        try {
            if (name.isEmpty()) {
                showError("Введите имя профиля");
                return;
            }

            // Собираем фразу
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < MNEMONIC_WORDS; i++) {
                String w = wordFields[i].getText() == null
                        ? "" : wordFields[i].getText().trim().toLowerCase();
                if (w.isEmpty()) {
                    showError("Заполните все 12 слов фразы");
                    return;
                }
                if (i > 0) sb.append(' ');
                sb.append(w);
            }
            String mnemonic = sb.toString();

            if (!Bip39.isValidMnemonic(mnemonic)) {
                showError("Фраза недействительна — проверьте слова и порядок");
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

            Path profilesDir = profilesDir();
            String safe = sanitize(name);
            Path recoveryPath = profilesDir.resolve(safe + ".recovery");
            Path keystorePath = profilesDir.resolve(safe + ".keys");

            if (!Files.exists(recoveryPath)) {
                showError("Профиль «" + name + "» не найден на этом устройстве "
                        + "(нет файла .recovery)");
                return;
            }

            hideError();
            startRestoration(name, mnemonic, password, recoveryPath, keystorePath);
        } finally {
            Arrays.fill(confirm, '\0');
        }
    }

    private void startRestoration(String name, String mnemonic, char[] newPassword,
                                  Path recoveryPath, Path keystorePath) {
        setRestoring(true);

        Task<Path> task = new Task<>() {
            @Override
            protected Path call() throws IOException {
                log.info("Восстановление профиля «{}» из {}", name, recoveryPath);

                char[] recoveryPwd = Bip39.mnemonicToKeystorePassword(mnemonic);
                PrivateKey privateKey;
                try {
                    privateKey = KeyManager.loadEncryptedPrivateKey(recoveryPath, recoveryPwd);
                } finally {
                    Arrays.fill(recoveryPwd, '\0');
                }

                // Атомарно перезаписываем .keys через временный файл,
                // чтобы крах посередине не оставил битый keystore.
                Path tmp = keystorePath.resolveSibling(keystorePath.getFileName() + ".tmp");
                try {
                    KeyManager.saveEncryptedPrivateKey(privateKey, tmp, newPassword);
                    Files.move(tmp, keystorePath,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                    Files.move(tmp, keystorePath,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(tmp);
                }
                return keystorePath;
            }
        };

        task.setOnSucceeded(e -> {
            Arrays.fill(newPassword, '\0');
            log.info("Профиль восстановлен: {}", task.getValue());
            showSuccess(name);
        });

        task.setOnFailed(e -> {
            Arrays.fill(newPassword, '\0');
            Throwable ex = task.getException();
            log.warn("Ошибка восстановления", ex);
            setRestoring(false);
            String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            String lower = msg.toLowerCase();
            if (lower.contains("decrypt") || lower.contains("tag") || lower.contains("mac")
                    || lower.contains("bad") || lower.contains("aead")) {
                showError("Не удалось расшифровать .recovery — фраза не подходит к этому профилю");
            } else {
                showError("Не удалось восстановить: " + msg);
            }
        });

        Thread t = new Thread(task, "restore-keystore");
        t.setDaemon(true);
        t.start();
    }

    private void backToWelcome() {
        WelcomeView welcome = new WelcomeView(context);
        context.router().show(welcome.getRoot(), "JBlockStorage — Добро пожаловать");
    }

    private void showSuccess(String profileName) {
        Platform.runLater(() -> {
            PlaceholderView success = new PlaceholderView(
                    context,
                    "Профиль восстановлен",
                    "Keystore профиля «" + profileName + "» перезаписан с новым паролем.\n\n"
                            + "Теперь вы можете войти в систему обычным способом — "
                            + "вернитесь на экран приветствия и выберите «Войти».",
                    () -> {
                        WelcomeView welcome = new WelcomeView(context);
                        context.router().show(welcome.getRoot(),
                                "JBlockStorage — Добро пожаловать");
                    }
            );
            context.router().show(success.getRoot(), "JBlockStorage — Профиль восстановлен");
        });
    }

    private void setRestoring(boolean inProgress) {
        restoreBtn.setDisable(inProgress);
        cancelBtn.setDisable(inProgress);
        nameField.setDisable(inProgress);
        passwordField.setDisable(inProgress);
        confirmField.setDisable(inProgress);
        for (TextField f : wordFields) {
            f.setDisable(inProgress);
        }
        restoreBtn.setText(inProgress ? "Восстанавливаем…" : "Восстановить");
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
    // Утилиты — повторяют логику CreateProfileStep3View, но без зависимости
    // от пакета createprofile (RestoreView лежит в gui.views).
    // ------------------------------------------------------------------

    private static Path profilesDir() {
        String envHome = System.getenv("JBS_HOME");
        Path base = (envHome != null && !envHome.isBlank())
                ? Paths.get(envHome)
                : Paths.get(System.getProperty("user.home"), ".jblockstorage");
        return base.resolve("profiles");
    }

    private static String sanitize(String name) {
        String result = name.trim().replaceAll("[^\\p{L}\\p{N}_-]", "_");
        return result.isEmpty() ? "profile" : result;
    }
}
