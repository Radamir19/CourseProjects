package ru.hse.jblockstorage.gui;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.hse.jblockstorage.gui.config.NodeConfigStore;
import ru.hse.jblockstorage.gui.theme.ThemeManager;
import ru.hse.jblockstorage.gui.views.WelcomeView;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Основное приложение JBlockStorage GUI.
 *
 * <p>Жизненный цикл:
 * <ol>
 *   <li>{@link #start} создаёт {@link ThemeManager} и {@link SceneRouter}</li>
 *   <li>На сцену устанавливается базовая корневая нода ({@link StackPane})</li>
 *   <li>Темы применяются к сцене (стандарт: системная)</li>
 *   <li>Через {@link SceneRouter#show} грузится {@link WelcomeView} —
 *       первый экран onboarding'а</li>
 * </ol>
 *
 * <p>Запускать через {@link Launcher}. Вручную можно:
 * {@code Application.launch(JBlockStorageApp.class, args);}
 */
public final class JBlockStorageApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(JBlockStorageApp.class);

    /** Минимальный размер окна — диктуется дизайном (sidebar 240px + контент). */
    public static final double MIN_WIDTH = 900;
    public static final double MIN_HEIGHT = 600;

    private ThemeManager themeManager;
    private SceneRouter router;
    private AppContext appContext;

    @Override
    public void start(Stage primaryStage) {
        log.info("JBlockStorage GUI: старт приложения");

        // 1. Корневой контейнер сцены. StackPane выбран намеренно: он легко
        //    позволяет в следующих днях положить overlay (модальный диалог)
        //    поверх основного содержимого.
        StackPane sceneRoot = new StackPane();
        Scene scene = new Scene(sceneRoot, MIN_WIDTH, MIN_HEIGHT);

        // 2. Темы. ThemeManager сам подключит base.css + light/dark по выбранной теме.
        //    SYSTEM по умолчанию — будет следовать за настройками ОС.
        themeManager = new ThemeManager();
        themeManager.applyTo(scene);
        themeManager.themeProperty().addListener((obs, old, val) -> {
            log.info("Тема изменена: {} -> {}", old, val);
            themeManager.applyTo(scene);
        });

        // 3. Роутер навигации + единый AppContext.
        router = new SceneRouter(primaryStage, scene);
        OverlayHost overlay = new OverlayHost(sceneRoot);
        appContext = new AppContext(router, themeManager, overlay);

        // 3.1. Загружаем конфиг приложения <JBS_HOME>/config.properties
        //      (день 15, ТЗ п. 4.1.1.1.1/4.1.3.2). Делаем это синхронно
        //      и до показа Welcome — UI должен сразу знать, есть ли seeds.
        NodeConfigStore configStore = NodeConfigStore.load(
                appJbsHome().resolve("config.properties"));
        appContext.setConfigStore(configStore);

        // 4. Стартовый экран — Welcome (ТЗ п. 4.1.5.1).
        WelcomeView welcome = new WelcomeView(appContext);
        router.show(welcome.getRoot(), "JBlockStorage — Добро пожаловать");

        // 5. Параметры окна.
        primaryStage.setMinWidth(MIN_WIDTH);
        primaryStage.setMinHeight(MIN_HEIGHT);
        primaryStage.show();

        log.info("JBlockStorage GUI: окно отображено ({}x{}, тема: {})",
                (int) MIN_WIDTH, (int) MIN_HEIGHT, themeManager.getResolvedTheme());
    }

    @Override
    public void stop() {
        log.info("JBlockStorage GUI: завершение приложения");
        // Если пользователь закрыл окно при активной сессии — корректно
        // закрываем узел (RocksDB sync, Netty shutdown, peers.json save).
        if (appContext != null) {
            appContext.logout();
        }
    }

    /** Доступ для тестов и для прямого запуска (минуя Launcher). */
    public static void main(String[] args) {
        Application.launch(JBlockStorageApp.class, args);
    }

    /**
     * Та же логика, что в {@code LoginView.jbsHome()} — root-папка
     * приложения. Дублируем намеренно: это early-init code, до показа
     * первого экрана; не хочется тащить статическую зависимость на
     * LoginView.
     */
    private static Path appJbsHome() {
        String env = System.getenv("JBS_HOME");
        if (env != null && !env.isBlank()) {
            return Paths.get(env);
        }
        return Paths.get(System.getProperty("user.home"), ".jblockstorage");
    }
}
