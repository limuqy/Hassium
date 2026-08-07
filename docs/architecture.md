# Hassium 架构与功能说明

本文档是项目**需求要点 + 模块架构 + 配置/运维**的权威说明。多版本细节见 [`version-segments.md`](version-segments.md)；区块缓存推送流水线见 [`chunk-cache.md`](chunk-cache.md)。

## 1. 项目定位

Hassium 是 Minecraft 多加载器模组（Fabric / Forge / NeoForge），用 **ZSTD** 替代原版 Zlib，优化：

1. **世界存档压缩**（Region 外层不变，payload type `126`；默认关，仅专用服务器）
2. **网络传输压缩**（自定义 `hassium:*` 通道 + 可选全局包压缩）
3. **客户端区块缓存**（本地 Region + `chunkHash` 命中跳过全量下载）
4. **分段增量**（缓存过期时仅补变更 section，默认开）
5. **超视渲染**（多人客户端 RD > 服务端视距时，本地缓存回填环带，仅渲染）
6. **世界导出**（本地缓存导出为可进单机的原版 Anvil 世界）

目标版本：Minecraft **1.20.1–1.21.11**（九段适配，见 version-segments）。Forge 仅 **1.20.1 / 1.20.6**。

## 2. 模块结构

```
Hassium/
├── common/              # 共享逻辑（合并进各加载器源码集编译）
├── fabric/ / forge/ / neoforge/
├── versionProperties/   # 每 MC 版本 builds_for、依赖版本
└── buildSrc/            # architectury-loom + Manifold
```

依赖方向：`common` ← 加载器模块。平台差异通过 `platform/services/` + ServiceLoader。

### 包地图（common）

| 包 | 职责 |
|----|------|
| `storage/` | `HassiumRegionFile`、`MetadataTable`、`RegionBitmap`、`HassiumChunkWriteBuffer`；type 126 压缩由 `compression/CompressionService` 收口 |
| `compression/` | `CompressionCodec` / `CompressionService`、字典注册 |
| `network/` | 握手、ZSTD Pipeline、聚合、chunkHash 推送、`ServerChunkPushManager`；`network/dataplane/` 多通道数据面（1.20.1 fabric PoC：`DataPlaneFrame` / `Hkdf` / `DataPlaneCodec` / `BulkRouter` / `DataPlaneServer` / `DataPlaneClientBundle` 等，见 [`multi-channel_network_research.md`](multi-channel_network_research.md)） |
| `cache/` | 客户端缓存、Bloom、`ClientHeatIndex` / `SectionHashStore`、淘汰 |
| `config/` | `HassiumConfigService` 门面；Fabric：`HassiumTomlConfigIO`；Forge/NeoForge：`HassiumConfigSpec` |
| `metrics/` | `NetworkStats` 零分配指标 |
| `compat/` | Manifold 跨版本 API 桥接 |
| `mixin/` | 全部 Mixin（common only） |
| `migration/` / `api/` | 路线图桩（未实现） |

## 3. 存储格式

外层保持 Anvil（`.mca`，32×32）：

```
Sector 0:     Offset Table
Sector 1–2:   MetadataTable v2（1024 × int64 contentHash）
Sector 3+:    [length(4)][type=126][ZSTD 压缩数据]
```

- **无** HassiumEnvelope / HSM1 / type 127 运行时写入（127 仅作未来原版 scheme 迁移规划）
- 服务端：`MixinRegionFile`（需 `storage.enabled`；仅专用服务器写，单人/局域网保持原版格式，读兼容）
- 客户端缓存：`HassiumRegionFile` 同构；`contentHash` = `combine(sectionHashes)`（与网络 chunkHash 一致）
- 客户端辅存：`heat.idx`（热度）、`section_hashes.bin`（per-section 哈希）
- 字典缺失时拒绝写入 Hassium payload，回退原版

## 4. 网络压缩

