# AGENTS.md

AI Agent 速查。多版本真相源见 [`docs/version-segments.md`](docs/version-segments.md)；2.0.0 网络核心架构见 [`docs/architecture.md`](docs/architecture.md)。收尾核销见 [`docs/network-core-followups.md`](docs/network-core-followups.md)（主体已落地；UDP 会话迁移等后续波见文）。

## 项目身份

Minecraft 1.20.1 / 1.21.1–1.21.11 多加载器模组（Fabric / Forge / NeoForge），ZSTD 优化存档与网络；七段适配单位见 version-segments。Forge 支持 **1.20.1 / 1.21.1 / 1.21.3–1.21.10**（1.21.2 上游无 Forge userdev；**1.21.11 起 sunset**，该段用 NeoForge）。**2.0.0** 起客户端网络由进程内网关（网络核心）接管：客户端↔世界侧纯原版协议，网关↔主控自有通道（ZSTD/UDP 数据面/hash/delta 保留；聚合为主控侧 vanilla 路径），主控切换为无感续流迁移（旧 failover 已退役）。

## 关键构建命令

`gradle.properties` 已开 `org.gradle.daemon=true`（8G）。**编译 / 测试 / 打包走 daemon，不要加 `--no-daemon`。**

先认当前 Shell，用对应启动器，不要跨壳套一层：

| | **pwsh 7** | **Git Bash** |
|--|------------|--------------|
| Gradle | `.\gradlew.bat common:compileJava` | `./gradlew common:compileJava` |
| 锚点编译 | `.\gradlew.bat compileAnchors` 或 `.\scripts\compile-anchors.ps1` | `./gradlew compileAnchors` 或 `./scripts/compile-anchors.sh` |
| 冒烟 | `.\scripts\runtime-smoke-test.ps1 ...` | `pwsh -File ./scripts/runtime-smoke-test.ps1 ...`（**无 .sh**；不要 `-NoProfile`） |
| `-Pmc_ver` | **必须** `"-Pmc_ver=1.20.1"`，否则被拆成 `1` | `-Pmc_ver=1.20.1` 即可（bash 不拆点号） |

```powershell
# pwsh
.\gradlew.bat common:decompile
.\gradlew.bat common:compileJava
.\gradlew.bat fabric:compileJava
.\gradlew.bat forge:compileJava
.\gradlew.bat neoforge:compileJava
.\gradlew.bat build
.\gradlew.bat common:test
.\gradlew.bat scanVersionBoundaries
.\gradlew.bat compileAnchors
```

```bash
# Git Bash（同一批任务，启动器换成 ./gradlew）
./gradlew common:decompile
./gradlew common:compileJava
./gradlew fabric:compileJava
./gradlew forge:compileJava
./gradlew neoforge:compileJava
./gradlew build
./gradlew common:test
./gradlew scanVersionBoundaries
./gradlew compileAnchors
```

**禁止套壳**（两壳都适用）：不要 `cmd /c "gradlew.bat --no-daemon ..."`，不要再包 `pwsh -NoProfile ...`，不要从 Git Bash 再 `cmd.exe /c` 去跑 `.bat`。Git Bash 直接 `./gradlew`；pwsh 直接 `.\gradlew.bat`。`--no-daemon` 见下节。

**Git Bash 额外陷阱**：

- **不要** `./gradlew ... 2>&1 | tail -15`（`tail` 等 EOF 才吐行，长任务看起来像卡住，还丢掉进度）。等命令自己退出，或 Await 同一 job。
- **不要** `sleep 240; ls -t build/smoke-test/logs | head`（见等待节）。
- **不要** `kill -9` / `pkill -f java` / `taskkill /F /IM java.exe`（会杀掉 daemon 和其它 Java）。
- MSYS 可能把看似路径的参数改写成 Windows 路径。Gradle `-P`/`-D` 出怪参数时，在该条命令前加 `MSYS_NO_PATHCONV=1`（或 `MSYS2_ARG_CONV_EXCL='*'`）。
- 冒烟、写 toml 仍走 **pwsh 7**（`.ps1` 用了 `Get-NetTCPConnection` / `Start-Process`；5.1 的 `utf8` 带 BOM）。调用时用 `pwsh -File`，不要 `powershell.exe`，不要 `-NoProfile`。

