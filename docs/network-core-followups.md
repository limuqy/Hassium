# Network Core 未达项交接清单（收尾版）

> 来源：T8/T9/T10/T11/T12 任务交接记录。网络核心 2.0.0 架构主体已落地并提交
> （`eb05ba5..11f3470`）；本文为后续波逐项核销后的最终状态（T11 收尾，2026-08-10）。
> 编号稳定可引用：A1-A8 / B1-B5 / C1-C2 / D1-D3 / E1-E2。
> 状态图例：✅ 已完成（代码/决策落地）｜✅ 已验证（真实双端或测试实证）
> ｜⚠️ 范围外（明确不属本波，注明依据）｜🔲 待实测（已实现，实测另行安排）。

## A. 运行时正确性

| # | 项 | 状态 | 最终结论 | 实现位置 / 验证证据 |
|---|---|---|---|---|
| A1 | 壳 keep-alive 饥饿 | ✅ 已验证 | keep-alive 走 vanilla TCP 壳完全正常，**无需补 S2C 应答镜像**；真实双端驻留 510s（ROUND1 340s + ROUND2 170s）0 踢线 | 无代码改动；证据：`work/RealE2EVerification-TASK.md`（T9v4 段4） |
| A2 | 主控 CONFIG_S2C 丢弃 | ✅ 已验证 | 双路径（vanilla TCP config 主通道 + 帧通道，后者仅登录桥/续流物化路径启用）6 会话 0 registry 重复/冲突/崩溃 → 丢弃正确，**无需补转发** | 证据：`work/RealE2EVerification-TASK.md`（T9v4 段2/段3 A2 观察）；s2c=0 语义裁决见该文 0. 节 |
| A3 | custom-payload 直发 | ✅ 已完成 | `ClientMetadataHandler` 无 `.send(`/sendPacket/channel.write 直发点（467 行全扫 + T9v4 复核 grep 0 命中）→ 已收口，无待办 | 复核：`work/RealE2EVerification-TASK.md`（T9v4 A3 观察） |
| A4 | pending-attach TTL | ✅ 已完成 | `PENDING_ATTACH_TTL_MS=10_000L` 兜底在位，超时 warn 后走登录桥/重连兜底；6 会话 + 驻留全程 0 告警 → **保持告警语义** | `server/GatewayPlayerBridge.java`（L97-98 常量、L123-126 告警）；证据：`work/RealE2EVerification-TASK.md`（T9v4 A4 观察） |
| A5 | 1.20.2–1.20.4 非锚点段 | ⚠️ 范围外 | 10 处历史遗留编译错误已修复，**10 版本编译全绿**（编译保证达成）；该段运行时冒烟明确不属本波，归发布前另行安排 | T8b（BoundaryCompileFix）；证据：任务交接 `BoundaryCompileFix` / T8 记录 |
| A6 | 续流玩家占位名 + 空数据 | ✅ 已完成 | `PlayerDataStorage` 磁盘加载链路落地，续流 resume data loaded（背包/末影箱/位置从 playerdata 恢复）；固定用户名 HassiumDev 后命中 UUID 676f0bd1-8e18-374c-b04d-a01048faeb67，实证加载 | `server/PlayerDataStorage.java`、`server/GatewayPlayerBridge.java`（L544-549 resume data loaded 日志）；证据：`work/T10-MigrationDrill-TASK.md`（V5，3 版本 × 双路径） |
| A7 | lightComputeSupported 未进帧握手 | ✅ 已完成 | 握手帧 C2S 追加第 5 字段（append-only，旧端忽略尾字节），Bridge 读取 → 剥光 gate 按客户端能力；剥光恢复待真实光照场景观察 | `network/HandshakeStateTail.java`（C2S 记录 L21-23、写 L64-65、读 L99-105）、`network/core/NetworkCore.java`（L1037-1041 值源）、`server/ServerChunkPushManager.java`（L108-112 / L1788-1791 剥光协商）、`server/GatewayPlayerBridge.java`（L585-587） |
| A8 | ZSTD 对称时序 | ✅ 已验证 | 平台 setZstd 与客户端配置同源 + CompressionReady ACK 已在；6 会话真实双端跑通（T9 默认配置）；深度压测留作可选增强，非阻塞项 | 修复：`network/ZstdContextDecoder.java`（frameAware）；证据：`work/RealE2EVerification-TASK.md`（T9v4 6/6 PASS） |

