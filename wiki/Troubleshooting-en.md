# Troubleshooting

---

> **English**: [Troubleshooting](Troubleshooting) · English

## Log locations

| Environment | Path |
| --- | --- |
| Client (release) | `.minecraft/logs/latest.log` |
| Client (Loom dev) | `fabric/run/client/logs/latest.log`, `forge/run/client/logs/latest.log`, `neoforge/run/client/logs/latest.log` |
| Server | `<server>/logs/latest.log` |

> Rotated logs live next to `latest.log` as `yyyy-MM-dd-N.log.gz`.

---

## Post-launch check

After starting the client or server, search `latest.log` for `Hassium`, `ERROR`, and `Exception`. On ERROR / Exception, include the surrounding time window when reporting.

---

## Debug log switches

The `debug.*` block at the end of `config/hassium/hassium-client.toml` or `hassium-server.toml` (per side — the two sides do not share one key set):

| Key | Side | Meaning |
| --- | --- | --- |
| `debug.metadataLogging` | Client | chunkHash / metadata comparison |
| `debug.dispatcherLogging` | Both | Main-thread dispatcher |
| `debug.asyncLogging` | Both | Async tasks |
| `debug.compressionLogging` | Both | Compression/decompression |
| `debug.chunkApplyLogging` | Both | Chunk apply |
| `debug.networkLogging` | Both | Network send/receive |
| `debug.cacheLogging` | Client | Cache read/write |
| `debug.lightVerify` | Client | Light verification |

Enable one category at a time; hot paths are quiet by default and enabling everything noticeably hurts FPS. ERROR / WARN always print.

---

## Common symptoms

| Symptom | Likely cause | Action |
| --- | --- | --- |
| Joining feels slower than vanilla | Client cache dir full or slow disk | `/hassiumc stats` for cache hits; check `hassium_cache` size and disk IO |
| Lighting anomalies | Shadow-side vs Sodium interaction | Disable `chunk.enabled` (server stops stripping light; light arrives with packets; vanilla path everywhere) |
| refmap WARNs at client startup | Normal in Loom dev environments | Ignore; release jars do not reproduce |
| Kicked from the server | `compat.requireClientMod = true` and the client lacks the mod | Install Hassium on the client; or set server `requireClientMod = false` |
| Saves unreadable | type 126 left behind after uninstalling/downgrading | Reinstall the Hassium build matching the save |
| Aggregation breaks third-party packets | Aggregation interference | Disable `master.enablePacketAggregation` or add `master.compressionBlacklist` |
| Heavy re-downloads after reconnect | Shadow save out of sync with the server / cache dir broken | Check the matching `hassium_cache` dir and disk space; some MISSes on the first load are normal — persistent re-downloads: delete that server's cache dir and rejoin (below) |

---

## Resetting the client cache

Fully reset the client cache (for one server only):

1. Leave that server
2. Close the game
3. Delete `.minecraft/hassium_cache/<server-id>/` (the directory name usually contains the server IP and port)
4. Rejoin

> **Caution**: this discards all cache-hit history for that server. `hassium_cache` is isolated per server; deleting one does not affect others.

---

## Reporting

If the steps above do not resolve it, open a GitHub issue with:

- MC version
- Loader and version (Fabric / Forge / NeoForge)
- Hassium version
- Client / server log excerpts (relevant `latest.log` window)
- `/hassium stats` or `/hassiumc stats` output
- Minimal reproduction steps

Repository: https://github.com/limuqy/Hassium/issues

---

[← FAQ](FAQ-en) · [Home](Home-en)
