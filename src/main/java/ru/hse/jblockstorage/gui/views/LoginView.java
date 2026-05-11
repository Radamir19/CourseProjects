package ru.hse.jblockstorage.gui.views;

import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.hse.jblockstorage.app.NodeApplication;
import ru.hse.jblockstorage.config.NodeConfig;
import ru.hse.jblockstorage.config.SeedNode;
import ru.hse.jblockstorage.crypto.KeyManager;
import ru.hse.jblockstorage.gui.AppContext;
import ru.hse.jblockstorage.gui.components.OnboardingSidebar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Экран входа по существующему keystore-файлу.
 *
 * <p>Дизайн (по итоговым мокапам):
 * <ul>
 *   <li>Sidebar — статус сети + SVG-схема узлов</li>
 *   <li>Колонка 340px: «← Назад», H1 «С возвращением», подзаголовок,
 *       form-card с выбранным keystore (KEY-бейдж, имя, путь, кнопка
 *       «Изменить»), внутри — поле пароля. Под карточкой — full-width
 *       кнопка «Войти», и тонкая ссылка «Восстановить по фразе»</li>
 * </ul>
 *
 * <p>По умолчанию первым подгружается профиль из {@code ~/.jblockstorage/profiles/}
 * (берём первый отсортированный {@code .keys}-файл). Пользователь может
 * указать любой другой через FileChooser.
 */
public final class LoginView {

    private static final Logger log = LoggerFactory.getLogger(LoginView.class);

    private final AppContext context;
    private final Parent root;

    /** Текущий выбранный keystore. Может быть {@code null} (нет профилей). */
    private Path keystorePath;

    private Label fileNameLabel;
    private Label filePathLabel;
    private PasswordField passwordField;
    private Label errorLabel;
    private Button loginBtn;

    public LoginView(AppContext context) {
        this.context = Objects.requireNonNull(context, "context");
        this.keystorePath = findFirstKeystore();
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
        // ← Назад
        Hyperlink back = new Hyperlink("‹ Назад");
        back.getStyleClass().add("back-link");
        back.setOnAction(e -> backToWelcome());

        Label title = new Label("С возвращением");
        title.getStyleClass().add("h1");

        Label subtitle = new Label("Откройте файл ключа и введите пароль.");
        subtitle.getStyleClass().add("welcome-subtitle");
        subtitle.setWrapText(true);

        // Form-card: KEY-бейдж + имя/путь + кнопка «Изменить»; разделитель; пароль
        VBox formCard = buildFormCard();

        loginBtn = new Button("Войти");
        loginBtn.getStyleClass().addAll("btn-primary", "btn-full");
        loginBtn.setMaxWidth(Double.MAX_VALUE);
        loginBtn.setDefaultButton(true);
        loginBtn.setOnAction(e -> onLogin());

        errorLabel = new Label();
        errorLabel.getStyleClass().add("form-error");
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
        errorLabel.setWrapText(true);

        Hyperlink restore = new Hyperlink("Восстановить по фразе");
        restore.getStyleClass().add("back-link");
        restore.setOnAction(e -> onRestore());

        Label restorePrompt = new Label("Забыли пароль?");
        restorePrompt.getStyleClass().add("text-secondary");

        HBox restoreRow = new HBox(4, restorePrompt, restore);
        restoreRow.setAlignment(Pos.CENTER);

        VBox stack = new VBox(0, back, title, subtitle, formCard, errorLabel, loginBtn, restoreRow);
        stack.setSpacing(0);
        VBox.setMargin(title,    new javafx.geometry.Insets(20, 0, 0, 0));
        VBox.setMargin(subtitle, new javafx.geometry.Insets(6,  0, 0, 0));
        VBox.setMargin(formCard, new javafx.geometry.Insets(20, 0, 0, 0));
        VBox.setMargin(errorLabel, new javafx.geometry.Insets(8, 0, 0, 0));
        VBox.setMargin(loginBtn, new javafx.geometry.Insets(14, 0, 0, 0));
        VBox.setMargin(restoreRow, new javafx.geometry.Insets(14, 0, 0, 0));

        stack.setMaxWidth(WelcomeView.CONTENT_COLUMN_WIDTH);
        stack.setMinWidth(WelcomeView.CONTENT_COLUMN_WIDTH);
        stack.setAlignment(Pos.TOP_LEFT);

        StackPane wrap = new StackPane(stack);
        StackPane.setAlignment(stack, Pos.CENTER);
        wrap.getStyleClass().add("onboarding-content");
        return wrap;
    }

