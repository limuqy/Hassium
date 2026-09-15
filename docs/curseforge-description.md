<!--
  CurseForge 项目描述草稿
  - 顶部 Summary：中英并列
  - 正文：面向用户的双语概览；详细配置、命令与排障统一维护在 GitHub Wiki
  - Logo：引用 GitHub 上的 docs/archive/logo.svg；徽标为内嵌 SVG
  - 粘贴时：Summary 用「项目简介」框；其余用「Description」
-->

# Summary（项目简介，建议粘贴到 CurseForge Summary）

**EN:** **Hassium** is a high-performance Minecraft optimization mod providing **efficient compression, network optimization, chunk cache, local generation, and lighting optimization**. Covers Minecraft **1.20.1–1.21.11** on **Fabric / Forge / NeoForge**.

**中文：** **Hassium** 是 Minecraft 的高性能优化模组，提供**高效压缩、网络优化、区块缓存、本地生成与光照优化**。覆盖 Minecraft **1.20.1–1.21.11**，支持 **Fabric / Forge / NeoForge**。

---

# Description（完整描述，建议粘贴到 CurseForge Description）

<p align="center">
  <img src="https://raw.githubusercontent.com/limuqy/Hassium/refs/heads/master/docs/archive/logo.svg" alt="Hassium Logo" width="200">