### Gradle daemon 与等待（AI 必读）

以前全程 `--no-daemon`，是因为代理常去监听 **GradleDaemon 的 PID**——daemon 本就不退出，于是无限等待。正确做法：

| 要等的 | 不要等的 |
|--------|----------|
| 你启动的 `gradlew` / `gradlew.bat` 命令本身 | `GradleDaemon` / 残留 `java.exe` |
| 输出出现 `BUILD SUCCESSFUL` 或 `BUILD FAILED`，且 **gradlew 已退出** | daemon 进程消失 |

- Shell：`block_until_ms` 覆盖本次构建（compile 常 2–10 min；首次 decompile 更长）。**gradlew 退出 = 构建结束，留下 daemon 是正常的。**
- 若后台跑：只 Await **本次 gradlew 那个 shell job**，pattern 用 `BUILD SUCCESSFUL|BUILD FAILED`；不要按 java PID 轮询，也不要 `notify_on_output` 去盯 daemon。
- **禁止 `sleep N` / `Start-Sleep` 硬等**（含 `sleep 240; ls logs`）。超时数字（如 `-ClientTimeoutSec` 默认 240）是脚本内部上限，不是你该睡的秒数。等的是**你启动的那条命令退出**或输出里的结束标记。
- **禁止**构建后 `taskkill /F /IM java.exe`（会杀掉 daemon 和其它 Java）。
- **禁止**在别人 / IDE 正在编时 `.\gradlew.bat --stop`（全局停所有 daemon，误杀并行会话）。切 `mc_ver` 遇 loom「Waiting for lock...」时先关 IDE Gradle Sync，或确认无其它构建后再 `--stop`。
- **仅这些用 `--no-daemon`**：直接跑 `runClient` / `runServer`（游戏 JVM 长驻）。等的是就绪日志（服务端 `Done!`），不是进程退出。**冒烟不要自己去等 `Done!`**：`runtime-smoke-test.ps1` 内部已经在等，你等脚本印 `=== RESULT:` 后退出即可。

```powershell
# pwsh
.\gradlew.bat --no-daemon :fabric:runClient
.\gradlew.bat --no-daemon :forge:runServer
.\gradlew.bat --no-daemon :neoforge:runClient
```

```bash
# Git Bash
./gradlew --no-daemon :fabric:runClient
./gradlew --no-daemon :forge:runServer
./gradlew --no-daemon :neoforge:runClient
```

写文件 / 读日志：**pwsh 7**（`pwsh`，勿用 Windows PowerShell 5.1——其 `utf8` 带 BOM 且不支持 `utf8NoBOM`）。Git Bash 里编 Gradle 用 `./gradlew`；改 toml、跑冒烟仍调 `pwsh`。

- **写文件**：pwsh 7 的 `Set-Content`/`Out-File` 默认即 UTF-8 无 BOM，写 toml/properties **不必** `-Encoding`；night-config 对 BOM 敏感（BOM 会导致整份配置静默回落默认）。
- **读日志**：Cursor 捕获 stdout 走管道、无真实控制台时，pwsh 会把输出编码锁成系统 ACP（中文 Windows = GBK），且首次输出后改不了。本机 profile 在任何输出前把 `[Console]::OutputEncoding` / `$OutputEncoding` 设为 UTF-8 无 BOM。**代理命令不要加 `-NoProfile`**，否则这段被跳过、中文乱码。`-NoProfile` 只留给必须隔离的内部子进程（如 Gradle 调 `compile-anchors.ps1`），不要套在自己的 Shell 命令外层。
`-Pmc_ver`：pwsh 必须 `"-Pmc_ver=1.20.1"`（引号）；Git Bash 写 `-Pmc_ver=1.20.1` 即可。
子工程构建产物按版本分目录（`<module>/build/<mc_ver 下划线化>/`，如 `common/build/1_21_11/`）：common 切 `-Pmc_ver` 互不覆盖、切回即 up-to-date；但 **fabric/forge/neoforge loader 子项目还会产出不分版本的泛型 `build/classes`**，跨版本切换可能残留旧变体类与版本目录并存（症状：loader 启动即崩，如 NeoForge 报 `must have exactly 1 public constructor, found 2`）。切版本后 loader 起不来时先删 `<loader>/build` 整目录再跑。根项目 `build/`（jdt-cp、smoke-test 日志）不分版本。

