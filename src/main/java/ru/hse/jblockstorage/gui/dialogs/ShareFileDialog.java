package ru.hse.jblockstorage.gui.dialogs;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.input.Clipboard;
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
import ru.hse.jblockstorage.blockchain.Transaction;
import ru.hse.jblockstorage.gui.AppContext;
import ru.hse.jblockstorage.gui.components.ContactAvatar;
import ru.hse.jblockstorage.gui.components.FormatUtils;
import ru.hse.jblockstorage.gui.contacts.Contact;
import ru.hse.jblockstorage.gui.contacts.ContactStore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Модальный диалог «Расшарить файл» — день 14, переписан под список
 * контактов с чекбоксами (см. {@code dashboard_share_file.html}).
 *
 * <p><b>Как работает:</b>
 * <ol>
 *   <li>Если в адресной книге есть контакты — основная область занята
 *       прокручиваемым списком: чекбокс + аватар + имя + короткий ключ.
 *       Над списком — счётчик «Выбрано N». Контакты, которым этот файл
 *       уже расшарен (есть ACL-tx в блокчейне), помечены подписью
 *       «уже расшарено», их чекбокс заблокирован.</li>
 *   <li>Если контактов нет — на месте списка показывается синий
 *       info-баннер «Адресная книга пуста — добавьте контакт в разделе
 *       Контакты или укажите ключ напрямую ниже».</li>
 *   <li>Внизу — свернутая по умолчанию секция «Поделиться по ключу
 *       напрямую» (та же textarea, что была в день 13). Нужна для
 *       сценариев «контакт ещё не добавлен в книгу» и для тестирования.</li>
 *   <li>Под всем — жёлтый warn-баннер о том, что получатель сможет
 *       оставить себе локальную копию (как и в день 13).</li>
 * </ol>
 *
 * <p>Кнопка «Поделиться» активна, если выбран ≥1 контакт ИЛИ введён
 * валидный ключ в textarea. При клике диалог последовательно вызывает
 * {@code onShare(publicKey)} для каждого выбранного получателя.
 *
 * <p><b>Семантика {@code onShare}:</b> для совместимости с MyFiles
 * остаётся та же — {@code Consumer<String> publicKey}, который
 * вызывает {@code node.shareFile(txId, publicKey)}. Разница в том, что
 * теперь callback может быть вызван несколько раз — по одному на
 * каждого получателя из чекбоксов. После всех успешных вызовов диалог
 * закрывается; если хоть один упал, окно остаётся открытым с пометкой
 * об ошибке.
 */
public final class ShareFileDialog {

    private static final Logger log = LoggerFactory.getLogger(ShareFileDialog.class);

    private static final double INITIAL_WIDTH = 480;
    private static final double MIN_WIDTH = 440;
    private static final double MIN_HEIGHT = 440;

    /** Минимальная длина Base64-публичного ключа. См. {@link ContactStore#MIN_KEY_LENGTH}. */
    private static final int MIN_KEY_LENGTH = ContactStore.MIN_KEY_LENGTH;

    private final AppContext context;
    private final Transaction tx;
    private final Consumer<String> onShare;
    /** Колбек после ВСЕХ успешных шаров (для diaog.dismiss + reload UI). */
    private final Runnable onAllShared;

    private VBox bodyNode;
    private VBox footerNode;
    private TextArea keyArea;
    private Label selectedLabel;
    private Button shareBtn;
    private Stage window;

    /** Чекбоксы по контактам — нужны для подсчёта выбранных. */
    private final Map<Contact, CheckBox> contactCheckBoxes = new LinkedHashMap<>();

    /** Получатели, которым этот файл уже расшарен (по ACL в блокчейне). */
    private final Set<String> alreadySharedKeys = new HashSet<>();

    /**
     * @param context     контекст приложения
     * @param tx          UPLOAD-tx файла
     * @param onShare     колбек на каждого выбранного получателя:
     *                    {@code Consumer<String> publicKey}
     * @param onAllShared финальный колбек после успеха всех {@code onShare};
     *                    обычно — закрытие окна и обновление UI
     */
    public ShareFileDialog(AppContext context,
                           Transaction tx,
                           Consumer<String> onShare,
                           Runnable onAllShared) {
        this.context = Objects.requireNonNull(context, "context");
        this.tx = Objects.requireNonNull(tx, "tx");
        this.onShare = Objects.requireNonNull(onShare, "onShare");
        this.onAllShared = onAllShared != null ? onAllShared : () -> {};
        if (tx.getKind() != Transaction.Kind.UPLOAD) {
            throw new IllegalArgumentException(
                    "ShareFileDialog принимает только UPLOAD-tx, "
                            + "получено: " + tx.getKind());
        }
        precomputeAlreadyShared();
        build();
    }

