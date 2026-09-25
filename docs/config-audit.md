# Hassium 配置项审计

> 审计日期：2026-09-16（`master.entity*` 实体网络优化键族共 **9 键**：分层更新 2 + 物品流独立档位 1 + 热点分档 3 + 帧预算压力 2 + 错峰 1；分档表统一逗号分隔；真相源 `ConfigSchema`，**52 键**）。
> 历史审计：2026-07-21（1.1.2 旧结构）、2026-08-09（config-restructure，74 键 + 删键 4）、2026-09-04（直连拓扑裁剪标注）、2026-09-10（OVD 退役 38 键）、2026-09-12（44 键）、2026-09-14（+`master.enabledOnLan`，45 键）、2026-09-15（51→52 口径校正）——旧键集见历史提交。

## 一、配置文件结构与加载链

真相源：`common/.../config/ConfigSchema.java` —— **唯一 schema**，三端后端均从中生成，无手写三端 Spec 表。生效默认值 = ConfigSchema 声明默认。**TOML/GUI 注释与用户文档「说明」列同源**（`ConfigComments.bilingual(commentZh, commentEn)`）。

| 加载器 | 后端 | 文件 / 模型 |
|--------|------|-------------|
| Fabric | `FabricTomlConfigIO` | **双文件模型**：`hassium/hassium-client.toml`（CLIENT scope）/ `hassium/hassium-server.toml`（SERVER scope）；**物理客户端双文件合并**（client 配客户端行为，server 供集成服务器/局域网；UI 只显示客户端键）；专用服仅 server |
| NeoForge | `NeoForgeConfigBackend` | 1.21.1+ `ModConfigSpec`；按 ConfigScope 生成 **CLIENT / SERVER 双 spec**；物理客户端双注册（CLIENT + COMMON），专用服仅 COMMON |
| Forge | `ForgeConfigBackend` | `ForgeConfigSpec`；CLIENT / SERVER 双 spec；物理客户端双注册（CLIENT + COMMON），专用服仅 COMMON |

生效加载链：`HassiumConfigService.loadFromToml` / `syncFromSpec` → 物理客户端 `ConfigSnapshotAdapter.fromMerged(clientValues, serverValues)`，专用服 `fromValues(serverValues, false)` → 三端 backend。

> 历史（1.1.2 及更早）：Fabric 三文件模型（`client.toml` + `common.toml` + `server.toml`）、Forge/NeoForge 三 spec（CLIENT/COMMON/SERVER）——2.0.0 已统一为**双文件 / 双 scope** 模型。物理客户端曾只读 client.toml（服务端侧键固定默认），后改为双文件合并以支持单人/局域网调节 `master.*` / `chunk.lightStrip` 等。

**Legacy key hygiene**：Fabric 加载/保存时按本 scope Schema 清除未知残留键（`purgeUnknownKeys`），不迁移、不报错。已清除键族：`net.*` 全族、`dataplane.*`、`master.controlReachableEndpoints` / `bindHost` / `authToken` / `migration*`（7 键）/ `resumeTicketTtlMs` / `globalPacketCompression` / `globalCompressionLevel` / `globalCompressionThreshold` / `magiclessZstd`、`chunk.ovdUnloadDelaySecs`（延迟卸载取消）/ `hassiumEngineEnabled` / `unloadDelaySecs` / `compressionLevel`、`storage.mode`、`chunk.seedGenThreads` / `master.serverChunkPushThreads`、`chunk.ovdLocalGeneration`。OVD 两键（`viewDistanceExtensionEnabled` / `maxRenderDistance`）已随双窗重做恢复，**仅 CLIENT scope**；误写入 server.toml 的客户端键（如冒烟脚本历史注入）亦被清除。

## 二、全部配置项（ConfigSchema，52 键）

键名前缀：区块核心 `chunk.*` / 服务端传输面 `master.*` / 存储 `storage.*` / 兼容 `compat.*` / 调试 `debug.*`。说明列与 `ConfigSchema` commentZh 一致（= TOML 注释中文行）。

