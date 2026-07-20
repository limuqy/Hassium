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
     * 1.21.9+: 用 pack(Strategy) 规范化，产出不依赖 palette 排列的字节。
     *   解决 PalettedContainerFactory 重构后 chunk 重新加载 palette 排列变化导致 hash 不匹配。
     * 1.20.1-1.21.8: 用 section.write() 字节（palette 排列稳定，命中率正常）。
     *
     * @param section 要哈希的 section
     * @param out     哈希输出流（如 HashingOutputStream）
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
        // 1.20.1-1.21.8: section.write() 字节
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            section.write(buf);
            byte[] sectionBytes = new byte[buf.readableBytes()];
            buf.getBytes(0, sectionBytes);
            out.write(sectionBytes);
        } finally {
            buf.release();
        }
#endif
    }
}
