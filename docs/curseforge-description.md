<!--
  CurseForge 项目描述草稿
  - 顶部 Summary：中英并列
  - 正文：面向用户的双语概览；详细配置、命令与排障统一维护在 GitHub Wiki
  - Logo：引用 GitHub 上的 docs/archive/logo.svg；徽标为内嵌 SVG
  - 粘贴时：Summary 用「项目简介」框；其余用「Description」
-->

# Summary（项目简介，建议粘贴到 CurseForge Summary）

**EN:** **Hassium** is a high-performance Minecraft optimization mod providing **efficient compression, network optimization, chunk cache, local generation, beyond-view rendering, and lighting optimization**. Covers Minecraft **1.20.1–1.21.11** on **Fabric / Forge / NeoForge**.

**中文：** **Hassium** 是 Minecraft 的高性能优化模组，提供**高效压缩、网络优化、区块缓存、本地生成、超视渲染与光照优化**。覆盖 Minecraft **1.20.1–1.21.11**，支持 **Fabric / Forge / NeoForge**。

---

# Description（完整描述，建议粘贴到 CurseForge Description）

<p align="center">
  <img src="https://raw.githubusercontent.com/limuqy/Hassium/refs/heads/master/docs/archive/logo.svg" alt="Hassium Logo" width="200">
