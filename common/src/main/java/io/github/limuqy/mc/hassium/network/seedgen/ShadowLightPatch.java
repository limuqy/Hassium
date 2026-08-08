package io.github.limuqy.mc.hassium.network.seedgen;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.DataLayer;

/**
 * 影子端光照计算结果（回传客户端主线程轻量落地用）。
 * <p>
 * DataLayer 数组为 {@link DataLayer#copy()} 独立副本，可跨线程传递；
 * null 槽位 = 该 section 无数据层（空 section，客户端侧保持原样）。
 */
public record ShadowLightPatch(ChunkPos pos, DataLayer[] sky, DataLayer[] block, int bottomSection) {
}
