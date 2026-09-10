# Hassium 配置项审计

> 审计日期：2026-09-10（对齐影子双窗 OVD 恢复三键后键集；真相源 `ConfigSchema`，41 键）。
> 历史审计：2026-07-21（1.1.2 旧结构）、2026-08-09（config-restructure，74 键 + 删键 4）、2026-09-04（直连拓扑裁剪标注）、2026-09-10（OVD 退役 38 键）——键集均已过时，本文为当前唯一快照。

## 一、配置文件结构与加载链

真相源：`common/.../config/ConfigSchema.java` —— **唯一 schema**，三端后端均从中生成，无手写三端 Spec 表。生效默认值 = ConfigSchema 声明默认。

| 加载器 | 后端 | 文件 / 模型 |
|--------|------|-------------|
| Fabric | `FabricTomlConfigIO` | **双文件模型**：`hassium/hassium-client.toml`（CLIENT scope）/ `hassium/hassium-server.toml`（SERVER scope）；物理客户端读 client，专用服读 server |
| NeoForge | `NeoForgeConfigBackend` | 1.21.1+ `ModConfigSpec`；1.20.1 为 `ForgeConfigSpec`；按 ConfigScope 生成 **CLIENT / SERVER 双 spec** |
| Forge | `ForgeConfigBackend` | `ForgeConfigSpec`；CLIENT / SERVER 双 spec |

生效加载链：`HassiumConfigService.loadFromToml` → `Services.CONFIG.load(scope)` → 三端 backend → `ConfigSnapshotAdapter.fromValues`。

> 历史（1.1.2 及更早）：Fabric 三文件模型（`client.toml` + `common.toml` + `server.toml`）、Forge/NeoForge 三 spec（CLIENT/COMMON/SERVER）——2.0.0 已统一为**双文件 / 双 scope** 模型。

**Legacy key hygiene**：旧 toml 残留键由加载器静默清除（`FabricTomlConfigIO` 清理表），不迁移、不报错。已清除键族：`net.*` 全族、`dataplane.*`、`master.controlReachableEndpoints` / `bindHost` / `authToken` / `migration*`（7 键）/ `resumeTicketTtlMs` / `globalPacketCompression` / `globalCompressionLevel` / `globalCompressionThreshold` / `magiclessZstd`、`chunk.ovdUnloadDelaySecs`（延迟卸载取消）/ `hassiumEngineEnabled` / `unloadDelaySecs` / `compressionLevel`、`storage.mode`。OVD 三键（`viewDistanceExtensionEnabled` / `maxRenderDistance` / `ovdLocalGeneration`）已随双窗重做恢复，不再清理。

## 二、全部配置项（ConfigSchema，41 键）

键名前缀：区块核心 `chunk.*` / 服务端传输面 `master.*` / 存储 `storage.*` / 兼容 `compat.*` / 调试 `debug.*`。

### A. CLIENT 键（client.toml / client spec，25 键）

