package io.github.limuqy.mc.hassium.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ConfigValues {
    private final Map<ConfigKey<?>, Object> values;

    private ConfigValues(Map<ConfigKey<?>, Object> values) {
        this.values = new HashMap<>(values);
    }

    public static ConfigValues defaults(List<ConfigEntry<?>> entries) {
        Map<ConfigKey<?>, Object> defaults = new HashMap<>();
        for (ConfigEntry<?> entry : entries) {
            Object value = entry.defaultValue();
            if (value instanceof List<?> list) {
                value = List.copyOf(list);
            }
            defaults.put(entry.key(), value);
        }
        return new ConfigValues(defaults);
    }

    public <T> T get(ConfigKey<T> key) {
        Object value = values.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing configuration value: " + key.path());
        }
        if (!key.valueType().isInstance(value) && !(key.valueType() == Number.class && value instanceof Number)) {
            throw new IllegalStateException("Invalid configuration value type for " + key.path());
        }
        @SuppressWarnings("unchecked")
        T typed = (T) value;
        return typed;
    }

    public <T> ConfigValues with(ConfigKey<T> key, T value) {
        if (value == null || !key.valueType().isInstance(value)) {
            throw new IllegalArgumentException("Invalid configuration value for " + key.path());
        }
        Map<ConfigKey<?>, Object> copy = new HashMap<>(values);
        copy.put(key, value instanceof List<?> list ? List.copyOf(list) : value);
        return new ConfigValues(copy);
    }

    public Map<ConfigKey<?>, Object> asMap() {
        return Map.copyOf(values);
    }
}
