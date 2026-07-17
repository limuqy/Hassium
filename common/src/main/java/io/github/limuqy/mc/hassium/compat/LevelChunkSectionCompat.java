package io.github.limuqy.mc.hassium.compat;

import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunkSection;
#if MC_VER >= MC_1_21_9
import net.minecraft.world.level.chunk.PalettedContainerFactory;
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
}
