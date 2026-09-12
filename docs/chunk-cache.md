# 区块缓存推送与进服加载

本文是 **ShadowPull 统一 Compare + Pull 流水线** 的真相源。客户端和服务端通过原版 `CustomPayload` 传输 ShadowPull。功能域归属：客户端侧缓存 / 影子端链路属**区块核心**；服务端以原版连接接收 ShadowPull。配置键 `chunk.*` 为区块核心配置族。

**相关专文（细节不在此重复）：**

| 主题 | 文档 | 本文摘要 |
|------|------|----------|
| 原版对齐交付边界 | [`architecture.md`](architecture.md) §6 | §13 |
| 磁盘 NBT / 影子端存档 / 分段增量 | 本文 §11 | §11 |
| 世界导出 | 本文 §12 | §12 |
| 客户端收包 → apply → 光照落地全链路 | [`client-chunk-light-flow.md`](client-chunk-light-flow.md) | §3 客户端侧延伸 |

**卖点特性（已实现）：** 统一 ShadowPull（§3）、分段增量（§11.5）、`/hassiumc export`（§12）。本地生成（SeedGen）开启时握手下发世界种子，由影子端原版写入 `level.dat`（**泄露种子**）；导出或手工把 `hassium_cache/<id>/world` 拷到 `saves/` 即可当存档。

> **超视渲染（OVD）为现行功能**（影子双窗，默认开启）：影子端 vanilla tracking 仍是权威窗区块交付的唯一 owner；OVD 窗只从本地源（注入 / 盘）回填，详见 §10。

## 1. 目标与约束

- 用 **内容哈希**（非 `inhabitedTime`）判断缓存是否可复用
- section 方块数据哈希排除会每 tick 变化的 blockEntity NBT
- blockEntity 不进缓存命中域：区块 apply 后再走专用请求（`block_entity_request_c2s` / `block_entity_data_s2c`）
- 客户端缓存 payload 为**磁盘 chunk `CompoundTag`**（影子端原版存档格式），跨大版本约束放宽到 NBT schema 兼容

## 2. 哈希

```
sectionHash = hash(section 方块 palette + 生物群系序列化字节)
chunkHash   = combineSectionHashes(sectionIndex → sectionHash)
```

实现：`ChunkContentHashUtil`。服务端与客户端算法一致。

客户端落盘时 contentHash **必须**等于 `combine(sectionHashes)`（与服务端权威 `chunkHash` 同值）。影子端 `ShadowStorageHashes` 表落盘同源（apply/注入时重算写入）。

命中比对（影子端 `ShadowLightCompute` / 磁盘 `ShadowStorageHashes`）：

1. 内存已注入 → `ShadowStorageHashes` 表优先，无表现算
2. 未注入 → `ShadowSeedServer.loadFromDisk` 读影子端存档比对（光脏标记拦截欠光块）
3. 与服务端 `chunkHash` 相等 → 命中直接回传；不等且光干净 → 分段增量候选

## 3. 现行数据流

> 服务端原版 `ServerPlayer` / `ChunkMap` 决定区块 tracking、可见范围与 forget；原版 `ClientChunkCache` 是唯一客户端生命周期真相源。Hassium 不维护第二套 admission/unload 状态。

### 3.1 正常 tracking 推送

```text
ServerPlayer / ServerChunkCache / ChunkMap
        │  原版 tracking 决定 chunk / forget
        ▼
标准连接
        │  ClientboundLevelChunkWithLightPacket
        ▼
ClientPacketListener.handleLevelChunkWithLight
        │
        ├─ 无影子基线 → 原版 apply，建立首次基线
        └─ 有影子基线 → ShadowPull Compare + Pull
        ▼
ClientChunkCache.replaceWithPacketData → renderer
```

正常原版首包应记为 `serverPushAppliedCount`；`fullChunkRequestCount` = 网络权威 FULL 落地（new+stale，无基线读 `newFullChunkRequestCount`）。唯一落地总数读 `landedTotal`（= `clientLandedChunkCount`，含 cacheHit 重交付，按坐标去重）；`clientAppliedChunkCount` 是来源事件和，可因跨源同一坐标重复而大于 landed。探针还记录累计 apply、完整 `ClientChunkCache.loadedChunks`，以及 trace 候选在采样时刻实际驻留的数量。

### 3.2 统一 ShadowPull（Compare + Pull / Generate + Validate）

原版 `ClientPacketListener.handleLevelChunkWithLight` 收到区块时，已有本地影子基线的柱进入唯一 `ShadowPull` 请求；无基线时保留原版 FULL 作为首次建基线路径。请求同时携带 `chunkHash`、`sectionHash` 和 section 平面综合征。

