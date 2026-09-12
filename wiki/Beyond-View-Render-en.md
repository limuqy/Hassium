# Beyond-View Render (OVD, shadow dual-window)

---

> **English**: [Beyond-View-Render](Beyond-View-Render) · English

> **Status: current feature.** Shadow dual-window OVD: tracking expands to effective clientRD; the authoritative window (serverVD) uses Compare+Pull, the OVD window fills from local sources only (disk / inject) and never requests from the real server. The client only raises `ClientChunkCache` radius and intercepts Forget. See [`docs/chunk-cache.md`](../docs/chunk-cache.md) §10.

---

## Design goal

When a multiplayer client's render distance (RD) exceeds the server view distance (serverVD), backfill the `serverVD < dist ≤ clientRD` ring from local cache — **render-only, no simulation**, and never request out-of-range chunks from the server.

## Current topology

- **Authoritative window** (`dist ≤ serverVD`): shadow tracking drives unified Compare+Pull
- **OVD window** (`serverVD < dist ≤ clientRD`): local sources only (injected / disk); never requested from the server
- **Client**: raise `ClientChunkCache` radius; intercept vanilla Forget so the ring stays loaded
- **Off**: `chunk.viewDistanceExtensionEnabled=false` falls radius back to serverVD (authoritative window only)
- **Exclusive with Bobby**: Hassium's own backfill; do not install together with Bobby

## Config

| Key | Default | Description |
| --- | --- | --- |
| `chunk.viewDistanceExtensionEnabled` | `true` | Beyond-view master switch (depends on `chunk.enabled`) |
| `chunk.maxRenderDistance` | `16` | Max effective clientRD (2–64) |

Delayed unload (`chunk.ovdUnloadDelaySecs`) was cancelled and is not restored with dual-window OVD; OVD local generation (`chunk.ovdLocalGeneration`) has been retired.

---

[← Features](Features-en) · [Home](Home-en) · [→ World-Export](World-Export-en)
