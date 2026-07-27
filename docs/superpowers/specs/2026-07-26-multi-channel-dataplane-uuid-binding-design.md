# Multi-channel Data Plane — Real UUID Binding Design

Date: 2026-07-26  
Parent rollout: [`2026-07-26-multi-channel-dataplane-rollout-design.md`](2026-07-26-multi-channel-dataplane-rollout-design.md)  
Parent research: [`docs/multi-channel_network_research.md`](../../multi-channel_network_research.md)  
Scope: **§14 第 2 步补全** — 真实玩家 UUID 绑定 + BindRequest 硬切换

> **[SUPERSEDED 2026-07-27 — implementation moved to UDP/KCP stack]** 本设计稿描述的 PoC TCP
> 多通道实现（`PlayerChannelBundle` / `BindRequestCodec` / `DataPlaneServer.getOrCreateBundle` 等）
> 已在合并 commit `7ba824c`（merge master → feature/udp-dataplane-failover）由 feature 侧的新 UDP/KCP
> 体系替代：传输改为 `DataPlaneUdpServer`（`NioDatagramChannel` + KCP），会话注册移至
> `DataPlaneSessionRegistry`（按 `(playerId, epoch)` 分桶），BindRequest codec 由 `UdpBindRequestCodec`
> 承担（实含 player UUID + HKDF 含 UUID），握手尾部由 `UdpDataPlaneHandshakeTail` 播发 endpoint group。
> 读者对照实现请取舍：本文 §3 wire 格式与 §4 call-site 表的 master 类名仅作为**语义参考**；feature
> 侧等价物见 `2026-07-27-unified-endpoint-status-and-pause.md` §8 续作 commit 链说明。
>
> §14 第 2 步（真实 UUID 绑定 + 多玩家隔离）的运行时语义**已由 feature 等价实现并测试覆盖**
>（`DataPlaneSessionRegistryTest.disconnectOnePlayerDoesNotAffectAnother` + `UdpLeaseRoutingTest`）；
> §14 第 4 步 per-portIdx 发送指标生产者也已在 `DataPlaneUdpServer.tryRouteBulk` 经
> `UdpBulkRouter.routeAndPick` 暴露 `chosen.endpointId()` 后重建（覆盖见 `UdpTryRouteBulkTest`
> per-portIdx 增量断言 + `SendPerPortMetricsTest`）。

## 1. Goal and non-goals

### Goal

1. Data 通道 `PlayerChannelBundle` 以**真实玩家 UUID** 为 key（多玩家隔离）
2. BindRequest 载荷携带 `uuid[16]`，`REQ_PROTOCOL` **1→2 硬切换**
3. ChunkSender / onPrimaryDisconnect 一律传 `player.getUUID()`
4. 单测覆盖两 UUID bundle 隔离；单玩家 e2e 冒烟回归 `DATAPLANE_PASS`

### Non-goals

| Item | 理由 |
|------|------|
| bump 主握手 `Constants.CURRENT_PROTOCOL_VERSION` | **不做**。主连接 HandshakeC2S/S2C 继续尾部 append + `isReadable()`；旧客户端仍可 Primary 正常进服，仅 Data 面 Bind 硬切换 |
| 主连接 failover / 健壮性 | 仍 non-goal（父研究稿 §1） |
| Forge | 用户锁定不补 |
| SectionDelta 路由 / per-portIdx 指标 / toml | §14 第 3/4 步及另期 |
| HKDF salt 改 playerUUID | 本轮只绑 bundle key；密钥派生仍 sessionToken + portIdx + channelId |

### Supersede notice

覆盖 rollout design 中下列已标 **[SUPERSEDED]** 的条目：

- 「真实玩家 UUID 绑定 / pseudoPlayerId」
- 「protocol version bump」——**仅限 Data 面 BindRequest `REQ_PROTOCOL`**，非主握手 `CURRENT_PROTOCOL_VERSION`

