# FAQ

---

> **English**: [FAQ](FAQ) · English

## Storage

### Q: Does enabling storage change the save format?

A: Yes. With `storage.enabled = true` chunk payloads on disk become ZSTD type 126; the `.mca` shell is unchanged. **Back up your world before first enable.**

### Q: Can I read saves after uninstalling Hassium?

A: Saves remain type 126 — **reinstall the Hassium build matching that MC version** to read them. To avoid the lock-in: set `storage.enabled = false` first (keeping network optimizations), let chunks be rewritten with vanilla Zlib, then uninstall.

### Q: Saves unreadable after a rollback?

A: Reinstall the Hassium version compatible with that save. Compression resources are bundled with Hassium; no separate install or config needed.

### Q: Do client caches survive an MC major-version upgrade?

A: Since 1.21.5 client caches are **not guaranteed across MC major versions**. Old caches are lazily overwritten (MISS → re-fetch → persist); nothing is wiped at startup, but the first session may see more MISSes. See [Compatibility](Compatibility-en).

---

## Network

### Q: Can a client without Hassium join my Hassium server?

A: Yes, by default. With `compat.requireClientMod = false` (default) mod-less clients connect over vanilla and only get server-side compression; client caching and negotiated compression need the mod on both sides.

### Q: Can I run a similar compression mod alongside Hassium?

A: Conditionally. Hassium's channel compression never touches the vanilla compression layer (pipeline-level global packet compression retired), but conflicts remain if the other mod replaces the vanilla pipeline. Pick one, or set `master.enabled = false` (client cache only).

### Q: A third-party mod's packets break inside Hassium aggregation?

A: Escape hatches: (1) `master.enablePacketAggregation = false`, or (2) add that **third-party** packet ID to `master.compressionBlacklist` (Hassium control-plane is always hard-coded excluded; this list is for third-party only). If the packet is a Hassium `hassium:*` ID, check that both sides run matching versions — do not put it on the blacklist.

### Q: Which ports does a public deployment need?

A: Only the game port (vanilla TCP). The direct topology has no gateway/UDP ports (both retired).

### Q: Does a reconnect re-download all chunks?

A: No. The shadow world is saved on disconnect (`hassium_cache/<serverId>/world`); after reconnecting, unchanged chunks hit the cache (UNCHANGED), changed chunks arrive as section delta (DELTA), and only missing chunks go full.

---

## Local generation (SeedGen)

### Q: Does SeedGen leak my world seed?

A: **Yes**. With `chunk.seedGenEnabled` enabled on the server, the world seed is sent to clients — equivalent to leaking the server seed (seed maps / exported saves can exploit it). Weigh this for public servers.

### Q: Can SeedGen work with mismatched client/server versions?

A: No. Local generation requires both sides on the same version; mismatches automatically fall back to full requests.

---

## Export

### Q: Can exported worlds be loaded in singleplayer right away?

A: Not yet. The 2.0.0 `export` copies the shadow world directory, keeping the type 126 + chunkHash format (vanilla translation pending); output goes to `<gameDir>/hassium_exports/<cacheId>/`.

### Q: Do exported worlds contain entities?

A: **No**. The shadow world contains only chunks/lighting and block-entity data — no player inventories, advancements, or regular entities. Export limits: [World-Export](World-Export-en).

---

## Beyond-view render

### Q: Is beyond-view render available now?

A: **Yes, and it is on by default.** Shadow dual-window OVD: the authoritative window (within the server view distance) uses the unified Compare+Pull, while the ring beyond it is backfilled from terrain the shadow server already has locally (disk/injected) — **render-only, never requested from the server**. Disable with `chunk.viewDistanceExtensionEnabled = false`. See [Beyond-View-Render](Beyond-View-Render-en).

### Q: Does Bobby conflict?

A: Yes. Hassium's shadow server manages caching and redelivery itself and is incompatible with Bobby — do not co-install.

---

## Troubleshooting

### Q: I see refmap load-failure WARNs in `latest.log`?

A: Dev-environment (Loom runtime) only — ignorable and does not affect behavior. Release jars ship the refmap and parse normally.

### Q: Why are there no hot-path logs?

A: Hot paths are quiet by default. Enable specific `debug.*` keys when diagnosing: `debug.metadataLogging` / `debug.networkLogging` / `debug.cacheLogging` / `debug.chunkApplyLogging`, etc. See [Troubleshooting](Troubleshooting-en).

---

[← Network-Architecture](Network-Architecture-en) · [Home](Home-en) · [→ Troubleshooting](Troubleshooting-en)
