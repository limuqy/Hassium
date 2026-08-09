# 配置

---

> **English**: [Configuration-en](Configuration-en) · 中文

Hassium 启动时在 `config/hassium/` 自动生成两份 TOML：

| 文件 | 适用端 | 主要内容 |
| --- | --- | --- |
| `hassium-client.toml` | 仅物理客户端 | 区块缓存（`chunk.*`）、渲染与生成、网络核心（`net.*`）、客户端调试 |
| `hassium-server.toml` | 仅专用服 | 存储（`storage.*`）、主控核心（`master.*`）、数据面（`dataplane.*`）、兼容（`compat.*`）、调试 |

游戏内编辑入口：

| 加载器 | 入口 | 备注 |
| --- | --- | --- |
| Fabric | 先装 [Mod Menu](https://modrinth.com/mod/modmenu) 与 Cloth，再在 Mod Menu 列表里点开 | 不依赖 FCAP / Configured |
| Forge | 模组列表「配置」按钮 | 需 Cloth |
| NeoForge | 模组列表「配置」按钮 | 需 Cloth；Configured 可选 |

> 也可以直接编辑 TOML 文件后重启；GUI 与 TOML 互相同步。
> 游戏内 UI 分 4 类：区块缓存 / 渲染与生成 / 网络与连接 / 调试（仅客户端键）；服务端键需直接编辑 `hassium-server.toml`。

---

## 完整配置项

### 区块缓存（`chunk.*`，区块核心）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `chunk.enabled` | `true` | 客户端区块缓存总开关 |
| `chunk.maxSizeMb` | `4096` | 缓存容量上限（MB）；超过后按热度清理最久未用的区块 |
| `chunk.sectionDeltaEnabled` | `true` | 缓存过期时只补变更分段；关闭则过期走全量重传 |
| `chunk.hassiumEngineEnabled` | `true` | Hassium 引擎（非网络向功能总开关）：进服启动影子端统一承担区块光照计算（客户端不再计算）；启动失败自动降级（缓存/超视渲染/SeedGen 关闭并提示）；关闭时服务端不剥光（握手协商），光照随包自带 |
| `chunk.ovdLocalGeneration` | `false` | 超视渲染本地生成：超视渲染区域缓存 miss 时按服务端世界种子本地生成区块并存入本地缓存；无种子（服务端未装 MOD）时自动关闭生成 |
| `chunk.mainThreadChunkBudgetMs` | `15` | 客户端每帧 apply 区块的预算（ms）；进服前约 10 秒走 JoinBoost 临时抬高 |

### 渲染与生成（`chunk.*`，区块核心）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `chunk.viewDistanceExtensionEnabled` | `true` | 超视渲染（多人服 clientVD > serverVD 时回填环带；**与 Bobby 互斥**） |
| `chunk.maxRenderDistance` | `16` | 超视渲染环带与有效 RD 上限（范围 2–64） |
| `chunk.ovdUnloadDelaySecs` | `5` | 离开超视渲染环带后延迟卸载秒数（0=同步卸载） |
| `chunk.loadThreads` | `4` | 客户端区块加载线程数 |
| `chunk.maxChunksPerFrame` | `6` | 每帧 apply 缓存区块硬顶 |
| `chunk.seedGenThreads` | `2` | 本地区块生成线程数（0=禁用本地生成，区块一律全量下载） |
| `chunk.seedGenEnabled` | `false` | 本地区块生成（双端键）：收到 SeedRef 引用时本地按世界种子重新生成区块（哈希校验兜底），避免整块下载；需双端同版本 |

### 网络核心（`net.*`）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `net.enabled` | `true` | 客户端网络核心总开关（进程内网关与优化通道；关后回退原版区块包） |
| `net.metricsEnabled` | `false` | 客户端网络指标（关闭后 `/hassiumc stats` 不可用） |
| `net.metricsAutoReset` | `true` | 退出服务器时自动清零本次会话的指标计数 |

### 主控核心（`master.*`，服务端网络与推送）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `master.enabled` | `true` | 服务端网络通道总开关 |
| `master.globalPacketCompression` | `true` | 全局管道用 ZSTD 替换原版 Zlib（关闭可与同类协议替换类 mod 共存） |
| `master.compressionLevel` | `3` | 自有通道压缩等级（速度优先） |
| `master.maxChunksPerTick` | `5` | 每玩家每 tick 提交上限（发送速率 = 本值 × tick 节奏，满 tick ≈ 5×20 = 100/s；掉刻自然降速保护主线程） |
| `master.enablePacketAggregation` | `true` | 包聚合；第三方通道被拦截异常时关掉 |
| `master.compressionBlacklist` | 10 项默认黑名单 | 包 ID 列表，命中的包不进压缩/聚合（默认含 CHUNK_PAYLOAD / SECTION_DELTA / HANDSHAKE / DICTIONARY_SYNC / INDEX_SYNC / CHUNK_HASH / LIGHT_DELTA / BLOCK_ENTITY_DATA / MAIN_CHANNEL / AGGREGATION） |
| `master.metricsEnabled` | `false` | 服务端网络指标（关闭后 `/hassium stats` 等命令不可用） |
| `master.controlReachableEndpoints` | `[]` | 网关监听端点（`endpoints[0]` 即网关端口，未配置时兜底 `25566`） |
| `master.migrationFaultTimeoutMs` | `60000` | 主控故障静默超时（ms）：超过后 L1 迁移引擎无感切换主控 |

### 数据面（`dataplane.*`）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `dataplane.enabled` | `false` | UDP 数据面（网关↔主控通道的 bulk 载体；默认关，启用前请按公网可达地址配置 listener 并放行 UDP 端口） |
| `dataplane.udpListeners` | 1 条默认（`0.0.0.0:25565`，reachable `127.0.0.1:25565`） | UDP listener 编码列表；`reachableEndpoints` 需填客户端可达的公网地址 |

### 存储（`storage.*`）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `storage.enabled` | `false` | 世界存档改用 ZSTD type 126（默认关；仅专用服务器可开启，**首次启用前请备份世界**） |
| `storage.zstdLevel` | `3` | 存储压缩等级；越高省磁盘越多、CPU 越重 |

### 兼容（`compat.*`）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `compat.requireClientMod` | `false` | 关 = 无模组客户端可连（仅享受服务端压缩）；开则强制客户端装模组 |
| `compat.autoDowngradeOnError` | `true` | 出错时自动回退原版行为 |

### 调试（`debug.*`，双端）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `debug.metadataLogging` | `false` | chunkHash / 元数据比对日志 |
| `debug.dispatcherLogging` | `false` | 主线程调度日志 |
| `debug.asyncLogging` | `false` | 异步任务日志 |
| `debug.compressionLogging` | `false` | 压缩/解压日志 |
| `debug.chunkApplyLogging` | `false` | 区块 apply 日志 |
| `debug.networkLogging` | `false` | 网络收发日志 |
| `debug.cacheLogging` | `false` | 缓存读写日志 |
| `debug.dataplaneLogging` | `false` | 数据面热路径日志 |
| `debug.lightVerify` | `false` | 光照验算日志 |

热路径默认安静（仅少量生命周期 INFO）；排查时按需开启对应 `debug.*`。ERROR / WARN 始终输出。详见 [Troubleshooting](Troubleshooting)。

---

## 常见调节场景

| 想要效果 | 改动 |
| --- | --- |
| 关闭存档压缩（保留网络优化） | `storage.enabled = false` |
| 临时存档前关闭存档压缩以免格式变更 | 同上，再备份世界 |
| 关闭超视渲染恢复原版 RD 钳制 | `chunk.viewDistanceExtensionEnabled = false` |
| 提高超视渲染上限到 48 | `chunk.maxRenderDistance = 48`，并手改 `options.txt` 抬高客户端滑块；注意 RD>32 时雾距可能穿帮 |
| 关闭 Hassium 引擎（不启动影子端；服务端不剥光，光照随包自带） | `chunk.hassiumEngineEnabled = false` |
| 进服本地生成（双端同版本） | 双端 `chunk.seedGenEnabled = true` |
| 与同进程 Via 桥叠用 | 关 `master.globalPacketCompression` |
| 第三方通道被聚合误伤 | 关 `master.enablePacketAggregation`，或把通道 ID 加进 `master.compressionBlacklist` |
| 启用 UDP 数据面 | `dataplane.enabled = true`，并把 `dataplane.udpListeners` 的可达地址改为公网地址、放行 UDP 端口 |
| 仅享受客户端缓存（服务端不装） | 客户端单独安装即可，服务端默认 `compat.requireClientMod = false` |

---

[← Installation](Installation) · [Home](Home) · [→ Commands](Commands)
