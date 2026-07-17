---
name: hassium-network
description: Hassium 网络与区块缓存推送技能。涉及握手、全局/通道 ZSTD、ServerChunkPushManager、ChunkHashS2C、ClientCacheLoadQueue、主线程预算/JoinBoost、黑名单、指标命令、section-delta 或 network/cache 包任务时使用。
---

# Hassium 网络与缓存推送

存储格式见 [[hassium-storage]]；拦截点见 [[hassium-mixin]]。流水线权威文档：`docs/chunk-cache.md`。

## 能力概览

| 能力 | 要点 |
|------|------|
| 自定义通道 | `hassium:*`，握手协商后启用 |
| 全局包压缩 | `ZstdPipelineSwitcher` 替换 Pipeline 中 Zlib |
| 上下文 / magicless / 聚合 | NetworkConfig 默认开启 |
| chunkHash 推送 | 替代已删除的 ChunkMetadata 旧协议 |
| 客户端缓存命中 | `ClientCacheLoadQueue` + 主线程时间预算 |
| 指标 | `/hassium stats`、`/hassiumc stats` |

## 现行推送流

```
Mixin 拦截 broadcast/trackChunk/PlayerChunkSender
  → pushPool 算 sectionHashes → chunkHash
  → 批量 ChunkHashS2C（控制面黑名单）
  → 命中：ClientCacheLoadQueue；未命中：全量 ChunkDataRequestC2S
  → onServerTick 主线程序列化 ≤ maxChunksPerTick → pushPool 压缩发送
  → 客户端 MainThreadBudget apply（JoinBoost ~5s）
```

**section-delta（阶段二）**：协议与 handler **保留**，生产 miss/mismatch **一律全量**。恢复前勿接回 `sendSectionHashRequest`。

已删除：`ChunkMetadataS2CPacket` / `sendMetadata*`。

## 关键类

| 类 | 职责 |
|----|------|
| `ServerChunkPushManager` | hash 批量、数据队列、tick、pushPool |
| `ClientMetadataHandler` | hash 比对、全量请求、（保留）delta |
| `ClientCacheLoadQueue` | 后台读缓存 |
| `ClientMainThreadBudget` / `MainThreadDispatcher` | 主线程 drain |
| `PacketCompressionBlacklist` | 控制面不进 PENDING 聚合 |
| `HassiumConnectionRegistry` | PENDING / ENABLED |
| `IndexSyncManager` / `NamespaceIndexManager` | 包 ID 索引同步 |
| `DictionaryManager` | 区块/聚合字典 |
| `NetworkStats` | 零分配指标 |

平台发送：`Services.NETWORK_MANAGER`（`INetworkManagerService`）。

## 限流配置

| 项 | 含义 |
|----|------|
| `maxChunksPerTick` | 每玩家每 **server tick** 序列化上限 |
| `mainThreadChunkBudgetMs` | 客户端每帧预算（默认 3；JoinBoost ~10） |
| `maxChunksPerFrame` / `maxCallbacksPerFrame` | 硬顶，非主限流 |
| `targetFPS` | **遗留**，不参与限流 |

## 日志

默认安静。热路径用 `DebugLogger`（`debug.networkLogging` / `metadataLogging` / `cacheLogging` 等，默认 false）。INFO 仅生命周期：初始化、字典加载、握手摘要、管道切换、断开清理。

## 命令

- 服务端：`/hassium stats`、`/hassium metrics on|off`、`/hassium stats reset`（OP 2）
- 客户端：`/hassiumc stats`
- `network.metricsEnabled=false` 时相关命令不可用

## 改动检查清单

1. 新控制面包 → 加入黑名单（`HassiumPacketIds` + 默认 compressionBlacklist）
2. 三端 NetworkManager / Payload 注册一致
3. 握手前勿拦截原版全量路径
4. 热路径禁止无条件 `LOGGER.info`
