package io.github.limuqy.mc.hassium.compat;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;

/**
 * RegistryAccess 版本兼容层
 * <p>
 * 1.20.1-1.20.6: RegistryAccess.registryOrThrow(ResourceKey) 返回 Registry&lt;T&gt;
 * 1.21.2+: RegistryAccess.lookupOrThrow(ResourceKey) 返回 HolderLookup.RegistryLookup&lt;T&gt;
 * <p>
 * 此工具类统一返回 Registry&lt;Biome&gt;，通过向下转型兼容 1.21.4+
 * （运行时 RegistryLookup 的实际对象仍是 MappedRegistry，转型安全）。
 */
public final class RegistryCompat {
    private RegistryCompat() {}

    @SuppressWarnings("unchecked")
    public static Registry<Biome> getBiomeRegistry(RegistryAccess access) {
#if MC_VER < MC_1_21_2
        return access.registryOrThrow(Registries.BIOME);
#else
        return (Registry<Biome>) access.lookupOrThrow(Registries.BIOME);
#endif
    }

    /**
     * 按 ResourceKey 取 Holder.Reference（1.21.2+ {@code getOrThrow}）。
     */
    public static <T> Holder.Reference<T> getHolderOrThrow(Registry<T> registry, ResourceKey<T> key) {
#if MC_VER < MC_1_21_2
        return registry.getHolderOrThrow(key);
#else
        return registry.getOrThrow(key);
#endif
    }
}
