# 运行时冒烟测试（Runtime Smoke Test）

Hassium 跨版本（1.20.1–1.21.11）× 多加载器（fabric / neoforge）的实跑验证流程。在 dev 环境同时启动服务端和客户端，自动连服 → 采集统计 → 断开 → 重连 → 再采集，用于发现编译通过但运行时才暴露的回归（路径错误、Mixin 失效、跨版本 API 漂移、缓存未清理等）。

## 覆盖分层

冒烟覆盖按成本与范围分四层：

| 层 | 载体 | 范围 | 说明 |
|----|------|------|------|
| **L0** | `common:test` | 随单元测试跑 | JUnit 代码级冒烟，无 MC 实例。代表：`GatewaySmokeTest`（真实 TCP 双端握手 / 续流票据 resume，见下文「网关双主控迁移冒烟」） |
| **L1** | classic 场景 | 全矩阵（12 版 × fabric/neoforge） | 两轮连服 VD 切换（VD=20 → 断开 → VD=10），核心缓存 / OVD / 光照 / 网关全链路验证 |
| **L2** | 场景目录 | 锚点集：1.20.1 fabric+neoforge、1.21.1 neoforge、1.21.11 neoforge | 数据驱动场景（seedgen / dimension / migrate 等），只在锚点版本×加载器组合上跑，控制总时长 |
| **L3** | 人工专项 | 按需 | AI 辅助游戏内功能测试（minecraft-mod-mcp 桥），不进自动 PASS 门禁，见 [`ai-functional-test.md`](ai-functional-test.md) |

## 概述

- **测试矩阵**：12 个 MC 版本 × 2 个加载器（fabric / neoforge）= 24 个默认会话；额外可显式 `-Loaders fabric,forge,neoforge` 跑 Forge，**Forge 仅 1.20.1 / 1.21.1 / 1.21.3–1.21.10 有 `builds_for`**（`forge` 子项目独立、经 `loom-forge.gradle`），其它版本会自动 SKIP。批量脚本读取每版本 `versionProperties/<ver>.properties` 的 `builds_for` 决定是否跳过该 loader，未列入 `builds_for` 的不会强行起 `:forge:runServer`（否则 `settings.gradle` 未 include forge 子项目会直接失败）。
- **执行方式**：PowerShell 脚本驱动 Gradle `runServer` / `runClient`，注入 `-Dhassium.smokeTest=true` 等 JVM 属性
- **场景驱动**：客户端行为由数据驱动的场景引擎执行（`hassium.smokeScenario=<name>` 加载 `.scenario` 文件）；未指定时默认 classic
- **结构化探针**：每轮统计以 PROBE JSON 落盘（`build/smoke-test/probe/<SessionId>/roundN.json`），harness 门禁优先读探针、缺失回退日志正则
- **dev 专用**：测试代码（`ClientSmokeTest` / `ServerSmokeTest` / 场景引擎）只在 dev 环境启用，正常生产 jar 不受影响
- **输出位置**：`build/smoke-test/`（已在 `.gitignore` 范围内）

## 前置条件

1. **JDK 21+**：Hassium 全版本编译需要
2. **Gradle wrapper**：使用项目自带的 `gradlew.bat`，无需本机全局安装
3. **Windows + PowerShell**：脚本依赖 `Get-NetTCPConnection`、`Start-Process` 等 cmdlet
4. **25565 端口可用**：脚本会尝试释放被占用端口，但建议预先关闭其它 MC 服务端
5. **首次运行前**：跑过一次 `./gradlew common:decompile`，确保 mappings 已下载（走 daemon；等 gradlew 退出即可，见 `AGENTS.md`）

## 快速开始

代理等待：这条 ps1 **会退出**。等它印 `=== RESULT:`（或读 `build/smoke-test/results/result_<SessionId>.json`），不要 `sleep 240` 再 `ls logs`，也不要 `| tail`。240 是 `-ClientTimeoutSec` 内部上限，不是会话时长。

```powershell
# 单次会话（1.20.1 fabric，classic 场景）
.\scripts\runtime-smoke-test.ps1 -Ver 1.20.1 -Loader fabric -Phase I -SessionId "1.20.1_fabric_I"

# 单次会话，指定场景（seedgen / dimension）
.\scripts\runtime-smoke-test.ps1 -Ver 1.20.1 -Loader fabric -Phase I -SessionId "1.20.1_fabric_I_seedgen" -Scenario seedgen

# 全量初始轮（12 版 × 2 加载器 classic；约 4–6 小时）
.\scripts\runtime-smoke-test-batch.ps1 -Phase I

# 并行跑全量初始轮（fabric+neoforge 同时，节省约一半时间）
.\scripts\runtime-smoke-test-batch.ps1 -Phase I -Parallel

# 批量多场景：classic 走全矩阵，seedgen/dimension 只在锚点集跑
.\scripts\runtime-smoke-test-batch.ps1 -Phase I -Scenarios classic,seedgen,dimension

# 仅指定版本×fabric
.\scripts\runtime-smoke-test-batch.ps1 -Phase I -Versions @("1.20.1","1.21.11") -Loaders fabric

# 回归轮（默认对全部版本再跑一遍；可结合初始轮结果挑选）
.\scripts\runtime-smoke-test-batch.ps1 -Phase R -Versions @("1.20.1","1.21.6")
```

### 单会话参数（`scripts/runtime-smoke-test.ps1`）

