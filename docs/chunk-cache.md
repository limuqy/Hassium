# 区块缓存推送与进服加载

本文是 **ShadowPull 统一 Compare + Pull 流水线** 的真相源。客户端和服务端通过原版 `CustomPayload` 传输 ShadowPull；旧 `25566` Gateway 不参与本功能。
功能域归属：客户端侧缓存 / 影子端链路属**区块核心**；服务端以原版 `25565` 连接接收 ShadowPull。配置键 `chunk.*` 为区块核心配置族。

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

### 3.1 统一 ShadowPull

原版 `ClientPacketListener.handleLevelChunkWithLight` 收到区块时，已有本地影子基线的柱进入唯一 `ShadowPull` 请求；无基线时保留原版 FULL 作为首次建基线路径。请求同时携带 `chunkHash`、`sectionHash` 和 section 平面综合征。

服务端统一返回：

```text
UNCHANGED → 复用影子缓存
DELTA     → 复用现有 SectionDeltaPlanner / SectionDeltaS2CPacket
FULL      → 返回原版 chunk+light payload
ERROR     → 客户端重新请求权威 FULL
```

`DELTA` 不再走独立的客户端入口；它作为 ShadowPull 的终态复用现有分段增量编码和应用逻辑。

```text
原版 25565 CustomPayload
    └─ shadow_pull_request_c2s
         └─ ShadowPullHandler
              └─ UNCHANGED / DELTA / FULL / ERROR
```

### 3.2 正常 tracking 推送

## 3. 现行数据流

> 服务端原版 `ServerPlayer` / `ChunkMap` 决定区块 tracking、可见范围与 forget；原版 `ClientChunkCache` 是唯一客户端生命周期真相源。Hassium 不维护第二套 admission/unload 状态。

### 正常 tracking 推送

```text
ServerPlayer / ServerChunkCache / ChunkMap
        │  原版 tracking 决定 chunk / forget
        ▼
标准 25565 Connection
        │  ClientboundLevelChunkWithLightPacket
        ▼
ClientPacketListener.handleLevelChunkWithLight
        │
        ├─ 无影子基线 → 原版 apply，建立首次基线
        └─ 有影子基线 → ShadowPull Compare + Pull
        ▼
ClientChunkCache.replaceWithPacketData → renderer
```

正常原版首包应记为 `serverPushAppliedCount`；`fullChunkRequestCount` 只表示 ShadowPull 回退的权威 FULL。探针还记录累计 apply、完整 `ClientChunkCache.loadedChunks`，以及 trace 候选在采样时刻实际驻留的数量。

### Compare + Pull / Generate + Validate

该分支处理 `SeedRef`、影子缓存重放和本地生成失败；不参与区块可见范围或卸载决策。

```text
候选区块
    │
    ├─ 本地生成：必须同时有客户端开关、服务端许可和真实 seed
    └─ 磁盘缓存：读取 ShadowStorageHashes / 影子端存档
    │
    ▼
ShadowPullRequestC2S(chunkHash, sectionHashes, planes)
    │
    ▼
服务端权威比较
    ├─ UNCHANGED → 影子端 materialize 后回传官方 chunk+light
    ├─ DELTA     → 复用 SectionDeltaS2CPacket → 影子端 apply + 重算光照
    ├─ FULL      → 影子端注入服务端原版 chunk+light payload
    └─ ERROR     → 无 baseline ShadowPull 请求权威 FULL
    │
    ▼
ClientPacketListener.handleLevelChunkWithLight
```

- `UNCHANGED` 只有影子端实际 materialize 柱时才算命中；hash 有记录但找不到柱时立即重试无 baseline 的权威 FULL。
- 本地生成必须同时满足客户端开关、服务端许可、真实 seed 和 content hash；任一条件缺失、生成失败或 hash mismatch 均提交无 baseline ShadowPull。
- `DELTA` 的编码、传输、应用和失败回退全部由统一 ShadowPull 管理，不再发送旧独立分段请求。

## 4. 主线程限流

| 机制 | 说明 |
|------|------|
| `mainThreadChunkBudgetMs` | 每帧 apply/回调共享预算（默认 15ms） |
| JoinBoost | 进服约 10s，预算从约 30ms 线性退坡到 `mainThreadChunkBudgetMs` |
| `maxChunksPerFrame` | 每 tick 缓存读取生产上限（默认 6；OVD 入队 + 影子读盘） |

ShadowPull 通过原版 `CustomPayload` 发送；它不使用旧 Gateway Envelope、UDP 或独立数据面。

## 5. 协议边界

```java
ShadowPullRequestC2SPacket  // 客户端带影子本地 baseline 请求权威比较
ShadowPullResponseS2CPacket // UNCHANGED / DELTA / FULL / ERROR
SectionDeltaS2CPacket       // ShadowPull 的 DELTA payload 编码
```

门控：`chunk.sectionDeltaEnabled`（默认 `true`；需同时 `chunk.enabled`）。

| 比对结果 | 分段增量关闭 | 分段增量开启（默认） |
|----------|--------------|----------------------|
| UNCHANGED | 影子端回放 | 影子端回放 |
| 不同      | FULL        | DELTA，规划失败则 FULL |


## 6. 关键组件

