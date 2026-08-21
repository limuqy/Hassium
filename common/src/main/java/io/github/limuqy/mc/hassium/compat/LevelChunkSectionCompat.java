package io.github.limuqy.mc.hassium.compat;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
#if MC_VER >= MC_1_21_9
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

#if MC_VER >= MC_1_21_9
    /**
     * review-fix: T8-28: Strategy 只依赖全局内置注册表 {@link Block#BLOCK_STATE_REGISTRY}，
     * 与调用方 registryAccess 无关——原实现每次 writeSectionForHash 重建（哈希计算高频），
     * 缓存为 static final。
     */
    private static final Strategy<BlockState> BLOCK_STATES_STRATEGY =
            Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
#endif

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
     * 全版本写完整 BlockState ID（含朝向/含水/作物 age 等属性），产出不依赖 palette
     * 排列的字节。BE NBT 不在此域。
     * 1.21.9+ 用 pack(Strategy)（palette entries + storage longs）；
     * 1.20.1–1.21.8 逐位置写 {@link Block#getId(net.minecraft.world.level.block.state.BlockState)}。
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
        PalettedContainerRO.PackedData<BlockState> packed = section.getStates().pack(BLOCK_STATES_STRATEGY);
        DataOutputStream dout = new DataOutputStream(out);
        for (BlockState state : packed.paletteEntries()) {
            dout.writeInt(Block.BLOCK_STATE_REGISTRY.getId(state));
        }
        // review-fix: T8-28: 不再用 RuntimeException 包装 IOException（会穿透只 catch IOException
        // 的调用方，见 ChunkContentHashUtil:190/258）——改普通迭代直抛 IOException。
        if (packed.storage().isPresent()) {
            java.util.PrimitiveIterator.OfLong it = packed.storage().get().iterator();
            while (it.hasNext()) {
                dout.writeLong(it.nextLong());
            }
        }
#else
        // 1.20.1–1.21.8: 逐位置写完整 BlockState ID（规范化；与 palette 排列无关）。
        // 不可写成 BLOCK.getId(state.getBlock())：会丢掉 facing/waterlogged/age 等属性。
        DataOutputStream dout = new DataOutputStream(out);
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    dout.writeInt(Block.getId(section.getBlockState(x, y, z)));
                }
            }
        }
#endif
    }

    /**
     * 平面综合征 / 方块列表用的 BlockState 数值 ID，与 1.20.1–1.21.8
     * {@link #writeSectionForHash} 的 {@code Block.getId} 口径一致。
     */
    public static int blockStateId(BlockState state) {
#if MC_VER >= MC_1_21_9
        return Block.BLOCK_STATE_REGISTRY.getId(state);
#else
        return Block.getId(state);
#endif
    }

    /** 方块列表 apply：由数值 ID 还原 BlockState（非法 ID 返回 null）。 */
    public static BlockState blockStateFromId(int id) {
        return Block.BLOCK_STATE_REGISTRY.byId(id);
    }
}
