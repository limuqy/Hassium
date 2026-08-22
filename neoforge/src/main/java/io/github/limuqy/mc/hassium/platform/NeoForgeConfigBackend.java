package io.github.limuqy.mc.hassium.platform;

import io.github.limuqy.mc.hassium.config.ConfigComments;
import io.github.limuqy.mc.hassium.config.ConfigEntry;
import io.github.limuqy.mc.hassium.config.ConfigScope;
import io.github.limuqy.mc.hassium.config.ConfigSchema;
import io.github.limuqy.mc.hassium.config.ConfigValues;
import io.github.limuqy.mc.hassium.platform.services.IConfigBackend;

// T10 收口：ForgeConfigSpec(1.20.1) vs ModConfigSpec(1.21.1+) 双类型名无法用 compat 消除，
// 类型名暴露仅保留 4 处分段点（import / 访问器返回类型 / Builder 构造 / SpecData 定义），
// 其余读写逻辑全部收敛到 SpecData.get/set 与 Object 局部变量。
#if MC_VER < MC_1_21_1
import net.minecraftforge.common.ForgeConfigSpec;
#else
import net.neoforged.neoforge.common.ModConfigSpec;
#endif

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class NeoForgeConfigBackend implements IConfigBackend {

    private final SpecData client;
    private final SpecData server;

    public NeoForgeConfigBackend() {
        client = build(ConfigScope.CLIENT);
        server = build(ConfigScope.SERVER);
    }

#if MC_VER < MC_1_21_1
    public ForgeConfigSpec clientSpec() { return client.spec(); }
    public ForgeConfigSpec serverSpec() { return server.spec(); }
#else
    public ModConfigSpec clientSpec() { return client.spec(); }
    public ModConfigSpec serverSpec() { return server.spec(); }
#endif

    @Override
    public ConfigValues load(ConfigScope scope) {
        ConfigValues values = ConfigValues.defaults(ConfigSchema.entries());
        return loadScope(values, scope, data(scope));
    }

    @Override
    public void save(ConfigScope scope, ConfigValues values) {
        writeScope(values, scope, data(scope));
        data(scope).spec().save();
    }

#if MC_VER < MC_1_21_1
    private static SpecData build(ConfigScope scope) {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        Map<String, ForgeConfigSpec.ConfigValue<?>> values = new HashMap<>();
#else
    private static SpecData build(ConfigScope scope) {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        Map<String, ModConfigSpec.ConfigValue<?>> values = new HashMap<>();
#endif
        for (ConfigEntry<?> entry : entries(scope)) {
            builder.comment(ConfigComments.lines(entry.comment())).translation(entry.translationKey());
#if MC_VER < MC_1_21_1
            ForgeConfigSpec.ConfigValue<?> configValue =
#else
            ModConfigSpec.ConfigValue<?> configValue =
#endif
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
                default -> throw new IllegalStateException("Unsupported configuration type: " + entry.type());
            };
            values.put(entry.path(), configValue);
        }
        return new SpecData(builder.build(), values);
    }

    private static ConfigValues loadScope(ConfigValues values, ConfigScope scope, SpecData data) {
        for (ConfigEntry<?> entry : entries(scope)) {
            Object value = data.get(entry.path());
            if (value != null) {
                values = withValue(values, entry, value);
            }
        }
        return values;
    }

    private static void writeScope(ConfigValues values, ConfigScope scope, SpecData data) {
        for (ConfigEntry<?> entry : entries(scope)) {
            data.set(entry.path(), values.get(entry.key()));
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> ConfigValues withValue(ConfigValues values, ConfigEntry<T> entry, Object value) {
        return values.with(entry.key(), (T) value);
    }

    private static List<ConfigEntry<?>> entries(ConfigScope scope) {
        return scope == ConfigScope.CLIENT ? ConfigSchema.clientEntries() : ConfigSchema.serverEntries();
    }

    private SpecData data(ConfigScope scope) {
        return scope == ConfigScope.CLIENT ? client : server;
    }

#if MC_VER < MC_1_21_1
    /** 原生 spec + ConfigValue 表；原生读写收口于 get/set（T10）。 */
    private record SpecData(ForgeConfigSpec spec, Map<String, ForgeConfigSpec.ConfigValue<?>> values) {
        Object get(String path) {
            return values.get(path).get();
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        void set(String path, Object value) {
            ((ForgeConfigSpec.ConfigValue) values.get(path)).set(value);
        }
    }
#else
    /** 原生 spec + ConfigValue 表；原生读写收口于 get/set（T10）。 */
    private record SpecData(ModConfigSpec spec, Map<String, ModConfigSpec.ConfigValue<?>> values) {
        Object get(String path) {
            return values.get(path).get();
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        void set(String path, Object value) {
            ((ModConfigSpec.ConfigValue) values.get(path)).set(value);
        }
    }
#endif
}
