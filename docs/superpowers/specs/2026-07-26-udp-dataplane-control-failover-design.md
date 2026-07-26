# UDP Data Plane and TCP Control Failover Design

Date: 2026-07-26

## 1. Goal

Replace the current extra TCP data-plane ports with reliable UDP data-plane endpoints while keeping the vanilla Minecraft TCP Play connection as the only control-plane transport. Add automatic failover between multiple configured TCP entrypoints without presenting a terminal disconnect UI to the client.

Target outcome:

- A client can start from one or more TCP entrypoints, for example `a.com:25565`, `b.com:25565`, and `c.com:25565`.
- One TCP entrypoint is the active master control connection. The others are cold backup entrypoints, not simultaneous Minecraft Play connections.
- The server advertises one or more public UDP endpoints, for example `a.com:25565`, `b.com:25566`, and `c.com:25567` over UDP. Each endpoint has an independent reliable-UDP session and weight.
- Chunk bulk traffic is sent over weighted reliable UDP. Control traffic, player actions, login, and all vanilla packets stay on the active TCP master.
- When the TCP master closes or is confirmed stalled, the client reconnects through the next TCP candidate, retains its disk cache, and suppresses the ordinary terminal disconnect flow while recovery is in progress.
- The server does not implement player-state handoff. A new TCP master is a normal Minecraft login. The cache/hash path minimizes reloading after it succeeds.

## 2. Explicit Boundaries

### 2.1 What this solves

- A failed TCP entrance can be replaced by another address/path to the same Minecraft server.
- A failed or congested UDP endpoint is removed from weighted routing without blocking other UDP endpoints.
- Large chunk traffic does not queue behind control traffic on the original Minecraft TCP connection.
- A reconnect can reuse the persisted Hassium chunk cache and existing hash comparison path.

### 2.2 What this cannot solve

Minecraft removes `ServerPlayer` when its Play TCP connection closes. Hassium cannot keep the player server-side alive without implementing a separate session-handoff system. Therefore:

- UDP can continue delivering already queued or in-flight data for a short lease only.
- The server cannot create new player-specific bulk data after that player is logged out.
- The client may freeze input and show recovery state, but it must not claim that the original vanilla Play session survived.
- Cache persistence reduces post-login reload work; it does not preserve an in-memory `ClientLevel` across a vanilla reconnect.

This design is the approved medium-depth recovery: transparent reconnect plus cache warm resume, not server-side session transfer.

## 3. Topology

```text
Client                                                        Minecraft server

TCP master: a.com:25565  -----------------------------------> vanilla TCP :25565
TCP backups: b.com:25565, c.com:25565 ----------------------> same vanilla TCP :25565

UDP/KCP: a.com:25565  --------------------------+           UDP endpoint 1
UDP/KCP: b.com:25566  --------------------------+----------> UDP endpoint 2
UDP/KCP: c.com:25567  --------------------------+----------> UDP endpoint 3
                                                   \         all terminate in one
                                                    \        DataPlaneUdpServer subsystem
```

The TCP addresses may be separate public routes, proxies, or network uplinks that terminate at the same Minecraft TCP listener.

The UDP data plane is logically multi-path, not NIC-affinity dependent. `DataPlaneUdpServer` owns a group of independent public UDP endpoint bindings. Endpoint `N` has a separate KCP session, NAT mapping, health sample, and WRR weight. The implementation may bind all endpoints on one host or to different local addresses; correctness never assumes a one-to-one physical NIC mapping.

A single UDP socket cannot represent the advertised `a:25565`, `b:25566`, and `c:25567` endpoints. The server must bind every configured public `(bindHost, bindPort)` pair. “Logical multi-path” means these endpoints are not required to model physical NIC failure domains.

## 4. Control Endpoint Discovery

### 4.1 Client-owned bootstrap list

Each multiplayer-server entry gains Hassium-owned control endpoint metadata:

