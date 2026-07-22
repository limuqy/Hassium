package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.compat.PacketCodecCompat;
import io.github.limuqy.mc.hassium.compat.PacketPayloadCompat;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
#if MC_VER < MC_1_21_11
import net.minecraft.resources.ResourceLocation;
#else
import net.minecraft.resources.Identifier;
#endif

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Hassium 包聚合管理器
 * <p>
 * 参考 NEB 的 AggregationManager，在 Connection.send() 层面拦截包，
 * 将多个小包聚合为一个大包，内部使用 ZSTD 压缩。
 * <p>
 * 关键设计：
 * 1. 每连接独立的包缓冲区
 * 2. 定时刷新（20ms 周期）
 * 3. PENDING 状态下缓冲但不刷新
 * 4. ENABLED 状态下正常聚合
 */
public class HassiumAggregationManager {
    private static int minBatchPackets = 4;
    private static int maxWaitCycles = 2;
    private static final int FLUSH_PERIOD_MS = 20;
    private static int maxAggregationSize = 256 * 1024;

    private static final ConcurrentHashMap<Connection, List<AggregatedSubPacket>> PACKET_BUFFER = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Connection, Integer> FLUSH_WAIT = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Hassium-Flush-thread");
        t.setDaemon(true);
        return t;
    });
    private static volatile ScheduledFuture<?> flushTask = null;
    private static volatile boolean initialized = false;

    /**
     * 聚合包发送器接口
     */
    public interface AggregationSender {
        void send(Connection connection, FriendlyByteBuf buf);
    }

    private static AggregationSender sender;

    /**
     * 设置聚合包发送器
     */
    public static void setSender(AggregationSender sender) {
        HassiumAggregationManager.sender = sender;
    }

    /**
     * 初始化聚合管理器
     */
    public static synchronized void init() {
        if (initialized) {
            return;
        }
        PACKET_BUFFER.clear();

        // 从配置读取参数
        HassiumConfigService config = HassiumConfigService.getInstance();
        minBatchPackets = config.getAggregationMinBatchSize();
        maxWaitCycles = Math.max(1, (int) (config.getAggregationMaxWaitTimeMs() / FLUSH_PERIOD_MS));
        maxAggregationSize = config.getAggregationMaxSize();

        if (flushTask != null) {
            flushTask.cancel(false);
        }
        flushTask = TIMER.scheduleAtFixedRate(HassiumAggregationManager::flush, 0,
                FLUSH_PERIOD_MS, TimeUnit.MILLISECONDS);
        initialized = true;
        Constants.LOG.info("Hassium aggregation manager initialized (minBatch={}, maxWait={}ms, maxSize={}KB)",
                minBatchPackets, maxWaitCycles * FLUSH_PERIOD_MS, maxAggregationSize / 1024);
    }

    /**
     * 接管包，添加到聚合缓冲区
     *
     * @param packet     数据包
     * @param connection 连接
     */
    public static void takeOver(Packet<?> packet, Connection connection) {
#if MC_VER < MC_1_21_11
        ResourceLocation
#else
        Identifier
#endif
        type = PacketTypeHelper.getPacketType(packet);
        if (type == null) {
            Constants.LOG.warn("Unknown packet type, skipping aggregation: {}", packet.getClass().getSimpleName());
            return;
        }

        // 序列化包数据
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            byte[] data;
            if (PacketPayloadCompat.isCustomPayloadPacket(packet)) {
                // 自定义 Payload 包：只提取 payload 数据部分
                // packet.write() 会写入 [ResourceLocation identifier] + [payload]
                // 但 identifier 已通过 CompactHeader 单独存储，不能重复编码
                data = PacketPayloadCompat.extractPayloadData(packet);
                if (data == null) {
                    Constants.LOG.warn("Failed to extract payload data, skipping aggregation: {}", type);
                    return;
                }
                Constants.LOG.debug("Hassium: Extracted payload from CustomPayloadPacket: {} ({} bytes)",
                        type, data.length);
            } else {
                // 原版包：只写入包数据（不含包 ID）；1.20.5+ 走 StreamCodec
                data = PacketCodecCompat.serializePacketBody(
                        packet, PacketCodecCompat.resolveRegistryAccess(connection));
                if (data == null) {
                    Constants.LOG.warn("Failed to serialize vanilla packet, skipping aggregation: {}", type);
                    return;
                }
                Constants.LOG.debug("Hassium: Serialized vanilla packet: {} ({} bytes)",
                        type, data.length);
            }

            AggregatedSubPacket subPacket = new AggregatedSubPacket(type, data);
            List<AggregatedSubPacket> list = PACKET_BUFFER.computeIfAbsent(connection, k -> new ArrayList<>());
            synchronized (list) {
                list.add(subPacket);
                Constants.LOG.debug("Added packet to aggregation buffer: {} (total: {})", type, list.size());
            }
        } catch (Exception e) {
            Constants.LOG.error("Failed to serialize packet for aggregation: {}", type, e);
        } finally {
            buf.release();
        }
    }

    /**
     * 定时刷新所有连接
     */
    private static void flush() {
        PACKET_BUFFER.keySet().removeIf(c -> !c.isConnected());
        FLUSH_WAIT.keySet().removeIf(c -> !c.isConnected());

        for (var entry : PACKET_BUFFER.entrySet()) {
            Connection connection = entry.getKey();
            List<AggregatedSubPacket> packets = entry.getValue();

            if (packets == null) {
                continue;
            }

            synchronized (packets) {
                if (packets.isEmpty()) {
                    continue;
                }

                // PENDING 状态下不刷新
                if (HassiumConnectionRegistry.isPending(connection)) {
                    Constants.LOG.debug("Connection is PENDING, skipping flush");
                    continue;
                }

                // 检查是否达到最小批量
                if (packets.size() < minBatchPackets) {
                    int waited = FLUSH_WAIT.getOrDefault(connection, 0);
                    if (waited < maxWaitCycles) {
                        FLUSH_WAIT.put(connection, waited + 1);
                        Constants.LOG.debug("Waiting for more packets: {} (waited: {}/{})", packets.size(), waited, maxWaitCycles);
                        continue;
                    }
                }

                FLUSH_WAIT.remove(connection);
                Constants.LOG.debug("Flushing aggregation buffer: {} packets", packets.size());
                flushInternal(connection, packets);
            }
        }
    }

    /**
     * 刷新指定连接的缓冲区
     */
    public static void flushConnection(Connection connection) {
        TIMER.execute(() -> flushConnectionInternal(connection));
    }

    /**
     * 同步刷新指定连接
     */
    public static void flushConnectionSync(Connection connection) {
        flushConnectionInternal(connection);
    }

    /**
     * 丢弃连接的缓冲区
     */
    public static void discardConnection(Connection connection) {
        List<AggregatedSubPacket> packets = PACKET_BUFFER.remove(connection);
        if (packets != null) {
            synchronized (packets) {
                packets.clear();
            }
        }
        FLUSH_WAIT.remove(connection);
    }

    private static void flushConnectionInternal(Connection connection) {
        PACKET_BUFFER.keySet().removeIf(c -> !c.isConnected());
        FLUSH_WAIT.remove(connection);
        List<AggregatedSubPacket> packets = PACKET_BUFFER.get(connection);
        if (packets == null) return;
        synchronized (packets) {
            flushInternal(connection, packets);
        }
    }

    private static void flushInternal(Connection connection, List<AggregatedSubPacket> packets) {
        try {
            if (packets == null || packets.isEmpty()) {
                return;
            }
            if (!connection.isConnected()) {
                packets.clear();
                return;
            }

            // 复制并清空缓冲区
            List<AggregatedSubPacket> sendPackets = new ArrayList<>(packets);
            packets.clear();

            // 检查聚合大小限制，超过则分批发送
            int totalSize = 0;
            for (AggregatedSubPacket sp : sendPackets) {
                totalSize += sp.getData().length;
            }
            if (totalSize > maxAggregationSize) {
                Constants.LOG.warn("Aggregation buffer exceeds max size ({} > {} bytes), splitting",
                        totalSize, maxAggregationSize);
                List<AggregatedSubPacket> batch = new ArrayList<>();
                int batchSize = 0;
                for (AggregatedSubPacket sp : sendPackets) {
                    if (batchSize + sp.getData().length > maxAggregationSize && !batch.isEmpty()) {
                        flushBatch(connection, batch);
                        batch = new ArrayList<>();
                        batchSize = 0;
                    }
                    batch.add(sp);
                    batchSize += sp.getData().length;
                }
                if (!batch.isEmpty()) {
                    flushBatch(connection, batch);
                }
                return;
            }

            flushBatch(connection, sendPackets);
        } catch (Exception e) {
            Constants.LOG.error("Failed to flush aggregation buffer", e);
        }
    }

    private static void flushBatch(Connection connection, List<AggregatedSubPacket> batch) {
        try {
            IndexSyncManager indexSyncManager = IndexSyncManager.getInstance();
            NamespaceIndexManager indexManager = indexSyncManager.getServerIndexManager();

            HassiumAggregationPacket aggregationPacket = new HassiumAggregationPacket(batch, indexManager);
            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            aggregationPacket.encode(buf);

            if (sender != null) {
                sender.send(connection, buf);
            } else {
                Constants.LOG.error("AggregationSender not set, dropping {} packets", batch.size());
                buf.release();
            }

            Constants.LOG.debug("Flushed aggregation batch: {} packets for {}",
                    batch.size(), connection.getRemoteAddress());
        } catch (Exception e) {
            Constants.LOG.error("Failed to flush aggregation batch", e);
        }
    }
}
