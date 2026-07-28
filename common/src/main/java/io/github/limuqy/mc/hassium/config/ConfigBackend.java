package io.github.limuqy.mc.hassium.config;

public interface ConfigBackend {
    ConfigValues load(ConfigScope scope);

    void save(ConfigScope scope, ConfigValues values);
}
