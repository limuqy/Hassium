# Control Failover and Weighted Routing

---

> **简体中文**: [Data-Plane-and-Failover](Data-Plane-and-Failover) · English

## What this is

**Control failover** and **weighted routing** are two Hassium networking features that solve the "freeze / disconnect / rejoin" experience players hit when the master connection hiccups on multiplayer servers, and share the chunk-download load when a busy server saturates its bandwidth. The two work together: failover keeps players almost unaware when the master connection has issues, and weighted routing spreads chunk-download traffic across multiple lines.

These are two of the capabilities listed on the Home page:

| Feature | Description |
| --- | --- |
| **Control failover** | On TCP-control stall or drop, auto-reconnect via candidate endpoints with a frozen on-screen world during recovery (tick paused, transition screens hidden, 1.20.1 segment), warm cache, and hidden disconnect screen (data-plane failover) |
| **Weighted routing** | Multiple UDP/KCP endpoints carry the data plane by weighted round-robin; control plane stays on vanilla TCP |

---

## The problems this solves (examples)

**Problem 1: The master connection hiccups, everyone drops to the main menu.**

In vanilla Minecraft, login, chat, commands, and entity sync all ride one TCP master Play connection, and chunk downloads share that same line. When the master connection stalls for a few seconds (network jitter, server restart, machine migration) or drops, the client shows a "Connection lost" screen, kicks players to the main menu, loses cache, and rejoining means re-downloading every chunk. A few players are deep in a cave; the owner restarts for routine maintenance; everyone's progress looks "wasted".

**Control failover** does this: when the master connection stalls or drops, the client follows the candidate list the server pre-delivered and auto-connects to the next reachable endpoint, **without showing a disconnect screen**. Already-downloaded chunk cache and the task executor are kept; the new session resumes directly — the explored terrain is still in cache, hit ratio does not drop. **The 1.20.1 segment offers two recovery styles** (`network.dataPlane.recoveryFreeze`, client-side, default true): freeze mode — world tick pauses, transition screens (connect/loading/receiving-world) are hidden at the render layer, the screen keeps the frozen world plus a "Switching master…" overlay, and motion resumes once recovery succeeds, no loading screen ever visible; seamless mode (false) — the world keeps running, player actions take effect locally but never reach the server, and after recovery the position snaps back to the disconnect point and freshly-mined blocks revert — it feels like a sudden latency spike with a small rollback, no switching UI at all.

**Problem 2: Hundreds of players log in at once and the master connection saturates.**

The bottleneck on high-population servers is often chunk downstream: every player pulls a patch of terrain and one line cannot keep up. Scaling out also means worrying that one flaky line will take the server with it.

**Weighted routing** does this: chunk downloads run on a UDP/KCP data plane that can be configured with multiple endpoints (multiple lines), sharing load by `weight` (weighted round-robin). If one line fills or degrades, traffic shifts onto the rest. Login and commands — "control-class" traffic — stay on vanilla TCP and are untouched by data-line issues.

---

## Who should enable this

These two features are **off by default** — `network.dataPlane.enabled = false`, and the mod uses vanilla single-TCP behavior with normal co-op untouched. When you need control failover or public weighted routing, enable them as follows:

1. Server-operator capability with Nginx / public-firewall / NAT rules;
2. Set `network.dataPlane.enabled = true` in `hassium-server.toml` and configure reachable public endpoints;
3. Verify the six self-check markers in order (see the bottom of this page).

> ⚠️ The production env config for this feature is still being migrated; it is currently driven by `DataPlanePoCConfig` as a stopgap. Operators without these capabilities can leave it off — the other features are unaffected.

For **solo friend co-op** or **small private servers**: leave it off and skip the rest of this page. The technical details below target server operators with ops needs.

---

## Technical details

The following targets server operators with ops capability. Regular players can skip to [FAQ](FAQ-en) for common questions.

### Topology

| Plane | Purpose | Protocol |
| --- | --- | --- |
| **Control plane (Master TCP)** | Vanilla Minecraft login + Play Connection | TCP |
| **Data plane (UDP/KCP)** | Chunk bulk and data-plane fan-out | UDP/KCP (independent sessions with KCP `ReliableDatagramSession`) |