> rollout design 正文里若写「bump `Constants.CURRENT_PROTOCOL_VERSION` 1→2」属表述过宽；以**本文件**为准：主握手不 bump。

## 2. Locked decisions

| Item | Decision |
|------|----------|
| BindRequest schema | 硬切换：`token[16] + uuid[16] + protocol(VarInt=2) + channelId(VarInt)` |
| 旧 BindRequest（无 uuid / protocol=1） | BindAck fail → close Data；Primary 不受影响 |
| 路由 key | ChunkSender 直接 `player.getUUID()` → `tryRouteBulk` |
| 验证 | 单玩家 e2e 冒烟保留 + 单测多玩家隔离 |
| 主握手协议号 | **不 bump** |
| Smoke Bind UUID | smoke 环境（`hassium.smokeTest` / `hassium.serverSmokeTest`）客户端 Bind 仍用 `pseudoPlayerId()`，与 `ServerSmokeTest` inject 对齐；生产用真实 UUID |

## 3. Wire: BindRequest v2

### 3.1 旧（v1，废弃）

```
token[16] + channelId(VarInt) + protocol(VarInt=1)
```

### 3.2 新（v2，唯一合法）

```
token[16]
uuid[16]          // mostSigBits + leastSigBits, big-endian each 8B
protocol(VarInt)  // 必须 == 2
channelId(VarInt) // 仍 1；weight 改用服务端 portIdx
```

最小合法长度：`16 + 16 + 1 + 1 = 34`。

### 3.3 服务端解析

```
if payload.length < 34 → BindAck fail "Bad request length"
token = payload[0..16)
if token != sessionToken → fail "Token mismatch"
uuid = UUID(msb=BE long payload[16..24), lsb=BE long payload[24..32))
rest = payload[32..)
protocol = readVarInt(rest)
channelId = readVarInt(rest)
if protocol != 2 → fail "Unsupported bind protocol"
playerId = uuid
getOrCreateBundle(playerId).addChannel(...)
weight = ENDPOINTS[portIdx-1].weight
```

### 3.4 客户端发送

HandshakeS2C 后取 UUID（smoke 则 `pseudoPlayerId`），`connectAndBind(uuid, token, endpoints)`，每条连接写 v2 BindRequest。

## 4. Call-site migration

| 位点 | 改动 |
|------|------|
| `BindRequestCodec` | encode/decode v2 helper |
| `DataPlaneClientBundle` | `REQ_PROTOCOL=2`；`connectAndBind(UUID, token, endpoints)` |
| `DataPlaneClientLifecycle` | `start(UUID, token, endpoints)` / `startFromHandshake(tail, uuid)` |
| fabric/neoforge 握手 S2C | 解析 player UUID 后传入 lifecycle |
| `DataPlaneServer.handleBindRequest` | 解析 uuid；拒 protocol≠2；weight 用 portIdx |
| ChunkSender fabric/neoforge | `tryRouteBulk(player.getUUID(), …)` |
| `MixinServerGamePacketListenerImpl` | `onPrimaryDisconnect(player.getUUID())` |
| `pseudoPlayerId()` | 仅 test/smoke |

## 5. Tests

| Test | Invariant |
|------|-----------|
| BindRequestCodec round-trip | uuid + protocol=2 |
| reject short / protocol=1 | fail reasons |
| 两 UUID bundle | 互不干扰；A degraded 不影响 B |
| common:test + compileJava | 绿 |

## 6. Done criteria

- [ ] 生产路径 ChunkSender/disconnect/Bind 用真实 UUID
- [ ] BindRequest v2 双向
- [ ] 两 UUID 单测绿
- [ ] 1.20.1 fabric/neoforge compile 绿

## 7. Approval trail

2026-07-26：硬切换 BindRequest；ChunkSender 直传 UUID；单测多玩家；主握手不 bump；smoke 仍 pseudo Bind UUID。
