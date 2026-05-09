package ru.hse.jblockstorage.gui.components;

import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.text.TextAlignment;

/**
 * Sidebar для onboarding-экранов (Welcome / Login / 3 шага создания профиля).
 *
 * <p>Структура (сверху вниз):
 * <ol>
 *   <li><b>Brand</b> — лого 28×28 + «JBlockStorage» + версия v1.0.0</li>
 *   <li><b>Center</b> — настраивается:
 *     <ul>
 *       <li>{@link #buildNetworkAndDiagram()} — для Welcome/Login: статус-карточка
 *           «Соединение установлено» + SVG-схема узлов сети</li>
 *       <li>{@link #buildStepsIndicatorNumbered(int)} — для CreateProfile:
 *           нумерованные шаги с галочками для пройденных</li>
 *     </ul>
 *   </li>
 *   <li><b>Footer</b> — RU.17701729.10.05-01 / БПИ246 · ФКН ВШЭ</li>
 * </ol>
 *
 * <p>Ширина зафиксирована в CSS (240px). Полупрозрачный фон
 * ({@code -color-sidebar-bg}) поверх градиента {@code .onboarding-root}
 * даёт характерный mac-native вид.
 */
public final class OnboardingSidebar {

    private final VBox root;
    private final VBox centerSlot;

    public OnboardingSidebar() {
        this.root = new VBox();
        this.root.getStyleClass().add("sidebar");

        Node brand = buildBrand();
        this.centerSlot = new VBox();
        this.centerSlot.setSpacing(8);

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        Node footer = buildFooter();

        root.getChildren().addAll(brand, centerSlot, spacer, footer);
    }

    public VBox getRoot() {
        return root;
    }

    public void setCenter(Node node) {
        centerSlot.getChildren().setAll(node);
    }

    // ------------------------------------------------------------------
    // Brand: лого 28×28 + название + версия
    // ------------------------------------------------------------------