| 组件 | 职责 |
|------|------|
| `ServerChunkPushManager` | 服务端原版 tracking 推送、ShadowPull 权威比较、复用分段增量规划 |
| `MixinChunkHolder` / `MixinServerPlayer` / `MixinPlayerChunkSender` | 拦截已废弃的旧推送路径 |
| `ShadowPullClient` | 原版首包命中时发起 Compare + Pull，收口 UNCHANGED / DELTA / FULL / ERROR |
| `ShadowLightCompute` | 影子端基线快照、缓存回放、分段增量应用与光照收敛 |
| `ShadowSeedServer.applySectionDelta` | 影子端 FULL/BLOCKS 覆盖、清光重算、contentHash 落表 |
| `ShadowStorageHashes` | 影子端 contentHash 表与光脏标记 |

## 7. 客户端淘汰

`ClientHeatIndex` 按 `chunkBytes`（单块压缩大小）与热度评分清理；超过 `maxSizeMb` 等阈值时删 Region 内单块（`storage.remove`），不整文件删除 `.mca`。

## 8. 调试

默认无热路径 INFO。排查时打开 `config/hassium/hassium-client.toml` 的 `debug.metadataLogging` / `debug.networkLogging` / `debug.cacheLogging` 等（见 architecture）。运行时统计：`/hassiumc stats`。

## 9. 待实现

- 方向性区块预加载（提高推送优先级，不改变协议）
- warm-stash 优化（收包后暂存 NBT，卸载时 dirty=false 则 flush warm 跳过 live 重算）


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

**光照缓存流水线**（影子端统一算光，真实客户端只消费标准原版区块包）：
1. 首次 FULL：服务端标准 `ClientboundLevelChunkWithLightPacket` 进入原版 `ClientPacketListener`；影子端以同一权威数据建立或更新本地基线。
2. `UNCHANGED` 缓存回放：影子端存档 `loadFromDisk` 走预播种 + 两阶段光屏障后回传标准 chunk+light；光脏标记拦截欠光块并回退权威 FULL。
3. `DELTA`：`ShadowPullResponseS2CPacket` 内嵌的 `SectionDeltaS2CPacket` 进入 `ShadowLightCompute.submitDelta`，变更 section 清光重算；heightmap 覆盖后重算 sky 光源表。
4. 区块卸载 / 断连 dump：`saveAll` 全量重写收敛光；欠光块 `markLightDirty`。
5. 屏障完成但光层不全自动重试（≤6 轮），5s 超时后短暂续投（≤2 轮）；仍不全则欠光打包、标脏并在传播完成后回传覆盖。打包瞬间不直写 `skyEngine.queuedSections`，预播种由 Threaded 引擎任务完成。

**引擎失败降级**：影子端未启动、未获许可或没有真实 seed 时，不发生本地生成；统一 ShadowPull 请求服务端 FULL，客户端仍消费随原版包携带的光照。

**指标语义**（`/hassiumc stats`）：
- 展示：`区块缓存：xx%（全命中 N/B，部分命中 N/B，增量 B，应用 B）`
- 命中率 = `(全命中 + 部分命中 − 增量) / 应用`
- **全命中**：收到 `UNCHANGED` 后影子端存档/内存实际 materialize 并回传；仅 hash 相等但无柱时不记命中。
- **部分命中**：`DELTA` 成功提交；`sectionDeltaRequestsSent` 计实际收到的 `DELTA` 终态，不存在独立分段请求包。
- **过期**：带 baseline 的 ShadowPull 返回 `FULL`；**未命中**：无 baseline 的权威 FULL 返回。两者按响应 `requestId` 模式分类，断连时清理在途状态。
- **增量**：实际变更内容（`FULL` 整段 / `BLOCKS` 按格），从命中分子扣除。

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

`chunk.sectionDeltaEnabled`（默认开）。影子端 MISMATCH 且光干净时，`ShadowPullRequestC2SPacket` 携带本地 section hash + 每非空段 48×u32 平面综合征；服务端按需比对（不常驻缓存）：

- 稀疏变更（矿道、树、岩浆柱等）→ `BLOCKS` 方块列表
- 过多（AABB ≥400 格，如炸坑）或整段 paletted 更小（铺平/灌水）→ `FULL` 整段
- 变更段占非空段 ≥75% → 整块全量
- apply 后校验 `expectedChunkHash`；失败/超时/`skipped` → 全量

影子端注入时把综合征放内存，活体方块更新失效后下次现算。BE / heightmap 仍随包；变更段清光重算。2.0.0 线格式不兼容历史。

### 11.6 关键组件

| 组件 | 职责 |
|------|------|
| `ShadowLightCompute` | 影子基线快照、缓存 materialize、DELTA 应用与光照收敛 |
| `ShadowSeedServer.applySectionDelta` | FULL 整段覆盖 / BLOCKS 逐格写入；hash 校验 + 清光 |
| `SectionDeltaPlanner` | 柱级 75% 整块回退；段级 BLOCKS vs FULL |
| `ShadowPullClient` | 将响应 DELTA 提交到 `submitDelta`，失败统一回退权威 FULL |

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
缓存目录仍为 `hassium_cache/<serverId>/world`，存储和热度淘汰由影子服务端承担。Bloom、`CHUNK_HASH` 和旧独立分段请求不属于当前客户端区块入口；分段增量现作为统一 `ShadowPull` 的 `DELTA` 终态复用。
