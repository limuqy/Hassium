> 归档：历史 superpowers 规格（已完成使命）（2026-08-09）
# Multi-channel Data Plane PoC Design

Date: 2026-07-25  
Parent: [`docs/archive/multi-channel_network_research.md`](../../multi-channel_network_research.md)  
Scope: **仅 PoC（§14 第 1 步子集）**  
Anchor: **1.20.1 fabric**

## 1. Goal and non-goals

### Goal

在 1.20.1 fabric 跑通数据面骨架：

- 服务端额外 bind 两个裸 Data 端口
- 客户端 JOIN 后直连两端口，Bind 成功后接收 bulk
- `share` / `exclusive` 两种 bulk 路由 + WRR
- Bind 成功后启用 HKDF-SHA256 + AES/CFB8
- `exclusive` 无候选时：立即 drop + 连续 3 次 drop 后 `degraded`，本会话后续 bulk 强制 Primary

### Non-goals (explicit)

| Item | Deferred to |
|------|-------------|
| HandshakeS2C endpoints / token / mode 下发 | §14 第 2 步 |
| HandshakeC2S `multiChannelSupported` | §14 第 2 步 |
| `server.toml [network.dataPlane]` 正式配置 | §14 第 2 步 |
| SectionDelta 接入 BulkRouter | §14 第 3 步 |
| NetworkStats 分通道指标 | §14 第 4 步 |
| forge / neoforge 同构 | §14 第 5 步 |
| 真异步 exclusive 排队等待 | 落地（本 PoC 用 drop+degraded 简化） |
| token TTL / 单次 Bind 上限 | 握手扩展阶段 |
| 改 `MixinConnection` 全包分流 | **永不做**（父设计 §10 禁止） |

### Intentional divergence from parent §8

Parent §8: exclusive 无候选 → per-player 短队列，最长 `exclusiveWaitMs`，超时 drop。

**PoC:** 不实际排队（避免阻塞 `ServerChunkPushManager` push 线程池）。立即 drop + `consecutiveDrops`；达到 3 次 → `degraded=true` → 本会话 bulk 恒走 Primary。

- 表象：父设计"晚到" → PoC "没到需再请求"
- 客户端已有超时/重试；不退化
- 落地握手扩展时一并补真异步档队

## 2. Locked decisions

| Item | Decision |
|------|----------|
| Loader | 1.20.1 fabric only |
| Dual port form | Independent bare Netty ports (parent §6.3); not reuse MC ServerBootstrap childHandler |
| Frame types in PoC | 1 BindRequest, 2 BindAck, 3 BulkCompressedChunk, 5 KeepAlive (optional), 6 KeepAliveAck, 7 Close (optional) |
| Encryption | Full §6.4: HKDF-SHA256 + AES/CFB8 after Bind |
| HKDF | **纯 JDK 手写** HKDF-SHA256（extract+expand，~30 行）；与 BouncyCastle 输出位等价。AES/CFB8 亦用 JDK `Cipher`，整个 PoC **零第三方加密依赖**。后续可在不改动 `Hkdf` 调用方的前提下替换为 BC |
| Handshake | **Not extended.** Local PoC config + fixed token; both sides read same config |
| Client capability | Config switch `client.enableDataPlane=true` (pseudo multiChannelSupported) |
| Token validation | Fixed 16-byte token from config; no TTL / per-token Bind cap in PoC |
| PRIMARY candidate | `BulkRouter` returns `false` → caller continues existing Primary path (no reimplementation of Primary send) |
| Metrics | Logs only; accept that Data-plane hits skip `NetworkStats.recordChunkSent` until §11 |
| Endpoints | Two hard-coded endpoints in PoC config (e.g. `127.0.0.1:25566` weight 50, `127.0.0.1:25567` weight 50); `primaryWeight=100` |

## 3. Module layout and class boundaries

### common (platform-agnostic)

| Class | Package | Responsibility |
|-------|---------|----------------|
| `DataPlanePoCConfig` | `...network.dataplane.poc` | Hard-coded endpoints, mode, weights, fixed token, exclusive degrade threshold |
| `DataPlaneFrame` | `...network.dataplane` | Length-prefixed frame codec types 1–7 |
| `DataPlaneCodec` | `...network.dataplane` | AES/CFB8 encrypt/decrypt of `type||payload`; frameLen stays cleartext |
| `Hkdf` | `...network.dataplane.crypto` | Thin wrapper over third-party HKDF |
| `DataPlaneServer` | `...network.dataplane` | Multi-port ServerBootstrap, accept, Bind check, per-player routing dispatch |
| `PlayerChannelBundle` | `...network.dataplane` | Per-player Data channels + WRR state + `degraded` + `consecutiveDrops` |
| `BulkRouter` | `...network.dataplane` | share/exclusive candidate set + WRR + handleNoCandidate |
| `DataPlaneClientBundle` | `...network.dataplane` | Client connect, BindRequest, demux bulk frames |

