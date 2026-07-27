# Data Plane and Control Failover

---

> **简体中文**: [Data-Plane-and-Failover](Data-Plane-and-Failover) · English

Advanced networking: UDP/KCP data plane + TCP control failover. **Disabled by default**, intended for server operators with ops capability. Run the six smoke markers before enabling.

> ⚠️ Production env config for this feature is still being migrated; it is currently driven by `DataPlanePoCConfig` as a stopgap. Before enabling, make sure you can operate Nginx / public-firewall / NAT rules.

---

## Topology

| Plane | Purpose | Protocol |
| --- | --- | --- |
| **Control plane (Master TCP)** | Vanilla Minecraft login + Play Connection | TCP |
| **Data plane (UDP/KCP)** | Chunk bulk and data-plane fan-out | UDP/KCP (independent sessions with KCP `ReliableDatagramSession`) |

- The control-plane endpoint list is delivered via S2C handshake tail (host:port + priority)
- The client mixes bootstrap + advertised endpoints, sorted by priority descending, up to 4 candidates (`ControlEndpointManager.MAX_CANDIDATES`)
- Each `(host, port)` UDP endpoint binds an independent KCP `ReliableDatagramSession`
- The client issues a separate BindRequest per advertised endpoint with an HKDF-derived AES-GCM key

---

## Triggers

### Hard disconnect

The Master TCP `channelInactive` → `ControlReconnectOrchestrator.onPrimaryDisconnected` immediately launches the next candidate; the client enters a 60-second recovery window.

### Master stalled + UDP healthy

The server detects a control stall (default 6 seconds). During the stall, `DataPlaneUdpServer.recordControlActivity` advances; if the UDP session is healthy (matching epoch) the server issues a `FailoverPermit` (`expiryMs` default 30 seconds). The client only connects via `attemptConnectOnlyIfPermitValid`.

---

## Recovery retention

When `ClientRecoveryState.shouldSuppressFinalization()` is true, `ClientLifecycleHelper.finalizeDisconnectIfTerminal` short-circuits `finalizeDisconnect`, preserving:

- Disk cache
- `CacheSaveQueue`
- `HassiumTaskExecutor`
- Dirty flags

When the next candidate session starts, the cache is ready to use and the hit ratio does **not drop**.

`ClientPlayConnectionEvents.DISCONNECT` calls `DataPlaneClientLifecycle.stopUdp(/*keepLease*/ true)`, so the UDP bundle is not released immediately.

---

## Candidate exhaustion

`ControlReconnectOrchestrator.performTerminalFinalization` calls `ClientLifecycleHelper.finalizeDisconnectIfTerminal`; the singleton `ClientRecoveryState.consumeTerminalCleanup` guarantees exactly-once disk-resource cleanup.

---

## Weighted routing

The data plane supports multiple UDP endpoints sharing chunk bulk traffic by `weight` (weighted round-robin):

- One `ReliableDatagramSession` per endpoint
- WRR over `weight`
- `share` / `exclusive` routing modes
- UDP health feeds into weight adjustment (degraded demotion)

---

## Config keys

| Key | Default | Notes |
| --- | --- | --- |
| `network.dataPlane.enabled` | `false` | Data-plane master switch (default off; true during 1.20.1 Fabric PoC) |
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
5. Nginx `stream` reverse proxy can carry TCP master + UDP-direct failover (see the smoke harness below)

---

## Smoke markers (must run before enabling)

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
