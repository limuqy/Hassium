# Configuration

---

> **简体中文**: [Configuration](Configuration) · English

Hassium generates two TOML files under `config/hassium/` on startup:

| File | Side | Contents |
| --- | --- | --- |
| `hassium-client.toml` | Physical client only | Chunk Core (`clientCache.*`), beyond-view render, client-side network |
| `hassium-server.toml` | Dedicated server only | Storage compression, shared network, compat, debug |

In-game config screen entry points:

| Loader | Entry | Notes |
| --- | --- | --- |
| Fabric | Install [Mod Menu](https://modrinth.com/mod/modmenu) and Cloth, then open from the Mod Menu list | No FCAP / Configured dependency |
| Forge | "Configure" button in the mods list | Requires Cloth |
| NeoForge | "Configure" button in the mods list | Requires Cloth; Configured optional |

> You can also edit the TOML directly and restart; GUI and TOML stay in sync.

---

## Full config reference

### Storage

| Key | Default | Notes |
| --- | --- | --- |
| `storage.enabled` | `false` | World save uses ZSTD type 126 (off by default; dedicated servers only — **back up worlds before first enable**) |
| `storage.mode` | `mirror` | Storage mode (only `mirror` is wired) |
| `storage.zstdLevel` | `3` | Storage compression level; higher = smaller saves, more CPU |

### Chunk Core (`clientCache.*`)

| Key | Default | Notes |
| --- | --- | --- |
| `clientCache.enabled` | `true` | Master switch for client chunk cache |
| `clientCache.sectionDeltaEnabled` | `true` | On cache mismatch, fetch only changed sections; off = full re-fetch |
| `clientCache.hassiumEngineEnabled` | `true` | Hassium engine (master switch for non-network features): starts an in-process shadow server on login that owns all chunk lighting computation (the client stops computing light itself); on startup failure it degrades automatically (client cache / beyond-view render / SeedGen disabled with an in-game notice); when disabled the server does not strip light (negotiated at handshake), light arrives with the packets |
| `clientCache.ovdLocalGeneration` | `false` | OVD local generation: beyond-view-render chunks that miss the client cache are generated locally using the server's world seed and stored into the local cache; auto-disabled when no seed is available (server without the mod) |
| `clientCache.viewDistanceExtensionEnabled` | `true` | Beyond-view render (multiplayer, clientVD > serverVD ring fill; **incompatible with Bobby**) |
| `clientCache.maxRenderDistance` | `16` | Beyond-view ring and effective RD cap (range 2–64) |
| `clientCache.ovdUnloadDelaySecs` | `5` | Seconds of delayed unload after leaving the beyond-view ring (0 = sync) |
| `clientCache.mainThreadChunkBudgetMs` | `15` | Per-frame chunk apply budget on the client (ms); JoinBoost temporarily raises it for ~10s after join |

### Network

| Key | Default | Notes |
| --- | --- | --- |
| `network.enabled` | `true` | Custom `hassium:*` channels (off = revert to vanilla full packets) |
| `network.globalPacketCompression` | `true` | Replace the vanilla Netty Zlib with ZSTD globally (off = coexist with protocol-replacement mods) |
| `network.compressionLevel` | `3` | Network compression level (speed-biased) |
| `network.maxChunksPerTick` | `5` | Per-player submit cap per tick (send rate = cap × tick rhythm; ≈ 5×20 = 100/s at full tick, naturally slows on lag) |
| `network.metricsEnabled` | `false` | Metrics collection (turn off disables `/hassium stats` etc.) |
| `network.enablePacketAggregation` | `true` | Packet aggregation; turn off if a third-party channel misbehaves |
| `network.compressionBlacklist` | 10-item default | Packet ID list; matched packets bypass compression/aggregation (default includes CHUNK_PAYLOAD / SECTION_DELTA / HANDSHAKE / DICTIONARY_SYNC / INDEX_SYNC / CHUNK_HASH / LIGHT_DELTA / BLOCK_ENTITY_DATA / MAIN_CHANNEL / AGGREGATION) |

### Compat & debug

| Key | Default | Notes |
| --- | --- | --- |
| `compat.requireClientMod` | `false` | Off = vanilla clients can join (server-only compression benefit); on = require the mod on clients |
| `debug.metadataLogging` | `false` | chunkHash / metadata comparison logs |
| `debug.dispatcherLogging` | `false` | Main-thread dispatch logs |
| `debug.asyncLogging` | `false` | Async task logs |
| `debug.compressionLogging` | `false` | Compression / decompression logs |
| `debug.chunkApplyLogging` | `false` | Chunk apply logs |
| `debug.networkLogging` | `false` | Network send / receive logs |
| `debug.cacheLogging` | `false` | Cache read / write logs |

The hot path is quiet by default (only a few lifecycle INFO logs). Toggle `debug.*` categories as needed while debugging. ERROR / WARN are always emitted. See [Troubleshooting](Troubleshooting-en).

---

## Common tweaks

| Goal | Tweak |
| --- | --- |
| Disable storage compression, keep network benefits | `storage.enabled = false` |
| Back up worlds without format change first | Same — set `storage.enabled = false`, back up, then re-enable |
| Disable beyond-view render, restore vanilla RD clamp | `clientCache.viewDistanceExtensionEnabled = false` |
| Raise beyond-view cap to 48 | `clientCache.maxRenderDistance = 48`, and edit `options.txt` to raise the client slider; fog may show artifacts beyond RD 32 |
| Disable the Hassium engine (no shadow server; server then does not strip light) | `clientCache.hassiumEngineEnabled = false` |
| Coexist with in-process Via bridges | Turn off `network.globalPacketCompression` |
| Third-party channel hurt by aggregation | `network.enablePacketAggregation = false`, or add its channel ID to `network.compressionBlacklist` |
| Client cache only (server does not install) | Install on the client only; server keeps `compat.requireClientMod = false` |

---

[← Installation](Installation-en) · [Home](Home-en) · [→ Commands](Commands-en)