### A. CLIENT 键（client.toml / client spec，26 键）

**A1. chunk.\*（15 键，区块核心）**

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `chunk.enabled` | `true` | 是否启用区块核心缓存 |
| `chunk.maxSizeMb` | `4096` | 缓存最大容量（MB；影子端存档容量上限，超限触发热度淘汰） |
| `chunk.hotScoreThreshold` | `0.3` | 热点分数阈值（低于此值视为冷 region 文件，清理时优先淘汰） |
| `chunk.recencyWeight` | `0.7` | 最近访问权重 |
| `chunk.frequencyWeight` | `0.3` | 访问频率权重 |
| `chunk.cleanupIntervalTicks` | `6000` | 清理检查间隔（刻） |
| `chunk.targetSizeMb` | `0` | 目标缓存大小（MB；0=自动） |
| `chunk.minCleanupBatchSize` | `100` | 每轮最多淘汰的 region 文件数 |
| `chunk.sectionDeltaEnabled` | `true` | 是否启用分段增量（服务端规划 + 客户端应用） |
| `chunk.maxChunksPerFrame` | `6` | 每 tick 缓存读取生产上限（影子入队 + 影子读盘；主线程消费只受时间预算） |
| `chunk.mainThreadChunkBudgetMs` | `15` | 主线程 apply 预算（ms） |
| `chunk.seedGenEnabled` | `false` | 是否启用 SeedGen（本地生成 pristine 区块；需双端同版本，默认关）。服务端开启时会下发世界种子 |
| `chunk.viewDistanceExtensionEnabled` | `true` | 超视渲染 OVD（影子双窗：clientRD>serverVD 时本地源回填环带） |
| `chunk.maxRenderDistance` | `16` | 超视渲染 effective clientRD 上限 |
| `chunk.lightHaloRadius` | `1` | 光照光环半径（环）：计算/拉取域 = 服务端视距 + 此值，专供权威边界柱补齐 3×3 邻域（对齐原版 `ChunkStatus.LIGHT` range=1）；只算不交付。0=关（回退旧语义）。上限 1 = `ShadowPullRadii.AUTHORITY_MARGIN − 1`（客户端窗口 `contains(VD+R)` 的切比雪夫外接盒是 `cheb ≤ VD+R+1`，服务端签发是 `cheb ≤ VD+AUTHORITY_MARGIN`，对齐要求 `R+1 ≤ AUTHORITY_MARGIN`） |


**A2. debug.\*（CLIENT 10 键；与 SERVER 同名键共用路径，scope 隔离）**

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `debug.metadataLogging` | `false` | 元数据调试日志 |
| `debug.dispatcherLogging` | `false` | 主线程调度调试日志 |
| `debug.asyncLogging` | `false` | 异步调试日志 |
| `debug.compressionLogging` | `false` | 压缩调试日志 |
| `debug.chunkApplyLogging` | `false` | 区块 apply 调试日志 |
| `debug.networkLogging` | `false` | 网络调试日志 |
| `debug.cacheLogging` | `false` | 缓存调试日志 |
| `debug.lightVerify` | `false` | 历史别名，等价 `debug.lightLogging`（任一开即生效） |
| `debug.lightLogging` | `false` | 光照调试总开关：算光链路日志 + 引擎/光包探针 + 冒烟 `lightProbe` 块 |
| `debug.networkMetricsEnabled` | `false` | 是否启用客户端网络指标 |
| `debug.networkMetricsAutoReset` | `true` | 登出服务器时自动重置网络指标 |

### B. SERVER 键（server.toml / server spec，28 键；含双 scope 键 `chunk.seedGenEnabled` 的服务端侧，客户端侧见 A1）

**B1. chunk（2 键，区块核心服务端侧）**

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `chunk.lightStrip` | `true` | 是否启用光照剥离 |
| `chunk.seedGenEnabled` | `false` | 是否启用 SeedGen（服务端开启下发世界种子；客户端门控开时影子 tracking 触发 vanilla worldgen 本地生成，再 compare-pull；需双端同版本，默认关）。警告：开启会向客户端下发世界种子，等同泄露服务端种子 |

