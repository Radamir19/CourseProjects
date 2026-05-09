package ru.hse.jblockstorage.gui.dialogs;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.hse.jblockstorage.blockchain.Blockchain;
import ru.hse.jblockstorage.blockchain.StorageReceipt;
import ru.hse.jblockstorage.blockchain.Transaction;
import ru.hse.jblockstorage.gui.AppContext;
import ru.hse.jblockstorage.gui.components.ContactAvatar;
import ru.hse.jblockstorage.gui.components.FormatUtils;
import ru.hse.jblockstorage.network.PeerManager;
import ru.hse.jblockstorage.network.PeerSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Модальный диалог «Свойства файла» — день 13, шаг 4.
 *
 * <p>По плану (см. summary-day13.md):
 * <ul>
 *   <li>Секция «Общее» — загружено (fullDateTime), размер, шардов,
 *       шифрование AES-256-GCM, ID с кнопкой «Скопировать»</li>
 *   <li>Секция «Хранители» — список реплик с {@link ContactAvatar} по
 *       nodeId, статус ● онлайн / ○ оффлайн через
 *       {@link PeerManager#snapshotSessions()}</li>
 * </ul>
 *
 * <p>Источник реплик: для UPLOAD-транзакции
 * {@link Blockchain#findLatestRepairFor(String)}: если был REPAIR — берём
 * его список реплик (это самый свежий), иначе — оригинальные из UPLOAD.
 * Это синхронизировано с тем, как реплики отображаются в строке таблицы
 * «Мои файлы» (см. {@code MyFilesContentView}).
 *
 * <p>Структура окна — как в {@link UploadDialog#showAsWindow}:
 * отдельный {@link Stage} с {@link StageStyle#UNIFIED}, body в
 * {@link ScrollPane}, footer (одна кнопка «Закрыть») всегда виден.
 *
 * <p>Окно read-only — никаких действий пользователь отсюда не делает,
 * только смотрит. Диалоги «Скачать» / «Расшарить» / «Удалить» доступны
 * из контекстного меню строки в основной таблице.
 */
public final class FilePropertiesDialog {

    private static final Logger log = LoggerFactory.getLogger(FilePropertiesDialog.class);

    /** Стартовая ширина окна — чуть шире UploadDialog: тут больше колонок данных. */
    private static final double INITIAL_WIDTH = 480;
    private static final double MIN_WIDTH = 420;
    private static final double MIN_HEIGHT = 360;

    private final AppContext context;
    private final Transaction tx;

    private VBox bodyNode;
    private VBox footerNode;
    private Stage window;

    /**
     * @param context контекст приложения (нужен node + router)
     * @param tx      UPLOAD-транзакция, для которой открыты свойства.
     *                Diff между UPLOAD и REPAIR-репликами рассчитывается внутри.
     */
    public FilePropertiesDialog(AppContext context, Transaction tx) {
        this.context = Objects.requireNonNull(context, "context");
        this.tx = Objects.requireNonNull(tx, "tx");
        if (tx.getKind() != Transaction.Kind.UPLOAD) {
            throw new IllegalArgumentException(
                    "FilePropertiesDialog принимает только UPLOAD-tx, "
                            + "получено: " + tx.getKind());
        }
        build();
    }

    public Parent getRoot() {
        // Возвращаем body+footer уже свёрстанные (но для тестов в основном
        // используется showAsWindow). Никто кроме тестов get'ом не пользуется.
        VBox card = new VBox(bodyNode, footerNode);
        card.getStyleClass().add("modal-card");
        return card;
    }

    // ------------------------------------------------------------------
    // Сборка содержимого
    // ------------------------------------------------------------------

    private void build() {
        // Заголовок: «Свойства файла» + имя файла
        Label title = new Label("Свойства файла");
        title.getStyleClass().add("modal-title");

        Label subtitle = new Label(tx.getFileName());
        subtitle.getStyleClass().add("modal-subtitle");
        subtitle.setWrapText(true);

        VBox header = new VBox(6, title, subtitle);
        header.setPadding(new Insets(20, 20, 12, 20));

        // Секция «Общее»
        Label generalLabel = new Label("ОБЩЕЕ");
        generalLabel.getStyleClass().add("props-section-label");
        VBox generalLabelWrap = new VBox(generalLabel);
        generalLabelWrap.setPadding(new Insets(0, 20, 0, 20));

        VBox generalCard = buildGeneralCard();
        VBox generalCardWrap = new VBox(generalCard);
        generalCardWrap.setPadding(new Insets(0, 20, 14, 20));

        // Секция «Хранители»
        List<StorageReceipt> replicas = resolveLatestReplicas();
        Label storersLabel = new Label("ХРАНИТЕЛИ ШАРДОВ · " + replicas.size() + " "
                + FormatUtils.pluralPeople(replicas.size()));
        storersLabel.getStyleClass().add("props-section-label");
        VBox storersLabelWrap = new VBox(storersLabel);
        storersLabelWrap.setPadding(new Insets(0, 20, 0, 20));

        VBox storersCard = buildStorersCard(replicas);
        VBox storersCardWrap = new VBox(storersCard);
        storersCardWrap.setPadding(new Insets(0, 20, 18, 20));

        bodyNode = new VBox(
                header,
                generalLabelWrap, generalCardWrap,
                storersLabelWrap, storersCardWrap);
        bodyNode.setFillWidth(true);

        // Footer
        Region divider = new Region();
        divider.getStyleClass().add("modal-section-divider");

        Button close = new Button("Закрыть");
        close.getStyleClass().add("btn-secondary");
        close.setOnAction(e -> dismissWindow());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox btnRow = new HBox(8, spacer, close);
        btnRow.setAlignment(Pos.CENTER_RIGHT);
        btnRow.setPadding(new Insets(14, 20, 16, 20));

        footerNode = new VBox(divider, btnRow);
    }

    /** Карточка «Общее» — пары label/value с разделителями. */
    private VBox buildGeneralCard() {
        VBox card = new VBox();
        card.getStyleClass().add("props-card");

        int shards = tx.getShardHashes() != null ? tx.getShardHashes().size() : 0;

        card.getChildren().addAll(
                propsRow("Загружено", FormatUtils.fullDateTime(tx.getTimestamp()), false),
                propsRow("Размер", FormatUtils.humanSize(tx.getFileSize()), false),
                propsRow("Шардов", shards + " " + FormatUtils.pluralShards(shards), false),
                propsRow("Шифрование", "AES-256-GCM", false),
                propsRowWithCopy("ID транзакции", tx.getId(), true)
        );
        return card;
    }

    /**
     * Карточка «Хранители» — по строке на каждую реплику.
     * Если реплик нет (ситуация теоретическая, но возможная для пустых
     * файлов или сразу после загрузки до подтверждения) — показывается
     * соответствующая подсказка.
     */
    private VBox buildStorersCard(List<StorageReceipt> replicas) {
        VBox card = new VBox();
        card.getStyleClass().add("props-card");

        if (replicas.isEmpty()) {
            Label empty = new Label("Нет данных о хранителях шардов.");
            empty.getStyleClass().add("props-row-label");
            VBox wrap = new VBox(empty);
            wrap.setPadding(new Insets(12, 14, 12, 14));
            card.getChildren().add(wrap);
            return card;
        }

        Map<String, PeerSession> sessions = context.node().peerManager().snapshotSessions();
        String selfId = context.node().selfNodeId();

        for (int i = 0; i < replicas.size(); i++) {
            StorageReceipt sr = replicas.get(i);
            boolean last = (i == replicas.size() - 1);
            card.getChildren().add(buildStorerRow(sr, sessions, selfId, last));
        }
        return card;
    }

    /** Одна строка хранителя: аватар + имя/ключ + статус online/offline. */
    private HBox buildStorerRow(StorageReceipt sr,
                                Map<String, PeerSession> sessions,
                                String selfId,
                                boolean last) {
        String storerKey = sr.getStorerPublicKey();
        boolean isSelf = storerKey != null && storerKey.equals(selfId);
        boolean online;
        if (isSelf) {
            online = true;
        } else {
            PeerSession s = sessions.get(storerKey);
            online = s != null && s.isActive();
        }

        StackPane avatar = ContactAvatar.create(storerKey != null ? storerKey : "?",
                ContactAvatar.SIZE_LARGE);

        Label name = new Label(isSelf ? "Этот узел" : "Узел " + shortId(storerKey));
        name.getStyleClass().add("props-storer-name");

        Label keyLine = new Label(FormatUtils.shortenPubKey(storerKey));
        keyLine.getStyleClass().add("props-storer-key");

        VBox texts = new VBox(2, name, keyLine);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Точка-индикатор + текст «онлайн / оффлайн»
        Region statusDot = new Region();
        statusDot.setMinSize(7, 7);
        statusDot.setMaxSize(7, 7);
        statusDot.setPrefSize(7, 7);
        statusDot.setStyle(
                "-fx-background-color: " + (online ? "-color-success" : "-color-text-hint") + ";"
                        + "-fx-background-radius: 999;"
        );
        Label statusText = new Label(online ? "онлайн" : "оффлайн");
        statusText.getStyleClass().add("props-row-label");

        HBox status = new HBox(6, statusDot, statusText);
        status.setAlignment(Pos.CENTER_LEFT);

        HBox row = new HBox(10, avatar, texts, spacer, status);
        row.getStyleClass().add("props-storer-row");
        if (last) row.getStyleClass().add("props-row--last");
        return row;
    }

    /** Строка «label — value» в card'е свойств. */
    private HBox propsRow(String label, String value, boolean last) {
        Label l = new Label(label);
        l.getStyleClass().add("props-row-label");

        Label v = new Label(value);
        v.getStyleClass().add("props-row-value");
        v.setWrapText(false);
        v.setMaxWidth(Double.MAX_VALUE);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(l, spacer, v);
        row.getStyleClass().add("props-row");
        if (last) row.getStyleClass().add("props-row--last");
        return row;
    }

    /** Строка с моноширинным значением и кнопкой «Копировать». Для txId. */
    private HBox propsRowWithCopy(String label, String value, boolean last) {
        Label l = new Label(label);
        l.getStyleClass().add("props-row-label");

        Label v = new Label(FormatUtils.shortenTxId(value));
        v.getStyleClass().add("props-row-value-mono");

        Button copyBtn = new Button("Копировать");
        copyBtn.getStyleClass().add("btn-copy");
        copyBtn.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(value);
            Clipboard.getSystemClipboard().setContent(cc);
            log.debug("FileProperties: txId скопирован в буфер обмена");
            // Кратковременная подсказка «скопировано» — без сложных popup'ов.
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

        HBox row = new HBox(l, spacer, v, copyBtn);
        row.getStyleClass().add("props-row");
        if (last) row.getStyleClass().add("props-row--last");
        return row;
    }

    /**
     * Возвращает «свежий» список реплик: если для UPLOAD есть REPAIR —
     * берём из него (это актуальный список после восстановления), иначе
     * берём оригинальные реплики UPLOAD-tx.
     */
    private List<StorageReceipt> resolveLatestReplicas() {
        Blockchain bc = context.node().blockchain();
        Optional<Transaction> repair = bc.findLatestRepairFor(tx.getId());
        if (repair.isPresent() && repair.get().getReplicas() != null) {
            log.debug("FileProperties: используем реплики из REPAIR-tx {}",
                    repair.get().getId());
            return repair.get().getReplicas();
        }
        List<StorageReceipt> orig = tx.getReplicas();
        return orig != null ? orig : new ArrayList<>();
    }

    /** Короткий «удобочитаемый» id узла из публичного ключа: первые 6 символов. */
    private static String shortId(String pubKey) {
        if (pubKey == null) return "?";
        return pubKey.length() <= 6 ? pubKey : pubKey.substring(0, 6);
    }

    // ------------------------------------------------------------------
    // Окно
    // ------------------------------------------------------------------

    /**
     * Показывает диалог как отдельное нативное окно (см. шаблон в
     * {@link UploadDialog#showAsWindow}).
     */
    public void showAsWindow(Stage ownerStage) {
        Stage stage = new Stage();
        stage.initOwner(ownerStage);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.initStyle(StageStyle.UNIFIED);
        stage.setTitle("Свойства файла");
        stage.setResizable(true);
        stage.setMinWidth(MIN_WIDTH);
        stage.setMinHeight(MIN_HEIGHT);

        ScrollPane scroll = new ScrollPane(bodyNode);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.getStyleClass().add("modal-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox card = new VBox(scroll, footerNode);
        card.getStyleClass().add("modal-card");

        Scene scene = new Scene(card);
        // Подхватываем стили и темы от главной сцены (см. UploadDialog).
        if (ownerStage.getScene() != null) {
            scene.getStylesheets().setAll(ownerStage.getScene().getStylesheets());
            for (String cls : ownerStage.getScene().getRoot().getStyleClass()) {
                if (!scene.getRoot().getStyleClass().contains(cls)) {
                    scene.getRoot().getStyleClass().add(cls);
                }
            }
        }

        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) stage.close();
        });

        stage.setScene(scene);
        stage.setWidth(INITIAL_WIDTH);
        autoFitInitialHeight(stage, scene);

        this.window = stage;
        stage.showAndWait();
    }

    private static void autoFitInitialHeight(Stage stage, Scene scene) {
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        double naturalHeight = scene.getRoot().prefHeight(INITIAL_WIDTH);
        double maxByScreen = Screen.getPrimary().getVisualBounds().getHeight() * 0.8;
        double finalHeight = Math.max(MIN_HEIGHT, Math.min(naturalHeight, maxByScreen));
        stage.setHeight(finalHeight + 28);
    }

    public void dismissWindow() {
        if (window != null) {
            window.close();
            window = null;
        }
    }
}
