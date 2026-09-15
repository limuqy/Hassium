# Hassium

<p align="center">
  <img src="https://raw.githubusercontent.com/limuqy/Hassium/master/common/src/main/resources/assets/hassium/logo.png" alt="Hassium Logo" width="200">
</p>

**Hassium** is a high-performance optimization mod for Minecraft, providing **efficient compression, network optimization, chunk cache, beyond-view render, local generation, and lighting optimization**. Covers Minecraft **1.20.1–1.21.11** on **Fabric / Forge / NeoForge**.

> Repository: [github.com/limuqy/Hassium](https://github.com/limuqy/Hassium) · [简体中文](Home)

![License](https://img.shields.io/badge/License-GPL--3.0-blue.svg)
![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1--1.21.11-green.svg)
![Loaders](https://img.shields.io/badge/Loaders-Fabric%20%7C%20Forge%20%7C%20NeoForge-orange.svg)
[![CurseForge](https://img.shields.io/badge/CurseForge-Hassium-644DF4.svg?logo=curseforge)](https://www.curseforge.com/minecraft/mc-mods/hassium)

---

## Core capabilities

| Category | Feature | Description |
| --- | --- | --- |
| **Efficient compression** | Storage compression | World chunks are ZSTD-compressed on disk for significantly smaller saves; keeps the vanilla Region (`.mca`) layout |
| | Channel compression | On-wire compression lowers bandwidth and download waits; never touches the vanilla compression layer, no cross-mod conflicts |
| **Network optimization** | Smooth push | Chunks are rate-limited per tick and encode/compress work is offloaded; joins and view expansion never stall the main thread |
| | Entity optimization | Distance-tiered rates, hotspot density throttle, packet-budget backpressure, and phase stagger; replication-only — vanilla clients can join |
| **Chunk cache** | World save | Chunks you visit are saved to a local cache automatically; saved on disconnect, reused on reconnect — no full re-download |
| | Section delta | On stale cache only changed blocks or whole sections are fetched instead of the whole chunk |
| | Local generation | With both sides on the same version, unexplored terrain is generated locally to save bandwidth; **enabling on the server sends the world seed to clients** |
| | Beyond-view render | When client render distance exceeds server view distance, the outer ring is backfilled from locally cached terrain; **render-only, never requested from the server**; mutually exclusive with Bobby |
| | Heat eviction | Over-capacity caches are cleaned by region heat automatically |
| | World export | `/hassiumc export` copies the local cache into a standalone save directory |
| **Lighting optimization** | Unified lighting | An in-process engine computes chunk lighting and packs it back; the main thread is no longer occupied by lighting on load; auto-degrades on startup failure |
| | Light stripping | The server may strip light data to save bandwidth; the client computes and writes it back |
| | Light cache | Computed lighting is saved with the chunk and reused on reconnect, skipping recomputation |
| **Utilities** | Traffic monitoring | `/hassium stats` (server) and `/hassiumc stats` (client) show compression and cache effectiveness |

Feature details: [Features](Features-en).

---

## Quick start

1. Download the JAR for your loader from [GitHub Releases](https://github.com/limuqy/Hassium/releases) or [CurseForge](https://www.curseforge.com/minecraft/mc-mods/hassium).
2. Drop it into the client/server `mods/` directory.
3. Launch the game; config files are generated under `config/hassium/`.
4. **Back up your world before enabling storage** (see [FAQ](FAQ-en)).

Installation and prerequisites: [Installation](Installation-en).

---

## Documentation

| Page | Content |
| --- | --- |
| [Installation](Installation-en) | Download, prerequisites, loader differences |
| [Configuration](Configuration-en) | Full key reference and GUI paths |
| [Commands](Commands-en) | `/hassium` and `/hassiumc` reference |
| [Features](Features-en) | Feature details |
| [Beyond-View-Render](Beyond-View-Render-en) | Beyond-view render (OVD) |
| [World-Export](World-Export-en) | Cache world export |
| [Compatibility](Compatibility-en) | Multi-mod compatibility |
| [Support-Matrix](Support-Matrix-en) | Version × loader matrix |
| [Network Architecture](Network-Architecture-en) | Direct-connection topology |
| [FAQ](FAQ-en) | Common questions |
| [Troubleshooting](Troubleshooting-en) | Diagnostics and logs |

---

## Support matrix (summary)

| Minecraft | Fabric | Forge | NeoForge |
| --- | --- | --- | --- |
| 1.20.1 | ✅ | ✅ | — |
| 1.21.1 | ✅ | ✅ | ✅ |
| 1.21.2 | ✅ | — | ✅ |
| 1.21.3–1.21.10 | ✅ | ✅ | ✅ |
| 1.21.11 | ✅ | — | ✅ |

Forge is available on 1.20.1 / 1.21.1 / 1.21.3–1.21.10 (1.21.2 skipped upstream; sunset from 1.21.11 — use NeoForge). **No separate NeoForge build for 1.20.1**: NeoForge 47.x natively loads Forge mods, so NeoForge users install the Forge build.

Full segment details: [Support-Matrix](Support-Matrix-en).

---

## License

 [GPL-3.0-or-later](https://github.com/limuqy/Hassium/blob/master/LICENSE)
