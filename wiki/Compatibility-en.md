# Compatibility

---

> **简体中文**: [Compatibility](Compatibility) · English

Hassium compatibility with common optimization mods, plus escape hatches. Every verdict lists the action to take.

---

## Compatibility overview

| Target | Verdict | Notes |
| --- | --- | --- |
| **Bobby and similar client-side out-of-range caches** | ❌ **Incompatible** | Hassium ships its own beyond-view renderer; co-installation will conflict |
| **Immersive Portals** | ❌ **Incompatible** | |
| **Other compression / protocol-replacement mods (e.g. rewriting the Netty Zlib)** | ❌ **Incompatible** | Conflicts with `network.globalPacketCompression` |
| **Starlight** | — **Not considered** | Already merged into vanilla lighting |
| **Third-party packets hurt by aggregation** | ⚠️ Disable aggregation or blacklist | `network.enablePacketAggregation = false`, or add the channel ID to `network.compressionBlacklist` |
| **Anti-x-ray (rewrites the outbound chunk packet)** | ✅ Likely compatible | miss path reuses the already-built packet bytes; if the rewriter only patches `Connection.send` after Hassium cancels, it may bypass |
| **Distant Horizons / Voxy** | ✅ Likely compatible | Independent LOD channels; aggregation mishaps handled as above |
| **ViaVersion** | ⚠️ Conditional | See below |
| **Sodium / Iris / Lithium / FerriteCore / EntityCulling / ImmediatelyFast** | ✅ Compatibility-tested | Fabric 1.20.1 record in [Support-Matrix](Support-Matrix-en) |
| **C2ME** | ✅ Soft compatible | Default modules pass; chunkio rewrite fully on is not promised; `storage.enabled = false` is the escape hatch |
| **File-level server backup (incl. InstantBackup)** | ✅ Compatible | type 126 is transparent to backup tools |
| **Tools that semantically unpack Anvil** | ❌ Incompatible | Won't recognize type 126 |

---

## ViaVersion topologies

| Topology | Verdict |
| --- | --- |
| Same version, both sides install Hassium | Via not involved; works |
| Server Hassium + Via, client has no Hassium | Works: handshake fails → vanilla packets → Via translates |
| Both sides install Hassium but MC versions differ (bridged by Via) | ❌ Unsupported (wire format is tied to `MC_VER`) |

> Stacking `globalPacketCompression` with in-process Via can confuse compression frame assumptions. Recommend disabling global compression for in-process Via.

---

## Escape hatches

| Goal | Tweak |
| --- | --- |
| Disable storage compression, keep network benefits | `storage.enabled = false` |
| Disable custom channels and push | `network.enabled = false` |
| Disable global ZSTD (coexist with protocol replacement) | `network.globalPacketCompression = false` |
| Disable packet aggregation | `network.enablePacketAggregation = false` |
| Exclude a third-party channel from compression/aggregation | `network.compressionBlacklist` |
| Disable lighting optimization (recompute each load) | `clientCache.lightCacheEnabled = false` |
| Disable section delta (stale = full fetch) | `clientCache.sectionDeltaEnabled = false` |
| Disable beyond-view render, restore vanilla RD clamp | `clientCache.viewDistanceExtensionEnabled = false` |
| Require the mod on clients | `compat.requireClientMod = true` |

---

## Config GUI compatibility

| Mod | Relationship |
| --- | --- |
| **Mod Menu** (Fabric) | Soft-compatible; install separately to open the Cloth screen |
| **Cloth Config** | jiJ on Fabric / Forge / NeoForge; main config screen path |
| **Configured** | Optional on Forge/NeoForge; Fabric does not need it |
| **Forge Config API Port** | Fabric does not use it (Night Config manages TOML); only Forge 1.20.6 jiJs it for the ModConfigSpec bridge |

---

## Save compatibility notes

- Hassium type 126 is a **ZSTD payload on disk**; the outer `.mca` layout stays vanilla
- Uninstalling the mod leaves type 126 saves: reinstall a **matching version** of Hassium to read them
- Client cache is **not guaranteed to be cross-MC-version compatible**: after an upgrade the old cover is lazily overwritten (MISS → refetch → persist); no full invalidate on start
- After rollback, to read type 126 you must install the matching MC version of Hassium
- File-level backup (whole-file/directory/zip/incremental blob, not unpacking compression types) is compatible; tools that unpack chunk → edit NBT → repack are not

---

## Compatibility test (2026-07, Fabric 1.20.1)

Environment: ~50 optimization-oriented mods (FO-style: Sodium / Iris / Lithium / FerriteCore / C2ME / EntityCulling / ImmediatelyFast / Mod Menu / Cloth etc.; **no** Bobby / ViaFabric / Immersive Portals installed).

| Check | Result |
| --- | --- |
| Launch and join | Pass; handshake `accepted=true`, `globalCompression=true` |
| Client cache | Bloom / heat / CacheSaveQueue normal; disconnect cleanup normal |
| Runtime stats | Use `/hassiumc stats` to inspect compression savings and cache hits |
| `latest.log` for Hassium | No ERROR / Exception; only the dev-environment refmap WARN (see [Troubleshooting](Troubleshooting-en)) |

### Suggested coverage

- [ ] Anti-x-ray + Hassium client: ores stay obfuscated
- [ ] Distant Horizons both sides / Voxy + companion mod: LOD works
- [ ] Via: vanilla clients connect; same-version Hassium clients have full features
- [ ] C2ME chunkio rewrite on/off × `storage` on/off matrix
- [ ] Sodium + `lightCacheEnabled` on/off (light glitches)
- [ ] Forge / NeoForge equivalent opt-pack compatibility test
- [ ] Beyond-view in-circuit: multiplayer with `view-distance=8`, client RD=16, the visited ring is visible; F3 shows no large out-of-range requests
- [ ] Beyond-view + Sodium: meshing stays correct as the ViewArea expands

---

[← World-Export](World-Export-en) · [Home](Home-en) · [→ Support-Matrix](Support-Matrix-en)
