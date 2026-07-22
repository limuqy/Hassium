# AGENTS.md

AI Agent 速查。完整背景见 [`CLAUDE.md`](CLAUDE.md)；多版本真相源见 [`docs/version-segments.md`](docs/version-segments.md)。

## 项目身份

Minecraft 1.20.1–1.21.11 多加载器模组（Fabric / Forge / NeoForge），ZSTD 优化存档与网络；九段适配单位见 version-segments。Forge 仅 **1.20.1 / 1.20.6**。

## 关键构建命令

```bash
./gradlew --no-daemon common:decompile
./gradlew --no-daemon common:compileJava          # 改 common 后先编
./gradlew --no-daemon fabric:compileJava
./gradlew --no-daemon forge:compileJava
./gradlew --no-daemon neoforge:compileJava
./gradlew --no-daemon build
./gradlew --no-daemon common:test
```

PowerShell：始终写 `"-Pmc_ver=1.20.1"`，否则 `1.20.1` 会被截成 `1`。

## 模块依赖

```
common/  ← 无 fabric/forge/neoforge import
  ↑
fabric/ | forge/ | neoforge/
```

业务逻辑进 `common`；加载器 API 进对应模块；跨版本差异进 `common/.../compat/`，禁止业务散落新 `#if MC_VER`。

## ServiceLoader

1. 接口：`common/.../platform/services/IXxxHelper.java`
2. 访问：`Services.XXX`
3. 实现：三端各一份
4. 注册：`META-INF/services/<接口 FQN>`（三端都要）

漏注册 → 运行时 `NoSuchElementException`，编译不过滤。

## Mixin（仅 common）

- 命名：`@Unique` + `hassium$` 前缀
- 存储相关：入口先查 `isStorageEnabled()`
- 网络相关：入口先查网络开关 + 握手状态
- 登记：`common/src/main/resources/hassium.mixins.json`
- 优先 `@Inject` cancellable，避免 `@Overwrite`

清单与规范：skill `hassium-mixin`。

## 配置红线

| 项 | 默认 | 注意 |
|----|------|------|
| `storage.enabled` | **true** | 改存档格式 → 提醒备份 |
| `network.enabled` | true | |
| `globalPacketCompression` | true | |
| `clientCache.enabled` | true | |
| `clientCache.sectionDeltaEnabled` | true | 过期分段增量 |
| `clientCache.viewDistanceExtensionEnabled` | true | 超视渲染（多人；≠ Bobby） |
| `debug.*` | false | 热路径用 `DebugLogger` |

存档格式 type **126**（非 127）；元数据推送字段为 **chunkHash**（非 inhabitedTime）。客户端磁盘 payload 为 **NBT（HBT1）**，非旧 packet 字节。

卖点（已实现）：**分段增量**、**超视渲染**、**光照剥离**、**光照缓存**、**`/hassiumc export` 世界导出**。

## Skills

| Skill | 用途 |
|-------|------|
| `hassium-dev` | 构建、模块、ServiceLoader、配置、包地图 |
| `hassium-storage` | 存储 / codec / Region / 字典 |
| `hassium-network` | 网络压缩、chunkHash 推送、限流、指标 |
| `hassium-mixin` | Mixin 清单与注入 |

## CurseForge 本地推送

- 配置：`curseforge_project_id`（`gradle.properties`）+ `CURSEFORGE_TOKEN`（环境变量，勿提交）
- 单版本：`./gradlew build publishCurseForge "-Pmc_ver=1.20.1"`
- 全版本：`./scripts/publish-curseforge.ps1`（干跑加 `-DryRun`）

## 文档

- [`docs/architecture.md`](docs/architecture.md)
- [`docs/chunk-cache.md`](docs/chunk-cache.md)
- [`docs/ovd.md`](docs/ovd.md)
- [`docs/disk-nbt-cache.md`](docs/disk-nbt-cache.md)
- [`docs/version-segments.md`](docs/version-segments.md)
- [`docs/mod-compat.md`](docs/mod-compat.md)
- [`docs/runtime-smoke-test.md`](docs/runtime-smoke-test.md)
