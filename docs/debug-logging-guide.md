# 区块推送系统调试日志指南

## 概述

本文档描述了在区块推送系统中添加的关键日志点，用于排查区块加载问题。

## 服务端日志流程

### 1. 握手阶段
```
[HANDSHAKE] Received handshake packet from player Player310
[HANDSHAKE] Handshake details: protocol=1, modVersion=..., algorithms=..., clientCache=true, globalCompression=true, compactHeader=true
[HANDSHAKE] Enabled compression for player Player310
[HANDSHAKE] Verification - Player Player310 compression enabled: true
```

**关键检查点**: 确认 `compression enabled: true`

### 2. Mixin 类加载
```
[MIXIN] MixinChunkHolder class loaded
[MIXIN] MixinServerPlayer class loaded
```

**关键检查点**: 确认两个 Mixin 都已加载（在类加载时触发）

### 3. 区块广播拦截 (MixinChunkHolder)
```
[BROADCAST] Chunk [x, z] broadcast triggered, players count: N
[BROADCAST] Player Player310 compression enabled: true
[BROADCAST] Chunk [x, z] inhabitedTime: 12345
[BROADCAST] Sending metadata for chunk [x, z] to player Player310
```

**关键检查点**: 确认 `compression enabled: true` 和元数据发送

### 4. 新区块跟踪 (MixinServerPlayer)
```
[TRACK_CHUNK] Player Player310 tracking chunk [x, z], compression enabled: true
[TRACK_CHUNK] Sending metadata for new chunk [x, z] to player Player310 (inhabitedTime=12345)
```

**关键检查点**: 确认 `compression enabled: true`

### 5. 发送元数据
```
[SEND_METADATA] Sending chunk [x, z] to player Player310 (dimension=minecraft:overworld, timestamp=12345, bufSize=18)
[SEND_METADATA] Successfully sent chunk [x, z] to player Player310
```

### 6. 接收数据请求
```
[SERVER] Received chunk data request packet from player Player310
[SERVER] Decoded chunk data request: 5 chunks, dimension=minecraft:overworld, chunks=[[x1, z1], [x2, z2], ...]
[SERVER] Processing chunk data request on server thread for player Player310
```

### 7. 入队处理
```
[ENQUEUE_DATA] Player Player310 requested 5 chunks (dimension=minecraft:overworld)
[ENQUEUE_DATA] Player Player310 queued 5 chunks (queueSize=5, playerPos=(100.5, 200.3))
```

### 8. 队列处理
```
[PROCESS_QUEUE] Processing queue for player Player310 (queueSize=5)
[PROCESS_QUEUE] Processing up to 10 chunks for player Player310
[PROCESS_QUEUE] Processing chunk [x, z] (priority=15.3, remaining=4)
[PROCESS_QUEUE] Serialized chunk [x, z] (45678 bytes)
[PROCESS_QUEUE] Sent chunk [x, z] to player Player310 (45678 -> 12345 bytes, ratio=3.70)
[PROCESS_QUEUE] Completed processing for player Player310: sent=5, remaining=0
```

## 客户端日志流程

### 1. 接收元数据包
```
[CLIENT] Received chunk metadata packet
[CLIENT] Decoded chunk metadata: 5 entries, dimension=minecraft:overworld
[CLIENT] Processing chunk metadata on main thread
```

### 2. 元数据处理
```
[CLIENT_METADATA] Received metadata packet, dimension=minecraft:overworld, entries=5
[CLIENT_METADATA] Using async path for metadata comparison
```

### 3. 缓存比对
```
[COMPARE_METADATA] Starting comparison: 5 entries, dimension=minecraft:overworld, playerPos=(100.5, 200.3)
[COMPARE_METADATA] HIT chunk [x, z] (localTs=12345, serverTs=12345, dist=15.3)
[COMPARE_METADATA] MISS chunk [x, z] (not cached)
[COMPARE_METADATA] Result: 2 hits, 0 stale, 3 misses (total 5)
```

