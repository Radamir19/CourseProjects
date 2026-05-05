package ru.hse.jblockstorage.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.hse.jblockstorage.blockchain.Blockchain;
import ru.hse.jblockstorage.blockchain.StorageReceipt;
import ru.hse.jblockstorage.blockchain.Transaction;
import ru.hse.jblockstorage.crypto.AesGcm;
import ru.hse.jblockstorage.crypto.KeyManager;
import ru.hse.jblockstorage.crypto.RsaOaep;
import ru.hse.jblockstorage.network.PeerInfo;
import ru.hse.jblockstorage.network.PeerSession;
import ru.hse.jblockstorage.network.PutShardAckMessage;
import ru.hse.jblockstorage.network.PutShardMessage;
import ru.hse.jblockstorage.storage.FileChunker;
import ru.hse.jblockstorage.storage.MerkleTree;
import ru.hse.jblockstorage.storage.Shard;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Оркестратор загрузки файла в распределённую сеть.
 * <p>
 * Алгоритм одного upload (см. вариант Е из проекта, leaderless replication
 * по Клеппману, гл. 5):
 * <ol>
 *   <li>Сгенерировать случайный AES-256 ключ файла.</li>
 *   <li>Зашифровать файл этим ключом (AES-GCM).</li>
 *   <li>Зашифровать сам ключ публичным ключом владельца (RSA-OAEP) — это
 *       нужно, чтобы при download владелец смог расшифровать файл,
 *       используя свой приватный RSA-ключ. Без этого AES-ключ был бы
 *       где-то снаружи системы.</li>
 *   <li>Разрезать шифротекст на шарды (по 512 КБ по умолчанию из ТЗ).</li>
 *   <li>Построить дерево Меркла, получить Merkle Root.</li>
 *   <li>Выбрать K=3 узла-хранителя из {@code PeerManager}.</li>
 *   <li>Отправить каждому из них все шарды через {@link PutShardMessage},
 *       дождаться {@link PutShardAckMessage} с подписанным receipt'ом.</li>
 *   <li>Сформировать транзакцию (включая encrypted AES key, hashes, receipts)
 *       и подписать приватным ключом владельца.</li>
 *   <li>Добавить транзакцию в локальный блокчейн через {@code Blockchain.addBlock}.</li>
 * </ol>
 *
 * <h3>Что класс <i>не</i> делает</h3>
 * Не рассылает блок другим узлам — это задача отдельного компонента
 * (или будущего {@code BroadcastBlockMessage}). На дне 6 нам важно,
 * чтобы транзакция оказалась в блокчейне локально, и это даёт
 * downloader-у нужные метаданные.
 */
public final class FileUploader {

    private static final Logger LOG = LoggerFactory.getLogger(FileUploader.class);

    /** Ответ на PUT_SHARD ждём не дольше этого. */
    public static final long ACK_TIMEOUT_MILLIS = 10_000;

    /** Фактор репликации по умолчанию (ТЗ п. 4.1.1.2.5: K ≥ 3). */
    public static final int DEFAULT_REPLICATION_FACTOR = 3;

    private final PublicKey ownerPublicKey;
    private final PrivateKey ownerPrivateKey;
    private final String ownerPublicKeyBase64;
    private final Blockchain blockchain;
    private final int chunkSize;
    private final int replicationFactor;

    /**
     * Pending acknowledgements: shardHash → future, который завершится
     * при получении соответствующего {@link PutShardAckMessage}.
     * Ключ дополнительно содержит nodeId хранителя на случай, если разные
     * узлы хранят один и тот же шард — каждое ожидание независимо.
     */
    private final Map<AckKey, CompletableFuture<PutShardAckMessage>> pendingAcks = new ConcurrentHashMap<>();

    public FileUploader(PublicKey ownerPublicKey, PrivateKey ownerPrivateKey,
                        Blockchain blockchain) {
        this(ownerPublicKey, ownerPrivateKey, blockchain,
             FileChunker.DEFAULT_CHUNK_SIZE, DEFAULT_REPLICATION_FACTOR);
    }

    public FileUploader(PublicKey ownerPublicKey, PrivateKey ownerPrivateKey,
                        Blockchain blockchain, int chunkSize, int replicationFactor) {
        this.ownerPublicKey = Objects.requireNonNull(ownerPublicKey, "ownerPublicKey");
        this.ownerPrivateKey = Objects.requireNonNull(ownerPrivateKey, "ownerPrivateKey");
        this.ownerPublicKeyBase64 = KeyManager.publicKeyToBase64(ownerPublicKey);
        this.blockchain = Objects.requireNonNull(blockchain, "blockchain");
        if (chunkSize <= 0) throw new IllegalArgumentException("chunkSize должен быть > 0");
        if (replicationFactor < 1) throw new IllegalArgumentException("replicationFactor должен быть ≥ 1");
        this.chunkSize = chunkSize;
        this.replicationFactor = replicationFactor;
    }

