package ru.hse.jblockstorage.gui.contacts;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Адресная книга профиля — день 14.
 *
 * <p>Хранит список {@link Contact} в JSON-файле
 * {@code <JBS_HOME>/data/<profile>/contacts.json}. Аналогична
 * {@code peers.json} ({@link ru.hse.jblockstorage.network.PeerManager}):
 * грузится один раз при старте, сохраняется на каждое изменение
 * атомарно (запись в {@code .tmp} + {@code rename}). Это защищает от
 * частичной записи при SIGKILL посередине.
 *
 * <p><b>Семантика добавления.</b> Контакт уникален по
 * {@code publicKey} — это ровно соответствует {@code selfNodeId} в
 * сетевом протоколе. Добавление дубликата (попытка добавить тот же ключ
 * с другим именем) бросает {@link DuplicateKeyException} — UI покажет
 * пользователю, под каким именем этот ключ уже есть. Имя дубликатом не
 * считается: можно назвать двух людей «Боб», это не ошибка.
 *
 * <p><b>Сортировка.</b> Список всегда возвращается отсортированным по
 * имени (случай-нечувствительно, ru-локаль). Это упрощает UI: не нужно
 * каждый раз пересортировывать. Для имён, начинающихся одинаково,
 * fallback — стабильный порядок добавления.
 *
 * <p><b>Thread-safety.</b> Не thread-safe — все мутации делаются с
 * UI-thread (как и {@code AppContext}).
 *
 * <p><b>Не путать с {@code PeerManager}.</b> Это разные сущности:
 * <ul>
 *   <li>{@code peers.json} — кэш сетевых соседей (host:port + nodeId),
 *       гонится gossip'ом, нужен для bootstrap и доставки.</li>
 *   <li>{@code contacts.json} — адресная книга пользователя
 *       (имя + publicKey), руками добавляется владельцем профиля. Не
 *       обязательно «знакомый узел» вообще когда-либо был онлайн в
 *       нашей сети — главное, что мы знаем его публичный ключ.</li>
 * </ul>
 */
public final class ContactStore {

    private static final Logger log = LoggerFactory.getLogger(ContactStore.class);

    /** Минимальная длина Base64-публичного ключа (RSA-2048 ≈ 392 символа). С запасом. */
    public static final int MIN_KEY_LENGTH = 200;

    /** Алфавит Base64 (с padding). См. валидацию в {@link #add}. */
    private static final Pattern BASE64_PATTERN = Pattern.compile("^[A-Za-z0-9+/=]+$");

    private final Path file;
    private final List<Contact> contacts = new ArrayList<>();

    /**
     * Открывает (или создаёт пустой) контакт-стор. Если файл существует —
     * грузит его; если нет — стартует с пустым списком, файл будет создан
     * при первом сохранении.
     *
     * @param file путь к {@code contacts.json}. Родительская папка должна
     *             существовать (обычно это {@code data/<profile>/}, она
     *             создаётся при логине).
     */
    public ContactStore(Path file) throws IOException {
        this.file = Objects.requireNonNull(file, "file");
        load();
    }

    // ------------------------------------------------------------------
    // Чтение
    // ------------------------------------------------------------------

    /**
     * Снимок списка — отсортированный по имени, защищённый от мутации.
     * Возвращает копию, чтобы внешний код не смог зацепить внутренний
     * список и сломать инвариант сортировки.
     */
    public List<Contact> all() {
        List<Contact> copy = new ArrayList<>(contacts);
        copy.sort(byNameThenAdded());
        return Collections.unmodifiableList(copy);
    }

    /** Сколько контактов сейчас в книге. */
    public int size() {
        return contacts.size();
    }

    /** Есть ли в книге контакт с таким публичным ключом. */
    public boolean hasKey(String publicKey) {
        if (publicKey == null) return false;
        String normalized = publicKey.replaceAll("\\s+", "");
        for (Contact c : contacts) {
            if (c.getPublicKey().equals(normalized)) return true;
        }
        return false;
    }

    /** Найти контакт по публичному ключу (или null). */
    public Contact findByKey(String publicKey) {
        if (publicKey == null) return null;
        String normalized = publicKey.replaceAll("\\s+", "");
        for (Contact c : contacts) {
            if (c.getPublicKey().equals(normalized)) return c;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Мутации
    // ------------------------------------------------------------------

    /**
     * Добавляет новый контакт.
     *
     * @param name      пользовательский лейбл (1..{@link Contact#MAX_NAME_LENGTH})
     * @param publicKey Base64-публичный ключ получателя
     * @return созданный {@link Contact}
     * @throws IllegalArgumentException если имя или ключ невалидны
     * @throws DuplicateKeyException    если такой ключ уже в книге
     * @throws IOException              если не удалось сохранить файл
     */
    public Contact add(String name, String publicKey) throws IOException {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Имя контакта не может быть пустым");
        }
        if (name.trim().length() > Contact.MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "Имя длиннее " + Contact.MAX_NAME_LENGTH + " символов");
        }
        if (publicKey == null) {
            throw new IllegalArgumentException("Публичный ключ обязателен");
        }
        String normalizedKey = publicKey.replaceAll("\\s+", "");
        if (normalizedKey.length() < MIN_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "Публичный ключ слишком короткий (нужно ≥" + MIN_KEY_LENGTH + " символов)");
        }
        if (!BASE64_PATTERN.matcher(normalizedKey).matches()) {
            throw new IllegalArgumentException(
                    "Публичный ключ должен быть в Base64 (буквы, цифры, +, /, =)");
        }
        Contact existing = findByKey(normalizedKey);
        if (existing != null) {
            throw new DuplicateKeyException(
                    "Этот публичный ключ уже есть в адресной книге под именем «"
                            + existing.getName() + "»");
        }

