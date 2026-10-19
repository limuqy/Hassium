package io.github.limuqy.mc.hassium.compat;

import com.mojang.serialization.Lifecycle;
import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.mixin.shadow.ReloadableServerResourcesFactory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.WorldStem;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.DataPackConfig;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.minecraft.server.permissions.LevelBasedPermissionSet;

/** Cache/light assembly uses the real connection's registries, never local worldgen datapacks. */
public final class ShadowCacheBootstrap {
    private static Object connection;
    private static final Map<ResourceKey<LevelStem>, Holder<DimensionType>> observedTypes = new LinkedHashMap<>();

    private ShadowCacheBootstrap() {}

    public static synchronized void reset() {
        connection = null;
        observedTypes.clear();
    }

    /** Remember authoritative types only within one connection; a dimension ID is not a type ID. */
    public static synchronized void observe(Minecraft mc) {
        if (mc == null || mc.level == null || mc.getConnection() == null) return;
        if (connection != mc.getConnection()) {
            connection = mc.getConnection();
            observedTypes.clear();
        }
        observedTypes.put(ResourceKey.create(Registries.LEVEL_STEM,
                        ResourceLocationCompat.create(LevelCompat.getDimensionId(mc.level))),
                mc.level.dimensionTypeRegistration());
    }

    public static WorldStem create(Minecraft mc) {
        return io.github.limuqy.mc.hassium.shadow.server.ShadowAssemblyScope.cacheOnly(() -> createWithinScope(mc));
    }

    private static WorldStem createWithinScope(Minecraft mc) {
        if (mc == null || mc.level == null || mc.getConnection() == null) {
            throw new IllegalStateException("Cache shadow requires the logged-in connection's registries");
        }
        observe(mc);
        RegistryAccess.Frozen dynamic = cacheRegistries(mc.level.registryAccess(),
                io.github.limuqy.mc.hassium.platform.Services.PLATFORM.cacheDataPackRegistryKeys());
        var layers = RegistryLayer.createRegistryAccess().replaceFrom(worldLayer(), dynamic);
        var biomes = RegistryCompat.getBiomeRegistry(dynamic);
        var plains = RegistryCompat.getHolderOrThrow(biomes, Biomes.PLAINS);
        Map<ResourceKey<LevelStem>, LevelStem> stems = new LinkedHashMap<>();
        Registry<DimensionType> types = registry(dynamic, Registries.DIMENSION_TYPE);
        add(stems, LevelStem.OVERWORLD, RegistryCompat.getHolderOrThrow(types, BuiltinDimensionTypes.OVERWORLD), plains);
        add(stems, LevelStem.NETHER, RegistryCompat.getHolderOrThrow(types, BuiltinDimensionTypes.NETHER), plains);
        add(stems, LevelStem.END, RegistryCompat.getHolderOrThrow(types, BuiltinDimensionTypes.END), plains);
        synchronized (ShadowCacheBootstrap.class) {
            observedTypes.forEach((key, type) -> add(stems, key, type, plains));
        }
        var worldDimensions = new WorldDimensions(Map.copyOf(stems));
        var complete = worldDimensions.bake(new MappedRegistry<LevelStem>(Registries.LEVEL_STEM, Lifecycle.stable()).freeze());
        layers = layers.replaceFrom(RegistryLayer.DIMENSIONS, complete.dimensionsRegistryAccess());
        layers = layers.replaceFrom(RegistryLayer.RELOADABLE, cacheReloadableRegistries(mc.level.registryAccess()));
        var features = mc.level.enabledFeatures();
        var settings = new LevelSettings("HassiumCache", GameType.CREATIVE,
                new LevelSettings.DifficultySettings(Difficulty.NORMAL, false, false), true,
                new WorldDataConfiguration(DataPackConfig.DEFAULT, features));
        var worldData = new PrimaryLevelData(settings, complete.specialWorldProperty(), complete.lifecycle());
        worldData.setInitialized(true);
        ReloadableServerResources resources;
#if MC_VER >= MC_26_3
        resources = ReloadableServerResourcesFactory.hassium$create(
                new net.minecraft.server.ReloadableServerRegistries.LoadResult(layers, layers.compositeAccess()), features,
                Commands.CommandSelection.DEDICATED, List.of(), LevelBasedPermissionSet.OWNER, List.of());
#else
        resources = ReloadableServerResourcesFactory.hassium$create(layers, layers.compositeAccess(), features,
                Commands.CommandSelection.DEDICATED, List.of(), LevelBasedPermissionSet.OWNER, List.of());
#endif
        Constants.LOG.info("[SHADOW-CACHE] assembly=connection registries={} dimensions={} worldgen=false datapackReload=false",
                dynamic.registries().count(), stems.keySet());
        var gen = new net.minecraft.world.level.levelgen.WorldGenSettings(new WorldOptions(0L, false, false),
                new WorldDimensions(complete.dimensions()));
        return new WorldStem(new MultiPackResourceManager(PackType.SERVER_DATA, List.of()), resources, layers,
                new net.minecraft.world.level.storage.LevelDataAndDimensions.WorldDataAndGenSettings(worldData, gen));
    }

