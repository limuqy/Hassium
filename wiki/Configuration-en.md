# Configuration

---

> **English**: [Configuration](Configuration) · English

Hassium generates two TOML files under `config/hassium/` on first launch:

| File | Side | Contents |
| --- | --- | --- |
| `hassium-client.toml` | Physical client only | Chunk core (`chunk.*`), client debug |
| `hassium-server.toml` | Dedicated server only | Storage (`storage.*`), server transport (`master.*`), compat (`compat.*`), debug |

In-game editors:

| Loader | Entry | Notes |
| --- | --- | --- |
| Fabric | Install [Mod Menu](https://modrinth.com/mod/modmenu), open from the mod list | No FCAP / Configured dependency |
| Forge | "Config" button in the mod list | Requires Cloth |
| NeoForge | "Config" button in the mod list | Requires Cloth; Configured optional |

> You can also edit the TOML files and restart; GUI and TOML stay in sync.
> Key-set source of truth: `ConfigSchema` (38 keys); full audit in the repo [`docs/config-audit.md`](https://github.com/limuqy/Hassium/blob/master/docs/config-audit.md).

---

## Full key reference

### Chunk core (`chunk.*`, client)

| Key | Default | Description |
| --- | --- | --- |
| `chunk.enabled` | `true` | Chunk-core master switch (shadow-world saving/lighting/cache/Pull mode; off = vanilla path everywhere) |
| `chunk.maxSizeMb` | `4096` | Cache size cap (MB); over-capacity cold regions (`.mca`) are deleted whole-file by heat |
| `chunk.hotScoreThreshold` | `0.3` | Heat-score threshold (below = cold region, evicted first) |
| `chunk.recencyWeight` | `0.7` | Recency weight in the heat score |
| `chunk.frequencyWeight` | `0.3` | Frequency weight in the heat score |
| `chunk.cleanupIntervalTicks` | `6000` | Cleanup check interval (ticks) |
| `chunk.targetSizeMb` | `0` | Target cache size (MB; 0 = auto) |
| `chunk.minCleanupBatchSize` | `100` | Max region files evicted per cleanup pass |
| `chunk.sectionDeltaEnabled` | `true` | On stale cache send changed blocks only (whole section/chunk beyond that); off = stale goes full |
| `chunk.maxChunksPerFrame` | `6` | Per-tick cache-read production cap (shadow enqueue + shadow disk); main-thread apply is bounded only by `mainThreadChunkBudgetMs` |
| `chunk.mainThreadChunkBudgetMs` | `15` | Client per-frame apply budget (ms); JoinBoost temporarily raises it for 30s after join |
| `chunk.seedGenEnabled` | `false` | Local generation (both sides): shadow tracking triggers vanilla worldgen then compare-pull; both sides same version. **Server enablement sends the world seed (seed leak)** |

### Chunk core (`chunk.*`, server)

| Key | Default | Description |
| --- | --- | --- |
| `chunk.lightStrip` | `true` | Light stripping: packets may carry an empty lightMask; actual stripping is negotiated at handshake |

### Server transport (`master.*`)

| Key | Default | Description |
| --- | --- | --- |
| `master.enabled` | `true` | Server network-channel master switch (gate for login handshake/aggregation) |
| `master.compressionLevel` | `3` | Private-channel ZSTD level (speed-first) |
| `master.useContextCompression` | `true` | Context compression (dictionary ZSTD) |
| `master.enablePacketAggregation` | `true` | Packet aggregation; turn off if it breaks third-party channels |
| `master.aggregationMinBatchSize` | `4` | Aggregation minimum batch size |
| `master.aggregationMaxWaitTimeMs` | `50` | Aggregation max wait (ms; ACK timeout 5s auto-downgrades to direct send) |
| `master.aggregationMaxSize` | `262144` | Aggregation max size (bytes) |
| `master.compressionBlacklist` | control-plane keys | Packet IDs excluded from compression/aggregation (defaults cover control plane: dictionary / index / light delta / aggregation itself, etc.) |
| `master.maxChunksPerTick` | `5` | Per-player per-tick Pull FULL/DELTA completion cap (send rate = value × tick pace, ≈ 5×20 = 100/s at full tick; UNCHANGED is a separate cap of 32; degrades naturally on laggy ticks) |

### Storage (`storage.*`)

| Key | Default | Description |
| --- | --- | --- |
| `storage.enabled` | `false` | World saves use ZSTD type 126 (off by default; dedicated server only, **back up your world before first enable**) |
| `storage.zstdLevel` | `3` | Storage level; higher = smaller saves, heavier CPU |

### Compat (`compat.*`)

| Key | Default | Description |
| --- | --- | --- |
| `compat.requireClientMod` | `false` | Off = mod-less clients can join (server-side compression only); on = kick when the login handshake fails |
| `compat.autoDowngradeOnError` | `true` | Auto-fall-back to vanilla behavior on error |

### Debug (`debug.*`, per side)

Client `hassium-client.toml`:

| Key | Default | Description |
| --- | --- | --- |
| `debug.metadataLogging` | `false` | chunkHash / metadata comparison logs |
| `debug.dispatcherLogging` | `false` | Main-thread dispatcher logs |
| `debug.asyncLogging` | `false` | Async task logs (incl. SeedGen / shadow) |
| `debug.compressionLogging` | `false` | Decompression logs |
| `debug.chunkApplyLogging` | `false` | Chunk apply logs |
| `debug.networkLogging` | `false` | Network send/receive logs |
| `debug.cacheLogging` | `false` | Cache read/write logs |
| `debug.lightVerify` | `false` | Light verification logs |
| `debug.networkMetricsEnabled` | `false` | Client network metrics (force-enabled by smoke test `hassium.smokeTest=true`) |
| `debug.networkMetricsAutoReset` | `true` | Auto-reset session metric counters on server disconnect |

Server `hassium-server.toml`:

| Key | Default | Description |
| --- | --- | --- |
| `debug.dispatcherLogging` | `false` | Main-thread dispatch / MSPT logs |
| `debug.asyncLogging` | `false` | Async task logs |
| `debug.compressionLogging` | `false` | Compression send logs |
| `debug.chunkApplyLogging` | `false` | Chunk resend / resync logs |
| `debug.networkLogging` | `false` | Network send/receive logs |

Hot paths are quiet by default (a few lifecycle INFO lines); enable specific `debug.*` keys when diagnosing. ERROR / WARN always print. See [Troubleshooting](Troubleshooting-en).

---

## Common adjustments

| Goal | Change |
| --- | --- |
| Disable save compression (keep network optimizations) | `storage.enabled = false` (off by default) |
| Temporarily disable save compression before backing up | Same, then back up the world |
| Disable shadow side / cache (vanilla path everywhere) | `chunk.enabled = false` (server stops stripping light; light arrives with packets) |
| Local generation on join (same versions) | `chunk.seedGenEnabled = true` on both sides (mind the seed-leak surface) |
| Aggregation breaks a third-party channel | Disable `master.enablePacketAggregation`, or add the channel ID to `master.compressionBlacklist` |
| Client cache only (no server mod) | Install on the client alone; server default `compat.requireClientMod = false` |
| Force the client mod | Server `compat.requireClientMod = true` |

---

[← Installation](Installation-en) · [Home](Home-en) · [→ Commands](Commands-en)