**IDE 编译输出目录（`<module>/bin/main`、`<module>/out/production`）同样会进运行时 classpath**：loom 组装 MOD_CLASSES 时会把存在的 Eclipse（`.classpath`→`bin/main`）与 IntelliJ（`out/production`）输出目录一并列为 mod 坐标（debug.log 可见 5 个坐标）。VS Code JDT / Eclipse 增量编译留下的**陈旧副本**会让 FML 加载到旧类——2026-08-23 实证：`neoforge/bin/main` 里 01:58 的旧 `HassiumNeoForge.class`（双构造器版）压过 04:54 新构建导致 runServer 必崩，且删 `build/` 无效。loader 起不来且 `build/` 已清时，删全部 `<module>/bin`、`<module>/out` 再跑。

## Minecraft 源码查询

查 MC 源码/映射/反编译时用 **minecraft-dev**（工具名 `minecraft_dev_*`）：
- `get_minecraft_source` / `decompile_minecraft_version` / `search_minecraft_code` / `search_indexed`（先 `index_minecraft_version`）— 按版本查反编译源码
- `find_mapping` — official / intermediary / yarn / mojmap 互查
- `analyze_mixin` / `validate_access_transformer` / `validate_access_widener` / `analyze_mod_jar` — 验证 Mixin / AT / AW / 第三方 mod

查本仓库代码优先用 **codegraph**（`codegraph_explore`），不要先 grep/Read 扫一遍。

游戏内看屏/点 UI/跑功能测试用 **minecraft-mod-mcp**（stdio 桥 `npx -y minecraft-mod-mcp`，配置见项目根 `.cursor/mcp.json`）。**不要**把 Cursor MCP 配成指向 `localhost:9876` 的 SSE。操作手册：[`docs/ai-functional-test.md`](docs/ai-functional-test.md)。与 minecraft-dev 职责不同，禁止混用（动游戏找 mod-mcp，看代码找 dev）。**现状**：dev `runClient` 尚未挂 companion mod，冒烟起的客户端目前不能被该桥驱动。

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

## 配置红线

键集真相源：`ConfigSchema`；审计表见 [`docs/config-audit.md`](docs/config-audit.md)。Fabric 双文件 `hassium-client.toml` / `hassium-server.toml`（按物理端二选一）；Forge/NeoForge 双 spec 亦按物理端二选一注册（客户端仅 CLIENT，专用服仅 COMMON，客户端不生成 server toml）。

| 项 | 默认 | 注意 |
|----|------|------|
| `storage.enabled` | **false** | 默认关；开启后改存档格式（type 126）→ 提醒备份；仅专用服务器写，单人/局域网保持原版格式（读兼容）；客户端影子端（hassium_cache）固定写 126，不受本开关约束 |
| `net.enabled` | true | 客户端网络核心总开关 |
| `master.enabled` | true | 服务端网络通道总开关 |
| `master.globalPacketCompression` | true | |
| `master.maxChunksPerTick` | 4 | 每玩家每 tick 提交上限（满 tick ≈ 80/s） |
| `master.controlReachableEndpoints` | 空→25566 | 网关监听；公网须改可达地址 |
| `chunk.enabled` | true | |
| `chunk.hassiumEngineEnabled` | true | 影子端统一算光；失败降级关缓存/OVD/SeedGen |
| `chunk.seedGenEnabled` | **false** | 双端同版本；**服务端开启会泄露世界种子** |
| `chunk.sectionDeltaEnabled` | true | 分段增量 |
| `chunk.viewDistanceExtensionEnabled` | true | 超视渲染（多人；≠ Bobby） |
| `chunk.maxRenderDistance` | 16 | 超视渲染环带上限（2–64） |
| `chunk.ovdUnloadDelaySecs` | 5 | 超视渲染卸载延迟 |
| `dataplane.enabled` | **false** | UDP 数据面；默认 listener 仅 `127.0.0.1` |
| `debug.*` | false | 热路径用 `DebugLogger` |

