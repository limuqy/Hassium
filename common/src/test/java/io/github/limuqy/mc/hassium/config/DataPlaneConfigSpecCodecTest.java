package io.github.limuqy.mc.hassium.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DataPlaneConfigSpecCodecTest {

    @Test
    void compactListenerCodecPreservesIpv6AndMultipleReachableCandidates() {
        HassiumConfig.UdpListenerConfig listener = new HassiumConfig.UdpListenerConfig("::", 25565, 50, List.of(
                new HassiumConfig.ReachableEndpoint("[2001:db8::1]", 41001, 100),
                new HassiumConfig.ReachableEndpoint("edge.example", 42001, 80)));

        assertEquals(listener, DataPlaneEndpointConfig.decodeListener(
                DataPlaneEndpointConfig.encodeListener(listener)));
    }

    @Test
    void compactCodecRejectsMalformedOrDuplicateValues() {
        for (String raw : List.of(
                "host,not-a-port,1",
                "host,65536,1",
                "bind,25565,50;0.0.0.0,25565,1",
                "bind,25565,50;edge,25565,-1",
                "bind,25565,50;edge,25565,1;edge,25565,2")) {
            assertThrows(IllegalArgumentException.class, () -> DataPlaneEndpointConfig.decodeListener(raw));
        }
    }
}
