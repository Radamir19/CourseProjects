package ru.hse.jblockstorage.gui.download;

import javafx.concurrent.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.hse.jblockstorage.app.DownloadProgressListener;
import ru.hse.jblockstorage.app.NodeApplication;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Оркестратор скачивания одного файла — день 15. Аналог
 * {@code UploadController}, но проще: backend честно репортит реальный
 * прогресс через {@link DownloadProgressListener}, нам не надо
 * имитировать этапы Timeline'ом.
 *
 * <p><b>Жизненный цикл:</b>
 * <ol>
 *   <li>Создание ({@code new DownloadController(...)}) с готовым
 *       {@link DownloadProgressState}, в который пойдёт прогресс.</li>
 *   <li>{@link #start(String, Path, Runnable, java.util.function.Consumer)}
 *       — кикает фоновый {@link Task}. Задача синхронно зовёт
 *       {@link NodeApplication#downloadFile(String, Path, DownloadProgressListener)},
 *       наш listener мостит события в state через
 *       {@code Platform.runLater} (это уже внутри {@link DownloadProgressState}).</li>
 *   <li>На onSucceeded — state.complete(); на onFailed — state.fail().</li>
 *   <li>UI наблюдает за state и показывает диалог.</li>
 * </ol>
 */
public final class DownloadController {

    private static final Logger log = LoggerFactory.getLogger(DownloadController.class);

    private final NodeApplication node;
    private final DownloadProgressState state;
    private Task<Void> currentTask;

    public DownloadController(NodeApplication node, DownloadProgressState state) {
        this.node = Objects.requireNonNull(node, "node");
        this.state = Objects.requireNonNull(state, "state");
    }

    public DownloadProgressState state() {
        return state;
    }

    /**
     * Запускает скачивание в фоне.
     *
     * @param txId         идентификатор UPLOAD-транзакции
     * @param outputFile   целевой файл (будет перезаписан)
     * @param onSuccess    UI-колбек при успехе (выполнится на FX-thread)
     * @param onFailure    UI-колбек при ошибке (выполнится на FX-thread,
     *                     получает причину)
     */
    public void start(String txId, Path outputFile,
                      Runnable onSuccess,
                      java.util.function.Consumer<Throwable> onFailure) {
        Objects.requireNonNull(txId, "txId");
        Objects.requireNonNull(outputFile, "outputFile");
        if (currentTask != null && currentTask.isRunning()) {
            throw new IllegalStateException(
                    "Скачивание уже идёт — дождитесь завершения.");
        }

        // Сразу выставляем состояние «идёт скачивание». Имя/размер/шарды
        // придут из onStart, но фоновому Task'у нужно несколько ms на старт.
        state.start("Скачивание…", 0, 0, outputFile.toString());

        DownloadProgressListener listener = new DownloadProgressListener() {
            @Override
            public void onStart(String fileName, long fileSize, int totalShards) {
                state.start(fileName, fileSize, totalShards, outputFile.toString());
            }

            @Override
            public void onShardFinished(int shardIndex, String storerId, long bytes) {
                state.recordShard(shardIndex, storerId, bytes);
            }
            // onShardStarted/onShardFailed/onFailed — не репортим в state
            // отдельно, итоговое success/failure обрабатываем в Task ниже.
        };

        currentTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                node.downloadFile(txId, outputFile, listener);
                return null;
            }
        };

        currentTask.setOnSucceeded(e -> {
            log.info("Download OK: {} → {}", txId, outputFile);
            state.complete(outputFile.toString());
            if (onSuccess != null) onSuccess.run();
        });

        currentTask.setOnFailed(e -> {
            Throwable ex = currentTask.getException();
            log.warn("Download failed", ex);
            state.fail(explain(ex));
            if (onFailure != null) onFailure.accept(ex);
        });

        Thread t = new Thread(currentTask, "download-" + txId.substring(0,
                Math.min(8, txId.length())));
        t.setDaemon(true);
        t.start();
    }

    private static String explain(Throwable t) {
        if (t == null) return "Неизвестная ошибка";
        if (t.getMessage() != null && !t.getMessage().isBlank()) return t.getMessage();
        return t.getClass().getSimpleName();
    }
}