### fabric 1.20.1 (hooks only)

| Hook | File / site | Change |
|------|-------------|--------|
| Server start | `MixinMinecraftServer.onServerInit`（复用既有钩子，与 `ServerChunkPushManager` 同源） | `DataPlaneServer.bind` |
| Server stop | `MixinMinecraftServer.onServerStop`（stopServer HEAD） | `DataPlaneServer.shutdown` |
| Bulk send intercept | `FabricNetworkManager.sendCompressedChunk` (~line 682) | Call `BulkRouter.sendBulk` first; if `true`, return; else existing Primary path |
| Client JOIN | Client play join handler | If PoC config enable → start `DataPlaneClientBundle` |
| HKDF provider | Gradle dep + service if needed | BouncyCastle (or chosen lib) on fabric classpath; common uses abstract `Hkdf` |

### Must not touch

- `MixinConnection`
- `HassiumHandshake` / protocol version bump
- `ServerChunkPushManager` body (call site at :976 stays; routing is inside fabric `sendCompressedChunk`)
- `NetworkStats` (except log-only PoC counters if convenient)

### Verified call chain (codegraph)

```
ServerChunkPushManager:976
  sender.sendCompressedChunk(player, compressed)
        │ fabric lambda HassiumMod:25-27
        ▼
FabricNetworkManager.sendCompressedChunk:682   ← PoC sole server insert
        │
        ├─ BulkRouter.sendBulk(...) == true  → done (Data or exclusive-drop)
        └─ false → existing Primary encode/send
```

`ChunkSender` remains the platform-agnostic interface; PoC does **not** change the interface or `ServerChunkPushManager`.

## 4. Wire protocol (PoC subset of parent §6)

### Frame

```
frameLen: VarInt   # bytes after this field (includes type)
type: u8
payload: frameLen-1 bytes
```

After Bind: encrypt `type||payload` with AES/CFB8; `frameLen` remains cleartext.

### Key derivation (parent §6.4)

```
dataKey = HKDF-SHA256(
  ikm  = BIND_TOKEN,               # 16 bytes from PoC config (PoC 全 0)
  salt = BIND_TOKEN,               # PoC 复用 BIND_TOKEN 作为 salt
  info = FRAME_KEY_INFO_TAG || portIdx || reqChannelId
)[16]
```

> **实现偏差（PoC vs 父协议 §6.4）**
> 父协议 §6.4 规定 `salt = playerUUID bytes`、`info = "hassium-dataplane-v1" || channelId`。
> PoC 因「不扩展握手、无法绑定真实玩家 UUID」约束（见 §3 `pseudoPlayerId`），统一用 `DataPlanePoCConfig.pseudoPlayerId()` 归入同一 bundle，
> 故 HKDF 输入改为全玩家共用的常量：`ikm = salt = BIND_TOKEN`，`info = FRAME_KEY_INFO_TAG("DPL1") || portIdx(int32 BE) || reqChannelId(int32 BE)`。
> 即**单密钥派生策略退化为「per-channel 而非 per-player」**，PoC 安全性下限可接受（BIND_TOKEN 本身 PoC 为全 0）；
> 后续若恢复 `salt=playerUUID`，需先将 bundle 绑定回真实玩家（握手传 UUID），见 §11 post-PoC。

- No reuse of main connection Cipher state
- No second pipeline ZSTD on Data path (payload already application-compressed)

### Bind

- Server accepts only type=1 before Bind
- After Bind: server ignores business C→S except KeepAliveAck
- Client never writes bulk on Data
- PoC protocol version constant e.g. `POC_PROTOCOL = 1` in BindRequest

## 5. BulkRouter behavior

```text
sendBulk(player, frameType, bytes) -> boolean:
  if !pocEnabled || !clientEnableDataPlane: return false

  bundle = PlayerChannelBundle.get(player)
  if bundle == null: return false

  if mode == exclusive && bundle.degraded:
    return false   // permanent Primary fallback this session

  candidates = []
  if mode == share:
    candidates += PRIMARY(primaryWeight)   // virtual; selecting it → return false
  for ch in bundle.dataChannels:
    if ch.active && ch.writable:
      candidates += DATA(ch, weight)

  if candidates empty:
    return handleNoCandidate(bundle, mode)

  target = WRR(candidates)   // per-player state
  if target is PRIMARY:
    return false
  write encrypted frame on target Channel
  bundle.consecutiveDrops = 0   // success resets degrade counter
  return true
```

### handleNoCandidate (PoC)

```text
if mode == share:
  return false
// exclusive
bundle.consecutiveDrops++
if bundle.consecutiveDrops >= 3:
  bundle.degraded = true
  warn log
  return false   // degrade → Primary
// immediate drop (no queue)
debug log exclusive drop
return true      // caller must NOT send Primary
```

### Failure table (PoC)

