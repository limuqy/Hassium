# Features

---

> **English**: [Features](Features) · English

Hassium pairs a client and server mod to optimize Minecraft along **efficient compression, network optimization, chunk cache, lighting optimization, and utilities**. This page summarizes each feature and when it applies.

---

## Efficient compression

### Storage compression

- **Goal**: smaller world saves while keeping the vanilla `.mca` layout
- **How**: the server compresses each chunk payload with ZSTD and marks it type 126; the outer Region (32×32) structure is unchanged
- **Config**: `storage.enabled` (default `false`, dedicated server only), `storage.zstdLevel` (default `3`)
- **Note**: enabling rewrites the chunk save format — **back up your world**. See [FAQ](FAQ-en).

---

### Channel compression

- **Goal**: shorter download waits and lower bandwidth when joining or exploring
- **How**:
  - Dictionary ZSTD inside aggregated packets (always compressed above threshold)
  - Chunk-push native compression (independent of the vanilla compression layer)
  - Aggregation + compact headers improve ratio and packet efficiency
- **Config**: `master.enabled`, `master.compressionLevel`, `master.enablePacketAggregation`
- **Boundary**: never touches the vanilla compression layer (pipeline-level global packet compression retired); no cross-mod pipeline conflicts

---

## Network optimization

### Smooth push

- **Goal**: the server never saturates its main thread during joins or view expansion, and the client avoids stutter spikes
- **Server side**:
  - **Per-tick cap**: `master.maxChunksPerTick` (default `5`) limits per-player per-tick chunk sends (5×20 = 100/s at full tick); rate degrades naturally on laggy ticks
  - **Background serialization**: encode / ZSTD / hash / send run on a CPU-count push pool (`availableProcessors()`); the main thread only builds packet snapshots — aligned with vanilla (main thread builds, netty encodes)
- **Client side**:
  - Per-frame apply budget `chunk.mainThreadChunkBudgetMs` (default `15`)
  - JoinBoost temporarily raises the budget for 30s after join (30ms cap window), then falls back
- **Metrics**: `/hassium stats` and `/hassiumc stats`

---

### Entity optimization

- **Goal**: cut bandwidth and main-thread spikes when many mobs/items would otherwise fire on the same tick; **applies to vanilla clients too** (replication cadence only, protocol unchanged)
- **How** (four orthogonal layers, all on by default):
  - **Distance tiers**: farther entities update less often (`master.entityTieredUpdateEnabled` + `entityTierIntervals`)
  - **Item-flow table**: dropped items / XP orbs use their own interval table (`entityItemTierIntervals`, default 2/4/8/16 ticks) so the vanilla 20-tick idle beat cannot flatten them into 1 packet/s flicker
  - **Hotspot density**: stretch intervals when the entity's own chunk is crowded (`entityDensityThrottleEnabled` + `entityDensityTierCounts/Factors`)
  - **Packet-budget backpressure**: if a player keeps exceeding the per-tick entity packet budget, entities in their view become sparser (`entityFrameBudgetPerPlayer`, default 256)
  - **Phase stagger**: same-interval entities are UUID-offset across ticks — total volume over an interval is unchanged, the same-tick burst is flattened (`entitySmoothPushEnabled`)
- **Boundary**: only the replication (send) cadence; server entity ticks / pickup / hopper logic untouched; player self-motion is exempt
- **Config**: `master.entity*` (follows the `master.enabled` master switch; all off = vanilla behavior)

---

## Chunk cache

### World save

- **Goal**: avoid re-downloading full chunks when revisiting an area
- **How**: the client is **fully passive** and only receives official chunk+light packets; comparison happens on the shadow side. The server declares an authoritative content hash on push: a matching local hash means **zero-request local delivery**; mismatch/unknown falls back to unified ShadowPull (UNCHANGED reuse / DELTA only changed sections / FULL when missing)
- **Config**: `chunk.enabled` (default `true`)
- **Details**: visited chunks are saved by the shadow side under `hassium_cache/<serverId>/world`; section delta, world export, and heat eviction all reuse the same cache (below)

---

### Heat eviction

- **Goal**: keep cache size under the configured cap
- **How**: region files accumulate access heat; over-capacity colder regions are deleted whole-file first
- **Config**: `chunk.maxSizeMb` (default `4096`), `chunk.hotScoreThreshold`, `chunk.cleanupIntervalTicks`