    private Node buildBrand() {
        Region logo = new Region();
        logo.getStyleClass().add("brand-logo");
        logo.setPrefSize(28, 28);
        logo.setMinSize(28, 28);
        logo.setMaxSize(28, 28);

        Label name = new Label("JBlockStorage");
        name.getStyleClass().add("sidebar-brand-name");

        Label version = new Label("v1.0.0");
        version.getStyleClass().add("text-secondary");

        VBox titles = new VBox(0, name, version);

        HBox row = new HBox(10, logo, titles);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    // ------------------------------------------------------------------
    // Footer: RU-идентификатор и шифр группы (моноширинный)
    // ------------------------------------------------------------------

    private Node buildFooter() {
        Label name = new Label("Нурмагомедов Р.Р.");
        name.getStyleClass().add("sidebar-footer-text");

        Label group = new Label("БПИ-246");
        group.getStyleClass().add("sidebar-footer-text");

        return new VBox(2, name, group);
    }

    // ==================================================================
    // Готовые блоки для центральной зоны
    // ==================================================================

    /**
     * Для Welcome/Login: блок «СЕТЬ» со статусом + блок «КАК ЭТО РАБОТАЕТ»
     * с SVG-схемой узлов и подписью.
     */
    public static Node buildNetworkAndDiagram() {
        return new VBox(20, buildNetworkStatus(), buildHowItWorks());
    }

    private static Node buildNetworkStatus() {
        Label sectionLabel = new Label("СЕТЬ");
        sectionLabel.getStyleClass().add("steps-section-label");

        Region dot = new Region();
        dot.getStyleClass().addAll("status-dot", "status-success");
        dot.setPrefSize(7, 7);
        dot.setMinSize(7, 7);

        Label text = new Label("Соединение установлено");
        text.getStyleClass().add("body");

        HBox row = new HBox(7, dot, text);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("sidebar-card");

        return new VBox(8, sectionLabel, row);
    }

    private static Node buildHowItWorks() {
        Label sectionLabel = new Label("КАК ЭТО РАБОТАЕТ");
        sectionLabel.getStyleClass().add("steps-section-label");

        Node diagram = buildNodesDiagram();

        Label caption = new Label("Файлы хранятся\nу других участников.");
        caption.getStyleClass().add("text-secondary");
        caption.setTextAlignment(TextAlignment.CENTER);
        caption.setMaxWidth(Double.MAX_VALUE);
        caption.setAlignment(Pos.CENTER);

        return new VBox(8, sectionLabel, diagram, caption);
    }

    /**
     * Схема узлов: 1 синий в центре, 5 серых вокруг, пунктир-линии.
     * Координаты соответствуют viewBox 200×130 из мокапа.
     * Используем нативные JavaFX-shapes — без встраивания SVG.
     */
    private static Node buildNodesDiagram() {
        double[][] peers = {
                {40, 30}, {160, 30}, {30, 100}, {170, 100}, {100, 115}
        };
        double cx = 100, cy = 65;

        Group g = new Group();

        // Сначала линии — чтобы они были под кругами.
        for (double[] p : peers) {
            Line line = new Line(cx, cy, p[0], p[1]);
            line.setStroke(Color.web("#c7c7cc"));
            line.setStrokeWidth(0.6);
            line.getStrokeDashArray().addAll(2.0, 2.0);
            g.getChildren().add(line);
        }
        // Дополнительные связи между периферийными узлами
        Line linkA = new Line(40, 30, 30, 100);
        linkA.setStroke(Color.web("#e1e1e6"));
        linkA.setStrokeWidth(0.6);
        linkA.getStrokeDashArray().addAll(2.0, 2.0);
        Line linkB = new Line(160, 30, 170, 100);
        linkB.setStroke(Color.web("#e1e1e6"));
        linkB.setStrokeWidth(0.6);
        linkB.getStrokeDashArray().addAll(2.0, 2.0);
        g.getChildren().addAll(linkA, linkB);

        // Серые периферийные узлы
        for (double[] p : peers) {
            Circle c = new Circle(p[0], p[1], 5);
            c.setFill(Color.web("#8e8e93"));
            g.getChildren().add(c);
        }

        // Центральный синий узел с белой обводкой
        Circle center = new Circle(cx, cy, 9);
        center.setFill(Color.web("#007aff"));
        center.setStroke(Color.WHITE);
        center.setStrokeWidth(2);
        g.getChildren().add(center);

        StackPane wrap = new StackPane(g);
        wrap.setPrefHeight(130);
        wrap.setMinHeight(130);
        wrap.setMaxHeight(130);
        wrap.setAlignment(Pos.CENTER);
        return wrap;
    }

    // ==================================================================
    // Numbered steps indicator (1/2/3 + ✓ для пройденных)
    // ==================================================================

    /**
     * Индикатор шагов для процесса создания профиля. Пройденные показываются
     * с зелёной галочкой ✓, активный — синим кружком, будущие — серым.
     *
     * @param current номер текущего шага (1..3)
     */
    public static Node buildStepsIndicatorNumbered(int current) {
        Label label = new Label("ШАГИ");
        label.getStyleClass().add("steps-section-label");

        VBox list = new VBox(2,
                stepRow(1, "Параметры", current),
                stepRow(2, "Фраза восстановления", current),
                stepRow(3, "Подтверждение", current)
        );

        return new VBox(12, label, list);
    }

    private static Node stepRow(int step, String title, int current) {
        boolean done = step < current;
        boolean active = step == current;

        Label circle = new Label(done ? "✓" : Integer.toString(step));
        circle.getStyleClass().add("step-circle");
        if (done) {
            circle.getStyleClass().add("step-circle--done");
        } else if (active) {
            circle.getStyleClass().add("step-circle--active");
        } else {
            circle.getStyleClass().add("step-circle--pending");
        }
        circle.setAlignment(Pos.CENTER);

        Label titleLbl = new Label(title);
        titleLbl.getStyleClass().add("step-row-title");
        titleLbl.getStyleClass().add(active ? "step-row-title--active" : "step-row-title--inactive");

        HBox row = new HBox(10, circle, titleLbl);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("step-row");
        if (active) {
            row.getStyleClass().add("step-row--active");
        }
        return row;
    }
}
