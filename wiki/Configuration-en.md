# Configuration

---

> **简体中文**: [Configuration](Configuration) · English

Hassium generates two TOML files under `config/hassium/` on startup:

| File | Side | Contents |
| --- | --- | --- |
| `hassium-client.toml` | Physical client only | Chunk cache (`chunk.*`), rendering & generation, network core (`net.*`), client debug |
| `hassium-server.toml` | Dedicated server only | Storage (`storage.*`), master core (`master.*`), data plane (`dataplane.*`), compat (`compat.*`), debug |

In-game config screen entry points:

| Loader | Entry | Notes |
| --- | --- | --- |
| Fabric | Install [Mod Menu](https://modrinth.com/mod/modmenu) and Cloth, then open from the Mod Menu list | No FCAP / Configured dependency |
| Forge | "Configure" button in the mods list | Requires Cloth |
| NeoForge | "Configure" button in the mods list | Requires Cloth; Configured optional |

> You can also edit the TOML directly and restart; GUI and TOML stay in sync.
> The in-game UI has 4 categories: Chunk Cache / Rendering & Generation / Network & Connection / Debug (client keys only); server keys are edited directly in `hassium-server.toml`.

---

## Full config reference

### Chunk Cache (`chunk.*`, Chunk Core)

| Key | Default | Notes |
| --- | --- | --- |
| `chunk.enabled` | `true` | Master switch for the client chunk cache |
| `chunk.maxSizeMb` | `4096` | Disk cap for the local cache (MB); overflow evicts least-recently-used chunks by heat |
| `chunk.sectionDeltaEnabled` | `true` | On cache mismatch, fetch only changed sections; off = full re-fetch |

### Rendering & Generation (`chunk.*`, Chunk Core)

| Key | Default | Notes |
| --- | --- | --- |
| `chunk.viewDistanceExtensionEnabled` | `true` | Beyond-view render (multiplayer, clientVD > serverVD ring fill; **incompatible with Bobby**) |
| `chunk.maxRenderDistance` | `16` | Beyond-view ring and effective RD cap (range 2–64) |
| `chunk.ovdUnloadDelaySecs` | `5` | Seconds of delayed unload after leaving the beyond-view ring (0 = sync) |
| `chunk.unloadDelaySecs` | `30` | Shadow-server in-memory chunk recycle delay in seconds (starts counting once a chunk leaves the unload boundary; on timeout the chunk is flushed to disk and freed from memory; 0 = disable recycling) |
| `chunk.maxChunksPerFrame` | `6` | Hard cap on main-thread chunk operations per frame (shared by apply callbacks, beyond-view enqueue and shadow light delivery) |
| `chunk.mainThreadChunkBudgetMs` | `15` | Per-frame chunk apply budget on the client (ms); JoinBoost temporarily raises it for ~10s after join |
| `chunk.hassiumEngineEnabled` | `true` | Hassium engine (master switch for non-network features): starts an in-process shadow server on login that owns all chunk lighting computation (the client stops computing light itself); on startup failure it degrades automatically (client cache / beyond-view render / SeedGen disabled with an in-game notice); when disabled the server does not strip light (negotiated at handshake), light arrives with the packets |
| `chunk.ovdLocalGeneration` | `false` | Beyond-view local generation: beyond-view-render chunks that miss the client cache are generated locally using the server's world seed and stored into the local cache; auto-disabled when no seed is available (server without the mod) |
| `chunk.seedGenThreads` | `2` | Local generation threads (0 = disable local generation; chunks are always downloaded in full) |
| `chunk.seedGenEnabled` | `false` | Local chunk generation (both sides): on SeedRef, regenerate the chunk locally from the world seed (hash-verified) instead of a full download; requires matching versions on both sides |

### Network Core (`net.*`)

| Key | Default | Notes |
| --- | --- | --- |
| `net.enabled` | `true` | Master switch for the client network core (in-process gateway and optimized channels; off = revert to vanilla chunk packets) |
| `net.metricsEnabled` | `false` | Client network metrics (off disables `/hassiumc stats`) |
| `net.metricsAutoReset` | `true` | Reset the metric counters of the current session when leaving a server |

### Master Core (`master.*`, server-side network & push)

| Key | Default | Notes |
| --- | --- | --- |
| `master.enabled` | `true` | Master switch for the server-side network channels |
| `master.globalPacketCompression` | `true` | Replace the vanilla Netty Zlib with ZSTD globally (off = coexist with protocol-replacement mods) |
| `master.compressionLevel` | `3` | Own-channel compression level (speed-biased) |
| `master.maxChunksPerTick` | `5` | Per-player submit cap per tick (send rate = cap × tick rhythm; ≈ 5×20 = 100/s at full tick, naturally slows on lag) |
| `master.enablePacketAggregation` | `true` | Packet aggregation; turn off if a third-party channel misbehaves |
| `master.compressionBlacklist` | 10-item default | Packet ID list; matched packets bypass compression/aggregation (default includes CHUNK_PAYLOAD / SECTION_DELTA / HANDSHAKE / DICTIONARY_SYNC / INDEX_SYNC / CHUNK_HASH / LIGHT_DELTA / BLOCK_ENTITY_DATA / MAIN_CHANNEL / AGGREGATION) |
| `master.metricsEnabled` | `false` | Server network metrics (off disables `/hassium stats` etc.) |
| `master.controlReachableEndpoints` | `[]` | Gateway listen endpoints (`endpoints[0]` is the gateway port; falls back to `25566` when unset) |
| `master.migrationFaultTimeoutMs` | `60000` | Master fault-silence timeout (ms): after this, the L1 migration engine switches masters seamlessly |

### Data Plane (`dataplane.*`)

| Key | Default | Notes |
| --- | --- | --- |
| `dataplane.enabled` | `false` | UDP data plane (bulk carrier for the gateway↔master channel; off by default — before enabling, configure publicly reachable listeners and open the UDP ports) |
| `dataplane.udpListeners` | 1 default (`0.0.0.0:25565`, reachable `127.0.0.1:25565`) | UDP listener list; `reachableEndpoints` must be publicly reachable addresses |

### Storage (`storage.*`)

| Key | Default | Notes |
| --- | --- | --- |
| `storage.enabled` | `false` | World save uses ZSTD type 126 (off by default; dedicated servers only — **back up worlds before first enable**) |
| `storage.zstdLevel` | `3` | Storage compression level; higher = smaller saves, more CPU |

### Compat (`compat.*`)

| Key | Default | Notes |
| --- | --- | --- |
| `compat.requireClientMod` | `false` | Off = vanilla clients can join (server-only compression benefit); on = require the mod on clients |
| `compat.autoDowngradeOnError` | `true` | Fall back to vanilla behavior on errors |

### Debug (`debug.*`, both sides)

| Key | Default | Notes |
| --- | --- | --- |
| `debug.metadataLogging` | `false` | chunkHash / metadata comparison logs |
| `debug.dispatcherLogging` | `false` | Main-thread dispatch logs |
| `debug.asyncLogging` | `false` | Async task logs |
| `debug.compressionLogging` | `false` | Compression / decompression logs |
| `debug.chunkApplyLogging` | `false` | Chunk apply logs |
| `debug.networkLogging` | `false` | Network send / receive logs |
| `debug.cacheLogging` | `false` | Cache read / write logs |
| `debug.dataplaneLogging` | `false` | Data-plane hot-path logs |
| `debug.lightVerify` | `false` | Light verification logs |

The hot path is quiet by default (only a few lifecycle INFO logs). Toggle `debug.*` categories as needed while debugging. ERROR / WARN are always emitted. See [Troubleshooting](Troubleshooting-en).

---

## Common tweaks

| Goal | Tweak |
| --- | --- |
| Disable storage compression, keep network benefits | `storage.enabled = false` |
| Back up worlds without format change first | Same — set `storage.enabled = false`, back up, then re-enable |
| Disable beyond-view render, restore vanilla RD clamp | `chunk.viewDistanceExtensionEnabled = false` |
| Raise beyond-view cap to 48 | `chunk.maxRenderDistance = 48`, and edit `options.txt` to raise the client slider; fog may show artifacts beyond RD 32 |
| Disable the Hassium engine (no shadow server; server then does not strip light) | `chunk.hassiumEngineEnabled = false` |
| Local chunk generation (matching versions on both sides) | `chunk.seedGenEnabled = true` on both sides |
| Coexist with in-process Via bridges | Turn off `master.globalPacketCompression` |
| Third-party channel hurt by aggregation | `master.enablePacketAggregation = false`, or add its channel ID to `master.compressionBlacklist` |
| Enable the UDP data plane | `dataplane.enabled = true`, set `dataplane.udpListeners` reachable addresses to public ones, and open the UDP ports |
| Client cache only (server does not install) | Install on the client only; server keeps `compat.requireClientMod = false` |

---

[← Installation](Installation-en) · [Home](Home-en) · [→ Commands](Commands-en)
