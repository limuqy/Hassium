package io.github.limuqy.mc.hassium.compat;

import io.github.limuqy.mc.hassium.mixin.ChunkMapAccessor;
import io.github.limuqy.mc.hassium.network.seedgen.ShadowSeedServer;
import io.github.limuqy.mc.hassium.network.seedgen.ShadowServerRegistry;
import io.github.limuqy.mc.hassium.server.RuntimeServerContext;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
#if MC_VER < MC_1_21_1
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
     * 注入表未命中时：非 SeedGen 不得走原版 IOWorker 读 type 126。
     * {@code ShadowStorageManager} 会整文件重写 .mca，原版 RegionFile 扇区表过期后
     * 会把邻槽/半写头解析成「负长度 / 错位 / 外部流」，再当权威柱推到客户端（虚空）。
     */
    public static boolean shouldBypassVanillaRegionRead(boolean shadowContext, boolean worldgenAllowed) {
        return shadowContext && !worldgenAllowed;
    }

    /** 影子存档只有 type 126；非 126 槽不得交给原版 zlib 解析。 */
    public static boolean shouldSkipVanillaChunkParse(boolean shadowContext, boolean hassiumType126) {
        return shadowContext && !hassiumType126;
    }

    public static boolean isEmptyStatus(ChunkStatus status) {
        return status == ChunkStatus.EMPTY;
    }

    /** 除 LIGHT 外透传注入柱的地形步骤；LIGHT 必须执行原版任务。 */
    public static boolean shouldPassthroughGenerationStep(boolean shadowContext, boolean worldgenAllowed,
                                                          boolean emptyStatus, boolean lightStatus) {
        return shadowContext && !worldgenAllowed && !emptyStatus && !lightStatus;
    }

    public static boolean shouldPassthroughGenerationStep(boolean shadowContext, boolean worldgenAllowed,
                                                          boolean emptyStatus) {
        return shouldPassthroughGenerationStep(shadowContext, worldgenAllowed, emptyStatus, false);
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

    public static int ticketLevel(ChunkStatus status) {
        return net.minecraft.server.level.ChunkLevel.byStatus(status);
    }

    public static void addUnknownTicket(ServerChunkCache cache, ChunkPos pos, ChunkStatus status) {
        if (cache == null || pos == null || status == null) {
            return;
        }
#if MC_VER < MC_1_21_5
        cache.chunkMap.getDistanceManager().addTicket(TicketType.UNKNOWN, pos, ticketLevel(status), pos);
#else
        // 1.21.5+ exposes only the radius API for UNKNOWN; LIGHT/FULL resolve to
        // the same zero-radius holder ticket and the requested status comes from lightFuture.
        cache.addTicketWithRadius(TicketType.UNKNOWN, pos, 0);
#endif
    }

    public static void removeUnknownTicket(ServerChunkCache cache, ChunkPos pos, ChunkStatus status) {
        if (cache == null || pos == null || status == null) {
            return;
        }
#if MC_VER < MC_1_21_5
        cache.chunkMap.getDistanceManager().removeTicket(TicketType.UNKNOWN, pos, ticketLevel(status), pos);
#else
        cache.removeTicketWithRadius(TicketType.UNKNOWN, pos, 0);
#endif
    }

    public static void addFullUnknownTicket(ServerChunkCache cache, ChunkPos pos) {
        addUnknownTicket(cache, pos, ChunkStatus.FULL);
    }

    public static void removeFullUnknownTicket(ServerChunkCache cache, ChunkPos pos) {
        removeUnknownTicket(cache, pos, ChunkStatus.FULL);
    }
    /**
     * 原版首次区块包在中心及一圈邻柱均为 FULL 后发送。
     * Halo 是可见柱的该邻域，必须与可见柱同持 FULL ticket；
     * INITIALIZE_LIGHT 只足以参与 lightChunk，无法满足原版首包时机。
     */
    public static void addShadowRoleTicket(ServerChunkCache cache, ChunkPos pos,
                                           io.github.limuqy.mc.hassium.network.ShadowChunkRole role) {
        addUnknownTicket(cache, pos, ChunkStatus.FULL);
    }

    public static void removeShadowRoleTicket(ServerChunkCache cache, ChunkPos pos,
                                              io.github.limuqy.mc.hassium.network.ShadowChunkRole role) {
        removeUnknownTicket(cache, pos, ChunkStatus.FULL);
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
#if MC_VER < MC_1_21_1
            return holder.getFutureIfPresentUnchecked(parent)
                    .getNow(ChunkHolder.UNLOADED_CHUNK).left().isPresent();
            // 1.20.5–1.20.6 的 Optional 中间层分支已随版本支持裁剪删除（API 自 1.21.1 起变化）
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

    /**
     * 可见 FULL 柱：优先 ChunkMap holder，Imposter 解包为 {@link LevelChunk}。
     * 存储刷脏用；未进 map 时返回 null（调用方回落注入表）。
     */
    public static LevelChunk fullLevelChunkIfPresent(ServerLevel level, ChunkPos pos) {
        if (level == null || pos == null) {
            return null;
        }
        try {
            ChunkMapAccessor map = (ChunkMapAccessor) (Object) level.getChunkSource().chunkMap;
            ChunkHolder holder = map.hassium$getVisibleChunkIfPresent(pos.toLong());
            if (holder == null) {
                return null;
            }
#if MC_VER < MC_1_21_1
            ChunkAccess access = holder.getFutureIfPresentUnchecked(ChunkStatus.FULL)
                    .getNow(ChunkHolder.UNLOADED_CHUNK).left().orElse(null);
#else
            ChunkAccess access = holder.getChunkIfPresentUnchecked(ChunkStatus.FULL);
#endif
            return unwrapLevelChunk(access);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static LevelChunk unwrapLevelChunk(ChunkAccess access) {
        if (access instanceof LevelChunk levelChunk) {
            return levelChunk;
        }
        if (access instanceof ImposterProtoChunk imposter) {
            return imposter.getWrapped();
        }
        return null;
    }
}
