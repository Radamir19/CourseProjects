package ru.hse.jblockstorage.network;

import ru.hse.jblockstorage.blockchain.Block;

/**
 * Рассылка нового блока по сети.
 * <p>
 * Узел, который собрал блок из накопленных транзакций, рассылает его
 * соседям. Соседи валидируют блок (см. {@code Block.validate()}) и
 * добавляют в свою цепь. Если у них уже есть более длинная цепь,
 * срабатывает Longest Chain Rule (ТЗ п. 4.1.1.3.4).
 * </p>
 */
public class BroadcastBlockMessage extends Message {

    private Block block;

    public BroadcastBlockMessage() {}

    public BroadcastBlockMessage(Block block) {
        this.block = block;
    }

    public Block getBlock() { return block; }
    public void setBlock(Block block) { this.block = block; }

    @Override
    public String toString() {
        return "BroadcastBlockMessage{block="
                + (block == null ? "null" : block.toString()) + "}";
    }
}