        Contact c = new Contact(name, normalizedKey, System.currentTimeMillis());
        contacts.add(c);
        save();
        log.info("Добавлен контакт: «{}» · {}…", c.getName(),
                c.getPublicKey().substring(0, 8));
        return c;
    }

    /**
     * Удаляет контакт по публичному ключу. Идемпотентен — если такого
     * ключа нет, ничего не делает.
     *
     * @return {@code true}, если контакт действительно был удалён
     */
    public boolean removeByKey(String publicKey) throws IOException {
        if (publicKey == null) return false;
        String normalized = publicKey.replaceAll("\\s+", "");
        boolean removed = contacts.removeIf(c -> c.getPublicKey().equals(normalized));
        if (removed) {
            save();
            log.info("Удалён контакт с ключом {}…", normalized.substring(0, 8));
        }
        return removed;
    }

    /**
     * Переименовывает контакт по публичному ключу. Если ключ не найден —
     * бросает {@link IllegalArgumentException}.
     */
    public void rename(String publicKey, String newName) throws IOException {
        if (newName == null || newName.trim().isEmpty()) {
            throw new IllegalArgumentException("Имя контакта не может быть пустым");
        }
        if (newName.trim().length() > Contact.MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "Имя длиннее " + Contact.MAX_NAME_LENGTH + " символов");
        }
        String normalizedKey = publicKey == null ? null : publicKey.replaceAll("\\s+", "");
        for (int i = 0; i < contacts.size(); i++) {
            Contact c = contacts.get(i);
            if (c.getPublicKey().equals(normalizedKey)) {
                contacts.set(i, new Contact(newName, c.getPublicKey(), c.getAddedAt()));
                save();
                return;
            }
        }
        throw new IllegalArgumentException("Контакт с таким ключом не найден");
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    /** Загрузка из файла на диске. Если файла нет — список остаётся пустым. */
    private void load() throws IOException {
        contacts.clear();
        if (!Files.isRegularFile(file)) {
            log.debug("contacts.json не существует — стартуем с пустой адресной книгой");
            return;
        }
        ObjectMapper mapper = new ObjectMapper();
        // Прощаем неизвестные поля — на случай миграции схемы вперёд-назад.
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        try {
            List<Contact> loaded = mapper.readValue(
                    file.toFile(),
                    mapper.getTypeFactory().constructCollectionType(List.class, Contact.class));
            for (Contact c : loaded) {
                if (c == null) continue;
                if (c.getPublicKey() == null || c.getPublicKey().length() < MIN_KEY_LENGTH) {
                    log.warn("contacts.json: пропущен контакт с подозрительным ключом");
                    continue;
                }
                // Дедуп по ключу — на случай, если файл редактировали руками.
                if (!hasKey(c.getPublicKey())) {
                    contacts.add(c);
                }
            }
            log.info("Загружено контактов: {}", contacts.size());
        } catch (IOException e) {
            // Битый файл — не падаем целиком, но шумим в лог. Пользователь
            // увидит пустую адресную книгу и сможет добавить заново.
            log.warn("Не удалось разобрать contacts.json: {}. Продолжаем с пустой книгой.",
                    e.toString());
            contacts.clear();
        }
    }

    /**
     * Атомарная запись: сериализуем в {@code <file>.tmp}, затем
     * {@code Files.move} с {@code ATOMIC_MOVE}. На POSIX это атомарный
     * rename, на Windows — best-effort, но Jackson всё равно записывает
     * файл целиком, поэтому худшее, что может случиться — старая версия.
     */
    private void save() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        mapper.writerWithDefaultPrettyPrinter()
                .writeValue(tmp.toFile(), contacts);
        try {
            Files.move(tmp, file,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // ATOMIC_MOVE может не поддерживаться (например, кросс-FS) —
            // делаем обычный move как fallback.
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        log.debug("Сохранено {} контактов в {}", contacts.size(), file);
    }

    // ------------------------------------------------------------------
    // Утилиты
    // ------------------------------------------------------------------

    /**
     * Сортировка по имени с учётом ru-локали (case-insensitive).
     * При равных именах — стабильно по {@code addedAt} (раньше добавленный
     * выше). Это нужно, чтобы UI не «прыгал» при перерисовке.
     */
    private static Comparator<Contact> byNameThenAdded() {
        Locale ru = new Locale("ru", "RU");
        java.text.Collator coll = java.text.Collator.getInstance(ru);
        coll.setStrength(java.text.Collator.PRIMARY);
        return Comparator
                .comparing(Contact::getName, coll)
                .thenComparingLong(Contact::getAddedAt);
    }

    /**
     * Сигнальное исключение: попытка добавить контакт с уже известным
     * ключом. UI ловит специально, чтобы показать «уже есть под именем X».
     */
    public static final class DuplicateKeyException extends IllegalStateException {
        public DuplicateKeyException(String message) {
            super(message);
        }
    }
}
