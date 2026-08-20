# 网络核心与主控迁移

---

> **English**: [Network-Core-and-Master-Migration-en](Network-Core-and-Master-Migration-en) · 中文

## 这是什么

**网络核心**与**主控迁移**是 Hassium 2.0.0 的客户端网络架构：客户端进程内由**网络核心**（NetworkCore，`network/core/`）完全接管与主控的收发，世界侧只见纯原版协议；服务端进程内的**主控核心**（`network/gateway/` 网关 + 服务端区块推送）提供对应接入。两者配合达成三件事：

| 能力 | 说明 |
| --- | --- |
| **进程内网关** | 客户端↔世界侧纯原版协议（零压缩/零聚合/零自定义包）；网络核心 handler 直调注入 S2C、`routeC2S` 收口 |
| **无感主控迁移** | 换 outbound + 续流票据（epoch 防重放）+ `resumeAccepted`；客户端 Connection 不断、世界零重载 |
| **L1 负载均衡** | 故障 / 负载阈值 / 维护窗口 / 演练四类触发迁移；预热 + 空闲窗口 |

2.0.0 之前的断连重连方案已退役：客户端不再维护候选端点表、不弹恢复界面。架构决策与删除清单见 [网络核心交接文档](../docs/handoff/handoff-2026-08-09-network-core.md)，旧研究归档见 [docs/archive/](../docs/archive/)。

---

## 进程内网关架构

### 客户端：世界侧纯原版协议

客户端进程内网络收发由网络核心接管，世界侧（`Minecraft` 游戏线程可见的）连接只是"壳"：

- **C2S 收口**：PLAY 期客户端出站包经 `MixinConnection` 截获后全部转网关自有通道，由 `NetworkCore.routeC2S` 统一路由；壳连接仅 keep-alive 响应仍走原版 TCP。
- **S2C 注入**：网关自有通道收包解码后，网络核心经 handler 层**直调注入**原版监听器（`NetworkCore.dispatchS2C`），世界侧只见原版 `Packet` 对象。
- **零压缩 / 零聚合 / 零自定义包**：客户端↔世界侧不再承载任何 Hassium 压缩、聚合或自定义协议字节——管道层 Mod、handler 层 Mod 全部兼容。

### 主控：网关通道

- 网关帧连接（`GatewayChannel`）即控制连接，承载全量玩家流量；ZSTD 装在帧协议**之外**（`ControlFrameCodec` 外挂载，客户端 `OutboundConnection.installZstd` / 主控 `GatewayChannel.installZstd`，阈值与等级复用 `master.globalCompression*` 配置）。
- **聚合仅主控侧 vanilla 路径**：包聚合挂 vanilla `Connection.send`（`MixinConnection` 仅对服务端玩家监听器生效），网关通道不聚合。
- **UDP 数据面**（`network/dataplane/`）完整保留，作为网关↔主控通道的 bulk 载体，默认关（`dataplane.enabled = false`）。

### 拓扑总览

| 连接 | 承载 | 协议 |
| --- | --- | --- |
| 客户端世界侧壳连接 | keep-alive 响应 + handler 注入的原版包 | 纯原版 TCP（零压缩/零聚合/零自定义包） |
| 网关↔主控自有通道 | 全量玩家流量（帧 + ZSTD） | 帧协议（ControlFrameCodec），ZSTD 帧外 |
| UDP 数据面（可选） | bulk 区块载体 | UDP（`network/dataplane/`，默认关） |

---

## 无感主控迁移

迁移 = 客户端**换 outbound**（网关帧连接从主控 A 换到主控 B），`NetworkCore` 状态机 `ACTIVE → MIGRATING → ACTIVE`。原版连接不断开、不弹任何断连/加载界面，世界不重载。

### 续流票据 ResumeTicket

迁移握手携带**续流票据**（`ResumeTicket`，`network/` 包）：

- **构造**：`playerId`（16B）+ `epoch`（8B BE）经 HMAC-SHA256 签名，密钥为主控 A/B 共享密钥（`ResumeTicket.setSharedKey`）。
- **防重放**：`epoch` 在客户端进程生命周期内单调递增（1 起，登录不重置）；主控 `ResumeTicketValidator` 记录各玩家最后接受的 epoch（跨会话不清理），验签通过且 epoch 递增才接受——旧票据重放一律拒绝。
- **握手结果**：`HandshakeStateTail` S2C 尾携带 `resumeAccepted`：
  - `resumeAccepted = true`（续流就绪）：主控按票据中的玩家身份复用 UUID-keyed 推送链（`ServerChunkPushManager.markPlayerResumeActive`），数据推送直接流入，客户端磁盘缓存与任务执行器原样承接；
  - `resumeAccepted = false`（会话未附着）：无票据 / 验票失败 / 重放，会话待登录桥（`GatewayPlayerBridge.attachPlayer`）附着，数据推送不流入，走登录桥/重连兜底。