| 能力 | 说明 | 默认 |
|------|------|------|
| 自定义通道 | `hassium:*` ZSTD 传区块等 | `network.enabled=true` |
| 全局包压缩 | Pipeline 替换原版 Zlib | `globalPacketCompression=true` |
| 上下文 / magicless / 聚合 | 提升压缩比 | 均默认启用 |
| 紧凑包头 | 聚合包内 `CompactHeaderCodec` | 默认启用 |
| 平滑推送 | 每 tick 提交上限限速（`maxChunksPerTick=5`，满 tick ≈ 100/s，掉刻时每 tick 提交量不变、每秒总量自然下降保护主线程）；encode/压缩/hash/发送全后台（1.21.2+ 主线程仅 build，<1.21.2 全后台） | 默认启用 |
| UDP/KCP 数据面 | 每个 `udpListeners` 项建立独立 KCP session；按 `weight` 加权轮询发送 S2C bulk，异常时自动回落 TCP | `network.dataPlane.enabled=true`（默认端点仅本机可用） |
| TCP 控制恢复 | 数据面健康时允许经 `FailoverPermit` 迁移主控 TCP；候选耗尽后只执行一次终态清理 | 默认 6 s stall、30 s permit、60 s 恢复窗口 |

控制面（握手、index sync、chunkHash 等）在压缩黑名单，不进 PENDING 聚合缓冲，也不走 UDP 数据面；UDP 只承载 Bind 后的 S2C bulk。多通道的早期裸 TCP PoC 已退役；运行时验证见 [`runtime-smoke-test.md`](runtime-smoke-test.md) 的 `UdpFailover` phase。

## 5. 配置默认值（安全与行为）

配置文件：

- `config/hassium/hassium-client.toml` — 仅物理客户端（缓存与客户端网络应用项）
- `config/hassium/hassium-server.toml` — 客户端与专用服（存储、服务端网络、兼容与调试）

游戏内编辑：
- **Fabric**：Night Config 自管 toml + jiJ **Cloth**；安装 **Mod Menu** 即可打开。不依赖 FCAP / Configured。
- **Forge / NeoForge**：原生 ConfigSpec + jiJ **Cloth**（模组列表「配置」按钮）；亦可手改 toml。Configured 仍可选。Forge **1.20.6** 因与 NeoForge 共用 `ModConfigSpec`，仅该端保留 FCAP Forge 桥接。
各项 GUI 文案见 `assets/hassium/lang/*`；toml 注释仍为中文。

| 项 | 默认 | 说明 |
|----|------|------|
| `storage.enabled` | **false** | 默认关；开启后存档 ZSTD（type 126）；**启用前请备份世界**。仅专用服务器写（单人/局域网保持原版格式，读兼容） |
| `storage.mode` | `mirror` | 镜像模式 |
| `storage.zstdLevel` | 9 | 存储压缩等级 |
| `clientCache.enabled` | true | 客户端缓存 |
| `clientCache.sectionDeltaEnabled` | true | 缓存过期时分段增量（关则过期走全量） |
| `clientCache.viewDistanceExtensionEnabled` | true | 超视渲染（依赖 clientCache.enabled；与 Bobby 互斥） |
| `clientCache.maxRenderDistance` | 32 | 运行时有效 RD / 超视渲染环带上限（2–64；默认对齐 vanilla 滑块） |
| `clientCache.ovdUnloadDelaySecs` | 5 | 超视渲染离开环带后延迟卸载秒数（0=同步卸载） |
| `network.enabled` | true | Hassium 通道 |
| `network.globalPacketCompression` | true | 全局 ZSTD |
| `network.compressionLevel` | 3 | 网络压缩等级（速度优先） |
| `network.maxChunksPerTick` | 5 | 每玩家每 tick 提交上限（1.21.2+ 为主线程序列化上限，1.20.x/1.21.1 为后台提交上限；发送速率 = 本值 × tick 节奏，满 tick ≈ 5×20/s） |
| `network.dataPlane.enabled` | true | 启用 UDP/KCP 数据面和控制恢复；关后不启动 UDP listener、不广告端点、不处理 failover |
| `network.dataPlane.controlStallMs` | 6000 | 控制 TCP 静默多久后可申请 failover（ms） |
| `network.dataPlane.failoverExpiryMs` | 30000 | 服务端 `FailoverPermit` 有效期（ms） |
| `network.dataPlane.recoveryWindowMs` | 60000 | 客户端候选重连窗口（ms） |
| `clientCache.mainThreadChunkBudgetMs` | 15 | 客户端主线程 apply 预算（ms） |
| `clientCache.parallelLightEngineEnabled` | false | 并行光照（需接入 Promethium）：默认官方引擎——光照重算经统一异步缓冲队列，帧尾按预算消费（每帧部分预算，剩余留帧）；开启后转 Promethium 后台线程池重算 + 主线程帧预算原子落地 |
| `clientCache.parallelLightEngineThreads` | 4 | 并行光照线程数（虚拟线程模式忽略） |
| `clientCache.lightSyncMode` | false | 光照重算同步模式（双帧缓冲）：默认官方引擎异步预算消费（黑块随重算逐帧消减）；开启后本帧收集无光照区块、下一帧尾阻塞全量落地（黑块窗口 ≤1 帧；落地量受 chunk apply 限流约束；与并行光照同开时本项优先） |
| `clientCache.lightCacheEnabled` | true | 光照缓存：首次加载重算后存储光照，缓存命中直接应用 |
| `network.lightStrip` | true | 光照剥离：服务端发包带空 lightMask，客户端本地重算 |
| `network.maxLightRecomputePerFrame` | 10 | 每帧最多重算光照的区块数 |
| `network.metricsEnabled` | false | 指标收集 |
| `compat.requireClientMod` | false | 无模组客户端可连 |
| `debug.*` | 全 false | 调试分类日志，见下 |

