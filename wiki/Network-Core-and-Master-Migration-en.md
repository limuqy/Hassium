# Network Core and Master Migration

---

> **简体中文**: [Network-Core-and-Master-Migration](Network-Core-and-Master-Migration) · English

## What this is

**Network Core** and **master migration** are Hassium 2.0.0's client-side networking architecture: in the client process, the **Network Core** (NetworkCore, `network/core/`) fully takes over all traffic to and from the master, so the world side only ever sees vanilla protocol; on the server process, the **Master Core** (`network/gateway/` gateway + server-side chunk push) provides the matching ingress. Together they deliver three things:

| Capability | Description |
| --- | --- |
| **In-process gateway** | Client↔world side is pure vanilla protocol (zero compression / zero aggregation / zero custom packets); Network Core injects S2C via direct handler calls and funnels C2S through `routeC2S` |
| **Seamless master migration** | Switch outbound + resume ticket (epoch anti-replay) + `resumeAccepted`; the client's Connection never drops and the world never reloads |
| **L1 load balancing** | Four trigger classes — fault / load thresholds / maintenance window / drill; prewarm + idle window |

The pre-2.0.0 disconnect-and-reconnect scheme is retired: the client no longer keeps a candidate-endpoint table and no longer shows recovery UI. Architecture decisions and the deletion list live in the [network-core handoff](../docs/handoff/handoff-2026-08-09-network-core.md); older research is archived in [docs/archive/](../docs/archive/).

---

## In-process gateway architecture

### Client: pure vanilla protocol on the world side

The Network Core takes over all client-side networking; the connection visible to the world side (the Minecraft game thread) is just a "shell":

- **C2S funnel**: during PLAY, client outbound packets are intercepted by `MixinConnection` and routed into the gateway's own channel via `NetworkCore.routeC2S`; the shell connection only carries vanilla keep-alive responses over TCP.
- **S2C injection**: inbound frames are decoded and the Network Core injects the resulting vanilla `Packet` objects directly at the handler layer into the vanilla listener (`NetworkCore.dispatchS2C`).
- **Zero compression / zero aggregation / zero custom packets**: the client↔world side carries no Hassium compression, aggregation, or custom protocol bytes at all — pipeline-layer mods and handler-layer mods both stay compatible.

### Master: gateway channel

- The gateway frame connection (`GatewayChannel`) **is** the control connection and carries all player traffic; ZSTD is mounted **outside** the frame protocol (alongside `ControlFrameCodec` — client `OutboundConnection.installZstd` / master `GatewayChannel.installZstd`, reusing the `master.globalCompression*` thresholds and levels).
- **Aggregation is a master-side vanilla path only**: packet aggregation hooks vanilla `Connection.send` (`MixinConnection` applies it only to server-side player listeners); the gateway channel never aggregates.
- The **UDP data plane** (`network/dataplane/`) is fully retained as the bulk carrier for the gateway↔master channel, off by default (`dataplane.enabled = false`).

### Topology overview

| Connection | Carries | Protocol |
| --- | --- | --- |
| Client world-side shell | Keep-alive responses + handler-injected vanilla packets | Pure vanilla TCP (zero compression/aggregation/custom packets) |
| Gateway↔master own channel | All player traffic (frames + ZSTD) | Frame protocol (ControlFrameCodec), ZSTD outside frames |
| UDP data plane (optional) | Bulk chunk carrier | UDP (`network/dataplane/`, off by default) |

---

## Seamless master migration

Migration = the client **switches outbound** (the gateway frame connection moves from master A to master B), with the `NetworkCore` state machine `ACTIVE → MIGRATING → ACTIVE`. The vanilla connection never drops, no disconnect/loading UI appears, and the world does not reload.

### Resume ticket (ResumeTicket)

The migration handshake carries a **resume ticket** (`ResumeTicket`, `network/` package):

- **Construction**: `playerId` (16B) + `epoch` (8B BE) signed with HMAC-SHA256, keyed by the master A/B shared secret (`ResumeTicket.setSharedKey`).
- **Anti-replay**: `epoch` increases monotonically over the client process lifetime (starting at 1, never reset on login); the master's `ResumeTicketValidator` keeps the last accepted epoch per player (table never cleared across sessions) and only accepts tickets that verify AND carry a strictly newer epoch — replayed old tickets are always rejected.
- **Handshake result**: the `HandshakeStateTail` S2C tail carries `resumeAccepted`:
  - `resumeAccepted = true` (resume ready): the master reuses the UUID-keyed push chain for the ticket's player identity (`ServerChunkPushManager.markPlayerResumeActive`), data push flows straight in, and the client's disk cache and task executor carry over untouched;
  - `resumeAccepted = false` (session not attached): no ticket / verification failure / replay — the session waits for the login bridge (`GatewayPlayerBridge.attachPlayer`), data push does not flow, falling back to the login-bridge / reconnect path.

