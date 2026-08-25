package io.github.limuqy.mc.hassium.network.seedgen;

import io.github.limuqy.mc.hassium.utils.DimensionKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E1 LightReadinessRegistry 状态机行为不变量（纯 Java，无 MC 依赖）：
 * 乱序 LIT 事件 → SURROUNDED 判定、收敛重算触发条件、clear/remove 幂等。
 *
 * <p>背景（work/LightDiag-TASK.md）：三轮查询式修复死于邻域判不出——本套测试锁死
 * 「事件序决定状态」的契约，任何退回查询式判定或漏接 LIT 事件源的重演在此暴露。
 */
class LightReadinessRegistryTest {

    private static final String DIM = "minecraft:overworld";

    /** 3×3 邻域：center(0,0) + 8 邻。 */
    private long center;
    private final List<Long> ring = new ArrayList<>(8);

    private final List<Long> triggered = new ArrayList<>();

    @BeforeEach
    void setUp() {
        LightReadinessRegistry.clear();
        triggered.clear();
        center = DimensionKey.key(DIM, 0, 0);
        ring.clear();
        for (int[] d : new int[][]{{-1, -1}, {-1, 0}, {-1, 1}, {0, -1},
                {0, 1}, {1, -1}, {1, 0}, {1, 1}}) {
            ring.add(DimensionKey.key(DIM, d[0], d[1]));
        }
        LightReadinessRegistry.setRelightTrigger(triggered::add);
    }

    @AfterEach
    void tearDown() {
        LightReadinessRegistry.setRelightTrigger(null);
        LightReadinessRegistry.clear();
    }

    private void ingestAll() {
        LightReadinessRegistry.markIngested(center);
        for (long key : ring) {
            LightReadinessRegistry.markIngested(key);
        }
    }

    @Test
    @DisplayName("乱序 LIT：8 邻未齐前无 SURROUNDED，末根邻柱 LIT 后 center 升级")
    void outOfOrderLitEvents_surroundedOnlyAfterRingComplete() {
        ingestAll();
        // 逆序点亮 ring 的 7 根 + center，留 1 根不点
        for (int i = 6; i >= 0; i--) {
            LightReadinessRegistry.onLightComputed(ring.get(i), false, true, 100 + i);
        }
        LightReadinessRegistry.onLightComputed(center, false, true, 200);
        assertEquals(LightReadinessRegistry.Phase.LIT,
                LightReadinessRegistry.phaseOf(center));
        for (int i = 0; i <= 6; i++) {
            assertEquals(LightReadinessRegistry.Phase.LIT,
                    LightReadinessRegistry.phaseOf(ring.get(i)),
                    "邻柱自身也不得提前 SURROUNDED");
        }
        // 末根 LIT：center 的 3×3 齐备 → 升级；环列外侧邻柱不存在 → 保持 LIT
        LightReadinessRegistry.onLightComputed(ring.get(7), false, true, 300);
        assertEquals(LightReadinessRegistry.Phase.SURROUNDED,
                LightReadinessRegistry.phaseOf(center));
        for (long key : ring) {
            assertEquals(LightReadinessRegistry.Phase.LIT,
                    LightReadinessRegistry.phaseOf(key),
                    "外侧无邻柱数据，环列不得凭空升级");
        }
    }

    @Test
    @DisplayName("重算触发：存在晚于本柱末次计算跃迁变 LIT 的邻柱 → 触发且仅触发一次")
    void relightTriggered_whenNeighborLitAfterCompute_exactlyOnce() {
        ingestAll();
        // center 先算（t=100），邻柱随后陆续变 LIT（t>100）
        LightReadinessRegistry.onLightComputed(center, false, false, 100);
        for (int i = 0; i < 7; i++) {
            LightReadinessRegistry.onLightComputed(ring.get(i), false, true, 150 + i);
        }
        assertTrue(triggered.isEmpty(), "8 邻未齐不得触发");
        LightReadinessRegistry.onLightComputed(ring.get(7), false, true, 400);
        assertEquals(List.of(center), triggered, "SURROUNDED 时按「邻晚于己」触发一次");
        assertTrue(LightReadinessRegistry.isPendingConverge(center));
        assertFalse(LightReadinessRegistry.isSettled(center), "待收敛不得落盘光");
        // 后续邻列事件重复评估：pendingConverge 去重，再无新触发
        LightReadinessRegistry.onLightComputed(ring.get(0), false, true, 500);
        assertEquals(List.of(center), triggered, "pendingConverge 去重");
    }

