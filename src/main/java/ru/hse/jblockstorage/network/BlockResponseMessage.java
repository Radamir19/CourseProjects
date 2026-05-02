package ru.hse.jblockstorage.network;

import ru.hse.jblockstorage.blockchain.Block;

/**
 * Ответ на {@link GetBlockMessage}.
 * <p>
 * Если блок с запрошенным индексом найден — поле {@link #block} содержит его,
 * иначе {@code null}. Принимающая сторона должна обработать оба случая.
 * </p>
 */
public class BlockResponseMessage extends Message {

    /** Индекс, который был запрошен (зеркалится для удобной маршрутизации). */
    private int requestedIndex;

    /** Сам блок или {@code null}, если блок не найден. */
    private Block block;

    public BlockResponseMessage() {}

    public BlockResponseMessage(int requestedIndex, Block block) {
        this.requestedIndex = requestedIndex;
        this.block = block;
    }

    public int getRequestedIndex() { return requestedIndex; }
    public Block getBlock() { return block; }

    public void setRequestedIndex(int requestedIndex) { this.requestedIndex = requestedIndex; }
    public void setBlock(Block block) { this.block = block; }

    @Override
    public String toString() {
        return "BlockResponseMessage{index=" + requestedIndex
                + ", found=" + (block != null) + "}";
    }
}