存档格式 type **126**（非 127）；元数据推送字段为 **chunkHash**（非 inhabitedTime）。客户端影子端世界 = `hassium_cache/<serverId>/world`（原版存档结构 + type 126 + chunkHash 落盘，`MixinRegionFile` shadow 上下文 gate）；旧 HBT1 客户端磁盘缓存已裁剪（热度清理为影子端 `ShadowCacheEviction` + `ShadowRegionHeat`：`heat.idx` 按 region 文件计，`hassium_cache/<serverId>/heat.idx` per-server）。

## 三核心速记（2.0.0）

**网络核心**（客户端进程内网关，`network/core/`）——NetworkCore 五态状态机（IDLE/CONNECTING/HANDSHAKING/ACTIVE/MIGRATING）+ `outbound/` 帧协议（TCP 控制面 + UDP 数据面启停）+ `migration/` L1 迁移引擎 + `viafabric/` 兼容桥；S2C handler 直调注入（dispatchS2C → GatewayS2CRouter），C2S routeC2S 收口；主控切换 = 换 outbound + 续流票据（ResumeTicket，epoch 防重放），客户端零重载。

**区块核心**（客户端进程内区块域）——`network/seedgen/` 影子端（= 本域后端引擎：生成/算光/落盘/淘汰）+ `network/` 顶层摄入管线（ClientChunkPipeline / ClientMetadataHandler / ChunkHash 客户端侧）+ `cache/`（OVD / MainThreadBudget / Bloom / 生命周期）；`chunk.*` 键族 = 本域配置族（2026-08-09 config-restructure：原 `clientCache.*` 重排为 `chunk.*`）。

**主控核心**（服务端进程内网络与推送）——`network/gateway/` 接入层（GatewayServer / GatewayChannel / GatewayPlayerSession / GatewayPlayerRegistry，端口 = `master.controlReachableEndpoints[0]` 或 25566 兜底）+ 服务端区块推送（ServerChunkPushManager / ChunkHashS2C / ChunkSender / SectionDelta 服务端 / ServerLoadReporter）+ 服务端聚合与 ZstdPipeline 兼容链（HassiumAggregationManager / ZstdPipelineSwitcher）。

```
客户端 world 侧（纯原版协议）── 网络核心（网关）── 帧连接 ── 主控核心（GatewayServer）
   ├ S2C 直调注入（区块/实体/业务）
   ├ C2S routeC2S 收口（keep-alive 例外走壳连接）
   ├ 续流票据 resumeAccepted → 复用推送链
   └ 主控切换 = MigrationEngine.migrateTo：关旧 outbound → 新握手带续流尾 → ACTIVE（无感）
```

- 客户端↔世界侧**零压缩/零聚合/零自定义包**；网关↔主控自有通道（ZSTD/聚合/UDP 数据面/hash/delta 全保留；聚合仅主控侧 vanilla 路径生效）
- 存储域（`storage/` `compression/`）、UDP 数据面（`network/dataplane/`）= 支撑域，不立核心名；「影子端」仅指区块核心后端引擎

## 卖点（已实现，按类）

**高效压缩**——存储压缩（ZSTD 落盘 type 126）、网络压缩（网关↔主控通道：全局包/包聚合）；**网络优化**——平滑推送（每 tick 提交上限限速 + 全路径后台化）、主控无感切换（网关换 outbound + 续流票据，客户端零重载）、L1 负载均衡（策略驱动迁移，故障/负载阈值/维护窗口/演练）；**区块缓存**——影子端世界保存（进服区块由进程内影子服务端落盘原版存档 `hassium_cache/<serverId>/world`，断连保存重连复用；目录 key 稳定不受主控切换影响）、容量/热度淘汰（heat.idx + 整文件删除 `.mca`）、分段增量、本地生成（SeedGen：pristine 区块发坐标引用，客户端同种子本地生成；**服务端开启会泄露世界种子**；失败回退全量）、超视渲染、`/hassiumc export` 世界导出；**光照优化**——Hassium 引擎（影子端统一算光 + 官方通道回传，客户端不计算；剥光握手协商）、光照剥离。

## 运行时冒烟

分层、门禁与场景见 [`docs/runtime-smoke-test.md`](docs/runtime-smoke-test.md)。L3 游戏内操作见 [`docs/ai-functional-test.md`](docs/ai-functional-test.md)。