该分支处理影子缓存重放、本地生成及其失败回退；不参与区块可见范围或卸载决策。

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
| JoinBoost | 进服起 30s 宽松封顶窗口（`JOIN_BOOST_CAP_MS`），预算 30ms、apply 活跃可续期但总窗口不超 30s，之后回落 `mainThreadChunkBudgetMs` |
| `maxChunksPerFrame` | 每 tick 缓存读取生产上限（默认 6；影子入队 + 影子读盘；主线程消费只受时间预算） |

ShadowPull 通过原版 `CustomPayload` 发送；不使用任何网关 Envelope、UDP 或独立数据面（均已退役）。

## 5. 协议边界

```java
ShadowPullRequestC2SPacket  // 客户端带影子本地 baseline 请求权威比较
ShadowPullResponseS2CPacket // UNCHANGED / DELTA / FULL / ERROR
SectionDeltaS2CPacket       // ShadowPull 的 DELTA 终态内嵌载荷
BlockEntityRequestC2S / BlockEntityDataS2C  // BE 专用请求（不进缓存命中域）
LightDeltaS2CPacket         // 增量光变更掩码
```

全部走原版 `CustomPayload`（`hassium:*` 命名空间，`PacketId` / `HassiumChannels` 注册）。

## 6. 关键组件

| 组件 | 职责 |
|------|------|
| `ServerChunkPushManager` | 服务端原版 tracking 推送、ShadowPull 权威比较、复用分段增量规划 |
| `MixinChunkHolder` / `MixinServerPlayer` / `MixinPlayerChunkSender` | 拦截已废弃的旧推送路径 |
| `ShadowPullClient` | 原版首包命中时发起 Compare + Pull，收口 UNCHANGED / DELTA / FULL / ERROR |
| `ShadowLightCompute` | 影子端基线快照、缓存回放、分段增量应用与光照收敛 |
| `ShadowSeedServer.applySectionDelta` | 影子端 FULL/BLOCKS 覆盖、清光重算、contentHash 落表 |
| `ShadowStorageHashes` | 影子端 contentHash 表与光脏标记 |

## 7. 缓存淘汰（影子端）

淘汰由影子服务端承担（旧客户端 `ClientHeatIndex` 单块删除已裁剪）：

- 热度索引 `ShadowRegionHeat`（`heat.idx`，按 `r.X.Z.mca` region 文件计，per-server：`hassium_cache/<serverId>/heat.idx`）；解析文件内容计热度，不靠 mtime
- 热度分数 = `recencyWeight`(0.7) × 最近访问 + `frequencyWeight`(0.3) × 访问频率；低于 `hotScoreThreshold`(0.3) 视为冷
- 容量 = 各维度 `*.mca` 的 `Files.size` 之和，超 `maxSizeMb`(4096) 触发清理；`targetSizeMb`=0 自动
- **删除粒度 = 整文件**：`ShadowSeedServer.deleteRegion` → 存储管理器卸映像并删 `.mca`（真实缩小占用；不删 region 内单块）
- 安全 gate：本会话 `injectedChunks` 落到的 region 整文件跳过
- 清理检查间隔 `cleanupIntervalTicks`(6000)；每轮最多 `minCleanupBatchSize`(100) 个 region 文件

## 8. 调试

默认无热路径 INFO。排查时打开 `config/hassium/hassium-client.toml` 的 `debug.metadataLogging` / `debug.cacheLogging` / `debug.chunkApplyLogging` 等（见 architecture §10）。运行时统计：`/hassiumc stats`。

## 9. 待实现

- 方向性区块预加载（提高推送优先级，不改变协议）
- warm-stash 优化（收包后暂存 NBT，卸载时 dirty=false 则 flush warm 跳过 live 重算）

## 10. 超视渲染（OVD，影子双窗）

> 2026-09 设计重做：废弃旧「客户端环带 admission 状态机」（`ViewDistanceExtensionService` / `OvdLocalGenerator`，已删）。现行 OVD = **影子 tracking 扩窗 + 权威/OVD 双窗分流**；客户端只抬半径并拦 Forget，不枚举环带、不 miss 重试、无延迟卸载。

### 10.1 目标

多人服且客户端 RD 滑块 > 服务端视距时，用**影子端本地已有地形**（注入表 / 磁盘）回填环带，使曾探索区域在视距外仍可见。

| 场景 | 行为 |
|------|------|
| 单人 / 局域网 | 不启用（无 serverVD 钳制） |
| `chunk.viewDistanceExtensionEnabled=false` | 半径回落 serverVD，走权威窗 only |
| 影子引擎未激活 / 失败降级 | 同上 |
| 滑块 ≤ serverVD | 无 OVD 窗 |

