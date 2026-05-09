package ru.hse.jblockstorage.gui.views;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.hse.jblockstorage.gui.AppContext;
import ru.hse.jblockstorage.gui.components.MainSidebar;
import ru.hse.jblockstorage.gui.components.MainSidebar.Section;

import java.util.Objects;

/**
 * Главный экран приложения после Login.
 *
 * <p>Раскладка двухколоночная (по мокапам {@code dashboard_*}):
 * <pre>
 *   ┌────────────┬──────────────────────────────────┐
 *   │ MainSidebar│ [баннер «нет подключений», если]  │
 *   │  240px     │ Content area (раздел из меню)    │
 *   └────────────┴──────────────────────────────────┘
 * </pre>
 *
 * <p>Sidebar — это {@link MainSidebar}, принимает
 * {@code UploadProgressState} из {@link AppContext}: когда идёт загрузка,
 * в sidebar появляется компактный блок «Идёт загрузка X%» поверх
 * «Сеть стабильна» (по мокапу {@code upload_progress.html}).
 *
 * <p><b>День 14 — закрыты все четыре раздела меню</b>: «Мои файлы»,
 * «Расшаренные мне», «Контакты», «Настройки». Все view кэшируются как
 * поля, чтобы скрытие/возврат на вкладку не пересоздавал тяжёлые
 * структуры (RocksDB-итерации, ContactStore reload и т.д.) — мы только
 * вызываем {@code reload()}, если данные могли измениться.
 *
 * <p><b>День 15 — баннер «нет подключений»:</b> если у пользователя
 * пустой список seed-узлов в {@code config.properties} И нет ни одного
 * активного подключения, поверх контента появляется dismissable-баннер
 * с CTA «Открыть Настройки → Seed-узлы». Баннер автоматически прячется,
 * когда подключение появляется. Реализует требование «полностью рабочее
 * приложение из коробки» — пользователь сразу понимает, почему он один
 * в сети, и куда идти, чтобы это исправить.
 */
public final class DashboardView {

    private static final Logger log = LoggerFactory.getLogger(DashboardView.class);

    private final AppContext context;
    private final Parent root;
    private final MainSidebar sidebar;
    private final StackPane contentArea;
    /** Закешированные экземпляры — чтобы переход не создавал заново. */
    private MyFilesContentView myFilesView;
    private SharedFilesContentView sharedFilesView;
    private ContactsContentView contactsView;
    private SettingsContentView settingsView;

    /** Тонкий info-баннер сверху на Dashboard'е (день 15). */
    private HBox isolationBanner;
    /** Опрос состояния сети для скрытия/показа баннера. */
    private Timeline isolationPoll;
    /**
     * Если пользователь руками закрыл баннер — больше не показываем
     * до конца сессии (даже если состояние снова стало «нет подключений»).
     */
    private boolean isolationBannerDismissed;

    public DashboardView(AppContext context) {
        this.context = Objects.requireNonNull(context, "context");
        if (!context.isAuthenticated()) {
            throw new IllegalStateException("DashboardView требует активную сессию");
        }
        this.sidebar = new MainSidebar(
                context.node(),
                context.profileName(),
                context.uploadState());
        this.contentArea = new StackPane();
        this.contentArea.getStyleClass().add("app-background");

        sidebar.setOnSectionChanged(this::showSection);

        // Стартовый раздел — Мои файлы
        showSection(Section.MY_FILES);

        this.root = buildRoot();
    }

    public Parent getRoot() {
        return root;
    }

