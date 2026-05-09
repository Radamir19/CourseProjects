package ru.hse.jblockstorage.gui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.hse.jblockstorage.app.NodeApplication;
import ru.hse.jblockstorage.gui.config.NodeConfigStore;
import ru.hse.jblockstorage.gui.contacts.ContactStore;
import ru.hse.jblockstorage.gui.theme.ThemeManager;
import ru.hse.jblockstorage.gui.upload.UploadProgressState;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Единое состояние GUI-приложения.
 *
 * <p>До успешного Login: содержит только {@link SceneRouter},
 * {@link ThemeManager}, {@link OverlayHost} и пустой
 * {@link UploadProgressState}.
 * После успешного Login: дополнительно содержит запущенный
 * {@link NodeApplication} (открыт RocksDB, поднят Netty-сервер),
 * имя активного профиля, путь к данным профиля и {@link ContactStore}
 * адресной книги.
 *
 * <p>Передаётся во все view-классы вместо отдельных параметров — это
 * избавляет нас от прокидывания трёх-четырёх ссылок через каждый
 * конструктор и даёт удобный единый способ logout: закрыть узел и
 * вернуться на Welcome.
 *
 * <p><b>UploadProgressState</b> создаётся одной штукой на всё
 * приложение: и компактный блок в sidebar, и большая карточка в
 * «Мои файлы» наблюдают за этим единым состоянием. Так мы получаем
 * корректную синхронизацию двух UI-проекций без дополнительной шины
 * событий.
 *
 * <p><b>ContactStore</b> (день 14) живёт в
 * {@code <JBS_HOME>/data/<profile>/contacts.json}. Создаётся в
 * {@link #setSession} вместе с привязкой к профилю и закрывается при
 * {@link #logout} автоматически (он stateless после save). Нужен
 * {@code ShareFileDialog}, {@code ContactsContentView} и
 * {@code SharedFilesContentView} (для отображения «От кого» — если
 * адрес есть в книге, показываем имя вместо короткого ключа).
 *
 * <p>Класс не thread-safe — все мутации делаются с UI-thread в одном
 * месте (LoginView / DashboardView). Если в будущем понадобится
 * фоновое обновление статуса — вынесем NodeApplication в отдельный
 * actor с подпиской.
 */
public final class AppContext {

    private static final Logger log = LoggerFactory.getLogger(AppContext.class);

    private final SceneRouter router;
    private final ThemeManager themeManager;
    private final OverlayHost overlay;
    private final UploadProgressState uploadState;

    /**
     * Конфиг приложения уровня {@code <JBS_HOME>/config.properties}. День
     * 15: ТЗ п. 4.1.1.1.1/4.1.3.2 — список seed-узлов и опциональные
     * override'ы порта/replication живут здесь. Создаётся один раз при
     * старте приложения и шарится между экранами.
     *
     * <p>Может быть {@code null}, если {@link #setConfigStore} не был
     * вызван (например, в старых тестах). В таком случае LoginView
     * откатывается на чистые env-переменные.
     */
    private NodeConfigStore configStore;

    /** {@code null} до Login. */
    private NodeApplication node;
    /** {@code null} до Login. Имя профиля для отображения в UI. */
    private String profileName;
    /** {@code null} до Login. Корневая папка данных текущего профиля. */
    private Path profileDataDir;
    /** {@code null} до Login. Адресная книга текущего профиля. */
    private ContactStore contactStore;

    public AppContext(SceneRouter router, ThemeManager themeManager, OverlayHost overlay) {
        this.router = Objects.requireNonNull(router, "router");
        this.themeManager = Objects.requireNonNull(themeManager, "themeManager");
        this.overlay = Objects.requireNonNull(overlay, "overlay");
        this.uploadState = new UploadProgressState();
    }

    public SceneRouter router() {
        return router;
    }

    public ThemeManager themeManager() {
        return themeManager;
    }

    /** Хост для модальных оверлеев (диалогов поверх Dashboard). */
    public OverlayHost overlay() {
        return overlay;
    }

    /** Глобальное состояние текущей загрузки (наблюдают sidebar + content). */
    public UploadProgressState uploadState() {
        return uploadState;
    }

    /** {@code null} до Login. После Login — запущенный узел. */
    public NodeApplication node() {
        return node;
    }

    /** {@code null} до Login. */
    public String profileName() {
        return profileName;
    }

    /**
     * Корневая папка данных текущего профиля
     * ({@code <JBS_HOME>/data/<profile>/}). Внутри лежат
     * {@code shards/}, {@code blockchain/}, {@code peers.json},
     * {@code contacts.json}. {@code null} до Login.
     */
    public Path profileDataDir() {
        return profileDataDir;
    }

    /**
     * Адресная книга текущего профиля. {@code null} до Login или если
     * {@code contacts.json} не удалось открыть. Используется в
     * Settings/Contacts/Share.
     */
    public ContactStore contactStore() {
        return contactStore;
    }

    /**
     * Конфиг приложения ({@code <JBS_HOME>/config.properties}). Может
     * быть {@code null} в старых сценариях, где {@link #setConfigStore}
     * не вызывался. UI должен это учитывать.
     */
    public NodeConfigStore configStore() {
        return configStore;
    }

    /**
     * Регистрирует конфиг приложения. Вызывается в {@code Application#start}
     * до отображения первого экрана.
     */
    public void setConfigStore(NodeConfigStore store) {
        this.configStore = Objects.requireNonNull(store, "configStore");
    }

    public boolean isAuthenticated() {
        return node != null;
    }

    /**
     * Регистрирует активную сессию — узел запущен, профиль выбран.
     * Можно вызвать только если {@link #isAuthenticated()} false.
     *
     * @param node           запущенный узел
     * @param profileName    отображаемое имя профиля
     * @param profileDataDir корневая папка данных профиля (см.
     *                       {@link #profileDataDir()}); внутри будет
     *                       создан/прочитан {@code contacts.json}
     */
    public void setSession(NodeApplication node, String profileName, Path profileDataDir) {
        if (this.node != null) {
            throw new IllegalStateException(
                    "Сессия уже активна. Сначала вызовите logout().");
        }
        this.node = Objects.requireNonNull(node, "node");
        this.profileName = Objects.requireNonNull(profileName, "profileName");
        this.profileDataDir = Objects.requireNonNull(profileDataDir, "profileDataDir");

        // Адресная книга — отдельный файл рядом с peers.json
        try {
            this.contactStore = new ContactStore(profileDataDir.resolve("contacts.json"));
        } catch (Exception e) {
            // Не падаем целиком, если адресная книга не открылась —
            // основной функционал (загрузки/скачивания) работает и без неё.
            log.warn("Не удалось открыть contacts.json: {}. Адресная книга недоступна.",
                    e.toString());
            this.contactStore = null;
        }

        log.info("Сессия открыта: профиль «{}», nodeId={}, port={}",
                profileName, node.selfNodeId(), node.listenPort());
    }

    /**
     * Совместимый перегруженный {@link #setSession(NodeApplication, String, Path)}
     * без явного {@code profileDataDir} — для тестов и легаси кода. В
     * этом режиме {@link #contactStore()} и {@link #profileDataDir()}
     * вернут {@code null}, и адресная книга будет недоступна.
     *
     * @deprecated Используйте трёхпараметровую версию, указывая папку
     * данных профиля. Этот вариант существует только для совместимости
     * с тестами, написанными до дня 14.
     */
    @Deprecated
    public void setSession(NodeApplication node, String profileName) {
        if (this.node != null) {
            throw new IllegalStateException(
                    "Сессия уже активна. Сначала вызовите logout().");
        }
        this.node = Objects.requireNonNull(node, "node");
        this.profileName = Objects.requireNonNull(profileName, "profileName");
        this.profileDataDir = null;
        this.contactStore = null;
        log.info("Сессия открыта (без dataDir): профиль «{}», nodeId={}, port={}",
                profileName, node.selfNodeId(), node.listenPort());
    }

    /**
     * Закрывает активную сессию: останавливает узел (RocksDB + Netty),
     * сбрасывает имя профиля. Идемпотентен — можно звать когда сессия
     * не открыта (например, на shutdown приложения).
     */
    public void logout() {
        if (node == null) return;
        log.info("Закрываем сессию профиля «{}»", profileName);
        try {
            node.close();
        } catch (Exception e) {
            log.warn("Ошибка при закрытии узла", e);
        }
        node = null;
        profileName = null;
        profileDataDir = null;
        contactStore = null;
        // На всякий случай сбрасываем прогресс — если кто-то закрыл окно
        // прямо во время загрузки.
        try { uploadState.reset(); } catch (Exception ignored) {}
    }
}