- The control-plane endpoint list is delivered via S2C handshake tail (host:port + priority)
- The client mixes bootstrap + advertised endpoints, sorted by priority descending, up to 4 candidates (`ControlEndpointManager.MAX_CANDIDATES`)
- Each `(host, port)` UDP endpoint binds an independent KCP `ReliableDatagramSession`
- The client issues a separate BindRequest per advertised endpoint with an HKDF-derived AES-GCM key

### Triggers

**Hard disconnect**: the Master TCP `channelInactive` → `ControlReconnectOrchestrator.onPrimaryDisconnected` immediately launches the next candidate; the client enters a 60-second recovery window.

**Master stalled + UDP healthy**: the server detects a control stall (default 6 seconds). During the stall, `DataPlaneUdpServer.recordControlActivity` advances; if the UDP session is healthy (matching epoch) the server issues a `FailoverPermit` (`expiryMs` default 30 seconds). The client only connects via `attemptConnectOnlyIfPermitValid`.

### Recovery retention

When `ClientRecoveryState.shouldSuppressFinalization()` is true, `ClientLifecycleHelper.finalizeDisconnectIfTerminal` short-circuits `finalizeDisconnect`, preserving:

- Disk cache
- `CacheSaveQueue`
- `HassiumTaskExecutor`
- Dirty flags

When the next candidate session starts, the cache is ready to use and the hit ratio does **not drop**.

`ClientPlayConnectionEvents.DISCONNECT` calls `DataPlaneClientLifecycle.stopUdp(/*keepLease*/ true)`, so the UDP bundle is not released immediately.

### Candidate exhaustion

`ControlReconnectOrchestrator.performTerminalFinalization` calls `ClientLifecycleHelper.finalizeDisconnectIfTerminal`; the singleton `ClientRecoveryState.consumeTerminalCleanup` guarantees exactly-once disk-resource cleanup.

### Weighted routing

The data plane supports multiple UDP endpoints sharing chunk-bulk traffic by `weight` (weighted round-robin):

- One `ReliableDatagramSession` per endpoint
- WRR over `weight`
- `share` / `exclusive` routing modes
- UDP health feeds into weight adjustment (degraded demotion)

---

## Config keys

| Key | Default | Notes |
| --- | --- | --- |
| `network.dataPlane.enabled` | `false` | Data-plane master switch (off by default; configure reachable endpoints and verify the six self-check markers in order before enabling) |
| `network.dataPlane.controlStallMs` | `6000` | Stalled-master duration before the client sends `FailoverRequest` |
| `network.dataPlane.failoverPermitTtlMs` | `30000` | Validity window for the server-issued `FailoverPermit` |
| `network.dataPlane.udpEndpoints` | (pending toml) | Candidate list; each item has `host`, `port`, `weight`, optional `priority` |

> `udpEndpoints` is currently delivered via the S2C tail; it cannot be hand-edited in `hassium.toml` until the production migration lands.

---

## Operations checklist

1. Each public `udpEndpoints` endpoint needs a public-UDP firewall/NAT rule
2. The 10-second UDP `lease` only drains in-flight data; no new player data is produced before login completes
3. `controlStallMs` requires the server to issue `FailoverPermit`; the client does not open a second master Play connection based on latency alone
4. TCP control endpoints and UDP endpoints are separate lists; their public ports may differ
5. Nginx `stream` reverse proxy can carry TCP master + UDP-direct failover (see the self-check procedure below)

---

## Self-check markers (must run before public deployment)

| Marker | Meaning |
| --- | --- |
| `UDP_BIND_OK` | Server UDP endpoint binds successfully |
| `UDP_WRR_OK` | Weighted round-robin dispatch is correct |
| `FAILOVER_PERMIT_OK` | Server can issue a permit under stall + UDP healthy |
| `FAILOVER_RECONNECT_OK` | Client can switch to the next candidate per the permit |
| `CACHE_RESUME_HIT` | Disk cache resumes after switch; hit ratio does not drop |
| `FAILOVER_TERMINAL_OK` | On candidate exhaustion, finalize exactly once |

With `network.dataPlane.enabled = false`, no UDP listener / bind / failover behavior should occur (regression guard).

---

[← Support-Matrix](Support-Matrix-en) · [Home](Home-en) · [→ FAQ](FAQ-en)