| 参数 | 必填 | 默认 | 说明 |
|------|------|------|------|
| `-Ver` | 是 | — | MC 版本，如 `1.20.1` |
| `-Loader` | 是 | — | `fabric` 或 `neoforge`（`forge` 按 `builds_for` 支持范围可用） |
| `-Phase` | 是 | — | `I`（初始轮）或 `R`（回归轮） |
| `-SessionId` | 是 | — | 会话 ID，用于日志文件命名，如 `1.20.1_fabric_I` |
| `-Scenario` | 否 | `classic` | 场景名，加载 `common/src/main/resources/hassium/smoke/scenario/<name>.scenario`；默认 classic 不注入 `-Dhassium.smokeScenario`（既有路径零行为变化）。`seedgen`/`dimension` 强制 `-CleanWorld` |
| `-CleanWorld` | 否 | false | 删除服务端存档；batch 按 loader 策略决定（见下） |
| `-PregenOnly` | 否 | false | 只跑服务端预生成（`SmokePhases=pregen`，49×49 区域），产物存 `build/smoke-test/pregen-world/<Loader>-<Ver>/` 供后续 CleanWorld 恢复；不启客户端 |
| `-SmokeHost` | 否 | 空 | 客户端连服完整地址（如 `127.0.0.1:25566`）；指定后优先于 `-ServerPort` |
| `-ServerPort` | 否 | `25565` | 服务端监听端口（并行模式由 batch 脚本分配：fabric=BasePort, neoforge=BasePort+1） |
| `-DelayMs` | 否 | `10000` | 进世界后等待毫秒（classic ROUND1 窗口=DelayMs×2=20s，ROUND2=max(3000, DelayMs)） |
| `-ReconnectDelayMs` | 否 | `3000` | 第一轮断开后到重连的毫秒 |
| `-JoinTimeoutMs` | 否 | `0`（→ Java 侧 120s） | 客户端进服等待超时；调大 `-DelayMs` 时须同步调大 |
| `-MoveSeconds` | 否 | `0` | 进服后飞行移动秒数（先爬升再平飞；0=不动），驱动「进服即移动」补给顺序，非标准默认行为 |
| `-Vd1` / `-Vd2` | 否 | `20` / `10` | 服务端两轮视距 |
| `-ServerReadyTimeoutSec` | 否 | `160` | 服务端 `Done!` 出现超时 |
| `-ClientTimeoutSec` | 否 | `240` | 客户端退出超时 |
| `-SmokePhases` | 否 | `classic` | Java 侧阶段：`classic`（经典两轮）/ `pregen`（预生成，经 `-PregenOnly` 使用） |
| `-ManualLogout` | 否 | false | ROUND1 断开改走真实手动登出路径（`Minecraft.disconnect(Screen[,Z])` / `clearLevel`），验证手动登出光照/方块落盘 |
| `-DryRun` 类遗留参数 | 已删除 | — | UdpFailover / Nginx 相关参数已随退役链路整体删除（见文末「退役说明」） |

### 批量参数（`scripts/runtime-smoke-test-batch.ps1`）

| 参数 | 必填 | 默认 | 说明 |
|------|------|------|------|
| `-Phase` | 是 | — | `I` 或 `R` |
| `-Scenarios` | 否 | `classic` | 场景列表（逗号分隔）。`classic` 走全矩阵（`-Versions` × `-Loaders`）；非 classic 场景只跑锚点集（硬编码：1.20.1 fabric+neoforge、1.21.1 neoforge、1.21.11 neoforge，再与 `-Versions`/`-Loaders`/`builds_for` 取交集）。非 classic 会话 sessionId 追加 `_<scenario>` 后缀避免 result JSON 冲突 |
| `-Versions` | 否 | 全部 12 版 | 指定版本子集 |
| `-Loaders` | 否 | `fabric,neoforge` | 加载器子集 |
| `-MaxRetries` | 否 | `3` | 单会话失败重试次数上限 |
| `-Parallel` | 否 | false | 同版本 fabric+neoforge 并行跑（Start-Process） |
| `-BasePort` | 否 | `25565` | 起始端口；fabric 用此端口，neoforge 自动 +1（仅并行模式生效） |

**batch `CleanWorld` 策略**（按 loader 独立跟踪，因为 fabric/neoforge 各有 `run/server`）：

| 场景 | 是否清理 |
|------|----------|
| 该 loader 的第一个版本 | 清理 |
| 版本变化（升或降） | 清理（worldgen 跨版本可能变化——1.21.9 地形塑造重构、1.21.4 pale garden 等；复用旧版本 terrain 会让新版本 seedgen 影子端系统性 mismatch，R2 命中率崩塌。T8 1.21.11 实测 17.8%） |
| 同版本 | 不清理（复用存档，加快启动） |
| 同会话失败重试 | 强制清理 |
| `seedgen` / `dimension` 场景 | 强制清理（单会话脚本内置，干净世界前置） |

## 测试流程

```
┌─────────────────────────────────────────────────────────────────────────┐
│  1. 清理 <loader>/run/client/hassium_cache + crash-reports             │
│  2. 写 <loader>/run/server/server.properties (VD=20, online-mode=false) │
│  3. (CleanWorld) 删 <loader>/run/server/world*                          │
│     （有预生成存档时优先从 build/smoke-test/pregen-world/ 恢复）          │
│  4. 启动 :<loader>:runServer  →  ServerSmokeTest 设置 VD=20            │
│     （hassium.serverSmokeScenario 非空时玩家 join 即自动 OP）            │
│  5. 等待 server log "Done ("                                            │
│  6. 启动 :<loader>:runClient  →  场景引擎按 .scenario 步骤驱动           │
│     ┌─────────────────────── classic 场景 ──────────────────────────┐   │
│     │  join WAIT_JOIN_1  →  等到玩家进服                             │   │
│     │      ↓ fly + wait(delayMs*2)                                   │   │
│     │  dump ROUND1（写 probe round1.json + 统计 marker）              │   │
│     │      ↓ disconnect                                              │   │
│     │      ↓ reconnect (reconnectDelayMs)；服务端切 VD=10 + 注入石墙   │   │
│     │  join WAIT_JOIN_2                                              │   │
│     │      ↓ fly + wait(max(3000,delayMs))                           │   │
│     │  dump ROUND2（写 probe round2.json）                            │   │
│     │  exit → System.exit(0 / 2)                                     │   │
│     └────────────────────────────────────────────────────────────────┘   │
│  7. 解析 probe roundN.json（缺失回退 client log 中文正则）：              │
│     提取统计、PASS/FAIL/GATEWAY 标记、评估门禁                            │
│  8. 写 result_${SessionId}.json + stats/*.txt(+*_probe.json)             │
│  9. 杀服务端 + 残留 java；（dimension 场景）post-exit 磁盘门禁            │
└─────────────────────────────────────────────────────────────────────────┘
```

**为什么进服后才开始计时？** 部分版本进服很慢（需要区块替换、服务端处理）；如果 player 对象一创建就开始计时，统计时区块还没加载完，`hits + misses == 0`。改为等玩家位置被服务端确认后才进入等待窗口。