**关键检查点**: 确认有 HIT 或 MISS 记录

### 4. 应用元数据结果
```
[APPLY_METADATA] Applying result: 2 hits, 3 misses, dimension=minecraft:overworld
[APPLY_METADATA] Queued chunk [x, z] for cache loading (distance=15.3)
[APPLY_METADATA] 2 chunks queued for cache loading
[APPLY_METADATA] Requesting 3 chunks from server: [[x1, z1], [x2, z2], [x3, z3]]
[APPLY_METADATA] Successfully sent chunk data request to server
```

**关键检查点**: 确认发送了数据请求

### 5. 接收压缩区块数据
```
[CLIENT] Received chunk payload packet
[CLIENT] Received compressed chunk payload (12345 bytes)
[CLIENT] Processing compressed chunk on main thread
```

### 6. 处理压缩数据
```
[HANDLE_COMPRESSED] Received compressed chunk data (12345 bytes)
[HANDLE_COMPRESSED] Decoded chunk [x, z] (originalSize=45678, algorithm=hassium:zstd, compressedSize=12345)
[HANDLE_COMPRESSED] Decompressing chunk [x, z] in background
[HANDLE_COMPRESSED] Decompressed chunk [x, z] (12345 -> 45678 bytes)
[HANDLE_COMPRESSED] Applying chunk [x, z] to world
[HANDLE_COMPRESSED] Successfully applied chunk [x, z] from server
```

### 7. 缓存加载队列
```
[CACHE_LOAD_QUEUE] Enqueuing chunk [x, z] (priority=15.3, renderOnly=false, pendingSize=0)
[CACHE_LOAD] Processing chunk [x, z] (priority=15.3, pendingSize=1)
[CACHE_LOAD] Chunk [x, z] loaded from disk (45678 bytes, readySize=1)
```

### 8. 应用区块到世界
```
[CACHE_APPLY] Processing queue, readySize=1
[CACHE_APPLY] Applying chunk [x, z] to world (renderOnly=false, remaining=0)
[CACHE_APPLY] Successfully applied chunk [x, z] to world
[CACHE_APPLY] Applied 1 chunks this frame (remaining: 0)
```

## 常见问题排查

### 问题1: 玩家移动后不加载新区块

**检查点**:
1. 确认 `[BROADCAST]` 或 `[TRACK_CHUNK]` 日志出现
2. 确认 `compression enabled: true`
3. 确认 `[SEND_METADATA]` 日志出现

**可能原因**:
- Mixin 未正确注入
- PlayerCompressionTracker 未启用压缩

### 问题2: 客户端收到元数据但不请求数据

**检查点**:
1. 确认 `[CLIENT] Received chunk metadata packet` 出现
2. 确认 `[COMPARE_METADATA]` 有 MISS 记录
3. 确认 `[APPLY_METADATA] Requesting N chunks from server` 出现

**可能原因**:
- 缓存比对逻辑问题
- 网络请求未发送

### 问题3: 服务端收到请求但不发送数据

**检查点**:
1. 确认 `[SERVER] Received chunk data request packet` 出现
2. 确认 `[ENQUEUE_DATA]` 日志出现
3. 确认 `[PROCESS_QUEUE]` 日志出现

**可能原因**:
- 线程池未初始化
- 区块未加载

### 问题4: 客户端收到数据但不应用

**检查点**:
1. 确认 `[CLIENT] Received chunk payload packet` 出现
2. 确认 `[HANDLE_COMPRESSED]` 日志出现
3. 确认 `[APPLY_CHUNK]` 日志出现

**可能原因**:
- 解压失败
- 区块应用失败

## 日志级别调整

如需更详细的日志，可在 `logback.xml` 中调整：

```xml
<logger name="Hassium/ChunkHolder" level="DEBUG"/>
<logger name="io.github.limuqy.mc.hassium.network" level="DEBUG"/>
<logger name="io.github.limuqy.mc.hassium.cache" level="DEBUG"/>
```