**A1. chunk.\*（18 键，区块核心）**

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `chunk.enabled` | `true` | 区块核心总开关（影子端世界保存/算光/缓存/Pull 模式；关后全程原版路径） |
| `chunk.maxSizeMb` | `4096` | 缓存容量上限（MB；影子端存档容量上限，超限触发热度淘汰） |
| `chunk.hotScoreThreshold` | `0.3` | 热点分数阈值（低于视为冷 region 文件，清理时优先淘汰） |
| `chunk.recencyWeight` | `0.7` | 热度分数中最近访问权重 |
| `chunk.frequencyWeight` | `0.3` | 热度分数中访问频率权重 |
| `chunk.cleanupIntervalTicks` | `6000` | 清理检查间隔（刻） |
| `chunk.targetSizeMb` | `0` | 目标缓存大小（MB；0=自动） |
| `chunk.minCleanupBatchSize` | `100` | 每轮最多淘汰的 region 文件数 |
| `chunk.sectionDeltaEnabled` | `true` | 分段增量（服务端规划 + 客户端应用） |
| `chunk.viewDistanceExtensionEnabled` | `true` | 超视渲染 OVD（影子双窗；见 chunk-cache.md §10） |
| `chunk.maxRenderDistance` | `16` | 超视渲染 effective clientRD 上限（2–64） |
| `chunk.ovdLocalGeneration` | `false` | OVD 窗缓存 miss 时本地生成（需真实 seed） |
| `chunk.maxChunksPerFrame` | `6` | 每 tick 缓存读取生产上限（影子入队 + 影子读盘；主线程消费只受时间预算） |
| `chunk.mainThreadChunkBudgetMs` | `15` | 主线程 apply 预算（ms） |
| `chunk.seedGenThreads` | `2` | SeedGen 本地生成线程数（0=禁用本地生成，SeedRef 一律回退全量） |
| `chunk.seedGenEnabled` | `false` | SeedGen 本地生成（双端同版本；服务端开启时下发世界种子） |

**A2. debug.\*（CLIENT 10 键；与 SERVER 同名键共用路径，scope 隔离）**

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `debug.metadataLogging` | `false` | chunkHash 元数据收发日志（客户端专属） |
| `debug.dispatcherLogging` | `false` | 主线程调度队列日志 |
| `debug.asyncLogging` | `false` | 后台任务调度日志 |
| `debug.compressionLogging` | `false` | 压缩大小/字典日志 |
| `debug.chunkApplyLogging` | `false` | 区块 apply 落地日志 |
| `debug.networkLogging` | `false` | 网络收发日志 |
| `debug.cacheLogging` | `false` | 缓存命中/未命中/读盘日志（客户端专属） |
| `debug.lightVerify` | `false` | 光照验算日志（客户端专属） |
| `debug.networkMetricsEnabled` | `false` | 客户端网络指标（冒烟测试 `hassium.smokeTest=true` 强开） |
| `debug.networkMetricsAutoReset` | `true` | 客户端退出自动复位指标 |

### B. SERVER 键（server.toml / server spec，22 键）

**B1. chunk.lightStrip（1 键，区块核心服务端侧）**

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `chunk.lightStrip` | `true` | 服务端光照剥离（必须经 Hassium 能力握手；由影子端统一算光回传） |

**B2. storage.\*（2 键，存储域）**

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `storage.enabled` | `false` | 存档压缩总开关（**默认关**；开启改写存档格式 type 126，启用前备份；仅专用服务器写，单人/局域网保持原版格式、读兼容） |
| `storage.zstdLevel` | `3` | 存储 ZSTD 压缩等级（1–22） |

**B3. master.\*（10 键，服务端传输面）**

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `master.enabled` | `true` | 服务端网络通道总开关（登录期握手/聚合的门） |
| `master.compressionLevel` | `3` | 自有通道 ZSTD 压缩等级 |
| `master.useContextCompression` | `true` | 上下文压缩（字典 ZSTD） |
| `master.enablePacketAggregation` | `true` | 包聚合 |
| `master.aggregationMinBatchSize` | `4` | 聚合最小批量 |
| `master.aggregationMaxWaitTimeMs` | `50` | 聚合最大等待时间（ms；ACK 超时 5s 自动降级直发） |
| `master.aggregationMaxSize` | `262144` | 聚合最大大小（字节） |
| `master.compressionBlacklist` | 控制面键集 | 压缩/聚合黑名单（控制面不进聚合缓冲） |
| `master.maxChunksPerTick` | `4` | 每玩家每 tick 提交到后台序列化的区块上限（满 tick ≈ 本值×20/s） |
| `master.serverChunkPushThreads` | `4` | 服务端区块推送固定线程数（encode / hash / ZSTD） |

**B4. compat.\*（2 键）**

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `compat.requireClientMod` | `false` | 无模组客户端可连（true 时登录期握手失败即踢出，替代超时等待） |
| `compat.autoDowngradeOnError` | `true` | 出错时自动降级 |