    /** Unsynced worldgen registries are empty lookup tables, not locally decoded mod generators. */
    static RegistryAccess.Frozen cacheRegistries(RegistryAccess synced) {
        return cacheRegistries(synced, List.of());
    }

    static RegistryAccess.Frozen cacheRegistries(RegistryAccess synced,
            Iterable<ResourceKey<? extends Registry<?>>> declaredKeys) {
        Map<ResourceKey<? extends Registry<?>>, Registry<?>> registries = new LinkedHashMap<>();
        var staticAccess = RegistryLayer.createRegistryAccess().compositeAccess();
        var staticKeys = staticAccess.registries().map(RegistryAccess.RegistryEntry::key)
                .collect(java.util.stream.Collectors.toSet());
        var reloadableKeys = reloadableKeys().stream()
                .collect(java.util.stream.Collectors.toSet());
        for (var data : worldRegistryData()) {
            if (!staticKeys.contains(data.key())) registries.put(data.key(), emptyRegistry(data.key()));
        }
        for (var key : declaredKeys) {
            if (!staticKeys.contains(key) && !reloadableKeys.contains(key) && !key.equals(Registries.LEVEL_STEM))
                registries.putIfAbsent(key, emptyRegistry(key));
        }
        // Even a flat ChunkMap constructs RandomState/SurfaceSystem and requires vanilla noise constants.
        registries.put(Registries.NOISE, vanillaNoises());
        synced.registries().filter(entry -> !staticKeys.contains(entry.key()) && !reloadableKeys.contains(entry.key())
                        && !entry.key().equals(Registries.LEVEL_STEM))
                .forEach(entry -> registries.put(entry.key(), entry.value()));
        return new RegistryAccess.ImmutableRegistryAccess(registries).freeze();
    }

    static RegistryAccess.Frozen cacheReloadableRegistries(RegistryAccess synced) {
        Map<ResourceKey<? extends Registry<?>>, Registry<?>> registries = new LinkedHashMap<>();
        reloadableKeys().forEach(key -> registries.put(key, emptyRegistry(key)));
        synced.registries().filter(entry -> registries.containsKey(entry.key()))
                .forEach(entry -> registries.put(entry.key(), entry.value()));
        return new RegistryAccess.ImmutableRegistryAccess(registries).freeze();
    }

    static RegistryLayer worldLayer() {
#if MC_VER >= MC_26_3
        return RegistryLayer.WORLD;
#else
        return RegistryLayer.WORLDGEN;
#endif
    }

    private static List<RegistryDataLoader.RegistryData<?>> worldRegistryData() {
#if MC_VER >= MC_26_3
        return RegistryDataLoader.WORLD_REGISTRIES;
#else
        return RegistryDataLoader.WORLDGEN_REGISTRIES;
#endif
    }

