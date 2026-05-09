package ru.hse.jblockstorage.gui.contacts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тесты {@link ContactStore} — день 14.
 *
 * <p>Покрывают: добавление/удаление/переименование, валидацию (пустое имя,
 * короткий ключ, не-Base64), детект дубликатов по ключу, сортировку,
 * round-trip через файл, корректную обработку отсутствующего/битого файла,
 * атомарную запись.
 */
class ContactStoreTest {

    /** Минимальный валидный ключ — 200 символов в Base64-алфавите. */
    private static final String VALID_KEY_A = "A".repeat(200);
    private static final String VALID_KEY_B = "B".repeat(200);
    private static final String VALID_KEY_C = "ABCDEFGH".repeat(50); // 400 chars

    @Test
    void emptyOnFreshFile(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("contacts.json");
        ContactStore store = new ContactStore(file);
        assertEquals(0, store.size());
        assertTrue(store.all().isEmpty());
        assertFalse(store.hasKey(VALID_KEY_A));
        assertNull(store.findByKey(VALID_KEY_A));
    }

    @Test
    void addContactThenSeeIt(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("contacts.json");
        ContactStore store = new ContactStore(file);

        Contact added = store.add("Боб", VALID_KEY_A);

        assertEquals("Боб", added.getName());
        assertEquals(VALID_KEY_A, added.getPublicKey());
        assertTrue(added.getAddedAt() > 0);

        assertEquals(1, store.size());
        assertTrue(store.hasKey(VALID_KEY_A));
        assertNotNull(store.findByKey(VALID_KEY_A));
    }

    @Test
    void rejectsEmptyName(@TempDir Path tmp) throws IOException {
        ContactStore store = new ContactStore(tmp.resolve("contacts.json"));
        assertThrows(IllegalArgumentException.class,
                () -> store.add("", VALID_KEY_A));
        assertThrows(IllegalArgumentException.class,
                () -> store.add("   ", VALID_KEY_A));
        // null-имя нам сообщает «Имя контакта не может быть пустым» —
        // это IAE с понятным сообщением, не NPE. Так осмысленнее в UI.
        assertThrows(IllegalArgumentException.class,
                () -> store.add(null, VALID_KEY_A));
    }

    @Test
    void rejectsTooLongName(@TempDir Path tmp) throws IOException {
        ContactStore store = new ContactStore(tmp.resolve("contacts.json"));
        String longName = "x".repeat(Contact.MAX_NAME_LENGTH + 1);
        assertThrows(IllegalArgumentException.class,
                () -> store.add(longName, VALID_KEY_A));
    }

    @Test
    void rejectsShortKey(@TempDir Path tmp) throws IOException {
        ContactStore store = new ContactStore(tmp.resolve("contacts.json"));
        assertThrows(IllegalArgumentException.class,
                () -> store.add("Боб", "short"));
        // null-ключ тоже как IAE «Публичный ключ обязателен».
        assertThrows(IllegalArgumentException.class,
                () -> store.add("Боб", null));
    }

    @Test
    void rejectsNonBase64Key(@TempDir Path tmp) throws IOException {
        ContactStore store = new ContactStore(tmp.resolve("contacts.json"));
        // Кириллица в ключе — невалидно (не Base64-алфавит)
        String badKey = "Я".repeat(200);
        assertThrows(IllegalArgumentException.class,
                () -> store.add("Боб", badKey));
    }

    @Test
    void duplicateKeyThrowsAndExposesExistingName(@TempDir Path tmp) throws IOException {
        ContactStore store = new ContactStore(tmp.resolve("contacts.json"));
        store.add("Боб", VALID_KEY_A);

        ContactStore.DuplicateKeyException ex = assertThrows(
                ContactStore.DuplicateKeyException.class,
                () -> store.add("Не Боб", VALID_KEY_A));
        assertTrue(ex.getMessage().contains("Боб"),
                "сообщение об ошибке должно подсказать существующее имя");
    }

    @Test
    void duplicateNameIsAllowed(@TempDir Path tmp) throws IOException {
        ContactStore store = new ContactStore(tmp.resolve("contacts.json"));
        store.add("Боб", VALID_KEY_A);
        // Двух Бобов добавить можно — это разные люди с разными ключами
        assertDoesNotThrow(() -> store.add("Боб", VALID_KEY_B));
        assertEquals(2, store.size());
    }

    @Test
    void keyIsNormalizedFromWhitespace(@TempDir Path tmp) throws IOException {
        ContactStore store = new ContactStore(tmp.resolve("contacts.json"));
        // Пользователи копируют ключи иногда с переносами — должны
        // нормально приниматься
        String keyWithBreaks = "AAAA\n" + "BBBB\n".repeat(50);
        Contact c = store.add("Боб", keyWithBreaks);
        assertFalse(c.getPublicKey().contains("\n"));
        assertFalse(c.getPublicKey().contains(" "));
    }

