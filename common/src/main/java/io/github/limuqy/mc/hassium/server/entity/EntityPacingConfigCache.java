package io.github.limuqy.mc.hassium.server.entity;

import io.github.limuqy.mc.hassium.config.HassiumConfig;

/**
 * Parsed entity-pacing settings cached by immutable config snapshot identity.
 * Access is serialized by the server tick thread; {@link #clear()} is called when that server stops.
 */
final class EntityPacingConfigCache {

    private HassiumConfig source;
    private Values values;

    Values get(HassiumConfig config) {
        if (config != source || values == null) {
            source = config;
            values = Values.from(config);
        }
        return values;
    }

    void clear() {
        source = null;
        values = null;
    }

    static final class Values {
        final boolean tiered;
        final int[] entityIntervals;
        final int[] itemIntervals;
        final boolean densityThrottle;
        final int[] densityTierCounts;
        final double[] densityTierFactors;
        final int maxFactor;
        final int budgetPerPlayer;
        final boolean smoothPush;

        private Values(boolean tiered, int[] entityIntervals, int[] itemIntervals,
                       boolean densityThrottle, int[] densityTierCounts, double[] densityTierFactors,
                       int maxFactor, int budgetPerPlayer, boolean smoothPush) {
            this.tiered = tiered;
            this.entityIntervals = entityIntervals;
            this.itemIntervals = itemIntervals;
            this.densityThrottle = densityThrottle;
            this.densityTierCounts = densityTierCounts;
            this.densityTierFactors = densityTierFactors;
            this.maxFactor = maxFactor;
            this.budgetPerPlayer = budgetPerPlayer;
            this.smoothPush = smoothPush;
        }

        private static Values from(HassiumConfig config) {
            HassiumConfig.MasterCoreConfig master = config.master();
            return new Values(
                    master.entityTieredUpdateEnabled(),
                    EntityUpdateTiering.parseIntervals(
                            master.entityTierIntervals(), EntityUpdateTiering.DEFAULT_ENTITY_INTERVALS),
                    EntityUpdateTiering.parseIntervals(
                            master.entityItemTierIntervals(), EntityUpdateTiering.DEFAULT_ITEM_INTERVALS),
                    master.entityDensityThrottleEnabled(),
                    EntityUpdateTiering.parseDensityCounts(master.entityDensityTierCounts()),
                    EntityUpdateTiering.parseDensityFactors(master.entityDensityTierFactors()),
                    Math.min(16, Math.max(1, master.entityMaxThrottleFactor())),
                    Math.max(0, master.entityFrameBudgetPerPlayer()),
                    master.entitySmoothPushEnabled());
        }
    }
}
