# AGENTS.md

AI Agent 速查。多版本真相源见 [`docs/version-segments.md`](docs/version-segments.md)；直连拓扑架构见 [`docs/architecture.md`](docs/architecture.md)。[`docs/network-core-followups.md`](docs/network-core-followups.md) 已归档（网络核心/主控/UDP 数据面随直连拓扑裁剪）。

## 项目身份

Minecraft 1.20.1 / 1.21.1–1.21.11 多加载器模组（Fabric / Forge / NeoForge），ZSTD 优化存档与网络；七段适配单位见 version-segments。Forge 支持 **1.20.1 / 1.21.1 / 1.21.3–1.21.10**（1.21.2 上游无 Forge userdev；**1.21.11 起 sunset**，该段用 NeoForge）。**1.20.1 不构建 neoforge**：NeoForge 47.x 原生兼容 Forge mod，NeoForge 用户直接使用 forge 子项目的 Forge 产物。**回归直连拓扑**：客户端↔服务端唯一 vanilla TCP（登录期能力握手——1.20.1 走 `hassium:login_hello` login query，1.21.1+ 走配置阶段 `PreHandshakePayload`；Play 期 `play_init_s2c` 激活）；ZSTD 管线压缩/包聚合/区块推送/分段增量/SeedGen/ShadowPull 全走 vanilla 通道，网络核心（进程内网关）/UDP 数据面/续流迁移已裁剪。

## 关键构建命令

`gradle.properties` 已开 `org.gradle.daemon=true`（8G）。**编译 / 测试 / 打包走 daemon，不要加 `--no-daemon`。**

先认当前 Shell，用对应启动器，不要跨壳套一层：

| | **pwsh 7** | **Git Bash** |
|--|------------|--------------|
| Gradle | `.\gradlew.bat common:compileJava` | `./gradlew common:compileJava` |
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
- **禁止 `sleep N` / `Start-Sleep` 硬等**（含 `sleep 240; ls logs`）。超时数字（如 `-ClientTimeoutSec` 默认 300）是脚本内部上限，不是你该睡的秒数。等的是**你启动的那条命令退出**或输出里的结束标记。
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
- **读日志**：捕获 stdout 走管道、无真实控制台时，pwsh 会把输出编码锁成系统 ACP（中文 Windows = GBK），且首次输出后改不了。本机 profile 在任何输出前把 `[Console]::OutputEncoding` / `$OutputEncoding` 设为 UTF-8 无 BOM。**代理命令不要加 `-NoProfile`**，否则这段被跳过、中文乱码。`-NoProfile` 只留给必须隔离的内部子进程，不要套在自己的 Shell 命令外层。
`-Pmc_ver`：pwsh 必须 `"-Pmc_ver=1.20.1"`（引号）；Git Bash 写 `-Pmc_ver=1.20.1` 即可。
子工程构建产物按版本分目录（`<module>/build/<mc_ver 下划线化>/`，如 `common/build/1_21_11/`）：common 切 `-Pmc_ver` 互不覆盖、切回即 up-to-date；但 **fabric/forge/neoforge loader 子项目还会产出不分版本的泛型 `build/classes`**，跨版本切换可能残留旧变体类与版本目录并存（症状：loader 启动即崩，如 NeoForge 报 `must have exactly 1 public constructor, found 2`）。切版本后 loader 起不来时先删 `<loader>/build` 整目录再跑。根项目 `build/`（jdt-cp、smoke-test 日志）不分版本。

**IDE 编译输出目录（`<module>/bin/main`、`<module>/out/production`）同样会进运行时 classpath**：loom 组装 MOD_CLASSES 时会把存在的 Eclipse（`.classpath`→`bin/main`）与 IntelliJ（`out/production`）输出目录一并列为 mod 坐标（debug.log 可见 5 个坐标）。VS Code JDT / Eclipse 增量编译留下的**陈旧副本**会让 FML 加载到旧类——2026-08-23 实证：`neoforge/bin/main` 里 01:58 的旧 `HassiumNeoForge.class`（双构造器版）压过 04:54 新构建导致 runServer 必崩，且删 `build/` 无效。loader 起不来且 `build/` 已清时，删全部 `<module>/bin`、`<module>/out` 再跑。

## Minecraft 源码查询

