package ru.hse.jblockstorage.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Юнит-тесты простого парсера аргументов командной строки.
 * <p>
 * Проверяем все три формата ({@code --key=value}, {@code --key value} и
 * чистый флаг {@code --flag}), отсутствие/наличие субкоманды и корректную
 * обработку ошибок.
 */
class SimpleArgsTest {

    @Test
    void parsesSubcommandAndKeyValueWithEquals() {
        SimpleArgs args = SimpleArgs.parse(new String[]{
                "upload", "--config=node.yaml", "--file=doc.pdf"
        });

        assertEquals("upload", args.subcommand());
        assertEquals("node.yaml", args.require("config"));
        assertEquals("doc.pdf", args.require("file"));
    }

    @Test
    void parsesKeyValueWithSpace() {
        SimpleArgs args = SimpleArgs.parse(new String[]{
                "download", "--tx-id", "abc123", "--output", "/tmp/out.bin"
        });

        assertEquals("download", args.subcommand());
        assertEquals("abc123", args.require("tx-id"));
        assertEquals("/tmp/out.bin", args.require("output"));
    }

    @Test
    void parsesFlagWithoutValue() {
        // --verbose в конце — должен стать "true".
        SimpleArgs args = SimpleArgs.parse(new String[]{
                "list", "--config=node.yaml", "--verbose"
        });

        assertEquals("list", args.subcommand());
        assertEquals("true", args.getOrDefault("verbose", "false"));
    }

    @Test
    void parsesFlagFollowedByOption() {
        // --verbose сразу за ним --config: --verbose должен стать "true",
        // а не "съесть" следующий ключ как значение.
        SimpleArgs args = SimpleArgs.parse(new String[]{
                "start-node", "--verbose", "--config=node.yaml"
        });

        assertEquals("start-node", args.subcommand());
        assertEquals("true", args.getOrDefault("verbose", "false"));
        assertEquals("node.yaml", args.require("config"));
    }

    @Test
    void noArgsReturnsNullSubcommand() {
        SimpleArgs args = SimpleArgs.parse(new String[0]);
        assertNull(args.subcommand());
        assertTrue(args.options().isEmpty());
    }

    @Test
    void onlyOptionsNoSubcommand() {
        // Когда первый аргумент уже опция — субкоманда null
        SimpleArgs args = SimpleArgs.parse(new String[]{"--help"});
        assertNull(args.subcommand());
        assertEquals("true", args.getOrDefault("help", "false"));
    }

    @Test
    void requireThrowsForMissingKey() {
        SimpleArgs args = SimpleArgs.parse(new String[]{"upload"});
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> args.require("file"));
        assertTrue(ex.getMessage().contains("file"),
                "Сообщение об ошибке должно упоминать имя параметра");
    }

    @Test
    void unknownPositionalArgRejected() {
        // После субкоманды позиционные аргументы не разрешены.
        assertThrows(IllegalArgumentException.class,
                () -> SimpleArgs.parse(new String[]{"upload", "extra-pos", "--config=x"}));
    }

    @Test
    void emptyKeyRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> SimpleArgs.parse(new String[]{"--=value"}));
    }

    @Test
    void getReturnsEmptyOptionalForMissingKey() {
        SimpleArgs args = SimpleArgs.parse(new String[]{"list", "--config=x"});
        assertTrue(args.get("nonexistent").isEmpty());
        assertEquals("default", args.getOrDefault("nonexistent", "default"));
    }
}
