# Features

---

> **简体中文**: [Features](Features) · English

Hassium is a single client + server suite that optimizes Minecraft from five directions: **efficient compression, network optimization, chunk caching, lighting optimization, and utilities**. This page groups every feature into categories with a quick overview and when it applies.

---

## Efficient compression

### Storage compression

- **Goal**: Shrink world saves while keeping the vanilla `.mca` layout
- **How**: The server compresses each chunk payload with ZSTD as type 126; the outer Region (32×32) structure is unchanged
- **Config**: `storage.enabled` (default `false`, dedicated servers only), `storage.zstdLevel` (default `9`)
- **Note**: First-time enable rewrites the on-disk chunk format — **back up the world**. See [FAQ](FAQ-en).

---

### Network compression

- **Goal**: Shorter joins, less bandwidth while exploring
- **How**:
  - Custom `hassium:*` channels carry chunk data with ZSTD
  - Optional global pipeline replaces the vanilla Zlib (`globalPacketCompression`)
  - Aggregation + compact headers + context-aware compression improve the ratio
- **Config**: `network.enabled`, `network.globalPacketCompression`, `network.compressionLevel`, `network.enablePacketAggregation`

---

## Network optimization

### Smooth push

- **Goal**: The server keeps the main thread under control during join and view expansion; the client avoids hitch spikes
- **Server side** (push):
  - **Constant-rate throttling**: a per-player token bucket releases at `network.smoothChunkSendRate` (default `150` chunks/s), flattening per-tick batch bursts — no more network spikes
  - **Main-thread budget**: `network.maxChunksPerTick` (default `4`) caps per-player main-thread serialization per tick (4×20 = 80/s ≥ the smooth rate); main-thread peak ≤ ~8 ms/tick
  - **Background serialization**: encode / ZSTD compression / hash computation / send all run on the push pool (`serverChunkPushThreads` default 8, dynamically resizable); the main thread only builds the packet — aligned with vanilla (which also builds on the main thread and encodes on netty). On 1.20.x/1.21.1 the whole serialization chain runs off-thread
- **Client side** (loading):
  - Per-frame main-thread apply budget `clientCache.mainThreadChunkBudgetMs` (default `15`)
  - JoinBoost temporarily raises the budget for ~10s after join, then linearly ramps down
- **Metric**: Watch throughput and cache under `/hassium stats` and `/hassiumc stats`

---

### Control failover

- **Goal**: On TCP master disconnect or stall, auto-reconnect to a backup endpoint with the cache retained, disconnect UI hidden, and the world frozen on screen during recovery (tick paused, transition screens hidden at the render layer), so players barely notice
- **How**: The server pre-delivers a control-plane candidate list to the client during handshake; on a hard disconnect or a stall past the threshold with the UDP data plane healthy, the client auto-connects the next reachable candidate without showing "Connection lost". Disk cache, save queue, and task executor are all preserved across the switch, and the new session resumes directly — hit ratio holds, terrain does not re-download. The world tick pauses during recovery, transition screens (connect/loading/receiving-world) keep vanilla driving but are hidden from rendering, the screen keeps the frozen world plus a "Switching master…" overlay, and motion resumes once recovery succeeds
- **Default**: Off (`network.dataPlane.enabled = false`; the mod uses vanilla single-TCP by default). Requires ops capability — confirm Nginx / public-firewall / NAT rules before enabling
- **Config**: `network.dataPlane.controlStallMs` (default `6000`, how long a master stall triggers failover), `failoverPermitTtlMs` (default `30000`, validity of the server-issued FailoverPermit)
- **Deep dive**: [Control failover and weighted routing](Data-Plane-and-Failover-en)

---

### Weighted routing

- **Goal**: Share the bandwidth bottleneck of chunk downstream on high-population servers across multiple lines
- **How**: Chunk downloads run on a UDP/KCP data plane that can be configured with multiple endpoints (multiple lines), carrying traffic by `weight` weighted round-robin. When one line saturates or degrades, traffic shifts onto the rest; login, commands, and entity sync — "control-class" traffic — stay on vanilla TCP and are untouched by data-line issues
- **Default**: Off (same switch as control failover: `network.dataPlane.enabled = false`). Requires per-line public UDP endpoints
- **Config**: Each endpoint under `network.dataPlane.udpEndpoints` carries a `weight` (default `100`); `priority` controls candidate ordering
- **Deep dive**: [Control failover and weighted routing](Data-Plane-and-Failover-en)