查 MC 源码/映射/反编译时用 **minecraft-dev**（工具名 `minecraft_dev_*`）：
- `get_minecraft_source` / `decompile_minecraft_version` / `search_minecraft_code` / `search_indexed`（先 `index_minecraft_version`）— 按版本查反编译源码
- `find_mapping` — official / intermediary / yarn / mojmap 互查
- `analyze_mixin` / `validate_access_transformer` / `validate_access_widener` / `analyze_mod_jar` — 验证 Mixin / AT / AW / 第三方 mod

查本仓库代码优先用 **codegraph**（`codegraph_explore`），不要先 grep/Read 扫一遍。

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
| `network/` | 直连传输面：登录期握手 `network/handshake/`（LoginHandshake / LoginCaps / 双端激活）+ 客户端摄入管线（ClientChunkPipeline / ClientMetadataHandler）+ 服务端区块推送（ServerChunkPushManager）+ 聚合链（HassiumAggregationManager）；区块核心：`network/seedgen/` 影子端（`ShadowSeedServer` 等，= 区块核心后端引擎） |
| `cache/` | 客户端轻量设施（预算、生命周期、mesh 编译日志）；缓存存储与清理由影子端承担 |
| `config/` `metrics/` `compat/` `mixin/` | 配置、指标、跨版本桥、Mixin |

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
- **三方兼容 mixin 单独登记**：`hassium.modcompat.mixins.json`（`required:false` +
  `HassiumModCompatMixinPlugin` 按外部 mod 存在性 gating），代码在 `mixin/modcompat/` 与
  `compat/mods/`；三端元数据（`fabric.mod.json` / `neoforge.mods.toml` /
  `buildSrc/src/main/groovy/loom-forge.gradle`）各登记一份。删除该包 + 三行登记即可整体回退。
  详见 [`docs/mod-compat.md`](docs/mod-compat.md) §7/§7b。
- 优先 `@Inject` cancellable，避免 `@Overwrite`

## 算光红线（钉死）

