package ru.hse.jblockstorage.gui.upload;

import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.hse.jblockstorage.app.NodeApplication;
import ru.hse.jblockstorage.blockchain.Transaction;
import ru.hse.jblockstorage.storage.FileChunker;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Оркестратор загрузки одного файла. Связывает между собой:
 * <ul>
 *   <li>фоновый {@link Task}, который зовёт синхронный
 *       {@link NodeApplication#uploadFile} (одна реальная операция, без
 *       внутренних коллбэков)</li>
 *   <li>{@link Timeline}, который двигает «видимый» прогресс по этапам
 *       и заполняет процент. Это <i>имитация</i>, но честная: этапы и
 *       порядок соответствуют тому, что делает бэкенд внутри.
 *       Альтернатива (instrumentation FileUploader коллбэками) — это
 *       вторжение в стабильный бэкенд, ради него ломать 232 теста не
 *       будем</li>
 *   <li>{@link UploadProgressState} — observable модель, на которую
 *       подписан UI</li>
 * </ul>
 *
 * <h3>Имитация прогресса</h3>
 * Время реальной загрузки сильно зависит от размера файла, числа пиров,
 * скорости сети и CPU. На практике {@code uploadFile} большую часть
 * времени проводит в transfer-фазе (передача шардов хранителям), и
 * шифрование/шардирование занимают единицы секунд для типичных файлов.
 *
 * <p>Стратегия: грубо оцениваем общее время по размеру файла, делим
 * 5%/15%/70%/8%/2% между этапами, двигаем прогресс линейно внутри
 * этапа. Если бэкенд завершился раньше предсказанного времени — сразу
 * перепрыгиваем в CONFIRM/DONE. Если позже — застреваем на 90% в
 * TRANSFER до фактического возврата.
 */
public final class UploadController {

    private static final Logger log = LoggerFactory.getLogger(UploadController.class);

    /** Базовая продолжительность, к ней добавляется время по размеру. */
    private static final double BASE_DURATION_SEC = 2.0;
    /** Доли этапов в общем «бюджете времени». */
    private static final double SHARE_ENCRYPT  = 0.05;
    private static final double SHARE_SHARD    = 0.15;
    private static final double SHARE_TRANSFER = 0.70;
    private static final double SHARE_CONFIRM  = 0.08;
    // Оставшееся 0.02 — буфер «полировка», не используется явно.

    /** Кусок прогресс-бара, выделенный каждому этапу. */
    private static final double PROG_ENCRYPT_END  = 0.10;
    private static final double PROG_SHARD_END    = 0.30;
    private static final double PROG_TRANSFER_END = 0.92;
    private static final double PROG_CONFIRM_END  = 0.98;

    /** Сколько держать карточку видимой после успеха/ошибки. */
    private static final Duration HIDE_AFTER_DONE = Duration.seconds(2.5);
    private static final Duration HIDE_AFTER_FAIL = Duration.seconds(6);

    private final NodeApplication node;
    private final UploadProgressState state;
    private final Path file;
    private final Consumer<Transaction> onSuccess;
    private final Runnable onAnyFinish;

    private final long fileSize;
    private final int totalShards;
    private final long startMillis;
    private final long expectedDurationMs;

    private Task<Transaction> task;
    private Timeline ticker;

    /**
     * @param node       активный узел
     * @param state      общая модель прогресса (одна на приложение)
     * @param file       путь к файлу
     * @param onSuccess  колбек после успешного DONE (на UI-потоке).
     *                   Для перерисовки списка «Мои файлы»
     * @param onAnyFinish колбек на любой исход (DONE/FAILED), вызывается
     *                   после того, как карточка состояния спрячется
     */
    public UploadController(NodeApplication node,
                            UploadProgressState state,
                            Path file,
                            Consumer<Transaction> onSuccess,
                            Runnable onAnyFinish) {
        this.node = Objects.requireNonNull(node, "node");
        this.state = Objects.requireNonNull(state, "state");
        this.file = Objects.requireNonNull(file, "file");
        this.onSuccess = onSuccess;
        this.onAnyFinish = onAnyFinish;

        long size;
        try {
            size = Files.size(file);
        } catch (Exception e) {
            size = 0;
        }
        this.fileSize = size;
        this.totalShards = (int) Math.max(1L,
                (size + FileChunker.DEFAULT_CHUNK_SIZE - 1) / FileChunker.DEFAULT_CHUNK_SIZE);

        // Грубая оценка: 0.5 секунды на МБ + база. Не точно, но достаточно
        // для плавной анимации этапов на типичных файлах.
        long mb = Math.max(1, size / (1024 * 1024));
        this.expectedDurationMs = (long) (BASE_DURATION_SEC * 1000 + mb * 500);

        this.startMillis = System.currentTimeMillis();
    }

    public void start() {
        log.info("Upload start: {} ({} байт, {} шардов, оценка {} мс)",
                file.getFileName(), fileSize, totalShards, expectedDurationMs);

        // 1. Инициализируем модель — карточка показывается сразу.
        state.start(file.getFileName().toString(), fileSize, totalShards);

        // 2. Запускаем фоновую задачу — реальный uploadFile.
        task = new Task<>() {
            @Override
            protected Transaction call() throws Exception {
                long t0 = System.currentTimeMillis();
                Transaction tx = node.uploadFile(file);
                log.info("Upload OK за {} мс, txId={}", System.currentTimeMillis() - t0, tx.getId());
                return tx;
            }
        };
        task.setOnSucceeded(e -> handleSuccess(task.getValue()));
        task.setOnFailed(e -> handleFailure(task.getException()));

        Thread th = new Thread(task, "upload-" + file.getFileName());
        th.setDaemon(true);
        th.start();

        // 3. Ticker — двигает этапы и прогресс каждые 100мс.
        ticker = new Timeline(new KeyFrame(Duration.millis(100), e -> tick()));
        ticker.setCycleCount(Timeline.INDEFINITE);
        ticker.play();
    }

