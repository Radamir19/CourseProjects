package ru.hse.jblockstorage.storage;

import ru.hse.jblockstorage.crypto.CryptoUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Локальное хранилище шардов на диске — файлы с именем = SHA-256 хеш в hex.
 * <p>
 * Каждый шард хранится отдельным файлом в указанной директории. Имя файла
 * = hex SHA-256 содержимого, что даёт сразу два полезных свойства:
 * <ol>
 *   <li>дедупликация (одинаковые шарды разных файлов делят один blob);</li>
 *   <li>встроенная проверка целостности (имя файла = хеш — если содержимое
 *       не сходится, шард считается повреждённым).</li>
 * </ol>
 * </p>
 *
 * <h3>Почему не RocksDB</h3>
 * Для шардов RocksDB избыточен: данные иммутабельны, никаких range-запросов
 * нам не нужно, размер одного значения может быть сотни КБ. Обычный
 * файл-на-шард работает быстрее и проще отлаживается (можно посмотреть в FS).
 * Если в будущем понадобится индекс «какие файлы каким хранителем хранятся»,
 * его можно добавить отдельным RocksDB-словарём.
 *
 * <h3>Безопасность путей</h3>
 * Имя файла строго ограничено: 64 hex-символа. Любая попытка передать
 * содержащую '/', '..' и т.п. строку как hash отлавливается валидацией —
 * это защищает от path traversal, если хеши когда-нибудь начнут приходить
 * из недоверенного источника.
 */
public final class ShardStorage {

    /** SHA-256 в hex — ровно 64 символа из [0-9a-f]. */
    private static final Pattern HEX_HASH = Pattern.compile("[0-9a-f]{64}");

    private final Path baseDir;

    /**
     * Создаёт хранилище в указанной директории. Директория будет создана,
     * если ещё не существует.
     */
    public ShardStorage(Path baseDir) throws IOException {
        this.baseDir = Objects.requireNonNull(baseDir, "baseDir").toAbsolutePath().normalize();
        Files.createDirectories(this.baseDir);
    }

    /** Где живут шарды (для отладки/логов). */
    public Path baseDir() {
        return baseDir;
    }

    /**
     * Сохраняет шард на диск. Перед записью сверяет, что переданный
     * {@code expectedHashHex} действительно равен SHA-256 от {@code data}.
     * <p>
     * Если шард с таким хешем уже есть — операция идемпотентна (ничего
     * не делаем, возвращаемся успешно). Это удобно для повторных PUT
     * от того же uploader'а.
     * </p>
     *
     * @return {@code true} если новый файл был создан, {@code false} если
     *         шард уже хранился (дубликат).
     */
    public boolean save(String expectedHashHex, byte[] data) throws IOException {
        validateHash(expectedHashHex);
        Objects.requireNonNull(data, "data");

        // Проверяем целостность ДО записи на диск — иначе можно засорить
        // хранилище мусором, который кто-то прислал под видом нужного шарда.
        String actualHash = CryptoUtils.toHex(CryptoUtils.applySha256(data));
        if (!actualHash.equals(expectedHashHex)) {
            throw new IllegalArgumentException(
                    "Данные шарда не соответствуют заявленному хешу: ожидали "
                            + expectedHashHex + ", получили " + actualHash);
        }

        Path target = pathFor(expectedHashHex);
        if (Files.exists(target)) {
            return false; // уже храним — ничего делать не нужно
        }

        // Запись через атомарный rename (если поддерживается ОС): сначала во временный
        // файл, потом перемещаем. Это защищает от частично записанного файла при крэше.
        Path tmp = target.resolveSibling(expectedHashHex + ".tmp");
        Files.write(tmp, data);
        Files.move(tmp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        return true;
    }

    /**
     * Загружает шард с диска. Дополнительно сверяет хеш — на случай, если
     * файл повреждён диском или подменён извне процесса.
     */
    public Optional<byte[]> load(String hashHex) throws IOException {
        validateHash(hashHex);
        Path target = pathFor(hashHex);
        if (!Files.exists(target)) return Optional.empty();

        byte[] data = Files.readAllBytes(target);
        String actualHash = CryptoUtils.toHex(CryptoUtils.applySha256(data));
        if (!actualHash.equals(hashHex)) {
            // Файл повреждён. Не возвращаем мусор — пусть лучше будет «нет шарда»,
            // и downloader попробует другую реплику.
            return Optional.empty();
        }
        return Optional.of(data);
    }

    /** Хранит ли мы шард с таким хешем. Дёшево — только проверка существования файла. */
    public boolean has(String hashHex) {
        try {
            validateHash(hashHex);
            return Files.exists(pathFor(hashHex));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Удаляет шард, если он есть. Идемпотентно. */
    public boolean delete(String hashHex) throws IOException {
        validateHash(hashHex);
        return Files.deleteIfExists(pathFor(hashHex));
    }

    /** Возвращает путь файла для данного хеша (в открытом виде — для тестов). */
    Path pathFor(String hashHex) {
        return baseDir.resolve(hashHex);
    }

    private static void validateHash(String hashHex) {
        if (hashHex == null || !HEX_HASH.matcher(hashHex).matches()) {
            throw new IllegalArgumentException(
                    "Хеш должен быть строкой ровно из 64 hex-символов: '" + hashHex + "'");
        }
    }
}