    @Test
    @DisplayName("内部柱零重算：全部邻柱先于本柱计算变 LIT → 不触发；重算完成不再乒乓")
    void noRelight_whenAllNeighborsLitBeforeCompute_andNoPingPong() {
        ingestAll();
        for (int i = 0; i < 8; i++) {
            LightReadinessRegistry.onLightComputed(ring.get(i), false, true, 10 + i);
        }
        LightReadinessRegistry.onLightComputed(center, false, false, 100); // 末根：自身事件补评
        assertEquals(LightReadinessRegistry.Phase.SURROUNDED,
                LightReadinessRegistry.phaseOf(center));
        assertTrue(triggered.isEmpty(), "邻序天然满足的柱零重算");
        LightReadinessRegistry.onLightComputed(center, false, true, 900);
        assertTrue(triggered.isEmpty(), "同柱重算完成不产生新触发");
        for (int i = 0; i < 8; i++) {
            LightReadinessRegistry.onLightComputed(ring.get(i), false, true, 800 - i);
        }
        assertTrue(triggered.isEmpty(), "邻列重复 COMPUTED（非新跃迁）不触发");
    }

    @Test
    @DisplayName("存档复用光（REUSE_CACHE）：8 邻 settled 后才校验重算；完成才出队")
    void reusedStorageLight_triggersVerificationRelight_untilRecomputeCompletes() {
        ingestAll();
        // center 光来自存档复用（reused=true），邻柱会话内后算
        LightReadinessRegistry.onLightComputed(center, true, false, 100);
        assertEquals(0L, LightReadinessRegistry.lastComputeAtOf(center),
                "复用光不记会话内计算时刻");
        for (int i = 0; i < 8; i++) {
            LightReadinessRegistry.onLightComputed(ring.get(i), false, true, 150 + i);
        }
        assertEquals(List.of(center), triggered, "8 邻光层齐后必须校验重算一次");
        assertFalse(LightReadinessRegistry.isSettled(center));
        // 整柱重算完成（COMPUTED 事件）→ 出队 + 刷新 lastCompute → settled
        LightReadinessRegistry.onLightComputed(center, false, true, 900);
        assertFalse(LightReadinessRegistry.isPendingConverge(center), "重算完成才出队");
        assertTrue(LightReadinessRegistry.isSettled(center));
        assertEquals(900L, LightReadinessRegistry.lastComputeAtOf(center));
        // 出队后邻列重复 COMPUTED（非新跃迁）不再触发
        LightReadinessRegistry.onLightComputed(ring.get(3), false, true, 800);
        assertEquals(List.of(center), triggered);
    }

    @Test
    @DisplayName("存档复用：邻柱仅 LIT 未 settled 时不得清掉屋檐光")
    void reusedStorageLight_waitsForNeighborSettledLayers() {
        ingestAll();
        LightReadinessRegistry.onLightComputed(center, true, false, 100);
        for (int i = 0; i < 8; i++) {
            LightReadinessRegistry.onLightComputed(ring.get(i), false, false, 150 + i);
        }
        assertEquals(LightReadinessRegistry.Phase.SURROUNDED,
                LightReadinessRegistry.phaseOf(center));
        assertTrue(triggered.isEmpty(), "邻柱尚未 converged=true，不得清复用屋檐光");
        assertFalse(LightReadinessRegistry.areNeighborsFullySettled(center));
        LightReadinessRegistry.onLightComputed(ring.get(0), false, true, 400);
        assertTrue(triggered.isEmpty(), "尚未 8 邻 settled");
        for (int i = 1; i < 8; i++) {
            LightReadinessRegistry.onLightComputed(ring.get(i), false, true, 400 + i);
        }
        assertEquals(List.of(center), triggered, "8 邻光层齐才校验重算");
        assertTrue(LightReadinessRegistry.shouldTriggerRelight(0L, true, 407L));
        assertFalse(LightReadinessRegistry.shouldTriggerRelight(0L, false, 407L),
                "邻柱光层未齐：保持复用");
    }

