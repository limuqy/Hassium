# 配置

---

> **English**: [Configuration-en](Configuration-en) · 中文

Hassium 启动时在 `config/hassium/` 自动生成两份 TOML：

| 文件 | 适用端 | 主要内容 |
| --- | --- | --- |
| `hassium-client.toml` | 仅物理客户端 | 区块核心（`chunk.*`）、客户端调试 |
| `hassium-server.toml` | 仅专用服 | 存储（`storage.*`）、服务端传输面（`master.*`）、兼容（`compat.*`）、调试 |

游戏内编辑入口：

| 加载器 | 入口 | 备注 |
| --- | --- | --- |
| Fabric | 先装 [Mod Menu](https://modrinth.com/mod/modmenu) 与 Cloth，再在 Mod Menu 列表里点开 | 不依赖 FCAP / Configured |
| Forge | 模组列表「配置」按钮 | 需 Cloth |
| NeoForge | 模组列表「配置」按钮 | 需 Cloth；Configured 可选 |

> 也可以直接编辑 TOML 文件后重启；GUI 与 TOML 互相同步。
> 键集真相源：`ConfigSchema`（38 键）；完整审计见仓库 [`docs/config-audit.md`](https://github.com/limuqy/Hassium/blob/master/docs/config-audit.md)。

---

## 完整配置项

### 区块核心（`chunk.*`，客户端）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `chunk.enabled` | `true` | 区块核心总开关（影子端世界保存/算光/缓存/Pull 模式；关后全程原版路径） |
| `chunk.maxSizeMb` | `4096` | 缓存容量上限（MB）；超过后按热度整文件删除冷 region（`.mca`） |
| `chunk.hotScoreThreshold` | `0.3` | 热点分数阈值（低于视为冷 region，清理时优先淘汰） |
| `chunk.recencyWeight` | `0.7` | 热度分数中最近访问权重 |
| `chunk.frequencyWeight` | `0.3` | 热度分数中访问频率权重 |
| `chunk.cleanupIntervalTicks` | `6000` | 清理检查间隔（刻） |
| `chunk.targetSizeMb` | `0` | 目标缓存大小（MB；0=自动） |
| `chunk.minCleanupBatchSize` | `100` | 每轮最多淘汰的 region 文件数 |
| `chunk.sectionDeltaEnabled` | `true` | 缓存过期时只补变更方块（过多则整段/整块）；关闭则过期走全量 |
| `chunk.maxChunksPerFrame` | `6` | 每 tick 缓存读取生产上限（影子入队 + 影子读盘）；主线程 apply 只受 `mainThreadChunkBudgetMs` 约束 |
| `chunk.mainThreadChunkBudgetMs` | `15` | 客户端每帧 apply 区块的预算（ms）；进服 30s 内走 JoinBoost 临时抬高 |
| `chunk.seedGenThreads` | `2` | 本地区块生成线程数（0=禁用本地生成，SeedRef 一律回退全量） |
| `chunk.seedGenEnabled` | `false` | 本地区块生成（双端键）：收到 SeedRef 引用时本地按世界种子重新生成区块（哈希校验兜底），避免整块下载；需双端同版本。**服务端开启会下发世界种子（泄露种子）** |

### 区块核心（`chunk.*`，服务端）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `chunk.lightStrip` | `true` | 光照剥离：发包可带空 lightMask；实际剥光由握手协商（客户端声明引擎可用才剥） |

### 服务端传输面（`master.*`）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `master.enabled` | `true` | 服务端网络通道总开关（登录期握手/聚合的门） |
| `master.compressionLevel` | `3` | 自有通道 ZSTD 压缩等级（速度优先） |
| `master.useContextCompression` | `true` | 上下文压缩（字典 ZSTD） |
| `master.enablePacketAggregation` | `true` | 包聚合；第三方通道被拦截异常时关掉 |
| `master.aggregationMinBatchSize` | `4` | 聚合最小批量 |
| `master.aggregationMaxWaitTimeMs` | `50` | 聚合最大等待时间（ms；ACK 超时 5s 自动降级直发） |
| `master.aggregationMaxSize` | `262144` | 聚合最大大小（字节） |
| `master.compressionBlacklist` | 控制面键集 | 包 ID 列表，命中的包不进压缩/聚合（默认含控制面：握手 / 字典 / 索引 / chunkHash / 光增量 / BE 数据等） |
| `master.maxChunksPerTick` | `5` | 每玩家每 tick 完成的 Pull FULL/DELTA 上限（发送速率 = 本值 × tick 节奏，满 tick ≈ 5×20 = 100/s；UNCHANGED 另额 32；掉刻自然降速保护主线程） |
| `master.serverChunkPushThreads` | `4` | 服务端区块推送固定线程数（encode / hash / ZSTD 后台池） |

### 存储（`storage.*`）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `storage.enabled` | `false` | 世界存档改用 ZSTD type 126（默认关；仅专用服务器可开启，**首次启用前请备份世界**） |
| `storage.zstdLevel` | `3` | 存储压缩等级；越高省磁盘越多、CPU 越重 |

### 兼容（`compat.*`）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `compat.requireClientMod` | `false` | 关 = 无模组客户端可连（仅享受服务端压缩）；开则登录期握手失败即踢出 |
| `compat.autoDowngradeOnError` | `true` | 出错时自动回退原版行为 |

### 调试（`debug.*`，按端分离）

客户端 `hassium-client.toml`：

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `debug.metadataLogging` | `false` | chunkHash / 元数据比对日志 |
| `debug.dispatcherLogging` | `false` | 主线程调度日志 |
| `debug.asyncLogging` | `false` | 异步任务日志（含 SeedGen / 影子端） |
| `debug.compressionLogging` | `false` | 解压日志 |
| `debug.chunkApplyLogging` | `false` | 区块 apply 日志 |
| `debug.networkLogging` | `false` | 网络收发日志 |
| `debug.cacheLogging` | `false` | 缓存读写日志 |
| `debug.lightVerify` | `false` | 光照验算日志 |
| `debug.networkMetricsEnabled` | `false` | 客户端网络指标（冒烟测试 `hassium.smokeTest=true` 强开） |
| `debug.networkMetricsAutoReset` | `true` | 退出服务器时自动清零本次会话的指标计数 |

服务端 `hassium-server.toml`：

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `debug.dispatcherLogging` | `false` | 主线程调度 / MSPT 日志 |
| `debug.asyncLogging` | `false` | 异步任务日志 |
| `debug.compressionLogging` | `false` | 压缩发包日志 |
| `debug.chunkApplyLogging` | `false` | 区块补发 / resync 日志 |
| `debug.networkLogging` | `false` | 网络收发日志 |

热路径默认安静（仅少量生命周期 INFO）；排查时按需开启对应 `debug.*`。ERROR / WARN 始终输出。详见 [Troubleshooting](Troubleshooting)。

---

## 常见调节场景

| 想要效果 | 改动 |
| --- | --- |
| 关闭存档压缩（保留网络优化） | `storage.enabled = false`（默认已关） |
| 临时存档前关闭存档压缩以免格式变更 | 同上，再备份世界 |
| 关闭影子端/缓存（全程原版路径） | `chunk.enabled = false`（服务端不剥光，光照随包自带） |
| 进服本地生成（双端同版本） | 双端 `chunk.seedGenEnabled = true`（注意种子泄露面） |
| 第三方通道被聚合误伤 | 关 `master.enablePacketAggregation`，或把通道 ID 加进 `master.compressionBlacklist` |
| 仅享受客户端缓存（服务端不装） | 客户端单独安装即可，服务端默认 `compat.requireClientMod = false` |
| 强制客户端装模组 | 服务端 `compat.requireClientMod = true` |

---

[← Installation](Installation) · [Home](Home) · [→ Commands](Commands)
