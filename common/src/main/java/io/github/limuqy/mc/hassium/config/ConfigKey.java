package io.github.limuqy.mc.hassium.config;

import java.util.Objects;

public final class ConfigKey<T> {
    private final String path;
    private final ConfigScope scope;
    private final Class<?> valueType;

    ConfigKey(String path, ConfigScope scope, Class<?> valueType) {
        this.path = Objects.requireNonNull(path, "path");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.valueType = Objects.requireNonNull(valueType, "valueType");
    }

    public String path() {
        return path;
    }

    public ConfigScope scope() {
        return scope;
    }

    Class<?> valueType() {
        return valueType;
    }
}
