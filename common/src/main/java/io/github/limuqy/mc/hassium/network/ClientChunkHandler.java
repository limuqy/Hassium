package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.shadow.light.ShadowVanillaLightPipeline;
import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.utils.DebugLogger.LogType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.LightLayer;


import java.util.List;



/**
 * 客户端区块处理器门面（Phase 0 隔离重构后）。
 * <p>
 * 全部可变状态（storage、pending hash 表、apply 重入标志）已迁入
 * {@link ClientChunkPipeline} 实例字段；本类保留既有 public static 签名
 * 供调用方零改动转发，处理逻辑经 {@link ClientChunkPipeline#getInstance()} 访问状态。
 * Phase 4 完成后删除本门面，调用方直指 pipeline 实例。
 */
public class ClientChunkHandler {
    /** 仅诊断链路使用的区块数据来源；绝不编码进网络包或存档。 */
    public enum TraceOrigin {
        /**
         * 经 chunk_payload 归一通道到达的 FULL（Compare+Pull 响应正文 / 影子 tracking
         * 本地存货 / 拦截模式先达数据）；历史命名为“服务端推送”，pull 模式下
         * 不代表服务端自主灌输——稳态期间该来源应为零。
         */
        SERVER_PUSH("server_push"),
        /** Compare+Pull 响应 FULL 落地（影子 tracking 采集；非服务端自主推送）。 */
        REMOTE_PULL("remote_pull"),
        SHADOW_MEMORY_CACHE("shadow_memory_cache"),
        SHADOW_DISK_CACHE("shadow_disk_cache"),
        LOCAL_GENERATION("local_generation"),
        /** 分段增量就地合并后回传；不是服务端整柱直推，不得记入全量 miss。 */
        SECTION_DELTA("section_delta");

        private final String logValue;

        TraceOrigin(String logValue) {
            this.logValue = logValue;
        }
    }

    /** 在途 pull FULL 落地标记（ChunkPos.asLong；applyShadowPullFull 置位，listener 消费）。
     *  必须是集合：主线程 mc.execute 会积压多柱，单槽 AtomicLong 会被后到的 mark 覆盖。 */
    private static final java.util.Set<Long> PENDING_PULL_APPLY =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** pull FULL 响应落地前调用；listener 据此以 REMOTE_PULL 归因，且勿走原版首包拦截。 */
    static void markPullApply(int chunkX, int chunkZ) {
        PENDING_PULL_APPLY.add(ChunkPos.asLong(chunkX, chunkZ));
    }

    static boolean isPendingPullApply(int chunkX, int chunkZ) {
        return PENDING_PULL_APPLY.contains(ChunkPos.asLong(chunkX, chunkZ));
    }

    /** listener 分支消费：该柱是否为在途 pull FULL 落地；命中即清除。 */
    public static boolean consumePullApplyOrigin(int chunkX, int chunkZ) {
        return PENDING_PULL_APPLY.remove(ChunkPos.asLong(chunkX, chunkZ));
    }

    /**
     * 统计/落地归因必须始终携带真实来源。曾经按 CHUNK_APPLY 日志开关 strip 成 null，
     * 导致生产（日志关）下 {@code accountAuthoritativeLanded} 整段跳过、区块加载恒 0。
     */
    public static TraceOrigin traceOriginIfLoggingEnabled(TraceOrigin origin) {
        return origin;
    }

    /**
     * 影子端官方通道落地的诊断事件；来源为 null 表示开启日志前已入队。
     */
    public static void logShadowChunkApplyEvent(String phase, ChunkPos pos, boolean renderOnly, TraceOrigin origin) {
        logChunkApplyEvent(phase, pos, renderOnly, Minecraft.getInstance(), origin);
    }


    public static void onDimensionChanged() {
        PENDING_PULL_APPLY.clear();
    }

    /**
     * 重置客户端缓存存储（断开连接时调用，转发 pipeline）
     */
    public static void resetStorage() {
        PENDING_PULL_APPLY.clear();
        ClientChunkPipeline.getInstance().resetStorage();
    }


    /**
     * 暂存 contentHash，供后续收到区块数据时使用（转发 pipeline）
     */
    public static void storePendingContentHash(int chunkX, int chunkZ, long contentHash) {
        ClientChunkPipeline.getInstance().storePendingContentHash(chunkX, chunkZ, contentHash);
    }


