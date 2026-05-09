package ru.hse.jblockstorage.gui.download;

import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Observable-модель прогресса <b>скачивания</b> файла — день 15.
 * Аналог {@code UploadProgressState}, но реализующий ТЗ п. 4.1.5.3
 * («*Отображение скорости передачи данных и списка узлов, с которых
 * идет скачивание*») с реальными данными — не имитацией, как у Upload.
 *
 * <p>На неё подписан только один потребитель UI — модальный
 * {@code DownloadProgressDialog}. Не интегрировано в sidebar намеренно:
 * скачивание — обычно разовая операция, для неё лучше подходит модальное
 * окно «нажал → прогресс → готово», а не глобальный плавающий индикатор.
 *
 * <p><b>Источники данных:</b>
 * <ul>
 *   <li>{@code progress}, {@code currentShard}, {@code totalShards} —
 *       триггерятся колбеками {@code DownloadProgressListener} из
 *       {@code FileDownloader} (см. {@link DownloadController}).</li>
 *   <li>{@code speedMbps} — rolling average по последним
 *       {@link #SPEED_WINDOW_MILLIS} мс. Считается в
 *       {@link #recordShard}, который вызывается после каждого
 *       успешного шарда.</li>
 *   <li>{@code storers} — список «по узлу X получено N шардов / M байт»,
 *       инкрементируется на каждом успешном шарде. Используется UI для
 *       блока «С узлов:».</li>
 * </ul>
 *
 * <p><b>Threading:</b> публичные методы безопасны вне UI-thread —
 * сами оборачивают мутации в {@code Platform.runLater}. Это
 * принципиальное отличие от {@code UploadProgressState}, где требовался
 * UI-thread у вызывающего: backend listener живёт в фоновом потоке
 * Task'а, и проксировать через runLater удобнее в одном месте.
 */
public final class DownloadProgressState {

    /** Окно для расчёта скорости (мс). Чем больше — тем стабильнее, но менее «живо». */
    private static final long SPEED_WINDOW_MILLIS = 2_000;

    /** Этапы скачивания. По сравнению с Upload — проще: только три состояния. */
    public enum Stage {
        /** Прогресса нет, диалог скрыт. */
        IDLE,
        /** Идёт скачивание шардов / расшифровка. */
        DOWNLOADING,
        /** Скачано и сохранено. */
        DONE,
        /** Сбой. */
        FAILED
    }

    private final ObjectProperty<Stage> stage =
            new SimpleObjectProperty<>(this, "stage", Stage.IDLE);
    private final StringProperty fileName = new SimpleStringProperty(this, "fileName", "");
    private final LongProperty fileSize = new SimpleLongProperty(this, "fileSize", 0);
    private final IntegerProperty totalShards = new SimpleIntegerProperty(this, "totalShards", 0);
    private final IntegerProperty currentShard = new SimpleIntegerProperty(this, "currentShard", 0);
    private final DoubleProperty progress = new SimpleDoubleProperty(this, "progress", 0.0);
    /** Скорость в байтах/секунду (rolling average). */
    private final DoubleProperty speedBps = new SimpleDoubleProperty(this, "speedBps", 0.0);
    private final StringProperty errorText = new SimpleStringProperty(this, "errorText", "");
    private final StringProperty outputPath = new SimpleStringProperty(this, "outputPath", "");

    /**
     * Список «активных» хранителей: nodeId → счётчик «откуда получили
     * сколько шардов и байт». UI читает это для блока «С узлов:».
     * {@link ObservableList} обёртывает порядок добавления — новые
     * узлы попадают в конец.
     */
    private final ObservableList<StorerStat> storers =
            FXCollections.observableArrayList();
    private final Map<String, StorerStat> storerIndex = new LinkedHashMap<>();

    /**
     * История завершений шардов: {@code timestampMillis -> bytes}.
     * Для расчёта rolling-скорости. Старше {@link #SPEED_WINDOW_MILLIS}
     * — отбрасывается.
     */
    private final List<long[]> shardHistory = new ArrayList<>();

    public ObjectProperty<Stage> stageProperty() { return stage; }
    public StringProperty fileNameProperty() { return fileName; }
    public LongProperty fileSizeProperty() { return fileSize; }
    public IntegerProperty totalShardsProperty() { return totalShards; }
    public IntegerProperty currentShardProperty() { return currentShard; }
    public DoubleProperty progressProperty() { return progress; }
    public DoubleProperty speedBpsProperty() { return speedBps; }
    public StringProperty errorTextProperty() { return errorText; }
    public StringProperty outputPathProperty() { return outputPath; }
    public ObservableList<StorerStat> getStorers() { return storers; }

    public Stage getStage() { return stage.get(); }
    public String getFileName() { return fileName.get(); }
    public long getFileSize() { return fileSize.get(); }
    public int getTotalShards() { return totalShards.get(); }
    public int getCurrentShard() { return currentShard.get(); }
    public double getProgress() { return progress.get(); }
    public double getSpeedBps() { return speedBps.get(); }

    /** Этап «работа в процессе» — для UI решений показать/скрыть busy-индикаторы. */
    public boolean isInProgress() {
        return stage.get() == Stage.DOWNLOADING;
    }

    // ------------------------------------------------------------------
    // Мутации (thread-safe)
    // ------------------------------------------------------------------

    /** Зайти в новое скачивание. Можно звать с любого потока. */
    public void start(String name, long size, int shards, String output) {
        runFx(() -> {
            fileName.set(name == null ? "" : name);
            fileSize.set(size);
            totalShards.set(shards);
            currentShard.set(0);
            progress.set(0.0);
            speedBps.set(0.0);
            errorText.set("");
            outputPath.set(output == null ? "" : output);
            storers.clear();
            storerIndex.clear();
            synchronized (shardHistory) {
                shardHistory.clear();
            }
            stage.set(Stage.DOWNLOADING);
        });
    }

    /**
     * Записать факт успешного получения шарда. Обновляет счётчик
     * текущего шарда, общий процент, rolling-скорость, и инкрементирует
     * счётчик в списке хранителей.
     */
    public void recordShard(int shardIndex, String fromNodeId, long bytes) {
        long now = System.currentTimeMillis();
        synchronized (shardHistory) {
            shardHistory.add(new long[]{now, bytes});
            // Чистим старые записи прямо тут.
            shardHistory.removeIf(rec -> now - rec[0] > SPEED_WINDOW_MILLIS);
        }
        runFx(() -> {
            currentShard.set(shardIndex + 1);
            int total = totalShards.get();
            if (total > 0) {
                progress.set(Math.min(1.0, (shardIndex + 1.0) / total));
            }
            speedBps.set(computeSpeedBps());

            StorerStat existing = storerIndex.get(fromNodeId);
            if (existing == null) {
                StorerStat s = new StorerStat(fromNodeId, 1, bytes);
                storerIndex.put(fromNodeId, s);
                storers.add(s);
            } else {
                StorerStat updated = existing.plus(bytes);
                storerIndex.put(fromNodeId, updated);
                int idx = storers.indexOf(existing);
                if (idx >= 0) storers.set(idx, updated);
            }
        });
    }

    public void complete(String savedTo) {
        runFx(() -> {
            outputPath.set(savedTo == null ? "" : savedTo);
            progress.set(1.0);
            speedBps.set(0.0);
            stage.set(Stage.DONE);
        });
    }

    public void fail(String message) {
        runFx(() -> {
            errorText.set(message == null ? "Неизвестная ошибка" : message);
            speedBps.set(0.0);
            stage.set(Stage.FAILED);
        });
    }

    /** Спрятать диалог (вызывается после OK или close). */
    public void reset() {
        runFx(() -> {
            stage.set(Stage.IDLE);
            fileName.set("");
            fileSize.set(0);
            totalShards.set(0);
            currentShard.set(0);
            progress.set(0.0);
            speedBps.set(0.0);
            errorText.set("");
            outputPath.set("");
            storers.clear();
            storerIndex.clear();
            synchronized (shardHistory) {
                shardHistory.clear();
            }
        });
    }

    /**
     * Текущая rolling-скорость в байтах/секунду. Считается синхронно
     * из {@code shardHistory} — снимаем lock на shardHistory, чтобы не
     * пересечься с recordShard в фоновом потоке.
     */
    private double computeSpeedBps() {
        long now = System.currentTimeMillis();
        long totalBytes = 0;
        long oldestTs = now;
        synchronized (shardHistory) {
            // Чистим старые записи на всякий случай (если recordShard
            // не вызывался какое-то время — окно «застаревает»).
            shardHistory.removeIf(rec -> now - rec[0] > SPEED_WINDOW_MILLIS);
            for (long[] rec : shardHistory) {
                totalBytes += rec[1];
                if (rec[0] < oldestTs) oldestTs = rec[0];
            }
        }
        long windowMillis = Math.max(now - oldestTs, 100);
        return totalBytes * 1000.0 / windowMillis;
    }

    /** Удобный иммутабельный иммутабельный снимок «N шардов / X байт от nodeId». */
    public static final class StorerStat {
        private final String nodeId;
        private final int shardCount;
        private final long totalBytes;

        public StorerStat(String nodeId, int shardCount, long totalBytes) {
            this.nodeId = nodeId;
            this.shardCount = shardCount;
            this.totalBytes = totalBytes;
        }

        public String getNodeId() { return nodeId; }
        public int getShardCount() { return shardCount; }
        public long getTotalBytes() { return totalBytes; }

        StorerStat plus(long bytes) {
            return new StorerStat(nodeId, shardCount + 1, totalBytes + bytes);
        }
    }

    /** Если уже на FX-thread — выполняем синхронно, иначе через {@code runLater}. */
    private static void runFx(Runnable r) {
        if (Platform.isFxApplicationThread()) r.run();
        else Platform.runLater(r);
    }

    /** Только для тестов — снимок списка хранителей. */
    public List<StorerStat> snapshotStorers() {
        return Collections.unmodifiableList(new ArrayList<>(storers));
    }
}
