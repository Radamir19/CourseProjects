package ru.hse.jblockstorage.storage;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Разбивает файл на блоки фиксированного размера («шарды») и собирает обратно.
 * <p>
 * Согласно ТЗ (п. 4.1.1.2.3) размер блока по умолчанию 512 КБ, последний
 * блок может иметь меньший размер. Шардинг применяется к уже зашифрованному
 * содержимому файла (см. п. 4.1.1.2.2).
 * </p>
 */
public final class FileChunker {

    /** Размер блока по умолчанию — 512 КБ, как в ТЗ. */
    public static final int DEFAULT_CHUNK_SIZE = 512 * 1024;

    private FileChunker() {
        // утилитный класс
    }

    public static List<Shard> chunk(byte[] data) {
        return chunk(data, DEFAULT_CHUNK_SIZE);
    }

    /**
     * Разбивает массив байт на шарды размером {@code chunkSize}.
     * Если длина не кратна — последний шард получит остаток.
     */
    public static List<Shard> chunk(byte[] data, int chunkSize) {
        Objects.requireNonNull(data, "data");
        validateChunkSize(chunkSize);
        if (data.length == 0) return List.of();

        int total = (data.length + chunkSize - 1) / chunkSize;
        List<Shard> shards = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            int from = i * chunkSize;
            int to = Math.min(from + chunkSize, data.length);
            byte[] chunk = new byte[to - from];
            System.arraycopy(data, from, chunk, 0, chunk.length);
            shards.add(new Shard(i, chunk));
        }
        return shards;
    }

    public static List<Shard> chunk(Path file) throws IOException {
        return chunk(file, DEFAULT_CHUNK_SIZE);
    }

    /**
     * Разбивает файл на шарды, не загружая его целиком в память.
     * Используется для больших файлов (по ТЗ — до 5 ГБ, п. 4.1.2).
     */
    public static List<Shard> chunk(Path file, int chunkSize) throws IOException {
        validateChunkSize(chunkSize);
        List<Shard> shards = new ArrayList<>();
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            byte[] buffer = new byte[chunkSize];
            int index = 0;
            int filled = 0;
            int read;
            while ((read = in.read(buffer, filled, buffer.length - filled)) > 0) {
                filled += read;
                if (filled == buffer.length) {
                    shards.add(new Shard(index++, copy(buffer, filled)));
                    filled = 0;
                }
            }
            if (filled > 0) {
                shards.add(new Shard(index, copy(buffer, filled)));
            }
        }
        return shards;
    }

    /**
     * Собирает шарды обратно в один массив байт.
     * Шарды могут идти в произвольном порядке — метод сортирует их по {@code index}
     * и проверяет, что нет пропусков.
     */
    public static byte[] assemble(List<Shard> shards) {
        Objects.requireNonNull(shards, "shards");
        if (shards.isEmpty()) return new byte[0];

        List<Shard> sorted = sortAndValidate(shards);
        int total = 0;
        for (Shard s : sorted) total += s.size();
        byte[] result = new byte[total];
        int pos = 0;
        for (Shard s : sorted) {
            System.arraycopy(s.data(), 0, result, pos, s.size());
            pos += s.size();
        }
        return result;
    }

    /** Собирает шарды и записывает в файл (без загрузки всего в память). */
    public static void assemble(List<Shard> shards, Path outputFile) throws IOException {
        Objects.requireNonNull(shards, "shards");
        Objects.requireNonNull(outputFile, "outputFile");
        List<Shard> sorted = sortAndValidate(shards);
        try (OutputStream out = Files.newOutputStream(outputFile)) {
            for (Shard s : sorted) {
                out.write(s.data());
            }
        }
    }

    private static List<Shard> sortAndValidate(List<Shard> shards) {
        List<Shard> sorted = new ArrayList<>(shards);
        sorted.sort(Comparator.comparingInt(Shard::index));
        for (int i = 0; i < sorted.size(); i++) {
            int actual = sorted.get(i).index();
            if (actual != i) {
                throw new IllegalStateException(
                        "Отсутствует шард с индексом " + i + " (следующий доступный — " + actual + ")");
            }
        }
        return sorted;
    }

    private static void validateChunkSize(int chunkSize) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize должен быть > 0, получено " + chunkSize);
        }
    }

    private static byte[] copy(byte[] src, int length) {
        byte[] dst = new byte[length];
        System.arraycopy(src, 0, dst, 0, length);
        return dst;
    }
}