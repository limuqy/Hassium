# 区块缓存推送与进服加载

本文档是 **chunkHash 元数据推送 + 客户端缓存命中** 流水线的唯一真相源。存储文件格式见 [`architecture.md`](architecture.md)。

**相关专文（细节不在此重复）：**

| 主题 | 文档 | 本文摘要 |
|------|------|----------|
| 超视渲染 | [`ovd.md`](ovd.md) | §10 |
| 磁盘 NBT / Live-Unload / 分段增量 | [`disk-nbt-cache.md`](disk-nbt-cache.md) | §11 |
| 世界导出 | 同上 + 本文 §12 | §12 |

**卖点特性（已实现）：** 分段增量（§3 阶段二 / §11）、超视渲染（§10）、`/hassiumc cache export`（§12）。

## 1. 目标与约束

- 用 **内容哈希**（非 `inhabitedTime`）判断缓存是否可复用
- section 方块数据哈希排除会每 tick 变化的 blockEntity NBT
- blockEntity 不进缓存命中域：区块 apply 后再走专用请求
- 自 `disk-nbt-cache-and-export` 起：客户端缓存 payload 为 **磁盘 chunk `CompoundTag`**（含 `"HBT1"` magic 前缀），跨大版本约束放宽到 NBT schema 兼容

## 2. 哈希

```
sectionHash = hash(section 方块 palette + 生物群系序列化字节)
chunkHash   = combineSectionHashes(sectionIndex → sectionHash)
```

实现：`ChunkContentHashUtil`。服务端与客户端算法一致。

客户端落盘时 `MetadataTable.contentHash` **必须**等于 `combine(sectionHashes)`（与 `ChunkHashS2C` 同值）。卸载重写（`CacheSaveQueue`）同样用 combine，勿用含 BE/heightmap 的 `compute()`。

命中比对（`ClientHassiumStorage.readChunkHash`）：

1. Bloom 预筛 → 打开 Region
2. 读 MetadataTable `contentHash`（合法非 0；`1` 视为无效占位）
3. 若无效则 `combine(SectionHashStore)` 回退
4. 与服务端 `chunkHash` 相等 → 命中

## 3. 现行数据流

> **阶段二 分段增量**（默认开）：缓存过期（MISMATCH）走 `SectionHashRequest` → NBT merge（失败回退全量）。详见 §11。

### 服务端

```
ChunkHolder.broadcast / ServerPlayer.trackChunk / PlayerChunkSender
        │  (握手后 Mixin 拦截，cancel 原版全量包)
        │  主线程：编码并缓存已构建包字节（反透视兼容，见 mod-compat.md）
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
  主线程: 优先 take 缓存包字节，否则 getChunk + serialize ≤ maxChunksPerTick
  pushPool: ZSTD + ChunkPayloadS2C
```

### 客户端

```
ChunkHashS2C
        │
storage 未就绪 → 暂存；就绪后批量比对（超时约 2s 回退全量）
        │
readChunkHash（MetadataTable，必要时 SectionHashStore combine）
        │
   ┌────┴────────────┬────────────┐
  HIT              MISS         MISMATCH（过期）
   │                 │              │
CacheLoadQueue   全量请求     分段增量（默认开）
   │                 │         SectionHashRequest
后台磁盘+解压    ChunkPayload    → SectionDelta → NBT merge
   └────────┬────────┘              │（失败/skipped/超时 → 全量）
            └───────────┬───────────┘
主线程时间预算 apply（JoinBoost：进服约 5s 提高预算）
        │
HIT apply 后再请求 blockEntity
```
## 4. 主线程限流

| 机制 | 说明 |
|------|------|
| `mainThreadChunkBudgetMs` | 每帧 apply/回调共享预算（默认 3ms） |
| JoinBoost | 进服约 5s 预算约 10ms |
| `maxChunksPerFrame` / `maxCallbacksPerFrame` | 安全硬顶（默认 32） |

控制面包（hash / 握手 / index sync 等）在 `PacketCompressionBlacklist`，避免进 PENDING 聚合窗口。

## 5. 协议（阶段一 / 阶段二）

### 阶段一（现行）

```java
ChunkHashS2CPacket(dimension, List<Entry>)
// Entry(chunkX, chunkZ, chunkHash, sectionBitmap)
```

### 阶段二：分段增量（默认开启，可关闭）

```java
SectionHashRequestC2SPacket  // 客户端 → 服务端
SectionDeltaS2CPacket        // 服务端 → 客户端（变更分段 + BE）
```

门控：`clientCache.sectionDeltaEnabled`（默认 `true`；需同时 `clientCache.enabled`）。

