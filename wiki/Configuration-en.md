# Configuration

---

> **English**: [Configuration](Configuration) · English

Hassium generates two TOML files under `config/hassium/` on first launch:

| File | Side | Contents |
| --- | --- | --- |
| `hassium-client.toml` | Physical client | Chunk core (`chunk.*`), client debug |
| `hassium-server.toml` | Physical client + dedicated server | Storage (`storage.*`), server transport (`master.*`), compat (`compat.*`), `chunk.lightStrip` / `chunk.seedGenEnabled`, server debug |

On a **physical client** both files are read and written: `client.toml` configures client behavior; `server.toml` is used by the **integrated server / Open to LAN** (the in-game config UI shows client keys only — edit server keys in the TOML directly). Dedicated servers use only `server.toml`. `storage.enabled` takes effect only on dedicated servers; singleplayer/LAN keeps the vanilla save format.

In-game editors:

| Loader | Entry | Notes |
| --- | --- | --- |
| Fabric | Install [Mod Menu](https://modrinth.com/mod/modmenu) and Cloth, open from the mod list | No FCAP / Configured dependency |
| Forge | "Config" button in the mod list | Requires Cloth |
| NeoForge | "Config" button in the mod list | Requires Cloth; Configured optional |

> You can also edit the TOML files and restart; GUI and TOML stay in sync (the GUI only changes client fields; server fields are preserved as-is).
> Key-set source of truth: `ConfigSchema` (52 keys); full audit in the repo [`docs/config-audit.md`](https://github.com/limuqy/Hassium/blob/master/docs/config-audit.md).
> Descriptions below match the TOML comments (from `ConfigSchema` commentEn).

---

## Full key reference

### Chunk core (`chunk.*`, client)

| Key | Default | Description |
| --- | --- | --- |
| `chunk.enabled` | `true` | Whether to enable the chunk-core cache |
| `chunk.maxSizeMb` | `4096` | Max cache size in MB (shadow-world disk cap; excess triggers heat eviction) |
| `chunk.hotScoreThreshold` | `0.3` | Heat-score threshold (below = cold region file; preferred for eviction) |
| `chunk.recencyWeight` | `0.7` | Recency weight in heat score |
| `chunk.frequencyWeight` | `0.3` | Visit-frequency weight in heat score |
| `chunk.cleanupIntervalTicks` | `6000` | Cleanup check interval in ticks |
| `chunk.targetSizeMb` | `0` | Target cache size in MB (0 = auto) |
| `chunk.minCleanupBatchSize` | `100` | Max region files evicted per cleanup pass |
| `chunk.sectionDeltaEnabled` | `true` | Enable section delta (server-side planning + client-side apply) |
| `chunk.maxChunksPerFrame` | `6` | Per-tick cache-read production cap (shadow enqueue + shadow disk); consume is time-budget only |
| `chunk.mainThreadChunkBudgetMs` | `15` | Main-thread apply budget in ms |
| `chunk.seedGenEnabled` | `false` | Enable SeedGen (local pristine chunks; both sides same version; default off). Server enablement sends the world seed |
| `chunk.viewDistanceExtensionEnabled` | `true` | Beyond-view render OVD (shadow dual-window: local fill when clientRD > serverVD) |
| `chunk.maxRenderDistance` | `16` | Max effective client render distance for OVD |

### Chunk core (`chunk.*`, server)

| Key | Default | Description |
| --- | --- | --- |
| `chunk.lightStrip` | `true` | Enable light stripping |
| `chunk.seedGenEnabled` | `false` | Enable SeedGen (server sends world seed; client gates local worldgen via shadow tracking then compare-pull; both sides same version; default off). WARNING: this leaks the server world seed to clients |

### Server transport (`master.*`)

| Key | Default | Description |
| --- | --- | --- |
| `master.enabled` | `true` | Enable master-core network channel |
| `master.enabledOnLan` | `false` | Enable Hassium network features for remote LAN players on an Open-to-LAN host (handshake/aggregation/push/lightStrip). Default off; host local memory connection stays vanilla; storage remains dedicated-only |
| `master.compressionLevel` | `3` | Private-channel ZSTD level |
| `master.enablePacketAggregation` | `true` | Enable packet aggregation |
| `master.aggregationMaxWaitTimeMs` | `50` | Flush watchdog: force flush if none happened for this many ms (tick-end flush is primary; covers main-thread stalls) |
| `master.aggregationMaxSize` | `262144` | Aggregation max size (bytes) |
| `master.compressionBlacklist` | `[]` | Third-party packet IDs excluded from compression / aggregation (default empty). Hassium control-plane and private channels are always hard-coded excluded; editing this list does not affect them |
| `master.maxChunksPerTick` | `5` | Per-player per-tick chunk send cap: Pull FULL/DELTA completions + vanilla whole-chunk path (≈ value×20/s at full tick) |

#### Entity optimization (`master.entity*`)

Replication cadence only — protocol unchanged, **vanilla clients join and benefit**. Follows the `master.enabled` master switch; all off = vanilla behavior.

| Key | Default | Description |
| --- | --- | --- |
| `master.entityTieredUpdateEnabled` | `true` | Enable distance-tiered entity updates (entities further away are updated less often). Enabled by default; turning it off disables only the distance tables — density, pressure and smooth-push remain independent |
| `master.entityTierIntervals` | `"3,6,10,20"` | Entity update interval in ticks for the four distance tiers, comma-separated, ordered near/mid/far/edge (tier boundaries at 25%/50%/75% of the tracking range). Default 3,6,10,20. Values must be non-decreasing; 0 or blank means default |
| `master.entityItemTierIntervals` | `"2,4,8,16"` | Update interval in ticks for dropped items and experience orbs across the same four tiers, default 2,4,8,16. Raise these values when many items are on the ground; keep the near value at 3 ticks or lower so items next to the player still move smoothly |
| `master.entityDensityThrottleEnabled` | `true` | Enable hotspot throttling: when too many entities pile up in one chunk, their update interval is stretched further. Enabled by default |
| `master.entityDensityTierCounts` | `"32,64,96,128"` | Per-tier hotspot threshold: once the number of active entities in the entity's own chunk reaches this value, that tier's interval is multiplied by the matching factor. Comma-separated, near/mid/far/edge; default 32,64,96,128 |
| `master.entityDensityTierFactors` | `"1.0,1.5,2.0,3.0"` | Per-tier hotspot multiplier applied once the threshold above is reached, comma-separated, near/mid/far/edge; default 1.0,1.5,2.0,3.0 (1.0 means no change). Decimals allowed; values below 1 are treated as 1 |
| `master.entityMaxThrottleFactor` | `4` | Upper bound on the product of the hotspot factor and the pressure factor (default 4). Raise it to throttle harder in crowded areas |
| `master.entityFrameBudgetPerPlayer` | `128` | Expected entity update packets per player per tick (default 128). When a player keeps exceeding it, entities in their view are updated less often to avoid lag; 0 = no automatic limit |
| `master.entitySmoothPushEnabled` | `true` | Entity smooth push: entities sharing the same update interval are phase-staggered by UUID so total volume over an interval is unchanged but the per-tick spike is flattened. Enabled by default |

### Storage (`storage.*`)

| Key | Default | Description |
| --- | --- | --- |
| `storage.enabled` | `false` | Enable save compression (default off; chunk cache unaffected) |
| `storage.zstdLevel` | `3` | Storage ZSTD compression level |

### Compat (`compat.*`)

| Key | Default | Description |
| --- | --- | --- |
| `compat.requireClientMod` | `false` | Require the Hassium client mod |
| `compat.autoDowngradeOnError` | `true` | Auto-downgrade on error |

### Debug (`debug.*`, per side)

Client `hassium-client.toml`:

| Key | Default | Description |
| --- | --- | --- |
| `debug.metadataLogging` | `false` | Metadata debug logging |
| `debug.dispatcherLogging` | `false` | Main-thread dispatcher debug logging |
| `debug.asyncLogging` | `false` | Async-task debug logging |
| `debug.compressionLogging` | `false` | Compression debug logging |
| `debug.chunkApplyLogging` | `false` | Chunk-apply debug logging |
| `debug.networkLogging` | `false` | Network debug logging |
| `debug.cacheLogging` | `false` | Cache debug logging |
| `debug.lightVerify` | `false` | Light verification and light-packet apply probes |
| `debug.networkMetricsEnabled` | `false` | Enable client network metrics |
| `debug.networkMetricsAutoReset` | `true` | Auto-reset network metrics when leaving a server |

Server `hassium-server.toml`:

| Key | Default | Description |
| --- | --- | --- |
| `debug.dispatcherLogging` | `false` | Main-thread dispatcher debug logging |
| `debug.asyncLogging` | `false` | Async-task debug logging |
| `debug.compressionLogging` | `false` | Compression debug logging |
| `debug.chunkApplyLogging` | `false` | Chunk-apply debug logging |
| `debug.networkLogging` | `false` | Network debug logging |

Hot paths are quiet by default (a few lifecycle INFO lines); enable specific `debug.*` keys when diagnosing. ERROR / WARN always print. See [Troubleshooting](Troubleshooting-en).

---

## Common adjustments

| Goal | Change |
| --- | --- |
| Disable save compression (keep network optimizations) | `storage.enabled = false` (off by default) |
| Temporarily disable save compression before backing up | Same, then back up the world |
| Disable chunk-core cache (vanilla path everywhere) | `chunk.enabled = false` (server stops stripping light; light arrives with packets) |
| Local generation on join (same versions) | `chunk.seedGenEnabled = true` on both sides (mind the seed-leak surface) |
| Aggregation breaks a third-party channel | Disable `master.enablePacketAggregation`, or add the channel ID to `master.compressionBlacklist` |
| Client cache only (no server mod) | Install on the client alone; server default `compat.requireClientMod = false` |
| Force the client mod | Server `compat.requireClientMod = true` |

---

[← Installation](Installation-en) · [Home](Home-en) · [→ Commands](Commands-en)
