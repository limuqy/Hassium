# Troubleshooting

---

> **简体中文**: [Troubleshooting](Troubleshooting) · English

## Log file locations

| Environment | Path |
| --- | --- |
| Client (production) | `.minecraft/logs/latest.log` |
| Client (Loom dev) | `fabric/run/client/logs/latest.log`, `forge/run/client/logs/latest.log`, `neoforge/run/client/logs/latest.log` |
| Server | `<server>/logs/latest.log` |

> Historical logs roll to `yyyy-MM-dd-N.log.gz` in the same directory.

---

## Post-start checks

After starting the client or server, search `latest.log` for `Hassium`, `ERROR`, and `Exception`. When an ERROR / Exception appears, troubleshoot or report it together with the surrounding log window.

---

## Debug toggles

The `debug.*` block near the bottom of `config/hassium/hassium-client.toml` or `config/hassium/hassium-server.toml` (keys differ by side):

| Key | Side | Meaning |
| --- | --- | --- |
| `debug.metadataLogging` | client | chunkHash / metadata comparison |
| `debug.dispatcherLogging` | both | Main-thread dispatch |
| `debug.asyncLogging` | both | Async tasks |
| `debug.compressionLogging` | both | Compression / decompression |
| `debug.chunkApplyLogging` | both | Chunk apply |
| `debug.networkLogging` | both | Network send / receive |
| `debug.cacheLogging` | client | Cache read / write |
| `debug.lightVerify` | client | Light verification |
| `debug.dataplaneLogging` | server | UDP data-plane hot path |

Toggle the relevant category only; the hot path is quiet by default and enabling all of them will hurt FPS noticeably. `ERROR` / `WARN` are always emitted.

---

## Symptoms and checks

| Symptom | Likely cause | Action |
| --- | --- | --- |
| Joins are slower, not faster | Client cache is full or disk is slow | `/hassiumc stats` for hit ratio; check `hassium_cache` size and disk IO |
| Far chunks flicker | Beyond-view render handoff with real chunks | Disable `chunk.viewDistanceExtensionEnabled` to validate; upgrade to a recent version |
| Light glitches | `chunk.hassiumEngineEnabled` clash with Sodium | Disable `chunk.hassiumEngineEnabled` (the server then stops stripping light) |
| Client logs show refmap WARN | Normal in Loom dev environments | Ignore; released jars do not replay this |
| Server rejects clients | `compat.requireClientMod = true` and clients do not have the mod | Install Hassium on the client; or set `requireClientMod = false` |
| Saves fail to load | Type 126 left behind after uninstall/downgrade | Reinstall the matching MC version of Hassium |
| Third-party packets break under aggregation | Aggregation interferes | Disable `master.enablePacketAggregation`, or use `master.compressionBlacklist` |
| Fog extends too far, far chunks pop in | RD > 32 with Fog Mixin not implemented | Keep RD ≤ 32 |
| In-process Via bridge misbehaves | `master.globalPacketCompression` vs compression frame assumptions | Disable `master.globalPacketCompression` |

---

## Migration and gateway debugging

| Symptom | Likely cause | Action |
| --- | --- | --- |
| Client drops straight away on master failure, no migration | Gateway listener not ready / migration window too short | Confirm the master gateway port is reachable (`master.controlReachableEndpoints[0]`, falls back to `25566`); raise `master.migrationSilentTimeoutMs` (default `10000`) as needed; legacy fallback key is `migrationFaultTimeoutMs` |
| Terrain re-downloads heavily after migration (resume refused) | Shadow-side save inconsistent with the master / cache directory broken | Check the `hassium_cache` entry and disk space; some MISS right after migration is normal, but if re-downloads persist, delete that server's cache directory and rejoin (below) |
| UDP data plane configured but no UDP traffic | `dataplane.enabled` is off by default | Enable it explicitly; while off, all traffic goes through the gateway frame connection (TCP control channel) |
| Gateway port already in use | Conflict with another service | Point `master.controlReachableEndpoints` at another port (fallback is `25566`) |

See [Network Core and Master Migration](Network-Core-and-Master-Migration-en).

---

## Resetting the client cache

Full reset for one server only:

1. Leave the server
2. Close the game
3. Delete `.minecraft/hassium_cache/<server-id>/` (the directory name usually embeds the server IP and port)
4. Reconnect

> Use with care — deletion drops the cache hit ratio for that server. `hassium_cache` is partitioned per server, so removing one does not affect others.

---

## Reporting feedback

If troubleshooting does not resolve the issue, when filing a GitHub Issue please include:

- MC version
- Loader and version (Fabric / Forge / NeoForge)
- Hassium version
- Relevant window of client/server `latest.log`
- `/hassium stats` or `/hassiumc stats` output
- Minimal reproduction steps

Repo: https://github.com/limuqy/Hassium/issues

---

[← FAQ](FAQ-en) · [Home](Home-en)
