# Hassium 配置项全面审计报告

审计日期：2026-08-09（docs-2.0 工作流 T6 按事实基线 `.omp/workflows/docs-2.0/work/facts-baseline.md` ① 重跑）
原审计：2026-07-21（基于 1.1.2 旧结构，键集/默认值/文件模型均已过时，本次全文重写）
术语按 `.omp/workflows/docs-2.0/work/domain-naming.md`（网络核心 / 区块核心 / 主控核心；影子端 = 区块核心后端引擎）。

## 一、配置文件结构与加载链

真相源：`common/.../config/ConfigSchema.java` —— **唯一 schema**，三端后端均从中生成，无手写三端 Spec 表。生效默认值 = ConfigSchema 声明默认（`ConfigValues.defaults(ConfigSchema.entries())`，FabricTomlConfigIO.java:64）。

| 加载器 | 后端 | 文件 / 模型 |
|--------|------|-------------|
| Fabric | `FabricTomlConfigIO` | **双文件模型**：`hassium/hassium-client.toml`（CLIENT scope）/ `hassium/hassium-server.toml`（SERVER scope）；物理客户端读 client，专用服读 server（FabricTomlConfigIO.java:24,31-38） |
| NeoForge | `NeoForgeConfigBackend` | `ModConfigSpec`（1.20.2+）；<1.20.2 为 `ForgeConfigSpec`；按 ConfigScope 生成 **CLIENT / SERVER 双 spec**（NeoForgeConfigBackend.java:24-46） |
| Forge | `ForgeConfigBackend` | `ForgeConfigSpec`；CLIENT / SERVER 双 spec（ForgeConfigBackend.java:13-16） |

生效加载链：`HassiumConfigService.loadFromToml`（HassiumConfigService.java:59-77）→ `Services.CONFIG.load(scope)` → 三端 backend → `ConfigSnapshotAdapter.fromValues`（ConfigSnapshotAdapter.java:33-40,76-90）。

> 历史（1.1.2 及更早）：Fabric 三文件模型（`client.toml` + `common.toml` + `server.toml`）、Forge/NeoForge 三 spec（CLIENT/COMMON/SERVER）——2.0.0 已统一为**双文件 / 双 scope** 模型，旧模型不再适用。

## 二、全部配置键逐项审计（ConfigSchema，75 键）

### A. CLIENT 键（client.toml / client spec，34 键）

**A1. clientCache.\*（20 键，区块核心配置族）**

| 配置项 | 默认值 | 说明 | 出处 |
|--------|--------|------|------|
| `clientCache.enabled` | `true` | 客户端缓存总开关 | ConfigSchema.java:13 |
| `clientCache.maxSizeMb` | `4096` | 缓存容量上限（MB；影子端存档上限，超限热度淘汰） | :14 |
| `clientCache.cacheCompressionLevel` | `3` | 缓存压缩等级 | :15 |
| `clientCache.hotScoreThreshold` | `0.3` | 热点分数阈值（热度淘汰） | :16 |
| `clientCache.recencyWeight` | `0.7` | 最近访问权重 | :17 |
| `clientCache.frequencyWeight` | `0.3` | 访问频率权重 | :18 |
| `clientCache.cleanupIntervalTicks` | `6000` | 清理检查间隔（刻） | :19 |
| `clientCache.targetCacheSizeMb` | `0` | 目标容量（0=自动=maxSizeMb×0.8，HassiumConfig.java:112-117） | :20 |
| `clientCache.minCleanupBatchSize` | `100` | 每次最少清理区块数 | :21 |
| `clientCache.viewDistanceExtensionEnabled` | `true` | 超视渲染（OVD）开关 | :22 |
| `clientCache.maxRenderDistance` | `16` | 超视渲染距离上限 | :23 |
| `clientCache.ovdUnloadDelaySecs` | `5` | 超视卸载延迟秒 | :24 |
| `clientCache.sectionDeltaEnabled` | `true` | 分段增量控制位（影子端 delta 消费实现前；**当前客户端 no-op**） | :25 |
| `clientCache.joinBoostEnabled` | `true` | 进服加速 | :26 |
| `clientCache.loadThreads` | `4` | 客户端区块加载线程数 | :27 |
| `clientCache.maxChunksPerFrame` | `6` | 每帧 apply 缓存区块硬顶 | :28 |
| `clientCache.mainThreadChunkBudgetMs` | `15` | 主线程 apply 预算（ms） | :29 |
| `clientCache.seedGenThreads` | `2` | SeedGen 本地生成线程数（0=禁用本地生成，SeedRef 回退全量） | :30 |
| `clientCache.ovdLocalGeneration` | `false` | OVD miss 时影子端按世界种子本地生成；需 `hassiumEngineEnabled` | :31 |
| `clientCache.hassiumEngineEnabled` | `true` | 影子端引擎总开关（进服启动承担光照计算；失败降级关闭缓存/OVD/SeedGen 并游戏内提示） | :33 |

