# 区块缓存推送与进服加载

本文档是 **chunkHash 元数据推送 + 客户端缓存命中** 流水线的唯一真相源。存储文件格式见 [`architecture.md`](architecture.md)。

功能域归属：客户端侧缓存 / 影子端链路属**区块核心**（客户端进程内区块域，影子端 = 其后端引擎），网络传输经**网络核心**（客户端进程内网关）outbound 承载；服务端推送侧属**主控核心**。配置键 `chunk.*` 为区块核心配置族（2026-08-09 config-restructure：原 `clientCache.*` 重排为 `chunk.*`）。

**相关专文（细节不在此重复）：**

| 主题 | 文档 | 本文摘要 |
|------|------|----------|
| 超视渲染 | 本文 §10 | §10 |
| 磁盘 NBT / Live-Unload / 分段增量 | 本文 §11 | §11 |
| 世界导出 | 本文 §12 | §12 |
| 客户端收包 → apply → 光照落地全链路 | [`client-chunk-light-flow.md`](client-chunk-light-flow.md) | §3 客户端侧延伸 |

**卖点特性（已实现）：** 分段增量（§3 阶段二 / §11）、超视渲染（§10）、`/hassiumc export`（§12）。本地生成（SeedGen）开启时握手下发世界种子，由影子端原版写入 `level.dat`（**泄露种子**）；导出或手工把 `hassium_cache/<id>/world` 拷到 `saves/` 即可当存档。

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

客户端落盘时 contentHash **必须**等于 `combine(sectionHashes)`（与 `ChunkHashS2C` 同值）。影子端 `ShadowStorageHashes` 表落盘同源（apply/注入时重算写入）。

命中比对（影子端 `ShadowLightCompute` / 磁盘 `ShadowStorageHashes`）：

1. 内存已注入 → `ShadowStorageHashes` 表优先，无表现算
2. 未注入 → `ShadowSeedServer.loadFromDisk` 读影子端存档比对（光脏标记拦截欠光块）
3. 与服务端 `chunkHash` 相等 → 命中直接回传；不等且光干净 → 分段增量候选

## 3. 现行数据流

> **阶段二 分段增量**（默认开）：缓存过期（MISMATCH）走 `SectionHashRequest` → 影子端 apply（失败回退全量）。详见 §11。

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
enqueueDataRequest（距离优先）→ per-player `ChunkAdmissionController`（keyed pending/in-flight）
        ▼
onServerTick（真实 server tick 限流 + ACK 背压）:
  主线程: 优先 take 缓存包字节，否则 getChunk + serialize
         ≤ master.maxChunksPerTick，且受未确认批次窗口约束
         （首次 ACK 前 1 批，之后最多 10 批；deliveryId 单调）
  pushPool: ZSTD + ChunkPayloadS2C / SeedRef
        ▼
客户端 authoritative apply 成功 → `CHUNK_APPLY_ACK`（批量 deliveryId）
        ▼
服务端幂等释放 in-flight / 放行下一批

### 客户端

```
ChunkHashS2C
        │
storage 未就绪 → 暂存；就绪后批量比对（超时约 2s 回退全量）
        │
readChunkHash（MetadataTable，必要时 SectionHashStore combine）
        │
   ┌────┴────────┬────────────┐
  HIT              MISS         MISMATCH（过期）
   │                 │              │
直接回传          全量请求     分段增量（默认开）
   │                 │         SectionHashRequest
（影子端数据）  ChunkPayload    → SectionDelta → 影子端 apply
   └────────┬────────┘              │（失败/skipped/超时 → 全量）
            └───────────┬───────────┘
drainReady 帧尾 apply；原版包经网关注入（handler 直调 handleLevelChunkWithLight）
```
## 4. 主线程限流

| 机制 | 说明 |
|------|------|
| `mainThreadChunkBudgetMs` | 每帧 apply/回调共享预算（默认 15ms） |
| JoinBoost | 进服约 10s，预算从约 30ms 线性退坡到 `mainThreadChunkBudgetMs` |
| `maxChunksPerFrame` | 每 tick 缓存读取生产上限（默认 6；OVD 入队 + 影子读盘） |

