package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.ResourceLocationCompat;
import net.minecraft.network.FriendlyByteBuf;
#if MC_VER < MC_1_21_11
import net.minecraft.resources.ResourceLocation;
#else
import net.minecraft.resources.Identifier;
#endif

/**
 * 客户端 -> 服务端：同步缓存 Bloom 位图
 * <p>
 * 客户端把「磁盘缓存可能含有哪些区块」以 Bloom 位图形式同步给服务端，
 * 服务端据此分流：miss（确定无缓存）→ 主动直推数据；hit（可能有）→ 发 hash 让客户端对比。
 * <p>
 * {@code full=true}：进服时全量位图（当前存储 Bloom 快照），服务端覆盖旧层；
 * {@code full=false}：会话内增量批次位图（新缓存块），服务端追加一层。
 * 查询语义 = 任一层命中（OR）。
 */
public record ClientBloomSyncPacket(
        boolean full,
        byte[] bloomBytes
) {
    public static final
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
CHANNEL = ResourceLocationCompat.create(Constants.MOD_ID, "client_bloom_sync_c2s");

    /**
     * 编码到网络缓冲区
     */
    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(full);
        buf.writeVarInt(bloomBytes.length);
        buf.writeBytes(bloomBytes);
    }

    /**
     * 从网络缓冲区解码
     */
    public static ClientBloomSyncPacket decode(FriendlyByteBuf buf) {
        boolean full = buf.readBoolean();
        int length = buf.readVarInt();
        byte[] bloomBytes = new byte[length];
        buf.readBytes(bloomBytes);
        return new ClientBloomSyncPacket(full, bloomBytes);
    }
}
