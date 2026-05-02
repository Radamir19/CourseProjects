package ru.hse.jblockstorage.storage;

import org.junit.jupiter.api.Test;
import ru.hse.jblockstorage.crypto.CryptoUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MerkleTreeTest {

    @Test
    void singleLeafTreeRootEqualsLeafHash() {
        byte[] leaf = CryptoUtils.applySha256("a".getBytes());
        MerkleTree tree = MerkleTree.fromHashes(List.of(leaf));
        assertArrayEquals(leaf, tree.root());
    }

    @Test
    void twoLeavesRootEqualsHashOfConcatenation() {
        byte[] a = CryptoUtils.applySha256("a".getBytes());
        byte[] b = CryptoUtils.applySha256("b".getBytes());
        byte[] expected = CryptoUtils.applySha256(a, b);

        assertArrayEquals(expected, MerkleTree.fromHashes(List.of(a, b)).root());
    }

    @Test
    void oddLeafCountDuplicatesLastLeaf() {
        byte[] a = CryptoUtils.applySha256("a".getBytes());
        byte[] b = CryptoUtils.applySha256("b".getBytes());
        byte[] c = CryptoUtils.applySha256("c".getBytes());

        // Уровень 1: SHA(a||b), SHA(c||c)
        byte[] left = CryptoUtils.applySha256(a, b);
        byte[] right = CryptoUtils.applySha256(c, c);
        byte[] expected = CryptoUtils.applySha256(left, right);

        assertArrayEquals(expected, MerkleTree.fromHashes(List.of(a, b, c)).root());
    }

    @Test
    void emptyInputRejected() {
        assertThrows(IllegalArgumentException.class, () -> MerkleTree.fromHashes(List.of()));
    }

    @Test
    void wrongHashLengthRejected() {
        byte[] tooShort = new byte[16];
        assertThrows(IllegalArgumentException.class, () -> MerkleTree.fromHashes(List.of(tooShort)));
    }

    @Test
    void proofVerifiesForEveryLeafInPowerOfTwoTree() {
        // 8 листьев — идеальное дерево, проверяем все
        List<byte[]> leaves = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            leaves.add(CryptoUtils.applySha256(("data-" + i).getBytes()));
        }
        MerkleTree tree = MerkleTree.fromHashes(leaves);
        for (int i = 0; i < leaves.size(); i++) {
            List<MerkleTree.ProofStep> proof = tree.proof(i);
            assertTrue(MerkleTree.verifyProof(leaves.get(i), proof, tree.root()),
                    "proof для листа " + i + " должен проходить проверку");
        }
    }

    @Test
    void proofVerifiesForEveryLeafInOddTree() {
        // 7 листьев — на каждом уровне будет нечётный остаток
        List<byte[]> leaves = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            leaves.add(CryptoUtils.applySha256(("data-" + i).getBytes()));
        }
        MerkleTree tree = MerkleTree.fromHashes(leaves);
        for (int i = 0; i < leaves.size(); i++) {
            List<MerkleTree.ProofStep> proof = tree.proof(i);
            assertTrue(MerkleTree.verifyProof(leaves.get(i), proof, tree.root()),
                    "proof для листа " + i + " (нечётное дерево) должен проходить проверку");
        }
    }

    @Test
    void proofFailsForTamperedLeafHash() {
        List<byte[]> leaves = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            leaves.add(CryptoUtils.applySha256(("data-" + i).getBytes()));
        }
        MerkleTree tree = MerkleTree.fromHashes(leaves);
        List<MerkleTree.ProofStep> proof = tree.proof(2);

        byte[] tampered = leaves.get(2).clone();
        tampered[0] ^= 1;

        assertFalse(MerkleTree.verifyProof(tampered, proof, tree.root()));
    }

    @Test
    void proofFailsAgainstWrongRoot() {
        List<byte[]> leaves = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            leaves.add(CryptoUtils.applySha256(("x-" + i).getBytes()));
        }
        MerkleTree tree = MerkleTree.fromHashes(leaves);
        List<MerkleTree.ProofStep> proof = tree.proof(0);

        byte[] wrongRoot = tree.root().clone();
        wrongRoot[0] ^= 1;

        assertFalse(MerkleTree.verifyProof(leaves.get(0), proof, wrongRoot));
    }

    @Test
    void proofIndexOutOfRangeRejected() {
        MerkleTree tree = MerkleTree.fromHashes(
                List.of(CryptoUtils.applySha256("only".getBytes())));
        assertThrows(IndexOutOfBoundsException.class, () -> tree.proof(1));
        assertThrows(IndexOutOfBoundsException.class, () -> tree.proof(-1));
    }

    @Test
    void differentFilesProduceDifferentRoots() {
        List<Shard> a = FileChunker.chunk("hello world".getBytes(), 4);
        List<Shard> b = FileChunker.chunk("hello earth".getBytes(), 4);
        assertFalse(Arrays.equals(
                MerkleTree.fromShards(a).root(),
                MerkleTree.fromShards(b).root()));
    }

    @Test
    void identicalDataProducesIdenticalRoot() {
        byte[] data = "identical content for both trees".getBytes();
        byte[] rootA = MerkleTree.fromShards(FileChunker.chunk(data, 7)).root();
        byte[] rootB = MerkleTree.fromShards(FileChunker.chunk(data, 7)).root();
        assertArrayEquals(rootA, rootB);
    }

    @Test
    void rootHexIs64Characters() {
        byte[] leaf = CryptoUtils.applySha256("test".getBytes());
        assertEquals(64, MerkleTree.fromHashes(List.of(leaf)).rootHex().length());
    }

    @Test
    void rootIsIndependentOfShardOrderBecauseSortedByIndex() {
        // Корень дерева должен зависеть только от содержимого, а не от того,
        // в каком порядке шарды лежали в списке (например, после загрузки из сети)
        byte[] data = new byte[200];
        for (int i = 0; i < data.length; i++) data[i] = (byte) i;

        List<Shard> ordered = FileChunker.chunk(data, 50);
        List<Shard> shuffled = new ArrayList<>(ordered);
        Collections.shuffle(shuffled);

        assertArrayEquals(
                MerkleTree.fromShards(ordered).root(),
                MerkleTree.fromShards(shuffled).root());
    }

    @Test
    void leafCountReportsCorrectValue() {
        List<byte[]> leaves = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            leaves.add(CryptoUtils.applySha256(("l" + i).getBytes()));
        }
        assertEquals(5, MerkleTree.fromHashes(leaves).leafCount());
    }
}