    /**
     * Form-card: верхняя строка — KEY-бейдж + имя/путь + кнопка «Изменить»,
     * разделитель, нижняя строка — поле «ПАРОЛЬ».
     */
    private VBox buildFormCard() {
        VBox card = new VBox();
        card.getStyleClass().add("form-card");

        // ---- Верхняя строка: keystore-файл --------------------------
        Region keyBadge = new Region();
        keyBadge.getStyleClass().add("key-badge");

        Label keyLabel = new Label("KEY");
        keyLabel.getStyleClass().add("key-badge-label");

        StackPane badgeStack = new StackPane(keyBadge, keyLabel);
        badgeStack.setMinSize(30, 34);
        badgeStack.setPrefSize(30, 34);

        fileNameLabel = new Label();
        fileNameLabel.getStyleClass().add("body");
        fileNameLabel.setStyle("-fx-font-weight: 500;");

        filePathLabel = new Label();
        filePathLabel.getStyleClass().add("text-secondary");

        VBox names = new VBox(2, fileNameLabel, filePathLabel);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button changeBtn = new Button("Изменить");
        changeBtn.getStyleClass().add("btn-ghost");
        changeBtn.setOnAction(e -> chooseFile());

        HBox topRow = new HBox(11, badgeStack, names, spacer, changeBtn);
        topRow.setAlignment(Pos.CENTER_LEFT);
        topRow.getStyleClass().add("form-card-row");

        // ---- Нижняя строка: ПАРОЛЬ ----------------------------------
        Label pwdLabel = new Label("ПАРОЛЬ");
        pwdLabel.getStyleClass().add("form-card-label");

        passwordField = new PasswordField();
        passwordField.setPromptText("••••••••");
        passwordField.getStyleClass().add("password-field-inline");

        VBox pwdBox = new VBox(3, pwdLabel, passwordField);
        pwdBox.getStyleClass().addAll("form-card-row", "form-card-row--last");

        card.getChildren().addAll(topRow, pwdBox);

        // Обновим отображение текущего keystore
        refreshFileLabels();
        return card;
    }

    // ------------------------------------------------------------------
    // FileChooser
    // ------------------------------------------------------------------

