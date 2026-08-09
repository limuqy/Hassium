# 运行时冒烟测试（Runtime Smoke Test）

Hassium 跨版本（1.20.1–1.21.11）× 多加载器（fabric / neoforge）的实跑验证流程。在 dev 环境同时启动服务端和客户端，自动连服 → 采集统计 → 断开 → 重连 → 再采集，用于发现编译通过但运行时才暴露的回归（路径错误、Mixin 失效、跨版本 API 漂移、缓存未清理等）。

## 概述

- **测试矩阵**：17 个 MC 版本 × 2 个加载器（fabric / neoforge）= 34 个默认会话；额外可显式 `-Loaders fabric,forge,neoforge` 跑 Forge，**Forge 仅有 1.20.1 / 1.20.6 有 `builds_for`**（`forge` 子项目独立、经 `loom-forge.gradle`），其它版本会自动 SKIP。批量脚本读取每版本 `versionProperties/<ver>.properties` 的 `builds_for` 决定是否跳过该 loader，未列入 `builds_for` 的不会强行起 `:forge:runServer`（否则 `settings.gradle` 未 include forge 子项目会直接失败）。
- **执行方式**：PowerShell 脚本驱动 Gradle `runServer` / `runClient`，注入 `-Dhassium.smokeTest=true` 等 JVM 属性
- **dev 专用**：测试代码（`ClientSmokeTest` / `ServerSmokeTest`）只在 dev 环境启用，正常生产 jar 不受影响
- **输出位置**：`build/smoke-test/`（已在 `.gitignore` 范围内）

## 前置条件

1. **JDK 21+**：Hassium 全版本编译需要
2. **Gradle wrapper**：使用项目自带的 `gradlew.bat`，无需本机全局安装
3. **Windows + PowerShell**：脚本依赖 `Get-NetTCPConnection`、`Start-Process` 等 cmdlet
4. **25565 端口可用**：脚本会尝试释放被占用端口，但建议预先关闭其它 MC 服务端
5. **首次运行前**：跑过一次 `./gradlew --no-daemon common:decompile`，确保 mappings 已下载

## 快速开始

```powershell
# 单次会话（1.20.1 fabric，初始轮）
.\scripts\runtime-smoke-test.ps1 -Ver 1.20.1 -Loader fabric -Phase I -SessionId "1.20.1_fabric_I"

# 全量初始轮（17 版 × 2 加载器，约 4–6 小时）
.\scripts\runtime-smoke-test-batch.ps1 -Phase I

# 并行跑全量初始轮（fabric+neoforge 同时，节省约一半时间，约 20–30 分钟）
.\scripts\runtime-smoke-test-batch.ps1 -Phase I -Parallel

# 并行 + 自定义起始端口（fabric=25570, neoforge=25571）
.\scripts\runtime-smoke-test-batch.ps1 -Phase I -Parallel -BasePort 25570

# 仅指定版本×fabric
.\scripts\runtime-smoke-test-batch.ps1 -Phase I -Versions @("1.20.1","1.21.11") -Loaders fabric

# 回归轮（默认对全部版本再跑一遍；可结合初始轮结果挑选）
.\scripts\runtime-smoke-test-batch.ps1 -Phase R -Versions @("1.20.1","1.21.6")
```

### 单会话参数

