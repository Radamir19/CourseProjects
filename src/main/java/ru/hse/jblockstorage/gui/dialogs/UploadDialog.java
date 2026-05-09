package ru.hse.jblockstorage.gui.dialogs;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.hse.jblockstorage.app.FileUploader;
import ru.hse.jblockstorage.app.NodeApplication;
import ru.hse.jblockstorage.gui.AppContext;
import ru.hse.jblockstorage.gui.components.FileTypeBadge;
import ru.hse.jblockstorage.network.PeerSession;
import ru.hse.jblockstorage.storage.FileChunker;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Модальный диалог загрузки файла.
 *
 * <p>Состоит из секций (по мокапу {@code upload_dialog.html}):
 * <ol>
 *   <li><b>Заголовок</b> — «Загрузить файл» + подзаголовок</li>
 *   <li><b>Drop-zone</b> — большая прерывистая рамка цвета акцента,
 *       реагирует на drag-over подсветкой. Внутри — текст «Перетащите
 *       файл сюда» + ссылка «выберите на диске» (открывает FileChooser).</li>
 *   <li><b>Превью выбранного файла</b> (если файл выбран) — типизированный
 *       бейдж + имя + размер + кнопка ×</li>
 *   <li><b>Сводка</b> — сколько шардов будет создано (вычисляется по
 *       реальному размеру файла и {@link FileChunker#DEFAULT_CHUNK_SIZE}),
 *       сколько реплик из конфига, какой шифр</li>
 *   <li><b>Предупреждение</b>, если активных пиров недостаточно для
 *       заданной replication factor — кнопка «Загрузить» блокируется,
 *       чтобы пользователь не получил «Недостаточно хранителей» от бэка</li>
 *   <li><b>Кнопки</b> «Отмена» (закрывает оверлей) и «Загрузить» —
 *       делегирует в {@link Consumer onUploadStart} с выбранным файлом</li>
 * </ol>
 *
 * <p>Сам upload не делает — это задача {@link UploadProgressDialog}.
 * Этот класс только собирает выбор пользователя.
 */
public final class UploadDialog {

    private static final Logger log = LoggerFactory.getLogger(UploadDialog.class);

    /** Ширина модалки (по мокапу 460px). */
    private static final double DIALOG_WIDTH = 460;

    private final AppContext context;
    private final Consumer<Path> onUploadStart;

    private final Parent root;
    private VBox dropZone;
    private Label dropZoneTitle;
    private Label dropZoneHint;
    private VBox previewSlot;
    private VBox summarySlot;
    private VBox warnSlot;
    private Button uploadBtn;

    private Path selected;

    /**
     * @param context        контекст с overlay/router/node
     * @param onUploadStart  колбек, вызываемый при клике «Загрузить» с
     *                       выбранным файлом. Диалог сам не вызывает
     *                       {@code uploadFile} — это делает следующий шаг.
     */
    public UploadDialog(AppContext context, Consumer<Path> onUploadStart) {
        this.context = Objects.requireNonNull(context, "context");
        this.onUploadStart = Objects.requireNonNull(onUploadStart, "onUploadStart");
        this.root = build();
        refresh();
    }

    public Parent getRoot() {
        return root;
    }

    private Parent build() {
        // Заголовок
        Label title = new Label("Загрузить файл");
        title.getStyleClass().add("modal-title");

        Label subtitle = new Label("Файл будет зашифрован на устройстве "
                + "и распределён по сети.");
        subtitle.getStyleClass().add("modal-subtitle");
        subtitle.setWrapText(true);

        VBox header = new VBox(6, title, subtitle);
        header.setPadding(new Insets(20, 20, 12, 20));

        // Drop-zone
        dropZone = buildDropZone();
        VBox dropWrap = new VBox(dropZone);
        dropWrap.setPadding(new Insets(0, 20, 14, 20));

        // Слоты для превью / сводки / предупреждения — заполняются в refresh()
        previewSlot = new VBox();
        previewSlot.setPadding(new Insets(0, 20, 0, 20));

        summarySlot = new VBox();
        summarySlot.setPadding(new Insets(10, 20, 0, 20));

        warnSlot = new VBox();
        warnSlot.setPadding(new Insets(10, 20, 0, 20));

        // Контент карточки — собирается в один VBox без footer'а.
        // Footer хранится отдельно (см. поле footerNode), чтобы при показе
        // в окне можно было обернуть body в ScrollPane, а footer оставить
        // прижатым к низу — стандартный UX для модалок.
        VBox bodyContent = new VBox(header, dropWrap, previewSlot, summarySlot, warnSlot);
        bodyContent.setFillWidth(true);

        // Footer-кнопки на отдельной полосе
        Region divider = new Region();
        divider.getStyleClass().add("modal-section-divider");

        Button cancel = new Button("Отмена");
        cancel.getStyleClass().add("btn-secondary");
        cancel.setOnAction(e -> dismissWindow());

        uploadBtn = new Button("Загрузить");
        uploadBtn.getStyleClass().add("btn-primary");
        uploadBtn.setDefaultButton(true);
        uploadBtn.setOnAction(e -> {
            if (selected == null) return;
            onUploadStart.accept(selected);
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox buttonsRow = new HBox(8, spacer, cancel, uploadBtn);
        buttonsRow.setAlignment(Pos.CENTER_RIGHT);
        buttonsRow.setPadding(new Insets(14, 20, 16, 20));

        VBox footer = new VBox(divider, buttonsRow);
        this.footerNode = footer;
        this.bodyNode = bodyContent;

        VBox card = new VBox(bodyContent, footer);
        card.getStyleClass().add("modal-card");
        // Ширину карточки не фиксируем — окно растяжимое, и при resize
        // карточка должна расти вместе с окном. Минимальная ширина
        // ограничена на уровне Stage (см. showAsWindow).

        // Карточка — корень самой Scene нового окна (см. showAsWindow).
        // Дополнительный StackPane-wrap тут не нужен: Stage сам по себе
        // изолированный контейнер.
        return card;
    }

    /** Body карточки (всё, кроме footer'а) — в showAsWindow обертываем в ScrollPane. */
    private VBox bodyNode;
    /** Footer карточки (divider + кнопки) — всегда виден внизу окна. */
    private VBox footerNode;


    /**
     * Показывает диалог как отдельное нативное окно, по мокапу
     * {@code upload_dialog.html} (с заголовком macOS — тремя кружочками).
     *
     * <p>Нюансы:
     * <ul>
     *   <li>{@code Modality.APPLICATION_MODAL} — главное окно блокируется,
     *       пока диалог открыт</li>
     *   <li>На сцене подгружается тот же набор стилей, что и на главной
     *       сцене — иначе диалог будет выглядеть «голым» (default JavaFX
     *       caspian/modena стили)</li>
     *   <li>Окно закрывается либо кнопкой «Отмена» внутри диалога, либо
     *       при выборе файла (после колбека onUploadStart). Esc по умолчанию
     *       закрывает Stage с modality, но мы явно его обрабатываем,
     *       чтобы не было двусмысленности</li>
     * </ul>
     *
     * @param ownerStage главное окно приложения (для modality)
     */
    /** Минимальная ширина окна (ниже карточка раздавится). */
    private static final double MIN_WIDTH = 420;
    /** Минимальная высота окна (drop-zone + footer должны влезать). */
    private static final double MIN_HEIGHT = 360;
    /** Стартовая ширина — по мокапу. */
    private static final double INITIAL_WIDTH = 460;

    public void showAsWindow(javafx.stage.Stage ownerStage) {
        javafx.stage.Stage stage = new javafx.stage.Stage();
        stage.initOwner(ownerStage);
        stage.initModality(javafx.stage.Modality.APPLICATION_MODAL);
        // StageStyle.UNIFIED — на macOS даёт цельное окно: заголовок не
        // отделён серой полосой, а сливается с фоном Scene. На Windows
        // даёт обычный заголовок (UNIFIED там не поддерживается, но это
        // не критично — главная цель macOS native look).
        stage.initStyle(javafx.stage.StageStyle.UNIFIED);
        stage.setTitle("Загрузить файл");
        // Растягиваемое окно — пользователь может ужать или растянуть,
        // если хочется видеть больше деталей одновременно (превью + сводка
        // + warn-баннер на маленьком экране иногда требуют скролла).
        stage.setResizable(true);
        stage.setMinWidth(MIN_WIDTH);
        stage.setMinHeight(MIN_HEIGHT);

        // Переcобираем корневую ноду окна:
        //   ┌─ card ─────────────────┐
        //   │  ScrollPane(body)      │  ← скроллится, если контента слишком много
        //   │  ─────────────────     │
        //   │  footer (кнопки)       │  ← всегда виден, прижат к низу окна
        //   └────────────────────────┘
        // Это решает проблему «не докручивается до кнопок Отмена/Загрузить»
        // на маленьких экранах: даже если внутри body 10 баннеров —
        // кнопки никуда не уходят.
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(bodyNode);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.getStyleClass().add("modal-scroll");
        // VGrow внутри VBox — забирает всё доступное пространство, footer прижат к низу.
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox card = new VBox(scroll, footerNode);
        card.getStyleClass().add("modal-card");
        // Card растёт по ширине окна — никаких setMaxWidth/MinWidth здесь.
        // Внутренние секции (drop-zone, summary, banner) сами тянутся через
        // padding'и в build(). Это даёт ровный вид при любом resize.

        javafx.scene.Scene scene = new javafx.scene.Scene(card);
        // Подхватываем те же стили, что висят на главной сцене.
        if (ownerStage.getScene() != null) {
            scene.getStylesheets().setAll(ownerStage.getScene().getStylesheets());
            // Добавляем CSS-классы темы (light/dark) к корневой ноде нашей
            // сцены, не затирая собственные стили card'а (.modal-card).
            for (String cls : ownerStage.getScene().getRoot().getStyleClass()) {
                if (!scene.getRoot().getStyleClass().contains(cls)) {
                    scene.getRoot().getStyleClass().add(cls);
                }
            }
        }

        // Esc — закрыть.
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                stage.close();
            }
        });

        stage.setScene(scene);
        // Стартовая ширина — фиксированная по мокапу, но пользователь
        // может растянуть. Высоту подбираем под контент (см. ниже).
        stage.setWidth(INITIAL_WIDTH);
        autoFitInitialHeight(stage, scene);

        // Сохраняем ссылку, чтобы dismissWindow() из колбека закрывал именно это окно.
        this.window = stage;
        stage.showAndWait();
    }

    /**
     * Подбирает стартовую высоту окна под текущий контент. Окно
     * открывается высотой {@code min(natural-height, 80% screen-height)},
     * не меньше {@link #MIN_HEIGHT}. Дальше пользователь сам распоряжается
     * размером — мы не вмешиваемся.
     *
     * <p>Раньше тут был listener на изменение контента, который автоматом
     * подгонял высоту при появлении превью/сводки/баннера. Это давало
     * прыжки окна и спорило с ручным resize'ом — убрали. Внутри body
     * есть ScrollPane, так что даже если контент перерос окно — ничего
     * не пропадёт.
     */
    private static void autoFitInitialHeight(javafx.stage.Stage stage, javafx.scene.Scene scene) {
        scene.getRoot().applyCss();
        scene.getRoot().layout();

        double naturalHeight = scene.getRoot().prefHeight(INITIAL_WIDTH);
        double maxByScreen = javafx.stage.Screen.getPrimary().getVisualBounds().getHeight() * 0.8;
        double finalHeight = Math.max(MIN_HEIGHT, Math.min(naturalHeight, maxByScreen));
        // +28 — припуск под заголовок macOS и небольшую тень.
        stage.setHeight(finalHeight + 28);
    }

    /** Программно закрыть окно (вызывается из onUploadStart после выбора файла). */
    public void dismissWindow() {
        if (window != null) {
            window.close();
            window = null;
        }
    }

    private javafx.stage.Stage window;

    // ------------------------------------------------------------------
    // Drop zone + DnD
    // ------------------------------------------------------------------

    private VBox buildDropZone() {
        // Иконка-стрелка вверх в кружке (32×32)
        Region iconBg = new Region();
        iconBg.setMinSize(32, 32);
        iconBg.setPrefSize(32, 32);
        iconBg.setMaxSize(32, 32);
        iconBg.setStyle(
                "-fx-background-color: -color-accent-bg-strong;"
                        + "-fx-background-radius: 16;"
        );
        Label arrow = new Label("↑");
        arrow.setStyle("-fx-font-size: 18px; -fx-text-fill: -color-accent;");
        StackPane icon = new StackPane(iconBg, arrow);
        icon.setMinSize(32, 32);
        icon.setMaxSize(32, 32);

        dropZoneTitle = new Label("Перетащите файл сюда");
        dropZoneTitle.getStyleClass().add("drop-zone-title");

        Hyperlink chooseLink = new Hyperlink("выберите на диске");
        chooseLink.getStyleClass().add("drop-zone-link");
        chooseLink.setOnAction(e -> openFileChooser());
        // Hyperlink в JavaFX по умолчанию рисует пунктирное focus-кольцо
        // вокруг текста при первом фокусе и подчёркивает текст при hover.
        // Оба поведения отличаются от мокапа — гасим.
        chooseLink.setFocusTraversable(false);
        chooseLink.setUnderline(false);

        dropZoneHint = new Label("или ");
        dropZoneHint.getStyleClass().add("drop-zone-hint");

        HBox hintRow = new HBox(0, dropZoneHint, chooseLink);
        hintRow.setAlignment(Pos.CENTER);

        VBox box = new VBox(8, icon, dropZoneTitle, hintRow);
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("drop-zone");

        // DnD: разрешаем COPY если перетаскивают файл
        box.setOnDragOver(this::onDragOver);
        box.setOnDragDropped(this::onDragDropped);
        box.setOnDragEntered(e -> {
            if (e.getDragboard().hasFiles()) {
                box.getStyleClass().add("drop-zone--hover");
            }
        });
        box.setOnDragExited(e -> box.getStyleClass().removeAll("drop-zone--hover"));
        // Клик по самому drop-zone тоже открывает FileChooser
        box.setOnMouseClicked(e -> openFileChooser());
        return box;
    }

    private void onDragOver(DragEvent e) {
        Dragboard db = e.getDragboard();
        if (db.hasFiles() && db.getFiles().size() == 1) {
            e.acceptTransferModes(TransferMode.COPY);
        }
        e.consume();
    }

    private void onDragDropped(DragEvent e) {
        Dragboard db = e.getDragboard();
        boolean ok = false;
        if (db.hasFiles() && db.getFiles().size() == 1) {
            File f = db.getFiles().get(0);
            if (f.isFile()) {
                selected = f.toPath();
                refresh();
                ok = true;
            }
        }
        e.setDropCompleted(ok);
        e.consume();
    }

    private void openFileChooser() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Выберите файл для загрузки");
        // Стартовая директория — Documents если есть
        File homeDocs = new File(System.getProperty("user.home"), "Documents");
        if (homeDocs.isDirectory()) chooser.setInitialDirectory(homeDocs);
        File f = chooser.showOpenDialog(context.router().stage());
        if (f != null) {
            selected = f.toPath();
            refresh();
        }
    }

    // ------------------------------------------------------------------
    // Refresh: пересчёт превью / сводки / предупреждения / состояния кнопки
    // ------------------------------------------------------------------

    private void refresh() {
        previewSlot.getChildren().clear();
        summarySlot.getChildren().clear();
        warnSlot.getChildren().clear();

        if (selected == null) {
            uploadBtn.setDisable(true);
            return;
        }

        // 1. Превью
        long size;
        try {
            size = Files.size(selected);
        } catch (Exception e) {
            log.warn("Не удалось прочитать размер файла {}: {}", selected, e.toString());
            uploadBtn.setDisable(true);
            warnSlot.getChildren().add(banner(
                    "Не удалось прочитать файл: " + e.getMessage(), true));
            return;
        }
        if (size == 0) {
            uploadBtn.setDisable(true);
            warnSlot.getChildren().add(banner(
                    "Пустые файлы не поддерживаются.", true));
            previewSlot.getChildren().add(buildPreview(size));
            return;
        }
        previewSlot.getChildren().add(buildPreview(size));

        // 2. Сводка
        int chunkSize = FileChunker.DEFAULT_CHUNK_SIZE;
        int shards = (int) ((size + chunkSize - 1) / chunkSize); // ceil
        int replication = FileUploader.DEFAULT_REPLICATION_FACTOR;

        summarySlot.getChildren().add(buildSummary(shards, chunkSize, replication));

        // 3. Проверка пиров.
        // С появлением self-storage (день 13 fix3) узел тоже считает себя
        // одним из хранителей — поэтому в счёт идут active + 1 (self).
        // При replication=3 достаточно 2 других активных узлов, что
        // соответствует требованию ТЗ «минимум 3 экземпляра приложения».
        NodeApplication node = context.node();
        int otherActive = countActivePeers(node);
        int totalActive = otherActive + 1; // +1 = self
        if (totalActive < replication) {
            uploadBtn.setDisable(true);
            int needMore = replication - totalActive;
            warnSlot.getChildren().add(banner(
                    "Сейчас в сети " + totalActive + " узлов (вы + "
                            + otherActive + " " + (otherActive == 1 ? "другой" : "других")
                            + "), а нужно ≥ " + replication + ". Запустите ещё "
                            + needMore + " " + (needMore == 1 ? "узел" : "узла")
                            + " — кнопка станет активной автоматически.", true));
        } else {
            uploadBtn.setDisable(false);
        }
    }

    private static int countActivePeers(NodeApplication node) {
        if (node == null) return 0;
        Map<String, PeerSession> sessions = node.peerManager().snapshotSessions();
        int n = 0;
        for (PeerSession s : sessions.values()) {
            if (s.isActive()) n++;
        }
        return n;
    }

    private HBox buildPreview(long size) {
        StackPane badge = FileTypeBadge.forFile(selected.getFileName().toString());

        Label name = new Label(selected.getFileName().toString());
        name.getStyleClass().add("body");

        Label meta = new Label(humanSize(size));
        meta.getStyleClass().add("text-secondary");

        VBox texts = new VBox(2, name, meta);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button remove = new Button("×");
        remove.getStyleClass().add("btn-ghost");
        remove.setStyle("-fx-font-size: 16px; -fx-padding: 0 8;");
        remove.setOnAction(e -> {
            selected = null;
            refresh();
        });

        HBox row = new HBox(badge, texts, spacer, remove);
        row.getStyleClass().add("file-preview");
        return row;
    }

    private VBox buildSummary(int shards, int chunkSize, int replication) {
        VBox box = new VBox();
        box.getStyleClass().add("summary-card");

        box.getChildren().addAll(
                summaryRow("Будет создано шардов",
                        shards + " (по " + humanSize(chunkSize) + ")"),
                summaryRow("Реплик каждого шарда", String.valueOf(replication)),
                summaryRow("Шифрование", "AES-256-GCM")
        );
        return box;
    }

    private static HBox summaryRow(String label, String value) {
        Label l = new Label(label);
        l.getStyleClass().add("summary-row-label");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label v = new Label(value);
        v.getStyleClass().add("summary-row-value");

        HBox row = new HBox(l, spacer, v);
        row.getStyleClass().add("summary-row");
        return row;
    }

    private static HBox banner(String text, boolean warn) {
        Label icon = new Label(warn ? "!" : "i");
        icon.setStyle(
                "-fx-min-width: 16; -fx-min-height: 16;"
                        + "-fx-pref-width: 16; -fx-pref-height: 16;"
                        + "-fx-max-width: 16; -fx-max-height: 16;"
                        + "-fx-background-color: " + (warn ? "-color-warning" : "-color-accent") + ";"
                        + "-fx-text-fill: white;"
                        + "-fx-background-radius: 8;"
                        + "-fx-alignment: center;"
                        + "-fx-font-size: 11px; -fx-font-weight: 700;"
        );

        Label t = new Label(text);
        t.getStyleClass().add("info-banner-text");
        t.setWrapText(true);
        HBox.setHgrow(t, Priority.ALWAYS);

        HBox row = new HBox(10, icon, t);
        row.setAlignment(Pos.TOP_LEFT);
        // Небольшой top-padding на иконку, чтобы её центр лежал на уровне
        // первой строки текста (аналог alignItems: flex-start в CSS).
        HBox.setMargin(icon, new Insets(2, 0, 0, 0));
        row.getStyleClass().addAll("info-banner");
        if (warn) row.getStyleClass().add("info-banner--warn");
        return row;
    }

    /** «12.4 МБ», «487 КБ», «8.2 ГБ». */
    public static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " Б";
        double kb = bytes / 1024.0;
        if (kb < 1024) return formatTwoDigits(kb) + " КБ";
        double mb = kb / 1024.0;
        if (mb < 1024) return formatTwoDigits(mb) + " МБ";
        double gb = mb / 1024.0;
        return formatTwoDigits(gb) + " ГБ";
    }

    private static String formatTwoDigits(double v) {
        // Никаких локалей — точка как разделитель, как в мокапе «12.4 МБ»
        if (v >= 100) return String.format("%.0f", v);
        if (v >= 10)  return String.format("%.1f", v);
        return String.format("%.2f", v);
    }
}