## B. 迁移引擎（L1 交付，运维面全链）

| # | 项 | 状态 | 最终结论 | 实现位置 / 验证证据 |
|---|---|---|---|---|
| B1 | 端点通告 | ✅ 已完成 | 主控握手 udpTail.controlEndpoints → `MigrationEngine.setTargetEndpoints`（空列表不覆盖 = 编程注入兜底）；单测 `handshakeAdvertisementFillsTargetEndpoints` | `network/core/NetworkCore.java`（L458-468）、`network/core/migration/MigrationEngine.java`（L140-144）、`dataplane/UdpDataPlaneHandshakeTail.java`、`dataplane/DataPlaneHandshakeAdvertisement.java`；单测：`NetworkCoreMigrationTest#handshakeAdvertisementFillsTargetEndpoints` |
| B2 | 参数全链接线 | ✅ 已完成 | `master.migration*` 键族 7 键全链（CLIENT 6：minTps/maxLoadAverage/maintenanceWindow/heartbeatIntervalMs/idleWindowMs/silentTimeoutMs + SERVER 1：prewarmTtlMs；faultTimeoutMs 为 legacy 回退语义）；ConfigSchema → 快照 → toml 往返全通 | `config/ConfigSchema.java`（L68-77）、`config/ConfigSnapshotAdapter.java`、`config/FabricTomlConfigIO.java`、`network/core/migration/MigrationPolicyConfig.java`、`MigrationPolicy#resolvedSilentTimeoutMs` |
| B3 | 预热会话 TTL | ✅ 已完成 | `GatewayPlayerRegistry.sweepExpired` + `GatewayPlayerBridge.tick` 每 tick 集成（TTL 读 `master.migrationPrewarmTtlMs`）；7 单测；T10 演练三端 0 误伤（session removed 为正常注销） | `server/GatewayPlayerRegistry.java`（L120）、`server/GatewayPlayerBridge.java`（L433-441）、`server/GatewayPlayerSession.java`（L67-70 TTL 起算点）；单测：`GatewayPlayerRegistryTtlTest`（7 用例）；证据：`work/T10-MigrationDrill-TASK.md`（V7） |
| B4 | 迁移命令注册 | ✅ 已完成 | `/hassium migrate`（list / migrate <host:port> / status）三端注册且**仅开发环境**（正式包不暴露；T10 演练真实命令路径触发） | `command/HassiumCommandHandler.java`、`fabric/.../FabricHassiumCommand.java`、`forge/.../ForgeHassiumCommand.java`、`neoforge/.../NeoForgeHassiumClientCommand.java`；证据：`work/T10-MigrationDrill-TASK.md`（会话 A/B + 1.20.1/1.21.11） |
| B5 | UDP 数据面迁移 | ✅ 已完成（决策） | 决策已文档化：帧连接即控制连接（udpTail 恒 `udpSupported=false`、epoch=0），beginControlConnection 不参与；**UDP 会话迁移归后续波**，T7 的「epoch 并入 validator」在网关形态下不适用（不触发） | `network/core/migration/MigrationEngine.java`（javadoc「UDP 会话迁移决策（记录）」L47-50） |

## C. 登录/会话语义

| # | 项 | 状态 | 最终结论 | 实现位置 / 验证证据 |
|---|---|---|---|---|
| C1 | 1.20.1 双 ServerPlayer | ✅ 已验证 | T9 两会话 0 冲突/覆盖（"UUID of added entity already exists: Bat" 为影子端实体同步 vanilla WARN，无关）；附着逻辑真实联调通过 | 证据：`work/RealE2EVerification-TASK.md`（T9v4 C1 观察）+ `work/T10-MigrationDrill-TASK.md`（1.20.1 迁移 PASS） |
| C2 | config 中继 1.20.2–1.20.4 | ⚠️ 范围外 | 该段 CONFIG 帧路径仅编译级保证（随 A5 一并达成 10 版本编译全绿）；运行时验证随 A5 归发布前冒烟 | T8b（BoundaryCompileFix）；证据：任务交接 `BoundaryCompileFix` / T8 记录 |

