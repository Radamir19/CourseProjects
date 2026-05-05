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

    /**
     * Добавляет уже сформированный блок в конец цепи. В отличие от
     * {@link #addBlock(List)}, который сам создаёт блок из транзакций,
     * этот метод нужен для приёма блоков от других узлов (broadcast/sync).
     * <p>
     * Проверяет:
     * <ul>
     *   <li>{@code block.index == latest.index + 1};</li>
     *   <li>{@code block.prevHash == latest.hash};</li>
     *   <li>{@code block.validate()} — внутренняя целостность (хеш + подписи).</li>
     * </ul>
     * Возвращает {@code true} если блок принят и добавлен.
     * <p>
     * Если приходит блок с {@code index <= height-1} (мы такого уже видели или
     * у нас более длинная цепь) — возвращаем {@code false} без побочных эффектов.
     * Если приходит блок с {@code index > height} (есть пропуск) — тоже
     * {@code false}; вышестоящий код должен инициировать sync через
     * {@code GetBlockMessage}.
     *
     * @param block блок для добавления
     * @return {@code true} если блок успешно добавлен
     */
    public synchronized boolean appendBlock(Block block) {
        Objects.requireNonNull(block, "block");
        Block latest = getLatestBlock();
        if (block.getIndex() != latest.getIndex() + 1) {
            return false;
        }
        if (!latest.getHash().equals(block.getPrevHash())) {
            return false;
        }
        if (!block.validate()) {
            return false;
        }
        chain.add(block);
        return true;
    }

    /**
     * Проверяет, есть ли в цепи блок с указанным индексом — без выбрасывания
     * исключения. Используется при синхронизации, чтобы не запрашивать
     * блоки, которые у нас уже есть.
     */
    public boolean hasBlock(int index) {
        return index >= 0 && index < chain.size();
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
     *
     * <h3>Семантика дня 9</h3>
     * Возвращает только {@link Transaction.Kind#UPLOAD} транзакции, у которых
     * нет соответствующей DELETE-транзакции от того же владельца. То есть
     * с точки зрения пользователя — только «живые» файлы. ACL-транзакции
     * этого пользователя сюда не входят (для них есть {@link #listByRecipient}).
     */
    public List<Transaction> listByOwner(String ownerPublicKeyBase64) {
        Objects.requireNonNull(ownerPublicKeyBase64, "ownerPublicKeyBase64");
        List<Transaction> result = new ArrayList<>();
        for (Block b : chain) {
            if (b.getTransactions() == null) continue;
            for (Transaction tx : b.getTransactions()) {
                if (tx.getKind() != Transaction.Kind.UPLOAD) continue;
                if (!ownerPublicKeyBase64.equals(tx.getOwnerPublicKey())) continue;
                if (isDeleted(tx.getId())) continue;
                result.add(tx);
            }
        }
        return result;
    }

    /**
     * Проверяет, помечен ли файл (UPLOAD-транзакция с id={@code uploadTxId})
     * как удалённый — то есть существует ли DELETE-транзакция от того же
     * владельца, ссылающаяся на этот id.
     * <p>
     * Возвращает {@code false}, если оригинальной UPLOAD-транзакции нет в
     * цепи (обычно это значит, что вызов сделан до её появления — лучше
     * считать «не удалена», чем кидать исключение).
     */
    public boolean isDeleted(String uploadTxId) {
        Objects.requireNonNull(uploadTxId, "uploadTxId");
        Optional<Transaction> uploadOpt = findByTxId(uploadTxId);
        if (uploadOpt.isEmpty() || uploadOpt.get().getKind() != Transaction.Kind.UPLOAD) {
            return false;
        }
        String owner = uploadOpt.get().getOwnerPublicKey();
        for (Block b : chain) {
            if (b.getTransactions() == null) continue;
            for (Transaction tx : b.getTransactions()) {
                if (tx.getKind() != Transaction.Kind.DELETE) continue;
                if (!uploadTxId.equals(tx.getReferencedTxId())) continue;
                // Только владелец имеет право удалить. Чужой DELETE
                // (даже валидно подписанный) игнорируется на уровне семантики.
                if (owner.equals(tx.getOwnerPublicKey())) return true;
            }
        }
        return false;
    }

    /**
     * Ищет последнюю ACL-транзакцию, выданную владельцем UPLOAD-транзакции
     * {@code uploadTxId} получателю {@code recipientPublicKeyBase64}.
     * <p>
     * «Последнюю» в смысле порядка в блокчейне — берётся самая поздняя
     * запись. Это позволит в будущем «отзывать» доступ через новую
     * ACL-транзакцию с пустым AES-ключом (на дне 9 не реализуем — пусть
     * будет точка расширения).
     *
     * @return найденная ACL-транзакция или {@link Optional#empty()}
     */
    public Optional<Transaction> findAclFor(String uploadTxId, String recipientPublicKeyBase64) {
        Objects.requireNonNull(uploadTxId, "uploadTxId");
        Objects.requireNonNull(recipientPublicKeyBase64, "recipientPublicKeyBase64");
        // Идём с конца цепи — первой нашей встретится самая свежая ACL.
        for (int i = chain.size() - 1; i >= 0; i--) {
            Block b = chain.get(i);
            if (b.getTransactions() == null) continue;
            // Внутри одного блока тоже идём с конца, чтобы при нескольких
            // ACL в одном блоке вернуть ту, что добавили позже.
            List<Transaction> txs = b.getTransactions();
            for (int j = txs.size() - 1; j >= 0; j--) {
                Transaction tx = txs.get(j);
                if (tx.getKind() != Transaction.Kind.ACL) continue;
                if (!uploadTxId.equals(tx.getReferencedTxId())) continue;
                if (!recipientPublicKeyBase64.equals(tx.getRecipientPublicKey())) continue;
                return Optional.of(tx);
            }
        }
        return Optional.empty();
    }

    /**
     * Все UPLOAD-транзакции, к которым у указанного recipient'а есть ACL.
     * <p>
     * Удалённые файлы не возвращаются. Используется в GUI «Расшаренное со
     * мной» и для CLI {@code list-shared}.
     */
    public List<Transaction> listByRecipient(String recipientPublicKeyBase64) {
        Objects.requireNonNull(recipientPublicKeyBase64, "recipientPublicKeyBase64");
        // Сначала собираем txId, к которым у меня выдан ACL.
        List<String> accessibleTxIds = new ArrayList<>();
        for (Block b : chain) {
            if (b.getTransactions() == null) continue;
            for (Transaction tx : b.getTransactions()) {
                if (tx.getKind() != Transaction.Kind.ACL) continue;
                if (!recipientPublicKeyBase64.equals(tx.getRecipientPublicKey())) continue;
                if (tx.getReferencedTxId() != null
                        && !accessibleTxIds.contains(tx.getReferencedTxId())) {
                    accessibleTxIds.add(tx.getReferencedTxId());
                }
            }
        }
        // Затем находим оригинальные UPLOAD'ы, отбрасывая удалённые.
        List<Transaction> result = new ArrayList<>();
        for (String uploadId : accessibleTxIds) {
            findByTxId(uploadId).ifPresent(uploadTx -> {
                if (uploadTx.getKind() == Transaction.Kind.UPLOAD && !isDeleted(uploadId)) {
                    result.add(uploadTx);
                }
            });
        }
        return result;
    }

    /**
     * Ищет последнюю REPAIR-транзакцию для оригинального UPLOAD'а
     * {@code uploadTxId}. «Последнюю» в смысле порядка в блокчейне —
     * берётся самая поздняя запись.
     * <p>
     * Используется в {@link ru.hse.jblockstorage.app.FileDownloader} —
     * downloader предпочитает самый свежий список реплик. Также в
     * {@code RepairService} — чтобы понять, какие реплики уже были
     * перерекомендованы при предыдущем repair.
     *
     * <p>Семантика: возвращается REPAIR от того же владельца, что и
     * оригинальный UPLOAD (чужой REPAIR на чужой файл — игнорируется).
     */
    public Optional<Transaction> findLatestRepairFor(String uploadTxId) {
        Objects.requireNonNull(uploadTxId, "uploadTxId");
        Optional<Transaction> uploadOpt = findByTxId(uploadTxId);
        if (uploadOpt.isEmpty()) return Optional.empty();
        String legitimateOwner = uploadOpt.get().getOwnerPublicKey();

        // Идём с конца цепи — первая встреченная REPAIR будет самой свежей.
        for (int i = chain.size() - 1; i >= 0; i--) {
            Block b = chain.get(i);
            if (b.getTransactions() == null) continue;
            List<Transaction> txs = b.getTransactions();
            for (int j = txs.size() - 1; j >= 0; j--) {
                Transaction tx = txs.get(j);
                if (tx.getKind() != Transaction.Kind.REPAIR) continue;
                if (!uploadTxId.equals(tx.getReferencedTxId())) continue;
                if (!legitimateOwner.equals(tx.getOwnerPublicKey())) continue;
                return Optional.of(tx);
            }
        }
        return Optional.empty();
    }
}
