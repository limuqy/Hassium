# Hassium 配置项全面审计报告

审计日期：2026-07-21

## 一、配置文件结构

| 文件名 | 加载方 | 包含分类 |
|--------|--------|----------|
| `hassium/hassium-client.toml` | 仅物理客户端 | `clientCache.*` + `network.client*` + `network.background*` + `network.maxChunks*` + `network.maxCallbacks*` + `network.mainThread*` |
| `hassium/hassium-common.toml` | 客户端 + 专用服 | `storage.*` + `network.enabled/compression/aggregate/compact/metrics` + `compat.autoDowngradeOnError` + `debug.*` |
| `hassium/hassium-server.toml` | 仅专用服 | `network.maxChunksPerTick` + `network.serverChunkPush*` + `network.dynamicThread*` + `network.minPush*` + `network.maxPush*` + `compat.requireClientMod` |

物理客户端读取: `client.toml` + `common.toml`（两份）
专用服务器读取: `server.toml` + `common.toml`（两份）

Forge/NeoForge 端注册 3 个 spec（CLIENT / COMMON / SERVER），物理客户端只加载 CLIENT + COMMON，专用服只加载 COMMON + SERVER——与 Fabric 端对齐。

## 二、全部配置项逐项审计

### A. Storage（3 项）→ `common.toml`

| 配置项 | 默认值 | 客户端/服务端/双端 | 实际调用 | 状态 |
|--------|--------|---------------------|----------|------|
| `storage.enabled` | `true` | **双端** | `MixinRegionFile:116,133` 门控存储操作 | ✅ 正常 |
| `storage.mode` | `"mirror"` | **双端** | `MixinRegionFile:137` 决定 mirror/readonly_vanilla/hassium_only | ✅ 正常 |
| `storage.zstdLevel` | `9` | **双端** | `HassiumRegionStorageImpl:42` 初始化压缩 codec | ✅ 正常 |

### B. ClientCache（18 项）→ `client.toml`

| 配置项 | 默认值 | 客户端/服务端/双端 | 实际调用 | 状态 |
|--------|--------|---------------------|----------|------|
| `clientCache.enabled` | `true` | **客户端** | 多处门控 | ✅ 正常 |
| `clientCache.maxSizeMb` | `2048` | **客户端** | 缓存容量上限 | ✅ 正常 |
| `clientCache.maxAgeDays` | `30` | **客户端** | **❌ 死代码** — getter 存在但无业务逻辑调用 | ⚠️ |
| `clientCache.hotScoreThreshold` | `0.3` | **客户端** | `CacheEvictionManager:94` 保护热区块 | ✅ 正常 |
| `clientCache.recencyWeight` | `0.7` | **客户端** | `CacheEvictionManager:49-53` 热度计算 | ✅ 正常 |
| `clientCache.frequencyWeight` | `0.3` | **客户端** | `CacheEvictionManager:49-53` 热度计算 | ✅ 正常 |
| `clientCache.cleanupIntervalTicks` | `6000` | **客户端** | `ClientHassiumStorage:441` 清理频率 | ✅ 正常 |
| `clientCache.targetCacheSizeMb` | `0`(自动) | **客户端** | `CacheEvictionManager:214` via `targetCacheSizeBytes()` | ✅ 正常 |
| `clientCache.minCleanupBatchSize` | `100` | **客户端** | `CacheEvictionManager:83` 最小清理批次 | ✅ 正常 |
| `clientCache.bloomFilterEnabled` | `true` | **客户端** | `ClientHassiumStorage:57` 门控 Bloom | ✅ 正常 |
| `clientCache.bloomFilterExpectedInsertions` | `10000` | **客户端** | `ChunkBloomFilter:71` 配置 Bloom | ✅ 正常 |
| `clientCache.bloomFilterFpp` | `0.01` | **客户端** | `ChunkBloomFilter:72` 配置 Bloom | ✅ 正常 |
| `clientCache.viewDistanceExtensionEnabled` | `true` | **客户端** | `MixinClientPacketListener:92`, `MixinOptions:38`, `ViewDistanceExtensionService:212,790` | ✅ 正常 |
| `clientCache.maxRenderDistance` | `32` | **客户端** | `MixinOptions:44`, `ViewDistanceExtensionService:121` | ✅ 正常 |
| `clientCache.ovdUnloadDelaySecs` | `5` | **客户端** | `ViewDistanceExtensionService:255` | ✅ 正常 |
| `clientCache.sectionDeltaEnabled` | `true` | **客户端** | `ClientMetadataHandler:248` | ✅ 正常 |
| `clientCache.joinBoostEnabled` | `true` | **客户端** | `ClientMainThreadBudget:31` | ✅ 正常 |
| `clientCache.entitySnapshotsEnabled` | `false` | **客户端** | `MixinClientLevel:110`, `ClientLifecycleHelper:172` | ✅ 正常 |

