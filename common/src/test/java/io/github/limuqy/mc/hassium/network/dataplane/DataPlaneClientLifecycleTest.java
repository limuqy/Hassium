package io.github.limuqy.mc.hassium.network.dataplane;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DataPlaneClientLifecycleTest {

    @Test
    void defersHandshakeTailUntilClientPlayerExistsAndConsumesItOnce() {
        DataPlaneClientLifecycle lifecycle = DataPlaneClientLifecycle.getInstance();
        var tail = new UdpDataPlaneHandshakeTail.S2CTail(
                true, true, 42L, UdpDataPlaneHandshakeTail.PROTOCOL_VERSION, new byte[16],
                List.of(), List.of(new UdpDataPlaneHandshakeTail.UdpEndpointInfo("udp.example", 25565, 100, 0)));

        lifecycle.deferUdpStart(tail);

        var pending = lifecycle.takePendingUdpStart();
        assertEquals(tail, pending);
        assertNull(lifecycle.takePendingUdpStart());
    }
}
