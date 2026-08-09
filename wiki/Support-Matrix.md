# 支持矩阵

---

> **English**: [Support-Matrix-en](Support-Matrix-en) · 中文

Hassium 覆盖 Minecraft **1.20.1–1.21.11**，按 **9 个版本段 × 加载器** 适配。一版一段，段内版本以锚点编译代表。

---

## MC 版本 × 加载器

| Minecraft | Fabric | Forge | NeoForge |
| --- | --- | --- | --- |
| 1.20.1 | ✅ | ✅ | ✅ |
| 1.20.2 | ✅ | — | ✅ |
| 1.20.3 | ✅ | — | ✅ |
| 1.20.4 | ✅ | — | ✅ |
| 1.20.5 | ✅ | — | ✅ |
| 1.20.6 | ✅ | ✅ | ✅ |
| 1.21.1 | ✅ | ✅ | ✅ |
| 1.21.2 | ✅ | — | ✅ |
| 1.21.3 | ✅ | ✅ | ✅ |
| 1.21.4 | ✅ | ✅ | ✅ |
| 1.21.5 | ✅ | ✅ | ✅ |
| 1.21.6 | ✅ | ✅ | ✅ |
| 1.21.7 | ✅ | ✅ | ✅ |
| 1.21.8 | ✅ | ✅ | ✅ |
| 1.21.9 | ✅ | ✅ | ✅ |
| 1.21.10 | ✅ | ✅ | ✅ |
| 1.21.11 | ✅ | — | ✅ |

- **Forge 支持 1.20.1 / 1.20.6 / 1.21.1 / 1.21.3–1.21.10**；1.21.2 上游未发布 Forge userdev（官方跳过），**1.21.11 起 sunset**，1.21.x 推荐使用 NeoForge
- 1.20.1 的 Forge 兼容由 neoforge 子项目（`loom.platform=forge`）承担；独立 forge 子项目仅构建 1.20.6 / 1.21.x
- 1.20.6 因与 NeoForge 共用 `ModConfigSpec`，仅 Forge 1.20.6 一处保留 Forge Config API Port 桥接

---

## 九段锚点（编译矩阵）

各版本段以一个锚点版本为代表参与编译与自检：

| 段 | 锚点 | 段内其余版本 | 关键变化（摘要） |
| --- | --- | --- | --- |
| A | **1.20.1** | — | 基准：旧网络 + 全部旧 API |
| B | **1.20.2** | 1.20.3 | CustomPayload 路径；NeoForge 包名 |
| C | **1.20.5** | 1.20.6 | StreamCodec；`Packet.write` 等移除 |
| D | **1.21.1** | — | `DisconnectionDetails`；ResourceLocation 构造私有化 |
| E | **1.21.2** | 1.21.3, 1.21.4 | `SerializableChunkData`、`lookupOrThrow` |
| F | **1.21.5** | — | CompoundTag API；ProtocolInfo Unbound 拆分；客户端缓存**不跨 MC 大版本兼容** |
| G | **1.21.6** | 1.21.7, 1.21.8 | `serverLevel()`→`level()`；Connection.send 监听器；NeoForge EBS bus 移除 |
| H | **1.21.9** | 1.21.10 | LevelChunkSection；`getServer()` 移除；`setLevel` 单参 |
| I | **1.21.11** | — | `ResourceLocation` → `Identifier` |

每段一个锚点参与编译与自检；其余段内版本以发布形式提供，由 Manifold `#if MC_VER` 在同一套源码内分段。

---

## 客户端缓存跨版本策略

自段 F（1.21.5）起，客户端区块缓存**不保证跨 MC 大版本读写兼容**：

- 同 MC 版本内（含 Fabric ↔ NeoForge）正常命中与覆盖写入
- 升版本后旧缓存可懒覆盖（MISS → 重拉 → persist），**不做启动时整库作废**
- 不实现跨版本迁移 / 格式协商

详见 [Compatibility](Compatibility)。

---

## 仓库与构建

- 仓库：`https://github.com/limuqy/Hassium`
- 构建系统：Architectury Loom + Manifold，`versionProperties/<ver>.properties` 控制每版本依赖
- 锚点编译命令（开发者）：`./gradlew compileAnchors`
- 版本边界扫描：`./gradlew scanVersionBoundaries`

---

[← Compatibility](Compatibility) · [Home](Home) · [→ Network-Core-and-Master-Migration](Network-Core-and-Master-Migration)