**A2. network.\*（4 键，网络核心）**

| 配置项 | 默认值 | 说明 | 出处 |
|--------|--------|------|------|
| `network.enabled` | `true` | 客户端 Hassium 自定义通道 | ConfigSchema.java:35 |
| `network.metricsEnabled` | `false` | 客户端网络指标 | :36 |
| `network.metricsAutoReset` | `true` | 登出自动重置指标 | :37 |
| `network.seedGen.enabled` | `false` | SeedGen 开关（pristine 本地生成；需双端同版本）——归属**区块核心**的 SeedRef 机制，键名因历史留在 `network.*` | :38 |

**A3. dataPlane（1 键，数据面域；历史语义保留）**

| 配置项 | 默认值 | 说明 | 出处 |
|--------|--------|------|------|
| `network.dataPlane.recoveryFreeze` | `true` | 见 §四.3（历史语义，键保留，无 UI 定格消费，仅 ClientSmokeTest 打标） | ConfigSchema.java:70 |

**A4. debug.\*（9 键，支撑设施）**

`debug.metadataLogging` / `dispatcherLogging` / `asyncLogging` / `compressionLogging` / `chunkApplyLogging` / `networkLogging` / `cacheLogging` / `dataplaneLogging` / `lightVerify` —— 全 `false`（客户端调试日志族）｜ :74-82

### B. SERVER 键（server.toml / server spec，41 键）

**B1. storage.\*（3 键，存储域）**

| 配置项 | 默认值 | 说明 | 出处 |
|--------|--------|------|------|
| `storage.enabled` | `false` | 存档压缩开关（**默认关**） | ConfigSchema.java:40 |
| `storage.mode` | `"mirror"` | 存储模式 | :41 |
| `storage.zstdLevel` | `3` | 存储 ZSTD 等级 | :42 |

**B2. network.\*（22 键，主控核心）**

