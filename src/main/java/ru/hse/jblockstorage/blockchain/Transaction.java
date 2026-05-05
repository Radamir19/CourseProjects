package ru.hse.jblockstorage.blockchain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import ru.hse.jblockstorage.crypto.CryptoUtils;
import ru.hse.jblockstorage.crypto.KeyManager;
import ru.hse.jblockstorage.crypto.Signer;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * Транзакция, хранящая информацию о загруженном файле.
 * <p>
 * Согласно ТЗ (п. 4.1.1.3.1), содержит метаданные файла и подпись владельца.
 * Подпись вычисляется алгоритмом SHA256withRSA от канонической строки полей
 * (см. {@link #getDataToSign()}) и кодируется в Base64.
 * Публичный ключ владельца также хранится в Base64 (X.509 SubjectPublicKeyInfo).
 * </p>
 *
 * <h3>Расширения дня 6</h3>
 * Добавлены поля для гибридного шифрования и proof-of-placement:
 * <ul>
 *   <li>{@link #encryptedAesKey} — AES-ключ файла, зашифрованный
 *       публичным ключом владельца через RSA-OAEP. При скачивании
 *       владелец расшифровывает его своим приватным RSA-ключом и
 *       использует для расшифровки самого файла.</li>
 *   <li>{@link #shardHashes} — список SHA-256 хешей шардов в порядке.
 *       По нему downloader понимает, что нужно скачать.</li>
 *   <li>{@link #replicas} — список {@link StorageReceipt}, подтверждающих
 *       размещение шардов на конкретных узлах сети.</li>
 * </ul>
 * Поля включены в {@link #getDataToSign()} — нельзя подменить ни ключ,
 * ни список шардов, ни список реплик после подписания.
 */
public class Transaction implements Serializable {

    /** Уникальный ID транзакции (обычно хэш). */
    private String id;

    /** Публичный ключ владельца файла (Base64 X.509). */
    private String ownerPublicKey;

    /** Имя файла (например, "docs.pdf"). */
    private String fileName;

    /** Размер файла в байтах. */
    private long fileSize;

    /** Корневой хэш дерева Меркла (Merkle Root) для проверки целостности. */
    private String merkleRoot;

    /** Цифровая подпись транзакции (Base64). */
    private String signature;

    /** Временная метка транзакции. */
    private long timestamp;

    /** AES-ключ файла, зашифрованный RSA-OAEP под публичным ключом владельца (Base64). */
    private String encryptedAesKey;

    /** Хеши шардов в порядке (hex, 64 символа каждый). */
    private List<String> shardHashes = Collections.emptyList();

    /** Подписанные квитанции от хранителей шардов (proof of placement). */
    private List<StorageReceipt> replicas = Collections.emptyList();

    // ---------- Поля дня 9: типизация транзакций (ACL, DELETE) ----------

    /**
     * Тип транзакции. Default = {@link Kind#UPLOAD} для совместимости со
     * старыми тестами и blockchain-данными в RocksDB, где этого поля не было.
     */
    private Kind kind = Kind.UPLOAD;

    /**
     * Для ACL/DELETE — id оригинальной UPLOAD-транзакции, к которой
     * применяется операция. Для UPLOAD-транзакций не используется.
     */
    private String referencedTxId;

    /**
     * Для ACL — публичный ключ получателя доступа (Base64 X.509).
     * Это пользователь, которому владелец предоставляет право скачать файл.
     */
    private String recipientPublicKey;

    /**
     * Для ACL — AES-ключ файла, перешифрованный под публичным ключом recipient'а
     * через RSA-OAEP (Base64). При скачивании recipient расшифровывает его
     * своим приватным ключом, как обычно владелец делает с {@link #encryptedAesKey}.
     */
    private String encryptedAesKeyForRecipient;

    /**
     * Тип транзакции (ТЗ п. 4.1.1.3.1, 4.1.1.3.2, 4.1.1.4.3, 4.2.2).
     */
    public enum Kind {
        /** Загрузка нового файла — содержит метаданные, шарды, реплики. */
        UPLOAD,
        /** Предоставление доступа другому пользователю (ACL TX). */
        ACL,
        /** Логическое удаление файла (Delete TX). */
        DELETE,
        /**
         * Восстановление репликации после выхода узлов из сети
         * (Repair TX, ТЗ п. 4.2.2). Содержит ссылку на оригинальный UPLOAD
         * + актуальный полный список реплик (старые живые + новые).
         * Поле {@link #shardHashes} дублируется из оригинала, чтобы
         * downloader мог скачивать файл, опираясь только на свежую REPAIR-TX.
         */
        REPAIR
    }

    // Пустой конструктор для Jackson (JSON)
    public Transaction() {}

    // Старый конструктор — оставлен для совместимости с тестами дня 3.
    public Transaction(String ownerPublicKey, String fileName, long fileSize, String merkleRoot) {
        this.ownerPublicKey = ownerPublicKey;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.merkleRoot = merkleRoot;
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * Получаем данные, которые надо подписать. Подпись сюда не входит,
     * иначе её можно было бы поменять и пересчитать хеш.
     * <p>
     * Расширенная версия включает все поля дня 6 — это означает, что
     * злоумышленник не может подменить ни список шардов, ни AES-ключ,
     * ни список реплик после того, как владелец подписал транзакцию.
     * </p>
     */
    @JsonIgnore
    public String getDataToSign() {
        StringBuilder sb = new StringBuilder();
        sb.append(ownerPublicKey).append('|')
          .append(fileName).append('|')
          .append(fileSize).append('|')
          .append(merkleRoot).append('|')
          .append(timestamp);

        // Поля дня 6 — добавляем только если установлены, чтобы старые тесты
        // (которые подписывают транзакцию без них) продолжали работать.
        if (encryptedAesKey != null) {
            sb.append('|').append(encryptedAesKey);
        }
        if (shardHashes != null && !shardHashes.isEmpty()) {
            sb.append("|shards=");
            for (String h : shardHashes) sb.append(h).append(',');
        }
        if (replicas != null && !replicas.isEmpty()) {
            sb.append("|replicas=");
            for (StorageReceipt r : replicas) {
                sb.append(r.getStorerPublicKey()).append(':').append(r.getShardHashHex()).append(',');
            }
        }

        // Поля дня 9 — добавляются только для ACL/DELETE транзакций.
        // Для UPLOAD они не выводятся, что сохраняет байт-в-байт совместимость
        // со старыми подписями и блоками в RocksDB.
        if (kind != null && kind != Kind.UPLOAD) {
            sb.append("|kind=").append(kind.name());
            if (referencedTxId != null) {
                sb.append("|ref=").append(referencedTxId);
            }
            if (recipientPublicKey != null) {
                sb.append("|recipient=").append(recipientPublicKey);
            }
            if (encryptedAesKeyForRecipient != null) {
                sb.append("|aclKey=").append(encryptedAesKeyForRecipient);
            }
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return id + ownerPublicKey + fileName + fileSize + merkleRoot + timestamp;
    }

    /**
     * Подписывает транзакцию приватным ключом владельца.
     * После вызова поля {@link #signature} и {@link #id} заполнены.
     */
    public void sign(PrivateKey privateKey) {
        byte[] signatureBytes = Signer.sign(
                getDataToSign().getBytes(StandardCharsets.UTF_8), privateKey);
        this.signature = Base64.getEncoder().encodeToString(signatureBytes);
        this.id = calculateTxId();
    }

    /**
     * Проверяет подпись транзакции по публичному ключу из самой транзакции.
     * Возвращает {@code false} если подписи нет, ключ некорректен или подпись
     * не сходится.
     */
    public boolean verify() {
        if (signature == null || ownerPublicKey == null) return false;
        try {
            PublicKey publicKey = KeyManager.publicKeyFromBase64(ownerPublicKey);
            byte[] signatureBytes = Base64.getDecoder().decode(signature);
            return Signer.verify(
                    getDataToSign().getBytes(StandardCharsets.UTF_8),
                    signatureBytes, publicKey);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * "Предварительный" id транзакции — вычисляется без учёта {@link #replicas}.
     * <p>
     * Этот id стабилен между моментом, когда uploader сформировал транзакцию,
     * и моментом, когда он добавил в неё receipts от хранителей. Receipts
     * подписываются именно под этим id (хранители не знают финального id,
     * который зависит от их же receipts).
     * </p>
     */
    @JsonIgnore
    public String calculatePreliminaryId() {
        // Тот же набор полей что в getDataToSign, но без replicas.
        StringBuilder sb = new StringBuilder();
        sb.append(ownerPublicKey).append('|')
          .append(fileName).append('|')
          .append(fileSize).append('|')
          .append(merkleRoot).append('|')
          .append(timestamp);
        if (encryptedAesKey != null) {
            sb.append('|').append(encryptedAesKey);
        }
        if (shardHashes != null && !shardHashes.isEmpty()) {
            sb.append("|shards=");
            for (String h : shardHashes) sb.append(h).append(',');
        }
        return CryptoUtils.applySha256(sb.toString());
    }

    /**
     * Дополнительная валидация: проверяет, что все receipts в {@link #replicas}
     * имеют правильные подписи и относятся к этой же транзакции
     * (по preliminary id — см. {@link #calculatePreliminaryId()}).
     * Возвращает {@code true}, если replicas пустой или все receipts валидны.
     */
    public boolean verifyReplicas() {
        if (replicas == null || replicas.isEmpty()) return true;
        String preliminaryId = calculatePreliminaryId();
        for (StorageReceipt r : replicas) {
            if (!r.getTransactionId().equals(preliminaryId)) return false;
            if (!r.verify()) return false;
        }
        return true;
    }

    /**
     * Простой сеттер подписи без побочных эффектов — нужен Jackson для десериализации.
     * Если хочешь и подписать, и обновить id — используй {@link #sign(PrivateKey)}.
     */
    public void setSignature(String signature) {
        this.signature = signature;
    }

    public String calculateTxId() {
        String input = getDataToSign() + (signature == null ? "" : " " + signature);
        return CryptoUtils.applySha256(input);
    }

    // Геттеры
    public String getId() { return id; }
    public String getOwnerPublicKey() { return ownerPublicKey; }
    public String getFileName() { return fileName; }
    public long getFileSize() { return fileSize; }
    public String getMerkleRoot() { return merkleRoot; }
    public String getSignature() { return signature; }
    public long getTimestamp() { return timestamp; }
    public String getEncryptedAesKey() { return encryptedAesKey; }
    public List<String> getShardHashes() { return shardHashes; }
    public List<StorageReceipt> getReplicas() { return replicas; }

    public Kind getKind() { return kind; }
    public String getReferencedTxId() { return referencedTxId; }
    public String getRecipientPublicKey() { return recipientPublicKey; }
    public String getEncryptedAesKeyForRecipient() { return encryptedAesKeyForRecipient; }

    // Сеттеры — нужны только Jackson для десериализации из JSON.
    // В коде их не вызываем напрямую; для подписи используем sign(PrivateKey).
    public void setId(String id) { this.id = id; }
    public void setOwnerPublicKey(String ownerPublicKey) { this.ownerPublicKey = ownerPublicKey; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }
    public void setMerkleRoot(String merkleRoot) { this.merkleRoot = merkleRoot; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    public void setEncryptedAesKey(String encryptedAesKey) { this.encryptedAesKey = encryptedAesKey; }
    public void setShardHashes(List<String> shardHashes) {
        this.shardHashes = shardHashes == null ? Collections.emptyList() : new ArrayList<>(shardHashes);
    }
    public void setReplicas(List<StorageReceipt> replicas) {
        this.replicas = replicas == null ? Collections.emptyList() : new ArrayList<>(replicas);
    }

    public void setKind(Kind kind) {
        // Jackson при десериализации старых блоков (где этого поля нет) пришлёт
        // null — значит UPLOAD. Сами никогда не должны устанавливать null.
        this.kind = kind == null ? Kind.UPLOAD : kind;
    }
    public void setReferencedTxId(String referencedTxId) { this.referencedTxId = referencedTxId; }
    public void setRecipientPublicKey(String recipientPublicKey) { this.recipientPublicKey = recipientPublicKey; }
    public void setEncryptedAesKeyForRecipient(String encryptedAesKeyForRecipient) {
        this.encryptedAesKeyForRecipient = encryptedAesKeyForRecipient;
    }

    // ---------- Фабричные методы для ACL / DELETE (день 9) ----------

    /**
     * Создаёт ACL-транзакцию: владелец предоставляет доступ к файлу
     * {@code originalUploadTx} пользователю с публичным ключом {@code recipientPublicKey}.
     * <p>
     * Содержимое поля {@code encryptedAesKeyForRecipient} рассчитывается
     * вызывающим кодом (см. {@code FileUploader.shareFile}) — здесь оно
     * передаётся уже готовое, потому что шифрование AES-ключа RSA-OAEP'ом
     * требует доступа к приватному ключу владельца, который Transaction
     * не должен видеть.
     * <p>
     * Возвращённая транзакция ещё не подписана — вызывающий код должен
     * вызвать {@link #sign(PrivateKey)} с приватным ключом владельца.
     *
     * @param ownerPublicKeyB64           публичный ключ владельца файла (Base64)
     * @param originalTxId                id оригинальной UPLOAD-транзакции
     * @param recipientPublicKeyB64       публичный ключ recipient'а (Base64)
     * @param encryptedAesKeyForRecipient AES-ключ файла, зашифрованный
     *                                    под публичным ключом recipient'а (Base64)
     */
    public static Transaction newAcl(String ownerPublicKeyB64,
                                     String originalTxId,
                                     String recipientPublicKeyB64,
                                     String encryptedAesKeyForRecipient) {
        Transaction tx = new Transaction();
        tx.kind = Kind.ACL;
        tx.ownerPublicKey = ownerPublicKeyB64;
        tx.referencedTxId = originalTxId;
        tx.recipientPublicKey = recipientPublicKeyB64;
        tx.encryptedAesKeyForRecipient = encryptedAesKeyForRecipient;
        // Для удобства поиска и логов оставляем имя/размер как «n/a» —
        // ACL не сам по себе файл, а ссылка на UPLOAD.
        tx.fileName = "ACL:" + (originalTxId == null ? "?" : originalTxId.substring(0, Math.min(8, originalTxId.length())));
        tx.fileSize = 0L;
        tx.merkleRoot = "";
        tx.timestamp = System.currentTimeMillis();
        return tx;
    }

    /**
     * Создаёт DELETE-транзакцию: владелец помечает файл как удалённый.
     * <p>
     * Возвращённая транзакция ещё не подписана.
     *
     * @param ownerPublicKeyB64 публичный ключ владельца (Base64)
     * @param originalTxId      id оригинальной UPLOAD-транзакции
     */
    public static Transaction newDelete(String ownerPublicKeyB64, String originalTxId) {
        Transaction tx = new Transaction();
        tx.kind = Kind.DELETE;
        tx.ownerPublicKey = ownerPublicKeyB64;
        tx.referencedTxId = originalTxId;
        tx.fileName = "DELETE:" + (originalTxId == null ? "?" : originalTxId.substring(0, Math.min(8, originalTxId.length())));
        tx.fileSize = 0L;
        tx.merkleRoot = "";
        tx.timestamp = System.currentTimeMillis();
        return tx;
    }

    /**
     * Создаёт REPAIR-транзакцию: владелец фиксирует обновлённый список реплик
     * после авто-восстановления репликации (ТЗ п. 4.2.2).
     * <p>
     * Содержит:
     * <ul>
     *   <li>ссылку на оригинальный UPLOAD через {@link #referencedTxId};</li>
     *   <li>дублированные {@link #shardHashes} в правильном порядке —
     *       чтобы downloader мог восстановить файл по одной только этой TX;</li>
     *   <li>актуальный список {@link #replicas}: старые живые receipts +
     *       новые от свежевыбранных хранителей. Любой из них можно использовать
     *       для скачивания.</li>
     * </ul>
     * <p>
     * Возвращённая транзакция ещё не подписана.
     *
     * @param ownerPublicKeyB64 публичный ключ владельца (Base64)
     * @param originalTxId      id оригинальной UPLOAD-транзакции
     * @param shardHashes       полный упорядоченный список хешей шардов
     *                          (копируется из оригинального UPLOAD)
     */
    public static Transaction newRepair(String ownerPublicKeyB64,
                                        String originalTxId,
                                        List<String> shardHashes) {
        Transaction tx = new Transaction();
        tx.kind = Kind.REPAIR;
        tx.ownerPublicKey = ownerPublicKeyB64;
        tx.referencedTxId = originalTxId;
        tx.shardHashes = shardHashes == null
                ? Collections.emptyList() : new ArrayList<>(shardHashes);
        tx.fileName = "REPAIR:" + (originalTxId == null ? "?" : originalTxId.substring(0, Math.min(8, originalTxId.length())));
        tx.fileSize = 0L;
        tx.merkleRoot = "";
        tx.timestamp = System.currentTimeMillis();
        // replicas дополняются вызывающим кодом ПЕРЕД sign(): ему нужно сначала
        // собрать receipts от новых хранителей, потом вызвать setReplicas, потом
        // sign(). По той же схеме, что и UPLOAD в FileUploader.
        return tx;
    }
}