    /**
     * Вызывается каждые 100мс. Двигает фейковый прогресс по этапам,
     * пока бэкенд не вернётся. После CONFIRM/DONE/FAILED ticker не
     * трогает состояние — это финальные позиции.
     */
    private void tick() {
        // Если уже финал — ничего не делаем (handle* уже остановили ticker).
        UploadProgressState.Stage s = state.getStage();
        if (s == UploadProgressState.Stage.DONE
                || s == UploadProgressState.Stage.FAILED
                || s == UploadProgressState.Stage.IDLE) {
            return;
        }

        long elapsed = System.currentTimeMillis() - startMillis;
        double t = Math.min(1.0, (double) elapsed / expectedDurationMs);

        // Распределение времени по этапам:
        double tEncryptEnd  = SHARE_ENCRYPT;
        double tShardEnd    = tEncryptEnd + SHARE_SHARD;
        double tTransferEnd = tShardEnd + SHARE_TRANSFER;
        double tConfirmEnd  = tTransferEnd + SHARE_CONFIRM;

        if (t < tEncryptEnd) {
            // ENCRYPT 0…10%
            double local = t / SHARE_ENCRYPT;
            state.setStage(UploadProgressState.Stage.ENCRYPT);
            state.setProgress(local * PROG_ENCRYPT_END);
            state.setCurrentShard(0);
        } else if (t < tShardEnd) {
            // SHARD 10…30%
            double local = (t - tEncryptEnd) / SHARE_SHARD;
            state.setStage(UploadProgressState.Stage.SHARD);
            state.setProgress(PROG_ENCRYPT_END + local * (PROG_SHARD_END - PROG_ENCRYPT_END));
            state.setCurrentShard(0);
        } else if (t < tTransferEnd) {
            // TRANSFER 30…92%
            double local = (t - tShardEnd) / SHARE_TRANSFER;
            state.setStage(UploadProgressState.Stage.TRANSFER);
            state.setProgress(PROG_SHARD_END + local * (PROG_TRANSFER_END - PROG_SHARD_END));
            // currentShard растёт линейно с прогрессом передачи
            state.setCurrentShard((int) Math.min(totalShards, Math.round(local * totalShards)));
            updateEta(elapsed);
        } else if (t < tConfirmEnd) {
            // CONFIRM 92…98% — но ждём фактического возврата task
            state.setStage(UploadProgressState.Stage.CONFIRM);
            double local = (t - tTransferEnd) / SHARE_CONFIRM;
            state.setProgress(PROG_TRANSFER_END + local * (PROG_CONFIRM_END - PROG_TRANSFER_END));
            state.setEta("Подтверждение в блокчейне…");
            state.setCurrentShard(totalShards);
        } else {
            // Время истекло, а task ещё не вернулся — застреваем на 92%
            // в TRANSFER. Это честно: реальная сеть медленнее ожиданий.
            state.setStage(UploadProgressState.Stage.TRANSFER);
            state.setProgress(PROG_TRANSFER_END);
            state.setCurrentShard(totalShards);
            state.setEta("ещё чуть-чуть…");
        }
    }

    private void updateEta(long elapsedMs) {
        long remainingMs = expectedDurationMs - elapsedMs;
        if (remainingMs <= 0) {
            state.setEta("ещё чуть-чуть…");
            return;
        }
        long sec = Math.max(1, remainingMs / 1000);
        if (sec >= 60) {
            state.setEta("~" + (sec / 60) + " мин");
        } else {
            state.setEta("~" + sec + " секунд");
        }
    }

    private void handleSuccess(Transaction tx) {
        if (ticker != null) ticker.stop();
        state.complete(tx == null ? "" : tx.getId());
        if (onSuccess != null) onSuccess.accept(tx);

        // Через несколько секунд автоматически прячем карточку.
        PauseTransition hide = new PauseTransition(HIDE_AFTER_DONE);
        hide.setOnFinished(e -> {
            state.reset();
            if (onAnyFinish != null) onAnyFinish.run();
        });
        hide.play();
    }

    private void handleFailure(Throwable ex) {
        if (ticker != null) ticker.stop();
        log.warn("Upload failed", ex);
        String msg = explain(ex);
        state.fail(msg);

        PauseTransition hide = new PauseTransition(HIDE_AFTER_FAIL);
        hide.setOnFinished(e -> {
            state.reset();
            if (onAnyFinish != null) onAnyFinish.run();
        });
        hide.play();
    }

    /** Прервать загрузку (текущий MVP — без отмены, оставлено как точка расширения). */
    public void cancel() {
        if (ticker != null) ticker.stop();
        if (task != null) task.cancel(true);
        Platform.runLater(state::reset);
    }

    private static String explain(Throwable ex) {
        if (ex == null) return "Неизвестная ошибка";
        if (ex.getMessage() != null && !ex.getMessage().isBlank()) return ex.getMessage();
        return ex.getClass().getSimpleName();
    }
}