```text
bootstrapControlEndpoints = [
  { host = "a.com", port = 25565, priority = 100 },
  { host = "b.com", port = 25565, priority = 80 },
  { host = "c.com", port = 25565, priority = 60 }
]
```

The first endpoint is used for the initial standard Minecraft connect. The remaining endpoints are cold candidates. They are not opened as extra Play connections.

### 4.2 Server-advertised candidates

A successful Hassium handshake can advertise additional TCP control candidates. This supports a client configured with only `a.com:25565`.

The client stores them in a Hassium endpoint cache scoped to the multiplayer-server entry and its resolved server identity. It must not silently rewrite the vanilla multiplayer list. User-configured endpoints remain authoritative; advertised endpoints supplement the current session and are persisted only in Hassium metadata.

The client rejects malformed endpoints, duplicates, and endpoint lists over a fixed cap. Defaults:

```text
maxControlEndpoints = 4
maxUdpEndpoints = 4
```

## 5. Transport

### 5.1 Reliable UDP requirement

Raw UDP is not permitted for chunk bulk. Chunk payloads exceed normal MTU and need loss recovery, congestion control, fragmentation/reassembly, duplicate suppression, and backpressure. The data plane therefore uses a tested KCP-compatible reliable datagram implementation in message mode.

The implementation boundary is `ReliableDatagramSession`; the first implementation is KCP. This boundary exists because the code must not leak KCP packet details into chunk routing or Minecraft-version adapters.

KCP is configured with congestion control enabled, bounded send and receive windows, MTU below path MTU, fast retransmit, and a bounded reassembly budget. A KCP session is independent per `(player UUID, UDP endpoint id, remote address, connection epoch)`.

KCP's ordered delivery is accepted in the first version. A lost chunk can delay later frames on that same endpoint, but not frames routed through other endpoints. Separate reliable streams are an optional future QUIC migration, not part of this design.

### 5.2 Encryption and anti-replay

The existing TCP data-plane AES/CFB8 framing must not be copied unchanged. UDP is trivially spoofed and CFB8 has no integrity protection.

After bind, each application frame is encrypted and authenticated with JDK `AES/GCM/NoPadding`:

```text
key = HKDF-SHA256(
  ikm  = udpSessionToken,
  salt = playerUUID || connectionEpoch,
  info = "hassium-udp-v1" || endpointId || channelId
)[16]
```

The plaintext includes a monotonically increasing application-frame sequence. The GCM nonce derives from direction plus that sequence. Retransmission reuses the exact ciphertext. Each direction maintains a replay window. KCP headers remain routable but application payloads are authenticated before dispatch.

BindRequest and BindAck are the only pre-AEAD frames. Bind token mismatch, stale epoch, duplicate bind, or a bind deadline expiration closes the session.

### 5.3 Datagram frames

KCP carries the existing logical data-plane frame types:

| Type | Direction | Name | Payload |
|---|---|---|---|
| 1 | C2S | BindRequest | `token[16] + uuid[16] + connectionEpoch[long] + protocol[varint] + channelId[varint]` |
| 2 | S2C | BindAck | `ok[bool] + reason[utf] + endpointId[varint]` |
| 3 | S2C | BulkCompressedChunk | Existing compressed chunk payload |
| 4 | S2C | BulkSectionDelta | Existing section-delta payload |
| 5 | S2C | KeepAlive | `nonce[long]` |
| 6 | C2S | KeepAliveAck | `nonce[long]` |
| 7 | S2C | Close | `reason[utf]` |
| 8 | C2S | FailoverRequest | `connectionEpoch[long] + requestedEndpointId[varint]` |
| 9 | S2C | FailoverPermit | `connectionEpoch[long] + expiryMs[long]` |

`connectionEpoch` is server-generated for every accepted TCP master. It prevents an old UDP lease from taking over a newer control connection.

## 6. Master Election and Failover

### 6.1 State model

