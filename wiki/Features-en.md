# Features

---

> **简体中文**: [Features](Features) · English

Hassium is a single client + server suite that optimizes Minecraft from six directions: **efficient compression, network optimization, chunk cache, local generation, lighting optimization, and utilities**. This page groups every feature into categories with a quick overview and when it applies.

---

## Efficient compression

### Storage compression

- **Goal**: Shrink world saves while keeping the vanilla `.mca` layout
- **How**: The server compresses each chunk payload with ZSTD as type 126; the outer Region (32×32) structure is unchanged
- **Config**: `storage.enabled` (default `false`, dedicated servers only), `storage.zstdLevel` (default `3`)
- **Note**: First-time enable rewrites the on-disk chunk format — **back up the world**. See [FAQ](FAQ-en).

---

### Network compression

- **Goal**: Shorter joins, less bandwidth while exploring
- **How**:
  - Custom `hassium:*` channels carry chunk data with ZSTD
  - Optional global pipeline replaces the vanilla Zlib (`master.globalPacketCompression`)
  - Aggregation + compact headers + context-aware compression improve the ratio
- **Config**: `master.enabled`, `master.globalPacketCompression`, `master.compressionLevel`, `master.enablePacketAggregation`

---

## Network optimization

### Smooth push

- **Goal**: The server keeps the main thread under control during join and view expansion; the client avoids hitch spikes
- **Server side** (push):
  - **Tick-granularity throttling**: `master.maxChunksPerTick` (default `5`) caps per-player submits per tick (5×20 = 100/s at full tick); the per-tick submit count stays fixed during lag so the per-second rate naturally drops — protecting the server main thread; main-thread peak ≤ ~8 ms/tick
  - **Background serialization**: encode / ZSTD compression / hash computation / send all run on the push pool (`master.serverChunkPushThreads` default 2, dynamically resizable); the main thread only builds the packet — aligned with vanilla (which also builds on the main thread and encodes on netty). On 1.20.x/1.21.1 the whole serialization chain runs off-thread
  - **Progressive admission**: full / SeedGen deliveries are keyed by `(dimension, chunk)`; the client sends `ChunkApplyAck` after authoritative apply; one unacked batch before the first ACK, then up to 10; `GatewayChannel.isWritable` is transport backpressure only
  - **Verification status**: Implementation plus compile/unit tests are delivered; the Fabric 1.20.1 live join/move production–apply curve still needs a dedicated runtime window (see [`network-core-followups.md`](../docs/network-core-followups.md))
- **Client side** (loading):
  - Per-frame main-thread apply budget `chunk.mainThreadChunkBudgetMs` (default `15`)
  - JoinBoost temporarily raises the budget for ~10s after join, then linearly ramps down
- **Metric**: Watch throughput and cache under `/hassium stats` and `/hassiumc stats`

---

### In-process gateway and seamless migration

- **Goal**: The client connects to the master core through an in-process gateway (Network Core); on master disconnect or stall it migrates seamlessly — the cache resumes, the disconnect screen is hidden, players barely notice
- **How**: The in-process Network Core on the client (`network/core/`: NetworkCore state machine / outbound frame protocol / migration engine / viafabric bridge) connects to the master core (`network/gateway/`, GatewayServer) over the gateway frame protocol; when the master fails, the L1 migration engine decides based on the effective silence timeout (default `master.migrationSilentTimeoutMs`=`10000`) and migrates directly — disk cache, save queue, and task executor are all preserved, the new session resumes directly, hit ratio holds and terrain is not re-downloaded, with no "Connection lost" popup
- **UDP data plane**: bulk carrier for the gateway↔master channel (UDP/KCP, AES-GCM mutual authentication); off by default (`dataplane.enabled = false`) — all traffic then goes through the gateway frame connection
- **Config**: `dataplane.enabled` (default `false`), `master.migrationSilentTimeoutMs` (default `10000`), `master.migrationFaultTimeoutMs` (legacy `60000` fallback), server `master.controlReachableEndpoints` (handshake-synced to the client; falls back to `25566` when unset)
- **Deep dive**: [Network Core and Master Migration](Network-Core-and-Master-Migration-en)

---

### L1 load balancing

- **Goal**: Share the bandwidth bottleneck of chunk downstream on high-population servers across multiple lines
- **How**: The master core listens on the control-reachable endpoints; the UDP data plane supports multiple UDP listeners (`dataplane.udpListeners`) carrying chunk downstream by `weight` weighted round-robin. When one line saturates or degrades, traffic shifts onto the rest; login, commands, and entity sync — "control-class" traffic — stay on the gateway frame connection and are untouched by data-line issues
- **Default**: Off (same switch as the data plane: `dataplane.enabled = false`). Requires per-line public UDP endpoints
- **Config**: `dataplane.udpListeners` (`weight` default `100`)
- **Deep dive**: [Network Core and Master Migration](Network-Core-and-Master-Migration-en)

---

## Chunk Cache

### Cache hit

- **Goal**: Revisiting an area should skip full chunk downloads
- **How**: The server computes chunkHash before pushing; the client compares against the local cache contentHash; on hit it decompresses and applies locally, skipping the vanilla full download
- **Config**: `chunk.enabled` (default `true`)
- **Details**: The cache lives in the shadow engine — visited chunks are saved into a vanilla-format world at `hassium_cache/<serverId>/world` (type 126 + chunkHash; the old HBT1 client-cache format has been removed); eviction is per-chunk by heat (`heat.idx`, accumulated across sessions). Section delta, beyond-view rendering and world export all reuse the same cache data (below)