- **`LightNeighborhoodGate`（3×3 齐套后再 `lightChunk`）禁止拆除/旁路**。非 REUSE 且未 promote 时必须入齐套门；缺邻时引擎按 Bedrock 挡天光 → 屋檐/洞口黑且可能无补光。
- **`startLightBarrier` 复用 phase-1 `nativeLightChunks` 只跑 LIGHT（2026-09-19 解除红线，现为生产语义）**：phase-1 已对**同一 ChunkPos** 跑过 INITIALIZE，而引擎层存储按 SectionPos 索引（不按 chunk 实例）→ 屏障内再跑一遍是重复劳动，不是正确性来源；`nativeLightChunks` 无条目（REUSE 柱 / phase-1 未完成）时自动回落到从零 INITIALIZE+LIGHT。开关：`ShadowLightCompute.REUSE_PHASE1_INITIALIZE`（默认 true；置 false 可整体退回旧语义做对照）。**解除依据**：① 原理——天光**播种在 LIGHT 步**（`propagateLightSources` 读 `ChunkSkyLightSources` 逐列播种：源以上 15、向下衰减），INITIALIZE 只做「装空层 / 启用该柱光照数据 / retainData 记账」，**不算任何亮度值**；屋檐黑属播种问题，与 INITIALIZE 是否复用无关。② 2026-09-19 用户多轮手动 run 未复现屋檐黑。**历史 2026-09-18 ⑤ 结论（复用 → 移动后屋檐柱全黑）作废**：该实验是在 `9595ee9d` 去门回归、且尚无 `7d335166` 读盘柱光源表修复的树上做的，归因混杂；本次**未做 A/B**。
- 2026-09-18 去门实验：冒烟可 PASS，但**游戏内屋檐变黑**；用户目视回退后恢复。**冒烟绿不能作为拆齐套门的依据**；验收须含移动中的屋檐/洞口。
- **交付域 = `serverVD` ∪ OVD 带（2026-09-19 用户拍板，S3b）**：`DELIVER_VIEW_MARGIN_CHUNKS=4` 余量已删。光环柱（计算域最外圈，3×3 天生不完整）**不得进入客户端视野**——余量会让它进。改交付域前先读 `handoff-2026-09-19-light-halo-selfdriven-delivery.md` §3.3。
- **缺邻柱不得标「光照完成」（2026-09-19 用户拍板，S5）**：`isLightCorrect`（= 原版 NBT `isLightOn`）只在 **3×3 真齐全**（8 邻全过 INITIALIZE，**含窗外邻柱**）时置真——形式复用原版 `ThreadedLevelLightEngine.lightChunk`「入口清假、引擎跑完才置真」。缺邻落盘 = 坏光被永久复用。**落盘判据（`promotedClean`，含 `outsideWindow==0`）与迟到重触发判据（`isNeighborhoodLightReady`）口径刻意不同（2026-09-19 任务 #37 修正，原「必须同口径」作废）**：重触发只要求**窗内**邻柱 init 过（窗外邻柱永不被 acquire → 同口径会让重触发永不成立 → S2c 补不回前沿柱）；churn 改由 `recheckPendingAuthoritative` 的**进展门**挡住——只有 `inWindowReadyNeighborCount` 比登记时**增加**才重算（计数单调、每柱有界），缺这道门会 `finishLight → recheck → re-light → finishLight` 自激。
- **光环是「形状的切比雪夫膨胀」，不是「形状环」**：`ChunkShapeCompat.containsDilated(VD, R)`，**不得**用 `contains(VD + R)`（每窗漏 8~20 个权威柱的邻柱；`ChunkShapeDilationTest` 钉死）。`MAX_LIGHT_HALO_RADIUS = AUTHORITY_MARGIN − 1`，故 `chunk.lightHaloRadius` 只有 0/1。
- **客户端推送 = 交付窗内 且「本会话 LIGHT 步已完成」（2026-09-19 用户拍板，S6；原版口径）**：原版判据 = `ChunkMap.prepareTickingChunk`（`ChunkMap.java:726`）`getChunkRangeFuture(holder, 1, s -> ChunkStatus.FULL)`——**该柱及 3×3 全部到 `FULL`** 才发；发包处（`:1256-1258`）**不看光**；`LIGHT`(`ChunkStatus.java:141`，range=1)→`SPAWN`(:149)→`FULL`(:156) ⟹ 到 FULL 蕴含本会话 LIGHT 已完成。Hassium 对应物 = `isLightCorrect ∪ lightRanThisSession`（前者覆盖 REUSE 柱——不进齐套门故 `promotedClean` 恒假；后者 = 本会话 LIGHT 步跑完过这个**事实**，**2026-09-19 任务 #36 起取代 `isColumnLightAuthoritative`**——`promotedClean` 要求 `outsideWindow==0`，飞行前沿柱结构性拿不到 → 永久洞）。门**只挂** `ShadowLightCompute.pushReady`（柱在手、传真值）；**禁止侧查注入表**（柱已摘表/未入表会假阴性）；B 族 `offerBuiltChunkPacket` 不加门（既有决策：不因光未对齐丢官方整柱包）。锚点：`ShadowTrackingSession.isPushableToClient(dim,pos,lightCorrect)`。
- **`drainAuthorityAcquires` 的预算只约束「本轮新 acquire」，且不得 `break`**（2026-09-19 用户拍板；实测教训）：判据必须是 `submitted >= budget`，**不得**写成 `submitted + published >= budget`——`published` 是**交付**计数，会让近处已 material 柱的交付吃光预算，把远处非 material 柱的 acquire 饿死（实测 `submitted=0` 占 181/206 轮、1341 柱一轮没 acquire → 邻柱永不到齐 → 齐套门降级）。**不得**用 `break` 提前退出列表（预算用尽后仍要走完，material 分支的交付/compare 照常）。预算值 = `chunk.maxChunksPerFrame × 4`（用户口径，取消原固定 64）；**同组（3×3 域）要发完**：组内超预算也把本组 acquire 完。修好后 R1 交付逐字回到基线 1529、降级 promote 174→60。锚点：`ShadowTrackingSession.authorityAcquireBudget` / `drainAuthorityAcquires`。
- 锚点：`shadow/light/LightNeighborhoodGate.java`、`ShadowLightCompute.startLightBarrier` / `submitLightNoNeighborhoodGate` / `enqueueInjectedForLight`、`ShadowTrackingSession.isInComputeDomain` / `isDeliverableToClient`、`compat/ChunkShapeCompat.containsDilated`。详见 `docs/client-chunk-light-flow.md` §4 / §8.1。

## 配置红线

键集真相源：`ConfigSchema`；审计表见 [`docs/config-audit.md`](docs/config-audit.md)。Fabric 双文件 `hassium-client.toml` / `hassium-server.toml`；**物理客户端双文件合并**（client 配客户端行为，server 供集成服务器/局域网；UI 只显示客户端键）；专用服仅 server。Forge/NeoForge 物理客户端双注册（CLIENT + COMMON），专用服仅 COMMON。

