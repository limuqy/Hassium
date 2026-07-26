# UDP Data Plane + TCP Control Failover Rollout Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement remaining tasks task-by-task.
>
> **Supersedes** the PoC-era `2026-07-26-multi-channel-dataplane-rollout.md` (main repo, untracked) — the production path is UDP/KCP, not the TCP multi-channel PoC matrix; the original four rollout tasks are marked PoC-specific and superseded in §6.

**Goal:** 把 1.20.1 Fabric 已落地的 **authenticated KCP-over-UDP 数据面 + TCP 控制 failover**（commit `22c9c3f`，Task 1–9）收敛到生产化：cross-process 冒烟 Phase 接入、TCP dataplane 遗留类清理、`hassium.toml` 正式配置、NeoForge 同构、九段锚点跨版本编译/冒烟。

**Architecture:** TCP 控制面（原版 login + Play Connection，master）+ UDP 数据面（按 advertised `(host,port)` 各绑一个 KCP `ReliableDatagramSession`）；master 硬断或 stalled + UDP 健康 → `ControlReconnectOrchestrator` 在 60s 窗口内按 priority 候选重连；恢复期保留 disk cache / executor（`ClientRecoveryState` gate）；候选耗尽仅一次性 terminal finalize。详见 [`docs/architecture.md`](../../architecture.md) §9.5。

**Tech Stack:** Java 17, Netty, KCP-Netty, Manifold, JUnit 5, PowerShell smoke。

## Global Constraints

- `common` 模块不 import Fabric / Forge / NeoForge / Minecraft API（除既有 `FriendlyByteBuf` / `SectionDeltaS2CPacket` 等已接受点）。
- 仅 `ReliableDatagramSession` 接触 KCP vendor API；router / registry / handshake codec / loader / Minecraft hook 不得引用 KCP-vendor 类。
- TCP 是唯一原生控制路径（login + play）；备份 TCP endpoint 为冷候选，**不并发** 第二条 Minecraft Play Connection。
- `tryRouteBulk(playerUUID, type, payload)`：`true` = 数据面已消费/丢弃；`false` = Primary 发 payload。Adapter 契约不得变。
- `MixinConnection` 不做整包改路；`ServerChunkPushManager` 业务逻辑不动。
- `LogType` 是嵌套枚举 `DebugLogger.LogType`。
- Windows host：构建 `cmd /c "gradlew.bat --no-daemon <module>:<task> -Pmc_ver=1.20.1 --console=plain"`，跑完必有 `taskkill /F /IM java.exe`。
- 不 bump `protocolVersion`；握手尾部 append + `isReadable` 协同演进。
- 禁止关功能换绿；禁止破坏 1.20.1 fabric 已验证路径。

## 1. 当前状态（HEAD `bed48c6`）

### 1.1 已落地（Tasks 1–10 docs，均已 commit）

| Task | Commit | 内容 | 测试 |
|---|---|---|---|
| 1 | `c944428` | Reliable UDP 依赖 + wire contract（vendored KCP-Netty 最小适配层） | — |
| 2 | `ec9fde1` + 修复 `cb2c67b` | 有界 `ReliableDatagramSession`（KCP output buffer 泄漏 F1 + oversized reassembly F2） | — |
| 3 | `866694c` | UDP endpoint 绑定替代 TCP 数据面 server 生命周期 | — |
| 4 | `d71c643` | health-aware UDP WRR bulk 路由（`UdpBulkRouter`） | — |
| 5 | `84bb17d` | `UdpDataPlaneHandshakeTail` + `DataPlaneClientBundle` 重写 + `DataPlaneClientLifecycle` 门面 + Fabric 接线 | 8 ✓ |
| 6 | `b9ff288` | controlled TCP master failover over UDP（`ControlFailoverHandler` / `FailoverFrameCodec` / `DataPlaneSessionRegistry` / `DataPlaneUdpServer` / `ReliableDatagramSession` / `MixinServerGamePacketListenerImpl` / `FabricNetworkManager`） | 8+2+1 ✓ |
| 7 | `27a3678` | preserve client cache during control recovery（`ClientRecoveryState` NONE→RECOVERING→RECOVERED→TERMINAL，`ControlEndpointManager` / `ClientLifecycleHelper.finalizeDisconnectIfTerminal()` / `MixinMinecraft` 三处 TAIL 替换） | 8 ✓ |
| 8 | `27a3678` | deliver section deltas over UDP（`DataPlaneClientBundle.safeDispatch` TYPE_BULK_SECTION_DELTA 分支 + `SectionDeltaDispatcher` seam；chunk sender `pseudoPlayerId()`→`player.getUUID()`） | 2 ✓ |
| 9 | `22c9c3f` | recover Fabric control connection through backup endpoint（`ControlReconnectOrchestrator` 自有 recovery 字段 / `ControlReconnectLauncher` seam / `FabricControlReconnectLauncher` 包 1.20.1 `ConnectScreen.startConnecting` / `HassiumClientMod` 重写 / `FabricNetworkManager` 两 `#if MC_VER` 分支接候选下发） | 4 ✓ |
| 10 (partial) | `bed48c6` | **运维文档**（4 篇 docs 更新：architecture §9.5 / chunk-cache §9 / runtime-smoke-test markers / version-segments 1.20.1 网络） | — |

