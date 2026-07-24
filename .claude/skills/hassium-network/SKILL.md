---
name: hassium-network
description: Hassium 网络与区块缓存推送技能。涉及握手、管线级/通道 ZSTD、包聚合、紧凑包头（CompactHeader）、SkipAware 跳过、ServerChunkPushManager、ChunkHashS2C、ClientCacheLoadQueue、主线程预算/JoinBoost、黑名单、指标命令、分段增量（section-delta）或 network/cache 包任务时使用。
---

# Hassium 网络与缓存推送

存储格式见 [[hassium-storage]]；拦截点见 [[hassium-mixin]]。流水线权威文档：`docs/chunk-cache.md`。管线 ZSTD + 聚合实现设计：`docs/superpowers/specs/2026-07-22-network-implementation-design.md`（以代码为准，见下文「实现要点」）。

## 能力概览

| 能力 | 要点 |
|------|------|
| 自定义通道 | `hassium:*`，握手协商后启用 |
| 管线级 ZSTD | `ZstdPipelineSwitcher` 用 `SkipAwareZstdEncoder` + `ZstdContextDecoder` 替换 Pipeline 中 Zlib |
| 应用层聚合 | `MixinConnection` → `HassiumAggregationManager`；聚合包内部字典 ZSTD |
| 紧凑包头 | 聚合子包用两级 VarInt 索引代替 `ResourceLocation` 字符串（`CompactHeaderCodec`） |
| 聚合跳过管线 | Channel 属性 `HassiumPipelineAttributes.SKIP_PIPELINE_COMPRESSION`（非 ByteBuf.attr） |
| magicless / 上下文 | `magiclessZstd`、`useContextCompression` 默认开 |
| chunkHash 推送 | 替代已删除的 ChunkMetadata 旧协议 |
| 客户端缓存命中 | `ClientCacheLoadQueue` + 主线程时间预算 |
| 光照剥离 | `lightStripEnabled`：发包不带 LightData；客户端 `MixinLightRecompute` 本地重算 |
| 指标 | `/hassium stats`、`/hassiumc stats` |

## 双层压缩架构

```
Connection.send()
    │
    ├─ 管线级 ZSTD 已安装？
    │   ├─ 否 → 原版 Zlib（或 pause 后的未压缩帧）
    │   └─ 是 → Channel.attr(SKIP_PIPELINE_COMPRESSION)？
    │       ├─ true → 写 VarInt(0)+raw（聚合包内部已字典 ZSTD）
    │       └─ false → SkipAwareZstdEncoder / ZstdContextEncoder
    │
    └─ MixinConnection 拦截（仅服务端 ServerGamePacketListenerImpl）
        └─ 聚合开关 + 连接 isActive + 非黑名单？
            ├─ 否 → 直接发送（仍可走管线 ZSTD）
            └─ 是 → HassiumAggregationManager 缓冲 → 字典 ZSTD 聚合包
```

### 模式矩阵

| 场景 | 管线级 ZSTD | 应用层聚合 | 效果 |
|------|-------------|-----------|------|
| 聚合开 + 管线开 | 非聚合包走管线 ZSTD | 聚合包内部字典 ZSTD | 全覆盖；聚合包 skip 管线 |
| 聚合关 + 管线开 | 所有包走管线 ZSTD | 无 | 小包也受益 |
| 管线关 | 原版 Zlib | 聚合包内部字典 ZSTD | 仅应用层 |
| 客户端不支持 ZSTD | 保持 Zlib / 不装管线 | 不进 PENDING 聚合 | 向后兼容 |

## 握手与管线安装（现行实现）

设计文档偏「setupCompression 时切 ZSTD」；**现行代码以主动安装 + 未压缩安全窗口为主**，`MixinConnectionSetupCompression` 为协商后再次 `setupCompression` 的兜底。

