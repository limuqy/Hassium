# Beyond-View Render (Planned)

---

> **English**: [Beyond-View-Render](Beyond-View-Render) · English

> **Status: planned, not enabled in the current build.** After the 2.0.0 direct-connection regression, chunk delivery is fully driven by the shadow server's vanilla tracking (must-deliver on range-enter, Forget on range-exit). The old OVD ring path (`renderOnly` chunks, `ClientHeatIndex` per-chunk eviction, out-of-range `ChunkDataRequestC2S`) was cut from the code, and its config keys (`chunk.viewDistanceExtensionEnabled` / `chunk.maxRenderDistance` / `chunk.ovdUnloadDelaySecs` / `chunk.ovdLocalGeneration`) were removed. This page keeps the design record; the "Enable" section and keys return when the feature ships.

---

## Design goal

Beyond-view render lets a multiplayer client, when its render distance (RD) exceeds the server view distance (serverVD), backfill positions in the `serverVD < dist ≤ clientVD` ring from local cache — **render-only, no simulation**, and it never requests out-of-range chunks or block entities from the server.

## Design notes (historical)

- **Multiplayer only**: not enabled in singleplayer
- **Ring backfill**: cached chunks apply as renderOnly; cache misses roll back silently without requesting from the server
- **Real chunks win**: when a real chunk arrives at a renderOnly position, it overrides the marker and requests BEs
- **Exclusive with Bobby**: Hassium's own backfill; do not install together with Bobby
- **Resource reuse**: reuses the shadow cache eviction machinery; no dedicated memory pool
- **Boundaries**: auto-clears when `clientVD ≤ serverVD`; `serverRenderDistance == 0` falls back to simulationDistance

## Current behavior

In the current build, in-range chunks are delivered by the shadow server (including cache-hit reuse and section delta); out-of-range terrain is not backfilled or requested — identical to vanilla. For out-of-range terrain previews, wait for this feature to ship.

---

[← Features](Features-en) · [Home](Home-en) · [→ World-Export](World-Export-en)
