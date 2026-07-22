<!--
  CurseForge 项目描述草稿
  - 顶部 Summary：中英并列
  - 正文：完整英文段 → 完整中文段
  - Logo：引用 GitHub 上的 docs/logo.svg；徽标为内嵌 SVG
  - 不含安装步骤 / 源码构建（由 CF 文件页与 README 承担）
  粘贴时：Summary 用「项目简介」框；正文用「Description」
-->

# Summary（项目简介，建议粘贴到 CurseForge Summary）

**EN:** High-performance chunk compression & client cache for Minecraft. Smaller saves, less bandwidth, smoother joins. Fabric / Forge / NeoForge · 1.20.1–1.21.11.

**中文：** 高性能区块压缩与客户端缓存模组。缩小存档与带宽、复用本地区块、减轻进服卡顿。支持 Fabric / Forge / NeoForge，覆盖 Minecraft 1.20.1–1.21.11。

---

# Description（完整描述，建议粘贴到 CurseForge Description）

<p align="center">
  <img src="https://raw.githubusercontent.com/limuqy/Hassium/refs/heads/master/docs/logo.svg" alt="Hassium Logo" width="200">
</p>

<p align="center">
<svg xmlns="http://www.w3.org/2000/svg" width="118" height="20" role="img" aria-label="License: GPL-3.0">
  <title>License: GPL-3.0</title>
  <linearGradient id="hsBadgeA" x2="0" y2="100%"><stop offset="0" stop-color="#bbb" stop-opacity=".1"/><stop offset="1" stop-opacity=".1"/></linearGradient>
  <clipPath id="hsBadgeClipA"><rect width="118" height="20" rx="3" fill="#fff"/></clipPath>
  <g clip-path="url(#hsBadgeClipA)">
    <rect width="55" height="20" fill="#555"/>
    <rect x="55" width="63" height="20" fill="#007ec6"/>
    <rect width="118" height="20" fill="url(#hsBadgeA)"/>
  </g>
  <g fill="#fff" text-anchor="middle" font-family="Verdana,Geneva,DejaVu Sans,sans-serif" font-size="11">
    <text x="27.5" y="14">License</text>
    <text x="86.5" y="14">GPL-3.0</text>
  </g>
</svg>
&#160;
<svg xmlns="http://www.w3.org/2000/svg" width="168" height="20" role="img" aria-label="Minecraft 1.20.1-1.21.11">
  <title>Minecraft 1.20.1–1.21.11</title>
  <linearGradient id="hsBadgeB" x2="0" y2="100%"><stop offset="0" stop-color="#bbb" stop-opacity=".1"/><stop offset="1" stop-opacity=".1"/></linearGradient>
  <clipPath id="hsBadgeClipB"><rect width="168" height="20" rx="3" fill="#fff"/></clipPath>
  <g clip-path="url(#hsBadgeClipB)">
    <rect width="69" height="20" fill="#555"/>
    <rect x="69" width="99" height="20" fill="#4c1"/>
    <rect width="168" height="20" fill="url(#hsBadgeB)"/>
  </g>
  <g fill="#fff" text-anchor="middle" font-family="Verdana,Geneva,DejaVu Sans,sans-serif" font-size="11">
    <text x="34.5" y="14">Minecraft</text>
    <text x="118.5" y="14">1.20.1–1.21.11</text>
  </g>
