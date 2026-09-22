# AGENTS.md

AI Agent 速查。多版本真相源见 [`docs/version-segments.md`](docs/version-segments.md)；架构与握手时序见 [`docs/architecture.md`](docs/architecture.md)。

## 项目身份

Minecraft 1.20.1 / 1.21.1–1.21.11 多加载器模组（Fabric / Forge / NeoForge），ZSTD 优化存档与网络。Forge 支持 **1.20.1 / 1.21.1 / 1.21.3–1.21.10**（1.21.2 无 Forge userdev；**1.21.11 起 sunset**，该段用 NeoForge）。**1.20.1 不构建 neoforge**（NeoForge 用户用 forge 子项目产物）。**回归直连拓扑**：客户端↔服务端唯一 vanilla TCP（登录期能力握手 + Play 期 `play_init_s2c` 激活）；网络核心/UDP 数据面/续流迁移已裁剪，不复活。

## 关键构建命令

`gradle.properties` 已开 daemon（8G）。**编译 / 测试 / 打包走 daemon，不要加 `--no-daemon`。** 先认当前 Shell，用对应启动器，不要跨壳套一层：

| | **pwsh 7** | **Git Bash** |
|--|------------|--------------|
| Gradle | `.\gradlew.bat common:compileJava` | `./gradlew common:compileJava` |
| 冒烟 | `.\scripts\runtime-smoke-test.ps1 ...` | `pwsh -File ./scripts/runtime-smoke-test.ps1 ...`（**无 .sh**） |
| `-Pmc_ver` | **必须** `"-Pmc_ver=1.20.1"`（引号，否则拆成 `1`） | `-Pmc_ver=1.20.1` 即可 |

常用任务：`common:decompile` / `common:compileJava` / `fabric|forge|neoforge:compileJava` / `build` / `common:test` / `scanVersionBoundaries`。

**禁止套壳**：不要 `cmd /c "gradlew.bat ..."`、不要包 `pwsh -NoProfile`、不要 Git Bash 再转 `cmd.exe /c`。冒烟/写 toml 走 **pwsh 7**（5.1 的 `utf8` 带 BOM；night-config 对 BOM 敏感 → 配置静默回落默认）。代理命令**不要加 `-NoProfile`**（会跳过 profile 的 UTF-8 初始化 → 中文乱码）。

**Git Bash 陷阱**：不要 `| tail`（等 EOF 才吐行）；不要 `sleep N; ls logs`；不要 `kill -9` / `pkill -f java` / `taskkill /F /IM java.exe`；Gradle 参数被 MSYS 改路径时加 `MSYS_NO_PATHCONV=1`。

### Gradle daemon 与等待（AI 必读）

| 要等的 | 不要等的 |
|--------|----------|
| 你启动的 `gradlew` / `gradlew.bat` 命令本身 | `GradleDaemon` / 残留 `java.exe` |
| 输出 `BUILD SUCCESSFUL` 或 `BUILD FAILED`，且 **gradlew 已退出** | daemon 进程消失 |

- 前台：`block_until_ms` 覆盖本次构建（compile 常 2–10 min）；后台：Await **本次 gradlew 那个 shell job**，pattern `BUILD SUCCESSFUL|BUILD FAILED`。
- **禁止 `sleep N` / `Start-Sleep` 硬等**；**禁止**构建后 `taskkill java`；**禁止**在他人/IDE 构建时 `gradlew --stop`。
- **仅 `runClient` / `runServer` 用 `--no-daemon`**（游戏 JVM 长驻，等就绪日志）。冒烟脚本内部已等 `Done!`，你等它印 `=== RESULT:` 退出即可。
- 分版本产物在 `<module>/build/<mc_ver 下划线化>/`，切 `-Pmc_ver` 互不覆盖，无需 clean。
- **测试发现失败 ≠ 断言失败**：`common:test` 报 `TestEngine with ID 'junit-jupiter' failed to discover tests` → 多半是 `common/build/*/classes/java/test/` 陈旧测试类残留；处置 `rm -rf common/build/*/classes/java/test common/build/*/test-results` 后复跑。
- **loader 起不来且 `build/` 已清** → 删 IDE 输出目录 `<module>/bin`、`<module>/out`（loom 会把它们并进 MOD_CLASSES，旧类压过新构建）。

## Minecraft 源码查询

查 MC 源码/映射/反编译用 **minecraft-dev**（`minecraft_dev_*`）：`get_minecraft_source` / `decompile_minecraft_version` / `search_minecraft_code` / `search_indexed`（先 `index_minecraft_version`）/ `find_mapping` / `analyze_mixin` / `validate_access_transformer` / `validate_access_widener` / `analyze_mod_jar`。查本仓库优先 **codegraph**（`codegraph_explore`），不要先 grep/Read 扫一遍。

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
| `network/` | 直连传输面：`network/handshake/` + 客户端摄入 + 服务端区块推送/聚合；区块核心 `network/seedgen/` 影子端 |
| `cache/` | 客户端轻量设施（预算、生命周期、mesh 编译日志）；存储与清理由影子端承担 |
| `config/` `metrics/` `compat/` `mixin/` | 配置、指标、跨版本桥、Mixin |

