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
}