| 比对结果 | 开关关 | 开关开（默认） |
|----------|--------|----------------|
| HIT | 缓存队列 | 缓存队列 |
| MISS | 全量 | 全量 |
| MISMATCH（过期） | 全量 | `sendSectionHashRequest` → NBT merge（失败回退全量） |

服务端按 `0..sectionsCount-1` 比对 hash（空气=0），不等则统一 `serializeSection`（无专用空气协议）。详见 §11.5。

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
| `ClientHassiumStorage` / `HassiumRegionFile` | 客户端 Region 缓存 + MetadataTable |
| `ClientHeatIndex` | 访问热度 / LRU（`config/hassium/heat.idx`），不参与命中 |
| `SectionHashStore` | per-dimension `section_hashes.bin`；分段增量比对 / 命中回退 |

## 7. 客户端淘汰

`ClientHeatIndex` 按 `chunkBytes`（单块压缩大小）与热度评分清理；超过 `maxSizeMb` 等阈值时删 Region 内单块（`storage.remove`），不整文件删除 `.mca`。

## 8. 调试

默认无热路径 INFO。排查时打开 `config/hassium/hassium-common.toml` 的 `debug.metadataLogging` / `debug.networkLogging` / `debug.cacheLogging` 等（见 architecture）。运行时统计：`/hassiumc stats`。

## 9. 待实现

- 方向性区块预加载（提高推送优先级，不改变协议）
- warm-stash 优化（收包后暂存 NBT，卸载时 dirty=false 则 flush warm 跳过 live 重算）

## 10. 超视渲染（renderOnly）

### 10.1 目标

客户端渲染距离（RD） > 服务端 `view-distance` 时，用本地 `hassium_cache` 历史区块回填 `serverVD < dist ≤ clientVD` 的环形带，**仅参与渲染，不参与模拟**，且不向服务器请求视距外区块 / BE。不改服务端协议；stale 接受为「历史快照」。

### 10.2 解锁渲染距离

`MixinOptions` 注入 `Options#getEffectiveRenderDistance`（HEAD cancellable）：当 `clientCache.enabled && viewDistanceExtensionEnabled && 多人游戏` 时返回客户端滑块值，绕过原版 `serverRenderDistance` 钳制。ViewArea 随之扩大（原版自动）。单人游戏不启用。

`serverRenderDistance` 经 `OptionsAccessor`（Mixin `@Accessor`）从 Options private 字段读取；未登录时 fallback `simulationDistance`。

### 10.3 数据流

```
MixinClientTick.tick
  → ViewDistanceExtensionService.update()（单例）
    → serverVD = OptionsAccessor.getServerRenderDistance()
    → 环带 = {pos : serverVD < dist(pos,player) ≤ clientVD}（圆形）
    → toLoad：跳过 ClientChunkCache.hasChunk 的真实区块 + 已 renderOnly 的
    → ClientCacheLoadQueue.enqueue(pos, dist, renderOnly=true)
        ├ 命中：applyChunkData(renderOnly=true)
        │   → applier.applyToLevelFromByteBuf → handleLevelChunkWithLight + addRenderOnlyChunk
        │   → 跳过 ClientMetadataHandler.onChunkApplied（不请求 BE）
        └ miss/异常：静默，调 ViewDistanceExtensionService.onRenderOnlyMiss(pos)
            → loadedRenderOnly.remove + level.hassium$removeRenderOnlyChunk
            → 【不】requestChunkFromServer
```

### 10.4 边界替换（P1）

真实区块到达 renderOnly pos 时（`ChunkHash` 命中或全量包），三端 applier 在 `handleLevelChunkWithLight` 前调 `hassium$removeRenderOnlyChunk(pos)` + `ViewDistanceExtensionService.onRealChunkApplied(pos)`，覆盖为正常区块并请求 BE。

### 10.5 真正卸载（P1）

`ViewDistanceExtensionService.unloadRenderOnlyChunk` 反射 `ClientChunkCache.Storage.drop(x, z)` 拿到旧 `LevelChunk`，调 `level.unload(old)` 触发 BE 清理 + 缓存保存（经 `MixinClientLevel.hassium$onUnload`）。P0 阶段仅清标记，不 drop。

### 10.6 断连清理

`ClientLifecycleHelper.cleanupOnDisconnect` 在 `ClientCacheLoadQueue.clear()` 后调 `ViewDistanceExtensionService.clearAllRenderOnly()`，清空 `loadedRenderOnly` + level 标记，避免重连后残留。

### 10.7 关键组件

| 组件 | 职责 |
|------|------|
| `MixinOptions` | 解除 `getEffectiveRenderDistance` 钳制 |
| `OptionsAccessor` | 读取 `Options.serverRenderDistance` |
| `ViewDistanceExtensionService` | 单例；环带计算 / enqueue / miss 回调 / 清理 |
| `ClientCacheLoadQueue` | renderOnly miss 静默（不请求服务器） |
| `ClientChunkHandler.applyChunkData` | renderOnly 跳过 `onChunkApplied`（不请求 BE） |
| `MixinClientLevel` | `hassium$renderOnlyChunks` 标记集合 |

