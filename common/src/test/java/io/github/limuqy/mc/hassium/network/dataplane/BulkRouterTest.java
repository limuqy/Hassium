package io.github.limuqy.mc.hassium.network.dataplane;

import org.junit.jupiter.api.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class BulkRouterTest {

    private PlayerChannelBundle bundle;

    private static PlayerChannel mockChannel(boolean active, boolean writable, int weight) {
        return new PlayerChannel(null, weight) {
            @Override public boolean isActive() { return active; }
            @Override public boolean isWritable() { return writable; }
        };
    }

    @BeforeEach void setUp() {
        bundle = new PlayerChannelBundle();
    }

    @Test @DisplayName("share 模式,两条 Data 都可用 → 路由到 Data（返回 true）")
    void share_withDataChannels_returnsTrue() {
        bundle.addChannel(mockChannel(true, true, 50));
        bundle.addChannel(mockChannel(true, true, 50));
        AtomicInteger dataCount = new AtomicInteger();
        AtomicInteger primaryFallback = new AtomicInteger();
        for (int i = 0; i < 100; i++) {
            boolean result = BulkRouter.sendBulk(bundle, "share", 100, 3);
            if (result) dataCount.incrementAndGet();
            else primaryFallback.incrementAndGet();
        }
        // share 模式: Primary weight=100, Data weight=50+50=100, 所以约 50% 路由到 Data
        assertTrue(dataCount.get() > 20, "应有一定比例的 bulk 走 Data: " + dataCount.get());
        assertTrue(primaryFallback.get() > 20, "应有一定比例的 bulk 走 Primary: " + primaryFallback.get());
    }

    @Test @DisplayName("share 模式,无 Data 通道 → 返回 false（Primary fallback）")
    void share_noData_returnsFalse() {
        assertFalse(BulkRouter.sendBulk(bundle, "share", 100, 3));
    }

    @Test @DisplayName("exclusive 模式,无 Data 通道 → 返回 true（drop）且 consecutiveDrops 递增")
    void exclusive_noData_dropsAndIncrements() {
        bundle.consecutiveDrops = 0;
        boolean result = BulkRouter.sendBulk(bundle, "exclusive", 100, 3);
        assertTrue(result); // caller 不要发 Primary
        assertEquals(1, bundle.consecutiveDrops);
    }

    @Test @DisplayName("exclusive 模式,连续 3 次 drop → degraded=true → 返回 false")
    void exclusive_threeDrops_degrade() {
        bundle.consecutiveDrops = 0;
        BulkRouter.sendBulk(bundle, "exclusive", 100, 3); // drop #1
        BulkRouter.sendBulk(bundle, "exclusive", 100, 3); // drop #2
        BulkRouter.sendBulk(bundle, "exclusive", 100, 3); // drop #3 → degraded
        assertTrue(bundle.degraded);
        // 第四次: degraded=true → 返回 false
        assertFalse(BulkRouter.sendBulk(bundle, "exclusive", 100, 3));
    }

    @Test @DisplayName("degraded bundle 任何 bulk 都返回 false")
    void degraded_alwaysReturnsFalse() {
        bundle.degraded = true;
        assertFalse(BulkRouter.sendBulk(bundle, "share", 100, 3));
        assertFalse(BulkRouter.sendBulk(bundle, "exclusive", 100, 3));
    }

    @Test @DisplayName("成功发送后 consecutiveDrops 归零")
    void successfulSend_resetsDrops() {
        bundle.consecutiveDrops = 2;
        bundle.addChannel(mockChannel(true, true, 50));
        BulkRouter.sendBulk(bundle, "share", 100, 3);
        assertEquals(0, bundle.consecutiveDrops);
    }
}