| 项 | 默认 | 注意 |
|----|------|------|
| `storage.enabled` | **false** | 默认关；开启后改存档格式（type 126）→ 提醒备份；**运行时仅专用服生效**（`isStorageEnabled` 门控 `RuntimeServerContext`），单人/局域网保持原版格式（读兼容）；客户端影子端（hassium_cache）固定写 126，不受本开关约束 |
| `master.enabled` | true | 专用服网络通道总开关（登录期握手/聚合的门） |
| `master.enabledOnLan` | **false** | 集成服已开局域网时对**远程**玩家启用网络面；本机 memory 恒原版；storage 仍仅专用服 |
| `master.maxChunksPerTick` | 5 | 每玩家每 tick 区块下发上限：Pull FULL/DELTA 完成 + **原版通道整柱**（专用服全员 / LAN 远程；满 tick ≈ 100/s；UNCHANGED 另额 32） |
|`master.entity*`|见文档|实体域降帧 **9 键**（分层更新总开关 1 `entityTieredUpdateEnabled`；**两张逗号分隔档位表**（近/中/远/边际，须非降序）`entityTierIntervals`=`3,4,6,10` 与物品流独立的 `entityItemTierIntervals`=`2,4,8,16`——掉落物/经验球原版 `updateInterval`=20 是空闲节拍、位置靠每 tick `hasImpulse`，共用生物表会被压平成 1 包/s 而闪现；热点分档 3 `entityDensityTierCounts`=`10,20,32,64`/`entityDensityTierFactors`=`1.5,2.0,3.0,4.0`（逗号分隔按 近/中/远/边缘，支持小数）+ `entityMaxThrottleFactor` 总上限默认 5；帧预算压力 1 `entityFrameBudgetPerPlayer` 默认 256；错峰推送 1 `entitySmoothPushEnabled`——同间隔实体按 UUID 错开发送时刻，3 刻总量不变、摊平齐发尖峰），默认全开；全关 = 行为等同未接入。**vanilla 兼容、不要求客户端握手**：只改复制节拍，门控 = 主服实例 + `master.enabled`/`enabledOnLan` 总闸 + entity* 配置（压力采样覆盖全部游戏态连接）；玩家实体与 ItemFrame 类豁免；**密度按实体自己所在 chunk 统计（局部）**，**压力每观察者一份（独立反压，取最近观察者那份作用于实体）**；只改复制（下发客户端）节拍，不碰服务端实体 tick/漏斗判定。实施与设计修正记录见 [`docs/handoff/entity-network-optimization-plan.md`](docs/handoff/entity-network-optimization-plan.md) §6/§8/§9|
| `chunk.enabled` | true | 区块核心总开关（影子端世界保存/算光/缓存；关后全程原版路径） |
| `chunk.seedGenEnabled` | **false** | 双端同版本；**服务端开启会泄露世界种子** |
| `chunk.sectionDeltaEnabled` | true | 分段增量 |
| `chunk.lightStrip` | true | 服务端光照剥离（影子端统一算光） |
| `debug.*` | false | 热路径用 `DebugLogger` |

存档格式 type **126**（非 127）；元数据推送字段为 **chunkHash**（非 inhabitedTime）。客户端影子端世界 = `hassium_cache/<serverId>/world`（原版存档结构 + type 126 + chunkHash 落盘，`MixinRegionFile` shadow 上下文 gate）；旧 HBT1 客户端磁盘缓存已裁剪（热度清理为影子端 `ShadowCacheEviction` + `ShadowRegionHeat`：`heat.idx` 按 region 文件计，`hassium_cache/<serverId>/heat.idx` per-server）。

## 直连拓扑速记

