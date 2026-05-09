package ru.hse.jblockstorage.gui.views;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.hse.jblockstorage.blockchain.Transaction;
import ru.hse.jblockstorage.gui.AppContext;
import ru.hse.jblockstorage.gui.components.ContactAvatar;
import ru.hse.jblockstorage.gui.components.FileTypeBadge;
import ru.hse.jblockstorage.gui.components.FormatUtils;

import java.io.File;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.IntConsumer;

/**
 * Раздел «Расшаренные мне» главного экрана — день 13, шаг 6.
 *
 * <p>По плану (см. summary-day13.md): «простая таблица имя+дата |
 * поделившийся (ContactAvatar small) | размер | кнопка «Скачать».
 * Без меню ⋯ и без колонки реплик. Использует {@code node.listAccessibleFiles()}».
 *
 * <p>Логика проще, чем в «Мои файлы»:
 * <ul>
 *   <li>{@link ru.hse.jblockstorage.app.NodeApplication#listAccessibleFiles()}
 *       возвращает оригинальные UPLOAD-tx, к которым у меня выдан ACL</li>
 *   <li>Поделившийся = {@code tx.getOwnerPublicKey()} (т.к. это
 *       UPLOAD-tx чужого пользователя)</li>
 *   <li>Скачивание — обычное {@link
 *       ru.hse.jblockstorage.app.NodeApplication#downloadFile(String, Path)}.
 *       Бэкенд сам поймёт, что есть ACL, и расшифрует ключ для меня.</li>
 *   <li>Сортировка — по дате убыванию (самые свежие сверху). Поиска и
 *       режимов сортировки тут нет: в этом разделе обычно мало записей,
 *       и UX простой.</li>
 * </ul>
 *
 * <p>Empty state — большая иконка по центру, объяснение «зачем» и
 * подсказка как получить файлы (попросить отправителя расшарить вам).
 */
public final class SharedFilesContentView {

    private static final Logger log = LoggerFactory.getLogger(SharedFilesContentView.class);

    /** Пиксельные ширины колонок — синхронизированы с {@link #buildHeaderRow()}. */
    private static final double COL_FROM = 180;
    private static final double COL_SIZE = 100;
    private static final double COL_ACTION = 110;

    private final AppContext context;
    private final Parent root;
    private final VBox listContainer = new VBox();
    private Node listHeaderNode;

    private int totalCount;
    private long totalSize;
    private final Label headerSubtitle = new Label();

    /** Внешний наблюдатель за изменением числа файлов (Dashboard обновляет sidebar). */
    private IntConsumer onCountChanged;

    public SharedFilesContentView(AppContext context) {
        this.context = Objects.requireNonNull(context, "context");
        if (!context.isAuthenticated()) {
            throw new IllegalStateException("SharedFilesContentView требует активную сессию");
        }
        this.root = build();
        reload();
    }

    public Parent getRoot() {
        return root;
    }

    public int getFileCount() {
        return totalCount;
    }