---

### Section delta

- **Goal**: avoid whole-chunk retransmits when the local baseline is stale
- **How**: the shadow side reports section hashes and plane syndromes; the server sends changed blocks only (`BLOCKS`) for sparse edits, whole sections (`FULL`) when cheaper, whole chunk when changed sections ≥75%. Failures/timeouts fall back to full chunks
- **Config**: `chunk.sectionDeltaEnabled` (default `true`; requires `chunk.enabled`)

| Compare result | Delta off | Delta on (default) |
| --- | --- | --- |
| Authority hash hit | Local delivery (zero-request) | Local delivery (zero-request) |
| UNCHANGED | Materialize locally | Materialize locally |
| No baseline / FULL | Full fetch | Full fetch |
| DELTA (stale baseline) | Full fetch | Changed blocks / whole section (fallback to full) |

---

### World export

- **Goal**: copy the local cache into a standalone save directory
- **Command**: `/hassiumc export [<serverIp>] [seed]`
- **Details**: [World-Export](World-Export-en)

---

### Local generation

- **Goal**: unexplored terrain is generated locally instead of transferred chunk by chunk
- **How**: with both sides on the same version and the gate open, the server ships the world seed; the client triggers vanilla worldgen locally, then results are authority-checked before delivery. Failures/mismatches fall back to full requests
- **Config**: `chunk.seedGenEnabled` (default `false`, both sides same version)
- **Risk**: **server enablement sends the world seed to clients — equivalent to leaking the server seed** (seed maps / exported saves can exploit it)

---

### Beyond-view render

- **Goal**: when client render distance exceeds server view distance, backfill the outer ring from local cache — **render-only, never simulated**
- **How**: the ring beyond server view distance is filled only from local cache with **no requests to the server**; the client raises its local chunk-cache radius and intercepts Forget
- **Config**: `chunk.viewDistanceExtensionEnabled` (default `true`; requires `chunk.enabled`), `chunk.maxRenderDistance` (default `16`)
- **Boundary**: mutually exclusive with Bobby; see [Beyond-View-Render](Beyond-View-Render-en)

---

## Lighting optimization

### Unified lighting (on by default)

- **What**: on join, an in-process engine takes over chunk lighting and official chunk-packet packing — the client no longer computes lighting, and the loading phase no longer spends main-thread time on light recomputation. It also owns world saving (cache)
- **Master switch**: `chunk.enabled` (default `true`); when off, light arrives with packets — vanilla path everywhere
- **Auto-degrade**: if startup fails, client cache / local generation are disabled with an in-game notice; networking and basic loading are unaffected. Without the server mod, light comes with packets while cache / export still work
- **World seed**: uses the world seed delivered by the server handshake (when the server mod is installed); it never invents a world

### Light stripping

- **Goal**: save the light payload on the wire
- **How**: the server may send an empty lightMask (`chunk.lightStrip`, default `true` — near-zero cost); **stripping is negotiated at handshake** — only when the client declares the engine available (`chunk.enabled=true`); stripped lighting is computed by the client shadow server and written back to the cache
- **Config**: `chunk.lightStrip`

---

### Light cache

- **Goal**: avoid recomputing lighting on the client
- **How**: computed lighting is saved with the chunk; later cache hits apply the stored lighting directly; merged section updates are recomputed
- **Metrics**: `/hassiumc stats` shows lighting cache hit rate and recompute time

---

## Utilities

### Traffic monitoring

| Command | Side | Output |
| --- | --- | --- |
| `/hassium stats` | Server | Sent (vanilla-Zlib equivalent) / savings% / ratio / metadata sent / data requests received / chunk compression |
| `/hassiumc stats` | Client | Bandwidth compression / chunk cache (full+partial−delta over applied, by bytes; local generation not counted as cache) / chunk loading (new+stale+local) / lighting cache / lighting recompute / traffic savings (actual / no-mod expected) |

Full reference: [Commands](Commands-en).

---

> **Compatibility**: vanilla clients can join by default (`compat.requireClientMod = false`) and only get server-side compression; caching and negotiated compression need the mod on both sides. See [Compatibility](Compatibility-en).

[← Commands](Commands-en) · [Home](Home-en) · [→ Beyond-View-Render](Beyond-View-Render-en)
