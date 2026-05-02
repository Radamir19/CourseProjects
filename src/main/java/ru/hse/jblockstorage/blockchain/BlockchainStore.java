package ru.hse.jblockstorage.blockchain;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Постоянное хранилище блоков на основе RocksDB.
 * <p>
 * Согласно ТЗ (п. 4.5.3) RocksDB используется как высокопроизводительная
 * встраиваемая Key-Value база для локального хранения реестра блоков.
 * Ключ — индекс блока в виде 8-байтового big-endian (это обеспечивает
 * лексикографически правильный порядок при итерации).
 * Значение — JSON-сериализация блока через Jackson (см. п. 4.5.3 — Jackson
 * Databind 2.13+).
 * </p>
 *
 * <h3>Жизненный цикл</h3>
 * Хранилище реализует {@link AutoCloseable} — открывайте через try-with-resources
 * или явно вызывайте {@link #close()}, иначе нативные ресурсы RocksDB утекут.
 */
public class BlockchainStore implements AutoCloseable {

    static {
        // Загружаем нативную библиотеку один раз на JVM.
        RocksDB.loadLibrary();
    }

    private final RocksDB db;
    private final Options options;
    private final ObjectMapper mapper = new ObjectMapper()
            // Не падаем, если в JSON встретилось поле, которого нет в Block/Transaction —
            // полезно для устойчивости при изменении модели между версиями.
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private BlockchainStore(RocksDB db, Options options) {
        this.db = db;
        this.options = options;
    }

    /** Открывает или создаёт хранилище в указанной директории. */
    public static BlockchainStore open(Path directory) {
        try {
            Files.createDirectories(directory);
            Options options = new Options().setCreateIfMissing(true);
            RocksDB db = RocksDB.open(options, directory.toString());
            return new BlockchainStore(db, options);
        } catch (RocksDBException | IOException e) {
            throw new IllegalStateException("Не удалось открыть RocksDB по пути " + directory, e);
        }
    }

    /** Сохраняет блок (перезаписывает, если блок с таким индексом уже был). */
    public void saveBlock(Block block) {
        try {
            byte[] key = indexToKey(block.getIndex());
            byte[] value = mapper.writeValueAsBytes(block);
            db.put(key, value);
        } catch (RocksDBException e) {
            throw new IllegalStateException("Не удалось записать блок #" + block.getIndex(), e);
        } catch (IOException e) {
            throw new UncheckedIOException("Сериализация блока в JSON упала", e);
        }
    }

    /**
     * Атомарно сохраняет всю цепочку — используется при загрузке/замене
     * локальной цепи на полученную от другого узла (см. п. 4.2.1: транзакционная
     * запись через WriteBatch для предотвращения повреждения реестра при сбое).
     */
    public void saveChain(List<Block> blocks) {
        try (WriteBatch batch = new WriteBatch();
             WriteOptions writeOptions = new WriteOptions()) {
            for (Block block : blocks) {
                batch.put(indexToKey(block.getIndex()), mapper.writeValueAsBytes(block));
            }
            db.write(writeOptions, batch);
        } catch (RocksDBException e) {
            throw new IllegalStateException("Не удалось сохранить цепочку", e);
        } catch (IOException e) {
            throw new UncheckedIOException("Сериализация цепочки упала", e);
        }
    }

    /** Возвращает блок по индексу, либо {@code null} если такого нет. */
    public Block loadBlock(int index) {
        try {
            byte[] value = db.get(indexToKey(index));
            if (value == null) return null;
            return mapper.readValue(value, Block.class);
        } catch (RocksDBException e) {
            throw new IllegalStateException("Не удалось прочитать блок #" + index, e);
        } catch (IOException e) {
            throw new UncheckedIOException("Десериализация блока упала", e);
        }
    }

    /** Возвращает все блоки в порядке возрастания индекса. */
    public List<Block> loadAll() {
        List<Block> blocks = new ArrayList<>();
        try (RocksIterator it = db.newIterator()) {
            for (it.seekToFirst(); it.isValid(); it.next()) {
                try {
                    blocks.add(mapper.readValue(it.value(), Block.class));
                } catch (IOException e) {
                    throw new UncheckedIOException(
                            "Десериализация блока с ключом " + keyToIndex(it.key()) + " упала", e);
                }
            }
        }
        return blocks;
    }

    @Override
    public void close() {
        if (db != null) db.close();
        if (options != null) options.close();
    }

    /** Кодирует индекс в 8-байтовый big-endian ключ — лексикографически совпадает с числовым порядком. */
    private static byte[] indexToKey(int index) {
        return ByteBuffer.allocate(Long.BYTES).putLong(index).array();
    }

    private static int keyToIndex(byte[] key) {
        return (int) ByteBuffer.wrap(key).getLong();
    }
}