### 1.2 已通过验证

- `common:test --tests io.github.limuqy.mc.hassium.network.dataplane.*` → 全 11 个 gradle task `BUILD SUCCESSFUL`（11/11 测试绿）
- `common:compileJava` + `fabric:compileJava` GREEN under `-Pmc_ver=1.20.1`
- `common:test` 全量（baseline 184）：7 个预存失败（`DeltaMergeTest`/`ResourceKey` 5 + `HassiumMetricsImplTest.resetClearsClientDisplayMetrics` + `ChunkDiskCodecTest` + `CompressionServiceDictionaryTest`）— 非本计划引入，已接受为 baseline

### 1.3 Commit 链

`c944428` → `ec9fde1` → `cb2c67b` → `866694c` → `d71c643` → `84bb17d` → `b9ff288` → `27a3678` → `22c9c3f` → `bed48c6`

## 2. 待办（Task 10b followup，全部 blocked）

### 2.1 退役 `ServerSmokeTest.runDataplane` phase

**为什么必须先做**：`ServerSmokeTest.java` line 195 / 202 / 211 / 232 仍调 `DataPlaneServer.killDataChannelByPortIdx` 与 `getBundle<PlayerChannelBundle>`——是 PoC 多通道 phase 旧链路。删 `BulkRouter` / `PlayerChannelBundle` 等遗留类前必须退役该 phase，否则 `ServerSmokeTest` 不可编译。

**步骤**（plan §1064 red/green）：

1. 在 `DataPlaneTransportCutoverTest.runtimeTransportNamesForTest()` 加红色断言：`DataPlaneUdpServer` 运行时 transport 类型集合 **不含** `NioServerSocketChannel`（PoC 的 TCP server socket）。
2. 删除 `ServerSmokeTest.runDataplane`（保留 `runClassic`）；同步删 `SmokePhases` 中 `dataplane` 取值及 `dataplane` 状态机（11 个 DP state 自驱 kill/mode 切换断言）。
3. 绿色：`DataPlaneTransportCutoverTest` PASS；`common:test` 不破。

**验收**：

- `common:compileJava` + `common:test` GREEN。
- `SmokePhases` 仅包含 `classic`（+ 后续 §2.4 的 `udp-failover`）。

### 2.2 TCP 数据面遗留类清理

**依赖**：§2.1 完成（`ServerSmokeTest.dataplane` phase 退役）。

**删除清单**（识别自 `common/src/main` grep）：

- `network/dataplane/BulkRouter.java`（老 PoC TCP WRR；`UdpBulkRouter` 已替代）
- `network/dataplane/DataPlaneCodec.java`（老 PoC TCP 编解码）
- `network/dataplane/VarIntLengthFrameSplitter.java`（老 PoC TCP splitter）
- `network/dataplane/PlayerChannelBundle.java`（老 PoC TCP bundle；UDP 改 `DataPlaneSessionRegistry`）
- `network/dataplane/PlayerChannel.java`（老 PoC TCP channel 包装）

**保留**：

- `DataPlaneServer.java` 作为 `tryRouteBulk(UUID,int,byte[])` façade，forward 到 `DataPlaneUdpServer.tryRouteBulk`（已是 §Task3 状态）。

**操作**：在 §2.1 退役 dataplane phase 之后，逐文件删；每删一类跑一次 `common:compileJava`，确认无残留引用；最后 `common:test` 全绿。

**验收**：

- 删除后 `common:compileJava` + `common:test` + `fabric:compileJava` 三者 GREEN；`ServerSmokeTest` 不挂。
- `DataPlaneTransportCutoverTest` 全绿。

### 2.3 Cross-process smoke harness（`-Phase UdpFailover` + 6 markers）

**为什么独立做**：跨进程时序：客户端 vanilla DisconnectedScreen + DISCONNECT callback 驱动 orchestrator forward；需 mock 主 TCP close + UDP lease 保留行为；服务端与客户端日志跨进程聚合 6 个 marker。是单独 smoke harness 工程，与 Task 9 production code 不耦合。

