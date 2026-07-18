---
name: hassium-mixin
description: Hassium Mixin 注入技能。在 common 模块为 RegionFile、Connection、ChunkHolder、ServerPlayer、ClientLevel 等添加/修改拦截，或登记 hassium.mixins.json 时使用。
---

# Hassium Mixin 技能

全部 Mixin 位于 `common/.../mixin/`，随加载器源码集合并编译（Fabric / Forge / NeoForge）。通用规则见 [[hassium-dev]]；存储读写见 [[hassium-storage]]；推送逻辑见 [[hassium-network]]。

## 约定

- 注入字段/方法：`@Unique` + `hassium$` 前缀
- Logger：`LoggerFactory.getLogger("Hassium/TargetClass")`
- 私有成员：`@Accessor` / `@Invoker`（如 `RegionFileAccessor`、`ClientLevelAccessor`）
- 优先 `@Inject` + `cancellable = true`，避免 `@Overwrite`
- 登记：类简名写入 `common/src/main/resources/hassium.mixins.json`（`mixins` 或 `client`）

## Feature gate

| 类型 | 入口检查 |
|------|----------|
| 存储 | `HassiumConfigService.getInstance().isStorageEnabled()` |
| 网络推送 / 全局压缩 | `isNetworkCompressionEnabled()` + 握手/连接状态 |
| 客户端缓存写 | `isClientCacheEnabled()`（及 storage 就绪） |

## 已注册注入点

### 存储

| Mixin | 目标 | 职责 |
|-------|------|------|
| `MixinRegionFile` | `RegionFile` | type 126 ZSTD 读写 |
| `RegionFileAccessor` | `RegionFile` | Accessor |
| `MixinIOWorker` | `IOWorker` | 统计/日志 |
| `MixinChunkSerializer` | `ChunkSerializer` / `SerializableChunkData` | 统计（含 `#if`） |

### 网络 / 推送

| Mixin | 目标 | 职责 |
|-------|------|------|
| `MixinChunkHolder` | `ChunkHolder` | 拦截 broadcast → chunkHash |
| `MixinServerPlayer` | `ServerPlayer` | 拦截 trackChunk |
| `MixinPlayerChunkSender` | `PlayerChunkSender` | 1.20.2+；1.20.1 可为占位 |
| `MixinMinecraftServer` | `MinecraftServer` | tick：`onServerTick` + hash flush |
| `MixinConnection` | `Connection` | 聚合发送等 |
| `MixinConnectionSetupCompression` | `Connection` | 全局 ZSTD 替换 Zlib |
| `MixinServerGamePacketListenerImpl` | `ServerGamePacketListenerImpl` | 断开清理等 |

### 客户端

| Mixin | 目标 | 职责 |
|-------|------|------|
| `MixinClientLevel` | `ClientLevel` | 卸载写缓存 |
| `ClientLevelAccessor` | `ClientLevel` | Accessor |
| `MixinMinecraft` | `Minecraft` | 配置/生命周期 |
| `MixinClientTick` | `Minecraft` | 客户端 drain |
| `MixinClientPacketListener` | `ClientPacketListener` | 包处理 |
| `MixinClientCommonPacketListenerImpl` | `ClientCommonPacketListenerImpl` | 通用监听 |
| `MixinLightRecompute` | `ClientPacketListener` | lightStrip 后本地重算（propagate + 邻区边界拉取）与每帧限流 |
| `MixinLevelRenderer` | `LevelRenderer` | 渲染相关 |

当前共 **19** 个已注册类（以 `hassium.mixins.json` 为准）。

## 添加流程

1. 用目标 MC 的 sources / mappings 核对方法签名（Mojang 名）
2. 新建 Mixin，加 gate 与 `hassium$` 命名
3. 跨版本签名差异：`#if` 或抽到 `compat/`
4. 写入 `hassium.mixins.json`
5. 编译验证：

```bash
./gradlew --no-daemon common:compileJava
./gradlew --no-daemon fabric:compileJava forge:compileJava neoforge:compileJava
# 锚点冒烟（PowerShell 引号）：
./gradlew --no-daemon fabric:compileJava "-Pmc_ver=1.20.1"
./gradlew --no-daemon neoforge:compileJava "-Pmc_ver=1.21.1"
```

映射错误多在运行时暴露，编译通过不等于注入成功。

## 常见坑

- Connection `send` 在 1.21.6+ 监听器类型变更 → 见 `MixinConnection` / version-segments
- static 字段泄漏玩家状态 → 用弱引用或连接级 registry
- 同一逻辑勿在 ChunkHolder + ServerPlayer 重复发送（核对拦截点）
- 1.20.1 无 `PlayerChunkSender` 时保留空 mixin 以满足 json 注册需要

## 调试

热路径日志走 `DebugLogger`（`debug.*`）。注入失败查 refmap / 目标方法名 / 是否进 mixins.json。
