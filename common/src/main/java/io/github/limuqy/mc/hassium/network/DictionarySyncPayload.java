package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import net.minecraft.network.FriendlyByteBuf;
#if MC_VER < MC_1_21_11
import net.minecraft.resources.ResourceLocation;
#else
import net.minecraft.resources.Identifier;
#endif
import io.github.limuqy.mc.hassium.compat.ResourceLocationCompat;

/**
 * 字典同步包
 * <p>
 * 服务端在握手时发送给客户端，携带训练好的 ZSTD 字典。
 * 支持两种字典：
 * 1. 区块字典（静态，所有用户通用）
 * 2. 聚合包字典（动态，因 mod 组合而异）
 * <p>
 * 空字典（长度 0）表示没有可用字典。
 */
public record DictionarySyncPayload(byte[] dictionary, boolean isChunkDict) {
    public static final
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
CHANNEL = ResourceLocationCompat.create(Constants.MOD_ID, "dictionary_sync");

    /**
     * 最大字典大小（256KB）
     */
    private static final int MAX_DICT_SIZE = 256 * 1024;

    /**
     * 编码
     */
    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(isChunkDict);
        if (dictionary != null && dictionary.length > 0) {
            buf.writeVarInt(dictionary.length);
            buf.writeBytes(dictionary);
        } else {
            buf.writeVarInt(0);
        }
    }

    /**
     * 解码
     */
    public static DictionarySyncPayload decode(FriendlyByteBuf buf) {
        boolean isChunkDict = buf.readBoolean();
        int length = buf.readVarInt();
        if (length > MAX_DICT_SIZE) {
            throw new IllegalArgumentException("Dictionary too large: " + length + " bytes (max " + MAX_DICT_SIZE + ")");
        }
        if (length > 0) {
            byte[] dict = new byte[length];
            buf.readBytes(dict);
            return new DictionarySyncPayload(dict, isChunkDict);
        }
        return new DictionarySyncPayload(new byte[0], isChunkDict);
    }
}