| 层 | 载体 | 说明 |
|----|------|------|
| L0 | `common:test` | 无 MC 实例（如网关握手/续流单测） |
| L1 | classic 场景 | 全矩阵（12 版 × fabric/neoforge） |
| L2 | 场景目录 | 锚点集：seedgen / dimension / migrate 等 |
| L3 | minecraft-mod-mcp | 人工专项，不进自动 PASS 门禁 |

冒烟只有 `.ps1`（依赖 Windows 网络/进程 cmdlet），没有 bash 版。

```powershell
# pwsh：当前壳里直接跑
.\scripts\runtime-smoke-test.ps1 -Ver 1.20.1 -Loader fabric -Phase I -SessionId "1.20.1_fabric_I"
.\scripts\runtime-smoke-test.ps1 -Ver 1.20.1 -Loader fabric -Phase I -SessionId "1.20.1_fabric_I_seedgen" -Scenario seedgen
```

```bash
# Git Bash：调 pwsh 跑同一脚本（不要 -NoProfile，不要 | tail）
pwsh -File ./scripts/runtime-smoke-test.ps1 -Ver 1.20.1 -Loader fabric -Phase I -SessionId "1.20.1_fabric_I"
pwsh -File ./scripts/runtime-smoke-test.ps1 -Ver 1.20.1 -Loader fabric -Phase I -SessionId "1.20.1_fabric_I_seedgen" -Scenario seedgen
```

**这条 ps1 会自己结束**（起服 → 等 `Done!` → 起客户端 → 两轮 → 写 JSON → 印 `=== RESULT: PASS|FAIL ===` → 退出码 0/2/3）。典型 4–12 min，最坏约 `ServerReadyTimeoutSec`(160) + `ClientTimeoutSec`(240) + 收尾。

| 做 | 不要 |
|----|------|
| 前台：`block_until_ms` 至少 600000（10 min），不够再 Await 同一 job | `sleep 240` 然后 `ls build/smoke-test/logs` |
| 后台：Await **本次 ps1 那个 job**，pattern `=== RESULT:` | 把 240（客户端超时上限）当成整场时长 |
| 结束后读 `build/smoke-test/results/result_<SessionId>.json` | `| tail -15`（要等 EOF 才吐行，还丢掉 [1/9]…[9/9]） |
| 日志按 SessionId：`logs/server_<id>.log`、`client_<id>.log` | `ls -t logs \| head` 猜最新文件 |

脚本已给 `runServer`/`runClient` 加 `--no-daemon`。不要另开一套会话抢 25565/25566，也不要 `gradlew --stop`。场景文件：`common/src/main/resources/hassium/smoke/scenario/<name>.scenario`，客户端由 `ScenarioEngine` 执行。

## Skills

Manifold / 七段 / `#if MC_VER` / `PacketId` / `Identifier` 改代码时自动采用项目 skill：
[`.cursor/skills/hassium-manifold/SKILL.md`](.cursor/skills/hassium-manifold/SKILL.md)。

## 文档

- [`docs/architecture.md`](docs/architecture.md) — 架构总览
- [`docs/chunk-cache.md`](docs/chunk-cache.md) — 缓存推送、超视渲染与磁盘细节（§10 超视渲染、§11 磁盘 NBT/分段增量、§12 导出）
- [`docs/client-chunk-light-flow.md`](docs/client-chunk-light-flow.md) — 客户端光照流
- [`docs/chunk-load-optimization.md`](docs/chunk-load-optimization.md) — 进服/重连加载路径与速率锚点
- [`docs/version-segments.md`](docs/version-segments.md) — 七段适配真相源
- [`docs/mod-compat.md`](docs/mod-compat.md) — 多 Mod 兼容
- [`docs/runtime-smoke-test.md`](docs/runtime-smoke-test.md) — 运行时冒烟（L0–L3、PROBE、场景引擎）
- [`docs/ai-functional-test.md`](docs/ai-functional-test.md) — AI 游戏内功能测试（minecraft-mod-mcp）
- [`docs/config-audit.md`](docs/config-audit.md) — 配置项审计
- [`docs/network-core-followups.md`](docs/network-core-followups.md) — 网络核心收尾核销（后续波）
