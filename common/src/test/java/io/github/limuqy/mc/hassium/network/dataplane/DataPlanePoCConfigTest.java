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

    @Test
    @DisplayName("UDP session HKDF 标签维持协议值")
    void frameKeyInfoTag() {
        assertEquals(0x44_50_4C_31, DataPlanePoCConfig.FRAME_KEY_INFO_TAG);
    }
}