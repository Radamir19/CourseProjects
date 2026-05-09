package ru.hse.jblockstorage.gui;

import javafx.application.Application;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Единая точка входа JBlockStorage.
 *
 * <p>Поведение:
 * <ul>
 *   <li>{@code java -jar JBlockStorage.jar} (без аргументов) — запуск JavaFX GUI</li>
 *   <li>{@code java -jar JBlockStorage.jar generate-keys --output=...} — старый CLI</li>
 * </ul>
 *
 * <p>В диагностической сборке устанавливаем глобальный uncaught-exception
 * handler — он перехватит любую нежданку в JavaFX Application Thread
 * и фоновых тредах, иначе JavaFX молча их проглатывает и поведение
 * становится «UI завис без объяснений».
 */
public final class Launcher {

    private static final Logger log = LoggerFactory.getLogger(Launcher.class);

    private Launcher() {}

    public static void main(String[] args) {
        // Глобальный перехватчик — увидим в stderr любые exception'ы
        // из FX-thread (которые JavaFX обычно глотает).
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            System.err.println(">>> UNCAUGHT in thread '" + t.getName() + "':");
            e.printStackTrace(System.err);
        });

        if (args.length == 0) {
            log.info("Запуск JBlockStorage в режиме GUI");
            Application.launch(JBlockStorageApp.class, args);
        } else {
            log.info("Запуск JBlockStorage в режиме CLI с {} аргументом(ами)", args.length);
            ru.hse.jblockstorage.cli.Main.main(args);
        }
    }
}