**B2. storage.\*（2 键，存储域）**

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `storage.enabled` | `false` | 是否启用存档压缩（默认关；区块核心缓存独立不受影响） |
| `storage.zstdLevel` | `3` | 存储 ZSTD 压缩等级 |

**B3. master.\*（17 键，服务端传输面 / 实体网络优化）**

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `master.enabled` | `true` | 是否启用服务端网络通道（压缩/聚合/区块推送/实体优化） |
| `master.enabledOnLan` | `false` | 局域网主机是否对远程玩家启用 Hassium 网络面（握手/聚合/推送/lightStrip 等）。默认关；本机 memory 连接始终原版；storage 仍仅专用服 |
| `master.compressionLevel` | `3` | 自有通道 ZSTD 压缩等级 |
| `master.enablePacketAggregation` | `true`（检测到 BandwidthOptimizer 时默认 `false`） | 是否启用包聚合。三方兼容（`compat/mods/ModCompatFlags`）：BO 的原版字节流批处理/流式压缩与聚合帧叠加冲突，检测到时新配置默认关；显式 `true` 恒生效，**已落盘的旧值视为显式选择、不自动翻转** |
| `master.aggregationMaxWaitTimeMs` | `50` | 冲刷兜底：超过该时长（ms）未冲刷则强制冲一次（tick 尾冲刷为主，应对主线程卡顿） |
| `master.aggregationMaxSize` | `262144` | 聚合最大大小 |
| `master.compressionBlacklist` | `[]` | 第三方包 ID 的压缩/聚合排除列表（默认空）。Hassium 控制面与独立压缩通道已硬编码排除，改本列表不影响它们 |
| `master.maxChunksPerTick` | `5` | 每玩家每 tick 区块下发上限：Pull FULL/DELTA 完成 + 原版通道整柱发送（满 tick ≈ 本值×20/s） |
| `master.entityTieredUpdateEnabled` | `true` | 是否按玩家距离分四档降频下发实体更新（离得越远更新越稀）。默认开；关闭后距离档表失效，密度/压力/错峰仍可独立生效 |
| `master.entityTierIntervals` | `"3,4,6,10"` | 四个距离档的实体更新间隔（刻），用逗号分隔，依次为 近/中/远/边缘；挡位边界是实体跟踪范围的 25%/50%/75%。默认 3,4,6,10（越远越稀）。数字要大不要小，须非递减；写 0 或留空用默认值。最终生效间隔（乘上密度/压力倍率后）建议 ≤ 20 刻：客户端平滑窗口上限 20 刻，超出会回退 3 刻窗口、画面走-停 |
| `master.entityItemTierIntervals` | `"2,4,8,16"` | 掉落物与经验球的四档更新间隔（刻），逗号分隔、顺序同上，默认 2,4,8,16。物品数量多、带宽吃紧时可以把它们调稀；贴近玩家的掉落物建议不超过 3 刻，否则看起来会一跳一跳 |
| `master.entityDensityThrottleEnabled` | `true` | 是否启用区块热点降频：某个区块里实体过于密集时，对其中实体进一步加大更新间隔。默认开 |
| `master.entityDensityTierCounts` | `"10,20,32,64"` | 每档热点阈值：实体所在区块的活跃实体数达到该值时，该档的间隔按对应倍率放大。逗号分隔按 近/中/远/边缘，默认 10,20,32,64，写 0 或留空用默认值 |
| `master.entityDensityTierFactors` | `"1.5,2.0,3.0,4.0"` | 每档热点倍率：达到上面阈值后间隔乘多少倍，逗号分隔按 近/中/远/边缘，默认 1.5,2.0,3.0,4.0（1.0 = 该档不放大）。支持小数；小于 1 按 1 处理；乘上压力倍率后再受 entityMaxThrottleFactor 限制 |
| `master.entityMaxThrottleFactor` | `5` | 热点倍率与压力倍率相乘后的总上限（默认 5），用来兜住最坏情况；调大 = 密集时降得更狠 |
| `master.entityFrameBudgetPerPlayer` | `256` | 每个玩家每 tick 期望收到的实体更新包数（默认 256）。某个玩家持续超过这个量时，他视野内的实体更新会自动变稀，避免卡顿；0 = 不做这个自动限制 |
| `master.entitySmoothPushEnabled` | `true` | 实体错峰推送：同一更新间隔的实体按 UUID 稳定错开发送时刻，3 刻总量不变但不再齐发尖峰。默认开；关闭后退回原版齐发 |

