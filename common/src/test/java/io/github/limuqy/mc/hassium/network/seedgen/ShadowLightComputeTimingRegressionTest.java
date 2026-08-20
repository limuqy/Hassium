package io.github.limuqy.mc.hassium.network.seedgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登录早期影子端尚不可创建的回归契约。
 *
 * <p>消费者取得 {@code null} 影子端时，只有 registry 已进入不可恢复失败态才可放弃
 * 权威剥光区块；例如 {@code gameDir} 尚未设置的暂态必须保留，待 ready 后再消费。
 */
class ShadowLightComputeTimingRegressionTest {

    @Test
    @DisplayName("影子端可恢复未就绪时保留权威剥光区块，失败后才允许清理")
    void retainsAuthoritativeChunkWhileShadowServerIsTemporarilyUnready() {
        boolean isShadowServerFailed = false; // handshake done, gameDir still null

        assertTrue(ShadowLightCompute.shouldRetainPendingWhenServerUnavailable(isShadowServerFailed),
                "getOrCreate()==null 且 registry 未失败是可恢复状态，pending 不得清空");
        assertTrue(ShadowLightCompute.shouldPumpAfterShadowServerReady(true),
                "影子端 ready 后必须重新 pump 保留的权威工作");
        assertFalse(ShadowLightCompute.shouldPumpAfterShadowServerReady(false),
                "没有保留工作时 ready 事件不得创建重复消费任务");
        assertFalse(ShadowLightCompute.shouldRetainPendingWhenServerUnavailable(true),
                "只有明确不可恢复失败才允许消费者放弃 pending");
    }

    @Test
    @DisplayName("lightChunk 第二参：仅引擎内已有光才跳过 propagate")
    void lightChunkHasExistingLightOnlyWhenReusingEngineLight() {
        assertTrue(ShadowLightCompute.lightChunkHasExistingLight(true));
        assertFalse(ShadowLightCompute.lightChunkHasExistingLight(false));
    }

    @Test
    @DisplayName("客户端已有柱时欠光首包必须暂缓，防先亮后暗")
    void defersIncompleteOverwriteWhenClientAlreadyHasChunk() {
        assertTrue(ShadowLightCompute.shouldDeferIncompleteClientOverwrite(true, false));
        assertFalse(ShadowLightCompute.shouldDeferIncompleteClientOverwrite(false, false),
                "客户端尚无柱：欠光首包仍可推（先暗后亮）");
        assertFalse(ShadowLightCompute.shouldDeferIncompleteClientOverwrite(true, true));
        assertFalse(ShadowLightCompute.shouldDeferIncompleteClientOverwrite(false, true));
    }

    @Test
    @DisplayName("磁盘命中续算：只看 isLightCorrect（NBT isLightOn），不另管脏表")
    void diskNeedRelightFollowsIsLightCorrectOnly() {
        assertFalse(ShadowLightCompute.diskNeedRelight(true),
                "isLightCorrect → lightReuse，lightChunk(true) 跳过 propagate");
        assertTrue(ShadowLightCompute.diskNeedRelight(false),
                "isLightOn=false → lightChunk(false) 播种+传播续算");
    }

    @Test
    @DisplayName("光桥打包：须已有影子区块包，且跳过屏障/欠光暂缓/等待 LIGHT")
    void lightMaskBudgetSkipsInflightAndDeferred() {
        assertTrue(ShadowLightCompute.canDrainLightMaskThisFrame(false, false, true, false));
        assertFalse(ShadowLightCompute.canDrainLightMaskThisFrame(false, false, false, false),
                "加载屏 blocks-only 柱不得套光包");
        assertFalse(ShadowLightCompute.canDrainLightMaskThisFrame(true, false, true, false),
                "屏障中的柱不得占光桥预算");
        assertFalse(ShadowLightCompute.canDrainLightMaskThisFrame(false, true, true, false),
                "欠光暂缓覆盖的柱不得占光桥预算");
        assertFalse(ShadowLightCompute.canDrainLightMaskThisFrame(false, false, true, true),
                "尚未 lightChunk（等邻柱 INITIALIZE_LIGHT）不得快照");
    }

