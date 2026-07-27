# 数据面与主控 Failover

---

> **English**: [Data-Plane-and-Failover-en](Data-Plane-and-Failover-en) · 中文

高级网络特性：UDP/KCP 数据面 + TCP 控制 failover。**默认关闭**，主打有运维能力的服主。启用前请先跑 6 个 smoke marker。

> ⚠️ 该特性的生产 env 配置仍在迁移过程中，目前由 `DataPlanePoCConfig` 临时驱动；启用前请确认你已具备 Nginx / 公网防火墙 / NAT 规则的运维能力。

---

## 拓扑总览

| 平面 | 用途 | 协议 |
| --- | --- | --- |
| **控制面（Master TCP）** | 原版 Minecraft login + Play Connection | TCP |
| **数据面（UDP/KCP）** | 区块 bulk 与数据面分发 | UDP/KCP（独立 session 与 KCP ReliableDatagramSession） |

- 控制面端点列表由 S2C handshake tail 下发（host:port + priority）
- 客户端混合 bootstrap + advertised，按 priority 降序，最多 4 个候选（`ControlEndpointManager.MAX_CANDIDATES`）
- 每个 `(host, port)` UDP 端点绑一个独立 KCP `ReliableDatagramSession`
- 客户端对每个 advertised endpoint 单独 BindRequest + HKDF 派生 AES-GCM key

---

## 触发条件

### 硬断连

Master TCP `channelInactive` → `ControlReconnectOrchestrator.onPrimaryDisconnected` 立刻 launch 下一个候选；客户端进入 60 秒恢复窗口。

### Master stalled + UDP healthy

服务端检测 control stall（默认 6 秒）。stalled 期间 `DataPlaneUdpServer.recordControlActivity` 推进；若 UDP session 健康（epoch 一致）服务端下发 `FailoverPermit`（`expiryMs` 默认 30 秒），客户端 `attemptConnectOnlyIfPermitValid` 才连接。

---

## 恢复期保留资源

`ClientRecoveryState.shouldSuppressFinalization()` 为真时，`ClientLifecycleHelper.finalizeDisconnectIfTerminal` 短路 `finalizeDisconnect`：

- 磁盘缓存保留
- `CacheSaveQueue` 保留
- `HassiumTaskExecutor` 保留
- dirty 标志保留

进入下一候选会话时缓存可直接承接，**命中率不降**。

`ClientPlayConnectionEvents.DISCONNECT` 路径调 `DataPlaneClientLifecycle.stopUdp(/*keepLease*/ true)`，UDP bundle 不立即释放。

---

## 候选耗尽

`ControlReconnectOrchestrator.performTerminalFinalization` 调 `ClientLifecycleHelper.finalizeDisconnectIfTerminal`，单例 `ClientRecoveryState.consumeTerminalCleanup` 保证只触发一次磁盘资源关闭。

---

## 加权分流

数据面支持多个 UDP endpoint 按 `weight` 加权轮询承载:

- 每个端点一个 `ReliableDatagramSession`
- 按 `weight` 跑 WRR
- 支持 `share` / `exclusive` 路由模式
- UDP 健康度可调权（degraded 降级）

---

## 配置项

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `network.dataPlane.enabled` | `false` | 数据面总开关（默认关；1.20.1 Fabric PoC 阶段 true） |
| `network.dataPlane.controlStallMs` | `6000` | Master TCP 卡顿多久触发 `FailoverRequest` |
| `network.dataPlane.failoverPermitTtlMs` | `30000` | 服务端 `FailoverPermit` 有效期 |
| `network.dataPlane.udpEndpoints` | （待 toml 化） | 候选列表，每项 `host`、`port`、`weight`、可选 `priority` |

> `udpEndpoints` 目前通过 S2C tail 下发；正式落地到 `hassium.toml` 后才能在文件里手改。

---

## 运维须知

1. 每个公网 `udpEndpoints` 端点需要公网 UDP 防火墙/NAT 规则放行
2. 10 秒 UDP `lease` 仅用于 drain in-flight data；login 完成前不产生新玩家数据
3. `controlStallMs` 要求服务端 issue `FailoverPermit`；客户端不会因 latency 单独创建第二条 master Play 连接
4. TCP 控制 endpoints 与 UDP endpoints 分开列表，公网端口可能不同
5. Nginx `stream` 反代可承载 TCP 主控 + UDP 直连 failover（见下方 smoke harness）

---

## Smoke markers（启用前必跑）

| 标记 | 含义 |
| --- | --- |
| `UDP_BIND_OK` | 服务端 UDP 端点成功 bind |
| `UDP_WRR_OK` | 加权轮询分发正确 |
| `FAILOVER_PERMIT_OK` | 服务端能在 stall + UDP healthy 时下发 permit |
| `FAILOVER_RECONNECT_OK` | 客户端能按 permit 切到下一候选 |
| `CACHE_RESUME_HIT` | 切换后磁盘缓存续上、命中率不降 |
| `FAILOVER_TERMINAL_OK` | 候选耗尽时 finalize exactly once |

关闭 `network.dataPlane.enabled` 时不应该有任何 UDP listener/bind/failover 行为（regression guard）。

---

[← Support-Matrix](Support-Matrix) · [Home](Home) · [→ FAQ](FAQ)
