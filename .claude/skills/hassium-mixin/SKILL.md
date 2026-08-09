---
name: hassium-mixin
description: Hassium Mixin 注入技能。在 common 模块为 RegionFile、Connection、ChunkHolder、ServerPlayer、ClientLevel 等添加/修改拦截，或登记 hassium.mixins.json 时使用。
---

# Hassium Mixin 技能

全部 Mixin 位于 `common/.../mixin/`，随加载器源码集合并编译（Fabric / Forge / NeoForge）。通用规则见 [[hassium-dev]]；存储读写见 [[hassium-storage]]；网络核心/推送逻辑见 [[hassium-network]]。

## 约定

- 注入字段/方法：`@Unique` + `hassium$` 前缀
- Logger：`LoggerFactory.getLogger("Hassium/TargetClass")`
- 私有成员：`@Accessor` / `@Invoker`（如 `RegionFileAccessor`、`ClientLevelAccessor`、`OptionsAccessor`）
- 优先 `@Inject` + `cancellable = true`，避免 `@Overwrite`
- 登记：类简名写入 `common/src/main/resources/hassium.mixins.json`（`mixins` 或 `client` 段）

## Feature gate

| 类型 | 入口检查 |
|------|----------|
| 存储 | `HassiumConfigService.getInstance().isStorageEnabled()` |
| 网络推送 / 全局压缩 | `isNetworkCompressionEnabled()` + 握手/连接状态 |
| 客户端缓存写 | `isClientCacheEnabled()`（及 storage 就绪） |

## 已注册注入点（以 `hassium.mixins.json` 为准，2026-08-09 核：mixins 17 + client 12 = **29** 类）

### 存储域

| Mixin | 目标 | 职责 |
|-------|------|------|
| `MixinRegionFile` | `RegionFile` | type 126 ZSTD 读写 |
| `RegionFileAccessor` | `RegionFile` | Accessor |
| `MixinIOWorker` | `IOWorker` | 统计/日志 |
| `MixinChunkSerializer` | `ChunkSerializer` / `SerializableChunkData` | 统计（含 `#if`） |

### 区块核心（服务端侧推送 + 影子端）

| Mixin | 目标 | 职责 |
|-------|------|------|
| `MixinChunkHolder` | `ChunkHolder` | 拦截 broadcast → chunkHash；增量 light 包拦截转 `LightDeltaS2C` |
| `MixinServerPlayer` | `ServerPlayer` | 拦截 trackChunk（1.20.1；1.20.2+ 由 PlayerChunkSender 承担） |
| `MixinPlayerChunkSender` | `PlayerChunkSender` | 1.20.2+ 拦截 `sendChunk`，替代 trackChunk 注入；1.20.1 挂空壳到 `MinecraftServer` 以满足 json 注册 |
| `MixinMinecraftServer` | `MinecraftServer` | tick TAIL：`MainThreadDispatcher.flushServer` + 按真实 tick 限流序列化区块 + 冲刷 ChunkHash 批次（仅 dedicated）+ `DataPlaneUdpServer` / `GatewayServer` 生命周期（bind/tick/stop） |
| `MixinChunkAccess` | `LevelChunk` | pristine 跟踪修改标记（SeedGen Phase 1）：`setBlockState` 移除 pristine 登记，服务端不再发 SeedRef |
| `MixinLightDataWrite` | `ClientboundLightUpdatePacketData` | 实测出站 chunk 包光照线格式字节数，校准 `NetworkStats.ESTIMATED_LIGHT_BYTES` 与 `lightStrip` 带宽决策 |
| `LightUpdatePacketAccessor` | `ClientboundLightUpdatePacket` | 反射辅助（字段名跨版本不同）；字段提取逻辑在 `MixinChunkHolder` |
| `ChunkMapAccessor` | `ChunkMap` | 磁盘加载访问器：影子端读盘（缓存命中 / OVD 环带回填）复用官方 `scheduleChunkLoad` 完整链（`< MC_1_21_2` 返回 `Either`；1.21.2+ 返回 `CompletableFuture`） |
| `ServerLevelAccessor` | `ServerLevel` | `entityManager` 访问器：影子端实体通道（`updateChunkStatus` 置 ENTITY_TICKING + `saveAll` 落盘 `entities/`） |

### 主控核心（服务端网络与网关接入）

