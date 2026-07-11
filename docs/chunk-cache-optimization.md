# 区块缓存优化方案

## 概述

将区块缓存从整块比对升级为 **分区哈希 + 增量同步**，解决因 blockEntity NBT 每 tick 变化导致的缓存命中率低问题。

## 核心设计

### 问题根因

原版区块缓存使用整块 hash 比对，但 blockEntity 的 NBT 数据（如熔炉 BurnTime、漏斗 TransferCooldown）每 tick 都在变化，导致 hash 不稳定。即使方块数据未变，hash 也会因 blockEntity 变化而不匹配。

### 解决方案

1. **分区哈希**：只对 section 的方块数据计算哈希，排除 blockEntity NBT
2. **sectionHashes → chunkHash 推导**：先计算 per-section 哈希，再组合为 chunkHash，避免重复计算
3. **两阶段同步**：阶段一广播 chunkHash 快速比对，阶段二客户端驱动增量同步
4. **blockEntity 不缓存**：始终从服务端获取最新数据

### 关键约束

- 不追踪 blockEntity 版本号（Mod 兼容性）
- 区块在玩家加载范围内时的方块变更无需关注（缓存在区块卸载时更新）
- 客户端发送 sectionHashes，服务端比对后推送变更数据

## 哈希计算

### section 哈希

```java
// ChunkContentHashUtil.computeSectionHash(LevelChunkSection)
// 只包含方块状态 palette + 生物群系，不含 blockEntity
// 序列化格式：section.write(buf) 的原始字节
```

### chunkHash 推导

```java
// ChunkContentHashUtil.combineSectionHashes(Map<Integer, Long>)
// 按 sectionIndex 排序，依次写入 index + hash 的字节
// 服务端和客户端使用相同的组合算法，确保一致性
```

**优势**：
- 服务端：计算 sectionHashes 后直接组合，无需单独计算 chunkHash
- 客户端：从缓存读取 sectionHashes 后直接组合，无需重新遍历 section

## 数据流

### 服务端

```
ChunkHolder.broadcast() / ServerPlayer.trackChunk()
    ↓ (Mixin 拦截)
获取 LevelChunk
    ↓
computeSectionHashes(chunk)     ← 计算 per-section 哈希
    ↓
combineSectionHashes()          ← 组合为 chunkHash
    ↓
sendChunkHash(chunkHash + sectionBitmap)
    ↓ (阶段二请求到达时)
handleSectionHashRequest()
    ↓
比对客户端 sectionHashes → 只发送变更的 section + blockEntity
```

### 客户端

```
收到 ChunkHashS2CPacket (chunkHash + sectionBitmap)
    ↓
读取缓存的 sectionHashes → combineSectionHashesFromArray() → cachedChunkHash
    ↓
┌─────────┴─────────┐
chunkHash 匹配      chunkHash 不匹配
↓                     ↓
缓存命中            发送 SectionHashRequestC2SPacket (带 sectionHashes)
↓                     ↓
加载缓存 +          服务端比对 → 发送 SectionDeltaS2CPacket
请求 blockEntity       ↓
                     合并缓存 sections + 变更 sections + blockEntity
```

## 包定义

### 阶段一：chunkHash 广播

```java
// ChunkHashS2CPacket (服务端 → 客户端)
record ChunkHashS2CPacket(String dimension, List<Entry> entries) {
    record Entry(int chunkX, int chunkZ, long chunkHash, int sectionBitmap) {}
}
```

### 阶段二：section 哈希请求 + delta 响应

```java
// SectionHashRequestC2SPacket (客户端 → 服务端)
record SectionHashRequestC2SPacket(String dimension, List<Entry> entries) {
    record Entry(int chunkX, int chunkZ, long[] sectionHashes) {}
}

// SectionDeltaS2CPacket (服务端 → 客户端)
record SectionDeltaS2CPacket(String dimension, List<DeltaEntry> entries) {
    record DeltaEntry(int chunkX, int chunkZ,
                      List<SectionData> changedSections,
                      List<BlockEntityData> blockEntities) {}
    record SectionData(int sectionIndex, byte[] blockData) {}
    record BlockEntityData(BlockPos pos, ResourceLocation type, CompoundTag nbt) {}
}
```

## 存储

### region 文件

```
Header (3 sectors):
├── Sector 0: Offset Table (1024 ints, 4096 bytes)
└── Sector 1-2: Metadata Table (1024 × int64 contentHash, 8192 bytes)
Data:
└── [length(4)][type=126][ZSTD compressed packet bytes]
```

### section 哈希持久化

section 哈希存储在 SQLite 数据库的 `cache_entries` 表中：

```sql
ALTER TABLE cache_entries ADD COLUMN section_hashes BLOB DEFAULT NULL;
```

