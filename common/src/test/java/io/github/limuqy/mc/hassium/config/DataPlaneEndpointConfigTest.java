package io.github.limuqy.mc.hassium.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DataPlaneEndpointConfigTest {

    @Test
    void rejectsWildcardAndInvalidReachableHosts() {
        assertThrows(IllegalArgumentException.class,
                () -> new HassiumConfig.ReachableEndpoint("0.0.0.0", 25565, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new HassiumConfig.ReachableEndpoint("::", 25565, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new HassiumConfig.ReachableEndpoint("", 25565, 1));
    }

    @Test
    void keepsHighestPriorityDuplicateAndSortsDescending() {
        var normalized = DataPlaneEndpointConfig.normalizeReachableEndpoints(List.of(
                new HassiumConfig.ReachableEndpoint("b.example", 25565, 10),
                new HassiumConfig.ReachableEndpoint("a.example", 25565, 30),
                new HassiumConfig.ReachableEndpoint("b.example", 25565, 40)), 8, "test");

        assertEquals(List.of(
                new HassiumConfig.ReachableEndpoint("b.example", 25565, 40),
                new HassiumConfig.ReachableEndpoint("a.example", 25565, 30)), normalized);
    }

    @Test
    void enabledDataPlaneRequiresUniqueBoundListenersWithReachableCandidates() {
        var endpoint = new HassiumConfig.ReachableEndpoint("play.example", 25565, 1);
        var listener = new HassiumConfig.UdpListenerConfig("0.0.0.0", 25565, 50, List.of(endpoint));

        assertThrows(IllegalArgumentException.class,
                () -> DataPlaneEndpointConfig.normalizeUdpListeners(true, List.of(listener, listener)));
        assertThrows(IllegalArgumentException.class,
                () -> DataPlaneEndpointConfig.normalizeUdpListeners(true, List.of(
                        new HassiumConfig.UdpListenerConfig("0.0.0.0", 25565, 50, List.of()))));
    }
}
