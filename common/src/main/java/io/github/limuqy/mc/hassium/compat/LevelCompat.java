package io.github.limuqy.mc.hassium.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Level / ResourceKey 版本兼容层
 * <p>
 * 1.21.11: {@code ResourceKey#location()} 重命名为 {@code identifier()}；
 * 1.21.9: 出生点取值由 {@code getSharedSpawnPos()} 改为 {@code getRespawnData().pos()}。
 * 维度 id / 出生点提取统一收口于此（WAVE2 契约 B 节：accessor 散落一次收口）。
 */
public final class LevelCompat {
    private LevelCompat() {}

    /**
     * ResourceKey 的 id 字符串（{@code namespace:path}；两版本 location/identifier 封装）。
     */
    public static String keyId(ResourceKey<?> key) {
        return key.identifier().toString();
    }

    /**
     * Level 的维度 id 字符串（{@code namespace:path}）。
     */
    public static String getDimensionId(Level level) {
        return level == null ? null : keyId(level.dimension());
    }

    /**
     * 服务端出生点（{@code <1.21.9 getSharedSpawnPos()} / {@code >=1.21.9 getRespawnData().pos()}）。
     */
    public static BlockPos spawnPos(ServerLevel level) {
        return level.getRespawnData().pos();
    }
    /**
     * 非阻塞 FULL 柱查取：仅当该柱已在加载列表且达到 FULL 状态时返回，否则 null（不触发生成）。
     * pull 待推送队列的就绪判定用；就绪柱的生成/算光由原版 chunk 系统异步推进。
     */
    public static net.minecraft.world.level.chunk.LevelChunk loadedFullChunk(ServerLevel level, int x, int z) {
        return io.github.limuqy.mc.hassium.compat.ShadowChunkMapCompat.unwrapLevelChunk(
                level.getChunk(x, z, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false));
    }
}