`network.controlReachableEndpoints` 是客户端主控 TCP 重连候选；每项为 `{ host, port, priority }`，最多 4 项。`network.dataPlane.udpListeners` 是服务端 UDP socket 与其客户端可达地址的列表；每项为 `{ bindHost, bindPort, weight, reachableEndpoints }`，其中 `reachableEndpoints` 同样使用 `{ host, port, priority }`，最多 8 项。`bindHost` 只在服务端绑定，绝不下发给客户端；公网服必须把默认的 `127.0.0.1:25565` 改成客户端实际可达的 UDP 地址，并放行对应 UDP 端口。

## 6. 日志策略

正常加载路径默认安静：仅少量生命周期 INFO（初始化、字典加载、握手摘要、管道切换、断开清理）。

热路径（收发包、命中/未命中、压缩大小等）走 `DebugLogger`，由 `debug.*` 控制：

| 配置键 | 含义 |
|--------|------|
| `debug.metadataLogging` | chunkHash / 元数据比对 |
| `debug.dispatcherLogging` | 主线程调度 |
| `debug.asyncLogging` | 异步任务 |
| `debug.compressionLogging` | 压缩/解压 |
| `debug.chunkApplyLogging` | 区块 apply |
| `debug.networkLogging` | 网络收发 |
| `debug.cacheLogging` | 缓存读写 |

ERROR / WARN 始终输出。

## 7. 命令与监控

| 命令 | 侧 | 说明 |
|------|-----|------|
| `/hassium stats` | 服务端 | 压缩/发送统计（需 OP 2） |
| `/hassium metrics on\|off` | 服务端 | 运行时开关指标 |
| `/hassium stats reset` | 服务端 | 重置计数器 |
| `/hassiumc stats` | 客户端 | 接收/缓存命中/超视渲染 统计 |
| `/hassiumc export [<服务器IP>] [seed]` | 客户端 | 导出本地缓存为 `saves/` 下原版 Anvil 世界 |

实现：`metrics/NetworkStats`（`AtomicLong`，可关闭）。指标关闭时相关 stats 命令不可用。导出走 `CacheWorldExporter`（异步，见 `disk-nbt-cache.md` / `chunk-cache.md` §12）。

## 8. 构建与运行

```bash
./gradlew build
./gradlew build "-Pmc_ver=1.21.1"   # PowerShell 必须给 -P 加引号
./gradlew :fabric:runClient
./gradlew :forge:runServer
./gradlew common:compileJava        # 改 common 后先编
./gradlew scanVersionBoundaries
./gradlew compileAnchors
```

首次或缺少反编译产物：`./gradlew common:decompile`。

## 9. 卖点特性（已实现摘要）

按大类组织：**高效压缩 / 网络优化 / 区块缓存 / 光照优化 / 实用工具**。

### 9.1 高效压缩

| 特性 | 配置 / 命令 | 要点 | 详文 |
|------|-------------|------|------|
| **存储压缩** | `storage.enabled`（默认 false，仅专用服）、`storage.zstdLevel`（9） | chunk payload ZSTD 落盘 type 126；外层 Region 不变；启用改写落盘格式需备份 | [`chunk-cache.md`](chunk-cache.md)、[`disk-nbt-cache.md`](disk-nbt-cache.md) |
| **网络压缩** | `network.enabled`、`globalPacketCompression`、`compressionLevel`、`enablePacketAggregation` | `hassium:*` 通道 ZSTD + 可选全局管道替换 Zlib + 聚合/紧凑包头/上下文压缩 | [`chunk-cache.md`](chunk-cache.md) |

### 9.2 网络优化