**R2 方块变化注入（2.0.0 起）**：玩家离线窗口内，`ServerSmokeTest` 在世界出生点上方放置一堵 4 格高石墙（x∈[-160,160)、y∈[64,68)、z∈[2,4)，横跨 VD10 全宽）。R2 客户端从区块核心缓存读回该区域时 chunkHash 不一致 → 请求 section hash → 服务端回 `SectionDeltaS2CPacket` → 客户端 merge 后变化 section 缺光字段 → 触发增量分段光照重算路径（`[LIGHT-SEG]`）。无变化时该路径不会走，因此注入是 ROUND2 冒烟触发 section-delta / 分段光照的前置条件（`ServerSmokeTest.injectR2BlockChange`）。

## PROBE JSON v1（结构化探针）

`SmokeProbeWriter` 在每轮统计输出时把 metrics 原值 / 网关状态 / 计数器 / 影子端磁盘状态写成 JSON：

- **路径**：`build/smoke-test/probe/<SessionId>/roundN.json`（由 JVM 属性 `hassium.smokeTest.probeDir` 注入；属性未设置时整体 no-op）
- **消费方**：单会话 harness 解析结果时**优先读 probe**，缺失（旧客户端 / 写入失败）回退中文日志正则——两条路产出同一批 result 字段
- **契约**：JSON v1 只增不改名不删；任何内部失败只记 warn，绝不影响状态机判定/退出码

顶层 schema：

```jsonc
{
  "round": 1,                       // 轮次（dump 的 round 参数）
  "timestampMs": 1755000000000,
  "joined": true,                   // player/level 非空；未进服轮次为 false
  "dimension": "minecraft:overworld", // 未进服为 null（跨版本取法兼容 1.21.11 identifier()）
  "playerPos": [x, y, z],           // 定点 6 位小数；未进服为 null
  "stats": { /* NetworkStats metrics 原值快照，字段名同 HassiumMetricsImpl getter */ },
  "gateway": {
    "state": "ACTIVE",              // NetworkCoreState；读取异常降级 "ERROR"
    "resumeAccepted": true,
    "c2s": 123,                     // c2sRoutedCount
    "s2c": 456                      // s2cDispatchedCount
  },
  "counters": {
    "ovdLoaded": 120,               // OVD 已加载（ViewDistanceExtensionService.loadedRenderOnly）
    "ovdPendingMiss": 3,            // OVD 缺失待补
    "ovdShadowServed": 45,          // OVD 影子复用服务数
    "sectionDeltaRequestsSent": 2,
    "sectionDeltaApplied": 2,       // = stats.sectionDeltaChunksReceived
    "lightSegRecalc": 1,            // [LIGHT-SEG] 增量分段光重算（= lightCacheMissCount）
    "locallyGenerated": 0           // SeedGen 本地生成块数
  },
  "disk": {
    "shadowRegionExists": true,     // 影子端 hassium_cache/<serverId>/world/region 存在
    "regionFileCount": 12,          // region 目录常规文件数
    "cacheDir": "...\\world\\region",
    "dimensions": {                 // T7：影子端三维度 region 落盘数；目录不存在为 -1
      "overworld":  { "regionFileCount": 12 },   // world/region
      "nether":     { "regionFileCount": 4  },   // world/DIM-1/region
      "end":        { "regionFileCount": 3  }    // world/DIM1/region
    }
  }
}
```

> 注意：dimension 场景的 dump 时刻影子世界尚未 flush（断连/退出才落盘），probe 内 `disk.dimensions` 全为 -1 属预期语义；真实磁盘校验由 harness post-exit 磁盘门禁完成（见下）。

## 场景引擎

客户端行为不再硬编码在状态机里，而是由数据驱动的场景引擎执行：

- **选择**：JVM 属性 `hassium.smokeScenario=<name>` → 从 classpath 加载 `/hassium/smoke/scenario/<name>.scenario`；**未设置时** `hassium.smokeTest.migrateTo` 非空 → `migrate`，否则 `classic`（与旧状态机默认分支一致）。`ClientSmokeTest` 已收薄为门面（init / marker 输出 / 反射工具），步骤执行全部在 `ScenarioEngine`
- **变量注入**：场景文件内的 `${delayMs}` / `${joinTimeoutMs}` 等占位符由 JVM 属性求值（含派生值 `round1WaitMs=delayMs*2`、`round2WaitMs=max(3000,delayMs)`、`dimWaitMs=max(20000,delayMs*2)`、`endWaitMs=max(30000,delayMs*3)`）

### 场景原语

| 原语 | 关键参数 | 行为 |
|------|----------|------|
| `join` | `label`、`since=start\|disconnect`、`timeoutMs` | 等待进服（玩家位置被服务端确认） |
| `wait` | `ms` / `until=migrated`、`settleMs`、`timeoutMs` | 定长等待或等迁移完成后再等 settle 让 S2C 流入 |
| `fly` | `seconds`、`tag` | 注入飞行移动（先爬升 2s 再平飞），驱动区块补给顺序 |
| `command` | `mode=migrate`、`immediate`、`posBefore` | 发送命令；migrate 模式 `immediate=true` 走 `NetworkCore.migrateToImmediate` 直调（真实断线窗口），false 走 `/hassium migrate` 真实命令（无缝） |
| `dimension` | `to=nether\|end\|overworld` | `/execute in <dim> run tp @s ~ ~ ~` 切维（需 OP，服务端场景注入自动 op） |
| `disconnect` | — | 主动断开（`manualLogout=true` 时走真实手动登出路径） |
| `reconnect` | `delayMs` | 延迟后反射重连（跨版本 `startConnecting` 签名适配） |
| `dump` | `label`、`round`、`gate=true\|false` | 输出一轮统计 + 写 probe roundN.json；`gate=false` 跳过 validateStats 的 ROUND2 缓存命中口径（切维后全新区块无缓存命中属预期） |
| `assertProbe` | `key`、`op=gt\|ge\|lt\|eq`、`value` / `vs=<key>` | 对 probe 字段做活体断言，失败即退出码 2 |
| `exit` | `rounds` | 结束并退出；`rounds=1` 表示单轮场景（跳过 classic 两轮判定口径） |

