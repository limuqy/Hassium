# handoff — 光照齐套自驱动算法（3×3 域波前 + 唯一权威柱交付出口）

> 状态：**设计已与用户对齐（2026-09-19）**；本文为实现阶段唯一真相源。
> 前置：[`handoff-2026-09-18-shadow-vanilla-align.md`](handoff-2026-09-18-shadow-vanilla-align.md)（A1 三链 / 「唯一权威柱交付出口」的原始定义）。
> 关联：[`../client-chunk-light-flow.md`](../client-chunk-light-flow.md) §4（齐套门）/ §7（光桥删除）、[`../mod-compat.md`](../mod-compat.md) §11。
> 代码锚点见 §6。

## 0. 问题：三处已确认的不一致

光桥删除后（2026-09-19），交付语义变成「光只随整柱包**一次**下发」（[`../client-chunk-light-flow.md`](../client-chunk-light-flow.md) §7.2）。一次性快照意味着：**已交付的暗柱没有重推路径**。于是"交付前光是否已算对"从"会影响观感"升级为"错了就一直在"。

在这个前提下查出三处不一致（全部【已验证】）：

| # | 现象 | 证据 |
|---|------|------|
| **P1** | `ShadowPullRadii.LIGHT_HALO_RADIUS = 1` 是**死常量**（全仓 0 引用）。其 javadoc 声称「在 serverVD 之外再多拉 1 环…光环柱只进影子端 inject/算光/缓存，**不得**交付客户端；交付域仍是 serverVD」——**没有任何代码在实现**。halo 是被主动删掉后留下的化石（`ShadowVanillaLightPipeline:16`「不再维护 halo」、`ShadowSeedServer:282`「不额外预生成 halo」、`ShadowLightCompute:1391`「no halo role is tracked here」） | `ShadowPullRadii.java:23-30`；全仓 grep |
| **P2** | **交付域比计算域宽 4 环**，方向与「外圈不完整光块不进视野」相反：acquire/计算域 = `serverViewDistance`；交付域 = `serverViewDistance + DELIVER_VIEW_MARGIN_CHUNKS(4)` ∪ OVD 带 | `ShadowTrackingSession.java:370-383` vs `:926-936`、`:917-923` |
| **P3** | 齐套门的 `wanted` 判据用**交付域**(+4)，而 acquire 只填到 serverVD → 门会为 `(serverVD, serverVD+4]` 这个**永远不会被拉取**的环等待，必然吃满 4s 硬超时后降级放行 | `LightNeighborhoodGate.java:261-272` vs `drainAuthorityAcquires` |
| **P4** | **降级放行也会写入 `promoted`**，而 `wasPromoted` 是在**算光侧**（`startLightBarrier`）检查的 → 交付侧**没有**「3×3 已就绪」门 | `LightNeighborhoodGate.java:297`、`ShadowLightCompute.java:2038-2040` |
| **P5** | **没有**「邻柱迟到 → 重算/重投」入口：`AcquireReason.RELIGHT` 声明后零引用；`promoted` 单调；`tryPromote` 出队即移除；`noteNeighborhoodProgress` 只推进**还在 awaiting 的**条目超时时钟 | `ShadowChunkProvider.java:19-23`、`LightNeighborhoodGate.java:72,143-175,293-298` |
| **P6** | 往真实客户端发整柱包的入口有 **7 个**：`publishCachedChunk` ×4 调用点 + `forwardToRealClient` ×3 mixin 注入点 | `ShadowTrackingSession:408`、`ShadowPullClient:352`、`ShadowVanillaLightPipeline:71`、`ShadowChunkDeliver:52`；`MixinChunkMap:108`、`MixinPlayerChunkSender:127`、`MixinServerPlayer:65` |

### 0.1 实测降级率（2026-09-19 手动 run，1.20.1 fabric，09:46–09:53）

```
就绪放行 = 13878
缺邻降级 =  1724      ← 11.0%
（降级全部 out=0：缺的邻居全是「窗内未注入」，即 P3 描述的"没人去拉"）
```