**握手链**（`network/handshake/`）——1.20.1 服务端在 `handleAcceptedLogin` 内 **LoginCompression 之后、GameProfile 之前**发 `hassium:login_hello` query（压缩就绪后发包，消除裸应答被压缩解码器误读的竞态，见 `483e1fb`）、`handleCustomQueryPacket` 解析应答；**1.21.1+ 服务端主导配置阶段协商**：NeoForge `RegisterConfigurationTasksEvent` / Forge `GatherLoginConfigurationTasksEvent` 注册配置任务（`hasChannel` / `isRemotePresent` 过滤非 Hassium 客户端 → 原版零干扰；任务执行在通道协商完成之后，无「发早被踢」竞态）→ 下发 `hassium:prehandshake_hello_s2c` → 客户端 handler 内同步应答 `PreHandshakePayload`（C2S）→ `PreHandshakeProtocol` 协商登记（旧客户端主动 announce 仍被接收，向前兼容）；Fabric 走官方 `ClientConfigurationConnectionEvents.START` + `ClientConfigurationNetworking.send` 主动声明（等价官方路径，无竞态）。能力位 `LoginCaps`（agg/delta/seed/light/pull/shadow_pull/pull_mode）按位与协商，结果入 `PlayerCompressionTracker`。`ServerPlayer <init>` TAIL 消费（`ServerHandshakeActivation.onPlayerInit`：压制原版区块窗口），tick 泵激活：dictionary_sync/index_sync → 聚合 PENDING（5s 无 ACK 降级直发）→ `play_init_s2c`（协商位 + SeedGen 种子）→ 客户端 index_sync 后回激活 ACK → 聚合 ENABLED。**管线级全局包压缩已退役（run9 退役波）**：原版压缩层全程不触碰，通道压缩 = 聚合包内部字典 ZSTD（EventLoop 阈值翻折防双重压缩）+ 区块推送自有压缩。

**区块核心**（客户端进程内区块域）——`network/seedgen/` 影子端（= 本域后端引擎：生成/算光/落盘/淘汰）+ `network/` 顶层摄入管线（ClientChunkPipeline / ClientMetadataHandler / ChunkHash 客户端侧）+ `cache/`（MainThreadBudget / 生命周期 / mesh 编译日志）；`chunk.*` 键族 = 本域配置族。

**服务端传输面**——区块推送（ServerChunkPushManager / SectionDelta 服务端）+ 聚合链（HassiumAggregationManager / ConnectionChannelAccess）；`master.*` 键族 = 本域配置族。

```
Mod 客户端 ←──唯一 vanilla TCP（登录期握手 + Play 期自定义 payload）──→ Mod 服务端
   ├ 登录期：login_hello（1.20.1）/ 配置任务 hello + PreHandshakePayload 应答（1.21.1+，Fabric 为 START 主动声明）
   ├ Play 期：dict/index → 聚合 PENDING → play_init 激活 → 客户端 aggregation_ready ACK → 聚合放行
   └ 区块/实体/业务自定义 payload 全走 vanilla 通道（shadow_pull/section_delta）
```

- 网络核心（`network/core/` 进程内网关）、UDP 数据面（`network/dataplane/`）、续流迁移（ResumeTicket）均已裁剪，不复活
- 「影子端」仅指区块核心后端引擎（`network/seedgen/`）

## 卖点（已实现，按类）

**高效压缩**——存储压缩（ZSTD 落盘 type 126）、通道压缩（聚合包内部字典 ZSTD + 区块推送自有压缩；管线级全局包压缩已退役——不触碰 vanilla 压缩层，无跨 mod 管线冲突面）；**网络优化**——平滑推送（每 tick 区块下发上限限速 + 全路径后台化）、**实体域降帧/错峰**（按观察者距离分挡 / 按 chunk 密度热点降档 / 按每玩家每 tick 实体包实测反压 / UUID 相位错峰摊平齐发；**vanilla 兼容、不要求客户端握手**，跟 master 总闸即可；物品流另用独立档位表以免被原版 20 刻空闲节拍压平；`master.entity*` 9 键，默认开，见 [`docs/handoff/entity-network-optimization-plan.md`](docs/handoff/entity-network-optimization-plan.md)）、登录期能力协商 + Play 激活链与按需 Compare+Pull（内部机制，用户无感）；**区块缓存**——影子端世界保存（进服区块由进程内影子服务端落盘原版存档 `hassium_cache/<serverId>/world`，断连保存重连复用）、容量/热度淘汰（heat.idx + 整文件删除 `.mca`）、分段增量、本地生成（SeedGen：门控开时影子 tracking 触发 vanilla worldgen，交付后 compare-pull；**服务端开启会泄露世界种子**）、`/hassiumc export` 世界导出；**光照优化**——Hassium 引擎（影子端统一算光 + 官方通道回传，客户端不计算；剥光由双端能力协商）、光照剥离。

## 运行时冒烟

分层、门禁与场景见 [`docs/runtime-smoke-test.md`](docs/runtime-smoke-test.md)。

| 层 | 载体 | 说明 |
|----|------|------|
| L0 | `common:test` | 无 MC 实例（如登录期握手编解码/协商单测） |
| L1 | classic 场景 | 全矩阵（12 版 × fabric/neoforge，按 versionProperties `builds_for` 过滤；1.20.1 无 neoforge 自动 SKIP） |
| L2 | 场景目录 | 最简三锚点（seedgen / dimension，migrate 已退役为 log-and-skip）：**1.20.1 forge、1.21.1 neoforge、1.21.11 fabric** |

