package io.github.limuqy.mc.hassium.client;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.ChunkShapeCompat;
import io.github.limuqy.mc.hassium.mixin.client.LevelRendererAccessor;
import io.github.limuqy.mc.hassium.shadow.light.ShadowVanillaLightPipeline;
import io.github.limuqy.mc.hassium.shadow.server.ShadowServerRegistry;
import io.github.limuqy.mc.hassium.utils.DimensionKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;

/**
 * 遮挡图侵蚀自愈（26.x 客户端渲染域）。
 * <p>
 * <b>缺陷面</b>：vanilla 26.x 遮挡图（{@code SectionOcclusionGraph}）只能穿过
 * 「已编译 / EMPTY」的 section 传播（{@code SectionMesh.UNCOMPILED.facesCanSeeEachother}
 * 恒 false），而 {@code compileSections} 只调度图内（octree 可达）的 section——图的增长是
 * 「编译涟漪」。影子端交付（SeedGen compare 门两波、ready 队列距离优先）不是 vanilla 的
 * 连续批次，交付前沿会留下「已交付但未编译」的洞：洞后柱不能编译（不在图内）、不能入图
 * （邻柱 UNCOMPILED 阻断传播）→ 死锁。
 * 实测（2026-10-05，26.1.2 fabric，全新影子世界）：1553 交付柱中 188 根在渲染度量内
 * 数据/光照齐全却从未编译，天空视角呈现为视距圆内的成片虚空。
 * <p>
 * <b>自愈</b>：帧尾检测「渲染可达形状内 已交付 − 已编译 差额 ≥ 阈值 且编译停摆」→
 * {@code sectionOcclusionGraph.invalidate()}——全图以当前 mesh 状态重 BFS（已编译柱的
 * 可见性真实可用），图外脏柱重新入图、按常编译。状态读写全在渲染线程
 * （{@code ShadowLightCompute.drainReady} 帧尾），invalidate 只置一个标志位。
 * 交付域形状（buffer=2）<b>不改</b>：边缘 mesh 需要邻柱数据，vanilla 同款。
 */
public final class OcclusionGraphHealer {

    /** 触发自愈的「渲染可达内已交付未编译」柱数下限（低于此视为在途管线正常波动）。 */
    static final int GAP_THRESHOLD = 8;
    /** 编译停摆判定：超过该时长无任何 mesh 编译且差额仍在 → 死锁而非在途。 */
    static final long STALL_MS = 5_000L;
    /** 两次自愈最小间隔（一次全图重 BFS 后涟漪重铺需要数秒）。 */
    static final long HEAL_COOLDOWN_MS = 10_000L;
    /** 会话自愈上限：防病态循环（正常 1~2 次即收敛）。 */
    static final int MAX_HEALS_PER_SESSION = 12;
    /** 差额采样节流：metric 扫描每秒至多一次（集合量级 ~2k）。 */
    private static final long SCAN_INTERVAL_MS = 1_000L;
    /** 日志里最多列出的侵蚀柱样本数。 */
    private static final int SAMPLE_LIMIT = 8;

    /** key = DimensionKey 复合键。渲染线程写，扫描也在渲染线程——CHM 只为维度切换安全。 */
    private static final ConcurrentHashMap<Long, Boolean> APPLIED_COLUMNS = new ConcurrentHashMap<>();
    private static volatile long lastScanMs;
    private static volatile long lastHealMs;
    private static volatile int healsThisSession;

    private OcclusionGraphHealer() {}

    /** 权威整柱包落地成功（applyReadyChunk success 路径）。渲染线程调用。 */
    public static void onChunkApplied(String dimension, int chunkX, int chunkZ) {
        APPLIED_COLUMNS.put(DimensionKey.key(dimension, chunkX, chunkZ), Boolean.TRUE);
    }

    /** 维度切换清态（与 {@code ShadowLightCompute.onClientDimensionChanged} 同点调用）。 */
    public static void reset() {
        APPLIED_COLUMNS.clear();
        lastScanMs = 0L;
        lastHealMs = 0L;
        healsThisSession = 0;
    }

