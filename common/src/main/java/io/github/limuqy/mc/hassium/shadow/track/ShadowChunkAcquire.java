package io.github.limuqy.mc.hassium.shadow.track;

import io.github.limuqy.mc.hassium.shadow.server.ShadowSeedServer;
import io.github.limuqy.mc.hassium.shadow.server.ShadowServerRegistry;
import io.github.limuqy.mc.hassium.shadow.server.SeedGenExecutor;
import io.github.limuqy.mc.hassium.shadow.light.ShadowLightCompute;

import io.github.limuqy.mc.hassium.protocol.ShadowPullClient;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.utils.DimensionKey;
import java.util.List;
import net.minecraft.world.level.ChunkPos;

/**
 * 统一取块入口（§3.2 ShadowPull：Compare+Pull / Generate+Validate）。
 * <p>
 * 选柱驱动（scheduleChunkLoad 悬置 / bootGrid / sweep / 权威 enter 提示）只调用本类。
 * 权威声明（{@code chunk_authority_s2c}）**不是**独立加载驱动：仅当影子端无该柱且
 * 非 pull 在途时触发选柱；tracking/sweep 与权威提示共用 {@code markPullInFlight} 互斥。
 * <p>
 * 裁决顺序：
 * <ol>
 *   <li>窗外（非 OVD 本地）→ 跳过</li>
 *   <li>已物化（injected 非占位）→ 跳过（交付由 Deliver / redeliver 负责）</li>
 *   <li>SeedGen 门控开且无本地基线 → 本地生成，不发 pull</li>
 *   <li>已在 pull 在途 → 跳过（两种选柱来源互斥）</li>
 *   <li>有本地基线 → {@code requestFull}（compare-pull）</li>
 *   <li>无基线 → {@code requestAuthoritativeFull}</li>
 * </ol>
 */
public final class ShadowChunkAcquire {

    /** 选柱路径的取块裁决（不直接发网络；由调用方批量 emit）。 */
    public enum SelectionAction {
        SKIP,
        SEEDGEN_LOCAL,
        PULL_COMPARE,
        PULL_AUTHORITATIVE
    }

    private ShadowChunkAcquire() {}

    /**
     * 权威 enter 提示选柱：影子端无柱且非在途 → 发 §3.2 pull；已物化/在途/SeedGen 跳过。
     *
     * @return 实际发出的 pull 动作；{@code null}=未发（已在途 / 已有柱 / 窗外等）
     */
    public static SelectionAction tryAcquireForAuthority(String dimension, int x, int z) {
        if (dimension == null) {
            return null;
        }
        ShadowSeedServer shadow = ShadowServerRegistry.getInstance().get();
        // 权威包语义 = 服务端 tracking 域内：视为窗内；窗外柱服务端本就 RANGE 拒
        SelectionAction action = decideSelection(shadow, dimension, x, z, true, true);
        if (action == SelectionAction.PULL_COMPARE || action == SelectionAction.PULL_AUTHORITATIVE) {
            ChunkPos pos = new ChunkPos(x, z);
            pullOne(dimension, pos, action == SelectionAction.PULL_COMPARE);
        }
        return action == SelectionAction.SKIP || action == SelectionAction.SEEDGEN_LOCAL
                ? null : action;
    }

    /**
     * 选柱取块裁决。
     * <p>
     * {@code inAuthorityWindow=false}：OVD 冻结 / 窗外 → 跳过（不 pull、不生成）。
     * 窗口判定由调用方传入，应为 {@code isChunkInRange(serverVD)}（阶段 A/C 统一 ServerVD）。
     *
     * @param markInFlight 置在途；返回 true 才允许调用方把该柱列入 pull 批次
     */
    public static SelectionAction decideSelection(
            ShadowSeedServer shadow,
            String dimension,
            int x,
            int z,
            boolean inAuthorityWindow,
            boolean markInFlight) {
        if (dimension == null) {
            return SelectionAction.SKIP;
        }
        if (!inAuthorityWindow) {
            // OVD 冻结：窗外不 pull、不生成；本地源 publish 由 Deliver/材料化桥单独处理
            return SelectionAction.SKIP;
        }
        if (shadow != null) {
            LevelChunkHolder holder = injectedNonPlaceholder(shadow, dimension, x, z);
            if (holder.present()) {
                return SelectionAction.SKIP;
            }
        }
        ChunkPos pos = new ChunkPos(x, z);
        // 失败冷却：RANGE 等拒绝后短冷却，避免风暴；冷却内两种选柱来源都跳过
        if (!io.github.limuqy.mc.hassium.protocol.ShadowPullClient
                .isPullRetryAllowed(dimension, pos)) {
            return SelectionAction.SKIP;
        }
        boolean hasBaseline = ShadowLightCompute.hasLocalPullBaseline(dimension, pos);
        if (!hasBaseline && preferLocalGeneration()) {
            return SelectionAction.SEEDGEN_LOCAL;
        }
        // 在途互斥：tracking/sweep 已发 pull 时权威提示不得再发；反向同理
        if (markInFlight && !markPullInFlight(dimension, pos)) {
            return SelectionAction.SKIP;
        }
        return hasBaseline ? SelectionAction.PULL_COMPARE : SelectionAction.PULL_AUTHORITATIVE;
    }

    /**
     * 单柱 §3.2 拉取：有基线 compare-pull，无基线权威 FULL。
     * 调用方须已 {@code markPullInFlight} 或接受本方法内置在途去重。
     */
    public static void pullOne(String dimension, ChunkPos pos, boolean hasBaseline) {
        if (dimension == null || pos == null) {
            return;
        }
        if (hasBaseline) {
            ShadowPullClient.requestFull(dimension, List.of(pos));
        } else {
            ShadowPullClient.requestAuthoritativeFull(dimension, List.of(pos));
        }
    }

    /** 批量发射 compare / 权威 FULL（与 {@link ShadowTrackingSession} 的 emitPullGroups 同语义）。 */
    public static void emitPullBatches(String dimension,
                                       List<ChunkPos> withBaseline,
                                       List<ChunkPos> withoutBaseline) {
        if (dimension == null) {
            return;
        }
        if (withBaseline != null && !withBaseline.isEmpty()) {
            DebugLogger.info(DebugLogger.LogType.NETWORK,
                    "[SHADOW_TRACK] compare-pull {} chunks (dimension={})",
                    withBaseline.size(), dimension);
            ShadowPullClient.requestFull(dimension, withBaseline);
        }
        if (withoutBaseline != null && !withoutBaseline.isEmpty()) {
            DebugLogger.info(DebugLogger.LogType.NETWORK,
                    "[SHADOW_TRACK] authoritative-full pull {} chunks (dimension={})",
                    withoutBaseline.size(), dimension);
            ShadowPullClient.requestAuthoritativeFull(dimension, withoutBaseline);
        }
    }

    private static boolean preferLocalGeneration() {
        return SeedGenExecutor.getInstance().isGenerationGateOpen();
    }

    private static boolean markPullInFlight(String dimension, ChunkPos pos) {
        return ShadowTrackingSession.getInstance()
                .markPullInFlightForAcquire(dimension, pos, System.currentTimeMillis());
    }

    private record LevelChunkHolder(boolean present) {}

    private static LevelChunkHolder injectedNonPlaceholder(ShadowSeedServer shadow,
                                                           String dimension, int x, int z) {
        boolean injected = shadow.injectedChunk(dimension, x, z) != null
                && !shadow.isPlaceholder(dimension, x, z);
        return new LevelChunkHolder(injected);
    }
}
