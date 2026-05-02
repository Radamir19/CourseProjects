package ru.hse.jblockstorage.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Gossip-сообщение: узел делится своей таблицей известных пиров.
 * <p>
 * Согласно ТЗ (п. 4.1.1.1.2) узел должен автоматически обнаруживать активных
 * пиров. Самая простая рабочая схема: каждые N секунд узел шлёт случайному
 * соседу список своих известных пиров. Сосед добавляет тех, о ком ещё не знал,
 * и в свою очередь рассылает уже расширенную таблицу. Через несколько раундов
 * gossip все участники сети узнают друг о друге.
 * </p>
 *
 * <h3>Размер</h3>
 * Список ограничен сверху значением {@code maxPeers} из {@code NodeConfig}
 * (по умолчанию 16) — это предохраняет от заваливания трафика и памяти,
 * если кто-то начнёт флудить gossip-сообщениями с тысячами фиктивных пиров.
 */
public class PeerListMessage extends Message {

    private List<PeerInfo> peers;

    public PeerListMessage() {
        this.peers = Collections.emptyList();
    }

    public PeerListMessage(List<PeerInfo> peers) {
        this.peers = peers == null ? Collections.emptyList() : new ArrayList<>(peers);
    }

    public List<PeerInfo> getPeers() { return peers; }
    public void setPeers(List<PeerInfo> peers) {
        this.peers = peers == null ? Collections.emptyList() : peers;
    }

    @Override
    public String toString() {
        return "PeerListMessage{count=" + peers.size() + "}";
    }
}
