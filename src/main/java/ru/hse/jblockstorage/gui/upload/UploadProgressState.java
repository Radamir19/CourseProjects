package ru.hse.jblockstorage.gui.upload;

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

/**
 * Observable модель текущей загрузки. Один экземпляр живёт в
 * {@link ru.hse.jblockstorage.gui.AppContext}, на него подписываются
 * UI-компоненты, которые должны отражать прогресс — сейчас это:
 * <ul>
 *   <li>компактный блок в sidebar ({@code SidebarUploadCard})</li>
 *   <li>большая карточка наверху раздела «Мои файлы»
 *       ({@code MyFilesUploadCard})</li>
 * </ul>
 *
 * <p>Решение про «не диалог, а встроенная карточка» взято из мокапа
 * {@code upload_progress.html} — там прогресс показан именно как часть
 * основного экрана, sidebar при этом дублирует общий процент. Это
 * лучше UX для нашего случая, чем модалка: пользователь может
 * параллельно листать список файлов, читать настройки и т. п.
 *
 * <p>Все мутации делаются с UI-thread (FX). {@link UploadController}
 * проксирует фоновые события через {@code Platform.runLater}.
 */
public final class UploadProgressState {

    /** Этапы загрузки — соответствуют чекпоинтам в мокапе. */
    public enum Stage {
        /** Прогресса нет, карточка скрыта. */
        IDLE,
        /** AES-GCM шифрование на устройстве. */
        ENCRYPT,
        /** Разрезание зашифрованного файла на 512 КБ шарды. */
        SHARD,
        /** Отправка шардов хранителям + сбор расписок. */
        TRANSFER,
        /** Сборка UPLOAD-tx, добавление блока в блокчейн, broadcast. */
        CONFIRM,
        /** Файл в сети, вернувшаяся транзакция доступна в {@link #txId}. */
        DONE,
        /** Сбой — текст в {@link #errorText}. */
        FAILED
    }

    private final ObjectProperty<Stage> stage =
            new SimpleObjectProperty<>(this, "stage", Stage.IDLE);
    private final StringProperty fileName = new SimpleStringProperty(this, "fileName", "");
    private final LongProperty fileSize = new SimpleLongProperty(this, "fileSize", 0);
    private final IntegerProperty totalShards = new SimpleIntegerProperty(this, "totalShards", 0);
    private final IntegerProperty currentShard = new SimpleIntegerProperty(this, "currentShard", 0);
    private final DoubleProperty progress = new SimpleDoubleProperty(this, "progress", 0.0);
    private final StringProperty etaText = new SimpleStringProperty(this, "etaText", "");
    private final StringProperty errorText = new SimpleStringProperty(this, "errorText", "");
    private final StringProperty txId = new SimpleStringProperty(this, "txId", "");

    public ObjectProperty<Stage> stageProperty() { return stage; }
    public StringProperty fileNameProperty() { return fileName; }
    public LongProperty fileSizeProperty() { return fileSize; }
    public IntegerProperty totalShardsProperty() { return totalShards; }
    public IntegerProperty currentShardProperty() { return currentShard; }
    public DoubleProperty progressProperty() { return progress; }
    public StringProperty etaTextProperty() { return etaText; }
    public StringProperty errorTextProperty() { return errorText; }
    public StringProperty txIdProperty() { return txId; }

    public Stage getStage() { return stage.get(); }
    public String getFileName() { return fileName.get(); }
    public long getFileSize() { return fileSize.get(); }
    public int getTotalShards() { return totalShards.get(); }
    public int getCurrentShard() { return currentShard.get(); }
    public double getProgress() { return progress.get(); }
    public String getEtaText() { return etaText.get(); }
    public String getErrorText() { return errorText.get(); }
    public String getTxId() { return txId.get(); }

    /**
     * «Сейчас идёт загрузка / только что завершилась» — компоненты
     * показывают карточку в этих состояниях. {@link Stage#IDLE} — нет
     * карточки. {@link Stage#DONE}/{@link Stage#FAILED} — финальные
     * состояния, карточка ещё видна (несколько секунд) перед сбросом.
     */
    public boolean isVisible() {
        Stage s = stage.get();
        return s != Stage.IDLE;
    }

    /** «Загрузка ещё в работе»: ENCRYPT/SHARD/TRANSFER/CONFIRM. */
    public boolean isInProgress() {
        Stage s = stage.get();
        return s == Stage.ENCRYPT || s == Stage.SHARD
                || s == Stage.TRANSFER || s == Stage.CONFIRM;
    }

    // ------------------------------------------------------------------
    // Мутации (только UI-thread)
    // ------------------------------------------------------------------

    public void start(String name, long size, int shards) {
        fileName.set(name);
        fileSize.set(size);
        totalShards.set(shards);
        currentShard.set(0);
        progress.set(0.0);
        etaText.set("");
        errorText.set("");
        txId.set("");
        stage.set(Stage.ENCRYPT);
    }

    public void setStage(Stage s) {
        stage.set(s);
    }

    public void setProgress(double p) {
        if (p < 0) p = 0;
        if (p > 1) p = 1;
        progress.set(p);
    }

    public void setCurrentShard(int n) {
        currentShard.set(Math.max(0, n));
    }

    public void setEta(String text) {
        etaText.set(text == null ? "" : text);
    }

    public void complete(String resultTxId) {
        txId.set(resultTxId == null ? "" : resultTxId);
        progress.set(1.0);
        stage.set(Stage.DONE);
    }

    public void fail(String message) {
        errorText.set(message == null ? "Неизвестная ошибка" : message);
        stage.set(Stage.FAILED);
    }

    /** Спрятать карточку (вызывается через несколько секунд после DONE/FAILED). */
    public void reset() {
        stage.set(Stage.IDLE);
        fileName.set("");
        fileSize.set(0);
        totalShards.set(0);
        currentShard.set(0);
        progress.set(0.0);
        etaText.set("");
        errorText.set("");
        txId.set("");
    }
}
