package io.github.limuqy.mc.hassium.cache.client;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher;
import io.github.limuqy.mc.hassium.mixin.OptionsAccessor;
import io.github.limuqy.mc.hassium.network.ClientChunkHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 主控热切（failover）恢复期区块预填充。
 *
 * <p>无感切换（{@code network.dataPlane.recoveryFreeze=false}）恢复成功后，vanilla
 * {@code handleLogin} 会创建全新 {@code ClientLevel} 并 {@code setLevel}，新 level 的
 * {@code ClientChunkCache} 全空，服务端随后重发全部权威区块（网络下载秒级），期间玩家
 * 看到区块重新加载 / 虚空 —— 与「无感」体验相悖。
 *
 * <p>两级本地供给，数据源优先内存、兜底磁盘：
 * <ol>
 *   <li><b>内存快照</b>（{@link #captureAndStart}，{@code setLevel} HEAD 执行）：旧 level
 *       仍在内存、区块未清时，把权威区内已加载区块序列化为 NBT 直接投 {@link ClientCacheLoadQueue}
 *       就绪队列（零磁盘 IO），新 level 接管后分帧 apply。</li>
 *   <li><b>磁盘兜底</b>（{@link #tick}）：快照预算外 / 旧 level 未加载的区块，从磁盘缓存
 *       （{@code hassium_cache}）异步加载。</li>
 * </ol>
 * 两者均以 renderOnly 身份 apply；服务端权威区块随后到达时由 applier 覆盖
 * （{@code onRealChunkApplied} 清理 renderOnly 标记），过期快照不会回写。Hassium 数据面的
 * chunkHash 比对（{@code ClientMetadataHandler}）在数据面恢复后同步进行：HIT 区块从缓存转正
 * 不下载，MISMATCH 走分段增量 / 全量——本类只负责 hash 包到达前的空窗与数据面未恢复的兜底。
 *
 * <p>与超视渲染（{@link ViewDistanceExtensionService}）分工：OVD 填 serverVD 外环带，
 * 本类填 serverVD 内权威区；两者共用同一加载 / apply 管线与时间预算。
 *
 * <p>线程模型：全部方法仅主线程调用（tick / mixin 注入点）。
 */
public final class RecoveryChunkPrefill {

    private static final RecoveryChunkPrefill INSTANCE = new RecoveryChunkPrefill();

    /** 预填充窗口：恢复成功后最多持续这么久（权威区块通常数秒内到齐，超时自停兜底） */
    private static final long PREFILL_TIMEOUT_MS = 20_000L;

    /** 每 tick 新入队上限：与 OVD 共用后台加载 / apply 管线，避免压垮 executor 与主线程预算 */
    private static final int PREFILL_ENQUEUE_BUDGET = 32;

    /** 内存快照单次捕获上限（按距离取近）：序列化在主线程，控制恢复瞬间卡顿预算 */
    private static final int PREFILL_CAPTURE_MAX = 256;

    public static RecoveryChunkPrefill getInstance() {
        return INSTANCE;
    }

    /** 已入队、尚未落地（renderOnly apply 或权威先到）的区块；防重复入队，主线程独占 */
    private final Set<Long> pending = new HashSet<>();

    private boolean active = false;
    private long startedAtMs = 0L;

    private RecoveryChunkPrefill() {
    }

    /**
     * 恢复成功瞬间（{@code setLevel} HEAD，无感模式 + 恢复会话）调用：
     * 从旧 level 内存快照权威区已加载区块（零磁盘 IO），并进入磁盘兜底窗口。
     *
     * <p>此时旧 level 仍是 {@code Minecraft.level}（setLevel 方法体未执行），区块未清；
     * 新 level 即将接管，快照数据先入就绪队列，由后续 tick 的 {@code processQueueUntil}
     * 分帧 apply 到新 level。维度不一致（非 failover 的世界切换）时跳过。
     */
    public void captureAndStart(ClientLevel newLevel) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel oldLevel = mc.level;
        if (oldLevel == null || newLevel == null || oldLevel == newLevel) {
            Constants.LOG.info("Hassium: Prefill capture skipped: oldLevel={} newLevel={}",
                    oldLevel == null ? "null" : "present", newLevel == null ? "null" : "present");
            return;
        }
        // 仅无感模式 + 恢复会话（recoverySessionActive：恢复启动置位、新世界接管/terminal 清除）：
        // 定格模式保持原行为；普通进服 / 换服务器 / 换维度（非恢复会话）不捕获。
        if (io.github.limuqy.mc.hassium.config.HassiumConfigService.getInstance().isRecoveryFreeze()) {
            Constants.LOG.info("Hassium: Prefill capture skipped: recoveryFreeze=true (freeze mode)");
            stop();
            return;
        }
        if (!io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.isRecoverySessionActive()) {
            Constants.LOG.info("Hassium: Prefill capture skipped: not a recovery session");
            stop();
            return;
        }
        // 维度必须一致（failover 同一服务器同存档），否则数据不可复用
        if (!oldLevel.dimension().equals(newLevel.dimension())) {
            Constants.LOG.info("Hassium: Prefill capture skipped: dimension changed {} -> {}",
                    oldLevel.dimension(), newLevel.dimension());
            return;
        }
        int serverVD = ((OptionsAccessor) mc.options).hassium$getServerRenderDistance();
        if (serverVD <= 0) {
            Constants.LOG.info("Hassium: Prefill capture skipped: serverVD={}", serverVD);
            return;
        }
        int fillVD = Math.min(ViewDistanceExtensionService.resolveEffectiveClientVD(mc), serverVD);
        if (fillVD <= 0) {
            Constants.LOG.info("Hassium: Prefill capture skipped: fillVD={}", fillVD);
            return;
        }
        // teardownFrozenWorld（handleLogin HEAD）已把 mc.player 置 null；旧 level 因
        // freezeOnDisconnect cancel 保留，玩家实体仍在旧 level 实体列表里。
        net.minecraft.world.entity.player.Player player = mc.player;
        if (player == null) {
            List<? extends net.minecraft.world.entity.player.Player> players = oldLevel.players();
            if (!players.isEmpty()) {
                player = players.get(0);
            }
        }
        if (player == null) {
            Constants.LOG.info("Hassium: Prefill capture skipped: no player position available");
            return;
        }
        ChunkPos playerPos = player.chunkPosition();

        // 收集权威区内已加载区块，切比雪夫距离升序（近的先快照）
        ClientChunkCache cache = oldLevel.getChunkSource();
        List<ChunkPos> loaded = new ArrayList<>();
        for (int dx = -fillVD; dx <= fillVD; dx++) {
            for (int dz = -fillVD; dz <= fillVD; dz++) {
                ChunkPos pos = new ChunkPos(playerPos.x + dx, playerPos.z + dz);
                if (cache.getChunkNow(pos.x, pos.z) != null) {
                    loaded.add(pos);
                }
            }
        }
        loaded.sort(Comparator
                .comparingInt((ChunkPos p) -> Math.max(Math.abs(p.x - playerPos.x), Math.abs(p.z - playerPos.z)))
                .thenComparingLong(p -> {
                    long dx = (long) p.x - playerPos.x;
                    long dz = (long) p.z - playerPos.z;
                    return dx * dx + dz * dz;
                }));

        active = true;
        startedAtMs = System.currentTimeMillis();
        pending.clear();
        int captured = 0;
        for (ChunkPos pos : loaded) {
            if (captured >= PREFILL_CAPTURE_MAX) {
                break;
            }
            LevelChunk chunk = cache.getChunkNow(pos.x, pos.z);
            if (chunk == null) {
                continue;
            }
            try {
                CompoundTag nbt = ChunkDiskCodec.levelChunkToNbt(chunk, oldLevel);
                byte[] nbtBytes = ChunkDiskCodec.nbtToBytes(nbt);
                if (nbtBytes == null) {
                    continue;
                }
                long key = ChunkPos.asLong(pos.x, pos.z);
                pending.add(key);
                ClientCacheLoadQueue.getInstance().enqueueWithData(
                        pos, nbtBytes, MainThreadDispatcher.renderOnlyPriority(pos), true);
                captured++;
            } catch (Exception e) {
                Constants.LOG.debug("Hassium: Prefill snapshot failed for {}", pos, e);
            }
        }
        Constants.LOG.info("Hassium: Recovery prefill snapshot {} chunks from old level (fillVD={})",
                captured, fillVD);
    }

    /** 世界切换（{@code setLevel}）/ 会话终止（finalize）时调用；幂等。 */
    public void stop() {
        if (!active) {
            return;
        }
        active = false;
        pending.clear();
        Constants.LOG.info("Hassium: Recovery chunk prefill stopped");
    }

    public boolean isActive() {
        return active;
    }

    /**
     * 主线程每 tick 驱动：把玩家周围权威区内缺失区块按距离升序入队（renderOnly）。
     * <ul>
     *   <li>已落地（{@code hasChunk}）的 pending 摘除；权威先到的不再入队 / 不再覆盖</li>
     *   <li>磁盘 miss 的区块挂 pending 等权威（服务端必推），不重试</li>
     *   <li>超时自停；玩家移动后每 tick 以新位置重扫，旧 pending 残留由 stop 清理</li>
     * </ul>
     */
    public void tick() {
        if (!active) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - startedAtMs > PREFILL_TIMEOUT_MS) {
            stop();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        // 磁盘缓存未就绪：恢复会话应始终就绪（finalize 被恢复状态抑制），防御性返回
        if (ClientChunkHandler.getClientStorage() == null) {
            return;
        }
        int serverVD = ((OptionsAccessor) mc.options).hassium$getServerRenderDistance();
        if (serverVD <= 0) {
            // 服务端 SetChunkCacheRadius 未到（通常旧会话残留值已有效），等待
            return;
        }
        int fillVD = Math.min(ViewDistanceExtensionService.resolveEffectiveClientVD(mc), serverVD);
        if (fillVD <= 0) {
            return;
        }

        ClientLevel level = mc.level;
        ClientChunkCache cache = level.getChunkSource();
        ChunkPos playerPos = mc.player.chunkPosition();

        // 收集缺失区块（跳过已入队 / 已落地），切比雪夫距离升序，近的先入队
        List<ChunkPos> missing = new ArrayList<>();
        for (int dx = -fillVD; dx <= fillVD; dx++) {
            for (int dz = -fillVD; dz <= fillVD; dz++) {
                int x = playerPos.x + dx;
                int z = playerPos.z + dz;
                long key = ChunkPos.asLong(x, z);
                if (pending.contains(key)) {
                    // 已入队：落地（renderOnly apply 或权威先到）后摘除
                    if (cache.hasChunk(x, z)) {
                        pending.remove(key);
                    }
                    continue;
                }
                if (cache.hasChunk(x, z)) {
                    continue;
                }
                missing.add(new ChunkPos(x, z));
            }
        }
        missing.sort(Comparator
                .comparingInt((ChunkPos p) -> Math.max(Math.abs(p.x - playerPos.x), Math.abs(p.z - playerPos.z)))
                .thenComparingLong(p -> {
                    long dx = (long) p.x - playerPos.x;
                    long dz = (long) p.z - playerPos.z;
                    return dx * dx + dz * dz;
                }));

        int budget = PREFILL_ENQUEUE_BUDGET;
        for (ChunkPos pos : missing) {
            if (budget-- <= 0) {
                break;
            }
            double priority = MainThreadDispatcher.renderOnlyPriority(pos);
            pending.add(ChunkPos.asLong(pos.x, pos.z));
            ClientCacheLoadQueue.getInstance().enqueue(pos, priority, true);
        }
    }
}