序列化格式：`count(4) + [index(4) + hash(8)] × N`

读取路径：`ClientHassiumStorage.readSectionHashes()` → SQLite 查询 → 反序列化

## 实现状态

| 组件 | 状态 | 说明 |
|------|------|------|
| `ChunkContentHashUtil.combineSectionHashes()` | ✅ | 从 sectionHashes 组合 chunkHash |
| `ChunkContentHashUtil.combineSectionHashesFromArray()` | 客户端从 long[] 组合 |
| `ChunkContentHashUtil.computeSectionHashesFromBytes()` | 从 packet 字节计算 section 哈希 |
| `ServerChunkPushManager.submitMetadataTask()` | sectionHashes → chunkHash |
| `ClientMetadataHandler.handleChunkHashPacket()` | 阶段一比对 |
| `ClientMetadataHandler.handleSectionDeltaPacket()` | 阶段二 delta 应用 |
| `ClientChunkHandler` section hash 暂存 | 后台线程计算 + 暂存 |
| `ClientCacheDatabase` section_hashes 列 | SQLite 持久化 |
| `ClientHassiumStorage.readSectionHashes()` | 从 SQLite 读取 |
| `ClientHassiumStorage.persist()` | 支持 sectionHashes 参数 |
| `HassiumRegionFile` | ✅ | 基础存储 |
| 区块卸载缓存写入 | ✅ | `MixinClientLevel.unload` + `CacheSaveQueue` 含 sectionHashes |
| section 数据合并 | ✅ | `replaceSectionsInPacket` 精确 section 级别替换 |
| blockEntity 请求优化 | ✅ | `BlockEntityRequestC2SPacket` + `BlockEntityDataS2CPacket` 专用通道 |
| GlobalPalette 解析修复 | ✅ | `skipPalettedContainer` + `skipPalettedContainerBytes` |
| 线程安全修复 | ✅ | `submitMetadataTask` 从 packet 数据计算 hash |

## 依赖

- zstd-jni (`com.github.luben:zstd-jni`) - ZSTD 压缩
- xxHash (`net.jpountz.xxhash`) - 快速哈希计算
- SQLite (`org.xerial:sqlite-jdbc`) - 客户端缓存索引

## 网络通道

### 共享接口

```java
// common/src/main/java/.../network/NetworkManager.java
void sendChunkHashPacket(ServerPlayer player, FriendlyByteBuf buf)
void sendSectionHashRequest(FriendlyByteBuf buf)
void sendSectionDeltaPacket(ServerPlayer player, FriendlyByteBuf buf)
void sendBlockEntityRequest(FriendlyByteBuf buf)
void sendBlockEntityData(ServerPlayer player, FriendlyByteBuf buf)
```

### 通道列表

| 通道 | 方向 | 包类 | 用途 |
|------|------|------|------|
| `hassium:chunk_hash_s2c` | S→C | `ChunkHashS2CPacket` | 阶段一：chunkHash 广播 |
| `hassium:section_hash_request_c2s` | C→S | `SectionHashRequestC2SPacket` | 阶段二：section 哈希请求 |
| `hassium:section_delta_s2c` | S→C | `SectionDeltaS2CPacket` | 阶段二：section delta 响应 |
| `hassium:block_entity_request_c2s` | C→S | `BlockEntityRequestC2SPacket` | blockEntity 数据请求 |
| `hassium:block_entity_data_s2c` | S→C | `BlockEntityDataS2CPacket` | blockEntity 数据响应 |

## 带宽分析

假设 16×16 区块，24 个 section：

| 场景 | 数据量 | 说明 |
|------|--------|------|
| 阶段一广播 | ~18 bytes/chunk | chunkHash(8) + sectionBitmap(4) + 坐标(8) |
| blockEntity 请求 | ~12 bytes/chunk | 坐标(8) + 维度开销 |
| blockEntity 响应 | ~1-2 KB/chunk | 仅 blockEntity NBT 数据 |
| 阶段二请求 | ~200 bytes/chunk | sectionHashes(24×8=192) + 坐标(8) |
| 阶段二响应 | 仅变更的 section | 通常 1-3 个 section × ~2KB |
| 全量传输 | ~50-100 KB/chunk | 原版完整区块数据 |

**典型场景**：玩家静止不动，方块未变更
- 阶段一：chunkHash 匹配 → 缓存命中，仅 18 bytes/chunk
- blockEntity 请求 + 响应：~1-2 KB/chunk
- 对比旧方案（全量 50-100KB）：节省 ~98%

**方块变更场景**：1 个 section 变更
- 阶段一：chunkHash 不匹配 → 进入阶段二
- 阶段二：200 bytes 请求 + ~2KB 响应
- 对比全量 50KB：节省 ~95%