## D. ViaFabric 兼容（桥已落地；实测用户拍板不属本波）

| # | 项 | 状态 | 最终结论 | 实现位置 / 验证证据 |
|---|---|---|---|---|
| D1 | 主控编码侧跨版本 | 🔲 待实测 | 桥已落地（`translateBytes` 已暴露）；ViaVersion 5.x 实测另行安排 | 任务交接 ViaFabric 桥实现；E1 记录 |
| D2 | C2S 方向转换 | 🔲 待实测 | via-encoder 同构未实现（仅 S2C decode 桥）；按 D1 实测结果补 | 同上 |
| D3 | ZSTD×ViaFabric 顺序 | 🔲 待实测 | 压缩与协议转换先后未定；实测另行安排 | 同上 |

## E. 验证缺口

| # | 项 | 状态 | 最终结论 | 实现位置 / 验证证据 |
|---|---|---|---|---|
| E1 | 真实双端联调 | ✅ 已完成 | fabric+neoforge × 1.20.1/1.21.1/1.21.11 = **6 会话全 PASS + GatewayGatePass=true**；双主控迁移演练 3 端 PASS（resumeAccepted=true、世界不重载、s2c>0、C2S 续增、A6 数据加载、N1 位置回退、B3 无误伤） | `scripts/runtime-smoke-test.ps1`（gate=两轮 ACTIVE 且 c2s>0，T9v3 放宽裁决）；证据：`work/RealE2EVerification-TASK.md`（T9v4 6/6 表）+ `work/T10-MigrationDrill-TASK.md`（V1-V8 全过） |
| E2 | ViaFabric 运行时冒烟 | 🔲 待实测 | 桥已落地，**不实测**（用户拍板）：装 → `ViaFabric detected + bridge installed` 日志；不装 → 无桥日志。验收口径已定，实测另行安排 | 任务交接 ViaFabric 桥实现 |

---

## 本轮实现记录（T8–T11 追加需求 + 修复）

### 新需求（T9/T10 追加）
- **N1 C2S 丢弃 + 位置回退** ✅
  - C2S 三分类丢弃：ACTIVE+入站静默 → 丢弃（`MigrationEngine.isInboundSilent`，与故障判定同源同参）；CONNECTING/HANDSHAKING → 原版直连兜底；IDLE/MIGRATING → 已消费丢弃。实现：`network/core/NetworkCore.java`（routeC2S L712-752）、`network/core/migration/MigrationEngine.java`（L305-311）。
  - 位置回退：immediate 迁移路径断线窗口内客户端预测移动，迁移完成回退到断线快照（`MIGRATE_POS_BEFORE`），T10 实证 rollback -> (-22.5, 111.7, 694.6)。证据：`work/T10-MigrationDrill-TASK.md`（会话 C V6）。
- **N2 失效识别 ≤15s** ✅
  - `master.migrationSilentTimeoutMs` 默认 10000ms（`migrationSilentTimeoutMs` 显式配置优先，未配置回退 `master.migrationFaultTimeoutMs` 语义），+ Netty 读超时 → fault 映射（onFault → 迁移而非踢下线）。实现：`config/ConfigSchema.java`（L77）、`config/HassiumConfig.java`（L305 默认）、`network/core/migration/MigrationPolicy.java`（resolvedSilentTimeoutMs L84-86）、`MigrationEngine.java`（L313-323 enableReadTimeout）。

### Root cause 修复（T9/T10 定位）
- **C8 配置终包兜底**：NetworkCore routeC2S 顶部 FinishConfiguration 兜底（修复前 UnsupportedOperationException → 断连）；证据：`work/RealE2EVerification-TASK.md`（0. 节根因修复 + diag6/diag7 决定性管线证据）。

