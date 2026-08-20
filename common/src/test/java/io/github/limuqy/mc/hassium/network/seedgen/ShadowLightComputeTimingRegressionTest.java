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
    @DisplayName("光桥打包：须已有影子区块包，且跳过屏障/欠光暂缓/邻柱重播")
    void lightMaskBudgetSkipsInflightAndDeferred() {
        assertTrue(ShadowLightCompute.canDrainLightMaskThisFrame(false, false, true, false));
        assertFalse(ShadowLightCompute.canDrainLightMaskThisFrame(false, false, false, false),
                "加载屏 blocks-only 柱不得套光包");
        assertFalse(ShadowLightCompute.canDrainLightMaskThisFrame(true, false, true, false),
                "屏障中的柱不得占光桥预算");
        assertFalse(ShadowLightCompute.canDrainLightMaskThisFrame(false, true, true, false),
                "欠光暂缓覆盖的柱不得占光桥预算");
        assertFalse(ShadowLightCompute.canDrainLightMaskThisFrame(false, false, true, true),
                "邻柱重播尚未排完不得快照中间态空层");
    }

    @Test
    @DisplayName("影子回传 FIFO：后入队的优先级数值更大")
    void shadowApplyIsFifoNotDistance() {
        double first = ShadowLightCompute.fifoApplyPriority();
        double second = ShadowLightCompute.fifoApplyPriority();
        assertTrue(first < second, "入队序号必须单调递增，drainReady 按到达顺序 apply");
    }

    @Test
    @DisplayName("跨柱屋檐：只重播已注入且不在屏障中的邻柱光源，不清光")
    void respreadsNeighborSourcesWithoutFullRelight() {
        assertTrue(ShadowLightCompute.shouldRespreadNeighborSources(true, false),
                "已注入邻柱必须再 propagate，才能把天空光推进后到的屋檐柱");
        assertFalse(ShadowLightCompute.shouldRespreadNeighborSources(false, false),
                "邻柱尚未注入：其随后的 lightChunk 会 propagate");
        assertFalse(ShadowLightCompute.shouldRespreadNeighborSources(true, true),
                "邻柱正在屏障中：lightChunk(false) 结束时会 propagate，避免重复");
        assertTrue(ShadowLightCompute.shouldRespreadSelfSources(true),
                "REUSE 跳过了本柱 propagate，邻柱后到时本柱光源也要再推一次");
        assertFalse(ShadowLightCompute.shouldRespreadSelfSources(false),
                "RECOMPUTE 刚跑过本柱 propagate，不必立刻再投");
    }
}