    @Test
    @DisplayName("markIngested 回退：REPLACE 重注入清光 → LIT/SURROUNDED 回退并清时间戳")
    void reinjectDemotesLitColumn_andBlocksStaleSurrounded() {
        ingestAll();
        for (int i = 0; i < 8; i++) {
            LightReadinessRegistry.onLightComputed(ring.get(i), false, true, 10 + i);
        }
        LightReadinessRegistry.onLightComputed(center, false, false, 100);
        assertEquals(LightReadinessRegistry.Phase.SURROUNDED,
                LightReadinessRegistry.phaseOf(center));
        // 重注入：回退 INGESTED、时间戳清零
        LightReadinessRegistry.markIngested(center);
        assertEquals(LightReadinessRegistry.Phase.INGESTED,
                LightReadinessRegistry.phaseOf(center));
        assertEquals(0L, LightReadinessRegistry.litAtOf(center));
        assertEquals(0L, LightReadinessRegistry.lastComputeAtOf(center));
        assertFalse(LightReadinessRegistry.isSettled(center), "回退列禁止落盘光");
        // 重新 LIT 后按新时刻评估；降级后重 LIT = 新跃迁
        LightReadinessRegistry.onLightComputed(center, false, true, 500);
        assertEquals(500L, LightReadinessRegistry.litAtOf(center),
                "降级后重新 LIT = 新跃迁，litAt 取新时刻");
        assertEquals(LightReadinessRegistry.Phase.SURROUNDED,
                LightReadinessRegistry.phaseOf(center));
        assertTrue(triggered.isEmpty(), "邻柱跃迁均早于本柱重算 → 不触发");
        // 邻柱重注入→更晚重新 LIT（新跃迁）→ 已 SURROUNDED 的 center 必须重评并再触发
        LightReadinessRegistry.markIngested(ring.get(0));
        LightReadinessRegistry.onLightComputed(ring.get(0), false, true, 600);
        assertTrue(triggered.contains(center), "邻列新跃迁晚于己方计算 → 再次触发");
    }

    @Test
    @DisplayName("abandonConverge：目标不可重算时放弃登记，允许后续事件重新触发")
    void abandonConverge_allowsRetrigger() {
        ingestAll();
        LightReadinessRegistry.onLightComputed(center, false, false, 100);
        for (int i = 0; i < 8; i++) {
            LightReadinessRegistry.onLightComputed(ring.get(i), false, true, 150 + i);
        }
        assertEquals(List.of(center), triggered);
        LightReadinessRegistry.abandonConverge(center);
        assertFalse(LightReadinessRegistry.isPendingConverge(center));
        // 邻柱新跃迁（更晚）→ 重新触发
        LightReadinessRegistry.markIngested(ring.get(0));
        LightReadinessRegistry.onLightComputed(ring.get(0), false, true, 700);
        assertEquals(List.of(center, center), triggered, "abandon 后可重新触发");
    }

