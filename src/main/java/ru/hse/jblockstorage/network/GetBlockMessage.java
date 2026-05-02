package ru.hse.jblockstorage.network;

/**
 * Запрос блока блокчейна по индексу.
 * <p>
 * Используется при синхронизации цепи: после handshake узел видит, что у соседа
 * больше блоков, и запрашивает недостающие. Ответ — {@link BlockResponseMessage}.
 * </p>
 */
public class GetBlockMessage extends Message {

    /** Индекс запрашиваемого блока (0 = genesis). */
    private int index;

    public GetBlockMessage() {}

    public GetBlockMessage(int index) {
        this.index = index;
    }

    public int getIndex() { return index; }
    public void setIndex(int index) { this.index = index; }

    @Override
    public String toString() {
        return "GetBlockMessage{index=" + index + "}";
    }
}
