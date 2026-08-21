package io.github.limuqy.mc.hassium.network.seedgen;

import io.github.limuqy.mc.hassium.compat.ShadowChunkMapCompat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    @DisplayName("JoinBoost：有 chunk 在等时本帧不落地光包")
    void shouldApplyLightThisFramePrefersChunksDuringJoinBoost() {
        assertTrue(ShadowLightCompute.shouldApplyLightThisFrame(false, true, 0),
                "非 JoinBoost 保持 FIFO，光包可与 chunk 交错");
        assertTrue(ShadowLightCompute.shouldApplyLightThisFrame(false, false, 0));
        assertFalse(ShadowLightCompute.shouldApplyLightThisFrame(true, true, 0),
                "JoinBoost 且队列还有 chunk：光包 reoffer，不得 force 消化旧光");
        assertFalse(ShadowLightCompute.shouldApplyLightThisFrame(true, true, 1),
                "本帧已落地过 chunk 但队列仍有 chunk：继续优先 chunk");
        assertTrue(ShadowLightCompute.shouldApplyLightThisFrame(true, false, 1),
                "本帧 chunk 过完：剩余预算落地光");
        assertTrue(ShadowLightCompute.shouldApplyLightThisFrame(true, false, 0),
                "本帧没有 chunk：光包可用剩余预算");
    }

    @Test
    @DisplayName("JoinBoost：光桥本帧最多打包 1 条，且仅在 chunk 过完且 deadline 未到")
    void shouldPackLightMaskThisFrameLimitsJoinBoost() {
        assertTrue(ShadowLightCompute.shouldPackLightMaskThisFrame(false, true, 0, false, 0),
                "非 JoinBoost 不受 0～1 条限制");
        assertTrue(ShadowLightCompute.shouldPackLightMaskThisFrame(false, false, 0, true, 0),
                "非 JoinBoost：第一条不受 deadline 约束");
        assertFalse(ShadowLightCompute.shouldPackLightMaskThisFrame(false, false, 0, true, 1),
                "非 JoinBoost：已打包后才受 deadline 约束");
        assertFalse(ShadowLightCompute.shouldPackLightMaskThisFrame(true, true, 0, false, 0),
                "JoinBoost 且 chunk 在等：不打包光桥");
        assertFalse(ShadowLightCompute.shouldPackLightMaskThisFrame(true, false, 1, true, 0),
                "JoinBoost deadline 已到：不打包");
        assertTrue(ShadowLightCompute.shouldPackLightMaskThisFrame(true, false, 1, false, 0),
                "本帧已 apply 过 chunk 且 deadline 未到：允许 1 条");
        assertFalse(ShadowLightCompute.shouldPackLightMaskThisFrame(true, false, 1, false, 1),
                "JoinBoost 本帧最多 1 条");
        assertTrue(ShadowLightCompute.shouldPackLightMaskThisFrame(true, false, 0, false, 0),
                "本帧无 chunk：仍允许 1 条光桥补光");
    }

    @Test
    @DisplayName("影子回传 FIFO：后入队的优先级数值更大")
    void shadowApplyIsFifoNotDistance() {
        double first = ShadowLightCompute.fifoApplyPriority();
        double second = ShadowLightCompute.fifoApplyPriority();
        assertTrue(first < second, "入队序号必须单调递增，drainReady 按到达顺序 apply");
    }

    @Test
    @DisplayName("LIGHT：全量重算等 8 邻建层才 lightChunk，光桥/增量不等邻")
    void waitsForVanillaLightNeighborsBeforeLightChunk() {
        assertTrue(ShadowLightCompute.needsVanillaLightNeighborWait(true),
                "全量重算必须等 8 邻 initializeLight：先算柱播种要推进后算柱已建的层");
        assertFalse(ShadowLightCompute.needsVanillaLightNeighborWait(false),
                "光桥/增量/磁盘复用不等邻柱");
        assertFalse(ShadowLightCompute.canStartVanillaLightStage(4, 8, false),
                "未齐 8 邻不得进 lightChunk（屋檐跨边界传播依赖邻柱建层）");
        assertTrue(ShadowLightCompute.canStartVanillaLightStage(8, 8, false),
                "8 邻都已 initializeLight：进入 LIGHT");
        assertTrue(ShadowLightCompute.canStartVanillaLightStage(0, 0, false),
                "地图边缘没有邻柱：与 ChunkMap 邻柱不存在相同，立即 LIGHT");
        assertTrue(ShadowLightCompute.canStartVanillaLightStage(4, 8, true),
                "超时按视距边缘处理");
    }

    @Test
    @DisplayName("邻柱就绪谓词：holder INITIALIZE_LIGHT parent / 无 holder / 超时当边缘")
    void neighborReadyUsesHolderInitializeLightParent() {
        assertTrue(ShadowLightCompute.isVanillaLightNeighborReady(true, false),
                "holder 已有 INITIALIZE_LIGHT parent → 可 lightChunk");
        assertTrue(ShadowLightCompute.isVanillaLightNeighborReady(false, true),
                "本端 initializeLight 已完成（票尚未消化）→ 可 lightChunk");
        assertFalse(ShadowLightCompute.isVanillaLightNeighborReady(false, false),
                "无 holder 且未 initializeLight");
        assertTrue(ShadowLightCompute.isVanillaLightNeighborExpected(true, false, false));
        assertTrue(ShadowLightCompute.isVanillaLightNeighborExpected(false, true, false));
        assertTrue(ShadowLightCompute.isVanillaLightNeighborExpected(false, false, true),
                "视距内回退：票尚未落地");
        assertFalse(ShadowLightCompute.isVanillaLightNeighborExpected(false, false, false));
        assertTrue(ShadowLightCompute.canStartVanillaLightStage(0, 1, true),
                "超时当边缘：不等缺失 holder");
    }

    @Test
    @DisplayName("scheduleChunkLoad 短路：仅影子+注入表命中；SeedGen 允许 worldgen")
    void scheduleChunkLoadShortCircuitDoesNotWorldgenOnInject() {
        assertTrue(ShadowChunkMapCompat.shouldShortCircuitScheduleLoad(true, true));
        assertFalse(ShadowChunkMapCompat.shouldShortCircuitScheduleLoad(true, false),
                "未命中注入表：不得短路成注入柱，但也不得在注入路径 worldgen");
        assertFalse(ShadowChunkMapCompat.shouldShortCircuitScheduleLoad(false, true));
        assertTrue(ShadowChunkMapCompat.shouldPassthroughGenerationStep(true, false, false),
                "注入票路径：地形步透传");
        assertFalse(ShadowChunkMapCompat.shouldPassthroughGenerationStep(true, true, false),
                "SeedGen generateChunk 期间允许 worldgen");
        assertFalse(ShadowChunkMapCompat.shouldPassthroughGenerationStep(true, false, true),
                "EMPTY 不透传，走 scheduleChunkLoad");
    }

    @Test
    @DisplayName("FULL 取数：注入表未命中不得把票扩散 ProtoChunk 交给 ServerLevel.getChunk")
    void suppressesUninjectedFullGetChunkToAvoidProtoCast() {
        assertTrue(ShadowChunkMapCompat.shouldSuppressUninjectedFullGetChunk(true, false, false, true),
                "注入票路径 FULL：邻柱 Proto 必须对 getChunk 隐藏");
        assertFalse(ShadowChunkMapCompat.shouldSuppressUninjectedFullGetChunk(true, false, true, true),
                "注入表已命中：返回 LevelChunk");
        assertFalse(ShadowChunkMapCompat.shouldSuppressUninjectedFullGetChunk(true, true, false, true),
                "SeedGen worldgen 期间放行原版取数");
        assertFalse(ShadowChunkMapCompat.shouldSuppressUninjectedFullGetChunk(true, false, false, false),
                "EMPTY/LIGHT 等非 FULL 仍可走 ProtoChunk holder");
        assertFalse(ShadowChunkMapCompat.shouldSuppressUninjectedFullGetChunk(false, false, false, true));
    }

    @Test
    @DisplayName("UNKNOWN FULL 票集合加/卸对称")
    void injectTicketAddRemoveAreSymmetric() {
        java.util.Set<Long> keys = new java.util.HashSet<>();
        long a = net.minecraft.world.level.ChunkPos.asLong(3, -7);
        long b = net.minecraft.world.level.ChunkPos.asLong(4, -7);
        assertTrue(ShadowChunkMapCompat.rememberTicketKey(keys, a));
        assertFalse(ShadowChunkMapCompat.rememberTicketKey(keys, a), "同柱不重复加票");
        assertTrue(ShadowChunkMapCompat.rememberTicketKey(keys, b));
        assertEquals(2, keys.size());
        assertTrue(ShadowChunkMapCompat.forgetTicketKey(keys, a));
        assertFalse(ShadowChunkMapCompat.forgetTicketKey(keys, a), "还票后不再 remove");
        assertEquals(1, keys.size());
        assertTrue(ShadowChunkMapCompat.forgetTicketKey(keys, b));
        assertTrue(keys.isEmpty());
    }

    @Test
    @DisplayName("空 sky 层：源之上仍打包，源之下省略——仅未收敛光包路径仍用该谓词")
    void omitsEmptySkyBelowSourcesFromPacket() {
        assertTrue(ShadowLightCompute.shouldIncludeSkySectionInPacket(true, false, true),
                "非空层必须进包（(-13,3) sectionY=5 柱心已是 15）");
        assertTrue(ShadowLightCompute.shouldIncludeSkySectionInPacket(true, false, false));
        assertTrue(ShadowLightCompute.shouldIncludeSkySectionInPacket(true, true, true),
                "源之上空层仍要 empty 掩码，否则客户端按缺层向上继承成 15");
        assertFalse(ShadowLightCompute.shouldIncludeSkySectionInPacket(true, true, false),
                "源之下空层不得 emptySkyYMask（仅未收敛光包）");
        assertFalse(ShadowLightCompute.shouldIncludeSkySectionInPacket(false, false, true));
        assertFalse(ShadowLightCompute.shouldIncludeSkySectionInPacket(false, true, false));
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
    @DisplayName("直推注入：未请求过的柱计入应用区块，hash miss 已记账的不再重复")
    void accountsServerPushInjectOnce() {
        assertTrue(ShadowLightCompute.shouldAccountServerPushAsApplied(false));
        assertFalse(ShadowLightCompute.shouldAccountServerPushAsApplied(true),
                "hash miss 已 recordFullChunkRequests，注入不得再加分母");
    }
}