```text
CONNECTED
  active TCP master and one or more bound UDP/KCP sessions

TCP_CLOSED_RECOVERY
  TCP channel closed; client retains cache and immediately tries next control endpoint

TCP_STALLED_PROBING
  TCP still open but no control progress; UDP is healthy and sends FailoverRequest

FAILOVER_PERMITTED
  server closed the old master and authorized a short reconnect lease

RECONNECTING
  standard Minecraft Login through next candidate; retry remaining candidates by priority

RECOVERED
  new TCP master accepted; handshake issues a new connectionEpoch and UDP token

TERMINAL_DISCONNECT
  every candidate failed or recovery deadline elapsed; ordinary disconnect UI resumes
```

### 6.2 Election rules

The client must never open a second Minecraft Play connection merely because RTT is high. Duplicate Play login can kick the active player and turn jitter into a disconnect loop.

Election is permitted only when one of these conditions holds:

1. The active Minecraft TCP channel reaches `channelInactive` or an equivalent hard-disconnect callback. The client immediately moves to `TCP_CLOSED_RECOVERY`.
2. The active TCP channel has no observed control progress for `controlStallMs` while authenticated UDP KCP remains healthy. The client sends `FailoverRequest` through KCP and enters `TCP_STALLED_PROBING`.

The server verifies `FailoverRequest` against the player UUID, UDP session, active connection epoch, and its own inbound-control activity timestamp. If the master was inactive for at least `controlStallMs`, it closes the old Minecraft `Connection`, creates a bounded `FailoverLease`, and replies with `FailoverPermit` over KCP. The client may then connect the next TCP candidate.

Default values:

```text
controlStallMs = 6000
failoverPermitTtlMs = 10000
controlReconnectAttemptMs = 5000
maxControlReconnectAttempts = endpoint count
udpLeaseAfterPrimaryCloseMs = 10000
```

The previous proposed “2 second latency gate” is rejected. It is too short for normal Internet jitter and unsafe without server-authorized close.

### 6.3 UDP lease behavior

Current code closes all data channels when Primary disconnects. This must change:

- On intentional or unintentional master loss, `DataPlaneSessionRegistry` retains valid UDP sessions for `udpLeaseAfterPrimaryCloseMs`.
- During the lease, only already queued frames may drain. The bulk producer must not enqueue new player-specific work for a removed player.
- A successful new TCP handshake replaces the epoch and binds fresh UDP sessions. Old epoch sessions are closed.
- Lease expiry closes the UDP sessions and clears the registry.

## 7. Client Recovery and Cache Reuse

The current disconnect path always reaches `MixinMinecraft` and `ClientLifecycleHelper.finalizeDisconnect()`, which shuts down the cache executor and storage. That behavior is correct for user logout and terminal failure but wrong for automatic failover.

Introduce `ClientRecoveryState` with `NONE`, `RECOVERING`, and `TERMINAL` states.

On automatic failover:

1. Set `RECOVERING` before the vanilla disconnect callback can finalize Hassium.
2. Suppress the terminal disconnect screen and show a non-interactive recovery overlay.
3. Permit vanilla to tear down the old network world. Do not retain or mutate the old `ClientLevel` after its listener has closed.
4. Skip `ClientLifecycleHelper.finalizeDisconnect()` while `RECOVERING`; keep `ClientHassiumStorage`, `SectionHashStore`, cache queues, and their executor alive.
5. Trigger the standard Minecraft connect path for the next TCP candidate.
6. On new handshake, initialize the new world normally. `ChunkHashS2C` compares against the existing disk cache, so matching chunks are loaded locally and only mismatches request bulk data.
7. On recovery success, replace old UDP sessions with new-epoch sessions and clear `RECOVERING`.
8. On exhaustion or deadline, set `TERMINAL`, execute the ordinary finalization path once, and present the actual disconnect reason.

The client intentionally does not promise retained in-memory rendered chunks during the vanilla world replacement. The recovery overlay and warm disk cache are the supported continuity contract.

## 8. Routing

`BulkRouter` keeps its existing meaning: `true` means the data-plane absorbed the frame or intentionally dropped it; `false` means send through Primary.