**B5. debug.\*（SERVER 5 键；与 CLIENT 同名键共用路径，scope 隔离；不含元数据/缓存/光照验算）**

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `debug.dispatcherLogging` | `false` | 主线程调度队列日志 |
| `debug.asyncLogging` | `false` | 后台任务调度日志 |
| `debug.compressionLogging` | `false` | 压缩大小/字典日志 |
| `debug.chunkApplyLogging` | `false` | 区块 apply 落地日志 |
| `debug.networkLogging` | `false` | 网络收发日志 |


## 三、退役键族（直连拓扑 / OVD / 全局包压缩，全部已删）

| 键族 | 键数 | 退役原因 |
|------|------|----------|
| `net.*`（客户端网络核心） | 3（`enabled` / `metricsEnabled` / `metricsAutoReset`） | 网络核心（进程内网关）裁剪；客户端指标并入 `debug.networkMetrics*` |
| `dataplane.*`（UDP 数据面） | 2（`enabled` / `recoveryWindowMs`） | UDP 数据面裁剪 |
| `master.controlReachableEndpoints` / `bindHost` / `authToken` | 3 | 网关监听/端点/鉴权裁剪 |
| `master.migration*`（L1 迁移） | 7 | 主控迁移/续流裁剪 |
| `master.resumeTicketTtlMs` | 1 | 续流票据裁剪 |
| `master.globalPacketCompression` / `globalCompressionLevel` / `globalCompressionThreshold` / `magiclessZstd` | 4 | 管线级全局包压缩退役（通道压缩 = 聚合字典 ZSTD + 区块推送自有压缩） |
| `master.enableCompactHeader` / `master.metricsEnabled` | 2 | 紧凑包头改能力位协商；服务端指标并入 `debug.*` |
| `chunk.ovdUnloadDelaySecs` | 1 | 超视延迟卸载取消（双窗重做不恢复） |
| `chunk.viewDistanceExtensionEnabled` / `maxRenderDistance` / `ovdLocalGeneration` | — | **已恢复**（2026-09 影子双窗 OVD），见 A1 |
| `chunk.hassiumEngineEnabled` | 1 | 影子端与 `chunk.enabled` 合并（单一区块核心开关） |
| `chunk.unloadDelaySecs` / `chunk.compressionLevel` | 2 | 影子端自身 unload 语义承担；客户端压缩等级并入通道压缩 |
| `storage.mode` | 1 | 存档格式单一化（type 126） |

## 四、统计汇总

| 分类（前缀） | scope | 键数 | 默认关 / 特殊 |
|------|--------|------|----------------|
| `chunk.*` | CLIENT | 18 | `seedGenEnabled`=false；`seedGenThreads`=0 表示禁用；`ovdLocalGeneration`=false |
| `debug.*` | CLIENT | 10 | 全 false（`networkMetricsAutoReset`=true） |
| `storage.*` | SERVER | 2 | `enabled`=false |
| `master.*` | SERVER | 10 | — |
| `debug.*` | SERVER | 5 | 全 false |
| `chunk.lightStrip` | SERVER | 1 | — |
| **合计** | | **41** | |

## 五、审计方法

1. 以 `ConfigSchema.java` 静态键表为唯一真相源（`grep -oE '"(chunk|storage|master|compat|debug)\.[a-zA-Z]+"' ConfigSchema.java | sort -u`）。
2. 逐键核对 `HassiumConfigService` 读取路径与 `FabricTomlConfigIO` legacy 清理表。
3. 双端语义（`isNetworkCompressionEnabled` 等）以 `resolveNetworkEnabled` 实现为准：客户端解析 `chunk.enabled`，服务端解析 `master.enabled`。

[← architecture](architecture.md) · [Home](../README.md) · [→ version-segments](version-segments.md)
