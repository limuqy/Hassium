# FAQ

---

> **简体中文**: [FAQ](FAQ) · English

## Storage

### Q: Does enabling storage change the save format?

A: Yes. With `storage.enabled = true`, chunk payloads are written as ZSTD type 126 inside the unchanged `.mca` shell. **Back up worlds before first enabling**.

### Q: Can I read saves after uninstalling Hassium?

A: Saves remain type 126 and require reinstalling a **matching MC version** of Hassium. If you want to decouple: set `storage.enabled = false` (keeps network benefits until chunks are overwritten with vanilla Zlib) before uninstalling.

### Q: After rollback the save no longer loads?

A: Reinstall a Hassium version compatible with that save. Compression resources are bundled with Hassium; users do not install or configure them separately.

### Q: Will my client cache survive a major-version upgrade?

A: From 1.21.5 onward, the client cache is **not guaranteed to be cross-MC-version compatible**. Old chunks are lazily overwritten (MISS → refetch → persist); there is no full invalidate on start, but the first session may see more misses. See [Compatibility](Compatibility-en).

---

## Network

### Q: Can a client without Hassium connect to a Hassium-enabled server?

A: Yes by default. With `compat.requireClientMod = false` (default), vanilla clients connect via the vanilla protocol and benefit from server-side compression. Client cache, negotiated compression, and other advanced features require the mod on both sides.

### Q: I run another compression mod; can I coexist with Hassium?

A: No — it conflicts with `master.globalPacketCompression`. Escape hatches: `master.globalPacketCompression = false` or `master.enabled = false` (client cache only).

### Q: A third-party mod's packets break under Hassium aggregation. What now?

A: Escape via (1) `master.enablePacketAggregation = false`, or (2) add the packet ID to `master.compressionBlacklist`.

---

## Beyond-view render

### Q: I am using Bobby and want to try Hassium's beyond-view renderer?

A: **Pick one.** Hassium is incompatible with Bobby. Remove Bobby from the client before enabling Hassium's beyond-view render.

### Q: Does beyond-view render work in singleplayer?

A: No. It is multiplayer-only; singleplayer has no server-side `view-distance` limit.

### Q: With RD set to 48 I see far chunks pop in through the fog. Why?

A: Known limitation. The Fog Mixin is not implemented across the nine version segments; with RD > 32 the fog distance follows `getEffectiveRenderDistance` and far chunks may pop in. Recommended to keep RD ≤ 32.

### Q: How much memory does the beyond-view ring use?

A: Ring size depends on the gap between client RD and server view distance. Lower `chunk.maxRenderDistance`, or disable `chunk.viewDistanceExtensionEnabled`, to limit resource use. Beyond-view rendering reuses the existing cache eviction mechanism and adds no dedicated memory pool.

---

## Network Core and migration

### Q: Must the client run Hassium to connect through the gateway? Is the UDP data plane on by default?

A: Connecting through the in-process gateway (Network Core) to the master core is the default path in 2.0.0; the UDP data plane (bulk carrier for the gateway↔master channel) is off by default (`dataplane.enabled = false`) and only needs to be enabled when you want data lines. See [Network Core and Master Migration](Network-Core-and-Master-Migration-en).

### Q: Will the client disconnect when the master disconnects or stalls?

A: Not immediately. The L1 migration engine decides based on the effective silence timeout (default `master.migrationSilentTimeoutMs`=`10000`; explicitly changing `migrationFaultTimeoutMs` can fall back) and migrates seamlessly: disk cache and save queue are preserved, the new session resumes directly, no "Connection lost" popup; a real disconnect happens only when the master cannot recover. Migration is smoother when the UDP data plane is enabled and healthy.

---

## Export

### Q: Can I open the exported world directly as a singleplayer world?

A: Not yet. In 2.0.0 `export` copies the shadow-side world directory wholesale, keeping the type 126 + chunkHash on-disk format (vanilla translation is planned later); the export lands in `<gameDir>/hassium_exports/<cacheId>/`.

### Q: Does the exported world contain entities?

A: **No.** The shadow-side world holds only chunk/light and block-entity data; no inventory, advancements, or world entities. See [World-Export](World-Export-en) for full caveats.

---

## Debug

### Q: `latest.log` shows a refmap WARN?

A: This only appears in dev environments (Loom runtime) and is safe to ignore. Released client/server jars bundle the refmap and resolve targets normally.

### Q: The hot path has no logs?

A: The hot path is quiet by default. Toggle `debug.*` as needed: `debug.metadataLogging` / `debug.networkLogging` / `debug.cacheLogging` / `debug.chunkApplyLogging` etc. See [Troubleshooting](Troubleshooting-en).

---

[← Network-Core-and-Master-Migration](Network-Core-and-Master-Migration-en) · [Home](Home-en) · [→ Troubleshooting](Troubleshooting-en)
