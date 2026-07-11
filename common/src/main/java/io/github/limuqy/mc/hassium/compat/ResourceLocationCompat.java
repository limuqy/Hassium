package io.github.limuqy.mc.hassium.compat;

#if MC_VER < MC_1_21_11
import net.minecraft.resources.ResourceLocation;
#else
import net.minecraft.resources.Identifier;
#endif

/**
 * ResourceLocation 版本兼容层
 * <p>
 * 1.20.1-1.20.6: new ResourceLocation(namespace, path) 公开
 * 1.21.1+: 构造器变 private，需用 ResourceLocation.fromNamespaceAndPath(namespace, path)
 * 1.21.11+: ResourceLocation 重命名为 Identifier
 */
public final class ResourceLocationCompat {
    private ResourceLocationCompat() {}

    public static
#if MC_VER < MC_1_21_11
    ResourceLocation
#else
    Identifier
#endif
    create(String namespace, String path) {
#if MC_VER < MC_1_21_1
        return new ResourceLocation(namespace, path);
#else
#if MC_VER < MC_1_21_11
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
#else
        return Identifier.fromNamespaceAndPath(namespace, path);
#endif
#endif
    }

    /**
     * 从单个字符串（如 "minecraft:dirt"）创建 ResourceLocation
     * <p>
     * 1.20.1-1.20.6: new ResourceLocation(String) 公开
     * 1.21.1+: 单参构造器被移除，需用 ResourceLocation.parse(String)
     */
    public static
#if MC_VER < MC_1_21_11
    ResourceLocation
#else
    Identifier
#endif
    create(String location) {
#if MC_VER < MC_1_21_1
        return new ResourceLocation(location);
#else
#if MC_VER < MC_1_21_11
        return ResourceLocation.parse(location);
#else
        return Identifier.parse(location);
#endif
#endif
    }
}
