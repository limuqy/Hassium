package io.github.limuqy.mc.hassium.protocol;

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
 * 2. 冲刷触发：服务端 tick 尾 {@link #flushAllAsync()}（主线程只入队，压缩/发送在 TIMER 线程）
 * 3. 兜底：每次冲刷后重排一次性 watchdog，超过 {@code aggregationMaxWaitTimeMs}
 *    未再冲刷则强制冲一次（应对主线程卡顿导致 tick 尾长期不执行）
 * 4. PENDING 状态下缓冲但不刷新
 * 5. ENABLED 状态下正常聚合
 */
public class HassiumAggregationManager {
    private static int maxWaitMs = 50;
    private static int maxAggregationSize = 256 * 1024;
    /** 上次实际执行 flushAll 的墙钟；watchdog 以此判断是否过期。 */
    private static volatile long lastFlushAtMs = System.currentTimeMillis();

    /** 2.0.X 兼容面：聚合 PENDING 缓冲硬上限（字节）= 8 MiB。拍板值（约 2× 配置上限量级）。超限语义=丢弃该连接整个缓冲 + 降级直发 + warn（与 5s ACK 超时降级一致，非直发）。2.0.X 全小版本冻结，不可随配置调整。 */
    private static final long MAX_PENDING_BUFFER_BYTES = 8L * 1024 * 1024;
    /** 2.0.X 兼容面：聚合缓冲硬上限（条数），防小包洪泛时每包对象开销失控。 */
    private static final int MAX_PENDING_BUFFER_ENTRIES = 65536;

    /**
     * 会话内序列化失败过的包类型（"ns:path"）：直发短路 + 失败降噪。
     * <p>
     * 第三方 payload 可能无法在服务端序列化（如方法签名引用 client 专属类，专用服上
     * dist-clean 拒绝加载）。失败对同一类型是持续性的——不短路则每包重复反射尝试
     * （CPU 开销）并每包一条 ERROR 刷屏。仅内存集合：重启后重新尝试一次（mod 更新后可自愈）。
     */
    private static final java.util.Set<String> AGGREGATION_INCOMPATIBLE_TYPES = ConcurrentHashMap.newKeySet();

    /** 该类型本会话是否曾聚合序列化失败（调用方应直接放行直发）。 */
    public static boolean isAggregationIncompatible(String packetTypeId) {
        return AGGREGATION_INCOMPATIBLE_TYPES.contains(packetTypeId);
    }

    /** 记录一次序列化失败；返回 true 表示该类型首见（调用方据此决定日志级别）。 */
    private static boolean markAggregationIncompatible(PacketId type) {
        return AGGREGATION_INCOMPATIBLE_TYPES.add(type.fullId());
    }

    /**
     * 单连接聚合缓冲：O(1) 运行计数（takeOver 是 Netty 热路径，逐包累计避免 O(n²)）。
     * {@code bytes}/{@code count} 与 {@code packets} 在同一把锁内维护，二者恒一致。
     */
    private static final class ConnBuffer {
        final List<AggregatedSubPacket> packets = new ArrayList<>();
        long bytes;
        int count;
    }

    private static final ConcurrentHashMap<Connection, ConnBuffer> PACKET_BUFFER = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Hassium-Flush-thread");
        t.setDaemon(true);
        return t;
    });
    private static volatile ScheduledFuture<?> watchdogTask = null;
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
        maxWaitMs = (int) Math.max(1L, config.getAggregationMaxWaitTimeMs());
        maxAggregationSize = config.getAggregationMaxSize();

        rescheduleWatchdog();
        initialized = true;
        Constants.LOG.info("Hassium aggregation manager initialized (maxWait={}ms, maxSize={}KB)",
                maxWaitMs, maxAggregationSize / 1024);
    }

    /**
     * 接管包，添加到聚合缓冲区。
     *
     * @param packet     数据包
     * @param connection 连接
     * @return true=已入聚合缓冲（调用方应 cancel 原发送）；false=未入缓冲（调用方必须直发，禁止 cancel）
     */
    public static boolean takeOver(Packet<?> packet, Connection connection) {
        PacketId type = PacketTypeHelper.getPacketType(packet);
        if (type == null) {
            Constants.LOG.warn("Unknown packet type, skipping aggregation: {}", packet.getClass().getSimpleName());
            return false;
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
                    if (markAggregationIncompatible(type)) {
                        Constants.LOG.warn("Failed to extract payload data, skipping aggregation: {} (further occurrences degrade to direct send silently)", type);
                    } else {
                        Constants.LOG.debug("Failed to extract payload data, skipping aggregation: {}", type);
                    }
                    return false;
                }
                Constants.LOG.debug("Hassium: Extracted payload from CustomPayloadPacket: {} ({} bytes)",
                        type, data.length);
            } else {
                // 原版包：只写入包数据（不含包 ID）；1.20.5+ 走 StreamCodec
                data = PacketCodecCompat.serializePacketBody(
                        packet, PacketCodecCompat.resolveRegistryAccess(connection));
                if (data == null) {
                    Constants.LOG.warn("Failed to serialize vanilla packet, skipping aggregation: {}", type);
                    return false;
                }
                Constants.LOG.debug("Hassium: Serialized vanilla packet: {} ({} bytes)",
                        type, data.length);
            }

            AggregatedSubPacket subPacket = new AggregatedSubPacket(type, data);
            ConnBuffer buffer = PACKET_BUFFER.computeIfAbsent(connection, k -> new ConnBuffer());
            synchronized (buffer.packets) {
                if (buffer.bytes + data.length > MAX_PENDING_BUFFER_BYTES || buffer.count >= MAX_PENDING_BUFFER_ENTRIES) {
                    // 2.0.X 兼容面语义：超限丢弃整个缓冲 + 关闭该连接聚合（后续包直发）+ warn
                    // 当前包必须返回 false 让调用方直发，否则 ci.cancel() 会静默丢掉本包
                    Constants.LOG.warn("Aggregation buffer overflow for {}, dropping {} packets/{} bytes; degrading to direct send",
                            sanitizeLog(connection.getRemoteAddress()), buffer.count, buffer.bytes);
                    buffer.packets.clear();
                    buffer.bytes = 0;
                    buffer.count = 0;
                    HassiumConnectionRegistry.markDisabled(connection);
                    return false;
                }
                buffer.packets.add(subPacket);
                buffer.bytes += data.length;
                buffer.count++;
                Constants.LOG.debug("Added packet to aggregation buffer: {} (total: {})", type, buffer.count);
                return true;
            }
        } catch (Exception e) {
            if (markAggregationIncompatible(type)) {
                Constants.LOG.error("Failed to serialize packet for aggregation: {} (type marked incompatible; further occurrences degrade to direct send silently)", type, e);
            } else {
                Constants.LOG.debug("Failed to serialize packet for aggregation: {}", type, e);
            }
            return false;
        } finally {
            buf.release();
        }
    }

    /**
     * tick 尾异步冲刷：主线程只入队，压缩与发送在 TIMER 线程执行，不阻塞 tick。
     */
    public static void flushAllAsync() {
        TIMER.execute(HassiumAggregationManager::flushAll);
    }

    /**
     * 冲刷所有连接的非空缓冲（无批量门槛）。每次调用重置 lastFlushAtMs 并重排 watchdog。
     */
    private static void flushAll() {
        lastFlushAtMs = System.currentTimeMillis();
        PACKET_BUFFER.keySet().removeIf(c -> !c.isConnected());

        for (var entry : PACKET_BUFFER.entrySet()) {
            Connection connection = entry.getKey();
            ConnBuffer buffer = entry.getValue();

            if (buffer == null) {
                continue;
            }

            synchronized (buffer.packets) {
                if (buffer.packets.isEmpty()) {
                    continue;
                }

                // PENDING 状态下不刷新
                if (HassiumConnectionRegistry.isPending(connection)) {
                    Constants.LOG.debug("Connection is PENDING, skipping flush");
                    continue;
                }

                Constants.LOG.debug("Flushing aggregation buffer: {} packets", buffer.packets.size());
                flushInternal(connection, buffer);
            }
        }
        rescheduleWatchdog();
    }

    /**
     * 重排一次性兜底：超过 maxWaitMs 未再冲刷则强制冲一次（主线程卡顿时的安全网）。
     */
    private static void rescheduleWatchdog() {
        ScheduledFuture<?> prev = watchdogTask;
        if (prev != null) {
            prev.cancel(false);
        }
        watchdogTask = TIMER.schedule(HassiumAggregationManager::flushAll, maxWaitMs, TimeUnit.MILLISECONDS);
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
        ConnBuffer buffer = PACKET_BUFFER.remove(connection);
        if (buffer != null) {
            synchronized (buffer.packets) {
                buffer.packets.clear();
            }
        }
    }

    private static void flushConnectionInternal(Connection connection) {
        PACKET_BUFFER.keySet().removeIf(c -> !c.isConnected());
        ConnBuffer buffer = PACKET_BUFFER.get(connection);
        if (buffer == null) return;
        synchronized (buffer.packets) {
            flushInternal(connection, buffer);
        }
    }

    private static void flushInternal(Connection connection, ConnBuffer buffer) {
        try {
            if (buffer == null || buffer.packets.isEmpty()) {
                return;
            }
            if (!connection.isConnected()) {
                buffer.packets.clear();
                buffer.bytes = 0;
                buffer.count = 0;
                return;
            }

            // review-fix: T2-76: sender 未就绪（初始化顺序异常窗口）时保留缓冲，下轮重试不丢数据
            if (sender == null) {
                Constants.LOG.warn("AggregationSender not set, deferring flush of {} packets", buffer.packets.size());
                return;
            }

            // 复制并清空缓冲区
            List<AggregatedSubPacket> sendPackets = new ArrayList<>(buffer.packets);
            buffer.packets.clear();
            buffer.bytes = 0;
            buffer.count = 0;

            // 检查聚合大小限制，超过则分批发送
            long totalSize = 0;
            for (AggregatedSubPacket sp : sendPackets) {
                totalSize += sp.getData().length;
            }
            if (totalSize > maxAggregationSize) {
                Constants.LOG.warn("Aggregation buffer exceeds max size ({} > {} bytes), splitting",
                        totalSize, maxAggregationSize);
                List<AggregatedSubPacket> batch = new ArrayList<>();
                long batchSize = 0;
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
            // DICT 帧头是否写 epoch 按连接协商结果决定（旧协议客户端保持旧帧格式）
            byte[] rawFrame = aggregationPacket.encode(buf, HassiumConnectionRegistry.isEpochAware(connection));
            // debug.exportAggregatedPackets：导出编码后的完整聚合帧（开关关时仅一次配置读）
            AggregatedPacketExporter.exportAggregationFrame(buf, batch.size());
            // 字典更新语料：存在激活字典后每分钟随机目标捕获一帧（压缩前原始字节；异步落盘）
            DictionaryManager.collectCorpusSample(connection, rawFrame);

            if (sender != null) {
                sendAggregateBypassingVanillaCompression(connection, buf);
            } else {
                // review-fix: T2-76: sender 缺失（初始化顺序异常窗口）时回队兜底，不丢数据
                Constants.LOG.warn("AggregationSender not set, re-queueing {} packets", batch.size());
                buf.release();
                ConnBuffer buffer = PACKET_BUFFER.computeIfAbsent(connection, k -> new ConnBuffer());
                synchronized (buffer.packets) {
                    buffer.packets.addAll(batch);
                    for (AggregatedSubPacket sp : batch) {
                        buffer.bytes += sp.getData().length;
                    }
                    buffer.count += batch.size();
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
