# Handoff — 2.0.0 文档与 Wiki 重构（docs-2.0）

日期：2026-08-09 · 状态：**规划完成（含命名体系决议），待执行**（主会话已压缩/结束，本会话接力执行）
真相源：`.omp/workflows/docs-2.0/REQ.md` + `TASKS.md` + `work/domain-naming.md`（本文件是接续索引，不重复 REQ 内容）

## 为什么做

Hassium **2.0.0** 架构颠覆（代码已提交 `eb05ba5..11f3470`，`gradle.properties` mod_version=2.0.0）：
客户端网络由**进程内网关（网络核心）**接管——客户端↔世界侧纯原版协议（零压缩/零聚合/零自定义包），
网关↔主控自有通道（ZSTD/聚合/UDP 数据面保留）；旧 failover 全套退役（主控热切/加权分流/定格恢复/预握手
mixin 已删）；主控切换 = **网关无感迁移**（L1 迁移引擎 + 续流票据 ResumeTicket）。生产发布为 **1.1.2**，
GitHub wiki 不推，`wiki/` 改动本地提交仓库。

## 已完成（本阶段，勿重做）

- ✅ **CLAUDE.md 删除并整合进 AGENTS.md**（AGENTS 已重写含「2.0.0 网络核心」节；引用已更新 README×2 / architecture §12 / hassium-dev SKILL / publish 脚本）
- ✅ **影响面调研**（结果在 scout 任务结果，摘要见下）
- ✅ **REQ.md + TASKS.md 落盘**（`.omp/workflows/docs-2.0/`）——**执行前必读**
- ✅ **功能域命名体系决议**（2026-08-09 用户拍板，`work/domain-naming.md` 真相源）：
  - **三核心 + 支撑域**（按进程归属）：**网络核心** = 客户端进程内网关（`network/core/`，类名已吻合）；**区块核心** = 客户端进程内区块域（`network/seedgen/` 影子端 + `network/` 客户端摄入管线 + `cache/`；「影子端」保留为后端引擎称呼）；**主控核心** = 服务端进程内网络与推送（`network/gateway/` + 服务端区块推送 + 服务端聚合/ZstdPipeline 兼容链）；存储域/UDP 数据面/配置指标 = 支撑域
  - 类名/包名/配置键一律不改；仅文档表述与代码注释术语（GatewayServer/ShadowSeedServer 类头补定位行）
  - 术语映射：影子端（泛指）→ 区块核心；区块缓存/客户端缓存（域名）→ 区块核心；主控侧网关接入层 → 主控核心；网关（泛指）→ 按进程网络核心/主控核心

## 影响面摘要（调研结论）

**docs/（51 份）**：顶层 13 份需更新网络段（README×2、architecture §2/§4/§7/§8/§11.2/§11.6/§12、chunk-cache §9/§10.6/§13.4、client-chunk-light-flow L82-85、config-audit、mod-compat L100/L108、runtime-smoke-test UdpFailover 整节、version-segments 预握手/failover 段）；8 份历史 + superpowers 23 份 → archive；6 份仍有效（network-core-followups 等）。

**wiki/（24 页，12 主题×中英）**：
- A 层整页退役：`Data-Plane-and-Failover{zh,en}` → 重写为「网络核心与主控迁移」专文
- B 层章节删：Configuration（数据面节 L61-69）、Features（主控热切 L48-54 + 加权分流 L58-64）、FAQ（L65-71）、Troubleshooting（L60-67）
- C 层引用：Home 能力表 L25-26 + 导航 L63、_Sidebar L12、Compatibility
- D 层命令核对 ~20 处（`/hassium stats`、`/hassiumc stats|export`）
- E 层新增概念（进程内网关/L1 迁移/预握手）；F 层不动（Support-Matrix/Installation/_Footer/Beyond-View-Render）
- 顺手修：Home-en L24 "150 chunks/s" 与 maxChunksPerTick=4（≈80/s）口径矛盾

**.claude/skills/（4 份）**：hassium-network（重写网络核心）、hassium-mixin（清单更新）、hassium-dev（包地图）、hassium-storage（核对）——2.0.0 后过时。

## 任务清单（详情见 TASKS.md）

| # | 任务 | 并行组 |
|---|---|---|
| T1 | docs 目录重组（docs/archive/ + docs/handoff/ + README 索引，引用全更） | 先行 |
| T2 | 2.0.0 事实基线（命令表/配置键表，代码出处）→ work/facts-baseline.md | 先行（与 T1 并行） |
| T3 | README.md + README-en.md 更新 | 与 T4-T7 并行 |
| T4 | architecture.md 网络段重构 + 新增 2.0.0 网络核心节 | 同上 |
| T5 | chunk-cache + client-chunk-light-flow 恢复/通道段 | 同上 |
| T6 | config-audit + mod-compat + version-segments | 同上 |
| T7 | runtime-smoke-test 改写（UdpFailover → 网关双主控迁移冒烟） | 同上 |
| T8 | wiki A 层重写（页名可改，同步引用） | 与 T9 并行 |
| T9 | wiki B/C/D/F 层 + Home-en 速率修正 | 同上 |
| T11 | .claude/skills/ 4 份重构（对照 T2 基线 + hassium.mixins.json） | 与 T3-T7 并行 |
| T10 | 全量核验（关键词 grep 零残留/死链/中英同步）→ work/T10-TASK.md | 收尾 |