| 参数 | 必填 | 默认 | 说明 |
|------|------|------|------|
| `-Ver` | 是 | — | MC 版本，如 `1.20.1` |
| `-Loader` | 是 | — | `fabric` 或 `neoforge` |
| `-Phase` | 是 | — | `I`（初始轮）或 `R`（回归轮）；`UdpFailover` 为 1.1.2 遗留值（客户端 failover 已退役，跑必 FAIL，见「Nginx Failover Harness（历史，已退役）」） |
| `-SessionId` | 是 | — | 会话 ID，用于日志文件命名，如 `1.20.1_fabric_I` |
| `-CleanWorld` | 否 | false | 删除服务端存档；batch 按 loader 策略决定（见下） |
| `-SmokeHost` | 否 | 空 | 客户端连服完整地址（如 `127.0.0.1:25566`）；指定后优先于 `-ServerPort` |
| `-ServerPort` | 否 | `25565` | 服务端监听端口（并行模式由 batch 脚本分配：fabric=BasePort, neoforge=BasePort+1） |
| `-DelayMs` | 否 | `10000` | 进世界后等待毫秒，再 dump 统计（ROUND1 窗口=DelayMs×2=20s，ROUND2=DelayMs=10s） |
| `-ReconnectDelayMs` | 否 | `3000` | 第一轮断开后到重连的毫秒 |
| `-ServerReadyTimeoutSec` | 否 | `160` | 服务端 `Done!` 出现超时 |
| `-ClientTimeoutSec` | 否 | `240` | 客户端退出超时（遗留 `UdpFailover` phase 会强制抬到 300） |
| `-SmokePhases` | 否 | `classic` | Java 侧阶段：`classic`（经典两轮）。`udp-failover` 已删除（客户端 failover 退役，`ClientSmokeTest` 不再识别该阶段，传入等同 classic）；旧 `dataplane` / `all` 值仅为脚本与 JVM 参数兼容而解析，已退役，不应作为验证入口 |
| `-NginxExePath` | 否 | `D:\app\nginx-1.31.3\nginx.exe` | 仅遗留 `UdpFailover` phase 使用（nginx stream 反代路径，已退役） |
| `-ProxyPort` | 否 | `0`（→ `$ServerPort + 5`） | 仅遗留 `UdpFailover` phase 使用（nginx stream listen port，已退役） |
| `-DryRun` | 否 | false | 仅遗留 `UdpFailover` phase 使用（仅起 nginx + 验 listen + stop + exit；不起 server/client，已退役） |
| `-InjectTcpClose` | 否 | false | 仅遗留 `UdpFailover` phase 使用（Round1 后由 nginx `-s stop` 真实关闭主控 TCP，已退役） |
| `-SeamlessMode` | 否 | false | 仅遗留 `UdpFailover` phase 使用（patch 客户端 `hassium-client.toml` 的 `network.dataPlane.recoveryFreeze=false` 跑无感链路，已退役；`recoveryFreeze` 键已删） |

### 批量参数

| 参数 | 必填 | 默认 | 说明 |
|------|------|------|------|
| `-Phase` | 是 | — | `I` 或 `R` |
| `-Versions` | 否 | 全部 17 版 | 指定版本子集 |
| `-Loaders` | 否 | `fabric,neoforge` | 加载器子集 |
| `-MaxRetries` | 否 | `3` | 单会话失败重试次数上限 |
| `-Parallel` | 否 | false | 同版本 fabric+neoforge 并行跑（Start-Process） |
| `-BasePort` | 否 | `25565` | 起始端口；fabric 用此端口，neoforge 自动 +1（仅并行模式生效） |

**batch `CleanWorld` 策略**（按 loader 独立跟踪，因为 fabric/neoforge 各有 `run/server`）：

| 场景 | 是否清理 |
|------|----------|
| 该 loader 的第一个版本 | 清理 |
| 后续升版本 / 同向 | **不清理**（复用存档，加快启动） |
| 退版本（高→低） | 强制清理（高版本存档无法被低版本读取） |
| 同会话失败重试 | 强制清理 |

## 测试流程

```
┌─────────────────────────────────────────────────────────────────────────┐
│  1. 清理 <loader>/run/client/hassium_cache + crash-reports             │
│  2. 写 <loader>/run/server/server.properties (VD=20, online-mode=false) │
│  3. (CleanWorld) 删 <loader>/run/server/world*                          │
│  4. 启动 :<loader>:runServer  →  ServerSmokeTest 设置 VD=20            │
│  5. 等待 server log "Done ("                                            │
│  6. 启动 :<loader>:runClient  →  ClientSmokeTest 状态机驱动             │
│     ┌───────────────────────────────────────────────────────────────┐  │
│     │  WAIT_JOIN_1  →  等到 player.getY() > 0                       │  │
│     │      ↓ (DelayMs)                                              │  │
│     │  ROUND_1_STATS  →  dump HassiumCommandHandler.getClientStats  │  │
│     │      ↓                                                         │  │
│     │  DISCONNECTING  →  conn.disconnect + NetworkStats.reset       │  │
│     │      ↓ (ReconnectDelayMs)                                      │  │
│     │  等服务端检测玩家数 0→切 VD=10 + 注入石墙              │  │
│     │      ↓                                                         │  │
│     │  WAIT_JOIN_2  →  反射 ConnectScreen.startConnecting            │  │
│     │      ↓ (DelayMs)                                              │  │
│     │  ROUND_2_STATS  →  dump 第二轮统计                             │  │
│     │      ↓                                                         │  │
│     │  DONE  →  System.exit(0 / 2)                                   │  │
│     └───────────────────────────────────────────────────────────────┘  │
│  7. 解析 client log：提取 ROUND1/2 统计、PASS/FAIL 标记                  │
│  8. 写 result_${SessionId}.json + stats/*.txt                           │
│  9. 杀服务端 + 残留 java                                                │
└─────────────────────────────────────────────────────────────────────────┘
```