### 10.2 拓扑（双窗）

```text
影子 ChunkMap viewDistance = effectiveClientVD
  = OVD 开 && 滑块 > serverVD ? min(滑块, maxRenderDistance) : serverVD(+1 权威边距)

┌─────────────────────────────────────┐
│  影子 ticket 加载窗（chebyshev）     │
│   ┌───────────────────────────┐     │
│   │ 权威窗 = isChunkInRange(   │     │  → Compare+Pull / bootGrid / sweep
│   │            serverVD)       │     │  → 真服务端数据源
│   └───────────────────────────┘     │
│   OVD 窗 = client 窗 − 权威窗        │  → injected → loadFromDisk
│                                     │  → 禁止 ShadowPull / 禁止真服请求
└─────────────────────────────────────┘
```

**职责切分**

| 端 | 做 | 不做 |
|----|----|------|
| 客户端 | 捕获 serverVD；算 effectiveClientVD；抬 `ClientChunkCache` 半径；Forget 仍在 effective 窗内则取消 drop；几何同步给影子 | 环带枚举 / miss 退避 / 延迟卸载表 / 独立 admission |
| 影子端 | 扩 `setChunkViewDistance(effective)`；权威窗 pull 契约不变；OVD 窗本地源分流；光管线 `renderOnly` 回传官方包 | 向真服请求 OVD 柱；OVD 柱发 `ShadowPullRequest` |

### 10.3 数据流

```text
真服 tracking(serverVD) ──权威包──▶ 影子注入/算光/落盘
                                      │
影子 ticket(clientVD)                 │
  ├─ 权威窗柱 → 现有 scheduleLoad / pending pull / compare
  └─ OVD 窗柱 → injected? → disk?
                 └─ 均无：空 Proto 站位（不 pull）
                 └─ 有数据：submitPreLight(renderOnly=true)
                         → 光收敛 → drainReady
                         → 官方 ClientboundLevelChunkWithLight
                         → 客户端（半径已抬高，Storage 接收）
```

**外圈→内圈基线**：OVD 柱物化后进 `injectedChunks`；玩家移动使该柱进入权威窗时，`drainSelections` 见 `injectedChunk != null` 跳过 pull，`onChunkMaterialized` 桥带基线走 Compare（UNCHANGED/DELTA），无需二次读盘。

### 10.4 客户端最小边界

1. **半径抬高**：`ClientChunkCache.updateViewRadius(effectiveClientVD)`（每 tick 守护，防 `SetChunkCacheRadius` 缩回后 apply 被 `inRange` 丢弃）。
2. **Forget 保留**：真服 Forget 时若 pos 仍在 effective 窗（`!serverRange && clientRange`）→ 取消 drop。玩家走出权威圈时真服会 Forget，柱尚在 client 窗。
3. **无延迟卸载**：`ovdUnloadDelaySecs` 功能取消；出 effective 窗后由 `ClientChunkCache` 原版 Storage 窗口自然 drop。

### 10.5 门禁不变量

```text
∀ OVD 柱：ShadowPull 发送数 = 0
∀ pull 柱：isChunkInRange(serverVD) == true   // AUTHORITY_MARGIN 契约不变
bootGrid / sweepVisibleShape 半径仍是 serverVD，不被 clientVD 污染
服务端 ShadowPullRequestValidator 仍是安全网（误发必 RANGE 拒绝）
OVD 回传记 ovd 指标，不进缓存命中率分母
```

### 10.6 配置键（恢复，不含延迟卸载）

| 键 | 默认 | 说明 |
|----|------|------|
| `chunk.viewDistanceExtensionEnabled` | true | 超视渲染总开关（依赖 `chunk.enabled`；与 Bobby 互斥） |
| `chunk.maxRenderDistance` | 16 | effective clientRD 上限（2–64） |

`chunk.ovdUnloadDelaySecs` **不再恢复**（延迟卸载取消）；`chunk.ovdLocalGeneration`（OVD 窗本地生成）**已退役删除**，OVD 窗回填只读本地已有数据（注入 / 盘）。

### 10.7 不做（延续原版化边界）

- 不由真实客户端枚举影子区块或维护 halo / pending / miss 表。
- 不向服务端请求权威窗之外的区块；OVD 数据仅本地源。
- 不以客户端 `hasChunk`、Bloom、chunk hash 回执决定影子 admission。
- 不给 OVD 柱请求 blockEntity。
- 不复活 HBT1 客户端磁盘缓存；不恢复 `LoginCaps.OVD`（纯客户端本地能力）。