控制面包（hash / 握手 / index sync 等）在 `PacketCompressionBlacklist`，避免进 PENDING 聚合窗口。

## 5. 协议（阶段一 / 阶段二）

### 阶段一（现行）

```java
ChunkHashS2CPacket(dimension, List<Entry>)
// Entry(chunkX, chunkZ, chunkHash, sectionBitmap)
```

### 阶段二：分段增量（默认开启，可关闭）

```java
SectionHashRequestC2SPacket  // 客户端 → 服务端（section hashes + 平面综合征）
SectionDeltaS2CPacket        // 服务端 → 客户端（BLOCKS 方块列表或 FULL 整段 + heightmaps + BE）
```

门控：`chunk.sectionDeltaEnabled`（默认 `true`；需同时 `chunk.enabled`）。

| 比对结果 | 开关关 | 开关开（默认） |
|----------|--------|----------------|
| HIT | 缓存队列 | 影子端直接回传 |
| MISS | 全量 | 全量 |
| MISMATCH（过期） | 全量 | 分段增量（失败回退全量） |

MISMATCH 时客户端上报每段 hash 与 48 条平面综合征；服务端只补变更格（`BLOCKS`），变更过多或 paletted 更小则整段（`FULL`），变更段 ≥75% 则整块。详见 §11.5。

旧 `ChunkMetadataS2C`（contentHash 批量元数据）协议已删除。

## 6. 关键组件

| 组件 | 职责 |
|------|------|
| `ServerChunkPushManager` | hash 批量、数据队列、tick 序列化、pushPool、delta 比对回包 |
| `MixinChunkHolder` / `MixinServerPlayer` / `MixinPlayerChunkSender` | 拦截原版全量推送 |
| `ClientMetadataHandler` | hash 比对、全量请求、blockEntity 请求 |
| `ShadowLightCompute` | 影子端 hash 比对、delta 候选/请求/超时回退、consumeLoop 应用与回传 |
| `ShadowSeedServer.applySectionDelta` | 影子端 FULL/BLOCKS 覆盖 + 清光重算 + contentHash 落表 |
| `ChunkBloomFilter` | 减少无效磁盘 IO |
| `ShadowStorageHashes` | 影子端 contentHash 表 + 光脏标记（R2 命中判定） |

## 7. 客户端淘汰

`ClientHeatIndex` 按 `chunkBytes`（单块压缩大小）与热度评分清理；超过 `maxSizeMb` 等阈值时删 Region 内单块（`storage.remove`），不整文件删除 `.mca`。

## 8. 调试

默认无热路径 INFO。排查时打开 `config/hassium/hassium-client.toml` 的 `debug.metadataLogging` / `debug.networkLogging` / `debug.cacheLogging` 等（见 architecture）。运行时统计：`/hassiumc stats`。

## 9. 待实现

- 方向性区块预加载（提高推送优先级，不改变协议）
- warm-stash 优化（收包后暂存 NBT，卸载时 dirty=false 则 flush warm 跳过 live 重算）

## 9.1 数据面与恢复（网络核心内无感迁移）

`ChunkHashS2C`、握手、index sync 与 `SectionHashRequest` 都是 TCP 控制面：经网络核心网关 outbound 帧协议（`ControlFrameCodec`）承载，在压缩黑名单中，不进入聚合 PENDING 缓冲，也不走 UDP。`ChunkPayloadS2C` 与 `SectionDeltaS2CPacket` 在已 Bind 的 UDP/KCP session 可用时经 `DataPlaneClientBundle.safeDispatch` 送入既有 `SectionDeltaDispatcher` / chunk apply 路径；无 session 或路由失败时仍由 TCP 发送，缓存一致性协议不变。

主控故障或负载触发时的恢复由**网络核心内部 L1 迁移引擎**完成（旧候选重连 / 世界定格语义已退役），对客户端原版 `Connection` 与区块核心（缓存 / 影子端）全程无感：