```
客户端 → 服务端: HandshakeC2S
  (protocolVersion, algorithms, clientCache…, globalPacketCompressionSupported, compactHeaderSupported)

服务端 completeServerHandshake / handleHandshake:
  useGlobal = serverConfig.globalPacketCompression && clientSupport
  useCompact = serverConfig.enableCompactHeader && clientSupport
  if (useGlobal):
    DictionaryManager.init()
    IndexSyncManager.initializeServerIndex()
    pauseOutboundCompression(channel)   // 阈值→MAX，发未压缩帧
  发送 HandshakeS2C(accepted, useGlobal, useCompact)
  if (!useGlobal): resyncTrackedChunks(player)   // 必须补发，否则缓存主链路不启动

客户端收 HandshakeS2C(useGlobal=true):
  switchToZstdWhenReady(channel)        // 管线未就绪则 EventLoop 短重试
    → markNegotiated
    → send CompressionReady C2S         // Forge 无此 ACK，见下

服务端收 CompressionReady（Fabric / NeoForge）:
  installServerZstdAfterClientReady:
    switchToZstdWhenReady → markNegotiated
    → send DictionarySync + IndexSync
    → markPending(connection)
    → HassiumAggregationManager.init()
    → resyncTrackedChunks
    → 5s PENDING 超时 demote + discard

客户端收 IndexSync:
  markEnabled(connection)（客户端侧）
  HassiumAggregationManager.init()
  （部分路径再发 CompressionReady）

服务端 PENDING→ENABLED：
  收到 CompressionReady 后 markEnabled → 聚合缓冲可 flush
```

### 加载器差异

| 加载器 | 要点 |
|--------|------|
| Fabric | 完整 ACK：`pause` → 客户端装 ZSTD + Ready → 服务端装 ZSTD → Dict/Index → PENDING → Ready 升 ENABLED |
| NeoForge | 同 Fabric 主路径；`tryInstallClientZstdPipeline` / `installServerZstdAfterClientReady` |
| Forge | **无 CompressionReady ACK**；握手后 `pause` + 延迟 `switchToZstdWhenReady`，装好后直接 Dict/Index + markPending + init；客户端 HandshakeS2C 后自行装管线 |

### 关键时序工具

| API | 用途 |
|-----|------|
| `ZstdPipelineSwitcher.pauseOutboundCompression` | 切换窗口内双方发 `VarInt(0)+明文`，Zlib/ZSTD 解码器均可吃 |
| `ZstdPipelineSwitcher.switchToZstdWhenReady` | 等 PacketDecoder/Encoder 就绪再 `switchToZstd`（默认约 25×200ms） |
| `ZstdPipelineSwitcher.switchToZstd` | 安装 `ZstdContextDecoder` + **`SkipAwareZstdEncoder`**（magicless 读自配置） |
| `ZstdNegotiationTracker.markNegotiated` | 标记已协商；`MixinConnectionSetupCompression` 依赖此状态 |
| `HassiumConnectionRegistry` | PENDING（缓冲不 flush）/ ENABLED（正常聚合） |

## 应用层聚合

- 入口：`MixinConnection.hassium$tryAggregate` — 仅服务端、非聚合包自身、非黑名单/高频、`isActive`、`isPacketAggregationEnabled()`、无 sendListener
- 参数：`init()` 从配置读 `aggregationMinBatchSize` / `aggregationMaxWaitTimeMs` / `aggregationMaxSize`
- flush 周期 20ms；超 `maxAggregationSize` 分批 `flushBatch`
- 发送前：`ZstdPipelineSwitcher.markSkipNextPipelineCompression(connection)`
- 断开：`MixinConnection` → `markDisabled` + `discardConnection` + `ZstdNegotiationTracker.removeChannel`

**实现注意：** 聚合包经 Packet 编码后原 ByteBuf 标记会丢，故 skip 使用 **Channel.attr**，`SkipAwareZstdEncoder` 在 encode 时 `getAndSet(false)`。设计稿中的 ByteBuf.attr / `SkipAwareZstdDecoder` **未采用**（解码侧不需要 skip）。

## 紧凑包头（Compact Header）

**作用范围：** 仅在**聚合包内部**子包标识符编码；**不**做独立 Pipeline Handler。线协议仍是「聚合包 payload」，子包头用索引代替完整 `namespace:path` 字符串。

### 线格式（子包）

```
AggregatedSubPacket:
  [identifier: CompactHeader] [length:VarInt] [data]
```

`CompactHeaderCodec` 格式（索引从 **1** 起，`0` = 未索引）：

| 模式 | 编码 |
|------|------|
| 已索引 | `[namespaceIndex:VarInt] [pathIndex:VarInt]` |
| 未索引 | `[0:VarInt] [identifier:Utf]` |

### 索引同步

```
服务端 IndexSyncManager.initializeServerIndex()
  → NamespaceIndexManager 注册原版 + 自定义包类型（namespace/path 两级）
  → IndexSyncPacket 序列化整表
  → S2C IndexSync
客户端 applyTo(client IndexManager)
  → 之后聚合子包 encode/decode 共用同一套索引
```

