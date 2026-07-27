# 运行时冒烟测试（Runtime Smoke Test）

Hassium 跨版本（1.20.1–1.21.11）× 多加载器（fabric / neoforge）的实跑验证流程。在 dev 环境同时启动服务端和客户端，自动连服 → 采集统计 → 断开 → 重连 → 再采集，用于发现编译通过但运行时才暴露的回归（路径错误、Mixin 失效、跨版本 API 漂移、缓存未清理等）。

## 概述

- **测试矩阵**：17 个 MC 版本 × 2 个加载器 = 34 个会话（fabric / neoforge；Forge 仅 1.20.1 / 1.20.6，由 neoforge 子项目以 `loom.platform='forge'` 覆盖）
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
| `-Phase` | 是 | — | `I`（初始轮）或 `R`（回归轮）或 `UdpFailover`（Nginx 反代+UDP failover smoke） |
| `-SessionId` | 是 | — | 会话 ID，用于日志文件命名，如 `1.20.1_fabric_I` |
| `-CleanWorld` | 否 | false | 删除服务端存档；batch 按 loader 策略决定（见下） |
| `-SmokeHost` | 否 | 空 | 客户端连服完整地址（如 `127.0.0.1:25566`）；指定后优先于 `-ServerPort` |
| `-ServerPort` | 否 | `25565` | 服务端监听端口（并行模式由 batch 脚本分配：fabric=BasePort, neoforge=BasePort+1） |
| `-DelayMs` | 否 | `10000` | 进世界后等待毫秒，再 dump 统计 |
| `-ReconnectDelayMs` | 否 | `3000` | 第一轮断开后到重连的毫秒 |
| `-ServerReadyTimeoutSec` | 否 | `160` | 服务端 `Done!` 出现超时 |
| `-ClientTimeoutSec` | 否 | `240` | 客户端退出超时（UdpFailover phase 自动至少 300） |
| `-SmokePhases` | 否 | `classic` | Java 侧阶段：`classic`（经典两轮）或 `udp-failover`（复用两轮连服，聚合六个生产 marker）。旧 `dataplane` / `all` 值仅为脚本与 JVM 参数兼容而解析，数据面 PoC 状态机已退役，不应作为验证入口 |
| `-NginxExePath` | 否 | `D:\app\nginx-1.31.3\nginx.exe` | UdpFailover phase：nginx stream 反代可执行文件路径 |
| `-ProxyPort` | 否 | `0`（→ `$ServerPort + 5`） | UdpFailover phase：nginx stream listen port；`-SmokeHost` 显式指定时优先 |
| `-DryRun` | 否 | false | UdpFailover phase：仅起 nginx + 验 listen + stop + exit；不起 server/client |
| `-InjectTcpClose` | 否 | false | UdpFailover phase：Round1 后由 nginx `-s stop` 真实关闭主控 TCP；默认走客户端模拟断连 |

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
│     │  等服务端检测玩家数 0→切 VD=8                                  │  │
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
- ROUND2（VD=8，已有缓存）：缓存命中率 >99%，OVD loaded >1100，光照缓存命中率 >95%

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
│   ├── <SessionId>_round2_VD8.txt       # 提取后的 ROUND2 统计（VD=8 + OVD 场景）
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
        "build/smoke-test/stats/1.20.1_fabric_I_round2_VD8.txt"
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
- **修复后**：`Stop-SessionJava` 只通过端口和 `<loader>\run\{client,server}` 目录定位本会话 java 进程，不影响另一会话
- 若仍出现：检查是否有其他脚本/工具调用了 `Get-Process -Name java | Stop-Process`

## 并行模式

`-Parallel` 开关启用后，同版本的 fabric + neoforge 用 `Start-Process` 同时启动，节省约一半时间。

**端口分配**：`fabric = BasePort`（默认 25565），`neoforge = BasePort + 1`（默认 25566）。用 `-BasePort` 可整体偏移。

**版本间仍串行**：并行只在同一版本的两个加载器之间；不同版本之间仍串行，避免跨版本存档冲突（高版本存档无法被低版本读取）。

**资源需求**：同时跑 4 个 JVM（2 服务端 + 2 客户端），每个 2–4G，建议至少 16G RAM。本机若内存不足，去掉 `-Parallel` 回退到串行模式。

**预编译**：并行模式下，先同步编译所有 loader（`compileJava`），避免两个并行进程同时触发编译冲突。若某 loader 预编译失败，该 loader 会话会被跳过（结果记录为 `precompile_failed`），不影响其他 loader。

**进程清理（关键）**：并行模式下，单会话结束时的清理**只杀本会话相关的 java 进程**，不会杀掉另一会话的 java：
- 服务端：通过 `Get-NetTCPConnection -LocalPort $ServerPort` 定位占用端口的 java 进程
- 客户端/兜底服务端：通过 `Get-CimInstance Win32_Process` 匹配命令行中包含 `<loader>\run\client` 或 `<loader>\run\server` 的 java 进程
- **不杀 gradle daemon**（其命令行不含 `run/server` 或 `run/client`），保留给下一版本复用

