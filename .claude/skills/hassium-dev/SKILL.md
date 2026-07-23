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

## 修复红线

改 bug、过编译、过冒烟时 **硬约束**（违反即错误修复）：

1. **功能不可降级**  
   禁止为“先绿再说”而关闭/削弱已实现能力，例如关掉 `lightCache`、`sectionDelta`、`viewDistanceExtension`、`globalPacketCompression`，或改默认配置、放宽协议/缓存语义。必须在功能完整前提下修根因。

2. **已验证路径不可覆盖**  
   多版本/多加载器兼容时，禁止改写、折叠、删除已在旧版本验证通过的代码路径。版本差异用 `#if MC_VER` 或 `compat/` **叠加隔离**；老路径保持行为不变。新版本适配不得“统一重写”吞掉旧锚点逻辑。

**禁止借口：**

| 借口 | 现实 |
|------|------|
| “关掉 X 测试就过了” | 测试绿 ≠ 功能在；关功能不算修复 |
| “先统一写法再调旧版” | 旧版已验证路径是基线，只能旁路适配 |
| “这个版本特殊所以重写整段” | 用版本门/compat 包住差异，不覆盖公共已通过路径 |
| “命中率差一点可以接受，先关缓存” | 卖点功能不得以指标或便利为由下线 |

## 常用命令

```bash
./gradlew --no-daemon common:decompile
./gradlew --no-daemon common:compileJava
./gradlew --no-daemon fabric:compileJava forge:compileJava neoforge:compileJava
./gradlew --no-daemon :fabric:runClient
./gradlew --no-daemon common:test
./gradlew --no-daemon common:runJava -PmainClass=<FQN> -Pargs=a,b
```

## 运行时冒烟测试（dev 环境）

跨版本×加载器实跑验证：服务端 VD=20 → 客户端连服 → 10s 统计 → 断开 → 服务端切 VD=8 → 重连 → 10s 统计 → 退出。Java 侧 `ClientSmokeTest` / `ServerSmokeTest` 已集成在 mixin 与加载器入口点，仅 dev 生效（`-Dhassium.smokeTest=true`）。

```powershell
# 单会话
.\scripts\runtime-smoke-test.ps1 -Ver 1.20.1 -Loader fabric -Phase I -SessionId "1.20.1_fabric_I"
# 全量初始轮（17 版 × 2 加载器，约 4–6 小时）
.\scripts\runtime-smoke-test-batch.ps1 -Phase I
# 并行跑全量初始轮（fabric+neoforge 同时，约 20–30 分钟）
.\scripts\runtime-smoke-test-batch.ps1 -Phase I -Parallel
# 指定版本回归轮
.\scripts\runtime-smoke-test-batch.ps1 -Phase R -Versions @("1.20.1","1.21.11")
```

关键真相源（避免重蹈覆辙）：
- **Loom runDir 在子项目目录**：`fabric/run/client/`、`neoforge/run/client/`，不是根目录 `run/`
- **缓存清理要删整个 `hassium_cache` 目录** + `config/hassium/`，不是单个 `heat.idx`
- **batch CleanWorld 策略**：每个 loader 首轮清档，后续默认复用；退版本与失败重试强制清档
- **`$projectRoot` 由 `$PSScriptRoot` 推导**，脚本不依赖工作目录
- **并行模式 `-Parallel`**：fabric 用 25565、neoforge 用 25566，版本间仍串行；至少 16G RAM

输出：`build/smoke-test/{logs,stats,results}/`；退出码 0=PASS / 2=FAIL / 3=server_not_ready。完整说明见 `docs/runtime-smoke-test.md`。

## CurseForge 本地推送

1. `gradle.properties` 填 `curseforge_project_id`（CF 项目数字 ID）
2. Token：`$env:CURSEFORGE_TOKEN=...` 或本机 `~/.gradle/gradle.properties` 的 `curseforge_token=`（勿提交）
3. 单版本：`./gradlew --no-daemon build publishCurseForge "-Pmc_ver=1.20.1"`
4. 全版本：`./scripts/publish-curseforge.ps1`（或 `./gradlew publishCurseForgeAll`）
5. 干跑：`./scripts/publish-curseforge.ps1 -DryRun` 或 `-Pcurseforge_debug=true`
6. 仅锚点+1.20.6：`-AnchorsOnly`

## 文档

- `docs/architecture.md` — 架构与配置
- `docs/chunk-cache.md` — 缓存流水线
- `docs/version-segments.md` — 九段
- `CLAUDE.md` / `AGENTS.md` — 入口
