package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.LevelStem;

/**
 * SeedGen 握手 S2C 尾部（append-only；三端 NetworkManager 复用）。
 * <p>
 * 布局（在 UDP dataplane tail 之后追加）：
 * <pre>
 *   long worldSeed        // 仅 seedGenEnabled=true 时为真实主世界 seed；否则 0（避免关功能仍泄露种子）
 *   varint stemLen + bytes // LevelStem NBT（0 = 未提供）
 *   boolean seedGenEnabled // 服务端 SeedGen 开关
 * </pre>
 * 旧客户端读到 UDP tail 结束即停，忽略尾部字节。
 * 开启 SeedGen 会向客户端下发世界种子，等同泄露服务端种子。
 */
public final class SeedGenTail {

    private SeedGenTail() {
    }

    /**
     * 编码服务端主世界 LevelStem 为 NBT 字节（失败返回 null，调用方写 0 长度）。
     */
    public static byte[] encodeLevelStemNbt(ServerLevel level) {
        try {
            RegistryAccess registryAccess = level.registryAccess();
            RegistryOps<net.minecraft.nbt.Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registryAccess);
            Registry<LevelStem> stems;
#if MC_VER < MC_1_21_2
            stems = registryAccess.registryOrThrow(Registries.LEVEL_STEM);
#else
            stems = (Registry<LevelStem>) registryAccess.lookupOrThrow(Registries.LEVEL_STEM);
#endif
            LevelStem stem;
#if MC_VER < MC_1_21_2
            stem = stems.get(ResourceKey.create(Registries.LEVEL_STEM,
                    level.dimension()
#if MC_VER < MC_1_21_11
                            .location()
#else
                            .identifier()
#endif
            ));
#else
            java.util.Optional<net.minecraft.core.Holder.Reference<LevelStem>> stemRef =
                    stems.get(ResourceKey.create(Registries.LEVEL_STEM,
                            level.dimension()
#if MC_VER < MC_1_21_11
                                    .location()
#else
                                    .identifier()
#endif
                    ));
            stem = stemRef.map(net.minecraft.core.Holder.Reference::value).orElse(null);
#endif
            if (stem == null) {
                return null;
            }
            var encoded = LevelStem.CODEC.encodeStart(ops, stem).result();
            if (encoded.isEmpty()) {
                return null;
            }
            FriendlyByteBuf tmp = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            try {
                tmp.writeNbt((CompoundTag) encoded.get());
                byte[] bytes = new byte[tmp.readableBytes()];
                tmp.readBytes(bytes);
                return bytes;
            } finally {
                tmp.release();
            }
        } catch (Exception e) {
            Constants.LOG.warn("Hassium: Failed to encode levelStem NBT", e);
            return null;
        }
    }

    /** 握手用世界种子：仅在 SeedGen 开启时下发真实 seed，否则 0。 */
    public static long handshakeWorldSeed(ServerLevel level, boolean enabled) {
        return enabled && level != null ? level.getSeed() : 0L;
    }

    /**
     * 追加 SeedGen 尾部（服务端调用；enabled = 服务端配置开关）。
     * enabled=false 时写 seed=0 且不附 LevelStem，避免关本地生成仍把种子发给客户端。
     */
    public static void writeS2C(FriendlyByteBuf response, ServerLevel level, boolean enabled) {
        response.writeLong(handshakeWorldSeed(level, enabled));
        byte[] stemNbt = enabled ? encodeLevelStemNbt(level) : null;
        response.writeVarInt(stemNbt != null ? stemNbt.length : 0);
        if (stemNbt != null) {
            response.writeBytes(stemNbt);
        }
        response.writeBoolean(enabled);
    }
}
