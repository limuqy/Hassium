<!--
  CurseForge 项目描述草稿
  - 顶部 Summary：中英并列
  - 正文：面向用户的双语概览；详细配置、命令与排障统一维护在 GitHub Wiki
  - Logo：引用 GitHub 上的 docs/logo.svg；徽标为内嵌 SVG
  - 粘贴时：Summary 用「项目简介」框；其余用「Description」
-->

# Summary（项目简介，建议粘贴到 CurseForge Summary）

**EN:** **Hassium** is a high-performance Minecraft optimization mod providing **efficient storage, network optimization, chunk caching, beyond-view rendering, and lighting optimization**. Covers Minecraft **1.20.1–1.21.11** on **Fabric / Forge / NeoForge**.

**中文：** **Hassium** 是 Minecraft 的高性能优化模组，提供**高效存储、网络优化、区块缓存、超视渲染与光照优化**。覆盖 Minecraft **1.20.1–1.21.11**，支持 **Fabric / Forge / NeoForge**。

---

# Description（完整描述，建议粘贴到 CurseForge Description）

<p align="center">
  <img src="https://raw.githubusercontent.com/limuqy/Hassium/refs/heads/master/docs/logo.svg" alt="Hassium Logo" width="200">
</p>

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](https://raw.githubusercontent.com/limuqy/Hassium/refs/heads/master/LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1--1.21.11-green.svg)](https://www.minecraft.net/)
[![Loaders](https://img.shields.io/badge/Loaders-Fabric%20%7C%20Forge%20%7C%20NeoForge-orange.svg)](https://github.com/limuqy/Hassium/wiki/Support-Matrix)

**Hassium** is a high-performance Minecraft optimization mod providing **efficient storage, network optimization, chunk caching, beyond-view rendering, and lighting optimization**. Covers Minecraft **1.20.1–1.21.11** on **Fabric / Forge / NeoForge**.

**Hassium** 是 Minecraft 的高性能优化模组，提供**高效存储、网络优化、区块缓存、超视渲染与光照优化**。覆盖 Minecraft **1.20.1–1.21.11**，支持 **Fabric / Forge / NeoForge**。

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

| Feature | Description |
| --- | --- |
| **Efficient storage** | Higher-ratio world chunk compression for smaller saves; keeps vanilla Region (`.mca`) layout |
| **Network compression** | More efficient compression for chunks and packets — less bandwidth and wait time |
| **Chunk cache** | Loaded chunks are kept locally; revisiting an area prefers the cache instead of full downloads |
| **Section delta** | On cache mismatch, fetch only changed sections (`sectionDelta`) instead of the whole chunk |
| **Beyond-view render** | When client RD exceeds server view distance (multiplayer), fill the outer ring from local cache (render-only; no out-of-range server requests) |
| **World export** | `/hassiumc export` writes the local cache as a vanilla Anvil singleplayer world |
| **Light stripping** | Server can omit light data; the client recomputes lighting locally to save more bandwidth |
| **Light cache** | Light data is cached after first recompute; cache hits apply pre-computed lighting directly, skipping expensive recomputation |
| **Control failover** | If the TCP control connection stalls or drops, reconnect automatically through candidate endpoints; the cache stays warm and the disconnect screen is hidden (data-plane failover) |
| **Weighted distribution** | Multiple UDP/KCP endpoints carry the data plane with weight-based round-robin; the control plane stays on vanilla TCP |
| **Smooth loading** | Caps main-thread work during join and view expansion to reduce hitch spikes |
| **Client-friendly** | Clients without the mod can connect by default; install on both sides for full compression and cache benefits |
| **Traffic metrics** | `/hassium stats` (server) and `/hassiumc stats` (client) to inspect compression and cache results |

### Supported versions

| Minecraft | Fabric | Forge | NeoForge |
| --- | --- | --- | --- |
| 1.20.1 | Yes | Yes | Yes |
| 1.20.2–1.20.5 | Yes | — | Yes |
| 1.20.6 | Yes | Yes | Yes |
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
| Beyond-view render and world export | [Beyond-view render](https://github.com/limuqy/Hassium/wiki/Beyond-View-Render-en) · [World export](https://github.com/limuqy/Hassium/wiki/World-Export-en) |
| Compatibility and diagnostics | [Compatibility](https://github.com/limuqy/Hassium/wiki/Compatibility-en) · [Troubleshooting](https://github.com/limuqy/Hassium/wiki/Troubleshooting-en) |
| UDP data plane and control failover | [Data Plane and Failover](https://github.com/limuqy/Hassium/wiki/Data-Plane-and-Failover-en) |

---

## 简体中文

### 快速开始

1. 在 Files 页下载与你的**Minecraft 版本和加载器**匹配的 JAR。
2. 放入客户端或服务端的 `mods/`。Fabric 另需 **Fabric API**；Forge / NeoForge 无额外前置。
3. 推荐服务端与客户端都安装，以启用协商压缩和缓存命中；未装 Hassium 的客户端默认仍可连接。
4. **已有世界首次启用存储前务必备份。** 存储压缩会改写区块落盘 payload。

完整安装说明：[安装](https://github.com/limuqy/Hassium/wiki/Installation) · [常见问题](https://github.com/limuqy/Hassium/wiki/FAQ)

### 功能

| 能力 | 说明                                                    |
| --- |-------------------------------------------------------|
| **高效存储** | 世界区块用更高压缩率落盘，显著减小存档体积；仍兼容原版 Region（`.mca`）布局          |
| **网络压缩** | 区块与数据包用更高效压缩传输，降低带宽占用与下载等待                            |
| **区块缓存** | 曾加载过的区块写入本地；再次进入同一区域时优先用本地数据，少传全量包                    |
| **分段增量** | 缓存过期时仅拉取变更分段（`sectionDelta`），避免整块重传                   |
| **超视渲染** | 多人服客户端 RD 大于服务端视距时，用本地缓存回填视距外地形（仅渲染、不向服索要视距外区块）       |
| **世界导出** | `/hassiumc export` 将本地缓存导出为可进单机的原版 Anvil 世界     |
| **光照剥离** | 服务端可不传光照数据，由客户端本地重算，进一步省流量 |
| **光照缓存** | 首次加载重算后缓存光照数据，后续缓存命中直接应用，跳过同步重算 |
| **主控热切** | TCP 主控断或卡时按候选自动重连，缓存暖续、隐藏断连界面（数据面 failover） |
| **加权分流** | 多 UDP/KCP endpoint 按 weight 加权轮询承载数据面，控制面留原版 TCP |
| **平滑加载** | 进服与视野扩展时限制主线程压力，减少卡顿尖峰                                |
| **兼容友好** | 未安装本模组的客户端默认可连接；双端都装才能吃满压缩与缓存                         |
| **流量监控** | `/hassium stats`（服务端）、`/hassiumc stats`（客户端）查看压缩与缓存效果 |

### 支持版本

| Minecraft | Fabric | Forge | NeoForge |
| --- | --- | --- | --- |
| 1.20.1 | ✅ | ✅ | ✅ |
| 1.20.2–1.20.5 | ✅ | — | ✅ |
| 1.20.6 | ✅ | ✅ | ✅ |
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
| 超视渲染与缓存世界导出 | [超视渲染](https://github.com/limuqy/Hassium/wiki/Beyond-View-Render) · [世界导出](https://github.com/limuqy/Hassium/wiki/World-Export) |
| 兼容性与故障排查 | [兼容性](https://github.com/limuqy/Hassium/wiki/Compatibility) · [排查](https://github.com/limuqy/Hassium/wiki/Troubleshooting) |
| UDP 数据面与主控 Failover | [数据面与主控 Failover](https://github.com/limuqy/Hassium/wiki/Data-Plane-and-Failover) |

许可证：[GPL-3.0-or-later](https://github.com/limuqy/Hassium/blob/master/LICENSE)
