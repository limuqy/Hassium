package io.github.limuqy.mc.hassium.config;

import java.util.ArrayList;
import java.util.List;

/** Converts the typed common configuration model to and from the loader-neutral schema values. */
public final class ConfigSnapshotAdapter {
    private ConfigSnapshotAdapter() {
    }

    public static ConfigValues toValues(HassiumConfig config) {
        ConfigValues values = ConfigValues.defaults(ConfigSchema.entries());
        HassiumConfig.ClientCacheConfig cache = config.clientCache();
        values = values.with(ConfigSchema.CACHE_ENABLED, cache.enabled())
                .with(ConfigSchema.CACHE_MAX_SIZE_MB, cache.maxSizeMb())
                .with(ConfigSchema.CACHE_COMPRESSION_LEVEL, cache.cacheCompressionLevel())
                .with(ConfigSchema.CACHE_HOT_SCORE_THRESHOLD, cache.hotScoreThreshold())
                .with(ConfigSchema.CACHE_RECENCY_WEIGHT, cache.recencyWeight())
                .with(ConfigSchema.CACHE_FREQUENCY_WEIGHT, cache.frequencyWeight())
                .with(ConfigSchema.CACHE_CLEANUP_INTERVAL_TICKS, cache.cleanupIntervalTicks())
                .with(ConfigSchema.CACHE_TARGET_SIZE_MB, cache.targetCacheSizeMb())
                .with(ConfigSchema.CACHE_MIN_CLEANUP_BATCH_SIZE, cache.minCleanupBatchSize())
                .with(ConfigSchema.CACHE_VIEW_DISTANCE_EXTENSION_ENABLED, cache.viewDistanceExtensionEnabled())
                .with(ConfigSchema.CACHE_MAX_RENDER_DISTANCE, cache.maxRenderDistance())
                .with(ConfigSchema.CACHE_OVD_UNLOAD_DELAY_SECS, cache.ovdUnloadDelaySecs())
                .with(ConfigSchema.CACHE_SECTION_DELTA_ENABLED, cache.sectionDeltaEnabled())
                .with(ConfigSchema.CACHE_JOIN_BOOST_ENABLED, cache.joinBoostEnabled())
                .with(ConfigSchema.CACHE_ENTITY_SNAPSHOTS_ENABLED, cache.entitySnapshotsEnabled())
                .with(ConfigSchema.CACHE_LOAD_THREADS, cache.loadThreads())
                .with(ConfigSchema.CACHE_LIGHT_CACHE_ENABLED, cache.lightCacheEnabled())
                .with(ConfigSchema.CACHE_MAX_CHUNKS_PER_FRAME, cache.maxChunksPerFrame())
                .with(ConfigSchema.CACHE_MAIN_THREAD_BUDGET_MS, cache.mainThreadChunkBudgetMs())
                .with(ConfigSchema.CACHE_PARALLEL_LIGHT_ENGINE_ENABLED, cache.parallelLightEngineEnabled())
                .with(ConfigSchema.CACHE_PARALLEL_LIGHT_ENGINE_THREADS, cache.parallelLightEngineThreads())
                .with(ConfigSchema.CACHE_LIGHT_SYNC_MODE, cache.lightSyncMode());

        HassiumConfig.ClientNetworkConfig clientNet = config.clientNetwork();
        values = values.with(ConfigSchema.CLIENT_NETWORK_ENABLED, clientNet.enabled())
                .with(ConfigSchema.CLIENT_NETWORK_METRICS_ENABLED, clientNet.metricsEnabled())
                .with(ConfigSchema.CLIENT_NETWORK_METRICS_AUTO_RESET, clientNet.metricsAutoReset())
                .with(ConfigSchema.DATAPLANE_RECOVERY_FREEZE, clientNet.recoveryFreeze())
                .with(ConfigSchema.CLIENT_SEED_GEN_ENABLED, clientNet.seedGenEnabled());

        HassiumConfig.DebugConfig debug = config.debug();
        values = values.with(ConfigSchema.CLIENT_DEBUG_METADATA, debug.metadataLogging())
                .with(ConfigSchema.CLIENT_DEBUG_DISPATCHER, debug.dispatcherLogging())
                .with(ConfigSchema.CLIENT_DEBUG_ASYNC, debug.asyncLogging())
                .with(ConfigSchema.CLIENT_DEBUG_COMPRESSION, debug.compressionLogging())
                .with(ConfigSchema.CLIENT_DEBUG_CHUNK_APPLY, debug.chunkApplyLogging())
                .with(ConfigSchema.CLIENT_DEBUG_NETWORK, debug.networkLogging())
                .with(ConfigSchema.CLIENT_DEBUG_CACHE, debug.cacheLogging())
                .with(ConfigSchema.CLIENT_DEBUG_DATAPLANE, debug.dataplaneLogging())
                .with(ConfigSchema.CLIENT_DEBUG_LIGHT_VERIFY, debug.lightVerify());
        HassiumConfig.ServerNetworkConfig network = config.serverNetwork();
        values = values.with(ConfigSchema.STORAGE_ENABLED, config.storage().enabled())
                .with(ConfigSchema.STORAGE_MODE, config.storage().mode())
                .with(ConfigSchema.STORAGE_ZSTD_LEVEL, config.storage().zstdLevel())
                .with(ConfigSchema.SERVER_NETWORK_ENABLED, network.enabled())
                .with(ConfigSchema.NETWORK_COMPRESSION_LEVEL, network.compressionLevel())
                .with(ConfigSchema.NETWORK_MAGICLESS_ZSTD, network.magiclessZstd())
                .with(ConfigSchema.NETWORK_GLOBAL_PACKET_COMPRESSION, network.globalPacketCompression())
                .with(ConfigSchema.NETWORK_GLOBAL_COMPRESSION_LEVEL, network.globalCompressionLevel())
                .with(ConfigSchema.NETWORK_GLOBAL_COMPRESSION_THRESHOLD, network.globalCompressionThreshold())
                .with(ConfigSchema.NETWORK_USE_CONTEXT_COMPRESSION, network.useContextCompression())
                .with(ConfigSchema.NETWORK_PACKET_AGGREGATION, network.enablePacketAggregation())
                .with(ConfigSchema.NETWORK_AGGREGATION_MIN_BATCH, network.aggregationMinBatchSize())
                .with(ConfigSchema.NETWORK_AGGREGATION_MAX_WAIT, (long) network.aggregationMaxWaitTimeMs())
                .with(ConfigSchema.NETWORK_AGGREGATION_MAX_SIZE, network.aggregationMaxSize())
                .with(ConfigSchema.NETWORK_COMPACT_HEADER, network.enableCompactHeader())
                .with(ConfigSchema.NETWORK_COMPRESSION_BLACKLIST, new ArrayList<>(network.compressionBlacklist()))
                .with(ConfigSchema.SERVER_NETWORK_METRICS_ENABLED, network.metricsEnabled())
                .with(ConfigSchema.NETWORK_MAX_CHUNKS_PER_TICK, network.maxChunksPerTick())
                .with(ConfigSchema.NETWORK_SERVER_PUSH_THREADS, network.serverChunkPushThreads())
                .with(ConfigSchema.NETWORK_DYNAMIC_THREADS, network.dynamicThreadPoolEnabled())
                .with(ConfigSchema.NETWORK_MIN_PUSH_THREADS, network.minPushThreads())
                .with(ConfigSchema.NETWORK_MAX_PUSH_THREADS, network.maxPushThreads())
                .with(ConfigSchema.NETWORK_LIGHT_STRIP, network.lightStrip())
                .with(ConfigSchema.SERVER_SEED_GEN_ENABLED, network.seedGenEnabled())
                .with(ConfigSchema.NETWORK_CONTROL_ENDPOINTS, network.controlReachableEndpoints().stream().map(DataPlaneEndpointConfig::encodeReachable).toList())
                .with(ConfigSchema.DATAPLANE_ENABLED, network.dataPlane().enabled())
                .with(ConfigSchema.DATAPLANE_UDP_LISTENERS, network.dataPlane().udpListeners().stream().map(DataPlaneEndpointConfig::encodeListener).toList())
                .with(ConfigSchema.DATAPLANE_CONTROL_STALL_MS, network.dataPlane().controlStallMs())
                .with(ConfigSchema.DATAPLANE_FAILOVER_EXPIRY_MS, network.dataPlane().failoverExpiryMs())
                .with(ConfigSchema.DATAPLANE_RECOVERY_WINDOW_MS, network.dataPlane().recoveryWindowMs());

        HassiumConfig.CompatConfig compat = config.compat();
        values = values.with(ConfigSchema.COMPAT_REQUIRE_CLIENT_MOD, compat.requireClientMod())
                .with(ConfigSchema.COMPAT_AUTO_DOWNGRADE, compat.autoDowngradeOnError());
        return values.with(ConfigSchema.SERVER_DEBUG_METADATA, debug.metadataLogging())
                .with(ConfigSchema.SERVER_DEBUG_DISPATCHER, debug.dispatcherLogging())
                .with(ConfigSchema.SERVER_DEBUG_ASYNC, debug.asyncLogging())
                .with(ConfigSchema.SERVER_DEBUG_COMPRESSION, debug.compressionLogging())
                .with(ConfigSchema.SERVER_DEBUG_CHUNK_APPLY, debug.chunkApplyLogging())
                .with(ConfigSchema.SERVER_DEBUG_NETWORK, debug.networkLogging())
                .with(ConfigSchema.SERVER_DEBUG_CACHE, debug.cacheLogging())
                .with(ConfigSchema.SERVER_DEBUG_DATAPLANE, debug.dataplaneLogging())
                .with(ConfigSchema.SERVER_DEBUG_LIGHT_VERIFY, debug.lightVerify());
    }

