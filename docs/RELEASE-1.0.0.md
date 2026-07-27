<!-- 1.0.0 首个正式发布说明。可粘贴到 GitHub Release 与 CurseForge changelog。
  仓库: github.com/limuqy/Hassium
  双语版本：先中文，后英文。 -->

# Hassium 1.0.0

**首个正式发布。** 高性能区块压缩与客户端缓存模组，用 ZSTD 替代原版 Zlib，覆盖 **Fabric / Forge / NeoForge · Minecraft 1.20.1–1.21.11**。

仓库：[github.com/limuqy/Hassium](https://github.com/limuqy/Hassium) · 许可证 GPL-3.0-or-later

---

## 简体中文

### 这是什么

用 ZSTD 优化三件事：

1. **世界存档压缩** — 外层仍是 Anvil / Region（`.mca`），区块 payload 改用更高压缩率（type `126`）。
2. **网络传输压缩** — 自定义 `hassium:*` 通道 + 可选全局包压缩，降低区块与常规数据包带宽。
3. **客户端区块缓存** — 曾加载的区块写入本地；再次进入同一区域时用 `chunkHash` 比对，未变化则跳过全量下载。

未装模组的客户端默认可连接。**双端都安装**才能吃满协商压缩与缓存命中。

### 已实现特性

| 特性 | 说明 |
| --- | --- |
| 高效存储 | 更高压缩率落盘，显著减小存档；Region 外壳不变 |
| 网络压缩 | 区块与全局数据包用 ZSTD，省带宽与等待 |
| 区块缓存 | 本地复用已加载区块，少传全量包 |
| **分段增量** | 缓存过期时仅拉变更分段，避免整块重传 |
| **超视渲染** | 多人 RD > 服务端视距时，本地历史回填环带（仅渲染、不向服索要视距外区块） |
| **世界导出** | `/hassiumc export` 把本地缓存写成可进单机的原版 Anvil 世界 |
| 光照优化 | 服务端可剥离光照数据；客户端重算后缓存，命中跳过同步重算 |
| 平滑加载 | 限制进服与视野扩展的主线程压力（含进服前 ~10s JoinBoost） |
| 兼容友好 | 无模组客户端默认可连；双端安装吃满收益 |
| 流量监控 | `/hassium stats`（服务端）、`/hassiumc stats`（客户端） |

### 支持矩阵

| Minecraft | Fabric | Forge | NeoForge |
| --- | --- |-------| --- |
| 1.20.1 | ✅ | ✅     | ✅ |
| 1.20.2–1.20.4 | ✅ | —     | ✅ |
| 1.20.5–1.20.6 | ✅ | —     | ✅ |
| 1.21.1–1.21.11 | ✅ | —     | ✅ |

Fabric 需 Fabric API（Cloth 已打包）；Forge / NeoForge 无额外前置。从 [Releases](https://github.com/limuqy/Hassium/releases) 选对应**加载器 + MC 版本**的 JAR。

### 默认行为

安装后默认启用：Hassium 通道 + 全局包压缩、客户端区块缓存（含分段增量、多人且 RD > 服务端视距时的超视渲染）、**存档存储压缩**。

> **首次使用前请备份世界。** 启用存储会改写区块落盘格式（type 126）。无模组客户端默认可连接（`compat.requireClientMod = false`）。

### 安装

1. 下载对应加载器的 JAR，放入客户端 / 服务端 `mods/`。
2. 首次启动生成 `config/hassium/hassium-client.toml` 与 `config/hassium/hassium-common.toml`。
3. Fabric：Mod Menu + Cloth 打开配置；Forge / NeoForge：模组列表「配置」按钮，或手改 toml。

### 已知边界

- **不兼容** Bobby / 同类视距外缓存、同类压缩或协议替换模组、Immersive Portals。
- 磁盘缓存为 NBT 快照格式，**不保证跨 MC 大版本兼容**。
- `migration/` 与公共 `HassiumApi` 尚未实现（路线图）。
- 详细兼容边界见 [`docs/mod-compat.md`](mod-compat.md)。

### 基准（简表）

ROUND1（VD=20，冷缓存）：1.20.1 上比原版 Zlib 节省 **~43%** 带宽（12.3 MB → 7.0 MB）；1.21.x 节省 **~20–21%**。ZSTD 级别 3 压缩比原版 Zlib 级别 6 快 **4–17×**，解压快 **20–40%**。

### 文档

架构 / 缓存推送 / 超视渲染 / 磁盘 NBT / 多版本适配 / 兼容见 [`docs/`](https://github.com/limuqy/Hassium/tree/master/docs) 与根 `README.md`。

---

## English

### What is this

ZSTD replaces vanilla Zlib for three things:

1. **World save compression** — chunks stay in Anvil / Region (`.mca`) layout, payload switches to a higher ratio (type `126`).
2. **Network compression** — custom `hassium:*` channels plus optional global packet compression for less bandwidth on chunks and general packets.
3. **Client chunk cache** — loaded chunks are kept locally; on revisit, a `chunkHash` comparison skips the full download if unchanged.

Vanilla clients can connect by default. Install Hassium on **both** client and server for negotiated compression and cache hits.

### Implemented features

| Feature | What it does |
| --- | --- |
| Efficient storage | Higher-ratio chunk compression, smaller saves; Region shell unchanged |
| Network compression | ZSTD for chunks and (optionally) the whole packet pipeline |
| Chunk cache | Local reuse of loaded chunks, fewer full downloads |
| **Section delta** | On mismatch, pull only changed sections instead of the whole chunk |
| **Beyond-view render** | When client RD > server view distance (multiplayer), fill the outer ring from local history (render-only; no out-of-range server requests) |
| **World export** | `/hassiumc export` turns the local cache into a vanilla Anvil singleplayer world |
| Light optimization | Server can strip light data; client recomputes and caches, hits skip recomputation |
| Smooth loading | Caps main-thread work during join / view expansion (with a ~10s JoinBoost) |
| Client-friendly | Vanilla clients join by default; both sides needed for full benefits |
| Traffic metrics | `/hassium stats` (server), `/hassiumc stats` (client) |

### Support matrix

| Minecraft | Fabric | Forge | NeoForge |
| --- | --- | --- | --- |
| 1.20.1 | Yes | Yes | Yes |
| 1.20.2–1.20.4 | Yes | — | Yes |
| 1.20.5–1.20.6 | Yes | Yes (1.20.6 only) | Yes |
| 1.21.1–1.21.11 | Yes | — | Yes |

Fabric needs Fabric API (Cloth is bundled); Forge / NeoForge have no required extras. Pick the jar matching your **loader + MC version** from [Releases](https://github.com/limuqy/Hassium/releases).

### Defaults

Enabled by default: Hassium channels + global packet compression, client chunk cache (section delta, and beyond-view render in multiplayer when RD > server view distance), **world storage compression**.

> **Back up your world before first use.** Storage compression rewrites on-disk chunk payloads (type 126). Vanilla clients can still join (`compat.requireClientMod = false`).

### Installation

1. Download the jar for your loader, drop it into client / server `mods/`.
2. First launch creates `config/hassium/hassium-client.toml` and `config/hassium/hassium-common.toml`.
3. Fabric: Mod Menu + Cloth; Forge / NeoForge: the “Config” button in the mod list, or edit the toml by hand.

### Known limitations

- **Incompatible** with Bobby / similar beyond-view caches, competing compression or protocol-replacement mods, and Immersive Portals.
- The disk cache uses an NBT snapshot format; **not** guaranteed across major Minecraft versions.
- `migration/` and the public `HassiumApi` are not yet implemented (roadmap).
- See [`docs/mod-compat.md`](mod-compat.md) for full compatibility boundaries.

### Benchmark (summary)

ROUND1 (VD=20, cold cache): ~**43%** bandwidth savings vs vanilla Zlib on 1.20.1 (12.3 MB → 7.0 MB); ~**20–21%** on 1.21.x. ZSTD level 3 compresses **4–17×** faster than vanilla Zlib level 6, with **20–40%** faster decompression.

### Docs

Architecture / cache pipeline / beyond-view render / disk NBT / multi-version / compatibility live in [`docs/`](https://github.com/limuqy/Hassium/tree/master/docs) and the root `README.md`.
