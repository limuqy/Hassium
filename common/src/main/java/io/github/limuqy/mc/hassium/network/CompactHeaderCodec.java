package io.github.limuqy.mc.hassium.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 紧凑包头编解码器
 * <p>
 * 借鉴 NEB 的实现，使用两级 VarInt 索引替换 ResourceLocation 字符串。
 * <p>
 * 编码格式：
 * - 已索引：[namespaceIndex:VarInt] [pathIndex:VarInt]
 * - 未索引：[0x00] [identifier:String]
 * <p>
 * 索引从 1 开始，0 作为"未索引"标记。
 */
public class CompactHeaderCodec {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/CompactCodec");

    /**
     * 未索引标记
     */
    public static final int ILLEGAL = 0;

    /**
     * 编码紧凑包头
     *
     * @param identifier   ResourceLocation 格式的标识符（如 "minecraft:commands"）
     * @param indexManager 索引管理器
     * @param buf          输出缓冲区
     */
    public static void writeIdentifier(String identifier, FriendlyByteBuf buf, NamespaceIndexManager indexManager) {
        int[] index = indexManager.getIndex(identifier);

        if (index != null) {
            // 已索引：写入两个 VarInt
            buf.writeVarInt(index[0]);
            buf.writeVarInt(index[1]);

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Compact encoded: {} -> [{}, {}] (saved {} bytes)",
                        identifier, index[0], index[1],
                        identifier.length() - 2);
            }
        } else {
            // 未索引：写入 0x00 + 完整 Identifier
            buf.writeVarInt(ILLEGAL);
            buf.writeUtf(identifier);

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Unknown packet type, using original format: {}", identifier);
            }
        }
    }

    /**
     * 解码紧凑包头
     *
     * @param buf          输入缓冲区
     * @param indexManager 索引管理器
     * @return 解码后的标识符，如果解码失败返回 null
     */
    public static String readIdentifier(FriendlyByteBuf buf, NamespaceIndexManager indexManager) {
        // 偷看首字节
        int firstByte = buf.readVarInt();

        if (firstByte == ILLEGAL) {
            // 未索引模式：读取完整 Identifier
            return buf.readUtf();
        } else {
            // 索引模式：读取 path 索引
            int pathIndex = buf.readVarInt();
            return indexManager.getIdentifier(firstByte, pathIndex);
        }
    }

    /**
     * 编码紧凑包头到 ByteBuf
     *
     * @param identifier   ResourceLocation 格式的标识符
     * @param indexManager 索引管理器
     * @param buf          输出缓冲区
     */
    public static void writeIdentifier(String identifier, ByteBuf buf, NamespaceIndexManager indexManager) {
        FriendlyByteBuf friendlyBuf = new FriendlyByteBuf(buf);
        writeIdentifier(identifier, friendlyBuf, indexManager);
    }

    /**
     * 从 ByteBuf 解码紧凑包头
     *
     * @param buf          输入缓冲区
     * @param indexManager 索引管理器
     * @return 解码后的标识符，如果解码失败返回 null
     */
    public static String readIdentifier(ByteBuf buf, NamespaceIndexManager indexManager) {
        FriendlyByteBuf friendlyBuf = new FriendlyByteBuf(buf);
        return readIdentifier(friendlyBuf, indexManager);
    }

    /**
     * 检查数据是否是紧凑格式
     */
    public static boolean isCompactFormat(ByteBuf data) {
        if (data.readableBytes() < 1) {
            return false;
        }
        // 偷看首字节，如果不是 0x00，则是紧凑格式
        int firstByte = data.getByte(data.readerIndex());
        return firstByte != ILLEGAL;
    }
}