### C. Network — 共享部分（14 项）→ `common.toml`

| 配置项 | 默认值 | 客户端/服务端/双端 | 实际调用 | 状态 |
|--------|--------|---------------------|----------|------|
| `network.enabled` | `true` | **双端** | 各 loader NetworkManager 门控通道注册 | ✅ 正常 |
| `network.compressionLevel` | `3` | **双端** | 自定义通道压缩等级 | ✅ 正常 |
| `network.globalPacketCompression` | `true` | **双端** | 全局 ZSTD 替换 Zlib | ✅ 正常 |
| `network.globalCompressionLevel` | `3` | **双端** | 全局压缩等级 | ✅ 正常 |
| `network.globalCompressionThreshold` | `256` | **双端** | 压缩阈值 | ✅ 正常 |
| `network.compressionBlacklist` | `Set.of(...)` | **双端** | `PacketCompressionBlacklist:62` | ✅ 正常 |
| `network.useContextCompression` | `true` | **双端** | `ZstdPipelineSwitcher:56`, `HassiumAggregationPacket:74` | ✅ 正常 |
| `network.magiclessZstd` | `true` | **双端** | `ZstdPipelineSwitcher:57`, `HassiumAggregationPacket:79,138` | ✅ 正常 |
| `network.enablePacketAggregation` | `true` | **双端** | `ZstdPipelineSwitcher:58` | ✅ 正常 |
| `network.aggregationMinBatchSize` | `4` | **双端** | `ZstdPipelineSwitcher:66-68` | ✅ 正常 |
| `network.aggregationMaxWaitTimeMs` | `20` | **双端** | `ZstdPipelineSwitcher:66-68` | ✅ 正常 |
| `network.aggregationMaxSize` | `256KB` | **双端** | `ZstdPipelineSwitcher:66-68` | ✅ 正常 |
| `network.enableCompactHeader` | `true` | **双端** | `ZstdPipelineSwitcher:92` + 各 loader 握手协商 | ✅ 正常 |
| `network.metricsEnabled` | `true` | **双端** | `CommonClass:46` 开关指标 + 命令门控 | ✅ 正常 |

### D. Network — 客户端专属（6 项）→ `client.toml`

| 配置项 | 默认值 | 客户端/服务端/双端 | 实际调用 | 状态 |
|--------|--------|---------------------|----------|------|
| `network.clientChunkLoadThreads` | `10` | **客户端** | `ClientLifecycleHelper` 初始化线程池 | ✅ 正常 |
| `clientCache.lightCacheEnabled` | `true` | **客户端** | 光照缓存：首次重算后存储光照，缓存命中跳过重算 | ✅ 正常 |
| `network.backgroundThreads` | `8` | **客户端** | **❌ 死代码** — getter 存在但无业务逻辑调用 | ⚠️ |
| `network.maxChunksPerFrame` | `32` | **客户端** | `ClientMainThreadBudget:78` | ✅ 正常 |
| `network.maxCallbacksPerFrame` | `32` | **客户端** | **❌ 死代码** — getter 存在但无业务逻辑调用 | ⚠️ |
| `network.mainThreadChunkBudgetMs` | `10` | **客户端** | `ClientMainThreadBudget:59` | ✅ 正常 |

### E. Network — 服务端专属（5 项）→ `server.toml`

| 配置项 | 默认值 | 客户端/服务端/双端 | 实际调用 | 状态 |
|--------|--------|---------------------|----------|------|
| `network.maxChunksPerTick` | `32` | **服务端** | `ServerChunkPushManager:888` | ✅ 正常 |
| `network.serverChunkPushThreads` | `8` | **服务端** | `ServerChunkPushManager:146` | ✅ 正常 |
| `network.dynamicThreadPoolEnabled` | `true` | **服务端** | `ServerChunkPushManager:1063` | ✅ 正常 |
| `network.minPushThreads` | `2` | **服务端** | `ServerChunkPushManager:147` | ✅ 正常 |
| `network.maxPushThreads` | `8` | **服务端** | `ServerChunkPushManager:148,1079` | ✅ 正常 |

### F. Compat（2 项）→ 分散在 `common.toml` + `server.toml`

| 配置项 | 默认值 | 客户端/服务端/双端 | 实际调用 | 状态 |
|--------|--------|---------------------|----------|------|
| `compat.requireClientMod` | `false` | **服务端** | `MixinChunkHolder:48` | ✅ 正常 |
| `compat.autoDowngradeOnError` | `true` | **双端** | `MixinRegionFile:202,225` | ✅ 正常 |