### 内置场景

| 场景文件 | 内容 | 门禁 |
|----------|------|------|
| `classic.scenario` | 两轮连服 VD 切换（join → R1 dump → disconnect → reconnect → R2 dump → exit），行为不变迁移自旧状态机 | classic 四门禁 + validateStats |
| `migrate.scenario` | 网关双主控迁移演练单轮模式（R1 → command migrate → fly → wait until=migrated → R2 dump）；`migrateImmediate` 选直调 API 或真实命令 | 迁移完成断言 + R2 统计 |
| `seedgen.scenario` | SeedGen 本地生成单轮冒烟（join → R1 dump → exit rounds=1）。需 profile=`seedgen` 双端 `chunk.seedGenEnabled=true` + 干净世界 | `counters.locallyGenerated > 0`；`staleFullChunkRequestCount < clientAppliedChunkCount`（SeedRef 回退全量有界） |
| `dimension.scenario` | 四轮切维冒烟：主世界 → 下界 → 末地 → 回主世界（单连接不断开）；中段轮 `gate=false`；整体 PASS 只看 R1 统计 + 各轮 assertProbe | 每轮 `joined` 且 `dimension` 正确；harness 另加 post-exit 三维度磁盘门禁 |

### 配置档案（profiles）

存在 `scripts/smoke/profiles/<name>.profile.properties` 时，单会话脚本按键值对 patch 双端 hassium toml（客户端 `run/client/config/hassium/hassium-client.toml`、服务端 `run/server/config/hassium/hassium-server.toml`）。行式 `key=value`、`#` 注释；value 须为合法 TOML 字面量（字符串自带引号）。profile 文件不存在时整体 no-op。

例：seedgen 场景需要 `seedgen` 档案提供双端 `chunk.seedGenEnabled=true`；dimension 场景无 toml 改动需求，无档案文件。

## 门禁与会话判定

### classic 四门禁（仅 classic 场景评估，作用于 ROUND2 探针）

| 门禁 | 探针条件 | 失败标记 |
|------|----------|----------|
| G1 | `counters.ovdLoaded > 0` | `ovdLoaded_not_positive` |
| G2 | `sectionDeltaApplied > 0` 或 `lightSegRecalc > 0` | `section_delta_or_light_recalc_absent` |
| G3 | `disk.shadowRegionExists` 且 `disk.regionFileCount > 0` | `shadow_region_missing` |
| G4 | `counters.locallyGenerated == 0`（影子端全量命中，不允许本地补生成） | `locally_generated_nonzero` |

任一不满足 → `Round2Pass=false`，失败名单记入 result JSON `ProbeGateFailures`。probe 缺失或对应字段缺失（旧客户端）时跳过该门禁保持兼容。

**非 classic 场景四条门禁整体跳过**（`ProbeGateScenarioGated=true`，`ProbeGateFailures` 恒空）：其探针语义不同（如 dimension 切维轮无 ovd/影子区），套用 classic 口径会误判 FAIL。

### 非 classic 场景会话判定

```
PASS ⇔ HasPass && !HasFail && exit==0
       && 出现过的 GATEWAY_CLIENT marker 全部 state=ACTIVE（零 marker 视为网络核心路径缺失 → FAIL；
          不要求两轮齐备、不查 c2s>0——场景引擎提前退出时最后一轮可能无 R2 dump）
       && （仅 dimension）post-exit 磁盘门禁通过
```

**dimension 磁盘门禁（post-exit）**：维度切换不断连，shadow 世界只在断连/退出时落盘。客户端正常退出后，按 probe 记录的 `disk.cacheDir`（兜底 `hassium_cache` 下最近修改目录）定位影子世界根，校验 `world/region`（主世界）、`world/DIM-1/region`（下界）、`world/DIM1/region`（末地）三处均存在且 ≥1 个 `.mca`；失败名单记入 `DimensionGateFailures`。遗留：主世界同坐标未被覆写的深度比对本轮不做。

### classic 场景会话判定

```
PASS ⇔ HasPass && exit==0 && 网关门禁 && Round2Pass（含四门禁）
```

## 退出码

| 退出码 | 含义 |
|--------|------|
| `0` | PASS：门禁全过且客户端正常退出 |
| `2` | FAIL：统计/探针校验失败、assertProbe 失败、客户端崩溃或非 0 退出 |
| `3` | 进服超时 / server_not_ready：服务端 160s 内未出现 `Done!` 或客户端 `joinTimeoutMs` 内未进服 |

## 统计字段说明

每个 ROUND 的统计来自 `/hassiumc stats` 命令的输出（`HassiumCommandHandler.getClientStatsMessage()`；PROBE JSON `stats` 为同一批计数器的原值快照），主要字段：

| 字段 | 含义 |
|------|------|
| **带宽压缩(Zlib→ZSTD)** | 压缩节省百分比 = (原版Zlib - Hassium 实际) / 原版Zlib × 100%；括号内为 Hassium ZSTD 线缆实测字节与原版 Zlib 管线估算字节 |
| **压缩比** | Zlib 估算 ÷ ZSTD 实测，如 `1.76:1` 表示 ZSTD 比 Zlib 少 43% |
| **区块缓存** | `缓存命中 = (全命中 + 部分命中 − 增量) / 应用`，按内容等价值字节。全命中 = 本地缓存 contentHash 整柱复用；部分命中 = 缓存柱作基线的分段增量；增量 = `FULL` 整段 / `BLOCKS` 按格。**SeedGen 本地生成不算缓存命中**（只在「区块加载 / 本地」）。应用 = 全量请求 + 全命中 + 部分命中（OVD 不计入） |
| **区块加载** | 总数（新增数/新增字节 + 过期数/过期字节 + 本地生成数/字节） |
| **超视渲染（OVD）** | `已加载 / 缺失 / 影子复用 / 环带服务`；ROUND2 应非 0。OVD 环带不参与缓存命中率评估 |
| **光照缓存** | 命中率 = (直连命中 + 影子复用) / (命中 + 本地重算)。影子端本会话重算光（远程全量注入 / 分片增量 / LightDelta / SeedGen 本地生成 / 光脏缓存命中）都计为本地重算；ROUND1 重算为主（命中 0% 正常），ROUND2 有缓存复用且存在分片增量/全量请求时会低于 100% |
| **流量节省** | `服务端实际推送 / 无MOD应收 × 100%`（越小越省；已节省 = 100% − 该值）。无MOD应收 = 数据包（含分片全量等价）+ 本地重算 + 客户端缓存 + 光照；**不含 OVD**（无 MOD 时服务端本来也不推 serverVD 之外区块） |

