package ru.hse.jblockstorage.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.hse.jblockstorage.blockchain.StorageReceipt;
import ru.hse.jblockstorage.blockchain.Transaction;
import ru.hse.jblockstorage.crypto.AesGcm;
import ru.hse.jblockstorage.crypto.CryptoUtils;
import ru.hse.jblockstorage.crypto.RsaOaep;
import ru.hse.jblockstorage.network.GetShardMessage;
import ru.hse.jblockstorage.network.PeerSession;
import ru.hse.jblockstorage.network.ShardResponseMessage;
import ru.hse.jblockstorage.storage.ShardStorage;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Оркестратор скачивания файла из распределённой сети.
 * <p>
 * Алгоритм одного download:
 * <ol>
 *   <li>Получить транзакцию из локального блокчейна (по txId или fileName).</li>
 *   <li>Из {@code transaction.replicas} извлечь список (storerNodeId → shardHash)
 *       — каждый receipt говорит, что узел X согласился хранить шард Y.</li>
 *   <li>Для каждого нужного шарда выбрать живого хранителя (по
 *       {@code availableSessions}) и послать ему {@link GetShardMessage}.</li>
 *   <li>Получить ответ {@link ShardResponseMessage}, проверить SHA-256 шарда.</li>
 *   <li>Когда все шарды собраны — склеить шифротекст по индексам.</li>
 *   <li>Расшифровать AES-ключ из транзакции своим приватным RSA-ключом
 *       (RSA-OAEP).</li>
 *   <li>Расшифровать шифротекст AES-ключом (AES-GCM).</li>
 *   <li>Записать plaintext в файл назначения.</li>
 * </ol>
 *
 * <h3>Устойчивость к отключению узлов</h3>
 * Если первый выбранный хранитель не отвечает за {@link #SHARD_TIMEOUT_MILLIS},
 * downloader пробует следующего из списка реплик. Это и есть сценарий ТЗ
 * п. 8.2.1: «Узел Б выключен — скачать файл с оставшихся узлов».
 */
public class FileDownloader {

    private static final Logger LOG = LoggerFactory.getLogger(FileDownloader.class);

    /** Тайм-аут на ответ одной реплики. */
    public static final long SHARD_TIMEOUT_MILLIS = 5_000;

    private final PrivateKey ownerPrivateKey;

    /**
     * Наш собственный публичный ключ (Base64). Нужен, чтобы при скачивании
     * чужого файла понять «я recipient ACL» и взять перешифрованный ключ.
     * Может быть {@code null} в старом API дня 6 (тогда работает только
     * сценарий «скачиваем свой файл»).
     */
    private final String myPublicKeyBase64;

    /**
     * Ссылка на локальный блокчейн — нужна для поиска ACL-транзакций при
     * скачивании чужих файлов и для
     * проверки, не помечен ли файл как удалённый
     * Может быть {@code null} в старом API дня 6 — в этом случае
     * поддерживается только скачивание собственных файлов.
     */
    private final ru.hse.jblockstorage.blockchain.Blockchain blockchain;

    /** Pending shard requests: shardHashHex → future с ответом. */
    private final Map<String, CompletableFuture<ShardResponseMessage>> pendingShards = new ConcurrentHashMap<>();

    /**
     * Локальное хранилище шардов самого узла. Если не {@code null} и
     * среди хранителей шарда оказывается self (см. {@link #selfNodeId}),
     * шард читается локально вместо сетевого GET_SHARD round-trip.
     * Симметрично self-path в {@link FileUploader} — без этого узел не
     * может скачать собственный файл, если сам же хранит единственную
     * живую реплику (типично для replication=2 и одного упавшего пира).
     *
     * <p>Может быть {@code null} для обратной совместимости (старые
     * тесты, конструировавшие FileDownloader без storage).
     */
    private final ShardStorage selfStorage;

    /**
     * Наш {@code nodeId} = публичный ключ Base64. Нужен, чтобы в
     * {@link #fetchShard} понять «этот хранитель — это я» и дернуть
     * {@link #selfStorage} вместо сети. {@code null} в старом API.
     */
    private final String selfNodeId;

    /**
     * Старый конструктор дня 6 — без поддержки ACL.
     * Скачивание возможно только если транзакция принадлежит этому же владельцу
     * (приватный ключ, переданный сюда). Используется в тестах дня 6, которые
     * не работают с ACL.
     */
    public FileDownloader(PrivateKey ownerPrivateKey) {
        this(ownerPrivateKey, null, null, null, null);
    }

    /**
     * Полный конструктор дня 9 — с поддержкой ACL и DELETE.
     *
     * @param ownerPrivateKey    приватный ключ текущего пользователя
     * @param myPublicKeyBase64  публичный ключ (Base64) — для поиска ACL
     * @param blockchain         блокчейн — для поиска ACL и проверки удаления
     */
    public FileDownloader(PrivateKey ownerPrivateKey,
                          String myPublicKeyBase64,
                          ru.hse.jblockstorage.blockchain.Blockchain blockchain) {
        this(ownerPrivateKey, myPublicKeyBase64, blockchain, null, null);
    }

    /**
     * Полный конструктор с поддержкой self-storage (день 13 fix3, симметрично
     * {@link FileUploader}). Если {@code selfStorage != null} и self
     * упомянут в {@link Transaction#getReplicas()} как хранитель,
     * скачивание идёт через локальный диск, а не через сеть.
     */
    public FileDownloader(PrivateKey ownerPrivateKey,
                          String myPublicKeyBase64,
                          ru.hse.jblockstorage.blockchain.Blockchain blockchain,
                          String selfNodeId,
                          ShardStorage selfStorage) {
        this.ownerPrivateKey = Objects.requireNonNull(ownerPrivateKey, "ownerPrivateKey");
        this.myPublicKeyBase64 = myPublicKeyBase64;
        this.blockchain = blockchain;
        this.selfNodeId = selfNodeId;
        this.selfStorage = selfStorage;
    }

    /**
     * Главная точка входа.
     *
     * @param tx                  транзакция файла из локального блокчейна
     * @param storerSessions      сессии к доступным хранителям (nodeId → session).
     *                            Должны включать как минимум одного из реплик
     *                            каждого шарда, иначе скачать не получится.
     * @param outputFile          куда записать восстановленный файл
     */
    public void downloadFile(Transaction tx,
                             Map<String, PeerSession> storerSessions,
                             Path outputFile)
            throws IOException, InterruptedException, TimeoutException {
        downloadFile(tx, storerSessions, outputFile, null);
    }

    /**
     * Расширенная точка входа с {@link DownloadProgressListener} —
     * день 15, ТЗ п. 4.1.5.3. Listener получает события на каждом шаге
     * (начало, каждый шард, успех/сбой), что позволяет UI показать
     * прогресс-бар, скорость и список узлов в реальном времени.
     *
     * <p>Если {@code listener == null}, поведение полностью идентично
     * старому 3-параметровому варианту. Это сохраняет совместимость
     * со всеми существующими тестами.
     *
     * @param listener колбек событий или {@code null}
     */
    public void downloadFile(Transaction tx,
                             Map<String, PeerSession> storerSessions,
                             Path outputFile,
                             DownloadProgressListener listener)
            throws IOException, InterruptedException, TimeoutException {

        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(storerSessions, "storerSessions");
        Objects.requireNonNull(outputFile, "outputFile");

        // День 9: проверки kind + DELETE.
        if (tx.getKind() != Transaction.Kind.UPLOAD) {
            throw new IllegalArgumentException(
                    "Скачивать можно только UPLOAD-транзакции, а у этой kind="
                            + tx.getKind());
        }
        if (blockchain != null && blockchain.isDeleted(tx.getId())) {
            throw new IllegalStateException(
                    "Файл помечен как удалённый — скачать нельзя");
        }

        // 1. Подготовка: hashHex → список nodeId, которые его хранят.
        // День 10: если есть REPAIR-транзакция — её список реплик свежее
        // оригинального UPLOAD'а. Используем последнюю REPAIR.
        Transaction replicaSource = tx;
        if (blockchain != null) {
            replicaSource = blockchain.findLatestRepairFor(tx.getId()).orElse(tx);
        }
        Map<String, List<String>> shardToStorers = buildShardToStorerIndex(replicaSource);
        List<String> orderedShards = tx.getShardHashes();
        if (orderedShards == null || orderedShards.isEmpty()) {
            throw new IllegalArgumentException("В транзакции нет списка шардов");
        }

        LOG.info("Начинаем download '{}' ({} шардов, {} реплик{})",
                tx.getFileName(), orderedShards.size(),
                replicaSource.getReplicas().size(),
                replicaSource == tx ? "" : ", REPAIR применён");

        // День 15: уведомляем listener о старте — UI узнает имя/размер/шарды.
        if (listener != null) {
            try {
                listener.onStart(tx.getFileName(), tx.getFileSize(), orderedShards.size());
            } catch (Exception e) {
                LOG.debug("Listener onStart кинул: {}", e.toString());
            }
        }

        // 2. Расшифровка AES-ключа — делаем это ПЕРВЫМ, ДО скачивания шардов.
        //    Если у нас нет доступа к файлу (нет своего ключа и нет ACL),
        //    лучше упасть с явным "ACL не найден", чем сначала тянуть мегабайты
        //    шардов по сети, а уже потом обнаружить, что расшифровать их нечем.
        //    Это поведение ожидается тестом charlieWithoutAclCannotDownload.
        byte[] rawAesKey;
        try {
            rawAesKey = resolveAesKey(tx);
        } catch (RuntimeException e) {
            if (listener != null) {
                try { listener.onFailed(e); } catch (Exception ignore) {}
            }
            throw e;
        }

        try {
            // 3. Скачиваем шарды ПАРАЛЛЕЛЬНО (ТЗ п. 4.1.1.4.1: «параллельная загрузка
            //    с нескольких узлов»). Каждый шард — отдельная задача в пуле; внутри
            //    одной задачи fetchShard сохраняет sequential fallback по хранителям
            //    (это закрывает сценарий 8.2.1 «Б упал, тянем с В»). Между шардами —
            //    параллельность: общее время ≈ max(один шард), а не sum.
            byte[][] collectedShards = fetchAllShardsParallel(
                    orderedShards, shardToStorers, storerSessions, listener);

            // 4. Склейка шифротекста
            int totalLen = 0;
            for (byte[] s : collectedShards) totalLen += s.length;
            byte[] ciphertext = new byte[totalLen];
            int offset = 0;
            for (byte[] s : collectedShards) {
                System.arraycopy(s, 0, ciphertext, offset, s.length);
                offset += s.length;
            }

            // 5. Расшифровка содержимого AES-GCM
            SecretKey aesKey = AesGcm.keyFromBytes(rawAesKey);
            byte[] plaintext = AesGcm.decrypt(ciphertext, aesKey);

            // 6. Sanity check — размер должен совпадать с записанным в транзакции
            if (plaintext.length != tx.getFileSize()) {
                IllegalStateException ex = new IllegalStateException(
                        "Размер расшифрованного файла " + plaintext.length
                                + " не совпадает с заявленным в транзакции " + tx.getFileSize());
                if (listener != null) {
                    try { listener.onFailed(ex); } catch (Exception ignore) {}
                }
                throw ex;
            }

            Files.write(outputFile, plaintext);
            LOG.info("Файл успешно восстановлен: {} ({} байт)", outputFile, plaintext.length);
            if (listener != null) {
                try { listener.onFinished(); } catch (Exception ignore) {}
            }
        } catch (RuntimeException | IOException e) {
            // На любой неожиданный сбой — тоже сигнализируем listener'у.
            // Но не в случае, если onFailed уже вызывался выше (для
            // конкретных проверенных исключений). Поэтому проверим тип.
            if (listener != null && !(e instanceof IllegalStateException)) {
                try { listener.onFailed(e); } catch (Exception ignore) {}
            }
            throw e;
        } finally {
            // Затираем raw AES-ключ из памяти для гигиены — даже если выше
            // случилось исключение (например, шард не скачался).
            java.util.Arrays.fill(rawAesKey, (byte) 0);
        }
    }

    /**
     * Возвращает raw AES-ключ файла, расшифрованный нашим приватным
     * ключом. Логика выбора источника:
     * <ol>
     *   <li>Если транзакция принадлежит нам — используем
     *       {@link Transaction#getEncryptedAesKey()} напрямую.</li>
     *   <li>Иначе — ищем ACL-транзакцию, выданную нам владельцем,
     *       и берём {@link Transaction#getEncryptedAesKeyForRecipient()}.</li>
     *   <li>Если ни того, ни другого — выбрасываем {@link IllegalStateException}.</li>
     * </ol>
     */
    private byte[] resolveAesKey(Transaction tx) {
        // Сценарий 1: владелец TX = мы.
        if (myPublicKeyBase64 == null
                || myPublicKeyBase64.equals(tx.getOwnerPublicKey())) {
            // myPublicKeyBase64 == null — старый API дня 6 (FileDownloader без
            // ACL). В этом сценарии тест предполагает, что вызывает владелец.
            if (tx.getEncryptedAesKey() == null) {
                throw new IllegalStateException("В транзакции нет encryptedAesKey");
            }
            byte[] enc = Base64.getDecoder().decode(tx.getEncryptedAesKey());
            return RsaOaep.decrypt(enc, ownerPrivateKey);
        }

        // Сценарий 2: ищем ACL — нужен blockchain.
        if (blockchain == null) {
            throw new IllegalStateException(
                    "Это чужая транзакция, но FileDownloader создан без блокчейна — "
                            + "ACL искать негде");
        }
        Transaction acl = blockchain.findAclFor(tx.getId(), myPublicKeyBase64)
                .orElseThrow(() -> new IllegalStateException(
                        "У вас нет доступа к этому файлу: ACL-транзакции не найдено"));

        if (acl.getEncryptedAesKeyForRecipient() == null) {
            throw new IllegalStateException(
                    "ACL-транзакция найдена, но в ней нет ключа для recipient'а");
        }
        if (!acl.verify()) {
            // Это серьёзный сигнал: ACL подделан или повреждён в блокчейне.
            // Лучше отказать, чем расшифровать мусором.
            throw new IllegalStateException(
                    "ACL-транзакция не прошла проверку подписи владельца");
        }
        byte[] enc = Base64.getDecoder().decode(acl.getEncryptedAesKeyForRecipient());
        LOG.info("Используем ACL-ключ от {} для скачивания", shortKey(acl.getOwnerPublicKey()));
        return RsaOaep.decrypt(enc, ownerPrivateKey);
    }

    private static String shortKey(String key) {
        if (key == null) return "?";
        return key.length() > 8 ? key.substring(0, 8) + "…" : key;
    }

    /**
     * Скачивает один шард, перебирая хранителей по списку. Возвращает {@code null},
     * если ни один не ответил или все ответили мусором.
     * <p>
     * Метод пакет-приватный — переиспользуется в {@code RepairService}
     * для скачивания шарда перед его перерепликацией (день 10).
     */
    byte[] fetchShard(String hashHex, List<String> storerNodeIds,
                      Map<String, PeerSession> sessions)
            throws InterruptedException {
        return fetchShard(hashHex, storerNodeIds, sessions, -1, null);
    }

    /**
     * Параллельно скачивает все шарды (ТЗ п. 4.1.1.4.1).
     * Каждый шард обрабатывается в отдельном потоке, общее время ограничено
     * самым медленным шардом (а не суммой), что критично для больших файлов
     * (например, 100 шардов по 512 КБ = 100 round-trip'ов в sequential vs
     * 1 round-trip в parallel). Внутри одного шарда сохранён sequential
     * fallback по хранителям — это закрывает сценарий ТЗ 8.2.1.
     *
     * <p>Размер пула ограничен 16 потоками (этого достаточно даже для
     * крупных файлов: больший параллелизм упирается в пропускную способность
     * сети, не в потоки), но не больше числа шардов. Пул с daemon-потоками
     * — JVM не задержится на их ожидании при выходе.
     *
     * @return массив шардов в исходном порядке; для не скачавшихся — {@code null}
     *         в соответствующей позиции
     */
    private byte[][] fetchAllShardsParallel(List<String> orderedShards,
                                            Map<String, List<String>> shardToStorers,
                                            Map<String, PeerSession> storerSessions,
                                            DownloadProgressListener listener)
            throws InterruptedException {
        int n = orderedShards.size();
        int parallelism = Math.min(n, 16);

        AtomicInteger threadCounter = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(parallelism, r -> {
            Thread t = new Thread(r, "shard-fetch-" + threadCounter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });

        List<CompletableFuture<byte[]>> futures = new ArrayList<>(n);
        try {
            for (int i = 0; i < n; i++) {
                final int idx = i;
                final String hash = orderedShards.get(idx);
                final List<String> storers = shardToStorers.getOrDefault(hash, List.of());

                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        return fetchShard(hash, storers, storerSessions, idx, listener);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }, pool));
            }

            // Ждём, пока ВСЕ задачи завершатся (успешно или с null).
            // Тайм-аут на отдельный шард уже задан в fetchShard через
            // SHARD_TIMEOUT_MILLIS на хранителя × число хранителей —
            // здесь верхней границы не ставим, иначе получим двойной timeout.
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
            } catch (ExecutionException e) {
                // Внутри supplyAsync мы уже ловим InterruptedException и
                // возвращаем null — других checked исключений быть не должно.
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (cause instanceof RuntimeException re) throw re;
                throw new RuntimeException("Сбой параллельной загрузки шардов", cause);
            }
        } finally {
            pool.shutdown();
            // Не ждём termination — пулы с daemon-потоками не блокируют JVM.
        }

        byte[][] collected = new byte[n][];
        for (int i = 0; i < n; i++) {
            byte[] data;
            try {
                data = futures.get(i).get();
            } catch (ExecutionException e) {
                data = null; // не должно случиться, см. выше
            }
            if (data == null) {
                String hashShort = orderedShards.get(i).substring(0, 16);
                int storersCount = shardToStorers.getOrDefault(orderedShards.get(i), List.of()).size();
                IllegalStateException ex = new IllegalStateException(
                        "Не удалось скачать шард " + hashShort
                                + "… ни от одного из " + storersCount + " известных хранителей");
                if (listener != null) {
                    try { listener.onFailed(ex); } catch (Exception ignore) {}
                }
                throw ex;
            }
            collected[i] = data;
        }
        return collected;
    }

    /**
     * Расширенный {@link #fetchShard(String, List, Map)} с уведомлениями
     * для UI (день 15). Каждая попытка обращения к хранителю
     * репортится через {@code listener}: до — {@code onShardStarted},
     * успех — {@code onShardFinished}, неуспех — {@code onShardFailed}.
     *
     * @param shardIndex индекс шарда для UI; -1 если listener=null
     * @param listener   {@code null} → метод ведёт себя как старый
     */
    byte[] fetchShard(String hashHex, List<String> storerNodeIds,
                      Map<String, PeerSession> sessions,
                      int shardIndex,
                      DownloadProgressListener listener)
            throws InterruptedException {
        for (String nodeId : storerNodeIds) {
            // Уведомляем listener'а о попытке. Если у нас self-storage
            // путь (см. ниже), это будет корректно "self".
            if (listener != null) {
                try { listener.onShardStarted(shardIndex, nodeId); } catch (Exception ignore) {}
            }

            // SELF-PATH: если в списке хранителей мы сами и у нас есть
            // локальное хранилище — читаем шард локально без сети.
            // Это критично для сценария «replication=2, один пир упал»:
            // оставшийся хранитель = self, и без локального чтения
            // download падает с «не удалось скачать» (см. fix3).
            if (selfStorage != null && nodeId.equals(selfNodeId)) {
                try {
                    java.util.Optional<byte[]> local = selfStorage.load(hashHex);
                    if (local.isPresent()) {
                        byte[] data = local.get();
                        // Проверка целостности всё равно полезна — на случай
                        // повреждения локального файла.
                        String actualHash = CryptoUtils.toHex(CryptoUtils.applySha256(data));
                        if (actualHash.equals(hashHex)) {
                            LOG.debug("Self-storage: шард {} прочитан локально ({} байт)",
                                    hashHex.substring(0, 8) + "…", data.length);
                            if (listener != null) {
                                try { listener.onShardFinished(shardIndex, nodeId, data.length); }
                                catch (Exception ignore) {}
                            }
                            return data;
                        } else {
                            LOG.warn("Self-storage: локальный шард {} испорчен (хеш не сходится), пробуем сеть",
                                    hashHex.substring(0, 8) + "…");
                            if (listener != null) {
                                try { listener.onShardFailed(shardIndex, nodeId, "хеш не сходится"); }
                                catch (Exception ignore) {}
                            }
                        }
                    } else {
                        LOG.debug("Self-storage: шарда {} локально нет, пробуем сеть",
                                hashHex.substring(0, 8) + "…");
                        if (listener != null) {
                            try { listener.onShardFailed(shardIndex, nodeId, "нет локально"); }
                            catch (Exception ignore) {}
                        }
                    }
                } catch (IOException e) {
                    LOG.warn("Self-storage: ошибка чтения {}: {}, пробуем сеть",
                            hashHex.substring(0, 8) + "…", e.toString());
                    if (listener != null) {
                        try { listener.onShardFailed(shardIndex, nodeId, e.toString()); }
                        catch (Exception ignore) {}
                    }
                }
                // Если самосчитать не удалось — fall through, дальше идёт сеть.
                // Но self в списке хранителей будет один раз, поэтому здесь
                // continue: следующий nodeId в storerNodeIds будет уже не self.
                continue;
            }

            PeerSession session = sessions.get(nodeId);
            if (session == null || !session.isActive()) {
                LOG.debug("Хранитель {} недоступен, пробуем следующего", nodeId);
                if (listener != null) {
                    try { listener.onShardFailed(shardIndex, nodeId, "сессия неактивна"); }
                    catch (Exception ignore) {}
                }
                continue;
            }

            CompletableFuture<ShardResponseMessage> future = new CompletableFuture<>();
            pendingShards.put(hashHex, future);
            session.send(new GetShardMessage(hashHex));

            ShardResponseMessage resp;
            try {
                resp = future.get(SHARD_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                LOG.debug("Хранитель {} не ответил за {}мс — пробуем следующего",
                        nodeId, SHARD_TIMEOUT_MILLIS);
                if (listener != null) {
                    try { listener.onShardFailed(shardIndex, nodeId, "таймаут"); }
                    catch (Exception ignore) {}
                }
                continue;
            } catch (java.util.concurrent.ExecutionException e) {
                LOG.debug("Сбой при ожидании шарда от {}: {}", nodeId, e.toString());
                if (listener != null) {
                    try { listener.onShardFailed(shardIndex, nodeId, e.toString()); }
                    catch (Exception ignore) {}
                }
                continue;
            } finally {
                pendingShards.remove(hashHex);
            }

            byte[] data = resp.getData();
            if (data == null) {
                LOG.debug("Хранитель {} ответил, но шарда не имеет — следующий", nodeId);
                if (listener != null) {
                    try { listener.onShardFailed(shardIndex, nodeId, "шарда нет у хранителя"); }
                    catch (Exception ignore) {}
                }
                continue;
            }

            // Целостность: hash полученных данных должен совпасть с запрошенным.
            String actualHash = CryptoUtils.toHex(CryptoUtils.applySha256(data));
            if (!actualHash.equals(hashHex)) {
                LOG.warn("Хранитель {} прислал испорченный шард (хеш не сходится)", nodeId);
                if (listener != null) {
                    try { listener.onShardFailed(shardIndex, nodeId, "хеш не сходится"); }
                    catch (Exception ignore) {}
                }
                continue;
            }

            if (listener != null) {
                try { listener.onShardFinished(shardIndex, nodeId, data.length); }
                catch (Exception ignore) {}
            }
            return data;
        }
        return null;
    }

    /**
     * Построить индекс «хеш шарда → список nodeId хранителей» из транзакции.
     */
    private Map<String, List<String>> buildShardToStorerIndex(Transaction tx) {
        Map<String, List<String>> result = new HashMap<>();
        for (StorageReceipt r : tx.getReplicas()) {
            result.computeIfAbsent(r.getShardHashHex(), k -> new ArrayList<>())
                    .add(r.getStorerPublicKey()); // мы используем publicKey как nodeId
        }
        return result;
    }

    /**
     * Колбэк для роутера сообщений: внешняя логика (узел) при получении
     * {@link ShardResponseMessage} должна вызвать этот метод. Метод завершает
     * соответствующий future, на котором ждёт {@link #fetchShard}.
     *
     * @return {@code true} если ответ был ожидаем
     */
    public boolean handleShardResponse(ShardResponseMessage resp) {
        CompletableFuture<ShardResponseMessage> future = pendingShards.remove(resp.getShardHashHex());
        if (future == null) return false;
        future.complete(resp);
        return true;
    }
}