**B4. compat.\*（2 键）**

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `compat.requireClientMod` | `false` | 是否强制要求客户端安装 Hassium |
| `compat.autoDowngradeOnError` | `true` | 出错时是否自动降级 |

**B5. debug.\*（SERVER 6 键；与 CLIENT 同名键共用路径，scope 隔离；不含元数据/缓存/光照）**

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `debug.dispatcherLogging` | `false` | 主线程调度调试日志 |
| `debug.asyncLogging` | `false` | 异步调试日志 |
| `debug.compressionLogging` | `false` | 压缩调试日志 |
| `debug.chunkApplyLogging` | `false` | 区块 apply 调试日志 |
| `debug.networkLogging` | `false` | 网络调试日志 |
| `debug.exportAggregatedPackets` | `false` | 导出聚合包调试流：所有经过服务端发包拦截点的 S2C 包（含编码后的聚合帧）逐行写入 `logs/hassium-aggregated-packets/*.jsonl`（payload base64）。导出点在握手/聚合 gating 之前，单人/LAN 集成服同样生效；每包写盘，仅诊断用 |


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
| `chunk.viewDistanceExtensionEnabled` / `maxRenderDistance` | — | **已恢复**（2026-09 影子双窗 OVD），见 A1 |
| `chunk.ovdLocalGeneration` | 1 | OVD 窗本地生成退役删除（OVD 回填只读本地已有数据） |
| `chunk.hassiumEngineEnabled` | 1 | 影子端与 `chunk.enabled` 合并（单一区块核心开关） |
| `chunk.unloadDelaySecs` / `chunk.compressionLevel` | 2 | 影子端自身 unload 语义承担；客户端压缩等级并入通道压缩 |
| `storage.mode` | 1 | 存档格式单一化（type 126） |

## 四、统计汇总

| 分类（前缀） | scope | 键数 | 默认关 / 特殊 |
|------|--------|------|----------------|
| `chunk.*` | CLIENT | 15 | `seedGenEnabled`=false |
| `debug.*` | CLIENT | 10 | 全 false（`networkMetricsAutoReset`=true） |
| `storage.*` | SERVER | 2 | `enabled`=false |
| `master.*` | SERVER | 17 | `enabledOnLan`=false；`entity*` 键族 = 实体网络优化 **9 键**（分层更新总开关 + 两张逗号分隔档位表 + 每档热点阈值/倍率 + 倍率上限 + 帧预算压力 + 错峰，默认全开） |
| `compat.*` | SERVER | 2 | `requireClientMod`=false |
| `debug.*` | SERVER | 6 | 全 false |
| `chunk.lightStrip` / `chunk.seedGenEnabled` | SERVER | 2 | `seedGenEnabled`=false |
| **合计** | | **53** | |

## 五、审计方法

1. 以 `ConfigSchema.java` 静态键表为唯一真相源（`grep -oE '"(chunk|storage|master|compat|debug)\.[a-zA-Z]+"' ConfigSchema.java | sort -u`）。
2. 逐键核对 `HassiumConfigService` 读取路径与 `FabricTomlConfigIO` legacy 清理表。
3. 双端语义（`isNetworkCompressionEnabled` 等）以 `resolveNetworkEnabled` 实现为准：客户端解析 `chunk.enabled`，服务端解析 `master.enabled`。
4. 用户文档（README / wiki Configuration）「说明」列 = `ConfigSchema` commentZh/commentEn；改注释时三处同步。

[← architecture](architecture.md) · [Home](../README.md) · [→ version-segments](version-segments.md)