### 10.8 边界条件

| 场景 | 处理 |
|------|------|
| 单人游戏 | `MixinOptions` / `ViewDistanceExtensionService` 均检查 `mc.getSingleplayerServer() != null` → 跳过超视渲染 |
| `serverRenderDistance == 0`（未登录） | fallback `simulationDistance`；仍 ≤0 则 `clearAllRenderOnly` |
| `clientVD <= serverVD` | `clearAllRenderOnly`，恢复原版 |
| 配置关（`viewDistanceExtensionEnabled=false`） | `clearAllRenderOnly`；`MixinOptions` 不 cancel（原版钳制） |
| 缓存 miss（renderOnly） | 静默 + 回滚标记，不向服务器请求 |
| RD > 32（手改 options.txt） | 可工作；雾距跟随 `getEffectiveRenderDistance` 扩大，可能穿帮（Fog Mixin 未实现，见下）。建议保持 RD ≤ 32 |

### 10.10 Fog 钳制（未实现）

`maxRenderDistance < clientVD` 时钳制雾距的 MixinFogRenderer **未实现**。理由：

- 默认配置（`maxRenderDistance=32`，vanilla 滑块上限 32）下为 no-op
- `FogRenderer.setupFog` 跨 9 段签名差异大（1.20.1 vs 1.21.x 参数列表重构）
- `RenderSystem` fog API 在 1.20.1（`fogEnd` field）与 1.21+（`setShaderFogEnd` method）间不兼容

RD > 32（需手改 `options.txt`）时雾距会跟随 `getEffectiveRenderDistance` 扩大，远端区块可能突然显现（穿帮）。若需 RD > 32，建议接受此视觉影响或等待后续按段实现 Fog Mixin。

### 10.11 内存估算

超视渲染环带区块数 ≈ `π × (clientVD² − serverVD²)`（圆形），每块完整 `LevelChunk` 约 20–50 KB（视方块密度与生物群系复杂度）。

示例：

| serverVD | clientVD | 环带区块数 | 估算内存 |
|----------|----------|-----------|---------|
| 8 | 16 | ~600 | ~12–30 MB |
| 8 | 24 | ~1700 | ~34–85 MB |
| 8 | 32 | ~3100 | ~62–155 MB |
| 12 | 32 | ~2500 | ~50–125 MB |

建议保持 RD ≤ 32（vanilla 滑块上限）。RD > 32 时内存显著增长且雾可能穿帮（§10.10）。依赖现有 `ClientHeatIndex` 缓存淘汰，不新增内存池。

### 10.9 不做

- Bobby FakeChunk / 独立 `.bobby` 目录
- 视距外向服务器 `ChunkDataRequestC2S` / 放宽 BE 视距校验
- 分段增量接回超视渲染
- 抬高 vanilla 滑块上限 >32（版本差异大，用户编辑 options.txt）

## 11. 磁盘 NBT 缓存格式

自 `disk-nbt-cache-and-export` 起，客户端缓存 payload 从 packet 字节改为磁盘 chunk `CompoundTag`。

### 11.1 外层布局（不变）

仍为 `HassiumRegionFile` 的 3-sector header + `[length(4)][type=126][ZSTD 字典压缩 NBT 字节]`：
- Sector 0: offset table（4096B）
- Sector 1-2: MetadataTable（1024 × int64 contentHash）
- Data: `[length(4)][type=126][ZSTD compressed NBT bytes]`

### 11.2 内层 NBT schema

解压后的字节为 `["HBT1" magic(4)][NBT binary]`，NBT 顶层 `CompoundTag`：

| 字段 | 类型 | 说明 |
|------|------|------|
| `x` | IntTag | chunkX |
| `z` | IntTag | chunkZ |
| `section_count` | IntTag | section 数量 |
| `sections` | ListTag&lt;CompoundTag&gt; | 每个：`{"data": ByteArrayTag (LevelChunkSection 线格式), "has_only_air": ByteTag}` |
| `heightmaps` | CompoundTag | 1.21.5-: 直接 NBT；1.21.5+: `Map<Types, long[]>` 序列化为 CompoundTag |
| `block_entities` | ListTag&lt;CompoundTag&gt; | 每个 BE 的完整 NBT |
| `is_light_on` | ByteTag | 固定 0（apply 时由客户端重算光照） |

### 11.3 Live-Unload Snapshot（主一致性方案）