| 配置项 | 默认值 | 说明 | 出处 |
|--------|--------|------|------|
| `network.enabled` | `true` | 服务端 Hassium 自定义通道 | ConfigSchema.java:43 |
| `network.seedGen.enabled` | `false` | 服务端 SeedRef 替代区块数据（hash 兜底）——归属**区块核心** | :44 |
| `network.compressionLevel` | `3` | 自定义通道 ZSTD 压缩等级 | :45 |
| `network.magiclessZstd` | `true` | 无 magic 的 ZSTD | :46 |
| `network.globalPacketCompression` | `true` | 全局包压缩（管线级 ZSTD 替换 Zlib） | :47 |
| `network.globalCompressionLevel` | `3` | 全局压缩等级 | :48 |
| `network.globalCompressionThreshold` | `256` | 全局压缩阈值 | :49 |
| `network.useContextCompression` | `true` | 上下文压缩 | :50 |
| `network.enablePacketAggregation` | `true` | 包聚合 | :51 |
| `network.aggregationMinBatchSize` | `4` | 聚合最小批量 | :52 |
| `network.aggregationMaxWaitTimeMs` | `20` | 聚合最大等待（ms） | :53 |
| `network.aggregationMaxSize` | `262144`（256KB） | 聚合最大大小 | :54 |
| `network.enableCompactHeader` | `true` | 紧凑包头 | :55 |
| `network.compressionBlacklist` | 10 项默认黑名单（CHUNK_PAYLOAD/SECTION_DELTA/HANDSHAKE/DICTIONARY_SYNC/INDEX_SYNC/CHUNK_HASH/LIGHT_DELTA/BLOCK_ENTITY_DATA/MAIN_CHANNEL/AGGREGATION） | 压缩/聚合黑名单；默认源 HassiumConfig.java:247-256 | :56 |
| `network.metricsEnabled` | `false` | 服务端网络指标 | :57 |
| `network.maxChunksPerTick` | **`5`**（schema） | 每玩家每 tick 提交序列化区块上限（满 tick ≈ 本值×20/s） | :58 |
| `network.serverChunkPushThreads` | `2` | 服务端推送线程数 | :59 |
| `network.dynamicThreadPoolEnabled` | `true` | 动态调整推送线程 | :60 |
| `network.minPushThreads` | `2` | 动态池最小线程 | :61 |
| `network.maxPushThreads` | `8` | 动态池最大线程 | :62 |
| `network.lightStrip` | `true` | 光照剥离 | :63 |
| `network.controlReachableEndpoints` | `[]` | 控制可达端点——**网关监听地址源**（见 §五.2 gateway 端口） | :64 |

**B3. dataPlane（5 键，数据面域，不立核心名）**

| 配置项 | 默认值 | 说明 | 出处 |
|--------|--------|------|------|
| `network.dataPlane.enabled` | `false` | UDP/KCP Data Plane 开关（**默认关**；网关↔主控通道 bulk 载体） | ConfigSchema.java:65 |
| `network.dataPlane.udpListeners` | 1 条默认（`0.0.0.0:25565`, weight=100, reachable=`127.0.0.1:25565`） | UDP listener 编码列表；默认源 HassiumConfig.java:238-244 | :66 |
| `network.dataPlane.controlStallMs` | `6000` | 控制 TCP 静默时间（服务端 failover permit 判定用） | :67 |
| `network.dataPlane.failoverExpiryMs` | `30000` | failover permit 有效期 | :68 |
| `network.dataPlane.recoveryWindowMs` | `60000` | **2.0.0 语义 = 网关故障静默超时**（网络核心 L1 迁移引擎 faultTimeout），见 §四.3 | :69 |

**B4. compat.\*（2 键，支撑设施）**

| 配置项 | 默认值 | 说明 | 出处 |
|--------|--------|------|------|
| `compat.requireClientMod` | `false` | 强制客户端装 Hassium | ConfigSchema.java:71 |
| `compat.autoDowngradeOnError` | `true` | 出错自动降级 | :72 |

**B5. debug.\*（9 键，支撑设施）**

`debug.metadataLogging` / `dispatcherLogging` / `asyncLogging` / `compressionLogging` / `chunkApplyLogging` / `networkLogging` / `cacheLogging` / `dataplaneLogging` / `lightVerify` —— 全 `false`（服务端调试日志族）｜ :83-91

## 三、配置后端（1.1.2 → 2.0.0）

三端后端统一由 ConfigSchema 生成（`926cfeb config: dynamic schema-driven config backend across loaders`）：Fabric = TOML、NeoForge/Forge = Spec；`FabricTomlConfigIO.load()`（record 快照旧路径）已无调用者。

## 四、键变化（1.1.2 → 2.0.0，git `eb05ba5..HEAD`）

