# 配置系统重构设计

日期：2026-07-21

## 背景

当前配置系统存在以下问题：

1. **客户端不需要 `storage.*` 配置**——客户端缓存使用独立的硬编码 ZSTD level 9，与服务端存储层完全解耦
2. **Bloom filter 不需要用户配置**——只要启用客户端缓存就应该开启，参数可以写死
3. **`NetworkConfig` 是 25 字段的巨型混合 record**——客户端、共享、服务端字段混在一起，14 个"共享"字段中客户端实际只读 2 个
4. **存在死代码配置项**——`maxAgeDays`、`backgroundThreads`、`maxCallbacksPerFrame`、`globalCompressionLevel`、`globalCompressionThreshold` 从未被业务逻辑调用
5. **`ZstdPipelineSwitcher.switchToZstd()` 是死代码**——全局包压缩管线从未被安装，7 个相关配置项实际未生效（标记为实验性保留）
6. **三文件结构造成混淆**——`common.toml` 中的"共享"字段大多只有服务端读取

## 设计目标

- 每端只看到自己能用的配置，不会出现"改了没效果"的困惑
- 双端各一个配置文件：`client.toml` + `server.toml`
- 消除死代码配置项
- 拆分巨型 `NetworkConfig` record
- 运行时 config class 与 toml 文件结构对齐

## 文件结构

| 文件 | 加载方 | 内容 |
|------|--------|------|
| `config/hassium/client.toml` | 仅物理客户端 | `clientCache.*`（14项）+ `network.*`（2项）+ `debug.*`（7项）= **23 项** |
| `config/hassium/server.toml` | 仅专用服 | `storage.*`（3项）+ `network.*`（14项）+ `compat.*`（2项）+ `debug.*`（7项）= **26 项** |

取消 `common.toml`。

## client.toml 字段清单

### `clientCache.*`（14 项）

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `enabled` | bool | `true` | 是否启用客户端缓存 |
| `maxSizeMb` | int | `2048` | 缓存最大容量（MB） |
| `cacheCompressionLevel` | int | `9` | **新增**：缓存 ZSTD 压缩等级（1–22） |
| `hotScoreThreshold` | double | `0.3` | 热点分数阈值 |
| `recencyWeight` | double | `0.7` | 最近访问权重 |
| `frequencyWeight` | double | `0.3` | 访问频率权重 |
| `cleanupIntervalTicks` | int | `6000` | 清理检查间隔（刻） |
| `targetCacheSizeMb` | int | `0` | 目标缓存大小（MB；0=自动） |
| `minCleanupBatchSize` | int | `100` | 每次最少清理区块数 |
| `viewDistanceExtensionEnabled` | bool | `true` | 超视渲染开关 |
| `maxRenderDistance` | int | `32` | 超视渲染/有效 RD 上限 |
| `ovdUnloadDelaySecs` | int | `5` | 离开超视渲染环带后延迟卸载秒数 |
| `sectionDeltaEnabled` | bool | `true` | 分段增量开关 |
| `joinBoostEnabled` | bool | `true` | 进服加速开关 |
| `entitySnapshotsEnabled` | bool | `false` | 实体快照开关 |

**删除项：**
- ~~`maxAgeDays`~~：死代码，热度评分已隐式覆盖过期淘汰
- ~~`bloomFilterEnabled` / `bloomFilterExpectedInsertions` / `bloomFilterFpp`~~：写死为 `enabled=true, insertions=10000, fpp=0.01`

### `network.*`（2 项）

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `enabled` | bool | `true` | 门控握手 + 通道注册 |
| `metricsEnabled` | bool | `true` | 指标收集开关 |

**移除项：**
- ~~`magiclessZstd`~~：聚合包两端统一使用 ZSTD，硬编码 `true`
- ~~`clientChunkLoadThreads`~~：移入 `clientCache.*`（改为 `loadThreads`）
- ~~`lightStripEnabled`~~：移入 `clientCache.*`（改为 `lightCacheEnabled`）
- ~~`maxChunksPerFrame`~~：移入 `clientCache.*`
- ~~`mainThreadChunkBudgetMs`~~：移入 `clientCache.*`

