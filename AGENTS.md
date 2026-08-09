# AGENTS.md

AI Agent 速查（原 `CLAUDE.md` 已合并于此，2026-08-09）。多版本真相源见 [`docs/version-segments.md`](docs/version-segments.md)；2.0.0 网络核心架构见 [`docs/architecture.md`](docs/architecture.md) 与 [`docs/network-core-followups.md`](docs/network-core-followups.md)（未达项清单）。

## 项目身份

Minecraft 1.20.1–1.21.11 多加载器模组（Fabric / Forge / NeoForge），ZSTD 优化存档与网络；九段适配单位见 version-segments。Forge 仅 **1.20.1 / 1.20.6**（1.21+ 用 NeoForge）。**2.0.0** 起客户端网络由进程内网关（网络核心）接管：客户端↔世界侧纯原版协议，网关↔主控自有通道（ZSTD/UDP 数据面/hash/delta 保留；聚合为主控侧 vanilla 路径），主控切换为无感续流迁移（旧 failover 已退役）。

## 关键构建命令

```bash
./gradlew --no-daemon common:decompile          # 首次 / 缺反编译产物
./gradlew --no-daemon common:compileJava        # 改 common 后先编
./gradlew --no-daemon fabric:compileJava
./gradlew --no-daemon forge:compileJava
./gradlew --no-daemon neoforge:compileJava
./gradlew --no-daemon build
./gradlew --no-daemon common:test
./gradlew --no-daemon scanVersionBoundaries
./gradlew --no-daemon compileAnchors
./gradlew --no-daemon :fabric:runClient | :forge:runServer | :neoforge:runClient
```

PowerShell：始终写 `"-Pmc_ver=1.20.1"`，否则 `1.20.1` 会被截成 `1`。

## 模块与包地图

```
common/  ← 无 fabric/forge/neoforge import
  ↑
fabric/ | forge/ | neoforge/
```

业务逻辑进 `common`；加载器 API 进对应模块；跨版本差异进 `common/.../compat/`，禁止业务散落新 `#if MC_VER`。

| 包 | 职责 |
|----|------|
| `storage/` | type 126 写缓冲 / chunkHash 桥；压缩由 `compression/CompressionService` 收口 |
| `compression/` | codec / 字典 |
| `network/` | 网络核心：`network/core/`（`NetworkCore` 五态状态机、`outbound/` 帧协议、`migration/` L1 迁移引擎、`viafabric/` 兼容桥）；区块核心：`network/seedgen/` 影子端（`ShadowSeedServer` 等，= 区块核心后端引擎）+ 顶层客户端摄入管线（ClientChunkPipeline / ClientMetadataHandler / `ServerChunkPushManager`）；主控核心：`network/gateway/`（`GatewayServer` / 玩家会话）+ 聚合与 ZstdPipeline 链（HassiumAggregationManager / ZstdPipelineSwitcher）；数据面：`network/dataplane/` |
| `cache/` | 客户端轻量设施（OVD、预算、Bloom、生命周期）；缓存存储与清理由影子端承担 |
| `config/` `metrics/` `compat/` `mixin/` | 配置、指标、跨版本桥、Mixin |
| `migration/` `api/` | 存档迁移工具与对外 API |

## ServiceLoader

1. 接口：`common/.../platform/services/IXxxHelper.java`
2. 访问：`Services.XXX`
3. 实现：三端各一份
4. 注册：`META-INF/services/<接口 FQN>`（三端都要）

漏注册 → 运行时 `NoSuchElementException`，编译不过滤。

## Mixin（仅 common）

- 命名：`@Unique` + `hassium$` 前缀
- 存储相关：入口先查 `isStorageEnabled()`
- 网络相关：入口先查网络开关 + 握手状态
- 登记：`common/src/main/resources/hassium.mixins.json`
- 优先 `@Inject` cancellable，避免 `@Overwrite`

清单与规范：skill `hassium-mixin`。

## 配置红线

| 项 | 默认 | 注意 |
|----|------|------|
| `storage.enabled` | **false** | 默认关；开启后改存档格式（type 126）→ 提醒备份；仅专用服务器写，单人/局域网保持原版格式（读兼容）；客户端影子端（hassium_cache）固定写 126，不受本开关约束 |
| `network.enabled` | true | |
| `globalPacketCompression` | true | |
| `clientCache.enabled` | true | |
| `clientCache.sectionDeltaEnabled` | true | 过期分段增量 |
| `clientCache.viewDistanceExtensionEnabled` | true | 超视渲染（多人；≠ Bobby） |
| `clientCache.maxRenderDistance` | 16 | OVD 环带上限（2–64） |
| `clientCache.ovdUnloadDelaySecs` | 5 | OVD 卸载延迟 |
| `debug.*` | false | 热路径用 `DebugLogger` |

存档格式 type **126**（非 127）；元数据推送字段为 **chunkHash**（非 inhabitedTime）。客户端影子端世界 = `hassium_cache/<serverId>/world`（原版存档结构 + type 126 + chunkHash 落盘，`MixinRegionFile` shadow 上下文 gate）；旧 HBT1 客户端磁盘缓存已裁剪（热度清理逻辑迁移为影子端 `ShadowCacheEviction`：heat.idx 容量/热度淘汰，`hassium_cache/<serverId>/heat.idx` per-server）。