    /**
     * Главная точка входа: загружает файл в сеть и возвращает созданную транзакцию.
     *
     * @param file               путь к файлу для загрузки
     * @param availableStorers   список потенциальных хранителей (обычно — {@code peerManager.snapshot()})
     *                           вместе с открытыми сессиями к ним
     * @return подписанная транзакция, уже добавленная в локальный блокчейн
     */
    public Transaction uploadFile(Path file, List<StorerHandle> availableStorers)
            throws IOException, InterruptedException, TimeoutException {

        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(availableStorers, "availableStorers");

        if (availableStorers.size() < replicationFactor) {
            throw new IllegalStateException(
                    "Недостаточно хранителей: нужно ≥" + replicationFactor
                            + ", есть " + availableStorers.size());
        }

        // ШАГ 1-3: AES-ключ + шифрование файла + шифрование ключа RSA-OAEP
        byte[] plaintext = Files.readAllBytes(file);
        long fileSize = plaintext.length;

        SecretKey aesKey = AesGcm.generateKey();
        byte[] ciphertext = AesGcm.encrypt(plaintext, aesKey);

        byte[] rawAesKey = AesGcm.keyToBytes(aesKey);
        byte[] encryptedAesKey = RsaOaep.encrypt(rawAesKey, ownerPublicKey);
        String encryptedAesKeyB64 = Base64.getEncoder().encodeToString(encryptedAesKey);

        // ШАГ 4-5: шардинг + Merkle root
        List<Shard> shards = FileChunker.chunk(ciphertext, chunkSize);
        if (shards.isEmpty()) {
            // Пустой файл — допустимо, но тогда Merkle Tree поломается.
            // На MVP проще запретить.
            throw new IllegalArgumentException("Пустые файлы не поддерживаются");
        }
        MerkleTree merkle = MerkleTree.fromShards(shards);
        String merkleRoot = merkle.rootHex();

        List<String> shardHashes = new ArrayList<>(shards.size());
        for (Shard s : shards) shardHashes.add(s.hashHex());

        LOG.info("Файл {} ({} байт) → {} шардов, Merkle root {}",
                file.getFileName(), fileSize, shards.size(),
                merkleRoot.substring(0, 16) + "…");

        // ШАГ 6: выбор K хранителей.
        // Простая стратегия: первые K из доступных. Сохраняем порядок —
        // потом используется тестом «отключи первого».
        List<StorerHandle> chosen = availableStorers.stream()
                .limit(replicationFactor)
                .toList();

        // ШАГ 7: формируем "пред-транзакцию" с tx-id, чтобы receipts могли
        // на него ссылаться. Подписи и receipts будут добавлены далее.
        Transaction tx = new Transaction(ownerPublicKeyBase64,
                file.getFileName().toString(), fileSize, merkleRoot);
        tx.setEncryptedAesKey(encryptedAesKeyB64);
        tx.setShardHashes(shardHashes);
        // Заранее вычисляем "preliminary" id — он стабилен и не зависит
        // от ещё не собранных receipts. Все receipts будут подписаны под
        // этим preliminary id, и Transaction.verifyReplicas() сверяется
        // именно с ним.
        String preliminaryTxId = tx.calculatePreliminaryId();

        // ШАГ 7 (продолжение): рассылаем шарды и собираем receipts
        List<StorageReceipt> allReceipts = pushShardsToStorers(chosen, preliminaryTxId, shards);

        // ШАГ 8: финализируем транзакцию с receipts и подписываем
        tx.setReplicas(allReceipts);
        tx.sign(ownerPrivateKey);

        // ШАГ 9: добавляем в локальный блокчейн
        blockchain.addBlock(List.of(tx));

        LOG.info("Upload завершён: tx={}, шардов={}, реплик={}",
                tx.getId().substring(0, 16) + "…", shards.size(), allReceipts.size());

        return tx;
    }

