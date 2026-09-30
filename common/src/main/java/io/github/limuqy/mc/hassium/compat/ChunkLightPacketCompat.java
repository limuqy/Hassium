package io.github.limuqy.mc.hassium.compat;

import java.util.BitSet;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;

/**
 * 整柱/光照包跨版本访问收口。
 * <p>
 * 26.3 三包 record 化（访问器 {@code x()/z()/chunkData()/lightData()/skyYMask()…}），
 * 26.1.2–26.2 为 class + getX()/getChunkData()/getSkyYMask()… 风格。
 * 业务代码一律走本门面，禁止散落 {@code #if}（见 hassium-manifold skill）。
 */
public final class ChunkLightPacketCompat {

    private ChunkLightPacketCompat() {
    }

    // ===== ClientboundLevelChunkWithLightPacket =====

    public static int chunkX(ClientboundLevelChunkWithLightPacket packet) {
#if MC_VER >= MC_26_3
        return packet.x();
#else
        return packet.getX();
#endif
    }

    public static int chunkZ(ClientboundLevelChunkWithLightPacket packet) {
#if MC_VER >= MC_26_3
        return packet.z();
#else
        return packet.getZ();
#endif
    }

    public static ClientboundLevelChunkPacketData chunkData(ClientboundLevelChunkWithLightPacket packet) {
#if MC_VER >= MC_26_3
        return packet.chunkData();
#else
        return packet.getChunkData();
#endif
    }

    public static ClientboundLightUpdatePacketData lightData(ClientboundLevelChunkWithLightPacket packet) {
#if MC_VER >= MC_26_3
        return packet.lightData();
#else
        return packet.getLightData();
#endif
    }

    // ===== ClientboundLightUpdatePacket =====

    public static int lightX(ClientboundLightUpdatePacket packet) {
#if MC_VER >= MC_26_3
        return packet.x();
#else
        return packet.getX();
#endif
    }

    public static int lightZ(ClientboundLightUpdatePacket packet) {
#if MC_VER >= MC_26_3
        return packet.z();
#else
        return packet.getZ();
#endif
    }

    // ===== ClientboundLightUpdatePacketData =====

    public static BitSet skyYMask(ClientboundLightUpdatePacketData data) {
#if MC_VER >= MC_26_3
        return data.skyYMask();
#else
        return data.getSkyYMask();
#endif
    }

    public static BitSet blockYMask(ClientboundLightUpdatePacketData data) {
#if MC_VER >= MC_26_3
        return data.blockYMask();
#else
        return data.getBlockYMask();
#endif
    }

    public static BitSet emptySkyYMask(ClientboundLightUpdatePacketData data) {
#if MC_VER >= MC_26_3
        return data.emptySkyYMask();
#else
        return data.getEmptySkyYMask();
#endif
    }

    public static BitSet emptyBlockYMask(ClientboundLightUpdatePacketData data) {
#if MC_VER >= MC_26_3
        return data.emptyBlockYMask();
#else
        return data.getEmptyBlockYMask();
#endif
    }

    @SuppressWarnings("unchecked")
    public static List<byte[]> skyUpdates(ClientboundLightUpdatePacketData data) {
#if MC_VER >= MC_26_3
        return data.skyUpdates();
#else
        return data.getSkyUpdates();
#endif
    }

    @SuppressWarnings("unchecked")
    public static List<byte[]> blockUpdates(ClientboundLightUpdatePacketData data) {
#if MC_VER >= MC_26_3
        return data.blockUpdates();
#else
        return data.getBlockUpdates();
#endif
    }

    /**
     * 全空光数据（light-strip 替换包内光照用；客户端由原版光照引擎重算）。
     * <p>
     * 26.3：record 六参直构；26.1.2–26.2：走线格式 buffer 构造（4 空 BitSet + 2 空列表）。
     */
    public static ClientboundLightUpdatePacketData emptyLightData(int chunkX, int chunkZ) {
#if MC_VER >= MC_26_3
        BitSet empty = new BitSet();
        return new ClientboundLightUpdatePacketData(
                empty, empty, empty, empty, List.<byte[]>of(), List.<byte[]>of());
#else
        FriendlyByteBuf buffer = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            BitSet empty = new BitSet();
            buffer.writeBitSet(empty); // skyYMask
            buffer.writeBitSet(empty); // blockYMask
            buffer.writeBitSet(empty); // emptySkyYMask
            buffer.writeBitSet(empty); // emptyBlockYMask
            buffer.writeVarInt(0); // skyUpdates 空列表
            buffer.writeVarInt(0); // blockUpdates 空列表
            return new ClientboundLightUpdatePacketData(buffer, chunkX, chunkZ);
        } finally {
            buffer.release();
        }
#endif
    }
}
