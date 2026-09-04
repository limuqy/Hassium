package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.compat.PacketCodecCompat;
import io.github.limuqy.mc.hassium.compat.PacketId;
import io.github.limuqy.mc.hassium.compat.PacketPayloadCompat;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;

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
    /** 定时器粒度；maxWaitCycles = aggregationMaxWaitTimeMs / 本值（默认 50ms → 5 周期）。 */
    private static final int FLUSH_PERIOD_MS = 10;
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
        PacketId type = PacketTypeHelper.getPacketType(packet);
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

            // review-fix: T2-76: sender 未就绪（初始化顺序异常窗口）时保留缓冲，下轮重试不丢数据
            if (sender == null) {
                Constants.LOG.warn("AggregationSender not set, deferring flush of {} packets", packets.size());
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
                sendAggregateBypassingVanillaCompression(connection, buf);
            } else {
                // review-fix: T2-76: sender 缺失（初始化顺序异常窗口）时回队兜底，不丢数据
                Constants.LOG.warn("AggregationSender not set, re-queueing {} packets", batch.size());
                buf.release();
                List<AggregatedSubPacket> buffer = PACKET_BUFFER.computeIfAbsent(connection, k -> new ArrayList<>());
                synchronized (buffer) {
                    buffer.addAll(batch);
                }
            }

            Constants.LOG.debug("Flushed aggregation batch: {} packets for {}",
                    batch.size(), sanitizeLog(connection.getRemoteAddress()));
        } catch (Exception e) {
            Constants.LOG.error("Failed to flush aggregation batch", e);
        }
    }

    /**
     * 聚合帧直通发送（run9 退役波）：聚合包内部已有字典 ZSTD，若经 vanilla zlib
     * 再压即双重压缩——在<b>同一个 EventLoop 任务内</b>做阈值翻折
     * {@code setThreshold(MAX) → write → setThreshold(原值)}，聚合帧按
     * {@code VarInt(0)+明文} 出站。EventLoop FIFO 保证翻折窗口对其他写包原子；
     * vanilla zlib 对普通包的行为不受影响。
     * <p>
     * 原值取 {@code server.getCompressionThreshold()}（与 vanilla SetCompression 同源，
     * 编码端阈值必须等于解码端阈值）；原版压缩未启用（threshold&lt;0 或无 compress
     * handler）时聚合帧本就直通，无需翻折。
     */
    private static void sendAggregateBypassingVanillaCompression(Connection connection, FriendlyByteBuf buf) {
        io.netty.channel.Channel channel = ConnectionChannelAccess.getConnectionChannel(connection);
        if (channel == null) {
            sender.send(connection, buf);
            return;
        }
        Runnable send = () -> sender.send(connection, buf);
        if (!channel.eventLoop().inEventLoop()) {
            channel.eventLoop().execute(() -> sendAggregateBypassingVanillaCompression(connection, buf));
            return;
        }
        io.netty.channel.ChannelHandler compress = channel.pipeline().get("compress");
        if (compress instanceof net.minecraft.network.CompressionEncoder encoder) {
            int vanillaThreshold = resolveVanillaCompressionThreshold(connection);
            if (vanillaThreshold < 0) {
                send.run();
                return;
            }
            encoder.setThreshold(Integer.MAX_VALUE);
            try {
                send.run();
            } finally {
                encoder.setThreshold(vanillaThreshold);
            }
        } else {
            send.run();
        }
    }

    /**
     * 解析连接所属专用服的原版压缩阈值（server.properties 同源）；无法解析时回退
     * 原版默认 256。仅在压缩已启用（有 compress handler）时被调用。
     * <p>
     * 经 packet listener 按类型反射取 {@code MinecraftServer}（1.21.x 起
     * {@code ServerGamePacketListenerImpl.player} 字段可见性/形态跨版本不稳，
     * 类型匹配对 SRG/intermediary/改名免疫，见 {@code ReflectionCompat}）。
     */
    private static int resolveVanillaCompressionThreshold(Connection connection) {
        try {
            Object listener = connection.getPacketListener();
            net.minecraft.server.MinecraftServer server = (net.minecraft.server.MinecraftServer)
                    io.github.limuqy.mc.hassium.compat.ReflectionCompat.getFieldByTypeOrNull(
                            listener, net.minecraft.server.MinecraftServer.class, true);
            if (server != null) {
                return server.getCompressionThreshold();
            }
        } catch (Exception e) {
            Constants.LOG.debug("Hassium: failed to resolve vanilla compression threshold: {}", e.toString());
        }
        return 256;
    }

    /**
     * 过滤日志输出中的控制字符（review-fix: T2-73：远端地址等网络可控输入防日志注入）。
     */
    private static String sanitizeLog(Object value) {
        String s = String.valueOf(value);
        StringBuilder sb = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                if (sb == null) {
                    sb = new StringBuilder(s.length());
                    sb.append(s, 0, i);
                }
                sb.append('?');
            } else if (sb != null) {
                sb.append(c);
            }
        }
        return sb != null ? sb.toString() : s;
    }
}
