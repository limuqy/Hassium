package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.cache.client.ChunkBloomFilter;
import io.github.limuqy.mc.hassium.cache.client.ClientHassiumStorage;
import io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher;
import io.github.limuqy.mc.hassium.platform.Services;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.utils.DebugLogger.LogType;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 客户端缓存 Bloom 位图同步器
 * <p>
 * 把客户端磁盘缓存的存在性同步给服务端（per-player Bloom 层）：
 * <ul>
 *   <li>storage 就绪后发送一次全量位图（{@code full=true}），服务端覆盖旧层；</li>
 *   <li>新缓存落盘后攒增量批次，≥ {@link #INCREMENT_THRESHOLD} 且距上次发送 ≥
 *       {@link #SEND_COOLDOWN_MS} 时按批构建独立位图（{@code full=false}）追加一层。</li>
 * </ul>
 * 位图只增不减（服务端不关心客户端淘汰/过期——假 hit 由客户端 hash 对比 MISS/MISMATCH
 * 兜底走增量或全量）；增量丢失无害（服务端 miss → 直推）。
 */
public final class ClientBloomSyncTracker {

    /** 增量批次阈值：攒够这么多新缓存块才值得发一批 */
    private static final int INCREMENT_THRESHOLD = 64;
    /** 增量发送冷却：进服风暴期合并为少数批次，避免每 64 块全量轰炸 */
    private static final long SEND_COOLDOWN_MS = 5000L;

    /** 待同步增量条目（含维度：位图 hash 需要 dimension 混淆） */
    private record IncrementKey(int x, int z, String dimension) {}

    private static final ConcurrentLinkedQueue<IncrementKey> pendingIncrement = new ConcurrentLinkedQueue<>();

    private static volatile boolean storageReady = false;
    private static volatile boolean fullSent = false;
    private static volatile long lastSendMs = 0L;

    private ClientBloomSyncTracker() {}

    /**
     * 缓存落盘成功后调用（CacheSaveQueue 后台线程）：收集增量坐标。
     * 由 {@link ClientHassiumStorage#persist} 触发。
     */
    public static void onChunkCached(int chunkX, int chunkZ, String dimension) {
        pendingIncrement.offer(new IncrementKey(chunkX, chunkZ, dimension));
    }

    /**
     * 客户端缓存 storage 就绪后调用（后台线程）：置位，下一帧发送全量位图。
     */
    public static void onStorageReady() {
        storageReady = true;
    }

    /**
     * 断连清理：重置同步状态，重连后（storage 重新就绪时）重发全量位图。
     */
    public static void onDisconnect() {
        storageReady = false;
        fullSent = false;
        lastSendMs = 0L;
        pendingIncrement.clear();
    }

    /**
     * 每帧调用（挂 {@link ClientMetadataHandler#tickPendingHashGate}）：
     * 发送全量（一次）+ 按阈值/冷却发送增量批次。
     */
    public static void tick() {
        if (!storageReady || fullSent) {
            if (storageReady && fullSent) {
                sendIncrementIfDue();
            }
            return;
        }
        fullSent = true;
        sendFull();
        lastSendMs = System.currentTimeMillis();
        sendIncrementIfDue();
    }

    private static void sendIncrementIfDue() {
        int pending = pendingIncrement.size();
        if (pending < INCREMENT_THRESHOLD) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSendMs < SEND_COOLDOWN_MS) {
            return;
        }
        sendIncrement();
        lastSendMs = now;
    }

    /**
     * 发送全量位图：当前 dimension 存储 Bloom 快照。空缓存也发（全零位图告知服务端
     * 「无缓存」→ 全部直推，同时让服务端 resync 无需等待 5s 超时即可启动）。
     */
    private static void sendFull() {
        ClientHassiumStorage storage = ClientChunkHandler.getClientStorage();
        if (storage == null) {
            DebugLogger.warn(LogType.NETWORK, "[BLOOM_SYNC] Storage null, skip full bloom sync");
            return;
        }
        byte[] bytes = storage.serializeBloomFilter();
        if (bytes == null) {
            return;
        }
        sendPacket(new ClientBloomSyncPacket(true, bytes), "full", bytes.length);
    }

    /**
     * 构建增量批次位图（按本批实际块数精确容量，零浪费）并发送。
     */
    private static void sendIncrement() {
        List<IncrementKey> batch = new ArrayList<>();
        IncrementKey key;
        while ((key = pendingIncrement.poll()) != null) {
            batch.add(key);
        }
        if (batch.isEmpty()) {
            return;
        }
        try {
            ChunkBloomFilter filter = new ChunkBloomFilter(batch.size(), 0.01);
            for (IncrementKey ik : batch) {
                filter.put(ik.x(), ik.z(), ik.dimension());
            }
            byte[] bytes = filter.toByteArray();
            sendPacket(new ClientBloomSyncPacket(false, bytes), "increment", bytes.length);
            DebugLogger.debug(LogType.NETWORK,
                    "[BLOOM_SYNC] Queued incremental bloom: {} chunks -> {} bytes", batch.size(), bytes.length);
        } catch (Exception e) {
            DebugLogger.error("[BLOOM_SYNC] Failed to build incremental bloom", e);
        }
    }

    private static void sendPacket(ClientBloomSyncPacket packet, String kind, int bytes) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) {
            return;
        }
        // 构造在主线程之外（后台线程/帧循环回调），发送切主线程（各平台 send 要求主线程语义一致）
        MainThreadDispatcher.execute(() -> {
            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            boolean sent = false;
            try {
                packet.encode(buf);
                Services.NETWORK_MANAGER.sendClientBloomSync(buf);
                sent = true;
                DebugLogger.info(LogType.NETWORK,
                        "[BLOOM_SYNC] Sent {} bloom sync ({} bytes, pending={})",
                        kind, bytes, pendingIncrement.size());
            } catch (Exception e) {
                DebugLogger.error("[BLOOM_SYNC] Failed to send {} bloom sync", e);
            } finally {
                if (!sent && buf != null) {
                    buf.release();
                }
            }
        });
    }
}
