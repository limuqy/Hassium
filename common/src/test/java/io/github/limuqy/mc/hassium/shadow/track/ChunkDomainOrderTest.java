package io.github.limuqy.mc.hassium.shadow.track;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S4 波前「3×3 域分组排序」与 S3 光环半径钳位（L0：无 MC 实例）。
 * <p>
 * 分组排序是纯函数，可完整锁定语义；运行时触发（200ms 驱动 / 预算 / 移动重播种）需实机。
 */
class ChunkDomainOrderTest {

    private static List<ChunkPos> sorted(int cx, int cz, ChunkPos... pts) {
        List<ChunkPos> list = new ArrayList<>(List.of(pts));
        ShadowTrackingSession.sortByDomain(list, cx, cz);
        return list;
    }

    @Test
    @DisplayName("同域连片：组内按距离升序，组间按各域最近柱距离升序")
    void groupsByDomainThenDistance() {
        // 三个平铺 3×3 域：T(0,0)=x0..2/z0..2、T(1,0)=x3..5/z0..2、T(0,1)=x0..2/z3..5
        // 相对中心 (0,0) 的距离²：T(0,0) 最近柱 (1,1)=2；T(1,0) 最近柱 (3,0)=9；T(0,1) 最近柱 (0,4)=16
        List<ChunkPos> out = sorted(0, 0,
                new ChunkPos(5, 1),   // T(1,0)，远
                new ChunkPos(0, 4),   // T(0,1)
                new ChunkPos(2, 0),   // T(0,0)
                new ChunkPos(3, 0),   // T(1,0)，近
                new ChunkPos(1, 1));  // T(0,0)
        assertEquals(List.of(new ChunkPos(1, 1), new ChunkPos(2, 0)), out.subList(0, 2),
                "最近域 T(0,0) 的两柱应连片且按距离升序");
        assertEquals(List.of(new ChunkPos(3, 0), new ChunkPos(5, 1)), out.subList(2, 4),
                "次近域 T(1,0) 连片，组内按距离升序");
        assertEquals(new ChunkPos(0, 4), out.get(4), "最远域 T(0,1) 最后");
    }

    @Test
    @DisplayName("跨域不交错：任一域的两柱之间不得夹进别的域的柱")
    void domainsDoNotInterleave() {
        List<ChunkPos> out = sorted(0, 0,
                new ChunkPos(4, 4),   // T(1,1) 远
                new ChunkPos(0, 0),   // T(0,0)
                new ChunkPos(1, 0),   // T(0,0)
                new ChunkPos(3, 0),   // T(1,0)
                new ChunkPos(4, 0),   // T(1,0)
                new ChunkPos(5, 0));  // T(1,0)
        List<Long> domainSeq = new ArrayList<>();
        for (ChunkPos p : out) {
            long id = ShadowTrackingSession.domainId(p.x, p.z);
            if (domainSeq.isEmpty() || domainSeq.get(domainSeq.size() - 1) != id) {
                domainSeq.add(id);
            }
        }
        assertEquals(domainSeq.size(), new java.util.HashSet<>(domainSeq).size(),
                "同一域不得在序列里出现两段（分组 = 连续段）");
    }

    @Test
    @DisplayName("平铺锚点：floorDiv 向下取整，负数坐标域 -1 覆盖 -3..-1")
    void domainTilesByFloorDiv() {
        assertEquals(ShadowTrackingSession.domainId(0, 0), ShadowTrackingSession.domainId(2, 2));
        assertNotEquals(ShadowTrackingSession.domainId(2, 2), ShadowTrackingSession.domainId(3, 2));
        assertNotEquals(ShadowTrackingSession.domainId(0, 0), ShadowTrackingSession.domainId(0, 3));
        assertEquals(ShadowTrackingSession.domainId(-1, 0), ShadowTrackingSession.domainId(-3, 0),
                "floorDiv：-1 与 -3 同属域 -1");
        assertNotEquals(ShadowTrackingSession.domainId(-1, 0), ShadowTrackingSession.domainId(0, 0));
        assertNotEquals(ShadowTrackingSession.domainId(-1, 0), ShadowTrackingSession.domainId(-4, 0));
    }

    @Test
    @DisplayName("退化输入不抛：空表 / 单元素 / null")
    void degenerateInputsAreNoOps() {
        List<ChunkPos> one = new ArrayList<>(List.of(new ChunkPos(7, 7)));
        ShadowTrackingSession.sortByDomain(one, 0, 0);
        assertEquals(List.of(new ChunkPos(7, 7)), one);
        List<ChunkPos> empty = new ArrayList<>();
        ShadowTrackingSession.sortByDomain(empty, 0, 0);
        assertTrue(empty.isEmpty());
        ShadowTrackingSession.sortByDomain(null, 0, 0);
    }

    @Test
    @DisplayName("S3 光环半径：默认 = ShadowPullRadii.LIGHT_HALO_RADIUS，且钳在 [0, 签发余量-1]")
    void lightHaloRadiusIsClampedToServerIssuedRange() {
        int halo = ShadowTrackingSession.lightHaloRadius();
        assertTrue(halo >= 0, "光环半径不得为负：" + halo);
        assertTrue(halo <= io.github.limuqy.mc.hassium.protocol.ShadowPullRadii.MAX_LIGHT_HALO_RADIUS,
                "光环半径不得超服务端签发余量：" + halo);
        assertEquals(io.github.limuqy.mc.hassium.protocol.ShadowPullRadii.LIGHT_HALO_RADIUS, halo,
                "默认配置下应等于 ShadowPullRadii.LIGHT_HALO_RADIUS");
        // 客户端窗口 contains(VD+R) 的外接盒是 cheb ≤ VD+R+1，服务端签发是 cheb ≤ VD+AUTHORITY_MARGIN
        // → 对齐要求 R+1 ≤ AUTHORITY_MARGIN。默认 R=1 恰好贴满（AUTHORITY_MARGIN=2）。
        assertEquals(io.github.limuqy.mc.hassium.protocol.ShadowPullRadii.AUTHORITY_MARGIN - 1,
                io.github.limuqy.mc.hassium.protocol.ShadowPullRadii.MAX_LIGHT_HALO_RADIUS,
                "上限 = 签发余量 - 1（切比雪夫外接盒比半径多 1）");
        assertEquals(io.github.limuqy.mc.hassium.protocol.ShadowPullRadii.MAX_LIGHT_HALO_RADIUS,
                io.github.limuqy.mc.hassium.protocol.ShadowPullRadii.LIGHT_HALO_RADIUS,
                "默认值应恰好用满签发余量（否则默认就浪费了光环）");
    }
}