## 三核心速记（2.0.0）

**网络核心**（客户端进程内网关，`network/core/`）——NetworkCore 五态状态机（IDLE/CONNECTING/HANDSHAKING/ACTIVE/MIGRATING）+ `outbound/` 帧协议（TCP 控制面 + UDP 数据面启停）+ `migration/` L1 迁移引擎 + `viafabric/` 兼容桥；S2C handler 直调注入（dispatchS2C → GatewayS2CRouter），C2S routeC2S 收口；主控切换 = 换 outbound + 续流票据（ResumeTicket，epoch 防重放），客户端零重载。

**区块核心**（客户端进程内区块域）——`network/seedgen/` 影子端（= 本域后端引擎：生成/算光/落盘/淘汰）+ `network/` 顶层摄入管线（ClientChunkPipeline / ClientMetadataHandler / ChunkHash 客户端侧）+ `cache/`（OVD / MainThreadBudget / Bloom / 生命周期）；`clientCache.*` 键族 = 本域配置族。

**主控核心**（服务端进程内网络与推送）——`network/gateway/` 接入层（GatewayServer / GatewayChannel / GatewayPlayerSession / GatewayPlayerRegistry，端口 = controlReachableEndpoints[0] 或 25566 兜底）+ 服务端区块推送（ServerChunkPushManager / ChunkHashS2C / ChunkSender / SectionDelta 服务端 / ServerLoadReporter）+ 服务端聚合与 ZstdPipeline 兼容链（HassiumAggregationManager / ZstdPipelineSwitcher）。

```
客户端 world 侧（纯原版协议）── 网络核心（网关）── 帧连接 ── 主控核心（GatewayServer）
   ├ S2C 直调注入（区块/实体/业务）
   ├ C2S routeC2S 收口（keep-alive 例外走壳连接）
   ├ 续流票据 resumeAccepted → 复用推送链
   └ 主控切换 = MigrationEngine.migrateTo：关旧 outbound → 新握手带续流尾 → ACTIVE（无感）
```

- 客户端↔世界侧**零压缩/零聚合/零自定义包**；网关↔主控自有通道（ZSTD/聚合/UDP 数据面/hash/delta 全保留；聚合仅主控侧 vanilla 路径生效）
- 存储域（`storage/` `compression/`）、UDP 数据面（`network/dataplane/`）= 支撑域，不立核心名；「影子端」仅指区块核心后端引擎
- 未达项与后续波：`docs/network-core-followups.md`（A 运行时正确性 / B 迁移运维 / C 登录语义 / D ViaFabric / E 验证缺口）

## 卖点（已实现，按类）

**高效压缩**——存储压缩（ZSTD 落盘 type 126）、网络压缩（网关↔主控通道：全局包/包聚合）；**网络优化**——平滑推送（每 tick 提交上限限速 + 全路径后台化）、主控无感切换（网关换 outbound + 续流票据，客户端零重载）、L1 负载均衡（策略驱动迁移，故障/负载阈值/维护窗口/演练）；**区块缓存**——影子端世界保存（进服区块由进程内影子服务端落盘原版存档 `hassium_cache/<serverId>/world`，断连保存重连复用；目录 key 稳定不受主控切换影响）、容量/热度淘汰（heat.idx + 逐柱删除）、分段增量、超视渲染、`/hassiumc export` 世界导出；**光照优化**——Hassium 引擎（影子端统一算光 + 官方通道回传，客户端不计算；剥光握手协商）、光照剥离。

## Skills

| Skill | 用途 |
|-------|------|
| `hassium-dev` | 构建、模块、ServiceLoader、配置、包地图 |
| `hassium-storage` | 存储 / codec / Region / 字典 |
| `hassium-network` | 网络核心（网关状态机 / outbound 帧协议 / S2C 注入 / C2S 收口 / 续流票据 / L1 迁移 / ViaFabric 桥）、主控核心接入层（GatewayServer）、chunkHash 推送、限流、指标 |
| `hassium-mixin` | Mixin 清单与注入 |

## CurseForge 本地推送

- 配置：`curseforge_project_id`（`gradle.properties`）+ `CURSEFORGE_TOKEN`（环境变量，勿提交）
- 单版本：`./gradlew build publishCurseForge "-Pmc_ver=1.20.1"`
- 全版本：`./scripts/publish-curseforge.ps1`（干跑加 `-DryRun`）

## 文档

- [`docs/architecture.md`](docs/architecture.md) — 架构总览（2.0.0 重构中）
- [`docs/chunk-cache.md`](docs/chunk-cache.md) — 缓存推送、超视渲染与磁盘细节（§10 超视渲染、§11 磁盘 NBT/分段增量、§12 导出）
- [`docs/client-chunk-light-flow.md`](docs/client-chunk-light-flow.md) — 客户端光照流
- [`docs/version-segments.md`](docs/version-segments.md) — 九段适配真相源
- [`docs/mod-compat.md`](docs/mod-compat.md) — 多 Mod 兼容
- [`docs/runtime-smoke-test.md`](docs/runtime-smoke-test.md) — 运行时冒烟
- [`docs/network-core-followups.md`](docs/network-core-followups.md) — 网络核心未达项（后续波）