**为什么等到 `player.getY() > 0` 才开始计时？** 部分版本进服很慢（需要区块替换、服务端处理）；如果 player 对象一创建就开始 10s 计时，统计时区块还没加载完，`hits + misses == 0`。改为等玩家位置被服务端确认（收到 `ClientboundPlayerPositionPacket`）后才开始计时。

**R2 方块变化注入（2.0.0 起）**：玩家离线窗口内，`ServerSmokeTest` 在世界出生点上方放置一堵 4 格高石墙（x∈[-160,160)、y∈[64,68)、z∈[2,4)，横跨 VD10 全宽）。R2 客户端从区块核心缓存读回该区域时 chunkHash 不一致 → 请求 section hash → 服务端回 `SectionDeltaS2CPacket` → 客户端 merge 后变化 section 缺光字段 → 触发增量分段光照重算路径（`[LIGHT-SEG]`）。无变化时该路径不会走，因此注入是 ROUND2 冒烟触发 section-delta / 分段光照的前置条件（`ServerSmokeTest.injectR2BlockChange`）。

## 退出码

| 退出码 | 含义 |
|--------|------|
| `0` | PASS：两轮统计均 OK 且客户端正常退出 |
| `2` | FAIL：统计校验失败、客户端崩溃或非 0 退出 |
| `3` | server_not_ready：服务端 160s 内未出现 `Done!` |

## 统计字段说明

每个 ROUND 的统计来自 `/hassiumc stats` 命令的输出（`HassiumCommandHandler.getClientStatsMessage()`），主要字段：

| 字段 | 含义 |
|------|------|
| **带宽压缩(Zlib→ZSTD)** | 百分比 = 当前/原版Zlib × 100%（越小越省）；括号内为 Hassium ZSTD 线缆实测字节与原版 Zlib 管线估算字节 |
| **压缩比** | Zlib 估算 ÷ ZSTD 实测，如 `1.76:1` 表示 ZSTD 比 Zlib 少 43% |
| **区块缓存** | 命中率 + 命中字节（从本地缓存加载）+ 增量字节（section-delta 避免的完整加载） |
| **区块加载** | 总数（新增数/新增字节 + 过期数/过期字节） |
| **超视渲染（OVD）** | `已加载 / 缺失`；ROUND2 应非 0 |
| **光照缓存** | 命中率 + 命中数 + 重算数 |

> **注意：**"原版Zlib" 是 `VanillaZlibEstimator.estimate()` 对同负载模拟 `Deflater(level=6)` + 阈值 256 帧格式的输出估算值，并非真实原版管线实测。`estimate(int)`（无实际字节时使用）基于 MC 区块 NBT 典型压缩率 25–35% 校准。详见 `VanillaZlibEstimator` 和 `VanillaZlibVsZstdBenchmarkTest`。

**典型健康指标**（1.20.1 fabric ROUND1 VD=20 参考）：

- 带宽压缩(Zlib→ZSTD)：56.9%（当前 7.0 MB，原版Zlib 12.3 MB），压缩比 1.76:1
- 区块加载：~1600 新增 / ~25 MB
- ROUND2（VD=10，已有缓存）：缓存命中率 >99%，OVD loaded >1100，光照缓存命中率 >95%

## 日志位置

```
build/smoke-test/
├── logs/
│   ├── server_<SessionId>.log           # 服务端 stdout
│   ├── server_<SessionId>_err.log       # 服务端 stderr
│   ├── client_<SessionId>.log           # 客户端 stdout（含 ROUND1/2 统计原文）
│   └── client_<SessionId>_err.log       # 客户端 stderr
├── stats/
│   ├── <SessionId>_round1_VD20.txt      # 提取后的 ROUND1 统计（VD=20 场景）
│   ├── <SessionId>_round2_VD10.txt       # 提取后的 ROUND2 统计（VD=10 + OVD 场景）
│   └── <SessionId>_server.txt           # 服务端视距切换日志
├── results/
│   ├── result_<SessionId>.json          # 单会话结构化结果
├── batch-results-<Phase>.csv            # 批量汇总
└── failures-<Phase>.log                 # 失败会话清单（仅 batch 模式）
```