> **注意：**"原版Zlib" 是 `VanillaZlibEstimator.estimate()` 对同负载模拟 `Deflater(level=6)` + 阈值 256 帧格式的输出估算值，并非真实原版管线实测。`estimate(int)`（无实际字节时使用）基于 MC 区块 NBT 典型压缩率 25–35% 校准。详见 `VanillaZlibEstimator` 和 `VanillaZlibVsZstdBenchmarkTest`。

**典型健康指标**（1.20.1 fabric ROUND1 VD=20 参考）：

- 带宽压缩(Zlib→ZSTD)：~16%（当前 7.9 MB，原版Zlib 9.5 MB），压缩比 1.19:1
- 区块加载：~1570 新增 / ~25 MB，缓存命中 0%（首次连服无缓存，预期）
- ROUND1：光照缓存命中率 0%（影子端全量重算，`重算` 计数应接近已提交的注入区块数，正常）
- ROUND2（VD=10，已有缓存）：区块缓存应按缓存复用口径明显命中（应用 = 全命中 + 全量请求 + 部分命中，不含 SeedGen）；光照缓存命中率通常 70–95%（影子复用为主 + 分片增量/全量请求带来重算）；流量节省 = 实际/无MOD 通常在 15–30%（即已节省 70–85%）

## 日志位置

```
build/smoke-test/
├── logs/
│   ├── server_<SessionId>.log           # 服务端 stdout
│   ├── server_<SessionId>_err.log       # 服务端 stderr
│   ├── client_<SessionId>.log           # 客户端 stdout（含 ROUND1/2 统计原文与场景 marker）
│   └── client_<SessionId>_err.log       # 客户端 stderr
├── probe/<SessionId>/roundN.json        # PROBE JSON v1（SmokeProbeWriter 落盘）
├── stats/
│   ├── <SessionId>_round1_VD20.txt      # 提取后的 ROUND1 统计（VD=20 场景）
│   ├── <SessionId>_round2_VD10.txt      # 提取后的 ROUND2 统计（VD=10 + OVD 场景）
│   ├── <SessionId>_roundN_probe.json    # probe 原值副本（便于离线分析）
│   └── <SessionId>_server.txt           # 服务端视距切换日志
├── results/
│   └── result_<SessionId>.json          # 单会话结构化结果
├── batch-results-<Phase>.csv            # 批量汇总（含 ProbeR1/ProbeR2 观测列）
└── failures-<Phase>.log                 # 失败会话清单（仅 batch 模式）
```

`result_<SessionId>.json` 字段：

```json
{
    "SessionId": "1.20.1_fabric_I_seedgen",
    "Ver": "1.20.1",
    "Loader": "fabric",
    "Phase": "I",
    "Scenario": "seedgen",
    "Result": "PASS",
    "ClientExitCode": 0,
    "Round1Stats": true,
    "Round1Pass": true,
    "Round2Stats": true,
    "Round2Pass": true,
    "ProbeGateScenarioGated": true,
    "ProbeGateFailures": [],
    "DimensionGateFailures": [],
    "Probe": {
        "Round1": { "joined": true, "gateway": {}, "counters": {}, "disk": {} },
        "Round2": null
    },
    "ServerSwitched": true,
    "HasPass": true,
    "HasFail": false,
    "StatsFiles": ["..."]
}
```

新增字段说明：

| 字段 | 含义 |
|------|------|
| `Scenario` | 本会话场景名（classic 时也记录） |
| `ProbeGateScenarioGated` | 非 classic 场景四条 P0 门禁整体跳过时为 `true` |
| `ProbeGateFailures` | classic 四门禁失败名单（空数组 = 全过或 probe 缺失跳过） |
| `DimensionGateFailures` | dimension 场景 post-exit 磁盘门禁失败名单（仅 dimension 评估） |
| `Probe.Round1` / `Probe.Round2` | probe roundN.json 原值透传（缺失为 `null`）；batch CSV 将其摘要成一行短串（joined/gateway/counters）观测列 |

## 失败诊断清单

### 1. 客户端崩溃（exit 2 或非 0）

- 看 `client_<SessionId>_err.log` 末尾的异常堆栈
- 看 `<loader>/run/client/crash-reports/` 最新 crash report
- 常见：`readerIndex out of bounds` → fabric 1.21.5/1.21.7–1.21.11 已知问题（见「已知限制」）
- 常见：`ClassNotFoundException: ...TransferState` → 反射未匹配到正确类路径

### 2. 服务端未就绪（exit 3）

- 看 `server_<SessionId>.log` 是否有 `Done (` 行
- 如果没到 `Done!` 就退出：看 `_err.log`，常见是 mods.toml / neoforge.mods.toml 字段不兼容（`mandatory=true` vs `type="required"`）
- 如果卡在 `Preparing spawn area`：世界生成慢，可调大 `-ServerReadyTimeoutSec 300`

### 3. 重连失败

- 看 `client_<SessionId>.log` 是否有 `no compatible startConnecting method found`
- 检查场景引擎 `execReconnect` 反射逻辑是否覆盖当前版本签名
- 参考 1.20.5+ 的 6 参数 `startConnecting(Screen, Minecraft, ServerAddress, ServerData, boolean, TransferState)`；TransferState 类路径从未改包：1.20.5+ 全版本在 `net.minecraft.client.multiplayer.TransferState`（`multiplayer.transfer` 子包不存在，早期记载有误）

### 4. 统计无区块加载（`hits + misses == 0`）

- `validateStats` 会返回 false，标记 FAIL
- 原因 1：进服超时（等待窗口内区块未加载完）→ 调大 `-DelayMs 20000`（并同步调大 `-JoinTimeoutMs`）
- 原因 2：客户端连服失败（看 client log 是否有 `Connection refused`）
- 原因 3：单人世界被误判（`mc.getSingleplayerServer() != null` 时跳过）

