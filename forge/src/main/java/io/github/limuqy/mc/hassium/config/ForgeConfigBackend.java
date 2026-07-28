package io.github.limuqy.mc.hassium.config;

import io.github.limuqy.mc.hassium.platform.services.IConfigBackend;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

/** Forge-native ConfigSpec backend generated from the common schema. */
public final class ForgeConfigBackend implements IConfigBackend {
    private final ForgeConfigSpec clientSpec;
    private final ForgeConfigSpec serverSpec;

    public ForgeConfigBackend() {
        clientSpec = build(ConfigScope.CLIENT);
        serverSpec = build(ConfigScope.SERVER);
    }

    public ForgeConfigSpec clientSpec() {
        return clientSpec;
    }

    public ForgeConfigSpec serverSpec() {
        return serverSpec;
    }

    @Override
    public ConfigValues load(ConfigScope scope) {
        ConfigValues values = ConfigValues.defaults(entries(scope));
        for (ConfigEntry<?> entry : entries(scope)) {
            Object value = spec(scope).getValues().get(entry.path());
            if (value != null) {
                values = withValue(values, entry, value);
            }
        }
        return values;
    }

    @Override
    public void save(ConfigScope scope, ConfigValues values) {
        spec(scope).save();
    }

    private static ForgeConfigSpec build(ConfigScope scope) {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        for (ConfigEntry<?> entry : entries(scope)) {
            builder.comment(entry.comment()).translation(entry.translationKey());
            switch (entry.type()) {
                case BOOLEAN, STRING -> builder.define(entry.path(), entry.defaultValue());
                case INT -> builder.defineInRange(entry.path(), (Integer) entry.defaultValue(),
                        entry.min().intValue(), entry.max().intValue());
                case LONG -> builder.defineInRange(entry.path(), (Long) entry.defaultValue(),
                        entry.min().longValue(), entry.max().longValue());
                case DOUBLE -> builder.defineInRange(entry.path(), (Double) entry.defaultValue(),
                        entry.min().doubleValue(), entry.max().doubleValue());
                case STRING_LIST -> builder.defineList(entry.path(), (List<?>) entry.defaultValue(),
                        value -> value instanceof String);
            }
        }
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static <T> ConfigValues withValue(ConfigValues values, ConfigEntry<T> entry, Object value) {
        return values.with(entry.key(), (T) value);
    }

    private ForgeConfigSpec spec(ConfigScope scope) {
        return scope == ConfigScope.CLIENT ? clientSpec : serverSpec;
    }

    private static List<ConfigEntry<?>> entries(ConfigScope scope) {
        return scope == ConfigScope.CLIENT ? ConfigSchema.clientEntries() : ConfigSchema.serverEntries();
    }
}
