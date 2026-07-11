# 区块缓存系统改造技术文档

**开发日期**：2026-07-05

**目标**：简化客户端区块缓存逻辑，改为服务端主动推送元数据，客户端自主决策

## 1. 改造背景

原有实现存在以下问题：
- 缓存查询流程复杂：登录时发送批量查询 → 服务端返回决策 → 客户端处理
- 多套队列并存：MixinServerPlayer 的 HashMap、ChunkSendQueue 的 PriorityBlockingQueue
- 主线程阻塞风险：区块序列化、压缩都在主线程完成
- 状态管理复杂：waitingForCacheQuery、pendingDecisions、pendingChunks 等状态
- 缓存时间戳不准确：收到即保存，后续方块更新导致缓存立即过期
- 元数据读取性能差：需要读取整个 HassiumEnvelope 才能获取时间戳
- 客户端/服务端 RegionFile 实现不统一，存在重复逻辑

## 2. 新架构设计

### 2.1 数据流

```
服务端                              客户端
──────                              ──────
ChunkHolder.broadcast()             收到 ChunkMetadataS2C
ServerPlayer.trackChunk()               ↓
    ↓                               直接比对本地缓存（读取元数据表）
对装mod的客户端:                       ┌────────┴────────┐
  发送元数据(位置+时间戳) ────────→  缓存命中         缓存未命中
对原版客户端:                              ↓               ↓
  原版发送                          加载队列         发送数据请求
                                        ↑               ↓
                                    收到 ChunkDataS2C ← 服务端收到请求
                                        ↓               ↓
                                    线程池加载        数据请求队列
                                        ↓          (PriorityBlockingQueue)
                                    主线程应用           ↓ (线程池)
                                                     压缩+发送
                                        ↓
                                    ... 玩家移动、方块更新 ...
                                        ↓
                                    区块卸载时
                                        ↓
                                    构造 packet → 序列化 → 保存缓存
```

### 2.2 核心组件

| 组件 | 位置 | 职责 |
|------|------|------|
| ServerChunkPushManager | 服务端 | 管理数据请求队列 + 线程池 |
| ClientCacheLoadQueue | 客户端 | 管理区块加载队列 + 线程池 |
| ChunkMetadataS2CPacket | 网络 | 服务端→客户端区块元数据 |
| ChunkDataRequestC2SPacket | 网络 | 客户端→服务端请求区块数据 |
| **HassiumRegionFile** | **通用** | **统一的 region 文件实现** |
| **MetadataTable** | **通用** | **区块时间戳快速读取** |
| ClientHassiumStorage | 客户端 | 使用 HassiumRegionFile 的存储层 |

### 2.3 关键设计决策

| 决策 | 说明 |
|------|------|
| **缓存时机** | 区块卸载时保存（非收到即保存），确保缓存包含最终状态 |
| **时间戳** | 统一使用 `inhabitedTime`（服务端权威数据） |
| **存储格式** | 使用 HassiumEnvelope（ZSTD 压缩） |
| **RegionFile** | 客户端/服务端统一使用 MetadataTable |
| **元数据读取** | 通过元数据表直接读取，无需解析整个 envelope |
| **dataVersion** | 不存储 - 客户端缓存不需要，简化元数据表 |

## 3. 存储格式设计

### 3.1 HassiumRegionFile 格式（客户端）

```
Header (3 sectors, 12288 bytes):
├── Sector 0: Offset Table (1024 ints, 4096 bytes)
│   └── 每个 int: sectorOffset << 8 | sectorCount
└── Sector 1-2: Metadata Table (1024 entries × 8 bytes, 8192 bytes)
    └── 每个 entry: timestamp (long, inhabitedTime)

Data (Sector 3+):
└── Chunk Data (HassiumEnvelope bytes, 4096-byte aligned)
```

### 3.2 MetadataTable 格式（服务端/客户端共用）

```
1024 entries × 8 bytes = 8192 bytes
每个 entry:
└── timestamp: long (inhabitedTime)

索引计算: (chunkX & 31) + (chunkZ & 31) * 32
```

### 3.3 HassiumEnvelope 格式

```
HassiumEnvelope:
├── magic: "HSM1" (4 bytes)
├── storageFormatVersion: uint16
├── algorithmId: namespaced string (length-prefixed)
├── dictionaryId: nullable string (length-prefixed)
├── dataVersion: int32 (不使用，保留兼容)
├── uncompressedLength: int32
├── compressedLength: int32
├── chunkRevision: int64
├── lastModifiedGameTime: int64 (inhabitedTime)
├── lastSavedUnixTime: int64
├── checksum: uint64 (CRC32C)
└── compressedData: byte[] (ZSTD 压缩的 packet 数据)
```