1. **删除 `clientCache.entitySnapshotsEnabled`**（默认 true）——提交 `6ebaef6`（entity-shadow 已提交部分；ConfigSnapshotAdapter.java / FabricTomlConfigIO.java / HassiumConfig.java 同步删字段）。**零残留**。
2. **无新增键**。gateway 无专属配置键：网关监听端口复用 `network.controlReachableEndpoints[0]`（兜底 25566），见 §五.2。**文档不得写 "gateway.* 键族"**。
3. `network.dataPlane.*` 键语义（键全保留，语义迁移）：
   - `recoveryWindowMs`：1.1.2 = 客户端候选重连窗口（failover）→ 2.0.0 = **网络核心 L1 迁移引擎故障静默超时**（NetworkCore.java:104-106 → MigrationEngine.applyRecoveryWindowFromConfig:116-122；MigrationPolicy.java:22-23 明注"沿用既有 recoveryWindowMs 语义"）
   - `controlStallMs` / `failoverExpiryMs`：服务端 ControlFailoverHandler 仍消费（ControlFailoverHandler.java:230-249；failoverPermitTtlMs:199-201）；**客户端消费链已删**（729d92e）——客户端不再发起 failover request（DataPlaneClientBundle.java:333-336 仅识别帧类型打日志）
   - `recoveryFreeze`（CLIENT）：键保留，**无 UI 定格消费**（定格 MixinGui 等已删 729d92e），仅 ClientSmokeTest 打标（ClientSmokeTest.java:103-106）。文档按"历史语义，键保留"表述
4. **默认值口径**：生效默认 = ConfigSchema（三端 backend 均从 schema 生成）。`HassiumConfig.DEFAULT.maxChunksPerTick=4`（HassiumConfig.java:275）与 schema 的 **5** 不一致——死默认（旧 record 路径已无调用方），文档口径以 **5** 为准（满刻 ≈ 100/s）；"4≈80/s" 与 "150 chunks/s" 均为过时口径。

## 五、gateway 端口事实（无新增配置键的依据）

- `GatewayPlatformWiring.install` → `gateway.start(resolveBindHost(config), resolveBindPort(config))`（GatewayPlatformWiring.java:72）
- `resolveBindPort`（:148-159）：`network.controlReachableEndpoints[0].port()` 在 0<port<65536 时使用；**否则兜底 `GatewayPlayerBridge.DEFAULT_GATEWAY_PORT = 25566`**（GatewayPlayerBridge.java:88，注释"与 vanilla 端口错开"）并 warn（GatewayPlatformWiring.java:157-158）
- `resolveBindHost`（:136-146）：endpoints[0].host() 非空用之；否则 `"0.0.0.0"`
- 客户端 outbound 地址源 = 网络核心迁移引擎（非配置直读）

## 六、聚合 / 压缩键挂载点（仅主控侧 vanilla 路径）

- **聚合只挂 vanilla `Connection.send`（MixinConnection），网关通道不聚合**：`MixinConnection.hassium$tryAggregate` 仅 `packetListener instanceof ServerGamePacketListenerImpl` 时生效（"聚合只在服务端进行，客户端不聚合"，MixinConnection.java:144）→ `enablePacketAggregation` / `aggregation*` 四键为**主控侧 vanilla 路径**行为
- 网关通道（网络核心 outbound ↔ 主控核心 GatewayServer）**ZSTD 保留、无聚合**：`OutboundConnection.installZstd`（outbound/OutboundConnection.java:199-201）+ `GatewayChannel.installZstd`（gateway/GatewayChannel.java:356-358）；ZSTD 在帧协议之外（ControlFrameCodec.java:24-26）；等级/阈值键复用 `network.globalCompression*`
- 客户端侧保留聚合包解码链（handleAggregationClient，NeoForgeNetworkManager.java:2901-2919），但客户端**不聚合**
- 管线 ZSTD（`globalPacketCompression` 等）→ `ZstdPipelineSwitcher`（network/ZstdPipelineSwitcher.java）运行时替换 vanilla 管道 Zlib→ZSTD（MixinConnectionSetupCompression 拦截）

