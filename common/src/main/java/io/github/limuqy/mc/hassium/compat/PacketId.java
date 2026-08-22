package io.github.limuqy.mc.hassium.compat;

/**
 * 自定义包通道标识的稳定值类型（namespace + path 纯字符串对）。
 * <p>
 * 存在目的：vanilla 的 {@code ResourceLocation}（1.21.11+ 更名 {@code Identifier}）
 * 类名随版本切换，业务代码一旦把它当字段/签名类型就会把 {@code #if MC_VER}
 * 带回去。本类型零版本分支；仅在加载器边界经
 * {@link ResourceLocationCompat#vanilla(PacketId)} 转为 vanilla 类型。
 *
 * @param namespace 命名空间（如 "hassium"）
 * @param path      路径（如 "chunk_hash_s2c"）
 */
public record PacketId(String namespace, String path) {

    public PacketId {
        if (namespace == null || namespace.isEmpty()) {
            throw new IllegalArgumentException("namespace must not be empty");
        }
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("path must not be empty");
        }
    }

    /** 完整 id 字符串（"namespace:path"），与 vanilla toString 形态一致。 */
    public String fullId() {
        return namespace + ":" + path;
    }

    /** 从 "namespace:path" 解析。 */
    public static PacketId parse(String fullId) {
        int i = fullId.indexOf(':');
        if (i < 0) {
            return new PacketId("minecraft", fullId);
        }
        return new PacketId(fullId.substring(0, i), fullId.substring(i + 1));
    }

    @Override
    public String toString() {
        return fullId();
    }
}