Replace `PlayerChannel` TCP candidates with `ReliableDatagramSession` candidates.

```text
tryRouteBulk(player, frameType, payload):
  bundle = sessions[player UUID, current epoch]
  if bundle absent: return false

  candidates = healthy, writable KCP sessions
  if share mode and primary writable: add virtual Primary candidate

  selected = weightedRoundRobin(candidates)
  if selected is Primary: return false

  selected.enqueueAuthenticated(frameType, payload)
  return true
```

A session is excluded when it is unbound, KCP backpressured, beyond the RTT hard limit, has an invalid replay state, or has crossed the consecutive-loss threshold. Health affects effective weight but must not allocate on the hot path.

Failure behavior stays compatible with the existing PoC:

| Mode | No usable UDP candidate |
|---|---|
| `share` | return `false`; use TCP Primary |
| `exclusive` | drop three consecutive bulk frames, then mark degraded and return `false` for the rest of the session |

Master loss is different from a UDP failure: during the failover lease there is no valid Primary fallback. The server drains already accepted UDP frames; subsequent player bulk waits for the new login.

## 9. Handshake Extension

Extend the existing Hassium handshake tail. Fields are append-only and guarded by `isReadable()`.

C2S:

```text
udpDataplaneSupported: bool
controlFailoverSupported: bool
```

S2C:

```text
hasUdpDataplane: bool
hasControlFailover: bool
connectionEpoch: long
controlEndpointCount: varint
  repeat: host:utf, port:unsigned-short, priority:varint
udpEndpointCount: varint
  repeat: host:utf, port:unsigned-short, weight:varint, endpointId:varint
udpSessionToken: byte[16]
udpProtocol: varint
```

The server announces UDP only when the feature is enabled, the client supports it, at least one configured UDP endpoint bound successfully, and the session token is available.

No main Hassium protocol-version bump is required. Older clients ignore the tail and remain TCP Primary-only. The old TCP data-plane protocol is removed on the full migration; it is not kept as a runtime fallback.

## 10. Component Boundaries

| Component | Module | Responsibility |
|---|---|---|
| `DataPlaneUdpServer` | common | Bind configured UDP sockets, own KCP session table, route decoded frames |
| `ReliableDatagramSession` | common | KCP framing, AEAD, sequencing, KCP metrics, send backpressure |
| `DataPlaneSessionRegistry` | common | Player/epoch/endpoint session lifecycle and failover lease |
| `UdpBulkRouter` | common | Candidate health and WRR selection; preserves current `BulkRouter` caller contract |
| `ControlEndpointManager` | common client | Bootstrap/advertised candidate merge, selection, reconnect attempts |
| `ClientRecoveryState` | common client | Suppress terminal cleanup during recovery and issue one final cleanup on failure |
| `ControlFailoverHandler` | common server | Track inbound control progress, validate requests, close old master, issue permit |
| Loader network managers | fabric/neoforge | Encode/decode handshake tail and invoke common lifecycle APIs |

Replace the existing TCP-bound `DataPlaneServer` with the UDP subsystem while keeping the caller-facing surface stable.

| Existing TCP element | UDP replacement | Caller impact |
|---|---|---|
| `DataPlaneServer.bind()` opening `NioServerSocketChannel` per endpoint | `DataPlaneUdpServer.bind()` opening one `DatagramChannel` per advertised UDP endpoint | none; `MixinMinecraftServer` keeps the same `bind()/shutdown()` call |
| `DataPlaneChannelInitializer` + `BindHandshakeHandler` (TCP framed) | `DatagramBindHandler` decoding the first BindRequest into a `ReliableDatagramSession` | none |
| `DataPlaneServer.tryRouteBulk(UUID, int, byte[])` writing encrypted `ByteBuf` to a `PlayerChannel.channel` | `DataPlaneUdpServer.tryRouteBulk(UUID, int, byte[])` delegating candidate selection to `UdpBulkRouter` and `ReliableDatagramSession.enqueueAuthenticated` | signature preserved; loader `ChunkSender` adapters unchanged |
| `PlayerChannel` (TCP Channel + aesKey + portIdx) | `ReliableDatagramSession` (KCP + AEAD + endpointId + epoch) | internal; BulkRouter candidates only |
| `onPrimaryDisconnect(UUID)` removing the bundle | split: bounded lease retention in `DataPlaneSessionRegistry` plus explicit close on new epoch | call-site semantics change as §6.3 states |
| AES/CFB8 + `DataPlaneCodec` per-channel key | JDK AES/GCM + HKDF(playerUUID || connectionEpoch) per epoch; replay window | internal; frame types in §5.3 unchanged |

