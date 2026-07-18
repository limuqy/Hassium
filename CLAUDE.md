# CLAUDE.md

Hassium 开发者全景索引。任务级操作见 `.claude/skills/`；多版本真相源见 [`docs/version-segments.md`](docs/version-segments.md)。

## 项目身份

Minecraft 多加载器模组（Fabric / Forge / NeoForge），Manifold `#if MC_VER` 单套源码覆盖 **1.20.1–1.21.11**。用 ZSTD 优化存档与网络；客户端以 `chunkHash` 命中本地 Region 缓存。

- 构建：architectury-loom + `versionProperties/{mc_ver}.properties`
- 默认 `mc_ver`：见根 `gradle.properties`（PowerShell 覆盖时必须 `"-Pmc_ver=1.20.1"`）
- Forge **仅** 1.20.1 / 1.20.6；1.21+ 用 NeoForge

## 构建命令

```bash
./gradlew --no-daemon common:decompile          # 首次 / 缺反编译产物
./gradlew --no-daemon common:compileJava        # 改 common 后先编
./gradlew --no-daemon fabric:compileJava forge:compileJava neoforge:compileJava
./gradlew --no-daemon build
./gradlew --no-daemon :fabric:runClient | :forge:runServer | :neoforge:runClient
./gradlew --no-daemon scanVersionBoundaries
./gradlew --no-daemon compileAnchors
./gradlew --no-daemon common:test
```

## 模块与包

```
common/  ← 无加载器 API；源码合并进各 loader 编译
fabric/ forge/ neoforge/  ← ServiceLoader 实现 + 入口
```

| 包 | 职责 |
|----|------|
| `storage/` | type 126、`ChunkPayloadCodec`、`HassiumRegionFile`、`MetadataTable` |
| `compression/` | codec / 字典 |
| `network/` | 握手、Pipeline、聚合、`ServerChunkPushManager`、chunkHash |
| `cache/` | 客户端缓存、Bloom、淘汰 |
| `config/` `metrics/` `compat/` `mixin/` | 配置、指标、跨版本桥、Mixin |
| `migration/` `api/` | 未实现桩 |

## 配置默认（须与代码一致）

- `storage.enabled = true`（备份世界）
- `network.enabled = true`，`globalPacketCompression = true`
- `clientCache.enabled = true`
- `debug.* = false`（热路径经 `DebugLogger`）

## 区块缓存流水线（现行）

```
拦截 broadcast/trackChunk → 异步算 chunkHash → 批量 ChunkHashS2C
  → 命中 ClientCacheLoadQueue / 未命中全量请求
  → 主线程序列化 + pushPool 压缩 → 客户端时间预算 apply（JoinBoost）
```

**section-delta 阶段二保留但生产禁用**（miss 一律全量）。详见 [`docs/chunk-cache.md`](docs/chunk-cache.md)。

## 已完成 / 待办

**已完成（摘要）：** ZSTD 存储与网络、握手、chunkHash 推送、进服限流、Bloom、sectionHashes、BE 专用通道、监控命令、九段适配回归。

**待办：** 方向性预加载；恢复可靠的 section-delta merge；`migration` / 公共 `HassiumApi` 实现。

## 文档与 Skills

| 文档 | 用途 |
|------|------|
| [`docs/architecture.md`](docs/architecture.md) | 架构 / 存储 / 配置 / 日志 / 命令 |
| [`docs/chunk-cache.md`](docs/chunk-cache.md) | 缓存推送真相源 |
| [`docs/version-segments.md`](docs/version-segments.md) | 九段 / `#if` 白名单 / compat |
| [`docs/mod-compat.md`](docs/mod-compat.md) | 多 Mod 兼容边界与配置逃生 |
| [`AGENTS.md`](AGENTS.md) | Agent 速查 |

| Skill | 触发 |
|-------|------|
| `hassium-dev` | 模块、构建、ServiceLoader、配置 |
| `hassium-storage` | Region / type 126 / codec / 字典 |
| `hassium-network` | 握手、压缩管线、chunkHash、限流、指标 |
| `hassium-mixin` | Mixin 清单与注入规范 |
