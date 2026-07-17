package io.github.limuqy.mc.hassium.compat;

/**
 * 区块序列化入口版本说明（Mixin 目标类切换）。
 * <p>
 * 1.21.1-: 静态方法在 {@code ChunkSerializer}
 * 1.21.2+: 实例方法在 {@code SerializableChunkData}
 * <p>
 * Mixin 必须直接写在 {@code @Mixin} 注解上，无法完全消除该类上的 {@code #if}；
 * 业务代码勿再复制此分界，改动时只改 {@code MixinChunkSerializer}。
 */
public final class ChunkDataCompat {
    private ChunkDataCompat() {}

    /**
     * 当前版本是否使用 SerializableChunkData 作为序列化入口。
     */
    public static boolean usesSerializableChunkData() {
#if MC_VER < MC_1_21_2
        return false;
#else
        return true;
#endif
    }
}