握手协商：`HandshakeC2S.compactHeaderSupported` × `server enableCompactHeader` → `HandshakeS2C.useCompactHeader`。配置项 `enableCompactHeader`（默认 `true`），读取：`HassiumConfigService.isCompactHeaderEnabled()`。

### 聚合包体（再包一层压缩）

```
HassiumAggregationPacket:
  子包列表先写 raw（count + 各 AggregatedSubPacket，含紧凑头）
  rawSize >= 32 且 useContextCompression？
    是 → flag(1=无字典 / 2=字典) + VarInt(rawSize) + magicless ZSTD（可选聚合字典）
    否 → flag(0) + raw
```

解码端用**客户端** `NamespaceIndexManager` 还原 identifier → 原版包走 `PacketCodecCompat.deserializeClientbound`，自定义走 `PacketPayloadCompat.createClientboundPayload`。

### 关键类

| 类 | 职责 |
|----|------|
| `CompactHeaderCodec` | 两级 VarInt 写/读 identifier |
| `NamespaceIndexManager` | namespace/path 索引表；`getIndex` / `getIdentifier`；vanilla id 映射 |
| `IndexSyncManager` / `IndexSyncPacket` | 服务端建表、握手同步到客户端 |
| `AggregatedSubPacket` | 子包：紧凑头 + body |
| `HassiumAggregationPacket` | 聚合帧 + 内部 ZSTD/字典 |

**注意：**

- 紧凑头**依赖 IndexSync 已应用**；未索引类型回退 Utf 全名，功能仍可用但体积变大
- 自定义 Payload 聚合时只抽 payload 体、不重写 channel id（id 已在紧凑头）
- 改包类型集合 / 注册时机 → 同步检查 `initializeServerIndex` 与三端 IndexSync 发送

## 现行推送流（chunkHash）

```
Mixin 拦截 broadcast/trackChunk/PlayerChunkSender
  → pushPool 算 sectionHashes → chunkHash
  → 批量 ChunkHashS2C（控制面黑名单）
  → 客户端 readChunkHash（MetadataTable / SectionHashStore）比对
  → 命中：ClientCacheLoadQueue；未命中：全量 ChunkDataRequestC2S
  → MISMATCH 且 sectionDeltaEnabled → SectionDeltaS2C 合并写盘再 apply
  → onServerTick 主线程序列化 ≤ maxChunksPerTick → pushPool 压缩发送
  → 客户端 MainThreadBudget apply（JoinBoost ~10s）
  → persist：contentHash=combine(sectionHashes) + SectionHashStore
```

**分段增量**：`clientCache.sectionDeltaEnabled`（默认 `true`）。开启时 MISMATCH → `SectionHashRequest` → NBT merge；关闭走全量。服务端按索引比对（空气 hash=0）；超视距进 `skipped` 并始终回包；客户端对 skipped/超时回退全量。

已删除：`ChunkMetadataS2CPacket` / `sendMetadata*`；`AggregatedZstdEncoder/Decoder`、`PacketAggregator`（管线聚合不再需要）。

## 关键类

| 类 | 职责 |
|----|------|
| `ZstdPipelineSwitcher` | pause / switchToZstd / WhenReady / markSkip / Zlib 回退 |
| `ZstdNegotiationTracker` | per-channel 协商状态 |
| `SkipAwareZstdEncoder` | 管线编码；支持下一帧 skip |
| `HassiumPipelineAttributes` | `SKIP_PIPELINE_COMPRESSION` Channel 键 |
| `ZstdContextEncoder` / `ZstdContextDecoder` | 上下文 + magicless 管线编解码 |
| `HassiumAggregationManager` | 缓冲、定时 flush、分批、字典聚合包 |
| `HassiumAggregationPacket` / `AggregatedSubPacket` | 聚合协议体（子包含紧凑头） |
| `CompactHeaderCodec` | 聚合内 identifier 两级 VarInt 编解码 |
| `HassiumConnectionRegistry` | PENDING / ENABLED |
| `MixinConnection` | 聚合拦截 + 断开清理 |
| `MixinConnectionSetupCompression` | 协商后 setupCompression → 切 ZSTD |
| `IndexSyncManager` / `NamespaceIndexManager` / `IndexSyncPacket` | 紧凑头索引建表与握手同步 |
| `DictionaryManager` | 区块/聚合字典 |
| `PacketCompressionBlacklist` | 控制面/独立通道不进聚合 |
| `ServerChunkPushManager` | hash 批量、数据队列、tick、pushPool；拦截时缓存包字节供 miss 复用 |
| `ClientMetadataHandler` | hash 比对、全量请求、section delta |
| `ClientCacheLoadQueue` | 后台读缓存 |
| `ClientMainThreadBudget` / `MainThreadDispatcher` | 主线程 drain |
| `NetworkStats` | 零分配指标 |
| `CompressionReadyPayload` | 客户端 ZSTD/压缩就绪 ACK |