## 七、命令与指标键关联（全部存续，基线②）

| 命令 | 行为 | 指标键 |
|------|------|--------|
| `/hassium stats`（+`reset`/`toggle`）、`/hassium metrics on/off` | 服务端统计/重置/开关（requires metrics 开启；level 2） | `network.metricsEnabled`（SERVER） |
| `/hassiumc stats` | 客户端统计 7 行（requires metrics 开启） | `network.metricsEnabled`（CLIENT） |
| `/hassiumc export [<serverIp> [seed]]` | 影子端世界目录整体拷贝 → `hassium_exports/<cacheId>` | — |

## 八、问题总结

### 🔴 死代码配置项（0 项）

已全部清理。历史删除：
- `clientCache.entitySnapshotsEnabled`（6ebaef6）
- `lightCacheEnabled`（原 `lightStripEnabled`）——2026-08-08 光照逻辑清理：光照缓存并入区块缓存（无独立开关），剥光协商改为握手能力位（客户端声明 `lightComputeSupported` = `hassiumEngineEnabled`，服务端才剥）
- `clientCache.bloomFilterEnabled` / `bloomFilterExpectedInsertions` / `bloomFilterFpp`——schema 化（926cfeb）前已裁剪，从未进入 ConfigSchema；原审计（2026-07-21）列出的该族键作废
- `clientCache.maxAgeDays` / `network.backgroundThreads` / `network.maxCallbacksPerFrame`（更早的 storage-format-unification 清理）

### 🟡 分类问题

1. **`HassiumConfig.DEFAULT.maxChunksPerTick=4` 与 schema 的 5 不一致**——死默认（无调用方），文档/运维口径一律按 5（≈100/s 满刻）。
2. **ConfigSchema 内注释滞后于 2.0.0 消费语义**（键名/路径不变，仅注释与消费方表述过时）：
   - `network.dataPlane.recoveryWindowMs` 注释仍为"候选重连窗口"——实际消费 = L1 迁移引擎故障静默超时
   - `network.controlReachableEndpoints` 注释仍为"TCP 重连可达端点"——实际语义 = 网关监听地址源（主控核心）+ 迁移引擎地址源（网络核心）
   - `network.dataPlane.recoveryFreeze` 注释仍为"主控热切恢复期画面定格"——历史语义，无 UI 消费
3. **聚合键挂载点收敛**：聚合/压缩/紧凑包头键全为主控侧 vanilla 路径键；网关通道仅复用 `globalCompression*`（ZSTD 等级/阈值），无聚合、无独立键。
4. **键族归属**（domain-naming.md 核对，详见事实基线⑤）：唯一需显式注明归属的是 `network.seedGen.enabled`（区块核心，键名历史留在 `network.*`）与 `network.dataPlane.recoveryWindowMs`（2.0.0 语义 = 网络核心迁移故障超时）。

## 九、统计汇总

| 分类 | 键数 | 默认关 / 特殊 |
|------|------|----------------|
| clientCache.\*（区块核心） | 20 | `ovdLocalGeneration`=false、`seedGenThreads`=0 表示禁用 |
| network.\* 客户端（网络核心） | 4 | `metricsEnabled`=false、`seedGen.enabled`=false |
| dataPlane.recoveryFreeze（数据面，CLIENT） | 1 | 历史语义保留键 |
| debug.\* 客户端 | 9 | 全 false |
| storage.\*（存储域） | 3 | `enabled`=false |
| network.\* 服务端（主控核心） | 22 | `seedGen.enabled`=false、`metricsEnabled`=false、`controlReachableEndpoints`=[] |
| dataPlane.\*（数据面，SERVER） | 5 | `enabled`=false（UDP 默认关） |
| compat.\* | 2 | `requireClientMod`=false |
| debug.\* 服务端 | 9 | 全 false |
| **合计** | **75** | **死代码 0** |