</svg>
&#160;
<svg xmlns="http://www.w3.org/2000/svg" width="214" height="20" role="img" aria-label="Loaders: Fabric | Forge | NeoForge">
  <title>Loaders: Fabric | Forge | NeoForge</title>
  <linearGradient id="hsBadgeC" x2="0" y2="100%"><stop offset="0" stop-color="#bbb" stop-opacity=".1"/><stop offset="1" stop-opacity=".1"/></linearGradient>
  <clipPath id="hsBadgeClipC"><rect width="214" height="20" rx="3" fill="#fff"/></clipPath>
  <g clip-path="url(#hsBadgeClipC)">
    <rect width="55" height="20" fill="#555"/>
    <rect x="55" width="159" height="20" fill="#fe7d37"/>
    <rect width="214" height="20" fill="url(#hsBadgeC)"/>
  </g>
  <g fill="#fff" text-anchor="middle" font-family="Verdana,Geneva,DejaVu Sans,sans-serif" font-size="11">
    <text x="27.5" y="14">Loaders</text>
    <text x="134.5" y="14">Fabric | Forge | NeoForge</text>
  </g>
</svg>
</p>

**Hassium** — high-performance chunk compression and client-side caching for Minecraft.  
Smaller world saves and bandwidth than vanilla, local chunk reuse, and smoother joins. Supports Fabric / Forge / NeoForge across Minecraft 1.20.1–1.21.11.

**Hassium** · 高性能区块压缩与客户端缓存模组  
相对原版缩小存档与带宽、复用本地区块、减轻进服卡顿。支持 Fabric / Forge / NeoForge，覆盖 Minecraft 1.20.1–1.21.11。