| Mixin | 目标 | 职责 |
|-------|------|------|
| `MixinConnection` | `Connection` | `hassium$tryAggregate`（仅 `ServerGamePacketListenerImpl` 生效）+ C2S 通路截获 `hassium$routeC2S`（send HEAD，可 cancel）+ 断开清理 |
| `MixinConnectionGatewayServer` | `Connection` | 网关 Connection 桥：`sendPacket` HEAD 拦截 → 网关帧路由；channel/address accessor（`GatewayPlayerBridge` 创建假 Connection 时注入 EmbeddedChannel）；sendPacket 描述符按锚点分段（1.20.1 两参 / 1.20.2–1.21.5 三参 / 1.21.6+ ChannelFutureListener） |
| `MixinConnectionSetupCompression` | `Connection` | 协商后 `setupCompression` → 切换管线 ZSTD（ZstdPipelineSwitcher） |
| `MixinServerGamePacketListenerImpl` | `ServerGamePacketListenerImpl` | per-player 控制活动 / 断连记录（数据面 permit 判定链）、断开清理 |

### 客户端（区块核心侧：缓存 / 超视 / 光照 / 生命周期）

| Mixin | 目标 | 职责 |
|-------|------|------|
| `MixinMinecraft` | `Minecraft` | 断连清理（`cleanupOnDisconnect` HEAD）+ 最终清理（`finalizeDisconnectIfTerminal` TAIL）；维度切换刷新缓存保存队列 |
| `MixinClientPacketListener` | `ClientPacketListener` | 初始化缓存系统、断开清理（1.20.1）；元数据逻辑在 `ClientMetadataHandler` |
| `MixinClientCommonPacketListenerImpl` | `ClientCommonPacketListenerImpl` | 1.20.2+ 断开清理（onDisconnect 上移后在此注入）；1.20.1 挂空壳到 `Minecraft` 以满足 json 注册 |
| `MixinClientLevel` | `ClientLevel` | 卸载写缓存 |
| `ClientLevelAccessor` | `ClientLevel` | Accessor |
| `MixinLevel` | `Level` | 跳过 renderOnly（超视渲染）区块的 blockEntity tick（`tickBlockEntity` HEAD cancel；`require = 0` 跨版本静默不注入） |
| `MixinClientTick` | `Minecraft` | 每帧更新视距扩展服务、按时间预算应用区块、刷新回调；takePendingUdpStart 周期驱动 UDP 数据面 |
| `MixinOptions` | `Options` | 解除 `getEffectiveRenderDistance()` 服务端钳制 → `min(客户端 RD 滑块, clientCache.maxRenderDistance)`，扩大 ViewArea（仅多人 + clientCache.enabled + viewDistanceExtensionEnabled） |
| `OptionsAccessor` | `Options` | `serverRenderDistance` 字段访问器（`ViewDistanceExtensionService` 环带计算用） |
| `LevelLightEngineAccessor` | `LevelLightEngine` | 访问内部 sky/block engine，失败后清空 residual light queue |
| `LightEngineAccessor` | `LightEngine` | 访问 deferred 队列；`runLightUpdates` NPE 后清残留防崩溃 |
| `ThreadedLevelLightEngineAccessor` | `ThreadedLevelLightEngine` | 服务端光照任务队列访问器（影子端收敛判定：同时检查 `lightTasks` 空） |

> 已退役（不再登记，改代码前若在别处看到引用按已删处理）：光照本地重算、渲染相关、旧 UI 冻结套件（`recoveryFreeze` 键保留但无 UI 消费）。

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

- Connection `send` 在 1.21.6+ 监听器类型变更 → 见 `MixinConnection` / `docs/version-segments.md`
- static 字段泄漏玩家状态 → 用弱引用或连接级 registry
- 同一逻辑勿在 ChunkHolder + ServerPlayer 重复发送（核对拦截点：1.20.1 trackChunk / 1.20.2+ PlayerChunkSender 二选一）
- 1.20.1 无 `PlayerChunkSender` / `ClientCommonPacketListenerImpl` 时保留空壳到 `MinecraftServer` / `Minecraft`，以满足 json 注册需要
- 注入抽象方法会失败（asm insnNode null）→ 注入具体实现类（如 `MixinChunkAccess` 注入 `LevelChunk` 而非 `ChunkAccess`）
- `hassium.refmap.json`：Loom 生成的 Mixin 名映射表；发行 jar 必需。`runClient` 下 WARN「could not be read」通常可忽略（详见 [`docs/mod-compat.md`](../../../docs/mod-compat.md) §10）

## 调试

热路径日志走 `DebugLogger`（`debug.*`）。注入失败查 refmap / 目标方法名 / 是否进 mixins.json。