### 客户端零重载

迁移全程原版 `Connection` 状态保持、世界照常运行，成功接管后画面无感知。续流失败也不回滚到整段重载——只退化为登录桥兜底路径。

---

## L1 负载均衡

`MigrationEngine`（`network/core/migration/`）是 L1 迁移引擎：触发判定 + 迁移编排 + 预热 + 空闲窗口。四类触发：

| 触发 | 条件 | 行为 |
| --- | --- | --- |
| **故障** | outbound 入站静默超过生效超时（默认 `master.migrationSilentTimeoutMs`=10000；显式改 `migrationFaultTimeoutMs` 时可回退该 legacy 键）；心跳线程按 `migrationHeartbeatIntervalMs`（默认 5000）发 HEARTBEAT 监测 | 不预热，直接 `migrateToImmediate` |
| **负载阈值** | 主控负载报告（`ServerLoadReporter`）：TPS < `migrationMinTps`（默认 15.0）或系统负载均值 > `migrationMaxLoadAverage`（默认 4.0） | 策略迁移（预热） |
| **维护窗口** | `migrationMaintenanceWindow`（"HH:MM-HH:MM"，本地时区、支持跨午夜；空串禁用）：窗口内恒触发 | 策略迁移（预热） |
| **演练** | 客户端 `/hassium migrate list\|status\|<host:port>`（`NetworkCore.migrateTo`；**仅开发环境注册**） | 策略迁移（预热） |

### 预热 + 空闲窗口

- **预热**（`PrewarmSession`，`prewarmEnabled` 默认 true）：迁移前先连目标主控，以续流票据建立玩家会话——B 侧物化玩家 + `resyncTrackedChunks` 预同步；迁移时直接接管该连接，增量趋近零。
- **空闲窗口**（`IdleWindowDetector`）：玩家静止（移动低于阈值）且区块 hash 稳定（增量已收敛）→ 判定为适合迁移的时机。负载/维护/演练路径优先在空闲窗口内执行；故障路径不受限。

---

## 配置项

> 网关监听端口复用 `master.controlReachableEndpoints[0]`；键族按 2026-08-09 重排：网络核心 `net.*`（客户端）/ 主控核心 `master.*` / 数据面 `dataplane.*`。L1 策略键多在 **client.toml**（CLIENT scope）。

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `net.enabled` | `true` | 客户端网络核心总开关（进程内网关与优化通道） |
| `master.enabled` | `true` | 服务端网络通道总开关 |
| `master.controlReachableEndpoints` | `[]` | **服务端**主控网关监听/通告地址；端口取 `endpoints[0]`，**否则兜底 `25566`**；经握手尾与 `GatewayInfo` 同步到客户端（**客户端无需手填**） |
| `master.bindHost` | `127.0.0.1` | 网关监听 bind host（默认回环；空串=`0.0.0.0`） |
| `master.authToken` | `""` | 网关握手鉴权（空=不鉴权）；可由 `GatewayInfo` 下发 |
| `master.compressionLevel` | `3` | 自定义通道 ZSTD 压缩等级 |
| `master.globalPacketCompression` / `globalCompressionLevel` / `globalCompressionThreshold` | `true` / `3` / `256` | 全局压缩；亦为网关通道 ZSTD 安装源 |
| `dataplane.enabled` | `false` | UDP 数据面总开关（**默认关**） |
| `master.migrationSilentTimeoutMs` | `10000` | outbound 入站静默超时（默认生效值；失效识别 ≤15s） |
| `master.migrationFaultTimeoutMs` | `60000` | legacy 故障超时回退（显式改此键且 silent 仍为默认时生效） |
| `master.migrationMinTps` / `migrationMaxLoadAverage` / `migrationMaintenanceWindow` | `15` / `4` / `""` | 策略触发（CLIENT） |
| `master.migrationHeartbeatIntervalMs` / `migrationIdleWindowMs` | `5000` / `10000` | 心跳 / 空闲窗口（CLIENT） |
| `master.migrationPrewarmTtlMs` | `60000` | 预热会话 TTL（SERVER） |
| `master.resumeTicketTtlMs` | `300000` | 续流票据有效期（双端同名键） |

要点：

- 客户端迁移候选端点 = 主控握手尾 `controlEndpoints` + 登录期 `GatewayInfo` 同步（落盘 `failover-endpoints.properties`）；**不是**靠 client.toml 手填。演练入口 `/hassium migrate`（仅开发环境）。
- 服务端 `dataPlane` 键族已重排为 `dataplane.*`；旧 `controlStallMs` / `failoverExpiryMs` / `recoveryFreeze` 已删。
- Configuration 只列服主/玩家常用键；`ConfigSchema` 全量细键（聚合/动态线程池/热度淘汰等）以代码为准，不在 wiki 全量展开。

---

## 相关页面

[← Support-Matrix](Support-Matrix) · [Home](Home) · [→ FAQ](FAQ)
