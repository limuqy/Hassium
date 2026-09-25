package io.github.limuqy.mc.hassium.protocol;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.PacketCodecCompat;
import io.github.limuqy.mc.hassium.compat.PacketId;
import io.github.limuqy.mc.hassium.compat.PacketPayloadCompat;
import io.github.limuqy.mc.hassium.compression.ZstdRuntimeBridge;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import io.github.limuqy.mc.hassium.metrics.VanillaZlibEstimator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

import java.util.ArrayList;
import java.util.List;

/**
 * Hassium 聚合数据包
 * <p>
 * 将多个子包聚合为一个大包，内部使用 ZSTD 压缩。
 * <p>
 * 编码格式：
 * [isCompressed:byte] ([dictEpoch:VarInt] 仅 DICT 帧 + epoch 感知连接) [uncompressedLength:VarInt] [compressedData]
 * <p>
 * dictEpoch 只在「帧用字典压缩且对端已协商 epoch 感知」（{@code aggregation_ready} 携带
 * 字典回执）时写入；旧协议客户端的帧格式不变。客户端凭 epoch 在当前 + 上一版字典中选择
 * 解压字典，热切换窗口内的在途旧帧不会误用新字典。
 * <p>
 * compressedData 解压后是：
 * [packetCount:VarInt] [subPacket1...] [subPacket2...] ...
 * <p>
 * 子包格式：
 * [identifier:CompactHeader] [length:VarInt] [data]
 */
public class HassiumAggregationPacket {
    private static final byte COMPRESSED_FLAG = 1;
    private static final byte COMPRESSED_WITH_DICT_FLAG = 2;
    private static final byte NOT_COMPRESSED_FLAG = 0;
    private static final int COMPRESSION_THRESHOLD = 32;
    /** 解压后原始数据上限，与 {@code ZstdContextDecoder} 对齐 8MB，防恶意帧解压 OOM（review-fix: T13-C1） */
    private static final int MAXIMUM_UNCOMPRESSED_LENGTH = 8 * 1024 * 1024;
    /** 子包数量上限：每子包至少 2 字节（标识 + 长度 VarInt），且硬上限 4096（review-fix: T13-C1） */
    private static final int MAXIMUM_PACKET_COUNT = 4096;

    private final List<AggregatedSubPacket> subPackets;
    private final NamespaceIndexManager indexManager;

    /**
     * 编码构造器
     */
    public HassiumAggregationPacket(List<AggregatedSubPacket> subPackets, NamespaceIndexManager indexManager) {
        this.subPackets = subPackets;
        this.indexManager = indexManager;
    }

    /**
     * 编码聚合包（帧头不写 epoch；仅兼容旧调用方/调试工具，正式发送路径走
     * {@link #encode(FriendlyByteBuf, boolean)}）
     */
    public byte[] encode(FriendlyByteBuf buf) {
        return encode(buf, false);
    }