    /**
     * 纯判定（单测钉死）：差额达阈值、编译已启动但停摆、不在冷却、未超会话上限。
     */
    static boolean shouldHeal(int unmeshedGap, long nowMs, long lastCompileMs,
                              long lastHealMs, int healsSoFar) {
        if (unmeshedGap < GAP_THRESHOLD) {
            return false;
        }
        if (healsSoFar >= MAX_HEALS_PER_SESSION) {
            return false;
        }
        if (lastCompileMs == 0L) {
            return false; // 尚无任何编译：涟漪未启动，等首编
        }
        if (nowMs - lastCompileMs < STALL_MS) {
            return false; // 编译在推进：在途波动，不是死锁
        }
        if (lastHealMs != 0L && nowMs - lastHealMs < HEAL_COOLDOWN_MS) {
            return false;
        }
        return true;
    }

    /** 帧尾（drainReady，渲染线程）：死锁检测 + 一次性 invalidate 自愈。 */
    public static void onFrameTail() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null || mc.player == null || mc.options == null) {
            return;
        }
        if (ShadowServerRegistry.getInstance().get() == null) {
            return; // 影子端未激活：交付不经影子链路，无本缺陷面
        }
        long now = System.currentTimeMillis();
        if (now - lastScanMs < SCAN_INTERVAL_MS) {
            return;
        }
        lastScanMs = now;
        String dimension = ShadowVanillaLightPipeline.currentDimension();
        if (dimension == null) {
            return; // 维度未就绪（无 world key）：无从比对，跳过本轮
        }
        int renderRange = mc.options.getEffectiveRenderDistance();
        ChunkPos center = new ChunkPos(mc.player.getBlockX() >> 4, mc.player.getBlockZ() >> 4);
        int unmeshed = 0;
        List<long[]> stranded = new ArrayList<>();
        for (Map.Entry<Long, Boolean> e : APPLIED_COLUMNS.entrySet()) {
            long key = e.getKey();
            if (!dimension.equals(DimensionKey.dimensionOf(key))) {
                continue;
            }
            int x = DimensionKey.chunkXOf(key);
            int z = DimensionKey.chunkZOf(key);
            if (!ChunkShapeCompat.renderReachContains(center.x(), center.z(), renderRange, x, z)) {
                // 渲染可达形状外：vanilla 本就不渲染（buffer=2 交付域的边缘邻柱圈）
                continue;
            }
            if (!io.github.limuqy.mc.hassium.shadow.light.ShadowLightCompute.clientHasChunk(x, z)) {
                APPLIED_COLUMNS.remove(key); // 客户端已卸载：不得计为侵蚀（防差额虚高空转自愈）
                continue;
            }
            if (!ChunkMeshCompileLog.hasMeshedColumn(x, z)) {
                unmeshed++;
                if (stranded.size() < SAMPLE_LIMIT) {
                    stranded.add(new long[]{x, z});
                }
            }
        }
        if (!shouldHeal(unmeshed, now, ChunkMeshCompileLog.lastMeshCompileMs(),
                lastHealMs, healsThisSession)) {
            return;
        }
        int meshedBefore = ChunkMeshCompileLog.meshedColumnCount();
        lastHealMs = now;
        healsThisSession++;
        StringBuilder samples = new StringBuilder();
        for (int i = 0; i < stranded.size(); i++) {
            if (i > 0) {
                samples.append(' ');
            }
            samples.append('(').append(stranded.get(i)[0]).append(',').append(stranded.get(i)[1]).append(')');
        }
        ((LevelRendererAccessor) mc.levelRenderer)
                .hassium$getSectionOcclusionGraph().invalidate();
        Constants.LOG.info(
                "[OCC_HEAL] appliedInReach-unmeshed={} meshed={} heals={} sample-stranded={} (invalidate -> full graph re-BFS)",
                unmeshed, meshedBefore, healsThisSession, samples);
    }
}