    public void setOnCountChanged(IntConsumer cb) {
        this.onCountChanged = cb;
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    private Parent build() {
        Parent header = buildHeader();
        listHeaderNode = buildHeaderRow();

        ScrollPane scroll = new ScrollPane(listContainer);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("app-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox content = new VBox(header, listHeaderNode, scroll);
        content.getStyleClass().add("app-background");
        return content;
    }

    /** Шапка раздела: заголовок «Расшаренные мне» + подзаголовок с метой. */
    private Parent buildHeader() {
        Label title = new Label("Расшаренные мне");
        title.getStyleClass().add("section-title");

        headerSubtitle.getStyleClass().add("section-subtitle");

        VBox titles = new VBox(4, title, headerSubtitle);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(16, titles, spacer);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("section-header");
        return row;
    }

    /** Заголовок столбцов: «Имя | От кого | Размер | _». */
    private Node buildHeaderRow() {
        Label nameCol = new Label("Имя");
        nameCol.getStyleClass().add("files-list-col-label");
        HBox.setHgrow(nameCol, Priority.ALWAYS);
        nameCol.setMaxWidth(Double.MAX_VALUE);

        Label fromCol = new Label("От кого");
        fromCol.getStyleClass().add("files-list-col-label");
        fromCol.setMinWidth(COL_FROM);
        fromCol.setPrefWidth(COL_FROM);
        fromCol.setMaxWidth(COL_FROM);

        Label sizeCol = new Label("Размер");
        sizeCol.getStyleClass().add("files-list-col-label");
        sizeCol.setMinWidth(COL_SIZE);
        sizeCol.setPrefWidth(COL_SIZE);
        sizeCol.setMaxWidth(COL_SIZE);
        sizeCol.setAlignment(Pos.CENTER_RIGHT);

        // Пустая «колонка действия» — для выравнивания со строками
        Region actionsCol = new Region();
        actionsCol.setMinWidth(COL_ACTION);
        actionsCol.setPrefWidth(COL_ACTION);
        actionsCol.setMaxWidth(COL_ACTION);

        HBox row = new HBox(nameCol, fromCol, sizeCol, actionsCol);
        row.getStyleClass().add("files-list-header");
        row.setAlignment(Pos.CENTER_LEFT);
        row.managedProperty().bind(row.visibleProperty());
        return row;
    }

    // ------------------------------------------------------------------
    // Reload
    // ------------------------------------------------------------------

    /** Перечитывает список расшаренных файлов и перерисовывает UI. */
    public void reload() {
        List<Transaction> shared;
        try {
            shared = context.node().listAccessibleFiles();
        } catch (Exception e) {
            log.warn("listAccessibleFiles() failed", e);
            shared = List.of();
        }

        // Сортируем по дате убыванию: самые свежие — сверху.
        shared = shared.stream()
                .sorted(Comparator.comparingLong(Transaction::getTimestamp).reversed())
                .toList();

        totalCount = shared.size();
        totalSize = shared.stream().mapToLong(Transaction::getFileSize).sum();

        // Обновление подзаголовка
        if (totalCount == 0) {
            headerSubtitle.setText("");
        } else {
            headerSubtitle.setText(totalCount + " " + FormatUtils.pluralFiles(totalCount)
                    + " · " + FormatUtils.humanSize(totalSize));
        }

        // Перерисовка списка
        listContainer.getChildren().clear();
        if (shared.isEmpty()) {
            listHeaderNode.setVisible(false);
            listContainer.getChildren().add(buildEmptyState());
        } else {
            listHeaderNode.setVisible(true);
            for (Transaction tx : shared) {
                listContainer.getChildren().add(buildRow(tx));
            }
        }

        if (onCountChanged != null) onCountChanged.accept(totalCount);
    }

    // ------------------------------------------------------------------
    // Строка таблицы
    // ------------------------------------------------------------------

    /** Одна строка: бейдж + имя/дата | поделившийся (avatar small + ключ) | размер | «Скачать». */
    private HBox buildRow(Transaction tx) {
        // Колонка «Имя» — бейдж типа + имя файла + дата
        StackPane badge = FileTypeBadge.forFile(tx.getFileName());

        Label fileName = new Label(tx.getFileName());
        fileName.getStyleClass().add("files-row-name");
        fileName.setMaxWidth(Double.MAX_VALUE);

        Label fileMeta = new Label(FormatUtils.relativeDate(tx.getTimestamp()));
        fileMeta.getStyleClass().add("files-row-meta");

        VBox nameCell = new VBox(2, fileName, fileMeta);
        nameCell.setMaxWidth(Double.MAX_VALUE);
        HBox nameWrap = new HBox(10, badge, nameCell);
        nameWrap.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(nameWrap, Priority.ALWAYS);
        // Без minWidth, чтобы колонка могла «сжиматься» при узком окне
        // и работало многоточие в name (через ellipsisString=Default JFX).

        // Колонка «От кого» — аватар + короткий ключ
        String fromKey = tx.getOwnerPublicKey();
        StackPane fromAvatar = ContactAvatar.create(fromKey != null ? fromKey : "?",
                ContactAvatar.SIZE_SMALL);

        // У нас пока нет ContactStore (день 14) — поэтому отображаем
        // короткий ключ. Когда появится — здесь будет нормальное имя
        // из контактов с fallback на короткий ключ.
        Label fromName = new Label("Узел " + shortId(fromKey));
        fromName.getStyleClass().add("files-row-name");

        Label fromKeyLine = new Label(FormatUtils.shortenPubKey(fromKey));
        fromKeyLine.getStyleClass().add("files-row-meta");

        VBox fromTexts = new VBox(2, fromName, fromKeyLine);

        HBox fromCell = new HBox(8, fromAvatar, fromTexts);
        fromCell.setAlignment(Pos.CENTER_LEFT);
        fromCell.setMinWidth(COL_FROM);
        fromCell.setPrefWidth(COL_FROM);
        fromCell.setMaxWidth(COL_FROM);

        // Колонка «Размер» — выравнивание справа
        Label sizeLabel = new Label(FormatUtils.humanSize(tx.getFileSize()));
        sizeLabel.getStyleClass().add("files-row-size");
        HBox sizeCell = new HBox(sizeLabel);
        sizeCell.setAlignment(Pos.CENTER_RIGHT);
        sizeCell.setMinWidth(COL_SIZE);
        sizeCell.setPrefWidth(COL_SIZE);
        sizeCell.setMaxWidth(COL_SIZE);

        // Колонка «Действие» — кнопка «Скачать»
        Button downloadBtn = new Button("Скачать");
        downloadBtn.getStyleClass().add("files-row-download-btn");
        downloadBtn.setOnAction(e -> onDownload(tx));

        HBox actionCell = new HBox(downloadBtn);
        actionCell.setAlignment(Pos.CENTER_RIGHT);
        actionCell.setMinWidth(COL_ACTION);
        actionCell.setPrefWidth(COL_ACTION);
        actionCell.setMaxWidth(COL_ACTION);

        HBox row = new HBox(nameWrap, fromCell, sizeCell, actionCell);
        row.getStyleClass().add("files-row");
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** Empty state — большая иконка по центру, объяснение «зачем». */
    private Parent buildEmptyState() {
        Label iconBg = new Label("📥");
        iconBg.setStyle("-fx-font-size: 36px;");
        StackPane iconWrap = new StackPane(iconBg);
        iconWrap.getStyleClass().add("empty-state-icon-bg");
        iconWrap.setMinSize(72, 72);
        iconWrap.setMaxSize(72, 72);

        Label title = new Label("Никто пока не делился с вами файлами");
        title.getStyleClass().add("empty-state-title");
        title.setWrapText(true);

        Label text = new Label(
                "Когда другой участник сети расшарит вам файл, он появится здесь "
                        + "и станет доступен для скачивания. Чтобы получить файл, "
                        + "отправьте свой публичный ключ владельцу — его можно "
                        + "скопировать в разделе «Настройки».");
        text.getStyleClass().add("empty-state-text");
        text.setWrapText(true);
        text.setMaxWidth(420);

        VBox box = new VBox(14, iconWrap, title, text);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(48, 24, 48, 24));
        box.setMaxWidth(440);
        StackPane wrap = new StackPane(box);
        wrap.setPadding(new Insets(0, 28, 28, 28));
        wrap.setAlignment(Pos.TOP_CENTER);
        return wrap;
    }

    // ------------------------------------------------------------------
    // Действия
    // ------------------------------------------------------------------

    private void onDownload(Transaction tx) {
        log.info("Shared → Скачать {} (от {})", tx.getId(),
                FormatUtils.shortenPubKey(tx.getOwnerPublicKey()));
        FileChooser fc = new FileChooser();
        fc.setTitle("Скачать файл");
        fc.setInitialFileName(tx.getFileName());
        File target = fc.showSaveDialog(context.router().stage());
        if (target == null) return;
        Path out = target.toPath();

        // День 15 (ТЗ п. 4.1.5.3): прогресс-диалог со скоростью и списком узлов.
        ru.hse.jblockstorage.gui.download.DownloadProgressState state =
                new ru.hse.jblockstorage.gui.download.DownloadProgressState();
        ru.hse.jblockstorage.gui.download.DownloadController controller =
                new ru.hse.jblockstorage.gui.download.DownloadController(
                        context.node(), state);
        ru.hse.jblockstorage.gui.dialogs.DownloadProgressDialog dlg =
                new ru.hse.jblockstorage.gui.dialogs.DownloadProgressDialog(context, state);

        controller.start(tx.getId(), out, null, ex -> {
            log.warn("Shared download failed: {}", explain(ex));
        });
        dlg.showAsWindow(context.router().stage());
    }

    // ------------------------------------------------------------------
    // Утилиты
    // ------------------------------------------------------------------

    /** Короткий «удобочитаемый» id узла из публичного ключа: первые 6 символов. */
    private static String shortId(String pubKey) {
        if (pubKey == null) return "?";
        return pubKey.length() <= 6 ? pubKey : pubKey.substring(0, 6);
    }

    private void showInfo(String title, String msg) {
        Alert a = new Alert(AlertType.INFORMATION);
        a.initOwner(context.router().stage());
        a.setHeaderText(title);
        a.setContentText(msg);
        a.showAndWait();
    }

    private void showError(String title, String msg) {
        Alert a = new Alert(AlertType.ERROR);
        a.initOwner(context.router().stage());
        a.setHeaderText(title);
        a.setContentText(msg);
        a.showAndWait();
    }

    private static String explain(Throwable t) {
        if (t == null) return "Неизвестная ошибка";
        if (t.getMessage() != null && !t.getMessage().isBlank()) return t.getMessage();
        return t.getClass().getSimpleName();
    }
}
