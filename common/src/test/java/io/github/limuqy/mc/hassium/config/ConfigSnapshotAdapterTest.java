package io.github.limuqy.mc.hassium.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigSnapshotAdapterTest {
    @Test
    void defaultSnapshotRoundTripsThroughSchemaValues() {
        HassiumConfig original = HassiumConfig.DEFAULT;
        ConfigValues values = ConfigSnapshotAdapter.toValues(original);

        HassiumConfig restored = ConfigSnapshotAdapter.fromValues(values);

        assertEquals(original, restored);
    }

    @Test
    void endpointAndDataPlaneValuesRoundTrip() {
        HassiumConfig original = HassiumConfig.DEFAULT;
        HassiumConfig restored = ConfigSnapshotAdapter.fromValues(ConfigSnapshotAdapter.toValues(original));

        assertEquals(original.master().controlReachableEndpoints(), restored.master().controlReachableEndpoints());
        assertEquals(original.master().dataPlane(), restored.master().dataPlane());
    }
}
