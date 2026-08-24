# Hassium 配置项全面审计报告

审计日期：2026-08-09（config-restructure 工作流 T3 按定稿键表 `work/key-mapping.md` 全文重写；键名重排 2026-08-09 生效，**不兼容 1.x toml、无迁移逻辑**）
原审计：2026-07-21（基于 1.1.2 旧结构）与 2026-08-09 首轮（基于 2.0.0 旧键名）——键集/默认值/键名均已过时，本次全文重写。
术语按 `.omp/workflows/docs-2.0/work/domain-naming.md`（网络核心 / 区块核心 / 主控核心；影子端 = 区块核心后端引擎）。

## 一、配置文件结构与加载链

真相源：`common/.../config/ConfigSchema.java` —— **唯一 schema**，三端后端均从中生成，无手写三端 Spec 表。生效默认值 = ConfigSchema 声明默认（`ConfigValues.defaults(ConfigSchema.entries())`，FabricTomlConfigIO.java:64）。

| 加载器 | 后端 | 文件 / 模型 |
|--------|------|-------------|
| Fabric | `FabricTomlConfigIO` | **双文件模型**：`hassium/hassium-client.toml`（CLIENT scope）/ `hassium/hassium-server.toml`（SERVER scope）；物理客户端读 client，专用服读 server（FabricTomlConfigIO.java:24,31-38） |
| NeoForge | `NeoForgeConfigBackend` | 1.21.1+ `ModConfigSpec`；1.20.1 为 `ForgeConfigSpec`；按 ConfigScope 生成 **CLIENT / SERVER 双 spec**（NeoForgeConfigBackend.java:24-46） |
| Forge | `ForgeConfigBackend` | `ForgeConfigSpec`；CLIENT / SERVER 双 spec（ForgeConfigBackend.java:13-16） |

生效加载链：`HassiumConfigService.loadFromToml`（HassiumConfigService.java:59-77）→ `Services.CONFIG.load(scope)` → 三端 backend → `ConfigSnapshotAdapter.fromValues`（ConfigSnapshotAdapter.java:33-40,76-90）。

> 历史（1.1.2 及更早）：Fabric 三文件模型（`client.toml` + `common.toml` + `server.toml`）、Forge/NeoForge 三 spec（CLIENT/COMMON/SERVER）——2.0.0 已统一为**双文件 / 双 scope** 模型，旧模型不再适用。

## 二、全部配置项逐项审计（ConfigSchema，74 留存键 + 删键 4 标注）

键名前缀定稿（REQ 决策 4）：区块核心 `chunk.*` / 网络核心 `net.*`（客户端网关）/ 主控核心 `master.*`（服务端网络与推送）/ 数据面 `dataplane.*`；`storage.*` / `compat.*` / `debug.*` 前缀保留。

### A. CLIENT 键（client.toml / client spec，33 键）

**A1. chunk.\*（21 键，区块核心；原 clientCache.\* 全族 + network.seedGen.enabled）**

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `chunk.enabled` | `true` | 区块核心缓存总开关 |
| `chunk.maxSizeMb` | `4096` | 缓存容量上限（MB；影子端存档上限，超限热度淘汰） |
| `chunk.compressionLevel` | `3` | 缓存压缩等级 |
| `chunk.hotScoreThreshold` | `0.3` | 热点分数阈值（热度淘汰） |
| `chunk.recencyWeight` | `0.7` | 最近访问权重 |
| `chunk.frequencyWeight` | `0.3` | 访问频率权重 |
| `chunk.cleanupIntervalTicks` | `6000` | 清理检查间隔（刻） |
| `chunk.targetSizeMb` | `0` | 目标容量（0=自动=容量上限×0.8） |
| `chunk.minCleanupBatchSize` | `100` | 每次最少清理区块数 |
| `chunk.sectionDeltaEnabled` | `true` | 分段增量控制位（GatewayPacketCodec/NetworkCore/DataPlaneClientBundle 活跃消费） |
| `chunk.joinBoostEnabled` | `true` | 进服加速 |
| `chunk.viewDistanceExtensionEnabled` | `true` | 超视渲染（OVD）开关 |
| `chunk.maxRenderDistance` | `16` | 超视渲染 / 有效 RD 上限 |
| `chunk.ovdUnloadDelaySecs` | `5` | 超视卸载延迟秒 |
| `chunk.unloadDelaySecs` | `30` | 影子端内存区块回收延迟秒数（离开卸载边界后计时，超时落盘并清内存；0=禁用回收） |
| `chunk.maxChunksPerFrame` | `6` | 每 tick 缓存读取生产上限（OVD 入队 + 影子读盘；主线程消费只受时间预算） |
| `chunk.mainThreadChunkBudgetMs` | `15` | 主线程 apply 预算（ms） |
| `chunk.hassiumEngineEnabled` | `true` | Hassium 引擎总开关（进服启动承担光照计算；失败降级关闭缓存/OVD/SeedGen 并游戏内提示） |
| `chunk.ovdLocalGeneration` | `false` | OVD 本地生成（miss 时影子端按世界种子本地生成，renderOnly 落地；需引擎可用） |
| `chunk.seedGenThreads` | `2` | SeedGen 本地生成线程数（0=禁用本地生成，SeedRef 回退全量） |
| `chunk.seedGenEnabled` | `false` | SeedGen 开关（收到 SeedRef 本地复算；需双端同版本）。服务端开启会下发世界种子 |

