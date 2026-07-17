---
name: hassium-dev
description: Hassium 项目开发通用技能。涉及跨平台(common/fabric/forge/neoforge)多版本(1.20.1-1.21.11)代码改动、构建/运行/调试命令、新增服务接口(ServiceLoader)、配置项、或不确定改动该放哪个模块时使用。
---

# Hassium 开发通用技能

多加载器（Fabric / Forge / NeoForge）+ Manifold 九段多版本。存储/压缩细节见 [[hassium-storage]]；网络与缓存推送见 [[hassium-network]]；Mixin 见 [[hassium-mixin]]。版本分界真相源：`docs/version-segments.md`。

## 模块与 builds_for

```
common/           ← 无加载器 import；源码合并进各 loader 编译
fabric/ forge/ neoforge/
buildSrc/         ← loom + manifold
versionProperties/← 每版本 builds_for、依赖
```

- 例：1.20.1 → `fabric,forge,neoforge`；多数 1.21.x → `fabric,neoforge`
- **Forge 仅 1.20.1 与 1.20.6**；1.21+ 用 NeoForge
- NeoForge 1.20.1 特殊：`loom.platform = 'forge'`（见 settings.gradle）

改动归属：业务/算法 → `common`；加载器 API → 对应模块；跨版本 API → `common/.../compat/`（禁止业务散落新 `#if`）。

## 功能域地图

| 域 | 包 | Skill |
|----|-----|-------|
| 存储 / Region | `storage/` `compression/` | hassium-storage |
| 网络 / 推送 / 缓存流水线 | `network/` `cache/` | hassium-network |
| Mixin | `mixin/` | hassium-mixin |
| 配置 / 指标 / 平台 | `config/` `metrics/` `platform/` | 本 skill |

元数据推送字段为 **chunkHash**（`ChunkHashS2CPacket`），不是 inhabitedTime。磁盘压缩 type **126**。

## ServiceLoader

1. 接口：`common/.../platform/services/IXxxHelper.java`
2. `Services` 中 `load(...)`
3. fabric / forge / neoforge 各实现一份
4. 三端 `META-INF/services/<接口 FQN>` 各写一行实现类名

漏注册 → 运行时失败。客户端专用服务用懒加载（参考 `getClientChunkApplier()`）。

## 多版本（Manifold）

- 索引与 `MC_VER`：构建生成 `build.properties`（勿手改）
- 合法分界常量白名单见 `docs/version-segments.md`
- PowerShell：`"-Pmc_ver=1.20.1"`（必须加引号）

```bash
./gradlew --no-daemon scanVersionBoundaries
./gradlew --no-daemon compileAnchors
./gradlew --no-daemon <loader>:compileJava "-Pmc_ver=<锚点>"
```

compat 类索引（完整表见 version-segments）：`PacketPayloadCompat`、`ResourceLocationCompat`、`RegistryCompat`、`DisconnectCompat`、`PermissionCompat`、`PlayerCompat`、`BlockEntityCompat`、`LevelChunkSectionCompat`、`CompoundTagCompat`、`ChunkPacketDataCompat`、`ChunkDataCompat`、`NetworkCapability`、`PacketCodecCompat`。

## 配置 gate

| Gate | 方法 | 默认 |
|------|------|------|
| 存储 | `isStorageEnabled()` | **true**（改存档 → 提醒备份） |
| 网络通道 | `isNetworkCompressionEnabled()` | true |
| 客户端缓存 | `isClientCacheEnabled()` | true |
| 调试日志 | `DebugLogger` + `debug.*` | 全 false |

`zstd-jni` 已在 `common/build.gradle`；注意 native 在两加载器 jar-in-jar 打包。

## 常用命令

```bash
./gradlew --no-daemon common:decompile
./gradlew --no-daemon common:compileJava
./gradlew --no-daemon fabric:compileJava forge:compileJava neoforge:compileJava
./gradlew --no-daemon :fabric:runClient
./gradlew --no-daemon common:test
./gradlew --no-daemon common:runJava -PmainClass=<FQN> -Pargs=a,b
```

## 文档

- `docs/architecture.md` — 架构与配置
- `docs/chunk-cache.md` — 缓存流水线
- `docs/version-segments.md` — 九段
- `CLAUDE.md` / `AGENTS.md` — 入口
