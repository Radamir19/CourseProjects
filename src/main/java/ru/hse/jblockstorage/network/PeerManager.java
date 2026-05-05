package ru.hse.jblockstorage.network;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.hse.jblockstorage.config.NodeConfig;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Менеджер P2P-пиров — таблица известных узлов плюс фоновые задачи поверх неё.
 * <p>
 * Класс реализует:
 * <ul>
 *   <li>обработку входящих сообщений {@link HandshakeMessage}, {@link PingMessage},
 *       {@link PongMessage}, {@link PeerListMessage} (через {@link MessageHandler});</li>
 *   <li>периодическую отправку ping всем известным пирам — Keep-Alive
 *       (ТЗ п. 4.1.1.1.3);</li>
 *   <li>выкидывание мёртвых пиров (которые не отвечали дольше
 *       {@link NodeConfig#getPeerTimeout()});</li>
 *   <li>периодическую рассылку своей таблицы пиров — gossip-обмен
 *       (ТЗ п. 4.1.1.1.2).</li>
 * </ul>
 * </p>
 *
 * <h3>Threading</h3>
 * Все мутирующие операции с таблицей выполняются на event loop Netty
 * (через колбэки {@code MessageHandler}) или на потоке планировщика
 * {@link #scheduler}. Сама таблица — {@link ConcurrentHashMap}, читать её
 * можно из любого потока.
 *
 * <h3>Дизайн-замечание</h3>
 * Метод {@link #onConnected} не пытается сам послать handshake — это
 * ответственность того, кто открывает соединение. Так класс остаётся
 * пригодным как для серверной стороны (где handshake инициирует клиент),
 * так и для клиентской ({@link BootstrapDiscovery} шлёт его сам после connect).
 * Иначе обе стороны послали бы handshake одновременно, и было бы 2× работы.
 */
public final class PeerManager implements MessageHandler, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(PeerManager.class);

    private final NodeConfig config;
    private final String selfNodeId;
    private final java.util.function.IntSupplier blockchainHeightSupplier;
    private final java.util.function.LongSupplier clock;

    /**
     * Клиент для исходящих коннектов к новоузнанным пирам (опционально).
     * Если null — узел работает только в пассивном режиме (принимает входящие,
     * но сам не инициирует коннекты к пирам из gossip-таблицы). Это удобно
     * в тестах, где мы вручную управляем кто к кому коннектится.
     * <p>
     * Поле volatile + одноразовый сеттер — это уступка циклической зависимости
     * между PeerManager и NodeClient: NodeClient в конструкторе требует
     * MessageHandler (PeerManager), PeerManager хочет ссылку на NodeClient
     * для исходящих gossip-коннектов. Установка через {@link #setOutgoingClient}
     * после создания обоих — самый прямой выход.
     */
    private volatile NodeClient outgoingClient;

    /**
     * Если установлен, переопределяет {@code config.getListenPort()} в исходящих
     * handshake-сообщениях. Нужно для случая, когда listenPort=0 в конфиге
     * (ОС выбирает свободный порт), и реальный порт известен только после
     * {@link NodeServer#start()}. Устанавливается через {@link #setListenPort}.
     */
    private volatile int listenPortOverride = -1;

    /**
     * Колбэк, вызываемый после успешной обработки входящего handshake'а.
     * Принимает (сессия пира, peerNodeId, peerHeight). Нужен для подписки
     * BlockSyncService — после handshake'а сравнить высоты и инициировать
     * sync, если нужно. {@code null} (по умолчанию) — никакого сайд-эффекта.
     * <p>
     * Вынесен в callback, а не в прямую зависимость, чтобы PeerManager
     * оставался независим от пакета {@code app/}.
     */
    private volatile HandshakeListener handshakeListener;

    /** Известные пиры по их nodeId. */
    private final Map<String, PeerInfo> peers = new ConcurrentHashMap<>();

    /** Активные исходящие/входящие сессии по nodeId — для рассылки пингов и gossip. */
    private final Map<String, PeerSession> sessions = new ConcurrentHashMap<>();

    private ScheduledExecutorService scheduler;
    private boolean started;
    private boolean closed;

    /**
     * Путь к файлу persistent-таблицы пиров. Если установлен через
     * {@link #enablePeriodicPeersSave}, то в {@link #start()} зарегистрируется
     * фоновая задача, которая раз в {@link #peersSaveInterval} вызывает
     * {@link #savePeers(java.nio.file.Path)}. Защита от потери данных при
     * аварийном падении процесса (без graceful close).
     */
    private volatile java.nio.file.Path periodicPeersFile;
    private volatile java.time.Duration peersSaveInterval;

    /**
     * @param config                    параметры таймингов и максимального числа пиров
     * @param selfNodeId                собственный nodeId этого узла
     *                                  (Base64 публичного ключа из {@code KeyManager})
     * @param blockchainHeightSupplier  поставщик текущей высоты блокчейна
     *                                  (используется в исходящих handshake)
     */
    public PeerManager(NodeConfig config, String selfNodeId,
                       java.util.function.IntSupplier blockchainHeightSupplier) {
        this(config, selfNodeId, blockchainHeightSupplier, System::currentTimeMillis);
    }

    /** Конструктор с инжектируемым clock — нужен для unit-тестов. */
    PeerManager(NodeConfig config, String selfNodeId,
                java.util.function.IntSupplier blockchainHeightSupplier,
                java.util.function.LongSupplier clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.selfNodeId = Objects.requireNonNull(selfNodeId, "selfNodeId");
        this.blockchainHeightSupplier = Objects.requireNonNull(blockchainHeightSupplier, "blockchainHeightSupplier");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Привязывает {@link NodeClient} для активных исходящих коннектов из
     * gossip-таблицы. Можно установить только один раз и только до {@link #start()}.
     * Без вызова этого метода узел работает в чисто пассивном режиме.
     */
    public synchronized void setOutgoingClient(NodeClient client) {
        if (started) {
            throw new IllegalStateException(
                    "setOutgoingClient должен вызываться до start()");
        }
        if (this.outgoingClient != null) {
            throw new IllegalStateException("outgoingClient уже установлен");
        }
        this.outgoingClient = Objects.requireNonNull(client, "client");
    }

    /**
     * Подписаться на событие "handshake обработан". Вызывается ПОСЛЕ того, как
     * сессия зарегистрирована в таблице — то есть слушатель уже может слать
     * сообщения через {@code peer.send(...)}, и их доставка не зависит от того,
     * успел ли отработать ответный handshake. Также после вызова listener'а
     * сессия точно есть в {@link #snapshotSessions()}.
     *
     * <p>Установка возможна только до {@link #start()}, аналогично outgoingClient.
     * Listener один — для нашего use case (BlockSyncService) этого достаточно;
     * если в будущем понадобится несколько — заменим на {@code CopyOnWriteArrayList}.
     */
    public synchronized void setHandshakeListener(HandshakeListener listener) {
        if (started) {
            throw new IllegalStateException(
                    "setHandshakeListener должен вызываться до start()");
        }
        this.handshakeListener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Вызывается из {@link PeerManager#handleHandshake} сразу после регистрации
     * сессии. Слушатель видит уже консистентное состояние таблицы пиров.
     */
    @FunctionalInterface
    public interface HandshakeListener {
        void onHandshake(PeerSession peer, String peerNodeId, int peerHeight);
    }

    /**
     * Переопределяет listenPort, который узел сообщает о себе в исходящих handshake.
     * Нужно, когда в конфиге listenPort=0 (ОС-назначенный порт), и реальный порт
     * становится известен только после старта сервера.
     */
    public void setListenPort(int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port вне диапазона: " + port);
        }
        this.listenPortOverride = port;
    }

    /**
     * Возвращает listenPort, который мы рекламируем другим узлам.
     * Если был установлен override через {@link #setListenPort(int)} (ситуация
     * port=0 в конфиге → ОС выбрала свободный порт), используем его, иначе —
     * значение из конфига. Используется при формировании исходящих
     * {@link HandshakeMessage}, в т.ч. в {@link BootstrapDiscovery}.
     */
    public int advertisedListenPort() {
        return listenPortOverride > 0 ? listenPortOverride : config.getListenPort();
    }

    /** Внутренний alias для совместимости с уже написанным кодом класса. */
    private int effectiveListenPort() {
        return advertisedListenPort();
    }

    /** Запускает фоновые задачи: ping и gossip. Идемпотентен в том смысле, что повторный вызов кидает. */
    public synchronized void start() {
        if (closed) throw new IllegalStateException("PeerManager закрыт");
        if (started) throw new IllegalStateException("PeerManager уже запущен");
        started = true;

        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "peer-manager-scheduler");
            t.setDaemon(true);
            return t;
        });

        long pingMs = config.getPingInterval().toMillis();
        long gossipMs = config.getGossipInterval().toMillis();

        scheduler.scheduleAtFixedRate(this::pingTick,  pingMs,   pingMs,   TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(this::gossipTick, gossipMs, gossipMs, TimeUnit.MILLISECONDS);

        // День 11: периодический save peers.json. Защищает от потери данных
        // при аварийном падении процесса — на close() мы и так сохраняем,
        // но если JVM убили сигналом SIGKILL, close() не вызовется.
        if (periodicPeersFile != null) {
            long saveMs = Math.max(1, peersSaveInterval.toMillis());
            scheduler.scheduleAtFixedRate(this::periodicSavePeersTick,
                    saveMs, saveMs, TimeUnit.MILLISECONDS);
            LOG.debug("Periodic peers save включён (interval={}мс, file={})",
                    saveMs, periodicPeersFile);
        }

        LOG.debug("PeerManager стартовал (ping={}мс, gossip={}мс)", pingMs, gossipMs);
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        for (PeerSession s : sessions.values()) {
            try { s.close(); } catch (Exception ignored) {}
        }
        sessions.clear();
        peers.clear();
    }

    // ---------- Публичное API для остального кода ----------

    /** Снимок текущей таблицы (копия — мутирующие изменения не повлияют). */
    public List<PeerInfo> snapshot() {
        return new ArrayList<>(peers.values());
    }

    /**
     * Включает периодическое автосохранение таблицы пиров в указанный файл
     * (день 11). Должен быть вызван ДО {@link #start()} — в start()
     * регистрируется fixed-rate задача в общем scheduler'е.
     * <p>
     * Цель — защита от ситуации, когда JVM падает по SIGKILL и graceful close
     * не успевает выполнить save. Между авариями узел всё равно теряет данные,
     * но не больше, чем за один интервал.
     *
     * @param file     путь к файлу (тот же, что передаётся в {@link #savePeers})
     * @param interval период между сохранениями (например, {@code Duration.ofMinutes(1)})
     * @throws IllegalStateException если уже стартовал или закрыт
     */
    public synchronized void enablePeriodicPeersSave(
            java.nio.file.Path file, java.time.Duration interval) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(interval, "interval");
        if (started) throw new IllegalStateException(
                "enablePeriodicPeersSave должен вызываться до start()");
        if (closed) throw new IllegalStateException("PeerManager закрыт");
        if (interval.toMillis() < 1) {
            throw new IllegalArgumentException("interval должен быть положительным");
        }
        this.periodicPeersFile = file;
        this.peersSaveInterval = interval;
    }

    private void periodicSavePeersTick() {
        try {
            java.nio.file.Path file = periodicPeersFile;
            if (file == null) return;
            java.nio.file.Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                java.nio.file.Files.createDirectories(parent);
            }
            savePeers(file);
        } catch (Throwable t) {
            // Не позволяем исключению прибить scheduler — это бы остановило
            // ping и gossip заодно. Логируем и продолжаем.
            LOG.warn("Periodic save peers упал: {}", t.toString());
        }
    }

    /**
     * Сохраняет текущую таблицу пиров в JSON-файл (день 10, ТЗ п. 4.1.1.1.1
     * + ограничение «persistence пиров» из дней 5–7).
     * <p>
     * После рестарта узла мы восстанавливаем таблицу из файла, что ускоряет
     * дискавери — не нужно ждать gossip от seed'ов. Аналогично Bitcoin Core
     * с его {@code peers.dat}.
     * <p>
     * Записываются ВСЕ пиры из таблицы (даже те, что давно не отвечали) —
     * на загрузке мы не знаем, кто из них живой. Узел проверит по ping'у
     * и выкинет мёртвых через peerTimeout.
     *
     * @param file путь к файлу. Родительские директории должны существовать.
     */
    public void savePeers(java.nio.file.Path file) throws java.io.IOException {
        Objects.requireNonNull(file, "file");
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        List<PeerInfo> snapshot = snapshot();
        mapper.writerWithDefaultPrettyPrinter()
                .writeValue(file.toFile(), snapshot);
        LOG.debug("Сохранено {} пиров в {}", snapshot.size(), file);
    }

    /**
     * Загружает таблицу пиров из JSON-файла. Если файл не существует —
     * молча возвращает 0 (нормальная ситуация для первого запуска).
     * <p>
     * Загруженные записи добавляются через {@code putIfAbsent} — это значит,
     * что свежие записи (например, из gossip'а, который успел случиться
     * до load) не будут перезаписаны старыми с диска. Защита от ситуации,
     * когда узел между save и load успел что-то узнать.
     *
     * @return сколько записей загружено
     */
    public int loadPeers(java.nio.file.Path file) throws java.io.IOException {
        Objects.requireNonNull(file, "file");
        if (!java.nio.file.Files.isRegularFile(file)) {
            LOG.debug("Файла {} нет — пропускаем загрузку peers", file);
            return 0;
        }
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        // FAIL_ON_UNKNOWN_PROPERTIES = false — на случай, если в будущем
        // схема PeerInfo расширится, старые файлы должны грузиться.
        mapper.configure(
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                false);
        List<PeerInfo> loaded = mapper.readValue(file.toFile(),
                mapper.getTypeFactory().constructCollectionType(List.class, PeerInfo.class));

        int added = 0;
        for (PeerInfo p : loaded) {
            if (p == null || selfNodeId.equals(p.getNodeId())) continue;
            if (peers.size() >= config.getMaxPeers()) break;
            if (peers.putIfAbsent(p.getNodeId(), p) == null) {
                added++;
            }
        }
        LOG.info("Загружено {} пиров из {} (всего в таблице: {})",
                added, file, peers.size());
        return added;
    }

    /** Сколько пиров сейчас в таблице. */
    public int size() {
        return peers.size();
    }

    /** Регистрирует уже установленную сессию. Используется {@link BootstrapDiscovery}. */
    public void registerSession(String nodeId, PeerSession session) {
        sessions.put(nodeId, session);
    }

    /** Знает ли менеджер про пира с таким nodeId. */
    public boolean knows(String nodeId) {
        return peers.containsKey(nodeId);
    }

    /** Получить сессию к конкретному пиру (или null, если соединения нет). */
    public PeerSession sessionOf(String nodeId) {
        return sessions.get(nodeId);
    }

    /** Собственный nodeId этого узла. */
    public String selfNodeId() {
        return selfNodeId;
    }

    /**
     * Возвращает копию всех известных в данный момент сессий
     * (nodeId → session). Используется {@code FileDownloader} —
     * ему нужна карта живых сессий, чтобы перебирать их при поиске
     * хранителя нужного шарда.
     * <p>
     * Сессии под "временными" ключами (с префиксом {@code seed:} или
     * {@code active:}) не возвращаются — они ещё не сопоставлены настоящему
     * nodeId через handshake.
     * </p>
     */
    public Map<String, PeerSession> snapshotSessions() {
        Map<String, PeerSession> result = new java.util.HashMap<>();
        for (Map.Entry<String, PeerSession> e : sessions.entrySet()) {
            String key = e.getKey();
            if (key.startsWith("seed:") || key.startsWith("active:")) continue;
            result.put(key, e.getValue());
        }
        return result;
    }

    /**
     * Возвращает nodeId пира, к которому относится переданная сессия.
     * <p>
     * Сравнение идёт по {@link PeerSession#channel()}, а не по ссылке
     * самого {@code PeerSession}: один Netty-канал может иметь два разных
     * объекта-обёртки (один — у того, кто открыл соединение через
     * {@link NodeClient#connect}, другой — у входящей стороны через
     * {@link NettyMessageBridge#channelActive}). Это урок дня 6.
     * </p>
     */
    public Optional<String> findNodeIdBySession(PeerSession peer) {
        if (peer == null) return Optional.empty();
        for (Map.Entry<String, PeerSession> e : sessions.entrySet()) {
            if (e.getValue().channel() == peer.channel()) {
                String key = e.getKey();
                if (key.startsWith("seed:") || key.startsWith("active:")) continue;
                return Optional.of(key);
            }
        }
        return Optional.empty();
    }

    // ---------- MessageHandler ----------

    @Override
    public void onConnected(PeerSession peer) {
        // nodeId узнаём только из handshake; здесь регистрировать пока нечего.
        LOG.debug("Соединение установлено: {}", peer.remoteAddress());
    }

    @Override
    public void onDisconnected(PeerSession peer) {
        // Удаляем сессию, но запись о пире оставляем — peerTimeout сам её выкинет,
        // если пир не вернётся. Это даёт шанс пережить мгновенные разрывы.
        sessions.entrySet().removeIf(e -> e.getValue() == peer);
        LOG.debug("Соединение разорвано: {}", peer.remoteAddress());
    }

    @Override
    public void onError(PeerSession peer, Throwable cause) {
        LOG.warn("Ошибка на канале {}: {}", peer.remoteAddress(), cause.toString());
    }

    @Override
    public void onMessage(PeerSession peer, Message message) {
        if (message instanceof HandshakeMessage hs) {
            handleHandshake(peer, hs);
        } else if (message instanceof PingMessage ping) {
            handlePing(peer, ping);
        } else if (message instanceof PongMessage pong) {
            handlePong(peer, pong);
        } else if (message instanceof PeerListMessage list) {
            handlePeerList(list);
        }
        // Остальные сообщения (Get/Block/Shard/Broadcast) — забота MessageRouter
        // (день 7) или верхнего уровня; PeerManager их игнорирует.
    }

    // ---------- Обработчики конкретных типов ----------

    private void handleHandshake(PeerSession peer, HandshakeMessage hs) {
        if (hs.getProtocolVersion() != config.getProtocolVersion()) {
            LOG.warn("Несовместимая версия протокола от {}: {} vs наша {}, рвём соединение",
                    hs.getNodeId(), hs.getProtocolVersion(), config.getProtocolVersion());
            peer.close();
            return;
        }
        if (selfNodeId.equals(hs.getNodeId())) {
            // Защита от self-loop: при тестах с порт=0 легко случайно подключиться
            // к самому себе через seed-list, забитый своим же адресом.
            LOG.debug("Получили handshake от самого себя, рвём соединение");
            peer.close();
            return;
        }

        // Удалённый адрес сокета — это случайный исходящий порт другой стороны,
        // если она connect-инициатор. Реальный listenPort пира берём из handshake.
        String host = extractHost(peer);
        PeerInfo info = new PeerInfo(hs.getNodeId(), host, hs.getListenPort(), clock.getAsLong());

        boolean isNew = (peers.putIfAbsent(hs.getNodeId(), info) == null);
        if (!isNew) {
            // Обновляем lastSeen и, на всякий случай, host/port — они могли поменяться.
            peers.put(hs.getNodeId(), info);
        }

        // Если сессия уже была зарегистрирована под временным ключом
        // (BootstrapDiscovery так делает, потому что не знает nodeId до handshake)
        // — удаляем её, чтобы не было дубля. Затем регистрируем под настоящим nodeId.
        sessions.entrySet().removeIf(e ->
                e.getValue() == peer && !e.getKey().equals(hs.getNodeId()));
        sessions.put(hs.getNodeId(), peer);

        // Если nonce != 0 — это «инициирующий» handshake, нам нужно ответить.
        // Если nonce == 0 — это уже был ответный, отвечать не нужно (иначе бесконечный пинг-понг).
        if (hs.getNonce() != 0L) {
            HandshakeMessage reply = new HandshakeMessage(
                    selfNodeId,
                    effectiveListenPort(),
                    config.getProtocolVersion(),
                    blockchainHeightSupplier.getAsInt(),
                    /* nonce */ 0L);
            peer.send(reply);
        }

        LOG.debug("Handshake принят от {}{} (height={})",
                shortId(hs.getNodeId()), isNew ? " [NEW]" : "", hs.getBlockchainHeight());

        // Уведомляем подписчика (BlockSyncService) — он решает, нужно ли
        // запросить недостающие блоки.
        HandshakeListener listener = this.handshakeListener;
        if (listener != null) {
            try {
                listener.onHandshake(peer, hs.getNodeId(), hs.getBlockchainHeight());
            } catch (Exception e) {
                LOG.warn("HandshakeListener бросил исключение для {}: {}",
                        shortId(hs.getNodeId()), e.toString());
            }
        }
    }

    private void handlePing(PeerSession peer, PingMessage ping) {
        peer.send(new PongMessage(ping.getTimestamp(), clock.getAsLong()));
        bumpLastSeen(peer);
    }

    private void handlePong(PeerSession peer, PongMessage pong) {
        bumpLastSeen(peer);
    }

    private void handlePeerList(PeerListMessage list) {
        for (PeerInfo p : list.getPeers()) {
            if (p == null) continue;
            if (p.getNodeId().equals(selfNodeId)) continue;
            if (peers.size() >= config.getMaxPeers() && !peers.containsKey(p.getNodeId())) {
                continue; // защита от gossip-флуда
            }
            // Не перезаписываем lastSeen более старым значением:
            // у нас может быть свежий handshake, а в сообщении — устаревшая инфа.
            peers.merge(p.getNodeId(), p, (existing, incoming) ->
                    existing.getLastSeenMillis() >= incoming.getLastSeenMillis() ? existing : incoming);
        }
    }

    // ---------- Периодические задачи ----------

    /** Пакует ping всем пирам, выкидывает не отвечавших дольше peerTimeout. */
    void pingTick() {
        try {
            long now = clock.getAsLong();
            long timeoutMs = config.getPeerTimeout().toMillis();

            // Сначала выкидываем мёртвых
            List<String> dead = new ArrayList<>();
            for (Map.Entry<String, PeerInfo> e : peers.entrySet()) {
                if (now - e.getValue().getLastSeenMillis() > timeoutMs) {
                    dead.add(e.getKey());
                }
            }
            for (String id : dead) {
                peers.remove(id);
                PeerSession s = sessions.remove(id);
                if (s != null) {
                    try { s.close(); } catch (Exception ignored) {}
                }
                LOG.debug("Пир {} удалён по таймауту", shortId(id));
            }

            // Затем шлём ping всем оставшимся, у кого есть активная сессия
            PingMessage ping = new PingMessage(now);
            for (Map.Entry<String, PeerSession> e : sessions.entrySet()) {
                if (e.getValue().isActive()) {
                    e.getValue().send(ping);
                }
            }
        } catch (Throwable t) {
            // Фоновую задачу нельзя ронять необработанным исключением —
            // ScheduledExecutorService в этом случае молча перестанет вызывать её.
            LOG.error("Сбой в pingTick", t);
        }
    }

    /** Рассылает свою таблицу пиров всем активным соседям и подключается к новоузнанным. */
    void gossipTick() {
        try {
            // 1. Активный шаг: подключаемся к пирам, про которых знаем,
            // но с которыми ещё нет открытой сессии (пиры пришли через peer-list
            // от соседа, мы их раньше не видели).
            if (outgoingClient != null) {
                for (PeerInfo info : new ArrayList<>(peers.values())) {
                    if (info.getNodeId().equals(selfNodeId)) continue;
                    if (sessions.containsKey(info.getNodeId())) continue;
                    tryActiveConnect(info);
                }
            }

            if (sessions.isEmpty()) return;

            // 2. Рассылаем свою таблицу пиров активным соседям.
            List<PeerInfo> snapshot = snapshot();
            if (snapshot.isEmpty()) return;

            PeerListMessage msg = new PeerListMessage(snapshot);
            for (PeerSession s : sessions.values()) {
                if (s.isActive()) {
                    s.send(msg);
                }
            }
            LOG.debug("Gossip отправлен {} пирам ({} записей)", sessions.size(), snapshot.size());
        } catch (Throwable t) {
            LOG.error("Сбой в gossipTick", t);
        }
    }

    private void tryActiveConnect(PeerInfo info) {
        try {
            PeerSession session = outgoingClient.connect(info.getHost(), info.getListenPort());
            // Сразу шлём инициирующий handshake — иначе сосед не узнает, кто мы.
            HandshakeMessage hs = new HandshakeMessage(
                    selfNodeId,
                    effectiveListenPort(),
                    config.getProtocolVersion(),
                    blockchainHeightSupplier.getAsInt(),
                    System.nanoTime());
            session.send(hs);

            // Регистрируем под временным ключом, handshake-ответ заменит на настоящий nodeId.
            sessions.put("active:" + info.getNodeId(), session);
            LOG.debug("Активный коннект к {} установлен", info);
        } catch (Exception e) {
            LOG.debug("Не удалось активно подключиться к {}: {}", info, e.toString());
        }
    }

    // ---------- Утилиты ----------

    private void bumpLastSeen(PeerSession peer) {
        // Найти nodeId по сессии — обратный поиск, но сессий мало, не оптимизируем.
        for (Map.Entry<String, PeerSession> e : sessions.entrySet()) {
            if (e.getValue() == peer) {
                String id = e.getKey();
                peers.computeIfPresent(id, (k, v) -> v.withLastSeen(clock.getAsLong()));
                return;
            }
        }
    }

    private static String extractHost(PeerSession peer) {
        java.net.SocketAddress addr = peer.remoteAddress();
        if (addr instanceof java.net.InetSocketAddress isa) {
            return isa.getAddress().getHostAddress();
        }
        return addr == null ? "unknown" : addr.toString();
    }

    private static String shortId(String nodeId) {
        if (nodeId == null) return "?";
        return nodeId.length() > 8 ? nodeId.substring(0, 8) + "…" : nodeId;
    }

    /** Только для тестов: получить копию мапы пиров. */
    Collection<PeerInfo> peersForTesting() {
        return Collections.unmodifiableCollection(peers.values());
    }
}