    private Parent buildRoot() {
        // Правая колонка: баннер изоляции (если показывается) + контент
        VBox rightColumn = new VBox();
        rightColumn.getStyleClass().add("app-background");
        VBox.setVgrow(contentArea, Priority.ALWAYS);

        isolationBanner = buildIsolationBanner();
        isolationBanner.setVisible(false);
        isolationBanner.setManaged(false);

        rightColumn.getChildren().setAll(isolationBanner, contentArea);

        HBox layout = new HBox(sidebar.getRoot(), rightColumn);
        HBox.setHgrow(rightColumn, Priority.ALWAYS);
        layout.getStyleClass().add("app-background");

        layout.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null) {
                sidebar.shutdown();
                stopIsolationPoll();
            }
        });

        // Стартуем периодическую проверку состояния сети (раз в 3с —
        // достаточно редко, чтобы не нагружать, и достаточно быстро,
        // чтобы пользователь увидел, как баннер исчезает после связи).
        startIsolationPoll();
        refreshIsolationBanner();
        return layout;
    }

    /**
     * Переключает контент в зависимости от выбранного раздела.
     * Также обновляет счётчик в sidebar (для «Мои файлы» и «Расшаренные»).
     */
    private void showSection(Section section) {
        log.debug("Dashboard → раздел: {}", section);
        Parent content;
        switch (section) {
            case MY_FILES -> {
                if (myFilesView == null) {
                    myFilesView = new MyFilesContentView(context);
                    myFilesView.setOnCountChanged(this::updateMyFilesCount);
                }
                content = myFilesView.getRoot();
                updateMyFilesCount(myFilesView.getFileCount());
            }
            case SHARED -> {
                if (sharedFilesView == null) {
                    sharedFilesView = new SharedFilesContentView(context);
                    sharedFilesView.setOnCountChanged(this::updateSharedFilesCount);
                }
                content = sharedFilesView.getRoot();
                // При каждом переходе обновляем список — могли появиться
                // новые ACL во время того, как пользователь был на
                // другой вкладке. listAccessibleFiles читает из локального
                // блокчейна, поэтому это очень дёшево.
                sharedFilesView.reload();
                updateSharedFilesCount(sharedFilesView.getFileCount());
            }
            case CONTACTS -> {
                if (contactsView == null) {
                    contactsView = new ContactsContentView(context);
                    contactsView.setOnCountChanged(this::updateContactsCount);
                }
                content = contactsView.getRoot();
                contactsView.reload();
                updateContactsCount(contactsView.getCount());
            }
            case SETTINGS -> {
                if (settingsView == null) {
                    settingsView = new SettingsContentView(context);
                }
                content = settingsView.getRoot();
                // Обновляем динамические значения (размер, кол-во пиров) —
                // могли измениться, пока пользователь был на других вкладках.
                settingsView.reload();
            }
            default -> content = simplePlaceholder();
        }
        contentArea.getChildren().setAll(content);
    }

    private void updateMyFilesCount(int count) {
        sidebar.setCount(Section.MY_FILES, count > 0 ? count : null);
    }

    private void updateSharedFilesCount(int count) {
        sidebar.setCount(Section.SHARED, count > 0 ? count : null);
    }

    private void updateContactsCount(int count) {
        sidebar.setCount(Section.CONTACTS, count > 0 ? count : null);
    }

    // ------------------------------------------------------------------
    // Isolation banner (день 15)
    // ------------------------------------------------------------------

    /**
     * Тонкая «полоска» сверху над контентом: предупреждение, что узел
     * один в сети, и подсказка перейти в Настройки → Seed-узлы.
     */
    private HBox buildIsolationBanner() {
        Label icon = new Label("i");
        icon.setStyle(
                "-fx-min-width: 16; -fx-min-height: 16;"
                        + "-fx-pref-width: 16; -fx-pref-height: 16;"
                        + "-fx-max-width: 16; -fx-max-height: 16;"
                        + "-fx-background-color: -color-accent;"
                        + "-fx-text-fill: white;"
                        + "-fx-background-radius: 8;"
                        + "-fx-alignment: center;"
                        + "-fx-font-size: 11px; -fx-font-weight: 700;"
        );

        Label text = new Label(
                "Узел один в сети. Чтобы подключиться к другим узлам, "
                        + "добавьте seed-адреса в Настройках.");
        text.getStyleClass().add("info-banner-text");
        text.setWrapText(true);
        HBox.setHgrow(text, Priority.ALWAYS);

        Button openSettings = new Button("Открыть Настройки");
        openSettings.getStyleClass().add("btn-copy");
        openSettings.setOnAction(e -> {
            sidebar.setActiveSection(Section.SETTINGS);
            showSection(Section.SETTINGS);
        });

        Button dismiss = new Button("✕");
        dismiss.getStyleClass().add("btn-copy");
        dismiss.setOnAction(e -> {
            isolationBannerDismissed = true;
            isolationBanner.setVisible(false);
            isolationBanner.setManaged(false);
        });

        HBox row = new HBox(10, icon, text, openSettings, dismiss);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10, 28, 10, 28));
        row.getStyleClass().add("info-banner");
        // Чтобы баннер был именно тонкой полоской, не «карточкой» —
        // занулим радиус и отступ внутри. info-banner-стиль базовый
        // даёт скругления; в Dashboard'е они смотрятся неуместно.
        row.setStyle("-fx-background-radius: 0; -fx-border-radius: 0;");
        HBox.setMargin(icon, new Insets(2, 0, 0, 0));
        return row;
    }

    /** Решает, показать ли баннер «нет подключений» прямо сейчас. */
    private void refreshIsolationBanner() {
        if (isolationBanner == null) return;
        if (isolationBannerDismissed) {
            // Пользователь уже закрыл — не возвращаем в этой сессии.
            return;
        }
        boolean isolated = isCurrentlyIsolated();
        isolationBanner.setVisible(isolated);
        isolationBanner.setManaged(isolated);
    }

    /**
     * «Изолирован» — это когда seed-узлов в config'е нет (некуда идти
     * на bootstrap) ИЛИ просто нет активных сессий И нет известных
     * пиров. Любого из этих условий достаточно: первое — про настройку,
     * второе — про сетевую реальность.
     */
    private boolean isCurrentlyIsolated() {
        var node = context.node();
        if (node == null) return false; // дашборд без узла невозможен, но всё же
        boolean hasSession = node.peerManager().snapshotSessions().size() > 0;
        if (hasSession) return false;
        boolean hasKnown = node.knownPeers().size() > 0;
        if (hasKnown) return false;
        // Сейчас никого не знаем — это и есть «нет подключений».
        // Дополнительно проверяем seeds, потому что если seeds есть,
        // bootstrap ещё может сработать в течение нескольких секунд,
        // и мы не хотим мигнуть баннером и тут же его убрать.
        var cfg = context.configStore();
        if (cfg != null && !cfg.getSeeds().isEmpty()) {
            // Пользователь уже знает про seeds — он их сам настроил —
            // bootstrap скоро отработает. Скрываем баннер на время.
            return false;
        }
        return true;
    }

    private void startIsolationPoll() {
        // 3 секунды — достаточно редко, чтобы не нагружать, и достаточно
        // быстро, чтобы пользователь увидел изменение состояния.
        isolationPoll = new Timeline(new KeyFrame(
                Duration.seconds(3), e -> refreshIsolationBanner()));
        isolationPoll.setCycleCount(Timeline.INDEFINITE);
        isolationPoll.play();
    }

    private void stopIsolationPoll() {
        if (isolationPoll != null) {
            isolationPoll.stop();
            isolationPoll = null;
        }
    }

    /** Запасной placeholder — на случай, если кто-то добавит в enum новый раздел. */
    private Parent simplePlaceholder() {
        Label h1 = new Label("Раздел");
        h1.getStyleClass().add("section-title");

        VBox box = new VBox(16, h1);
        box.setAlignment(Pos.CENTER);

        StackPane wrap = new StackPane(box);
        wrap.setPadding(new Insets(40));
        wrap.getStyleClass().add("app-background");
        return wrap;
    }
}