### 5. 缓存命中率 0%

- 检查 `hassium_cache` 目录是否真的被清理（路径必须在 `<loader>/run/client/hassium_cache`，不是根目录 `run/client/`）
- Loom runDir 在子项目目录下，是关键真相源
- ROUND1 缓存命中率 0% 正常（首次连服无缓存）；ROUND2 缓存命中率 0% 说明缓存没被写入磁盘

### 6. ServerSwitched=false

- 服务端 `ServerSmokeTest` 未检测到玩家退出
- 检查 `server_<SessionId>.log` 是否有 `HassiumSmokeTest:SERVER` 开头的日志
- 检查 `MixinMinecraftServer.onServerTick` 是否真的被调用（mixin 配置问题）

### 7. ProbeGateFailures 非空

- 对照上文门禁表定位失败项：G1 看 OVD 加载、G2 看石墙注入是否触发（server log `[LIGHT-SEG]` / stone wall 日志）、G3 看影子端 region 落盘、G4 出现本地生成为异常（classic 下不允许）
- probe 整体缺失：看 client log 有无 `PROBE_WRITTEN`；无则确认 `-PhassiumSmokeProbeDir` 透传链是否生效（`hassium.smokeTest.probeDir` 未设置时 SmokeProbeWriter 静默 no-op）

### 8. 并行模式下 Round2Pass=False（fabric PASS 但 neoforge FAIL）

- 检查 `parallel_<SessionId>.log` 是否显示 `Round2: stats=False pass=False`
- 检查 `client_<SessionId>.log` 末尾是否停在第二轮等待期间（说明客户端在等待期间被杀）
- **根因**：单会话清理逻辑误杀了另一会话的 java 进程
- **修复后**：`Stop-SessionJava` 只杀命令行命中本工程 loom 特征（`-Dfabric.dli.config=<projectRoot>` + `-Dfabric.dli.env=server|client`）的 java；端口占用者非本工程时仅告警跳过，不影响另一会话/另一项目
- 若仍出现：检查是否有其他脚本/工具调用了 `Get-Process -Name java | Stop-Process`

## 并行模式

`-Parallel` 开关启用后，同版本的 fabric + neoforge 用 `Start-Process` 同时启动，节省约一半时间。

**端口分配**：`fabric = BasePort`（默认 25565），`neoforge = BasePort + 1`（默认 25566）。用 `-BasePort` 可整体偏移。

**版本间仍串行**：并行只在同一版本的两个加载器之间；不同版本之间仍串行，避免跨版本存档冲突（高版本存档无法被低版本读取）。

**资源需求**：同时跑 4 个 JVM（2 服务端 + 2 客户端），每个 2–4G，建议至少 16G RAM。本机若内存不足，去掉 `-Parallel` 回退到串行模式。

**预编译**：并行模式下，先同步编译所有 loader（`compileJava`），避免两个并行进程同时触发编译冲突。若某 loader 预编译失败，该 loader 会话会被跳过（结果记录为 `precompile_failed`），不影响其他 loader。

**进程清理（关键）**：并行模式下，单会话结束时的清理**只杀本工程 loom dev 实例**，不会杀掉另一会话/另一项目的 java：
- 服务端：`Get-NetTCPConnection -LocalPort $ServerPort -State Listen` 定位占用端口的进程，**且命令行须命中本工程 loom 特征**（`-Dfabric.dli.config=<projectRoot>` + `-Dfabric.dli.env=server`，或 `:<loader>:runServer`）才杀；他人进程仅告警跳过
- 客户端/兜底服务端：`Get-CimInstance Win32_Process` 匹配命令行含 `-Dfabric.dli.config=<projectRoot>` 且 `-Dfabric.dli.env=(server|client)` 的 java（loom devlaunchinjector 特征，fabric/forge/neoforge 通用）
- **不杀 gradle daemon**（命令行无 dli 特征），保留给下一版本复用

**`gradlew --stop` 策略**：全程**不调用** `gradlew --stop`（该命令全局停所有 daemon，会误杀并行会话/其他项目正在跑的构建）；runServer/runClient 均显式 `--no-daemon`，不依赖 daemon。仅清理残留 Minecraft java 进程（本工程 dli 特征）+ sleep 3s。

**失败重试**：并行模式下单次失败**不重试**（`Attempts=1`），与串行模式（`MaxRetries=3`）不同。如需重试，跑完一轮后对失败的会话单独跑回归轮（`-Phase R -Versions @(...)`）。

**Job 超时**：总超时 = `ServerReadyTimeoutSec + ClientTimeoutSec + 120s` 兜底；内部已有服务端 300s + 客户端 600s 超时，正常情况下不会触发外层超时。

**单 loader 模式**：若 `-Loaders fabric` 只指定一个加载器，`-Parallel` 仍生效但无并行意义，逻辑保持统一。

## 网关双主控迁移冒烟（L0）

2.0.0 的迁移冒烟焦点为**网关双主控迁移**：网络核心（客户端进程内网关，`network/core/`）在单主控故障/切换时，经迁移引擎（L1，`network/core/migration/`）带续流票据切到目标主控，世界侧无感、区块续流。当前**代码级冒烟已就位**（`GatewaySmokeTest`，真实 TCP 双端、本机环回，归 L0 层）；真实双端演练见 E1（[`network-core-followups.md`](network-core-followups.md)）。

### 冒烟依据：`GatewaySmokeTest`（真实 TCP 双端）

`common/src/test/java/io/github/limuqy/mc/hassium/network/gateway/GatewaySmokeTest.java`（JUnit，随 `common:test` 运行）用真实 TCP socket 起**单主控**（`GatewayServer.start(port)`，`setInfoProvider(null)` 默认关压缩/UDP/SeedGen）与客户端**网络核心**（`NetworkCore.connect`），覆盖：

