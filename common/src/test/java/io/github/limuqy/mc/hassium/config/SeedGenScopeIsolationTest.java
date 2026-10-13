package io.github.limuqy.mc.hassium.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SeedGenScopeIsolationTest {
    @Test
    void lanSeedDisclosureAndClientGenerationRemainIndependentAcrossSave() {
        for (boolean client : new boolean[]{false, true}) {
            for (boolean server : new boolean[]{false, true}) {
                ConfigValues defaults = ConfigValues.defaults(ConfigSchema.entries());
                HassiumConfig merged = ConfigSnapshotAdapter.fromMerged(
                        defaults.with(ConfigSchema.CLIENT_CHUNK_SEED_GEN_ENABLED, client),
                        defaults.with(ConfigSchema.SERVER_CHUNK_SEED_GEN_ENABLED, server)
                                .with(ConfigSchema.MASTER_ENABLED_ON_LAN, true));
                assertEquals(client, merged.chunk().seedGenEnabled());
                assertEquals(server, new HassiumConfigService(merged).isSeedGenEnabled());
                ConfigValues saved = ConfigSnapshotAdapter.toValues(merged.withDebug(merged.debug()));
                assertEquals(client, saved.get(ConfigSchema.CLIENT_CHUNK_SEED_GEN_ENABLED));
                assertEquals(server, saved.get(ConfigSchema.SERVER_CHUNK_SEED_GEN_ENABLED));
            }
        }
    }
}