冒烟只有 `.ps1`（依赖 Windows 网络/进程 cmdlet），没有 bash 版。

```powershell
# pwsh：当前壳里直接跑
.\scripts\runtime-smoke-test.ps1 -Ver 1.20.1 -Loader fabric -Phase I -SessionId "1.20.1_fabric_I"
.\scripts\runtime-smoke-test.ps1 -Ver 1.20.1 -Loader fabric -Phase I -SessionId "1.20.1_fabric_I_seedgen" -Scenario seedgen

# 快速冒烟（L2 三锚点：1.20.1 forge / 1.21.1 neoforge / 1.21.11 fabric）
.\scripts\runtime-smoke-test-batch.ps1 -Phase I -Scenarios seedgen,dimension
```

```bash
# Git Bash：调 pwsh 跑同一脚本（不要 -NoProfile，不要 | tail）
pwsh -File ./scripts/runtime-smoke-test.ps1 -Ver 1.20.1 -Loader fabric -Phase I -SessionId "1.20.1_fabric_I"
pwsh -File ./scripts/runtime-smoke-test.ps1 -Ver 1.20.1 -Loader fabric -Phase I -SessionId "1.20.1_fabric_I_seedgen" -Scenario seedgen

# 快速冒烟（L2 三锚点）
pwsh -File ./scripts/runtime-smoke-test-batch.ps1 -Phase I -Scenarios seedgen,dimension
```

**这条 ps1 会自己结束**（起服 → 等 `Done!` → 起客户端 → 两轮 → 写 JSON → 印 `=== RESULT: PASS|FAIL ===` → 退出码 0/2/3）。典型 4–12 min，最坏约 `ServerReadyTimeoutSec`(180) + `ClientTimeoutSec`(300) + 收尾。

| 做 | 不要 |
|----|------|
| 前台：`block_until_ms` 至少 600000（10 min），不够再 Await 同一 job | `sleep 240` 然后 `ls build/smoke-test/logs` |
| 后台：Await **本次 ps1 那个 job**，pattern `=== RESULT:` | 把 300（客户端超时上限）当成整场时长 |
| 结束后读 `build/smoke-test/results/result_<SessionId>.json` | `| tail -15`（要等 EOF 才吐行，还丢掉 [1/9]…[9/9]） |
| 日志按 SessionId：`logs/server_<id>.log`、`client_<id>.log` | `ls -t logs \| head` 猜最新文件 |

脚本已给 `runServer`/`runClient` 加 `--no-daemon`。不要另开一套会话抢 25565/25566，也不要 `gradlew --stop`。场景文件：`common/src/main/resources/hassium/smoke/scenario/<name>.scenario`，客户端由 `ScenarioEngine` 执行。

## Skills

Manifold / 七段 / `#if MC_VER` / `PacketId` / `Identifier` 改代码时自动采用项目 skill：
[`.omp/skills/hassium-manifold/SKILL.md`](.omp/skills/hassium-manifold/SKILL.md)。

## 文档

- [`docs/architecture.md`](docs/architecture.md) — 架构总览
- [`docs/chunk-cache.md`](docs/chunk-cache.md) — 缓存推送（ShadowPull 统一 Compare+Pull）与磁盘细节（§11 磁盘 NBT/分段增量、§12 导出）
- [`docs/client-chunk-light-flow.md`](docs/client-chunk-light-flow.md) — 客户端光照流
- [`docs/chunk-load-optimization.md`](docs/chunk-load-optimization.md) — 进服/重连加载路径与速率锚点
- [`docs/version-segments.md`](docs/version-segments.md) — 七段适配真相源
- [`docs/mod-compat.md`](docs/mod-compat.md) — 多 Mod 兼容
- [`docs/runtime-smoke-test.md`](docs/runtime-smoke-test.md) — 运行时冒烟（L0–L2、PROBE、场景引擎）
- [`docs/config-audit.md`](docs/config-audit.md) — 配置项审计
- [`docs/network-core-followups.md`](docs/network-core-followups.md) — 网络核心收尾核销（**已归档**：直连拓扑下仅存档参考）
- [`docs/client-chunk-flow-handover.md`](docs/client-chunk-flow-handover.md) — **进行中**：客户端区块数据流对齐 §6 统一 Compare+Pull 的交接（过渡链路清单 / 开发计划 / 清理清单）
