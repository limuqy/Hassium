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
}
