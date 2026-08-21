# Hassium

<p align="center">
  <img src="https://raw.githubusercontent.com/limuqy/Hassium/master/common/src/main/resources/assets/hassium/logo.png" alt="Hassium Logo" width="200">
</p>

**Hassium** 是 Minecraft 的高性能优化模组，提供**高效存储、网络优化、区块缓存、本地生成、超视渲染与光照优化**。覆盖 Minecraft **1.20.1–1.21.11**，支持 **Fabric / Forge / NeoForge**。

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
| **网络优化** | 平滑推送 | 服务端每 tick 提交上限限速（`master.maxChunksPerTick`，掉刻自然降速）+ 主线程序列化上限与后台化 + 客户端 apply ACK 驱动的渐进 admission；进服/扩展视野不卡主线程 |
| | 网关迁移 | 客户端经进程内网关（网络核心）接入主控核心；主控断/卡时 L1 迁移引擎无感续流，缓存不重下、断连界面隐藏 |
| | L1 负载均衡 | 多 UDP 线路按 weight 分担区块下行；UDP 数据面为网关↔主控 bulk 载体（默认关） |
| **区块缓存** | 区块缓存 | 曾加载过的区块写入本地；再次进入同一区域时用 contentHash 比对命中，少传全量包 |
| | 分段增量 | 缓存过期时只补变更方块；过多则整段，再多则整块 |
| | 本地生成（SeedGen） | 服务端对 pristine 区块只发 seed + 坐标引用，客户端用同种子本地生成，零传输生成区块；失败/超时自动回退全量 |
| | **超视渲染** | 多人服客户端 RD 大于服务端视距时，用本地缓存回填视距外地形（仅渲染、不向服索要视距外区块）；与 Bobby 互斥 |
| | 世界导出 | `/hassiumc export` 把影子端世界目录整体拷贝为导出存档（保留 type 126 格式） |
| **光照优化** | 光照剥离 | 服务端可剥光省流量，由 Hassium 引擎（影子端）统一计算光照并落盘缓存 |
| | 光照缓存 | 首次加载重算后缓存光照数据，后续缓存命中直接应用，跳过同步重算 |
| | 并行光照 | 可选：安装 Promethium 后开启；默认由影子端官方引擎异步算光（帧尾预算消费） |
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
| [Network-Core-and-Master-Migration](Network-Core-and-Master-Migration) | 进程内网关、无感迁移与主控核心（服主向） |
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
