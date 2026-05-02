package ru.hse.jblockstorage.network;

import ru.hse.jblockstorage.blockchain.Transaction;

/**
 * Рассылка новой транзакции по сети (gossip).
 * <p>
 * Когда пользователь загружает файл, его узел формирует транзакцию (см.
 * {@code Transaction.sign(...)}) и рассылает её соседям. Соседи проверяют
 * подпись через {@code Transaction.verify()} и, если всё корректно,
 * пересылают дальше — пока транзакция не попадёт в новый блок.
 * </p>
 */
public class BroadcastTxMessage extends Message {

    private Transaction transaction;

    public BroadcastTxMessage() {}

    public BroadcastTxMessage(Transaction transaction) {
        this.transaction = transaction;
    }

    public Transaction getTransaction() { return transaction; }
    public void setTransaction(Transaction transaction) { this.transaction = transaction; }

    @Override
    public String toString() {
        return "BroadcastTxMessage{tx="
                + (transaction == null ? "null" : transaction.getId()) + "}";
    }
}