### `debug.*`（7 项）

保持现状：`metadataLogging`、`dispatcherLogging`、`asyncLogging`、`compressionLogging`、`chunkApplyLogging`、`networkLogging`、`cacheLogging`

## server.toml 字段清单

### `storage.*`（3 项）

保持现状：`enabled`、`mode`、`zstdLevel`

### `network.*`（14 项）

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `enabled` | bool | `true` | 门控自定义通道 |
| `compressionLevel` | int | `3` | 自定义通道 ZSTD 等级 |
| `magiclessZstd` | bool | `true` | 无 magic ZSTD 帧格式 |
| `globalPacketCompression` | bool | `true` | **实验性**：全局 ZSTD 替换 Zlib（管线未安装） |
| `globalCompressionLevel` | int | `3` | **实验性**：全局压缩等级 |
| `globalCompressionThreshold` | int | `256` | **实验性**：全局压缩阈值 |
| `useContextCompression` | bool | `true` | 上下文压缩 |
| `enablePacketAggregation` | bool | `true` | **实验性**：包聚合开关 |
| `aggregationMinBatchSize` | int | `4` | **实验性**：聚合最小批量 |
| `aggregationMaxWaitTimeMs` | int | `20` | **实验性**：聚合最大等待（ms） |
| `aggregationMaxSize` | int | `262144` | **实验性**：聚合最大大小（字节） |
| `enableCompactHeader` | bool | `true` | **实验性**：紧凑包头 |
| `compressionBlacklist` | list | `[]` | 压缩/聚合黑名单 |
| `metricsEnabled` | bool | `true` | 指标收集开关 |

**服务端新增项（从原 client.toml 迁移）：**
- `maxChunksPerTick`（原 server 专属）
- `serverChunkPushThreads`（原 server 专属）
- `dynamicThreadPoolEnabled`、`minPushThreads`、`maxPushThreads`（原 server 专属）

**删除项：**
- ~~`globalCompressionLevel` getter~~：死代码，保留配置但 getter 可删除
- ~~`globalCompressionThreshold` getter~~：死代码，保留配置但 getter 可删除

### `compat.*`（2 项）

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `requireClientMod` | bool | `false` | 是否强制要求客户端安装 |
| `autoDowngradeOnError` | bool | `true` | 出错时自动降级 |

### `debug.*`（7 项）

与 client.toml 相同的 7 个字段。

## 运行时 Config Record 拆分

```
HassiumConfig (总 record)
├── ClientCacheConfig     ← client.toml clientCache.* + 部分原 network 字段
├── ClientNetworkConfig   ← client.toml network.*（2 字段）
├── StorageConfig         ← server.toml storage.*
├── ServerNetworkConfig   ← server.toml network.*（13+5 字段）
├── CompatConfig          ← server.toml compat.*
└── DebugConfig           ← 双端各自 debug.*
```

原 `NetworkConfig`（25 字段）拆为：
- `ClientNetworkConfig`：2 字段（`enabled`、`metricsEnabled`）
- `ServerNetworkConfig`：19 字段（共享 14 + 服务端专属 5）

`ClientCacheConfig` 吸收的字段：
- `loadThreads`（原 `network.clientChunkLoadThreads`）
- `lightCacheEnabled`（原 `network.lightStripEnabled`，概念改为光照缓存）
- `maxChunksPerFrame`（原 `network.maxChunksPerFrame`）
- `mainThreadChunkBudgetMs`（原 `network.mainThreadChunkBudgetMs`）

## 硬编码项

| 原配置项 | 硬编码值 | 原因 |
|----------|----------|------|
| `clientCache.bloomFilterEnabled` | `true` | 只要启用缓存就需要 Bloom |
| `clientCache.bloomFilterExpectedInsertions` | `10000` | 合理默认值 |
| `clientCache.bloomFilterFpp` | `0.01` | 1% 假阳性率 |
| `network.magiclessZstd`（聚合路径） | `true` | 聚合包统一 ZSTD |
| `clientCache.maxAgeDays` | N/A | 删除，不硬编码 |