**`gradlew --stop` 策略**：并行模式下每版本结束后**不调用** `gradlew --stop`（会杀掉共享 daemon，影响下一版本预编译）；仅清理残留 Minecraft java 进程（命令行匹配 `run[/\\](server|client)` 的 java）+ sleep 3s；整个 batch 结束后统一调用一次 `gradlew --stop`。

**失败重试**：并行模式下单次失败**不重试**（`Attempts=1`），与串行模式（`MaxRetries=3`）不同。如需重试，跑完一轮后对失败的会话单独跑回归轮（`-Phase R -Versions @(...)`）。

**Job 超时**：总超时 = `ServerReadyTimeoutSec + ClientTimeoutSec + 120s` 兜底；内部已有服务端 300s + 客户端 600s 超时，正常情况下不会触发外层超时。

**单 loader 模式**：若 `-Loaders fabric` 只指定一个加载器，`-Parallel` 仍生效但无并行意义，逻辑保持统一。

## `udp-failover` Phase Markers

`-Phase UdpFailover` 未显式传 `-SmokePhases` 时会自动启用 `udp-failover`。该阶段驱动 UDP 控制 Failover 端到端冒烟，跨进程日志必须包含下列 marker 才算 PASS；`FAILOVER_TERMINAL_OK` 仅在候选耗尽子用例出现：

| Marker | 含义 |
|--------|------|
| `UDP_BIND_OK` | 客户端 BindRequest 被服务端某 endpoint 接受，KCP `ReliableDatagramSession` 进入 ESTABLISHED |
| `UDP_WRR_OK` | `UdpBulkRouter` 成功送一帧 bulk → Primary/UDP 分流计数累加 |
| `FAILOVER_PERMIT_OK` | 服务端 `ControlFailoverHandler.requestFailover` 返回 `PERMITTED`，并通过 KCP `TYPE_FAILOVER_PERMIT` 回执客户端；客户端 epoch 匹配 + 未过期 |
| `FAILOVER_RECONNECT_OK` | 客户端 `ControlReconnectOrchestrator.onPrimaryDisconnected` 后 launch 候选 B，新一轮 S2C 握手成功 + `ClientRecoveryState.markRecovered()` 退出恢复态 |
| `CACHE_RESUME_HIT` | 重连后 ChunkHashS2C 触发至少一次缓存命中（`cacheHitFullChunkBytes > 0`）；恢复期未开 final disconnect UI |
| `FAILOVER_TERMINAL_OK` | 所有候选耗尽 → `ControlReconnectOrchestrator.performTerminalFinalization` → `ClientLifecycleHelper.finalizeDisconnectIfTerminal` 一次性 terminal 关闭 |

`network.dataPlane.enabled=false` 子用例：必须 **零** UDP listener / Bind / permit 标记，与 TCP revert 路径对齐；`DataPlaneEnabledGuard` 在单测中已覆盖。

## Nginx Failover Harness（UdpFailover phase）

`-Phase UdpFailover` 在 server ready 之前先启动一个本会话专属 nginx 反代，把 TCP 主控端点经 `stream` 块代理到真实 server `$ServerPort`，UDP 数据面仍直连。harness 通过 nginx 干预主控 TCP 的关闭时机，验证 production `ControlReconnectOrchestrator` + `ControlEndpointManager` 在真实 TCP 断链下的恢复链路。

### 拓扑

```
client ──TCP 25570── nginx stream proxy ──TCP 25565── server (primary)
client ──UDP uplink 25571 ────────────────────────────→ server (DataPlaneUdpServer)
```

### 关键参数

| 参数 | 默认 | 说明 |
|------|------|------|
| `-NginxExePath` | `D:\app\nginx-1.31.3\nginx.exe` | Nginx stream 反代可执行文件路径，覆盖仓库默认 |
| `-ProxyPort` | `0` (→ `$ServerPort + 5`，如 `25570`) | Nginx stream listen port；client 经此端口连主控 |
| `-DryRun` | false | 仅起 nginx + 验 listen + stop + exit；不起 server/client；用于 Pester 验证 harness 启停序列 |
| `-InjectTcpClose` | false | Round1 后由 nginx `-s stop` 真实关闭 client 已建立的 stream 连接，触发 channelInactive→orchestrator |

### 三种运行场景

1. **内部模拟断连（默认，`-InjectTcpClose` 缺省）**：client 经 nginx 连主控；`ClientSmokeTest` 状态机在 ROUND1 后主动 `conn.disconnect()` 触发 vanilla channelInactive → `ControlReconnectOrchestrator.onPrimaryDisconnected` → `FabricControlReconnectLauncher.startConnecting` 同一 nginx 端口 reconnect → ROUND2。Marker 全由 production path 触发；harness 不主动断链。这是 `mono-JVM 跑 server+client 同进程` 时唯一可行设计（harness 无法在不杀整个 JVM 的前提下真断 socket）。