真实区块卸载时用 `LevelChunk` + `ChunkContentHashUtil.computeSectionHashes(chunk)` 覆盖磁盘：
- `CacheSaveQueue.enqueue` 入口检查 renderOnly → 直接跳过（保留历史快照）
- `ChunkDiskCodec.levelChunkToNbt(chunk, level)` → NBT
- `computeSectionHashes(chunk)` → `combineSectionHashesFromArray` → contentHash（与服务端同算法）
- persist 覆盖磁盘

这保证「曾加载并收到更新」的块再进应 HIT。

### 11.4 旧 packet 缓存识别

`loadChunkDataFromCache` 解压后调 `ChunkDiskCodec.isValidChunkNbt`：
- 合法 NBT（含 magic 前缀）→ 正常返回
- 非法（旧 packet 字节）→ `clientStorage.remove(pos)` 删块 + 记 miss → 全量请求

### 11.5 分段增量（缓存过期 / MISMATCH 路径）

仅当 `HassiumConfigService.isSectionDeltaEnabled()` 为真时启用（默认开）；关闭则过期与未命中一样全量。

`compareChunkHashes` MISMATCH → `sendSectionHashRequest` → `ServerChunkPushManager.handleSectionHashRequest`（按索引完整比对；视距+1 余量）→ **始终**回 `SectionDeltaS2CPacket`（`entries` + `skipped`）：
1. `entries`：读缓存 NBT；`ensureSectionsSize` 占位须为合法 `writeSection` 字节（禁止 `data=[]`）
2. 变更分段的 `SectionData.blockData` 替换 `sections[idx].data`
3. BE 写盘覆盖；主线程 apply 后再 `applyBlockEntities`（packet 线格式不带 BE）
4. 重算 `sectionHashes` + `chunkHash` → persist + `nbtToPacketBytes` apply
5. `skipped` / merge 失败 / 3s 无覆盖 → `requestFullChunks`（保底）

### 11.6 关键组件

| 组件 | 职责 |
|------|------|
| `ChunkDiskCodec` | packet↔NBT、LevelChunk→NBT、NBT 字节序列化 |
| `ChunkPacketDataCompat` | section/heightmaps NBT 读写（跨版本） |
| `CompoundTagCompat` | CompoundTag getter（1.21.5+ Optional 兼容） |
| `CacheSaveQueue` | Live-Unload 主路径（renderOnly 跳过） |
| `ClientChunkHandler` | apply 走 NBT→packet 重组（复用 `applyToLevelFromByteBuf`） |

## 12. 缓存导出（`/hassiumc cache export`）

把客户端缓存导出为可进单机世界的原版 Anvil 存档。

### 12.1 命令

```
/hassiumc cache export [<worldName>]
```

- `<worldName>` 可选；默认 `HassiumCache_<timestamp>`
- 仅客户端命令，无权限要求
- 输出目录：`<gameDir>/saves/<worldName>/`

### 12.2 输出结构

| 维度 | 目标目录 |
|------|----------|
| `minecraft:overworld` | `region/` |
| `minecraft:the_nether` | `DIM-1/region/` |
| `minecraft:the_end` | `DIM1/region/` |
| 其它 | `dimensions/<ns>/<path>/region/` |

- `level.dat` + `level.dat_old`：最小可进世界脚手架（`DataVersion`=当前客户端、`GameType=SURVIVAL`、`SpawnX/Y/Z`、`generatorName=default`）
- 每个 Region 文件：原版 2-sector header（offset + timestamp）+ `[length(4)][type=2][zlib data]`
- 转码：Hassium type126 ZSTD → NBT → zlib type2

### 12.3 异步与进度

- 提交到 `HassiumTaskExecutor` 后台线程池
- 进度按维度粒度回调，通过聊天回报
- 单 region 失败不中断整体导出（累加失败计数）
- 全局 `AtomicReference<Future>` 防重入；正在导出时拒绝新请求

### 12.4 限制说明

导出完成后聊天回报包含以下限制：

- **无实体、无玩家背包/成就**：缓存仅含方块状态与 BE NBT
- **仅为「去过的区块」快照**：空洞区块由世界生成器填充
- **模组方块需相同模组与相近 MC 版本**：否则方块可能显示为未知
- **DataVersion 与当前客户端一致**：跨版本存档升级交给原版
- **BE 取决于缓存是否含 NBT**：Live-Unload 快照包含 BE；收包 warm-stash 可能缺失

### 12.5 示例

```
/hassiumc cache export MyCacheWorld
```

输出：
```
saves/MyCacheWorld/
├── level.dat
├── level.dat_old
├── region/
│   ├── r.0.0.mca
│   └── r.0.-1.mca
├── DIM-1/region/
└── DIM1/region/
```

完成后在单机主菜单可见 `MyCacheWorld`，进入后可浏览去过的区块。