## 死代码清理

| 项 | 位置 | 处理 |
|----|------|------|
| `maxAgeDays` | ClientCacheConfig | 删除字段 + getter |
| `backgroundThreads` | 原 NetworkConfig | 删除字段 + getter |
| `maxCallbacksPerFrame` | 原 NetworkConfig | 删除字段 + getter |
| `globalCompressionLevel` getter | HassiumConfigService | 删除 getter（配置保留，标记实验性） |
| `globalCompressionThreshold` getter | HassiumConfigService | 删除 getter（配置保留，标记实验性） |
| `ChunkBloomFilter.fromConfig()` | cache/client | 改为硬编码参数 |
| `HassiumTomlConfigIO` | config/ | 删除（Fabric 改用新 `FabricTomlConfigIO`） |

## Fabric 加载器适配

`HassiumTomlConfigIO` 被删除后，Fabric 端需要新的 `FabricTomlConfigIO`：

- 物理客户端：只读写 `client.toml`
- 专用服：只读写 `server.toml`
- 不再有 `common.toml` 的读写逻辑

## 服务端专属字段迁移

以下字段从原 `client.toml` / `clientCache.*` 迁移到 `server.toml` / `network.*`：

| 原位置 | 新位置 | 说明 |
|--------|--------|------|
| `network.lightStripEnabled`（client） | `clientCache.lightCacheEnabled`（client） + `serverNetwork.lightStrip`（server） | 客户端光照缓存 + 服务端剥离分离 |
| `network.maxChunksPerTick`（server） | 不变 | 服务端专属 |

**注意**：`lightStripEnabled` 原为客户端+服务端共用。重构后拆分为：`clientCache.lightCacheEnabled`（客户端光照缓存）+ `serverNetwork.lightStrip`（服务端网络剥离）。专用服可通过 `server.toml` 配置剥离行为。

## 影响面

需改动文件（估算）：

| 包 | 文件 | 改动类型 |
|----|------|----------|
| `config/` | `HassiumConfig.java` | 拆分 record |
| `config/` | `HassiumConfigSpec.java` | 重构为 Client/Server 两个 spec |
| `config/` | `HassiumConfigService.java` | 更新 getter + 移除死代码 |
| `config/` | `HassiumTomlConfigIO.java` | 删除 |
| `config/` | `FabricTomlConfigIO.java` | 新建（Fabric 专用） |
| `cache/client/` | `ClientHassiumStorage.java` | 用 `cacheCompressionLevel` 替代硬编码 |
| `cache/client/` | `ChunkBloomFilter.java` | 硬编码参数 |
| `cache/client/` | `CacheEvictionManager.java` | 无变化（不引用被删字段） |
| `fabric/` | `HassiumClientMod.java` | 更新 config 加载 |
| `fabric/` | `HassiumServerMod.java` | 更新 config 加载（如有） |
| `forge/` | `HassiumMod.java` | 更新 spec 注册 |
| `neoforge/` | `HassiumNeoForge.java` | 更新 spec 注册 |
| `cloth-ui/` | `HassiumClothConfigScreen.java` | 更新配置屏幕 |
| 各 loader | `*ConfigScreens.java` | 更新屏幕注册 |
| 各 loader | `*NetworkManager.java` | 更新 config 引用 |
| `common/` | `ServerChunkPushManager.java` | 更新 config 引用 |
| `common/` | `ClientMainThreadBudget.java` | 更新 config 引用 |
| `common/` | `ViewDistanceExtensionService.java` | 更新 config 引用 |

## 验证标准

1. 物理客户端只读 `client.toml`，专用服只读 `server.toml`
2. 客户端配置屏幕不显示任何服务端专属字段
3. 服务端配置屏幕不显示任何客户端缓存字段
4. 所有保留的配置项都有真实的运行时调用链
5. 硬编码项不再出现在配置文件中
6. 实验性字段在配置文件注释中标明"实验性——当前未生效"
