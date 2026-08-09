package io.github.limuqy.mc.hassium.config;

import java.util.List;
import java.util.Objects;

public record ConfigEntry<T>(
        ConfigKey<T> key,
        String path,
        ConfigScope scope,
        Domain domain,
        ConfigType type,
        T defaultValue,
        Number min,
        Number max,
        String comment,
        String translationKey
) {
    public ConfigEntry {
        Objects.requireNonNull(key, "key");
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Configuration path must not be blank");
        }
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(defaultValue, "defaultValue");
        if (min != null && max != null && Double.compare(min.doubleValue(), max.doubleValue()) > 0) {
            throw new IllegalArgumentException("Configuration range is reversed for " + path);
        }
        if (type == ConfigType.STRING_LIST && !(defaultValue instanceof List<?>)) {
            throw new IllegalArgumentException("STRING_LIST default must be a List for " + path);
        }
    }
}