### 10.8 分期与验收

| 阶段 | 内容 | 验收（1.20.1 fabric classic） |
|------|------|------------------------------|
| P0 | 影子扩窗 + 双窗分流 + 客户端半径/Forget | R2 环带可见；pull range 拒 = 0；OVD 发送 pull = 0 |
| P1 | 指标 `ovdLoaded` 等 + miss 空置语义 | `/hassiumc stats` 超视行；G1 `ovdLoaded>0` |
| P3 | L1 + mod-compat 超视条目 | 与 shape4/bootgrid 指标不回归 |

### 10.9 风险

1. 扩窗使 `processUnloads` / 光邻域 ticket 按 clientVD 走，OVD 柱占影子内存与算光队列 → 权威队列深时暂停 OVD 泵。
2. 与 Bobby 等视距模组双主冲突不变。
3. 若 P0 触发 pull 契约回归，回退 = `setChunkViewDistance` 改回 `serverVD+1`，双窗分流代码可保留作旁路开关。
4. **重连回放**：`resetRequestDedupForReconnect` 必须清 `shadowApplyEpochs`；否则上一 `ClientChunkCache` 的落地凭据会让 materialize/redeliver 误判「客户端已有」，R2 只回放部分柱（实测 1529→775，移动新区不出现）。

## 11. 磁盘 NBT 缓存格式（影子端存档）

> 旧 HBT1 客户端磁盘缓存已裁剪（`HassiumRegionFile` / `ClientCacheDatabase` / `CacheEvictionManager` 等旧类已删，数据不迁移）。现行缓存 = 影子端原版存档 `hassium_cache/<serverId>/world`（type 126 + chunkHash，见 [`architecture.md`](architecture.md) §7）。

### 11.1 外层布局

原版 Anvil 外层（`.mca`，32×32，2-sector header）+ Hassium payload：

```
Sector 0:     Offset Table
Sector 1:     Timestamp Table（原版）
Sector 2+:    [length(4)][type=126][magic 0x48][hash(8)][ZSTD 压缩数据]
```

- **无** HassiumEnvelope / HSM1 / type 127 运行时写入（127 仅作未来原版 scheme 迁移规划）
- 写 gate：`MixinRegionFile` shadow 上下文放行（影子端固定写 126）；真实服务端需 `storage.enabled`
- 字典缺失时拒绝写入 Hassium payload，回退原版

### 11.2 光照缓存流水线（影子端统一算光，真实客户端只消费标准原版区块包）

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

### 11.3 影子端存档（主一致性方案）

影子端 `ShadowSeedServer` 运行期维护注入区块，断连/卸载统一 `saveAll` 落盘：
- `ChunkSerializer.write(level, chunk)`（1.21.2+ `SerializableChunkData`）→ NBT
- `ChunkContentHashUtil.computeSectionHashes(chunk)` → `combineSectionHashes` → contentHash 落 `ShadowStorageHashes`
- chunkMap.write 落盘（type 126，MixinRegionFile shadow 上下文 gate）
- **脏柱增量**：磁盘命中且未修改的柱不重写；网络注入/增量/方块更新/光增量/relight/本地生成才置脏，`saveAll` 只重写脏柱
- **并行序列化**：脏柱 NBT 序列化在临时池并行（上限 4 线程），ChunkMap 写提交仍串行回到 saver 线程，IOWorker 统一 flush（避免并发写同一 mca 与 `ChunkMap.write` 非线程安全面）

这保证「曾加载并收到更新」的块 R2 再进应 HIT。

### 11.4 分段增量（缓存过期 / MISMATCH）

`chunk.sectionDeltaEnabled`（默认开）。影子端 MISMATCH 且光干净时，`ShadowPullRequestC2SPacket` 携带本地 section hash + 每非空段 48×u32 平面综合征；服务端按需比对（不常驻缓存）：

- 稀疏变更（矿道、树、岩浆柱等）→ `BLOCKS` 方块列表
- 过多（AABB ≥400 格，如炸坑）或整段 paletted 更小（铺平/灌水）→ `FULL` 整段
- 变更段占非空段 ≥75% → 整块全量
- apply 后校验 `expectedChunkHash`；失败/超时/`skipped` → 全量

影子端注入时把综合征放内存，活体方块更新失效后下次现算。BE / heightmap 仍随包；变更段清光重算。2.0.0 线格式不兼容历史。

### 11.5 关键组件

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
- **光照随区块保留**：收敛完成的区块携带 `SkyLight` / `BlockLight`

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
