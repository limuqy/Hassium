package io.github.limuqy.mc.hassium.network.dataplane;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DataPlanePoCConfigTest {

    @Test @DisplayName("默认启用了 data plane")
    void defaultEnabled() {
        assertTrue(DataPlanePoCConfig.ENABLED);
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