    /**
     * Совместимый конструктор без {@code onAllShared} — для участков
     * кода, которые ещё не обновлены под новый API. Закрытие окна и
     * пересчёт списков они делают сами.
     *
     * @deprecated Используйте 4-параметровую версию.
     */
    @Deprecated
    public ShareFileDialog(AppContext context, Transaction tx, Consumer<String> onShare) {
        this(context, tx, onShare, null);
    }

    public Parent getRoot() {
        VBox card = new VBox(bodyNode, footerNode);
        card.getStyleClass().add("modal-card");
        return card;
    }

    /**
     * Считает, кому уже расшарен этот файл, чтобы заблокировать чекбоксы.
     * Это удобно: если файл уже доступен Бобу, в списке у него будет
     * пометка «уже расшарено», и пользователь не запутается, отправляя
     * один и тот же файл повторно.
     */
    private void precomputeAlreadyShared() {
        try {
            // Идём по всем транзакциям (UI-сторона: их обычно сотни,
            // в крайнем случае тысячи — приемлемо для разовой выборки).
            for (Transaction t : context.node().blockchain().allTransactions()) {
                if (t.getKind() != Transaction.Kind.ACL) continue;
                if (!tx.getId().equals(t.getReferencedTxId())) continue;
                if (t.getRecipientPublicKey() != null) {
                    alreadySharedKeys.add(t.getRecipientPublicKey());
                }
            }
        } catch (Exception e) {
            // Если что-то упало — продолжаем без отметок «уже расшарено».
            // Не критично, дубли просто будут переотправлены.
            log.debug("Не удалось получить уже выданные ACL: {}", e.toString());
        }
    }

    private void build() {
        // Заголовок
        Label title = new Label("Поделиться файлом");
        title.getStyleClass().add("modal-title");

        Label subtitle = new Label(tx.getFileName() + " · "
                + FormatUtils.humanSize(tx.getFileSize()));
        subtitle.getStyleClass().add("modal-subtitle");
        subtitle.setWrapText(true);

        VBox header = new VBox(6, title, subtitle);
        header.setPadding(new Insets(20, 20, 12, 20));

        // ----- Список контактов -----
        ContactStore store = context.contactStore();
        List<Contact> contacts = store != null ? store.all() : List.of();

        Label contactsLabel = new Label("ВЫБЕРИТЕ ПОЛУЧАТЕЛЕЙ");
        contactsLabel.getStyleClass().add("props-section-label");

        selectedLabel = new Label("");
        selectedLabel.getStyleClass().add("section-subtitle");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox contactsTopRow = new HBox(8, contactsLabel, spacer, selectedLabel);
        contactsTopRow.setAlignment(Pos.CENTER_LEFT);

        Region contactsContent = contacts.isEmpty()
                ? buildEmptyContactsHint()
                : buildContactsList(contacts);

        VBox contactsWrap = new VBox(8, contactsTopRow, contactsContent);
        contactsWrap.setPadding(new Insets(0, 20, 12, 20));

        // ----- Свернутая секция: «Или ключом напрямую» -----
        TitledPane manualPane = buildManualKeyPane(contacts.isEmpty());
        VBox manualWrap = new VBox(manualPane);
        manualWrap.setPadding(new Insets(0, 20, 12, 20));

        // ----- Жёлтый warn-баннер -----
        VBox warnWrap = new VBox(buildWarnBanner());
        warnWrap.setPadding(new Insets(0, 20, 14, 20));

        bodyNode = new VBox(header, contactsWrap, manualWrap, warnWrap);
        bodyNode.setFillWidth(true);

        // ----- Footer -----
        Region divider = new Region();
        divider.getStyleClass().add("modal-section-divider");

        Button cancel = new Button("Отмена");
        cancel.getStyleClass().add("btn-secondary");
        cancel.setOnAction(e -> dismissWindow());

        shareBtn = new Button("Поделиться");
        shareBtn.getStyleClass().add("btn-primary");
        shareBtn.setDefaultButton(true);
        shareBtn.setDisable(true);
        shareBtn.setOnAction(e -> trySubmit());

        Region spacer2 = new Region();
        HBox.setHgrow(spacer2, Priority.ALWAYS);

        HBox btnRow = new HBox(8, spacer2, cancel, shareBtn);
        btnRow.setAlignment(Pos.CENTER_RIGHT);
        btnRow.setPadding(new Insets(14, 20, 16, 20));

        footerNode = new VBox(divider, btnRow);

        refreshState();
    }

