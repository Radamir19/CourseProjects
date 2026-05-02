package ru.hse.jblockstorage.network;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.io.Serializable;

/**
 * Базовый класс всех сетевых сообщений между узлами P2P-сети.
 * <p>
 * Согласно ТЗ (п. 4.1.1.1) сетевой слой должен поддерживать:
 * <ul>
 *   <li>рукопожатие при подключении (handshake);</li>
 *   <li>проверку доступности соседей (ping/pong, п. 4.1.1.1.3);</li>
 *   <li>обмен блоками блокчейна и шардами файлов;</li>
 *   <li>рассылку новых транзакций и блоков;</li>
 *   <li>gossip-обмен таблицей известных пиров (п. 4.1.1.1.2).</li>
 * </ul>
 * </p>
 *
 * <h3>Сериализация</h3>
 * Используем полиморфную Jackson-сериализацию через поле {@code type}
 * (см. {@link JsonTypeInfo}). Это значит,
 * что в JSON попадает дополнительное поле {@code "type": "PING"} и т.п.,
 * по которому Jackson на чтении выбирает нужный подкласс.
 * <p>
 * При добавлении новых типов сообщений нужно одновременно:
 * <ol>
 *   <li>создать класс-наследник {@code Message};</li>
 *   <li>добавить запись в {@code @JsonSubTypes} ниже.</li>
 * </ol>
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type"
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = HandshakeMessage.class, name = "HANDSHAKE"),
        @JsonSubTypes.Type(value = PingMessage.class, name = "PING"),
        @JsonSubTypes.Type(value = PongMessage.class, name = "PONG"),
        @JsonSubTypes.Type(value = GetBlockMessage.class, name = "GET_BLOCK"),
        @JsonSubTypes.Type(value = BlockResponseMessage.class, name = "BLOCK_RESPONSE"),
        @JsonSubTypes.Type(value = GetShardMessage.class, name = "GET_SHARD"),
        @JsonSubTypes.Type(value = ShardResponseMessage.class, name = "SHARD_RESPONSE"),
        @JsonSubTypes.Type(value = BroadcastTxMessage.class, name = "BROADCAST_TX"),
        @JsonSubTypes.Type(value = BroadcastBlockMessage.class, name = "BROADCAST_BLOCK"),
        @JsonSubTypes.Type(value = PeerListMessage.class, name = "PEER_LIST"),
        @JsonSubTypes.Type(value = PutShardMessage.class, name = "PUT_SHARD"),
        @JsonSubTypes.Type(value = PutShardAckMessage.class, name = "PUT_SHARD_ACK"),
})
public abstract class Message implements Serializable {
}
