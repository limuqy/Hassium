# 区块缓存推送与进服加载

本文档是 **chunkHash 元数据推送 + 客户端缓存命中** 流水线的唯一真相源。存储文件格式见 [`architecture.md`](architecture.md)。

## 1. 目标与约束

- 用 **内容哈希**（非 `inhabitedTime`）判断缓存是否可复用
- section 方块数据哈希排除会每 tick 变化的 blockEntity NBT
- blockEntity 不进缓存命中域：区块 apply 后再走专用请求
- 自 **1.21.5** 起：客户端缓存存当前 MC 的 chunk packet 线格式，**不保证跨 MC 大版本兼容**

## 2. 哈希

```
sectionHash = hash(section 方块 palette + 生物群系序列化字节)
chunkHash   = combineSectionHashes(sectionIndex → sectionHash)
```

实现：`ChunkContentHashUtil`。服务端与客户端算法一致。磁盘侧快速比对可读 Region **MetadataTable** 中的 64-bit `contentHash`（不解压）。

## 3. 现行数据流

> **阶段二 section-delta：代码保留，生产路径禁用。** miss / mismatch **一律全量** `ChunkDataRequestC2S`。

### 服务端

```
ChunkHolder.broadcast / ServerPlayer.trackChunk / PlayerChunkSender
        │  (握手后 Mixin 拦截，cancel 原版全量包)
        ▼
pushPool: computeSectionHashes → combine → chunkHash
        ▼
短窗口批量 sendChunkHash（≤16 entries 或约 10ms）
        ▼  ChunkHashS2C（控制面黑名单）
客户端 miss → ChunkDataRequestC2S
        ▼
enqueueDataRequest（距离优先）
        ▼
onServerTick（真实 server tick 限流）:
  主线程: getChunk + serialize ≤ maxChunksPerTick
  pushPool: ZSTD + ChunkPayloadS2C
```

### 客户端

```
ChunkHashS2C
        │
storage 未就绪 → 暂存；就绪后批量比对（超时约 2s 回退全量）
        │
读取 sectionHashes → combine → cachedChunkHash
        │
   ┌────┴────┐
 匹配       不匹配 / miss
   │            │
CacheLoadQueue  ChunkDataRequestC2S（全量）
   │            │
后台磁盘+解压   ChunkPayload → 后台解压
   └────┬────┘
主线程时间预算 apply（JoinBoost：进服约 5s 提高预算）
        │
apply 后再请求 blockEntity
```

## 4. 主线程限流

| 机制 | 说明 |
|------|------|
| `mainThreadChunkBudgetMs` | 每帧 apply/回调共享预算（默认 3ms） |
| JoinBoost | 进服约 5s 预算约 10ms |
| `maxChunksPerFrame` / `maxCallbacksPerFrame` | 安全硬顶（默认 32） |
| `targetFPS` | 遗留，**不参与**限流 |

控制面包（hash / 握手 / index sync 等）在 `PacketCompressionBlacklist`，避免进 PENDING 聚合窗口。

## 5. 协议（阶段一 / 阶段二）

### 阶段一（现行）

```java
ChunkHashS2CPacket(dimension, List<Entry>)
// Entry(chunkX, chunkZ, chunkHash, sectionBitmap)
```

### 阶段二（保留、暂禁用）

```java
SectionHashRequestC2SPacket  // 客户端 → 服务端
SectionDeltaS2CPacket        // 服务端 → 客户端（变更 section + BE）
```

生产比对在 `ClientMetadataHandler.compareChunkHashes`：命中走缓存队列，否则全量请求。`sendSectionHashRequest` 无生产调用方。

旧 `ChunkMetadataS2C`（contentHash 批量元数据）协议已删除。

## 6. 关键组件

| 组件 | 职责 |
|------|------|
| `ServerChunkPushManager` | hash 批量、数据队列、tick 序列化、pushPool |
| `MixinChunkHolder` / `MixinServerPlayer` / `MixinPlayerChunkSender` | 拦截原版全量推送 |
| `ClientMetadataHandler` | hash 比对、全量请求、（保留）delta handler |
| `ClientCacheLoadQueue` | 后台读缓存 |
| `ClientMainThreadBudget` + `MainThreadDispatcher` | 主线程时间预算 drain |
| `ChunkBloomFilter` | 减少无效磁盘 IO |
| `ClientHassiumStorage` / `HassiumRegionFile` | 客户端 Region 缓存 |
| `ClientCacheDatabase` | 仅热度 / LRU，不参与命中 |

## 7. 客户端淘汰

SQLite 维护访问热度与大小；超过 `maxSizeMb` / `maxAgeDays` 时按评分清理。命中路径 **不查 SQL**。

## 8. 调试

默认无热路径 INFO。排查时打开 `config/hassium/hassium.json` 的 `debug.metadataLogging` / `debug.networkLogging` / `debug.cacheLogging` 等（见 architecture）。运行时统计：`/hassiumc stats`。

## 9. 待实现

- 方向性区块预加载（提高推送优先级，不改变协议）
- 恢复 section-delta：需可靠 merge + 集成测试后再接回 miss 路径
