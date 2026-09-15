# Network architecture (direct topology)

---

> **English** · 中文: [Network-Architecture](Network-Architecture)

> **Since 2.0.0 Hassium is back on the direct topology**: the historical in-process gateway (network core) / seamless master migration / L1 load balancing / UDP data plane / client failover have all been retired (decision record: [`docs/handoff/handoff-2026-09-04-vanilla-direct-network.md`](https://github.com/limuqy/Hassium/blob/master/docs/handoff/handoff-2026-09-04-vanilla-direct-network.md)).

## What players actually get

| Capability | Effect |
| --- | --- |
| Smooth push | Per-player per-tick chunk-send cap + backgrounded encode/compress; joins never saturate the main thread |
| Entity optimization | Distance tiers / hotspot density / packet-budget backpressure / UUID phase stagger; applies to vanilla clients (replication cadence only) |
| Channel compression | Dictionary ZSTD inside aggregated packets + chunk-push native compression; the vanilla compression layer is never touched |
| Cache reuse on reconnect | Chunks reused by chunkHash: unchanged = UNCHANGED, changed = DELTA, missing = full fetch |

Details: [Features](Features-en).

## Current topology: a single vanilla TCP connection

There is **exactly one vanilla TCP connection** between client and server (the game port). All custom payloads (chunks / entities / business) travel this channel during Play; the vanilla compression layer is never touched.

Public deployments only need to open the game port; there are no gateway / UDP ports.

> When both sides install the mod, login/configuration performs one capability negotiation (internal, invisible to players). Clients without the mod take the vanilla path and can join by default (`compat.requireClientMod = false`).

---

## Configuration

Current network-related keys (full table in [Configuration](Configuration-en)):

| Key | Default | Description |
| --- | --- | --- |
| `master.enabled` | `true` | Server network-optimization master switch (compression / aggregation / chunk push / entity pacing) |
| `chunk.enabled` | `true` | Client chunk-core master switch (shadow server / cache / lighting) |
| `master.maxChunksPerTick` | `5` | Per-player per-tick chunk-send cap |
| `master.enablePacketAggregation` | `true` | Packet aggregation |
| `master.aggregationMaxWaitTimeMs` | `50` | Aggregation max wait (ms; ACK timeout 5s auto-downgrades to direct send) |
| `master.compressionBlacklist` | `[]` | Third-party packet compression / aggregation exclusion (Hassium control-plane is always hard-coded excluded) |
| `master.entity*` | see [Configuration](Configuration-en) | Entity optimization (9 keys, on by default; benefits vanilla clients too) |

---

## Related pages

[← Support-Matrix](Support-Matrix-en) · [Home](Home-en) · [→ FAQ](FAQ-en)