    /**
     * 编码聚合包
     *
     * @param dictEpochInFrame DICT 帧头写 epoch（仅对已协商 epoch 感知的连接；
     *                         旧协议客户端的帧格式保持不变）
     * @return 压缩前的原始帧字节（聚合包明文流；字典更新语料采集用）
     */
    public byte[] encode(FriendlyByteBuf buf, boolean dictEpochInFrame) {
        // 编码子包到原始缓冲区
        FriendlyByteBuf rawBuf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            // 写入子包数量
            rawBuf.writeVarInt(subPackets.size());

            // 写入每个子包
            for (AggregatedSubPacket subPacket : subPackets) {
                subPacket.encode(rawBuf, indexManager);
            }

            int rawSize = rawBuf.readableBytes();
            byte[] rawBytes = new byte[rawSize];
            rawBuf.readBytes(rawBytes);

            // 聚合包始终压缩（阈值）；不压缩需求走「关聚合 + 调 network-compression-threshold」
            HassiumConfigService config = HassiumConfigService.getInstance();
            boolean compress = rawSize >= COMPRESSION_THRESHOLD;

            if (compress) {
                // 使用 ZSTD 压缩（支持聚合包字典）
                int level = config.getCompressionLevel();
                ZstdRuntimeBridge.CompressCtx compressCtx = ZstdRuntimeBridge.newCompressCtx();
                compressCtx.setLevel(level);
                compressCtx.setMagicless(true);

                // 加载聚合包字典（如果有；快照整体读取，epoch 与字节同源）
                DictionarySnapshot snapshot = DictionaryManager.getActiveSnapshot();
                byte[] dict = snapshot != null ? snapshot.data() : null;
                boolean useDict = dict != null;
                if (useDict) {
                    compressCtx.loadDict(dict);
                }

                // 收集训练样本（仅用于聚合包字典）
                if (DictionaryManager.isSampling()) {
                    DictionaryManager.collectSample(rawBytes);
                }

                byte[] compressed = compressCtx.compress(rawBytes);
                compressCtx.close();

                // 写入标志位：区分是否使用了字典；DICT 帧对 epoch 感知连接附带字典代
                buf.writeByte(useDict ? COMPRESSED_WITH_DICT_FLAG : COMPRESSED_FLAG);
                if (useDict && dictEpochInFrame) {
                    buf.writeVarInt(snapshot.epoch());
                }
                buf.writeVarInt(rawSize);
                buf.writeBytes(compressed);

                Constants.LOG.debug("Aggregated and compressed: {} -> {} bytes ({}% reduction, dict={}, epoch={})",
                        rawSize, compressed.length,
                        String.format("%.2f", 100f * compressed.length / rawSize), useDict,
                        useDict && dictEpochInFrame ? snapshot.epoch() : -1);
            } else {
                // 不压缩
                buf.writeByte(NOT_COMPRESSED_FLAG);
                buf.writeBytes(rawBytes);
            }
            NetworkStats.recordVanillaBytesSent(VanillaZlibEstimator.estimate(rawBytes));
            return rawBytes;
        } finally {
            rawBuf.release();
        }
    }

    /**
     * 解码聚合包
     */
    public static HassiumAggregationPacket decode(FriendlyByteBuf buf, NamespaceIndexManager indexManager) {
        byte flag = buf.readByte();

        byte[] rawData;
        if (flag == COMPRESSED_FLAG || flag == COMPRESSED_WITH_DICT_FLAG) {
            int dictEpoch = 0;
            if (flag == COMPRESSED_WITH_DICT_FLAG && DictionaryManager.isServerEpochAware()) {
                // 服务端已协商 epoch 帧（dictionary_sync 扩展格式）→ DICT 帧头带字典代
                dictEpoch = buf.readVarInt();
            }
            // 解压
            int uncompressedLength = buf.readVarInt();
            if (uncompressedLength < 0 || uncompressedLength > MAXIMUM_UNCOMPRESSED_LENGTH) {
                throw new IllegalArgumentException(
                        "HassiumAggregationPacket: uncompressed length " + uncompressedLength
                                + " exceeds maximum " + MAXIMUM_UNCOMPRESSED_LENGTH);
            }
            int compressedLength = buf.readableBytes();
            byte[] compressed = new byte[compressedLength];
            buf.readBytes(compressed);

            ZstdRuntimeBridge.DecompressCtx decompressCtx = ZstdRuntimeBridge.newDecompressCtx();
            decompressCtx.setMagicless(true);

            // 只有当标志位指示使用了字典时，才加载字典
            if (flag == COMPRESSED_WITH_DICT_FLAG) {
                byte[] dict;
                if (dictEpoch != 0) {
                    // epoch 帧：按帧头代在当前 + 上一版中选择，切换窗口内的在途旧帧不误用新字典
                    dict = DictionaryManager.getAggregationDictForEpoch(dictEpoch);
                    if (dict == null) {
                        throw new DictionaryMissingException(
                                "dictionary epoch " + dictEpoch + " not installed (have current/previous only); frame rejected");
                    }
                } else {
                    dict = DictionaryManager.getAggregationDict();
                    if (dict == null) {
                        // 缺字典不解压（旧实现 warn 后继续解压必然 zstd 报错且语义含混）：直接拒绝该帧
                        throw new DictionaryMissingException(
                                "dict-flagged frame but no dictionary installed; frame rejected");
                    }
                }
                decompressCtx.loadDict(dict);
            }

            rawData = decompressCtx.decompress(compressed, uncompressedLength);
            decompressCtx.close();
            // 带宽压缩行锚点（聚合包）：该帧实际经过 zstd（原始 vs 压缩后线缆字节）。
            // 带宽压缩 = 聚合包压缩帧 + shadow pull 分段增量，chunk_payload 等其他通道不计。
            NetworkStats.recordZstdDecompressed(rawData.length, compressedLength);
        } else {
            // 未压缩
            rawData = new byte[buf.readableBytes()];
            buf.readBytes(rawData);
        }

        // 解码子包
        FriendlyByteBuf rawBuf = new FriendlyByteBuf(Unpooled.wrappedBuffer(rawData));
        try {
            int packetCount = rawBuf.readVarInt();
            if (packetCount < 0 || packetCount > MAXIMUM_PACKET_COUNT
                    || packetCount > rawData.length / 2) {
                throw new IllegalArgumentException(
                        "HassiumAggregationPacket: invalid packet count " + packetCount
                                + " for " + rawData.length + " raw bytes");
            }
            List<AggregatedSubPacket> subPackets = new ArrayList<>(packetCount);

            for (int i = 0; i < packetCount; i++) {
                subPackets.add(AggregatedSubPacket.decode(rawBuf, indexManager));
            }

            // 聚合帧 = 全局 vanilla 包流，不计入「流量节省」的「数据包」项（区块域埋点各自记账）。
            return new HassiumAggregationPacket(subPackets, indexManager);
        } finally {
            rawBuf.release();
        }
    }

    /**
     * 处理聚合包（解码并应用子包）
     */
    @SuppressWarnings("unchecked")
    public void handle(Connection connection) {
        for (AggregatedSubPacket subPacket : subPackets) {
            try {
                PacketId type = subPacket.getType();
                ByteBuf data = subPacket.getDataBuf();

                Constants.LOG.debug("Handling aggregated sub-packet: {}", sanitizeLog(type));

                if (type != null && HassiumPacketIds.SHADOW_PULL_RESPONSE_S2C.equals(type.fullId())) {
                    byte[] body = new byte[data.readableBytes()];
                    data.readBytes(body);
                    PayloadHandlers.handleShadowPullResponse(body);
                    continue;
                }

                // 检查是否是原版包
                Integer vanillaId = indexManager.getVanillaPacketId(type, PacketFlow.CLIENTBOUND);

                Packet<?> packet;
                if (vanillaId != null) {
                    // 原版包：1.20.5- 用 ConnectionProtocol；1.20.5+ 用 GameProtocols StreamCodec
                    try {
                        byte[] body = new byte[data.readableBytes()];
                        data.readBytes(body);
                        packet = PacketCodecCompat.deserializeClientbound(
                                vanillaId,
                                body,
                                PacketCodecCompat.resolveRegistryAccess(connection));
                    } catch (Exception e) {
                        Constants.LOG.error("Failed to decode vanilla packet {} (id={})", sanitizeLog(type), vanillaId, e);
                        continue;
                    }
                } else {
                    // 自定义包：通过 compat 层构造
                    byte[] rawBytes = new byte[data.readableBytes()];
                    data.readBytes(rawBytes);
                    packet = PacketPayloadCompat.createClientboundPayload(type, rawBytes);
                }

                // 分发到处理器。工人线程调用 handle()：PacketUtils 会 hop 主线程并抛
                // RunningOnDifferentThreadException（与 Connection.channelRead0 相同，必须吞掉）。
                if (packet != null) {
                    try {
                        @SuppressWarnings("rawtypes")
                        Packet rawPacket = packet;
                        rawPacket.handle(connection.getPacketListener());
                    } catch (net.minecraft.server.RunningOnDifferentThreadException ignored) {
                    } catch (Exception e) {
                        Constants.LOG.error("Failed to handle packet {}", sanitizeLog(type), e);
                    }
                }
            } catch (net.minecraft.server.RunningOnDifferentThreadException ignored) {
            } catch (Exception e) {
                Constants.LOG.error("Failed to handle aggregated sub-packet: {}", sanitizeLog(subPacket.getType()), e);
            }
        }
    }

    /**
     * 过滤日志输出中的控制字符（review-fix: T2-73：子包类型为网络可控输入，防日志注入）。
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

    public List<AggregatedSubPacket> getSubPackets() {
        return subPackets;
    }
}