| 特性 | 配置 / 命令 | 要点 | 详文 |
|------|-------------|------|------|
| **平滑推送** | `network.maxChunksPerTick`（5）、`serverChunkPushThreads` | 每 tick 提交上限限速（满 tick ≈ 5×20/s，掉刻自然降速保护主线程；主线程峰值 ≤8ms/tick）；encode/压缩/hash/发送全在推送池——1.21.2+ 主线程仅 build（对齐原版，ThreadingDetector 约束），<1.21.2 全后台 | [`chunk-cache.md`](chunk-cache.md)、[`runtime-smoke-test.md`](runtime-smoke-test.md) |
| **主控热切** | `network.controlReachableEndpoints`、`network.dataPlane.controlStallMs` | TCP 主控 `channelInactive`，或控制面 stalled 且 UDP 健康时，客户端进入恢复态并按 priority 串行重连；恢复期世界定格（tick 暂停、断连画面抑制、过渡画面仅隐藏渲染；可切无感模式），画面保持冻结世界 + 切换浮层；服务端许可路径以 `FailoverPermit` 限定时效；候选耗尽后终态清理只执行一次 | [`runtime-smoke-test.md`](runtime-smoke-test.md) §`udp-failover` |
| **加权分流** | `network.dataPlane.udpListeners`、每 listener `weight` | 每个 UDP/KCP listener 建独立 session，S2C bulk 按权重加权轮询；不健康或无可用 session 时回落 TCP，控制面始终保留 TCP | [`runtime-smoke-test.md`](runtime-smoke-test.md) §`udp-failover` |
| **多通道数据面（历史）** | 早期 `DataPlanePoCConfig` | 1.20.1 Fabric 的双裸 TCP PoC 已退役，不是生产配置或运维入口 | [`multi-channel_network_research.md`](multi-channel_network_research.md) |

### 9.3 区块缓存

| 特性 | 配置 / 命令 | 要点 | 详文 |
|------|-------------|------|------|
| **客户端区块缓存** | `clientCache.enabled`（默认 true） | chunkHash 比对命中即本地 apply；磁盘 NBT（`HBT1`）按热度淘汰 | [`chunk-cache.md`](chunk-cache.md)、[`disk-nbt-cache.md`](disk-nbt-cache.md) |
| **分段增量** | `clientCache.sectionDeltaEnabled`（默认 true） | MISMATCH 时按 section 比对，仅补变更分段 + BE 覆盖；失败/超时回退全量 | [`chunk-cache.md`](chunk-cache.md) §11.5、[`disk-nbt-cache.md`](disk-nbt-cache.md) |
| **超视渲染** | `viewDistanceExtensionEnabled`、`maxRenderDistance`、`ovdUnloadDelaySecs` | 多人、clientVD>serverVD 时本地缓存回填环带；Forget 原地 renderOnly；不向服索要视距外区块/BE | [`ovd.md`](ovd.md)、[`chunk-cache.md`](chunk-cache.md) §10 |
| **世界导出** | `/hassiumc export [<服务器IP>] [seed]` | 客户端缓存 → 原版 Anvil（type2 zlib）；无实体/仅去过的区块快照 | [`chunk-cache.md`](chunk-cache.md) §12、[`disk-nbt-cache.md`](disk-nbt-cache.md) |

### 9.4 光照优化

| 特性 | 配置 / 命令 | 要点 | 详文 |
|------|-------------|------|------|
| **光照剥离** | `network.lightStrip`（默认 true） | 服务端发包带空 lightMask（构造近零成本）；客户端本地重算并回写缓存 | [`chunk-cache.md`](chunk-cache.md)、[`runtime-smoke-test.md`](runtime-smoke-test.md) |
| **光照缓存** | `clientCache.lightCacheEnabled`（默认 true） | 首次重算后缓存光照，命中直接 apply 跳过同步重算；SectionDelta 合并强制失效 | [`chunk-cache.md`](chunk-cache.md)、[`disk-nbt-cache.md`](disk-nbt-cache.md) |
| **并行光照** | `clientCache.parallelLightEngineEnabled`（默认 false）、`parallelLightEngineThreads`（4） | 可选：需安装 Promethium MOD（客户端运行时经 `PromethiumLightBridge` 反射发现，零编译依赖；MOD 缺席自动降级官方引擎）。开启后重算在后台线程池并行，主线程只做快照捕获与帧预算内原子落地；默认官方引擎——光照重算经统一异步缓冲队列（帧尾预算消费，每帧部分预算） | [`chunk-cache.md`](chunk-cache.md)、[`runtime-smoke-test.md`](runtime-smoke-test.md) |
| **同步光照** | `clientCache.lightSyncMode`（默认 false） | 可选：官方引擎光照重算同步模式（双帧缓冲）——本帧收集无光照区块，下一帧尾阻塞全部重算落地，黑块窗口 ≤1 帧；默认异步预算消费（每帧部分预算，剩余留帧）；与并行光照同开时本项优先 | [`client-chunk-light-flow.md`](client-chunk-light-flow.md) |

