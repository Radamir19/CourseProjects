package ru.hse.jblockstorage.network;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.hse.jblockstorage.config.NodeConfig;
import ru.hse.jblockstorage.config.SeedNode;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

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
 * <h3>Авто-ретрай (день 15 fix)</h3>
 * Помимо одноразового {@link #bootstrap(int)} при старте, поддерживается
 * {@link #startPeriodicRetry(Duration, IntSupplier)} — если первый bootstrap
 * не нашёл живых seed-узлов (typical для P2P-сетей, где seed — это другой
 * пользователь, который мог быть оффлайн в момент нашего старта), узел
 * раз в N секунд повторяет попытку. Условие срабатывания: в таблице пиров
 * 0 активных сессий И в конфиге есть seed-адреса. Как только хотя бы один
 * seed поднялся — мы подключимся к нему, gossip разнесёт остальную сеть,
 * и retry перестанет срабатывать (peerManager.size() уже > 0).
 *
 * <p>Это закрывает тонкий race-condition при демо-сценарии «3 узла на
 * одной машине через скрипт»: если узел B стартует с seed=A, а узел A
 * ещё не успел поднять TCP-сервер, без retry B так и остался бы один.
 *
 * <h3>Что не делает</h3>
 * <ul>
 *   <li>Не делает retry для каждого seed по-отдельности — мы либо
 *       ретраимся ко всем сразу (если 0 пиров), либо не ретраимся
 *       (если хоть один сосед уже есть). Это намеренно: уже одной живой
 *       сессии достаточно, чтобы gossip восстановил остальное.</li>
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
public final class BootstrapDiscovery implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(BootstrapDiscovery.class);

    private final NodeConfig config;
    private final NodeClient client;
    private final PeerManager peerManager;

    /**
     * Планировщик периодического retry. {@code null} до вызова
     * {@link #startPeriodicRetry(Duration, IntSupplier)} и после
     * {@link #close()}.
     */
    private ScheduledExecutorService retryScheduler;

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

    /**
     * Запускает периодический retry bootstrap'а — день 15 fix.
     *
     * <p>Каждые {@code interval} секунд проверяет: если в таблице пиров
     * 0 активных сессий И в конфиге есть хотя бы один seed —
     * пробуем подключиться к ним заново. Это спасает от ситуации, когда
     * на момент первого bootstrap'а seed-узел ещё не запущен (race при
     * параллельном старте нескольких узлов на одной машине).
     *
     * <p>Идемпотентен: повторный вызов не запустит второй планировщик.
     *
     * @param interval          период между попытками. Рекомендуется
     *                          30 секунд — компромисс между «быстро
     *                          восстановиться после старта seed'а» и
     *                          «не нагружать сеть, если seed навсегда оффлайн»
     * @param blockchainHeight  поставщик текущей высоты блокчейна для
     *                          handshake. На каждом тике значение
     *                          может меняться, поэтому берём supplier,
     *                          а не value
     */
    public synchronized void startPeriodicRetry(Duration interval,
                                                IntSupplier blockchainHeight) {
        Objects.requireNonNull(interval, "interval");
        Objects.requireNonNull(blockchainHeight, "blockchainHeight");
        if (retryScheduler != null) return; // уже запущен
        if (config.getSeedNodes().isEmpty()) {
            // Без seeds ретраить нечего — в bootstrap'е ничего бы не делали.
            // Это полностью валидно: одиночный изолированный узел.
            return;
        }
        retryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "bootstrap-retry");
            t.setDaemon(true);
            return t;
        });
        long ms = interval.toMillis();
        retryScheduler.scheduleAtFixedRate(() -> {
            try {
                // Условие срабатывания. Пиром считаем любую активную
                // сессию — даже та, что висит как "seed:host:port" до
                // получения handshake. После handshake'а PeerManager
                // её перепривяжет к настоящему nodeId; нам тут это
                // безразлично, важно только «есть ли хоть один сосед».
                if (peerManager.size() > 0) return;
                int n = bootstrap(blockchainHeight.getAsInt());
                if (n > 0) {
                    LOG.info("Retry-bootstrap успешен: подключились к {} seed-узлам", n);
                }
            } catch (Exception e) {
                LOG.warn("Retry-bootstrap упал: {}", e.toString());
            }
        }, ms, ms, TimeUnit.MILLISECONDS);
        LOG.info("Авто-ретрай bootstrap запущен с интервалом {} мс", ms);
    }

    /** Останавливает планировщик retry, если он был запущен. */
    public synchronized void stop() {
        if (retryScheduler != null) {
            retryScheduler.shutdownNow();
            retryScheduler = null;
        }
    }

    /** Алиас для {@link #stop()} — поддержка {@link AutoCloseable}. */
    @Override
    public void close() {
        stop();
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