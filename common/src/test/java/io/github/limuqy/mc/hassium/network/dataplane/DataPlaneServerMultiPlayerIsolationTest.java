package io.github.limuqy.mc.hassium.network.dataplane;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 §14 第 2 步 UUID 绑定后多玩家 bundle 隔离：
 * 不同 UUID 各自独立 PlayerChannelBundle，操作互不影响；
 * onPrimaryDisconnect(uuidA) 不影响 uuidB 的 bundle。
 */
class DataPlaneServerMultiPlayerIsolationTest {

    private final UUID a = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private final UUID b = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    @AfterEach
    void cleanup() {
        // 清掉两 bundle，避免污染 DataPlaneServer 全局 PLAYER_BUNDLES 影响后续测试
        DataPlaneServer.onPrimaryDisconnect(a);
        DataPlaneServer.onPrimaryDisconnect(b);
    }

    @Test
    @DisplayName("getOrCreateBundle 按玩家 UUID 建立独立 bundle")
    void bundlesAreIsolatedByUuid() {
        PlayerChannelBundle ba = DataPlaneServer.getOrCreateBundle(a);
        PlayerChannelBundle bb = DataPlaneServer.getOrCreateBundle(b);
        assertNotSame(ba, bb, "两玩家 bundle 应是不同实例");
        // 再次取同 UUID 应返回同一实例
        assertSame(ba, DataPlaneServer.getOrCreateBundle(a));
        assertSame(bb, DataPlaneServer.getOrCreateBundle(b));
    }

    @Test
    @DisplayName("getBundle 返回各自 bundle，未建返回 null")
    void getBundleByUuid() {
        assertNull(DataPlaneServer.getBundle(a));
        assertNull(DataPlaneServer.getBundle(b));
        DataPlaneServer.getOrCreateBundle(a);
        assertNotNull(DataPlaneServer.getBundle(a));
        assertNull(DataPlaneServer.getBundle(b), "建 A 时 B 不应被副作用建出");
    }

    @Test
    @DisplayName("onPrimaryDisconnect(uuidA) 不影响 uuidB 的 bundle")
    void disconnectIsolation() {
        PlayerChannelBundle bb = DataPlaneServer.getOrCreateBundle(b);
        DataPlaneServer.getOrCreateBundle(a);
        DataPlaneServer.onPrimaryDisconnect(a);

        assertNull(DataPlaneServer.getBundle(a), "A disconnect 后 A bundle 应被移除");
        assertSame(bb, DataPlaneServer.getBundle(b), "B bundle 不应受 A disconnect 影响");
        assertNotNull(DataPlaneServer.getBundle(b));
    }

    @Test
    @DisplayName("disconnect 幂等：disconnect 已不存在的 UUID 不抛、不影响他人")
    void disconnectIdempotent() {
        DataPlaneServer.getOrCreateBundle(b);
        UUID ghost = UUID.fromString("11111111-1111-1111-1111-111111111111");
        assertDoesNotThrow(() -> DataPlaneServer.onPrimaryDisconnect(ghost));
        assertNotNull(DataPlaneServer.getBundle(b), "对不存在的 UUID disconnect 应不影响 B");
    }
}
