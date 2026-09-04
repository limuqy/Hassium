package io.github.limuqy.mc.hassium.config;

import java.util.ArrayList;
import java.util.List;

/** Converts the typed common configuration model to and from the loader-neutral schema values. */
public final class ConfigSnapshotAdapter {
    private ConfigSnapshotAdapter() {
    }
    public static ConfigValues toValues(HassiumConfig config) {
        ConfigValues values = ConfigValues.defaults(ConfigSchema.entries());
        HassiumConfig.ChunkCoreConfig chunk = config.chunk();
        HassiumConfig.MasterCoreConfig master = config.master();
        values = values.with(ConfigSchema.CHUNK_ENABLED, chunk.enabled())
                .with(ConfigSchema.CHUNK_MAX_SIZE_MB, chunk.maxSizeMb())
                .with(ConfigSchema.CHUNK_COMPRESSION_LEVEL, chunk.compressionLevel())
                .with(ConfigSchema.CHUNK_HOT_SCORE_THRESHOLD, chunk.hotScoreThreshold())
                .with(ConfigSchema.CHUNK_RECENCY_WEIGHT, chunk.recencyWeight())
                .with(ConfigSchema.CHUNK_FREQUENCY_WEIGHT, chunk.frequencyWeight())
                .with(ConfigSchema.CHUNK_CLEANUP_INTERVAL_TICKS, chunk.cleanupIntervalTicks())
                .with(ConfigSchema.CHUNK_TARGET_SIZE_MB, chunk.targetSizeMb())
                .with(ConfigSchema.CHUNK_MIN_CLEANUP_BATCH_SIZE, chunk.minCleanupBatchSize())
                .with(ConfigSchema.CHUNK_SECTION_DELTA_ENABLED, chunk.sectionDeltaEnabled())
                .with(ConfigSchema.CHUNK_VIEW_DISTANCE_EXTENSION_ENABLED, chunk.viewDistanceExtensionEnabled())
                .with(ConfigSchema.CHUNK_MAX_RENDER_DISTANCE, chunk.maxRenderDistance())
                .with(ConfigSchema.CHUNK_OVD_UNLOAD_DELAY_SECS, chunk.ovdUnloadDelaySecs())
                .with(ConfigSchema.CHUNK_UNLOAD_DELAY_SECS, chunk.unloadDelaySecs())
                .with(ConfigSchema.CHUNK_MAX_CHUNKS_PER_FRAME, chunk.maxChunksPerFrame())
                .with(ConfigSchema.CHUNK_MAIN_THREAD_CHUNK_BUDGET_MS, chunk.mainThreadChunkBudgetMs())
                .with(ConfigSchema.CHUNK_SEED_GEN_THREADS, chunk.seedGenThreads())
                .with(ConfigSchema.CHUNK_OVD_LOCAL_GENERATION, chunk.ovdLocalGeneration())
                .with(ConfigSchema.CLIENT_CHUNK_SEED_GEN_ENABLED, chunk.seedGenEnabled())
                .with(ConfigSchema.CHUNK_LIGHT_STRIP, chunk.lightStrip());
        HassiumConfig.DebugConfig debug = config.debug();
        values = values.with(ConfigSchema.CLIENT_DEBUG_METADATA, debug.metadataLogging())
                .with(ConfigSchema.CLIENT_DEBUG_DISPATCHER, debug.dispatcherLogging())
                .with(ConfigSchema.CLIENT_DEBUG_ASYNC, debug.asyncLogging())
                .with(ConfigSchema.CLIENT_DEBUG_COMPRESSION, debug.compressionLogging())
                .with(ConfigSchema.CLIENT_DEBUG_CHUNK_APPLY, debug.chunkApplyLogging())
                .with(ConfigSchema.CLIENT_DEBUG_NETWORK, debug.networkLogging())
                .with(ConfigSchema.CLIENT_DEBUG_CACHE, debug.cacheLogging())
                .with(ConfigSchema.CLIENT_DEBUG_LIGHT_VERIFY, debug.lightVerify())
                .with(ConfigSchema.CLIENT_DEBUG_NETWORK_METRICS, debug.networkMetricsEnabled())
                .with(ConfigSchema.CLIENT_DEBUG_NETWORK_METRICS_AUTO_RESET, debug.networkMetricsAutoReset())
                .with(ConfigSchema.SERVER_DEBUG_DISPATCHER, debug.dispatcherLogging())
                .with(ConfigSchema.SERVER_DEBUG_ASYNC, debug.asyncLogging())
                .with(ConfigSchema.SERVER_DEBUG_COMPRESSION, debug.compressionLogging())
                .with(ConfigSchema.SERVER_DEBUG_CHUNK_APPLY, debug.chunkApplyLogging())
                .with(ConfigSchema.SERVER_DEBUG_NETWORK, debug.networkLogging())
                .with(ConfigSchema.SERVER_DEBUG_DATAPLANE, debug.dataplaneLogging());
        values = values.with(ConfigSchema.STORAGE_ENABLED, config.storage().enabled())
                .with(ConfigSchema.STORAGE_ZSTD_LEVEL, config.storage().zstdLevel())
                .with(ConfigSchema.MASTER_ENABLED, master.enabled())
                .with(ConfigSchema.MASTER_COMPRESSION_LEVEL, master.compressionLevel())
                .with(ConfigSchema.MASTER_USE_CONTEXT_COMPRESSION, master.useContextCompression())
                .with(ConfigSchema.MASTER_PACKET_AGGREGATION, master.enablePacketAggregation())
                .with(ConfigSchema.MASTER_AGGREGATION_MIN_BATCH, master.aggregationMinBatchSize())
                .with(ConfigSchema.MASTER_AGGREGATION_MAX_WAIT, (long) master.aggregationMaxWaitTimeMs())
                .with(ConfigSchema.MASTER_AGGREGATION_MAX_SIZE, master.aggregationMaxSize())
                .with(ConfigSchema.MASTER_COMPACT_HEADER, master.enableCompactHeader())
                .with(ConfigSchema.MASTER_COMPRESSION_BLACKLIST, new ArrayList<>(master.compressionBlacklist()))
                .with(ConfigSchema.MASTER_METRICS_ENABLED, master.metricsEnabled())
                .with(ConfigSchema.MASTER_MAX_CHUNKS_PER_TICK, master.maxChunksPerTick())
                .with(ConfigSchema.MASTER_SERVER_PUSH_THREADS, master.serverChunkPushThreads())
                .with(ConfigSchema.SERVER_CHUNK_SEED_GEN_ENABLED, chunk.seedGenEnabled());

        HassiumConfig.CompatConfig compat = config.compat();
        values = values.with(ConfigSchema.COMPAT_REQUIRE_CLIENT_MOD, compat.requireClientMod())
                .with(ConfigSchema.COMPAT_AUTO_DOWNGRADE, compat.autoDowngradeOnError());
        return values.with(ConfigSchema.SERVER_DEBUG_DISPATCHER, debug.dispatcherLogging())
                .with(ConfigSchema.SERVER_DEBUG_ASYNC, debug.asyncLogging())
                .with(ConfigSchema.SERVER_DEBUG_COMPRESSION, debug.compressionLogging())
                .with(ConfigSchema.SERVER_DEBUG_CHUNK_APPLY, debug.chunkApplyLogging())
                .with(ConfigSchema.SERVER_DEBUG_NETWORK, debug.networkLogging())
                .with(ConfigSchema.SERVER_DEBUG_DATAPLANE, debug.dataplaneLogging());
    }

