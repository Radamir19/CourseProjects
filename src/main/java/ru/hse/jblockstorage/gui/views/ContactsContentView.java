package ru.hse.jblockstorage.gui.views;

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
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.hse.jblockstorage.gui.AppContext;
import ru.hse.jblockstorage.gui.components.ContactAvatar;
import ru.hse.jblockstorage.gui.components.FormatUtils;
import ru.hse.jblockstorage.gui.contacts.Contact;
import ru.hse.jblockstorage.gui.contacts.ContactStore;
import ru.hse.jblockstorage.gui.dialogs.AddContactDialog;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntConsumer;

/**
 * Раздел «Контакты» главного экрана — день 14.
 *
 * <p>Адресная книга текущего профиля. По мокапу
 * {@code dashboard_contacts.html}: шапка с заголовком и кнопкой
 * «+ Добавить контакт», ниже список карточек-контактов
 * (avatar 36×36 + имя + ключ + меню действий).
 *
 * <p>Действия из контекстного меню: «Копировать ключ»,
 * «Переименовать», «Удалить». Кнопки крупных действий «Поделиться»
 * специально нет — она живёт на уровне файла (контекстное меню в
 * «Мои файлы»), а не на уровне контакта.
 *
 * <p>Empty state — большая иконка, объяснение зачем нужны контакты,
 * и кнопка «+ Добавить контакт» по центру.
 *
 * <p>Если {@link AppContext#contactStore()} вдруг {@code null} (битый
 * {@code contacts.json}) — показываем сообщение об ошибке вместо
 * списка. Без этого пользователь увидел бы пустой экран и не понял
 * что не так.
 */
public final class ContactsContentView {

    private static final Logger log = LoggerFactory.getLogger(ContactsContentView.class);

    private final AppContext context;
    private final Parent root;
    private final VBox listContainer = new VBox();
    private final Label headerSubtitle = new Label();

    private int totalCount;
    /** Внешний наблюдатель за изменением числа контактов. */
    private IntConsumer onCountChanged;

    public ContactsContentView(AppContext context) {
        this.context = Objects.requireNonNull(context, "context");
        if (!context.isAuthenticated()) {
            throw new IllegalStateException("ContactsContentView требует активную сессию");
        }
        this.root = build();
        reload();
    }

    public Parent getRoot() {
        return root;
    }

