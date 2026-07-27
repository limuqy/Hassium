# Features

---

> **简体中文**: [Features](Features) · English

Hassium is a single client + server suite that optimizes Minecraft from three angles: save size, network bandwidth, and join smoothness. This page gives a quick overview of each feature and when it applies.

---

## Efficient storage

- **Goal**: Shrink world saves while keeping the vanilla `.mca` layout
- **How**: The server compresses each chunk payload with ZSTD as type 126; the outer Region (32×32) structure is unchanged
- **Config**: `storage.enabled` (default `true`), `storage.zstdLevel` (default `9`)
- **Note**: First-time enable rewrites the on-disk chunk format — **back up the world**. See [FAQ](FAQ-en).

---

## Network compression

- **Goal**: Shorter joins, less bandwidth while exploring
- **How**:
  - Custom `hassium:*` channels carry chunk data with ZSTD
  - Optional global pipeline replaces the vanilla Zlib (`globalPacketCompression`)
  - Aggregation + compact headers + context-aware compression improve the ratio
- **Config**: `network.enabled`, `network.globalPacketCompression`, `network.compressionLevel`, `network.enablePacketAggregation`

---

## Client chunk cache

- **Goal**: Revisiting an area should skip full chunk downloads
- **How**: The server computes chunkHash before pushing; the client compares against the local cache contentHash; on hit it decompresses and applies locally, skipping the vanilla full download
- **Config**: `clientCache.enabled` (default `true`)
- **Details**: The cache is stored on disk as NBT (`HBT1` magic + CompoundTag) under `hassium_cache`; eviction is per-chunk by heat (no whole `.mca` deletion)

---

## Section delta

- **Goal**: Avoid full re-fetch when the cache is stale (MISMATCH)
- **How**: The client sends `SectionHashRequest` against its sectionHashes; the server responds with `SectionDeltaS2C` listing only the changed sections; the client merges into its cache NBT, then writes to disk; on failure or timeout it falls back to a full fetch
- **Config**: `clientCache.sectionDeltaEnabled` (default `true`; also requires `clientCache.enabled`)

| Comparison | Section delta off | On (default) |
| --- | --- | --- |
| HIT | Cache queue | Cache queue |
| MISS | Full fetch | Full fetch |
| MISMATCH | Full fetch | `SectionHashRequest` → NBT merge (falls back to full on failure) |

---

## Beyond-view render

- **Goal**: When the client RD exceeds the server view distance (multiplayer), fill the outer ring from the local cache — **render only, not simulated**
- **How**: The client slider is unclamped from the serverVD limit; cached chunks are applied with a renderOnly marker; the client never asks the server for chunks/BE beyond the server side; when a real chunk arrives it overrides the renderOnly stub
- **Config**: `clientCache.viewDistanceExtensionEnabled` (default `true`), `clientCache.maxRenderDistance` (default `32`, range 2–64), `clientCache.ovdUnloadDelaySecs` (default `5`)
- **Limits**: Incompatible with Bobby; disabled in singleplayer; with RD > 32 the fog distance also widens and may show artifacts (Fog Mixin not implemented)
- **Deep dive**: [Beyond-View-Render](Beyond-View-Render-en)

---

## Lighting optimization

- **Goal**: Save bandwidth by omitting light data on the server; avoid recompute on the client each load
- **How**:
  1. The server can strip light; on first load the empty light data forces a local recompute, which is then written back to the cache (`is_light_on=1`)
  2. On cache hits the cached light is applied directly, skipping the synchronous recompute
  3. After a SectionDelta merge the light is forced to `is_light_on=0` to avoid false hits
- **Config**: `clientCache.lightCacheEnabled` (default `true`)
- **Metric**: `/hassiumc stats` shows `lighting optimization: xx% (hits N, recompute M)`

---

## World export

- **Goal**: Export the local cache to a vanilla Anvil singleplayer world
- **Command**: `/hassiumc export [<serverIp>] [seed]`
- **Deep dive**: [World-Export](World-Export-en)

---

## Control failover (TCP master)

- **Goal**: On TCP master disconnect or stall, auto-reconnect to a backup endpoint with the cache retained and disconnect UI hidden
- **Topology**: Control plane (Master TCP, vanilla login + Play) + data plane (UDP/KCP, multiple weighted endpoints)
- **Triggers**: Hard disconnect immediately launches the next candidate; on a 6s master stall with UDP healthy, the server issues a `FailoverPermit` and the client connects to the next candidate
- **Recovery retention**: Disk cache, `CacheSaveQueue`, `HassiumTaskExecutor`, and dirty flags are all preserved to accept the next candidate session
- **Config**: `network.dataPlane.controlStallMs` (default `6000`), `failoverPermitTtlMs` (default `30000`)
- **Deep dive**: [Data-Plane-and-Failover](Data-Plane-and-Failover-en)

---

## Weighted routing

- **Goal**: Multiple UDP/KCP endpoints share chunk bulk traffic by `weight` (weighted round-robin)
- **Config**: Each endpoint under `network.dataPlane.udpEndpoints` carries a `weight`
- **Deep dive**: [Data-Plane-and-Failover](Data-Plane-and-Failover-en)

---

## Smooth loading

- **Goal**: Reduce hitch spikes during join and view expansion
- **How**:
  - Server per-player serialize cap per tick: `network.maxChunksPerTick` (default `10`)
  - Client per-frame main-thread apply budget: `clientCache.mainThreadChunkBudgetMs` (default `15`)
  - JoinBoost temporarily raises the budget for ~10s after join and then linearly ramps down
- **Metric**: Watch throughput and cache under `/hassium stats` and `/hassiumc stats`

---

## Traffic monitoring

| Command | Side | Output |
| --- | --- | --- |
| `/hassium stats` | Server | Raw bytes / sent bytes / savings / push stats |
| `/hassiumc stats` | Client | Received bytes / cache hits / beyond-view / lighting optimization |

See [Commands](Commands-en) for the full reference.

---

[← Commands](Commands-en) · [Home](Home-en) · [→ Beyond-View-Render](Beyond-View-Render-en)