`result_<SessionId>.json` 字段：

```json
{
    "SessionId": "1.20.1_fabric_I",
    "Ver": "1.20.1",
    "Loader": "fabric",
    "Phase": "I",
    "Result": "PASS",
    "ClientExitCode": 0,
    "Round1Stats": true,
    "Round1Pass": true,
    "Round2Stats": true,
    "Round2Pass": true,
    "ServerSwitched": true,
    "HasPass": true,
    "HasFail": false,
    "StatsFiles": [
        "build/smoke-test/stats/1.20.1_fabric_I_round1_VD20.txt",
        "build/smoke-test/stats/1.20.1_fabric_I_round2_VD10.txt"
    ]
}
```

## 失败诊断清单

### 1. 客户端崩溃（exit 2 或非 0）

- 看 `client_<SessionId>_err.log` 末尾的异常堆栈
- 看 `<loader>/run/client/crash-reports/` 最新 crash report
- 常见：`readerIndex out of bounds` → fabric 1.21.5/1.21.7–1.21.11 已知问题
- 常见：`ClassNotFoundException: ...TransferState` → 反射未匹配到正确类路径

### 2. 服务端未就绪（exit 3）

- 看 `server_<SessionId>.log` 是否有 `Done (` 行
- 如果没到 `Done!` 就退出：看 `_err.log`，常见是 mods.toml / neoforge.mods.toml 字段不兼容（`mandatory=true` vs `type="required"`）
- 如果卡在 `Preparing spawn area`：世界生成慢，可调大 `-ServerReadyTimeoutSec 300`

### 3. 重连失败

- 看 `client_<SessionId>.log` 是否有 `no compatible startConnecting method found`
- 检查 `ClientSmokeTest.triggerReconnect` 反射逻辑是否覆盖当前版本签名
- 参考 1.20.5+ 的 6 参数 `startConnecting(Screen, Minecraft, ServerAddress, ServerData, boolean, TransferState)`，TransferState 类路径在 1.21.6+ 从 `multiplayer.TransferState` 改到 `multiplayer.transfer.TransferState`

### 4. 统计无区块加载（`hits + misses == 0`）

- `ClientSmokeTest.validateStats` 会返回 false，标记 FAIL
- 原因 1：进服超时（10s 内区块未加载）→ 调大 `-DelayMs 20000`
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

### 7. 并行模式下 Round2Pass=False（fabric PASS 但 neoforge FAIL）

- 检查 `parallel_<SessionId>.log` 是否显示 `Round2: stats=False pass=False`
- 检查 `client_<SessionId>.log` 末尾是否停在 `WAIT_JOIN_2 ... waiting N ms before stats`（说明客户端在 ROUND2 等待期间被杀）
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

## 网关双主控迁移冒烟（T7）

1.1.2 的 UDP 控制面 failover 冒烟（`udp-failover` phase）已随客户端 failover 套件退役（见下节），2.0.0 的迁移冒烟焦点为**网关双主控迁移**：网络核心（客户端进程内网关，`network/core/`）在单主控故障/切换时，经迁移引擎（L1，`network/core/migration/`）带续流票据切到目标主控，世界侧无感、区块续流。当前**代码级冒烟已就位**（`GatewaySmokeTest`，真实 TCP 双端、本机环回）；真实双端/双主控演练为阻塞缺口（E1，见下）。

### 冒烟依据：`GatewaySmokeTest`（真实 TCP 双端）

`common/src/test/java/io/github/limuqy/mc/hassium/network/gateway/GatewaySmokeTest.java`（JUnit，随 `common:test` 运行）用真实 TCP socket 起**单主控**（`GatewayServer.start(port)`，`setInfoProvider(null)` 默认关压缩/UDP/SeedGen）与客户端**网络核心**（`NetworkCore.connect`），覆盖：

