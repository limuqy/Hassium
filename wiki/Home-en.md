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
| **Efficient compression** | Storage compression | Chunk ZSTD on disk (type 126), significantly smaller saves; keeps vanilla Region (`.mca`) layout |
| | Channel compression | Dictionary ZSTD inside aggregated packets + chunk-push native compression; never touches the vanilla compression layer, no cross-mod pipeline conflicts |
| **Network optimization** | Smooth push | Per-player per-tick Pull completion cap (`master.maxChunksPerTick`, degrades naturally on laggy ticks) + backgrounded encode/compress; joins never saturate the main thread |
| | Login-phase capability handshake | `hassium:login_hello` login query on 1.20.1, config-stage payload on 1.21.1+; bitwise capability negotiation with no timeout dependency and zero interference for vanilla clients |
| | Pull mode | After negotiation the server stops pushing full chunks; chunk data is fetched by the unified Compare+Pull driven by the client shadow virtual player's vanilla tracking |
| **Chunk cache** | Shadow-world saving | Join chunks are lit and saved into a vanilla save dir (`hassium_cache/<serverId>/world`) by an in-process shadow server (full MinecraftServer); saved on disconnect, reused on reconnect |
| | Section delta | On stale cache only changed blocks are sent; whole section next, whole chunk beyond that |
| | Capacity/heat eviction | `heat.idx` tracks heat per region file; over-capacity regions are deleted whole-file |
| | Local generation (SeedGen) | With both sides on the same version and the gate open, the server ships the world seed during Play activation (`play_init_s2c`); the client's shadow tracking runs vanilla worldgen locally for pristine chunks, authority-checked via compare-pull before delivery. **Server enablement sends the world seed (seed leak)** |
| | World export | `/hassiumc export` copies the shadow world into an export save (keeps type 126; vanilla translation pending) |
| **Beyond-view render** | OVD (shadow dual-window) | When the client RD exceeds the server view distance, the ring beyond it is backfilled from terrain the shadow server already has locally (injected/disk); **render-only, never simulated**, and never requested from the server; mutually exclusive with Bobby |
| **Lighting** | Hassium engine | On join an in-process shadow server takes over world saving (cache) + chunk lighting + packing official chunk packets (returned over the official channel); auto-degrades on startup failure |
| | Light stripping | The server may strip light to save bandwidth (`chunk.lightStrip`); the shadow server computes lighting and packs it back |
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