**markers**（plan §1020，已在 runtime-smoke-test.md 标注）：

| Marker | 含义 |
|---|---|
| `UDP_BIND_OK` | 客户端 BindRequest 被某 endpoint 接受，KCP `ReliableDatagramSession` 进入 ESTABLISHED |
| `UDP_WRR_OK` | `UdpBulkRouter` 成功送一帧 bulk → 分流计数累加 |
| `FAILOVER_PERMIT_OK` | 服务端 `ControlFailoverHandler.requestFailover` 返回 `PERMITTED`，KCP `TYPE_FAILOVER_PERMIT` 回执，客户端 epoch 匹配 + 未过期 |
| `FAILOVER_RECONNECT_OK` | 客户端 `onPrimaryDisconnected` launch 候选 B，新一轮握手成功 + `ClientRecoveryState.markRecovered()` 退出恢复态 |
| `CACHE_RESUME_HIT` | 重连后 ChunkHashS2C 触发至少一次缓存命中（`cacheHitFullChunkBytes > 0`）；恢复期未开 final disconnect UI |
| `FAILOVER_TERMINAL_OK` | 所有候选耗尽 → `performTerminalFinalization` → `finalizeDisconnectIfTerminal` 一次性 terminal 关闭 |

**步骤**：

1. `scripts/runtime-smoke-test.ps1`：`SmokePhases` validation 加 `udp-failover` 取值。
2. `ServerSmokeTest`：新增 `runUdpFailover` phase —— mock 主 TCP close（或注入 `channelInactive`）+ UDP lease 保留；按 §1020 时序注入 6 个 marker log。
3. `ClientSmokeTest`：恢复 après 重连候选 B；客户端 marker 与服务端按时间序列聚合，全 6 标记出现才算 PASS。
4. disabled sub-case：`network.dataPlane.enabled=false` —— 必须出现 **零** UDP listener/Bind/permit 标记，`DataPlaneEnabledGuard` 单测已覆盖（regression guard）。
5. `-SmokePhases udp-failover` 跑通一次端到端。

**验收**：

- `-Phase` `udp-failover` 跑一次 PASS（全部 6 marker 出现 + 已知 baseline 不退化）。
- `network.dataPlane.enabled=false` sub-case PASS。
- 不破坏 `classic` 冒烟（与现有 dataplane phase 退役后协同工作）。

### 2.4 `hassium.toml` 生产化 + 多版本 + NeoForge

**待办**（与原 PoC rollout 4 task 主要对应点重定向）：

1. `network.dataPlane.udpEndpoints` / token lifecycle：当前由 `DataPlanePoCConfig` 临时驱动，迁入 `HassiumConfigService` + Fabric `HassiumTomlConfigIO`（与 §2.2 完成之后做，避免配置类干扰清理）。
   - 字段：`enabled` / `udpEndpoints (List<host:port>)` / `controlStallMs` / `failoverExpiryMs` / `recoveryWindowMs`。
   - 默认值对齐 §1.2 spec（`controlStallMs=6000`，`failoverExpiryMs=30000`，`recoveryWindowMs=60000`）。
2. NeoForge 同构：`ChunkSender` / handshake tail / `HassiumClientMod` 等效替换（已有 Fabric 分支做参照）。
3. 九段锚点跨版本编译 + DataplanePhase 冒烟：1.20.1 → 1.20.5 → 1.21.11（运行时验证锚点）；其余锚点编译 + 短冒烟为主。
   - 注意 `ConnectScreen.startConnecting` 签名随版本变（1.20.1 是 5 arg，后期变枚举）；`ServerData` 构造 third arg 1.20.1 是 boolean，后期变 enum。每段做好 `#if MC_VER` 桥。
4. Forge **不碰**（已与多通道 PoC rollout 对齐：Forge 仅 1.20.1/1.20.6；本计划数据面生产化聚焦 Fabric + NeoForge）。

**验收**：

- `hassium.toml` 含 `network.dataPlane.*` 字段；手动改值能切换 UDP listener 数量。
- NeoForge 1.20.1+ 编译 + 冒烟 PASS。
- 九段编译绿、`udp-failover` 选定 anchor 跑通 PASS。

## 3. Acceptance（rollout 整体）