| 用例 | 验证点 | 断言/日志依据 |
|------|--------|----------------|
| `endToEndStandardFlow` | 握手 accepted → 网络核心 ACTIVE → 会话注册 → S2C 推送 → C2S 路由 | `core.state() == NetworkCoreState.ACTIVE`；`server.registry().get(playerId) != null`；客户端 `s2cDispatchedCount` 递增；`c2sRoutedCount` + 主控 `c2sFramesReceived` 递增 |
| `resumeFlowThroughRealTcp` | 续流票据握手 → `resumeAccepted=true` → 会话 resume → 区块续流 | 票据 = `ResumeTicket(playerId, 递增 epoch, 共享密钥签名)`；`session.resume()` / `resumeEpoch`；`ServerChunkPushManager.isPlayerResumeActive(playerId)`（`[RESUME]` 推送链标记） |

> 注：迁移冒烟基于 TCP 网关连接（握手即控制连接，`udpSupported=false`）；数据面 UDP 默认关（`dataplane.enabled=false`），不在本冒烟范围。

### 新流程（与代码冒烟能力一一对应）

1. **启动单主控**：`GatewayServer.start(port)`。网关监听地址 = `master.controlReachableEndpoints[0]`，兜底 25566（`GatewayPlatformWiring.resolveBindPort`）。
2. **验证网络核心 ACTIVE**：握手 accepted → `NetworkCoreState.ACTIVE`（`NetworkCore.onHandshakeAccepted:361`；状态定义见 `NetworkCoreState.java:24`）；会话注册于主控 registry。
3. **模拟主控故障/切换**（两路皆可）：
   - 故障触发（生产语义）：迁移引擎心跳监测 outbound 入站静默 ≥ `faultTimeoutMs`（默认 60000，沿用 `master.migrationFaultTimeoutMs` 语义，`MigrationPolicy.java:22-23`）→ `Sink.onFault` → `NetworkCore.onFault` → `migrateToImmediate`（`MigrationEngine.tick:257-265` / `NetworkCore.java:858-873`）。
   - 直接切换（测试缝）：`NetworkCore.migrateToImmediate(endpoint)` —— ACTIVE→MIGRATING→`connectWithResume`（`NetworkCore.java:237-251`）。
4. **验证无感迁移（客户端 Connection 不断、区块续流）**：
   - 状态迁移 MIGRATING→ACTIVE 且 `resumeAccepted=true`（日志 `NetworkCore -> ACTIVE (migrated to ..., resumeAccepted=true — 续流就绪)`，`NetworkCore.java:373`）；
   - 区块续流：主控推送链续流标记激活（`ServerChunkPushManager.isPlayerResumeActive`，GatewaySmokeTest 同款断言）；
   - 客户端 Connection 不断：迁移发生在网关 outbound 层，vanilla 壳连接不动（`NetworkCoreState.MIGRATING` 定义「主控切换中（旧 outbound 已断开 / 新 outbound 连接中），世界侧无感」，`NetworkCoreState.java:25-26`；壳连接仅 keep-alive 响应走 vanilla TCP）。

代码级执行：`./gradlew common:test --tests 'io.github.limuqy.mc.hassium.network.gateway.GatewaySmokeTest'`（Test 侧 JUnit；本任务为纯文档任务不代跑）。

### 沿用可用部分（握手 / 缓存 / export 冒烟）

| 能力 | 冒烟载体 | 出处 |
|------|----------|------|
| 握手冒烟 | `GatewaySmokeTest` 双用例（标准握手 accepted + 续流票据握手） | GatewaySmokeTest.java |
| 缓存冒烟 | classic 两轮：ROUND2 区块核心缓存命中（参考 >99%）、OVD、光照缓存 | `ClientSmokeTest` 状态机 / `/hassiumc stats` |
| export 冒烟 | `/hassiumc export` 存续（影子端世界目录拷贝 → `hassium_exports/<cacheId>`，保留 type 126 + chunkHash）；当前 smoke 脚本未驱动 export，作为网关冒烟的手动/扩展项 | `HassiumCommandHandler.startCacheExport:255` |

### E1/E2 呼应（`docs/network-core-followups.md`）

- **E1（真实双端联调，阻塞项）**：上述冒烟均为单测/桩测，`GatewaySmokeTest` 真实 TCP 仅本机环回；E1 建议「真客户端 + 真主控跑 `docs/runtime-smoke-test.md`；双主控迁移演练」。本文档 classic 流程（真客户端 + 真主控 + ROUND1/2）即 E1 的「真客户端 + 真主控」载体；**双主控迁移演练仍未达**（端点通告未接 CONFIG 帧 B1、无 `/hassium migrate` 命令入口 B4），随 E1 一起补。
- **E2（ViaFabric 运行时冒烟，随 E1 一起）**：装 ViaFabric → 客户端日志出现 `Hassium: ViaFabric detected via classpath (<类>)` 或 `via mod list (<modId>)`（`ViaFabricCompat.java:146,160`）+ `Hassium: ViaFabric decode bridge installed (live <x> -> fresh <y>)`（`ViaDecodeBridge.java:102`）；不装 → 无桥日志（登录时重探测，`NetworkCore.onLogin:132`）。

