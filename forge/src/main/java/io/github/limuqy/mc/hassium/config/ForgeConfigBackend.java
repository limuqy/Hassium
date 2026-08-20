package io.github.limuqy.mc.hassium.config;

import io.github.limuqy.mc.hassium.platform.services.IConfigBackend;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Forge-native ConfigSpec backend generated from the common schema. */
public final class ForgeConfigBackend implements IConfigBackend {
    private final ForgeConfigSpec clientSpec;
    private final ForgeConfigSpec serverSpec;
    private final Map<String, ForgeConfigSpec.ConfigValue<?>> clientValues;
    private final Map<String, ForgeConfigSpec.ConfigValue<?>> serverValues;

    public ForgeConfigBackend() {
        SpecData client = build(ConfigScope.CLIENT);
        SpecData server = build(ConfigScope.SERVER);
        clientSpec = client.spec();
        serverSpec = server.spec();
        clientValues = client.values();
        serverValues = server.values();
    }

    public ForgeConfigSpec clientSpec() {
        return clientSpec;
    }

    public ForgeConfigSpec serverSpec() {
        return serverSpec;
    }

    @Override
    public ConfigValues load(ConfigScope scope) {
        ConfigValues values = ConfigValues.defaults(ConfigSchema.entries());
        return loadScope(values, scope, nativeValues(scope));
    }

    @Override
    public void save(ConfigScope scope, ConfigValues values) {
        writeScope(values, scope, nativeValues(scope));
        nativeSpec(scope).save();
    }

    private static SpecData build(ConfigScope scope) {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        Map<String, ForgeConfigSpec.ConfigValue<?>> values = new HashMap<>();
        for (ConfigEntry<?> entry : entries(scope)) {
            builder.comment(ConfigComments.lines(entry.comment())).translation(entry.translationKey());
            ForgeConfigSpec.ConfigValue<?> configValue;
            switch (entry.type()) {
                case BOOLEAN, STRING -> configValue = builder.define(entry.path(), entry.defaultValue());
                case INT -> configValue = builder.defineInRange(entry.path(), (Integer) entry.defaultValue(),
                        entry.min().intValue(), entry.max().intValue());
                case LONG -> configValue = builder.defineInRange(entry.path(), (Long) entry.defaultValue(),
                        entry.min().longValue(), entry.max().longValue());
                case DOUBLE -> configValue = builder.defineInRange(entry.path(), (Double) entry.defaultValue(),
                        entry.min().doubleValue(), entry.max().doubleValue());
                case STRING_LIST -> configValue = builder.defineList(entry.path(), (List<?>) entry.defaultValue(),
                        value -> value instanceof String);
                default -> throw new IllegalStateException("Unsupported configuration type: " + entry.type());
            }
            values.put(entry.path(), configValue);
        }
        return new SpecData(builder.build(), values);
    }

    private static ConfigValues loadScope(ConfigValues values, ConfigScope scope,
                                          Map<String, ForgeConfigSpec.ConfigValue<?>> nativeValues) {
        for (ConfigEntry<?> entry : entries(scope)) {
            Object value = nativeValues.get(entry.path()).get();
            if (value != null) {
                values = withValue(values, entry, value);
            }
        }
        return values;
    }

    private static void writeScope(ConfigValues values, ConfigScope scope,
                                   Map<String, ForgeConfigSpec.ConfigValue<?>> nativeValues) {
        for (ConfigEntry<?> entry : entries(scope)) {
            setValue(nativeValues.get(entry.path()), values, entry);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> void setValue(ForgeConfigSpec.ConfigValue<?> nativeValue,
                                      ConfigValues values, ConfigEntry<T> entry) {
        ((ForgeConfigSpec.ConfigValue<T>) nativeValue).set(values.get(entry.key()));
    }

    @SuppressWarnings("unchecked")
    private static <T> ConfigValues withValue(ConfigValues values, ConfigEntry<T> entry, Object value) {
        return values.with(entry.key(), (T) value);
    }

    private Map<String, ForgeConfigSpec.ConfigValue<?>> nativeValues(ConfigScope scope) {
        return scope == ConfigScope.CLIENT ? clientValues : serverValues;
    }

    private ForgeConfigSpec nativeSpec(ConfigScope scope) {
        return scope == ConfigScope.CLIENT ? clientSpec : serverSpec;
    }

    private static List<ConfigEntry<?>> entries(ConfigScope scope) {
        return scope == ConfigScope.CLIENT ? ConfigSchema.clientEntries() : ConfigSchema.serverEntries();
    }

    private record SpecData(ForgeConfigSpec spec, Map<String, ForgeConfigSpec.ConfigValue<?>> values) {
    }
}
