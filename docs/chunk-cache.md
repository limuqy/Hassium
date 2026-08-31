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
3. **续流**：主控验签通过且 epoch 递增（`ResumeTicketValidator`，防重放）→ S2C 尾 `resumeAccepted=true` → 复用影子虚拟玩家的原版 `ChunkMap` tracking 与缓存状态；`resumeAccepted=false`（票据无效 / 重放）→ 会话未附着，走登录桥 / 重连兜底
4. **客户端 `Connection` 不断**：无定格、无候选重连窗口，迁移期间既有缓存照常命中，断连清理不触发
5. **终态清理只在迁移失败回退时**：迁移端点候选耗尽 / 重试超限 → 回退为真正断连（outbound 关 → IDLE → 断连清理链），影子端 `saveAll` 落盘与资源终态清理此时才执行一次

UDP/KCP 的拓扑、地址配置见 [`architecture.md`](architecture.md) §9 尾段（`master.controlReachableEndpoints` / `udpListeners`）与 §12.6；运行时冒烟见 [`runtime-smoke-test.md`](runtime-smoke-test.md#网关双主控迁移冒烟t7)。

## 10. 超视渲染（当前链路不启用）

影子端原版化后，区块 admission、加载、卸载和推送全部由影子 `ServerPlayer` 对应的 `ServerChunkCache` / `ChunkMap` 管理。本节旧的 `renderOnly` 环带逻辑不属于当前 chunk-core 链路，不能参与远程区块请求或客户端区块生命周期。

历史实现仍可能存在于兼容类或旧配置说明中，但不得作为当前链路行为依据；相关代码清理以 `.omp/workflows/shadow-chunk-loader-originalization/REQ.md` 为准。

### 10.1 当前边界

当前 chunk-core 不启用旧的 `renderOnly` 环带、客户端视距扩展或独立 OVD admission。影子端的虚拟 `ServerPlayer`、`ServerChunkCache` 和 `ChunkMap` 是区块 tracking、加载、卸载及推送的唯一 owner。

旧实现的设计记录不再作为运行时契约；需要查询历史方案时使用版本控制记录，不在本文件继续维护已删除的客户端区块状态机。

### 10.2 不做

- 不由真实客户端枚举影子区块或维护 halo。
- 不向服务端请求影子玩家 tracking 范围之外的区块。
- 不以客户端 `hasChunk`、Bloom、chunk hash 回执或 pending-confirm 决定影子端 admission。
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

## 13. 原版 tracking 与缓存边界

影子端不再维护客户端 `ShadowChunkLoader`、halo、Bloom admission 或 chunkHash 探活。唯一 tracking owner 是影子 `ServerPlayer` 对应的 `ServerChunkCache` / `ChunkMap`：

1. 真实玩家位置单向同步给影子虚拟玩家。
2. 原版 `ChunkMap` 决定 `ChunkHolder` 的加载和卸载范围。
3. `scheduleChunkLoad` 先通过 `MixinRegionFile` 读取 type 126；未命中时进入原版生成链。
4. 服务端校验允许的 SeedGen 结果在 pre-LIGHT 汇合，由影子 `ThreadedLevelLightEngine` 算光。
5. 影子 vanilla connection 发送官方 chunk+light 与 forget packet；真实客户端不提交 admission 请求。

缓存目录仍为 `hassium_cache/<serverId>/world`，存储和热度淘汰由影子服务端承担。Bloom、`CHUNK_HASH`、`PENDING_FULL_REQUESTS` 和旧客户端主动 pull 不属于当前区块生命周期；若协议类型仍保留，仅作为兼容定义，不得有运行时发送调用。
