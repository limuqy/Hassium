# 主控热切与加权分流

---

> **English**: [Data-Plane-and-Failover-en](Data-Plane-and-Failover-en) · 中文

## 这是什么

**主控热切**和**加权分流**是 Hassium 的两项网络能力，专门解决多人服在主连接抖动时玩家"卡死/掉线/重进"的体验问题，以及高人数服带宽吃紧时的负载分担问题。二者配合使用：主控热切保证"主连接出问题时玩家几乎无感知"，加权分流负责"把区块下载流量分散到多条线路"。

对应 Home 能力列表中的两条：

| 能力 | 说明 |
| --- | --- |
| **主控热切** | TCP 主控断或卡时按候选自动重连，恢复期画面定格、缓存暖续、隐藏断连界面（数据面 failover） |
| **加权分流** | 多 UDP/KCP endpoint 按 weight 加权轮询承载数据面，控制面留原版 TCP |

---

## 解决什么问题（举例）

**问题一：主连接抖一下，全员掉线回主菜单。**

原版 Minecraft 的登录、聊天、命令、实体同步走一条 TCP 主连接（master Play connection）。区块下载也挤在同一条线上。当主连接卡顿几秒（网络抖动、服主重启、机器迁移）或断开，客户端会弹"连接丢失"界面、踢玩家回主菜单、缓存丢失，重进又要重新下载所有区块——几个玩家正在地心探索，服主例行维护重启，全员进度看起来"白走了"。

**主控热切**做的：主连接断或卡时，客户端按服务端预先下发的候选列表自动连下一个可达端点，**不弹断连界面**。已下载的区块缓存和任务执行器都保留下来，切到新会话直接续上——探索过的地形仍在缓存里，命中率不掉。**1.20.1 段有两种恢复表现**（`network.dataPlane.recoveryFreeze`，客户端默认 true）：定格模式——世界 tick 暂停、过渡画面（连接/加载/接收世界）仅隐藏渲染，屏幕保持冻结世界 + 「正在切换主控…」浮层，恢复成功画面直接续动，全程看不到加载画面；无感模式（false）——世界照常运行，玩家操作本地照常生效但发不到服务器，恢复成功后位置回退到断线点、刚挖的方块还原，体感如同突然延迟变高卡了一下，全程无任何切换 UI。

**问题二：几百人同时进服，主连接带宽被打满。**

高人数服的瓶颈常是区块下行：每人都拉一片地形，单条线扛不住。扩容还要担心线路不稳那一边出问题。

**加权分流**做的：区块下载走 UDP/KCP 数据面，可以配多个 endpoint（多条线路），按 `weight` 加权轮询分担。一条线路满或降级，流量自动压到其余线路。登录命令等"控制类"流量仍走原版 TCP，不受数据线路波动影响。

---

## 谁适合启用

这两项能力**默认关闭**——`network.dataPlane.enabled = false`，模组默认走原版 TCP 单连接，普通联机行为不受影响。需要主控热切或公网分流时，按以下步骤启用：

1. 服主具备 Nginx / 公网防火墙 / NAT 规则的运维能力；
2. 在 `hassium-server.toml` 中设 `network.dataPlane.enabled = true`，并配置可达的公网端点；
3. 依次确认 6 个自检标记（见文末）。

> ⚠️ 生产环境配置仍在迁移过程中，目前由 `DataPlanePoCConfig` 临时驱动。不具备上述运维能力的服主保持默认关闭即可，不影响其它能力。

对**单个朋友联机**或**小型私服**的普通玩家：保持默认关闭即可，无需关心本页后续内容。本页技术细节面向有运维需求的服主。

---

## 技术细节

以下内容面向有运维能力的服主。普通玩家可跳至 [FAQ](FAQ) 查看常见问题。

### 拓扑总览

| 平面 | 用途 | 协议 |
| --- | --- | --- |
| **控制面（Master TCP）** | 原版 Minecraft login + Play Connection | TCP |
| **数据面（UDP/KCP）** | 区块 bulk 与数据面分发 | UDP/KCP（独立 session 与 KCP ReliableDatagramSession） |

- 控制面端点列表由 S2C handshake tail 下发（host:port + priority）
- 客户端混合 bootstrap + advertised，按 priority 降序，最多 4 个候选（`ControlEndpointManager.MAX_CANDIDATES`）
- 每个 `(host, port)` UDP 端点绑一个独立 KCP `ReliableDatagramSession`
- 客户端对每个 advertised endpoint 单独 BindRequest + HKDF 派生 AES-GCM key

### 触发条件

**硬断连**：Master TCP `channelInactive` → `ControlReconnectOrchestrator.onPrimaryDisconnected` 立刻 launch 下一个候选；客户端进入 60 秒恢复窗口。恢复期（1.20.1 段）世界 tick/实体 tick 暂停、断连画面与过渡画面均不呈现，仅显示切换浮层。

**Master stalled + UDP healthy**：服务端检测 control stall（默认 6 秒）。stalled 期间 `DataPlaneUdpServer.recordControlActivity` 推进；若 UDP session 健康（epoch 一致）服务端下发 `FailoverPermit`（`expiryMs` 默认 30 秒），客户端 `attemptConnectOnlyIfPermitValid` 才连接。

### 恢复期保留资源

`ClientRecoveryState.shouldSuppressFinalization()` 为真时，`ClientLifecycleHelper.finalizeDisconnectIfTerminal` 短路 `finalizeDisconnect`：

- 磁盘缓存保留
- `CacheSaveQueue` 保留
- `HassiumTaskExecutor` 保留
- dirty 标志保留

进入下一候选会话时缓存可直接承接，**命中率不降**。

`ClientPlayConnectionEvents.DISCONNECT` 路径调 `DataPlaneClientLifecycle.stopUdp(/*keepLease*/ true)`，UDP bundle 不立即释放。

### 候选耗尽

`ControlReconnectOrchestrator.performTerminalFinalization` 调 `ClientLifecycleHelper.finalizeDisconnectIfTerminal`，单例 `ClientRecoveryState.consumeTerminalCleanup` 保证只触发一次磁盘资源关闭。

### 加权分流

数据面支持多个 UDP endpoint 按 `weight` 加权轮询承载：

- 每个端点一个 `ReliableDatagramSession`
- 按 `weight` 跑 WRR
- 支持 `share` / `exclusive` 路由模式
- UDP 健康度可调权（degraded 降级）

---

## 配置项

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `network.dataPlane.enabled` | `false` | 数据面总开关（默认关；启用前请配置可达端点并依次确认 6 个自检标记） |
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
5. Nginx `stream` 反代可承载 TCP 主控 + UDP 直连 failover（见下方自检流程）

---

## 自检标记（公网部署前必跑）

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
