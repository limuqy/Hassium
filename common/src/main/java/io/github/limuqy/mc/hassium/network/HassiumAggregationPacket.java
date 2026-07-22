package io.github.limuqy.mc.hassium.network;

import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;
import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.PacketCodecCompat;
import io.github.limuqy.mc.hassium.compat.PacketPayloadCompat;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
#if MC_VER < MC_1_21_11
import net.minecraft.resources.ResourceLocation;
#else
import net.minecraft.resources.Identifier;
#endif

import java.util.ArrayList;
import java.util.List;

/**
 * Hassium 聚合数据包
 * <p>
 * 将多个子包聚合为一个大包，内部使用 ZSTD 压缩。
 * <p>
 * 编码格式：
 * [isCompressed:byte] [uncompressedLength:VarInt] [compressedData]
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
     * 编码聚合包
     */
    public void encode(FriendlyByteBuf buf) {
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

            // 检查是否需要压缩
            HassiumConfigService config = HassiumConfigService.getInstance();
            boolean compress = rawSize >= COMPRESSION_THRESHOLD && config.isUseContextCompression();

            if (compress) {
                // 使用 ZSTD 压缩（支持聚合包字典）
                int level = config.getCompressionLevel();
                boolean magicless = true; // 聚合包统一 ZSTD，硬编码

                ZstdCompressCtx compressCtx = new ZstdCompressCtx();
                compressCtx.setLevel(level);
                if (magicless) {
                    compressCtx.setMagicless(true);
                }

                // 加载聚合包字典（如果有）
                byte[] dict = DictionaryManager.getAggregationDict();
                boolean useDict = dict != null;
                if (useDict) {
                    compressCtx.loadDict(dict);
                }

                byte[] rawBytes = new byte[rawSize];
                rawBuf.readBytes(rawBytes);

                // 收集训练样本（仅用于聚合包字典）
                if (DictionaryManager.isSampling()) {
                    DictionaryManager.collectSample(rawBytes);
                }

                byte[] compressed = compressCtx.compress(rawBytes);
                compressCtx.close();

                // 写入标志位：区分是否使用了字典
                buf.writeByte(useDict ? COMPRESSED_WITH_DICT_FLAG : COMPRESSED_FLAG);
                buf.writeVarInt(rawSize);
                buf.writeBytes(compressed);

                Constants.LOG.debug("Aggregated and compressed: {} -> {} bytes ({}% reduction, dict={})",
                        rawSize, compressed.length,
                        String.format("%.2f", 100f * compressed.length / rawSize), useDict);
            } else {
                // 不压缩
                buf.writeByte(NOT_COMPRESSED_FLAG);
                buf.writeBytes(rawBuf);
            }
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
            // 解压
            int uncompressedLength = buf.readVarInt();
            int compressedLength = buf.readableBytes();
            byte[] compressed = new byte[compressedLength];
            buf.readBytes(compressed);

            boolean magicless = true; // 聚合包统一 ZSTD，硬编码

            ZstdDecompressCtx decompressCtx = new ZstdDecompressCtx();
            if (magicless) {
                decompressCtx.setMagicless(true);
            }

            // 只有当标志位指示使用了字典时，才加载字典
            if (flag == COMPRESSED_WITH_DICT_FLAG) {
                byte[] dict = DictionaryManager.getAggregationDict();
                if (dict != null) {
                    decompressCtx.loadDict(dict);
                } else {
                    Constants.LOG.warn("Compressed with dict flag set, but no dictionary available on client");
                }
            }

            rawData = decompressCtx.decompress(compressed, uncompressedLength);
            decompressCtx.close();
        } else {
            // 未压缩
            rawData = new byte[buf.readableBytes()];
            buf.readBytes(rawData);
        }

        // 解码子包
        FriendlyByteBuf rawBuf = new FriendlyByteBuf(Unpooled.wrappedBuffer(rawData));
        try {
            int packetCount = rawBuf.readVarInt();
            List<AggregatedSubPacket> subPackets = new ArrayList<>(packetCount);

            for (int i = 0; i < packetCount; i++) {
                subPackets.add(AggregatedSubPacket.decode(rawBuf, indexManager));
            }

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
#if MC_VER < MC_1_21_11
                ResourceLocation
#else
                Identifier
#endif
                type = subPacket.getType();
                ByteBuf data = subPacket.getDataBuf();

                Constants.LOG.debug("Handling aggregated sub-packet: {}", type);

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
                        Constants.LOG.error("Failed to decode vanilla packet {} (id={})", type, vanillaId, e);
                        continue;
                    }
                } else {
                    // 自定义包：通过 compat 层构造
                    byte[] rawBytes = new byte[data.readableBytes()];
                    data.readBytes(rawBytes);
                    packet = PacketPayloadCompat.createClientboundPayload(type, rawBytes);
                }

                // 分发到处理器
                if (packet != null) {
                    try {
                        @SuppressWarnings("rawtypes")
                        Packet rawPacket = packet;
                        rawPacket.handle(connection.getPacketListener());
                    } catch (Exception e) {
                        Constants.LOG.error("Failed to handle packet {}", type, e);
                    }
                }
            } catch (Exception e) {
                Constants.LOG.error("Failed to handle aggregated sub-packet: {}", subPacket.getType(), e);
            }
        }
    }

    public List<AggregatedSubPacket> getSubPackets() {
        return subPackets;
    }
}
