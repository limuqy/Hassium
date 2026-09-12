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
| **Efficient compression** | Storage compression | World chunk ZSTD on disk (type 126) for smaller saves; keeps vanilla Region (`.mca`) layout |
| | Channel compression | Dictionary ZSTD inside aggregated packets + chunk-push native compression — never touches the vanilla compression layer, no cross-mod pipeline conflicts |
| **Network optimization** | Smooth push | Per-player per-tick submit cap (`master.maxChunksPerTick`, ≈ cap×20/s at full tick) + fully backgrounded encode/compress/send; joins never saturate the main thread |
| | Login-phase capability handshake | `hassium:login_hello` login query on 1.20.1, config-stage payload on 1.21.1+; bitwise capability negotiation with no timeout dependency and zero interference for vanilla clients |
| | Pull mode | After negotiation the server stops pushing full chunks; chunk data is fetched by the unified Compare+Pull driven by the client shadow virtual player's vanilla tracking (`ShadowPull`: UNCHANGED / DELTA / FULL / ERROR) |
| **Chunk cache** | Shadow world save | Every chunk you visit is saved by the shadow engine (full MinecraftServer) into a vanilla-format save (`hassium_cache/<serverId>/world`, type 126 + chunkHash); saved on disconnect, reused on reconnect |
| | Section delta | On cache mismatch (MISMATCH), fetch only changed blocks (`BLOCKS`) or whole sections (`FULL`) and merge locally instead of the whole chunk |
| | Local generation (SeedGen) | With both sides on the same version, the server ships the world seed during Play activation; the client's shadow tracking runs vanilla worldgen locally for pristine chunks, authority-checked via compare-pull before delivery. **Enabling this leaks the server world seed to clients.** Falls back to full transfer on failure |
| | Beyond-view render (OVD) | Shadow dual-window: when the client RD exceeds the server view distance, the ring beyond it is backfilled from terrain the shadow server already has locally — render-only, never requested from the server |
| | Capacity/heat eviction | `heat.idx` tracks heat per region file; over-capacity regions are deleted whole-file |
| | World export | `/hassiumc export` copies the shadow-side world directory wholesale to `hassium_exports/<cacheId>` (keeps the type 126 + chunkHash format; vanilla translation is planned later) |
| **Lighting optimization** | Hassium engine | On join an in-process shadow server (full MinecraftServer) takes over world saving (cache) + chunk lighting + official chunk packet packing, returned over the official vanilla channel; auto-degrades on startup failure |
| | Light stripping | The server may strip light data (negotiated at handshake); the shadow side computes lighting centrally and packs it back |
| | Light cache | Shadow-side lighting is saved with the chunk (type 126 + chunkHash); reconnects reuse it, skipping recomputation |
| **Utilities** | Traffic metrics | `/hassium stats` (server) and `/hassiumc stats` (client) to inspect compression and cache results |

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
| **高效压缩** | 存储压缩 | 世界区块 ZSTD 落盘（type 126），存档体积显著减小；仍兼容原版 Region（`.mca`）布局 |
| | 通道压缩 | 聚合包内部字典 ZSTD + 区块推送自有压缩，降低带宽与下载等待；不触碰原版压缩层，无跨 mod 管线冲突面 |
| **网络优化** | 平滑推送 | 服务端每 tick 提交上限限速（`master.maxChunksPerTick`，满 tick ≈ 值×20/s）+ 主线程序列化上限与后台化；进服/扩展视野不卡主线程 |
| | 登录期能力握手 | 1.20.1 走 `hassium:login_hello` login query，1.21.1+ 走配置阶段 payload；按位与协商能力位，无超时依赖，原版客户端零干扰 |
| | Pull 模式 | 协商通过后服务端停发整柱推送，区块数据由客户端影子虚拟玩家 tracking 驱动的统一 Compare+Pull 拉取（UNCHANGED / DELTA / FULL / ERROR） |
| **区块缓存** | 影子端世界保存 | 进服区块统一由影子端（完整 MinecraftServer）落盘原版存档（`hassium_cache/<serverId>/world`），断连保存、重连复用 |
| | 分段增量 | 缓存过期（MISMATCH）时仅拉取变更方块（`BLOCKS`）或整段（`FULL`）本地合并，避免整块重传 |
| | 本地生成（SeedGen） | 双端同版本时，服务端在 Play 激活下发世界种子；客户端影子端 tracking 触发原版 worldgen 本地生成 pristine 区块，生成后经服务端权威 compare-pull 校验交付；**开启会向客户端下发并泄露服务端世界种子**；失败/校验不过自动回退全量 |
| | 超视渲染（OVD） | 影子双窗：客户端 RD 大于服务端视距时，视距外环带由影子端本地已有地形回填——**仅渲染**，不向服务端请求 |
| | 容量/热度淘汰 | `heat.idx` 按 region 文件计热度，超限整文件删除 `.mca` |
| | 世界导出 | `/hassiumc export` 将影子端世界目录整体拷贝为导出存档（`hassium_exports/<cacheId>`；保留 type 126 + chunkHash，原版翻译后续提供） |
| **光照优化** | Hassium 引擎 | 进服启动进程内影子服务端（完整 MinecraftServer）统一承担世界保存（缓存）+ 区块光照计算 + 打包官方区块包（官方通道回传），客户端不再计算；启动失败自动降级 |
| | 光照剥离 | 服务端可剥光省流量（握手协商），由影子端统一计算光照并打包回传 |
| | 光照缓存 | 影子端算光随区块一体落盘（type 126 + chunkHash），重连复用，跳过重算 |
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
