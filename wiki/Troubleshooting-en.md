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

The `debug.*` block near the bottom of `config/hassium/hassium-common.toml`:

| Key | Meaning |
| --- | --- |
| `debug.metadataLogging` | chunkHash / metadata comparison |
| `debug.dispatcherLogging` | Main-thread dispatch |
| `debug.asyncLogging` | Async tasks |
| `debug.compressionLogging` | Compression / decompression |
| `debug.chunkApplyLogging` | Chunk apply |
| `debug.networkLogging` | Network send / receive |
| `debug.cacheLogging` | Cache read / write |

Toggle the relevant category only; the hot path is quiet by default and enabling all of them will hurt FPS noticeably. `ERROR` / `WARN` are always emitted.

---

## Symptoms and checks

| Symptom | Likely cause | Action |
| --- | --- | --- |
| Joins are slower, not faster | Client cache is full or disk is slow | `/hassiumc stats` for hit ratio; check `hassium_cache` size and disk IO |
| Far chunks flicker | Beyond-view render handoff with real chunks | Disable `clientCache.viewDistanceExtensionEnabled` to validate; upgrade to a recent version |
| Light glitches | `clientCache.lightCacheEnabled` clash with Sodium | Disable `clientCache.lightCacheEnabled` |
| Client logs show refmap WARN | Normal in Loom dev environments | Ignore; released jars do not replay this |
| Server rejects clients | `compat.requireClientMod = true` and clients do not have the mod | Install Hassium on the client; or set `requireClientMod = false` |
| Saves fail to load | Type 126 left behind after uninstall/downgrade | Reinstall the matching MC version of Hassium |
| Third-party packets break under aggregation | Aggregation interferes | Disable `network.enablePacketAggregation`, or use `network.compressionBlacklist` |
| Fog extends too far, far chunks pop in | RD > 32 with Fog Mixin not implemented | Keep RD ≤ 32 |
| In-process Via bridge misbehaves | `globalPacketCompression` vs compression frame assumptions | Disable `network.globalPacketCompression` |

---

## Data-plane debugging

After enabling `network.dataPlane.enabled`, verify the six self-check markers in order:

1. `UDP_BIND_OK` fails: check UDP port in use / firewall rules
2. `UDP_WRR_OK` fails: validate `weight` values
3. `FAILOVER_PERMIT_OK` fails: confirm `controlStallMs` is not too short
4. `FAILOVER_RECONNECT_OK` fails: confirm candidate endpoint reachability on the public network
5. `CACHE_RESUME_HIT` fails: verify `ClientRecoveryState` truly blocks finalize (dirty-flags retained)
6. `FAILOVER_TERMINAL_OK` fails: candidate exhaustion did not call `consumeTerminalCleanup` exactly once

See [Data-Plane-and-Failover](Data-Plane-and-Failover-en).

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