1. **触发**：故障 = outbound 入站静默超时（`MigrationPolicy.faultTimeoutMs`，沿用 `master.migrationFaultTimeoutMs` 键语义）；策略 = 主控负载上报（TPS / 负载均值 / 维护窗口阈值）
2. **换 outbound**：`NetworkCore` ACTIVE → MIGRATING → 关闭旧 outbound → 连接新主控，握手携带 `ResumeTicket` 续流票据（玩家 UUID + 递增 epoch + 共享密钥 HMAC 签名）
3. **续流**：主控验签通过且 epoch 递增（`ResumeTicketValidator`，防重放）→ S2C 尾 `resumeAccepted=true` → 复用既有推送链（UUID-keyed 会话表，`resyncTrackedChunks`），迁移后的 `ChunkHashS2C` 继续按正常 HIT/MISS/MISMATCH 分支处理；`resumeAccepted=false`（票据无效 / 重放）→ 会话未附着，数据推送不流入，走登录桥 / 重连兜底
4. **客户端 `Connection` 不断**：无定格、无候选重连窗口，迁移期间既有缓存照常命中，断连清理不触发
5. **终态清理只在迁移失败回退时**：迁移端点候选耗尽 / 重试超限 → 回退为真正断连（outbound 关 → IDLE → 断连清理链），影子端 `saveAll` 落盘与资源终态清理此时才执行一次

