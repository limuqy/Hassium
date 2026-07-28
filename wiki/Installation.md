# 安装

---

> **English**: [Installation-en](Installation-en) · 中文

## 下载

从 [GitHub Releases](https://github.com/limuqy/Hassium/releases) 或 [CurseForge](https://www.curseforge.com/minecraft/mc-mods/hassium) 下载与 Minecraft 版本及加载器匹配的 JAR：

| 加载器 | 下载选择 |
| --- | --- |
| Fabric | 选择 Fabric 对应的 JAR |
| Forge | 选择 Forge 对应的 JAR |
| NeoForge | 选择 NeoForge 对应的 JAR |

## 安装步骤

1. 下载对应加载器的 JAR。
2. 将 JAR 放入客户端或专用服务器的 `mods/` 目录。
3. 启动游戏或服务器。配置文件会自动生成在以下位置：

| 端 | 配置文件 |
| --- | --- |
| 客户端 | `config/hassium/hassium-client.toml` |
| 专用服务器 | `config/hassium/hassium-server.toml` |

## 前置依赖

| 加载器 | 前置依赖 |
| --- | --- |
| Fabric | Fabric API |
| Forge | 无额外前置 |
| NeoForge | 无额外前置 |

## 客户端与服务器安装

| 安装方式 | 结果 |
| --- | --- |
| 客户端与服务器均安装 | 推荐；可启用协商压缩与缓存 |
| 仅客户端安装 | 可单独享受客户端缓存 |
| 仅服务器安装 | 未安装客户端模组的客户端默认仍可连接 |

## 配置

- Fabric：安装 **Mod Menu** 与 **Cloth** 后，在 Mod Menu 中打开配置。
- Forge / NeoForge：在模组列表中点击「配置」按钮；需要安装 **Cloth**。
- 也可以直接编辑 `config/hassium/hassium-client.toml` 或 `config/hassium/hassium-server.toml` 中的 TOML 配置。

## 首次启用存储

首次启用存储功能会改变世界存档格式。启用前请备份世界；相关处理方式见 [FAQ](FAQ)。

---

[← Home](Home) [→ Configuration](Configuration)
