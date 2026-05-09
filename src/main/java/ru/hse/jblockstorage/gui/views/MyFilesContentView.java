package ru.hse.jblockstorage.gui.views;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.hse.jblockstorage.blockchain.Transaction;
import ru.hse.jblockstorage.gui.AppContext;
import ru.hse.jblockstorage.gui.components.FileTypeBadge;
import ru.hse.jblockstorage.gui.components.FormatUtils;
import ru.hse.jblockstorage.gui.components.MyFilesUploadCard;
import ru.hse.jblockstorage.gui.dialogs.FilePropertiesDialog;
import ru.hse.jblockstorage.gui.dialogs.ShareFileDialog;
import ru.hse.jblockstorage.gui.dialogs.UploadDialog;
import ru.hse.jblockstorage.gui.upload.UploadController;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntConsumer;

/**
 * Раздел «Мои файлы» главного экрана — полноценная таблица.
 *
 * <p>По мокапу {@code dashboard_my_files_v3.html}: шапка с заголовком
 * и кнопкой «Загрузить файл», ниже toolbar с поиском и сортировкой,
 * далее заголовок столбцов и список строк-файлов. У каждой строки
 * контекстное меню (⋯): Скачать / Расшарить / Свойства / Удалить.
 *
 * <p>Над таблицей — карточка прогресса загрузки {@link MyFilesUploadCard}
 * (мокап {@code upload_progress.html}, правая часть). Появляется только
 * когда идёт загрузка; в sidebar дублируется компактный блок.
 *
 * <p><b>Решение по таблице.</b> Вместо JavaFX TableView используется
 * VBox со строками HBox: точное соответствие мокапу без переопределения
 * сотен селекторов TableView. Виртуализация не нужна — десятки файлов
 * это потолок реалистичной нагрузки на одного пользователя.
 *
 * <p><b>Реактивность.</b> После uploadFile/deleteFile/shareFile внешний
 * код вызывает {@link #reload}, который перезагружает данные из
 * {@code blockchain.listByOwner()} и перерисовывает строки. О смене
 * количества файлов уведомляется {@link #setOnCountChanged} —
 * это используется DashboardView для обновления счётчика в sidebar.
 */
public final class MyFilesContentView {

    private static final Logger log = LoggerFactory.getLogger(MyFilesContentView.class);

    /** Пиксельные ширины колонок — синхронизированы с {@link #buildHeaderRow()}. */
    private static final double COL_SIZE = 100;
    private static final double COL_REPLICAS = 100;
    private static final double COL_ACTIONS = 60;

    private final AppContext context;
    private final Parent root;
    private final VBox listContainer = new VBox();
    private final Label headerSubtitle = new Label();
    private final TextField searchField = new TextField();
    private final Node uploadCardRoot;
    /** Toolbar и заголовок — храним ссылки, чтобы скрывать на пустом состоянии. */
    private Node toolbarNode;
    private Node listHeaderNode;

    /** Текущий выбранный режим сортировки. */
    private SortMode sortMode = SortMode.DATE_DESC;

    /** Все файлы (UPLOAD-tx, не удалённые) — снимок последней загрузки. */
    private final ObservableList<Transaction> all = FXCollections.observableArrayList();
    private int totalCount;
    private long totalSize;

    /** Внешний наблюдатель за изменением числа файлов (Dashboard обновляет sidebar). */
    private IntConsumer onCountChanged;

