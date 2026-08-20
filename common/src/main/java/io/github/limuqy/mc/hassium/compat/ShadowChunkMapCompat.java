package io.github.limuqy.mc.hassium.compat;

import io.github.limuqy.mc.hassium.mixin.ChunkMapAccessor;
import io.github.limuqy.mc.hassium.network.seedgen.ShadowSeedServer;
import io.github.limuqy.mc.hassium.network.seedgen.ShadowServerRegistry;
import io.github.limuqy.mc.hassium.server.RuntimeServerContext;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
#if MC_VER < MC_1_20_5
import net.minecraft.world.level.chunk.ChunkStatus;
#else
import net.minecraft.world.level.chunk.status.ChunkStatus;
#endif

/**
 * 影子端 ChunkMap 票/holder 跨版本适配。
 * <p>
 * 探活结论（1.20.1 / 1.21.1 ChunkMap + ChunkStatusTasks）：{@code LevelChunk.getPersistedStatus()}
 * 恒为 {@code FULL}。{@code ChunkStep.apply} 在 persisted 已达目标时仍会调用 task；
 * {@code isLighted} 在 {@code !isLightCorrect} 时为 false，故 LIGHT 步<strong>有可能</strong>
 * 对 {@code ImposterProtoChunk} 跑 {@code initializeLight}+{@code lightChunk}。
 * 但 FULL 票会向外扩散（约 {@code RADIUS_AROUND_FULL_CHUNK}=8），邻柱无盘则走
 * GENERATION_PYRAMID（噪声地形）。注入路径禁止 worldgen，因此<strong>不能</strong>把金字塔
 * 当作 FULL 注入柱的唯一算光路径；官方 {@code initializeLight}/{@code lightChunk} 仍由
 * {@code ShadowLightCompute} 两阶段屏障提交。Ticket + {@code scheduleChunkLoad} 短路只负责
 * 让注入柱进入 ChunkMap。
 */
public final class ShadowChunkMapCompat {

    private static final ThreadLocal<Integer> WORLDGEN_DEPTH = ThreadLocal.withInitial(() -> 0);

    private ShadowChunkMapCompat() {}

    /** SeedGen {@code generateChunk} 期间允许金字塔 worldgen；注入票路径禁止。 */
    public static void enterWorldgen() {
        WORLDGEN_DEPTH.set(WORLDGEN_DEPTH.get() + 1);
    }

    public static void leaveWorldgen() {
        int depth = WORLDGEN_DEPTH.get() - 1;
        if (depth <= 0) {
            WORLDGEN_DEPTH.remove();
        } else {
            WORLDGEN_DEPTH.set(depth);
        }
    }

    public static boolean isWorldgenAllowed() {
        return WORLDGEN_DEPTH.get() > 0;
    }

    /** 影子上下文且注入表命中：抢 {@code scheduleChunkLoad}，不得落到噪声生成。 */
    public static boolean shouldShortCircuitScheduleLoad(boolean shadowContext, boolean injectedPresent) {
        return shadowContext && injectedPresent;
    }

    /**
     * 非 EMPTY 步在注入票路径上改为透传（含 LIGHT/FULL，以免与我们的屏障双算光，
     * 也禁止噪声地形）。EMPTY 仍走 {@code scheduleChunkLoad}（注入表短路）。
     */
    public static boolean shouldPassthroughGenerationStep(boolean shadowContext, boolean worldgenAllowed,
                                                          boolean emptyStatus) {
        return shadowContext && !worldgenAllowed && !emptyStatus;
    }

    public static boolean isEmptyStatus(ChunkStatus status) {
        return status == ChunkStatus.EMPTY;
    }

    public static boolean isFullOrAfter(ChunkStatus status) {
        return status != null && status.isOrAfter(ChunkStatus.FULL);
    }