**未验证**：降级柱实际是否偏暗、后来是否被补亮。本次 run 里 `[CHUNK_PROBE] source=light`（post-apply 复采样）**0 条**（光桥删掉后该打印路径消失），`source=shadow` 的 `skyTop` 是光队列 flush **前**的即时读数（`ClientChunkHandler.java:397-403` 自述为已知假阳性）→ 该日志**无法**回答这个问题。

## 1. 设计（已拍板）

### 1.1 三区状态机

| 区 | 语义 | 动作 |
|----|------|------|
| 权威 | 可交付 | 算光 + 交付 |
| **LIGHT_HALO** | 只算光，**不交付** | 拉取 + 注入 + 算光 + 缓存 |
| 窗外 | 不管 | 不拉、不等待 |

**参数**：`chunk.lightHaloRadius`，默认 **1**。
> 用户拍板：「先 1，用参数设置，后面测试发现问题方便调整」。

**为什么 1 环就够**（用户拍板，2026-09-19）：一圈 LIGHT_HALO 的**唯一职责是给权威柱补齐 3×3**。
权威边界柱的 8 邻中，向外一侧有 **3~5 个** LIGHT_HALO（边中 3 个 / 角 5 个），而光环柱本身是
**已算光的真实柱**（有数据层，不是 Bedrock）→ 权威柱的 3×3 完整，**不会出现「缺邻当基岩」的错误传播**。
光环柱自己的 3×3 不完整（它外面就是窗外），但**它不交付**，残差被限制在交付域之外。
> 保留参数化的理由：这是"原理上可论证、但仍值得留逃生口"的量——若实测在权威边界发现洞口残差，
> 直接调 `lightHaloRadius=2` 即可，不必改代码重编。

### 1.2 两条不变量（本设计的核心）

- **I1**：一柱只有其 3×3 全部「已过 INITIALIZE_LIGHT」才能跑 LIGHT。 —— **现状已有**（`LightNeighborhoodGate`）。
- **I2**：一柱只有其 3×3 全部「已跑完 LIGHT」才允许**交付**。 —— **现状没有**（见 P4）。

> `LIGHT_HALO → 权威` 的升级条件 = I2。这是本设计解决"半成品光区块"的全部要害；只搬 I1 是换个写法，没有收益。

### 1.3 自驱动波前

- **触发**：创建虚拟玩家 / TP / 穿越维度 / 复活。
- **起点块**：虚拟玩家脚下区块。
- **推进**：每轮扫描 `LIGHT_HALO` 集合，判断该柱是否已进入权威环；是 → 以该块为起点组建新的 3×3 域。
- ⚠ **起点不是一次性事件**：虚拟玩家连续移动（每轮 `flushVirtualPlayerChunks` 同步位置），波前是**每轮被重新播种**的。**不得退化成"从一个固定种子扩散"**（见 §4 风险 a）。

### 1.4 节拍与预算（保持现状语义）

- **200ms** 驱动（**不是每 tick**）—— 用户拍板「继续用 200ms，按你意见」。
- 预算语义**一起搬**（现状是为"防首帧风暴"设的，注释原话）：`AUTHORITY_ACQUIRE_BUDGET = 64`/轮、`PULL_DRAIN_INTERVAL_MS = 50`、`PULL_SENDS_PER_DRAIN = 5`。

### 1.5 排序与去重（用户拍板）

每次选块产出一个 **3×3 域**；因柱可能"内存已有/未获取"，实际每个域待办 **3~9 柱**。

- 先**去重**（跨域重叠的柱只留一次）。
- **相同 3×3 域为同一组**；组内按距离升序。
- **不同域之间**按各域的「最近柱距离」升序。

> 目的（用户原话）：「主要让服务端不会东投一柱，西投一柱」—— 成组交付，避免空间上散点式投递。

### 1.6 唯一「权威柱交付」出口

把 P6 的 7 个入口收敛成 **1 个** `deliverAuthoritative(dim, pos, Reason)`：

- 几何判据（权威形状 / OVD / halo）**只留一处** → 直接消灭 P2 那类"两处各写一遍必然漂移"。
- 带 `Reason`：`SEED`（新进视野）/ `HALO_PROMOTED`（半成品升级）/ `OVD` / `REDELIVER` / `PULL`。这正好填上 `AcquireReason` 里空着的 `RELIGHT`，也让下游能区分「新进入」与「重投」。
- 必须带**维度闸**（沿用 `ShadowOfficialPacketBridge.forwardToRealClient` 的 drop cross-dimension）。