    public MyFilesContentView(AppContext context) {
        this.context = Objects.requireNonNull(context, "context");
        if (!context.isAuthenticated()) {
            throw new IllegalStateException("MyFilesContentView требует активную сессию");
        }
        this.uploadCardRoot = new MyFilesUploadCard(context.uploadState()).getRoot();
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
        toolbarNode = buildToolbar();
        listHeaderNode = buildHeaderRow();

        // Скроллируется только список — тулбар, шапка таблицы и карточка
        // прогресса фиксированы.
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(listContainer);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("app-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox content = new VBox(header, uploadCardRoot, toolbarNode, listHeaderNode, scroll);
        content.getStyleClass().add("app-background");
        return content;
    }

    /** Шапка раздела: заголовок, описание, кнопка «Загрузить файл» справа. */
    private Parent buildHeader() {
        Label title = new Label("Мои файлы");
        title.getStyleClass().add("section-title");

        headerSubtitle.getStyleClass().add("section-subtitle");

        VBox titles = new VBox(4, title, headerSubtitle);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button upload = new Button("Загрузить файл");
        upload.getStyleClass().add("btn-primary");
        upload.setGraphic(plusIcon());
        upload.setOnAction(e -> onUpload());

        HBox row = new HBox(16, titles, spacer, upload);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("section-header");
        return row;
    }

    /** Toolbar с поиском и кнопкой сортировки (отображается, только если есть файлы). */
    private Node buildToolbar() {
        searchField.setPromptText("Поиск по имени");
        searchField.getStyleClass().add("files-toolbar-search");
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.textProperty().addListener((obs, old, val) -> applyFilterAndRender());

        Button sortBtn = new Button(sortLabel());
        sortBtn.getStyleClass().add("files-toolbar-sort");
        sortBtn.setOnAction(e -> {
            sortMode = sortMode.next();
            sortBtn.setText(sortLabel());
            applyFilterAndRender();
        });

        HBox row = new HBox(searchField, sortBtn);
        row.getStyleClass().add("files-toolbar");
        row.managedProperty().bind(row.visibleProperty());
        return row;
    }

    private String sortLabel() {
        return switch (sortMode) {
            case DATE_DESC -> "По дате ↓";
            case DATE_ASC -> "По дате ↑";
            case NAME_ASC -> "По имени А–Я";
            case SIZE_DESC -> "По размеру ↓";
        };
    }

    /** Заголовок столбцов «Имя | Размер | Реплик | _». */
    private Node buildHeaderRow() {
        Label nameCol = new Label("Имя");
        nameCol.getStyleClass().add("files-list-col-label");
        HBox.setHgrow(nameCol, Priority.ALWAYS);
        nameCol.setMaxWidth(Double.MAX_VALUE);

        Label sizeCol = new Label("Размер");
        sizeCol.getStyleClass().add("files-list-col-label");
        sizeCol.setMinWidth(COL_SIZE);
        sizeCol.setPrefWidth(COL_SIZE);
        sizeCol.setAlignment(Pos.CENTER_RIGHT);
        sizeCol.setMaxWidth(COL_SIZE);

        Label replCol = new Label("Реплик");
        replCol.getStyleClass().add("files-list-col-label");
        replCol.setMinWidth(COL_REPLICAS);
        replCol.setPrefWidth(COL_REPLICAS);
        replCol.setAlignment(Pos.CENTER_RIGHT);
        replCol.setMaxWidth(COL_REPLICAS);

        Region spacer = new Region();
        spacer.setMinWidth(COL_ACTIONS);
        spacer.setPrefWidth(COL_ACTIONS);

        HBox header = new HBox(nameCol, sizeCol, replCol, spacer);
        header.getStyleClass().add("files-list-header");
        header.setAlignment(Pos.CENTER_LEFT);
        header.managedProperty().bind(header.visibleProperty());
        return header;
    }

    // ------------------------------------------------------------------
    // Reload + render
    // ------------------------------------------------------------------

    /** Перезагружает список из блокчейна и перерисовывает. */
    public void reload() {
        all.clear();
        long sum = 0;
        try {
            List<Transaction> list = context.node().listMyFiles();
            for (Transaction tx : list) {
                if (tx.getKind() == Transaction.Kind.UPLOAD) {
                    all.add(tx);
                    sum += tx.getFileSize();
                }
            }
        } catch (Exception e) {
            log.warn("listMyFiles упал", e);
        }
        totalCount = all.size();
        totalSize = sum;
        updateHeaderSubtitle();
        applyFilterAndRender();
        if (onCountChanged != null) onCountChanged.accept(totalCount);
    }

    private void updateHeaderSubtitle() {
        if (totalCount == 0) {
            headerSubtitle.setText("Файлы будут шифроваться на устройстве перед загрузкой");
        } else {
            headerSubtitle.setText(totalCount + " "
                    + FormatUtils.pluralFiles(totalCount)
                    + " · " + FormatUtils.humanSize(totalSize) + " зашифровано");
        }
    }

    /** Фильтрует, сортирует и перерисовывает строки списка. */
    private void applyFilterAndRender() {
        if (toolbarNode != null) toolbarNode.setVisible(totalCount > 0);
        if (listHeaderNode != null) listHeaderNode.setVisible(totalCount > 0);

        listContainer.getChildren().clear();
        if (totalCount == 0) {
            listContainer.getChildren().add(buildEmptyState());
            return;
        }

        String query = searchField.getText() == null ? ""
                : searchField.getText().trim().toLowerCase(Locale.ROOT);
        List<Transaction> visible = new ArrayList<>();
        for (Transaction tx : all) {
            if (query.isEmpty()
                    || tx.getFileName().toLowerCase(Locale.ROOT).contains(query)) {
                visible.add(tx);
            }
        }

        switch (sortMode) {
            case DATE_DESC -> visible.sort((a, b) -> Long.compare(b.getTimestamp(), a.getTimestamp()));
            case DATE_ASC -> visible.sort((a, b) -> Long.compare(a.getTimestamp(), b.getTimestamp()));
            case NAME_ASC -> visible.sort((a, b) ->
                    a.getFileName().toLowerCase(Locale.ROOT)
                            .compareTo(b.getFileName().toLowerCase(Locale.ROOT)));
            case SIZE_DESC -> visible.sort((a, b) -> Long.compare(b.getFileSize(), a.getFileSize()));
        }

        if (visible.isEmpty()) {
            Label hint = new Label("Ничего не найдено");
            hint.getStyleClass().add("text-secondary");
            VBox box = new VBox(hint);
            box.setAlignment(Pos.CENTER);
            box.setPadding(new Insets(40));
            listContainer.getChildren().add(box);
            return;
        }

        for (Transaction tx : visible) {
            listContainer.getChildren().add(buildFileRow(tx));
        }
    }

    private Node buildEmptyState() {
        Region bg = new Region();
        bg.getStyleClass().add("empty-state-icon-bg");
        bg.setPrefSize(56, 56);
        bg.setMinSize(56, 56);
        bg.setMaxSize(56, 56);

        SVGPath cloud = new SVGPath();
        cloud.setContent("M16 22V8 M10 14L16 8L22 14 M6 24H26");
        cloud.setStroke(Color.web("#007aff"));
        cloud.setStrokeWidth(1.8);
        cloud.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
        cloud.setStrokeLineJoin(javafx.scene.shape.StrokeLineJoin.ROUND);
        cloud.setFill(Color.TRANSPARENT);

        StackPane icon = new StackPane(bg, cloud);
        icon.setAlignment(Pos.CENTER);

        Label title = new Label("Пока нет файлов");
        title.getStyleClass().add("empty-state-title");

        Label desc = new Label("Каждый файл шифруется на этом устройстве, разрезается "
                + "на шарды по 512 КБ и распределяется по узлам сети. Никто, кроме "
                + "вас и тех, кому вы дадите доступ, не сможет его прочитать.");
        desc.getStyleClass().add("empty-state-text");
        desc.setWrapText(true);
        desc.setMaxWidth(420);

        Button upload = new Button("Загрузить первый файл");
        upload.getStyleClass().add("btn-primary");
        upload.setDefaultButton(true);
        upload.setOnAction(e -> onUpload());

        VBox box = new VBox(20, icon, title, desc, upload);
        box.setAlignment(Pos.CENTER);
        box.setMaxWidth(420);

        StackPane wrap = new StackPane(box);
        wrap.setPadding(new Insets(40));
        StackPane.setAlignment(box, Pos.CENTER);
        VBox.setVgrow(wrap, Priority.ALWAYS);
        return wrap;
    }

    /** Одна строка таблицы. */
    private HBox buildFileRow(Transaction tx) {
        StackPane badge = FileTypeBadge.forFile(tx.getFileName());
        badge.setMinWidth(28);
        badge.setPrefWidth(28);

        Label name = new Label(tx.getFileName());
        name.getStyleClass().add("files-row-name");
        name.setMaxWidth(Double.MAX_VALUE);

        Label meta = new Label(FormatUtils.relativeDate(tx.getTimestamp()));
        meta.getStyleClass().add("files-row-meta");

        VBox nameBox = new VBox(2, name, meta);
        HBox.setHgrow(nameBox, Priority.ALWAYS);
        nameBox.setMaxWidth(Double.MAX_VALUE);

        HBox nameCol = new HBox(10, badge, nameBox);
        nameCol.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(nameCol, Priority.ALWAYS);
        nameCol.setMaxWidth(Double.MAX_VALUE);

        Label size = new Label(FormatUtils.humanSize(tx.getFileSize()));
        size.getStyleClass().add("files-row-size");
        size.setMinWidth(COL_SIZE);
        size.setPrefWidth(COL_SIZE);
        size.setMaxWidth(COL_SIZE);
        size.setAlignment(Pos.CENTER_RIGHT);
        size.setMaxHeight(Double.MAX_VALUE);

        HBox replicas = buildReplicasCell(tx);
        replicas.setMinWidth(COL_REPLICAS);
        replicas.setPrefWidth(COL_REPLICAS);
        replicas.setMaxWidth(COL_REPLICAS);
        replicas.setAlignment(Pos.CENTER_RIGHT);

        Button actions = new Button("⋯");
        actions.getStyleClass().add("files-row-action-btn");
        actions.setMinWidth(COL_ACTIONS);
        actions.setPrefWidth(COL_ACTIONS);
        actions.setMaxWidth(COL_ACTIONS);
        ContextMenu menu = buildContextMenu(tx);
        actions.setOnAction(e -> menu.show(actions,
                actions.localToScreen(actions.getBoundsInLocal()).getMinX(),
                actions.localToScreen(actions.getBoundsInLocal()).getMaxY()));

        HBox row = new HBox(0, nameCol, size, replicas, actions);
        row.getStyleClass().add("files-row");
        row.setAlignment(Pos.CENTER_LEFT);

        // Правый клик по самой строке — тоже открывает меню
        row.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.SECONDARY) {
                menu.show(row, e.getScreenX(), e.getScreenY());
                e.consume();
            }
        });
        return row;
    }

    /**
     * Ячейка «Реплик X/Y» с цветной точкой статуса.
     * <p>Берём актуальный список реплик: если есть REPAIR — из него,
     * иначе из самого UPLOAD'а. «Живая» реплика — у которой storer
     * сейчас в активной сессии {@code peerManager}, либо это мы сами.
     */
    private HBox buildReplicasCell(Transaction tx) {
        Optional<Transaction> repair = context.node().blockchain().findLatestRepairFor(tx.getId());
        List<?> replicas = repair.isPresent() ? repair.get().getReplicas() : tx.getReplicas();
        int total = replicas == null ? 0 : replicas.size();

        int alive = 0;
        if (replicas != null && total > 0) {
            String selfNodeId = context.node().selfNodeId();
            var sessions = context.node().peerManager().snapshotSessions();
            for (Object r : replicas) {
                String storerKey = ((ru.hse.jblockstorage.blockchain.StorageReceipt) r).getStorerPublicKey();
                if (storerKey == null) continue;
                if (storerKey.equals(selfNodeId)) {
                    alive++;
                } else if (sessions.containsKey(storerKey)) {
                    alive++;
                }
            }
        }

        Region dot = new Region();
        dot.setPrefSize(6, 6);
        dot.setMinSize(6, 6);
        dot.setMaxSize(6, 6);
        dot.getStyleClass().add("status-dot");
        if (total == 0) {
            dot.getStyleClass().add("status-danger");
        } else if (alive == total) {
            dot.getStyleClass().add("status-success");
        } else if (alive >= 1) {
            dot.getStyleClass().add("status-warning");
        } else {
            dot.getStyleClass().add("status-danger");
        }

        Label text = new Label(alive + " / " + total);
        text.getStyleClass().add("files-row-replicas");

        HBox box = new HBox(5, dot, text);
        box.setAlignment(Pos.CENTER_RIGHT);
        return box;
    }

    /** Контекстное меню для строки: Скачать / Расшарить / Свойства / Удалить. */
    private ContextMenu buildContextMenu(Transaction tx) {
        MenuItem download = new MenuItem("Скачать");
        download.setOnAction(e -> onDownload(tx));

        MenuItem share = new MenuItem("Расшарить");
        share.setOnAction(e -> onShare(tx));

        MenuItem properties = new MenuItem("Свойства");
        properties.setOnAction(e -> onProperties(tx));

        MenuItem copyId = new MenuItem("Скопировать ID транзакции");
        copyId.setOnAction(e -> onCopyId(tx));

        MenuItem delete = new MenuItem("Удалить");
        delete.getStyleClass().add("danger");
        delete.setOnAction(e -> onDelete(tx));

        return new ContextMenu(
                download, share, properties, copyId,
                new SeparatorMenuItem(),
                delete
        );
    }

    // ------------------------------------------------------------------
    // Действия
    // ------------------------------------------------------------------

    private void onUpload() {
        log.info("MyFiles → Загрузить файл");
        // Если уже идёт другая загрузка — не даём запустить вторую
        if (context.uploadState().isInProgress()) {
            showInfo("Загрузка уже идёт",
                    "Дождитесь окончания текущей загрузки или закройте её, "
                            + "прежде чем начинать новую.");
            return;
        }

        // UploadDialog показывается как ОТДЕЛЬНОЕ нативное окно (см. мокап
        // upload_dialog.html — оно с заголовком macOS, тремя кружочками).
        // Это работает надёжно: JavaFX корректно обрабатывает фокус,
        // клавиатурные события и закрытие — в отличие от in-window
        // overlay'я, с которым у нас были проблемы.
        //
        // AtomicReference нужен потому, что callback внутри лямбды должен
        // закрыть окно того диалога, который ещё не создан в момент
        // объявления лямбды. Классическая Java-проблема forward-reference
        // в инициализаторах.
        var ref = new java.util.concurrent.atomic.AtomicReference<UploadDialog>();

        var dlg = new UploadDialog(
                context,
                file -> {
                    log.info("UploadDialog → выбран файл: {}", file);
                    // 1. Закрываем окно выбора файла.
                    var d = ref.get();
                    if (d != null) d.dismissWindow();
                    // 2. Стартуем встроенный прогресс. SidebarUploadCard
                    //    и MyFilesUploadCard оба подписаны на context.uploadState()
                    //    и автоматически отрисуют этапы.
                    UploadController controller = new UploadController(
                            context.node(),
                            context.uploadState(),
                            file,
                            tx -> reload(),       // на успех — перезагрузить таблицу
                            null                  // карточка сама прячется через таймер
                    );
                    controller.start();
                }
        );
        ref.set(dlg);

        // showAsWindow блокирует FX-thread через showAndWait. Это нормально:
        // nested event loop корректно работает для модальных Stage, главное
        // окно остаётся отзывчивым (только заблокировано модальностью).
        dlg.showAsWindow(context.router().stage());
    }

    private void onDownload(Transaction tx) {
        log.info("MyFiles → Скачать {}", tx.getId());
        FileChooser fc = new FileChooser();
        fc.setTitle("Скачать файл");
        fc.setInitialFileName(tx.getFileName());
        File target = fc.showSaveDialog(context.router().stage());
        if (target == null) return;
        Path out = target.toPath();

        // День 15: показываем модальный диалог прогресса с скоростью и
        // списком узлов (ТЗ п. 4.1.5.3). Backend честно репортит
        // события через DownloadProgressListener.
        ru.hse.jblockstorage.gui.download.DownloadProgressState state =
                new ru.hse.jblockstorage.gui.download.DownloadProgressState();
        ru.hse.jblockstorage.gui.download.DownloadController controller =
                new ru.hse.jblockstorage.gui.download.DownloadController(
                        context.node(), state);
        ru.hse.jblockstorage.gui.dialogs.DownloadProgressDialog dlg =
                new ru.hse.jblockstorage.gui.dialogs.DownloadProgressDialog(context, state);

        controller.start(tx.getId(), out, null, ex -> {
            // Ошибка уже отражена в state.fail() — в диалоге появится
            // блок «Не удалось». Просто логируем поверх.
            log.warn("Download failed (UI поток): {}", explain(ex));
        });
        dlg.showAsWindow(context.router().stage());
    }

    private void onShare(Transaction tx) {
        log.info("MyFiles → Расшарить {}", tx.getId());
        // ShareFileDialog с днём 14 — список контактов с чекбоксами.
        // onShare callback вызывается ДЛЯ КАЖДОГО получателя по очереди
        // (диалог сам делает цикл, см. trySubmit). При исключении из
        // onShare — прокидываем наружу, диалог покажет «Шарим…» → ошибка.
        // onAllShared — после всех успешных, для финального успешного UX.
        java.util.List<String> sharedTo = new java.util.ArrayList<>();
        ShareFileDialog dlg = new ShareFileDialog(
                context,
                tx,
                recipientPubKey -> {
                    log.info("Share → {} → recipient {}", tx.getId(),
                            FormatUtils.shortenPubKey(recipientPubKey));
                    try {
                        Transaction acl = context.node().shareFile(tx.getId(), recipientPubKey);
                        log.info("Share OK: ACL-tx {}", acl.getId());
                        sharedTo.add(recipientPubKey);
                    } catch (Exception e) {
                        log.warn("Share failed", e);
                        showError("Не удалось расшарить файл",
                                "Получатель: " + FormatUtils.shortenPubKey(recipientPubKey)
                                        + "\n\n" + explain(e));
                        // Проброс RuntimeException останавливает цикл в
                        // диалоге — пользователь увидит частичный успех
                        // и сможет решить, что делать.
                        throw new RuntimeException(e);
                    }
                },
                () -> {
                    // onAllShared — все получатели успешно обработаны.
                    int n = sharedTo.size();
                    if (n == 0) return;
                    String msg = (n == 1)
                            ? "Файл «" + tx.getFileName() + "» теперь доступен "
                                    + "получателю с указанным публичным ключом. "
                                    + "Запись о доступе добавлена в блокчейн."
                            : "Файл «" + tx.getFileName() + "» теперь доступен "
                                    + n + " получателям. Записи о доступе "
                                    + "добавлены в блокчейн.";
                    showInfo("Файл расшарен", msg);
                    // reload не нужен: список «Мои файлы» от ACL не меняется,
                    // меняется только список «Расшаренные» у получателей.
                });
        dlg.showAsWindow(context.router().stage());
    }

    private void onProperties(Transaction tx) {
        log.info("MyFiles → Свойства {}", tx.getId());
        FilePropertiesDialog dlg = new FilePropertiesDialog(context, tx);
        dlg.showAsWindow(context.router().stage());
    }

    private void onCopyId(Transaction tx) {
        ClipboardContent cc = new ClipboardContent();
        cc.putString(tx.getId());
        Clipboard.getSystemClipboard().setContent(cc);
    }

    private void onDelete(Transaction tx) {
        Alert confirm = new Alert(AlertType.CONFIRMATION);
        confirm.initOwner(context.router().stage());
        confirm.setHeaderText("Удалить «" + tx.getFileName() + "»?");
        confirm.setContentText("Запись об удалении будет добавлена в блокчейн. "
                + "Существующие копии у получателей доступа останутся у них, но "
                + "новые скачивания станут недоступны.");
        ButtonType yes = new ButtonType("Удалить", ButtonType.OK.getButtonData());
        ButtonType no = new ButtonType("Отмена", ButtonType.CANCEL.getButtonData());
        confirm.getButtonTypes().setAll(no, yes);
        Optional<ButtonType> ans = confirm.showAndWait();
        if (ans.isEmpty() || ans.get() != yes) return;
        try {
            context.node().deleteFile(tx.getId());
            log.info("Файл удалён: {}", tx.getId());
            reload();
        } catch (Exception e) {
            log.warn("Delete failed", e);
            showError("Не удалось удалить файл", explain(e));
        }
    }

    // ------------------------------------------------------------------
    // Утилиты
    // ------------------------------------------------------------------

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

    /** Иконка «+» (12×12) для primary-кнопки «Загрузить файл». */
    private static SVGPath plusIcon() {
        SVGPath p = new SVGPath();
        p.setContent("M6 2V10 M2 6H10");
        p.setStroke(Color.WHITE);
        p.setStrokeWidth(1.5);
        p.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
        p.setFill(Color.TRANSPARENT);
        return p;
    }

    /** Режимы сортировки таблицы. Шаг — клик по кнопке. */
    private enum SortMode {
        DATE_DESC, DATE_ASC, NAME_ASC, SIZE_DESC;
        SortMode next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }
}
