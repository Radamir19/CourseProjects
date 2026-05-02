package ru.hse.jblockstorage.storage;

import ru.hse.jblockstorage.crypto.CryptoUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Дерево Меркла поверх списка шардов.
 * <p>
 * Согласно ТЗ (п. 4.1.1.2.4): для каждого блока вычисляется SHA-256-хеш,
 * хеши попарно конкатенируются и снова хешируются — пока не останется один
 * корневой хеш (Merkle Root). Корень — компактный «отпечаток» всего файла:
 * 32 байта, которые гарантируют неизменность сколь угодно большого набора
 * шардов и записываются в блокчейн.
 * </p>
 *
 * <h3>Обработка нечётного количества узлов</h3>
 * Если на каком-то уровне нечётное число узлов — последний узел дублируется
 * сам с собой (схема Bitcoin). Это позволяет всегда строить пары и упрощает
 * код проверки доказательств.
 */
public final class MerkleTree {

    /** Все уровни дерева снизу вверх: {@code levels.get(0)} — листья, последний — корень из 1 элемента. */
    private final List<List<byte[]>> levels;

    private MerkleTree(List<List<byte[]>> levels) {
        this.levels = levels;
    }

    /** Строит дерево из шардов. Шарды сортируются по индексу — порядок воспроизводим. */
    public static MerkleTree fromShards(List<Shard> shards) {
        Objects.requireNonNull(shards, "shards");
        if (shards.isEmpty()) {
            throw new IllegalArgumentException("Нельзя построить дерево из пустого списка шардов");
        }
        List<Shard> sorted = new ArrayList<>(shards);
        sorted.sort(Comparator.comparingInt(Shard::index));
        List<byte[]> leaves = new ArrayList<>(sorted.size());
        for (Shard s : sorted) leaves.add(s.hash());
        return fromHashes(leaves);
    }

    /** Строит дерево напрямую из списка SHA-256 хешей листьев. */
    public static MerkleTree fromHashes(List<byte[]> leafHashes) {
        Objects.requireNonNull(leafHashes, "leafHashes");
        if (leafHashes.isEmpty()) {
            throw new IllegalArgumentException("Список листовых хешей не может быть пустым");
        }
        for (byte[] h : leafHashes) {
            if (h == null || h.length != 32) {
                throw new IllegalArgumentException("Каждый лист должен быть SHA-256 хешем (32 байта)");
            }
        }

        List<List<byte[]>> levels = new ArrayList<>();
        levels.add(new ArrayList<>(leafHashes));

        while (levels.get(levels.size() - 1).size() > 1) {
            List<byte[]> current = levels.get(levels.size() - 1);
            List<byte[]> next = new ArrayList<>((current.size() + 1) / 2);
            for (int i = 0; i < current.size(); i += 2) {
                byte[] left = current.get(i);
                // Если нечётное число узлов — дублируем последний.
                byte[] right = (i + 1 < current.size()) ? current.get(i + 1) : left;
                next.add(CryptoUtils.applySha256(left, right));
            }
            levels.add(next);
        }
        return new MerkleTree(levels);
    }

    /** Корень дерева — 32 байта, тот самый «отпечаток» файла для блокчейна. */
    public byte[] root() {
        return levels.get(levels.size() - 1).get(0);
    }

    /** Hex-представление корня — 64 символа. */
    public String rootHex() {
        return CryptoUtils.toHex(root());
    }

    /** Количество исходных листьев (= количество шардов). */
    public int leafCount() {
        return levels.get(0).size();
    }

    /**
     * Доказательство принадлежности листа дереву (Merkle Proof).
     * <p>
     * Возвращает список «соседей» на пути от листа к корню. По этому списку
     * любой узел сети может за {@code O(log n)} проверить, что конкретный
     * шард действительно входит в файл с известным корнем — даже не имея
     * остальных шардов.
     * </p>
     */
    public List<ProofStep> proof(int leafIndex) {
        if (leafIndex < 0 || leafIndex >= leafCount()) {
            throw new IndexOutOfBoundsException(
                    "leafIndex=" + leafIndex + " вне диапазона [0, " + leafCount() + ")");
        }
        List<ProofStep> proof = new ArrayList<>();
        int idx = leafIndex;
        for (int level = 0; level < levels.size() - 1; level++) {
            List<byte[]> nodes = levels.get(level);
            int siblingIdx;
            boolean siblingOnRight;
            if (idx % 2 == 0) {
                // Сосед справа (или сам узел, если он последний и нечётный)
                siblingIdx = (idx + 1 < nodes.size()) ? idx + 1 : idx;
                siblingOnRight = true;
            } else {
                siblingIdx = idx - 1;
                siblingOnRight = false;
            }
            proof.add(new ProofStep(nodes.get(siblingIdx), siblingOnRight));
            idx /= 2;
        }
        return proof;
    }

    /**
     * Проверяет proof: восстанавливает корень из листового хеша,
     * последовательно применяя соседей. Возвращает {@code true},
     * если восстановленный корень совпадает с ожидаемым.
     */
    public static boolean verifyProof(byte[] leafHash, List<ProofStep> proof, byte[] expectedRoot) {
        Objects.requireNonNull(leafHash, "leafHash");
        Objects.requireNonNull(proof, "proof");
        Objects.requireNonNull(expectedRoot, "expectedRoot");

        byte[] current = leafHash;
        for (ProofStep step : proof) {
            if (step.siblingOnRight()) {
                current = CryptoUtils.applySha256(current, step.siblingHash());
            } else {
                current = CryptoUtils.applySha256(step.siblingHash(), current);
            }
        }
        return Arrays.equals(current, expectedRoot);
    }

    /** Один шаг доказательства: хеш-сосед и его положение (слева или справа от текущего узла). */
    public record ProofStep(byte[] siblingHash, boolean siblingOnRight) {
        public ProofStep {
            Objects.requireNonNull(siblingHash, "siblingHash");
            if (siblingHash.length != 32) {
                throw new IllegalArgumentException("siblingHash должен быть 32 байта");
            }
        }
    }
}