## Nginx Failover Harness（历史，已退役）

> 1.1.2 产物，已退役，仅保留事实记录防踩坑。**不要**再用 `-Phase UdpFailover` 作验证入口。

**退役理由**：2.0.0 客户端 failover 套件删除（729d92e：`ClientFailoverAttemptMarker` / `EndpointStore` / `Identity` / `ClientRecoveryState` / `ControlEndpoint` / `ControlReconnectLauncher` / `ControlReconnectOrchestrator` 全部移除），`ClientSmokeTest.java:92` 明注「T6：客户端 failover 已退役，udp-failover 阶段删除（旧链路失语义）」——旧六类自检 markers 全部不再产出。脚本层残留（`scripts/runtime-smoke-test.ps1` 的 `-Phase UdpFailover`、`scripts/smoke/UdpFailoverSmoke.psm1`）与 Java 侧脱节：跑该 phase 时 Java 实际执行 classic 两轮，marker 提取恒缺失 → 必 FAIL。`network.dataPlane.recoveryFreeze` 键已删（2026-08-09 config-restructure；原仅 `ClientSmokeTest` 打标 `HassiumSmokeTest:CLIENT_MODE recoveryFreeze=`，现为固定字面量）；`controlStallMs` / `failoverExpiryMs` 键亦已删，服务端 `ControlFailoverHandler` 引用固定常量（6000/30000）。

**历史形态**（存档参考）：`-Phase UdpFailover` 在 server ready 前起本会话专属 nginx stream 反代（`client → nginx :ProxyPort → server :ServerPort`，UDP 数据面直连），经 `-InjectTcpClose`（`nginx -s stop` 真断 TCP）或内部模拟断连触发客户端恢复链路，聚合六个生产 markers（Bind / WRR / Permit / Reconnect / CacheResume / Terminal 六类，见归档）判 PASS；`-DryRun` 仅验 harness 启停序列，`-SeamlessMode` patch `recoveryFreeze=false` 跑无感链路（键已删，历史）；退出码 4/5 为 nginx 缺失/未就绪。拓扑、场景细节与 Pester 覆盖见归档。

相关历史归档：`docs/archive/multi-channel_network_research.md`（多通道数据面研究，TCP PoC 退役记录）、`docs/archive/superpowers/`（旧工作流产物）；客户端 failover 设计决策见 `docs/handoff/handoff-2026-08-09-network-core.md`。

## 已知限制

| 版本 | 加载器 | 问题 |
|------|--------|------|
| 1.21.5 / 1.21.7–1.21.11 | fabric | `setViewDistance` 切换后区块包序列化出现 `readerIndex out of bounds`，客户端崩溃；ROUND2 大概率 FAIL |
| 高 ZSTD 级别（≥9） | 全部 | ZSTD-9 压缩速度远慢于 ZSTD-3（~50% @16KB, ~95% @256KB+），导致服务端无法在超时前推送完初始区块；客户端 100s 超时 FAIL；默认 ZSTD-3 稳定 |
| 慢加载版本 | 全部 | 部分版本首次进服需要区块替换，8s 不够；可调 `-DelayMs 20000` |
| Forge 1.20.1 / 1.20.6 | forge | 当前脚本未单独跑 forge 子项目；用 neoforge 子项目 + `loom.platform='forge'` 覆盖（见 `settings.gradle`） |

## Java 侧开关参考

### Gradle 属性（`-P`）

| 属性 | 值 | 作用 |
|------|----|----|
| `hassiumSmokeTest` | `true` | 触发 loom-fabric / loom-neoforge 注入 smoke test JVM 属性 |
| `hassiumSmokeHost` | `127.0.0.1:25565` | 客户端 quickPlayMultiplayer 目标地址 |
| `hassiumSmokeDelayMs` | `10000` | 每轮进服后等待毫秒 |
| `hassiumSmokeReconnectDelayMs` | `3000` | 第一轮断开后到重连的毫秒 |

