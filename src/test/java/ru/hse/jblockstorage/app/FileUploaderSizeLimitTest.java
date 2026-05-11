package ru.hse.jblockstorage.app;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.hse.jblockstorage.crypto.KeyManager;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ТЗ п. 4.1.2 — Таблица 1: «Размер до 5 ГБ. Ограничение текущей версии
 * протокола». Проверяем, что {@link FileUploader} отклоняет файл, чей
 * размер на диске превышает {@link FileUploader#MAX_FILE_SIZE_BYTES},
 * причём проверка происходит ДО загрузки содержимого в heap (иначе
 * 6-ГБ файл выжрет JVM до выдачи понятной ошибки).
 *
 * <p>Чтобы тест был быстрым и не требовал реальных 6 ГБ дискового
 * пространства, используется sparse-файл через {@link RandomAccessFile#setLength}
 * — на большинстве файловых систем (ext4, APFS, NTFS) это создаёт
 * файл-«дыру» нулевого фактического размера.
 */
class FileUploaderSizeLimitTest {

    @Test
    @DisplayName("Файл больше 5 ГБ отвергается с IllegalArgumentException")
    void filesOver5GBAreRejected(@TempDir Path tmp) throws IOException {
        Path tooLarge = tmp.resolve("huge.bin");
        long size = FileUploader.MAX_FILE_SIZE_BYTES + 1;

        // Sparse-файл: на диске занимает почти ничего, но Files.size() вернёт size.
        try (RandomAccessFile raf = new RandomAccessFile(tooLarge.toFile(), "rw")) {
            raf.setLength(size);
        }
        assertTrue(Files.size(tooLarge) == size,
                "sparse-файл должен сообщать заявленный размер");

        KeyPair keys = KeyManager.generateRsaKeyPair();
        FileUploader uploader = new FileUploader(
                keys.getPublic(), keys.getPrivate(),
                new ru.hse.jblockstorage.blockchain.Blockchain(),
                /* chunkSize */ 512 * 1024,
                /* replicationFactor */ 1);

        // availableStorers пустой — не дойдём до проверки пиров,
        // если размер отвергнется раньше.
        assertThrows(IllegalArgumentException.class,
                () -> uploader.uploadFile(tooLarge, List.of()),
                "Файл больше 5 ГБ должен отклоняться по 4.1.2");
    }

    @Test
    @DisplayName("Константа MAX_FILE_SIZE_BYTES соответствует ТЗ (5 × 2³⁰)")
    void constantMatches5GiB() {
        long expected = 5L * 1024 * 1024 * 1024;
        assertTrue(FileUploader.MAX_FILE_SIZE_BYTES == expected,
                "MAX_FILE_SIZE_BYTES должен быть ровно 5 GiB, есть "
                        + FileUploader.MAX_FILE_SIZE_BYTES);
    }
}
