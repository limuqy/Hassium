# Features

---

> **English**: [Features](Features) · English

Hassium pairs a client and server mod to optimize Minecraft along **efficient compression, network optimization, chunk cache, beyond-view render, local generation, lighting, and utilities**. This page summarizes each feature and when it applies.

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
  - **Per-tick cap**: `master.maxChunksPerTick` (default `5`) limits per-player per-tick Pull FULL/DELTA completions (5×20 = 100/s at full tick); UNCHANGED is a separate cap of 32
  - **Background serialization**: encode / ZSTD / hash / send run on a CPU-count push pool (`availableProcessors()`); the main thread only builds packet snapshots — aligned with vanilla (main thread builds, netty encodes)
- **Client side**:
  - Per-frame apply budget `chunk.mainThreadChunkBudgetMs` (default `15`)
  - JoinBoost temporarily raises the budget for 30s after join (30ms cap window), then falls back
- **Metrics**: `/hassium stats` and `/hassiumc stats`

---

### Login-phase handshake + Pull mode

- **Goal**: zero-timeout capability negotiation, zero interference for vanilla clients; chunk data fetched on demand after negotiation
- **How**:
  - On 1.20.1 the server sends the `hassium:login_hello` login query inside `handleAcceptedLogin` (after LoginCompression, before GameProfile); on 1.21.1+ the config-stage `PreHandshakePayload` (after authentication)
  - Bitwise capability negotiation (agg/delta/seed/light/pull/shadow_pull/pull_mode); empty answer or no shared capability → vanilla path (`compat.requireClientMod=true` kicks at login instead)
  - Play-phase activation: `ServerPlayer <init>` TAIL consumes negotiated caps (suppresses the vanilla chunk window) → dictionary_sync/index_sync → aggregation PENDING (5s ACK timeout downgrades to direct send) → `play_init_s2c` → client ACK → aggregation ENABLED
  - **Pull mode** (`pull_mode` capability): after negotiation the server stops pushing full chunk payloads (forget/metadata continue); chunk data is fetched by the unified Compare+Pull driven by the client shadow virtual player's vanilla tracking (`ShadowPull`: UNCHANGED / DELTA / FULL / ERROR)
- **Config**: `master.enabled` (server gate), `chunk.enabled` (client gate)

---

## Chunk cache

### Cache hits (shadow-world saving)

- **Goal**: avoid re-downloading full chunks when revisiting an area
- **How**: the server computes chunkHash before pushing; the client shadow side compares against cached contentHash — on hit it applies locally, skipping the vanilla full download
- **Config**: `chunk.enabled` (default `true`)
- **Details**: caching is owned by the shadow server — join chunks land in the vanilla save `hassium_cache/<serverId>/world` (type 126 + chunkHash; the legacy HBT1 client cache format is retired); heat-based per-region-file eviction (`heat.idx` accumulates across sessions, whole-file `.mca` deletion). Section delta and world export reuse the same cache (below)

---

### Section delta

- **Goal**: avoid whole-chunk retransmits on stale cache (MISMATCH)
- **How**: the shadow side reports section hashes and plane syndromes; the server sends changed blocks only (`BLOCKS`) for sparse edits, whole sections (`FULL`) when cheaper, whole chunk when changed sections ≥75%. Failures/timeouts fall back to full chunks
- **Config**: `chunk.sectionDeltaEnabled` (default `true`; requires `chunk.enabled`)

| Compare result | Delta off | Delta on (default) |
| --- | --- | --- |
| HIT | cache queue | cache queue |
| MISS | full request | full request |
| MISMATCH | full request | changed blocks / whole section (fallback to full) |

---

### World export

- **Goal**: export the shadow world as a standalone save (keeps type 126 + chunkHash; vanilla translation pending)
- **Command**: `/hassiumc export [<serverIp>] [seed]`
- **Details**: [World-Export](World-Export-en)

---

### Local generation (SeedGen)

- **Goal**: pristine terrain no longer needs per-chunk transmission — zero-bandwidth generation
- **How**: the server ships the world seed during Play activation (`play_init_s2c`, `LevelStem` NBT); with the gate open the client's shadow vanilla tracking runs worldgen directly for pristine chunks, then the result is authority-checked via compare-pull and takes the same pipeline as remote chunks (lighting → official packet → official channel), saved on disconnect. Failures/mismatches fall back to full requests
- **Config**: `chunk.seedGenEnabled` (default `false`, both sides same version)
- **Risk**: **server enablement sends the world seed to clients — equivalent to leaking the server seed** (seed maps / exported saves can exploit it)

---

## Beyond-view render

### OVD (shadow dual-window)

- **Goal**: when the client render distance (RD) exceeds the server view distance (serverVD), backfill the ring beyond it from local cache — **render-only, never simulated**
- **How**: shadow tracking widens to the effective clientRD; the authoritative window (`dist ≤ serverVD`) uses the unified Compare+Pull, while the OVD window is filled only from local sources (disk / injected) with **no requests to the real server**; the client only raises its `ClientChunkCache` radius and intercepts Forget
- **Config**: `chunk.viewDistanceExtensionEnabled` (default `true`; requires `chunk.enabled`), `chunk.maxRenderDistance` (default `16`)
- **Boundary**: mutually exclusive with Bobby; see [Beyond-View-Render](Beyond-View-Render-en) and [`docs/chunk-cache.md`](../docs/chunk-cache.md) §10

---

## Lighting

### Hassium engine (on by default)

- **What**: on join, an in-process shadow server (full MinecraftServer) takes over world saving (cache) + chunk lighting + packing official chunk packets — the client no longer computes lighting, and the loading phase no longer spends main-thread time on light recomputation
- **Master switch**: `chunk.enabled` (default `true`); when off the shadow server does not start and the server does not strip light (engine capability not declared) — light arrives with packets, vanilla path everywhere
- **Auto-degrade**: if the shadow server fails to start, client cache / SeedGen are disabled with an in-game notice; networking and basic loading are unaffected. Without the server mod the shadow server does not start (no world seed): light comes with packets while cache / export still work
- **World seed**: the shadow server uses the worldSeed delivered by the server handshake (server mod installed); it never invents a world

### Light stripping

- **Goal**: save the light payload on the wire
- **How**: the server may send an empty lightMask (`chunk.lightStrip`, default `true` — near-zero cost); **stripping is negotiated at handshake** — only when the client declares the engine available (`chunk.enabled=true`); stripped lighting is computed by the client shadow server and written back to the cache
- **Config**: `chunk.lightStrip`

---

### Lighting cache

- **Goal**: avoid recomputing lighting on the client
- **How**: shadow-computed lighting is written back into the shadow world save (stored with chunk data); later cache hits apply the stored lighting directly; merged SectionDelta is recomputed by the shadow side
- **Metrics**: `/hassiumc stats` shows `Lighting cache: xx% (hits N, shadow reuse M, recomputed K)` and `Lighting recompute: main x ms, background y ms`

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