客户端磁盘缓存 payload 为 NBT（`"HBT1"` + CompoundTag），主一致性路径为 **Live-Unload Snapshot**（renderOnly 跳过落盘）。控制恢复期间缓存写队列与执行器保持可用，重连成功后继续命中既有缓存；旧 packet 字节缓存读到即删并全量请求。

## 9.5. UDP 数据面 + TCP 控制 Failover 运维

**拓扑与职责**：原版 Minecraft TCP 连接仍是 login、控制与兼容回退路径；UDP/KCP 只承载已 Bind session 的 S2C bulk。服务端从 `network.dataPlane.udpListeners` 向客户端广告可达 UDP 地址，并从 `network.controlReachableEndpoints` 广告备用 TCP 主控地址。两类地址必须分别配置：前者需要 UDP 防火墙/NAT 放行，后者必须能建立完整 Minecraft TCP 会话。

**恢复触发**：主控 TCP `channelInactive` 立即进入候选重连；控制面静默达到 `controlStallMs` 时，仅在 UDP session 健康且服务端签发未过期 `FailoverPermit` 的情况下允许迁移。客户端在 `recoveryWindowMs` 内按 priority 串行尝试候选；期间抑制最终断连并保留磁盘缓存、保存队列、任务执行器。**无缝定格（全版本，`network.dataPlane.recoveryFreeze`）**：恢复期间世界 tick/实体 tick 暂停（画面定格），vanilla 断连流程（`clearLevel`/DisconnectedScreen）与过渡画面（ConnectScreen/ProgressScreen/ReceivingLevelScreen）均不呈现——过渡 screen 保持 vanilla 逻辑驱动（ConnectScreen 必须可见，Fabric 经 `screen instanceof ConnectScreen` 取回候选连接的 Login listener），仅渲染层跳过其绘制，画面保持冻结世界 + 「正在切换主控…」浮层；恢复成功 `setLevel` 换新世界，候选耗尽才执行一次 terminal cleanup。其他版本段保留非无缝路径（断连画面短暂出现，缓存秒回）。

**配置原则**：默认 listener `0.0.0.0:25565` 仅将 `127.0.0.1:25565` 作为客户端可达地址，适合本机开发，不能直接用于公网服。公网部署必须为每个 listener 填写可从客户端访问的 `reachableEndpoints`，避免把 wildcard 或内网 bind 地址下发；使用不同公网端口时，TCP 控制候选与 UDP 可达端点应分别写入。

**冒烟验证**：运行 `UdpFailover` phase 可核验 `UDP_BIND_OK`、`UDP_WRR_OK`、`FAILOVER_PERMIT_OK`、`FAILOVER_RECONNECT_OK`、`CACHE_RESUME_HIT` 与候选耗尽时的 `FAILOVER_TERMINAL_OK`。`network.dataPlane.enabled=false` 时必须不存在 UDP listener、Bind 或 permit marker。Nginx stream harness 仅代理 TCP 主控，UDP 仍直连，详见 [`runtime-smoke-test.md`](runtime-smoke-test.md) §`udp-failover`。

## 11. 相关文档

- [`chunk-cache.md`](chunk-cache.md) — 缓存推送、超视渲染摘要、磁盘 NBT、导出
- [`ovd.md`](ovd.md) — 超视渲染技术实现
- [`disk-nbt-cache.md`](disk-nbt-cache.md) — 磁盘 NBT 缓存、Live-Unload、分段增量、导出细节
- [`version-segments.md`](version-segments.md) — 九段适配真相源
- [`mod-compat.md`](mod-compat.md) — 多 Mod 兼容边界与配置逃生
- [`multi-channel_network_research.md`](multi-channel_network_research.md) — 多通道设计与已退役裸 TCP PoC 的历史记录
- [`runtime-smoke-test.md`](runtime-smoke-test.md) — 多版本 runtime smoke 与 UDP failover harness
- 根目录 `README.md` — 用户安装与特性
- `CLAUDE.md` / `AGENTS.md` — 开发者与 Agent 入口
