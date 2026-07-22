package io.github.limuqy.mc.hassium.cache.client;

import com.mojang.serialization.Codec;
import io.github.limuqy.mc.hassium.compat.ChunkPacketDataCompat;
import io.github.limuqy.mc.hassium.compat.CompoundTagCompat;
import io.github.limuqy.mc.hassium.compat.LevelChunkSectionCompat;
import io.github.limuqy.mc.hassium.compat.RegistryCompat;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
#if MC_VER >= MC_1_21_9
import net.minecraft.world.level.chunk.PalettedContainerFactory;
#endif
import net.minecraft.world.level.chunk.PalettedContainerRO;

/** Converts Hassium's packet-oriented cache NBT into vanilla chunk-storage NBT. Preserves cached light data when available. */
final class VanillaChunkNbtCompat {
    private VanillaChunkNbtCompat() {}

    static CompoundTag convert(CompoundTag cached, RegistryAccess registryAccess, int minSection) throws ConversionException {
        if (cached == null) throw new ConversionException("missing cached chunk NBT");
        Tag sectionsTag = cached.get("sections");
        if (!(sectionsTag instanceof ListTag cachedSections)) {
            throw new ConversionException("missing sections list");
        }

        CompoundTag result = new CompoundTag();
        result.putInt("xPos", CompoundTagCompat.getInt(cached, "x", 0));
        result.putInt("yPos", minSection);
        result.putInt("zPos", CompoundTagCompat.getInt(cached, "z", 0));
        result.putInt("DataVersion", dataVersion(cached));
        result.putString("Status", "minecraft:full");
        result.put("Heightmaps", compoundOrEmpty(cached, "heightmaps"));
        result.put("block_entities", listOrEmpty(cached, "block_entities"));
        result.put("block_ticks", new ListTag());
        result.put("fluid_ticks", new ListTag());
        result.put("PostProcessing", new ListTag());
        result.put("structures", new CompoundTag());

        boolean hasCachedLight = ChunkDiskCodec.isLightOn(cached);
        ListTag sections = new ListTag();
        for (int i = 0; i < cachedSections.size(); i++) {
            Tag tag = cachedSections.get(i);
            if (!(tag instanceof CompoundTag cachedSection)) {
                throw new ConversionException("section " + i + " is not a compound");
            }
            Tag data = cachedSection.get("data");
            if (!(data instanceof net.minecraft.nbt.ByteArrayTag)) {
                throw new ConversionException("section " + i + " has no data byte array");
            }
            LevelChunkSection section = LevelChunkSectionCompat.create(registryAccess);
            try {
                ChunkPacketDataCompat.readSectionInto(cachedSection, section, registryAccess);
                CompoundTag sectionNbt = encodeSection(section, minSection + i, registryAccess);
                if (hasCachedLight) {
                    copyLightTag(cachedSection, sectionNbt, "sky_light", "SkyLight");
                    copyLightTag(cachedSection, sectionNbt, "block_light", "BlockLight");
                }
                sections.add(sectionNbt);
            } catch (RuntimeException e) {
                throw new ConversionException("section " + i + " could not be decoded", e);
            }
        }
        result.put("sections", sections);
        return result;
    }

    private static CompoundTag encodeSection(LevelChunkSection section, int y, RegistryAccess registryAccess)
            throws ConversionException {
        CompoundTag result = new CompoundTag();
        result.putByte("Y", (byte) y);
#if MC_VER < MC_1_21_9
        Registry<Biome> biomes = RegistryCompat.getBiomeRegistry(registryAccess);
        put(result, "block_states", PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY,
                BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState()), section.getStates());
        put(result, "biomes", PalettedContainer.codecRO(biomes.asHolderIdMap(), biomes.holderByNameCodec(),
                PalettedContainer.Strategy.SECTION_BIOMES, biomes.getHolderOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS)),
                section.getBiomes());
#else
        PalettedContainerFactory factory = PalettedContainerFactory.create(registryAccess);
        put(result, "block_states", factory.blockStatesContainerCodec(), section.getStates());
        put(result, "biomes", factory.biomeContainerCodec(), section.getBiomes());
#endif
        return result;
    }

    private static <T> void put(CompoundTag target, String key, Codec<T> codec, T value) throws ConversionException {
        codec.encodeStart(NbtOps.INSTANCE, value).resultOrPartial(message -> {
            throw new ConversionFailure(message);
        }).ifPresentOrElse(tag -> target.put(key, tag), () -> {
            throw new ConversionFailure("codec returned no " + key);
        });
    }

    private static int dataVersion(CompoundTag cached) {
        return CompoundTagCompat.getInt(cached, "DataVersion", 0);
    }

    private static CompoundTag compoundOrEmpty(CompoundTag tag, String key) {
        Tag value = tag.get(key);
        return value instanceof CompoundTag compound ? compound : new CompoundTag();
    }

    private static ListTag listOrEmpty(CompoundTag tag, String key) {
        Tag value = tag.get(key);
        return value instanceof ListTag list ? list : new ListTag();
    }

    /** 从缓存 section 复制光照 tag 到原版 section（重命名 key）。 */
    private static void copyLightTag(CompoundTag src, CompoundTag dst, String srcKey, String dstKey) {
        Tag lightTag = src.get(srcKey);
        if (lightTag instanceof net.minecraft.nbt.ByteArrayTag bat) {
            byte[] data = bat.getAsByteArray();
            if (data.length == 2048) {
                dst.putByteArray(dstKey, data.clone());
            }
        }
    }

    static final class ConversionException extends Exception {
        ConversionException(String message) { super(message); }
        ConversionException(String message, Throwable cause) { super(message, cause); }
    }

    private static final class ConversionFailure extends RuntimeException {
        ConversionFailure(String message) { super(message); }
    }
}