**A2. net.\*（3 键，网络核心）**

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `net.enabled` | `true` | 客户端网络核心总开关（进程内网关与帧连接） |
| `net.metricsEnabled` | `false` | 客户端网络指标 |
| `net.metricsAutoReset` | `true` | 登出自动重置指标 |

**A3. debug.\*（8 键，支撑设施；不含数据面）**

`debug.metadataLogging` / `dispatcherLogging` / `asyncLogging` / `compressionLogging` / `chunkApplyLogging` / `networkLogging` / `cacheLogging` / `lightVerify` —— 全 `false`（客户端调试日志族）

### B. SERVER 键（server.toml / server spec，38 键）

**B1. storage.\*（2 键，存储域）**

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `storage.enabled` | `false` | 存档压缩开关（**默认关**；区块核心缓存独立不受影响） |
| `storage.zstdLevel` | `3` | 存储 ZSTD 等级 |

**B2. master.\*（21 键，主控核心；原 network.\* SERVER 族 + recoveryWindowMs 语义化迁移）**

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `master.enabled` | `true` | 主控核心网络通道总开关 |
| `master.compressionLevel` | `3` | 自有通道 ZSTD 压缩等级 |
| `master.magiclessZstd` | `true` | 无 magic 的 ZSTD |
| `master.globalPacketCompression` | `true` | 全局包压缩（管线级 ZSTD 替换 Zlib） |
| `master.globalCompressionLevel` | `3` | 全局压缩等级 |
| `master.globalCompressionThreshold` | `256` | 全局压缩阈值 |
| `master.useContextCompression` | `true` | 上下文压缩 |
| `master.enablePacketAggregation` | `true` | 包聚合 |
| `master.aggregationMinBatchSize` | `4` | 聚合最小批量 |
| `master.aggregationMaxWaitTimeMs` | `20` | 聚合最大等待（ms） |
| `master.aggregationMaxSize` | `262144`（256KB） | 聚合最大大小 |
| `master.enableCompactHeader` | `true` | 紧凑包头 |
| `master.compressionBlacklist` | 10 项默认黑名单（CHUNK_PAYLOAD/SECTION_DELTA/HANDSHAKE/DICTIONARY_SYNC/INDEX_SYNC/CHUNK_HASH/LIGHT_DELTA/BLOCK_ENTITY_DATA/MAIN_CHANNEL/AGGREGATION） | 压缩/聚合黑名单；默认源 HassiumConfig.java:225-235 |
| `master.metricsEnabled` | `false` | 主控网络指标 |
| `master.maxChunksPerTick` | `4` | 每玩家每 tick 提交序列化区块上限（满 tick ≈ 80/s） |
| `master.serverChunkPushThreads` | `4` | 服务端区块推送固定线程数（encode / hash / ZSTD） |
| `master.controlReachableEndpoints` | `[]` | 网关监听/outbound 端点（网关监听地址源） |
| `master.migrationFaultTimeoutMs` | `60000` | L1 迁移故障超时（ms；faultTimeout 未覆盖时生效） |

**B3. chunk.\*（2 键，区块核心，双端同名键）**

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `chunk.seedGenEnabled` | `false` | 服务端对 pristine 区块发 SeedRef；**开启会向客户端下发世界种子（泄露服务端种子）** |
| `chunk.lightStrip` | `true` | 光照剥离（剥光实际由握手协商门控） |

**B4. dataplane.\*（2 键，数据面域，不立核心名）**

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `dataplane.enabled` | `false` | UDP/KCP Data Plane 开关（**默认关**；网关↔主控通道 bulk 载体） |
| `dataplane.udpListeners` | 1 条默认（`0.0.0.0:25565`, weight=100, reachable=`127.0.0.1:25565`） | UDP listener 编码列表 |