### 3.4 缓存目录结构

```
.minecraft/hassium_cache/
└── server_<ip>/
    └── <world_id>/
        └── <dimension>/
            └── r.<x>.<z>.mca    ← 统一的 HassiumRegionFile
```

## 4. 时间戳统一

### 4.1 解决方案

统一使用 `inhabitedTime`：

| 位置 | 时间戳来源 |
|------|-----------|
| 服务端发送元数据 | `LevelChunk.getInhabitedTime()` |
| 客户端缓存保存 | `LevelChunk.getInhabitedTime()` |
| 客户端缓存比对 | `meta.timestamp()` vs `serverTimestamp` |

## 5. 实现细节

### 5.1 配置扩展

**文件**：`common/.../config/HassiumConfig.java`

NetworkConfig 新增：
```java
int serverChunkPushThreads,   // 服务端推送线程数 (默认2)
int clientChunkLoadThreads    // 客户端加载线程数 (默认2)
```

### 5.2 客户端缓存保存（卸载时）

**拦截点**：`ClientLevel.unload()` 方法 HEAD

```java
@Inject(method = "unload", at = @At("HEAD"))
private void hassium$onUnload(LevelChunk chunk, CallbackInfo ci) {
    hassium$renderOnlyChunks.remove(chunk.getPos());
    hassium$saveChunkToCache(chunk);
}

private void hassium$saveChunkToCache(LevelChunk chunk) {
    // 使用 MC 原生的序列化逻辑构造 packet
    ClientboundLevelChunkWithLightPacket packet = 
        new ClientboundLevelChunkWithLightPacket(
            chunk, level.getLightEngine(), null, null);
    
    // 序列化为字节
    FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
    packet.write(buf);
    byte[] data = new byte[buf.readableBytes()];
    buf.readBytes(data);
    buf.release();
    
    // 使用 inhabitedTime 作为时间戳
    long inhabitedTime = chunk.getInhabitedTime();
    ClientChunkHandler.persistToCache(chunk.getPos(), data, inhabitedTime);
}
```

### 5.3 客户端元数据比对

```java
public static void handleMetadataPacket(ChunkMetadataS2CPacket packet) {
    for (MetadataEntry entry : packet.entries()) {
        ChunkPos pos = new ChunkPos(entry.chunkX(), entry.chunkZ());
        
        // 快速读取元数据（只读 Metadata Table）
        ClientChunkMetadata meta = storage.readMetadata(pos);
        
        if (meta != null && meta.timestamp() >= entry.timestamp()) {
            loadQueue.enqueue(pos, distance);  // 缓存命中
        } else {
            missedChunks.add(pos);             // 缓存未命中
        }
    }
    
    if (!missedChunks.isEmpty()) {
        sendChunkDataRequest(dimension, missedChunks);
    }
}
```

## 6. 性能表现

### 6.1 集成测试结果

| 测试项 | 结果 | 说明 |
|--------|------|------|
| 服务端启动 | ✅ 通过 | 无异常 |
| 客户端登入 | ✅ 通过 | 握手正常 |
| 元数据推送 | ✅ 通过 | `[SEND METADATA]` 日志正常 |
| 缓存比对 | ✅ 通过 | `Cache comparison result` 正常 |
| 缓存命中 | ✅ 通过 | `CACHE HIT` + `CACHE APPLY` 正常 |
| 缓存未命中 | ✅ 通过 | `DATA REQUEST` + `DATA PROCESS` 正常 |
| 区块卸载保存 | ✅ 通过 | `[CACHE SAVE]` 正常 |
| MetadataTable | ✅ 通过 | 无 `Invalid metadata index` 错误 |

### 6.2 性能监测结果

使用性能监测工具分析，**本项目的代码不在性能消耗占比高的方法中**，说明：

- 异步线程池设计有效避免了主线程阻塞
- MetadataTable 快速读取（8 bytes）避免了大量 IO
- 区块压缩在线程池中执行，不影响主线程
- 元数据直接发送，无需排队等待

### 6.3 日志验证

