package io.github.limuqy.mc.hassium.compat;

#if MC_VER < MC_1_21_1
import net.minecraft.world.level.chunk.ChunkStatus;
#else
import net.minecraft.world.level.chunk.status.ChunkStatus;
#endif

/**
 * 跨版本 {@code ChunkStatus} 名称查询：判断一条区块列 NBT 的 {@code Status} 是否已到
 * <b>方块状态定型</b>。
 * <p>
 * 【为什么阈值是 {@code FEATURES}】1.21.1 {@code ChunkStatusTasks} 里最后一个改写方块状态的
 * 步骤是 {@code generateFeatures}（{@code primeHeightmaps} + {@code applyBiomeDecoration}）；
 * 其后的 {@code initializeLight} / {@code light} 只做光照、{@code generateSpawn} 只做刷怪、
 * {@code full} 只做 ProtoChunk→LevelChunk 提升（1.20.1 同名任务链一致，见
 * {@code ChunkStatus} 内联任务）。因此 {@code status.isOrAfter(FEATURES)} 的列，其 block state
 * 已与 FULL 列一致，可作为 compare 基线；更早的列（{@code structure_starts} / {@code biomes} /
 * {@code noise} / {@code surface} / {@code carvers}）要么整柱空气、要么方块尚未定型。
 * <p>
 * 【唯一版本差异】{@code ChunkStatus} 1.20.1 在 {@code net.minecraft.world.level.chunk}，
 * 1.21.1 起搬到 {@code net.minecraft.world.level.chunk.status}；{@code byName(String)} 与
 * {@code isOrAfter(ChunkStatus)} 两段都有。
 * <p>
 * 失败语义：名称缺失 / 非法 / 注册表未初始化一律返回 {@code false}（= 不认作有内容），
 * 调用方据此降级到「无基线」路径，绝不因此抛异常打断收编或读盘。
 */
public final class ChunkStatusCompat {

    private ChunkStatusCompat() {}

    /**
     * 该状态名是否已到「方块状态定型」（{@code >= FEATURES}）。
     *
     * @param statusName 列 NBT 顶层 {@code Status} 字段（如 {@code minecraft:structure_starts}）
     */
    public static boolean isBlockFinal(String statusName) {
        if (statusName == null || statusName.isEmpty()) {
            return false;
        }
        try {
            ChunkStatus status = ChunkStatus.byName(statusName);
            return status != null && status.isOrAfter(ChunkStatus.FEATURES);
        } catch (Throwable ignored) {
            // 名称非法（ResourceLocation 解析失败）或注册表未就绪：按「未定型」处理。
            return false;
        }
    }
}