| 用例 | 验证点 | 断言/日志依据 |
|------|--------|----------------|
| `endToEndStandardFlow` | 握手 accepted → 网络核心 ACTIVE → 会话注册 → S2C 推送 → C2S 路由 | `core.state() == NetworkCoreState.ACTIVE`；`server.registry().get(playerId) != null`；客户端 `s2cDispatchedCount` 递增；`c2sRoutedCount` + 主控 `c2sFramesReceived` 递增 |
| `resumeFlowThroughRealTcp` | 续流票据握手 → `resumeAccepted=true` → 会话 resume → 区块续流 | 票据 = `ResumeTicket(playerId, 递增 epoch, 共享密钥签名)`；`session.resume()` / `resumeEpoch`；`ServerChunkPushManager.isPlayerResumeActive(playerId)`（`[RESUME]` 推送链标记） |

> 注：迁移冒烟基于 TCP 网关连接（握手即控制连接，`udpSupported=false`）；数据面 UDP 默认关（`dataplane.enabled=false`），不在本冒烟范围。

### L1/L2 侧迁移入口

- **`migrate.scenario`**（L2 场景）：单轮迁移演练，`command mode=migrate` 原语支持 `immediate=true`（`NetworkCore.migrateToImmediate` 直调，真实断线窗口）或 `false`（`/hassium migrate` 真实命令，预热感知无缝）；`wait until=migrated` 等迁移完成后统计。
- 故障触发（生产语义）：迁移引擎心跳监测 outbound 入站静默 ≥ `faultTimeoutMs`（沿用 `master.migrationFaultTimeoutMs` 语义）→ `Sink.onFault` → `NetworkCore.onFault` → `migrateToImmediate`。
- 迁移完成验证：MIGRATING→ACTIVE 且 `resumeAccepted=true`（probe `gateway.state`/`gateway.resumeAccepted` 可直接观测）；区块续流经推送链续流标记（`isPlayerResumeActive`）。

代码级执行：`./gradlew common:test --tests 'io.github.limuqy.mc.hassium.network.gateway.GatewaySmokeTest'`。

### 沿用可用部分（握手 / 缓存 / export 冒烟）

| 能力 | 冒烟载体 | 出处 |
|------|----------|------|
| 握手冒烟 | `GatewaySmokeTest` 双用例（标准握手 accepted + 续流票据握手） | GatewaySmokeTest.java |
| 缓存冒烟 | classic 两轮：ROUND2 区块核心缓存按字节口径命中（应用 = 全命中+全量请求+部分命中，不含 SeedGen）、OVD、光照缓存；`validateStats` 逐项核公式 | 场景引擎 classic.scenario / `/hassiumc stats` |
| export 冒烟 | `/hassiumc export` 存续（影子端世界目录拷贝 → `hassium_exports/<cacheId>`，保留 type 126 + chunkHash）；当前 smoke 脚本未驱动 export，作为网关冒烟的手动/扩展项 | `HassiumCommandHandler.startCacheExport` |

### E1/E2 呼应（`docs/network-core-followups.md`）

- **E1（真实双端联调）**：上述冒烟多为单测/桩测，`GatewaySmokeTest` 真实 TCP 仅本机环回；E1 =「真客户端 + 真主控跑本文档 classic 流程（ROUND1/2）」+「双主控迁移演练」。迁移命令入口 `/hassium migrate`（B4，仅开发环境）与双主控演练已交付（见 [`network-core-followups.md`](network-core-followups.md) B4/E1）；端点通告 CONFIG 帧（B1）状态见该清单。
- **E2（ViaFabric 运行时冒烟，随 E1 一起）**：装 ViaFabric → 客户端日志出现 `Hassium: ViaFabric detected via classpath (<类>)` 或 `via mod list (<modId>)`（`ViaFabricCompat`）+ `Hassium: ViaFabric decode bridge installed (live <x> -> fresh <y>)`（`ViaDecodeBridge`）；不装 → 无桥日志（登录时重探测，`NetworkCore.onLogin`）。

## 已知限制

| 版本 | 加载器 | 问题 |
|------|--------|------|
| 1.21.5 / 1.21.7–1.21.11 | fabric | `setViewDistance` 切换后区块包序列化出现 `readerIndex out of bounds`，客户端崩溃；ROUND2 大概率 FAIL |
| 高 ZSTD 级别（≥9） | 全部 | ZSTD-9 压缩速度远慢于 ZSTD-3（~50% @16KB, ~95% @256KB+），导致服务端无法在超时前推送完初始区块；客户端 100s 超时 FAIL；默认 ZSTD-3 稳定 |
| 慢加载版本 | 全部 | 部分版本首次进服需要区块替换，默认等待不够；可调 `-DelayMs 20000`（同步调大 `-JoinTimeoutMs`） |
| Forge 1.20.1 / 1.21.1 / 1.21.3–1.21.10 | forge | 当前脚本未单独跑 forge 子项目；用 neoforge 子项目 + `loom.platform='forge'` 覆盖（见 `settings.gradle`） |
| dimension 场景 dump 时刻 | 全部 | 影子世界仅在断连/退出时 flush，probe `disk.dimensions` 在 dump 时刻为 -1 属预期；磁盘证据由 harness post-exit 门禁采集 |

## Java 侧开关参考

### Gradle 属性（`-P`，脚本 → loom 透传，三端 loom-fabric/forge/neoforge 同构映射）

| 属性 | 值 | 映射 JVM 属性 | 作用 |
|------|----|--------------|------|
| `hassiumSmokeTest` | `true` | `hassium.smokeTest` | 触发 smoke test 注入 |
| `hassiumSmokeHost` | `127.0.0.1:25565` | `hassium.smokeTest.host` | 客户端 quickPlayMultiplayer 目标地址 |
| `hassiumSmokeDelayMs` | `10000` | `hassium.smokeTest.delayMs` | 每轮进服后等待毫秒 |
| `hassiumSmokeReconnectDelayMs` | `3000` | `hassium.smokeTest.reconnectDelayMs` | 第一轮断开后到重连的毫秒 |
| `hassiumSmokeProbeDir` | `build/smoke-test/probe/<SessionId>` | `hassium.smokeTest.probeDir` | PROBE JSON 输出目录；未设置时 SmokeProbeWriter no-op |
| `hassiumSmokeScenario` | `<name>` | `hassium.smokeScenario` | 客户端场景选择（ScenarioEngine 加载 `.scenario`） |
| `hassiumServerSmokeScenario` | `<name>` | `hassium.serverSmokeScenario` | 服务端场景注入；非空时 ServerSmokeTest 自动 OP 在线玩家（dimension 场景 `/execute in` 需要） |
| `hassiumSmokePhases` | `classic` | `hassium.smokePhases` | Java 侧阶段：`classic` / `pregen` |
| `hassiumSmokeManualLogout` | `true` | `hassium.smokeTest.manualLogout` | 断开改走真实手动登出路径 |

