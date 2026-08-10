package io.github.limuqy.mc.hassium.network.dataplane;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DataPlanePoCConfigTest {

    @Test
    @DisplayName("路由降级阈值维持为 3")
    void degradeThreshold() {
        assertEquals(3, DataPlanePoCConfig.DEGRADE_AFTER_DROPS);
    }
    // review-fix: T4-80 — FRAME_KEY_INFO_TAG/BIND_TOKEN 死常量已删（密钥派生收敛 UdpSessionKey），原 frameKeyInfoTag 断言一并移除。

}