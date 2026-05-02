package ru.hse.jblockstorage.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.hse.jblockstorage.network.GetShardMessage;
import ru.hse.jblockstorage.network.HandshakeMessage;
import ru.hse.jblockstorage.network.Message;
import ru.hse.jblockstorage.network.MessageHandler;
import ru.hse.jblockstorage.network.PeerListMessage;
import ru.hse.jblockstorage.network.PeerManager;
import ru.hse.jblockstorage.network.PeerSession;
import ru.hse.jblockstorage.network.PingMessage;
import ru.hse.jblockstorage.network.PongMessage;
import ru.hse.jblockstorage.network.PutShardAckMessage;
import ru.hse.jblockstorage.network.PutShardMessage;
import ru.hse.jblockstorage.network.ShardResponseMessage;

import java.util.Objects;

/**
 * Единый {@link MessageHandler} для узла, объединяющий несколько
 * специализированных обработчиков.
 * <p>
 * Каждое сообщение направляется тому компоненту, который его понимает:
 * <ul>
 *   <li>{@link HandshakeMessage}, {@link PingMessage}, {@link PongMessage},
 *       {@link PeerListMessage} → {@link PeerManager} (управление таблицей пиров);</li>
 *   <li>{@link PutShardMessage}, {@link GetShardMessage} → {@link StorageNodeService}
 *       (роль хранителя — принять/отдать шард);</li>
 *   <li>{@link PutShardAckMessage} → {@link FileUploader#handleAck} (для текущего
 *       upload-а, если он есть);</li>
 *   <li>{@link ShardResponseMessage} → {@link FileDownloader#handleShardResponse}
 *       (для текущего download-а).</li>
 * </ul>
 *
 * <h3>Опциональные компоненты</h3>
 * Uploader/downloader/storage могут быть {@code null} — это нормально для узлов,
 * которые не выполняют соответствующую роль (например, daemon-узел только
 * хранит шарды, не загружает свои файлы; transient-клиент наоборот, не хранит
 * чужие). Соответствующие сообщения молча игнорируются.
 *
 * <h3>Lifecycle колбэков</h3>
 * {@link #onConnected}, {@link #onDisconnected}, {@link #onError} проксируются
 * только в {@link PeerManager} — никакой другой компонент в них не нуждается.
 *
 * <h3>Threading</h3>
 * Класс {@code stateless}: все мутирующие операции у компонентов-получателей.
 * Безопасен к одновременному вызову {@code onMessage} с разных Netty-потоков.
 */
public final class MessageRouter implements MessageHandler {

    private static final Logger LOG = LoggerFactory.getLogger(MessageRouter.class);

    private final PeerManager peerManager;
    private final StorageNodeService storageService;   // может быть null
    private final FileUploader uploader;                // может быть null
    private final FileDownloader downloader;            // может быть null

    public MessageRouter(PeerManager peerManager,
                         StorageNodeService storageService,
                         FileUploader uploader,
                         FileDownloader downloader) {
        this.peerManager = Objects.requireNonNull(peerManager, "peerManager");
        this.storageService = storageService;
        this.uploader = uploader;
        this.downloader = downloader;
    }

    @Override
    public void onConnected(PeerSession peer) {
        peerManager.onConnected(peer);
    }

    @Override
    public void onDisconnected(PeerSession peer) {
        peerManager.onDisconnected(peer);
    }

    @Override
    public void onError(PeerSession peer, Throwable cause) {
        peerManager.onError(peer, cause);
    }

    @Override
    public void onMessage(PeerSession peer, Message message) {
        // 1. Управление пирами — всегда отдаём PeerManager.
        if (message instanceof HandshakeMessage
                || message instanceof PingMessage
                || message instanceof PongMessage
                || message instanceof PeerListMessage) {
            peerManager.onMessage(peer, message);
            return;
        }

        // 2. Роль "хранителя" — принимаем шард / отдаём шард.
        if (message instanceof PutShardMessage || message instanceof GetShardMessage) {
            if (storageService != null) {
                storageService.handle(peer, message);
            } else {
                LOG.debug("Получено {}, но storageService не сконфигурирован — игнор",
                        message.getClass().getSimpleName());
            }
            return;
        }

        // 3. Ответ на наш PUT — отдаём uploader.
        if (message instanceof PutShardAckMessage ack) {
            if (uploader == null) {
                LOG.debug("Получен PutShardAck, но uploader не сконфигурирован — игнор");
                return;
            }
            // uploader.handleAck требует nodeId хранителя, а сам ack его не несёт.
            // PeerManager знает все сессии — он и найдёт.
            String storerNodeId = peerManager.findNodeIdBySession(peer).orElse(null);
            if (storerNodeId == null) {
                LOG.warn("Получили PutShardAck, но не смогли определить nodeId источника");
                return;
            }
            boolean handled = uploader.handleAck(storerNodeId, ack);
            if (!handled) {
                LOG.debug("PutShardAck для шарда {} от {} пришёл, но никто его не ждал",
                        shortHash(ack.getShardHashHex()), shortNode(storerNodeId));
            }
            return;
        }

        // 4. Ответ на наш GET — отдаём downloader.
        if (message instanceof ShardResponseMessage resp) {
            if (downloader == null) {
                LOG.debug("Получен ShardResponse, но downloader не сконфигурирован — игнор");
                return;
            }
            boolean handled = downloader.handleShardResponse(resp);
            if (!handled) {
                LOG.debug("ShardResponse для шарда {} пришёл, но никто его не ждал",
                        shortHash(resp.getShardHashHex()));
            }
            return;
        }

        // 5. Прочее (Get/BlockResponse/BroadcastTx/BroadcastBlock) — пока не обрабатываем.
        // Это будет добавлено позже, при реализации синхронизации блокчейна.
        LOG.debug("Сообщение типа {} не обработано — нет соответствующего получателя",
                message.getClass().getSimpleName());
    }

    private static String shortHash(String hash) {
        if (hash == null) return "?";
        return hash.length() > 8 ? hash.substring(0, 8) + "…" : hash;
    }

    private static String shortNode(String nodeId) {
        if (nodeId == null) return "?";
        return nodeId.length() > 8 ? nodeId.substring(0, 8) + "…" : nodeId;
    }
}