- §2.1 + §2.2：`ServerSmokeTest` 仅剩 `classic`（+§2.3 后 `udp-failover`）phase；TCP 遗留类全删；3 个 compileJava + 目标准测全绿。
- §2.3：cross-process `-SmokePhases udp-failover` 跑通一次；6 marker 全现；disabled guard 路径绿。
- §2.4：`hassium.toml` 正式字段落地；NeoForge 同构；1.20.1/1.20.5/1.21.11 anchor 运行时 PASS。
- 全程不破坏 baseline `common:test`（除 7 个预存失败）；不 bump 协议版本；1.20.1 fabric 原路径保持绿。

## 4. 关键约定与参照

- **`ControlReconnectOrchestrator` 构造签名**：`(ControlReconnectLauncher, List<ControlEndpoint> bootstrap, List<ControlEndpoint> advertised)`；`forTest(launcher, candidates)` 是 package-private 工厂。
- **`ControlReconnectOrchestrator` 字段独立性**：恢复 `recovering` / `connectionEpoch` / `current` / `terminalFinalizations` 自有；**不**复用 `ClientRecoveryState` 单例（该单例是 MixinMinecraft / ClientLifecycleHelper gate）。
- **launcher onFailure 是 view-only**：不反射重复 login 协议；vanilla `DisconnectedScreen` + `DISCONNECT` event 驱动 orchestrator 前进。
- **`DataPlaneFrame` 帧类型**：`TYPE_BULK_COMPRESSED_CHUNK=3` / `TYPE_BULK_SECTION_DELTA=4` / `TYPE_FAILOVER_REQUEST=8` / `TYPE_FAILOVER_PERMIT=9`。
- **`DataPlanePoCConfig.ENABLED`** 默认 `true`；生产化 §2.4 后由 `hassium.toml` 接管。
- **`SectionDeltaS2CPacket`** 是 record：`(String dimension, List<DeltaEntry> entries, List<SkippedChunk> skipped)`，2-arg 便利构造；`encode/decode(FriendlyByteBuf)`；`DeltaEntry(int chunkX, int chunkZ, List<SectionData> changedSections, List<BlockEntityData> blockEntities)`。
- **构建/测试命令**（cwd `/d/project/MC/Hassium/.worktrees/udp-failover`）：
  - `cmd /c "gradlew.bat --no-daemon <module>:compileJava -Pmc_ver=1.20.1 --console=plain"`
  - `cmd /c "gradlew.bat --no-daemon <module>:test --tests <FQN> --rerun-tasks -Pmc_ver=1.20.1 --console=plain"`
  - 跑完 `taskkill /F /IM java.exe`（PID 不可 terminate 是已知现象，下次构建无害）。
- **不可触碰**：主仓 master working tree 上有独立本地修改，worktree 分支不得回写主仓。

## 5. Spec / Plan 引用

- Spec: [`docs/superpowers/specs/2026-07-26-udp-dataplane-control-failover-design.md`](../specs/2026-07-26-udp-dataplane-control-failover-design.md)
- Failover plan（Tasks 1-9 原始）: [`docs/superpowers/plans/2026-07-26-udp-dataplane-control-failover.md`](2026-07-26-udp-dataplane-control-failover.md)
- SDD ledger: [`.superpowers/sdd/2026-07-26-udp-dataplane-control-failover/progress.md`](../../.superpowers/sdd/2026-07-26-udp-dataplane-control-failover/progress.md)
- 运维 summary: [`docs/architecture.md`](../../architecture.md) §9.5
- 冒烟 markers: [`docs/runtime-smoke-test.md`](../../runtime-smoke-test.md) «`udp-failover` Phase Markers»

## 6. 与原 `multi-channel-dataplane-rollout` 关系

原 `2026-07-26-multi-channel-dataplane-rollout.md`（主仓 untracked，不提交）描述的 4 个 rollout task —— common sessionToken → fabric 握手 → neoforge 同构 → 全矩阵冒烟 —— 是基于 **PoC TCP 多通道**矩阵设定。实际生产路径已切到 **authenticated KCP-over-UDP + TCP 控制 failover**（commit `c944428`..`bed48c6`），原 4 task 的内容被本 plan §1.1（Tasks 5/9）与 §2.4 取代：

- 原 Task 1（common sessionToken + ClientBundle API + Lifecycle + HandshakeTail）→ 已 §1.1 Task 5 完成。
- 原 Task 2（fabric 握手尾部 + JOIN timing）→ 已 §1.1 Task 5 + Task 9 完成。
- 原 Task 3（neoforge ChunkSender + 握手各段 + client lifecycle）→ 部分留为 §2.4.2 followup。
- 原 Task 4（compile anchors + DataplanePhase 全矩阵）→ 留为 §2.4.3 followup（与 cross-process smoke §2.3 + 多版本 §2.4 协同）。

原 rollout plan 不再是真实行进方向；以本 plan 为准。
