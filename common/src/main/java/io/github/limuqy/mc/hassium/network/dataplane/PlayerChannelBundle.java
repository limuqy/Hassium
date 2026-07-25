package io.github.limuqy.mc.hassium.network.dataplane;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * per-player Data 通道列表 + WRR 状态 + degraded 标志。
 * 线程安全：add/remove 在 server event loop 中调用，无需额外同步。
 */
public class PlayerChannelBundle {

    private final List<PlayerChannel> dataChannels = new ArrayList<>();
    public volatile int consecutiveDrops = 0;
    public volatile boolean degraded = false;

    /** 当前 WRR 累积权重（per-bundle 状态） */
    final java.util.concurrent.atomic.AtomicInteger wrrAccum = new java.util.concurrent.atomic.AtomicInteger(0);

    public void addChannel(PlayerChannel ch) { dataChannels.add(ch); }

    public void removeChannel(PlayerChannel ch) { dataChannels.remove(ch); }

    public List<PlayerChannel> getDataChannels() { return dataChannels; }

    public void resetDrops() { consecutiveDrops = 0; }

    /** 清理所有 Data 通道 */
    public void closeAll() {
        for (PlayerChannel ch : dataChannels) ch.close();
        dataChannels.clear();
    }
}