## ServiceLoader

接口 `common/.../platform/services/IXxxHelper.java` → 访问 `Services.XXX` → 实现三端各一份 → 注册 `META-INF/services/<接口 FQN>`（三端都要）。漏注册 → 运行时 `NoSuchElementException`，编译不过滤。

## Mixin（仅 common）

- 命名：`@Unique` + `hassium$` 前缀；优先 `@Inject` cancellable，避免 `@Overwrite`
- 存储相关入口先查 `isStorageEnabled()`；网络相关先查网络开关 + 握手状态
- 登记：`common/src/main/resources/hassium.mixins.json`
- **三方兼容 mixin**：`hassium.modcompat.mixins.json`（`required:false` + 按外部 mod gating），代码在 `mixin/modcompat/` 与 `compat/mods/`；三端元数据各登记一份。详见 [`docs/mod-compat.md`](docs/mod-compat.md) §7/§7b

## 算光红线（钉死）

- **`LightNeighborhoodGate`（3×3 齐套后再 `lightChunk`）禁止拆除/旁路**。冒烟绿不能作为拆门依据；验收须含移动中的屋檐/洞口。详见 [`docs/client-chunk-light-flow.md`](docs/client-chunk-light-flow.md) §4 / §8.1
- **交付域 = `serverVD` ∪ OVD 带**（`DELIVER_VIEW_MARGIN_CHUNKS` 已删）：光环柱不得进客户端视野。改交付域前先读 `docs/handoff/handoff-2026-09-19-light-halo-selfdriven-delivery.md` §3.3
- **缺邻柱不得标「光照完成」**：`isLightCorrect` 只在 3×3 真齐全（含窗外邻柱）时置真；落盘判据 `promotedClean` 与重触发判据 `isNeighborhoodLightReady` 口径**刻意不同**（重触发只要求窗内邻柱 init 过）
- **光环 = 形状的切比雪夫膨胀**：用 `ChunkShapeCompat.containsDilated(VD, R)`，**不得**用 `contains(VD + R)`（`ChunkShapeDilationTest` 钉死）
- **客户端推送门** = 交付窗内 且「本会话 LIGHT 已完成」（`isLightCorrect ∪ lightRanThisSession`）；门**只挂** `ShadowLightCompute.pushReady`，**禁止侧查注入表**。锚点：`ShadowTrackingSession.isPushableToClient`
- **`drainAuthorityAcquires` 预算只约束「本轮新 acquire」且不得 `break`**：判据 `submitted >= budget`（**不是** `submitted + published`）；预算 = `chunk.maxChunksPerFrame × 4`；同组 3×3 要发完。锚点：`ShadowTrackingSession.authorityAcquireBudget`
- 锚点：`shadow/light/LightNeighborhoodGate.java`、`ShadowLightCompute.startLightBarrier` / `submitLightReuseOrGate`、`compat/ChunkShapeCompat.containsDilated`

## 并发串行化（钉死）

**影子端碰原版 POI / SectionStorage / 注册表状态一律「转影子主循环」执行，不用锁。** `ShadowPoiGate.callExclusive` = 调度器（`ShadowSeedServer.runOnMainLoopAndWait`），与主循环 tick 天然同线程 ⇒ 结构性串行；非影子端上下文直接执行。

- **为什么不用锁**：与 `chunkLock`/`flushLock` 共存产生锁序 → 实测两次 ABBA 死锁/停摆
- **新增碰 POI / SectionStorage 的路径必须走 `ShadowPoiGate.callExclusive`**；`withChunkLock` 放在调度**之内**
- **1.20.1 注册表重建用「窗口」不用锁**：`ShadowRegistryWindow`（窗口内新访问直接跳过返回 null）；断连保存在 `clearLevel` TAIL 关窗后主线程同步执行
- **旧转储症状指纹**：`Light timeout (10000ms)` 刷屏、`[STALL-DIAG] ... ready=0` 不降、`shadow save shutdown still running` → 查是否有人绕过调度直接拿锁
- 锚点：`compat/ShadowPoiGate`、`compat/ShadowRegistryWindow`、`ClientLifecycleHelper.saveShadowOnDisconnect`、`ShadowLightCompute.withChunkLock`

## 配置红线

键集真相源：`ConfigSchema`；审计表见 [`docs/config-audit.md`](docs/config-audit.md)。Fabric 双文件 `hassium-client.toml` / `hassium-server.toml`（物理客户端双文件合并；专用服仅 server）。Forge/NeoForge 物理客户端双注册（CLIENT+COMMON），专用服仅 COMMON。

