package ru.hse.jblockstorage.gui.views;

import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.hse.jblockstorage.app.NodeApplication;
import ru.hse.jblockstorage.gui.AppContext;
import ru.hse.jblockstorage.gui.components.FormatUtils;
import ru.hse.jblockstorage.gui.theme.AppTheme;
import ru.hse.jblockstorage.gui.theme.ThemeManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Раздел «Настройки» главного экрана — день 14 (5 секций) +
 * день 15 (секция SEED-УЗЛЫ).
 *
 * <p>Состоит из 6 секций (карточек {@code .props-card}):
 * <ol>
 *   <li><b>Вид</b> — выбор темы (Системная/Светлая/Тёмная) через
 *       {@link ThemeManager}, мгновенно применяется ко всему приложению.</li>
 *   <li><b>Хранилище</b> — путь к шардам, к блокчейну, занятый размер
 *       (Files.walk). Read-only — поменять без перезапуска нельзя.</li>
 *   <li><b>Безопасность</b> — публичный ключ профиля с кнопкой «Копировать»
 *       (это та же строка, которая нужна получателю в адресной книге).
 *       Кнопка «Сменить пароль» — заглушка.</li>
 *   <li><b>Сеть</b> — TCP-порт, число активных подключений, число
 *       известных пиров, кнопка «Очистить запомненные узлы» (стирает
 *       {@code peers.json} — пригодится при отладке).</li>
 *   <li><b>Seed-узлы</b> — адреса узлов из {@code config.properties} с
 *       кнопками add/remove. Применяются при следующем рестарте.
 *       Реализует требование ТЗ п. 4.1.1.1.1 о подключении к сети
 *       через список из конфигурационного файла.</li>
 *   <li><b>Профиль</b> — имя профиля + кнопка «Выйти» (закрывает узел и
 *       возвращает на Welcome).</li>
 * </ol>
 *
 * <p>Прокручивается, потому что 6 секций обычно не помещаются в высоту
 * без scroll'а на типичном мониторе.
 */
public final class SettingsContentView {

    private static final Logger log = LoggerFactory.getLogger(SettingsContentView.class);

    private final AppContext context;
    private final Parent root;

    /** Слушатель темы — нужно отписываться при logout, иначе утечка. */
    private ChangeListener<AppTheme> themeListener;
    private ChoiceBox<AppTheme> themeChoice;

    /** Динамические лейблы — обновляем при reload. */
    private Label storageSizeValue;
    private Label networkActiveValue;
    private Label networkKnownValue;
    /**
     * Контейнер строк seed-списка. Перерисовывается целиком при
     * добавлении/удалении seed (их обычно 1-3, не нужна виртуализация).
     */
    private VBox seedListContainer;

    public SettingsContentView(AppContext context) {
        this.context = Objects.requireNonNull(context, "context");
        if (!context.isAuthenticated()) {
            throw new IllegalStateException("SettingsContentView требует активную сессию");
        }
        this.root = build();
    }

    public Parent getRoot() {
        return root;
    }

