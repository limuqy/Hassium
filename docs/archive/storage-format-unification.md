> 归档：历史整理方案（已完成）（2026-08-09）
# 存储路径整理方案

## Context

Hassium 存储主路径已经稳定，当前不改磁盘布局：

| 路径 | 容器 | 哈希 | payload（解压后） |
|------|------|------|-------------------|
| 服务端存档 | 原版 `RegionFile` 2-sector header（`MixinRegionFile`） | 无磁盘 contentHash | 原版 `ChunkSerializer` NBT |
| 客户端缓存 | `HassiumRegionFile`：offset + `MetadataTable`（contentHash64） | `MetadataTable` + `SectionHashStore` | `"HBT1"` + `ChunkDiskCodec` NBT |
| 外层 data | 两端均为 `[len(4)][type=126][ZSTD dict]` | — | — |

整理目标只有两项：

1. **删除未使用的存储桩与重复实现**
2. **把三处重复的 ZSTD 字典编解码收敛到 `CompressionService`**

不改 region header、不挪 contentHash、不动 `SectionHashStore` 语义，不影响 chunkHash 命中比对与 Live-Unload 不变量。

---

## 1. 磁盘布局：保持现状

### 客户端缓存 `.mca`（`HassiumRegionFile`）

```
Sector 0:     Offset Table           (1024 × int32, 4KB)
Sector 1–2:   MetadataTable          (1024 × int64 contentHash, 8KB)
Sector 3+:    [len(4)][type=126][ZSTD dict payload]
```

- 文件名稳定：`r.<rx>.<rz>.mca`
- `contentHash64` 与 data 同文件写入（`writeChunk` → offset + MetadataTable 一次 `writeHeader`）
- `readChunkHash` 热路径：Bloom → 已打开 region 的内存 `MetadataTable`（无额外文件 I/O）

### 客户端 section 哈希（`SectionHashStore`）

- 每维度一个 `section_hashes.bin`
- 内存 `ConcurrentHashMap` + 后台 dirty flush
- 用途：分段增量请求 / MetadataTable 无效时的 combine 回退
- **本次不改格式、不合并、不外置到其它文件**

### 服务端存档

- 继续原版 2-sector header + type 126
- 本次仅压缩调用去重，行为不变

### Payload 差异：保留

| | 服务端 | 客户端 |
|---|---|---|
| 解压后 | 原版 ChunkSerializer NBT | `"HBT1"` + ChunkDiskCodec NBT |
| 原因 | 完整世界存档 | packet/缓存优化格式 |

两端 container 外层一致即可；内部 NBT schema 服务不同目的，不强行统一。

---

## 2. 压缩代码去重 → `CompressionService`

### 现状

以下位置各自手写 `ZstdDictCompress` / `ZstdDictDecompress` + `DictionaryRegistry` 查找：

- `mixin/MixinRegionFile.java`
- `cache/client/ClientHassiumStorage.java`（`compressWithDictionary` / `decompressWithDictionary` / `decompressForExport`）
- `cache/client/CacheWorldExporter.java`（若仍内联解压；优先走 storage 已有入口）
- `storage/ChunkPayloadCodec.java`（仅桩使用，随第 3 节删除）

已有但热路径未统一使用的实现：

- `compression/ZstdDictionaryCompressionCodec`（`HassiumCompression.initialize()` 已注册为 `hassium:zstd-dict`）
- `CompressionService.compress/decompress`（当前默认走无字典 codec / 需显式 options）

### 改动

`compression/CompressionService.java` 新增默认字典便捷方法（内部固定 `Constants.DEFAULT_ZSTD_DICTIONARY_ID`）：

```java
public byte[] compressWithDictionary(byte[] data, int level) throws CompressionException;
public byte[] decompressWithDictionary(byte[] compressedData) throws CompressionException;
```

实现要求：

1. 经已注册的 `ZstdDictionaryCompressionCodec`（或等价私有路径）完成编解码，**禁止**再在调用方 `new ZstdDictCompress/Decompress`
2. **缓存**字典句柄：至少缓存 `ZstdDictDecompress`；`ZstdDictCompress` 按 `level` 缓存（避免每次 persist 重建）
3. 字典缺失 → 抛 `CompressionException`（与现行“拒绝写入 Hassium payload”一致）
4. 线程安全：句柄缓存可读并发；zstd-jni dict 对象若不可跨线程共享则 per-thread 或同步包装，实现时以 zstd-jni 语义为准

### 调用方替换

| 文件 | 改法 |
|------|------|
| `MixinRegionFile` | 私有 compress/decompress 改为 `CompressionService.getInstance().compressWithDictionary/decompressWithDictionary` |
| `ClientHassiumStorage` | 同上；保留 `decompressForExport` 作为薄委托（供 exporter），或让 exporter 直接调 `CompressionService` |
| `CacheWorldExporter` | 解压改为 `CompressionService`（若当前经 storage 委托，可一并收口） |

`type=126` 字节的拼接/剥离仍留在各调用方（属于 region/payload 组包，不属于 codec）。

### 建议单测

- `CompressionService` 字典往返：随机/固定字节 compress→decompress 相等
- 与旧手写路径字节兼容（同一 level、同一字典，压缩结果允许 zstd 帧元数据差异时，以 decompress 互操作通过为准）

---

## 3. 死代码清理

### 删除（确认无运行时引用）

