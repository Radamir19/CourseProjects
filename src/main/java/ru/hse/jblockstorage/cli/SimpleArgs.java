package ru.hse.jblockstorage.cli;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Минималистичный парсер аргументов командной строки в стиле {@code --key=value}
 * и {@code --key value}, без зависимостей.
 * <p>
 * Поддерживает только именованные параметры (флаги без значения тоже допустимы —
 * у них значение будет {@code "true"}). Первый позиционный аргумент трактуется
 * как имя субкоманды и доступен через {@link #subcommand()}.
 * </p>
 *
 * <h3>Примеры</h3>
 * <pre>
 *   --port=8080  --keys=./node.keys  --verbose
 *   --port 8080  --keys ./node.keys  --verbose
 * </pre>
 *
 * <h3>Зачем своё, а не picocli</h3>
 * Минимизируем число зависимостей в curriculum-проекте. Парсер занимает
 * полсотни строк и покрывает 100% наших нужд — пять команд с двумя-тремя
 * параметрами каждая. Появится сложность — заменим на picocli одной правкой
 * {@code build.gradle.kts}.
 */
public final class SimpleArgs {

    private final String subcommand;
    private final Map<String, String> options;

    private SimpleArgs(String subcommand, Map<String, String> options) {
        this.subcommand = subcommand;
        this.options = options;
    }

    /**
     * Разбирает массив аргументов вида:
     * <pre>
     *   subcommand [--key=value] [--key value] [--flag]
     * </pre>
     *
     * @param argv исходный {@code args} из {@code main}
     */
    public static SimpleArgs parse(String[] argv) {
        if (argv == null || argv.length == 0) {
            return new SimpleArgs(null, new LinkedHashMap<>());
        }

        String subcommand = null;
        Map<String, String> options = new LinkedHashMap<>();

        int i = 0;
        if (!argv[0].startsWith("-")) {
            subcommand = argv[0];
            i = 1;
        }

        while (i < argv.length) {
            String arg = argv[i];
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException(
                        "Неожиданный аргумент '" + arg + "': ожидался --key или --key=value");
            }
            String body = arg.substring(2);
            int eq = body.indexOf('=');
            if (eq >= 0) {
                String key = body.substring(0, eq);
                String value = body.substring(eq + 1);
                if (key.isEmpty()) {
                    throw new IllegalArgumentException("Пустой ключ в '" + arg + "'");
                }
                options.put(key, value);
                i++;
            } else {
                // Либо это флаг, либо значение в следующем аргументе.
                String key = body;
                if (key.isEmpty()) {
                    throw new IllegalArgumentException("Пустой ключ в '" + arg + "'");
                }
                if (i + 1 < argv.length && !argv[i + 1].startsWith("--")) {
                    options.put(key, argv[i + 1]);
                    i += 2;
                } else {
                    options.put(key, "true");
                    i++;
                }
            }
        }

        return new SimpleArgs(subcommand, options);
    }

    /** Имя субкоманды (первый позиционный аргумент) или {@code null}, если её нет. */
    public String subcommand() {
        return subcommand;
    }

    /** Значение опции по ключу. */
    public Optional<String> get(String key) {
        return Optional.ofNullable(options.get(key));
    }

    /** Значение опции по ключу, или указанное значение по умолчанию. */
    public String getOrDefault(String key, String defaultValue) {
        return options.getOrDefault(key, defaultValue);
    }

    /**
     * Значение опции по ключу с обязательностью. Если не задано —
     * кидает {@link IllegalArgumentException} с понятным сообщением.
     */
    public String require(String key) {
        String v = options.get(key);
        if (v == null) {
            throw new IllegalArgumentException(
                    "Не задан обязательный параметр --" + key);
        }
        return v;
    }

    /** Все распарсенные опции в порядке появления (для отладки и логирования). */
    public Map<String, String> options() {
        return new LinkedHashMap<>(options);
    }
}