Do not modify `MixinConnection` for whole-packet routing. Do not put loader APIs in `common`. Do not alter `ServerChunkPushManager` business logic; loader `ChunkSender` adapters retain the routing insertion point.

## 11. Configuration

Production configuration under `[network.dataPlane]`:

```toml
enabled = false
transport = "kcp-udp"
bulkRouteMode = "share"
primaryWeight = 100
controlStallMs = 6000
failoverPermitTtlMs = 10000
udpLeaseAfterPrimaryCloseMs = 10000
maxControlEndpoints = 4
maxUdpEndpoints = 4

[[network.dataPlane.udpEndpoints]]
address = "a.com"
port = 25565
weight = 100
bindHost = "0.0.0.0"
bindPort = 25565

[[network.dataPlane.udpEndpoints]]
address = "b.com"
port = 25566
weight = 100
bindHost = "0.0.0.0"
bindPort = 25566
```

`address` and `port` are advertised to clients; `bindHost` and `bindPort` are server-local. The server must reject duplicate bind pairs and invalid weights before startup.

Control endpoint advertisement is configured separately from UDP endpoints because TCP and UDP routes may not share port or proxy topology.

## 12. Verification

### Unit tests

- KCP message round trip, MTU fragmentation, loss/reorder/retransmit, and bounded reassembly.
- AEAD round trip, modified ciphertext rejection, replay rejection, and per-epoch key separation.
- Bind rejects stale token, duplicate channel, expired epoch, and invalid endpoint id.
- WRR distributes frames by endpoint weight and removes unhealthy sessions.
- `FailoverRequest` rejects healthy master, stale epoch, wrong player, and expired UDP lease.
- `ClientRecoveryState` skips cleanup only while recovery is active and finalizes exactly once on terminal failure.

### 1.20.1 Fabric end-to-end smoke

1. Start server with three advertised UDP endpoints and two or more TCP control candidates.
2. Verify all UDP endpoint binds and authenticated KCP binds.
3. Verify WRR sends bulk through multiple UDP endpoints.
4. Drop one UDP path; verify remaining paths continue.
5. Drop all UDP paths in `exclusive`; verify degraded then TCP Primary fallback.
6. Hard-close master TCP; verify no terminal disconnect UI, automatic backup TCP login, new UDP epoch bind, and chunk-hash cache hits after reconnect.
7. Stall master TCP while UDP stays healthy; verify server-authorized `FailoverRequest` / `FailoverPermit`, then backup login.
8. Exhaust TCP candidates; verify one final cleanup and normal disconnect UI.
9. Disable the feature; verify no UDP bind, no handshake data-plane side effect, and existing Primary behavior.

Only after the Fabric anchor passes should the feature be adapted across the nine version segments and NeoForge. Forge remains out of scope unless separately approved.

## 13. Migration and Non-goals

- The existing TCP data-plane PoC is replaced, not retained as a permanent dual transport.
- Existing control-plane ZSTD, aggregation, compact headers, chunk hash, section delta, and light cache behavior are unchanged.
- Server-side player-state handoff, proxy-aware public endpoint discovery, physical NIC-affinity guarantees, QUIC multi-stream migration, and Forge support are not part of this design.