    @Test
    void roundTripThroughFile(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("contacts.json");

        ContactStore store1 = new ContactStore(file);
        store1.add("Боб", VALID_KEY_A);
        store1.add("Алиса", VALID_KEY_B);

        // Файл создан и не пустой
        assertTrue(Files.isRegularFile(file));
        assertTrue(Files.size(file) > 0);

        // Создаём заново и проверяем, что данные те же
        ContactStore store2 = new ContactStore(file);
        assertEquals(2, store2.size());
        assertTrue(store2.hasKey(VALID_KEY_A));
        assertTrue(store2.hasKey(VALID_KEY_B));
    }

    @Test
    void corruptedFileGivesEmptyStoreNotCrash(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("contacts.json");
        Files.writeString(file, "{ this is not valid json }");

        // Должны открыться, но с пустым списком
        ContactStore store = new ContactStore(file);
        assertEquals(0, store.size());
    }

    @Test
    void sortOrderIsByName(@TempDir Path tmp) throws IOException {
        ContactStore store = new ContactStore(tmp.resolve("contacts.json"));
        store.add("Виктор", VALID_KEY_A);
        store.add("Алиса",  VALID_KEY_B);
        store.add("Боб",    VALID_KEY_C);

        List<Contact> all = store.all();
        assertEquals("Алиса",  all.get(0).getName());
        assertEquals("Боб",    all.get(1).getName());
        assertEquals("Виктор", all.get(2).getName());
    }

    @Test
    void sortIsCaseInsensitive(@TempDir Path tmp) throws IOException {
        ContactStore store = new ContactStore(tmp.resolve("contacts.json"));
        store.add("боб",   VALID_KEY_A);
        store.add("Алиса", VALID_KEY_B);

        // «Алиса» < «боб» case-insensitive
        assertEquals("Алиса", store.all().get(0).getName());
    }

    @Test
    void removeByKeyWorks(@TempDir Path tmp) throws IOException {
        ContactStore store = new ContactStore(tmp.resolve("contacts.json"));
        store.add("Боб", VALID_KEY_A);
        store.add("Алиса", VALID_KEY_B);

        assertTrue(store.removeByKey(VALID_KEY_A));
        assertEquals(1, store.size());
        assertFalse(store.hasKey(VALID_KEY_A));
        assertTrue(store.hasKey(VALID_KEY_B));
    }

    @Test
    void removeByKeyIsIdempotent(@TempDir Path tmp) throws IOException {
        ContactStore store = new ContactStore(tmp.resolve("contacts.json"));
        // Удаление несуществующего — не падает, возвращает false
        assertFalse(store.removeByKey(VALID_KEY_A));
        assertFalse(store.removeByKey(null));
    }

    @Test
    void renameWorks(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("contacts.json");
        ContactStore store = new ContactStore(file);
        store.add("Боб", VALID_KEY_A);

        store.rename(VALID_KEY_A, "Роберт");

        Contact c = store.findByKey(VALID_KEY_A);
        assertNotNull(c);
        assertEquals("Роберт", c.getName());

        // Перечитываем — изменения сохранены
        ContactStore store2 = new ContactStore(file);
        assertEquals("Роберт", store2.findByKey(VALID_KEY_A).getName());
    }

    @Test
    void renameThrowsWhenKeyNotFound(@TempDir Path tmp) throws IOException {
        ContactStore store = new ContactStore(tmp.resolve("contacts.json"));
        assertThrows(IllegalArgumentException.class,
                () -> store.rename(VALID_KEY_A, "Кто-то"));
    }

    @Test
    void renameValidatesEmptyName(@TempDir Path tmp) throws IOException {
        ContactStore store = new ContactStore(tmp.resolve("contacts.json"));
        store.add("Боб", VALID_KEY_A);
        assertThrows(IllegalArgumentException.class,
                () -> store.rename(VALID_KEY_A, ""));
        assertThrows(IllegalArgumentException.class,
                () -> store.rename(VALID_KEY_A, "   "));
    }

    @Test
    void allReturnsImmutableSnapshot(@TempDir Path tmp) throws IOException {
        ContactStore store = new ContactStore(tmp.resolve("contacts.json"));
        store.add("Боб", VALID_KEY_A);

        List<Contact> snap = store.all();
        assertThrows(UnsupportedOperationException.class,
                () -> snap.add(new Contact("X", VALID_KEY_B, 0)));
    }

    @Test
    void atomicWriteDoesNotLeaveTmpFile(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("contacts.json");
        ContactStore store = new ContactStore(file);
        store.add("Боб", VALID_KEY_A);

        // После успешной записи tmp-файла быть не должно
        Path tmpFile = tmp.resolve("contacts.json.tmp");
        assertFalse(Files.exists(tmpFile),
                "tmp-файл должен быть удалён после атомарной записи");
    }
}
