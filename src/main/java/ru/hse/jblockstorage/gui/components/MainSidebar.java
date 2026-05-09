package ru.hse.jblockstorage.gui.components;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;
import ru.hse.jblockstorage.app.NodeApplication;
import ru.hse.jblockstorage.gui.upload.UploadProgressState;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Sidebar главного приложения (после Login).
 *
 * <p>Структура (по мокапам {@code dashboard_*} и {@code upload_progress.html}):
 * <ol>
 *   <li><b>Brand</b> — лого 28×28 + «JBlockStorage» + имя профиля</li>
 *   <li><b>Nav-список</b> — 4 пункта (Мои файлы / Расшаренные мне /
 *       Контакты / Настройки), каждый со SVG-иконкой и опциональным
 *       счётчиком справа. Выбранный — синий с подсветкой фона.</li>
 *   <li><b>Upload card</b> (только когда идёт загрузка) — компактный
 *       блок с прогресс-баром и процентом. Виден когда
 *       {@link UploadProgressState#isVisible()} true.</li>
 *   <li><b>Network card</b> — статус сети с зелёной точкой и счётчиком
 *       подключений. Обновляется по таймеру каждые 2 секунды.</li>
 *   <li><b>Footer</b> — версия и шифр (моноширинный)</li>
 * </ol>
 *
 * <p>Один экземпляр живёт всю сессию; активный раздел переключается
 * через {@link #setActiveSection}. Колбек {@link #onSectionChanged}
 * срабатывает при клике пользователя на пункт меню.
 */
public final class MainSidebar {

    /** Идентификаторы разделов навигации. */
    public enum Section {
        MY_FILES("my-files",  "Мои файлы"),
        SHARED("shared",      "Расшаренные мне"),
        CONTACTS("contacts",  "Контакты"),
        SETTINGS("settings",  "Настройки");

        public final String id;
        public final String title;
        Section(String id, String title) {
            this.id = id;
            this.title = title;
        }
    }

    private final NodeApplication node;
    private final VBox root;

    /** Карта раздел → строка-пункт меню (для подсветки активного). */
    private final Map<Section, NavRow> rows = new LinkedHashMap<>();

    /** Активный раздел. Изначально {@link Section#MY_FILES}. */
    private final ObjectProperty<Section> activeSection =
            new SimpleObjectProperty<>(this, "activeSection", Section.MY_FILES);

    /** Внешний колбек: пользователь кликнул пункт. */
    private Consumer<Section> onSectionChanged;

    /** Лейблы статуса сети — обновляются по таймеру. */
    private final Label networkPeersLabel = new Label();
    private Timeline poll;

    /**
     * Конструктор для совместимости — без upload state. В этом режиме
     * sidebar не показывает блок «Идёт загрузка». Используется в
     * тестах и в случае, когда AppContext ещё не передан.
     */
    public MainSidebar(NodeApplication node, String profileName) {
        this(node, profileName, null);
    }

    /**
     * @param node        активный узел
     * @param profileName отображаемое имя профиля (видно под брендом)
     * @param uploadState observable состояние загрузки. Если {@code null},
     *                    блок прогресса не показывается вообще.
     */
    public MainSidebar(NodeApplication node, String profileName, UploadProgressState uploadState) {
        this.node = Objects.requireNonNull(node, "node");
        this.root = new VBox();
        this.root.getStyleClass().add("sidebar");

        Node brand = buildBrand(profileName);
        Node nav = buildNav();

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        Node uploadCard = uploadState != null
                ? new SidebarUploadCard(uploadState).getRoot()
                : null;
        Node networkCard = buildNetworkCard();
        Node footer = buildFooter();

        if (uploadCard != null) {
            root.getChildren().addAll(brand, nav, spacer, uploadCard, networkCard, footer);
        } else {
            root.getChildren().addAll(brand, nav, spacer, networkCard, footer);
        }
        root.setSpacing(20);

        // Bottom-block (upload + network + footer) — в мокапе нижние
        // элементы стоят плотнее, чем основной spacing 20px.
        // Ставим уменьшенные margin'ы между ними.
        if (uploadCard instanceof Region r) {
            VBox.setMargin(r, new javafx.geometry.Insets(0, 0, 0, 0));
        }

        // Изначальная подсветка
        applyActive(activeSection.get());
        startMetricsPoll();
    }

    public VBox getRoot() {
        return root;
    }

    /** Программно переключить раздел (без вызова колбека). */
    public void setActiveSection(Section s) {
        if (Objects.equals(activeSection.get(), s)) return;
        activeSection.set(s);
        applyActive(s);
    }

    public Section getActiveSection() {
        return activeSection.get();
    }

    /** Колбек: пользователь кликнул пункт меню. */
    public void setOnSectionChanged(Consumer<Section> callback) {
        this.onSectionChanged = callback;
    }

    /**
     * Должен быть вызван при закрытии Dashboard, чтобы остановить
     * фоновый Timeline опроса метрик. Иначе утечёт.
     */
    public void shutdown() {
        if (poll != null) poll.stop();
    }

    // ------------------------------------------------------------------
    // Brand
    // ------------------------------------------------------------------

    private Node buildBrand(String profileName) {
        Region logo = new Region();
        logo.getStyleClass().add("brand-logo");
        logo.setPrefSize(28, 28);
        logo.setMinSize(28, 28);
        logo.setMaxSize(28, 28);

        Label productName = new Label("JBlockStorage");
        productName.getStyleClass().add("sidebar-profile-name");

        Label profile = new Label(profileName);
        profile.getStyleClass().add("sidebar-profile-handle");

        VBox titles = new VBox(0, productName, profile);

        HBox row = new HBox(10, logo, titles);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    // ------------------------------------------------------------------
    // Nav list
    // ------------------------------------------------------------------

    private Node buildNav() {
        VBox box = new VBox(1);
        for (Section s : Section.values()) {
            NavRow row = new NavRow(s, iconPathFor(s));
            row.node.setOnMouseClicked(e -> {
                if (Objects.equals(activeSection.get(), s)) return;
                activeSection.set(s);
                applyActive(s);
                if (onSectionChanged != null) onSectionChanged.accept(s);
            });
            rows.put(s, row);
            box.getChildren().add(row.node);
        }
        return box;
    }

    private void applyActive(Section active) {
        for (Map.Entry<Section, NavRow> e : rows.entrySet()) {
            boolean isActive = e.getKey() == active;
            e.getValue().setActive(isActive);
        }
    }

    /**
     * SVG-path иконок взят из мокапов (viewBox 16×16). 1.3px stroke,
     * round join — соответствует базе мокапов.
     */
    private static String iconPathFor(Section s) {
        switch (s) {
            case MY_FILES:
                return "M3 5.5C3 4.67 3.67 4 4.5 4H6.5L8 5.5H11.5C12.33 5.5 13 6.17 13 7V11C13 11.83 12.33 12.5 11.5 12.5H4.5C3.67 12.5 3 11.83 3 11V5.5Z";
            case SHARED:
                return "M2 5.5L8 8.5L14 5.5L8 2.5L2 5.5Z M2 8.5L8 11.5L14 8.5 M2 11.5L8 14.5L14 11.5";
            case CONTACTS:
                return "M8 3.5C9.38 3.5 10.5 4.62 10.5 6C10.5 7.38 9.38 8.5 8 8.5C6.62 8.5 5.5 7.38 5.5 6C5.5 4.62 6.62 3.5 8 3.5Z M3.5 13C3.5 10.5 5.5 9 8 9C10.5 9 12.5 10.5 12.5 13";
            case SETTINGS:
            default:
                return "M8 6C9.10 6 10 6.90 10 8C10 9.10 9.10 10 8 10C6.90 10 6 9.10 6 8C6 6.90 6.90 6 8 6Z M8 1V3 M8 13V15 M15 8H13 M3 8H1 M12.5 3.5L11 5 M5 11L3.5 12.5 M12.5 12.5L11 11 M5 5L3.5 3.5";
        }
    }

    /** Группа: Node + методы для подсветки активности. */
    private static final class NavRow {
        final Section section;
        final HBox node;
        final SVGPath icon;
        final Label label;
        final Label countBadge;

        NavRow(Section section, String iconPathSvg) {
            this.section = section;

            this.icon = new SVGPath();
            this.icon.setContent(iconPathSvg);
            this.icon.getStyleClass().add("nav-item-icon");

            this.label = new Label(section.title);
            this.label.getStyleClass().add("nav-item-label");

            this.countBadge = new Label();
            this.countBadge.getStyleClass().add("nav-item-count");
            this.countBadge.setVisible(false);
            this.countBadge.setManaged(false);

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            StackPane iconWrap = new StackPane(icon);
            iconWrap.setMinSize(16, 16);
            iconWrap.setPrefSize(16, 16);

            this.node = new HBox(10, iconWrap, label, spacer, countBadge);
            this.node.setAlignment(Pos.CENTER_LEFT);
            this.node.getStyleClass().add("nav-item");
        }

        void setActive(boolean active) {
            if (active) {
                if (!node.getStyleClass().contains("nav-item--active"))
                    node.getStyleClass().add("nav-item--active");
                if (!label.getStyleClass().contains("nav-item-label--active"))
                    label.getStyleClass().add("nav-item-label--active");
                if (!icon.getStyleClass().contains("nav-item-icon--active"))
                    icon.getStyleClass().add("nav-item-icon--active");
                if (!countBadge.getStyleClass().contains("nav-item-count--active"))
                    countBadge.getStyleClass().add("nav-item-count--active");
            } else {
                node.getStyleClass().removeAll("nav-item--active");
                label.getStyleClass().removeAll("nav-item-label--active");
                icon.getStyleClass().removeAll("nav-item-icon--active");
                countBadge.getStyleClass().removeAll("nav-item-count--active");
            }
        }

        void setCount(Integer n) {
            if (n == null || n <= 0) {
                countBadge.setVisible(false);
                countBadge.setManaged(false);
            } else {
                countBadge.setText(Integer.toString(n));
                countBadge.setVisible(true);
                countBadge.setManaged(true);
            }
        }
    }

    /** Установить счётчик возле пункта меню (например, число файлов). */
    public void setCount(Section s, Integer count) {
        NavRow r = rows.get(s);
        if (r != null) r.setCount(count);
    }

    // ------------------------------------------------------------------
    // Network card
    // ------------------------------------------------------------------

    private Node buildNetworkCard() {
        Region dot = new Region();
        dot.getStyleClass().addAll("status-dot", "status-success");
        dot.setPrefSize(7, 7);
        dot.setMinSize(7, 7);

        Label title = new Label("Сеть стабильна");
        title.getStyleClass().add("network-card-title");

        HBox titleRow = new HBox(7, dot, title);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        networkPeersLabel.getStyleClass().add("network-card-meta");
        updatePeers();

        VBox card = new VBox(4, titleRow, networkPeersLabel);
        card.getStyleClass().add("network-card");
        return card;
    }

    private void updatePeers() {
        if (node == null) {
            networkPeersLabel.setText("0 подключений");
            return;
        }
        int active = node.peerManager().snapshotSessions().size();
        int known = node.knownPeers().size();
        // Высота блокчейна — выраженное требование ТЗ п. 4.1.5.2:
        // «количество подключенных пиров, высота блокчейна» в Dashboard.
        int height = -1;
        try {
            height = node.blockchain().height();
        } catch (Exception e) {
            // На раннем старте blockchain мог ещё не быть готов — игнор.
        }
        StringBuilder sb = new StringBuilder();
        if (active == 0 && known == 0) {
            sb.append("Нет подключений");
        } else {
            sb.append(active).append(" подключений · известных ").append(known);
        }
        if (height >= 0) {
            sb.append(" · блок #").append(height);
        }
        networkPeersLabel.setText(sb.toString());
    }

    private void startMetricsPoll() {
        poll = new Timeline(new KeyFrame(Duration.seconds(2), e -> updatePeers()));
        poll.setCycleCount(Timeline.INDEFINITE);
        poll.play();
    }

    // ------------------------------------------------------------------
    // Footer
    // ------------------------------------------------------------------

    private Node buildFooter() {
        Label name = new Label("Нурмагомедов Р.Р.");
        name.getStyleClass().add("sidebar-footer-text");

        Label group = new Label("БПИ-246");
        group.getStyleClass().add("sidebar-footer-text");

        return new VBox(2, name, group);
    }
}