    @Test
    @DisplayName("remove/clear 幂等：清空后查询面归零、新事件照常工作")
    void clearAndRemove_idempotent() {
        ingestAll();
        LightReadinessRegistry.onLightComputed(center, false, false, 100);
        LightReadinessRegistry.remove(ring.get(0));
        assertNull(LightReadinessRegistry.phaseOf(ring.get(0)));
        LightReadinessRegistry.clear();
        LightReadinessRegistry.clear(); // 幂等
        assertEquals(0, LightReadinessRegistry.size());
        assertNull(LightReadinessRegistry.phaseOf(center));
        assertFalse(LightReadinessRegistry.isSettled(center));
        assertFalse(LightReadinessRegistry.isPendingConverge(center));
        // 清空后新会话语义照常
        ingestAll();
        for (int i = 0; i < 8; i++) {
            LightReadinessRegistry.onLightComputed(ring.get(i), false, true, 20 + i);
        }
        LightReadinessRegistry.onLightComputed(center, false, true, 300);
        assertEquals(LightReadinessRegistry.Phase.SURROUNDED,
                LightReadinessRegistry.phaseOf(center));
    }

    @Test
    @DisplayName("未注册列的 LIT 事件隐式建簿（直推路径可能跳过显式 INGESTED）")
    void litEventWithoutIngest_createsEntry() {
        LightReadinessRegistry.onLightComputed(center, false, false, 100);
        assertEquals(LightReadinessRegistry.Phase.LIT,
                LightReadinessRegistry.phaseOf(center));
        assertEquals(100L, LightReadinessRegistry.lastComputeAtOf(center));
    }

    @Test
    @DisplayName("跨维同坐标不串扰（DimensionKey 复合键隔离）")
    void dimensionsAreIsolated() {
        long netherCenter = DimensionKey.key("minecraft:the_nether", 0, 0);
        LightReadinessRegistry.markIngested(center);
        LightReadinessRegistry.onLightComputed(netherCenter, false, false, 100);
        assertEquals(LightReadinessRegistry.Phase.INGESTED,
                LightReadinessRegistry.phaseOf(center),
                "下界柱 LIT 不得推进主世界同坐标柱");
    }
    @Test
    @DisplayName("方案D·park 迟到 true：邻柱 lightChunk 迟到完成(true) 触发已 SURROUNDED 列重评")
    void lateSettledNeighbor_triggersResurroundedColumnRelight() {
        ingestAll();
        // 复现 LightFinal 时序：center 先算(false)，ring 全部 LIT（含 park 柱 ring0）
        LightReadinessRegistry.onLightComputed(center, false, false, 100);
        for (int i = 0; i < 8; i++) {
            boolean converged = i != 0; // ring0 = park 未算柱，false 完成
            LightReadinessRegistry.onLightComputed(ring.get(i), false, converged, 150 + i);
        }
        assertEquals(List.of(center), triggered, "跃迁波：SURROUNDED 首次触发");
        // center 的收敛重算以 false 落地（ring0 尚未算）→ 出队、lastCompute=300
        LightReadinessRegistry.onLightComputed(center, false, false, 300);
        assertFalse(LightReadinessRegistry.isPendingConverge(center));
        int afterFirstWave = triggered.size();
        // ring0（park 柱）的 lightChunk 迟到执行且 converged=true：非新跃迁，
        // 但 settled 证据(600) > center.lastCompute(300) → 必须再触发（根因修复点）
        LightReadinessRegistry.onLightComputed(ring.get(0), false, true, 600);
        assertTrue(triggered.size() > afterFirstWave,
                "settled 证据波必须触发已 SURROUNDED 列再重算（根因修复点）");
        assertTrue(LightReadinessRegistry.settledAtOf(ring.get(0)) == 600L);
    }

