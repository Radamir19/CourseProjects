package ru.hse.jblockstorage.app;

/**
 * Колбек событий прогресса скачивания файла — день 15.
 *
 * <p>Передаётся в {@link FileDownloader#downloadFile} и
 * {@link NodeApplication#downloadFile(String, java.nio.file.Path,
 * DownloadProgressListener)}, чтобы UI-слой мог показать пользователю
 * текущую скорость и список узлов, с которых идёт скачивание (ТЗ
 * п. 4.1.5.3 «*Отображение скорости передачи данных и списка узлов, с
 * которых идет скачивание*»).
 *
 * <p><b>Когда вызывается:</b>
 * <ul>
 *   <li>{@link #onStart} — один раз перед началом сетевых запросов,
 *       когда уже известно общее число шардов;</li>
 *   <li>{@link #onShardStarted} — перед попыткой запросить очередной
 *       шард у конкретного хранителя;</li>
 *   <li>{@link #onShardFinished} — успешный shard fetch (с указанием
 *       размера в байтах и фактического хранителя, у которого получили);</li>
 *   <li>{@link #onShardFailed} — попытка с конкретным узлом не удалась,
 *       но downloader попробует следующего из списка реплик. Не
 *       финальная ошибка;</li>
 *   <li>{@link #onFinished} — все шарды собраны, файл записан;</li>
 *   <li>{@link #onFailed} — финальный сбой, file не сохранён.</li>
 * </ul>
 *
 * <p><b>Threading:</b> все методы вызываются из того же потока, что
 * вызвал {@code downloadFile} — обычно это фоновый JavaFX Task, не
 * UI-thread. Реализация листенера должна сама диспатчить в UI через
 * {@code Platform.runLater}, если меняет observable-свойства (см.
 * {@code DownloadController}).
 *
 * <p>Все методы имеют дефолтные пустые тела — это упрощает использование
 * (можно реализовать только нужные события) и позволяет добавлять новые
 * события в будущем без поломки существующих реализаций.
 */
public interface DownloadProgressListener {

    /**
     * Скачивание начинается.
     *
     * @param fileName    имя файла из транзакции
     * @param fileSize    общий размер файла в байтах (как записан в tx)
     * @param totalShards число шардов, которые предстоит скачать
     */
    default void onStart(String fileName, long fileSize, int totalShards) {}

    /**
     * Готовы запросить очередной шард у конкретного хранителя.
     *
     * @param shardIndex индекс шарда (0..totalShards-1)
     * @param storerId   nodeId хранителя, которому шлём запрос
     */
    default void onShardStarted(int shardIndex, String storerId) {}

    /**
     * Шард успешно получен.
     *
     * @param shardIndex индекс шарда
     * @param storerId   фактический хранитель, от которого получили
     *                   данные (может отличаться от первого попытанного,
     *                   если первый таймаутнул)
     * @param bytes      размер шарда в байтах (для расчёта скорости)
     */
    default void onShardFinished(int shardIndex, String storerId, long bytes) {}

    /**
     * Конкретная попытка скачать шард у этого узла не удалась
     * (таймаут / битые данные / нет шарда). Downloader попробует
     * следующего хранителя из списка. Это не финальный сбой — onFailed
     * отдельный.
     */
    default void onShardFailed(int shardIndex, String storerId, String reason) {}

    /** Файл успешно скачан и записан на диск. */
    default void onFinished() {}

    /** Финальный сбой — файл не скачан. */
    default void onFailed(Throwable cause) {}
}