**B5. compat.\*（2 键，支撑设施）**

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `compat.requireClientMod` | `false` | 强制客户端装 Hassium |
| `compat.autoDowngradeOnError` | `true` | 出错自动降级 |

**B6. debug.\*（6 键，支撑设施；不含元数据/缓存/光照验算）**

`debug.dispatcherLogging` / `asyncLogging` / `compressionLogging` / `chunkApplyLogging` / `networkLogging` / `dataplaneLogging` —— 全 `false`（服务端调试日志族）

### C. 删键（4 键，REQ 决策 2/B）

| 旧键 | scope | 删除原因 |
|--------|------|---------|
| `network.dataPlane.recoveryFreeze` | CLIENT | 无 UI/行为消费，仅 ClientSmokeTest 打标 |
| `network.dataPlane.controlStallMs` | SERVER | 服务端 permit 链保留但客户端已不再发 FAILOVER_REQUEST，休眠无触发（ControlFailoverHandler 引用改固定常量 6000） |
| `network.dataPlane.failoverExpiryMs` | SERVER | 同上（固定常量 30000） |
| `storage.mode` | SERVER | 用户决策不再需要配置；内部固定 mirror 字段 |

## 三、配置后端（1.1.2 → 2.0.0）

三端后端统一由 ConfigSchema 生成（`926cfeb config: dynamic schema-driven config backend across loaders`）：Fabric = TOML、NeoForge/Forge = Spec；`FabricTomlConfigIO.load()`（record 快照旧路径）已无调用者。

## 四、键变化（2.0.0 旧键名 → 2026-08-09 重排，config-restructure）

1. **键名重排（不兼容 1.x toml、无迁移逻辑；2.0.0 本来就不兼容 1.X）**：
   - `clientCache.*` 全族 → `chunk.*`（20 键，叶名微调：`cacheCompressionLevel`→`compressionLevel`、`targetCacheSizeMb`→`targetSizeMb`）
   - `network.seedGen.enabled`（双端）→ `chunk.seedGenEnabled`（叶名合并）
   - `network.enabled` 分端：CLIENT → `net.enabled`、SERVER → `master.enabled`
   - `network.metricsEnabled` / `metricsAutoReset`（CLIENT）→ `net.metrics*`；`network.metricsEnabled`（SERVER）→ `master.metricsEnabled`
   - `network.*` SERVER 压缩/聚合/推送/端点键 → `master.*`；`network.lightStrip` → `chunk.lightStrip`
   - `network.dataPlane.enabled` / `udpListeners` → `dataplane.*`；`network.dataPlane.recoveryWindowMs` → `master.migrationFaultTimeoutMs`（语义 = L1 迁移 faultTimeout）
   - 保留：`storage.enabled/zstdLevel`、`compat.*`、`debug.*`（路径不变）
2. **删除 4 键**（见 §二.C）；ControlFailoverHandler 引用字段改为固定常量（6000/30000）。
3. **默认值口径**：生效默认 = ConfigSchema（三端 backend 均从 schema 生成）。`maxChunksPerTick` / `serverChunkPushThreads` 默认 **4**。
4. **注释修正**（与 T1 同步）：`recoveryWindowMs`→migrationFaultTimeoutMs 语义、`controlReachableEndpoints` = 网关监听/outbound 端点、`sectionDeltaEnabled` 活跃消费、`net.enabled` = 网络核心总开关。

## 五、gateway 端口事实（无新增配置键的依据）

- `GatewayPlatformWiring.install` → `gateway.start(resolveBindHost(config), resolveBindPort(config))`（GatewayPlatformWiring.java:72）
- `resolveBindPort`（:148-159）：`master.controlReachableEndpoints[0].port()` 在 0<port<65536 时使用；**否则兜底 `GatewayPlayerBridge.DEFAULT_GATEWAY_PORT = 25566`**（GatewayPlayerBridge.java:88，注释"与 vanilla 端口错开"）并 warn（GatewayPlatformWiring.java:157-158）
- `resolveBindHost`（:136-146）：endpoints[0].host() 非空用之；否则 `"0.0.0.0"`
- 客户端 outbound 地址源 = 网络核心迁移引擎（非配置直读）

## 六、聚合 / 压缩键挂载点（仅主控侧 vanilla 路径）