| Scenario | Behavior |
|----------|----------|
| share, no Data | return false → Primary |
| share, only Primary writable | return false |
| exclusive, no Data / all unwritable | drop + consecutiveDrops; 3× → degraded |
| exclusive + degraded | return false always |
| Data write fail | remove channel, reselect; share may hit Primary |
| Primary disconnect | close all Data for player; clear bundle |
| Single Data disconnect | remove from bundle; continue |

## 6. Client behavior (PoC)

1. On play JOIN, if PoC enable: resolve two endpoints from local config, connect
2. Send BindRequest(token, channelId, protocol)
3. On BindAck ok: mark channel ready
4. On BulkCompressedChunk: decrypt → feed existing `ClientChunkHandler.handleCompressedChunk` (or equivalent path used by Primary compressed payload)
5. Do not change C2S paths
6. On primary disconnect: tear down all Data clients

Demux must land on the **same** handlers as Primary bulk so cache / light / apply paths stay identical.

## 7. Testing

### Unit (`common:test`)

| Test | Invariant |
|------|-----------|
| `DataPlaneFrameTest` | encode/decode round-trip; length = 1+payload |
| `DataPlaneCodecTest` | encrypt/decrypt round-trip; wrong key fails; different channelId → different key |
| `BulkRouterTest` | WRR distribution ~ weights; share-only-Primary → false; exclusive empty → true (drop); 3 drops → degraded; degraded → false |
| `PlayerChannelBundleTest` | add/remove; degraded flip; consecutiveDrops |

### E2E smoke (fabric 1.20.1)

| Step | Signal |
|------|--------|
| 1 | Server log bound two ports |
| 2 | BindAck ok both channels; bundle size=2 |
| 3 | share WRR log counts Primary vs Data |
| 4 | Kill one Data → bundle size=1; routing continues |
| 5 | exclusive, both Data down → 3 drops → degraded → bulk still arrives via Primary (**hard assert**) |
| 6 | Primary disconnect → all Data closed |
| 7 | `enabled=false` → zero DATA_PLANE logs; vanilla Primary path |

**Done when:** `common:test` green + steps 1–7 observed; step 5 hard-asserted.

## 8. Config surface (PoC only)

Not `server.toml` formal section. Temporary PoC flags (system property or small Java constants class under `dataplane.poc`):

```text
enabled = true/false
bulkRouteMode = share | exclusive
primaryWeight = 100
endpoints = [
  { address=127.0.0.1, port=25566, weight=50, bindHost=0.0.0.0, bindPort=25566 },
  { address=127.0.0.1, port=25567, weight=50, bindHost=0.0.0.0, bindPort=25567 },
]
bindToken = 16 fixed bytes
client.enableDataPlane = true
DEGRADE_AFTER_DROPS = 3
```

Field names mirror parent §4 so later migration is rename + wire, not redesign.

## 9. Security notes (PoC)

- Fixed token is **dev-only**; never ship as production default
- Unbound connections: short read timeout (e.g. 5s) then close
- Bind fail → close immediately
- Production token/TTL/UUID binding arrives with handshake step

## 10. Implementation order (this PoC only)

1. common: `DataPlaneFrame` + unit test
2. common: `Hkdf` + `DataPlaneCodec` + unit test
3. common: `PlayerChannelBundle` + `BulkRouter` + unit tests
4. common: `DataPlaneServer` accept/Bind (no routing yet)
5. fabric: lifecycle bind/shutdown + PoC config
6. fabric: intercept `sendCompressedChunk` → BulkRouter
7. common+fabric: `DataPlaneClientBundle` + JOIN hook + demux
8. E2E share then exclusive degrade path
9. Cleanup: ensure `enabled=false` zero behavior change

## 11. Open items for post-PoC (not blocking)

- ~~BouncyCastle version pin after multi-version Gradle conflict check~~ **已决策（see §2/§12）：PoC 用纯 JDK 手写 HKDF-SHA256，零第三方加密依赖。** 若未来切 BC 仅替换 `Hkdf` 内部实现，调用方不变
- True exclusive queue with `exclusiveWaitMs` without blocking push pool
- Handshake endpoint/token/mode; protocol version bump
- SectionDelta + metrics + multi-loader

## 12. Approval trail

Brainstorming (2026-07-25):

1. Scope: PoC only (§14 step 1)
2. Dual port: independent bare ports
3. Crypto: full HKDF+AES/CFB8 in PoC
4. HKDF: third-party library
5. Anchor: 1.20.1 fabric
6. Design §1–§5 approved (goals, modules, dataflow, exclusive simplify, tests)
7. PoC 细化（2026-07-25）：HKDF 纯 JDK 手写（零第三方加密依赖）；`DataPlaneServer` bind/shutdown 复用 `MixinMinecraftServer` 既有钩子

---

Parent research doc remains the long-term target architecture. This file is the **implementation contract for the PoC slice only**.
