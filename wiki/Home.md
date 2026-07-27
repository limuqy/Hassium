# Hassium

<p align="center">
  <img src="https://raw.githubusercontent.com/limuqy/Hassium/master/common/src/main/resources/assets/hassium/logo.png" alt="Hassium Logo" width="200">
</p>

**Hassium** 是 Minecraft 的高性能优化模组，提供**高效存储、网络优化、区块缓存、超视渲染与光照优化**。覆盖 Minecraft **1.20.1–1.21.11**，支持 **Fabric / Forge / NeoForge**。

> 仓库：[github.com/limuqy/Hassium](https://github.com/limuqy/Hassium) · [English](Home-en)

![License](https://img.shields.io/badge/License-GPL--3.0-blue.svg)
![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1--1.21.11-green.svg)
![Loaders](https://img.shields.io/badge/Loaders-Fabric%20%7C%20Forge%20%7C%20NeoForge-orange.svg)
[![CurseForge](https://img.shields.io/badge/CurseForge-Hassium-644DF4.svg?logo=curseforge)](https://www.curseforge.com/minecraft/mc-mods/hassium)

---

## 核心能力

| 能力 | 说明 |
| --- | --- |
| **高效存储** | 世界区块用更高压缩率落盘，显著减小存档体积；仍兼容原版 Region（`.mca`）布局 |
| **网络压缩** | 区块与数据包用更高效压缩传输，降低带宽占用与下载等待 |
| **区块缓存** | 曾加载过的区块写入本地；再次进入同一区域时优先用本地数据，少传全量包 |
| **分段增量** | 缓存过期时仅拉取变更分段（`sectionDelta`），避免整块重传 |
| **超视渲染** | 多人服客户端 RD 大于服务端视距时，用本地缓存回填视距外地形（仅渲染、不向服索要视距外区块） |
| **世界导出** | `/hassiumc export` 将本地缓存导出为可进单机的原版 Anvil 世界 |
| **光照剥离** | 服务端可不传光照数据，由客户端本地重算，进一步省流量 |
| **光照缓存** | 首次加载重算后缓存光照数据，后续缓存命中直接应用，跳过同步重算 |
| **主控热切** | TCP 主控断或卡时按候选自动重连，缓存暖续、隐藏断连界面（数据面 failover） |
| **加权分流** | 多 UDP/KCP endpoint 按 weight 加权轮询承载数据面，控制面留原版 TCP |
| **平滑加载** | 进服与视野扩展时限制主线程压力，减少卡顿尖峰 |
| **兼容友好** | 未安装本模组的客户端默认可连接；双端都装才能吃满压缩与缓存 |
| **流量监控** | `/hassium stats`（服务端）、`/hassiumc stats`（客户端）查看压缩与缓存效果 |

功能详情见 [Features](Features)。

---

## 快速上手

1. 从 [GitHub Releases](https://github.com/limuqy/Hassium/releases) 或 [CurseForge](https://www.curseforge.com/minecraft/mc-mods/hassium) 下载对应加载器的 JAR。
2. 放进客户端/服务端的 `mods/` 目录。
3. 启动游戏，配置文件会自动生成在 `config/hassium/`。
4. **首次启用存储前请备份世界**（见 [FAQ](FAQ)）。

详细安装与前置依赖见 [Installation](Installation)。

---

## 文档导航

| 页面 | 内容 |
| --- | --- |
| [Installation](Installation) | 下载、前置、各加载器差异 |
| [Configuration](Configuration) | 完整配置项表与 GUI 路径 |
| [Commands](Commands) | `/hassium` 与 `/hassiumc` 命令参考 |
| [Features](Features) | 功能特性详解 |
| [Beyond-View-Render](Beyond-View-Render) | 超视渲染详解 |
| [World-Export](World-Export) | 缓存世界导出 |
| [Compatibility](Compatibility) | 多 Mod 兼容对照表 |
| [Support-Matrix](Support-Matrix) | 版本 × 加载器支持矩阵 |
| [Data-Plane-and-Failover](Data-Plane-and-Failover) | UDP 数据面 + 主控热切（服主向） |
| [FAQ](FAQ) | 常见问题 |
| [Troubleshooting](Troubleshooting) | 排查路径与日志 |

---

## 支持矩阵（摘要）

| Minecraft | Fabric | Forge | NeoForge |
| --- | --- | --- | --- |
| 1.20.1 | ✅ | ✅ | ✅ |
| 1.20.2–1.20.4 | ✅ | — | ✅ |
| 1.20.5–1.20.6 | ✅ | ✅（仅 1.20.6） | ✅ |
| 1.21.1–1.21.11 | ✅ | — | ✅ |

完整九段适配见 [Support-Matrix](Support-Matrix)。

---

## 许可证

 [GPL-3.0-or-later](https://github.com/limuqy/Hassium/blob/master/LICENSE)