依赖：T1→T3-T10 路径引用；T2→T3-T11 内容；T8 页名→T9/T3 引用。T1 与 T2 无冲突。

## 执行方式

- 主会话只做派发与核验，**不自己实现**（实现走 task 子代理）
- 每个任务派一个子代理，任务描述自包含：目标 + 范围 + 验收（从 TASKS.md 抄录 + 影响面行号），不依赖本文件以外上下文
- 独立任务可并行（task 批量）：T1+T2 → T3-T7+T8+T9+T11 → T10（T8/T9 依赖 T2 基线，若 T2 未完成先派 T3-T7/T11 中不依赖基线的结构部分）
- 子代理开工先报 `ETA: N 分钟`；主会话 hub wait 带 timeoutMs = min(max(15min, ETA×2), task.maxRuntimeMs)
- 子代理自维护 `.omp/workflows/docs-2.0/work/<agent>-TASK.md`（每步更新，重启/交接时读取恢复）
- **报备模式**：任务描述写明"每 ≤45min 主动 yield 写 work/<agent>-TASK.md"，严禁被系统硬砍
- 纯文档任务：不跑编译/测试；验证 = grep + 死链检查 + diff 核对
- **git 纪律**：并行子代理不 commit，只留工作区改动；全部完成后主会话核验并按逻辑分批提交（参考 network-core 的 7 批模式）
- 全部完成后核验 REQ 验收标准（逐条，看证据），通过才收尾；`learn`/`retain` 沉淀

## 硬约束（违反即打回）

1. 内容以代码为准：**T2 事实基线先行**，任何配置键/命令/端口表述须有代码出处（ConfigSchema.java / 命令实现 / network/core）
2. GitHub wiki 不推（1.1.2 生产）；`wiki/` 改动正常 git commit
3. entity-shadow 并行工作流未提交（ShadowSeedServer 实体镜像等）——**文档不引用其未提交内容**；`entitySnapshotsEnabled` 已删零残留（wiki 零足迹，无需删）
4. 中英双语同步（wiki 全部 12 主题 ×2、README-en）
5. 禁止删除仍有效的真相源（architecture/chunk-cache/version-segments 等）；归档不移除历史
6. 过时关键词检查清单：`failover` / 热切 / 加权分流 / 定格 / `ControlEndpoint` / `ClientRecoveryState` / `FailoverPermit` / 预握手（语义仍适用处须注明保留理由）
7. 移动/删除文件前 grep 引用（T1 铁律）；`docs/handoff/` 目录由 T1 创建，本 handoff 文件初始即在 `docs/handoff/handoff-2026-08-09-docs-2.0.md`（T1 无需再移，旧 handoff-2026-08-09-network-core.md 移入 docs/handoff/，entity-shadow handoff 入 archive）

## 已完成上下文（代码侧事实，文档表述依据）

- 网络核心：`common/.../network/core/`（NetworkCore 五态状态机、outbound/ 帧协议、migration/ L1 迁移引擎、viafabric/ 兼容桥）、`network/gateway/`（主控接入：GatewayServer/玩家会话/登录桥）、`server/GatewayPlatformWiring` + `GatewayPlayerBridge` + `MixinConnectionGatewayServer`
- 客户端注入：`GatewayS2CRouter`（S2C handler 直调）、`MixinConnection`（C2S HEAD 截获 → routeC2S）、`ViaFabricCompat`（失败降级直注）
- 续流：`ResumeTicket`（56B HMAC）+ `ResumeTicketValidator`（epoch 防重放）+ 握手 append-only 尾（HandshakeStateTail）+ `resumeAccepted`
- 迁移：`MigrationEngine.migrateTo`（故障/策略/维护窗口/演练触发、PrewarmSession、IdleWindowDetector）
- 已删除（文档不得再引用）：ClientFailoverIdentity/ClientRecoveryState/ControlReconnect*/ControlEndpoint*/ServerListBackupPing/MixinGui/MixinGameRenderer/MixinConnectScreen/MixinServerStatusPinger/MixinVanillaChunkApplyBudget/MixinLightRecompute/MixinClientConfigurationPacketListenerImpl/ZstdPacketDecoder/Encoder/平台 ReconnectLauncher×3
- 保留：客户端 UDP 数据面（DataPlaneClientBundle/UdpDataPlane，outbound 复用）、服务端 ZstdPipeline 链休眠（旧客户端兼容）、服务端聚合门控
- 未达项（文档可引用）：`docs/network-core-followups.md`（A 运行时正确性 8 / B 迁移运维 5 / C 登录语义 2 / D ViaFabric 3 / E 验证缺口 2）