    @Test
    @DisplayName("方案D·false 不传播：邻柱 converged=false 完成不触发已 SURROUNDED 列")
    void falseCompletion_doesNotPropagate() {
        ingestAll();
        LightReadinessRegistry.onLightComputed(center, false, true, 100);
        for (int i = 0; i < 7; i++) {
            LightReadinessRegistry.onLightComputed(ring.get(i), false, true, 150 + i);
        }
        int baseline = triggered.size();
        // ring7 以 false 完成（跃迁事件）：center 升级 SURROUNDED 瞬间按 ring0..6
        // 既有 settled 证据(151..156 > 100)触发一次——正确行为（center 计算于 100，
        // 早于多数邻柱终值）；ring7 自身不产生证据。
        LightReadinessRegistry.onLightComputed(ring.get(7), false, false, 400);
        assertEquals(baseline + 1, triggered.size(),
                "升级瞬间按既有 settled 证据触发一次");
        int afterUpgrade = triggered.size();
        // 同柱重复 false 完成：不得新增证据、不得再触发
        LightReadinessRegistry.onLightComputed(ring.get(7), false, false, 450);
        assertEquals(afterUpgrade, triggered.size(),
                "false 完成不得作为终值证据传播/再触发");
        assertEquals(0L, LightReadinessRegistry.settledAtOf(ring.get(7)));
        // 同柱后续以 true 完成才补记证据
        LightReadinessRegistry.onLightComputed(ring.get(7), false, true, 500);
        assertEquals(500L, LightReadinessRegistry.settledAtOf(ring.get(7)));
    }

    @Test
    @DisplayName("方案D·true 波终止：A/B 互为邻柱的 settled 证据不互相顶高、有限收敛")
    void settledEvidenceWave_terminates() {
        long a = DimensionKey.key(DIM, 0, 0);
        long b = DimensionKey.key(DIM, 1, 0);
        // 构造 A、B 各自的完整 3×3 环境
        for (int dx = -1; dx <= 2; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                LightReadinessRegistry.markIngested(DimensionKey.key(DIM, dx, dz));
            }
        }
        // A 先 true；B 后 true（晚于 A 的计算）→ B 的证据触发 A 重算一次
        LightReadinessRegistry.onLightComputed(a, false, true, 100);
        LightReadinessRegistry.onLightComputed(b, false, true, 200);
        int triggersA = (int) triggered.stream().filter(k -> k == a).count();
        int triggersB = (int) triggered.stream().filter(k -> k == b).count();
        assertTrue(triggersA <= 1 && triggersB <= 1,
                "每柱对每邻至多响应一次，A/B 不乒乓: A=" + triggersA + " B=" + triggersB);
        // A 重算完成(true, t=300) 后不得把 settledAt 从 100 顶到 300，否则 B 会再触发
        LightReadinessRegistry.onLightComputed(a, false, true, 300);
        assertEquals(100L, LightReadinessRegistry.settledAtOf(a),
                "重算完成不得顶高已落下的终值证据");
        assertTrue((int) triggered.stream().filter(k -> k == b).count() <= triggersB,
                "true 波必终止，不得交替无限触发");
    }

    @Test
    @DisplayName("方案D·内部场：全场 SURROUNDED 后重算完成不得乒乓邻柱")
    void interiorField_relightCompletion_doesNotPingPongNeighbors() {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                LightReadinessRegistry.markIngested(DimensionKey.key(DIM, dx, dz));
            }
        }
        long t = 10L;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                LightReadinessRegistry.onLightComputed(
                        DimensionKey.key(DIM, dx, dz), false, true, t++);
            }
        }
        long a = DimensionKey.key(DIM, 0, 0);
        long b = DimensionKey.key(DIM, 1, 0);
        assertEquals(LightReadinessRegistry.Phase.SURROUNDED,
                LightReadinessRegistry.phaseOf(a));
        assertEquals(LightReadinessRegistry.Phase.SURROUNDED,
                LightReadinessRegistry.phaseOf(b));
        long aSettled = LightReadinessRegistry.settledAtOf(a);
        triggered.clear();
        LightReadinessRegistry.onLightComputed(a, false, true, 10_000L);
        assertEquals(aSettled, LightReadinessRegistry.settledAtOf(a),
                "内部场重算完成不得顶高 settledAt");
        assertEquals(0, triggered.stream().filter(k -> k == b).count(),
                "A 重算完成不得再触发已 settled 的邻柱 B");
    }
}
