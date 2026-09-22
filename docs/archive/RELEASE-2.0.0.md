<!--
  2.0.0 发布说明草稿。可粘贴到 GitHub Release 与 CurseForge changelog。
  仓库: github.com/limuqy/Hassium
  双语：先中文，后英文。
  依据：v1.1.0..HEAD（411 commits）、docs/architecture.md、docs/version-segments.md、
        docs/config-audit.md（52 键）、docs/archive/classic-matrix-smoke-report-2026-09-22.md（15/15 PASS）
-->

# Hassium 2.0.0

**架构大版本。** 客户端↔服务端收敛为**唯一 vanilla TCP 直连**；客户端内嵌**影子服务端**统一负责保存 / 算光 / 本地生成；存储压缩改为**默认关闭**。覆盖 **Fabric / Forge / NeoForge · Minecraft 1.20.1 / 1.21.1–1.21.11**。

仓库：[github.com/limuqy/Hassium](https://github.com/limuqy/Hassium) · 许可证 GPL-3.0-or-later

相对 **1.1.0**（2026-08-01）：411 个提交；全矩阵冒烟 **15/15 PASS**（2026-09-22）。

---

## 简体中文

### 这是什么

用 ZSTD 优化存档与区块传输，并把「进服 / 重连 / 探图」的主线程与带宽压力压到更低：

1. **直连拓扑** — 客户端与服务端之间只有一条 vanilla TCP；登录期协商能力位，Play 期 `play_init` 激活聚合与区块通道。
2. **影子端（客户端进程内）** — 进服区块由进程内影子服务端落盘（`hassium_cache/<serverId>/world`）、统一算光、支持本地生成；断连保存、重连 Compare+Pull 复用。
3. **实体域降帧** — 按距离分档 / 密度热点 / 帧预算反压 / UUID 错峰，只改复制节拍，原版客户端可连。

未装模组的客户端默认可连。**双端都安装**才能吃满协商压缩、缓存命中与影子端能力。

### 破坏性变更（从 1.x 升级）

| 变更 | 说明 |
|------|------|
| **裁剪 1.1.x 数据面** | 进程内网关、UDP 数据面、续流迁移（ResumeTicket）**已移除且不复活**；公网只需放行游戏端口 |
| **配置重构** | 键前缀改为 `chunk.*` / `master.*` / `storage.*` / `compat.*` / `debug.*`；Fabric **双文件** `hassium-client.toml` / `hassium-server.toml`（旧 `common.toml` 三文件模型退役）；旧键加载时清理、不迁移 |
| **`storage.enabled` 默认 false** | 存档压缩改为**默认关**；单人 / 局域网保持原版格式；**仅专用服可开启**，开启前请**备份世界**（type 126） |
| **管线级全局包压缩退役** | 原版压缩层全程不触碰；通道压缩 = 聚合包内部字典 ZSTD + 区块推送自有压缩 |
| **协议硬切** | 与 1.1.x **不互通**（protocol 2）；双端需同升 2.0.0 |
| **支持矩阵收缩** | 去掉 **1.20.2–1.20.6** 段；**1.20.1 不单独发 NeoForge 文件**（用 Forge 版）；Forge **1.21.11 sunset** |

配置键语义与默认值见 [`docs/config-audit.md`](https://github.com/limuqy/Hassium/blob/master/docs/config-audit.md)（**52 键**，真相源 `ConfigSchema`）。

### 新增特性 / Features

- **直连拓扑 / Vanilla direct topology**：唯一 vanilla TCP；1.20.1 走 `hassium:login_hello` login query，1.21.1+ 走配置阶段 `PreHandshakePayload`；Play 期 `play_init_s2c` 激活。原版零干扰，无额外端口
    - Single vanilla TCP connection; capability handshake at login/config, activation via `play_init_s2c`; no extra ports
- **影子端世界保存 + 统一 Compare+Pull（ShadowPull）**：进程内影子服务端保存 / 物化 / 算光；UNCHANGED 全命中、DELTA 只补变更方块/分段；重连可复用
    - In-process shadow server for save/materialize/light; UNCHANGED full hits, DELTA only changed blocks/sections
- **影子端本地生成（SeedGen）**：门控开启时先权威校验再本地 worldgen，减少未探索地形传输；**服务端开启会下发世界种子**
    - Local worldgen under authority check; enabling on the server shares the world seed
- **服务端光照统一计算 + 光照剥离**：影子端 3×3 邻域齐套后再算光，光随原版整柱包下发；`chunk.lightStrip` 双端协商剥离
    - Shadow-side neighborhood lighting; light ships with the full vanilla chunk packet; optional light strip
- **实体域降帧 / 错峰**：`master.entity*` 9 键——距离分档、物品流独立档位、密度热点、帧预算反压、UUID 相位错峰；**vanilla 兼容、不要求客户端握手**
    - Tiered entity pacing, density throttle, per-player backpressure, UUID smooth-push; vanilla-compatible
- **平滑推送**：`master.maxChunksPerTick` 限速 + 原版整柱通道抑制（1.20.1 / 1.21.1+ 分版本钩子）；encode / 压缩 / 发送后台化
    - Per-tick chunk pacing and whole-chunk suppression on the vanilla channel; encoding/compression off main thread
- **超视渲染（OVD）双窗**：权威窗服务端声明 + Compare+Pull；OVD 环带本地回填（仅渲染，不向服索要视距外区块）
    - Dual-window OVD: server-authoritative inner window + local render-only outer ring
- **多维缓存**：主世界 / 地狱 / 末地及自定义维度组装与缓存
    - Multi-dimension cache including custom dimensions
- **配置 / 发布**：schema 驱动 52 键、中英注释同源；一键同时发布 **CurseForge + Modrinth**
    - Schema-driven bilingual config; one build publishes to CurseForge and Modrinth
- **冒烟门禁**：L0 单测 + classic 全矩阵 + L2 三锚点（seedgen / dimension）；PROBE JSON 业务门禁
    - Layered smoke (L0–L2) with PROBE JSON business gates

### 支持矩阵 / Support Matrix

| Minecraft | Fabric | Forge | NeoForge |
|-----------|--------|-------|----------|
| 1.20.1 | ✅ | ✅ | —（用 Forge 版） |
| 1.21.1 | ✅ | ✅ | ✅ |
| 1.21.2 | ✅ | —（无 userdev） | ✅ |
| 1.21.3–1.21.10 | ✅ | ✅ | ✅ |
| 1.21.11 | ✅ | —（sunset） | ✅ |

- **已移除**：1.20.2–1.20.6
- Fabric 需 Fabric API（Cloth 已打包）；Forge / NeoForge 无额外前置
- 从 [Releases](https://github.com/limuqy/Hassium/releases) / [CurseForge](https://www.curseforge.com/minecraft/mc-mods/hassium) / Modrinth 选**加载器 + MC 版本**对应的 JAR

### 默认行为

安装后默认启用：区块核心（影子端保存 / 算光 / 缓存 / 分段增量 / OVD）、直连握手与聚合压缩、实体域降帧、平滑推送。

默认**关闭**：

- `storage.enabled = false` — 专用服存档压缩；开启会改写格式（type 126），**先备份**
- `chunk.seedGenEnabled = false` — 本地生成；服务端开启会**泄露世界种子**，且需双端同版本
- `master.enabledOnLan = false` — 集成服默认不对远程玩家开网络面

无模组客户端默认可连（`compat.requireClientMod = false`）。

### 安装 / 升级

1. 下载对应**加载器 + MC 版本**的 JAR，放入客户端 / 服务端 `mods/`。
2. 首次启动生成 `config/hassium/hassium-client.toml` 与 `config/hassium/hassium-server.toml`。
3. **从 1.x 升级**：双端同升 2.0.0；旧配置键不会自动迁移，请按新 `chunk.*` / `master.*` 键族重配；确认 `storage.enabled` 意图后再开。
4. Fabric：Mod Menu + Cloth；Forge / NeoForge：模组列表配置屏，或手改 toml。

### 验证（发布前）

- Classic 全矩阵冒烟 **15/15 PASS**（2026-09-22，`c1bd4284` 树；12 fabric + 2 forge + 1 neoforge；1.20.1 neoforge 按 `builds_for` SKIP）——见 [`docs/archive/classic-matrix-smoke-report-2026-09-22.md`](https://github.com/limuqy/Hassium/blob/master/docs/archive/classic-matrix-smoke-report-2026-09-22.md)
- 重连场景 R2 **区块缓存 15/15 场 100%**；服务端 + 客户端日志零错误指纹

### 已知边界

- **不兼容** Bobby / 同类视距外缓存、同类压缩或协议替换模组、Immersive Portals（见 [`docs/mod-compat.md`](https://github.com/limuqy/Hassium/blob/master/docs/mod-compat.md)）。
- 磁盘缓存为 type 126 原版存档结构，**不保证跨 MC 大版本兼容**。
- 与 **1.1.x 协议不互通**；UDP / 网关 / 续流迁移已裁剪，不再提供。
- 光照验收须含**移动中**的屋檐 / 洞口目视；冒烟绿不能代替目视（算光红线）。

### 文档

架构 / 直连拓扑 / 缓存推送 / 光照流 / 七段适配 / 配置审计 / 冒烟见 [`docs/`](https://github.com/limuqy/Hassium/tree/master/docs) 与根 `README.md`。

---

## English

### What is this

Hassium shrinks saves and chunk traffic with ZSTD, and reduces join / rejoin / explore pressure on the main thread and the wire:

1. **Direct topology** — a single vanilla TCP connection between client and server; capabilities negotiated at login/config, activated in Play via `play_init`.
2. **Shadow server (in-process)** — visited chunks are saved (`hassium_cache/<serverId>/world`), lit, and optionally generated locally inside the client process; disconnect saves, reconnect uses Compare+Pull.
3. **Entity pacing** — distance tiers, density hotspots, per-player packet budget, UUID phase stagger; replication-only; vanilla clients can join.

Vanilla clients can connect by default. Install on **both** sides for negotiated compression, cache hits, and the full shadow pipeline.

### Breaking changes (upgrading from 1.x)

| Change | Detail |
|--------|--------|
| **1.1.x data plane retired** | In-process gateway, UDP data plane, and resume/migration tickets are **removed for good**; only the game port is required |
| **Config restructure** | Keys under `chunk.*` / `master.*` / `storage.*` / `compat.*` / `debug.*`; Fabric uses **two files** `hassium-client.toml` / `hassium-server.toml`; legacy keys are purged, not migrated |
| **`storage.enabled` defaults to false** | Save compression is **off by default**; single-player/LAN stay vanilla; **dedicated servers only** when enabled — **back up first** (type 126) |
| **Global packet compression retired** | Vanilla compression layer is never touched; channel compression = aggregation dictionary ZSTD + chunk-push framing |
| **Hard protocol cut** | **Not interoperable with 1.1.x** (protocol 2); both ends must run 2.0.0 |
| **Support matrix shrunk** | **1.20.2–1.20.6 dropped**; no separate NeoForge 1.20.1 file (use the Forge jar); Forge **1.21.11 sunset** |

Full key list and defaults: [`docs/config-audit.md`](https://github.com/limuqy/Hassium/blob/master/docs/config-audit.md) (**52 keys**, source of truth `ConfigSchema`).

### Highlights

- **Vanilla direct topology** — one TCP connection; `hassium:login_hello` (1.20.1) or config-phase `PreHandshakePayload` (1.21.1+); Play activation via `play_init_s2c`; zero interference with vanilla; no extra ports
- **Shadow world save + unified Compare+Pull** — in-process shadow server; UNCHANGED full hits, DELTA only changed blocks/sections; reuse across reconnects
- **Local generation (SeedGen)** — authority-checked local worldgen when enabled; **server enablement shares the world seed**
- **Unified server-side lighting + light strip** — neighborhood-gated light on the shadow side; light rides the full vanilla chunk packet; optional strip negotiation
- **Entity pacing** — `master.entity*` (9 keys): distance tiers, independent item-stream table, density throttle, frame budget, UUID smooth-push; vanilla-compatible, no client handshake required
- **Smooth chunk push** — `master.maxChunksPerTick` + whole-chunk suppression on the vanilla channel; encode/compress off the main thread
- **Beyond-view render (OVD)** — dual window: server-authoritative inner + local render-only outer ring
- **Multi-dimension cache** — overworld/nether/end and custom dimensions
- **Publish + config** — schema-driven bilingual config; one build ships CurseForge + Modrinth
- **Smoke gates** — L0 unit + classic full matrix + L2 anchors, PROBE JSON business gates

### Support matrix

| Minecraft | Fabric | Forge | NeoForge |
|-----------|--------|-------|----------|
| 1.20.1 | Yes | Yes | — (use Forge jar) |
| 1.21.1 | Yes | Yes | Yes |
| 1.21.2 | Yes | — (no userdev) | Yes |
| 1.21.3–1.21.10 | Yes | Yes | Yes |
| 1.21.11 | Yes | — (sunset) | Yes |

- **Removed:** 1.20.2–1.20.6
- Fabric needs Fabric API (Cloth is bundled); Forge / NeoForge need no extras
- Pick the jar matching **loader + MC version** from [Releases](https://github.com/limuqy/Hassium/releases) / CurseForge / Modrinth

### Defaults

Enabled by default: chunk core (shadow save/light/cache/section delta/OVD), direct handshake + aggregation compression, entity pacing, smooth push.

**Off** by default:

- `storage.enabled = false` — dedicated-server save compression (type 126); **back up before enabling**
- `chunk.seedGenEnabled = false` — local generation; server enablement **shares the world seed**; both ends must match version
- `master.enabledOnLan = false` — LAN host does not open the network plane to remote players

Vanilla clients join by default (`compat.requireClientMod = false`).

### Install / upgrade

1. Download the JAR for your **loader + MC version** into client / server `mods/`.
2. First launch creates `config/hassium/hassium-client.toml` and `config/hassium/hassium-server.toml`.
3. **From 1.x:** upgrade **both** ends to 2.0.0; config keys are **not** auto-migrated — re-apply under `chunk.*` / `master.*`; decide `storage.enabled` deliberately before enabling.
4. Fabric: Mod Menu + Cloth; Forge / NeoForge: mod list config screen, or edit the toml.

### Pre-release validation

- Classic full-matrix smoke **15/15 PASS** (2026-09-22, tree `c1bd4284`; 12 fabric + 2 forge + 1 neoforge; 1.20.1 neoforge SKIP by `builds_for`) — [`docs/archive/classic-matrix-smoke-report-2026-09-22.md`](https://github.com/limuqy/Hassium/blob/master/docs/archive/classic-matrix-smoke-report-2026-09-22.md)
- Reconnect (R2) **chunk cache 100% on 15/15 sessions**; zero error fingerprints in server + client logs

### Known limitations

- **Incompatible** with Bobby / similar beyond-view caches, competing compression or protocol-replacement mods, and Immersive Portals ([`docs/mod-compat.md`](https://github.com/limuqy/Hassium/blob/master/docs/mod-compat.md)).
- Disk cache uses type 126 vanilla save layout; **not** guaranteed across major Minecraft versions.
- **Not interoperable with 1.1.x**; UDP / gateway / resume migration are gone.
- Lighting acceptance must include **in-motion** eaves/cave mouths by eye; a green smoke run is not enough.

### Docs

Architecture / direct topology / cache pipeline / light flow / seven-segment matrix / config audit / smoke: [`docs/`](https://github.com/limuqy/Hassium/tree/master/docs) and the root `README.md`.