### G. Debug（7 项）→ `common.toml`

| 配置项 | 默认值 | 客户端/服务端/双端 | 实际调用 | 状态 |
|--------|--------|---------------------|----------|------|
| `debug.metadataLogging` | `false` | **双端** | `DebugLogger` → `LogType.METADATA` | ✅ 正常 |
| `debug.dispatcherLogging` | `false` | **双端** | `DebugLogger` → `LogType.DISPATCHER` | ✅ 正常 |
| `debug.asyncLogging` | `false` | **双端** | `DebugLogger` → `LogType.ASYNC` | ✅ 正常 |
| `debug.compressionLogging` | `false` | **双端** | `DebugLogger` → `LogType.COMPRESSION` | ✅ 正常 |
| `debug.chunkApplyLogging` | `false` | **双端** | `DebugLogger` → `LogType.APPLY_CHUNK` | ✅ 正常 |
| `debug.networkLogging` | `false` | **双端** | `DebugLogger` → `LogType.NETWORK` | ✅ 正常 |
| `debug.cacheLogging` | `false` | **双端** | `DebugLogger` → `LogType.CACHE` | ✅ 正常 |

## 三、问题总结

### 🔴 死代码配置项（3 项）

| 配置项 | 位置 | 问题 |
|--------|------|------|
| `clientCache.maxAgeDays` | `client.toml` | `HassiumConfigService.getCacheMaxAgeDays()` 存在但**无任何业务代码调用**。清理逻辑只用 `hotScoreThreshold` + `targetCacheSizeMb`，从未检查过期天数 |
| `network.backgroundThreads` | `client.toml` | `HassiumConfigService.getBackgroundThreads()` 存在但**无任何业务代码调用**。`ClientLifecycleHelper` 用的是 `getClientChunkLoadThreads()` |
| `network.maxCallbacksPerFrame` | `client.toml` | `HassiumConfigService.getMaxCallbacksPerFrame()` 存在但**无任何业务代码调用**。限流只用了 `maxChunksPerFrame` + `mainThreadChunkBudgetMs` |

### 🟡 分类问题

#### 1. `NetworkConfig` 是个巨型混合 record（25 字段）

运行时 `HassiumConfig.NetworkConfig` 把客户端、共享、服务端的网络字段全塞进一个 record。拆分来源：

- 客户端 6 字段 → 从 `client.toml` 读
- 共享 14 字段 → 从 `common.toml` 读
- 服务端 5 字段 → 从 `server.toml` 读

`HassiumTomlConfigIO` 用内部 record `ClientNet` / `CommonNet` / `ServerNet` 做了拆分再 merge，但 `HassiumConfigSpec` 和 `HassiumConfig` 层面仍是单个 `NetworkConfig`，**概念上不清晰**。

#### 2. `CompatConfig` 字段物理分布不一致

- `requireClientMod` 定义在 `Server` spec → 写入 `server.toml`
- `autoDowngradeOnError` 定义在 `Common` spec → 写入 `common.toml`
- 运行时 merge 成一个 `CompatConfig` record

这不算 bug，但 `CompatConfig` 横跨两个文件，用户可能困惑。

#### 3. `lightCacheEnabled`（原 `lightStripEnabled`）

光照缓存功能由 `clientCache.lightCacheEnabled` 控制（客户端）。服务端是否从网络包剥离光照数据由 `serverNetwork.lightStrip` 控制。

客户端写入 `client.toml` 没问题，但专用服**没有这个文件**，会使用默认值 `true`。这意味着服务端的光照剥离行为无法通过配置调整——除非用户手动创建 `client.toml`（但专用服不会读它）。

## 四、建议

1. **清理 3 个死代码配置项**：要么实现 `maxAgeDays` 过期清理逻辑、`backgroundThreads` 线程池初始化、`maxCallbacksPerFrame` 限流；要么移除这些配置定义和 getter
2. **考虑拆分 `NetworkConfig` record** 为 `ClientNetworkConfig` + `SharedNetworkConfig` + `ServerNetworkConfig`，与 toml 文件结构对齐
3. **`serverNetwork.lightStrip` 服务端可达性**：当前专用服无法配置此项，如果需要可将其移到 `common.toml`

## 五、统计汇总

| 分类 | 总字段数 | 正常 | 死代码 |
|------|----------|------|--------|
| Storage | 3 | 3 | 0 |
| ClientCache | 18 | 17 | 1 |
| Network（共享） | 14 | 14 | 0 |
| Network（客户端） | 6 | 4 | 2 |
| Network（服务端） | 5 | 5 | 0 |
| Compat | 2 | 2 | 0 |
| Debug | 7 | 7 | 0 |
| **合计** | **55** | **52** | **3** |