    public static HassiumConfig fromValues(ConfigValues values) {
        return fromValues(values, false);
    }

    /**
     * Restores a runtime snapshot from schema values, choosing CLIENT or SERVER {@code debug.*}
     * keys according to the running physical side. Dual-scoped flags (dispatcher / async /
     * compression / chunkApply / network) read the matching side; client-only flags
     * (metadata / cache / lightVerify) and the server-only flag (dataplane) always come
     * from their own schema key (the other side keeps the default {@code false}).
     * The same side-routing applies to the double-scoped {@code chunk.seedGenEnabled}.
     */
    public static HassiumConfig fromValues(ConfigValues values, boolean physicalClient) {
        HassiumConfig.ChunkCoreConfig chunk = new HassiumConfig.ChunkCoreConfig(
                values.get(ConfigSchema.CHUNK_ENABLED), values.get(ConfigSchema.CHUNK_MAX_SIZE_MB),
                values.get(ConfigSchema.CHUNK_COMPRESSION_LEVEL), values.get(ConfigSchema.CHUNK_HOT_SCORE_THRESHOLD),
                values.get(ConfigSchema.CHUNK_RECENCY_WEIGHT), values.get(ConfigSchema.CHUNK_FREQUENCY_WEIGHT),
                values.get(ConfigSchema.CHUNK_CLEANUP_INTERVAL_TICKS), values.get(ConfigSchema.CHUNK_TARGET_SIZE_MB),
                values.get(ConfigSchema.CHUNK_MIN_CLEANUP_BATCH_SIZE), values.get(ConfigSchema.CHUNK_SECTION_DELTA_ENABLED),
                values.get(ConfigSchema.CHUNK_VIEW_DISTANCE_EXTENSION_ENABLED),
                values.get(ConfigSchema.CHUNK_MAX_RENDER_DISTANCE), values.get(ConfigSchema.CHUNK_OVD_UNLOAD_DELAY_SECS),
                values.get(ConfigSchema.CHUNK_UNLOAD_DELAY_SECS), values.get(ConfigSchema.CHUNK_MAX_CHUNKS_PER_FRAME),
                values.get(ConfigSchema.CHUNK_MAIN_THREAD_CHUNK_BUDGET_MS), values.get(ConfigSchema.CHUNK_SEED_GEN_THREADS),
                values.get(ConfigSchema.CHUNK_OVD_LOCAL_GENERATION),
                seedGenValue(values, physicalClient, ConfigSchema.CLIENT_CHUNK_SEED_GEN_ENABLED, ConfigSchema.SERVER_CHUNK_SEED_GEN_ENABLED),
                values.get(ConfigSchema.CHUNK_LIGHT_STRIP));
        HassiumConfig.MasterCoreConfig master = new HassiumConfig.MasterCoreConfig(
                values.get(ConfigSchema.MASTER_ENABLED), values.get(ConfigSchema.MASTER_COMPRESSION_LEVEL),
                values.get(ConfigSchema.MASTER_USE_CONTEXT_COMPRESSION), values.get(ConfigSchema.MASTER_PACKET_AGGREGATION),
                values.get(ConfigSchema.MASTER_AGGREGATION_MIN_BATCH), values.get(ConfigSchema.MASTER_AGGREGATION_MAX_WAIT),
                values.get(ConfigSchema.MASTER_AGGREGATION_MAX_SIZE), values.get(ConfigSchema.MASTER_COMPACT_HEADER),
                SetCopy.copy(values.get(ConfigSchema.MASTER_COMPRESSION_BLACKLIST)), values.get(ConfigSchema.MASTER_METRICS_ENABLED),
                values.get(ConfigSchema.MASTER_MAX_CHUNKS_PER_TICK), values.get(ConfigSchema.MASTER_SERVER_PUSH_THREADS));
        HassiumConfig.CompatConfig compat = new HassiumConfig.CompatConfig(
                values.get(ConfigSchema.COMPAT_REQUIRE_CLIENT_MOD), values.get(ConfigSchema.COMPAT_AUTO_DOWNGRADE));
        HassiumConfig.DebugConfig debug = new HassiumConfig.DebugConfig(
                values.get(ConfigSchema.CLIENT_DEBUG_METADATA),
                debugValue(values, physicalClient, ConfigSchema.CLIENT_DEBUG_DISPATCHER, ConfigSchema.SERVER_DEBUG_DISPATCHER),
                debugValue(values, physicalClient, ConfigSchema.CLIENT_DEBUG_ASYNC, ConfigSchema.SERVER_DEBUG_ASYNC),
                debugValue(values, physicalClient, ConfigSchema.CLIENT_DEBUG_COMPRESSION, ConfigSchema.SERVER_DEBUG_COMPRESSION),
                debugValue(values, physicalClient, ConfigSchema.CLIENT_DEBUG_CHUNK_APPLY, ConfigSchema.SERVER_DEBUG_CHUNK_APPLY),
                debugValue(values, physicalClient, ConfigSchema.CLIENT_DEBUG_NETWORK, ConfigSchema.SERVER_DEBUG_NETWORK),
                values.get(ConfigSchema.CLIENT_DEBUG_CACHE),
                values.get(ConfigSchema.SERVER_DEBUG_DATAPLANE),
                values.get(ConfigSchema.CLIENT_DEBUG_LIGHT_VERIFY),
                values.get(ConfigSchema.CLIENT_DEBUG_NETWORK_METRICS),
                values.get(ConfigSchema.CLIENT_DEBUG_NETWORK_METRICS_AUTO_RESET));
        return new HassiumConfig(new HassiumConfig.StorageConfig(
                values.get(ConfigSchema.STORAGE_ENABLED), values.get(ConfigSchema.STORAGE_ZSTD_LEVEL)),
                chunk, master, compat, debug);
    }
    private static boolean debugValue(ConfigValues values, boolean physicalClient,
                                       ConfigKey<Boolean> clientKey, ConfigKey<Boolean> serverKey) {
        return values.get(physicalClient ? clientKey : serverKey);
    }

    private static boolean seedGenValue(ConfigValues values, boolean physicalClient,
                                        ConfigKey<Boolean> clientKey, ConfigKey<Boolean> serverKey) {
        return values.get(physicalClient ? clientKey : serverKey);
    }

    private static final class SetCopy {
        static java.util.Set<String> copy(List<String> values) {
            return java.util.Set.copyOf(values);
        }
    }
}
