package io.github.limuqy.mc.hassium.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 光照光环的几何口径（L0：无 MC 实例，纯谓词）。
 * <p>
 * 锁两件事：
 * <ol>
 *   <li><b>计算域 = 权威形状的切比雪夫膨胀</b>（{@code containsDilated(VD, halo)}），
 *       其唯一职责是让权威柱的 3×3 全部在场（原版 {@code ChunkStatus.LIGHT} range=1 依赖）。</li>
 *   <li><b>不得退回 {@code contains(VD + halo)} 近似</b>：那是「形状环」，在形状切角处会漏掉
 *       每窗 8~20 个权威柱的邻柱（本测用反例钉住）。</li>
 * </ol>
 * 另含：膨胀形状的最大切比雪夫半径必须 ≤ 服务端签发上限
 * （{@code VD + ShadowPullRadii.AUTHORITY_MARGIN}），否则外环被 RANGE 拒。
 */
class ChunkShapeDilationTest {

    private static final int[] VIEW_DISTANCES = {10, 16, 20};

    /**
     * 注册表 bootstrap 前置：{@link ChunkShapeCompat#contains} 在 1.20.1 走
     * {@code ChunkMap.isChunkInRange} → {@code ChunkMap.<clinit>} → {@code ChunkStatus.<clinit>}
     * → {@code BuiltInRegistries}。未 bootstrap 就触碰会让这些类在测试 JVM 内**永久不可用**
     * （{@code NoClassDefFoundError: Could not initialize class BuiltInRegistries}），
     * 连锁污染同 JVM 内其它依赖注册表的测试（如 {@code NetworkOptimizationTest}）。
     * 同一模式见 {@code protocol/NetworkOptimizationTest}。
     */
    @BeforeAll
    static void bootstrapRegistries() {
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();
    }

    /** 权威柱中「3×3 戳出 {@code predicate} 域」的柱数（用 {@code naive=true} 走形状环近似）。 */
    private static int authoritativeWithLeakedNeighbor(int vd, boolean naive) {
        int box = vd + 3;
        int dirty = 0;
        for (int x = -box; x <= box; x++) {
            for (int z = -box; z <= box; z++) {
                if (!ChunkShapeCompat.contains(0, 0, vd, x, z)) {
                    continue;
                }
                boolean leaked = false;
                for (int dx = -1; dx <= 1 && !leaked; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dz == 0) {
                            continue;
                        }
                        boolean inDomain = naive
                                ? ChunkShapeCompat.contains(0, 0, vd + 1, x + dx, z + dz)
                                : ChunkShapeCompat.containsDilated(0, 0, vd, 1, x + dx, z + dz);
                        if (!inDomain) {
                            leaked = true;
                            break;
                        }
                    }
                }
                if (leaked) {
                    dirty++;
                }
            }
        }
        return dirty;
    }

    @Test
    @DisplayName("dilate=0 与 contains 逐点等价（跨版本公式同族，防漂移）")
    void zeroDilationEqualsContains() {
        for (int vd : VIEW_DISTANCES) {
            int box = vd + 3;
            for (int x = -box; x <= box; x++) {
                for (int z = -box; z <= box; z++) {
                    assertEquals(ChunkShapeCompat.contains(0, 0, vd, x, z),
                            ChunkShapeCompat.containsDilated(0, 0, vd, 0, x, z),
                            "dilate=0 必须等于原版形状判定 @(" + x + "," + z + ") vd=" + vd);
                }
            }
        }
    }

    @Test
    @DisplayName("光环 1 环：每个权威柱的 3×3 都落在计算域内（零缺口）")
    void dilationCoversAuthoritativeNeighborhood() {
        for (int vd : VIEW_DISTANCES) {
            assertEquals(0, authoritativeWithLeakedNeighbor(vd, false),
                    "vd=" + vd + " 权威柱仍有 3×3 缺口 → 光环半径不够");
        }
    }

    @Test
    @DisplayName("反例钉死：contains(VD+1) 近似会漏（每窗 8~20 个权威柱缺邻）")
    void shapeRingApproximationLeaksNeighbors() {
        for (int vd : VIEW_DISTANCES) {
            assertTrue(authoritativeWithLeakedNeighbor(vd, true) > 0,
                    "vd=" + vd + "：contains(VD+1) 应当漏邻（否则本测失去意义，需重新核对原版公式）");
        }
    }

    @Test
    @DisplayName("膨胀形状的枚举盒：覆盖自身，且不超服务端签发上限")
    void dilationBoxFitsServerIssuedRange() {
        for (int vd : VIEW_DISTANCES) {
            int box = ChunkShapeCompat.dilatedBoundingRadius(vd, 1);
            int maxCheb = 0;
            for (int x = -box - 2; x <= box + 2; x++) {
                for (int z = -box - 2; z <= box + 2; z++) {
                    if (ChunkShapeCompat.containsDilated(0, 0, vd, 1, x, z)) {
                        maxCheb = Math.max(maxCheb, Math.max(Math.abs(x), Math.abs(z)));
                    }
                }
            }
            assertTrue(maxCheb <= box,
                    "枚举盒必须覆盖膨胀形状：maxCheb=" + maxCheb + " box=" + box);
            int limit = vd + io.github.limuqy.mc.hassium.protocol.ShadowPullRadii.AUTHORITY_MARGIN;
            assertTrue(maxCheb <= limit,
                    "膨胀形状超出服务端签发上限：maxCheb=" + maxCheb + " 上限=" + limit);
        }
    }

    @Test
    @DisplayName("dilate 单调：外扩半径越大集合越大（无空洞）")
    void dilationIsMonotone() {
        int vd = 10;
        int box = vd + 5;
        for (int x = -box; x <= box; x++) {
            for (int z = -box; z <= box; z++) {
                if (ChunkShapeCompat.containsDilated(0, 0, vd, 1, x, z)) {
                    assertTrue(ChunkShapeCompat.containsDilated(0, 0, vd, 2, x, z),
                            "dilate=1 ⊄ dilate=2 @(" + x + "," + z + ")");
                }
            }
        }
    }
}