### Zero client reload

Throughout migration the vanilla `Connection` state is preserved and the world keeps running; takeover completes imperceptibly. Even a failed resume degrades only to the login-bridge fallback, never to a full reload.

---

## L1 load balancing

`MigrationEngine` (`network/core/migration/`) is the L1 migration engine: trigger decision + migration orchestration + prewarm + idle window. Four trigger classes:

| Trigger | Condition | Behavior |
| --- | --- | --- |
| **Fault** | Outbound inbound silence exceeds the effective timeout (default `master.migrationSilentTimeoutMs`=10000; explicitly changing `migrationFaultTimeoutMs` can fall back to that legacy key); a heartbeat thread sends HEARTBEATs every `migrationHeartbeatIntervalMs` (default 5000) | No prewarm; direct `migrateToImmediate` |
| **Load thresholds** | Master load report (`ServerLoadReporter`): TPS < `migrationMinTps` (default 15.0) or system load average > `migrationMaxLoadAverage` (default 4.0) | Policy migration (prewarm) |
| **Maintenance window** | `migrationMaintenanceWindow` ("HH:MM-HH:MM", local timezone, midnight-crossing supported; empty = disabled): always triggers while inside the window | Policy migration (prewarm) |
| **Drill** | Client `/hassium migrate list\|status\|<host:port>` (`NetworkCore.migrateTo`) | Policy migration (prewarm) |

### Prewarm + idle window

- **Prewarm** (`PrewarmSession`, `prewarmEnabled` default true): before migrating, connect to the target master first and establish the player session with a resume ticket — the B side materializes the player and pre-syncs (`resyncTrackedChunks`); migration then takes over that connection directly, so the delta approaches zero.
- **Idle window** (`IdleWindowDetector`): the player is stationary (movement below threshold) and chunk hashes are stable (delta converged) → a good moment to migrate. Load / maintenance / drill paths prefer the idle window; the fault path is not restricted by it.

---

## Config keys

> Gateway listen port reuses `master.controlReachableEndpoints[0]`; key families follow the 2026-08-09 restructure: network core `net.*` (client) / master core `master.*` / data plane `dataplane.*`. Most L1 policy keys live in **client.toml** (CLIENT scope).

| Key | Default | Notes |
| --- | --- | --- |
| `net.enabled` | `true` | Master switch for the client network core (in-process gateway and optimized channels) |
| `master.enabled` | `true` | Master switch for the server-side network channels |
| `master.controlReachableEndpoints` | `[]` | **Server** master gateway listen/advertise addresses; port from `endpoints[0]`, **else `25566`**; synced to the client via handshake tail and `GatewayInfo` (**clients do not fill this**) |
| `master.bindHost` | `127.0.0.1` | Gateway bind host (loopback by default; empty = `0.0.0.0`) |
| `master.authToken` | `""` | Gateway handshake auth (empty = off); may be delivered by `GatewayInfo` |
| `master.compressionLevel` | `3` | Custom-channel ZSTD compression level |
| `master.globalPacketCompression` / `globalCompressionLevel` / `globalCompressionThreshold` | `true` / `3` / `256` | Global compression; also the gateway ZSTD install source |
| `dataplane.enabled` | `false` | UDP data-plane master switch (**off by default**) |
| `master.migrationSilentTimeoutMs` | `10000` | Outbound inbound silence timeout (effective default; fail detection ≤15s) |
| `master.migrationFaultTimeoutMs` | `60000` | Legacy fault-timeout fallback (used when this key is changed and silent stays at default) |
| `master.migrationMinTps` / `migrationMaxLoadAverage` / `migrationMaintenanceWindow` | `15` / `4` / `""` | Policy triggers (CLIENT) |
| `master.migrationHeartbeatIntervalMs` / `migrationIdleWindowMs` | `5000` / `10000` | Heartbeat / idle window (CLIENT) |
| `master.migrationPrewarmTtlMs` | `60000` | Prewarm session TTL (SERVER) |
| `master.resumeTicketTtlMs` | `300000` | Resume ticket TTL (dual-scope) |

Notes:

- Client migration candidate endpoints = master handshake-tail `controlEndpoints` + login-phase `GatewayInfo` (persisted to `failover-endpoints.properties`); **not** filled by hand in client.toml. Drill entry = `/hassium migrate`.
- Former `dataPlane` / `controlStallMs` / `failoverExpiryMs` / `recoveryFreeze` keys were removed.
- Configuration lists operator/player common keys only; full `ConfigSchema` detail keys (aggregation / dynamic pools / hot-score eviction, etc.) stay code-authoritative and are not fully expanded in the wiki.

---

## Related pages

[← Support-Matrix](Support-Matrix-en) · [Home](Home-en) · [→ FAQ](FAQ-en)