平台发送：`Services.NETWORK_MANAGER`（`INetworkManagerService`）；各端 `Fabric/Forge/NeoForgeNetworkManager`。

## 配置（须与代码默认一致）

| 项 | 默认 | 含义 |
|----|------|------|
| `network.enabled` | true | 网络通道总开关（`isNetworkCompressionEnabled`） |
| `globalPacketCompression` | true | 管线级 ZSTD |
| `globalCompressionLevel` / `Threshold` | 3 / 256 | 管线级别与阈值 |
| `magiclessZstd` | true | 去 4B 魔数 |
| `useContextCompression` | true | 上下文压缩 |
| `enablePacketAggregation` | true | 应用层聚合 |
| `aggregationMinBatchSize` | 4 | 最小批量 |
| `aggregationMaxWaitTimeMs` | 20 | 最长等待 → wait cycles |
| `aggregationMaxSize` | 256KiB | 超限分批 |
| `enableCompactHeader` | true | 聚合内紧凑包头（两级 VarInt 索引）；握手协商 |
| `compressionBlacklist` | 控制面包集合 | 见 `ServerNetworkConfig.DEFAULT_COMPRESSION_BLACKLIST` |

Config 读取：`HassiumConfigService.isGlobalPacketCompressionEnabled()`、`isPacketAggregationEnabled()`、`getAggregation*()`、`isMagiclessZstd()`、`getGlobalCompressionLevel()` 等。

## 限流配置（推送）

| 项 | 含义 |
|----|------|
| `maxChunksPerTick` | 每玩家每 **server tick** 序列化上限 |
| `mainThreadChunkBudgetMs` | 客户端每帧预算（默认 15；JoinBoost ~30） |
| `maxChunksPerFrame` / `maxCallbacksPerFrame` | 硬顶，非主限流 |
| `maxLightRecomputePerFrame` | 客户端每帧光照重算区块上限；超额进溢出队列 |

## 日志

默认安静。热路径用 `DebugLogger`（`debug.networkLogging` / `metadataLogging` / `cacheLogging` 等，默认 false）。INFO 仅生命周期：初始化、字典加载、握手摘要、管线安装/切换、断开清理。

## 命令

- 服务端：`/hassium stats`、`/hassium metrics on|off`、`/hassium stats reset`（OP 2）
- 客户端：`/hassiumc stats`
- `network.metricsEnabled=false` 时相关命令不可用

## 改动检查清单

1. 新控制面包 → 加入黑名单（`HassiumPacketIds` + 默认 `compressionBlacklist`；聚合包自身亦在黑名单）
2. 新可聚合包类型 → 纳入 `NamespaceIndexManager` / `initializeServerIndex`，保证紧凑头可索引
3. 三端 NetworkManager / Payload 注册一致（含 Handshake、Dict/Index、CompressionReady、Aggregation）
4. 握手前勿拦截原版全量路径；`useGlobal=false` 时必须 `resyncTrackedChunks`
5. 装管线前优先 `pauseOutboundCompression`，装好后再 `markNegotiated` + 发压缩流量
6. 聚合发送必须 `markSkipNextPipelineCompression`，避免双重压缩
7. 断开路径清理 Registry / Aggregation / NegotiationTracker
8. 热路径禁止无条件 `LOGGER.info`
9. common 禁止 loader API；平台差异放 `*NetworkManager`

## 常见坑

- **客户端 stats 全 0**：握手未 `resyncTrackedChunks`（尤其 `globalPacketCompression=false` 路径）
- **一边 Zlib 一边 ZSTD 炸包**：缺少 pause 窗口或过早 markNegotiated
- **聚合包双重压缩**：漏 skip 标记，或误用 ByteBuf.attr（会丢）
- **Forge 无 Ready ACK**：不要照搬 Fabric 的双向 ACK 时序
- **管线未就绪**：用 `switchToZstdWhenReady`，勿假设 `setupCompression` 立刻再入
- **紧凑头解码失败 / 子包 type 错乱**：IndexSync 未到或双方索引表不一致；未注册类型应走 `0 + Utf` 回退
- **误加独立紧凑头 Pipeline Handler**：设计明确「仅聚合内部」；`enableCompactHeader` 不对应管线 handler
