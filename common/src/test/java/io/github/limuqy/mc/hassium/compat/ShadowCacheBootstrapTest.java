package io.github.limuqy.mc.hassium.compat;

import com.mojang.serialization.Lifecycle;
import java.util.List;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ShadowCacheBootstrapTest {
    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test void suppliesEmptyWorldgenLookupsWithoutDecodingGenerators() {
        var result = ShadowCacheBootstrap.cacheRegistries(RegistryAccess.EMPTY);
        assertNotNull(result.lookupOrThrow(Registries.NOISE).getOrThrow(net.minecraft.world.level.levelgen.Noises.CLAY_BANDS_OFFSET));
        assertDoesNotThrow(() -> net.minecraft.world.level.levelgen.RandomState.create(
                net.minecraft.world.level.levelgen.NoiseGeneratorSettings.dummy(), result.lookupOrThrow(Registries.NOISE), 0L));
        assertEquals(0, result.lookupOrThrow(Registries.NOISE_SETTINGS).listElements().count());
        assertEquals(0, result.lookupOrThrow(Registries.STRUCTURE_SET).listElements().count());
        assertEquals(0, result.lookupOrThrow(Registries.WORLD_PRESET).listElements().count());
    }

    @Test void keepsSyncedCustomRegistriesAndTheirHolderIdentity() {
        var key = ResourceKey.<String>createRegistryKey(ResourceLocationCompat.create("fixture:shared"));
        var custom = new MappedRegistry<>(key, Lifecycle.stable()).freeze();
        var synced = new RegistryAccess.ImmutableRegistryAccess(List.of(custom)).freeze();
        var result = ShadowCacheBootstrap.cacheRegistries(synced);
        assertSame(custom, result.registries().filter(entry -> entry.key().equals(key)).findFirst().orElseThrow().value());
        assertEquals(1, synced.registries().count(), "Never add placeholders to the live connection");
    }

    @Test void syncedBiomeRegistryWinsOverEmptyPlaceholder() {
        var biomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable()).freeze();
        var result = ShadowCacheBootstrap.cacheRegistries(new RegistryAccess.ImmutableRegistryAccess(List.of(biomes)).freeze());
        assertSame(biomes, result.registries().filter(entry -> entry.key().equals(Registries.BIOME)).findFirst().orElseThrow().value());
    }

    @Test void declaredUnsyncedModRegistryHasAnEmptyLookupWithoutLoadingItsValues() {
        var key = ResourceKey.<String>createRegistryKey(ResourceLocationCompat.create("fixture:worldgen_modifier"));
        var result = ShadowCacheBootstrap.cacheRegistries(RegistryAccess.EMPTY, List.of(key));
        assertEquals(0, result.lookupOrThrow(key).listElements().count());
        var authoritative = new MappedRegistry<>(key, Lifecycle.stable()).freeze();
        var synced = new RegistryAccess.ImmutableRegistryAccess(List.of(authoritative)).freeze();
        result = ShadowCacheBootstrap.cacheRegistries(synced, List.of(key));
        assertSame(authoritative, result.registries().filter(entry -> entry.key().equals(key)).findFirst().orElseThrow().value());
    }

    @Test void doesNotDuplicateOrRebindGlobalStaticRegistries() {
        var synced = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        long before = BuiltInRegistries.BLOCK.size();
        var result = ShadowCacheBootstrap.cacheRegistries(synced);
        assertTrue(result.registries().noneMatch(entry -> entry.key().equals(Registries.BLOCK)));
        assertEquals(before, BuiltInRegistries.BLOCK.size());
    }

    @Test void cacheShellKeepsAuthoritativeDimensionTypeAndHasNoTerrainOrStructures() {
        var fixture = net.minecraft.data.registries.VanillaRegistries.createLookup();
        var type = fixture.lookupOrThrow(Registries.DIMENSION_TYPE)
                .getOrThrow(net.minecraft.world.level.dimension.BuiltinDimensionTypes.NETHER);
        var biome = fixture.lookupOrThrow(Registries.BIOME)
                .getOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS);
        var stem = ShadowCacheBootstrap.cacheStem(type, biome);
        assertSame(type, stem.type());
        assertFalse(stem.type().value().hasSkyLight());
        assertEquals(256, stem.type().value().height());
        var flat = (net.minecraft.world.level.levelgen.FlatLevelSource) stem.generator();
        assertTrue(flat.settings().getLayersInfo().isEmpty());
        assertEquals(0, flat.settings().structureOverrides().orElseThrow().stream().count());
    }

    @Test void cacheShellCanSaveLevelStemWithoutLocalFeatureOrGeneratorSettingsData() {
        var fixture = net.minecraft.data.registries.VanillaRegistries.createLookup();
        var biomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        var biome = register(biomes, net.minecraft.world.level.biome.Biomes.PLAINS,
                fixture.lookupOrThrow(Registries.BIOME).getOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS).value());
        var types = new MappedRegistry<>(Registries.DIMENSION_TYPE, Lifecycle.stable());
        var type = register(types, net.minecraft.world.level.dimension.BuiltinDimensionTypes.OVERWORLD,
                fixture.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(net.minecraft.world.level.dimension.BuiltinDimensionTypes.OVERWORLD).value());
        var synced = new RegistryAccess.ImmutableRegistryAccess(List.of(biomes.freeze(), types.freeze())).freeze();
        var access = net.minecraft.server.RegistryLayer.createRegistryAccess()
                .replaceFrom(net.minecraft.server.RegistryLayer.WORLDGEN, ShadowCacheBootstrap.cacheRegistries(synced))
                .compositeAccess();
        var encoded = net.minecraft.world.level.dimension.LevelStem.CODEC.encodeStart(
                net.minecraft.resources.RegistryOps.create(net.minecraft.nbt.NbtOps.INSTANCE, access),
                ShadowCacheBootstrap.cacheStem(type, biome));
        assertTrue(encoded.result().isPresent(), encoded::toString);
    }

    private static <T> net.minecraft.core.Holder.Reference<T> register(MappedRegistry<T> registry, ResourceKey<T> key, T value) {
#if MC_VER < MC_1_21_1
        return registry.register(key, value, Lifecycle.stable());
#else
        return registry.register(key, value, net.minecraft.core.RegistrationInfo.BUILT_IN);
#endif
    }
}