UDP/KCP 的拓扑、地址配置见 [`architecture.md`](architecture.md) §9 尾段（`master.controlReachableEndpoints` / `udpListeners`）与 §12.6；运行时冒烟见 [`runtime-smoke-test.md`](runtime-smoke-test.md#网关双主控迁移冒烟t7)。

## 10. 超视渲染（renderOnly）

### 10.1 目标

客户端渲染距离（RD） > 服务端 `view-distance` 时，用本地 `hassium_cache` 历史区块回填 `serverVD < dist ≤ clientVD` 的环形带，**仅参与渲染，不参与模拟**，且不向服务器请求视距外区块 / BE。不改服务端协议；stale 接受为「历史快照」。

### 10.2 解锁渲染距离

`MixinOptions` 注入 `Options#getEffectiveRenderDistance`（HEAD cancellable）：当 `chunk.enabled && viewDistanceExtensionEnabled && 多人游戏` 时返回客户端滑块值，绕过原版 `serverRenderDistance` 钳制。ViewArea 随之扩大（原版自动）。单人游戏不启用。

`serverRenderDistance` 经 `OptionsAccessor`（Mixin `@Accessor`）从 Options private 字段读取；未登录时 fallback `simulationDistance`。

### 10.3 数据流

```
MixinClientTick.tick
  → ViewDistanceExtensionService.update()（单例）
    → serverVD = OptionsAccessor.getServerRenderDistance()
    → 环带 = {pos : serverVD < dist(pos,player) ≤ clientVD}（圆形）
    → toLoad：跳过已 loaded/pending/未到期 miss；按切比雪夫(+欧氏次键)近距排序
    → 每 tick 最多 enqueue maxChunksPerFrame 个（缓存读取生产配额，与影子读盘共用）
    → ClientCacheLoadQueue.enqueue(pos, MainThreadDispatcher.renderOnlyPriority(pos), renderOnly=true)
      // RENDER_ONLY 层（tier*BIAS+distSq）；层序恒为 权威 > 未知任务 > 环带；priority 越小越优先
    → 未扫完 toLoad 不更新 lastPlayerPos → 下 tick 继续灌；另有 pendingLoad>128 门槛（JoinBoost 跳过）
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

`ClientLifecycleHelper.cleanupOnDisconnect`（vanilla 断连 / 登出链 HEAD）调 `ViewDistanceExtensionService.clearAllRenderOnly()`，清空 `loadedRenderOnly` + level 标记，避免重连后残留。断连落盘由影子端 `saveAll` 统一承担（`SeedGenLevelCompat.shutdown`，含 heat.idx 热度索引落盘），客户端无 dump 队列。

清理只在**真正断连**时触发——网络核心内主控迁移（§9.1）不经过断连链：迁移期间 outbound 换向、客户端 `Connection` 不断，区块核心（缓存 / 影子端 / OVD 标记）全程保留；终态资源清理仅在迁移失败回退为断连后执行。

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

- 默认配置（`maxRenderDistance=16`，vanilla 滑块上限 32）下客户端滑块 >16 时有效 RD 被钳 16、雾距仍按滑块渲染，存在穿帮可能（默认 32 时本为 no-op）
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

> **本节为旧 HBT1 客户端缓存格式的历史记录**：新架构下客户端不再读写磁盘缓存——缓存由影子端原版存档承担（`hassium_cache/<serverId>/world`，type 126 + chunkHash，见 architecture.md §6），清理由 `ShadowCacheEviction`（`heat.idx` region 文件级热度淘汰）负责。`HassiumRegionFile` / `ClientCacheDatabase` / `CacheEvictionManager` 等旧类已裁剪。

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
| `sections` | ListTag&lt;CompoundTag&gt; | 每个：`{"data": ByteArrayTag (LevelChunkSection 线格式), "has_only_air": ByteTag, "sky_light": ByteArrayTag[2048]?, "block_light": ByteArrayTag[2048]?}` |
| `heightmaps` | CompoundTag | 1.21.5-: 直接 NBT；1.21.5+: `Map<Types, long[]>` 序列化为 CompoundTag |
| `block_entities` | ListTag&lt;CompoundTag&gt; | 每个 BE 的完整 NBT |
| `is_light_on` | ByteTag | 0 = 光照未存储（apply 时客户端重算）；1 = 光照已存储（直接应用） |

**光照数据存储**：当 `is_light_on=1` 时，每个 section 的 NBT 可能包含 `sky_light` 和 `block_light`（各 2048 bytes）。

**光照缓存流水线**（Hassium 引擎统一算光，客户端本地无光照计算）：
1. 首次加载（剥光协商生效，服务端 lightStrip=true 且客户端声明 `lightComputeSupported`）：空光包 → `GatewayS2CRouter` 先投影子端而不直接 apply → `ShadowLightCompute` 注入清光 → 屏障前 sky 预播种（`ensureChunkLightLayers`：Threaded 引擎任务逐列把「源及其上方」queued 成 15，恒先于 initializeLight 执行）→ per-chunk 两阶段屏障（`initializeLight` → `lightChunk`）→ `isChunkLightComplete` 通过后才打包收敛光回传，断连 `saveAll` 落盘收敛光
2. 缓存命中（含超视 renderOnly）：影子端存档 `loadFromDisk` 同样走预播种 + 两阶段光屏障后回传（存档即收敛光；光脏标记拦截欠光块 → 走重算链）
3. 方块变更：`LightDeltaS2CPacket`（含 empty 掩码）→ `ShadowLightCompute.submitLightDelta` → `invalidateLightSections` 清对应 section → 重算收敛 → 光包回传且掩码仅含服务端声明的变更 section（未变化 section 客户端保留旧光，不回传区块数据）；SectionDelta 变更 section 清光重算（`applySectionDelta`，heightmap 覆盖后重算 sky 光源表）
4. 区块卸载 / 断连 dump：`saveAll` 全量重写（含收敛光）；欠光块 `markLightDirty`（R2 命中判定拦截）
5. 屏障完成但光层不全自动重试（≤6 轮），5s 超时后短暂续投（≤2 轮），仍不全：欠光打包 + 标脏 + 后台补发（引擎传播完成后重新回传覆盖，黑块不残留）；屏障完成瞬间丢弃该柱未消费的光桥掩码（杜绝「移除在途→最终光 offer 前」的中间态光包）；「邻柱补光」已移除（2026-08-15：光包风暴放大器，边界补光由跨柱传播 + collectLightUpdate → drainLightMasks 桥梁事件驱动覆盖，光桥只对影子区块包已落地且客户端未卸载的柱发送）；<b>打包瞬间严禁直写 raw skyEngine.queuedSections</b>（2026-08-16：与 `markNewInconsistencies` 的 fastutil 迭代器并发 → `LongArrayList.wrapped is null` NPE → runLightUpdates 中断 → POST 永不执行 → 批量超时/空层，改由 Threaded 引擎任务预播种 + 只读核验）

**引擎失败降级**：影子端启动失败 / 未握手种子 → `ShadowLightCompute` 引擎关闭，但服务端剥光同样经握手 gate 关闭（无 `lightComputeSupported` 声明 → 光随包自带），客户端无黑块。

**指标语义**（`/hassiumc stats`）：
- 展示：`区块缓存：xx%（全命中 N/B，部分命中 N/B，增量 B，应用 B）`
- 命中率 = `(全命中 + 部分命中 − 增量) / 应用`
- **全命中**：影子端存档/内存直接回传
- **部分命中**：delta 成功（整柱等价值）
- **增量**：实际变更内容（`FULL` 整段 / `BLOCKS` 按格），从命中分子扣除

**renderOnly**：`ClientCacheLoadQueue.ReadyChunk.hasCachedLight` 为 true 时不再投递影子端（空光仍经 TAIL 投递一次，Handler 只补内存 NBT 回写）。

### 11.3 影子端存档（主一致性方案）

影子端 `ShadowSeedServer` 运行期维护注入区块，断连/卸载统一 `saveAll` 落盘：
- `ChunkSerializer.write(level, chunk)`（1.21.2+ `SerializableChunkData`）→ NBT
- `ChunkContentHashUtil.computeSectionHashes(chunk)` → `combineSectionHashes` → contentHash 落 `ShadowStorageHashes`
- chunkMap.write 落盘（type 126，MixinRegionFile shadow 上下文 gate）
- **脏柱增量**：磁盘命中且未修改的柱不重写；网络注入/增量/方块更新/光增量/relight/本地生成才置脏，`saveAll` 只重写脏柱
- **并行序列化**：脏柱 NBT 序列化在临时池并行（上限 4 线程），ChunkMap 写提交仍串行回到 saver 线程，IOWorker 统一 flush（避免并发写同一 mca 与 `ChunkMap.write` 非线程安全面）

这保证「曾加载并收到更新」的块 R2 再进应 HIT。

### 11.4 旧 packet 缓存识别

`loadChunkDataFromCache` 解压后调 `ChunkDiskCodec.isValidChunkNbt`：
- 合法 NBT（含 magic 前缀）→ 正常返回
- 非法（旧 packet 字节）→ `clientStorage.remove(pos)` 删块 + 记 miss → 全量请求

### 11.5 分段增量（缓存过期 / MISMATCH）

`chunk.sectionDeltaEnabled`（默认开）。影子端 MISMATCH 且光干净时上报本地 section hash + 每非空段 48×u32 平面综合征；服务端按需比对（不常驻缓存）：

- 稀疏变更（矿道、树、岩浆柱等）→ `BLOCKS` 方块列表
- 过多（AABB ≥400 格，如炸坑）或整段 paletted 更小（铺平/灌水）→ `FULL` 整段
- 变更段占非空段 ≥75% → 整块全量
- apply 后校验 `expectedChunkHash`；失败/超时/`skipped` → 全量

影子端注入时把综合征放内存，活体方块更新失效后下次现算。BE / heightmap 仍随包；变更段清光重算。2.0.0 线格式不兼容历史。

### 11.6 关键组件

| 组件 | 职责 |
|------|------|
| `ShadowLightCompute` | hash 比对、delta 候选判定、请求/超时/回退、consumeLoop 应用与回传 |
| `ShadowSeedServer.applySectionDelta` | FULL 整段覆盖 / BLOCKS 逐格写入；hash 校验 + 清光 |
| `SectionDeltaPlanner` | 柱级 75% 整块回退；段级 BLOCKS vs FULL |
| `DataPlaneClientBundle` | 数据面帧 `TYPE_BULK_SECTION_DELTA` 分发 → `submitDelta`（默认 dispatcher） |

## 12. 缓存导出（`/hassiumc export`）

把影子端世界目录整体拷贝为导出存档（`hassium_exports/<cacheId>`；保留 type 126 + chunkHash 格式，原版翻译后续提供）。

### 12.1 命令

```
/hassiumc export [serverIp] [seed]
```

- `<serverIp>` 可选；指定时导出该服务器的影子端世界；不指定时导出当前连接的服务器
- `seed` 可选；**已忽略**。种子由影子端用原版 `saveDataTag` 写入 `level.dat`（`WorldOptions`），拷贝即可
- 亦可不进游戏、不用本命令：把 `<gameDir>/hassium_cache/<cacheId>/world` 复制到 `<gameDir>/saves/<任意名>/`（须已离开该服务器，避免占用 `session.lock`）
- 仅客户端命令，无权限要求
- 输出目录：`<gameDir>/hassium_exports/<cacheId>/`（`cacheId` = `server_<IP>_<端口>`，或当前连接服务器的 serverId）

### 12.2 输出结构

数据源 = 影子端世界目录 `hassium_cache/<serverId>/world` 整体拷贝，目录结构与源一致：

| 维度 | 目标目录 |
|------|----------|
| `minecraft:overworld` | `region/` |
| `minecraft:the_nether` | `DIM-1/region/` |
| `minecraft:the_end` | `DIM1/region/` |
| 其它 | `dimensions/<ns>/<path>/region/` |

- `level.dat` / `level.dat_old`、Region 文件等整体拷贝（跳过 `session.lock`）；`level.dat` 为影子端原版写出，含 WorldOptions 种子
- **格式保留**：type 126 + chunkHash 落盘格式不变（与影子端存储写路径一致）
- **原版翻译**（type 126 → 原版格式）后续提供；届时导出的世界方可直接进单机

### 12.3 异步与进度

- 提交到后台线程池异步拷贝；完成后聊天回报「导出完成 / 导出失败」
- 未连接时回报「未连接服务器，无法确定导出目标」；源目录缺失时回报「未找到影子端世界目录」
- 全局 `AtomicReference<Future>` 防重入；正在导出时拒绝新请求

### 12.4 限制说明

- **无实体、无玩家背包/成就**：影子端世界仅含区块/光照与方块实体数据
- **格式保留 type 126**：需 Hassium 读取；翻译为原版格式后续提供
- **仅为「去过的区块」快照**：空洞区块由世界生成器按 `level.dat` 种子填充（本地生成开启时即为服务端种子）
- **模组方块需相同模组与相近 MC 版本**：否则方块可能显示为未知
- **BE 取决于影子端缓存是否含 NBT**：Live-Unload 快照包含 BE；收包 warm-stash 可能缺失
- **光照随区块保留**：`is_light_on=1` 的区块携带 `SkyLight` / `BlockLight`

### 12.5 示例

```
/hassiumc export 192.168.1.100_25565
```

输出：
```
hassium_exports/server_192.168.1.100_25565/
├── level.dat
├── level.dat_old
├── region/
│   ├── r.0.0.mca
│   └── r.0.-1.mca
├── DIM-1/region/
└── DIM1/region/
```

目录结构与 `hassium_cache/server_192.168.1.100_25565/world/` 一致；完成后聊天回报 `导出完成: <目标路径>`。

## 13. 客户端 Bloom 同步与服务端直推（永久虚空修复）

### 13.1 背景：永久虚空根因

服务端数据队列（`ServerChunkPushManager.enqueueDataRequest`）在 drain 时对已出视距的任务**静默丢弃**：飞行中队列积压（`master.maxChunksPerTick` 默认 5）时，轮到处理时玩家已前移，任务被丢弃；客户端请求无超时重试，且静止后不再触发新的 `trackChunk`（块已在视距内），→ 前方 30° 扇形虚空永久存在。方向加权（`FORWARD_BIAS`）只改变优先级，堵不住丢弃漏洞。

### 13.2 机制

```
客户端（ClientBloomSyncTracker）
  ├─ storage 就绪 → 发全量位图（本地 ChunkBloomFilter 序列化，full=true）
  ├─ 新缓存落盘（persist）→ 攒增量 → ≥64 块且冷却 5s → 按批构建独立位图（full=false）
  └─ 断连（clearPendingState）→ 重置，重连后重发全量

服务端（ServerChunkPushManager）
  ├─ per-player Bloom 层列表：full → 覆盖；append → 追加（上限 64 层，溢出丢最旧）
  ├─ 分流（trackChunk / sendChunk / resync 提交点）：
  │    mightContain(pos, dim) == false（确定无缓存）→ 发 hash（contentHash 先行）+ 主动入队直推
  │    命中或 Bloom 未就绪 → 仅发 hash（客户端对比 HIT/MISS/MISMATCH）
  ├─ 入队去重（per-player 在队集合）：直推与客户端请求同块不重复推
  ├─ 出界不丢弃 → 待命集合，折返/静止后重新在视距内时恢复入队；10s 超时才真丢
  └─ resync 等待首个 Bloom（≤5s，旧客户端无 Bloom 则超时后原路径 fallback）
```

### 13.3 为什么正确

- Bloom 无假阴性：miss = 确定客户端无缓存 → 直推不会浪费（hash 先行保证客户端能暂存 contentHash，避免 0→1 翻转）
- 假阳性由客户端 hash 对比 MISS/MISMATCH 兜底：MISS → 请求 → 服务端直推链路；MISMATCH → section delta
- 位图只增不减：客户端淘汰/过期不通知服务端（假 hit 成本 = 一次 hash 包，无害）
- 增量丢失无害：服务端 miss → 直推（正确性兜底）
- 直推任务出界丢弃无害：trackChunk 触发时机与丢弃判定一致，丢弃 = 客户端不再需要

### 13.4 协议

`ClientBloomSyncPacket`（C2S，`client_bloom_sync_c2s`）：`boolean full + byte[] bloomBytes`（`[4B size][4B hashCount][bitSet]`）。

握手包（C2S）尾部追加客户端坐标（`double x, double z`，append-only 兼容旧服务端）：服务端校正 resync 视距中心（迁移/重连时服务端玩家对象位置滞后在出生点）；客户端发送握手时同步刷新 `MainThreadDispatcher` 位置缓存（不等首帧 tick）。

### 13.5 兜底

- 客户端全量请求超时重试（8s）：`PENDING_FULL_REQUESTS`，收到数据（`onChunkDataReceived`）清除
- 服务端 resync 等 Bloom 超时 5s → 无 Bloom 原路径（发 hash）
- 出界任务待命 10min 超时 → 真丢弃（玩家已远离；10s 对移动探索太短，
  frontline 任务被过早丢弃后客户端静止/折返时无新请求可触发，会造成“永久”扇形/十字虚空）

### 13.6 SeedGen 两级缓冲（FIFO 头部阻塞修复）

`SeedGenExecutor` 的缓冲分两级：`pendingLive`（服务端 SeedRef）与 `pendingPregen`
（盲预生成，contentHash=0），由 drain 按当前玩家位置**最近优先**释放进有界工作队列
（≤96 槽），活体 SeedRef 永远先于盲预生成。原实现为单条 FIFO（`ConcurrentLinkedQueue`），
移动探索时新到达的当前视野 SeedRef 排在更早路径/初始 resync/441 个盲预生成条目之后：
工作队列 96 槽被旧块占满，近处块几十秒后才生成，落地时玩家已走远被 vanilla
丢弃（`Ignoring chunk since it's not in the view range`）→ 身边持续空洞。
盲预生成条目永不超时（`SeedGenQueue.expire` 与 `peekNearest` 均只对 hash≠0 生效）。

### 13.7 SeedGen 自愈熔断与出界待命延长（mismatch 风暴修复）

同一会话内若客户端对 pristine 区块大量回退全量（说明本地世界gen与服务端不一致，
例如跨版本/数据包/客户端侧世界gen差异），`ServerChunkPushManager` 会在连续
`SEED_GEN_DISABLE_THRESHOLD`（16）次 pristine 全量请求后对该玩家**自动停发 SeedRef**，
改走全量推送；同时把出界待命任务超时从 10s 提高到 10min，避免移动时前排任务
被过早丢弃后无法自动补回。熔断状态随玩家断开/服务端停止清理。
