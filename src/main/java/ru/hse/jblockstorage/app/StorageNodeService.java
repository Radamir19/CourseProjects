package ru.hse.jblockstorage.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.hse.jblockstorage.blockchain.StorageReceipt;
import ru.hse.jblockstorage.network.GetShardMessage;
import ru.hse.jblockstorage.network.Message;
import ru.hse.jblockstorage.network.PeerSession;
import ru.hse.jblockstorage.network.PutShardAckMessage;
import ru.hse.jblockstorage.network.PutShardMessage;
import ru.hse.jblockstorage.network.ShardResponseMessage;
import ru.hse.jblockstorage.storage.ShardStorage;

import java.io.IOException;
import java.security.PrivateKey;
import java.util.Objects;
import java.util.Optional;

/**
 * Обработчик «storage-стороны» протокола: отвечает на запросы хранения шардов.
 * <p>
 * Узел использует этот сервис, чтобы участвовать в сети как хранитель:
 * принимать шарды от других uploader'ов и отдавать их при запросах.
 * Не Netty-handler — просто диспетчер для сообщений {@link PutShardMessage}
 * и {@link GetShardMessage}, который вызывает наружный код узла.
 * </p>
 *
 * <h3>Зачем отдельный класс</h3>
 * Логически он отделим от {@code FileUploader}/{@code FileDownloader} —
 * uploader/downloader это «инициатор» (клиентская роль), storage service это
 * «отзывчатая» сторона (серверная роль). На одном узле живут все три, но
 * разделение упрощает тестирование (можно собрать чисто-storage узел без
 * uploader-логики).
 */
public final class StorageNodeService {

    private static final Logger LOG = LoggerFactory.getLogger(StorageNodeService.class);

    private final String selfPublicKeyBase64;
    private final PrivateKey selfPrivateKey;
    private final ShardStorage storage;

    public StorageNodeService(String selfPublicKeyBase64,
                              PrivateKey selfPrivateKey,
                              ShardStorage storage) {
        this.selfPublicKeyBase64 = Objects.requireNonNull(selfPublicKeyBase64, "selfPublicKeyBase64");
        this.selfPrivateKey = Objects.requireNonNull(selfPrivateKey, "selfPrivateKey");
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    /**
     * Главная точка входа: вызывается из роутера сообщений узла, когда приходит
     * сообщение, которое относится к нашей зоне ответственности.
     *
     * @return {@code true} если сообщение было обработано, {@code false} если нет
     *         (тогда вызывающий код может попытаться передать его другому хендлеру).
     */
    public boolean handle(PeerSession peer, Message message) {
        if (message instanceof PutShardMessage put) {
            handlePut(peer, put);
            return true;
        }
        if (message instanceof GetShardMessage get) {
            handleGet(peer, get);
            return true;
        }
        return false;
    }

    private void handlePut(PeerSession peer, PutShardMessage put) {
        try {
            // Проверка целостности и сохранение делает ShardStorage.save —
            // там же отлавливается mismatch хеша.
            storage.save(put.getShardHashHex(), put.getData());

            // Подписываем receipt и отвечаем
            StorageReceipt receipt = StorageReceipt.create(
                    put.getTransactionId(),
                    selfPublicKeyBase64,
                    put.getShardHashHex(),
                    selfPrivateKey);
            peer.send(PutShardAckMessage.accepted(put.getShardHashHex(), receipt));
            LOG.debug("Принят шард {} для tx {}",
                    put.getShardHashHex().substring(0, 8) + "…",
                    put.getTransactionId().substring(0, 8) + "…");
        } catch (IllegalArgumentException e) {
            // Hash mismatch — клиент прислал данные, не соответствующие хешу
            LOG.warn("Отклонён шард: {}", e.getMessage());
            peer.send(PutShardAckMessage.rejected(put.getShardHashHex(), "hash_mismatch"));
        } catch (IOException e) {
            LOG.error("Ошибка записи шарда на диск: {}", e.toString());
            peer.send(PutShardAckMessage.rejected(put.getShardHashHex(), "io_error"));
        }
    }

    private void handleGet(PeerSession peer, GetShardMessage get) {
        try {
            Optional<byte[]> data = storage.load(get.getShardHashHex());
            byte[] payload = data.orElse(null); // null означает «нет шарда»
            peer.send(new ShardResponseMessage(get.getShardHashHex(), payload));
            LOG.debug("На запрос {} ответили {}",
                    get.getShardHashHex().substring(0, 8) + "…",
                    payload == null ? "null" : payload.length + " байт");
        } catch (IOException e) {
            LOG.error("Ошибка чтения шарда: {}", e.toString());
            peer.send(new ShardResponseMessage(get.getShardHashHex(), null));
        }
    }
}
