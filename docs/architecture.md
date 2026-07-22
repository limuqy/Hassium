# Hassium 架构与功能说明

本文档是项目**需求要点 + 模块架构 + 配置/运维**的权威说明。多版本细节见 [`version-segments.md`](version-segments.md)；区块缓存推送流水线见 [`chunk-cache.md`](chunk-cache.md)。

## 1. 项目定位

Hassium 是 Minecraft 多加载器模组（Fabric / Forge / NeoForge），用 **ZSTD** 替代原版 Zlib，优化：

1. **世界存档压缩**（Region 外层不变，payload type `126`）
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
| `storage/` | Region 读写、`ChunkPayloadCodec`、type 126、`HassiumRegionFile`、`MetadataTable` |
| `compression/` | `CompressionCodec` / `CompressionService`、字典注册 |
| `network/` | 握手、ZSTD Pipeline、聚合、chunkHash 推送、`ServerChunkPushManager` |
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
- 服务端：`MixinRegionFile`（需 `storage.enabled`）
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

控制面（握手、index sync、chunkHash 等）在压缩黑名单，不进 PENDING 聚合缓冲。

## 5. 配置默认值（安全与行为）

配置文件：

- `config/hassium/hassium-client.toml` — 仅物理客户端（缓存与客户端网络应用项）
- `config/hassium/hassium-common.toml` — 客户端与专用服（存储、共享网络、兼容与调试）

游戏内编辑：
- **Fabric**：Night Config 自管 toml + jiJ **Cloth**；安装 **Mod Menu** 即可打开。不依赖 FCAP / Configured。
- **Forge / NeoForge**：原生 ConfigSpec + jiJ **Cloth**（模组列表「配置」按钮）；亦可手改 toml。Configured 仍可选。Forge **1.20.6** 因与 NeoForge 共用 `ModConfigSpec`，仅该端保留 FCAP Forge 桥接。
各项 GUI 文案见 `assets/hassium/lang/*`；toml 注释仍为中文。

| 项 | 默认 | 说明 |
|----|------|------|
| `storage.enabled` | **true** | 存档 ZSTD；**启用前请备份世界** |
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
| `network.maxChunksPerTick` | 10 | 每玩家每 server tick 序列化上限 |
| `network.mainThreadChunkBudgetMs` | 3 | 客户端主线程 apply 预算（ms） |
| `clientCache.lightCacheEnabled` | true | 光照缓存：首次加载重算后存储光照，缓存命中直接应用 |
| `network.maxLightRecomputePerFrame` | 10 | 每帧最多重算光照的区块数 |
| `network.metricsEnabled` | true | 指标收集 |
| `compat.requireClientMod` | false | 无模组客户端可连 |
| `debug.*` | 全 false | 调试分类日志，见下 |

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

| 特性 | 配置 / 命令 | 要点 | 详文 |
|------|-------------|------|------|
| **分段增量** | `clientCache.sectionDeltaEnabled`（默认 true） | MISMATCH 时按 section 比对，仅补变更分段 + BE 覆盖；失败/超时回退全量 | [`chunk-cache.md`](chunk-cache.md) §11.5、[`disk-nbt-cache.md`](disk-nbt-cache.md) |
| **超视渲染** | `viewDistanceExtensionEnabled`、`maxRenderDistance`、`ovdUnloadDelaySecs` | 多人、clientVD>serverVD 时本地缓存回填环带；Forget 原地 renderOnly；不向服索要视距外区块/BE | [`ovd.md`](ovd.md)、[`chunk-cache.md`](chunk-cache.md) §10 |
| **世界导出** | `/hassiumc export [<服务器IP>] [seed]` | 客户端缓存 → 原版 Anvil（type2 zlib）；无实体/仅去过的区块快照 | [`chunk-cache.md`](chunk-cache.md) §12、[`disk-nbt-cache.md`](disk-nbt-cache.md) |

客户端磁盘缓存 payload 为 NBT（`"HBT1"` + CompoundTag），主一致性路径为 **Live-Unload Snapshot**（renderOnly 跳过落盘）。旧 packet 字节缓存读到即删并全量请求。

## 10. 路线图（未实现）

- **方向性预加载**：按移动方向提高推送优先级（不改协议；方案写入 `chunk-cache.md` 时同步）
- **分段增量接回超视渲染**：超视渲染 miss 路径仍不走 sectionDelta（需可靠 merge + 集成测试）
- **`migration/` / `HassiumApi`**：公共 API / 世界迁移工具桩，尚未落地
- **Fog / Sodium 条件 Mixin**：仅在 RD>32 穿帮或实测 mesh 不建时按需补

## 11. 相关文档

- [`chunk-cache.md`](chunk-cache.md) — 缓存推送、超视渲染摘要、磁盘 NBT、导出
- [`ovd.md`](ovd.md) — 超视渲染技术实现
- [`disk-nbt-cache.md`](disk-nbt-cache.md) — 磁盘 NBT 缓存、Live-Unload、分段增量、导出细节
- [`version-segments.md`](version-segments.md) — 九段适配真相源
- [`mod-compat.md`](mod-compat.md) — 多 Mod 兼容边界与配置逃生
- 根目录 `README.md` — 用户安装与特性
- `CLAUDE.md` / `AGENTS.md` — 开发者与 Agent 入口
