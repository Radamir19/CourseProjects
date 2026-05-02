package ru.hse.jblockstorage.network;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.hse.jblockstorage.config.NodeConfig;
import ru.hse.jblockstorage.config.SeedNode;

import java.util.Objects;

/**
 * Подключается к seed-узлам из {@link NodeConfig} при старте узла
 * (ТЗ п. 4.1.1.1.1).
 * <p>
 * Очень простая реализация: для каждого seed открывает TCP-соединение
 * через {@link NodeClient}, шлёт {@link HandshakeMessage}, регистрирует
 * полученную {@link PeerSession} в {@link PeerManager}. Дальше gossip
 * сделает остальное — сосед поделится своей таблицей пиров, и через
 * пару циклов наш узел узнает о всей сети.
 *
 * <h3>Что не делает</h3>
 * <ul>
 *   <li>Не делает retry — упавший seed просто пропускается. Это нормально:
 *       seeds в конфиге намеренно несколько, и хватит хотя бы одного живого.</li>
 *   <li>Не блокирует на ожидании ответного handshake — отправляем и
 *       продолжаем. Когда придёт ответ, {@link PeerManager#onMessage}
 *       сам положит запись в таблицу.</li>
 * </ul>
 *
 * <h3>Важно: listenPort</h3>
 * В handshake мы рекламируем порт через {@link PeerManager#advertisedListenPort()},
 * а НЕ {@link NodeConfig#getListenPort()}. Это критично для случая
 * {@code listenPort=0} в конфиге — ОС выдаёт реальный порт только после
 * {@code NodeServer.start()}, и {@code NodeApplication} записывает его
 * в {@code PeerManager} через {@code setListenPort(realPort)}. Если бы
 * мы брали значение из конфига, то слали бы 0 — и принимающая сторона
 * валилась бы на валидации {@link PeerInfo}.
 */
public final class BootstrapDiscovery {

    private static final Logger LOG = LoggerFactory.getLogger(BootstrapDiscovery.class);

    private final NodeConfig config;
    private final NodeClient client;
    private final PeerManager peerManager;

    public BootstrapDiscovery(NodeConfig config, NodeClient client, PeerManager peerManager) {
        this.config = Objects.requireNonNull(config, "config");
        this.client = Objects.requireNonNull(client, "client");
        this.peerManager = Objects.requireNonNull(peerManager, "peerManager");
    }

    /**
     * Подключается ко всем seed-узлам из конфига последовательно.
     * Возвращает количество успешно установленных соединений.
     */
    public int bootstrap(int blockchainHeight) {
        int connected = 0;
        for (SeedNode seed : config.getSeedNodes()) {
            if (tryConnect(seed, blockchainHeight)) {
                connected++;
            }
        }
        LOG.info("Bootstrap завершён: подключились к {} из {} seed-узлов",
                connected, config.getSeedNodes().size());
        return connected;
    }

    private boolean tryConnect(SeedNode seed, int blockchainHeight) {
        try {
            PeerSession session = client.connect(seed.host(), seed.port());

            // Инициирующий handshake — nonce != 0, чтобы вторая сторона ответила.
            // listenPort берём из PeerManager (см. javadoc класса) — иначе при
            // port=0 в конфиге уйдёт 0 и сервер другой стороны его отвергнет.
            long nonce = System.nanoTime();
            HandshakeMessage hs = new HandshakeMessage(
                    peerManager.selfNodeId(),
                    peerManager.advertisedListenPort(),
                    config.getProtocolVersion(),
                    blockchainHeight,
                    nonce);
            session.send(hs);

            // Регистрируем сессию заранее — иначе первый ping/gossip-tick
            // успеет сработать раньше, чем придёт ответный handshake.
            // Ключ — host:port, потому что nodeId узнаем только из ответа.
            // Когда придёт handshake, PeerManager перепривяжет сессию к настоящему nodeId.
            String tempKey = "seed:" + seed.host() + ":" + seed.port();
            peerManager.registerSession(tempKey, session);

            LOG.info("Подключились к seed {}", seed);
            return true;
        } catch (Exception e) {
            LOG.warn("Не удалось подключиться к seed {}: {}", seed, e.toString());
            return false;
        }
    }
}