package io.github.limuqy.mc.hassium.concurrent;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主线程 apply 回调按玩家距离优先的回归测试。
 * <p>
 * 覆盖进服首波曾因默认 (0,0) 导致远处块先 apply 的问题；
 * 以及层序：权威 &gt; 未知任务 &gt; 环带。
 */
class MainThreadDispatcherPriorityTest {

#if MC_VER >= MC_1_21_2
    // 同 ChunkDistancePriorityTest：1.21.2+ ChunkPos.<clinit> 触碰 BuiltInRegistries 需先 bootstrap。
    @BeforeAll
    static void bootstrapRegistries() {
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();
    }
#endif

    @BeforeEach
    void setUp() {
        MainThreadDispatcher.clearClient(false);
        MainThreadDispatcher.clearPlayerPosition();
    }

    @AfterEach
    void tearDown() {
        MainThreadDispatcher.clearClient(false);
        MainThreadDispatcher.clearPlayerPosition();
    }

    @Test
    @DisplayName("玩家坐标已知时近处 chunk 先于远处 apply")
    void nearChunkAppliesBeforeFarChunk() {
        // 玩家在 chunk (6, -37) 中心附近（与实测日志出生点一致量级）
        MainThreadDispatcher.updatePlayerPosition(6 * 16 + 8.0, -37 * 16 + 8.0);

        List<String> applied = new ArrayList<>();
        MainThreadDispatcher.execute(() -> applied.add("far"), new ChunkPos(0, 0));
        MainThreadDispatcher.execute(() -> applied.add("near"), new ChunkPos(6, -37));
        MainThreadDispatcher.execute(() -> applied.add("mid"), new ChunkPos(6, -30));
        MainThreadDispatcher.flushClient(10);
        assertEquals(0, MainThreadDispatcher.getClientQueueSize());
        assertEquals(List.of("near", "mid", "far"), applied);
    }

    @Test
    @DisplayName("入队后玩家移动不改写已冻结的优先级键")
    void priorityFrozenAtEnqueueIgnoresLaterPlayerMove() {
        // 入队时玩家在 (0,0)：A 近、B 远 → 冻结 A < B
        MainThreadDispatcher.updatePlayerPosition(0 * 16 + 8.0, 0 * 16 + 8.0);

        List<String> applied = new ArrayList<>();
        MainThreadDispatcher.execute(() -> applied.add("A"), new ChunkPos(0, 0));
        MainThreadDispatcher.execute(() -> applied.add("B"), new ChunkPos(10, 0));

        // flush 前玩家移动到 B 附近：已入队 key 不重算，仍 A→B
        MainThreadDispatcher.updatePlayerPosition(10 * 16 + 8.0, 0 * 16 + 8.0);
        MainThreadDispatcher.flushClient(10);

        assertEquals(0, MainThreadDispatcher.getClientQueueSize());
        assertEquals(List.of("A", "B"), applied);
    }

    @Test
    @DisplayName("玩家坐标未知时不按 (0,0) 伪装近处优先")
    void unknownPlayerPosDoesNotPreferOrigin() {
        // 不调用 updatePlayerPosition：两权威任务均为 AUTHORITATIVE base+0，顺序不伪装原点
        AtomicInteger order = new AtomicInteger();
        int[] originOrder = {-1};
        int[] remoteOrder = {-1};

        MainThreadDispatcher.execute(
                () -> originOrder[0] = order.getAndIncrement(),
                new ChunkPos(0, 0));
        MainThreadDispatcher.execute(
                () -> remoteOrder[0] = order.getAndIncrement(),
                new ChunkPos(6, -37));

        MainThreadDispatcher.flushClient(10);
        // 两者同层同键时，顺序由队列实现决定，但都不应因原点伪装而强制 remote 垫底。
        assertTrue(originOrder[0] >= 0 && remoteOrder[0] >= 0);
        assertEquals(0, MainThreadDispatcher.getClientQueueSize());
    }

    @Test
    @DisplayName("坐标未知时权威仍为 AUTHORITATIVE 层、环带仍为 RENDER_ONLY 层（层序不破）")
    void unknownPosKeepsTierOrderAuthoritativeAboveRenderOnly() {
        double auth = MainThreadDispatcher.authoritativePriority(new ChunkPos(0, 0));
        double ovd = MainThreadDispatcher.renderOnlyPriority(new ChunkPos(3, -7));
        double unknown = MainThreadDispatcher.PRIORITY_UNKNOWN;

        assertEquals(ChunkDistancePriority.ofUnknownDistance(ChunkDistancePriority.Tier.AUTHORITATIVE), auth);
        assertEquals(ChunkDistancePriority.ofUnknownDistance(ChunkDistancePriority.Tier.RENDER_ONLY), ovd);
        assertTrue(auth < unknown, "auth < unknown");
        assertTrue(unknown < ovd, "unknown < ovd");
        // 任意坐标未知权威均同键（不伪装原点）
        assertEquals(auth, MainThreadDispatcher.authoritativePriority(new ChunkPos(400, 400)));
    }