    /** Перечитать динамические значения (размер, сеть). */
    public void reload() {
        if (storageSizeValue != null) {
            storageSizeValue.setText(computeShardsSize());
        }
        if (networkActiveValue != null) {
            int active = context.node().peerManager().snapshotSessions().size();
            networkActiveValue.setText(String.valueOf(active));
        }
        if (networkKnownValue != null) {
            int known = context.node().knownPeers().size();
            networkKnownValue.setText(String.valueOf(known));
        }
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    private Parent build() {
        Label title = new Label("Настройки");
        title.getStyleClass().add("section-title");

        Label subtitle = new Label("Тема, хранилище, безопасность, сеть и профиль");
        subtitle.getStyleClass().add("section-subtitle");

        VBox titles = new VBox(4, title, subtitle);
        VBox header = new VBox(titles);
        header.getStyleClass().add("section-header");

        VBox sections = new VBox(18,
                buildAppearanceSection(),
                buildStorageSection(),
                buildSecuritySection(),
                buildNetworkSection(),
                buildSeedNodesSection(),
                buildProfileSection());
        sections.setPadding(new Insets(0, 28, 28, 28));

        VBox content = new VBox(header, sections);

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("app-scroll");

        VBox outer = new VBox(scroll);
        outer.getStyleClass().add("app-background");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return outer;
    }

    // ------------------------------------------------------------------
    // 1. Вид (тема)
    // ------------------------------------------------------------------

    private VBox buildAppearanceSection() {
        Label sectionLabel = new Label("ВИД");
        sectionLabel.getStyleClass().add("props-section-label");

        themeChoice = new ChoiceBox<>(FXCollections.observableArrayList(AppTheme.values()));
        themeChoice.setValue(context.themeManager().getTheme());
        // Двусторонний биндинг: смена в ChoiceBox → ThemeManager →
        // применение CSS. Если тема меняется снаружи (теоретически), мы
        // тоже отразим это.
        themeChoice.valueProperty().addListener((obs, old, val) -> {
            if (val != null && val != context.themeManager().getTheme()) {
                context.themeManager().setTheme(val);
            }
        });
        themeListener = (obs, old, val) -> {
            if (val != null && !val.equals(themeChoice.getValue())) {
                themeChoice.setValue(val);
            }
        };
        context.themeManager().themeProperty().addListener(themeListener);

        VBox card = new VBox();
        card.getStyleClass().add("props-card");
        card.getChildren().add(controlRow("Тема оформления",
                "Системная — следует за настройкой ОС.",
                themeChoice, true));

        return new VBox(8, sectionLabel, card);
    }

    // ------------------------------------------------------------------
    // 2. Хранилище
    // ------------------------------------------------------------------

    private VBox buildStorageSection() {
        Label sectionLabel = new Label("ХРАНИЛИЩЕ");
        sectionLabel.getStyleClass().add("props-section-label");

        Path dataDir = context.profileDataDir();
        String shardsPath = dataDir != null
                ? dataDir.resolve("shards").toString()
                : "—";
        String blockchainPath = dataDir != null
                ? dataDir.resolve("blockchain").toString()
                : "—";

        VBox card = new VBox();
        card.getStyleClass().add("props-card");

        card.getChildren().add(monoValueRow("Папка шардов", shardsPath, false));
        card.getChildren().add(monoValueRow("Папка блокчейна", blockchainPath, false));

        storageSizeValue = new Label(computeShardsSize());
        storageSizeValue.getStyleClass().add("props-row-value");
        card.getChildren().add(controlRow("Занято на диске",
                "Сумма размеров всех шардов в локальной папке.",
                storageSizeValue, true));

        return new VBox(8, sectionLabel, card);
    }

    /** Сумма размеров файлов в shards/ — для UI. */
    private String computeShardsSize() {
        Path dataDir = context.profileDataDir();
        if (dataDir == null) return "—";
        Path shards = dataDir.resolve("shards");
        if (!Files.isDirectory(shards)) return "0 Б";
        try (Stream<Path> walk = Files.walk(shards)) {
            long total = walk
                    .filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return 0L;
                        }
                    })
                    .sum();
            return FormatUtils.humanSize(total);
        } catch (IOException e) {
            return "—";
        }
    }

    // ------------------------------------------------------------------
    // 3. Безопасность
    // ------------------------------------------------------------------

    private VBox buildSecuritySection() {
        Label sectionLabel = new Label("БЕЗОПАСНОСТЬ");
        sectionLabel.getStyleClass().add("props-section-label");

        VBox card = new VBox();
        card.getStyleClass().add("props-card");

        // Публичный ключ профиля — длинная строка, показываем сокращённо
        // и даём кнопку «Копировать» (= тот же UX, что в FilePropertiesDialog).
        String pubKey = context.node().selfNodeId();

        Label keyLabel = new Label("Публичный ключ");
        keyLabel.getStyleClass().add("props-row-label");

        Label keyValue = new Label(FormatUtils.shortenPubKey(pubKey));
        keyValue.getStyleClass().add("props-row-value-mono");

        Button copyBtn = new Button("Копировать");
        copyBtn.getStyleClass().add("btn-copy");
        copyBtn.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(pubKey);
            Clipboard.getSystemClipboard().setContent(cc);
            // Лёгкая инлайн-индикация «Скопировано»
            String old = copyBtn.getText();
            copyBtn.setText("Скопировано");
            copyBtn.setDisable(true);
            javafx.animation.PauseTransition pt =
                    new javafx.animation.PauseTransition(javafx.util.Duration.seconds(1.2));
            pt.setOnFinished(ev -> {
                copyBtn.setText(old);
                copyBtn.setDisable(false);
            });
            pt.play();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox keyRow = new HBox(keyLabel, spacer, keyValue, copyBtn);
        keyRow.getStyleClass().add("props-row");
        keyRow.setAlignment(Pos.CENTER_LEFT);
        card.getChildren().add(keyRow);

        // Шифрование — статичная инфо-строка
        card.getChildren().add(simpleRow("Шифрование", "AES-256-GCM, ключи RSA-2048", false));

        // Сменить пароль — заглушка
        Button changePwd = new Button("Сменить пароль…");
        changePwd.getStyleClass().add("btn-secondary");
        changePwd.setOnAction(e -> {
            Alert a = new Alert(AlertType.INFORMATION);
            a.initOwner(context.router().stage());
            a.setHeaderText("Смена пароля");
            a.setContentText(
                    "Этот раздел появится в следующей итерации. "
                            + "Пока пароль keystore меняется только пересозданием "
                            + "профиля по seed-фразе из 12 слов.");
            a.showAndWait();
        });
        card.getChildren().add(controlRow("Пароль keystore",
                "Защищает приватный ключ. Меняется по seed-фразе.",
                changePwd, true));

        return new VBox(8, sectionLabel, card);
    }

    // ------------------------------------------------------------------
    // 4. Сеть
    // ------------------------------------------------------------------

    private VBox buildNetworkSection() {
        Label sectionLabel = new Label("СЕТЬ");
        sectionLabel.getStyleClass().add("props-section-label");

        NodeApplication node = context.node();
        VBox card = new VBox();
        card.getStyleClass().add("props-card");

        card.getChildren().add(simpleRow("TCP-порт",
                String.valueOf(node.listenPort()), false));

        networkActiveValue = new Label(String.valueOf(
                node.peerManager().snapshotSessions().size()));
        networkActiveValue.getStyleClass().add("props-row-value");
        card.getChildren().add(controlRow("Активных подключений",
                "Узлов, с которыми сейчас открыто TCP-соединение.",
                networkActiveValue, false));

        networkKnownValue = new Label(String.valueOf(node.knownPeers().size()));
        networkKnownValue.getStyleClass().add("props-row-value");

        Button refreshBtn = new Button("Обновить");
        refreshBtn.getStyleClass().add("btn-copy");
        refreshBtn.setOnAction(e -> reload());

        Label knownLabel = new Label("Запомненных узлов");
        knownLabel.getStyleClass().add("props-row-label");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox knownRow = new HBox(knownLabel, spacer, networkKnownValue, refreshBtn);
        knownRow.getStyleClass().add("props-row");
        knownRow.setAlignment(Pos.CENTER_LEFT);
        card.getChildren().add(knownRow);

        // Кнопка «Очистить запомненные узлы»
        Button clearBtn = new Button("Очистить запомненные узлы");
        clearBtn.getStyleClass().add("btn-secondary");
        clearBtn.setOnAction(e -> onClearKnownPeers());
        card.getChildren().add(controlRow("peers.json",
                "Удалить сохранённый кэш сетевых соседей. После этого "
                        + "узел будет искать пиров заново через seed-узлы.",
                clearBtn, true));

        return new VBox(8, sectionLabel, card);
    }

    /**
     * Чистит {@code peers.json} текущего профиля. Сами активные сессии
     * не разрываем — пользователь не должен потерять подключения, ему
     * только важно, что при следующем рестарте кэш не подтянется.
     */
    private void onClearKnownPeers() {
        Path dataDir = context.profileDataDir();
        if (dataDir == null) return;
        Path peersFile = dataDir.resolve("peers.json");

        Alert confirm = new Alert(AlertType.CONFIRMATION);
        confirm.initOwner(context.router().stage());
        confirm.setHeaderText("Очистить кэш запомненных узлов?");
        confirm.setContentText(
                "Файл " + peersFile.getFileName() + " будет удалён. "
                        + "Активные подключения сохранятся, но при следующем "
                        + "запуске узел заново найдёт пиров через seed-узлы.");
        ButtonType ok = new ButtonType("Очистить", ButtonType.OK.getButtonData());
        ButtonType cancel = new ButtonType("Отмена", ButtonType.CANCEL.getButtonData());
        confirm.getButtonTypes().setAll(ok, cancel);

        Optional<ButtonType> r = confirm.showAndWait();
        if (r.isEmpty() || r.get() != ok) return;

        try {
            Files.deleteIfExists(peersFile);
            log.info("peers.json удалён по запросу пользователя");
            // Обновляем счётчик. Список «знаемых» пиров живёт в памяти
            // PeerManager — мы его не трогаем, чтобы не разрушить
            // активную сеть. После рестарта он будет пуст.
            Alert ok2 = new Alert(AlertType.INFORMATION);
            ok2.initOwner(context.router().stage());
            ok2.setHeaderText("Кэш очищен");
            ok2.setContentText(
                    "Файл peers.json удалён. Активные подключения "
                            + "продолжают работать. После рестарта узел "
                            + "найдёт пиров заново через seed-узлы.");
            ok2.showAndWait();
        } catch (IOException ex) {
            log.warn("Не удалось удалить peers.json", ex);
            Alert err = new Alert(AlertType.ERROR);
            err.initOwner(context.router().stage());
            err.setHeaderText("Не удалось очистить кэш");
            err.setContentText(ex.getMessage() != null ? ex.getMessage()
                    : ex.getClass().getSimpleName());
            err.showAndWait();
        }
    }

    // ------------------------------------------------------------------
    // 4.5. Seed-узлы (день 15, ТЗ п. 4.1.1.1.1)
    // ------------------------------------------------------------------

    /**
     * Секция «SEED-УЗЛЫ» — управление списком seed'ов в
     * {@code <JBS_HOME>/config.properties}. Изменения применяются при
     * следующем запуске приложения; текущая Netty-сессия не трогается,
     * чтобы не разорвать активные подключения у пользователя.
     */
    private VBox buildSeedNodesSection() {
        Label sectionLabel = new Label("SEED-УЗЛЫ");
        sectionLabel.getStyleClass().add("props-section-label");

        VBox card = new VBox();
        card.getStyleClass().add("props-card");

        // Подсказка про смысл и про «применяется после рестарта»
        Label hint = new Label(
                "Адреса узлов, к которым приложение подключается при старте. "
                        + "Список сохраняется в config.properties и применяется "
                        + "при следующем запуске приложения.");
        hint.getStyleClass().add("section-subtitle");
        hint.setWrapText(true);
        VBox hintWrap = new VBox(hint);
        hintWrap.setPadding(new Insets(8, 14, 8, 14));
        card.getChildren().add(hintWrap);

        // Сам список
        seedListContainer = new VBox();
        renderSeedRows();
        card.getChildren().add(seedListContainer);

        // Нижняя строка с кнопкой «+ Добавить seed»
        Button addBtn = new Button("+ Добавить seed");
        addBtn.getStyleClass().add("btn-secondary");
        addBtn.setOnAction(e -> onAddSeed());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label fileHint = new Label("config.properties");
        fileHint.getStyleClass().add("props-row-value-mono");

        HBox addRow = new HBox(8, fileHint, spacer, addBtn);
        addRow.setAlignment(Pos.CENTER_LEFT);
        addRow.getStyleClass().addAll("props-row", "props-row--last");
        card.getChildren().add(addRow);

        return new VBox(8, sectionLabel, card);
    }

    /** Перерисовывает список seed-строк (вызывается при add/remove). */
    private void renderSeedRows() {
        if (seedListContainer == null) return;
        seedListContainer.getChildren().clear();

        var cfg = context.configStore();
        if (cfg == null) {
            // Конфиг недоступен (старый сценарий) — показываем info-строку
            Label l = new Label("Конфиг приложения недоступен.");
            l.getStyleClass().add("section-subtitle");
            HBox row = new HBox(l);
            row.getStyleClass().add("props-row");
            row.setPadding(new Insets(10, 14, 10, 14));
            seedListContainer.getChildren().add(row);
            return;
        }

        var seeds = cfg.getSeeds();
        if (seeds.isEmpty()) {
            Label l = new Label("Список seed-узлов пуст.");
            l.getStyleClass().add("section-subtitle");
            HBox row = new HBox(l);
            row.getStyleClass().add("props-row");
            row.setPadding(new Insets(10, 14, 10, 14));
            seedListContainer.getChildren().add(row);
            return;
        }

        for (var seed : seeds) {
            seedListContainer.getChildren().add(buildSeedRow(seed));
        }
    }

    /** Одна строка seed: «host:port» + кнопка «Удалить». */
    private HBox buildSeedRow(ru.hse.jblockstorage.config.SeedNode seed) {
        Label addr = new Label(seed.host() + ":" + seed.port());
        addr.getStyleClass().add("props-row-value-mono");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button delBtn = new Button("Удалить");
        delBtn.getStyleClass().add("btn-copy");
        delBtn.setOnAction(e -> onRemoveSeed(seed));

        HBox row = new HBox(8, addr, spacer, delBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("props-row");
        return row;
    }

    /** Открыть AddSeedDialog (день 15 финал — заменил TextInputDialog). */
    private void onAddSeed() {
        var cfg = context.configStore();
        if (cfg == null) return;
        ru.hse.jblockstorage.gui.dialogs.AddSeedDialog dlg =
                new ru.hse.jblockstorage.gui.dialogs.AddSeedDialog(
                        context, seed -> renderSeedRows());
        dlg.showAsWindow(context.router().stage());
    }

    /** Удалить seed по точному совпадению (с подтверждением). */
    private void onRemoveSeed(ru.hse.jblockstorage.config.SeedNode seed) {
        var cfg = context.configStore();
        if (cfg == null) return;

        Alert confirm = new Alert(AlertType.CONFIRMATION);
        confirm.initOwner(context.router().stage());
        confirm.setHeaderText("Удалить seed «" + seed + "»?");
        confirm.setContentText(
                "Этот адрес перестанет использоваться при следующем запуске. "
                        + "Текущее подключение, если оно есть, останется активным.");
        ButtonType ok = new ButtonType("Удалить", ButtonType.OK.getButtonData());
        ButtonType cancel = new ButtonType("Отмена", ButtonType.CANCEL.getButtonData());
        confirm.getButtonTypes().setAll(ok, cancel);

        Optional<ButtonType> r = confirm.showAndWait();
        if (r.isEmpty() || r.get() != ok) return;

        try {
            cfg.removeSeed(seed);
            renderSeedRows();
        } catch (IOException ex) {
            log.warn("Не удалось сохранить config.properties", ex);
            Alert a = new Alert(AlertType.ERROR);
            a.initOwner(context.router().stage());
            a.setHeaderText("Не удалось сохранить конфиг");
            a.setContentText(ex.getMessage() != null
                    ? ex.getMessage() : ex.getClass().getSimpleName());
            a.showAndWait();
        }
    }

    // ------------------------------------------------------------------
    // 5. Профиль
    // ------------------------------------------------------------------

    private VBox buildProfileSection() {
        Label sectionLabel = new Label("ПРОФИЛЬ");
        sectionLabel.getStyleClass().add("props-section-label");

        VBox card = new VBox();
        card.getStyleClass().add("props-card");

        card.getChildren().add(simpleRow("Имя профиля", context.profileName(), false));

        Path dataDir = context.profileDataDir();
        card.getChildren().add(monoValueRow("Папка данных",
                dataDir != null ? dataDir.toString() : "—", false));

        Button logoutBtn = new Button("Выйти из профиля");
        logoutBtn.getStyleClass().add("btn-danger");
        logoutBtn.setOnAction(e -> onLogout());
        card.getChildren().add(controlRow("Завершить сессию",
                "Узел остановится, RocksDB закроется, вы вернётесь на стартовый экран.",
                logoutBtn, true));

        return new VBox(8, sectionLabel, card);
    }

    private void onLogout() {
        Alert confirm = new Alert(AlertType.CONFIRMATION);
        confirm.initOwner(context.router().stage());
        confirm.setHeaderText("Выйти из профиля «" + context.profileName() + "»?");
        confirm.setContentText(
                "Узел будет остановлен. Данные на диске сохранятся: "
                        + "файлы, блокчейн и адресная книга останутся на месте.");
        ButtonType ok = new ButtonType("Выйти", ButtonType.OK.getButtonData());
        ButtonType cancel = new ButtonType("Отмена", ButtonType.CANCEL.getButtonData());
        confirm.getButtonTypes().setAll(ok, cancel);

        Optional<ButtonType> r = confirm.showAndWait();
        if (r.isEmpty() || r.get() != ok) return;

        // Отписываемся от theme listener'а перед logout
        if (themeListener != null) {
            try {
                context.themeManager().themeProperty().removeListener(themeListener);
            } catch (Exception ignored) {}
            themeListener = null;
        }
        log.info("Logout по кнопке Settings → Профиль");
        context.logout();
        WelcomeView welcome = new WelcomeView(context);
        context.router().show(welcome.getRoot(), "JBlockStorage — Добро пожаловать");
    }

    // ------------------------------------------------------------------
    // Шаблоны строк (без вытаскивания в отдельный класс — простые HBox)
    // ------------------------------------------------------------------

    /** Простая строка «label: value» без подзаголовка. */
    private HBox simpleRow(String label, String value, boolean last) {
        Label l = new Label(label);
        l.getStyleClass().add("props-row-label");

        Label v = new Label(value);
        v.getStyleClass().add("props-row-value");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(l, spacer, v);
        row.getStyleClass().add("props-row");
        if (last) row.getStyleClass().add("props-row--last");
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** Строка с моноширинным значением (для путей). */
    private HBox monoValueRow(String label, String value, boolean last) {
        Label l = new Label(label);
        l.getStyleClass().add("props-row-label");

        Label v = new Label(value);
        v.getStyleClass().add("props-row-value-mono");
        v.setWrapText(false);
        v.setMaxWidth(360);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(l, spacer, v);
        row.getStyleClass().add("props-row");
        if (last) row.getStyleClass().add("props-row--last");
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /**
     * Строка с лейблом, кратким описанием и контролом справа.
     * Описание пишется второй строкой под лейблом (как в iOS Settings).
     */
    private HBox controlRow(String label, String hint, javafx.scene.Node control, boolean last) {
        Label l = new Label(label);
        l.getStyleClass().add("props-row-label");
        l.setStyle("-fx-min-width: 0;"); // переопределяем 130px из base.css

        Label h = new Label(hint);
        h.getStyleClass().add("section-subtitle");
        h.setWrapText(true);
        h.setMaxWidth(320);

        VBox texts = new VBox(2, l, h);
        HBox.setHgrow(texts, Priority.ALWAYS);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.SOMETIMES);

        HBox row = new HBox(12, texts, spacer, control);
        row.getStyleClass().add("props-row");
        if (last) row.getStyleClass().add("props-row--last");
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }
}
