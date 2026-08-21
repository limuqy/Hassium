package io.github.limuqy.mc.hassium.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TickMonitorTest {

    @Test
    void formatHassiumMspt_matchesDispatcherLogShape() {
        // 20 tick window, 40ms / 8ms / 2ms totals → 2.0 / 0.4 / 0.1 per tick
        assertEquals(
                "[MSPT] hassium drainPending=2.0ms drainQueue=0.4ms flush=0.1ms",
                TickMonitor.formatHassiumMspt(40_000_000L, 8_000_000L, 2_000_000L, 20));
    }
}
