# 配置

---

> **English**: [Configuration-en](Configuration-en) · 中文

Hassium 启动时在 `config/hassium/` 自动生成两份 TOML：

| 文件 | 适用端 | 主要内容 |
| --- | --- | --- |
| `hassium-client.toml` | 物理客户端 | 区块核心（`chunk.*`）、客户端调试 |
| `hassium-server.toml` | 物理客户端 + 专用服 | 存储（`storage.*`）、服务端传输面（`master.*`）、兼容（`compat.*`）、`chunk.lightStrip` / `chunk.seedGenEnabled`、服务端调试 |

**物理客户端**会同时读写两份文件：`client.toml` 配客户端行为；`server.toml` 供**单人/开局域网的集成服务器**读取（游戏内配置 UI 只显示客户端键，服务端键请直接编辑 TOML）。专用服只使用 `server.toml`。`storage.enabled` 仅在专用服务器生效，单人/局域网保持原版存档格式。

游戏内编辑入口：

| 加载器 | 入口 | 备注 |
| --- | --- | --- |
| Fabric | 先装 [Mod Menu](https://modrinth.com/mod/modmenu) 与 Cloth，再在 Mod Menu 列表里点开 | 不依赖 FCAP / Configured |
| Forge | 模组列表「配置」按钮 | 需 Cloth |
| NeoForge | 模组列表「配置」按钮 | 需 Cloth；Configured 可选 |

> 也可以直接编辑 TOML 文件后重启；GUI 与 TOML 互相同步（GUI 只改客户端字段，服务端字段原样保留）。
> 键集真相源：`ConfigSchema`（52 键）；完整审计见仓库 [`docs/config-audit.md`](https://github.com/limuqy/Hassium/blob/master/docs/config-audit.md)。
> 下表「说明」列与 TOML 内注释同源（`ConfigSchema` commentZh）。

---

## 完整配置项

### 区块核心（`chunk.*`，客户端）

| 键 | 默认 | 说明 |
| --- | --- | --- |
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

### 区块核心（`chunk.*`，服务端）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `chunk.lightStrip` | `true` | 是否启用光照剥离 |
| `chunk.seedGenEnabled` | `false` | 是否启用 SeedGen（服务端开启下发世界种子；客户端门控开时影子 tracking 触发 vanilla worldgen 本地生成，再 compare-pull；需双端同版本，默认关）。警告：开启会向客户端下发世界种子，等同泄露服务端种子 |

### 服务端传输面（`master.*`）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `master.enabled` | `true` | 是否启用服务端网络通道（压缩/聚合/区块推送/实体优化） |
| `master.enabledOnLan` | `false` | 局域网主机是否对远程玩家启用 Hassium 网络面（握手/聚合/推送/lightStrip 等）。默认关；本机 memory 连接始终原版；storage 仍仅专用服 |
| `master.compressionLevel` | `3` | 自有通道 ZSTD 压缩等级 |
| `master.enablePacketAggregation` | `true` | 是否启用包聚合 |
| `master.aggregationMaxWaitTimeMs` | `50` | 冲刷兜底：超过该时长（ms）未冲刷则强制冲一次（tick 尾冲刷为主，应对主线程卡顿） |
| `master.aggregationMaxSize` | `262144` | 聚合最大大小 |
| `master.compressionBlacklist` | `[]` | 第三方包 ID 的压缩/聚合排除列表（默认空）。Hassium 控制面与独立压缩通道已硬编码排除，改本列表不影响它们 |
| `master.maxChunksPerTick` | `5` | 每玩家每 tick 区块下发上限：Pull FULL/DELTA 完成 + 原版通道整柱发送（满 tick ≈ 本值×20/s） |

#### 实体优化（`master.entity*`）

只改实体**下发节拍**，不改协议；**原版客户端可直接连并生效**。跟 `master.enabled` 总闸；全部关掉 = 行为等同未接入。

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `master.entityTieredUpdateEnabled` | `true` | 是否按玩家距离分四档降频下发实体更新（离得越远更新越稀）。默认开；关闭后距离档表失效，密度/压力/错峰仍可独立生效 |
| `master.entityTierIntervals` | `"3,4,6,10"` | 四个距离档的实体更新间隔（刻），用逗号分隔，依次为 近/中/远/边缘；挡位边界是实体跟踪范围的 25%/50%/75%。默认 3,4,6,10（越远越稀）。数字要大不要小，须非递减；写 0 或留空用默认值 |
| `master.entityItemTierIntervals` | `"2,4,8,16"` | 掉落物与经验球的四档更新间隔（刻），逗号分隔、顺序同上，默认 2,4,8,16。物品数量多、带宽吃紧时可以把它们调稀；贴近玩家的掉落物建议不超过 3 刻，否则看起来会一跳一跳 |
| `master.entityDensityThrottleEnabled` | `true` | 是否启用区块热点降频：某个区块里实体过于密集时，对其中实体进一步加大更新间隔。默认开 |
| `master.entityDensityTierCounts` | `"10,20,32,64"` | 每档热点阈值：实体所在区块的活跃实体数达到该值时，该档的间隔按对应倍率放大。逗号分隔按 近/中/远/边缘，默认 10,20,32,64，写 0 或留空用默认值 |
| `master.entityDensityTierFactors` | `"1.5,2.0,3.0,4.0"` | 每档热点倍率：达到上面阈值后间隔乘多少倍，逗号分隔按 近/中/远/边缘，默认 1.5,2.0,3.0,4.0（1.0 = 该档不放大）。支持小数；小于 1 按 1 处理；乘上压力倍率后再受 entityMaxThrottleFactor 限制 |
| `master.entityMaxThrottleFactor` | `5` | 热点倍率与压力倍率相乘后的总上限（默认 5），用来兜住最坏情况；调大 = 密集时降得更狠 |
| `master.entityFrameBudgetPerPlayer` | `256` | 每个玩家每 tick 期望收到的实体更新包数（默认 256）。某个玩家持续超过这个量时，他视野内的实体更新会自动变稀，避免卡顿；0 = 不做这个自动限制 |
| `master.entitySmoothPushEnabled` | `true` | 实体错峰推送：同一更新间隔的实体按 UUID 稳定错开发送时刻，3 刻总量不变但不再齐发尖峰。默认开；关闭后退回原版齐发 |

### 存储（`storage.*`）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `storage.enabled` | `false` | 是否启用存档压缩（默认关；区块核心缓存独立不受影响） |
| `storage.zstdLevel` | `3` | 存储 ZSTD 压缩等级 |

### 兼容（`compat.*`）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `compat.requireClientMod` | `false` | 是否强制要求客户端安装 Hassium |
| `compat.autoDowngradeOnError` | `true` | 出错时是否自动降级 |

### 调试（`debug.*`，按端分离）

客户端 `hassium-client.toml`：

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `debug.metadataLogging` | `false` | 元数据调试日志 |
| `debug.dispatcherLogging` | `false` | 主线程调度调试日志 |
| `debug.asyncLogging` | `false` | 异步调试日志 |
| `debug.compressionLogging` | `false` | 压缩调试日志 |
| `debug.chunkApplyLogging` | `false` | 区块 apply 调试日志 |
| `debug.networkLogging` | `false` | 网络调试日志 |
| `debug.cacheLogging` | `false` | 缓存调试日志 |
| `debug.lightVerify` | `false` | 光照验算与光包落地探针 |
| `debug.networkMetricsEnabled` | `false` | 是否启用客户端网络指标 |
| `debug.networkMetricsAutoReset` | `true` | 登出服务器时自动重置网络指标 |

服务端 `hassium-server.toml`：

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `debug.dispatcherLogging` | `false` | 主线程调度调试日志 |
| `debug.asyncLogging` | `false` | 异步调试日志 |
| `debug.compressionLogging` | `false` | 压缩调试日志 |
| `debug.chunkApplyLogging` | `false` | 区块 apply 调试日志 |
| `debug.networkLogging` | `false` | 网络调试日志 |

热路径默认安静（仅少量生命周期 INFO）；排查时按需开启对应 `debug.*`。ERROR / WARN 始终输出。详见 [Troubleshooting](Troubleshooting)。

---

## 常见调节场景

| 想要效果 | 改动 |
| --- | --- |
| 关闭存档压缩（保留网络优化） | `storage.enabled = false`（默认已关） |
| 临时存档前关闭存档压缩以免格式变更 | 同上，再备份世界 |
| 关闭区块核心缓存（全程原版路径） | `chunk.enabled = false`（服务端不剥光，光照随包自带） |
| 进服本地生成（双端同版本） | 双端 `chunk.seedGenEnabled = true`（注意种子泄露面） |
| 第三方通道被聚合误伤 | 关 `master.enablePacketAggregation`，或把通道 ID 加进 `master.compressionBlacklist` |
| 关闭实体优化（退回原版实体节奏） | `master.entityTieredUpdateEnabled` / `entityDensityThrottleEnabled` / `entitySmoothPushEnabled` 全 `false`，且 `entityFrameBudgetPerPlayer = 0` |
| 实体包太多仍卡 | 调大 `entityTierIntervals` / `entityItemTierIntervals`，或调小 `entityFrameBudgetPerPlayer`、调大 `entityMaxThrottleFactor` |
| 仅享受客户端缓存（服务端不装） | 客户端单独安装即可，服务端默认 `compat.requireClientMod = false` |
| 强制客户端装模组 | 服务端 `compat.requireClientMod = true` |

---

[← Installation](Installation) · [Home](Home) · [→ Commands](Commands)
