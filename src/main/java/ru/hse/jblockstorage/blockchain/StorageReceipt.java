package ru.hse.jblockstorage.blockchain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import ru.hse.jblockstorage.crypto.KeyManager;
import ru.hse.jblockstorage.crypto.Signer;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Objects;

/**
 * Квитанция о размещении шарда — подписанное хранителем подтверждение,
 * что узел согласился хранить указанный шард для указанной транзакции.
 * <p>
 * Используется в схеме <b>Proof of Placement</b>: когда uploader раздаёт
 * шарды (см. PutShardMessage), каждый принимающий узел подписывает
 * приёмку своим приватным ключом и возвращает receipt. Uploader включает
 * все receipts в транзакцию, после чего вся история размещения зафиксирована
 * в блокчейне криптографически.
 * </p>
 *
 * <h3>Что не доказывает receipt</h3>
 * Это не proof-of-storage — узел может подписать приёмку и потом удалить
 * данные. Для защиты от этого нужен периодический challenge-response
 * (узел доказывает, что у него до сих пор есть шард, отвечая на запрос
 * случайного байта). В нашей системе challenge-response — задача доработок,
 * см. раздел «известные ограничения» пояснительной записки.
 */
public final class StorageReceipt implements Serializable {

    /** ID транзакции, к которой относится receipt. */
    private final String transactionId;

    /** Публичный ключ хранителя (Base64 X.509). */
    private final String storerPublicKey;

    /** SHA-256 хеш шарда в hex (64 символа). */
    private final String shardHashHex;

    /** Подпись хранителя над {@code transactionId + storerPublicKey + shardHashHex} (Base64). */
    private final String signature;

    @JsonCreator
    public StorageReceipt(
            @JsonProperty("transactionId") String transactionId,
            @JsonProperty("storerPublicKey") String storerPublicKey,
            @JsonProperty("shardHashHex") String shardHashHex,
            @JsonProperty("signature") String signature) {
        this.transactionId = Objects.requireNonNull(transactionId, "transactionId");
        this.storerPublicKey = Objects.requireNonNull(storerPublicKey, "storerPublicKey");
        this.shardHashHex = Objects.requireNonNull(shardHashHex, "shardHashHex");
        this.signature = Objects.requireNonNull(signature, "signature");
    }

    public String getTransactionId() { return transactionId; }
    public String getStorerPublicKey() { return storerPublicKey; }
    public String getShardHashHex() { return shardHashHex; }
    public String getSignature() { return signature; }

    /** Канонические данные для подписи — то же самое и при создании, и при проверке. */
    @JsonIgnore
    public String getDataToSign() {
        return canonicalDataToSign(transactionId, storerPublicKey, shardHashHex);
    }

    /** Формирование канонической строки для подписи (статически — чтоб не плодить экземпляры). */
    public static String canonicalDataToSign(String txId, String storerPubKey, String shardHash) {
        return txId + "|" + storerPubKey + "|" + shardHash;
    }

    /**
     * Создаёт receipt: считает подпись приватным ключом хранителя.
     * Публичный ключ хранителя должен соответствовать переданному приватному.
     */
    public static StorageReceipt create(String transactionId, String storerPublicKey,
                                         String shardHashHex, PrivateKey storerPrivateKey) {
        String dataToSign = canonicalDataToSign(transactionId, storerPublicKey, shardHashHex);
        byte[] sigBytes = Signer.sign(dataToSign.getBytes(StandardCharsets.UTF_8), storerPrivateKey);
        String signature = Base64.getEncoder().encodeToString(sigBytes);
        return new StorageReceipt(transactionId, storerPublicKey, shardHashHex, signature);
    }

    /**
     * Проверяет подпись receipt'а. Возвращает {@code true} только если:
     * <ul>
     *   <li>{@code storerPublicKey} парсится как валидный X.509 ключ;</li>
     *   <li>подпись над каноническими данными корректна.</li>
     * </ul>
     */
    public boolean verify() {
        try {
            PublicKey publicKey = KeyManager.publicKeyFromBase64(storerPublicKey);
            byte[] sigBytes = Base64.getDecoder().decode(signature);
            return Signer.verify(
                    getDataToSign().getBytes(StandardCharsets.UTF_8),
                    sigBytes, publicKey);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public String toString() {
        String shortKey = storerPublicKey.length() > 12
                ? storerPublicKey.substring(0, 12) + "…"
                : storerPublicKey;
        String shortHash = shardHashHex.length() > 8
                ? shardHashHex.substring(0, 8) + "…"
                : shardHashHex;
        return "StorageReceipt{tx=" + transactionId.substring(0, Math.min(8, transactionId.length()))
                + "…, storer=" + shortKey + ", shard=" + shortHash + "}";
    }
}