| 项 | 默认 | 注意 |
|----|------|------|
| `storage.enabled` | **false** | 开启改存档格式（type 126）→ 提醒备份；**运行时仅专用服生效**，单人/LAN 保持原版格式；客户端影子端固定写 126 |
| `master.enabled` | true | 专用服网络通道总开关 |
| `master.enabledOnLan` | **false** | 集成服对**远程**玩家启用网络面；本机 memory 恒原版 |
| `master.maxChunksPerTick` | 5 | 每玩家每 tick 区块下发上限。**原版整柱钩子勿删**：1.20.1=`MixinServerPlayer`；1.21.1+=`MixinPlayerChunkSender`（删了等于既不抑制也不限速） |
| `master.entity*` | 见文档 | 实体域降帧 9 键（默认全开；vanilla 兼容、不要求握手）。键值与语义见 [`docs/config-audit.md`](docs/config-audit.md) |
| `chunk.enabled` | true | 区块核心总开关；关后全程原版路径 |
| `chunk.seedGenEnabled` | **false** | 双端同版本；**服务端开启会泄露世界种子** |
| `chunk.sectionDeltaEnabled` | true | 分段增量 |
| `chunk.lightStrip` | true | 服务端光照剥离 |
| `debug.*` | false | 热路径用 `DebugLogger` |

存档格式 type **126**（非 127）；元数据推送字段为 **chunkHash**（非 inhabitedTime）。客户端影子端世界 = `hassium_cache/<serverId>/world`；热度淘汰 `heat.idx` + 整文件删 `.mca`。

## 直连拓扑速记

```
Mod 客户端 ←──唯一 vanilla TCP（登录期握手 + Play 期自定义 payload）──→ Mod 服务端
   ├ 登录期：login_hello（1.20.1）/ 配置任务 hello + PreHandshakePayload（1.21.1+）
   ├ Play 期：dict/index → 聚合 PENDING → play_init 激活 → ACK → 聚合 ENABLED
   └ 区块/实体/业务 payload 全走 vanilla 通道（shadow_pull/section_delta）
```

- 握手时序/能力位/激活链详解见 [`docs/architecture.md`](docs/architecture.md) §握手与激活时序；分版本差异见 [`docs/version-segments.md`](docs/version-segments.md)
- 管线级全局包压缩**已退役**：原版压缩层不触碰；通道压缩 = 聚合包内部字典 ZSTD + 区块推送自有压缩
- `network/core/`、`network/dataplane/`、ResumeTicket 已裁剪，**不复活**；「影子端」= 区块核心后端引擎（`network/seedgen/`）
- `chunk.*` 键族 = 客户端区块域；`master.*` 键族 = 服务端传输面

## 运行时冒烟

分层、门禁与场景见 [`docs/runtime-smoke-test.md`](docs/runtime-smoke-test.md)。冒烟只有 `.ps1`（无 bash 版）。

```powershell
# pwsh
.\scripts\runtime-smoke-test.ps1 -Ver 1.20.1 -Loader fabric -Phase I -SessionId "1.20.1_fabric_I"
.\scripts\runtime-smoke-test-batch.ps1 -Phase I -Scenarios seedgen,dimension   # L2 三锚点
```

```bash
# Git Bash：多场景必须 -Command（-File 不拆逗号）
pwsh -Command '& "./scripts/runtime-smoke-test-batch.ps1" -Phase I -Scenarios seedgen,dimension'
pwsh -File ./scripts/runtime-smoke-test.ps1 -Ver 1.20.1 -Loader fabric -Phase I -SessionId "1.20.1_fabric_I"
```

**脚本会自己结束**并印 `=== RESULT: PASS|FAIL ===`（典型 4–12 min）。前台 `block_until_ms ≥ 600000`，后台 Await **本次 ps1 job** + pattern `=== RESULT:`；结果读 `build/smoke-test/results/result_<SessionId>.json`，日志 `logs/server|client_<id>.log`。不要另开端口会话，不要 `gradlew --stop`。

## Skills

Manifold / 七段 / `#if MC_VER` / `PacketId` / `Identifier` 改代码时自动采用 [`.omp/skills/hassium-manifold/SKILL.md`](.omp/skills/hassium-manifold/SKILL.md)。

## 文档

- [`docs/architecture.md`](docs/architecture.md) — 架构总览 + 握手时序
- [`docs/chunk-cache.md`](docs/chunk-cache.md) — 缓存推送（ShadowPull）与磁盘细节
- [`docs/client-chunk-light-flow.md`](docs/client-chunk-light-flow.md) — 客户端光照流（算光红线详解）
- [`docs/chunk-load-optimization.md`](docs/chunk-load-optimization.md) — 进服/重连加载路径
- [`docs/version-segments.md`](docs/version-segments.md) — 七段适配真相源
- [`docs/mod-compat.md`](docs/mod-compat.md) — 多 Mod 兼容
- [`docs/runtime-smoke-test.md`](docs/runtime-smoke-test.md) — 运行时冒烟（L0–L2、场景引擎）
- [`docs/config-audit.md`](docs/config-audit.md) — 配置项审计（含 `master.entity*` 全键）
- [`docs/client-chunk-flow-handover.md`](docs/client-chunk-flow-handover.md) — **进行中**：统一 Compare+Pull 交接
