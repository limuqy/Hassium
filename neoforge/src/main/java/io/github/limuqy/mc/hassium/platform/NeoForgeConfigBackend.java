package io.github.limuqy.mc.hassium.platform;

import io.github.limuqy.mc.hassium.config.ConfigEntry;
import io.github.limuqy.mc.hassium.config.ConfigScope;
import io.github.limuqy.mc.hassium.config.ConfigSchema;
import io.github.limuqy.mc.hassium.config.ConfigValues;
import io.github.limuqy.mc.hassium.platform.services.IConfigBackend;

#if MC_VER < MC_1_20_2
import net.minecraftforge.common.ForgeConfigSpec;
#else
import net.neoforged.neoforge.common.ModConfigSpec;
#endif

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class NeoForgeConfigBackend implements IConfigBackend {
#if MC_VER < MC_1_20_2
    private final ForgeConfigSpec clientSpec;
    private final ForgeConfigSpec serverSpec;
    private final Map<String, ForgeConfigSpec.ConfigValue<?>> clientValues;
    private final Map<String, ForgeConfigSpec.ConfigValue<?>> serverValues;
#else
    private final ModConfigSpec clientSpec;
    private final ModConfigSpec serverSpec;
    private final Map<String, ModConfigSpec.ConfigValue<?>> clientValues;
    private final Map<String, ModConfigSpec.ConfigValue<?>> serverValues;
#endif

    public NeoForgeConfigBackend() {
        SpecData client = build(ConfigScope.CLIENT);
        SpecData server = build(ConfigScope.SERVER);
        clientSpec = client.spec();
        serverSpec = server.spec();
        clientValues = client.values();
        serverValues = server.values();
    }

#if MC_VER < MC_1_20_2
    public ForgeConfigSpec clientSpec() { return clientSpec; }
    public ForgeConfigSpec serverSpec() { return serverSpec; }
#else
    public ModConfigSpec clientSpec() { return clientSpec; }
    public ModConfigSpec serverSpec() { return serverSpec; }
#endif

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

#if MC_VER < MC_1_20_2
    private static SpecData build(ConfigScope scope) {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        Map<String, ForgeConfigSpec.ConfigValue<?>> values = new HashMap<>();
#else
    private static SpecData build(ConfigScope scope) {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        Map<String, ModConfigSpec.ConfigValue<?>> values = new HashMap<>();
#endif
        for (ConfigEntry<?> entry : entries(scope)) {
            builder.comment(entry.comment()).translation(entry.translationKey());
#if MC_VER < MC_1_20_2
            ForgeConfigSpec.ConfigValue<?> configValue;
#else
            ModConfigSpec.ConfigValue<?> configValue;
#endif
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

#if MC_VER < MC_1_20_2
    private static ConfigValues loadScope(ConfigValues values, ConfigScope scope,
                                          Map<String, ForgeConfigSpec.ConfigValue<?>> nativeValues) {
#else
    private static ConfigValues loadScope(ConfigValues values, ConfigScope scope,
                                          Map<String, ModConfigSpec.ConfigValue<?>> nativeValues) {
#endif
        for (ConfigEntry<?> entry : entries(scope)) {
            Object value = nativeValues.get(entry.path()).get();
            if (value != null) {
                values = withValue(values, entry, value);
            }
        }
        return values;
    }

#if MC_VER < MC_1_20_2
    private static void writeScope(ConfigValues values, ConfigScope scope,
                                   Map<String, ForgeConfigSpec.ConfigValue<?>> nativeValues) {
#else
    private static void writeScope(ConfigValues values, ConfigScope scope,
                                   Map<String, ModConfigSpec.ConfigValue<?>> nativeValues) {
#endif
        for (ConfigEntry<?> entry : entries(scope)) {
            setValue(nativeValues.get(entry.path()), values, entry);
        }
    }

#if MC_VER < MC_1_20_2
    @SuppressWarnings("unchecked")
    private static <T> void setValue(ForgeConfigSpec.ConfigValue<?> nativeValue,
                                      ConfigValues values, ConfigEntry<T> entry) {
        ((ForgeConfigSpec.ConfigValue<T>) nativeValue).set(values.get(entry.key()));
    }
#else
    @SuppressWarnings("unchecked")
    private static <T> void setValue(ModConfigSpec.ConfigValue<?> nativeValue,
                                      ConfigValues values, ConfigEntry<T> entry) {
        ((ModConfigSpec.ConfigValue<T>) nativeValue).set(values.get(entry.key()));
    }
#endif

    @SuppressWarnings("unchecked")
    private static <T> ConfigValues withValue(ConfigValues values, ConfigEntry<T> entry, Object value) {
        return values.with(entry.key(), (T) value);
    }

    private static List<ConfigEntry<?>> entries(ConfigScope scope) {
        return scope == ConfigScope.CLIENT ? ConfigSchema.clientEntries() : ConfigSchema.serverEntries();
    }

#if MC_VER < MC_1_20_2
    private Map<String, ForgeConfigSpec.ConfigValue<?>> nativeValues(ConfigScope scope) {
        return scope == ConfigScope.CLIENT ? clientValues : serverValues;
    }

    private ForgeConfigSpec nativeSpec(ConfigScope scope) {
        return scope == ConfigScope.CLIENT ? clientSpec : serverSpec;
    }
#else
    private Map<String, ModConfigSpec.ConfigValue<?>> nativeValues(ConfigScope scope) {
        return scope == ConfigScope.CLIENT ? clientValues : serverValues;
    }

    private ModConfigSpec nativeSpec(ConfigScope scope) {
        return scope == ConfigScope.CLIENT ? clientSpec : serverSpec;
    }
#endif

#if MC_VER < MC_1_20_2
    private record SpecData(ForgeConfigSpec spec, Map<String, ForgeConfigSpec.ConfigValue<?>> values) {
    }
#else
    private record SpecData(ModConfigSpec spec, Map<String, ModConfigSpec.ConfigValue<?>> values) {
    }
#endif
}