</p>

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](https://raw.githubusercontent.com/limuqy/Hassium/refs/heads/master/LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1--1.21.11-green.svg)](https://www.minecraft.net/)
[![Loaders](https://img.shields.io/badge/Loaders-Fabric%20%7C%20Forge%20%7C%20NeoForge-orange.svg)](https://github.com/limuqy/Hassium/wiki/Support-Matrix)

**Hassium** is a high-performance Minecraft optimization mod providing **efficient compression, network optimization, chunk cache, local generation, beyond-view rendering, and lighting optimization**. Covers Minecraft **1.20.1–1.21.11** on **Fabric / Forge / NeoForge**.

**Hassium** 是 Minecraft 的高性能优化模组，提供**高效压缩、网络优化、区块缓存、本地生成、超视渲染与光照优化**。覆盖 Minecraft **1.20.1–1.21.11**，支持 **Fabric / Forge / NeoForge**。

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
| **Efficient compression** | Storage compression | World chunk ZSTD on disk (type 126) for smaller saves; keeps vanilla Region (`.mca`) layout |
| | Network compression | More efficient compression for chunks and packets (custom channels + global pipeline + packet aggregation) — less bandwidth and wait time |
| **Network optimization** | Smooth push | Per-player per-tick submit cap (`master.maxChunksPerTick`, ≈ cap×20/s at full tick) + main-thread serialization cap with background encoding; join and view expansion never saturate the main thread |
| | In-process gateway | Client-side in-process gateway (Network Core): vanilla client ↔ Network Core ↔ Master Core private channel; PLAY-phase traffic is routed through the gateway, the shell connection stays keep-alive only |
| | Seamless migration / L1 load balancing | On master inbound silence timeout (default `master.migrationSilentTimeoutMs`=10000), the L1 migration engine switches the gateway with a warm cache — seamless migration; multiple lines balanced via L1 |
| | UDP data plane | UDP/KCP bulk carrier for the gateway ↔ master-core channel (`dataplane.enabled`, off by default; control plane stays on vanilla TCP) |
| **Chunk cache** | Shadow world save | Every chunk you visit is saved by the shadow engine (full MinecraftServer) into a vanilla-format save (`hassium_cache/<serverId>/world`, type 126 + chunkHash); saved on disconnect, reused on reconnect |
| | Section delta | On cache mismatch (MISMATCH), fetch only changed sections (`sectionDelta`) and merge locally instead of the whole chunk |
| | Local generation (SeedGen) | For pristine (never-generated) chunks the server sends a tiny seed + position reference instead of chunk data; the client generates locally with the same seed — zero transfer. Falls back to full transfer on failure/timeout |
| | **Beyond-view render** | When client RD exceeds server view distance (multiplayer), fill the outer ring from local cache (render-only; no out-of-range server requests); incompatible with Bobby |
| | World export | `/hassiumc export` copies the shadow-side world directory wholesale to `hassium_exports/<cacheId>` (keeps the type 126 + chunkHash format; vanilla translation is planned later) |
| **Lighting optimization** | Hassium engine | Master switch for non-network features (default on): an in-process shadow server (full MinecraftServer) owns world saving (cache) + chunk lighting + official chunk packet packing, delivered back over the vanilla channel; degrades automatically on startup failure |
| | Light stripping | Server can strip light data; the Hassium engine (shadow side) computes lighting centrally and packs it back |
| | Light cache | Shadow-side lighting is saved with the chunk (type 126 + chunkHash); reconnects reuse it, skipping recomputation |
| | Parallel light engine | Light recomputation runs on a background thread pool; the main thread only submits snapshots (on by default) |
| **Utilities** | Traffic metrics | `/hassium stats` (server) and `/hassiumc stats` (client) to inspect compression and cache results |

Clients without the mod can connect by default (`compat.requireClientMod = false`); install on both sides for full compression and cache benefits.

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
| Network core and master migration | [Network Core and Master Migration](https://github.com/limuqy/Hassium/wiki/Network-Core-and-Master-Migration-en) |

---

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
| **高效压缩** | 存储压缩 | 世界区块 ZSTD 落盘（type 126），存档体积显著减小；仍兼容原版 Region（`.mca`）布局 |
| | 网络压缩 | 区块与数据包 ZSTD 传输（自定义通道 + 全局管道 + 包聚合），降低带宽与下载等待 |
| **网络优化** | 平滑推送 | 服务端每 tick 提交上限限速（`master.maxChunksPerTick`，满 tick ≈ 值×20/s）+ 主线程序列化上限与后台化；进服/扩展视野不卡主线程 |
| | 进程内网关 | 客户端进程内网关（网络核心）：原版客户端 ↔ 网络核心 ↔ 主控核心自有通道；PLAY 期数据经网关路由，壳连接仅保活 |
| | 无感迁移 / L1 负载均衡 | 主控入站静默超时（默认 `master.migrationSilentTimeoutMs`=10000）后由 L1 迁移引擎切换网关、缓存暖续，无感迁移；多线路按 L1 负载均衡 |
| | UDP 数据面 | 网关↔主控通道的 UDP/KCP bulk 载体（`dataplane.enabled`，默认关；控制面留原版 TCP） |
| **区块缓存** | 影子端世界保存 | 进服区块统一由影子端（完整 MinecraftServer）落盘原版存档（`hassium_cache/<serverId>/world`），断连保存、重连复用 |
| | 分段增量 | 缓存过期（MISMATCH）时仅拉取变更分段（`sectionDelta`）本地合并，避免整块重传 |
| | 本地生成（SeedGen） | 服务端对 pristine 区块只发 seed + 坐标引用，客户端用同种子本地生成，零传输生成区块；失败/超时自动回退全量 |
| | **超视渲染** | 多人服客户端 RD 大于服务端视距时，用本地缓存回填视距外地形（仅渲染、不向服索要视距外区块）；与 Bobby 互斥 |
| | 世界导出 | `/hassiumc export` 将影子端世界目录整体拷贝为导出存档（`hassium_exports/<cacheId>`；保留 type 126 + chunkHash，原版翻译后续提供） |
| **光照优化** | Hassium 引擎 | 非网络向功能总开关（默认开）：进服启动进程内影子服务端（完整 MinecraftServer）统一承担世界保存（缓存）+ 区块光照计算 + 打包官方区块包（官方通道回传），客户端不再计算；启动失败自动降级 |
| | 光照剥离 | 服务端可剥光省流量，由 Hassium 引擎（影子端）统一计算光照并打包回传 |
| | 光照缓存 | 影子端算光随区块一体落盘（type 126 + chunkHash），重连复用，跳过重算 |
| | 并行光照 | 可选：安装 Promethium MOD 后开启，光照重算在后台线程池并行执行；默认官方引擎（统一异步缓冲队列，帧尾预算消费，不阻塞主线程） |
| **实用工具** | 流量监控 | `/hassium stats`（服务端）、`/hassiumc stats`（客户端）查看压缩与缓存效果 |

未安装本模组的客户端默认可连接（`compat.requireClientMod = false`）；双端都装才能吃满压缩与缓存。

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
| 网络核心与主控迁移 | [网络核心与主控迁移](https://github.com/limuqy/Hassium/wiki/Network-Core-and-Master-Migration) |

许可证：[GPL-3.0-or-later](https://github.com/limuqy/Hassium/blob/master/LICENSE)
