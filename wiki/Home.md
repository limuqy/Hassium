# Hassium

<p align="center">
  <img src="https://raw.githubusercontent.com/limuqy/Hassium/master/common/src/main/resources/assets/hassium/logo.png" alt="Hassium Logo" width="200">
</p>

**Hassium** 是 Minecraft 的高性能优化模组，提供**高效压缩、网络优化、区块缓存、本地生成与光照优化**。覆盖 Minecraft **1.20.1–1.21.11**，支持 **Fabric / Forge / NeoForge**。

> 仓库：[github.com/limuqy/Hassium](https://github.com/limuqy/Hassium) · [English](Home-en)

![License](https://img.shields.io/badge/License-GPL--3.0-blue.svg)
![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1--1.21.11-green.svg)
![Loaders](https://img.shields.io/badge/Loaders-Fabric%20%7C%20Forge%20%7C%20NeoForge-orange.svg)
[![CurseForge](https://img.shields.io/badge/CurseForge-Hassium-644DF4.svg?logo=curseforge)](https://www.curseforge.com/minecraft/mc-mods/hassium)

---

## 核心能力

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
| [Beyond-View-Render](Beyond-View-Render) | 超视渲染（OVD） |
| [World-Export](World-Export) | 缓存世界导出 |
| [Compatibility](Compatibility) | 多 Mod 兼容对照表 |
| [Support-Matrix](Support-Matrix) | 版本 × 加载器支持矩阵 |
| [Network-Architecture](Network-Architecture) | 网络架构（直连拓扑） |
| [FAQ](FAQ) | 常见问题 |
| [Troubleshooting](Troubleshooting) | 排查路径与日志 |

---

## 支持矩阵（摘要）

| Minecraft | Fabric | Forge | NeoForge |
| --- | --- | --- | --- |
| 1.20.1 | ✅ | ✅ | — |
| 1.21.1 | ✅ | ✅ | ✅ |
| 1.21.2 | ✅ | — | ✅ |
| 1.21.3–1.21.10 | ✅ | ✅ | ✅ |
| 1.21.11 | ✅ | — | ✅ |

Forge 支持 1.20.1 / 1.21.1 / 1.21.3–1.21.10（1.21.2 上游无 Forge userdev；1.21.11 起 sunset，用 NeoForge）。**1.20.1 不单独发 NeoForge 文件**：NeoForge 47.x 原生兼容 Forge mod，NeoForge 用户直接使用 Forge 版。

完整七段适配见 [Support-Matrix](Support-Matrix)。

---

## 许可证

 [GPL-3.0-or-later](https://github.com/limuqy/Hassium/blob/master/LICENSE)