    /**
     * 应用 shadowPullV1 返回的权威 FULL 包。payload 是原版
     * {@code ClientboundLevelChunkWithLightPacket} 线格式，经原版 listener 派发
     * （{@code MixinClientPacketListener} HEAD 钩子接管影子光照管线）。
     */
    public static boolean applyShadowPullFull(byte[] payload) {
        net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket packet = decodeChunkPacket(payload);
        if (packet == null) {
            return false;
        }
        markPullApply(packet.getX(), packet.getZ());
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        net.minecraft.client.multiplayer.ClientPacketListener listener = mc.getConnection();
        if (listener == null) {
            return false;
        }
        mc.execute(() -> listener.handleLevelChunkWithLight(packet));
        return true;
    }

    /**
     * 还原官方区块包（线格式字节 → {@code ClientboundLevelChunkWithLightPacket}；
     * 影子链路投递用）。decode 失败返回 null（调用方回退旧链）。
     */
    private static net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
            decodeChunkPacket(byte[] packetBytes) {
        try {
#if MC_VER < MC_1_21_1
            io.netty.buffer.ByteBuf nettyBuf = io.netty.buffer.Unpooled.wrappedBuffer(packetBytes);
            try {
                net.minecraft.network.FriendlyByteBuf friendlyBuf =
                        new net.minecraft.network.FriendlyByteBuf(nettyBuf);
                return new net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket(friendlyBuf);
            } finally {
                nettyBuf.release();
            }
#else
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.level == null) {
                return null;
            }
            net.minecraft.network.RegistryFriendlyByteBuf buf = new net.minecraft.network.RegistryFriendlyByteBuf(
                    io.netty.buffer.Unpooled.wrappedBuffer(packetBytes), mc.level.registryAccess());
            try {
                return net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
                        .STREAM_CODEC.decode(buf);
            } finally {
                buf.release();
            }
#endif
        } catch (Exception e) {
            DebugLogger.error("[DECODE_CHUNK] Failed to decode chunk packet", e);
            return null;
        }
    }



    /** 标记柱全部 section dirty 触发渲染重建（与原版 apply 后行为对齐）。 */
    public static void markChunkSectionsDirty(ClientLevel level, int chunkX, int chunkZ) {
        int minSection = io.github.limuqy.mc.hassium.compat.LevelHeightCompat.getMinSection(level);
        int maxSection = io.github.limuqy.mc.hassium.compat.LevelHeightCompat.getMaxSectionExclusive(level);
        for (int sectionY = minSection; sectionY < maxSection; sectionY++) {
            level.setSectionDirtyWithNeighbors(chunkX, sectionY, chunkZ);
        }
    }

    /**
     * 诊断日志：同一条区块应用生命周期事件记录毫秒时间、来源、视图角色、目标区块与当前玩家位置。
     * 仅在 debug.chunkApplyLogging 开启时读取时钟和玩家坐标，避免正常热路径额外工作。
     */
    private static void logChunkApplyEvent(String phase, ChunkPos pos, boolean renderOnly, Minecraft mc) {
        logChunkApplyEvent(phase, pos, renderOnly, mc, TraceOrigin.SERVER_PUSH);
    }

    private static void logChunkApplyEvent(String phase, ChunkPos pos, boolean renderOnly, Minecraft mc,
                                           TraceOrigin origin) {
        if (!DebugLogger.isEnabled(LogType.CHUNK_APPLY)) {
            return;
        }
        long eventMs = System.currentTimeMillis();
        String originValue = origin == null ? "unknown" : origin.logValue;
        String view = renderOnly ? "ovd" : "authoritative";
        if (mc.player == null) {
            DebugLogger.info(LogType.CHUNK_APPLY,
                    "[CHUNK_APPLY] eventMs={} phase={} origin={} view={} target=({},{}) renderOnly={} player=unavailable",
                    eventMs, phase, originValue, view, pos.x, pos.z, renderOnly);
            return;
        }
        int playerX = (int) Math.floor(mc.player.getX());
        int playerY = (int) Math.floor(mc.player.getY());
        int playerZ = (int) Math.floor(mc.player.getZ());
        DebugLogger.info(LogType.CHUNK_APPLY,
                "[CHUNK_APPLY] eventMs={} phase={} origin={} view={} target=({},{}) renderOnly={} playerBlock=({},{},{}) playerChunk=({},{})",
                eventMs, phase, originValue, view, pos.x, pos.z, renderOnly,
                playerX, playerY, playerZ, playerX >> 4, playerZ >> 4);
    }


    /**
     * 光照缓存等价值字节估算（与 {@code ClientMetadataHandler.ESTIMATED_CHUNK_BYTES} 同口径，16KB/chunk）。
     * 见 {@link NetworkStats#ESTIMATED_LIGHT_BYTES} 注释。
     */
    /** 探针：per-pos 客户端 apply 计数（重复 apply 检测；仅诊断用）。 */
    private static final java.util.Map<Long, Integer> APPLY_COUNT =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 往返飞行黑块探针（flyroundtrip 场景断言锚）：
     *  {@link #darkLightProbeSamples} 全部「skyTop==0」**即时**采样数（含光队列 flush 前瞬态，仅观测）；
     *  {@link #PROBE_LAST_SKY} 每柱**复检后**（post-apply）最新采样值 → 读侧按「诊断时刻仍黑」统计；
     *  {@link #PROBE_EVER_LIT} 曾观测到 >0 的柱（不清除）→ 用于「曾亮柱被打黑」回归口径（flyroundtrip 主锚）。 */
    private static final java.util.concurrent.atomic.AtomicLong darkLightProbeSamples =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.Map<Long, Integer> PROBE_LAST_SKY =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<Long> PROBE_EVER_LIT =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * 待复检柱：光/整柱落地后客户端光队列可能仍要 1～数帧才 flush，单帧复检会把
     * 「尚未点亮」误判成回归。值 = 剩余复检帧数；仍黑则续排，亮了立即定稿。
     */
    private static final java.util.Map<Long, Integer> PROBE_PENDING_RECHECK =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final int PROBE_RECHECK_MAX_FRAMES = 8;

    /** 「skyTop==0」采样总数（含「首次落地瞬态」，仅诊断）。 */
    public static long darkLightProbeSampleCount() {
        return darkLightProbeSamples.get();
    }

    /**
     * **诊断时刻仍黑**的柱数（每柱取最新一次**复检后**采样值；已卸载柱不计）。
     * 观测口径（flyroundtrip 门禁主锚是 {@link #darkRegressionChunkCount}）。
     */
    public static long darkLightProbeChunkCount() {
        long n = 0;
        for (Integer sky : PROBE_LAST_SKY.values()) {
            if (sky != null && sky == 0) {
                n++;
            }
        }
        return n;
    }

    /** 仍黑**且本会话曾亮过**的柱数（= 用户口径「已缓存区块被打黑」，flyroundtrip 门禁主锚）。 */
    public static long darkRegressionChunkCount() {
        long n = 0;
        for (java.util.Map.Entry<Long, Integer> e : PROBE_LAST_SKY.entrySet()) {
            if (e.getValue() != null && e.getValue() == 0 && PROBE_EVER_LIT.contains(e.getKey())) {
                n++;
            }
        }
        return n;
    }

    /**
     * 客户端卸载该柱：清掉「最新采样」与待复检记录（仍黑统计不该含已不可见的柱），
     * 但保留「曾亮」标记——回程重新落地若变黑，必须仍算回归。
     */
    public static void onProbeChunkUnloaded(ChunkPos pos) {
        if (pos != null) {
            PROBE_LAST_SKY.remove(pos.toLong());
            PROBE_PENDING_RECHECK.remove(pos.toLong());
        }
    }

    /** 光包/整柱 apply 后排队复检（去重续期）：判定状态由 {@link #runProbeRecheck} 写入。 */
    public static void scheduleProbeRecheck(ChunkPos pos) {
        if (pos != null) {
            PROBE_PENDING_RECHECK.put(pos.toLong(), PROBE_RECHECK_MAX_FRAMES);
        }
    }

    /**
     * 帧首复检（{@code ShadowLightCompute.drainReady} 调用，客户端主线程）。
     * <p>
     * vanilla 光队列可能在 apply 后 1～数帧才 flush：单帧复检会把「尚未点亮」
     * 记成回归。仍黑且还有剩余帧则续排；亮了立即写入 {@link #PROBE_EVER_LIT}
     * 并定稿 {@link #PROBE_LAST_SKY}。
     */
    public static void runProbeRecheck(ClientLevel level) {
        if (PROBE_PENDING_RECHECK.isEmpty()) {
            return;
        }
        if (level == null) {
            PROBE_PENDING_RECHECK.clear(); // 断连/切维竞态：旧维度的待复检全部作废
            return;
        }
        for (java.util.Iterator<java.util.Map.Entry<Long, Integer>> it =
                PROBE_PENDING_RECHECK.entrySet().iterator(); it.hasNext(); ) {
            java.util.Map.Entry<Long, Integer> e = it.next();
            long key = e.getKey();
            int remaining = e.getValue();
            ChunkPos pos = new ChunkPos(key);
            if (!level.getChunkSource().hasChunk(pos.x, pos.z)) {
                it.remove();
                continue; // 已卸载：由 onProbeChunkUnloaded 收口，不判
            }
            int bx = (pos.x << 4) + 8;
            int bz = (pos.z << 4) + 8;
            int minY = io.github.limuqy.mc.hassium.compat.LevelHeightCompat.getMinBlockY(level);
            int topY = level.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz);
            if (topY <= minY) {
                e.setValue(remaining - 1);
                if (remaining - 1 <= 0) {
                    it.remove();
                }
                continue; // 取样点无效：续排或放弃
            }
            String topBlock = level.getBlockState(new BlockPos(bx, topY, bz))
                    .getBlock().getDescriptionId();
            if (!"block.minecraft.air".equals(topBlock)) {
                e.setValue(remaining - 1);
                if (remaining - 1 <= 0) {
                    it.remove();
                }
                continue; // 高度图过期：续排
            }
            int skyTop = level.getBrightness(LightLayer.SKY,
                    new BlockPos(bx, topY + 1, bz));
            if (skyTop > 0) {
                PROBE_LAST_SKY.put(key, skyTop);
                PROBE_EVER_LIT.add(key);
                it.remove();
                continue;
            }
            // 仍黑：还有帧则续等光队列，否则定稿为黑
            if (remaining - 1 > 0) {
                e.setValue(remaining - 1);
            } else {
                PROBE_LAST_SKY.put(key, 0);
                it.remove();
            }
        }
    }

    /**
     * 客户端区块应用探针（debug.chunkApplyLogging 开启时输出；关闭时零开销短路）：
     * per-pos apply 计数 + 区块光照采样（地表 sky / 高空 sky / 地下 block）+
     * 地表方块采样。同 pos 多次 apply（apply# > 1）或 skyTop=0 即「突变 / 黑块」嫌疑。
     * {@code source=light} 走 {@link #probeShadowLightState}，门控是 debug.lightVerify。
     */
    public static void probeChunkState(ChunkPos pos, ClientLevel level, String source) {
        probeChunkState(pos, level, source, true, null, false, -1L, -1L, -1L, false, true);
    }

    /**
     * 光照包落地后的复采样：保留最近一次全量区块应用上下文，但不增加 apply 计数。
     * 门控 {@code debug.lightVerify}（与区块 apply 日志分离：光包远密于全量柱）。
     */
    public static void probeShadowLightState(ChunkPos pos, ClientLevel level, TraceOrigin fullOrigin,
                                             boolean fullRenderOnly, long fullApplySequence, long fullApplyAgeMs,
                                             long lightQueueDelayMs, boolean fullAppliedAfterLightQueued,
                                             boolean chunkPresent) {
        probeChunkState(pos, level, "light", false, fullOrigin, fullRenderOnly, fullApplySequence,
                fullApplyAgeMs, lightQueueDelayMs, fullAppliedAfterLightQueued, chunkPresent);
    }

    private static void probeChunkState(ChunkPos pos, ClientLevel level, String source, boolean countAsApply,
                                        TraceOrigin fullOrigin, boolean fullRenderOnly, long fullApplySequence,
                                        long fullApplyAgeMs, long lightQueueDelayMs,
                                        boolean fullAppliedAfterLightQueued, boolean chunkPresent) {
        boolean lightProbe = !countAsApply;
        if (!DebugLogger.isEnabled(lightProbe ? LogType.LIGHT_VERIFY : LogType.CHUNK_APPLY)) {
            return;
        }
        if (level == null || pos == null) {
            return;
        }
        String origin = fullOrigin == null ? "unknown" : fullOrigin.logValue;
        String fullView = fullApplySequence < 0L ? "unknown" : fullRenderOnly ? "ovd" : "authoritative";
        if (!countAsApply && !chunkPresent) {
            DebugLogger.info(LogType.LIGHT_VERIFY,
                    "[CHUNK_PROBE] source=light pos=({},{}) fullOrigin={} fullView={} fullApplySeq={} fullApplyAgeMs={} lightQueueDelayMs={} fullAppliedAfterLightQueued={} chunkPresent=false",
                    pos.x, pos.z, origin, fullView, fullApplySequence, fullApplyAgeMs, lightQueueDelayMs,
                    fullAppliedAfterLightQueued);
            return;
        }
        int count = countAsApply
                ? APPLY_COUNT.merge(pos.toLong(), 1, Integer::sum)
                : APPLY_COUNT.getOrDefault(pos.toLong(), 0);
        int bx = (pos.x << 4) + 8;
        int bz = (pos.z << 4) + 8;
        int minY = io.github.limuqy.mc.hassium.compat.LevelHeightCompat.getMinBlockY(level);
        int maxY = minY + level.getHeight();
        int topY = level.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz);
        int sampleY = Math.max(topY, minY);
        int skyTop = level.getBrightness(LightLayer.SKY,
                new BlockPos(bx, Math.max(topY + 1, minY), bz));
        int skyAir = level.getBrightness(LightLayer.SKY,
                new BlockPos(bx, maxY - 2, bz));
        int blockLow = level.getBrightness(LightLayer.BLOCK,
                new BlockPos(bx, minY + 1, bz));
        String topBlock = level.getBlockState(new BlockPos(bx, sampleY, bz))
                .getBlock().getDescriptionId();
        int fixedY = Math.max(minY, Math.min(maxY - 1, 62));
        String fixedBlock = level.getBlockState(new BlockPos(bx, fixedY, bz))
                .getBlock().getDescriptionId();
        int originX = pos.x << 4;
        int originZ = pos.z << 4;
        int surfaceSection = SectionPos.blockToSectionCoord(Math.max(topY, minY));
        int midY = Math.max(minY, Math.min(maxY - 1, SectionPos.sectionToBlockCoord(surfaceSection) + 8));
        int skyMid = level.getBrightness(LightLayer.SKY, new BlockPos(bx, midY, bz));
        int skyW = level.getBrightness(LightLayer.SKY, new BlockPos(originX, midY, bz));
        int skyE = level.getBrightness(LightLayer.SKY, new BlockPos(originX + 15, midY, bz));
        int skyN = level.getBrightness(LightLayer.SKY, new BlockPos(bx, midY, originZ));
        int skyS = level.getBrightness(LightLayer.SKY, new BlockPos(bx, midY, originZ + 15));
        // 往返飞行黑块专项计数（flyroundtrip 场景断言锚）：只在「光包落地」探针上采。
        // 判据健全性：topY = 列内最高方块之上第一格（该列按定义无遮挡）→ 正确光必 >0，
        // 故 skyTop==0 恒为缺陷（空光掩码 / 整柱未算光）。再加「topY 处确实是空气」的
        // 自检：若高度图过期（topY 落在地面内）会采到 0，那不是黑块而是取样点错，必须剔除。
        // 即时读数是光队列 flush 前的旧值（§7.1 假阳性）：这里只记观测计数与「曾亮」，
        // 判定状态由下一帧 runProbeRecheck 的 post-apply 采样写入。
        if (lightProbe && topY > minY && "block.minecraft.air".equals(topBlock)) {
            if (skyTop > 0) {
                PROBE_EVER_LIT.add(pos.toLong());
            } else {
                darkLightProbeSamples.incrementAndGet();
            }
            scheduleProbeRecheck(pos);
        }
        if (!countAsApply) {
            DebugLogger.info(LogType.LIGHT_VERIFY,
                    "[CHUNK_PROBE] source=light pos=({},{}) apply#={} fullOrigin={} fullView={} fullApplySeq={} fullApplyAgeMs={} lightQueueDelayMs={} fullAppliedAfterLightQueued={} chunkPresent=true topY={} skyTop={} skyAir={} blockLow={} topBlock={} fixedY={} fixedBlock={} secY={} skyMid={} skyW={} skyE={} skyN={} skyS={}",
                    pos.x, pos.z, count, origin, fullView, fullApplySequence, fullApplyAgeMs, lightQueueDelayMs,
                    fullAppliedAfterLightQueued, topY, skyTop, skyAir, blockLow, topBlock, fixedY, fixedBlock,
                    surfaceSection, skyMid, skyW, skyE, skyN, skyS);
            return;
        }
        DebugLogger.info(LogType.CHUNK_APPLY,
                "[CHUNK_PROBE] source={} pos=({},{}) apply#={} topY={} skyTop={} skyAir={} blockLow={} topBlock={} fixedY={} fixedBlock={} secY={} skyMid={} skyW={} skyE={} skyN={} skyS={}",
                source, pos.x, pos.z, count, topY, skyTop, skyAir, blockLow, topBlock, fixedY, fixedBlock,
                surfaceSection, skyMid, skyW, skyE, skyN, skyS);
    }
    private static long getLightBytesPerChunk(ClientLevel level) {
        // level 参数保留以便未来按 sectionsCount 动态估算；当前与区块口径一致用常量
        return NetworkStats.ESTIMATED_LIGHT_BYTES;
    }
}