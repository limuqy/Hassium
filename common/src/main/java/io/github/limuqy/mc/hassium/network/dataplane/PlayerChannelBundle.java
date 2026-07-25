package io.github.limuqy.mc.hassium.network.dataplane;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * per-player Data 通道列表 + WRR 状态 + degraded 标志。
 * <p>
 * 线程模型：add/remove 在各自 data channel 的 event loop 中调用，
 * read/遍历在 {@code Hassium-ChunkPush} 推送线程中发生 —— 三线程并发，
 * 故 {@link #dataChannels} 用 {@link CopyOnWriteArrayList}（写少读多，迭代快照稳定），
 * 避免并发写导致 ArrayList 内部数组损坏（曾出现 null 元素致 BulkRouter NPE）。
 */
public class PlayerChannelBundle {

    private final List<PlayerChannel> dataChannels = new CopyOnWriteArrayList<>();
    public volatile int consecutiveDrops = 0;
    public volatile boolean degraded = false;

    /** 当前 WRR 累积权重（per-bundle 状态） */
    final AtomicInteger wrrAccum = new AtomicInteger(0);

    public void addChannel(PlayerChannel ch) { dataChannels.add(ch); }

    public void removeChannel(PlayerChannel ch) { dataChannels.remove(ch); }

    public List<PlayerChannel> getDataChannels() { return dataChannels; }

    public void resetDrops() { consecutiveDrops = 0; }

    /** 清理所有 Data 通道 */
    public void closeAll() {
        for (PlayerChannel ch : dataChannels) {
            if (ch != null) ch.close();
        }
        dataChannels.clear();
    }
}