### JVM 系统属性（`-D`，由 loom 自动注入）

| 属性 | 默认 | 作用 |
|------|------|------|
| `hassium.smokeTest` | `false` | 客户端启用 `ClientSmokeTest` |
| `hassium.smokeTest.delayMs` | `10000` | 同上 DelayMs |
| `hassium.smokeTest.reconnectDelayMs` | `3000` | 同上 ReconnectDelayMs |
| `hassium.smokeTest.joinTimeoutMs` | `120000` | 单轮进服超时 |
| `hassium.smokeTest.host` | `127.0.0.1:25565` | 重连目标 |
| `hassium.serverSmokeTest` | `false` | 服务端启用 `ServerSmokeTest` |
| `hassium.serverSmokeTest.vd1` | `20` | 第一轮视距 |
| `hassium.serverSmokeTest.vd2` | `8` | 第二轮视距 |
| `hassium.smokePhases` | `classic` | Java 侧阶段选择：`classic`（默认）/ `pregen`（脚本 `-PregenOnly` 用）。`udp-failover` 已删除（`ClientSmokeTest` 不再识别）；旧 `dataplane` / `all` 只为兼容旧调用，服务端忽略已退役的 PoC 状态机 |

## 相关代码

| 路径 | 作用 |
|------|------|
| `common/src/main/java/io/github/limuqy/mc/hassium/client/ClientSmokeTest.java` | 客户端两轮状态机（R1 断开 → R2 重连）、跨版本反射重连、统计校验；`udp-failover` 阶段已删（`ClientSmokeTest.java:92`），`CLIENT_MODE recoveryFreeze=` 固定字面量打标保留（键已删） |
| `common/src/main/java/io/github/limuqy/mc/hassium/server/ServerSmokeTest.java` | 服务端视距切换 + R2 方块变化注入（section delta / `[LIGHT-SEG]` 分段光照触发）；`dataplane`/`all` 兼容解析告警 |
| `common/src/test/java/io/github/limuqy/mc/hassium/network/gateway/GatewaySmokeTest.java` | **网关双主控迁移冒烟依据**：真实 TCP 双端（`GatewayServer.start` + `NetworkCore.connect`），握手/ACTIVE/会话注册/S2C 注入/C2S 路由/续流票据 resume + 推送链标记 |
| `common/.../network/core/NetworkCore.java` / `migration/MigrationEngine.java` / `MigrationPolicy.java` | 网络核心状态机（IDLE→CONNECTING→HANDSHAKING→ACTIVE→MIGRATING）与 L1 迁移引擎（心跳/故障触发 `faultTimeoutMs`=master.migrationFaultTimeoutMs 语义/续流票据） |
| `common/.../network/gateway/GatewayServer.java` / `GatewayChannel.java` / `ServerChunkPushManager.java` | 主控核心接入（网关监听端口 = `master.controlReachableEndpoints[0]` 兜底 25566）；推送链续流标记（`isPlayerResumeActive`） |
| `common/.../network/dataplane/DataPlaneUdpServer.java` / `ReliableDatagramSession.java` / `DataPlaneClientBundle.java` | 数据面 UDP/KCP 载体（网关↔主控通道 bulk 载体，默认关 `dataplane.enabled=false`） |
| `common/.../network/dataplane/ControlFailoverHandler.java` | 服务端 permit 链保留（`controlStallMs` / `failoverExpiryMs` 键已删，引用固定常量 6000/30000）；客户端侧消费链已删（729d92e） |
| `common/.../config/HassiumConfigService.java` | 统一配置快照（含迁移引擎 `master.migrationFaultTimeoutMs` 接线，`NetworkCore.java:104-106`） |
| `common/.../network/core/viafabric/ViaFabricCompat.java` / `ViaDecodeBridge.java` | ViaFabric 探测与 S2C 解码桥（E2 运行时冒烟日志源） |
| `scripts/runtime-smoke-test.ps1` | 单次会话脚本（含遗留 `-Phase UdpFailover`，已退役） |
| `scripts/smoke/UdpFailoverSmoke.psm1` / `runtime-smoke-test.Tests.ps1` | Nginx harness 遗留（历史，见「Nginx Failover Harness（历史，已退役）」） |
| `scripts/runtime-smoke-test-batch.ps1` | 批量经典 smoke 脚本 |