```
# 服务端
Hassium: [SEND METADATA] chunk [5, -1] to player Player37 (inhabitedTime=131)
Hassium: [DATA REQUEST] Player Player37 requested 20 chunks (queue size: 20)
Hassium: [DATA PROCESS] Sent 20 chunk data packets to player Player37 (remaining: 0)

# 客户端
Hassium: Received metadata packet with 100 chunks
Hassium: Cache comparison result: 80 hits, 0 stale, 20 misses (total 100)
Hassium: [CACHE APPLY] Applied 20 chunks this frame (remaining: 0)
Hassium: [CACHE SAVE] Saving chunk [5, -6] on unload (inhabitedTime=0, size=29692 bytes)
```

## 7. 开发计划完成状态

### 阶段 1：基础框架 ✅

| 任务 | 状态 |
|------|------|
| 配置扩展 | ✅ |
| 网络数据包 | ✅ |
| ServerChunkPushManager | ✅ |
| MixinChunkHolder 改造 | ✅ |
| MixinServerPlayer 改造 | ✅ |
| MixinConnection 简化 | ✅ |
| 网络通道注册 | ✅ |

### 阶段 2：统一 RegionFile ✅

| 任务 | 状态 |
|------|------|
| HassiumRegionFile | ✅ |
| MetadataTable | ✅ |
| RegionBitmap | ✅ |
| ClientHassiumStorage | ✅ |

### 阶段 3：客户端缓存改造 ✅

| 任务 | 状态 |
|------|------|
| ClientCacheLoadQueue 改造 | ✅ |
| MixinClientPacketListener 简化 | ✅ |
| MixinClientLevel 扩展 | ✅ |
| ClientChunkHandler 简化 | ✅ |

### 阶段 4：时间戳统一 ✅

| 任务 | 状态 |
|------|------|
| 移除 CacheValidationService | ✅ |
| MixinServerLevel 改造 | ✅ |
| MixinChunkMap 改造 | ✅ |
| 统一使用 inhabitedTime | ✅ |

### 阶段 5：集成测试 ✅

| 任务 | 状态 |
|------|------|
| 服务端启动测试 | ✅ |
| 客户端登入测试 | ✅ |
| 缓存功能验证 | ✅ |
| 性能监测 | ✅ |

## 8. 文件变更清单

### 新建文件
| 文件 | 说明 |
|------|------|
| `storage/HassiumRegionFile.java` | 统一的 region 文件实现 |
| `storage/MetadataTable.java` | 区块时间戳快速读取 |
| `storage/RegionBitmap.java` | sector 分配位图 |
| `network/ChunkMetadataS2CPacket.java` | 区块元数据包 |
| `network/ChunkDataRequestC2SPacket.java` | 区块数据请求包 |
| `network/ServerChunkPushManager.java` | 服务端推送管理器 |
| `network/ClientMetadataHandler.java` | 客户端元数据处理 |

### 删除文件
| 文件 | 说明 |
|------|------|
| `cache/client/CacheValidationService.java` | 已移除，直接使用 inhabitedTime |
| `cache/client/ClientRegionFile.java` | 被 HassiumRegionFile 替代 |

### 修改文件
| 文件 | 变更 |
|------|------|
| `HassiumConfig.java` | 新增线程池配置 |
| `HassiumConfigService.java` | 新增配置 getter |
| `HassiumPacketIds.java` | 新增包 ID |
| `CommonClass.java` | 移除 CacheValidationService |
| `MixinChunkHolder.java` | 改为发送元数据 |
| `MixinServerPlayer.java` | 新增 trackChunk 拦截 |
| `MixinConnection.java` | 移除区块拦截逻辑 |
| `MixinClientLevel.java` | 新增卸载时保存缓存 |
| `MixinChunkMap.java` | 简化 |
| `MixinServerLevel.java` | 简化 |
| `ClientHassiumStorage.java` | 使用 HassiumRegionFile |
| `ClientChunkMetadata.java` | 简化为只包含 timestamp |
| `ClientCacheLoadQueue.java` | 改为线程池消费 |
| `FabricNetworkManager.java` | 注册新通道，移除旧代码 |
| `ForgeNetworkManager.java` | 注册新通道，移除旧代码 |

## 9. 后续优化建议

1. **动态线程池**：根据队列深度动态调整线程池大小
2. **预加载优化**：客户端可预加载玩家移动方向上的区块
3. **压缩算法选择**：根据网络状况动态调整压缩等级
4. **离线地图支持**：扩展缓存为完整存档格式，支持单人游戏离线加载

> **注意**：批量元数据发送不需要额外实现，因为本项目已有包聚合机制（`HassiumAggregationManager`），会在网络层自动合并多个小包。