    /**
     * Рассылает все шарды каждому из выбранных хранителей и собирает receipts.
     * Возвращает все полученные receipts (один на (storer, shard) пару).
     * <p>
     * Метод пакет-приватный — переиспользуется в {@code RepairService}
     * для повторной репликации (день 10).
     */
    List<StorageReceipt> pushShardsToStorers(List<StorerHandle> storers,
                                             String preliminaryTxId,
                                             List<Shard> shards)
            throws InterruptedException, TimeoutException {
        List<StorageReceipt> receipts = new ArrayList<>();

        for (StorerHandle storer : storers) {
            for (Shard shard : shards) {
                CompletableFuture<PutShardAckMessage> future = new CompletableFuture<>();
                AckKey key = new AckKey(storer.nodeId(), shard.hashHex());
                pendingAcks.put(key, future);

                storer.session().send(new PutShardMessage(
                        preliminaryTxId, shard.hashHex(), shard.data()));

                PutShardAckMessage ack;
                try {
                    ack = future.get(ACK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.ExecutionException e) {
                    throw new IllegalStateException("Сбой при ожидании ack от " + storer.nodeId(), e);
                } finally {
                    pendingAcks.remove(key);
                }

                if (!ack.isAccepted()) {
                    throw new IllegalStateException(
                            "Хранитель " + storer.nodeId() + " отклонил шард "
                                    + shard.hashHex() + ": " + ack.getReason());
                }

                StorageReceipt receipt = ack.getReceipt();
                if (receipt == null || !receipt.verify()) {
                    throw new IllegalStateException(
                            "Хранитель " + storer.nodeId() + " прислал невалидный receipt для шарда "
                                    + shard.hashHex());
                }
                if (!receipt.getTransactionId().equals(preliminaryTxId)
                        || !receipt.getShardHashHex().equals(shard.hashHex())) {
                    throw new IllegalStateException(
                            "Receipt от " + storer.nodeId() + " относится не к тому шарду/транзакции");
                }
                receipts.add(receipt);
            }
        }
        return receipts;
    }

    /**
     * Колбэк для роутера сообщений: внешняя логика (узел) при получении
     * {@link PutShardAckMessage} вызывает этот метод, чтобы он завершил
     * соответствующий future, на котором ждёт {@link #pushShardsToStorers}.
     *
     * @return {@code true} если ack был ожидаем и future-ра завершён,
     *         {@code false} если ack пришёл «не ко времени» (таймаут или дубль)
     */
    public boolean handleAck(String storerNodeId, PutShardAckMessage ack) {
        AckKey key = new AckKey(storerNodeId, ack.getShardHashHex());
        CompletableFuture<PutShardAckMessage> future = pendingAcks.remove(key);
        if (future == null) return false;
        future.complete(ack);
        return true;
    }

    // ================================================================
    // День 9: ACL — предоставление доступа другому пользователю
    // ================================================================

    /**
     * Создаёт ACL-транзакцию: даёт recipient'у право скачать файл
     * {@code originalTxId}.
     * <p>
     * Алгоритм:
     * <ol>
     *   <li>Найти оригинальную UPLOAD-транзакцию в блокчейне.</li>
     *   <li>Убедиться, что мы её владелец (иначе — отказ, никто чужие
     *       файлы шарить не может).</li>
     *   <li>Расшифровать её AES-ключ нашим приватным ключом (RSA-OAEP).</li>
     *   <li>Перешифровать тот же AES-ключ под публичным ключом recipient'а
     *       (RSA-OAEP).</li>
     *   <li>Сформировать ACL-транзакцию через {@link Transaction#newAcl},
     *       подписать своим приватным ключом, добавить в блокчейн.</li>
     * </ol>
     *
     * <h3>Обоснование</h3>
     * Это закрывает ТЗ п. 4.1.1.3.2 «Управление доступом (ACL TX): возможность
     * отправки транзакции, предоставляющей право на скачивание файла
     * (расшифровку ключа) другому пользователю». Тривиальное расширение
     * существующей RSA-OAEP инфраструктуры дня 6 — тот же AES-ключ просто
     * шифруется под двух разных получателей.
     *
     * @return ACL-транзакция, уже добавленная в локальный блокчейн
     * @throws IllegalArgumentException если оригинальной TX нет в цепи или
     *                                  если она принадлежит не нам
     */
    public Transaction shareFile(String originalTxId, String recipientPublicKeyB64) {
        Objects.requireNonNull(originalTxId, "originalTxId");
        Objects.requireNonNull(recipientPublicKeyB64, "recipientPublicKeyB64");

        Transaction original = blockchain.findByTxId(originalTxId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Транзакция " + originalTxId + " не найдена в блокчейне"));

        if (original.getKind() != Transaction.Kind.UPLOAD) {
            throw new IllegalArgumentException(
                    "Можно расшаривать только UPLOAD-транзакции, а у этой kind="
                            + original.getKind());
        }
        if (!ownerPublicKeyBase64.equals(original.getOwnerPublicKey())) {
            throw new IllegalArgumentException(
                    "Расшаривать чужой файл нельзя: владелец TX — другой пользователь");
        }
        if (blockchain.isDeleted(originalTxId)) {
            throw new IllegalArgumentException(
                    "Файл удалён, расшаривать нечего");
        }
        if (recipientPublicKeyB64.equals(ownerPublicKeyBase64)) {
            throw new IllegalArgumentException(
                    "Расшаривать самому себе бессмысленно");
        }

        // 1. Расшифровываем AES-ключ файла нашим приватным RSA-ключом.
        byte[] encryptedAesKey = Base64.getDecoder().decode(original.getEncryptedAesKey());
        byte[] rawAesKey = RsaOaep.decrypt(encryptedAesKey, ownerPrivateKey);

        // 2. Шифруем тот же AES-ключ под публичным ключом recipient'а.
        PublicKey recipientKey = KeyManager.publicKeyFromBase64(recipientPublicKeyB64);
        byte[] reencrypted = RsaOaep.encrypt(rawAesKey, recipientKey);
        String reencryptedB64 = Base64.getEncoder().encodeToString(reencrypted);

        // 3. Из соображений гигиены затираем raw AES-ключ — Java не даёт
        // полноценно стирать строки, но byte[] обнулить можем.
        java.util.Arrays.fill(rawAesKey, (byte) 0);

        // 4. Формируем и подписываем ACL-транзакцию.
        Transaction acl = Transaction.newAcl(
                ownerPublicKeyBase64, originalTxId, recipientPublicKeyB64, reencryptedB64);
        acl.sign(ownerPrivateKey);

        // 5. Добавляем в блокчейн.
        blockchain.addBlock(List.of(acl));
        LOG.info("ACL выдан: txId={} recipient={}",
                shortHash(originalTxId), shortNode(recipientPublicKeyB64));
        return acl;
    }

    // ================================================================
    // День 9: DELETE — логическое удаление
    // ================================================================

    /**
     * Создаёт DELETE-транзакцию: помечает файл как удалённый.
     * <p>
     * Физически шарды с узлов-хранителей не стираются (это была бы отдельная
     * задача Garbage Collection, ТЗ п. 4.1.1.4.3 явно говорит о «логическом
     * удалении»). После DELETE:
     * <ul>
     *   <li>{@link Blockchain#listByOwner} перестаёт возвращать эту TX;</li>
     *   <li>попытка {@code download} на неё должна вернуть осмысленную ошибку
     *       (это контролируется в {@code FileDownloader});</li>
     *   <li>попытка повторного DELETE — допустима (идемпотентна), просто
     *       создаст вторую DELETE-транзакцию.</li>
     * </ul>
     *
     * @return DELETE-транзакция, уже добавленная в локальный блокчейн
     */
    public Transaction deleteFile(String originalTxId) {
        Objects.requireNonNull(originalTxId, "originalTxId");

        Transaction original = blockchain.findByTxId(originalTxId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Транзакция " + originalTxId + " не найдена в блокчейне"));

        if (original.getKind() != Transaction.Kind.UPLOAD) {
            throw new IllegalArgumentException(
                    "Удалить можно только UPLOAD-транзакцию, а у этой kind="
                            + original.getKind());
        }
        if (!ownerPublicKeyBase64.equals(original.getOwnerPublicKey())) {
            throw new IllegalArgumentException(
                    "Удалить чужой файл нельзя");
        }

        Transaction del = Transaction.newDelete(ownerPublicKeyBase64, originalTxId);
        del.sign(ownerPrivateKey);
        blockchain.addBlock(List.of(del));
        LOG.info("DELETE применён к txId={}", shortHash(originalTxId));
        return del;
    }

    // ================================================================
    // День 10: вспомогательные методы для RepairService
    // ================================================================

    /**
     * Подписывает уже сформированную транзакцию приватным ключом владельца.
     * <p>
     * Используется {@link RepairService} для подписи REPAIR-транзакции,
     * у которой все поля уже заполнены — нужна только подпись. Сделано
     * отдельным методом, чтобы RepairService не имел доступа к приватному
     * ключу напрямую.
     */
    void signTransactionAsOwner(Transaction tx) {
        Objects.requireNonNull(tx, "tx");
        if (!ownerPublicKeyBase64.equals(tx.getOwnerPublicKey())) {
            throw new IllegalArgumentException(
                    "Подписываем только свои транзакции; ownerPublicKey не совпадает");
        }
        tx.sign(ownerPrivateKey);
    }

    // ================================================================
    // Утилиты
    // ================================================================

    private static String shortHash(String hash) {
        if (hash == null) return "?";
        return hash.length() > 8 ? hash.substring(0, 8) + "…" : hash;
    }

    private static String shortNode(String nodeId) {
        if (nodeId == null) return "?";
        return nodeId.length() > 8 ? nodeId.substring(0, 8) + "…" : nodeId;
    }

    /** Удалённый хранитель — пара (nodeId, активная сессия к нему). */
    public record StorerHandle(String nodeId, PeerSession session) {}

    /** Композитный ключ для pendingAcks: разные хранители одного шарда — независимы. */
    private record AckKey(String nodeId, String shardHash) {}
}
