package io.github.limuqy.mc.hassium.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 包聚合器
 * <p>
 * 借鉴 NEB 的包聚合机制，将多个小包合并为一个大包再压缩。
 * 这样可以：
 * 1. 减少包数量
 * 2. 提升压缩率（更大的数据块压缩效果更好）
 * 3. 减少网络开销
 * <p>
 * 聚合格式：
 * [packetCount:VarInt] [packet1Length:VarInt] [packet1Data] [packet2Length:VarInt] [packet2Data] ...
 */
public class PacketAggregator {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/PacketAggregator");

    /**
     * 最小批量大小（达到此数量立即刷新）
     */
    private final int minBatchSize;

    /**
     * 最大等待时间（纳秒），超时后即使未达到批量也刷新
     */
    private final long maxWaitTimeNs;

    /**
     * 最大聚合数据大小（字节）
     */
    private final int maxAggregatedSize;

    /**
     * 待发送的包列表
     */
    private final List<byte[]> pendingPackets = new ArrayList<>();

    /**
     * 待发送数据的总大小
     */
    private int pendingBytes = 0;

    /**
     * 上次刷新时间
     */
    private long lastFlushTime = System.nanoTime();

    /**
     * 创建包聚合器
     *
     * @param minBatchSize    最小批量大小
     * @param maxWaitTimeMs   最大等待时间（毫秒）
     * @param maxAggregatedSize 最大聚合数据大小（字节）
     */
    public PacketAggregator(int minBatchSize, long maxWaitTimeMs, int maxAggregatedSize) {
        this.minBatchSize = minBatchSize;
        this.maxWaitTimeNs = maxWaitTimeMs * 1_000_000L;
        this.maxAggregatedSize = maxAggregatedSize;
    }

    /**
     * 添加包到聚合缓冲区
     *
     * @param packetData 包数据
     * @return 如果需要刷新则返回 true
     */
    public synchronized boolean addPacket(byte[] packetData) {
        pendingPackets.add(packetData);
        pendingBytes += packetData.length + 4; // +4 for length prefix

        // 检查是否需要刷新
        return shouldFlush();
    }

    /**
     * 检查是否应该刷新
     */
    public synchronized boolean shouldFlush() {
        if (pendingPackets.isEmpty()) {
            return false;
        }

        // 达到最小批量
        if (pendingPackets.size() >= minBatchSize) {
            return true;
        }

        // 超过最大等待时间
        long now = System.nanoTime();
        if (now - lastFlushTime > maxWaitTimeNs) {
            return true;
        }

        // 超过最大聚合大小
        if (pendingBytes >= maxAggregatedSize) {
            return true;
        }

        return false;
    }

    /**
     * 刷新聚合缓冲区，返回合并后的数据
     *
     * @return 合并后的数据，如果没有数据则返回 null
     */
    public synchronized ByteBuf flush() {
        if (pendingPackets.isEmpty()) {
            return null;
        }

        // 合并所有包
        ByteBuf aggregated = mergePackets(pendingPackets);

        // 清空缓冲区
        int packetCount = pendingPackets.size();
        int totalBytes = pendingBytes;
        pendingPackets.clear();
        pendingBytes = 0;
        lastFlushTime = System.nanoTime();

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Flushed aggregation buffer: {} packets, {} bytes", packetCount, totalBytes);
        }

        return aggregated;
    }

    /**
     * 合并多个包为一个 ByteBuf
     * <p>
     * 格式：[packetCount:VarInt] [packet1Length:VarInt] [packet1Data] ...
     */
    private ByteBuf mergePackets(List<byte[]> packets) {
        // 计算总大小
        int totalSize = 0;
        for (byte[] packet : packets) {
            totalSize += packet.length;
        }

        // 分配缓冲区（额外空间用于长度前缀）
        ByteBuf buffer = Unpooled.buffer(totalSize + packets.size() * 5 + 5);

        // 写入包数量
        writeVarInt(buffer, packets.size());

        // 写入每个包
        for (byte[] packet : packets) {
            writeVarInt(buffer, packet.length);
            buffer.writeBytes(packet);
        }

        return buffer;
    }

    /**
     * 写入 VarInt
     */
    private static void writeVarInt(ByteBuf buf, int value) {
        while ((value & ~0x7F) != 0) {
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte(value);
    }

    /**
     * 获取待发送的包数量
     */
    public synchronized int getPendingCount() {
        return pendingPackets.size();
    }

    /**
     * 获取待发送数据的总大小
     */
    public synchronized int getPendingBytes() {
        return pendingBytes;
    }

    /**
     * 清空聚合缓冲区
     */
    public synchronized void clear() {
        pendingPackets.clear();
        pendingBytes = 0;
        lastFlushTime = System.nanoTime();
    }

    /**
     * 从聚合数据中解析出单个包
     *
     * @param aggregated 聚合数据
     * @return 解析出的包列表
     */
    public static List<byte[]> disaggregate(ByteBuf aggregated) {
        List<byte[]> packets = new ArrayList<>();

        // 读取包数量
        int packetCount = readVarInt(aggregated);

        // 读取每个包
        for (int i = 0; i < packetCount; i++) {
            int length = readVarInt(aggregated);
            byte[] packet = new byte[length];
            aggregated.readBytes(packet);
            packets.add(packet);
        }

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Disaggregated {} packets", packetCount);
        }

        return packets;
    }

    /**
     * 读取 VarInt
     */
    private static int readVarInt(ByteBuf buf) {
        int value = 0;
        int shift = 0;
        byte b;
        do {
            b = buf.readByte();
            value |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return value;
    }
}
