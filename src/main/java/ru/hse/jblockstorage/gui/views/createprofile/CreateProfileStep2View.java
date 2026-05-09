package ru.hse.jblockstorage.gui.views.createprofile;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.hse.jblockstorage.gui.AppContext;
import ru.hse.jblockstorage.gui.components.OnboardingSidebar;

import java.util.Objects;

/**
 * Шаг 2: отображение мнемоники.
 *
 * <p>Дизайн (по итоговым мокапам):
 * <ul>
 *   <li>Sidebar — нумерованные шаги с ✓ для шага 1, активный шаг 2</li>
 *   <li>Колонка 460px: H1, описание, единая карточка с сеткой 3×4 слов
 *       (легкий фон ячейки, без рамки), жёлтое предупреждение, две
 *       кнопки внизу — «Скопировать» и «Продолжить» в соотношении 1 : 1.6</li>
 * </ul>
 */
public final class CreateProfileStep2View {

    private static final Logger log = LoggerFactory.getLogger(CreateProfileStep2View.class);

    private static final double COLUMN_WIDTH = 460;

    private final AppContext context;
    private final ProfileCreationState state;
    private final Parent root;

    public CreateProfileStep2View(AppContext context, ProfileCreationState state) {
        this.context = Objects.requireNonNull(context, "context");
        this.state = Objects.requireNonNull(state, "state");
        this.root = build();
    }

    public Parent getRoot() {
        return root;
    }

    private Parent build() {
        OnboardingSidebar sidebar = new OnboardingSidebar();
        sidebar.setCenter(OnboardingSidebar.buildStepsIndicatorNumbered(2));

        Label h1 = new Label("Запишите эти 12 слов");
        h1.getStyleClass().add("h1");

        Label subtitle = new Label("Это единственный способ восстановить профиль, "
                + "если вы забудете пароль или потеряете устройство.");
        subtitle.getStyleClass().add("welcome-subtitle");
        subtitle.setWrapText(true);

        GridPane grid = buildMnemonicGrid();

        Label warning = new Label("Не показывайте фразу никому и не храните в сети. "
                + "Запишите на бумаге.");
        warning.getStyleClass().add("warning-card");
        warning.setWrapText(true);
        warning.setMaxWidth(Double.MAX_VALUE);

        Button copyBtn = new Button("Скопировать");
        copyBtn.getStyleClass().addAll("btn-secondary", "btn-full");
        copyBtn.setOnAction(e -> onCopy(copyBtn));
        copyBtn.setMaxWidth(Double.MAX_VALUE);
        copyBtn.setPrefWidth(1);
        HBox.setHgrow(copyBtn, Priority.ALWAYS);

        Button continueBtn = new Button("Продолжить");
        continueBtn.getStyleClass().addAll("btn-primary", "btn-full");
        continueBtn.setDefaultButton(true);
        continueBtn.setOnAction(e -> onContinue());
        continueBtn.setMaxWidth(Double.MAX_VALUE);
        continueBtn.setPrefWidth(1.6);
        HBox.setHgrow(continueBtn, Priority.ALWAYS);

        HBox actions = new HBox(8, copyBtn, continueBtn);
        actions.setAlignment(Pos.CENTER);

        // Кнопка «Отмена» как ghost-link под основными — на случай выхода
        Button cancelBtn = new Button("Отмена");
        cancelBtn.getStyleClass().add("btn-ghost");
        cancelBtn.setOnAction(e -> onCancel());

        VBox stack = new VBox(0, h1, subtitle, grid, warning, actions, cancelBtn);
        VBox.setMargin(subtitle, new Insets(8,  0, 0, 0));
        VBox.setMargin(grid,     new Insets(20, 0, 0, 0));
        VBox.setMargin(warning,  new Insets(16, 0, 0, 0));
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

    /**
     * Сетка мнемоники 3 колонки × 4 ряда (как в мокапе): слова идут по строкам.
     */
    private GridPane buildMnemonicGrid() {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("mnemonic-grid-flat");

        // Каждая колонка должна занимать треть ширины
        for (int i = 0; i < 3; i++) {
            ColumnConstraints col = new ColumnConstraints();
            col.setHgrow(Priority.ALWAYS);
            col.setPercentWidth(33.33);
            grid.getColumnConstraints().add(col);
        }

        String[] words = state.mnemonicWords();
        // 12 слов, раскладка по строкам слева направо: 1 2 3 / 4 5 6 / 7 8 9 / 10 11 12
        for (int i = 0; i < 12; i++) {
            int row = i / 3;
            int col = i % 3;

            Label num = new Label(String.format("%2d", i + 1));
            num.getStyleClass().add("mnemonic-num-flat");

            Label word = new Label(words[i]);
            word.getStyleClass().add("mnemonic-word-flat");

            HBox cell = new HBox(num, word);
            cell.setAlignment(Pos.CENTER_LEFT);
            cell.getStyleClass().add("mnemonic-cell-flat");
            HBox.setHgrow(word, Priority.ALWAYS);

            grid.add(cell, col, row);
        }
        return grid;
    }

    // ------------------------------------------------------------------
    // Действия
    // ------------------------------------------------------------------

    private void onCopy(Button btn) {
        ClipboardContent content = new ClipboardContent();
        content.putString(state.mnemonic());
        Clipboard.getSystemClipboard().setContent(content);
        log.info("Мнемоника скопирована в буфер");
        String original = btn.getText();
        btn.setText("Скопировано");
        btn.setDisable(true);
        new Thread(() -> {
            try { Thread.sleep(1500); } catch (InterruptedException ignored) { return; }
            Platform.runLater(() -> {
                btn.setText(original);
                btn.setDisable(false);
            });
        }, "copy-feedback").start();
    }

    private void onContinue() {
        CreateProfileStep3View step3 = new CreateProfileStep3View(context, state);
        context.router().show(step3.getRoot(), "JBlockStorage — Проверка фразы");
    }

    private void onCancel() {
        state.clearPassword();
        ru.hse.jblockstorage.gui.views.WelcomeView welcome =
                new ru.hse.jblockstorage.gui.views.WelcomeView(context);
        context.router().show(welcome.getRoot(), "JBlockStorage — Добро пожаловать");
    }
}