| 删除 | 原因 |
|------|------|
| `storage/HassiumRegionStorage.java` | 公共存储接口桩，无实现调用方 |
| `storage/HassiumRegionStorageImpl.java` | 空实现 / 占位 |
| `storage/ChunkPayload.java` | 仅被上述桩引用 |
| `storage/EncodedChunkPayload.java` | 同上 |
| `storage/ChunkStorageKey.java` | 同上 |
| `storage/ChunkStorageMetadata.java` | 同上 |
| `storage/StorageException.java` | 同上 |
| `storage/ChunkPayloadCodec.java` | 仅被 `HassiumRegionStorageImpl` 使用；逻辑由第 2 节收口到 `CompressionService` |
| `cache/client/ClientRegionFile.java` | 早期 1-sector region 实现，已被 `HassiumRegionFile` 取代，无引用 |

### API 与配置文档

| 文件 | 操作 |
|------|------|
| `api/HassiumApi.java` | 移除 `getStorage(String)` 及对 `HassiumRegionStorage` 的 import |
| `docs/config-audit.md` | `storage.zstdLevel` 引用从 `HassiumRegionStorageImpl` 改为实际读取点（`MixinRegionFile` / `HassiumConfigService` 等） |
| `docs/architecture.md` / `CLAUDE.md` / `.claude/skills/hassium-storage/SKILL.md` | 包职责表去掉 `ChunkPayloadCodec` 作为主路径的表述；改为 `HassiumRegionFile` + `MetadataTable` + `CompressionService` |

### 保留

| 保留 | 原因 |
|------|------|
| `storage/StorageMode` | `HassiumConfigSpec` 配置枚举 |
| `storage/HassiumRegionFile` / `MetadataTable` | 客户端缓存主路径 |
| `storage/HassiumChunkWriteBuffer` / `ChecksumUtils` / `RegionBitmap`（storage） | 服务端/region 辅助，非本次清理对象（若后续确认无引用可另开清理） |
| `cache/client/SectionHashStore` | 分段增量与 hash 回退 |
| `compression/ZstdDictionaryCompressionCodec` | 第 2 节收敛目标，不是死代码 |

删除前用一次全仓引用检索（类名 + 简单名）确认；`CompressedChunkPayload`（网络包 record）与本次 `storage/ChunkPayload` **不是**同一类型，勿删。

---

## 4. 不改的部分

- `HassiumRegionFile` header 布局（3-sector：offset + MetadataTable）
- `MetadataTable` contentHash64 语义与 0/1 特殊值处理
- `SectionHashStore` 文件格式与后台 flush
- `ChunkDiskCodec` / `CacheWorldExporter` 导出转码逻辑（HBT1→原版 NBT、ZSTD→zlib）
- `VanillaRegionWriter` / `VanillaChunkNbtCompat`
- `ClientCacheLoadQueue` / `CacheSaveQueue` / Live-Unload「hash 一致只补光」不变量
- `MixinRegionFile` 除压缩调用外的注入逻辑
- 网络 chunkHash / section delta 协议

---

## 5. 收益与风险

### 收益

1. 去掉一整棵未接线的 storage 抽象树，降低误读「主路径在 HassiumRegionStorage」的成本
2. ZSTD 字典编解码单点维护，并有机会缓存 dict 句柄
3. 磁盘与命中比对路径零行为变化，回归面可控

### 风险

**低。** 不改 on-disk 格式；主要风险是误删仍被反射/文档引用的符号，以及压缩收口后 level/字典 ID 传错。用编译 + 既有测试兜住。

---

## 6. 文件变更清单

| 文件 | 操作 |
|------|------|
| `compression/CompressionService.java` | 改：新增 `compressWithDictionary` / `decompressWithDictionary`（含句柄缓存） |
| `compression/ZstdDictionaryCompressionCodec.java` | 视需要：配合句柄缓存微调，或保持由 Service 包装 |
| `mixin/MixinRegionFile.java` | 改：压缩/解压改调 `CompressionService` |
| `cache/client/ClientHassiumStorage.java` | 改：同上 |
| `cache/client/CacheWorldExporter.java` | 改：解压收口（若仍有内联） |
| `api/HassiumApi.java` | 改：移除 `getStorage` |
| `storage/HassiumRegionStorage.java` | **删** |
| `storage/HassiumRegionStorageImpl.java` | **删** |
| `storage/ChunkPayload.java` | **删** |
| `storage/EncodedChunkPayload.java` | **删** |
| `storage/ChunkStorageKey.java` | **删** |
| `storage/ChunkStorageMetadata.java` | **删** |
| `storage/StorageException.java` | **删** |
| `storage/ChunkPayloadCodec.java` | **删** |
| `cache/client/ClientRegionFile.java` | **删** |
| `docs/architecture.md` 等索引文档 | 改：主路径描述与配置审计引用 |

---

## 7. 验证

1. `./gradlew --no-daemon common:compileJava` — 删桩与 API 裁剪后通过
2. `./gradlew --no-daemon common:test` — 既有压缩/字典/Region 测试通过；补字典往返单测
3. 服务端：开 `storage.enabled` 写档 → 重开可读 type 126
4. 客户端：进服 → 缓存落盘 → 断连重进 → chunkHash 命中比对正常；分段增量仍可用
5. `/hassiumc export` → 原版 Minecraft 可加载导出世界

---

## 实施顺序

1. 在 `CompressionService` 增加字典便捷 API + 单测  
2. 替换 `MixinRegionFile` / `ClientHassiumStorage` / exporter 调用  
3. 删除死类并裁剪 `HassiumApi`  
4. 同步 architecture / skill / config-audit 表述  
5. 全量 `common:test` + 一次本地进服冒烟  
