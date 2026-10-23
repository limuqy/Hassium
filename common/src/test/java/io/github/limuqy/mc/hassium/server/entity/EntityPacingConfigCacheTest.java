package io.github.limuqy.mc.hassium.server.entity;

import io.github.limuqy.mc.hassium.config.HassiumConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class EntityPacingConfigCacheTest {

    @Test
    void reusesParsedValuesUntilConfigSnapshotChanges() {
        EntityPacingConfigCache cache = new EntityPacingConfigCache();
        HassiumConfig initial = withEntitySettings("3,4,6,10", 64);

        EntityPacingConfigCache.Values first = cache.get(initial);
        EntityPacingConfigCache.Values second = cache.get(initial);

        assertSame(first, second);
        assertEquals(3, first.entityIntervals[0]);
        assertEquals(64, first.budgetPerPlayer);
    }

    @Test
    void reloadUsesNewValuesAndClearDropsTheCachedSnapshot() {
        EntityPacingConfigCache cache = new EntityPacingConfigCache();
        HassiumConfig initial = withEntitySettings("3,4,6,10", 64);
        EntityPacingConfigCache.Values first = cache.get(initial);

        HassiumConfig reloaded = withEntitySettings("5,7,9,12", 96);
        EntityPacingConfigCache.Values afterReload = cache.get(reloaded);
        assertNotSame(first, afterReload);
        assertEquals(5, afterReload.entityIntervals[0]);
        assertEquals(96, afterReload.budgetPerPlayer);

        cache.clear();
        EntityPacingConfigCache.Values afterClear = cache.get(reloaded);
        assertNotSame(afterReload, afterClear);
        assertEquals(5, afterClear.entityIntervals[0]);
        assertEquals(96, afterClear.budgetPerPlayer);
    }

    private static HassiumConfig withEntitySettings(String intervals, int budget) {
        HassiumConfig.MasterCoreConfig original = HassiumConfig.MasterCoreConfig.DEFAULT;
        HassiumConfig.MasterCoreConfig master = new HassiumConfig.MasterCoreConfig(
                original.enabled(), original.enabledOnLan(), original.compressionLevel(),
                original.enablePacketAggregation(), original.aggregationMaxWaitTimeMs(), original.aggregationMaxSize(),
                original.compressionBlacklist(), original.maxChunksPerTick(),
                original.entityTieredUpdateEnabled(), intervals, original.entityItemTierIntervals(),
                original.entityDensityThrottleEnabled(), original.entityDensityTierCounts(),
                original.entityDensityTierFactors(), original.entityMaxThrottleFactor(), budget,
                original.entitySmoothPushEnabled(), original.sleepEnabled());
        HassiumConfig defaults = HassiumConfig.DEFAULT;
        return new HassiumConfig(defaults.storage(), defaults.chunk(), master,
                defaults.compat(), defaults.debug(), defaults.serverSeedGenEnabled());
    }
}
