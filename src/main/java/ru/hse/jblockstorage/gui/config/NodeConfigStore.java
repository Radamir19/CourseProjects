package ru.hse.jblockstorage.gui.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.hse.jblockstorage.config.SeedNode;

import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Properties;

/**
 * Конфиг приложения уровня {@code <JBS_HOME>/config.properties} —
 * день 15. Реализует ТЗ п. 4.1.1.1.1: «*При первом запуске узел должен
 * подключаться к сети, используя список известных адресов (Seed Nodes),
 * указанных в конфигурационном файле*» и п. 4.1.3.2: «*Файл
 * конфигурации узла (config.yaml/properties) с обновлённым списком
 * активных пиров*».
 *
 * <p><b>Формат — Java {@link Properties}.</b> Это прямое попадание в
 * букву ТЗ. Альтернативой был бы JSON (как у нас peers/contacts), но
 * properties делает «руками поправить файл блокнотом» абсолютно
 * банальным, что важно для эксплуатации:
 * <pre>
 *   # Список seed-узлов через запятую: host:port[,host:port,...]
 *   seeds=127.0.0.1:8081,demo.example.com:8080
 *
 *   # Опциональный override TCP-порта (приоритет ниже JBS_PORT)
 *   #listenPort=8081
 *
 *   # Опциональный override фактора репликации
 *   #replicationFactor=3
 * </pre>
 *
 * <p><b>Приоритет источников:</b> переменные окружения ({@code JBS_PORT},
 * {@code JBS_SEEDS}) > этот файл > дефолты {@code NodeConfig.defaults()}.
 * Так мы не ломаем демо-сценарий с тремя узлами в одном каталоге через
 * env, но даём пользователю обычного приложения один-на-машину
 * единственное место, где seeds можно поставить руками или из UI.
 *
 * <p><b>Семантика записи:</b> атомарная (tmp + {@code ATOMIC_MOVE}
 * с fallback на обычный {@code move}), как везде в проекте. Вся
 * запись идёт целым файлом, поэтому если приложение убьют посередине
 * — будет либо старая версия, либо новая, но не покорёженная.
 *
 * <p><b>Где живёт:</b> ровно в {@code <JBS_HOME>/config.properties} —
 * рядом с {@code profiles/}, выше каталогов профилей. Конфиг общий для
 * всех профилей: seed-узлы — это сетевой ресурс машины, а не свойство
 * конкретного юзера.
 */
public final class NodeConfigStore {

    private static final Logger log = LoggerFactory.getLogger(NodeConfigStore.class);

    private static final String KEY_SEEDS = "seeds";
    private static final String KEY_LISTEN_PORT = "listenPort";
    private static final String KEY_REPLICATION_FACTOR = "replicationFactor";

    private final Path file;
    private List<SeedNode> seeds;
    private OptionalInt listenPort;
    private OptionalInt replicationFactor;

    private NodeConfigStore(Path file,
                            List<SeedNode> seeds,
                            OptionalInt listenPort,
                            OptionalInt replicationFactor) {
        this.file = file;
        this.seeds = new ArrayList<>(seeds);
        this.listenPort = listenPort;
        this.replicationFactor = replicationFactor;
    }

    // ------------------------------------------------------------------
    // Загрузка
    // ------------------------------------------------------------------

    /**
     * Загружает конфиг из файла. Если файла нет — возвращает пустой
     * стор (всё default), файл будет создан при первом сохранении.
     * Битые поля молча игнорируются (с warn в лог) — не валим всё
     * приложение из-за опечатки в строке.
     */
    public static NodeConfigStore load(Path file) {
        Objects.requireNonNull(file, "file");
        if (!Files.isRegularFile(file)) {
            log.debug("config.properties не существует — стартуем с дефолтами");
            return empty(file);
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            log.warn("Не удалось прочитать {}: {}. Стартуем с дефолтами.",
                    file, e.toString());
            return empty(file);
        }

        List<SeedNode> seeds = parseSeeds(props.getProperty(KEY_SEEDS));
        OptionalInt port = parseOptionalPort(
                props.getProperty(KEY_LISTEN_PORT), KEY_LISTEN_PORT);
        OptionalInt rf = parseOptionalReplication(
                props.getProperty(KEY_REPLICATION_FACTOR));
        log.info("Загружен config.properties: seeds={}, listenPort={}, rf={}",
                seeds.size(), port, rf);
        return new NodeConfigStore(file, seeds, port, rf);
    }

