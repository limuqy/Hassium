# Compatibility

---

> **English**: [Compatibility](Compatibility) · English

Overview of Hassium's compatibility with common optimization mods, plus configuration escape hatches. Every conclusion includes a recommended action.

---

## Overview

| Target | Verdict | Notes |
| --- | --- | --- |
| **Bobby / similar client-side beyond-view caches** | ❌ **Incompatible** | Hassium's shadow server manages caching and redelivery itself; do not co-install |
| **Immersive Portals** | ❌ **Incompatible** | |
| **Similar compression / protocol replacements (Netty Zlib swaps)** | ⚠️ Conditional | Hassium's channel compression never touches the vanilla compression layer; conflicts remain if the other mod replaces the vanilla pipeline — pick one |
| **Starlight / ScalableLux** | ✅ **Actively compatible** | Same-bloodline lighting engines (ScalableLux provides `starlight`; mutually exclusive with Starlight); 4 shadow control-plane points adapted. Escape: `chunk.enabled = false` |
| **Aggregation breaking third-party packets** | ⚠️ Disable aggregation or blacklist | `master.enablePacketAggregation = false` or `master.compressionBlacklist` (third-party IDs; Hassium control-plane is always hard-coded excluded) |
| **Anti-x-ray (rewrites outgoing chunk packets)** | ✅ Intended compatible | Miss path reuses already-built packet bytes; implementations that rewrite only on `Connection.send` after Hassium's cancel may bypass |
| **Distant Horizons / Voxy** | ✅ Intended compatible | Independent LOD channels; same aggregation escape if needed |
| **ViaVersion** | ⚠️ Conditional | See table below |
| **Sodium / Iris / Lithium / FerriteCore / EntityCulling / ImmediatelyFast** | ✅ Tested | Fabric 1.20.1 session record in [Support-Matrix](Support-Matrix-en) |
| **C2ME** | ✅ Soft compatible | Default modules tested; no promise with chunkio rewrite fully on; disable `storage.enabled` as the escape |
| **File-level server backups (incl. InstantBackup)** | ✅ Compatible | 126 is transparent to backup tools |
| **Semantic Anvil-decompressing tools** | ❌ Incompatible | They do not understand type 126 |

---

## ViaVersion topologies

| Topology | Verdict |
| --- | --- |
| Same version, Hassium on both sides | Via not involved; normal |
| Server Hassium + Via, client **without** Hassium | Supported: the mod-less client speaks vanilla (empty login-handshake answer → vanilla path), Via translates vanilla |
| Hassium on both sides but different MC versions (bridged by Via) | ❌ Not promised (capability negotiation assumes same versions; cross-version untested) |

> Channel compression applies only to players who completed the Hassium handshake; un-handshaked players (incl. Via-translated targets) use the vanilla path — no framing conflicts.

---

## Starlight / ScalableLux

Both engines share the same bloodline (ScalableLux `provides: ["starlight"]`; mutually exclusive) and fully replace `LevelLightEngine`. Output lighting uses the public API and is naturally compatible; four control-plane points (clear-light, `lightChunk`, `lightTasks` watermark, sky sources) are degraded via `ForeignLightEngine`. Vanilla Starlight was merged into MC itself on newer versions; **ScalableLux is the current external engine**. Details: [`docs/mod-compat.md`](https://github.com/limuqy/Hassium/blob/master/docs/mod-compat.md) §7b.

---

## Escape hatches

| Goal | Change |
| --- | --- |
| Disable save compression, keep network optimizations | `storage.enabled = false` (off by default) |
| Disable the custom channel and push | `master.enabled = false` |
| Disable packet aggregation | `master.enablePacketAggregation = false` |
| Exclude third-party packets from compression/aggregation | `master.compressionBlacklist` |
| Disable shadow side / cache (vanilla path; server stops stripping light) | `chunk.enabled = false` |
| Disable section delta (stale goes full) | `chunk.sectionDeltaEnabled = false` |
| Force the client mod | `compat.requireClientMod = true` |

---

## Config GUI compatibility

| Mod | Relationship |
| --- | --- |
| **Mod Menu** (Fabric) | Soft-compatible; installing it alone opens the Cloth config |
| **Cloth Config** | jiJ'd on Fabric / Forge / NeoForge; primary config UI |
| **Configured** | Optional on Forge/NeoForge; not needed on Fabric |
| **Forge Config API Port** | **Not used** on Fabric (self-managed Night Config toml); the FCAP Forge bridge retired with Forge 1.20.6 |

---

## Save-format notes

- Hassium type 126 is a **ZSTD payload on disk**; the outer `.mca` layout is unchanged
- After uninstalling, saves remain 126: reinstall a **matching** Hassium version to read them
- Client caches are **not guaranteed across MC major versions**: old caches are lazily overwritten (MISS → re-fetch → persist); no startup-time wipe
- Reading 126 after a rollback: install the Hassium build matching that MC version
- File-level backups (whole-file/dir/zip/incremental blobs, no compression-type parsing) are compatible; tools that decompress chunks → edit NBT → recompress are not

---

## Compatibility test record (2026-07, Fabric 1.20.1)

Environment: ~50 optimization mods (FO-style: Sodium / Iris / Lithium / FerriteCore / C2ME / EntityCulling / ImmediatelyFast / Mod Menu / Cloth, etc.; **without** Bobby / ViaFabric / Immersive Portals).

| Check | Result |
| --- | --- |
| Startup and join | Pass; handshake `accepted=true` |
| Client cache | Shadow save / heat / disconnect cleanup normal |
| Runtime stats | `/hassiumc stats` shows compression savings and cache hits |
| `latest.log` from Hassium | No ERROR / Exception; only a dev-environment refmap WARN (see [Troubleshooting](Troubleshooting-en)) |

### Still recommended

- [ ] Anti-x-ray + Hassium client: ores must stay hidden
- [ ] Distant Horizons both sides / Voxy + companion: LOD works
- [ ] Via: mod-less old clients can join; same-version Hassium clients fully functional
- [ ] C2ME chunkio rewrite on/off × `storage` on/off matrix
- [ ] Sodium + `chunk.enabled` on/off (lighting anomalies)
- [ ] Forge / NeoForge equivalent optimization-pack smoke

---

[← World-Export](World-Export-en) · [Home](Home-en) · [→ Support-Matrix](Support-Matrix-en)
