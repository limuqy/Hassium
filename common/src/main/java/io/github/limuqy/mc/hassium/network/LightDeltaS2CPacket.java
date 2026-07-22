package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.ResourceLocationCompat;
import net.minecraft.network.FriendlyByteBuf;
#if MC_VER < MC_1_21_11
import net.minecraft.resources.ResourceLocation;
#else
import net.minecraft.resources.Identifier;
#endif

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * 服务端 -> 客户端：光照增量通知（轻量）
 * <p>
 * 拦截 {@code ClientboundLightUpdatePacket} 后，剥离光照数据，仅通知客户端
 * 哪些区块/section 的光照发生变化。客户端本地重算光照。
 * <p>
 * Wire format: {@code [entryCount:VarInt] [chunkX:VarInt, chunkZ:VarInt, skyYMask:BitSet, blockYMask:BitSet] * N}
 */
public record LightDeltaS2CPacket(List<Entry> entries) {

    public static final
#if MC_VER < MC_1_21_11
    ResourceLocation
#else
    Identifier
#endif
    CHANNEL = ResourceLocationCompat.create(Constants.MOD_ID, "light_delta_s2c");

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entries.size());
        for (Entry entry : entries) {
            buf.writeVarInt(entry.chunkX);
            buf.writeVarInt(entry.chunkZ);
            buf.writeBitSet(entry.skyYMask);
            buf.writeBitSet(entry.blockYMask);
        }
    }

    public static LightDeltaS2CPacket decode(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            int chunkX = buf.readVarInt();
            int chunkZ = buf.readVarInt();
            BitSet skyYMask = buf.readBitSet();
            BitSet blockYMask = buf.readBitSet();
            entries.add(new Entry(chunkX, chunkZ, skyYMask, blockYMask));
        }
        return new LightDeltaS2CPacket(entries);
    }

    /**
     * 单个区块的光照变更条目
     *
     * @param chunkX     区块 X 坐标
     * @param chunkZ     区块 Z 坐标
     * @param skyYMask   天空光照变更的 section 位掩码
     * @param blockYMask 方块光照变更的 section 位掩码
     */
    public record Entry(int chunkX, int chunkZ, BitSet skyYMask, BitSet blockYMask) {}
}
