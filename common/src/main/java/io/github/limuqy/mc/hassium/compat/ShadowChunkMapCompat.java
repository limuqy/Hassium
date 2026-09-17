package io.github.limuqy.mc.hassium.compat;

import io.github.limuqy.mc.hassium.mixin.ChunkMapAccessor;
import io.github.limuqy.mc.hassium.shadow.server.ShadowSeedServer;
import io.github.limuqy.mc.hassium.shadow.server.ShadowServerRegistry;
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
 * 探活结论（1.20.1 / 1.21.1 ChunkMap + ChunkStatusTasks）：影子注入柱以 LIGHT 级别票进入
 * ChunkMap，原版 LIGHT task 负责光照；FULL 票会向外扩散（约
 * {@code RADIUS_AROUND_FULL_CHUNK}=8），邻柱无盘则走 GENERATION_PYRAMID（噪声地形）。
 * 注入路径禁止 worldgen，因此不能把 FULL 金字塔当作算光路径；票据与
 * {@code scheduleChunkLoad} 短路只负责让注入柱进入 LIGHT holder。
 */
public final class ShadowChunkMapCompat {

    private static final ThreadLocal<Integer> WORLDGEN_DEPTH = ThreadLocal.withInitial(() -> 0);

    /**
     * worldgen 压制柱的悬置 load future（{@code scheduleChunkLoad} mixin 登记）。
     * 数据到位（packet 注入 / 读盘柱入表）时以 Imposter 放行，holder 恢复
     * LIGHT→FULL 推进 → {@code playerLoadedChunk} 桥重新触达。若不放行，悬置
     * holder 永卡 EMPTY：重连 tracking 既无新 {@code scheduleChunkLoad}（holder
     * 已存在）也无 ticking chunk（{@code getTickingChunk()} 为 null）→ 比对请求
     * 永不触发（R2 虚空根因）。
     */
    private static final java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.CompletableFuture<?>> SUSPENDED_LOADS =
            new java.util.concurrent.ConcurrentHashMap<>();

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

    /** 影子 worldgen 压制时登记悬置 load future（mixin suspend 分支调用）。 */
    public static void registerSuspendedLoad(String dimension, ChunkPos pos,
                                             java.util.concurrent.CompletableFuture<?> future) {
        if (dimension == null || pos == null || future == null) {
            return;
        }
        SUSPENDED_LOADS.put(io.github.limuqy.mc.hassium.utils.DimensionKey.key(dimension, pos.x, pos.z), future);
    }

    /**
     * 悬置柱数据到位：放行原版加载链。返回 true = 有悬置 future 被放行。
     * 完成值与 {@code scheduleChunkLoad} 短路分支一致（ImposterProtoChunk），
     * vanilla 链对 Imposter 的 FULL 晋升/BE 注册即读盘命中路径同款。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static boolean completeSuspendedLoad(String dimension, ChunkPos pos, LevelChunk chunk) {
        if (dimension == null || pos == null || chunk == null) {
            return false;
        }
        java.util.concurrent.CompletableFuture<?> future =
                SUSPENDED_LOADS.remove(io.github.limuqy.mc.hassium.utils.DimensionKey.key(dimension, pos.x, pos.z));
        if (future == null) {
            return false;
        }
        ImposterProtoChunk imposter = asImposter(chunk);
#if MC_VER < MC_1_21_1
        ((java.util.concurrent.CompletableFuture) future)
                .complete(com.mojang.datafixers.util.Either.left((ChunkAccess) imposter));
#else
        ((java.util.concurrent.CompletableFuture) future).complete((ChunkAccess) imposter);
#endif
        io.github.limuqy.mc.hassium.shadow.track.VanillaAlignedChunkProvider
                .completeAcquire(dimension, pos, chunk);
        return true;
    }

    /** 关停/park 清空：world 丢弃后悬置 future 无主，直接丢弃登记。 */
    public static void clearSuspendedLoads() {
        SUSPENDED_LOADS.clear();
    }

    /** 当前悬置 future 数量（诊断用）。 */
    public static int suspendedLoadCount() {
        return SUSPENDED_LOADS.size();
    }


    /** 影子存档只有 type 126；非 126 槽不得交给原版 zlib 解析。 */
    public static boolean shouldSkipVanillaChunkParse(boolean shadowContext, boolean hassiumType126) {
        return shadowContext && !hassiumType126;
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
    public static ImposterProtoChunk asImposter(LevelChunk chunk) {
        return new ImposterProtoChunk(chunk, false);
    }

    /**
     * 1.21.1+ {@code scheduleChunkLoad} 短路：已 materialize 柱直接以 Imposter 完成
     * （与 1.20.1 段 {@code completedFuture(Either.left(imposter))} 同语义，返回类型
     * 由版本差异决定）。
     */
    public static CompletableFuture<ChunkAccess> completedImposter(LevelChunk chunk) {
        return CompletableFuture.completedFuture(asImposter(chunk));
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
     * {@code scheduleChunkLoad} 短路：注入表或影子存档已有柱则直接 Imposter，
     * <b>禁止再走 worldgen</b>。映像整文件重写后原版 RegionFile 扇区表可能读空，
     * 必须问 {@link ShadowSeedServer#loadFromDisk}，不能只靠 IOWorker。
     */
    public static LevelChunk existingColumnForScheduleLoad(ShadowSeedServer server,
                                                           String dimension, ChunkPos pos) {
        if (server == null || pos == null) {
            return null;
        }
        LevelChunk injected = server.injectedChunk(dimension, pos.x, pos.z);
        if (injected != null) {
            return injected;
        }
        LevelChunk disk = server.loadFromDisk(dimension, pos);
        if (disk == null) {
            return null;
        }
        server.injectLoadedChunk(dimension, pos, disk, false);
        return disk;
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