Repository: [github.com/limuqy/Hassium](https://github.com/limuqy/Hassium)

---

## English

### What is Hassium?

Hassium replaces vanilla Zlib with **ZSTD** for three things that matter on multiplayer servers and large worlds:

1. **World save compression** — chunks on disk stay in the familiar Anvil / Region (`.mca`) layout, but the payload uses a higher compression ratio (type `126`).
2. **Network compression** — custom `hassium:*` channels plus optional **global packet compression** so chunk traffic and general packets use less bandwidth.
3. **Client chunk cache** — chunks you already loaded are kept locally; when you return to the same area, the client compares a content hash (`chunkHash`) and can skip a full download if nothing changed.

Vanilla clients can still connect by default. Install Hassium on **both** client and server to get negotiated compression and cache hits.

### Features

| Feature | What it does |
| --- | --- |
| **Efficient storage** | Higher-ratio world chunk compression for smaller saves; keeps vanilla Region (`.mca`) layout |
| **Network compression** | ZSTD for chunks and (optionally) the whole packet pipeline — less bandwidth and wait time |
| **Chunk cache** | Loaded chunks are kept locally; revisiting an area prefers the cache instead of full downloads |
| **Section delta** | On cache mismatch, pull only changed sections instead of the whole chunk |
| **Beyond-view render** | When client render distance exceeds server view distance (multiplayer), fill the outer ring from local history (render-only; no out-of-range server requests) |
| **World export** | `/hassiumc cache export` turns the local cache into a vanilla Anvil singleplayer world under `saves/` |
| **Light cache** | Light data is cached locally after first compute; cache hits apply pre-computed lighting directly |
| **Smooth loading** | Caps main-thread work during join and view expansion to reduce hitch spikes |
| **Client-friendly** | Clients without the mod can connect by default; both sides needed for full benefits |
| **Traffic metrics** | `/hassium stats` (server) and `/hassiumc stats` (client) to inspect compression and cache results |

### Support matrix

| Minecraft | Fabric | Forge | NeoForge |
| --- | --- | --- | --- |
| 1.20.1 | Yes | Yes | Yes |
| 1.20.2–1.20.4 | Yes | — | Yes |
| 1.20.5–1.20.6 | Yes | Yes (1.20.6 only) | Yes |
| 1.21.1–1.21.11 | Yes | — | Yes |

**Dependencies:** Fabric needs **Fabric API**. Forge / NeoForge have no required extras.

### How to use (defaults)

After first launch, config is created at `config/hassium/hassium-client.toml` and `config/hassium/hassium-common.toml`.

**Enabled by default:**

- Hassium channel compression and global packet compression
- Client chunk cache (**section delta** + **beyond-view render** when client RD > server view distance)
- **World storage compression** (`storage.enabled = true`)

> **Backup your world before first use.** Storage compression rewrites on-disk chunk payloads. Vanilla clients can still join (`compat.requireClientMod = false`).

Typical setups:

| Goal | What to do |
| --- | --- |
| Full benefit (recommended) | Install on server **and** clients |
| Server-only storage shrink | Server mod only; clients without the mod still connect, but no cache / negotiated chunk pipeline |
| Disable storage rewrite | Set `storage.enabled` to `false` (keeps network + cache if enabled) |
| Force clients to install | Set `compat.requireClientMod` to `true` |
| Disable beyond-view render only | Set `clientCache.viewDistanceExtensionEnabled` to `false` (keep cache) |
| Export visited terrain | Client: `/hassiumc cache export [worldName]` → `saves/` |

### Feature usage in practice

**Storage (server world)**  
With `storage.enabled = true`, the server writes Region files with ZSTD payloads. Outer Anvil layout is unchanged, so tools that only care about `.mca` structure usually still see regions. Always keep a backup before enabling on an existing world.

**Network compression**  
When both sides have the mod and complete the handshake, chunk data is sent over Hassium channels with ZSTD. Optional global packet compression replaces the vanilla Zlib pipeline for broader traffic savings. Control packets (handshake, hash sync, etc.) stay on a compression blacklist so they are not delayed by aggregation.

**Client chunk cache**  
Flow in short:

1. Server intercepts vanilla full-chunk sends after handshake.
2. Server computes a content `chunkHash` and pushes it to the client.
3. Client compares with its local Region / metadata.
4. **Hit** → load from local cache under a main-thread time budget.
5. **Mismatch** (and `sectionDeltaEnabled`) → request section hashes / deltas, merge into disk NBT, then apply; fallback to full chunk on failure.
6. **Miss** → request a full compressed chunk payload, then apply and persist.

Block entities are requested after a real (non–beyond-view) chunk is applied. Client disk cache uses an NBT snapshot format; it is **not** guaranteed across major Minecraft versions.

**Section delta**  
Default on. When the whole-chunk hash no longer matches but you still have a local copy, only changed vertical sections are transferred and merged — less bandwidth than re-downloading the entire chunk every time terrain edits.

**Beyond-view render**  
On multiplayer, if your client render distance is larger than the server’s `view-distance`, Hassium can show **historical** terrain from `hassium_cache` in the outer ring (render-only). It does **not** ask the server for those out-of-range chunks or block entities. Stale snapshots are intentional. Mutually exclusive with Bobby. Toggle with `clientCache.viewDistanceExtensionEnabled`; cap with `maxRenderDistance` (default 32).

**World export**  
`/hassiumc cache export [name]` writes a minimal vanilla Anvil world under `saves/` from your client cache (blocks + BE NBT you have visited). No entities, no player inventory; empty holes regenerate. Same mods / similar MC version recommended for modded blocks.

**Light cache**  
With `clientCache.lightCacheEnabled = true` (default), light data is stored in the local disk cache after the first recomputation. Subsequent cache hits apply pre-computed lighting directly, skipping the expensive synchronous recomputation (rate-limited by `maxLightRecomputePerFrame` for the initial load).

**Smooth loading**  
`network.mainThreadChunkBudgetMs` (default `3`) limits how much chunk-apply work runs per client frame. During the first ~5 seconds after join, a short “JoinBoost” raises the budget so the world fills in faster without long freezes. On the server, `maxChunksPerTick` caps how many chunks are serialized per player per tick.

### Config summary

Files: `config/hassium/hassium-client.toml`, `config/hassium/hassium-common.toml`

| Key | Default | Notes |
| --- | --- | --- |
| `storage.enabled` | `true` | World ZSTD — **back up first** |
| `storage.zstdLevel` | `9` | Storage compression level |
| `clientCache.enabled` | `true` | Client cache |
| `clientCache.sectionDeltaEnabled` | `true` | Section delta on mismatch |
| `clientCache.viewDistanceExtensionEnabled` | `true` | Beyond-view render (multiplayer; exclusive with Bobby) |
| `clientCache.maxRenderDistance` | `32` | Beyond-view / effective RD cap (2–64) |
| `clientCache.ovdUnloadDelaySecs` | `5` | Delay unload after leaving beyond-view ring (s) |
| `network.enabled` | `true` | Custom Hassium channels |
| `network.globalPacketCompression` | `true` | Global ZSTD pipeline |
| `network.compressionLevel` | `3` | Network level (speed-biased) |
| `network.maxChunksPerTick` | `10` | Per-player serialize cap per server tick |
| `network.mainThreadChunkBudgetMs` | `3` | Client apply budget per frame (ms) |
| `clientCache.lightCacheEnabled` | `true` | Cache light data; hits skip recomputation |
| `network.maxLightRecomputePerFrame` | `10` | Max light recomputes per frame |
| `network.metricsEnabled` | `true` | Metrics collection |
| `compat.requireClientMod` | `false` | Allow vanilla clients |
| `debug.*` | `false` | Category debug logs (quiet by default) |

Hot-path logs (packet sizes, cache hits/misses, etc.) stay off unless you enable the matching `debug.*` flags. ERROR / WARN always print.

### Commands

| Command | Side | Description |
| --- | --- | --- |
| `/hassium stats` | Server | Compression / send stats (permission level 2 / OP) |
| `/hassium metrics on\|off` | Server | Toggle metrics at runtime |
| `/hassium stats reset` | Server | Reset counters |
| `/hassiumc stats` | Client | Receive / cache-hit / beyond-view stats |
| `/hassiumc cache export [<worldName>]` | Client | Export local cache as vanilla Anvil under `saves/` |

When metrics are disabled, related stats commands are unavailable.

### Tips

- Prefer matching **loader + Minecraft version** jars from the Files tab.
- For existing production worlds: **backup → enable storage → verify** before relying on it long-term.
- If joins feel heavy, lower `maxChunksPerTick` or keep `mainThreadChunkBudgetMs` modest; if the world fills too slowly, raise them carefully.
- Use `/hassium stats` and `/hassiumc stats` to confirm compression and cache hits after a normal play session.

License: [GPL-3.0-or-later](https://github.com/limuqy/Hassium/blob/main/LICENSE)

---

## 简体中文

### Hassium 是什么？

Hassium 用 **ZSTD** 替代原版 Zlib，主要优化三件事：

1. **世界存档压缩** — 外层仍是熟悉的 Anvil / Region（`.mca`）布局，区块 payload 使用更高压缩率（type `126`）。
2. **网络传输压缩** — 自定义 `hassium:*` 通道，并可选开启**全局包压缩**，降低区块与常规数据包带宽。
3. **客户端区块缓存** — 曾加载过的区块写入本地；再次进入同一区域时用内容哈希（`chunkHash`）比对，未变化则可跳过全量下载。

未安装本模组的客户端默认仍可连接。要吃满协商压缩与缓存命中，请**双端都安装**。

### 特性

| 能力 | 说明 |
| --- | --- |
| **高效存储** | 世界区块更高压缩率落盘，显著减小存档体积；仍兼容原版 Region（`.mca`）布局 |
| **网络压缩** | 区块与（可选）全局数据包使用更高效压缩，降低带宽与等待 |
| **区块缓存** | 曾加载区块写入本地；再次进入优先用本地数据，少传全量包 |
| **分段增量** | 缓存过期时仅拉取变更分段，避免整块重传 |
| **超视渲染** | 多人客户端 RD 大于服务端视距时，用本地历史回填环带（仅渲染、不向服索要视距外区块） |
| **世界导出** | `/hassiumc cache export` 将本地缓存导出为可进单机的原版 Anvil 世界 |
| **光照缓存** | 首次加载重算后缓存光照数据，后续命中直接应用，跳过同步重算 |
| **平滑加载** | 进服与视野扩展时限制主线程压力，减少卡顿尖峰 |
| **兼容友好** | 未装模组的客户端默认可连；双端都装才能吃满压缩与缓存 |
| **流量监控** | `/hassium stats`（服务端）、`/hassiumc stats`（客户端）查看效果 |

### 支持矩阵

| Minecraft | Fabric | Forge | NeoForge |
| --- | --- | --- | --- |
| 1.20.1 | ✅ | ✅ | ✅ |
| 1.20.2–1.20.4 | ✅ | — | ✅ |
| 1.20.5–1.20.6 | ✅ | ✅（仅 1.20.6） | ✅ |
| 1.21.1–1.21.11 | ✅ | — | ✅ |

**依赖：** Fabric 需要 **Fabric API**；Forge / NeoForge 无额外前置。

### 用法与默认行为

首次启动后生成配置：`config/hassium/hassium-client.toml` 与 `config/hassium/hassium-common.toml`。

**默认启用：**

- Hassium 通道压缩与全局包压缩
- 客户端区块缓存（含 **分段增量**；多人且 RD > 服务端视距时启用 **超视渲染**）
- **存档存储压缩**（`storage.enabled = true`）

> **首次使用前请备份世界。** 启用存储会改写区块落盘格式。未装模组的客户端默认可连接（`compat.requireClientMod = false`）。

常见用法：

| 目标 | 做法 |
| --- | --- |
| 完整收益（推荐） | 服务端与客户端都安装 |
| 仅缩小服务端存档 | 只装服务端；无模组客户端仍可连，但无缓存 / 协商区块通道 |
| 关闭存档改写 | 将 `storage.enabled` 设为 `false`（网络与缓存可继续开） |
| 强制客户端安装 | 将 `compat.requireClientMod` 设为 `true` |
| 仅关闭超视渲染 | `clientCache.viewDistanceExtensionEnabled = false`（保留缓存） |
| 导出去过的地形 | 客户端 `/hassiumc cache export [世界名]` → `saves/` |

### 功能怎么用

**存档压缩（服务端世界）**  
`storage.enabled = true` 时，服务端以 ZSTD payload 写入 Region。外层 Anvil 布局不变。对已有世界启用前务必备份。

**网络压缩**  
双端安装并完成握手后，区块数据经 Hassium 通道以 ZSTD 传输。可选全局包压缩会替换原版 Zlib 管线，进一步省流量。握手、哈希同步等控制面在压缩黑名单中，不会被聚合缓冲拖慢。

**客户端区块缓存**  
简要流程：

1. 握手后服务端拦截原版全量区块包。
2. 服务端计算内容 `chunkHash` 并推送给客户端。
3. 客户端与本地 Region / 元数据比对。
4. **命中** → 在主线程时间预算内从本地缓存加载。
5. **过期（MISMATCH）且开启分段增量** → 仅拉变更分段合并写盘再 apply；失败回退全量。
6. **未命中** → 请求全量压缩区块，应用后写入本地缓存。

真实区块 apply 后再请求方块实体。磁盘缓存为 NBT 快照格式，**不保证跨 MC 大版本兼容**。

**分段增量**  
默认开启。整块 hash 不再匹配但仍有本地副本时，只传输变更的竖直分段并合并，比每次整块重下更省流量。

**超视渲染**  
多人服中，客户端渲染距离大于服务端 `view-distance` 时，可用 `hassium_cache` 历史区块回填外侧环带（**仅渲染**）。**不会**向服务器请求视距外区块或 BE；过期快照按设计接受。与 Bobby **互斥**。开关：`clientCache.viewDistanceExtensionEnabled`；上限：`maxRenderDistance`（默认 32）。

**世界导出**  
`/hassiumc cache export [名字]` 把客户端缓存写成 `saves/` 下可进单机的最小 Anvil 世界（方块 + 已缓存的 BE）。无实体、无玩家背包；空洞由世界生成补全。模组方块需相同模组与相近 MC 版本。

**光照缓存**
`clientCache.lightCacheEnabled = true`（默认）时，首次加载的光照重算结果会存入本地缓存。后续缓存命中直接应用预计算光照，跳过耗时的同步重算（首次加载仍受 `maxLightRecomputePerFrame` 限流）。

**平滑加载**  
`network.mainThreadChunkBudgetMs`（默认 `3`）限制客户端每帧用于 apply 的时间。进服约前 5 秒有短暂 JoinBoost，提高预算以加快填图、减少长时间卡死。服务端用 `maxChunksPerTick` 限制每玩家每 tick 序列化区块数。

### 配置摘要

文件：`config/hassium/hassium-client.toml`、`config/hassium/hassium-common.toml`

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `storage.enabled` | `true` | 世界存档 ZSTD（**请先备份**） |
| `storage.zstdLevel` | `9` | 存储压缩等级 |
| `clientCache.enabled` | `true` | 客户端缓存 |
| `clientCache.sectionDeltaEnabled` | `true` | 缓存过期时分段增量 |
| `clientCache.viewDistanceExtensionEnabled` | `true` | 超视渲染（多人；与 Bobby 互斥） |
| `clientCache.maxRenderDistance` | `32` | 超视渲染 / 有效 RD 上限（2–64） |
| `clientCache.ovdUnloadDelaySecs` | `5` | 离开超视渲染环带后延迟卸载（秒） |
| `network.enabled` | `true` | 自定义 Hassium 通道 |
| `network.globalPacketCompression` | `true` | 全局 ZSTD |
| `network.compressionLevel` | `3` | 网络压缩等级（偏速度） |
| `network.maxChunksPerTick` | `10` | 每玩家每 tick 序列化上限 |
| `network.mainThreadChunkBudgetMs` | `3` | 客户端每帧 apply 预算（ms） |
| `clientCache.lightCacheEnabled` | `true` | 光照缓存，命中跳过重算 |
| `network.maxLightRecomputePerFrame` | `10` | 每帧最多重算光照的区块数 |
| `network.metricsEnabled` | `true` | 指标收集 |
| `compat.requireClientMod` | `false` | 允许无模组客户端 |
| `debug.*` | `false` | 分类调试日志（默认安静） |

热路径日志（包大小、命中/未命中等）默认关闭，需要时再开对应 `debug.*`。ERROR / WARN 始终输出。

### 命令

| 命令 | 侧 | 说明 |
| --- | --- | --- |
| `/hassium stats` | 服务端 | 压缩/发送统计（需权限等级 2 / OP） |
| `/hassium metrics on\|off` | 服务端 | 运行时开关指标 |
| `/hassium stats reset` | 服务端 | 重置计数器 |
| `/hassiumc stats` | 客户端 | 接收/缓存命中/超视渲染 统计 |
| `/hassiumc cache export [<世界名>]` | 客户端 | 导出本地缓存为 `saves/` 下原版 Anvil 世界 |

指标关闭时，相关 stats 命令不可用。

### 使用建议

- 从 Files 页选择与你的**加载器 + Minecraft 版本**匹配的 JAR。
- 已有正式世界：先**备份 → 启用存储 → 验证**，再长期依赖。
- 进服偏卡可适当降低 `maxChunksPerTick` 或保持较小的 `mainThreadChunkBudgetMs`；填图过慢再谨慎上调。
- 正常游玩一段时间后用 `/hassium stats` 与 `/hassiumc stats` 确认压缩与缓存是否生效。

许可证：[GPL-3.0-or-later](https://github.com/limuqy/Hassium/blob/main/LICENSE)