    @Test
    @DisplayName("坐标已知时 renderOnly 仍低于同位置权威")
    void knownPosRenderOnlyAfterAuthoritative() {
        MainThreadDispatcher.updatePlayerPosition(48.0, -112.0);
        ChunkPos pos = new ChunkPos(3, -7);
        assertTrue(MainThreadDispatcher.authoritativePriority(pos)
                < MainThreadDispatcher.renderOnlyPriority(pos));
    }

    @Test
    @DisplayName("主线程调度：权威 > 无锚点未知任务 > 环带")
    void dispatchOrderAuthThenUnknownThenRenderOnly() {
        MainThreadDispatcher.updatePlayerPosition(0, 0);

        List<String> applied = new ArrayList<>();
        // 先入队环带（用显式 renderOnly 键）与未知（无锚点 execute）
        MainThreadDispatcher.execute(
                () -> applied.add("ovd"),
                MainThreadDispatcher.renderOnlyPriority(new ChunkPos(1, 0)),
                TaskCategory.SAFE_TO_CANCEL);
        MainThreadDispatcher.execute(() -> applied.add("unknown")); // PRIORITY_UNKNOWN
        // 极远权威仍应先于未知
        MainThreadDispatcher.execute(
                () -> applied.add("authFar"),
                new ChunkPos(100, 100));
        MainThreadDispatcher.execute(
                () -> applied.add("authNear"),
                new ChunkPos(0, 0));

        MainThreadDispatcher.flushClient(10);
        assertEquals(List.of("authNear", "authFar", "unknown", "ovd"), applied);
    }

    @Test
    @DisplayName("同位置重复入队：新任务取代旧任务，只执行一次")
    void samePosSupersedesOldApply() {
        MainThreadDispatcher.updatePlayerPosition(6 * 16 + 8.0, -37 * 16 + 8.0);

        List<String> applied = new ArrayList<>();
        ChunkPos pos = new ChunkPos(6, -37);
        MainThreadDispatcher.execute(() -> applied.add("old-data"), pos);
        MainThreadDispatcher.execute(() -> applied.add("new-data"), pos);
        MainThreadDispatcher.flushClient(10);

        assertEquals(0, MainThreadDispatcher.getClientQueueSize());
        // 旧任务被取代：只有新数据落地，无重复 apply
        assertEquals(List.of("new-data"), applied);
    }

    @Test
    @DisplayName("同位置不同语义（apply vs BE）互不取代")
    void samePosDifferentOpDoNotSupersede() {
        MainThreadDispatcher.updatePlayerPosition(6 * 16 + 8.0, -37 * 16 + 8.0);

        List<String> applied = new ArrayList<>();
        ChunkPos pos = new ChunkPos(6, -37);
        MainThreadDispatcher.execute(() -> applied.add("chunk"), pos);
        MainThreadDispatcher.execute(() -> applied.add("be"),
                MainThreadDispatcher.chunkKey(pos, MainThreadDispatcher.OP_BLOCK_ENTITY));
        MainThreadDispatcher.flushClient(10);

        assertEquals(0, MainThreadDispatcher.getClientQueueSize());
        assertEquals(List.of("chunk", "be"), applied);
    }

    @Test
    @DisplayName("网络请求任务不取代同位置 apply 任务")
    void requestDoesNotSupersedeApply() {
        MainThreadDispatcher.updatePlayerPosition(6 * 16 + 8.0, -37 * 16 + 8.0);

        List<String> applied = new ArrayList<>();
        ChunkPos pos = new ChunkPos(6, -37);
        MainThreadDispatcher.execute(() -> applied.add("apply"), pos);
        MainThreadDispatcher.execute(() -> applied.add("request"),
                MainThreadDispatcher.chunkKey(pos, MainThreadDispatcher.OP_REQUEST));
        MainThreadDispatcher.flushClient(10);

        assertEquals(0, MainThreadDispatcher.getClientQueueSize());
        assertEquals(List.of("apply", "request"), applied);
    }

    @Test
    @DisplayName("同位置三连入队：只保留最新，且最新优先级按最新玩家位置计算")
    void tripleEnqueueKeepsNewestWithFreshPriority() {
        // 玩家在 (0,0) 入队 A（近键）；移到远处再入队 A'（远键）——A' 取代 A
        MainThreadDispatcher.updatePlayerPosition(0, 0);
        ChunkPos pos = new ChunkPos(0, 0);

        List<String> applied = new ArrayList<>();
        MainThreadDispatcher.execute(() -> applied.add("first"), pos);
        MainThreadDispatcher.execute(() -> applied.add("second"), pos);
        // 玩家移到 (10,0)，再入队第三个
        MainThreadDispatcher.updatePlayerPosition(10 * 16 + 8.0, 0);
        MainThreadDispatcher.execute(() -> applied.add("third"), pos);

        MainThreadDispatcher.flushClient(10);
        assertEquals(0, MainThreadDispatcher.getClientQueueSize());
        assertEquals(List.of("third"), applied);
    }
}