> 注：`handoff-2026-09-18-shadow-vanilla-align.md` §1 早已写「三条链，唯一『权威柱交付』出口」，但当时只收敛了"来源链"，**出口本身仍是 7 个**。本 handoff 是那条约束的收尾。

## 2. 明确不做（边界）

1. **不每柱物化 9 柱域**。3×3 是**前置条件**，不是喂给引擎的载荷；引擎只要"该柱已装数据层 + 跑 LIGHT 任务"。现状 `lightInitPassed` 是**单个**单调 `Set<Long>`（`ShadowLightCompute:143`），`tryPromote` 对每柱做 8 次 set 查（`LightNeighborhoodGate:251`）——已足够。每柱存域 = 内存 ×9 + 波前推进时的一致性维护，纯负担。
   唯一真需要索引的是「邻柱到达时要唤醒谁」，用反向索引或扫 frontier 即可。
2. **不改**光桥删除后的"一次性快照"语义（不复活 section 级回传）。
3. **不解决** 2026-09-19 09:30:34 影子主循环静默停摆（chunk/IO 路径，另案；见 [`../mod-compat.md`](../mod-compat.md) §11 同族问题）。重设计不得把它当验收项。
4. **OVD 不在本期**（用户拍板「OVD 作为二期开发」）。S1 的单点化必须**保持 OVD 行为不变**（`inOvdWindow` 原样保留、不改判定）；`DELIVER_VIEW_MARGIN_CHUNKS = 4` 的来历若查明属 OVD，随二期一起处理。

## 3. 落地顺序（最小风险优先）

| 步 | 内容 | 为什么这个顺序 |
|----|------|----------------|
| **S1** | 统一交付出口（7→1）+ 几何单点化 | 收益最大、风险最低；**本身就能把 P2 暴露成一个可调参数** |
| **S2** | 交付侧补 I2：`promoted` 拆成 `lit` / `deliverable` 两态，交付口加「3×3 全 LIGHT 完成」判据 | 解决 P4；这是"半成品"的根治点 |
| **S3** | 接上光环：`chunk.lightHaloRadius`，计算域 = 权威域 + R（`drainAuthorityAcquires` / `isAuthorityPullEligible` / 门的 `wanted` 用**同一个**半径） | 解决 P1/P3；参数化便于调 R |
| **S4** | 波前驱动器替换（200ms + 预算 + 3×3 域分组排序） | 前 3 步做完后，这只是**调度替换**，不碰正确性；反过来先做会把正确性与调度搅在一起，难归因 |
| **S5** | `RELIGHT` 重触发：邻柱迟到 → 该柱 3×3 重新入队 + 往外 pull 新 halo | 解决 P5 |

> **本期范围 = S1~S5，不含 OVD**（二期）。S1 做几何单点化时，OVD 那一段（`inOvdWindow`）原样搬过去、判定逻辑不动；`inDeliverableShape` 的 `+4` 也先原样保留，只把它变成唯一一处参数。

## 4. 风险与未决

| ID | 风险 / 未决 | 处置 |
|----|-------------|------|
| a | **起点在移动**：虚拟玩家连续移动 → 波前每轮重新播种。若实现成"固定种子 BFS"，会出现"玩家飞走了、波前还在原地补" | 波前必须是"待办集合"而非"扩散球"；现状 `drainAuthorityAcquires` 每轮按**当前**中心重扫全窗，天然免疫——别丢这个性质 |
| b | **每 tick 扫描**会把频率 ×5 | 用户已拍板 200ms；预算语义必须一起搬（§1.4） |
| c | ~~光环几环够不够~~ → **已决：1 环**（用户拍板，2026-09-19）。理由见 §1.1「为什么 1 环就够」：光环只负责给权威柱补齐 3×3，边界权威柱有 3~5 个光环邻柱且它们都是真实已算光柱 → 不出现「缺邻当基岩」的错误传播；光环自身残差被限制在交付域之外 | 参数 `chunk.lightHaloRadius` 保留为逃生口：实测若在权威边界发现洞口残差，调到 2 即可，不改代码 |
| d | `DELIVER_VIEW_MARGIN_CHUNKS = 4` 的**来历未查明**（注释只写「交付门余量（B6）」） | **收紧它之前必须先查证**：可能是 OVD 环带边界或"服务端半径晚到/变化"的兜底。S1 只是把它单点化，**不改值** |
| e | 跨维度污染 | 交付出口必须带维度闸（P6 的 7 个入口里只有 `forwardToRealClient` 有） |

