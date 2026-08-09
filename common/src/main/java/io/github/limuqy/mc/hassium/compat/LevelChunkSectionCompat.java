package io.github.limuqy.mc.hassium.compat;

import java.io.IOException;
import java.io.OutputStream;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunkSection;
#if MC_VER >= MC_1_21_9
import java.io.DataOutputStream;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;
#endif

/**
 * LevelChunkSection 构造兼容层。
 * <p>
 * 1.21.8-: {@code new LevelChunkSection(biomeRegistry)}
 * 1.21.9+: {@code new LevelChunkSection(PalettedContainerFactory.create(registryAccess))}
 */
public final class LevelChunkSectionCompat {
    private LevelChunkSectionCompat() {}

    /**
     * 创建用于读写 section 字节流的临时 LevelChunkSection。
     */
    public static LevelChunkSection create(RegistryAccess registryAccess) {
#if MC_VER < MC_1_21_9
        Registry<Biome> biomeRegistry = RegistryCompat.getBiomeRegistry(registryAccess);
        return new LevelChunkSection(biomeRegistry);
#else
        return new LevelChunkSection(PalettedContainerFactory.create(registryAccess));
#endif
    }

    /**
     * 将 section 内容写入 OutputStream 用于哈希计算。
     * <p>
     * 全版本统一规范化语义：逐位置写 blockState ID，产出不依赖 palette 排列的字节。
     * 1.21.9+ 用 pack(Strategy)（palette entries + storage longs，同样规范化）；
     * 1.20.1-1.21.8 用逐位置写 BlockState ID（对齐 pack 的"只依赖 block-at-position"语义）。
     * <p>
     * 背景（1.20.1 实测）：服务端对同一 chunk 会在不同时刻构建 packet（trackChunk 拦截时
     * 算 hash 的包 vs drain 现场构建发送的包），HashMapPalette 的排列随构建时序变化；
     * section.write() 原始字节含 palette 序 → 同一内容两次 hash 不同 → 客户端 packetHash
     * 与服务端广播 hash 伪 MISMATCH（R1/R2 remote hash 均与客户端稳定字节不符）。逐位置
     * 规范化与 palette 表示无关，磁盘/内存/packet 三条 hash 路径全部一致。
     */
    public static void writeSectionForHash(LevelChunkSection section, OutputStream out) throws IOException {
#if MC_VER >= MC_1_21_9
        // 1.21.9+: pack(Strategy) 规范化
        Strategy<BlockState> strategy = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
        PalettedContainerRO.PackedData<BlockState> packed = section.getStates().pack(strategy);
        DataOutputStream dout = new DataOutputStream(out);
        for (BlockState state : packed.paletteEntries()) {
            dout.writeInt(Block.BLOCK_STATE_REGISTRY.getId(state));
        }
        final DataOutputStream fdout = dout;
        packed.storage().ifPresent(s -> s.forEachOrdered(v -> {
            try {
                fdout.writeLong(v);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }));
#else
        // 1.20.1-1.21.8: 逐位置写 BlockState ID（规范化；与 palette 排列无关）。
        // 注：旧实现 section.write() 字节对 palette 排列敏感——服务端两次构建 packet
        // 的 palette 排列不同 → 同一内容 hash 不同（伪 MISMATCH），见上方注释。
        java.io.DataOutputStream dout = new java.io.DataOutputStream(out);
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    dout.writeInt(net.minecraft.core.registries.BuiltInRegistries.BLOCK
                            .getId(section.getBlockState(x, y, z).getBlock()));
                }
            }
        }
#endif
    }
}