### JVM 系统属性（`-D`，由 loom 自动注入）

| 属性 | 默认 | 作用 |
|------|------|------|
| `hassium.smokeTest` | `false` | 客户端启用 `ClientSmokeTest`（门面 → 场景引擎） |
| `hassium.smokeTest.delayMs` | `10000` | 同上 DelayMs |
| `hassium.smokeTest.reconnectDelayMs` | `3000` | 同上 ReconnectDelayMs |
| `hassium.smokeTest.joinTimeoutMs` | `120000` | 单轮进服超时 |
| `hassium.smokeTest.moveSeconds` | `0` | 进服后飞行移动秒数 |
| `hassium.smokeTest.host` | `127.0.0.1:25565` | 连服/重连目标 |
| `hassium.smokeTest.probeDir` | 未设置（no-op） | PROBE JSON 输出目录 |
| `hassium.smokeScenario` | 未设置 | 场景选择；未设置时 migrateTo 非空 → migrate，否则 classic |
| `hassium.smokeTest.dimWaitMs` / `endWaitMs` | `max(20000, delayMs*2)` / `max(30000, delayMs*3)` | dimension 场景切维段 / END 段等待 |
| `hassium.smokeTest.manualLogout` | `false` | 断开改走真实手动登出路径 |
| `hassium.serverSmokeTest` | `false` | 服务端启用 `ServerSmokeTest` |
| `hassium.serverSmokeTest.vd1` / `vd2` | `20` / `10` | 两轮视距 |
| `hassium.serverSmokeScenario` | 未设置 | 服务端场景注入；非空时玩家 join 即 OP |
| `hassium.smokePhases` | `classic` | 阶段选择：`classic`（两轮连服）/ `pregen`（预生成大片区块后停服） |

## 相关代码

| 路径 | 作用 |
|------|------|
| `common/.../client/scenario/ScenarioEngine.java` | 数据驱动场景引擎：解析 `.scenario` 步骤序列并在客户端 tick 中逐步执行（join/wait/fly/command/dimension/disconnect/reconnect/dump/assertProbe/exit）；契约 marker 与退出码语义不变 |
| `common/.../client/scenario/ScenarioStep.java` | 场景步骤模型（类型 + 参数表） |
| `common/src/main/resources/hassium/smoke/scenario/*.scenario` | 内置场景四件套：classic / migrate / seedgen / dimension |
| `common/.../client/ClientSmokeTest.java` | 客户端冒烟门面：init 装配场景引擎、marker 输出、跨版本反射重连工具、统计校验 |
| `common/.../client/SmokeProbeWriter.java` | PROBE JSON v1 落盘（roundN.json：stats/gateway/counters/disk） |
| `common/.../server/ServerSmokeTest.java` | 服务端视距切换 + R2 方块变化注入（section delta / `[LIGHT-SEG]` 分段光照触发）+ 场景玩家自动 OP |
| `common/src/test/java/io/github/limuqy/mc/hassium/network/gateway/GatewaySmokeTest.java` | **L0 网关双主控迁移冒烟依据**：真实 TCP 双端（`GatewayServer.start` + `NetworkCore.connect`），握手/ACTIVE/会话注册/S2C 注入/C2S 路由/续流票据 resume + 推送链标记 |
| `common/.../network/core/NetworkCore.java` / `migration/MigrationEngine.java` / `MigrationPolicy.java` | 网络核心状态机（IDLE→CONNECTING→HANDSHAKING→ACTIVE→MIGRATING）与 L1 迁移引擎（心跳/故障触发/续流票据） |
| `common/.../network/gateway/GatewayServer.java` / `GatewayChannel.java` / `ServerChunkPushManager.java` | 主控核心接入（网关监听端口 = `master.controlReachableEndpoints[0]` 兜底 25566）；推送链续流标记（`isPlayerResumeActive`） |
| `common/.../network/dataplane/DataPlaneUdpServer.java` / `ReliableDatagramSession.java` / `DataPlaneClientBundle.java` | 数据面 UDP/KCP 载体（网关↔主控通道 bulk 载体，默认关 `dataplane.enabled=false`） |
| `common/.../config/HassiumConfigService.java` | 统一配置快照（含迁移引擎 `master.migrationFaultTimeoutMs` 接线） |
| `common/.../network/core/viafabric/ViaFabricCompat.java` / `ViaDecodeBridge.java` | ViaFabric 探测与 S2C 解码桥（E2 运行时冒烟日志源） |
| `scripts/runtime-smoke-test.ps1` | 单次会话脚本（场景选择、profiles patch、PROBE 解析、门禁评估、result JSON） |
| `scripts/runtime-smoke-test-batch.ps1` | 批量脚本（`-Scenarios` 场景计划、锚点集过滤、CSV 汇总） |
| `scripts/smoke/profiles/<name>.profile.properties` | 场景配置档案（patch 双端 hassium toml） |

## 退役说明（UdpFailover / Nginx harness）

1.1.2 的 UDP 控制面 failover 冒烟（`udp-failover` phase + nginx stream 反代 harness）已随 2.0.0 客户端 failover 套件退役并**全链删除**：脚本层 `-Phase UdpFailover` / `-NginxExePath` / `-ProxyPort` / `-DryRun` / `-InjectTcpClose` / `-SeamlessMode` 参数、`scripts/smoke/UdpFailoverSmoke.psm1`、`runtime-smoke-test.Tests.ps1`（Pester）、Java 侧 `udp-failover` / `dataplane` / `all` 死分支均已不存在；`network.dataPlane.recoveryFreeze` / `controlStallMs` / `failoverExpiryMs` 配置键亦已删。当前 `-SmokePhases` 仅剩 `classic` / `pregen`。历史拓扑与设计决策见 `docs/archive/multi-channel_network_research.md` 与 `docs/handoff/handoff-2026-08-09-network-core.md`。
