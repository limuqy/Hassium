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

| 分类 | 能力 | 说明 |
| --- | --- | --- |
| **高效压缩** | 存储压缩 | 区块 ZSTD 落盘（type 126），存档体积显著减小；仍兼容原版 Region（`.mca`）布局 |
| | 网络压缩 | 区块与数据包 ZSTD 传输（自定义通道 + 可选全局管道 + 聚合），降低带宽与下载等待 |
| **网络优化** | 平滑推送 | 服务端每 tick 提交上限限速（`maxChunksPerTick`，掉刻自然降速）+ 主线程序列化上限与后台化；进服/扩展视野不卡主线程 |
| | 主控热切 | TCP 主控断或卡时按候选自动重连；恢复期画面定格（可切无感切换：世界继续运行、恢复后回退）、缓存暖续、隐藏断连界面（默认关） |
| | 加权分流 | 多 UDP/KCP 线路按 weight 分担区块下载，控制面留原版 TCP（默认关） |
| **区块缓存** | 客户端缓存 | 曾加载过的区块写入本地；再次进入同一区域时用 contentHash 比对命中，少传全量包 |
| | 分段增量 | 缓存过期（MISMATCH）时仅拉取变更分段（`sectionDelta`）本地合并，避免整块重传 |
| | **超视渲染** | 多人服客户端 RD 大于服务端视距时，用本地缓存回填视距外地形（仅渲染、不向服索要视距外区块）；与 Bobby 互斥 |
| | 世界导出 | `/hassiumc export` 将本地缓存导出为可进单机的原版 Anvil 世界 |
| **光照优化** | 光照剥离 | 服务端可不传光照数据，由客户端本地重算，进一步省流量 |
| | 光照缓存 | 首次加载重算后缓存光照数据，后续缓存命中直接应用，跳过同步重算 |
| | 并行光照 | 光照重算在后台线程池并行执行，主线程只提交快照（默认开启） |
| **实用工具** | 流量监控 | `/hassium stats`（服务端）、`/hassiumc stats`（客户端）查看压缩与缓存效果 |

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
| [Data-Plane-and-Failover](Data-Plane-and-Failover) | 主控热切与加权分流（服主向） |
| [FAQ](FAQ) | 常见问题 |
| [Troubleshooting](Troubleshooting) | 排查路径与日志 |

---

## 支持矩阵（摘要）

| Minecraft | Fabric | Forge | NeoForge |
| --- | --- | --- | --- |
| 1.20.1 | ✅ | ✅ | ✅ |
| 1.20.2–1.20.5 | ✅ | — | ✅ |
| 1.20.6 | ✅ | ✅ | ✅ |
| 1.21.1 | ✅ | ✅ | ✅ |
| 1.21.2 | ✅ | — | ✅ |
| 1.21.3–1.21.10 | ✅ | ✅ | ✅ |
| 1.21.11 | ✅ | — | ✅ |

完整九段适配见 [Support-Matrix](Support-Matrix)。

---

## 许可证

 [GPL-3.0-or-later](https://github.com/limuqy/Hassium/blob/master/LICENSE)
