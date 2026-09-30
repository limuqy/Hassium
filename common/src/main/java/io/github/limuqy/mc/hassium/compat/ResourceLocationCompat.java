package io.github.limuqy.mc.hassium.compat;

import net.minecraft.resources.Identifier;

/**
 * ResourceLocation 版本兼容层
 * <p>
 * 1.20.1: new ResourceLocation(namespace, path) 公开
 * 1.21.1+: 构造器变 private，需用 ResourceLocation.fromNamespaceAndPath(namespace, path)
 * 1.21.11+: ResourceLocation 重命名为 Identifier
 */
public final class ResourceLocationCompat {
    private ResourceLocationCompat() {}

    public static
    Identifier
    create(String namespace, String path) {
        return Identifier.fromNamespaceAndPath(namespace, path);
    }

    /**
     * 从单个字符串（如 "minecraft:dirt"）创建 ResourceLocation
     * <p>
     * 1.20.1: new ResourceLocation(String) 公开
     * 1.21.1+: 单参构造器被移除，需用 ResourceLocation.parse(String)
     */
    public static
    Identifier
    create(String location) {
        return Identifier.parse(location);
    }

    /**
     * 单字符串解析，非法输入返回 {@code null}（对应 vanilla {@code ResourceLocation.tryParse}）。
     * <p>
     * 复用 {@link #create(String)} 的各版本解析路径，把它抛出的
     * {@code ResourceLocationException} 归一为 {@code null}——1.20.1 的单参构造器与
     * 1.21.1+ 的 {@code parse} 都是「非法即抛」，没有跨版本一致的 try 版本。
     */
    public static
    Identifier
    tryCreate(String location) {
        if (location == null) {
            return null;
        }
        try {
            return create(location);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * {@link PacketId} → vanilla 类型（加载器边界转换）。
     * 业务代码持有 {@link PacketId}，仅在注册/发送等需要 vanilla 类型的位置调用本方法。
     */
    public static
    Identifier
    vanilla(PacketId id) {
        return create(id.namespace(), id.path());
    }

    /**
     * vanilla 类型 → {@link PacketId}（业务侧收口用；getNamespace/getPath 两版一致）。
     */
    public static PacketId toPacketId(
    Identifier
    id) {
        return new PacketId(id.getNamespace(), id.getPath());
    }
}