</p>

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](https://raw.githubusercontent.com/limuqy/Hassium/refs/heads/master/LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1--1.21.11-green.svg)](https://www.minecraft.net/)
[![Loaders](https://img.shields.io/badge/Loaders-Fabric%20%7C%20Forge%20%7C%20NeoForge-orange.svg)](https://github.com/limuqy/Hassium/wiki/Support-Matrix)

**Hassium** is a high-performance Minecraft optimization mod providing **efficient compression, network optimization, chunk cache, local generation, and lighting optimization**. Covers Minecraft **1.20.1–1.21.11** on **Fabric / Forge / NeoForge**.

**Hassium** 是 Minecraft 的高性能优化模组，提供**高效压缩、网络优化、区块缓存、本地生成与光照优化**。覆盖 Minecraft **1.20.1–1.21.11**，支持 **Fabric / Forge / NeoForge**。

[GitHub Repository](https://github.com/limuqy/Hassium) · [Wiki 中文](https://github.com/limuqy/Hassium/wiki) · [Wiki English](https://github.com/limuqy/Hassium/wiki/Home-en)

---

## English

### Quick start

1. Download the JAR matching your **Minecraft version and loader** from the Files tab.
2. Put it in `mods/` on the client and/or server. Fabric additionally requires **Fabric API**; Forge and NeoForge need no extra dependency.
3. Install on both client and server for negotiated compression and cache hits. Vanilla clients can still join by default.
4. **Back up existing worlds before first enabling storage.** Storage compression rewrites on-disk chunk payloads.

Full instructions: [Installation](https://github.com/limuqy/Hassium/wiki/Installation-en) · [FAQ](https://github.com/limuqy/Hassium/wiki/FAQ-en)

### Features

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

Clients without the mod can connect by default (`compat.requireClientMod = false`); install on both sides for full compression and cache benefits.

### Supported versions

| Minecraft | Fabric | Forge | NeoForge |
| --- | --- | --- | --- |
| 1.20.1 | Yes | Yes | — |
| 1.21.1 | Yes | Yes | Yes |
| 1.21.2 | Yes | — | Yes |
| 1.21.3–1.21.10 | Yes | Yes | Yes |
| 1.21.11 | Yes | — | Yes |

Complete matrix: [Support Matrix](https://github.com/limuqy/Hassium/wiki/Support-Matrix-en)

### Documentation

| Need | Wiki page |
| --- | --- |
| Setup and dependencies | [Installation](https://github.com/limuqy/Hassium/wiki/Installation-en) |
| Every configuration option | [Configuration](https://github.com/limuqy/Hassium/wiki/Configuration-en) |
| Server and client commands | [Commands](https://github.com/limuqy/Hassium/wiki/Commands-en) |
| Cache, section delta, light optimization and more | [Features](https://github.com/limuqy/Hassium/wiki/Features-en) |
| Beyond-view render (OVD) and world export | [Beyond-view render](https://github.com/limuqy/Hassium/wiki/Beyond-View-Render-en) · [World export](https://github.com/limuqy/Hassium/wiki/World-Export-en) |
| Compatibility and diagnostics | [Compatibility](https://github.com/limuqy/Hassium/wiki/Compatibility-en) · [Troubleshooting](https://github.com/limuqy/Hassium/wiki/Troubleshooting-en) |
| Network architecture (direct topology) | [Network Architecture](https://github.com/limuqy/Hassium/wiki/Network-Architecture-en) |

## 简体中文

### 快速开始

1. 在 Files 页下载与你的**Minecraft 版本和加载器**匹配的 JAR。
2. 放入客户端或服务端的 `mods/`。Fabric 另需 **Fabric API**；Forge / NeoForge 无额外前置。
3. 推荐服务端与客户端都安装，以启用协商压缩和缓存命中；未装 Hassium 的客户端默认仍可连接。
4. **已有世界首次启用存储前务必备份。** 存储压缩会改写区块落盘 payload。

完整安装说明：[安装](https://github.com/limuqy/Hassium/wiki/Installation) · [常见问题](https://github.com/limuqy/Hassium/wiki/FAQ)

### 功能

| 分类 | 能力 | 说明 |
| --- | --- | --- |
| **高效压缩** | 存储压缩 | 世界区块 ZSTD 落盘，存档体积显著减小；仍兼容原版 Region（`.mca`）布局 |
| | 通道压缩 | 传输通道压缩，降低带宽与下载等待；不触碰原版压缩层，无跨 mod 冲突面 |
| **网络优化** | 平滑推送 | 区块按每 tick 上限限速下发，编码与压缩后台化；进服与扩展视野不卡主线程 |
| | 实体优化 | 按距离分档降频、热点密度降频、包量反压、错峰发送；只改下发节拍，原版客户端可直接连 |
| **区块缓存** | 世界保存 | 进服区块自动落盘本地缓存，断连保存、重连复用，少传全量区块 |
| | 分段增量 | 缓存过期时只补变更方块或整段，避免整块重传 |
| | 本地生成 | 双端同版本时，大片未探索地形由本地生成，节省带宽；**服务端开启会向客户端下发世界种子** |
| | 超视渲染 | 客户端渲染距离大于服务端视距时，用本地已有地形回填视距外环带；**仅渲染，不向服务端请求**；与 Bobby 互斥 |
| | 热度淘汰 | 缓存超容量时按 region 热度自动清理旧区块 |
| | 世界导出 | `/hassiumc export` 将本地缓存导出为独立存档目录 |
| **光照优化** | 统一算光 | 客户端进程内统一计算区块光照并打包回传，加载阶段主线程不再被算光占用；启动失败自动降级 |
| | 光照剥离 | 服务端可剥离光照数据省流量，由客户端统一计算后写回 |
| | 光照缓存 | 算好的光照随区块一体落盘，重连复用，跳过重算 |
| **实用工具** | 流量监控 | `/hassium stats`（服务端）、`/hassiumc stats`（客户端）查看压缩与缓存效果 |

未安装本模组的客户端默认可连接（`compat.requireClientMod = false`）；双端都装才能吃满压缩与缓存。

### 支持版本

| Minecraft | Fabric | Forge | NeoForge |
| --- | --- | --- | --- |
| 1.20.1 | ✅ | ✅ | — |
| 1.21.1 | ✅ | ✅ | ✅ |
| 1.21.2 | ✅ | — | ✅ |
| 1.21.3–1.21.10 | ✅ | ✅ | ✅ |
| 1.21.11 | ✅ | — | ✅ |

完整矩阵见：[支持矩阵](https://github.com/limuqy/Hassium/wiki/Support-Matrix)

### 文档导航

| 需要了解的内容 | Wiki 页面 |
| --- | --- |
| 安装和前置依赖 | [安装](https://github.com/limuqy/Hassium/wiki/Installation) |
| 全部配置项 | [配置](https://github.com/limuqy/Hassium/wiki/Configuration) |
| 服务端和客户端命令 | [命令](https://github.com/limuqy/Hassium/wiki/Commands) |
| 缓存、分段增量、光照优化等 | [特性](https://github.com/limuqy/Hassium/wiki/Features) |
| 超视渲染（OVD）与缓存世界导出 | [超视渲染](https://github.com/limuqy/Hassium/wiki/Beyond-View-Render) · [世界导出](https://github.com/limuqy/Hassium/wiki/World-Export) |
| 兼容性与故障排查 | [兼容性](https://github.com/limuqy/Hassium/wiki/Compatibility) · [排查](https://github.com/limuqy/Hassium/wiki/Troubleshooting) |
| 网络架构（直连拓扑） | [网络架构](https://github.com/limuqy/Hassium/wiki/Network-Architecture) |

许可证：[GPL-3.0-or-later](https://github.com/limuqy/Hassium/blob/master/LICENSE)
