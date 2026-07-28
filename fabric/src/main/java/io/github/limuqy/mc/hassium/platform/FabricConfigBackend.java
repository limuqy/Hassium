package io.github.limuqy.mc.hassium.platform;

import io.github.limuqy.mc.hassium.config.ConfigScope;
import io.github.limuqy.mc.hassium.config.ConfigValues;
import io.github.limuqy.mc.hassium.config.FabricTomlConfigIO;
import io.github.limuqy.mc.hassium.platform.services.IConfigBackend;

/** Fabric TOML backend driven by the common configuration schema. */
public final class FabricConfigBackend implements IConfigBackend {
    @Override
    public ConfigValues load(ConfigScope scope) {
        return FabricTomlConfigIO.loadValues(scope);
    }

    @Override
    public void save(ConfigScope scope, ConfigValues values) {
        FabricTomlConfigIO.saveValues(values, scope);
    }
}