---

### Section delta

- **Goal**: Avoid full re-fetch when the cache is stale (MISMATCH)
- **How**: The client reports section hashes plus plane syndromes. The server sends changed blocks (`BLOCKS`) when sparse, a full section (`FULL`) when too many or paletted is smaller, and a full chunk if ≥75% of sections changed. Failure/timeout falls back to a full fetch
- **Config**: `chunk.sectionDeltaEnabled` (default `true`; also requires `chunk.enabled`)

| Comparison | Section delta off | On (default) |
| --- | --- | --- |
| HIT | Cache queue | Cache queue |
| MISS | Full fetch | Full fetch |
| MISMATCH | Full fetch | Changed blocks / full section (falls back to full chunk on failure) |

---

### Beyond-view render

- **Goal**: When the client RD exceeds the server view distance (multiplayer), fill the outer ring from the local cache — **render only, not simulated**
- **How**: The client slider is unclamped from the serverVD limit; cached chunks are applied with a renderOnly marker; the client never asks the server for chunks/BE beyond the server side; when a real chunk arrives it overrides the renderOnly stub
- **Config**: `chunk.viewDistanceExtensionEnabled` (default `true`), `chunk.maxRenderDistance` (default `16`, range 2–64), `chunk.ovdUnloadDelaySecs` (default `5`)
- **Limits**: Incompatible with Bobby; disabled in singleplayer; with RD > 32 the fog distance also widens and may show artifacts (Fog Mixin not implemented)
- **Deep dive**: [Beyond-View-Render](Beyond-View-Render-en)

---

### World export

- **Goal**: Export the shadow-side world directory as a standalone archive (keeps the type 126 + chunkHash format; vanilla translation is planned later)
- **Command**: `/hassiumc export [<serverIp>] [seed]`
- **Deep dive**: [World-Export](World-Export-en)

---

### Local generation (SeedGen)

- **Goal**: No more per-chunk transfer for large unexplored areas (pristine chunks) — zero-bandwidth generation
- **How**: For pristine chunks the server sends only a reference (seed + position + hash, a few dozen bytes) instead of chunk data; the client's shadow server generates locally with the same seed, through the same pipeline as remote chunks (light → official packet packing → vanilla channel apply), saved by `saveAll` on disconnect; falls back to a full request on failure/timeout
- **Config**: `chunk.seedGenEnabled` (default `false`; requires matching versions and the option enabled on both sides), `chunk.seedGenThreads` (2)

---

## Lighting optimization

### Hassium engine (default on)

- **What**: On login an in-process shadow server is started that owns all chunk lighting computation — the client no longer computes light itself, and the main thread is not occupied by light recomputes during chunk loading
- **Master switch**: `chunk.hassiumEngineEnabled` (default `true`); when disabled the server does not strip light (the client does not declare the engine at handshake) and light arrives with the packets
- **Automatic degradation on startup failure**: if the shadow server fails to start, client cache / beyond-view render / SeedGen are disabled with an in-game notice; networking and basic chunk loading are unaffected. When the server does not run Hassium (no world seed) the shadow server is never started — light arrives with the packets and cache / OVD / world export stay available
- **World seed**: the shadow server uses the worldSeed sent during the Hassium handshake (server must run the mod); it never generates its own world

### Light stripping

- **Goal**: The server saves the bandwidth of light data
- **How**: The server can build chunk packets with an empty light mask (`chunk.lightStrip` default `true`, near-zero cost); **stripping is negotiated at handshake** — only when the client declares the engine (`hassiumEngineEnabled=true`) does the server strip, otherwise light arrives with the packets; stripped light is computed by the shadow server and written back to the cache
- **Config**: `chunk.lightStrip`

---

### Light cache

- **Goal**: Avoid recomputing lighting on every load
- **How**: Light computed by the shadow server is stored with the chunk data (`is_light_on=1`); later cache hits apply the stored light directly; a SectionDelta merge forces `is_light_on=0` so the shadow server recomputes it
- **Metric**: `/hassiumc stats` shows `light cache: xx% (hits N, recompute M)` and `light recompute: main-thread x ms, background y ms`

---

## Utilities

### Traffic monitoring

| Command | Side | Output |
| --- | --- | --- |
| `/hassium stats` | Server | Sent (vanilla-Zlib-equivalent) / savings % / compression ratio / metadata sent / data requests received / chunks compressed |
| `/hassiumc stats` | Client | Bandwidth compression / chunk cache (full hits + delta) / chunk loading (new + stale + local) / light cache / light recompute / beyond-view ON\|OFF / bandwidth savings |

See [Commands](Commands-en) for the full reference.

---

> **Compatibility**: Clients without the mod can connect by default (`compat.requireClientMod = false`) and still get server-side compression; cache, negotiated compression and other advanced features need the mod on both sides. See the matrix in [Compatibility](Compatibility-en).

[← Commands](Commands-en) · [Home](Home-en) · [→ Beyond-View-Render](Beyond-View-Render-en)
