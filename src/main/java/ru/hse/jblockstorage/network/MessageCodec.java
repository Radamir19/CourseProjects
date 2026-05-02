package ru.hse.jblockstorage.network;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * Сериализация и десериализация сообщений {@link Message} в JSON.
 * <p>
 * Используется один общий {@link ObjectMapper} — он thread-safe и его
 * рекомендуют переиспользовать (создание мэппера дорогое).
 * </p>
 *
 * <h3>Формат на проводе</h3>
 * Чистый JSON в UTF-8. Тип сообщения определяется полем {@code "type"}
 * (см. {@link Message}). Длину кадра при передаче по TCP добавляет/убирает
 * не этот класс, а Netty-ные {@code LengthFieldBasedFrameDecoder} и
 * {@code LengthFieldPrepender} в пайплайне.
 *
 * <h3>FAIL_ON_UNKNOWN_PROPERTIES</h3>
 * Отключено, чтобы добавление новых полей в сообщения не ломало старые
 * клиенты и наоборот — типичная практика для эволюции wire-протокола.
 */
public final class MessageCodec {

    /** Максимальный разумный размер одного кадра — 8 МБ.
     *  Шард 512 КБ в Base64 ≈ 700 КБ + JSON-обёртка, запас большой. */
    public static final int MAX_FRAME_LENGTH = 8 * 1024 * 1024;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private MessageCodec() {}

    /**
     * Сериализует сообщение в JSON-байты (UTF-8).
     * Бросает {@link IllegalStateException} при ошибке сериализации —
     * на практике это означает баг (например, циклическая ссылка),
     * checked exception тут только отвлекает.
     */
    public static byte[] encode(Message message) {
        try {
            return MAPPER.writeValueAsBytes(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Не удалось сериализовать сообщение " + message.getClass().getSimpleName(), e);
        }
    }

    /**
     * Десериализует JSON-байты в сообщение нужного подтипа
     * (Jackson выбирает класс по полю {@code "type"} — см. {@link Message}).
     * <p>
     * Бросает {@link IllegalArgumentException} при битом или незнакомом JSON —
     * это «нормальная» ошибка протокола (нам прислали мусор), вызывающий
     * код должен залогировать и закрыть соединение.
     * </p>
     */
    public static Message decode(byte[] bytes) {
        try {
            return MAPPER.readValue(bytes, Message.class);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Не удалось распарсить сообщение из " + bytes.length + " байт", e);
        }
    }

    /** Утилитарно — JSON в виде строки (для логов/отладки). */
    public static String encodeAsString(Message message) {
        try {
            return MAPPER.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Не удалось сериализовать сообщение в строку", e);
        }
    }
}