    /**
     * Список контактов с чекбоксами. Высота ограничена ScrollPane'ом,
     * чтобы при 50+ контактах диалог не разъезжался на весь экран.
     */
    private Region buildContactsList(List<Contact> contacts) {
        VBox list = new VBox(2);
        for (Contact c : contacts) {
            list.getChildren().add(buildContactRow(c));
        }

        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.getStyleClass().add("modal-scroll");
        // Ограничиваем высоту: примерно 6 строк по 52px + padding.
        scroll.setPrefViewportHeight(330);
        scroll.setMinViewportHeight(140);
        return scroll;
    }

    private HBox buildContactRow(Contact c) {
        boolean already = alreadySharedKeys.contains(c.getPublicKey());

        CheckBox cb = new CheckBox();
        cb.setDisable(already);
        cb.selectedProperty().addListener((obs, old, val) -> refreshState());
        contactCheckBoxes.put(c, cb);

        StackPane avatar = ContactAvatar.create(c.getName(), ContactAvatar.SIZE_LARGE);

        Label name = new Label(c.getName());
        name.getStyleClass().add("share-contact-name");

        Label keyLine = new Label(FormatUtils.shortenPubKey(c.getPublicKey()));
        keyLine.getStyleClass().add("share-contact-key");

        VBox texts = new VBox(2, name, keyLine);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(10, cb, avatar, texts, spacer);

        if (already) {
            Label tag = new Label("уже расшарено");
            tag.getStyleClass().add("share-contact-key");
            row.getChildren().add(tag);
        }

        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("share-contact-row");

        // Клик по строке тоже переключает чекбокс — типичный UX для
        // списка контактов. Disabled-строки не реагируют.
        if (!already) {
            row.setOnMouseClicked(e -> cb.setSelected(!cb.isSelected()));
        }

        return row;
    }

    /**
     * Подсказка вместо списка, когда контакты пусты. Не сама пустая
     * строка, а info-баннер: «адресная книга пуста, добавьте в Контакты
     * или используйте поле «По ключу» ниже».
     */
    private Region buildEmptyContactsHint() {
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
                "Адресная книга пуста. Добавьте контакты в разделе "
                        + "«Контакты» — потом будет удобнее. Или укажите "
                        + "публичный ключ получателя в поле ниже.");
        text.getStyleClass().add("info-banner-text");
        text.setWrapText(true);
        HBox.setHgrow(text, Priority.ALWAYS);

