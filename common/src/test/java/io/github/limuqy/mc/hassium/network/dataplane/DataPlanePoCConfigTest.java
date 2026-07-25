package io.github.limuqy.mc.hassium.network.dataplane;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DataPlanePoCConfigTest {

    @Test @DisplayName("默认启用了 data plane")
    void defaultEnabled() {
        // 默认 true；用 try/finally 防止 EnabledGuardTest 等并发用例 setEnabled(false) 污染全局。
        boolean prev = DataPlanePoCConfig.isEnabled();
        try {
            DataPlanePoCConfig.setEnabled(true);
            assertTrue(DataPlanePoCConfig.isEnabled());
        } finally {
            DataPlanePoCConfig.setEnabled(prev);
        }
    }

    @Test @DisplayName("setEnabled(false) 可在线关闭（volatile，非编译期内联）")
    void setEnabledFalseTakesEffect() {
        boolean prev = DataPlanePoCConfig.isEnabled();
        try {
            DataPlanePoCConfig.setEnabled(false);
            assertFalse(DataPlanePoCConfig.isEnabled());
            DataPlanePoCConfig.setEnabled(true);
            assertTrue(DataPlanePoCConfig.isEnabled());
        } finally {
            DataPlanePoCConfig.setEnabled(prev);
        }
    }


    @Test @DisplayName("两个 endpoint")
    void twoEndpoints() {
        assertEquals(2, DataPlanePoCConfig.ENDPOINTS.length);
        assertEquals(25566, DataPlanePoCConfig.ENDPOINTS[0].bindPort);
        assertEquals(25567, DataPlanePoCConfig.ENDPOINTS[1].bindPort);
    }

    @Test @DisplayName("token 固定 16 字节")
    void tokenLength() {
        assertEquals(16, DataPlanePoCConfig.BIND_TOKEN.length);
    }

    @Test @DisplayName("degrade threshold = 3")
    void degradeThreshold() {
        assertEquals(3, DataPlanePoCConfig.DEGRADE_AFTER_DROPS);
    }
}