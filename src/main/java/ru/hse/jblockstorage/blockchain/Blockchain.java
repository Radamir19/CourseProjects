package ru.hse.jblockstorage.blockchain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Цепочка блоков.
 * <p>
 * Согласно ТЗ (п. 4.1.1.3) реализует:
 * <ul>
 *   <li>добавление новых блоков с автоматической ссылкой на предыдущий;</li>
 *   <li>валидацию структуры (каждый блок ссылается на хеш предыдущего, все хеши и подписи корректны);</li>
 *   <li>правило самой длинной цепи (Longest Chain Rule) для разрешения конфликтов.</li>
 * </ul>
 * </p>
 *
 * <h3>Поиск транзакций (день 7)</h3>
 * Для CLI ({@code list}, {@code download}) нужно уметь находить транзакции
 * по идентификатору и фильтровать по владельцу. Соответствующие методы —
 * {@link #findByTxId(String)} и {@link #listByOwner(String)} — линейные
 * по числу блоков и не используют отдельного индекса. Для учебной системы
 * этого достаточно; в production имеет смысл вынести индекс в отдельную
 * таблицу RocksDB.
 */
public class Blockchain {

    private final List<Block> chain;

    /** Создаёт цепочку с одним genesis-блоком. */
    public Blockchain() {
        this.chain = new ArrayList<>();
        this.chain.add(Block.genesis());
    }

    /** Восстанавливает цепочку из заранее известного списка (например, после загрузки с диска). */
    public Blockchain(List<Block> blocks) {
        Objects.requireNonNull(blocks, "blocks");
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("Цепочка не может быть пустой — должен быть хотя бы genesis");
        }
        this.chain = new ArrayList<>(blocks);
        if (!validate()) {
            throw new IllegalStateException("Загруженная цепочка не прошла валидацию");
        }
    }

    /**
     * Создаёт новый блок с указанными транзакциями и добавляет его в конец цепи.
     * Все транзакции должны быть подписаны — иначе кидается {@link IllegalArgumentException}.
     */
    public Block addBlock(List<Transaction> transactions) {
        Objects.requireNonNull(transactions, "transactions");
        for (Transaction tx : transactions) {
            if (!tx.verify()) {
                throw new IllegalArgumentException(
                        "Транзакция с id=" + tx.getId() + " имеет некорректную подпись");
            }
        }
        Block prev = getLatestBlock();
        Block next = Block.create(prev.getIndex() + 1, prev.getHash(), transactions);
        chain.add(next);
        return next;
    }

    public Block getLatestBlock() {
        return chain.get(chain.size() - 1);
    }

    public Block getBlock(int index) {
        if (index < 0 || index >= chain.size()) {
            throw new IndexOutOfBoundsException("index=" + index + " вне [0, " + chain.size() + ")");
        }
        return chain.get(index);
    }

    public int height() {
        return chain.size();
    }

    public List<Block> getBlocks() {
        return Collections.unmodifiableList(chain);
    }

    /**
     * Проверяет всю цепь:
     * <ul>
     *   <li>genesis-блок имеет правильную структуру;</li>
     *   <li>каждый блок ссылается на хеш предыдущего;</li>
     *   <li>каждый блок проходит {@link Block#validate()}.</li>
     * </ul>
     */
    public boolean validate() {
        if (chain.isEmpty()) return false;

        Block genesis = chain.get(0);
        if (genesis.getIndex() != 0) return false;
        if (!Block.GENESIS_PREV_HASH.equals(genesis.getPrevHash())) return false;
        if (!genesis.validateHash()) return false;

        for (int i = 1; i < chain.size(); i++) {
            Block prev = chain.get(i - 1);
            Block curr = chain.get(i);
            if (curr.getIndex() != prev.getIndex() + 1) return false;
            if (!curr.getPrevHash().equals(prev.getHash())) return false;
            if (!curr.validate()) return false;
        }
        return true;
    }

    /**
     * Правило самой длинной цепи (п. 4.1.1.3.4): если приходящая цепь длиннее
     * нашей и валидна — заменяем свою на неё. Возвращает {@code true},
     * если замена произошла.
     */
    public boolean replaceIfLonger(List<Block> incoming) {
        Objects.requireNonNull(incoming, "incoming");
        if (incoming.size() <= chain.size()) return false;

        // Проверяем, что incoming сама по себе — валидная цепь.
        try {
            new Blockchain(incoming);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return false;
        }

        chain.clear();
        chain.addAll(incoming);
        return true;
    }

    // ---------- Поиск транзакций (день 7, для CLI) ----------

    /**
     * Все транзакции из всех блоков (genesis при этом обычно пустой,
     * но мы его всё равно проитерируем — это безопасно).
     * <p>
     * Возвращает копию списка, чтобы внешний код не мог мутировать цепь.
     * </p>
     */
    public List<Transaction> allTransactions() {
        List<Transaction> result = new ArrayList<>();
        for (Block b : chain) {
            if (b.getTransactions() != null) {
                result.addAll(b.getTransactions());
            }
        }
        return result;
    }

    /**
     * Ищет транзакцию по её id (хеш транзакции после подписания).
     * Возвращает {@link Optional#empty()}, если такой нет в цепи.
     */
    public Optional<Transaction> findByTxId(String txId) {
        Objects.requireNonNull(txId, "txId");
        for (Block b : chain) {
            if (b.getTransactions() == null) continue;
            for (Transaction tx : b.getTransactions()) {
                if (txId.equals(tx.getId())) {
                    return Optional.of(tx);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Все транзакции, владельцем которых является указанный публичный ключ
     * (Base64 X.509). Используется CLI {@code list} для показа файлов
     * текущего пользователя.
     */
    public List<Transaction> listByOwner(String ownerPublicKeyBase64) {
        Objects.requireNonNull(ownerPublicKeyBase64, "ownerPublicKeyBase64");
        List<Transaction> result = new ArrayList<>();
        for (Block b : chain) {
            if (b.getTransactions() == null) continue;
            for (Transaction tx : b.getTransactions()) {
                if (ownerPublicKeyBase64.equals(tx.getOwnerPublicKey())) {
                    result.add(tx);
                }
            }
        }
        return result;
    }
}
