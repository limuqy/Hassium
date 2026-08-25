package io.github.limuqy.mc.hassium.network.seedgen;

import io.github.limuqy.mc.hassium.compat.ShadowChunkMapCompat;
import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import io.github.limuqy.mc.hassium.network.ClientChunkHandler;
import io.github.limuqy.mc.hassium.network.ShadowChunkRole;
import io.github.limuqy.mc.hassium.utils.DimensionKey;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.ChunkPos;
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
    @DisplayName("欠光暂缓覆盖只针对已落地影子全量包；加载屏占位柱仍推首包")
    void defersIncompleteOverwriteWhenClientAlreadyHasChunk() {
        assertTrue(ShadowLightCompute.shouldDeferIncompleteClientOverwrite(true, false));
        assertFalse(ShadowLightCompute.shouldDeferIncompleteClientOverwrite(false, false),
                "尚未影子落地（含加载屏占位）：欠光首包仍可推，避免着火区 R1 空洞");
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
        assertFalse(ShadowLightCompute.diskHitNeedRelight(false, true),
                "盘上已亮且光不脏：R2 复用，不得因引擎层尚未安装而重算屋檐光");
        assertTrue(ShadowLightCompute.diskHitNeedRelight(true, true),
                "光脏位仍强制续算");
        assertTrue(ShadowLightCompute.diskHitNeedRelight(false, false));
    }

    @Test
    @DisplayName("可见柱回传亮光即落盘；Halo / 欠光包不写 isLightOn")
    void persistsPublishedVisibleLightWithoutSurroundedGate() {
        assertTrue(ShadowLightCompute.shouldPersistPublishedLight(false, true),
                "R1 已回传的屋檐光必须进 type 126");
        assertFalse(ShadowLightCompute.shouldPersistPublishedLight(true, true),
                "Halo 只提供边界，剥光落盘");
        assertFalse(ShadowLightCompute.shouldPersistPublishedLight(false, false),
                "欠光/超时首包不得把空层写成 isLightOn");
        assertTrue(ShadowLightCompute.shouldRestoreLightCorrectOnExit(false, false, true),
                "退出时层已齐的可见柱补 isLightCorrect，不看 SURROUNDED");
        assertFalse(ShadowLightCompute.shouldRestoreLightCorrectOnExit(true, false, true));
        assertFalse(ShadowLightCompute.shouldRestoreLightCorrectOnExit(false, true, true));
        assertFalse(ShadowLightCompute.shouldRestoreLightCorrectOnExit(false, false, false));
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
    @DisplayName("scheduleChunkLoad 短路：仅注入表；未命中禁止原版读 126")
    void scheduleChunkLoadShortCircuitDoesNotWorldgenOnInject() {
        assertTrue(ShadowChunkMapCompat.shouldShortCircuitScheduleLoad(true, true));
        assertFalse(ShadowChunkMapCompat.shouldShortCircuitScheduleLoad(true, false),
                "未命中注入表：不得短路成注入柱，但也不得在注入路径 worldgen");
        assertFalse(ShadowChunkMapCompat.shouldShortCircuitScheduleLoad(false, true));
        assertTrue(ShadowChunkMapCompat.shouldBypassVanillaRegionRead(true, false),
                "注入路径：未命中注入表也不得走 IOWorker 读 type 126");
        assertFalse(ShadowChunkMapCompat.shouldBypassVanillaRegionRead(true, true),
                "SeedGen generateChunk 期间允许原版空槽生成");
        assertFalse(ShadowChunkMapCompat.shouldBypassVanillaRegionRead(false, false));
        assertTrue(ShadowChunkMapCompat.shouldSkipVanillaChunkParse(true, false),
                "影子非 126 槽禁止原版当 zlib 解析");
        assertFalse(ShadowChunkMapCompat.shouldSkipVanillaChunkParse(true, true),
                "type 126 由 MixinRegionFile 解压");
        assertFalse(ShadowChunkMapCompat.shouldSkipVanillaChunkParse(false, false),
                "专用服非影子存档仍可混有原版槽");
        assertTrue(ShadowChunkMapCompat.shouldPassthroughGenerationStep(true, false, false),
                "注入票路径：地形步透传");
        assertFalse(ShadowChunkMapCompat.shouldPassthroughGenerationStep(true, true, false),
                "SeedGen generateChunk 期间允许 worldgen");
        assertFalse(ShadowChunkMapCompat.shouldPassthroughGenerationStep(true, false, true),
                "EMPTY 不透传，走 scheduleChunkLoad");
        assertFalse(ShadowChunkMapCompat.shouldPassthroughGenerationStep(true, false, false, true),
                "INITIALIZE_LIGHT/LIGHT 必须执行原版任务");
    }

    @Test
    @DisplayName("剥光注入柱 persisted=FULL 且 isLightCorrect=false：native FULL 不能当已算光")
    void strippedInjectNativeFullDoesNotMeanLighted() {
        assertFalse(ShadowLightCompute.nativeFullMeansLighted(true, false),
                "FULL + 欠光：vanilla 只 load，不会 generate LIGHT");
        assertTrue(ShadowLightCompute.nativeFullMeansLighted(true, true),
                "磁盘已亮柱：native FULL 才等于已算光");
        assertFalse(ShadowLightCompute.nativeFullMeansLighted(false, false));
    }

    @Test
    @DisplayName("Halo 算光后不得发布到 ClientLevel")
    void haloMustNotPublishToClient() {
        assertFalse(ShadowLightCompute.shouldPublishToClient(true));
        assertTrue(ShadowLightCompute.shouldPublishToClient(false));
        assertTrue(ShadowVanillaLightPipeline.isRenderable(ShadowChunkRole.VISIBLE));
        assertFalse(ShadowVanillaLightPipeline.isRenderable(ShadowChunkRole.HALO));
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
    @DisplayName("UNKNOWN FULL 票集合加卸与 Halo 首包邻域对称")
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
        assertTrue(ShadowLightCompute.shouldSkipUnchangedRepush(true, false, false, true),
                "Bloom 直推没有 remoteHash，已落地且光完备不得再整柱 REPLACE");
        assertTrue(ShadowLightCompute.shouldSkipUnchangedRepush(true, true, true, true));
        assertFalse(ShadowLightCompute.shouldSkipUnchangedRepush(true, true, false, true),
                "hash 不一致：方块变了，必须注入");
        assertFalse(ShadowLightCompute.shouldSkipUnchangedRepush(false, false, false, true));
        assertFalse(ShadowLightCompute.shouldSkipUnchangedRepush(true, false, false, false),
                "B1 欠光直推也算已落地——光没齐时跳过会让屏障 waiter 被丢弃后无人补光");
        assertFalse(ShadowLightCompute.shouldSkipUnchangedRepush(true, true, true, false),
                "光未完备：hash 命中也不得跳过，必须走屏障重算");
    }

    @Test
    @DisplayName("直推注入：未请求过的柱计入应用区块，hash miss 已记账的不再重复")
    void accountsServerPushInjectOnce() {
        assertTrue(ShadowLightCompute.shouldAccountServerPushAsApplied(false));
        assertFalse(ShadowLightCompute.shouldAccountServerPushAsApplied(true),
                "hash miss 已 recordFullChunkRequests，注入不得再加分母");
    }

    @Test
    @DisplayName("可见柱 native 入站：inject 当时记全量+光照重算，同柱只记一次")
    void visibleNetworkIngressAccountsOnceBeforeNativeLight() {
        NetworkStats.reset();
        NetworkStats.setEnabled(true);
        try {
            ChunkPos pos = new ChunkPos(7, -3);
            ShadowLightCompute.accountVisibleNetworkIngress(DimensionKey.OVERWORLD, pos);
            ShadowLightCompute.accountVisibleNetworkIngress(DimensionKey.OVERWORLD, pos);

            assertEquals(1, NetworkStats.getMetrics().getFullChunkRequestCount());
            assertEquals(1, NetworkStats.getMetrics().getNewFullChunkRequestCount());
            assertEquals(NetworkStats.ESTIMATED_CHUNK_BYTES,
                    NetworkStats.getMetrics().getFullChunkRequestBytes());
            assertEquals(1, NetworkStats.getMetrics().getLightCacheMissCount());
            assertEquals(NetworkStats.ESTIMATED_LIGHT_BYTES,
                    NetworkStats.getMetrics().getLightCacheMissBytes());
        } finally {
            ShadowLightCompute.onDisconnect();
            NetworkStats.reset();
            NetworkStats.setEnabled(false);
        }
    }

    @Test
    @DisplayName("hash 全命中按柱去重：磁盘后再走内存不得记两次")
    void cacheFullHitAccountsOncePerColumn() {
        NetworkStats.reset();
        NetworkStats.setEnabled(true);
        try {
            ChunkPos pos = new ChunkPos(4, 9);
            assertTrue(ShadowLightCompute.accountCacheFullHit(DimensionKey.OVERWORLD, pos));
            assertFalse(ShadowLightCompute.accountCacheFullHit(DimensionKey.OVERWORLD, pos),
                    "同一柱磁盘命中后再收到 hash 会走内存命中，全命中不得翻倍");
            assertEquals(1, NetworkStats.getMetrics().getCacheHitFullChunkCount());
            assertEquals(NetworkStats.ESTIMATED_CHUNK_BYTES,
                    NetworkStats.getMetrics().getCacheHitFullChunkBytes());
        } finally {
            ShadowLightCompute.onDisconnect();
            NetworkStats.reset();
            NetworkStats.setEnabled(false);
        }
    }

    @Test
    @DisplayName("hash 命中无论是否已落地都必须进 HIT 回执列表")
    void hashHitAlwaysCollectsReceiptEvenIfNotYetApplied() {
        List<ChunkPos> hits = new ArrayList<>();
        ChunkPos pos = new ChunkPos(2, -4);
        ShadowLightCompute.collectHitReceipt(hits, pos);
        ShadowLightCompute.collectHitReceipt(null, pos);
        ShadowLightCompute.collectHitReceipt(hits, null);
        assertEquals(List.of(pos), hits);
    }

    @Test
    @DisplayName("chunkLock 可重入：inject 持锁内再 capture/hash 不得自死锁")
    void chunkLockIsReentrant() {
        ChunkPos pos = new ChunkPos(0, -12);
        int[] n = {0};
        ShadowLightCompute.withChunkLock(pos, () -> {
            ShadowLightCompute.withChunkLock(pos, () -> n[0]++);
            n[0]++;
        });
        assertEquals(2, n[0]);
    }

    @Test
    @DisplayName("落地兜底：内存复用记缓存命中，直推记全量；同柱不与 inject 记账叠加")
    void authoritativeLandedAccountsByOriginWithoutDoubleCount() {
        NetworkStats.reset();
        NetworkStats.setEnabled(true);
        try {
            ChunkPos memory = new ChunkPos(1, 2);
            ChunkPos push = new ChunkPos(3, 4);
            ShadowLightCompute.accountAuthoritativeLanded(DimensionKey.OVERWORLD, memory,
                    ClientChunkHandler.TraceOrigin.SHADOW_MEMORY_CACHE);
            ShadowLightCompute.accountAuthoritativeLanded(DimensionKey.OVERWORLD, memory,
                    ClientChunkHandler.TraceOrigin.SHADOW_MEMORY_CACHE);
            ShadowLightCompute.accountVisibleNetworkIngress(DimensionKey.OVERWORLD, push);
            ShadowLightCompute.accountAuthoritativeLanded(DimensionKey.OVERWORLD, push,
                    ClientChunkHandler.TraceOrigin.SERVER_PUSH);

            assertEquals(1, NetworkStats.getMetrics().getCacheHitFullChunkCount());
            assertEquals(1, NetworkStats.getMetrics().getFullChunkRequestCount());
            assertEquals(2, NetworkStats.getMetrics().getClientAppliedChunkCount());
        } finally {
            ShadowLightCompute.onDisconnect();
            NetworkStats.reset();
            NetworkStats.setEnabled(false);
        }
    }

    @Test
    @DisplayName("hash miss 先 tryRequestMiss 不得挡住直推分母记账")
    void hashMissDebounceDoesNotSuppressIngressAccounting() {
        NetworkStats.reset();
        NetworkStats.setEnabled(true);
        try {
            ChunkPos pos = new ChunkPos(8, 8);
            assertTrue(ShadowLightCompute.tryRequestMiss(DimensionKey.OVERWORLD, pos));
            assertFalse(ShadowLightCompute.isAuthoritativeIngressInFlight(
                    DimensionKey.key(DimensionKey.OVERWORLD, pos.x, pos.z)));
            ShadowLightCompute.accountVisibleNetworkIngress(DimensionKey.OVERWORLD, pos);
            assertEquals(1, NetworkStats.getMetrics().getFullChunkRequestCount());
            assertEquals(1, NetworkStats.getMetrics().getClientAppliedChunkCount());
        } finally {
            ShadowLightCompute.onDisconnect();
            NetworkStats.reset();
            NetworkStats.setEnabled(false);
        }
    }

    @Test
    @DisplayName("影子未就绪时入站不得永久 failShadowServer")
    void missingShadowServerDoesNotFailEngine() {
        assertFalse(ShadowVanillaLightPipeline.shouldFailShadowWhenServerUnavailable());
    }

    @Test
    @DisplayName("光照按柱去重；LIGHT_ONLY 邻柱补光不进重算")
    void lightColumnAccountsOnceAndSkipsLightOnly() {
        NetworkStats.reset();
        NetworkStats.setEnabled(true);
        try {
            ChunkPos pos = new ChunkPos(-2, 8);
            assertTrue(ShadowLightCompute.accountLightColumn(DimensionKey.OVERWORLD, pos, false));
            assertFalse(ShadowLightCompute.accountLightColumn(DimensionKey.OVERWORLD, pos, false));
            assertFalse(ShadowLightCompute.accountLightColumn(DimensionKey.OVERWORLD, pos, true),
                    "同柱已记重算后不得再记复用");
            assertEquals(1, NetworkStats.getMetrics().getLightCacheMissCount());
            assertEquals(0, NetworkStats.getMetrics().getLightReuseShadowCount());
            assertFalse(ShadowLightCompute.shouldAccountLightBarrierMetric(true),
                    "邻柱 LIGHT_ONLY 不是区块级光照缓存事件");
            assertTrue(ShadowLightCompute.shouldAccountLightBarrierMetric(false));
        } finally {
            ShadowLightCompute.onDisconnect();
            NetworkStats.reset();
            NetworkStats.setEnabled(false);
        }
    }

    @Test
    @DisplayName("林火 LightDelta / 分段增量不得作废整柱首包；只作废 LIGHT_ONLY")
    void lightDeltaDoesNotSupersedeFullChunkBarrier() {
        assertFalse(ShadowLightCompute.isSupersededByNewerWork(true, false, true),
                "整柱 PENDING/GENERATED/DELTA 在途时 LightDelta 只排队，finishLight 后再 relight");
        assertFalse(ShadowLightCompute.isSupersededByNewerWork(true, false, false),
                "section delta 不算 hasBlockWork：岩浆/着火方块蔓延不得取消首包");
        assertTrue(ShadowLightCompute.isSupersededByNewerWork(true, true, false),
                "整柱重推（pending/generated）才取消在途整柱");
        assertTrue(ShadowLightCompute.isSupersededByNewerWork(true, true, true));
        assertTrue(ShadowLightCompute.isSupersededByNewerWork(false, false, true),
                "后续 LightDelta 取消过时的 LIGHT_ONLY");
        assertTrue(ShadowLightCompute.isSupersededByNewerWork(false, true, false));
        assertFalse(ShadowLightCompute.isSupersededByNewerWork(false, false, false));
    }

    @Test
    @DisplayName("首包：本柱 lightChunk 成功即可发布，邻柱林火不得挡住")
    void firstPacketPublishesWhenOwnLightChunkOk() {
        assertTrue(ShadowLightCompute.firstPacketLightReady(true, false, false),
                "邻柱 LIGHT_ONLY 在途时仍发首包");
        assertFalse(ShadowLightCompute.firstPacketLightReady(false, true, false),
                "本柱 lightChunk 失败不得发完备首包");
        assertTrue(ShadowLightCompute.firstPacketLightReady(true, true, false));
        assertFalse(ShadowLightCompute.firstPacketLightReady(true, false, true),
                "LIGHT_ONLY 仍要邻域 idle");
        assertTrue(ShadowLightCompute.firstPacketLightReady(true, true, true));
    }

    @Test
    @DisplayName("有未发布首包 waiter 时林火不得占满光管道")
    void lightOnlyYieldsToWaitingFirstPackets() {
        assertFalse(ShadowLightCompute.canStartLightOnlyWhileFirstPacketsWait(true));
        assertTrue(ShadowLightCompute.canStartLightOnlyWhileFirstPacketsWait(false));
    }

    @Test
    @DisplayName("隔离预览：lightChunk 已提交仍推；仅影子已落地或屏障结束才丢")
    void isolatedPreviewStillPushesWhileLightChunkInFlight() {
        assertTrue(ShadowLightCompute.shouldPushIsolatedPreview(false, false, true),
                "屏障仍在（含已提交 lightChunk）且未影子落地：推预览填空洞");
        assertFalse(ShadowLightCompute.shouldPushIsolatedPreview(true, false, true),
                "已落地影子全量包：丢预览，防盖暗");
        assertFalse(ShadowLightCompute.shouldPushIsolatedPreview(false, true, true),
                "已被整柱重推作废");
        assertFalse(ShadowLightCompute.shouldPushIsolatedPreview(false, false, false),
                "屏障已结束：等收敛包");
    }

    @Test
    @DisplayName("整柱屏障在途时不启动 LightDelta，等首包完成触发")
    void defersLightDeltaUntilFullChunkBarrierFinishes() {
        assertFalse(ShadowLightCompute.canStartLightDeltaNow(true),
                "同柱 inflight/waiting/pending 时 LightDelta 不得开第二条屏障");
        assertTrue(ShadowLightCompute.canStartLightDeltaNow(false),
                "整柱已推完：LightDelta 由 finishLight 的 pump 触发");
    }
}
