package io.github.limuqy.mc.hassium.protocol;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 字典同步包
 * <p>
 * 服务端在握手时发送给客户端，携带训练好的 ZSTD 字典（版本化快照）。
 * <p>
 * 线格式（旧版客户端只读前三段，扩展字段留在尾部被忽略；新版读取尾部并要求无多余字节）：
 * <pre>[isChunkDict:boolean] [len:VarInt] [bytes] [epoch:VarInt] [id:long]</pre>
 * <ul>
 *   <li>{@code epoch}：字典代（0 = 未安装/旧服务端）；</li>
 *   <li>{@code id}：字典内容 hash（{@link DictionarySnapshot#hash}；0 = 无扩展信息），
 *   客户端据此校验内容完整性后再安装。</li>
 * </ul>
 * 客户端判定「服务端是否在 DICT 聚合帧头写 epoch」也以尾部扩展字段是否存在为准
 * （{@link #hasDictionaryInfo()}）。
 */
public record DictionarySyncPayload(byte[] dictionary, boolean isChunkDict,
                                    int dictionaryEpoch, long dictionaryId, boolean extended) {

    /**
     * 最大字典大小（256KB）
     */
    private static final int MAX_DICT_SIZE = 256 * 1024;

    /**
     * 编码（扩展字段总是写入：旧端解码只读前三段，天然兼容）
     */
    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(isChunkDict);
        if (dictionary != null && dictionary.length > 0) {
            buf.writeVarInt(dictionary.length);
            buf.writeBytes(dictionary);
        } else {
            buf.writeVarInt(0);
        }
        buf.writeVarInt(dictionaryEpoch);
        buf.writeLong(dictionaryId);
    }

    /**
     * 解码（向后兼容）：无尾部扩展字段 = 旧服务端（extended=false）；读到扩展字段后
     * 要求缓冲耗尽，尾随垃圾按协议错误拒绝。
     */
    public static DictionarySyncPayload decode(FriendlyByteBuf buf) {
        boolean isChunkDict = buf.readBoolean();
        int length = buf.readVarInt();
        if (length > MAX_DICT_SIZE) {
            throw new IllegalArgumentException("Dictionary too large: " + length + " bytes (max " + MAX_DICT_SIZE + ")");
        }
        byte[] dict = new byte[length];
        if (length > 0) {
            buf.readBytes(dict);
        }
        if (!buf.isReadable()) {
            return new DictionarySyncPayload(dict, isChunkDict, 0, 0L, false);
        }
        int epoch = buf.readVarInt();
        long id = buf.readLong();
        if (buf.isReadable()) {
            throw new IllegalArgumentException(
                    "DictionarySyncPayload: " + buf.readableBytes() + " trailing bytes after extended fields");
        }
        return new DictionarySyncPayload(dict, isChunkDict, epoch, id, true);
    }

    /** 服务端是否携带扩展字段（epoch+id）——epoch 感知帧与 ACK 校验的前提。 */
    public boolean hasDictionaryInfo() {
        return extended;
    }
}