2. **真实 TCP close 注入（`-InjectTcpClose`，manual 一次性验证）**：Round1 客户端进世界后 harness 等 `DelayMs × 1.2 + 5s` 稳定窗口，再 `nginx -s stop` 强制关闭 stream listen 与已建立的 client→server proxy 连接 → client Netty channelInactive 真触发 → orchestrator 开始恢复 → harness 立即重启 nginx + 等 listen 重开 → client `startConnecting` 重连 25570 通过 nginx 代理回到 server。`HassiumSmokeTest:UDP_FAILOVER_HARNESS primaryCloseInjected` / `nginxQuit` / `primaryRestored` 三个 harness timeline 事件按时间顺序写入 `build/smoke-test/nginx/<SessionId>/harness_timeline.log`，便于 reviewer 关联 production markers 与断链时机。

3. **DryRun（`-DryRun`）**：仅起 nginx + 验 listen + 写 `nginxStarted` timeline + stop + exit 0；不起 server/client/JVM。用于 Pester 验证 harness 启停序列与 `Get-UdpFailoverHarnessTimeline` 解析正确，不依赖真实 server 与 nginx 在线验证。

### Helper 模块与 Pester

- `scripts/smoke/UdpFailoverSmoke.psm1` 暴露三个纯函数：`New-UdpFailoverNginxConfig` / `Get-UdpFailoverMarkers` / `Get-UdpFailoverHarnessTimeline`；harness 与 Pester 同源 shared。
- `scripts/smoke/runtime-smoke-test.Tests.ps1` Pester v3.4 兼容（用 `Should Be`/`Should Match`，禁用 `Should -BeTrue`）：覆盖 conf 生成 / 6-marker 聚合 / harness timeline 解析 / DryRun / InjectTcpClose 时序不变量。**运行方式**：`powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-Pester -Path scripts/smoke/runtime-smoke-test.Tests.ps1 -PassThru"`（Pester 3.4.0 不支持 `-Output Detailed`，用 `-PassThru` 取计数）。

### 清理与 zombie 防御

`Stop-FailoverNginxProxy` 先 `nginx -s stop` 优雅停 master，500ms 后再 `Stop-Process -Name nginx -Force` 兜底（防 master 已死、worker 残留）。本 harness 启的 nginx 子进程会被正常清理；**残留非本会话启的 nginx（如系统 service）不会**被混杀（脚本不主动杀非 `nginx -s stop` 提供的 PID；当前实现是按名全杀，多用户共享主机需谨慎，单用户 dev 机安全）。

### 退出码

| 退出码 | 含义 |
|--------|------|
| `0` | PASS（含 DryRun）/ client ROUND2 已断开→重连成功 + markers 全 fire |
| `2` | FAIL：markers 缺失或 client 非 0 退出 |
| `3` | server_not_ready |
| `4` | `NginxExePath` 不存在（UdpFailover phase 必备） |
| `5` | nginx listen 未就绪 |

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
| `hassium.smokePhases` | `classic` | Java 侧阶段选择（`classic` / `udp-failover`，可逗号组合）；旧 `dataplane` / `all` 只为兼容旧调用，服务端忽略已退役的 PoC 状态机 |

## 相关代码

| 路径 | 作用 |
|------|------|
| `common/src/main/java/io/github/limuqy/mc/hassium/client/ClientSmokeTest.java` | 客户端两轮状态机、跨版本反射重连、统计校验；`udp-failover` 复用该断开→重连周期 |
| `common/src/main/java/io/github/limuqy/mc/hassium/server/ServerSmokeTest.java` | 服务端视距切换；识别 `udp-failover` marker 聚合场景；旧裸 TCP PoC dataplane phase 已退役 |
| `common/.../network/dataplane/DataPlaneUdpServer.java` / `ReliableDatagramSession.java` / `DataPlaneClientBundle.java` / `ControlReconnectOrchestrator.java` | UDP/KCP listener、按权重 bulk 路由、客户端 Bind/dispatch 与 TCP 主控恢复 |
| `common/.../network/dataplane/ControlFailoverHandler.java` / `ClientRecoveryState.java` | permit 签发、恢复窗口和候选耗尽时的一次性终态清理 |
| `common/.../config/HassiumConfigService.java` | 提供统一的 UDP listener、控制候选和 failover 时限配置快照 |
| `scripts/smoke/UdpFailoverSmoke.psm1` | Nginx stream 配置、六 marker 聚合和 harness timeline 解析 |
| `scripts/smoke/runtime-smoke-test.Tests.ps1` | 上述 PowerShell helper 的 Pester 覆盖 |
| `scripts/runtime-smoke-test.ps1` | 单次会话脚本 |
| `scripts/runtime-smoke-test-batch.ps1` | 批量经典 smoke 脚本 |