    /**
     * FULL 票会向外扩散，邻柱可能只有 ProtoChunk holder。
     * {@code ServerLevel.getChunk} 会把返回值强转 {@code LevelChunk}——注入表未命中时
     * 不得把 Proto 交给这条路径。SeedGen worldgen 期间放行原版取数。
     */
    public static boolean shouldSuppressUninjectedFullGetChunk(boolean shadowContext, boolean worldgenAllowed,
                                                              boolean injectedPresent, boolean fullOrAfter) {
        return shadowContext && !worldgenAllowed && !injectedPresent && fullOrAfter;
    }

    /** 包可见：票集合加/卸对称（测试用）。 */
    public static boolean rememberTicketKey(Set<Long> keys, long chunkKey) {
        return keys != null && keys.add(chunkKey);
    }

    public static boolean forgetTicketKey(Set<Long> keys, long chunkKey) {
        return keys != null && keys.remove(chunkKey);
    }

    public static ImposterProtoChunk asImposter(LevelChunk chunk) {
        return new ImposterProtoChunk(chunk, false);
    }

    public static CompletableFuture<ChunkAccess> completedImposter(LevelChunk chunk) {
        return CompletableFuture.completedFuture(asImposter(chunk));
    }

    public static int fullTicketLevel() {
        return net.minecraft.server.level.ChunkLevel.byStatus(ChunkStatus.FULL);
    }

    public static void addFullUnknownTicket(ServerChunkCache cache, ChunkPos pos) {
        if (cache == null || pos == null) {
            return;
        }
        int level = fullTicketLevel();
#if MC_VER < MC_1_21_9
        cache.chunkMap.getDistanceManager().addTicket(TicketType.UNKNOWN, pos, level, pos);
#else
        // radius 0 → ticket level = ChunkLevel.byStatus(FULL)；UNKNOWN 不含 SIMULATION 位
        cache.addTicketWithRadius(TicketType.UNKNOWN, pos, 0);
#endif
    }

    public static void removeFullUnknownTicket(ServerChunkCache cache, ChunkPos pos) {
        if (cache == null || pos == null) {
            return;
        }
        int level = fullTicketLevel();
#if MC_VER < MC_1_21_9
        cache.chunkMap.getDistanceManager().removeTicket(TicketType.UNKNOWN, pos, level, pos);
#else
        cache.removeTicketWithRadius(TicketType.UNKNOWN, pos, 0);
#endif
    }

    /**
     * 与原版 {@code ServerChunkCache.getChunkForLighting} 同一条件：holder 上已有
     * {@code INITIALIZE_LIGHT} 的 parent（FEATURES）chunk。不经 getChunk mixin。
     */
    public static boolean hasInitializeLightParent(ServerChunkCache cache, int x, int z) {
        if (cache == null) {
            return false;
        }
        try {
            ChunkMapAccessor map = (ChunkMapAccessor) (Object) cache.chunkMap;
            ChunkHolder holder = map.hassium$getVisibleChunkIfPresent(ChunkPos.asLong(x, z));
            if (holder == null) {
                return false;
            }
            ChunkStatus parent = ChunkStatus.INITIALIZE_LIGHT.getParent();
#if MC_VER < MC_1_20_5
            return holder.getFutureIfPresentUnchecked(parent)
                    .getNow(ChunkHolder.UNLOADED_CHUNK).left().isPresent();
#elif MC_VER < MC_1_21_1
            return holder.getFutureIfPresentUnchecked(parent)
                    .getNow(ChunkHolder.UNLOADED_CHUNK).orElse(null) != null;
#else
            return holder.getChunkIfPresentUnchecked(parent) != null;
#endif
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean hasVisibleHolder(ServerChunkCache cache, int x, int z) {
        if (cache == null) {
            return false;
        }
        try {
            ChunkMapAccessor map = (ChunkMapAccessor) (Object) cache.chunkMap;
            return map.hassium$getVisibleChunkIfPresent(ChunkPos.asLong(x, z)) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static ShadowSeedServer shadowServerOrNull() {
        if (!RuntimeServerContext.isShadowServerContext()) {
            return null;
        }
        return ShadowServerRegistry.getInstance().get();
    }
}
