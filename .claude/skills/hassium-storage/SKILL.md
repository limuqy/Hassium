---
name: hassium-storage
description: Hassium 存储与压缩技能。涉及 Region/type 126、ChunkPayloadCodec、HassiumRegionFile、MetadataTable、CompressionCodec/字典、MixinRegionFile、存档安全或 compression/storage 包任务时使用。
---

# Hassium 存储与压缩

网络侧压缩与 chunkHash 推送见 [[hassium-network]]。Mixin 注入见 [[hassium-mixin]]。

## 磁盘格式（现行）

```
.mca Anvil 外层
├── Sector 0: Offset Table
├── Sector 1–2: MetadataTable v2（1024 × int64 contentHash）
└── Sector 3+: [length(4)][type=126][ZSTD 数据]
```

- **无** `HassiumEnvelope` / HSM1；运行时写入 type **126**
- type **127** 仅作 1.20.5+ 原版 custom scheme 迁移规划，勿当作当前写入格式
- 服务端：`MixinRegionFile`（先查 `isStorageEnabled()`）
- 客户端缓存：`HassiumRegionFile` + MetadataTable；`contentHash` = `combine(sectionHashes)`
- 客户端辅存：`ClientHeatIndex`（热度/LRU）、`SectionHashStore`（sectionHashes；命中回退 / 阶段二）
- 命中比对见 `docs/chunk-cache.md`（`readChunkHash`）

## 关键 API

| 类 | 职责 |
|----|------|
| `ChunkPayloadCodec` / `EncodedChunkPayload` | type 字节 + ZSTD 编解码 |
| `HassiumRegionFile` | 客户端/独立 Region 读写 |
| `MetadataTable` | contentHash64（= chunkHash） |
| `ClientHeatIndex` / `SectionHashStore` | 热度索引 / section 哈希辅存 |
| `ChunkContentHashUtil` | sectionHash / combine → chunkHash |
| `CompressionService` + `CompressionCodec` | 算法注册与调用 |
| `HassiumCompression` | 初始化；字典 codec 在此注册 |
| `SimpleDictionaryRegistry` / `ResourceDictionaryLoader` | 字典加载 |
| `CompressionAlgorithmId` | `hassium:zstd` 等命名空间 |

默认等级：**存储 zstdLevel=9**；**网络 compressionLevel=3**（见 NetworkConfig）。

## 编解码约定

1. 实现 `CompressionCodec`，用 `CompressionAlgorithmId` 注册
2. 字典缺失 → **拒绝写入** Hassium payload，回退原版
3. 解压端勿错误复用压缩侧 `CompressionOptions`（等级等）
4. 字典 ID / 存储算法固定为内置常量（不开放配置）

## 初始化链

```
模组入口 → HassiumCompression.initialize()
  → 注册 ZstdCompressionCodec / VanillaZlibCodec
  → 加载内置字典 → 注册字典 codec
```

离线训练：`benchmark/DictionaryTrainer`（`common:runJava`）。

## 配置

- `storage.enabled` 默认 **true**（改格式 → 备份世界）
- `storage.mode`：`readonly_vanilla` / `mirror` / `hassium_only`
- `migration/` 包为未实现桩，勿假定已有迁移工具

## 测试

```bash
./gradlew --no-daemon common:test
```

改存储路径后务必 `common:compileJava` + 至少一端 loader 编译，并用真实世界备份验证读写。
