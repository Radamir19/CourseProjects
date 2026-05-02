package ru.hse.jblockstorage.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Простой YAML-парсер для конфигурации узла.
 * <p>
 * Намеренно не используем Jackson YAML — не хочется тянуть ещё одну зависимость
 * ради чтения конфига из десятка строк. Парсер понимает только тот узкий
 * подмножество YAML, которое нам нужно: плоские пары {@code key: value}
 * и одноуровневые списки через {@code -}.
 * </p>
 *
 * <h3>Поддерживаемый синтаксис</h3>
 * <pre>
 *   # комментарий до конца строки
 *   listenPort: 8080
 *   pingIntervalSeconds: 10
 *   peerTimeoutSeconds: 30
 *   gossipIntervalSeconds: 15
 *   maxPeers: 16
 *   protocolVersion: 1
 *
 *   # Параметры дня 7 (CLI)
 *   keysFile: ./node.keys
 *   keysPassword: secretPin
 *   shardsDir: ./data/shards
 *   blockchainDir: ./data/blockchain
 *
 *   seedNodes:
 *     - 127.0.0.1:8081
 *     - 127.0.0.1:8082
 * </pre>
 *
 * <h3>Что НЕ поддерживается</h3>
 * Вложенные объекты, многострочные значения, кавычки, якоря, тэги.
 * Если конфиг разрастётся — переключимся на Jackson YAML, эту реализацию
 * выкинем целиком, никакого внешнего API она не предоставляет.
 */
public final class ConfigLoader {

    private ConfigLoader() {}

    /** Читает конфиг из файла на диске. */
    public static NodeConfig fromFile(Path path) throws IOException {
        return fromString(Files.readString(path));
    }

    /** Парсит конфиг из строки. */
    public static NodeConfig fromString(String yaml) {
        // 1. Считываем построчно: scalar-параметры идут в map, списки — в отдельную map
        Map<String, String> scalars = new HashMap<>();
        Map<String, List<String>> lists = new HashMap<>();

        String currentListKey = null;
        List<String> currentList = null;

        int lineNo = 0;
        for (String rawLine : yaml.split("\\R", -1)) {
            lineNo++;
            // Срезаем комментарии (# и до конца строки)
            int hash = rawLine.indexOf('#');
            String line = hash >= 0 ? rawLine.substring(0, hash) : rawLine;
            if (line.isBlank()) continue;

            if (line.startsWith("  - ") || line.startsWith("- ") || line.startsWith("\t- ")) {
                // Элемент списка
                if (currentList == null) {
                    throw new IllegalArgumentException(
                            "Строка " + lineNo + ": элемент списка без объявленного ключа: " + rawLine);
                }
                String value = line.substring(line.indexOf('-') + 1).trim();
                if (!value.isEmpty()) currentList.add(value);
                continue;
            }

            // Если строка не отступлена — это новая пара key: value на верхнем уровне
            if (Character.isWhitespace(line.charAt(0))) {
                throw new IllegalArgumentException(
                        "Строка " + lineNo + ": неожиданный отступ: " + rawLine);
            }

            int colon = line.indexOf(':');
            if (colon < 0) {
                throw new IllegalArgumentException(
                        "Строка " + lineNo + ": ожидалось 'key: value' или 'key:': " + rawLine);
            }
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (key.isEmpty()) {
                throw new IllegalArgumentException("Строка " + lineNo + ": пустой ключ");
            }

            if (value.isEmpty()) {
                // Открываем список
                currentList = new ArrayList<>();
                currentListKey = key;
                lists.put(currentListKey, currentList);
            } else {
                scalars.put(key, value);
                currentList = null;
                currentListKey = null;
            }
        }

        // 2. Конвертируем собранные значения в NodeConfig через Builder
        NodeConfig.Builder builder = new NodeConfig.Builder();

        if (scalars.containsKey("listenPort")) {
            builder.listenPort(parseInt(scalars.get("listenPort"), "listenPort"));
        }
        if (scalars.containsKey("pingIntervalSeconds")) {
            builder.pingInterval(Duration.ofSeconds(
                    parseInt(scalars.get("pingIntervalSeconds"), "pingIntervalSeconds")));
        }
        if (scalars.containsKey("peerTimeoutSeconds")) {
            builder.peerTimeout(Duration.ofSeconds(
                    parseInt(scalars.get("peerTimeoutSeconds"), "peerTimeoutSeconds")));
        }
        if (scalars.containsKey("gossipIntervalSeconds")) {
            builder.gossipInterval(Duration.ofSeconds(
                    parseInt(scalars.get("gossipIntervalSeconds"), "gossipIntervalSeconds")));
        }
        if (scalars.containsKey("maxPeers")) {
            builder.maxPeers(parseInt(scalars.get("maxPeers"), "maxPeers"));
        }
        if (scalars.containsKey("protocolVersion")) {
            builder.protocolVersion(parseInt(scalars.get("protocolVersion"), "protocolVersion"));
        }

        // Поля дня 7 — все опциональные строки.
        if (scalars.containsKey("keysFile")) {
            builder.keysFile(scalars.get("keysFile"));
        }
        if (scalars.containsKey("keysPassword")) {
            builder.keysPassword(scalars.get("keysPassword"));
        }
        if (scalars.containsKey("shardsDir")) {
            builder.shardsDir(scalars.get("shardsDir"));
        }
        if (scalars.containsKey("blockchainDir")) {
            builder.blockchainDir(scalars.get("blockchainDir"));
        }

        List<String> seeds = lists.get("seedNodes");
        if (seeds != null) {
            List<SeedNode> parsed = new ArrayList<>();
            for (String item : seeds) {
                parsed.add(parseSeed(item));
            }
            builder.seedNodes(parsed);
        }

        return builder.build();
    }

    private static int parseInt(String value, String fieldName) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Поле '" + fieldName + "' должно быть целым числом, получено: '" + value + "'");
        }
    }

    private static SeedNode parseSeed(String hostPort) {
        int colon = hostPort.lastIndexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException(
                    "Seed node должен быть в формате host:port, получено: '" + hostPort + "'");
        }
        String host = hostPort.substring(0, colon).trim();
        int port = parseInt(hostPort.substring(colon + 1).trim(), "seed port");
        return new SeedNode(host, port);
    }
}
