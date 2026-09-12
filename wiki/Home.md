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
| **高效压缩** | 存储压缩 | 区块 ZSTD 落盘（type 126），存档体积显著减小；仍兼容原版 Region（`.mca`）布局 |
| | 通道压缩 | 聚合包内部字典 ZSTD + 区块推送自有压缩；不触碰原版压缩层，无跨 mod 管线冲突面 |
| **网络优化** | 平滑推送 | 服务端每 tick Pull 完成上限限速（`master.maxChunksPerTick`，掉刻自然降速）+ encode/压缩后台化；进服不卡主线程 |
| | 登录期能力握手 | 1.20.1 走 `hassium:login_hello` login query，1.21.1+ 走配置阶段 payload；按位与协商能力位，无超时依赖，原版客户端零干扰 |
| | Pull 模式 | 协商通过后服务端停发整柱推送，区块数据由客户端影子虚拟玩家 tracking 驱动的统一 Compare+Pull 拉取 |
| **区块缓存** | 影子端世界保存 | 进服区块统一由进程内影子服务端（完整 MinecraftServer）算光并落盘原版存档（`hassium_cache/<serverId>/world`），断连保存、重连复用 |
| | 分段增量 | 缓存过期时只补变更方块；过多则整段，再多则整块 |
| | 容量/热度淘汰 | `heat.idx` 按 region 文件计热度，超限整文件删除 `.mca` |
| | 本地生成（SeedGen） | 双端同版本且开启时，服务端在 Play 激活（`play_init_s2c`）下发世界种子，客户端影子端 tracking 触发原版 worldgen 本地生成 pristine 区块，生成后经服务端权威 compare-pull 校验交付。**服务端开启会下发世界种子（泄露种子）** |
| | 世界导出 | `/hassiumc export` 把影子端世界目录整体拷贝为导出存档（保留 type 126 格式；原版翻译后续提供） |
| **超视渲染** | OVD（影子双窗） | 客户端 RD 大于服务端视距时，用影子端本地已有地形（盘 / 注入）回填视距外环带；**仅渲染不模拟**，不向服务端请求；与 Bobby 互斥 |
| **光照优化** | Hassium 引擎 | 进服启动进程内影子服务端统一承担世界保存（缓存）+ 区块光照计算 + 打包官方区块包（官方通道回传）；启动失败自动降级 |
| | 光照剥离 | 服务端可剥光省流量（`chunk.lightStrip`），由影子端统一计算光照并打包回传 |
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