- **聚合只挂 vanilla `Connection.send`（MixinConnection），网关通道不聚合**：`MixinConnection.hassium$tryAggregate` 仅 `packetListener instanceof ServerGamePacketListenerImpl` 时生效（"聚合只在服务端进行，客户端不聚合"，MixinConnection.java:144）→ `master.enablePacketAggregation` / `master.aggregation*` 四键为**主控侧 vanilla 路径**行为
- 网关通道（网络核心 outbound ↔ 主控核心 GatewayServer）**ZSTD 保留、无聚合**：`OutboundConnection.installZstd`（outbound/OutboundConnection.java:199-201）+ `GatewayChannel.installZstd`（gateway/GatewayChannel.java:356-358）；ZSTD 在帧协议之外（ControlFrameCodec.java:24-26）；等级/阈值键复用 `master.globalCompression*`
- 客户端侧保留聚合包解码链（handleAggregationClient，NeoForgeNetworkManager.java:2901-2919），但客户端**不聚合**
- 管线 ZSTD（`master.globalPacketCompression` 等）→ `ZstdPipelineSwitcher`（network/ZstdPipelineSwitcher.java）运行时替换 vanilla 管道 Zlib→ZSTD（MixinConnectionSetupCompression 拦截）

## 七、命令与指标键关联（全部存续）

| 命令 | 行为 | 指标键 |
|------|------|--------|
| `/hassium stats`（+`reset`/`toggle`）、`/hassium metrics on/off` | 服务端统计/重置/开关（requires metrics 开启；level 2） | `master.metricsEnabled`（SERVER） |
| `/hassiumc stats` | 客户端统计 7 行（requires metrics 开启） | `net.metricsEnabled`（CLIENT） |
| `/hassiumc export [<serverIp> [seed]]` | 拷贝影子端 `world`（含原版写出的 `level.dat`）→ `hassium_exports/<cacheId>`；也可手工拷到 `saves/` | — |

## 八、问题总结

### 🔴 死代码配置项（0 项）

已全部清理。历史删除：
- `clientCache.entitySnapshotsEnabled`（6ebaef6）
- `lightCacheEnabled`（原 `lightStripEnabled`）——2026-08-08 光照逻辑清理：光照缓存并入区块缓存（无独立开关），剥光协商改为握手能力位（客户端声明 `lightComputeSupported` = `hassiumEngineEnabled`，服务端才剥）
- `clientCache.bloomFilterEnabled` / `bloomFilterExpectedInsertions` / `bloomFilterFpp`——schema 化（926cfeb）前已裁剪，从未进入 ConfigSchema；原审计（2026-07-21）列出的该族键作废
- `clientCache.maxAgeDays` / `network.backgroundThreads` / `network.maxCallbacksPerFrame`（更早的 storage-format-unification 清理）
- `recoveryFreeze` / `controlStallMs` / `failoverExpiryMs` / `storage.mode`（2026-08-09 重排删除，见 §二.C）

### 🟡 分类问题

1. ~~`HassiumConfig.DEFAULT.maxChunksPerTick` 与 schema 不一致~~ —— **已对齐为 4**。
2. ~~ConfigSchema 内注释滞后于 2.0.0 消费语义~~ —— **已修复**（T1 修正 7 处过时注释）。
3. **聚合键挂载点收敛**：聚合/压缩/紧凑包头键全为主控侧 vanilla 路径键（`master.*`）；网关通道仅复用 `master.globalCompression*`（ZSTD 等级/阈值），无聚合、无独立键。
4. **键族归属**：2026-08-09 重排后，所有键按三核心 + 支撑域前缀归位（`chunk.*` 区块核心 / `net.*` 网络核心 / `master.*` 主控核心 / `dataplane.*` 数据面 / `storage.*` 存储 / `compat.*` 兼容 / `debug.*` 调试），无历史错放键。

## 九、统计汇总

| 分类（前缀） | domain | 键数 | 默认关 / 特殊 |
|------|--------|------|----------------|
| `chunk.*` CLIENT | 区块核心 | 21 | `ovdLocalGeneration`=false、`seedGenEnabled`=false、`seedGenThreads`=0 表示禁用 |
| `net.*` | 网络核心 | 3 | `metricsEnabled`=false |
| `debug.*` CLIENT | 调试 | 8 | 全 false |
| `storage.*` | 存储 | 2 | `enabled`=false |
| `master.*` | 主控核心 | 28 | `metricsEnabled`=false、`controlReachableEndpoints`=[]（CLIENT 6 + SERVER 22） |
| `chunk.*` SERVER | 区块核心 | 2 | `seedGenEnabled`=false |
| `dataplane.*` | 数据面 | 2 | `enabled`=false（UDP 默认关） |
| `compat.*` | 兼容 | 2 | `requireClientMod`=false |
| `debug.*` SERVER | 调试 | 6 | 全 false |
| **留存合计** | | **74** | **删键 4**（recoveryFreeze/controlStallMs/failoverExpiryMs/storage.mode） |