    private void chooseFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Выберите файл ключа JBlockStorage");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Файлы ключей (*.keys)", "*.keys")
        );
        // Стартовая директория: либо текущая папка keystore, либо профили
        Path initial = keystorePath != null
                ? keystorePath.getParent()
                : profilesDir();
        if (initial != null && Files.isDirectory(initial)) {
            chooser.setInitialDirectory(initial.toFile());
        }
        var file = chooser.showOpenDialog(context.router().stage());
        if (file == null) return;
        keystorePath = file.toPath();
        refreshFileLabels();
        hideError();
    }

    private void refreshFileLabels() {
        if (keystorePath == null) {
            fileNameLabel.setText("Файл не выбран");
            filePathLabel.setText("Нажмите «Изменить» чтобы выбрать .keys");
            return;
        }
        fileNameLabel.setText(keystorePath.getFileName().toString());
        filePathLabel.setText(prettyParentPath(keystorePath));
    }

    /** Сворачивает {@code /Users/X/...} до {@code ~/...}. */
    private static String prettyParentPath(Path p) {
        Path parent = p.getParent();
        if (parent == null) return "";
        String home = System.getProperty("user.home");
        String s = parent.toString();
        if (home != null && s.startsWith(home)) {
            return "~" + s.substring(home.length());
        }
        return s;
    }

    // ------------------------------------------------------------------
    // Login (async)
    // ------------------------------------------------------------------

    private void onLogin() {
        if (keystorePath == null || !Files.isRegularFile(keystorePath)) {
            showError("Сначала выберите файл ключа");
            return;
        }
        String password = passwordField.getText();
        if (password == null || password.isEmpty()) {
            showError("Введите пароль");
            return;
        }
        char[] passwordChars = password.toCharArray();

        hideError();
        setLoggingIn(true);

        Path keystore = keystorePath;
        String profileName = profileNameOf(keystore);
        // dataDir вычисляем снаружи Task, чтобы передать его не только
        // внутрь Task.call() для шардов/блокчейна, но и в onSucceeded — там
        // он нужен для открытия contacts.json (день 14, AppContext).
        Path dataDir = jbsHome().resolve("data").resolve(profileName);
        Task<NodeApplication> task = new Task<>() {
            @Override
            protected NodeApplication call() throws Exception {
                log.info("Login: пробуем войти под профилем «{}» ({})", profileName, keystore);

                PrivateKey priv = KeyManager.loadEncryptedPrivateKey(keystore, passwordChars);

                Path pubPath = keystore.resolveSibling(profileName + ".pub");
                if (!Files.exists(pubPath)) {
                    throw new IOException("Публичный ключ не найден: " + pubPath.getFileName());
                }
                PublicKey pub = KeyManager.loadPublicKey(pubPath);
                KeyPair kp = new KeyPair(pub, priv);

                Path shardsDir = dataDir.resolve("shards");
                Path blockchainDir = dataDir.resolve("blockchain");
                Files.createDirectories(shardsDir);
                Files.createDirectories(blockchainDir);

                // Приоритет источников (день 15, ТЗ п. 4.1.1.1.1):
                //   env JBS_PORT/JBS_SEEDS  >  config.properties  >  defaults
                // env существует ради демо-сценария «3 узла на одной
                // машине», у обычного пользователя его нет — тогда
                // подхватываем из <JBS_HOME>/config.properties, который
                // редактируется в Settings → Сеть → Seed-узлы.
                ru.hse.jblockstorage.gui.config.NodeConfigStore cfg = context.configStore();

                int finalPort = resolveListenPort(cfg);
                java.util.List<ru.hse.jblockstorage.config.SeedNode> finalSeeds =
                        resolveSeeds(cfg);

                NodeConfig config = NodeConfig.defaults().toBuilder()
                        .listenPort(finalPort)
                        .seedNodes(finalSeeds)
                        .build();

                NodeApplication app = NodeApplication.builder()
                        .config(config)
                        .keys(kp)
                        .shardsDir(shardsDir)
                        .blockchainDir(blockchainDir)
                        .build();
                app.start();
                log.info("Login: узел поднят на порту {} (seeds={})",
                        app.listenPort(), finalSeeds.size());
                return app;
            }
        };

        task.setOnSucceeded(e -> {
            Arrays.fill(passwordChars, '\0');
            NodeApplication node = task.getValue();
            try {
                context.setSession(node, profileName, dataDir);
            } catch (RuntimeException ex) {
                try { node.close(); } catch (Exception ignored) {}
                setLoggingIn(false);
                showError("Сессия уже активна. Выйдите из текущей и попробуйте снова.");
                return;
            }
            DashboardView dashboard = new DashboardView(context);
            context.router().show(dashboard.getRoot(), "JBlockStorage — " + profileName);
        });

        task.setOnFailed(e -> {
            Arrays.fill(passwordChars, '\0');
            Throwable ex = task.getException();
            log.warn("Ошибка Login", ex);
            setLoggingIn(false);
            String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            String lower = msg.toLowerCase();
            if (lower.contains("bad") || lower.contains("tag") || lower.contains("mac")
                    || lower.contains("aead") || lower.contains("decrypt")) {
                showError("Неверный пароль");
            } else {
                showError("Не удалось войти: " + msg);
            }
        });

        Thread t = new Thread(task, "login");
        t.setDaemon(true);
        t.start();
    }

    private void onRestore() {
        // ТЗ 4.1.5.1: восстановление профиля по BIP-39 seed-фразе.
        RestoreView restore = new RestoreView(context);
        context.router().show(restore.getRoot(), "JBlockStorage — Восстановление");
    }

    private void backToWelcome() {
        WelcomeView welcome = new WelcomeView(context);
        context.router().show(welcome.getRoot(), "JBlockStorage — Добро пожаловать");
    }

    private void setLoggingIn(boolean inProgress) {
        loginBtn.setDisable(inProgress);
        loginBtn.setText(inProgress ? "Входим…" : "Войти");
        passwordField.setDisable(inProgress);
    }

    private void showError(String msg) {
        errorLabel.setText(msg);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }

    private void hideError() {
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
    }

    // ------------------------------------------------------------------
    // Утилиты сканирования профилей
    // ------------------------------------------------------------------

    private static Path profilesDir() {
        return jbsHome().resolve("profiles");
    }

    /**
     * База данных приложения. По умолчанию {@code ~/.jblockstorage}, но
     * может быть переопределена через переменную окружения {@code JBS_HOME}
     * — это нужно, чтобы запускать несколько узлов рядом на одной машине
     * (демо-сценарий из ТЗ п. 8.2.1: «минимум три экземпляра приложения
     * на одном компьютере с различными сетевыми портами»). Без отдельной
     * home-папки у узлов лочится RocksDB и они видят keystore друг друга.
     */
    private static Path jbsHome() {
        String env = System.getenv("JBS_HOME");
        if (env != null && !env.isBlank()) {
            return Paths.get(env);
        }
        return Paths.get(System.getProperty("user.home"), ".jblockstorage");
    }

    /**
     * Решает финальный listenPort с учётом приоритета: env > config > default.
     * env — это {@code JBS_PORT}; config — поле {@code listenPort} в
     * {@code config.properties}; default — {@link NodeConfig#defaults()}.
     */
    private static int resolveListenPort(
            ru.hse.jblockstorage.gui.config.NodeConfigStore cfg) {
        // 1. env. Если задан и валиден — он побеждает.
        String env = System.getenv("JBS_PORT");
        if (env != null && !env.isBlank()) {
            try {
                int p = Integer.parseInt(env.trim());
                if (p >= 0 && p <= 65535) return p;
                log.warn("JBS_PORT={} вне диапазона, пробуем config", p);
            } catch (NumberFormatException e) {
                log.warn("JBS_PORT='{}' не число, пробуем config", env);
            }
        }
        // 2. config.properties.
        if (cfg != null && cfg.getListenPort().isPresent()) {
            return cfg.getListenPort().getAsInt();
        }
        // 3. default.
        return NodeConfig.defaults().getListenPort();
    }

    /**
     * Решает финальный список seed-узлов с приоритетом env > config > пусто.
     * Если env задан непустой — он используется целиком, даже если в
     * config'е ещё что-то есть (ожидание: env — для тестов, config — для
     * прода, не нужно их склеивать).
     */
    private static java.util.List<ru.hse.jblockstorage.config.SeedNode> resolveSeeds(
            ru.hse.jblockstorage.gui.config.NodeConfigStore cfg) {
        java.util.List<ru.hse.jblockstorage.config.SeedNode> envList = envSeedNodes();
        if (!envList.isEmpty()) return envList;
        if (cfg != null) return cfg.getSeeds();
        return java.util.List.of();
    }

    /**
     * Список seed-узлов из {@code JBS_SEEDS} в формате
     * {@code host1:port1,host2:port2}. Пустая или незаданная переменная →
     * пустой список (узел стартует один и ждёт, что к нему сами подключатся).
     */
    private static List<SeedNode> envSeedNodes() {
        String env = System.getenv("JBS_SEEDS");
        if (env == null || env.isBlank()) return List.of();
        List<SeedNode> result = new ArrayList<>();
        for (String entry : env.split(",")) {
            String s = entry.trim();
            if (s.isEmpty()) continue;
            int colon = s.lastIndexOf(':');
            if (colon < 0) {
                log.warn("JBS_SEEDS: пропускаю '{}' (нет порта)", s);
                continue;
            }
            String host = s.substring(0, colon).trim();
            String portStr = s.substring(colon + 1).trim();
            try {
                int port = Integer.parseInt(portStr);
                result.add(new SeedNode(host, port));
            } catch (IllegalArgumentException e) {
                // IllegalArgumentException покрывает и NumberFormatException
                // (если portStr не число), и проверки внутри SeedNode
                // (порт <= 0 или > 65535).
                log.warn("JBS_SEEDS: пропускаю '{}': {}", s, e.getMessage());
            }
        }
        return result;
    }

    private static Path findFirstKeystore() {
        Path dir = profilesDir();
        if (!Files.isDirectory(dir)) return null;
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.getFileName().toString().endsWith(".keys"))
                    .sorted()
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            log.warn("Не удалось прочитать каталог профилей {}: {}", dir, e.toString());
            return null;
        }
    }

    private static String profileNameOf(Path keystorePath) {
        String filename = keystorePath.getFileName().toString();
        return filename.endsWith(".keys")
                ? filename.substring(0, filename.length() - ".keys".length())
                : filename;
    }
}