    static List<ResourceKey<? extends Registry<?>>> reloadableKeys() {
        var keys = new java.util.ArrayList<ResourceKey<? extends Registry<?>>>();
#if MC_VER >= MC_26_3
        RegistryDataLoader.RELOADABLE_REGISTRIES.forEach(data -> keys.add(data.key()));
#else
        net.minecraft.world.level.storage.loot.LootDataType.values().forEach(data -> keys.add(data.registryKey()));
#endif
        return List.copyOf(keys);
    }

    private static Registry<?> vanillaNoises() {
        var noise = new MappedRegistry<>(Registries.NOISE, Lifecycle.stable());
        net.minecraft.data.worldgen.NoiseData.bootstrap(new NoiseBootstrapContext(noise));
        return noise.freeze();
    }

#if MC_VER >= MC_26_3
    private record NoiseBootstrapContext(MappedRegistry<net.minecraft.world.level.levelgen.synth.NormalNoise> registry)
            implements net.minecraft.data.worldgen.BootstrapContext<net.minecraft.world.level.levelgen.synth.NormalNoise> {
        @Override
        public Holder.Reference<net.minecraft.world.level.levelgen.synth.NormalNoise> register(
                ResourceKey<net.minecraft.world.level.levelgen.synth.NormalNoise> key,
                net.minecraft.world.level.levelgen.synth.NormalNoise value) {
            return registry.register(key, value, net.minecraft.core.RegistrationInfo.BUILT_IN);
        }
#else
    private record NoiseBootstrapContext(MappedRegistry<net.minecraft.world.level.levelgen.synth.NormalNoise.NoiseParameters> registry)
            implements net.minecraft.data.worldgen.BootstrapContext<net.minecraft.world.level.levelgen.synth.NormalNoise.NoiseParameters> {
        @Override
        public Holder.Reference<net.minecraft.world.level.levelgen.synth.NormalNoise.NoiseParameters> register(
                ResourceKey<net.minecraft.world.level.levelgen.synth.NormalNoise.NoiseParameters> key,
                net.minecraft.world.level.levelgen.synth.NormalNoise.NoiseParameters value, Lifecycle lifecycle) {
            return registry.register(key, value, new net.minecraft.core.RegistrationInfo(Optional.empty(), lifecycle));
        }
#endif
#if MC_VER >= MC_26_3
        @Override
        public <T> java.util.stream.Stream<Holder.Reference<T>> listContextElements(
                ResourceKey<? extends Registry<? extends T>> key) {
            throw new IllegalStateException("Vanilla noise bootstrap unexpectedly listed " + key);
        }
#endif
        @Override
        public <T> net.minecraft.core.HolderGetter<T> lookup(ResourceKey<? extends Registry<? extends T>> key) {
            throw new IllegalStateException("Vanilla noise bootstrap unexpectedly requested " + key);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Registry<?> emptyRegistry(ResourceKey<? extends Registry<?>> key) {
        // No values are inserted: the erased element type cannot escape through an element lookup.
        return new MappedRegistry((ResourceKey) key, Lifecycle.stable()).freeze();
    }

    @SuppressWarnings("unchecked")
    private static <T> Registry<T> registry(RegistryAccess access, ResourceKey<? extends Registry<T>> key) {
        return (Registry<T>) access.lookupOrThrow(key);
    }

    private static void add(Map<ResourceKey<LevelStem>, LevelStem> stems, ResourceKey<LevelStem> key,
            Holder<DimensionType> type, Holder<net.minecraft.world.level.biome.Biome> biome) {
        // No terrain/structures/features: all chunks are supplied by the authority or disk cache.
        stems.put(key, cacheStem(type, biome));
    }

    static LevelStem cacheStem(Holder<DimensionType> type, Holder<net.minecraft.world.level.biome.Biome> biome) {
        var flat = new FlatLevelGeneratorSettings(Optional.of(HolderSet.direct()), biome, List.of());
        return new LevelStem(type, new FlatLevelSource(flat));
    }
}