        HBox row = new HBox(10, icon, text);
        row.setAlignment(Pos.TOP_LEFT);
        HBox.setMargin(icon, new Insets(2, 0, 0, 0));
        row.getStyleClass().add("info-banner");
        return row;
    }

    /**
     * Свернутая секция «Поделиться по ключу напрямую». Если контактов
     * нет, открыта по умолчанию (это единственный путь). Если есть,
     * свёрнута — пользователь сначала видит привычный список.
     */
    private TitledPane buildManualKeyPane(boolean expandByDefault) {
        keyArea = new TextArea();
        keyArea.setPromptText("MIIBIjANBgkqhkiG9w0BAQEFAAOC…");
        keyArea.setWrapText(true);
        keyArea.setPrefRowCount(4);
        keyArea.getStyleClass().add("share-paste-area");
        keyArea.textProperty().addListener((obs, old, val) -> refreshState());

        Button pasteBtn = new Button("Вставить из буфера");
        pasteBtn.getStyleClass().add("btn-secondary");
        pasteBtn.setOnAction(e -> {
            String s = Clipboard.getSystemClipboard().getString();
            if (s != null) {
                keyArea.setText(s.trim());
                keyArea.requestFocus();
                keyArea.positionCaret(keyArea.getText().length());
            }
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label hint = new Label("Используйте, если получателя ещё нет в адресной книге.");
        hint.getStyleClass().add("section-subtitle");
        hint.setWrapText(true);

        HBox topRow = new HBox(8, hint, spacer, pasteBtn);
        topRow.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(8, topRow, keyArea);
        content.setPadding(new Insets(8, 0, 0, 0));

        TitledPane pane = new TitledPane("Поделиться по ключу напрямую", content);
        pane.setExpanded(expandByDefault);
        pane.setCollapsible(true);
        // Снимаем focus-кольцо у заголовка — выглядит аккуратнее в нашей теме.
        pane.setFocusTraversable(false);
        return pane;
    }

    /** Жёлтый warn-баннер про невозможность отозвать локальные копии. */
    private HBox buildWarnBanner() {
        Label icon = new Label("!");
        icon.setStyle(
                "-fx-min-width: 16; -fx-min-height: 16;"
                        + "-fx-pref-width: 16; -fx-pref-height: 16;"
                        + "-fx-max-width: 16; -fx-max-height: 16;"
                        + "-fx-background-color: -color-warning;"
                        + "-fx-text-fill: white;"
                        + "-fx-background-radius: 8;"
                        + "-fx-alignment: center;"
                        + "-fx-font-size: 11px; -fx-font-weight: 700;"
        );

        Label text = new Label(
                "Получатель сможет скачать файл и оставить себе локальную копию. "
                        + "Отзыв доступа в блокчейне (DELETE-запись) не удалит уже "
                        + "скачанные копии — это фундаментальное ограничение любой "
                        + "файловой системы с обменом.");
        text.getStyleClass().add("info-banner-text");
        text.setWrapText(true);
        HBox.setHgrow(text, Priority.ALWAYS);

        HBox row = new HBox(10, icon, text);
        row.setAlignment(Pos.TOP_LEFT);
        HBox.setMargin(icon, new Insets(2, 0, 0, 0));
        row.getStyleClass().addAll("info-banner", "info-banner--warn");
        return row;
    }

    // ------------------------------------------------------------------
    // Состояние
    // ------------------------------------------------------------------

    /**
     * Собирает уникальный набор получателей: выбранные контакты + ключ
     * из textarea (если он валиден).
     */
    private List<String> collectRecipients() {
        Set<String> uniq = new java.util.LinkedHashSet<>();
        for (Map.Entry<Contact, CheckBox> e : contactCheckBoxes.entrySet()) {
            if (e.getValue().isSelected()) {
                uniq.add(e.getKey().getPublicKey());
            }
        }
        String manual = sanitizedManualKey();
        if (manual != null) {
            uniq.add(manual);
        }
        return new ArrayList<>(uniq);
    }

    /**
     * Кнопка «Поделиться» активна, когда выбран ≥1 контакт ИЛИ введён
     * валидный ключ. Одновременно обновляет счётчик «Выбрано N».
     */
    private void refreshState() {
        int contactsCount = 0;
        for (CheckBox cb : contactCheckBoxes.values()) {
            if (cb.isSelected()) contactsCount++;
        }
        boolean manualOk = sanitizedManualKey() != null;
        int total = contactsCount + (manualOk ? 1 : 0);

        if (total == 0) {
            selectedLabel.setText("");
        } else {
            selectedLabel.setText("Выбрано " + total);
        }
        shareBtn.setDisable(total == 0);
    }

    /** Очищенный ключ из textarea (или null, если ввод невалиден). */
    private String sanitizedManualKey() {
        if (keyArea == null || keyArea.getText() == null) return null;
        String s = keyArea.getText().replaceAll("\\s+", "");
        if (s.length() < MIN_KEY_LENGTH) return null;
        if (!s.matches("^[A-Za-z0-9+/=]+$")) return null;
        return s;
    }

    /**
     * Расшаривает файл всем выбранным получателям. Делает это
     * последовательно: если кто-то упал, останавливаемся, чтобы
     * пользователь увидел ошибку и решил что делать. Уже успешно
     * расшаренные блокируем (visual feedback в чекбоксе).
     */
    private void trySubmit() {
        List<String> recipients = collectRecipients();
        if (recipients.isEmpty()) return;

        // Самопроверка: не расшариваем сам себе
        String selfId = context.node().selfNodeId();
        if (recipients.contains(selfId)) {
            Alert a = new Alert(Alert.AlertType.WARNING);
            a.initOwner(window);
            a.setHeaderText("Это ваш собственный публичный ключ");
            a.setContentText(
                    "Один из выбранных получателей — вы сами. "
                            + "Снимите его и попробуйте снова.");
            a.showAndWait();
            return;
        }

        shareBtn.setDisable(true);
        shareBtn.setText("Шарим…");
        try {
            for (String key : recipients) {
                onShare.accept(key);
                // Если onShare кидает исключение, оно пробросится наружу.
                // Если нет — считаем успехом и двигаемся дальше.
            }
            log.info("Все {} получателей успешно обработаны", recipients.size());
            onAllShared.run();
            dismissWindow();
        } catch (RuntimeException ex) {
            // onShare сам показывает Alert через MyFiles, но кнопка
            // «Поделиться» должна снова стать активной, если user
            // решит попробовать ещё раз.
            shareBtn.setText("Поделиться");
            refreshState();
            log.warn("Сбой при расшаривании", ex);
        }
    }

    // ------------------------------------------------------------------
    // Окно
    // ------------------------------------------------------------------

    public void showAsWindow(Stage ownerStage) {
        Stage stage = new Stage();
        stage.initOwner(ownerStage);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.initStyle(StageStyle.UNIFIED);
        stage.setTitle("Поделиться файлом");
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
