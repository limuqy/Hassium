# Hassium

<p align="center">
  <img src="https://raw.githubusercontent.com/limuqy/Hassium/master/common/src/main/resources/assets/hassium/logo.png" alt="Hassium Logo" width="200">
</p>

**Hassium** is a high-performance Minecraft optimization mod providing **efficient storage, network optimization, chunk caching, beyond-view rendering, and lighting optimization**. Covers Minecraft **1.20.1–1.21.11** on **Fabric / Forge / NeoForge**.

> Repo: [github.com/limuqy/Hassium](https://github.com/limuqy/Hassium) · [简体中文](Home)

![License](https://img.shields.io/badge/License-GPL--3.0-blue.svg)
![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1--1.21.11-green.svg)
![Loaders](https://img.shields.io/badge/Loaders-Fabric%20%7C%20Forge%20%7C%20NeoForge-orange.svg)
[![CurseForge](https://img.shields.io/badge/CurseForge-Hassium-644DF4.svg?logo=curseforge)](https://www.curseforge.com/minecraft/mc-mods/hassium)

---

## Core capabilities

| Category | Feature | Description |
| --- | --- | --- |
| **Efficient compression** | Storage compression | World chunk ZSTD on disk (type 126) for smaller saves; keeps vanilla Region (`.mca`) layout |
| | Network compression | More efficient compression for chunks and packets (custom channels + optional global pipeline + aggregation) — less bandwidth and wait time |
| **Network optimization** | Smooth push | Constant-rate server throttling (150 chunks/s token bucket) + per-tick serialization cap with background encoding; join and view expansion never saturate the main thread |
| | Control failover | On TCP master disconnect or stall, auto-reconnect through candidate endpoints; during recovery the world freezes on screen by default (optional seamless mode keeps the world running and rolls back after recovery) with the cache kept warm and the disconnect screen hidden (off by default) |
| | Weighted routing | Multiple UDP/KCP lines share chunk downloads by weight; control stays on vanilla TCP (off by default) |
| **Chunk caching** | Client chunk cache | Loaded chunks are kept locally; revisiting an area hits via contentHash comparison instead of full downloads |
| | Section delta | On cache mismatch (MISMATCH), fetch only changed sections (`sectionDelta`) and merge locally instead of the whole chunk |
| | **Beyond-view render** | When client RD exceeds server view distance (multiplayer), fill the outer ring from local cache (render-only; no out-of-range server requests); incompatible with Bobby |
| | World export | `/hassiumc export` writes the local cache as a vanilla Anvil singleplayer world |
| **Lighting optimization** | Light stripping | Server can omit light data; the client recomputes lighting locally to save more bandwidth |
| | Light cache | Light data is cached after first recompute; cache hits apply pre-computed lighting directly, skipping expensive recomputation |
| | Parallel light engine | Light recomputation runs on a background thread pool; the main thread only submits snapshots (on by default) |
| **Utilities** | Traffic metrics | `/hassium stats` (server) and `/hassiumc stats` (client) to inspect compression and cache results |

Feature details: [Features](Features-en).

---

## Quick start

1. Download the loader-specific JAR from [GitHub Releases](https://github.com/limuqy/Hassium/releases) or [CurseForge](https://www.curseforge.com/minecraft/mc-mods/hassium).
2. Drop it into `mods/` on client and/or server.
3. Launch the game; config files are generated under `config/hassium/`.
4. **Back up worlds before first enabling storage** (see [FAQ](FAQ-en)).

Details: [Installation](Installation-en).

---

## Docs

| Page | Content |
| --- | --- |
| [Installation](Installation-en) | Download, prerequisites, per-loader notes |
| [Configuration](Configuration-en) | Full config table and GUI paths |
| [Commands](Commands-en) | `/hassium` and `/hassiumc` reference |
| [Features](Features-en) | Feature deep-dive |
| [Beyond-View-Render](Beyond-View-Render-en) | Beyond-view render details |
| [World-Export](World-Export-en) | Cache world export |
| [Compatibility](Compatibility-en) | Multi-mod compatibility table |
| [Support-Matrix](Support-Matrix-en) | Version × loader matrix |
| [Data-Plane-and-Failover](Data-Plane-and-Failover-en) | Control failover and weighted routing (server ops) |
| [FAQ](FAQ-en) | Frequently asked questions |
| [Troubleshooting](Troubleshooting-en) | Debug paths and logs |

---

## Support matrix (summary)

| Minecraft | Fabric | Forge | NeoForge |
| --- | --- | --- | --- |
| 1.20.1 | ✅ | ✅ | ✅ |
| 1.20.2–1.20.5 | ✅ | — | ✅ |
| 1.20.6 | ✅ | ✅ | ✅ |
| 1.21.1 | ✅ | ✅ | ✅ |
| 1.21.2 | ✅ | — | ✅ |
| 1.21.3–1.21.10 | ✅ | ✅ | ✅ |
| 1.21.11 | ✅ | — | ✅ |

Full nine-segment table: [Support-Matrix](Support-Matrix-en).

---

## License

 [GPL-3.0-or-later](https://github.com/limuqy/Hassium/blob/master/LICENSE)