    public int getCount() {
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

        ScrollPane scroll = new ScrollPane(listContainer);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("app-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox content = new VBox(header, scroll);
        content.getStyleClass().add("app-background");
        return content;
    }

    private Parent buildHeader() {
        Label title = new Label("Контакты");
        title.getStyleClass().add("section-title");

        headerSubtitle.getStyleClass().add("section-subtitle");

        VBox titles = new VBox(4, title, headerSubtitle);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button addBtn = new Button("+ Добавить контакт");
        addBtn.getStyleClass().add("btn-primary");
        addBtn.setOnAction(e -> onAddContact());

        HBox row = new HBox(16, titles, spacer, addBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("section-header");
        return row;
    }

    // ------------------------------------------------------------------
    // Reload
    // ------------------------------------------------------------------

    /** Перечитывает адресную книгу и перерисовывает UI. */
    public void reload() {
        ContactStore store = context.contactStore();
        if (store == null) {
            // Адресная книга недоступна — показываем явное сообщение.
            renderUnavailable();
            return;
        }

        List<Contact> contacts = store.all();
        totalCount = contacts.size();

        if (totalCount == 0) {
            headerSubtitle.setText("Здесь живёт ваша адресная книга");
        } else {
            headerSubtitle.setText(totalCount + " " + FormatUtils.pluralContacts(totalCount));
        }

        listContainer.getChildren().clear();
        if (contacts.isEmpty()) {
            listContainer.getChildren().add(buildEmptyState());
        } else {
            for (Contact c : contacts) {
                listContainer.getChildren().add(buildRow(c));
            }
        }

        if (onCountChanged != null) onCountChanged.accept(totalCount);
    }

    /** Сообщение в случае, когда адресная книга не открылась. */
    private void renderUnavailable() {
        headerSubtitle.setText("Адресная книга недоступна");
        listContainer.getChildren().clear();

        Label icon = new Label("⚠");
        icon.setStyle("-fx-font-size: 36px;");
        StackPane iconWrap = new StackPane(icon);
        iconWrap.getStyleClass().add("empty-state-icon-bg");
        iconWrap.setMinSize(72, 72);
        iconWrap.setMaxSize(72, 72);

        Label title = new Label("Не удалось открыть адресную книгу");
        title.getStyleClass().add("empty-state-title");

        Label text = new Label(
                "Файл contacts.json в папке профиля недоступен или повреждён. "
                        + "Проверьте права доступа и логи приложения, либо удалите "
                        + "файл — он будет создан заново при следующем добавлении.");
        text.getStyleClass().add("empty-state-text");
        text.setWrapText(true);
        text.setMaxWidth(420);

        VBox box = new VBox(14, iconWrap, title, text);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(48, 24, 48, 24));
        StackPane wrap = new StackPane(box);
        wrap.setPadding(new Insets(0, 28, 28, 28));
        wrap.setAlignment(Pos.TOP_CENTER);
        listContainer.getChildren().add(wrap);
    }

    // ------------------------------------------------------------------
    // Row
    // ------------------------------------------------------------------

    /** Одна строка контакта: avatar + имя/ключ + меню действий. */
    private HBox buildRow(Contact c) {
        StackPane avatar = ContactAvatar.create(c.getName(), ContactAvatar.SIZE_LARGE);

        Label name = new Label(c.getName());
        name.getStyleClass().add("props-storer-name");

        Label keyLine = new Label(FormatUtils.shortenPubKey(c.getPublicKey()));
        keyLine.getStyleClass().add("props-storer-key");

        VBox texts = new VBox(2, name, keyLine);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label addedAt = new Label(FormatUtils.relativeDate(c.getAddedAt()));
        addedAt.getStyleClass().add("files-row-meta");

        Button menuBtn = new Button("⋯");
        menuBtn.getStyleClass().add("files-row-action-btn");
        ContextMenu menu = buildContextMenu(c);
        menuBtn.setOnAction(e ->
                menu.show(menuBtn,
                        menuBtn.localToScreen(0, menuBtn.getHeight()).getX(),
                        menuBtn.localToScreen(0, menuBtn.getHeight()).getY()));

        HBox row = new HBox(12, avatar, texts, spacer, addedAt, menuBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("props-storer-row");
        // Правый клик по строке — тоже показывает меню (как в файлах).
        row.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.SECONDARY) {
                menu.show(row, e.getScreenX(), e.getScreenY());
            }
        });
        return row;
    }

    private ContextMenu buildContextMenu(Contact c) {
        MenuItem copyKey = new MenuItem("Копировать ключ");
        copyKey.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(c.getPublicKey());
            Clipboard.getSystemClipboard().setContent(cc);
            log.debug("Скопирован ключ контакта «{}»", c.getName());
        });

        MenuItem rename = new MenuItem("Переименовать…");
        rename.setOnAction(e -> onRename(c));

        MenuItem delete = new MenuItem("Удалить");
        delete.setOnAction(e -> onDelete(c));

        ContextMenu menu = new ContextMenu(copyKey, rename, delete);
        menu.getStyleClass().add("context-menu");
        return menu;
    }

    /** Empty state — иконка + объяснение + кнопка добавления. */
    private Parent buildEmptyState() {
        Label icon = new Label("👥");
        icon.setStyle("-fx-font-size: 36px;");
        StackPane iconWrap = new StackPane(icon);
        iconWrap.getStyleClass().add("empty-state-icon-bg");
        iconWrap.setMinSize(72, 72);
        iconWrap.setMaxSize(72, 72);

        Label title = new Label("Адресная книга пока пуста");
        title.getStyleClass().add("empty-state-title");

        Label text = new Label(
                "Добавьте контакт — это сохранит публичный ключ другого "
                        + "пользователя под удобным именем. Когда захотите "
                        + "поделиться файлом, вы сможете выбрать получателя "
                        + "из списка вместо ручного копирования ключа.");
        text.getStyleClass().add("empty-state-text");
        text.setWrapText(true);
        text.setMaxWidth(420);

        Button addBtn = new Button("+ Добавить контакт");
        addBtn.getStyleClass().add("btn-primary");
        addBtn.setOnAction(e -> onAddContact());

        VBox box = new VBox(14, iconWrap, title, text, addBtn);
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

    private void onAddContact() {
        if (context.contactStore() == null) return;
        AddContactDialog dlg = new AddContactDialog(context, c -> reload());
        dlg.showAsWindow(context.router().stage());
    }

    private void onRename(Contact c) {
        TextInputDialog dlg = new TextInputDialog(c.getName());
        dlg.initOwner(context.router().stage());
        dlg.setHeaderText("Переименовать контакт");
        dlg.setContentText("Новое имя:");
        Optional<String> result = dlg.showAndWait();
        if (result.isEmpty()) return;
        String newName = result.get().trim();
        if (newName.isEmpty() || newName.equals(c.getName())) return;
        try {
            context.contactStore().rename(c.getPublicKey(), newName);
            reload();
        } catch (Exception e) {
            log.warn("Не удалось переименовать", e);
            showError("Не удалось переименовать", explain(e));
        }
    }

    private void onDelete(Contact c) {
        Alert confirm = new Alert(AlertType.CONFIRMATION);
        confirm.initOwner(context.router().stage());
        confirm.setHeaderText("Удалить контакт «" + c.getName() + "»?");
        confirm.setContentText(
                "Контакт будет удалён только из вашей адресной книги. "
                        + "Файлы, которые вы уже расшарили этому пользователю "
                        + "по ACL-записям, останутся доступными ему.");
        ButtonType del = new ButtonType("Удалить", ButtonType.OK.getButtonData());
        ButtonType cancel = new ButtonType("Отмена", ButtonType.CANCEL.getButtonData());
        confirm.getButtonTypes().setAll(del, cancel);

        Optional<ButtonType> r = confirm.showAndWait();
        if (r.isEmpty() || r.get() != del) return;

        try {
            context.contactStore().removeByKey(c.getPublicKey());
            reload();
        } catch (Exception e) {
            log.warn("Не удалось удалить контакт", e);
            showError("Не удалось удалить", explain(e));
        }
    }

    // ------------------------------------------------------------------
    // Утилиты
    // ------------------------------------------------------------------

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