## 5. 验收

1. **视觉（主锚）**：移动中的**屋檐 / 洞口**。红线：冒烟 PASS **不能**代替目视（`AGENTS.md` 算光红线）。
2. **数据**：缺邻降级率（`[LIGHT_GATE] Promote ... missingInWindow=N`）目标降到 ~0；或降级柱被 I2 挡在交付域外（此时降级率可以不为 0，但要证明它们不交付）。
3. **回归**：`common:test` + `common`/`fabric`/`forge`/`neoforge` 三版本编译 + `scanVersionBoundaries`。
4. **对照**：`REUSE_PHASE1_INITIALIZE` 与 `chunk.lightHaloRadius` 两个开关都要能单独回退做 A/B。

## 6. 现状锚点（代码索引）

| 关注点 | 位置 |
|--------|------|
| 光环常量（死） | `protocol/ShadowPullRadii.java:23-30`（`LIGHT_HALO_RADIUS=1`）、`:21`（`AUTHORITY_MARGIN=2`） |
| 计算/acquire 域 | `shadow/track/ShadowTrackingSession.java:354-447`（`:370` range=serverVD、`:374` boundingRadius、`:395` budget） |
| 交付域 | `ShadowTrackingSession.java:917-923`（`isDeliverableToClient`）、`:926-936`（`+4`）、`:939-949`（OVD）、`:905-914`（权威窗） |
| 节拍/预算 | `ShadowTrackingSession.java:69,71`；`shadow/track/VanillaAlignedChunkProvider.java:44-50` |
| 齐套门 | `shadow/light/LightNeighborhoodGate.java:54,60`（soft 2s / hard 4s）、`:221-299`（`tryPromote`）、`:251`（就绪判据）、`:261-272`（`wanted`）、`:297`（写 `promoted`） |
| 已就绪集合 | `shadow/light/ShadowLightCompute.java:143`（`lightInitPassed`）、`:1547-1548`（`isLightInitPassed`）、`:1526`（写入点） |
| 算光侧门 | `ShadowLightCompute.java:2038-2050`（`wasPromoted` 检查点） |
| 光超时兜底 | `ShadowLightCompute.java:2357`（`sweepLightTimeouts`，Render 线程） |
| 交付入口 ×7 | `ShadowTrackingSession:408`、`ShadowPullClient:352`、`ShadowVanillaLightPipeline:71`、`ShadowChunkDeliver:52`；`mixin/shadow/MixinChunkMap:108`、`mixin/server/MixinPlayerChunkSender:127`、`mixin/server/MixinServerPlayer:65` |
| 交付实现 | `ShadowLightCompute.java:788/802`（`publishCachedChunk`）、`shadow/track/ShadowOfficialPacketBridge.java:41`（`forwardToRealClient`） |
| 原因枚举（`RELIGHT` 空置） | `shadow/track/ShadowChunkProvider.java:19-23` |
| 几何谓词 | `compat/ChunkShapeCompat.java:22`（`contains`）、`:43`（`boundingRadius`）、`:53`（`inOvdBand`） |
| 交付客户端 apply | `shadow/light/ShadowLightCompute.java` `drainReady` / `applyReadyChunk`；客户端探针 `client/ClientChunkHandler.java:397-403` |

## 7. 会话来源

2026-09-19 会话：光桥删除 → 手动 run 发现「飞行到退出前位置区块不加载 + 退出卡顿」（影子主循环静默停摆，另案）→ 用户提出「外圈缺光块 + VD+1 光环」设计质疑 → 核对确认 P1~P6 → 用户拍板三区状态机 + 参数化光环 + 200ms + 3×3 域分组排序。
