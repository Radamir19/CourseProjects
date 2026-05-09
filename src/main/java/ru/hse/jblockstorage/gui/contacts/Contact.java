package ru.hse.jblockstorage.gui.contacts;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Запись адресной книги пользователя — день 14.
 *
 * <p>«Контакт» — это локальная для конкретного профиля метка, которая
 * связывает удобочитаемое имя («Боб», «Команда HSE») с публичным
 * ключом другого узла сети. Контакты не публикуются в блокчейн и не
 * расшариваются по gossip — это чисто UX-сущность, существующая только
 * на диске владельца профиля в {@code <JBS_HOME>/data/<profile>/contacts.json}.
 *
 * <p><b>Зачем.</b> Без контактов любой обмен файлами требует ручного
 * копирования Base64-ключа длиной ≈392 символа. С контактами «расшарить
 * Бобу файл» сводится к клику чекбокса — публичный ключ подставляется
 * автоматически. Это полностью UX-слой над существующей криптографией:
 * семантика {@code shareFile(txId, recipientPublicKey)} не меняется.
 *
 * <p><b>Структура.</b> Иммутабельный record-like:
 * <ul>
 *   <li>{@code name} — пользовательский лейбл (1..64 символа, любой
 *       Unicode). Не уникален: можно назвать «Боб» нескольких людей,
 *       но это, скорее, ошибка пользователя — UI это покажет.</li>
 *   <li>{@code publicKey} — Base64 RSA-публичного ключа получателя
 *       (≈392 символа). Должен совпадать с {@code selfNodeId} на стороне
 *       получателя.</li>
 *   <li>{@code addedAt} — unix-millis добавления в адресную книгу. Только
 *       для отображения «когда добавлен». Сортировок по добавлению пока
 *       нет: список идёт по имени.</li>
 * </ul>
 *
 * <p>Сделан как обычный {@code final class}, не record, чтобы Jackson мог
 * без дополнительных аннотаций (де)сериализовать его и в контейнере JsonNode
 * (если в будущем понадобится мигрировать схему — добавить поле без
 * перелопачивания всего файла).
 */
public final class Contact {

    /** Максимальная длина имени контакта в UTF-16 code units. */
    public static final int MAX_NAME_LENGTH = 64;

    private final String name;
    private final String publicKey;
    private final long addedAt;

    @JsonCreator
    public Contact(
            @JsonProperty("name") String name,
            @JsonProperty("publicKey") String publicKey,
            @JsonProperty("addedAt") long addedAt) {
        this.name = sanitizeName(Objects.requireNonNull(name, "name"));
        this.publicKey = sanitizeKey(Objects.requireNonNull(publicKey, "publicKey"));
        this.addedAt = addedAt;
    }

    public String getName() {
        return name;
    }

    public String getPublicKey() {
        return publicKey;
    }

    public long getAddedAt() {
        return addedAt;
    }

    /**
     * Урезает и нормализует имя: trim'им пробелы, обрезаем по
     * {@link #MAX_NAME_LENGTH}. Пустое имя — это ошибка использования,
     * валидация делается выше (см. {@link ContactStore#add}).
     */
    private static String sanitizeName(String raw) {
        String s = raw.trim();
        if (s.length() > MAX_NAME_LENGTH) {
            s = s.substring(0, MAX_NAME_LENGTH);
        }
        return s;
    }

    /**
     * Удаляет все пробелы/переносы из ключа — пользователи иногда
     * копируют ключ построчно, и принципиально это валидный кейс, ведь
     * содержимое (Base64-алфавит) не теряется. Глубокую валидацию
     * выполняет {@link ContactStore#add}.
     */
    private static String sanitizeKey(String raw) {
        return raw.replaceAll("\\s+", "");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Contact other)) return false;
        return addedAt == other.addedAt
                && Objects.equals(name, other.name)
                && Objects.equals(publicKey, other.publicKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, publicKey, addedAt);
    }

    @Override
    public String toString() {
        return "Contact{" + name + " · " + publicKey.substring(0, Math.min(8, publicKey.length()))
                + "… · " + addedAt + "}";
    }
}