    public static HassiumConfig fromValues(ConfigValues values) {
        return fromValues(values, false);
    }

    /**
     * Restores a runtime snapshot from schema values, choosing CLIENT or SERVER {@code debug.*}
     * keys according to the running physical side. The schema registers a parallel
     * {@code debug.*} set per scope, while {@link HassiumConfig} carries a single
     * {@link HassiumConfig.DebugConfig}; on the physical client the active DebugConfig
     * mirrors {@code CLIENT_DEBUG_*}, on a dedicated server it mirrors {@code SERVER_DEBUG_*}.
     */
    public static HassiumConfig fromValues(ConfigValues values, boolean physicalClient) {
        HassiumConfig.ClientCacheConfig cache = new HassiumConfig.ClientCacheConfig(
                values.get(ConfigSchema.CACHE_ENABLED), values.get(ConfigSchema.CACHE_MAX_SIZE_MB),
                values.get(ConfigSchema.CACHE_COMPRESSION_LEVEL), values.get(ConfigSchema.CACHE_HOT_SCORE_THRESHOLD),
                values.get(ConfigSchema.CACHE_RECENCY_WEIGHT), values.get(ConfigSchema.CACHE_FREQUENCY_WEIGHT),
                values.get(ConfigSchema.CACHE_CLEANUP_INTERVAL_TICKS), values.get(ConfigSchema.CACHE_TARGET_SIZE_MB),
                values.get(ConfigSchema.CACHE_MIN_CLEANUP_BATCH_SIZE), values.get(ConfigSchema.CACHE_VIEW_DISTANCE_EXTENSION_ENABLED),
                values.get(ConfigSchema.CACHE_MAX_RENDER_DISTANCE), values.get(ConfigSchema.CACHE_OVD_UNLOAD_DELAY_SECS),
                values.get(ConfigSchema.CACHE_SECTION_DELTA_ENABLED), values.get(ConfigSchema.CACHE_JOIN_BOOST_ENABLED),
                values.get(ConfigSchema.CACHE_ENTITY_SNAPSHOTS_ENABLED), values.get(ConfigSchema.CACHE_LOAD_THREADS),
                values.get(ConfigSchema.CACHE_LIGHT_CACHE_ENABLED), values.get(ConfigSchema.CACHE_MAX_CHUNKS_PER_FRAME),
                values.get(ConfigSchema.CACHE_MAIN_THREAD_BUDGET_MS),
                values.get(ConfigSchema.CACHE_PARALLEL_LIGHT_ENGINE_ENABLED), values.get(ConfigSchema.CACHE_PARALLEL_LIGHT_ENGINE_THREADS),
                values.get(ConfigSchema.CACHE_LIGHT_SYNC_MODE));

        List<HassiumConfig.ReachableEndpoint> controlEndpoints = values.get(ConfigSchema.NETWORK_CONTROL_ENDPOINTS).stream()
                .map(DataPlaneEndpointConfig::decodeReachable).toList();
        List<HassiumConfig.UdpListenerConfig> listeners = values.get(ConfigSchema.DATAPLANE_UDP_LISTENERS).stream()
                .map(DataPlaneEndpointConfig::decodeListener).toList();
        HassiumConfig.DataPlaneConfig dataPlane = new HassiumConfig.DataPlaneConfig(
                values.get(ConfigSchema.DATAPLANE_ENABLED), listeners,
                values.get(ConfigSchema.DATAPLANE_CONTROL_STALL_MS), values.get(ConfigSchema.DATAPLANE_FAILOVER_EXPIRY_MS),
                values.get(ConfigSchema.DATAPLANE_RECOVERY_WINDOW_MS));
        HassiumConfig.ServerNetworkConfig network = new HassiumConfig.ServerNetworkConfig(
                values.get(ConfigSchema.SERVER_NETWORK_ENABLED), values.get(ConfigSchema.NETWORK_COMPRESSION_LEVEL),
                values.get(ConfigSchema.NETWORK_MAGICLESS_ZSTD), values.get(ConfigSchema.NETWORK_GLOBAL_PACKET_COMPRESSION),
                values.get(ConfigSchema.NETWORK_GLOBAL_COMPRESSION_LEVEL), values.get(ConfigSchema.NETWORK_GLOBAL_COMPRESSION_THRESHOLD),
                values.get(ConfigSchema.NETWORK_USE_CONTEXT_COMPRESSION), values.get(ConfigSchema.NETWORK_PACKET_AGGREGATION),
                values.get(ConfigSchema.NETWORK_AGGREGATION_MIN_BATCH), values.get(ConfigSchema.NETWORK_AGGREGATION_MAX_WAIT),
                values.get(ConfigSchema.NETWORK_AGGREGATION_MAX_SIZE), values.get(ConfigSchema.NETWORK_COMPACT_HEADER),
                SetCopy.copy(values.get(ConfigSchema.NETWORK_COMPRESSION_BLACKLIST)), values.get(ConfigSchema.SERVER_NETWORK_METRICS_ENABLED),
                values.get(ConfigSchema.NETWORK_MAX_CHUNKS_PER_TICK), values.get(ConfigSchema.NETWORK_SERVER_PUSH_THREADS),
                values.get(ConfigSchema.NETWORK_DYNAMIC_THREADS), values.get(ConfigSchema.NETWORK_MIN_PUSH_THREADS),
                values.get(ConfigSchema.NETWORK_MAX_PUSH_THREADS), values.get(ConfigSchema.NETWORK_LIGHT_STRIP),
                values.get(ConfigSchema.SERVER_SEED_GEN_ENABLED), controlEndpoints, dataPlane);
        HassiumConfig.CompatConfig compat = new HassiumConfig.CompatConfig(
                values.get(ConfigSchema.COMPAT_REQUIRE_CLIENT_MOD), values.get(ConfigSchema.COMPAT_AUTO_DOWNGRADE));
        HassiumConfig.DebugConfig debug = new HassiumConfig.DebugConfig(
                debugValue(values, physicalClient, ConfigSchema.CLIENT_DEBUG_METADATA, ConfigSchema.SERVER_DEBUG_METADATA),
                debugValue(values, physicalClient, ConfigSchema.CLIENT_DEBUG_DISPATCHER, ConfigSchema.SERVER_DEBUG_DISPATCHER),
                debugValue(values, physicalClient, ConfigSchema.CLIENT_DEBUG_ASYNC, ConfigSchema.SERVER_DEBUG_ASYNC),
                debugValue(values, physicalClient, ConfigSchema.CLIENT_DEBUG_COMPRESSION, ConfigSchema.SERVER_DEBUG_COMPRESSION),
                debugValue(values, physicalClient, ConfigSchema.CLIENT_DEBUG_CHUNK_APPLY, ConfigSchema.SERVER_DEBUG_CHUNK_APPLY),
                debugValue(values, physicalClient, ConfigSchema.CLIENT_DEBUG_NETWORK, ConfigSchema.SERVER_DEBUG_NETWORK),
                debugValue(values, physicalClient, ConfigSchema.CLIENT_DEBUG_CACHE, ConfigSchema.SERVER_DEBUG_CACHE),
                debugValue(values, physicalClient, ConfigSchema.CLIENT_DEBUG_DATAPLANE, ConfigSchema.SERVER_DEBUG_DATAPLANE),
                debugValue(values, physicalClient, ConfigSchema.CLIENT_DEBUG_LIGHT_VERIFY, ConfigSchema.SERVER_DEBUG_LIGHT_VERIFY));
        return new HassiumConfig(new HassiumConfig.StorageConfig(
                values.get(ConfigSchema.STORAGE_ENABLED), values.get(ConfigSchema.STORAGE_MODE), values.get(ConfigSchema.STORAGE_ZSTD_LEVEL)),
                cache, new HassiumConfig.ClientNetworkConfig(
                        values.get(ConfigSchema.CLIENT_NETWORK_ENABLED), values.get(ConfigSchema.CLIENT_NETWORK_METRICS_ENABLED),
                        values.get(ConfigSchema.CLIENT_NETWORK_METRICS_AUTO_RESET),
                        values.get(ConfigSchema.DATAPLANE_RECOVERY_FREEZE),
                        values.get(ConfigSchema.CLIENT_SEED_GEN_ENABLED)),
                network, compat, debug);
    }
    private static boolean debugValue(ConfigValues values, boolean physicalClient,
                                       ConfigKey<Boolean> clientKey, ConfigKey<Boolean> serverKey) {
        return values.get(physicalClient ? clientKey : serverKey);
    }


    private static final class SetCopy {
        private static <T> java.util.Set<T> copy(List<T> values) {
            return java.util.Set.copyOf(values);
        }
    }
}