    private static NodeConfigStore empty(Path file) {
        return new NodeConfigStore(file, List.of(), OptionalInt.empty(), OptionalInt.empty());
    }

    /** {@code "h1:p1,h2:p2"} → список seed'ов. Битые элементы пропускаются. */
    private static List<SeedNode> parseSeeds(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<SeedNode> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String s = part.trim();
            if (s.isEmpty()) continue;
            int colon = s.lastIndexOf(':');
            if (colon < 0) {
                log.warn("config.properties: пропускаю seed '{}' (нет порта)", s);
                continue;
            }
            String host = s.substring(0, colon).trim();
            String portStr = s.substring(colon + 1).trim();
            try {
                int port = Integer.parseInt(portStr);
                out.add(new SeedNode(host, port));
            } catch (IllegalArgumentException e) {
                log.warn("config.properties: пропускаю seed '{}': {}", s, e.getMessage());
            }
        }
        return out;
    }

    private static OptionalInt parseOptionalPort(String raw, String keyForLog) {
        if (raw == null || raw.isBlank()) return OptionalInt.empty();
        try {
            int p = Integer.parseInt(raw.trim());
            if (p < 1 || p > 65535) {
                log.warn("config.properties: {}={} вне диапазона, игнорирую",
                        keyForLog, p);
                return OptionalInt.empty();
            }
            return OptionalInt.of(p);
        } catch (NumberFormatException e) {
            log.warn("config.properties: {}='{}' не число", keyForLog, raw);
            return OptionalInt.empty();
        }
    }

    private static OptionalInt parseOptionalReplication(String raw) {
        if (raw == null || raw.isBlank()) return OptionalInt.empty();
        try {
            int rf = Integer.parseInt(raw.trim());
            // Жёсткий минимум 1; верхней границы тут не требуем — она
            // в NodeConfig.Builder проверяется.
            if (rf < 1) {
                log.warn("config.properties: replicationFactor={} <1, игнорирую", rf);
                return OptionalInt.empty();
            }
            return OptionalInt.of(rf);
        } catch (NumberFormatException e) {
            log.warn("config.properties: replicationFactor='{}' не число", raw);
            return OptionalInt.empty();
        }
    }

    // ------------------------------------------------------------------
    // Чтение
    // ------------------------------------------------------------------

    /** Снимок seed-узлов (защищённый от внешних мутаций). */
    public List<SeedNode> getSeeds() {
        return Collections.unmodifiableList(new ArrayList<>(seeds));
    }

    /** Опциональный override порта из конфига. */
    public OptionalInt getListenPort() {
        return listenPort;
    }

    /** Опциональный override replicationFactor из конфига. */
    public OptionalInt getReplicationFactor() {
        return replicationFactor;
    }

    /** Путь к файлу конфигурации (для UI и логов). */
    public Path file() {
        return file;
    }

    // ------------------------------------------------------------------
    // Мутации (с автосохранением)
    // ------------------------------------------------------------------

    /**
     * Полностью заменяет список seed-узлов и сохраняет файл.
     * Дубликаты по {@code host:port} автоматически схлопываются.
     */
    public void setSeeds(List<SeedNode> newSeeds) throws IOException {
        Objects.requireNonNull(newSeeds, "newSeeds");
        // Дедуп с сохранением порядка
        List<SeedNode> deduped = new ArrayList<>();
        for (SeedNode s : newSeeds) {
            if (s == null) continue;
            if (!deduped.contains(s)) deduped.add(s);
        }
        this.seeds = deduped;
        save();
    }

    /**
     * Добавляет один seed (если его ещё нет). Возвращает {@code true},
     * если seed реально добавлен (не был дубликатом).
     */
    public boolean addSeed(SeedNode s) throws IOException {
        Objects.requireNonNull(s, "seed");
        if (seeds.contains(s)) return false;
        seeds = new ArrayList<>(seeds);
        seeds.add(s);
        save();
        return true;
    }

    /**
     * Удаляет seed (по точному совпадению host+port). Возвращает
     * {@code true}, если что-то реально удалилось.
     */
    public boolean removeSeed(SeedNode s) throws IOException {
        Objects.requireNonNull(s, "seed");
        List<SeedNode> next = new ArrayList<>(seeds);
        boolean removed = next.remove(s);
        if (removed) {
            seeds = next;
            save();
        }
        return removed;
    }

    /** Установить overrride порта (или сбросить, передав empty). */
    public void setListenPort(OptionalInt port) throws IOException {
        if (port.isPresent() && (port.getAsInt() < 1 || port.getAsInt() > 65535)) {
            throw new IllegalArgumentException(
                    "Порт должен быть в диапазоне 1..65535: " + port.getAsInt());
        }
        this.listenPort = Objects.requireNonNull(port, "port");
        save();
    }

    /** Установить override replicationFactor (или сбросить). */
    public void setReplicationFactor(OptionalInt rf) throws IOException {
        if (rf.isPresent() && rf.getAsInt() < 1) {
            throw new IllegalArgumentException(
                    "replicationFactor должен быть ≥1: " + rf.getAsInt());
        }
        this.replicationFactor = Objects.requireNonNull(rf, "rf");
        save();
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    /**
     * Атомарная запись: сериализуем в {@code <file>.tmp}, затем
     * {@code Files.move} с {@code ATOMIC_MOVE}. На POSIX это атомарный
     * rename. {@link Properties#store} сам экранирует unicode и спец-
     * символы, поэтому файл всегда валиден.
     */
    private void save() throws IOException {
        Properties props = new Properties();
        if (!seeds.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < seeds.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(seeds.get(i).host()).append(':').append(seeds.get(i).port());
            }
            props.setProperty(KEY_SEEDS, sb.toString());
        }
        if (listenPort.isPresent()) {
            props.setProperty(KEY_LISTEN_PORT, String.valueOf(listenPort.getAsInt()));
        }
        if (replicationFactor.isPresent()) {
            props.setProperty(KEY_REPLICATION_FACTOR,
                    String.valueOf(replicationFactor.getAsInt()));
        }

        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);

        Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            props.store(out, "JBlockStorage user config — день 15");
        }
        try {
            Files.move(tmp, file,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // ATOMIC_MOVE не всегда поддерживается (кросс-FS) — fallback
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        log.debug("Сохранён {} (seeds={}, port={}, rf={})",
                file, seeds.size(), listenPort, replicationFactor);
    }

    // ------------------------------------------------------------------
    // Утилита для UI: распарсить «host:port» в SeedNode
    // ------------------------------------------------------------------

    /**
     * Разбирает строку «{@code host:port}» в {@link SeedNode}.
     * Удобно для UI «Добавить seed»: пользователь вводит одну строку,
     * мы её отдаём сюда, и либо получаем валидный seed, либо
     * {@link Optional#empty()}.
     */
    public static Optional<SeedNode> parseSingle(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String s = raw.trim();
        int colon = s.lastIndexOf(':');
        if (colon < 0) return Optional.empty();
        String host = s.substring(0, colon).trim();
        String portStr = s.substring(colon + 1).trim();
        try {
            int port = Integer.parseInt(portStr);
            return Optional.of(new SeedNode(host, port));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
