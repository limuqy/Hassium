package io.github.limuqy.mc.hassium.server;

import io.github.limuqy.mc.hassium.protocol.ShadowPullRequestC2SPacket;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;

/**
 * 服务端声明的权威边沿（S2C）：客户端在真实服务端 tracking 集合进出时被通知，
 * 用于替代「影子端自绘几何推断权威集合」。
 * <p>
 * 语义：
 * <ul>
 *   <li>单向只发 enter：柱进入真实玩家的权威 tracking 集合；leave 继续由原版
 *       {@code ClientboundForgetLevelChunkPacket} 承载（不新增 leave 载荷）。</li>
 *   <li>{@code snapshot=true} 表示这是一次全量快照（视距变更 / 维度切换 / 重生 /
 *       重登），客户端必须先清空本地权威集合再按本包重建。</li>
 *   <li>{@code hash} = 服务端权威内容 hash（{@link io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil}
 *       口径）；{@code 0} = 未知（服务端 hash 缓存未命中且预算不足，客户端按未知处理照常 pull）。
 *       客户端本地有基线且 hash 相等时**不发任何请求**，直接本地交付并记「区块缓存全命中」。</li>
 * </ul>
 * 批量上限与 shadowPullV1 对齐（{@link ShadowPullRequestC2SPacket#MAX_ENTRIES}）。
 */
public record ChunkAuthorityS2CPacket(
        String dimension,
        long epoch,
        boolean snapshot,
        List<Entry> entries
) {
    public static final int MAX_ENTRIES = ShadowPullRequestC2SPacket.MAX_ENTRIES;

    /** 单柱权威边沿条目；{@code hash == 0} 表示服务端未附带权威 hash。 */
    public record Entry(int chunkX, int chunkZ, long hash) {
    }

    public ChunkAuthorityS2CPacket {
        if (dimension == null || dimension.length() > ShadowPullRequestC2SPacket.MAX_DIMENSION_LENGTH
                || entries == null || entries.size() > MAX_ENTRIES || epoch < 0) {
            throw new IllegalArgumentException("chunkAuthorityV1 packet exceeds limits");
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(dimension, ShadowPullRequestC2SPacket.MAX_DIMENSION_LENGTH);
        buf.writeVarLong(epoch);
        buf.writeBoolean(snapshot);
        buf.writeVarInt(entries.size());
        for (Entry entry : entries) {
            buf.writeVarInt(entry.chunkX());
            buf.writeVarInt(entry.chunkZ());
            buf.writeLong(entry.hash());
        }
    }

    public static ChunkAuthorityS2CPacket decode(FriendlyByteBuf buf) {
        String dimension = buf.readUtf(ShadowPullRequestC2SPacket.MAX_DIMENSION_LENGTH);
        long epoch = buf.readVarLong();
        boolean snapshot = buf.readBoolean();
        int size = buf.readVarInt();
        if (size < 0 || size > MAX_ENTRIES) {
            throw new IllegalArgumentException("chunkAuthorityV1 entry count exceeds limit");
        }
        List<Entry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            entries.add(new Entry(buf.readVarInt(), buf.readVarInt(), buf.readLong()));
        }
        return new ChunkAuthorityS2CPacket(dimension, epoch, snapshot, entries);
    }

    /** 单柱 enter 通知（非快照）。 */
    public static ChunkAuthorityS2CPacket enter(String dimension, long epoch, int chunkX, int chunkZ, long hash) {
        return new ChunkAuthorityS2CPacket(dimension, epoch, false, List.of(new Entry(chunkX, chunkZ, hash)));
    }
}