    @Test
    @DisplayName("影子回传 FIFO：后入队的优先级数值更大")
    void shadowApplyIsFifoNotDistance() {
        double first = ShadowLightCompute.fifoApplyPriority();
        double second = ShadowLightCompute.fifoApplyPriority();
        assertTrue(first < second, "入队序号必须单调递增，drainReady 按到达顺序 apply");
    }

    @Test
    @DisplayName("LIGHT range=1：8 邻完成 INITIALIZE_LIGHT 后才 lightChunk")
    void waitsForVanillaLightNeighborsBeforeLightChunk() {
        assertTrue(ShadowLightCompute.needsVanillaLightNeighborWait(true));
        assertFalse(ShadowLightCompute.needsVanillaLightNeighborWait(false),
                "光桥/增量/磁盘复用不等邻柱");
        assertFalse(ShadowLightCompute.canStartVanillaLightStage(4, 8, false),
                "视距内邻柱未建层：不得 lightChunk");
        assertTrue(ShadowLightCompute.canStartVanillaLightStage(8, 8, false),
                "8 邻都已 initializeLight：进入 LIGHT");
        assertTrue(ShadowLightCompute.canStartVanillaLightStage(0, 0, false),
                "地图边缘没有邻柱：与 ChunkMap 邻柱不存在相同，立即 LIGHT");
        assertTrue(ShadowLightCompute.canStartVanillaLightStage(4, 8, true),
                "超时按视距边缘处理");
    }

    @Test
    @DisplayName("客户端已落地影子全量包时 hash 命中不得整柱重推")
    void skipsRedundantFullPushWhenClientAlreadyHasShadowPacket() {
        assertTrue(ShadowLightCompute.shouldSkipRedundantFullPush(true),
                "走近触发的 hash 命中再推全量会把 emptySkyYMask 盖掉光桥屋檐光");
        assertFalse(ShadowLightCompute.shouldSkipRedundantFullPush(false),
                "加载屏 blocks-only / 尚未影子落地：仍要首次带光回传");
        assertTrue(ShadowLightCompute.shouldSkipUnchangedRepush(true, false, false),
                "Bloom 直推没有 remoteHash，已落地不得再整柱 REPLACE");
        assertTrue(ShadowLightCompute.shouldSkipUnchangedRepush(true, true, true));
        assertFalse(ShadowLightCompute.shouldSkipUnchangedRepush(true, true, false),
                "hash 不一致：方块变了，必须注入");
        assertFalse(ShadowLightCompute.shouldSkipUnchangedRepush(false, false, false));
    }

    @Test
    @DisplayName("空 sky 层：源之上仍打包，源之下省略以免 emptySkyYMask 钉死屋檐")
    void omitsEmptySkyBelowSourcesFromPacket() {
        assertTrue(ShadowLightCompute.shouldIncludeSkySectionInPacket(true, false, true),
                "非空层必须进包（(-13,3) sectionY=5 柱心已是 15）");
        assertTrue(ShadowLightCompute.shouldIncludeSkySectionInPacket(true, false, false));
        assertTrue(ShadowLightCompute.shouldIncludeSkySectionInPacket(true, true, true),
                "源之上空层仍要 empty 掩码，否则客户端按缺层向上继承成 15");
        assertFalse(ShadowLightCompute.shouldIncludeSkySectionInPacket(true, true, false),
                "源之下空层不得 emptySkyYMask");
        assertFalse(ShadowLightCompute.shouldIncludeSkySectionInPacket(false, false, true));
        assertFalse(ShadowLightCompute.shouldIncludeSkySectionInPacket(false, true, false));
    }
}