### 额外修复清单
- `ZstdContextDecoder` frameAware 模式（网关管道专用）：明文单元按 ControlFrameCodec 帧边界消费，修粘包下 `invalid control frame length: 0`；半包整体回退。`network/ZstdContextDecoder.java`（L26-31、L93-97）。
- `GatewayConnectionAccessor` 接口（新）：fabric Knot 运行时禁止直接引用 mixin 类（T10 首次真实续流物化崩溃根因），mixin 将接口挂到 Connection，业务代码 cast 接口。`server/GatewayConnectionAccessor.java`、`mixin/MixinConnectionGatewayServer.java`、`server/GatewayPlayerBridge.java`（L199-201 等）。
- T8b 10 处 1.20.2–1.20.4 边界编译错误修复（10 版本编译全绿）。
- scope 收口：客户端不再注册 `master.authToken` / `master.controlReachableEndpoints` / `master.resumeTicketTtlMs` 的 CLIENT 副本——端点与鉴权仅经 `gateway_info` 下发；客户端 schema 仅保留 L1 `master.migration*` 6 键。
- 1.20.1 neoforge dev 环境 IMBlocker 动态 mixin CNFE：`neoforge/build.gradle` 特判跳过（modLocalRuntime 不进生产）。
- 演练支撑（非生产语义改动）：`buildSrc/loom-fabric.gradle`（`-PhassiumSmokeUsername`/`-PhassiumSmokeMigrateImmediate`/`-PhassiumSmokeMigrateMoveSeconds` 透传）、`client/ClientSmokeTest.java`（migrate 单轮模式 + immediate API 直调，命令面无 immediate 子命令，故障路径内部 API）。

### 关闭口径
- 范围外（A5/C2 运行时冒烟）：随发布前冒烟另行安排，本波不阻塞。
- 待实测（D1-D3/E2 ViaFabric）：桥已落地、验收口径已定，实测另行安排（用户拍板不属本波）。

---

关联文档：`.omp/workflows/network-core/REQ.md`、`TASKS.md`、
`work/RealE2EVerification-TASK.md`（T9v4 交接）、`work/T10-MigrationDrill-TASK.md`、
`docs/runtime-smoke-test.md`、`docs/handoff/handoff-2026-08-09-network-core.md`。
## 本波追加：反馈式渐进区块推送（2026-08-19）

- ✅ 已实现：服务端 full/SeedRef 统一进入 per-player keyed admission；首次 authoritative ACK 前单未确认批次，ACK 后最多 10 批；`master.maxChunksPerTick` 保留为硬上限；deliveryId 贯穿压缩 full/SeedGen 到客户端最终落地。
- ✅ 已实现：客户端仅在 `shadow_applied`/最终 authoritative apply 成功后批量发送 `ChunkApplyAck`；服务端 ACK 幂等释放 in-flight；resync/hash/full 共用 admission；Gateway channel writability 作为传输背压。
- ✅ 已加固：SeedRef fallback 携带 deliveryId 转换 reservation；失败路径 rollback；pending/request count 有界；完整 dimension key；session-aware ACK；1.20.2+ `dropChunk` 精确释放；客户端 ACK 多批失败保留。
- ✅ 已验证：`common:test`、重点测试、`common:compileJava`、Fabric/Forge/NeoForge 1.20.1 与 Fabric/NeoForge 1.21.11 编译通过；证据见 `.omp/workflows/progressive-chunk-push/work/FinalCompileCheck-TASK.md`。
- 🔲 待实测：Fabric 1.20.1 真实进服/移动曲线。当前客户端固定使用 `fabric/run/client`，已有会话占用且没有 client runDir 隔离参数；本波未复用既有日志，需独立运行窗口后验收生产/apply 曲线、重复率、近环优先和 VD20 完整性。

实现与契约：`.omp/workflows/progressive-chunk-push/REQ.md`、`TASKS.md`、`work/CONTRACTS.md`；审查与阻塞记录：`work/REVIEW-FINDINGS.md`、`work/FabricRuntimeCurve-TASK.md`。