---

## Chunk caching

### Client chunk cache

- **Goal**: Revisiting an area should skip full chunk downloads
- **How**: The server computes chunkHash before pushing; the client compares against the local cache contentHash; on hit it decompresses and applies locally, skipping the vanilla full download
- **Config**: `clientCache.enabled` (default `true`)
- **Details**: The cache is stored on disk as NBT (`HBT1` magic + CompoundTag) under `hassium_cache`; eviction is per-chunk by heat (no whole `.mca` deletion). Section delta, beyond-view rendering and world export all reuse the same cache data (below)

---

### Section delta

- **Goal**: Avoid full re-fetch when the cache is stale (MISMATCH)
- **How**: The client sends `SectionHashRequest` against its sectionHashes; the server responds with `SectionDeltaS2C` listing only the changed sections; the client merges into its cache NBT, then writes to disk; on failure or timeout it falls back to a full fetch
- **Config**: `clientCache.sectionDeltaEnabled` (default `true`; also requires `clientCache.enabled`)

| Comparison | Section delta off | On (default) |
| --- | --- | --- |
| HIT | Cache queue | Cache queue |
| MISS | Full fetch | Full fetch |
| MISMATCH | Full fetch | `SectionHashRequest` → NBT merge (falls back to full on failure) |

---

### Beyond-view render

- **Goal**: When the client RD exceeds the server view distance (multiplayer), fill the outer ring from the local cache — **render only, not simulated**
- **How**: The client slider is unclamped from the serverVD limit; cached chunks are applied with a renderOnly marker; the client never asks the server for chunks/BE beyond the server side; when a real chunk arrives it overrides the renderOnly stub
- **Config**: `clientCache.viewDistanceExtensionEnabled` (default `true`), `clientCache.maxRenderDistance` (default `32`, range 2–64), `clientCache.ovdUnloadDelaySecs` (default `5`)
- **Limits**: Incompatible with Bobby; disabled in singleplayer; with RD > 32 the fog distance also widens and may show artifacts (Fog Mixin not implemented)
- **Deep dive**: [Beyond-View-Render](Beyond-View-Render-en)

---

### World export

- **Goal**: Export the local cache to a vanilla Anvil singleplayer world
- **Command**: `/hassiumc export [<serverIp>] [seed]`
- **Deep dive**: [World-Export](World-Export-en)

---

## Lighting optimization

### Light stripping

- **Goal**: The server saves the bandwidth of light data
- **How**: The server can build chunk packets with an empty light mask (`network.lightStrip` default `true`, near-zero cost); on first load the empty light data forces a local recompute, which is then written back to the cache
- **Config**: `network.lightStrip`

---

### Light cache

- **Goal**: Avoid recomputing lighting on every load
- **How**: After the first recompute the light data is written to the cache (`is_light_on=1`); later cache hits apply the stored light directly, skipping the synchronous recompute; a SectionDelta merge forces `is_light_on=0` to avoid false hits
- **Config**: `clientCache.lightCacheEnabled` (default `true`)

---

### Parallel light engine

- **Goal**: Light recomputation no longer blocks the main thread
- **How**: Recomputation runs on a background thread pool (default 4 threads; virtual-thread mode unbounded); the main thread only captures the 9-column snapshot and submits; completion callbacks are scheduled within the main-thread budget
- **Config**: `clientCache.parallelLightEngineEnabled` (default `true`), `clientCache.parallelLightEngineThreads` (default `4`)
- **Metric**: `/hassiumc stats` shows `lighting optimization: xx% (hits N, recompute M)`

---

## Utilities

### Traffic monitoring

| Command | Side | Output |
| --- | --- | --- |
| `/hassium stats` | Server | Raw bytes / sent bytes / savings / push stats |
| `/hassiumc stats` | Client | Received bytes / cache hits / beyond-view / lighting optimization |

See [Commands](Commands-en) for the full reference.

---

> **Compatibility**: Clients without the mod can connect by default (`compat.requireClientMod = false`) and still get server-side compression; cache, negotiated compression and other advanced features need the mod on both sides. See the matrix in [Compatibility](Compatibility-en).

[← Commands](Commands-en) · [Home](Home-en) · [→ Beyond-View-Render](Beyond-View-Render-en)
