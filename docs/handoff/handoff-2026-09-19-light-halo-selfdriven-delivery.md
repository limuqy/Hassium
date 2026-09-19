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
| **P3** | 齐套门的 `wanted` 判据用**交付域**(+4)，而 acquire 只填到 serverVD —— 判据域与拉取域**不一致**（门认为"应该会到"的邻柱，其中一环没人去拉） | `LightNeighborhoodGate.java:261-272` vs `drainAuthorityAcquires` |
| **P3′** | **【2026-09-19 实测修正】P3 不是降级的主因。** 11:44 手动飞行（B 族在）：降级 Promote 282 个，其**到交付中心的切比雪夫距离分布与全体 Promote 完全一致**（降级 261/19/2/0 @ dist 0-17/18/19/20 vs 全体 1956/19/2/0）→ 与窗口边界**无关**，是**均匀分布在整个窗内的时序问题**。且 `missingInWindow` 的语义是 `notInjected`（邻柱**根本没注入**），不是 `injectedNotInit` → **邻柱拉取/生成跟不上**，门等 2~4s 后降级放行。P3 仍是一处结构不一致，但**修它不会降降级率** | 见 §0.2 实测；判据来源 `LightNeighborhoodGate.java:280-291` 的日志字段 |
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

### 0.2 降级机制的实测归因（2026-09-19 两次手动飞行）

| 观测 | B 族断（11:39，109s） | B 族在（11:44，73s） |
|---|---|---|
| `Promote` 总数 | 3439 | 1977 |
| `missingInWindow`（降级） | **768（22.3%）** | **282（14.3%）** |
| 降级分布形状 | 全部 `out=0` + `timedOut=true` | 同 |
| `SKY-SOURCES highestLowestSourceY` | 109（8 次） | 109（9 次） |
| 用户目视 | 缺屋檐光 | **也有黑柱** |

**三条结论（已验证）：**

1. **黑柱与 B 族无关，也与本次 S1 改动无关**：B 开/关两次飞行**都有**黑柱；受控冒烟对比里 B 开关只让降级 472→492（+4%）。→ A/B 阴性。
2. **排除读盘柱光源表未填**：两次 `SKY-SOURCES` 均为 `109`（正常），不是 `-2147483648`。
3. **降级与窗口边界无关**（推翻 P3 作为主因）：降级柱的距离分布与全体 Promote 完全一致（见 P3′）。降级的语义是**邻柱未注入**（拉取/生成跟不上），不是"注入了没算光"。

**未验证**：降级柱是否**就是**用户看到的黑柱。机制上强（降级放行 → `lightChunk` 时缺邻被引擎当 `Blocks.BEDROCK` → 天光进不来），但**没有逐柱坐标关联**（用户未提供黑柱位置）。

### 0.2b 用户报的黑柱是「屋檐结构」+ 探针口径缺口（2026-09-19，**已决定延后**）

用户给坐标 **(-15, -30)**（TP 到达，排除边缘），实测该柱：

```
门：3×3 全部 neighborhood ready（9/9，非降级）
交付：phase=shadow_applied origin=remote_pull
探针：topY=121 skyTop=15 skyAir=15 secY=7 skyMid=15 skyW/E/N/S=15
玩家：playerBlock y=74（地下 47 格）；fixedY=62 是水
```

**三条已验证：**
1. 该柱的门**完全正常**（9 柱全就绪、无降级）→ 黑柱**不是降级造成的**（再次确认 A/B 阴性）。
2. 地表天光**全部 15**（正常）。
3. **探针口径缺口**：`[CHUNK_PROBE]` 只采 `topY+1`（地表）、`maxY-2`（世界顶）、`fixedY=62`、`midY`（地表 section +8）—— **没有一个采在玩家/檐下所在的 Y**。用户报的是**屋檐结构**（檐下靠水平进光），探针全在檐上 → **现有探针测不到用户真正关心的位置**。

> **回来时的入口**：加"玩家所在 Y（或檐下 section）的天光 + 方块光"采样，再与客户端本地值逐格对比。
> 才能区分「影子端投喂的檐下光是 0 而客户端本地算出 15」＝投喂侧丢光，与「两边都 0」＝结构本身无光。
> 关联：任务 #13「影子端方块光为何比客户端暗」（已有实测：27 次命中、6 次客户端该格 =15）。
> **另一处未决**：`tryPromote` 的就绪判据只要求邻柱过 **INITIALIZE**（不要求邻柱自身 clean）→
> 檐下柱可能"就绪放行"，但其**开口侧的邻柱**是降级算出的低光 → 檐下依旧偏暗。I2 的「不连坐」
> 选择**覆盖不到**这一条（I2 只看 self 是否 clean 放行），需在屋檐专项里重新评估。


### 0.3 由此确定的修复优先级

降级 = "半成品柱被交付"。两个配套动作：

- **S2（挡）**：`isLightCorrect` 落盘侧补 I2（只有干净齐套放行的柱才允许标 `isLightCorrect`）→ 降级柱**不被永久复用**（不落盘 → 下会话重算）。
- **S5（补）**：邻柱迟到 → 该柱重新过门重算 + 重交付 → 最终光正确。

> **2026-09-19 设计修正（已落地，见 §3.1）**：S2 原本写成"交付侧挡"（降级柱不进客户端）。
> 实现时改为**不门控交付**：不交付会在客户端留一个**空洞**（虚空），比一个偏暗的柱更显眼，
> 原版还会把它当未加载区。故最终语义 = **先照常交付（避免洞）+ 登记重算重交付（覆盖暗点）**；
> I2 只用于 `isLightCorrect` 落盘/复用。

**S2+S5 是当前黑柱的直接修复，优先级高于 S3（光环）。** 理由：降级均匀分布在整个窗内、占 14~22%，与光环/边界无关；加光环解决不了"邻柱拉取慢"。


## 1. 设计（已拍板）

### 1.1 三区状态机

| 区 | 语义 | 动作 |
|----|------|------|
| 权威（= 交付域，{@code serverVD}） | 可交付 | 算光 + 交付 + 3×3 齐全 → 落盘 |
| **LIGHT_HALO**（权威形状的切比雪夫膨胀） | 只算光，**不交付** | 拉取 + 注入 + 算光 + 缓存；3×3 不齐 → **不落盘** |
| 窗外 | 不管 | 不拉、不等待 |

**参数**：`chunk.lightHaloRadius`，默认 **1**。
> 用户拍板：「先 1，用参数设置，后面测试发现问题方便调整」。

**几何口径（2026-09-19 修正：必须是「膨胀」而非「形状环」）**：
计算域 = **权威形状的切比雪夫膨胀** `ChunkShapeCompat.containsDilated(serverVD, R)`
（实现 = 原版公式把 `|d|-1` 折成 `|d|-1-R`）。**不得**用 `contains(serverVD + R)` 近似：
那是形状环，在形状切角处会漏掉每窗 8/16/20 个权威柱的邻柱（VD=10/16/20，穷举实测），
即「1 环够不够」的答案是**在膨胀口径下才成立**。两种口径的最大切比雪夫半径相同
（`VD+R+1`），故都不增加服务端拒绝面。几何不变量由 L0 单测
`ChunkShapeDilationTest` 钉死（含反例：形状环近似必然漏邻）。

**为什么 1 环就够**（用户拍板，2026-09-19；膨胀口径下已实测零缺口）：一圈 LIGHT_HALO 的
**唯一职责是给权威柱补齐 3×3**。权威边界柱的 8 邻中，向外一侧有 **3~5 个** LIGHT_HALO
（边中 3 个 / 角 5 个），而光环柱本身是**已算光的真实柱**（有数据层，不是 Bedrock）
→ 权威柱的 3×3 完整，**不会出现「缺邻当基岩」的错误传播**。
光环柱自己的 3×3 不完整（它外面就是窗外），但**它不交付**，残差被限制在交付域之外。
> 上限：`MAX_LIGHT_HALO_RADIUS = AUTHORITY_MARGIN − 1 = 1`（膨胀形状的最大切比雪夫半径是
> `VD+R+1`，服务端签发是 `VD+AUTHORITY_MARGIN`）。故**取值只有 0 与 1**；R=2 需要两侧同改
> （会放宽所有客户端的请求上界），不是纯配置逃生口。

**交付域（2026-09-19 用户拍板 S3b）**：交付域 = **`serverVD` ∪ OVD 带**，
`DELIVER_VIEW_MARGIN_CHUNKS` 的 `+4` 余量**已删除**。理由（冒烟实测）：余量让「计算域比交付域窄」
时多出来的环（光环）也能进客户端视野——R1 交付恰好 = `|shape(serverVD+光环)|`（1529→1665），
而光环柱的 3×3 天生不完整 → 那正是「外圈不完整光块进视野」。收到 `serverVD` 后
**交付集 = 权威集**，光环只算不交付；OVD 环带仍按 `inOvdWindow` 独立放行（二期兼容）。

### 1.2 两条不变量（本设计的核心）

- **I1**：一柱只有其 3×3 全部「已过 INITIALIZE_LIGHT」才能跑 LIGHT。 —— **现状已有**（`LightNeighborhoodGate`）。
- **I2**：一柱只有其 **3×3 真齐全**（8 邻全部已过 INITIALIZE_LIGHT，**含窗外邻柱**）才算"光权威"，
  才允许标 `isLightCorrect` 落盘 / 被复用。 —— **已落地**（§3.1 S2a/S2b + §3.3 S5）。
  不门控交付（见 §0.3 的修正说明）；非权威柱登记进 `pendingAuthoritative`，由 S2c 重算覆盖。

> 判据来源：`promotedClean` 在 promote 那一刻断言 `notInjected==0 && injectedNotInit==0
> && outsideWindow==0`。**窗外邻柱也算缺邻**——它不是我们的拉取责任，但引擎照样把它当基岩挡光，
> 该柱的光在那个方向就是错的。这与原版落盘形式一致：`ThreadedLevelLightEngine.lightChunk`
> 入口就 `setLightCorrect(false)`，只有引擎跑完（3×3 依赖齐全）才置真，缺邻时任务不会完成
> → 落盘 `isLightOn=false` → 读档 `isLit=false` → 重跑 LIGHT。我们复用同一形式。
> **不叠加「8 邻都得 `wasPromoted`」**：REUSE 柱按设计不进齐套门、永远不会 `wasPromoted`，
> 叠加会让 R2 里任何挨着缓存柱的柱被判非权威（系统性误判）。详见 §3.1。

### 1.3 自驱动波前

- **触发**：创建虚拟玩家 / TP / 穿越维度 / 复活。
- **起点块**：虚拟玩家脚下区块。
- **推进**：每轮扫描 `LIGHT_HALO` 集合，判断该柱是否已进入权威环；是 → 以该块为起点组建新的 3×3 域。
- ⚠ **起点不是一次性事件**：虚拟玩家连续移动（每轮 `flushVirtualPlayerChunks` 同步位置），波前是**每轮被重新播种**的。**不得退化成"从一个固定种子扩散"**（见 §4 风险 a）。

### 1.4 节拍与预算（保持现状语义）

- **200ms** 驱动（**不是每 tick**）—— 用户拍板「继续用 200ms，按你意见」。
- 预算语义**一起搬**（现状是为"防首帧风暴"设的，注释原话）：`PULL_DRAIN_INTERVAL_MS = 50`、`PULL_SENDS_PER_DRAIN = 5`。
- **`AUTHORITY_ACQUIRE_BUDGET` 已取消（2026-09-19 用户拍板）**，改为与客户端消费能力绑定：
  `authorityAcquireBudget() = chunk.maxChunksPerFrame × ACQUIRE_BUDGET_TICKS_PER_ROUND(4)`
  —— 原话「可以顺便取消 AUTHORITY_ACQUIRE_BUDGET，改为 maxChunksPerFrame x 5(或4)」，
  后定 **×4**。驱动间隔 200ms ≈ 4 个客户端 tick，故乘 4。默认 `maxChunksPerFrame=6` ⟹
  **24/轮**（原固定 64/轮）；配置不可用时退回旧值 64。锚点：`ShadowTrackingSession.authorityAcquireBudget`。
- **同组（3×3 域）不因预算被切断**（同批用户口径：「达到预算后，同组(3x3域)要发完，减少同批分裂」）：
  `sortByDomain` 已保证同 `domainId` 连续，故预算到点后**只在组边界**停，组内超预算也把本组发完。
  理由：半组会让该组各柱都缺几个邻柱 → 齐套门降级（降级 promote 的来源之一）。

### 1.5 排序与去重（用户拍板）

每次选块产出一个 **3×3 域**；因柱可能"内存已有/未获取"，实际每个域待办 **3~9 柱**。

- 先**去重**（跨域重叠的柱只留一次）。
- **相同 3×3 域为同一组**；组内按距离升序。
- **不同域之间**按各域的「最近柱距离」升序。

> 目的（用户原话）：「主要让服务端不会东投一柱，西投一柱」—— 成组交付，避免空间上散点式投递。

> **S4 实现选择（2026-09-19）**：域取 **`floorDiv(·, 3)` 平铺**（而非「以每个待办柱为中心的 3×3」）。
> 两个理由：① 平铺互不重叠 → 上面那条「跨域去重」**结构性成立**，不需要去重集合；
> ② 锚点固定在世界原点，**不随玩家移动漂移** → 移动中分组稳定，不会每走一格重排。
> 若实测发现按玩家中心的域更贴合意图，改动点只在 `domainId`。

### 1.6 唯一「权威柱交付」出口

把 P6 的 7 个入口收敛成 **1 个** `deliverAuthoritative(dim, pos, Reason)`：

- 几何判据（权威形状 / OVD / halo）**只留一处** → 直接消灭 P2 那类"两处各写一遍必然漂移"。
- 带 `Reason`：`SEED`（新进视野）/ `HALO_PROMOTED`（半成品升级）/ `OVD` / `REDELIVER` / `PULL`。这正好填上 `AcquireReason` 里空着的 `RELIGHT`，也让下游能区分「新进入」与「重投」。
- 必须带**维度闸**（沿用 `ShadowOfficialPacketBridge.forwardToRealClient` 的 drop cross-dimension）。

> 注：`handoff-2026-09-18-shadow-vanilla-align.md` §1 早已写「三条链，唯一『权威柱交付』出口」，但当时只收敛了"来源链"，**出口本身仍是 7 个**。本 handoff 是那条约束的收尾。

### 1.7 OVD 适配（二期，2026-09-19 用户口述规格）

**术语先钉死**（下文一律用这几个词，避免「权威」二字歧义）：

- `ovd` = 配置 `chunk.maxRenderDistance`；`cvd` = 客户端视频设置里的渲染距离滑块。
- **OVD 圈** = `min(ovd, cvd)`；用户口径：**影子端推送窗 = OVD 圈**。
- `effectiveClientVD` = `OvdClientLifecycle.effectiveClientVD(mc)` = `max(serverVD, min(slider, max))`
  【已验证：`client/OvdClientLifecycle.java:46`】——即「OVD 圈」再对 `serverVD` 取 max。
- **OVD 带** = `ChunkShapeCompat.inOvdBand(center, serverVD, effectiveClientVD, x, z)`
  = `cheb ≤ effectiveClientVD ∧ ¬contains(serverVD)`【已验证：`compat/ChunkShapeCompat.java:86`】。
- **光环** = `containsDilated(serverVD, R) ∧ ¬contains(serverVD)`（计算域最外圈，见 §1.1）。

**用户规格（原文照录）**：

> OVD圈=影子端推送窗=min(ovd, cvd)，客户端推送保持排除LIGHT_HALO;驱动范围=max(OVD圈,VD)
> 然后复用现有的权威转OVD，OVD转权威=LIGHT_HALO转权威，都需要compare
> 权威范围不变；权威外有可能是OVD，或者LIGHT_HALO，权威外区块不3x3驱动；空区域推本地缓存读取(消费者自判断是否有缓存)

**逐条对码核对（G 表；每条给 file:line，不猜）**：

- **G1 — OVD 带的内边界是否排除光环**：现状 `inOvdBand` 排除的是 `contains(serverVD)`
  （**权威形状**），**不排除计算域** → `R>0` 时光环环带落在 OVD 带内 → **光环柱会被 OVD 路径交付**
  【已验证：`ChunkShapeCompat.java:92` 的 `!contains(...)`；交付点 `ShadowTrackingSession.java:722`
  `publishOvdCachedChunk`，另见 `:1245` materialize 分支与 `:1108` 的回收保留域】。
  → 与「客户端推送保持排除LIGHT_HALO」是否冲突，**取决于该句口径**（见下方「唯一分歧」）。
- **G2 — 驱动范围 = `max(OVD圈, VD)`**：OVD 环带扫描 `sweepOvdRing` 的枚举是
  `for (cheb = 1; cheb <= clientVD; cheb++)`，`clientVD = effectiveClientVD`
  【已验证：`ShadowTrackingSession.java:653,669`】→ **枚举范围已经是 `max(OVD圈, VD)`，无缺口**
  （若「驱动」指 OVD 扫描）。而权威 acquire 驱动 `drainAuthorityAcquires` 枚举盒 =
  `computeDomainBoxRadius()` 且过滤 `isInComputeDomain`【已验证：`:486-495`】→ 只覆盖计算域
  （权威 + 光环），**不含 OVD 带**；这与现状「OVD 带 = 本地源服务、禁止 pull」一致
  （`:1045` 注释），也与用户「空区域推本地缓存读取」一致。
- **G3 — OVD/光环 → 权威 的 compare 转换**：**已满足，无需新代码**。
  `drainAuthorityAcquires` 的 material 分支：`isConfirmed` → 允许交付；`isAwaiting` → 跳过；
  否则 `mark` + `pull`【已验证：`ShadowTrackingSession.java:509-538`】——即一柱先作为 OVD/光环
  以 `renderOnly` 注入（未 compare），**进入权威窗后会被 `mark` + pull**。
  另有：`renderOnly` publish 会 `SeedGenCompareGate.clear(gateDim, pos)`
  【已验证：`ShadowLightCompute.java:879-885`】→ 出权威后的 AWAITING 残留不挡环带回填，也不会让
  重进权威时命中 `isAwaiting` 死等（源码注释明写此意图）。**仅缺一条实测**（OVD 柱移入权威后确实走 compare-pull）。

**G1 的裁决（2026-09-19，用户补充口径）——不用几何排除，用「光照完成」条件**：

用户明确了两点，把 G1 从「几何口径二选一」变成「加一格推送条件」：

1. **推送窗 = `MAX(OVD圈, VD)`**，即 `effectiveClientVD = max(serverVD, min(slider, max))` ——
   **现状已满足**【已验证：`OvdClientLifecycle.java:46-57`】。
   > 用户曾口头补一句「跟客户端VD无关」，**随后自行更正**：OVD 圈本身（`min(ovd, cvd)`）就已经把
   > 客户端滑块算进去了，所以 `MAX(OVD圈, VD)` 当然含客户端 VD —— **滑块钳位保留，不改**。
   > 本文档早期把推送窗写成「OVD 圈」（漏了与 VD 取 max）也是错的，两处一并更正。
2. **OVD 环区扫描只对「非光环」区块提交 OVD 读取**；光环柱不做盘读。
3. **推送客户端再加一格条件：`非光环 或 (OVD 且 光照完成)`** —— 防止光照未完成的柱进客户端。

落地为 **S6**（见 §3 表）：

- 新增 `ShadowTrackingSession.isHalo(x,z)` = `isInComputeDomain(x,z) && !inVanillaVisibleShape(x,z)`
  （与 §1.1 的「光环」定义同源；`lightHaloRadius=0` 时恒 false，退化为空操作）。
- 新增 `ShadowTrackingSession.isPushableToClient(dim,pos)` = `isDeliverableToClient(pos)
  && (非光环 || 光照完成)`。**OVD 条件可省**：光环柱若在交付窗内，几何上只可能是 OVD 带
  （权威形状外）——「在窗内」已含 OVD。
- **「光照完成」= 影子柱的 `chunk.isLightCorrect()`**（原版 NBT `isLightOn`）：本会话新算柱由
  S5/I2 唯一收口置真，3×3 未齐全时**恒假** → 光环柱被挡；读盘 REUSE 柱标志随 NBT 还原 →
  上一会话已完整的柱**直接放行**（这正是「冒烟 R2 根本没有光环」的原因）。
- 接入点：`publishCachedChunk`（A 族 + OVD 读盘，放在 `renderOnly` 的 AWAITING 清理**之后**，
  保留原「出权威后清残留」语义）+ `offerReady`（**唯一出口**兜底，覆盖不走 publishCachedChunk 的
  `pushReady` / `offerBuiltChunkPacket` / `publishNativeLightResult`）。放在
  `SmokeChunkTrace.recordShadowReady` **之前** → 被挡下的柱不计入 `shadowReady`。
- `sweepOvdRing`：`inOvdWindow` 之后加 `if (isHalo(x,z)) continue;`（光环柱不做 OVD 盘读）。

**为什么这不是「几何排除光环」（原口径 B）**：口径 B 会在客户端视野里留一条 `1~2` 环宽的**空洞带**
（光环在 `cheb ∈ [VD+1, VD+2]`，`dilatedBoundingRadius(VD,1)=VD+2`，而 `cvd > VD` 时该带在渲染距离内）。
S6 保留几何（光环仍算 OVD 带），只在**推送**时按光照完成度过滤 → 已完整的光环柱照常交付，
不完整的不交付；等玩家推进、该柱从光环变成权威内、3×3 补齐后由 S2c 重算重交付。

**动态场景（用户口述，作为验收心智模型）**：

| 场景 | 光环 | 说明 |
|------|------|------|
| 冒烟 R2（缓存加载） | **无** | 计算域内柱全部 REUSE、`isLightCorrect` 随 NBT 为真 → 无「未完成光环」 |
| 无缓存加载 + 移动 | 后方 `光环+OVD+权威`；前方 `权威+光环` | 前沿是正在算的光环；后方是已转权威 + OVD 带 |
| R2 + 移动 | 前方 OVD 逐渐转权威；转完后继续前移，下一空区块进权威 → 正常建 3×3，**权威外自然生成光环** | 光环是**前沿的产物**，不是固定常驻环 |

**经典冒烟的几何：S6 在这里是「回归证明」而非「新行为证明」**
【已验证（脚本常量与算式）：`scripts/runtime-smoke-test.ps1:31-37, 486, 519-521`】

- **R1**：`Vd1=20`，客户端滑块被 pin 到 `max(ClientRenderDistance, Vd1)` → `effectiveClientVD =
  max(20, min(20, 16)) = 20` → OVD 带 = `cheb ≤ 20 ∧ ¬shape(20)` = **空** → R1 不跑 OVD。
- **R2**：`Vd2=10`，滑块仍 ≥ 20，`chunk.maxRenderDistance` pin 16 → `effectiveClientVD =
  max(10, 16) = 16` → OVD 带 = `cheb ≤ 16 ∧ ¬shape(10)` → **生效**（基线实测 636 柱）。
- R2 的光环（`dilated(shape(10),1) ∖ shape(10)`，cheb 11~12）在 R1 时是**内部柱**（11~12 ≪ 20）
  → 3×3 完整 → 落盘 `isLightOn=true` → R2 REUSE 时 `isLightCorrect=true` → **S6 放行**
  【推断：由 S5 的落盘口径 + 几何推出，待 R2 日志 `blocked reason=haloLightIncomplete` 计数佐证】。

⟹ classic 冒烟的**预期**是 **S6 不改变交付集**（R1 无 OVD 带；R2 光环是缓存完整的），指标应与
`s5_halo_v2` 逐项一致，`haloLightIncomplete` 阻挡计数应为 0。

**要真正证明 S6 起作用，需要一个现有 classic 不满足的场景**：冷缓存 + `serverVD` 小 + `clientVD` 大
+ 正在算新柱（即用户口述的「无缓存加载 + 移动」）——光环落在 OVD 带内且 3×3 未补齐。这是 OVD 二期
真正要补的验收场景（classic 的 R1 OVD 带为空、R2 光环已完整，都碰不到 S6 的分支）。

**其余待实测**：① OVD 柱移入权威后是否走 compare-pull（G3）；② 客户端视野内是否出现空洞带
（预期：仅「光环未完成」期间）。

#### S6 首轮冒烟回归（2026-09-19，`s6_ovd_classic`）——**我的实现错了两处**

首轮跑完 `pass=True`（冒烟门禁抓不到这个回归），但 **R2 交付集少了整圈光环**：

| 指标 | 基线 `s5_halo_v2` | S6 首轮 `s6_ovd_classic` | 差 |
|------|------------------|------------------------|----|
| R1 `networkReceived=shadowInjected` | 1705 = `\|dilate(shape(20),1)\|` | 1705 | 0 |
| R1 `shadowReady=clientApplied` | 1529 = `\|shape(20)\|` | 1529 | 0 |
| R2 `shadowReady` | — | 453 = `\|shape(10)\|` | — |
| R2 `clientApplied` | **1089** = `33²`（整个 `cheb ≤ 16` 窗） | **993** | **−96** |

几何校验【已验证，脚本穷举】：`\|shape(10)\|=453`、`\|dilate(shape(10),1)\|=549`
→ **光环 = 96 柱** = R2 缺失量。日志【已验证】：`offerReady blocked haloLightIncomplete`
**105 次**，被挡坐标全在 `cheb ∈ [10,12]`（R2 `serverVD=10` 的光环环）。

**两处实现错误（均为「把状态未知当成了光照未完成」）**：

1. **`offerReady` 用侧查注入表判光照完成 → 假阴性**。该出口的 B 族（官方包直通）在柱**已摘表 /
   从未入表**时被调用，`injectedChunk()==null` 被判成「光照未完成」。实证：被挡的 (-11,-10) 在 R1
   是 `[LIGHT_GATE] Promote ... timedOut=false`（3×3 齐全、已 promote），落盘应标真。
2. **`publishCachedChunk` 的门放在「柱还没解析出来」之前**。该函数在注入表未命中时会去读盘，门放在
   前面 → **整条 OVD 读盘路径被掐掉**（用户口径里的「空区域推本地缓存读取」直接失效）。
   另外我**误加了 `sweepOvdRing` 跳过光环柱**——把驱动路径也删了；用户原话「非光环区块，直接提交
   OVD 读取即可」是描述正常路径，**不是**「光环柱不许扫」。

**修正**（见 §3 表 S6 行的「修正」）：

- 门只在**拿得到柱**的地方判真值：`pushReady(key, chunk, ...)` 直接传 `chunk.isLightCorrect()`
  （新重载 `isPushableToClient(dim, pos, lightComplete)`）。
- `offerReady` 的兜底查表**查不到即放行**（状态未知 ≠ 光照未完成）；日志加 `detail=noInject|lc=…`
  以便区分两种情形。
- 删掉 `publishCachedChunk` 的门（该处必然「状态未知」）；删掉 `sweepOvdRing` 的跳过。

**门仍然生效的地方**：本会话新算的光环柱——柱必在注入表内、且 3×3 未齐全 → `isLightCorrect=false`
→ 被挡。这正是 S6 要拦的情形。

#### S6 判据改用「原版可推状态」（2026-09-19，源码已核）

「光照完成」不该用 `isLightCorrect` 当判据（它是**伴生结果**，且实测会被既有 bug 拖成假：
R2 `(-11,-9)` 在 R1/R2 都 `Promote ... timedOut=false`，`syncLightCorrect(true)` 却从未被调用
——无 `withhold` 日志——于是整圈光环被挡）。**原版判据（1.20.1 mojmap，逐行核过）**：

- 推送触发点 `ChunkMap.prepareTickingChunk`（`ChunkMap.java:726`）：
  `getChunkRangeFuture(holder, 1, s -> ChunkStatus.FULL)` —— **该柱及 range=1 邻域（3×3）全部达到
  `ChunkStatus.FULL`** 才发；发包处 `ChunkMap.playerLoadedChunk:1256-1258` **不看光**。
- 状态链 `LIGHT`（`ChunkStatus.java:141`，**range=1**）→ `SPAWN`(:149) → `FULL`(:156)
  ⟹ 到 FULL **蕴含本会话 LIGHT 步已完成**。
- 光只在「复用还是重算」处出现：`isLighted(c) = status>=LIGHT && c.isLightCorrect()`
  （`ChunkStatus.java:251`），被 `initializeLight`/`lightChunk`(:200-211) 用来决定跳过或重跑
  —— **盘上 `isLightOn=false` 的柱，原版会在本会话重跑 LIGHT、跑完置真，然后才可能到 FULL**。

⟹ **Hassium 对应物 = `isLightCorrect ∪ isColumnLightAuthoritative`（= `promotedClean`）**：
前者覆盖 REUSE/盘上已完整的柱（不进齐套门，`promotedClean` 恒假），后者覆盖本会话 3×3 齐全的柱。
判据落在 `ShadowTrackingSession.isPushableToClient(dim,pos,lightCorrect)`，门仍只在
`ShadowLightCompute.pushReady`（柱在手）。

**同批修掉的第二道闸**：`offerReady` 的 `SeedGenCompareGate` 对 `renderOnly` 也生效，与
`publishCachedChunk` 的既有决策（「OVD renderOnly 不进本闸」）自相矛盾。实测 R2 时序：
`disk-serve ... pub=true` → Promote 重新 `mark` AWAITING → 本闸把同一柱挡下（`clientHas` 恒 false）。
已加 `!renderOnly` 豁免。

#### 原版口径实测（2026-09-19，`s6_ovd_vanilla1`）——**口径对，但我们缺一个诚实的信号**

| 指标 | 基线 `s5_halo_v2` | `s6_ovd_vanilla1` | 差 |
|------|------------------|-------------------|----|
| R1 `shadowReady=clientApplied` | 1529 = `\|shape(20)\|` | **1099** | **−430** |
| R2 `clientApplied` | 1089 | 952 | −137 |
| `offerReady blocked seedGenAwaitingCompare` | — | **0** | ✅ 豁免生效 |
| `pushReady blocked lightIncomplete` | — | 8502 次，**全部 `lc=false auth=false`** | — |

冒烟 **FAIL**（`TRACE_EXPECTED_NOT_PRESENT` / `TRACE_INJECTED_NOT_READY` 各 178，P0；缺口位置含
**权威形状内**的柱如 `(-18,-9)`、`(-16,-6)` → 分析器的「光环豁免」不再适用）。

**归因**：`isLightCorrect` 与 `promotedClean` 都是**「权威（落盘可信）」**语义，**严格强于**「本会话
LIGHT 步已跑完」。约 28% 的权威柱（≈ 430/1529）两者皆假，来源是 ① **降级 promote**（实测 14~22%，
§0.1）与 ② **S2c 的 `rearm` 会清掉 `promotedClean`**（`LightNeighborhoodGate.java:285-288`）。
⟹ **原版口径不能靠复用 I2 的两个 flag 移植**：I2 故意保守，而原版 `ChunkStatus.FULL` 是「本会话
LIGHT 已完成」这个**事实**，不是「权威」这个**判断**。

**两条出路（需拍板）**：

- **A（忠实但需新信号）**：新增一个**会话级、与 I2 无关**的柱标记「本会话 LIGHT future 已完成」
  （打在 LIGHT 完成处），门 = `isDeliverableToClient && (lightRanThisSession || isLightCorrect)`。
  代价：降级 promote 的柱**照样被推**（它们的 LIGHT 也跑完了）→ S6 等于不做新事，只回到现行为。
- **B（改源头，推荐）**：让 `promotedClean` 对**真齐全**的柱恒为真——即修「降级 promote」那一族
  （与 §0.1/§0.2 的降级归因同一件事，且它同时破坏 S5 的落盘口径）。修好后本门即可原样保留。

#### 降级 promote 的机制（2026-09-19，`s6_ovd_vanilla1` 日志实测）

用户拍板走 **B**。先量根因：

- `[LIGHT_GATE] Promote` 共 1979 次，其中 **`timedOut=true`（降级）174 次**；样例一律
  `missingInWindow=3 outsideWindow=0 timedOut=true waited=2484ms sinceProgress=2003ms (no placeholders)`
  → **3 个邻柱在 2000ms 软超时内没被注入**（`outsideWindow=0`：不是几何问题）。
- 被新门挡下的**唯一柱 124 个**，且**含权威形状内的柱**（抽样 `(-16,-6)` cheb13、`(12,-5)` cheb15、
  `(-20,13)`/`(11,-18)` cheb17，`inShape=True`）→ 确属真回归，不是光环豁免问题。
- ⟹ 数量同量级（124 ~ 174）**指向同一族**：降级 promote → `promotedClean` 不置位 →
  `isLightCorrect` 被 I2 扣下 → 新门挡下。

**推理**：`AUTHORITY_ACQUIRE_BUDGET=64`/轮 × 200ms ⟹ 扫完 `dilate(shape(20),1)`（1705 柱）
需 27 轮 ≈ **5.4s**，远大于门的 2000ms 软超时 → 远处柱的邻域来不及填 → 降级。
故按用户口径把预算改为 `maxChunksPerFrame × 5`（默认 6×5 = **30/轮**，比原 64 **更低**）；
若读数显示降级未减，则瓶颈不在 acquire 预算而在**调度**（距离序扫描 ≠ 邻域优先），
下一步应是**需求驱动**：某柱进齐套门时优先把它的 3×3 缺邻拉上来（而不是等距离序扫到）。

#### 飞行场景的两个空洞（2026-09-19 手动 run）——**待实现：三处改动**

用户在飞行测试里看到**两个空洞**，日志定位（`fabric/run/client/logs/latest.log`，450MB）：

- **唯一生效的阻断是我的 S6 门**：`pushReady blocked lightIncomplete` **21739** 次；
  `SHADOW_PUBLISH blocked` / `seedGenAwaitingCompare` / `OVD_PATH disk-miss` 全 **0**。
- 被挡柱聚成**两簇**（= 用户看到的两个洞）：`(-48,3)(-48,4)(-48,5)` 与
  `(-28,20)…(-7,25)`（约 20 柱）。
- 两簇签名一致：
  ```
  LIGHT_GATE Promote (-48,4) missingInWindow=0 outsideWindow=3 timedOut=false   ← 干净 promote
  withhold isLightCorrect (not authoritative) (-48,4)                           ← I2 扣下
  pending authoritative (-48,4) pending=240                                     ← S2c 已登记
  pushReady blocked lightIncomplete (-48,4) lc=false auth=false                 ← S6 门挡下
  ```

**两个偏差叠加（都不符合原版）**：

1. **我们在交付侧用「权威」当推送判据，而它对前沿柱结构性不可达**。
   `promotedClean` 要求 `outsideWindow==0`（`LightNeighborhoodGate.java:382-384`），而飞行中邻域窗
   随玩家移动 → 前沿柱 promote 时向外邻柱在窗外 → 永远不 clean → 永久洞。
   **vanilla 不这样**：它的门是 `getChunkRangeFuture(holder,1,FULL)`，**可满足**——票系统生成到
   VD+1 边界，视野内每柱的 3×3 一定会齐，所以最终「照推」（用户原话）。
2. **S2c 补不回来（用户实测「靠近也没重新拉取」）**：重触发判据 `isNeighborhoodLightReady`
   要求 **8 邻全部** `isLightInitPassed`（无窗外豁免），而窗外邻柱**永远不会被 acquire**、
   也就永远过不了 INITIALIZE → 重触发条件**永不成立** → 该柱永久停在非权威。
   这正是红线「落盘判据与迟到重触发判据**必须同口径**」被违反之处。

**三处改动（用户已拍板）**：

- **①推送门改用「本会话 LIGHT 已跑过」**：新增会话级、**与 I2 无关**的标记（在 `startLightBarrier`
  完成处打），门 = `isDeliverableToClient && (lightRan || isLightCorrect)`。前沿柱**照推**（与
  vanilla 一致），不再出现永久洞。代价：前沿柱会带「向外一侧缺邻」的光进客户端，靠 ② 后补修正。
- **②重触发门改成窗内口径**：`isNeighborhoodLightReady` 只要求**窗内**邻柱 init 过，窗外邻柱不参与
  —— 与「3×3 齐套」的实际可达状态一致，S2c 才能真正收敛。
- **③缺邻降级改为原版的「空气柱」而不是基岩柱**（用户口径）。**原版证据（1.20.1 mojmap，
  已核源码）**：`SkyLightEngine.getLowestSourceY` 在邻柱未加载时
  （`getChunkSources` → `getChunkForLighting` 返回 null）**返回传入的 fallback**，
  `checkNode` 用 `getLowestSourceY(x, z, Integer.MAX_VALUE)` → 判定 `y >= lowestSourceY` 恒真 →
  **整列按「源头在最高处」= 全 15（开放天空）**。即 **vanilla 对缺失邻柱是「空气柱 / 开放天空」，
  不是不透明**。待查：影子端「缺失邻柱」当前呈现给光引擎的是什么（loaded-but-opaque？），
  改成让引擎走 null/空气路径。

#### 真根因：预算被「交付计数」吃掉（2026-09-19，`budget1`/`group1`/`budget2` 三轮定位）


上面的「队列深度」推理**是错的**（用户质疑「如果服务端按顺序 pull 的话不应该超时啊」，正确）。
日志标签与变量对不上：`authority-acquire ... submitted={} compareQueued={} material={}` 实际打的是
`submitted, published, skippedMaterial` —— **`compareQueued` 其实是 `published`（交付数）**。

于是旧判据 `submitted + published >= budget` 的后果（实测 `budget1`，`budget=30`）：

```
submitted=30 published=0  material=0        ← 第 1 轮
...
submitted=0  published=30 material=203      ← 之后 171/206 轮都这样
```

**近处柱先被枚举且已 material，其中 30 个可交付 → `published` 打满预算 → `break`** →
后面 1341 个**非 material 的远处柱，一轮都没被 acquire 过** → 它们的邻柱永不到齐 →
2000ms 软超时 → 降级 promote → `promotedClean` 假 → `isLightCorrect` 被扣 → 新门挡下。
「整行缺失」（`[.IM.M.IM]` 解出 dz=+1 整排）正是「远处某一行整排没被 acquire」的签名。
seedGen 分支**不是**原因（日志 `play init (caps=[...], seedGen=false)`、`local worldgen=0`）。

**修法（用户口径：「不要按完成轮次来算预算，每轮都从0开始」）**：

```java
// ① 判据只看 acquire；② 不 break（走完列表，material 分支照常）；③ 同组发完
long lastAcquireDomain = Long.MIN_VALUE;
for (ChunkPos pos : enter) { ... material 分支照常 ...
    long domain = domainId(pos.x, pos.z);
    if (submitted >= budget && domain != lastAcquireDomain) continue;
    lastAcquireDomain = domain;
    acquire(...); submitted++;
}
```
预算值 = `chunk.maxChunksPerFrame × 4`（用户口径；取消固定 64）。顺带把日志标签 `compareQueued` → `published`。

**实测结果（`s6_ovd_budget2`，**冒烟 PASS**）**：

| 指标 | 基线 `s5_halo_v2` | `vanilla1` | `group1` | **`budget2`** |
|------|------------------|-----------|----------|---------------|
| R1 `networkReceived=shadowInjected` | 1705 | 1277 | 1230 | **1705** ✅ |
| R1 `shadowReady=clientApplied` | 1529 | 1099 | 1066 | **1529** ✅ |
| R2 `clientApplied` | 1089 | 952 | 884 | **1085**（−4） |
| 降级 promote（`timedOut=true`） | — | 174 | 162 | **60** |
| 唯一被挡柱 | — | 124 | 97 | 74 |
| `submitted=0` 轮数 | — | 171/206 | 181/206 | **5/98** ✅ |

`submitted` 分布 21~32/轮（上界 32 > 预算 30 = **同组发完**在起作用）。冒烟 0 failures / 0 warnings。
R2 的 −4 与残留 74 被挡柱即「3×3 始终没齐」的真降级柱——按新口径**本就该挡**，由 S2c 重触发补。

## 2. 明确不做（边界）

1. **不每柱物化 9 柱域**。3×3 是**前置条件**，不是喂给引擎的载荷；真正需要按柱持有的只是
   `promoted` / `promotedClean` 这类**判定状态**。唯一真需要索引的是「邻柱到达时要唤醒谁」，
   用反向索引或扫 frontier 即可（S2c 的 `recheckPendingAuthoritative` 就是「扫 3×3」这一实现）。
2. **不改**光桥删除后的"一次性快照"语义（不复活 section 级回传）。
3. **不解决** 2026-09-19 09:30:34 影子主循环静默停摆（chunk/IO 路径，另案；见 [`../mod-compat.md`](../mod-compat.md) §11 同族问题）。重设计不得把它当验收项。
4. **OVD 不在本期**（用户拍板「OVD 作为二期开发」）。S1 的单点化必须**保持 OVD 行为不变**（`inOvdWindow` 原样保留、不改判定）；`DELIVER_VIEW_MARGIN_CHUNKS = 4` 的来历若查明属 OVD，随二期一起处理。
   → **二期规格已登记在 §1.7**（含 G1/G2/G3 对码与一处待裁决分歧）。

## 3. 落地顺序（最小风险优先）

| 步 | 内容 | 为什么这个顺序 |
|----|------|----------------|
| **S1** ✅ | 统一交付出口（7→1）+ 几何单点化 | 收益最大、风险最低；**本身就能把 P2 暴露成一个可调参数** |
| **S2** ✅ | 落盘侧补 I2：`promotedClean` + `isColumnLightAuthoritative` + `ShadowSeedServer.syncLightCorrect` 唯一收口；非权威柱登记 `pendingAuthoritative`（**不挡交付**） | 解决 P4；这是"半成品"的根治点 |
| **S2c** ✅ | 迟到重触发：邻域补齐（`isNeighborhoodLightReady`）→ `rearm` 重入齐套门 → 干净放行 → LIGHT → 重交付 | 解决 P5 的**本会话**部分（S5 是它的完整形态） |
| **S3** ✅ | 接上光环：`chunk.lightHaloRadius`（默认 1，钳 `[0,1]`），计算域 = 权威形状的**切比雪夫膨胀**（`isInComputeDomain` 单点：acquire 枚举 / pull 门 / 注入表保留域 / 齐套门等待窗） | 解决 P1/P3；参数化便于调 R |
| **S3b** ✅ | **交付域收到 `serverVD`**（删 `DELIVER_VIEW_MARGIN_CHUNKS=4`）→ 交付集 = 权威集，光环只算不交付；OVD 带独立放行 | 用户拍板：「扫描客户端推送时最简单的是直接排除 LIGHT_HALO…客户端推送窗 = VD 就行了」；同时解决 P2 |
| **S4** ✅ | 波前驱动：200ms + 预算**原样保留**，排序改为 3×3 域分组（`domainComparator`：同域一组、组内按距离；组间按各域最近柱距离），落在 **acquire 侧 + `drainPendingPulls` 真 C2S 出口**两处 | 前 3 步做完后，这只是**调度替换**，不碰正确性；反过来先做会把正确性与调度搅在一起，难归因 |
| **S5** ✅ | 缺邻（**含窗外邻柱**）算出的光**不落盘 `isLightCorrect`**；迟到重触发判据 `isNeighborhoodLightReady` 与落盘判据**同口径**（3×3 真齐全） | 用户拍板：「如果是缺邻超时的，落盘不能标记光照完成」；形式复用原版 `isLightOn`（见 §1.2） |
| **S6** ✅ | 推送门：`isPushableToClient = 交付窗内 && (非光环 \|\| 光照完成)`（光照完成 = `isLightCorrect`）；接入 `publishCachedChunk` + `offerReady`；`sweepOvdRing` 跳过光环柱 | 用户拍板：「推送客户端需要再加格条件，非光环 或者 (OVD 并且光照完成) 防止光照未完成的区块进入客户端」「扫描到 OVD 环区，非光环区块，直接提交 OVD 读取即可」（见 §1.7） |

> **本期范围 = S1~S5（不含 OVD）+ S6（OVD 二期第一项）**。OVD 二期规格与 G 表见 **§1.7**。S1 做几何单点化时，OVD 那一段（`inOvdWindow`）原样搬过去、判定逻辑不动；`inDeliverableShape` 的 `+4` 也先原样保留，只把它变成唯一一处参数。

### 3.1 落地记录（2026-09-19 S1 / S2 / S2c）

**S1**：`offerBuiltChunkPacket` 把 B 族（官方包直通）并入 `ready` 队列 → 唯一出口
`ready → drainReady → applyReadyChunk`（白拿 B6 几何门 / 维度闸 / apply 预算 / landed 记账）。
冒烟交付指标与基线逐字节一致（1529 柱 / 23.9MB、632 域 / 4、1075），`[SHADOW_BRIDGE] forward` → 0。

**S2a/S2b**：
- `LightNeighborhoodGate.promotedClean`：promote 时 `notInjected==0 && injectedNotInit==0` 才置位。
- `ShadowLightCompute.isColumnLightAuthoritative(dim,pos)` = `wasPromotedClean(selfKey)`。
- `ShadowSeedServer.syncLightCorrect` 是全仓**唯一**把 `isLightCorrect` 置真的地方 → 在那里加 I2 门，
  非权威柱扣下（`[SHADOW_LIGHT] withhold isLightCorrect (not authoritative)`）。

**S2c**：`pendingAuthoritative` 登记 + `finishLight` 内复查 3×3 → `rearm` + `enqueue` + `tryPromote` →
`submitLightBatch`。探针：`lightProbe.pendingAuthRegistered` / `pendingAuthRelight`；
`stallSnapshot()` 新增 `pendingAuth=N`。

**两处实现期修正（均为"改源头、不叠门"）**：

1. **I2 去掉「8 邻 `wasPromoted`」**：见 §1.2。REUSE 柱不进齐套门 → 该判据在 R2 系统性误判。
2. **REUSE 柱补标 `lightInitPassed`**（`startLightBarrier` 的 fallback 分支，
   `initializeNativeLight` 完成后）：REUSE 柱**确实**跑了 INITIALIZE，但原来不标记 →
   邻柱的齐套判定把它当「已注入未过 INITIALIZE」→ 白等 2s/4s 后**降级放行**
   → 又因 I2 被扣下不落盘。R2 里邻柱几乎全是读盘复用柱，成片发生。
   标记后齐套判定与引擎实际状态一致（不是放宽：邻柱没真装好层时，门照旧等）。

**验证（【已验证】）**：`common:test` 322 tests / 0 failures（1.20.1）+ `scanVersionBoundaries`
+ 1.20.1 fabric/forge、1.21.1 四模块、1.21.11 common/fabric/neoforge 全绿。

**未验证（必须显式列出）**：
- **运行时行为未验证**：S2c 的重算路径、`promotedClean` 的实际命中率、REUSE 标记对降级率的
  影响，全部**没有跑过游戏**（本次只做了编译 + L0 单测）。`[LIGHT_GATE] Promote` 的降级率
  与 `pendingAuthRelight` 计数需要一次手动 run 或冒烟才能读数。
- **`promotedClean` / `rearm` / `isNeighborhoodLightReady` 无单测**：都需要 MC 实例
  （`tryPromote` 要 `ShadowSeedServer`），L0 层覆盖不到。
- 屋檐/洞口**目视**未做（红线要求）。

### 3.2 落地记录（2026-09-19 S3 / S4）

**S3（光环接上）**——新增配置键 `chunk.lightHaloRadius`（CLIENT，默认
`ShadowPullRadii.LIGHT_HALO_RADIUS`=1，范围 `[0, MAX_LIGHT_HALO_RADIUS]`）。
`ShadowPullRadii.LIGHT_HALO_RADIUS` 从死常量变成该键的**默认值**。

**上限推导（实现期修正）**：初版把上限写成 `AUTHORITY_MARGIN`(=2)，**错了**。
客户端窗口 `contains(VD+R)` 的切比雪夫**外接盒**是 `cheb ≤ VD+R+1`
（`ChunkShapeCompat.boundingRadius` = `range+1`），服务端签发是纯切比雪夫
`cheb ≤ VD+AUTHORITY_MARGIN` → 对齐要求 `R+1 ≤ AUTHORITY_MARGIN` → **R ≤ 1**。
故 `MAX_LIGHT_HALO_RADIUS = AUTHORITY_MARGIN − 1 = 1`：默认值恰好贴满签发余量，
**没有上调空间**；R=2 需要两侧同时放宽（会放宽所有客户端的请求上界），不是纯配置逃生口。
（S3 之前 R=0 时余量是 1 环，这正是 `AUTHORITY_MARGIN=2` 的来历。）

**半径单点化**：新增 `ShadowTrackingSession.authorityRange()` = `serverVD + lightHaloRadius()`，
四个消费者全部改用它：
1. `drainAuthorityAcquires` 枚举半径（原 `serverVD`）→ 光环柱被真正拉取；
2. `isAuthorityPullEligible`（pull 门 / Provider 窗外丢弃）；
3. `enqueueOutOfWindowInjectedForReclaim` 的**保留域**——否则光环柱一注入就被登记回收
   （它在 serverVD 外），6s 后摘表 → 边界柱的 3×3 又缺邻；
4. 齐套门等待窗 `isInNeighborhoodWindow`（新，见下）。

**P3 收口（门的等待窗）**：`LightNeighborhoodGate.isInNeighborhoodWindow` 原为
`isAuthorityPullEligible || isDeliverableToClient`——后者是**交付域**（serverVD+4），
不是拉取承诺，于是门会去等「没人拉」的邻柱：白等 2~4s → 降级放行 → 又因 I2 被扣成「非权威」。
现改为 `authorityRange() ∪ OVD 环带`（`DELIVER_VIEW_MARGIN_CHUNKS` 的来历仍未查明，
风险 d，故**只收等待窗、不动交付域**）。语义等价性论证：那圈邻柱等完照样缺席，
**光的结果不变**，变的只是「等待时长」与「是否被误判为非权威」——所以这不是收紧正确性。

**S4（域分组排序）**——新增 `ShadowTrackingSession.domainComparator` / `sortByDomain` /
`domainId`：3×3 **平铺**（`floorDiv(·,3)`，锚点在世界原点，不随玩家漂移；平铺无重叠 →
§1.5 的「跨域去重」结构性成立）。两处生效：
- `drainAuthorityAcquires`（决定进 pending 队列的**前沿**）；
- `VanillaAlignedChunkProvider.drainPendingPulls0`（**真正的 C2S 出口**——只在 acquire 侧排序
  会被这里的距离排序覆盖，所以必须两处都改）。

**风险 a 保留**：`drainAuthorityAcquires` 仍是**每轮按当前中心重扫整个窗口**的「待办集合」，
不是固定种子扩散球 → 移动 / TP / 切维度天然免疫。

**验证（【已验证】）**：新增 L0 单测 `ChunkDomainOrderTest`（5 例：域分组连续、组间按最近柱距离、
组内距离升序、`floorDiv` 平铺含负数、退化输入不抛、光环半径钳位）= 全绿；
`common:test` 327 tests / 0 failures；配置键 round-trip 由 `ConfigRestructureRoundTripTest`
（`lightHaloRadius=2` 写入读回相等）覆盖；`scanVersionBoundaries` + 1.20.1 fabric/forge、
1.21.1 四模块、1.21.11 common/fabric/neoforge 全绿（cloth-ui 源集随 fabric/forge 编译）。

**运行时验证（【已验证】冒烟 `s3s4_halo_domain`，1.20.1 fabric，classic 两轮，PASS 101s）**：

| 指标 | 基线（S1/S2，11:28） | S3+S4（12:43） | 差 |
|------|---------------------|---------------|-----|
| R1 区块加载（VD20） | 1529 | **1665** | **+136** |
| R2 超视渲染已加载 / 缺失 | 632 / **4** | **636 / 0** | +4 / **−4** |
| R1 光照重算 | 1792 | 2009 | +217 |
| R1 区块缓存全命中 | 1770 | 1779 | +9 |

**这组数字被精确解释（非近似）**：用原版 `ChunkMap.isChunkInRange`（1.20.1）公式穷举得
`|shape(20)|=1529`、`|shape(21)|=1665`、两者之差 **136**。即
- 基线 R1 交付 = `|shape(serverVD)|` = 计算域；
- 本次 R1 交付 = `|shape(serverVD + halo)|` = **新的计算域**；
- R2 的 `636 = cheb(16) − shape(10)` = OVD 环带，其内侧一环正是光环环带 → 缺失 4→0。

**结论（【已验证】）**：光环柱确实被拉取、算光**并交付**了——即
**交付集 == 计算域**（因为交付域 `serverVD+4` ⊇ 计算域，没有任何东西去计算多出来的环）。
这直接印证了 P2 的实际后果，也说明 §1.1「LIGHT_HALO 只算光**不交付**」这一条**尚未实现**。
**净效应**：S3 把「最外圈缺邻环」整体外推一环（VD → VD+1）并多交付 136 柱；
它既没恶化、也没消除那个环——要消除它必须让**交付域 = serverVD（∪ OVD）**，
即 `DELIVER_VIEW_MARGIN_CHUNKS: 4 → 0`（一行）。**本次未改**（风险 d 的「先查证来历」门）。

**未验证（S3/S4）**：
- 域分组（S4）的**收益**未量化：冒烟是静止场（`MoveSeconds=0`），看不出「东投一柱西投一柱」；
  要量化需移动场 run + 服务端侧 pull 请求的空间聚集度统计（未做）。
- `authority-acquire` 日志里的 `halo=` 值、`[LIGHT_GATE] Promote` 降级率变化**未读数**
  （冒烟 PASS 只跑了分析器门禁，没有抓这两行）。

### 3.3 落地记录（2026-09-19 S3b / S5，含两处实现期修正）

用户飞行测试通过后拍板三件事：① 交付窗收到 `serverVD`（排除光环、兼容 OVD）；
② S5 = 缺邻超时的柱落盘不得标「光照完成」；③ **查原版 LIGHT_HALO 落盘标记形式并复用**。

**③ 原版形式（【已验证】读源）**：`net.minecraft.server.level.ThreadedLevelLightEngine.lightChunk`
（1.20.1）：
```java
public CompletableFuture<ChunkAccess> lightChunk(ChunkAccess chunk, boolean isLighted) {
    chunk.setLightCorrect(false);                       // 入口先清
    addTask(..., PRE_UPDATE, () -> { if (!isLighted) super.propagateLightSources(pos); });
    return CompletableFuture.supplyAsync(() -> {
        chunk.setLightCorrect(true);                    // 引擎跑完才置真
        chunkMap.releaseLightTicket(pos);
        return chunk;
    }, ...POST_UPDATE...);
}
```
配套：`ChunkStatus.LIGHT = register("light", INITIALIZE_LIGHT, range=1, hasLoadDependencies=true, …)`；
`ChunkStatus.isLighted(c) = c.getStatus().isOrAfter(LIGHT) && c.isLightCorrect()`；
落盘 = NBT `isLightOn`（`ChunkSerializer:194` 读回 `setLightCorrect`）。
**关键**：原版**没有**单独的 halo 标记——缺邻时 LIGHT 任务根本不完成，标志停在入口清掉的 `false`，
读档 `isLit=false` → 重跑 LIGHT。**我们复用同一形式**：只有 3×3 真齐全才置真。

**① S3b（交付域）**：删 `DELIVER_VIEW_MARGIN_CHUNKS`；`isDeliverableToClient` =
`inVanillaVisibleShape(serverVD) || inOvdWindow`。`inDeliverableShape` 与 `inVanillaVisibleShape`
本已同义，合并为一个谓词（消除「两处各写一遍」）。
风险 d（`+4` 来历未查明）由用户拍板直接收紧——实测依据：S3 之前 R1 交付恰好 `= |shape(20)|`，
说明 `+4` 对「光环与 OVD 之外的环」是空操作；收紧只影响光环与 OVD 边界。

**② S5（缺邻不落盘）**：`promotedClean` 增加 `outsideWindow == 0`；
`isNeighborhoodLightReady` 同步改为「8 邻全过 INITIALIZE」（去掉窗外豁免）。
两处**必须同口径**：否则光环柱会「重触发 → 仍不完整 → 再登记」无限 churn（这是实现期发现的坑）。

**修正 1（几何口径）**：S3 初版用 `contains(serverVD + R)`，**近似错了**。
穷举实测：VD=10/16/20 时分别有 **8/16/20 个权威柱**的 3×3 戳出该域（形状切角处）。
改为**切比雪夫膨胀** `containsDilated` 后为 **0**；且两者最大切比雪夫半径相同（`VD+R+1`），
`dilate=1` 恰好贴满服务端签发上限 `VD+AUTHORITY_MARGIN`。**「1 环就够」在膨胀口径下才成立。**

**修正 2（`MAX_LIGHT_HALO_RADIUS`）**：初版 = `AUTHORITY_MARGIN`(=2)，**错**。
膨胀形状最大切比雪夫半径 = `VD+R+1`，服务端签发 = `VD+AUTHORITY_MARGIN` → `R+1 ≤ AUTHORITY_MARGIN`
→ `R ≤ 1`。故上限 = `AUTHORITY_MARGIN − 1`：**取值只有 0/1**，默认恰好贴满。

**验证（【已验证】）**：新增 L0 `ChunkShapeDilationTest`（5 例：dilate=0 与 `contains` 逐点等价、
权威 3×3 零缺口、形状环近似必然漏邻（反例钉死）、枚举盒不超签发上限、dilate 单调）；
`common:test` **332 tests / 0 failures**；`scanVersionBoundaries` + 1.20.1 fabric/forge、
1.21.1 四模块、1.21.11 common/fabric/neoforge 全绿。
> 实现期踩坑（已修）：该测试若在 `Bootstrap.bootStrap()` 前触碰 `ChunkShapeCompat.contains`
> （→ `ChunkMap` → `BuiltInRegistries`），会让 `BuiltInRegistries` 在测试 JVM 内**永久不可用**，
> 连锁打挂同 JVM 的 `NetworkOptimizationTest`（`initializationError`）。已按既有模式加
> `@BeforeAll` bootstrap。**凡触碰 `ChunkShapeCompat.contains` 的测试都必须先 bootstrap。**

**运行时验证（【已验证】冒烟 `s5_halo_owndomain`，1.20.1 fabric classic）**——trace 计数：

| 计数 | 值 | 期望 |
|------|----|------|
| `networkReceived` | **1705** | `\|dilate(shape(20),1)\|` = 计算域 ✓ |
| `shadowInjected` | **1705** | 同上 ✓ |
| `shadowReady` | **1529** | `\|shape(20)\|` = 交付域 ✓ |
| `clientApplied` | **1529** | 客户端落地 = 交付域 ✓ |

即 **光环（1705−1529 = 176 柱）被拉取/注入/算光，但一柱都没进客户端**——S3b 生效。
（R2：`超视渲染 已加载 636 / 缺失 0` = `cheb(16) − shape(10)`，OVD 带不受影响 ✓。）

**冒烟门禁模型更新（S3b 的必然配套，【已验证】）**：该 run 首跑 **FAIL**，两个 P0
（`TRACE_EXPECTED_NOT_PRESENT` / `TRACE_INJECTED_NOT_READY`）gap **count 恰好 176**、坐标全在
权威形状之外——因为 trace 门禁的模型是 `expected = networkReceived`（注释原话「收到即常驻」），
而 S3b 刻意让光环「收到但不交付」，该假设被推翻。修法（不是放宽门禁，是修模型）：
`runtime-smoke-test.ps1` 透传 `Vd1/Vd2`；`analyzer.py` 新增 `_in_authority_shape` /
`_all_outside_authority_shape` / `_halo_only_gap`——**缺口全部在权威形状之外**才判为光环（INFO），
**只要有一个在形状内就仍是 P0**。用失败 run 的结果 JSON 回灌验证：`pass=True`，
两条 gap 降 INFO 且 detail 写明「inside-authority gaps remain P0」；`analyzer` 自带 26 条单测全绿。

**未验证（S3b/S5）**：
- S5 的**落盘**行为未直接读盘验证：预期「光环柱不写 `isLightOn`」需要用 type-126 导出或
  `ShadowStorageHashes` 探针逐柱核对（本次只验证了「不交付」与「不 ready」，未验 NBT）。
- 光环柱「不落盘 ⇒ 每次进服重算」的**代价未量化**（`lightProbe` 无「未落盘柱数」项）。
- **旧存档里已落盘的「光环柱坏光」不会自动清**：本次改动只保证今后不写，已写的
  `isLightOn=true` 仍会被复用。要么清 `hassium_cache`，要么加一次性落盘失效（未做）。



## 4. 风险与未决

| ID | 风险 / 未决 | 处置 |
|----|-------------|------|
| a | **起点在移动**：虚拟玩家连续移动 → 波前每轮重新播种。若实现成"固定种子 BFS"，会出现"玩家飞走了、波前还在原地补" | 波前必须是"待办集合"而非"扩散球"；现状 `drainAuthorityAcquires` 每轮按**当前**中心重扫全窗，天然免疫——别丢这个性质 |
| b | **每 tick 扫描**会把频率 ×5 | 用户已拍板 200ms；预算语义必须一起搬（§1.4） |
| c | ~~光环几环够不够~~ → **已决：1 环**（用户拍板，2026-09-19）。理由见 §1.1「为什么 1 环就够」：光环只负责给权威柱补齐 3×3，边界权威柱有 3~5 个光环邻柱且它们都是真实已算光柱 → 不出现「缺邻当基岩」的错误传播；光环自身残差被限制在交付域之外 | 参数 `chunk.lightHaloRadius` 是逃生口，但**取值只有 0 与 1**：客户端窗口 `contains(VD+R)` 的切比雪夫外接盒是 `cheb ≤ VD+R+1`，服务端签发是 `cheb ≤ VD+AUTHORITY_MARGIN` → 对齐要求 `R+1 ≤ AUTHORITY_MARGIN`（=2）→ `R ≤ 1`。**R=2 不是纯配置逃生口**：必须同时放宽服务端签发范围（`ShadowPullRadii.AUTHORITY_MARGIN` 或 `ShadowPullServer` 的 maxDistance），那会放宽**所有**客户端的请求上界 |
| d | ~~`DELIVER_VIEW_MARGIN_CHUNKS = 4` 的**来历未查明**~~ → **已决：删除（S3b，用户拍板 2026-09-19）**。实测依据：S3 之前 R1 交付恰好 `= \|shape(serverVD)\|`（说明 `+4` 对光环/OVD 之外的环是空操作）；S3 加了光环后交付变成 `\|shape(VD+1)\|`，光环被交付（用户 message-5 的「外圈不完整光块进视野」）。现交付域 = `serverVD` ∪ OVD 带，`inDeliverableShape` 与 `inVanillaVisibleShape` 合并为一个谓词 | 已落地；`+4` 的原注释（「B6 交付门余量」）无法追溯具体事故，如后续在边界发现交付空洞，优先怀疑此收紧 |
| e | 跨维度污染 | 交付出口必须带维度闸（P6 的 7 个入口里只有 `forwardToRealClient` 有） |

## 5. 验收

1. **视觉（主锚）**：移动中的**屋檐 / 洞口**。红线：冒烟 PASS **不能**代替目视（`AGENTS.md` 算光红线）。
2. **数据**：缺邻降级率（`[LIGHT_GATE] Promote ... missingInWindow=N`）目标降到 ~0；
   并读 `lightProbe.pendingAuthRegistered` / `pendingAuthRelight`：
   - `pendingAuthRelight / pendingAuthRegistered` 接近 1 → 登记柱基本都被补算（S2c 有效）；
   - 长期停在 `pendingAuth` 不降（`stallSnapshot` 的 `pendingAuth=N`）→ 邻域一直没补齐（S3/S4 该上场）。
3. **交付集口径（S3b，最直接的验收）**：classic R1 的 trace 计数必须满足
   - `networkReceived == shadowInjected == |dilate(shape(Vd1), 1)|`（VD20 → 1705）
   - `shadowReady == clientApplied == |shape(Vd1)|`（VD20 → 1529）
   
   即**光环柱一柱都没进客户端**；差额恰为光环环带（VD20 → 176）。
   `_in_authority_shape` 的 Python 口径见 `runtime-smoke-test.md`「光照光环豁免」。
4. **回归**：`common:test` + `common`/`fabric`/`forge`/`neoforge` 三版本编译 + `scanVersionBoundaries`
   + `python -m unittest scripts.smoke.test_analyzer`。
5. **对照**：`REUSE_PHASE1_INITIALIZE` 与 `chunk.lightHaloRadius`（0=回退旧语义 / 1=光环）两个开关都要能单独回退做 A/B。

## 6. 现状锚点（代码索引）

| 关注点 | 位置 |
|--------|------|
| 光环常量 | `protocol/ShadowPullRadii.java`（`LIGHT_HALO_RADIUS=1` = 配置默认值、`MAX_LIGHT_HALO_RADIUS=AUTHORITY_MARGIN−1=1`、`AUTHORITY_MARGIN=2`） |
| 配置键 | `config/ConfigSchema.java`（`CHUNK_LIGHT_HALO_RADIUS`）、`config/HassiumConfig.java`（`ChunkCoreConfig.lightHaloRadius`）、`config/ConfigSnapshotAdapter.java`、`config/FabricTomlConfigIO.java`（`readChunkCore`/`writeChunkCore`）、`cloth-ui/.../HassiumClothConfigScreen.java`（`toConfig` 透传） |
| **计算/拉取域**（唯一谓词） | `ShadowTrackingSession.isInComputeDomain`（228）+ `computeDomainBoxRadius`（241）+ `lightHaloRadius`（251）；几何 = `compat/ChunkShapeCompat.containsDilated`（62）/ `dilatedBoundingRadius`（76）；消费者：`drainAuthorityAcquires`（467 枚举）、`VanillaAlignedChunkProvider`（acquire 窗外丢弃 + drain 窗外丢弃）、`enqueueOutOfWindowInjectedForReclaim`（保留域）、`isInNeighborhoodWindow`（285 齐套门等待窗） |
| **交付域** | `ShadowTrackingSession.isDeliverableToClient`（1037）= `inVanillaVisibleShape`（1016，= `serverVD`）∪ `inOvdWindow`。**`DELIVER_VIEW_MARGIN_CHUNKS` 已删**（S3b） |
| S4 域分组 | `ShadowTrackingSession.domainId`（310）/ `domainComparator`（321）/ `sortByDomain`（333）；出口 `VanillaAlignedChunkProvider.drainPendingPulls0` |
| 节拍/预算 | `ShadowTrackingSession.AUTHORITY_ACQUIRE_INTERVAL_MS=200`、`AUTHORITY_ACQUIRE_BUDGET=64`；`VanillaAlignedChunkProvider.PULL_DRAIN_INTERVAL_MS=50`、`PULL_SENDS_PER_DRAIN=5` |
| S4 单测 | `common/src/test/.../shadow/track/ChunkDomainOrderTest.java`（5 例） |
| S3 几何单测 | `common/src/test/.../compat/ChunkShapeDilationTest.java`（5 例；**必须先 bootstrap**，见 §3.3 踩坑） |
| 齐套门 | `shadow/light/LightNeighborhoodGate.java`（soft 2s / hard 4s、`tryPromote`、就绪判据、`isInNeighborhoodWindow`（239）、`isNeighborhoodLightReady`（260，S2c/S5 触发判据，与落盘同口径）、`rearm`（285）、`promotedClean`（91，I2/S5 来源）） |
| 已就绪集合 | `shadow/light/ShadowLightCompute.java:143`（`lightInitPassed`）、`:1583`（`isLightInitPassed`）、`:1562`（写入点 phase-1）、`:2144-2158`（写入点 REUSE 屏障分支，S2c 修正） |
| I2 判据 | `ShadowLightCompute.java:702`（`isColumnLightAuthoritative`）、`ShadowSeedServer.syncLightCorrect`（唯一收口） |
| S2c 迟到重触发 | `ShadowLightCompute.java:166`（`pendingAuthoritative`）、`:2207,2255-2265`（`finishLight` 内登记 + 复查）、`:2273`（登记）、`:2300`（`recheckPendingAuthoritative`）、`:2873`（`stallSnapshot` 的 `pendingAuth=`） |
| 算光侧门 | `ShadowLightCompute.java:2095-2096`（`wasPromoted` 检查点） |
| 光超时兜底 | `ShadowLightCompute.java:2357`（`sweepLightTimeouts`，Render 线程） |
| 交付入口 ×7 | `ShadowTrackingSession:408`、`ShadowPullClient:352`、`ShadowVanillaLightPipeline:71`、`ShadowChunkDeliver:52`；`mixin/shadow/MixinChunkMap:108`、`mixin/server/MixinPlayerChunkSender:127`、`mixin/server/MixinServerPlayer:65` |
| 交付实现 | `ShadowLightCompute.java:788/802`（`publishCachedChunk`）、`shadow/track/ShadowOfficialPacketBridge.java:41`（`forwardToRealClient`） |
| 原因枚举（`RELIGHT` 空置） | `shadow/track/ShadowChunkProvider.java:19-23` |
| 几何谓词 | `compat/ChunkShapeCompat.java:22`（`contains`）、`:43`（`boundingRadius`）、`:53`（`inOvdBand`） |
| 交付客户端 apply | `shadow/light/ShadowLightCompute.java` `drainReady` / `applyReadyChunk`；客户端探针 `client/ClientChunkHandler.java:397-403` |

## 7. 会话来源

2026-09-19 会话：光桥删除 → 手动 run 发现「飞行到退出前位置区块不加载 + 退出卡顿」（影子主循环静默停摆，另案）→ 用户提出「外圈缺光块 + VD+1 光环」设计质疑 → 核对确认 P1~P6 → 用户拍板三区状态机 + 参数化光环 + 200ms + 3×3 域分组排序。

## 8. 第二轮（2026-09-19 夜）：任务 #36 / #37 / #38

### 8.1 飞行日志取证（`fabric/run/client/logs/latest.log`，450 MB / 3 289 045 行）

流式逐行统计（`StreamReader`，非 grep）：

| 计数 | 值 |
|------|----|
| `[LIGHT_GATE] Promote` | 8945 |
| 其中 `timedOut=true`（降级） | 264（2.95%） |
| 其中 `outsideWindow != 0` | **8853（99.0%）** |
| `pending authoritative`（首次登记） | 2865（2710 个不同坐标，单坐标最多 6 次） |
| `re-light after neighborhood ready` | 2423（2382 个不同坐标，**单坐标最多 2 次 → 旧判据下无 churn**） |
| `pushReady blocked lightIncomplete` | 21739 |
| `withhold isLightCorrect` | **3 051 991（占全文 93%）** |

**`withhold` 3M 不是算光循环**：`ShadowSeedServer.confirmLightsCorrectIfConverged()`（`:1649`）
被 `ShadowLightCompute.drainReady()`（`:2607`）**每帧**调用，遍历整张 `injectedChunks` 表，对每个
`complete && !isLightCorrect()` 的柱调 `syncLightCorrect(true)` → I2 扣下 → 每条打一行日志。
≈1500 柱 × 60 fps ⟹ 每帧数千行。**属既有诊断刷屏/重复扫描问题（另案）**，与本次三任务无关。

`(-48,3..5)`（用户报的第一个洞）逐行轨迹【已验证】：

```
[SHADOW_PROVIDER] enqueue pull (-48,4) reason=TRACKING
[LIGHT_GATE] Blocked (-48,4) neighbors=[XXXII...] injectedNotInit=2 notInjected=3
[SHADOW_LIGHT] INITIALIZE_LIGHT done (-48,4)
[LIGHT_GATE] Promote (-48,4) missingInWindow=0 outsideWindow=3 timedOut=false (no placeholders)
[SHADOW_LIGHT] withhold isLightCorrect (not authoritative) (-48,4)
[SHADOW_LIGHT] skip beyond view+margin (-48,4)          ← 该柱此刻不在交付窗
[SHADOW_LIGHT] pending authoritative (-48,4) pending=240
（此后 ~12k 行 withhold，全部来自 confirmLightsCorrectIfConverged 的每帧扫描）
```

关键：`outsideWindow=3` = 该柱**向外 3 个邻柱在等待窗之外**（前方，尚未进窗）。故
`promotedClean` 恒假 → `isLightCorrect` 被 I2 扣下 → 旧 S6 门挡下 → 洞。

### 8.2 任务 #36（已落地）：`lightRan` 会话级标记 + 推送门换判据

- 新增 `ShadowLightCompute.lightRan`（会话级 `Set<Long>`，**与 I2 无关**）。写入点 =
  `completeLight`（LIGHT future 完成、唯一完成收口；`startLightBarrier` 两条 `whenComplete` 都经此），
  保证早于同柱 `pushReady`；清除点 = `cancelChunkWork` / `onDisconnect`（与 `lightInitPassed` 同生命周期）。
- 新增 `ShadowLightCompute.lightRanThisSession(dim,pos)`。
- `ShadowTrackingSession.isPushableToClient` 第二判据由 `isColumnLightAuthoritative` 换成
  `lightRanThisSession`；`lightGateDetail` 的 `auth=` → `ran=`。
- **语义**：前沿柱的 LIGHT 也跑完了 → 照推（与 vanilla「到 `FULL` 蕴含本会话 LIGHT 已完成」一致），
  永久洞消失。**代价（用户已接受，见本文件「两条出路」A）**：降级 promote 的柱照样被推，
  其「向外一侧缺邻」的光靠 #37 的重算重交付覆盖；S6 相对现状等于不再新增阻挡。
- **预期回归**：classic R1 不变（光环柱仍被 `isDeliverableToClient` 挡住——S3b 的第一道门独立于本判据）；
  R2 `clientApplied` 应从 1085 回到 **1089**（= `33²`，OVD 带内的光环柱重新放行）。

### 8.3 任务 #37（已落地）：`isNeighborhoodLightReady` 改窗内口径 + **进展门**

- `LightNeighborhoodGate.isNeighborhoodLightReady` 改为**只要求窗内邻柱 init 过**
  （用 `isInNeighborhoodWindow` 过滤；窗外邻柱不参与）。
- **必须配套的进展门（否则必然自激）**：新增
  `LightNeighborhoodGate.inWindowReadyNeighborCount(dim,pos)`；`PendingAuth` 记录**登记时刻**的该计数；
  `recheckPendingAuthoritative` 在 `isNeighborhoodLightReady` 之后再要求
  `当前计数 > 登记计数` 才重算。
  - **为什么必须**：`isNeighborhoodLightReady` 是**单调**判据；带窗外缺邻的柱重算后
    `promotedClean` 依旧为假 → `finishLight` 再登记 → 无门即
    `finishLight → recheck → re-light → finishLight` **自激**（互触同理）。飞行日志里
    **99% 的 promote 都带 `outsideWindow>0`**，即绝大多数柱都是这种形态——风险不是理论上的。
  - **有界**：计数单调不减 ⟹ 每柱最多因 8 个邻柱各自「新过 INITIALIZE」而重算。
  - **正是「靠近能重新拉取」**：窗外邻柱进窗后被 acquire → INITIALIZE → 计数 +1 → 重算 → 干净放行。
- `AGENTS.md` 算光红线同步改写（原「落盘判据与重触发判据必须同口径」**作废**）。

**未验证（必须显式列出）**：
- **未跑运行时**：本轮只做了 `common:compileJava` + `common:test`（1.20.1）。`re-light` 计数、
  `pendingAuthRelight / pendingAuthRegistered` 比值、R2 `clientApplied` 是否回到 1089，全部**没有读数**。
- **#37 是否真能修「靠近不重拉取」未证实**：日志显示旧判据下已有 2423 次 re-light、21739 次被挡；
  窗内口径只覆盖「邻柱在窗外」这一种成因。**另一种候选成因**：邻柱已注入（OVD 读盘 / 早先 pull）
  但**从未跑过 INITIALIZE** → `lightInitPassed` 恒假 → **新旧判据都不成立**（窗内 + 未 init）。
  这一支**本轮未取证**；下一次飞行 run 读 `[LIGHT_GATE] Blocked ... neighbors=[...]` 里 `I` 与 `M`
  的占比即可区分（`M` 降 = 窗内口径生效；`I` 占比高 = 要修「注入后没跑 INITIALIZE」的源头）。
- 屋檐/洞口**目视**未做（红线要求）。

### 8.4 任务 #38（**取证完成，结论与本文件原证据相反 → 未改代码，待裁决**）

本文件 §「三处改动」③ 的原证据称：「`SkyLightEngine.getLowestSourceY` 邻柱 null → 返回 fallback →
`checkNode` 用 `Integer.MAX_VALUE` → `y >= lowestSourceY` **恒真** → 整列 15（开放天空）」。
**逐行核 1.20.1 mojmap 源码后，该推理的符号反了**：

1. `SkyLightEngine.getLowestSourceY(int x, int z, int fallback)`：
   `return sources == null ? fallback : sources.getLowestSourceY(...)`；
   `checkNode` 传的 fallback 是 `2147483647`（`Integer.MAX_VALUE`）→
   `boolean fromSource = y >= lowestSourceY` = `y >= MAX_VALUE` = **恒假** → 走 `else` 分支
   `enqueueDecrease(PULL_LIGHT_IN_ENTRY)`。即**缺失邻柱 = 「本列没有天光源」并向外拉光**，
   **不是**「整列 15」。（`addSourcesAbove` 对 4 邻探针传的 fallback 是 `-2147483648`，
   方向相反、用途不同，不可混用。）
2. **真正决定不透明性的是 `LightEngine.getState`**（`LightEngine.java` 1.20.1）：
   `LightChunk c = getChunk(x>>4, z>>4); return c == null ? Blocks.BEDROCK.defaultBlockState() : c.getBlockState(pos);`
   → **null 邻柱 = 基岩 = 不透明**。这正是项目红线「缺邻时引擎按 Bedrock 挡天光」的出处。
3. 影子端现状与 vanilla **同形**：`MixinServerChunkCache.hassium$shadowChunkForLighting`
   （HEAD, cancellable）只做「注入表命中 → 返回注入的 LevelChunk」，未命中就**落回原版**
   `ServerChunkCache.getChunkForLighting`（1.20.1 `:251`：holder 为 null → null；否则自顶向下找
   第一个 present 的 `ChunkAccess`，到 `INITIALIZE_LIGHT.getParent()` 仍无 → null）。
   → 未注入且无可见 holder 的邻柱返回 **null** → `getState` = **基岩**。

⟹ **vanilla 对缺失邻柱既不是空气、也不指望它出现**：它的保证是 `ChunkStatus.LIGHT` 的
`range=1 + hasLoadDependencies`（3×3 必先到 `INITIALIZE_LIGHT`），**缺邻路径只是防御分支**。
所以「改成让引擎走 vanilla 的 null/空气路径」在 vanilla 口径下**没有可改之处**——现状已经就是
null 路径，而它的语义是**基岩**。

**若要真的把缺邻降级柱变亮，只能刻意偏离 vanilla**：让 `getChunkForLighting` 对缺失邻柱返回
一个**空气柱**（合成空 `ChunkAccess` / 空壳注入），使 `getState` 读到空气 → 光可穿过。取舍：

- 收益：降级柱不再被基岩挡光，观感更接近邻柱到齐后的真值。
- 代价：① **自创机制**，与项目两条既定口径相抵——「机制先查原版再复用，别自创标记」
  「光照交付按原版语义」；② 空气柱会成为**跨柱光传播的真实通道**，可能把光推到本不该有的地方
  （基岩至少在方向上保守）；③ 只影响「降级放行」这一本就该被 #37/S2c 消灭的路径——
  若 #37 生效，缺邻场景显著减少，该改动收益随之下降。

**建议（本轮未改任何代码，等裁决）**：先跑一次飞行 run 验 #37（`Blocked` 里 `M` 是否下降、
`re-light` 是否增多、洞是否消失）。若 #37 已消掉缺邻降级，#38 不必做；若仍剩「窗内已注入但未 init」
一族，那要修的是**注入后没跑 INITIALIZE** 的源头，而不是光引擎的缺邻语义。

### 8.5 全核跑满的取证（2026-09-19 18:00，含 #36/#37 的 run）——**在 chunk/IO 路径，不在光照路径**

用户报「又触发全核跑满」。对运行中的客户端（PID 19100，Fabric runClient）取证：

| 观测 | 值 / 证据 |
|------|-----------|
| CPU | `Get-Process.CPU` 90.3 s / 3.02 s wall = **29.9 核**；持续 8 min 后仍 **30.9 核**（累计 19213 CPU-s） |
| 每线程 CPU | `jstack` 自带 `cpu=`：Top 25 **全是 `ForkJoinPool-3-worker-*`**（32 个，各 0.71–0.83 核） |
| 那些 carrier 在跑什么 | `Carrying virtual thread #…` → 虚拟线程名 **`hassium-client-*`**，栈一律：<br>`ShadowSeedServer.loadFromDiskAsync(:1097)` → `loadFromDisk(:1077)` → `parseNbtBytes(:1425)` → `ShadowServerCompat.parseChunkNbt(:198)` → `ChunkSerializer.read(:125)` → `PoiManager.checkConsistencyWithBlocks(:206)` |
| 并发规模 | `Thread.dump_to_file -format=json`：`ThreadPerTaskExecutor` 持 **58** 个 `hassium-client-*`；两次 dump（间隔 3 min）**都是 58 → 稳态，不是线程泄漏/加速** |
| Render thread | `cpu=139250ms / 297s` = 0.47 核，栈在 `Minecraft.runTick` → `Thread.yield`；它每秒产出 ~8400 行日志 |
| 日志 | 305 MB / 4 748 539 行；**95.6% 是 `withhold isLightCorrect`**，全部来自 Render thread |

**光照路径不是原因（与上一轮逐项对比）**：

| 计数 | 上一轮（无 #36/#37） | 本轮（有 #36/#37） |
|------|---------------------|-------------------|
| `re-light after neighborhood ready` | 2423 | **2096** |
| `pending authoritative` | 2865 | **2607** |
| `LIGHT_GATE] Promote` | 8945 | **5500** |
| `pushReady blocked lightIncomplete` | 21739 | **0**（#36 设计目标） |
| `pack after light task` | 24406 | **4155** |
| `INITIALIZE_LIGHT done` | 6446 | **3422** |

⟹ 光照侧全线**下降**，且 `ShadowLightCompute` 里**没有任何**到 `publishCachedChunk` /
`scheduleAsyncDiskPublish` / `loadFromDiskAsync` 的调用路径。**本轮的 #36/#37 不是 CPU 的消耗方。**

**两个真实缺陷（都属既有 chunk/IO 路径，handoff §2.3 早已列为「另案」）**：

1. **影子读盘风暴**：`ShadowSeedServer.loadFromDiskAsync` 每次调用 `HassiumTaskExecutor.submit`
   → **无上限虚拟线程**（`ExecutorFactory` virtual 模式 unbounded），而每次 `loadFromDisk` 走完整
   `ChunkSerializer.read` 且**逐 section 调 `PoiManager.checkConsistencyWithBlocks`**——实测每次读盘
   约 **0.5 CPU-秒**（≈ 正常区块读的数百倍）。调用侧 `sweepOvdRing` 的预算 `OVD_DISK_BUDGET=64` /
   `OVD_SWEEP_INTERVAL_MS=100` ⟹ 上限 **640 次/秒**。32 个 carrier 被 58 个并发读盘占满。
   （本轮 `async disk miss` 仅 18 → 这些读盘**基本都命中真实数据**，即真在解析、不是空转重试。）
2. **`confirmLightsCorrectIfConverged` 每帧全表扫描 + 逐柱 INFO 日志**（`ShadowSeedServer:1649`
   被 `ShadowLightCompute.drainReady:2607` 每帧调用）→ 每帧对每个 `complete && !isLightCorrect()`
   的注入柱打一行，构成 95.6% 的日志与 Render thread 的主要开销。

**未验证 / 不能排除**：本轮无法做 A/B（无上一轮 CPU 读数、且两轮飞行轨迹不同），故
「#36 解禁交付 → 客户端 apply 更多柱 → 影子会话/cache 状态变化 → OVD 扫到更多柱」这条**间接放大链
无法排除**。要定性需固定轨迹 + 固定 cache 状态跑两次（有/无 #36）。

#### 8.5.1 悬崖定位（2026-09-19 18:25，用户判断「CPU 是瞬间跳满」——**证实**）

按秒统计该 run 的日志（773 秒）：

```
17:55:40–17:56:37   init≈100/s  pull≈60–80/s  relight 递增   lines 1.1k–9.1k/s   withhold 0–7.4k
17:56:38 起         init=0      pull=0        relight=0      lines≈6845/s         withhold≈6840
                    ↑ 此后 11 分钟恒定，总行数 - withhold ≤ 5 行/秒
```

**结论（已验证）**：17:56:38 是**悬崖**——所有实质工作（pull / INITIALIZE / re-light / apply / pack）**归零**，
日志只剩 Render thread 每秒 ~6840 行 `withhold`，**持续 11 分钟**。即客户端进入**稳态死局**，不是「逐渐变慢」。

**同时排除了「OVD 环带扫描」这条假设（用户口径正确）**：
- `sweepOvdRing` 开头即 `if (serverVD <= 0 || clientVD <= serverVD) return;`（`ShadowTrackingSession:691`）；
- `OvdClientLifecycle.effectiveClientVD`（`:46`）在 **OVD 配置关 或 滑块 ≤ serverVD** 时**返回 `serverVD`**；
- VD=20、客户端滑块=20 ⟹ `clientVD == serverVD` ⟹ **该扫描一次都不跑**。
⟹ 影子读盘的来源**不是** OVD 环带扫描，是另一个 `publishCachedChunk` 调用方。

**死循环的形态（已验证）**：悬崖后**没有任何完成日志**——`async disk miss` 全 run 仅 18 条、
`INITIALIZE_LIGHT done` / `barrier reuse phase-1` / `LIGHT_CALL` / `pack after light task` 全部 0。
而 58 个 `hassium-client-*` 虚拟线程**恒定 58 个**（两次 dump 间隔 3 min 不变）、栈恒在
`loadFromDisk → parseNbtBytes → parseChunkNbt → ChunkSerializer.read → PoiManager.checkConsistencyWithBlocks`、
32 个 carrier 各 ~0.8 核。
⟹ 这 58 个线程是**卡在/空转在一次读盘解析内部**（既不返回、也不产出日志），不是「读盘量大」。
这与用户第 3 条完全吻合：**存储管理器在「槽内没柱」时没有快速短路，而是自旋**。

### 8.6 重入风暴取证与两项修复（2026-09-19 20:00）

取证对象：`fabric/run/client/logs/latest.log`（27.8 MB / 177 636 行，19:37:05–19:40:10，含 #36/#37 + 降级记时改全局基准）。

**降级（对照 §0.1）**：Promote 8 630，`timedOut=true` **53（0.61%）**；上一轮 15 799 / 525（3.32%）。
53 次**全部**由 `injectedNotInit > 0`（窗内邻柱已注入但未过 INITIALIZE）触发；
**窗内缺柱 `notInjected>0` 导致 = 0** ⟹ #37 的窗内口径生效。
`INITIALIZE_LIGHT failed/exception = 0`（done 4 755）。
⚠ 本节同时**推翻** §0.2 的「降级全是邻柱未注入」归因（该口径已不成立）。

**重入风暴（已验证）**：

| 观测 | 值 |
|------|-----|
| `[LIGHT_GATE] Enqueue` 总行数 / 唯一柱 | 22 498 / 4 736（p50=3, p90=8, p99=28, **max=151**） |
| 单柱重入速率 | **恰好 5 次/s，持续 30 s**（149 次），每次在**不同虚拟线程**上 |
| 这些柱的 Promote 行 | 一律 `waited=30002~30051ms sinceProgress=40~199ms` ⟹ **只撞 30 s 硬上限** |
| 单柱实际工作 | 注入 1 次 / `INITIALIZE_LIGHT done` 1 次 / `LIGHT_CALL` 1 次（其余 148 次入队后无任何工作） |
| 首投延迟（玩家首次进入 8 格视野 → 首次 apply） | 中位 **−19 s**（预取正常）；最差 4 个**全在最后站位附近**：`(12,-19) +20s`、`(14,-17) +19s`、`(17,-16) +20s`、`(20,-11) +16s` |
| 重复 apply | `shadow_applied` 10 298 / 唯一柱 4 413 ⟹ **57% 是重复 apply**，origin 全 `shadow_memory_cache` |

机制：`enqueue()` 每次调用都推进 `lastColumnLandedMs`（「全局最近一次柱落地」）。
同柱重入把「重入」误当「新柱落地」→ `sinceProgressMs` 恒 < 3 s → `NEIGHBORHOOD_TIMEOUT_MS` 软超时永不触发。
（另注：该 30 s 窗口内**确有 904 个新柱**首次入队，故软超时即便不被重入污染也不会触发——
「持续交付就不降级」这条口径本身会把这些前沿柱压到硬上限。）

**两项已落地修复**：

1. `LightNeighborhoodGate.enqueue`：`lastColumnLandedMs` **只在某柱首次入队时**推进（`awaiting.compute` 的 `prev == null` 分支）。
2. `ShadowLightCompute.startLightBarrier`：入队前 `LightNeighborhoodGate.isAwaiting(key)` **短路**。
   理由：`enqueue` 是 REPLACE 语义，会换掉 `AwaitingEntry` 对象，而 `pumpGateReady` 的放行是
   **条件移除** `awaiting.remove(key, entry)` —— 条目被换过即移除失败、本次 promote 静默丢弃。
   等待中的条目自有 `pumpGateReady` 每帧扫描，无需重新入队。

**诊断增强（为闭合 30 s 成因）**：

3. `logBlockedOnce` 由「每条目只打一次」改为**每 5 s 复打**（`BLOCKED_LOG_INTERVAL_MS`）。
   原实现只能看到首次阻塞快照，看不到「等 30 s 期间邻域到底缺什么」。
4. Promote 两个分支都补打 `injectedNotInit={}`。**这是原诊断的缺陷**：`neighborhood ready`
   分支不打印它，而它是 `notInjected==0 && outsideWindow==0` 时**唯一**仍能阻塞的项 ——
   `(12,-19)` 的 Promote 行写着 "neighborhood ready" 却等了 30 s，正是被这个缺陷掩盖。

**未决（下一次飞行用 #3 判定）**：`(12,-19)` 首次入队 70754、8 邻最晚 70754 全部过 INITIALIZE，
却在 70784 才 promote。148 次重入**无法归因**到任何单一已记录调用点（注入=1、light task=1、
`pending authoritative` 首次登记在 70784），故必须靠每 5 s 的 Blocked 复打区分：
- 若复打显示 `neighbors=[........]`（全就绪）⟹ 阻塞在 `tryPromote` 条件移除（#2 应已修）；
- 若显示 `[...I...]` ⟹ 存在「已注入但 `lightInitPassed` 被清」的陈旧态，需查
  `isVanillaAlignedClientPackReady` 快路径（`ShadowLightCompute:1561` 分支不调
  `initializeLightImmediately`、也不 `lightInitPassed.add`，与 `reuseBarrier` 分支
  `:2253` 的既有修法同形，但**未验证**，且触及算光红线，暂不改）。

编译：`common:compileJava` / `fabric:compileJava` / `common:test` 均 BUILD SUCCESSFUL（`-Pmc_ver=1.20.1`）。**未跑飞行回归。**

### 8.7 影子读盘：永久自旋取证 + 三重护栏（2026-09-19 20:40）

**取证（客户端 PID 17092，runClient，已断开）**

| 观测 | 值 |
|------|-----|
| 连接 | `Get-NetTCPConnection -OwningProcess 17092` **无任何远程连接**（服务端 5476 也无 established） |
| CPU | 725 s wall / **20747 CPU-s = 28.6 核**，且仍在涨 |
| 三次 dump（139 s / 328 s / 725 s） | 32 个 `hassium-client-*` 虚拟线程，栈**逐帧相同** |
| 栈 | `HassiumTaskExecutor.lambda$submit$0:78` → `ShadowSeedServer.lambda$loadFromDiskAsync$14:1097` → `loadFromDisk:1077` → `parseNbtBytes:1445` → `ShadowServerCompat.parseChunkNbt:198` → `ChunkSerializer.read:125` → `PoiManager.checkConsistencyWithBlocks` → `SectionStorage.getOrLoad:86` → `SectionStorage.get:79` → `Long2ObjectOpenHashMap.get:324` |
| **间隔 5 s 两次 dump 的带栈线程身份集合** | **交集 32，无新增无消失** ⟹ **同一批线程卡死**，不是「读得多」 |
| 全 JVM 其他线程 | 218 个线程里**除这 32 个无任何线程在执行 hassium 代码** ⟹ 无提交者 |
| carrier | 32 个 `ForkJoinPool-3-worker-*` 各 0.55–0.73 核 = 28 核来源 |

**判定（已验证）**：不是「读盘风暴 / 单次 0.5 CPU-秒」的量问题，是**永久、不可中断的自旋**。
栈里**无任何阻塞调用** ⟹ `FutureTask.cancel(true)` / `shutdownNow()` 只置中断标志、打不断它
⟹ 这解释了「退出服务器后 CPU 不降」。**§8.5 把它记为「读盘风暴」是低估。**

**方案2 调查 = 负结果**（不落地）：
- `SectionStorage` 构造函数**无条件**建 `IOWorker`（`this.worker = new IOWorker(...)`）——
  **没有「无 worker」分支**，「让影子端 POI 走无 worker 路径」在 1.20.1 不存在。
- `SectionStorage.getOrLoad` **无循环**：命中缓存 → 返回；否则 `readColumn(pos)` →
  `tryRead(pos).join()`（**阻塞，不烧 CPU**）；再取不到则抛 `IllegalStateException`。
  ⟹ **按官方 mojmap 源码，不可能在其中持续烧 CPU** ⟹ dump 的帧/行号归属与工具取到的源码
  对不上（loom 重映射版 / JIT 内联）。**自旋条件未确认。**

**已落地：三重护栏**（`ShadowSeedServer.loadFromDiskAsync`，用户口径「超时直接当无缓存」）：
1. **并发上限** `max(2, min(8, 核数/4))` 信号量；`tryAcquire` 失败 → 立即按「无缓存」回调，不排队。
2. **硬超时** `DISK_READ_TIMEOUT_MS = 8_000`（正常读盘亚秒级，8 s 必属 §8.5 那条不返回路径）
   → 按「无缓存」回调，结果丢弃。
3. **恰一次回调**：`AtomicBoolean settled` 仲裁超时与真实完成的竞争。

**关键设计**：许可语义 = 「**读盘线程正在跑**」，读盘段结束（成败都算）才归还；
**卡死时不归还** ⟹ 自旋受害面上限被钉在 N 个核。若改成「超时即归还」，32 个线程照样烧，
超时只是让调用方往前走 —— 这点是本方案的要害。

三个分支都收敛到「当作无缓存」⟹ 调用方 `scheduleAsyncDiskPublish` 无需区分，
`null` 走既有 `onDiskPublishMiss` → `DISK_READ_EMPTY`（本圈不再重读）。

编译：`common:compileJava` / `fabric:compileJava` / `common:test` 均 BUILD SUCCESSFUL（`-Pmc_ver=1.20.1`）。**未跑飞行回归。**

**未解决**：自旋本身。护栏把它从「28 核 + 会话报废」压到「≤N 核 + 影子缓存退化为无缓存」，
但**不返回的读盘线程仍在**（只是数量有上限）。

**下一步（已建任务）**：定点打点取证 —— 在 `loadFromDisk` 进入/返回处计数，跑一次复现：
- 进入数 ≈ 返回数 且栈恒定 ⟹ **真自旋**（进去不出来）
- 进入数 >> 返回数 且栈漂移 ⟹ **高频调用**（量的问题）

一刀切开两种可能后，方案1（Mixin `PoiManager.checkConsistencyWithBlocks` 影子上下文 no-op）
才知道打不打得中真凶。**不要在没有这一步的情况下直接上方案1。**

### 8.8 读盘自旋：定性完成 + 定点打点（2026-09-19 21:10）

**用户线索**：**自驱动算法之前从未出现此现象** ⟹ 新的是**调用方**，不是 vanilla 读盘路径本身。

**定性（已验证，决定性）**：`ExecutorFactory.createVirtual` 用
`Thread.ofVirtual().name("hassium-client-", 0L).factory()`——**单调计数器，线程名唯一**，同名即同一线程。

| dump | 时刻 | 带栈 `hassium-client-*` |
|------|------|------------------------|
| T1 | 139 s | 32 |
| T3 | ~700 s（**已断开**） | 32 |
| T4 | ~705 s | 32 |

**`T1 ∩ T4 = 32`**（`T1−T4 = ∅`，`T4−T1 = ∅`）——相隔 **9.5 分钟**，同一批 32 个线程、同一个栈。

⟹ **「进去不出来」确认：真自旋，不是高频调用。** §8.5 的「读盘风暴 / 单次 0.5 CPU-秒」
是**误判**（那是「量大」的模型）；实际是这 32 个线程**永不返回**。

**为什么退出服务器也停不下（已验证）**：栈里**无任何阻塞调用**（纯 CPU 循环）⟹
`FutureTask.cancel(true)` / `shutdownNow()` 只置中断标志，打不断 vanilla 代码。
且客户端已无任何远程 TCP 连接（`Get-NetTCPConnection -OwningProcess 17092` 为空）。

**已落地：定点打点**（`ShadowSeedServer`）：
- `DISK_READ_INFLIGHT`（key → 进入时刻 + **柱坐标** + 维度）+ `DISK_READ_ENTERED` / `DISK_READ_RETURNED`
- 看门狗每 5 s 跑一次，**只在「在途最老 ≥ 3 s」时出声**（正常态静默，不制造新日志风暴）：
  `[SHADOW_DISK] stuck reads inflight=N oldest=Xms entered=E returned=R slotsFree=S | [(x,z) Xms dim, ...]`
- 判据：`entered ≈ returned` 且本表恒非空 ⟹ 自旋；`entered >> returned` ⟹ 高频调用
- **最有价值的产出是柱坐标**——那是能拿去复现 / 查盘上 NBT 的东西

**下一步**：跑一次复现，读 `[SHADOW_DISK] stuck reads` 拿到卡住的柱坐标，然后
（a）看那根柱在盘上的 NBT 是不是被自驱动算法写坏 / 写成了畸形结构；（b）对着坐标在
`loadFromDisk` 里加更细的分段打点（readChunk / NbtIo.read / parseChunkNbt / toLevelChunk）。
**在拿到坐标之前不要上方案1（Mixin no-op）** —— 自旋点在 `PoiManager` 只是**表象**，
真凶大概率是那根柱的数据。

编译：`common:compileJava` / `fabric:compileJava` / `common:test` 均 BUILD SUCCESSFUL（`-Pmc_ver=1.20.1`）。

### 8.9 迟落地柱的真因：`lightInitPassed` 被卸载摘掉后不再补标（2026-09-19 21:40）

**用户探针**：20:29 run，三根「迟迟没落地」柱 `(-58,-2)` / `(-41,-22)` / `(-47,-44)`。

**打点结果**：`[SHADOW_DISK] stuck reads` **0 条** ⟹ 读盘侧干净，§8.5/§8.8 那条自旋本次未参与。

**§8.6 加的「每 5s 复打 Blocked」直接抓到真凶**（这正是当初加它的目的）：

```
(-58,-2)  74044 Enqueue/Blocked [.MX.XI..] injNotInit=1 notInjected=3
          74049 waited=5006ms  [.....I..] injectedNotInit=1 notInjected=0
          74054 waited=10009ms [.....I..] injectedNotInit=1 notInjected=0
          74059 waited=15012ms [.....I..] injectedNotInit=1 notInjected=0
          74064 waited=20061ms [.....I..] injectedNotInit=1 notInjected=0
          74067 Promote waited=22210ms (timedOut=true sinceProgress=3003ms)
```
8 邻里 7 个就绪，**恒定只有 SW 邻柱 `(-59,-1)` 是 'I'**；而 `(-59,-1)` 的
`INITIALIZE_LIGHT done` 是 **74045**（首次 Blocked 后 1 秒）。`(-41,-22)` 同型，卡 26.7s。

**机制闭环（`(-40,-21)` = `(-41,-22)` 的 SE 邻柱，已验证）**：

```
73903 enqueue pull / INITIALIZE_LIGHT done (-40,-21)   ← lightInitPassed.add
73903 Cancelled work before unload (-40,-21)           ← ★ cancelChunkWork 摘掉标记
73903 Client unload invalidated (-40,-21) epoch=4182
73904 LIGHT_CALL pos=(-40,-21) lit=false ×2            ← 仍在注入表里（injected != null）
73904..73929 邻柱 (-41,-22) 的 Blocked 恒为 'I'（卡 26.7s）
74034 enqueue pull / LIGHT_CALL pos=(-40,-21) lit=true
```

**链条**：邻柱 N 注入 → INITIALIZE → `lightInitPassed.add(N)` → **N 被卸载
（`cancelChunkWork` 同时摘 `lightInitialized` + `lightInitPassed`）** → N 重新注入时
**没有把标记补回来** → N 对所有邻柱恒为 'I' → 邻柱齐套门只能等软超时降级放行。

**为什么「自驱动算法之前没有」**：自驱动引入的反复重投 + 卸载重入，放大了这个窗口。

**全局量化**：`Blocked` 7522 行 / 6794 柱；其中「纯等待型卡门」（`injectedNotInit>0 且
notInjected==0`）536 行；**waited ≥ 5s 的 129 柱**，最长 **25.2s**（撞软超时）。
最长 12 根：`(-76,-39) 25099ms`、`(-76,-40) 25106`、`(-40,-23) 25106`、`(-73,-40) 25108`、
`(-79,-41) 25114`、`(-79,-43) 25116`、`(-41,-22) 25118`、`(-59,-7) 25120`、`(-74,-11) 25121`、
`(-40,-24) 25121`、`(-81,-45) 25132`、`(-78,-40) 25194`。

**★ 查出一个我自己引入的回归（§8.6 修复 #2）**：
`startLightBarrier` 的 `isAwaiting` 短路被放在 `initializeLightImmediately` **之前**。
而 `initializeLightImmediately` 正是幂等的「确保 phase-1 INITIALIZE 跑过 + 补标
`lightInitPassed`」的副作用源；卸载会同时摘掉 `lightInitialized` + `lightInitPassed`，
此后重投必须靠它重跑重标。被短路挡在前面 ⟹ 该柱对**所有邻柱**恒为 'I'。
**本次 run 的 `(-40,-21)` 正是这个形态。**

**三处修复**：

1. **回归修复**：`startLightBarrier` 把 `initializeLightImmediately` 移回 `isAwaiting`
   短路**之前**（顺序钉死，注释写明理由）。
2. **标记点归位**：`startLightBarrier` 屏障分支的 `lightInitPassed.add` 去掉
   `if (reuseBarrier)` 条件，改为**无条件**——该分支确实跑了 `initializeNativeLight`
   （INITIALIZE），标记点就该在真正跑完 INITIALIZE 的地方，不依赖调用方是否代标。
   （原实现的 `if (reuseBarrier)` 只覆盖 REUSE_CACHE，RECOMPUTE 靠门控分支代标 → 正是被短路
   打掉的那条。）
3. **快路径补标**：`enqueueInjectedForLight` 的 `isVanillaAlignedClientPackReady` 分支 +
   `submitLightNoNeighborhoodGate` 的 `lightReuse` 分支补 `lightInitPassed.add(key)`
   （这两条路径**不跑** `initializeLightImmediately`，故必须自行补标）。

**语义依据**：`lightInitPassed` 的用途 = 「该柱有可写入的层、邻柱可传播」。
`isLightCorrect()` 为真 ⟹ 引擎层是齐的 ⟹ 标记属实，不是撒谎。

编译：`common:compileJava` / `fabric:compileJava` / `common:test` 均 BUILD SUCCESSFUL（`-Pmc_ver=1.20.1`）。**未跑飞行回归。**

**另立待办**：`(-47,-44)` 是**另一类**问题——齐套门 `waited=2ms` 立即放行，但反复
`pack after light task` + `skip beyond view+margin`（交付门挡下）+ `pending authoritative`，
applied 3 次、被 unload 2 次。属交付门/重投，与齐套门无关，不混在本修复里。

### 8.11 `(-47,-44)` 三问结案：21 s 是等窗、重投是边界竞态、pending 不是 churn（2026-09-19 夜）

> 编号说明：本节与下方 §8.10（另一会话并发追加的「§8.9 回归验证 + 影子读盘两项优化」）落笔时都占用了
> §8.10；本节改写为 §8.11，物理位置仍在 §8.10 之前（不搬动对方内容）。

**取证对象**：`fabric/run/client/logs/latest - 副本.log`（43 MB / 20:29:47–20:34:41，即 §8.9 那次 20:29 run）。

⚠ **口径修正**：任务书给的时间戳↔内部秒映射（`73927 = 20:32:07`）与本日志对不上（按该 run 起点 20:29:47
反算应约 `73727`，差 3 分 20 秒）。本文一律用**日志内墙钟**；被引用的事件行与任务书摘录逐字一致，
仅「内部秒」这一列不可用。另外任务书摘要漏了 20:33:15 那次 unload，本文补全。

**判据（已核 vanilla 源码）**：1.20.1 mojmap `ChunkMap.isChunkInRange`（`ChunkMap.java:220-228`）：
`i=max(0,|dx|-1); j=max(0,|dz|-1); k=max(0,max(i,j)-1); l=min(i,j); l²+k² < range²`。
Hassium `ChunkShapeCompat.contains`（`ChunkShapeCompat.java:22-30`，`#if MC_VER < MC_1_21_1` 分支）**逐字**调它，
`range = serverViewDistance = 20`（本 run `[SHADOW_TRACK] authority-acquire … vd=20`）。

**Q1 —— 73927→73948 的 21 s 是「交付窗外正常等待」，不是 bug。【已验证】**

| 事件 | 时刻 | 玩家柱 | i,j,k,l | m | 形状内 |
|---|---|---|---|---|---|
| `skip beyond view+margin` | 20:32:07 | (-41,-22) | 5,21,20,5 | 425 | ✗ |
| 首次 `shadow_applied` | 20:32:28 | (-40,-23) | 6,20,19,6 | 397 | ✓ |

玩家在 20:32:07→20:32:28 之间 z 柱从 -22 走到 -23（`playerBlock` -338→-357），跨过边界后判定翻真 → 当场打包投递。
**21 s 不是「算完不投」，是「算完时玩家还在窗外，等玩家进窗」**；而且交付比玩家接近**更早**：
任务书口径的「玩家首次进入 8 格视野」= 20:32:38，此时该柱早已在客户端（早约 10 s）。
判定链：`ShadowLightCompute.pushReady:2634` → `ShadowTrackingSession.isDeliverableToClient:1078`
→ `inVanillaVisibleShape:1057` → `ChunkShapeCompat.contains:22` → `ChunkMap.isChunkInRange`。
`skip beyond view+margin` 有两个打点（`ShadowChunkDeliver.deliverLocal:39`、`ShadowLightCompute.pushReady:2637`）；
本 run 本柱两条都出自 **`pushReady`**（同柱前一行即 `pack after light task`，属 `finishLight` 链）。

**Q2 —— 卸载后仍 pack/登记/apply = 边界擦边柱的判定抖动 + 设计内重投，不是 ready 残留。【已验证】**

三次 unload 全部**合法**（真服 Forget），三次 apply 也全部**合法**（投递时刻确实在形状内）：

| 事件 | 时刻 | 玩家柱 | m | 形状内 |
|---|---|---|---|---|
| apply #1 | 20:32:28 | (-40,-23) | 397 | ✓ |
| unload #1 | 20:33:15 | (-64,-59) | 421 | ✗ |
| apply #2 | 20:33:47 | (-68,-37) | 397 | ✓ |
| **unload #2** | 20:33:48 | (-68,-36) | **410** | ✗ |
| **apply #3** | 20:33:48 | (-67,-36) | **373** | ✓ |
| unload #3 | 20:34:06 | (-51,-22) | 409 | ✗ |

unload #2 → apply #3 相隔 **840 ms**，玩家只动了 **1 格**（x 柱 -68→-67），m 由 410 跳到 373、跨过 400 阈值。
**该柱是「擦边柱」：m 全程落在 373~425，1 格中心位移即翻转判定。**

机制（`file:line`）：

- `ClientLevel.unload` 的唯一可达路径 = `ClientChunkCache.drop` / `Storage.replace`
  （vanilla 1.20.1 `ClientChunkCache.java:55-61` / `:188-208`）；`drop` 只在槽位命中同柱时卸
  ⟹ 真服 Forget 一定卸到「该柱自己」，不会张冠李戴。
- unload #2 是**同毫秒 43 柱的批**（`eventMs=1789821228122/123`，全部 `playerChunk=(-68,-36)`）。
  逐柱验算这 43 柱在中心 (-68,-36) 下**全部 m ≥ 400**（抽验：`(-89,-44)=410`、`(-75,-58)=436`、
  `(-70,-58)=401`、`(-69,-58)=400`、`(-68,-58)=400`、`(-60,-57)=410`、`(-53,-53)=421`、
  `(-49,-48)=410`、`(-48,-46)=405`、`(-85,-51)=421`）⟹ 这就是「玩家走 1 格后真服形状的 trailing edge」，
  vanilla 正常行为，不是泄漏、也不是 ring 槽位回收。
- Hassium 侧：`MixinClientLevel:19-33` → `ShadowLightCompute.onClientChunkUnloaded:2999-3030`
  （`shadowApplyEpochs.remove` + `cancelChunkWork`）→ 落地凭据被清 → 下一轮
  `ShadowTrackingSession.drainAuthorityAcquires:545-550`（`!hasClientApplyEpoch && isDeliverableToClient`
  → `ShadowChunkDeliver.deliverLocal`）重投。**这是设计内语义**（`MixinClientLevel:28` 注释、
  `onClientChunkUnloaded` javadoc「只作废落地凭据（`shadowApplyEpochs`）」）。
- **`ready` 队列确实不在清除清单里**（`cancelChunkWork:2580-2595` 与 `onClientChunkUnloaded` 都不碰 `ready`）。
  但本例的 pack 发生在 unload **之后**（`LIGHT_CALL` / `pack after light task` 的行号在 unload 行之后）
  ⟹ 本例**不是** ready 残留；ready 残留是同类但独立的第二个风险面，本 run 未观测到它单独致投。
- `ignoredApplyRetries` / `shadowApplyEpoch` **都不会拦**：前者只在 `hasClientChunk==false` 分支用
  （`applyReadyChunk:2974-2986`），后者是记账凭据、且被 unload 主动清除（它正是重投的开关）。
  `applyReadyChunk:2923-2988` 全程无「该柱是否刚被卸载」检查。
- `completeLight:2298-2301`（`inflightLight.remove(key, inf)` 失败即短路丢弃）证明
  「被 `cancelChunkWork` 取消的在途光任务**不会** pack」⟹ 本例 pack 来自 unload **之后新提交**的光任务，
  不是被取消任务的迟到回调。

**全局量化（同一 run）**：`shadow_applied` **16 864 行 / 7 678 去重柱 = 2.20×**（origin 15 154 shadow_memory_cache、
1 549 remote_pull、147 local_generation、14 shadow_disk_cache）；`CHUNK_UNLOAD` 9 977 行只落在
**520 个毫秒**里，且大量 **42–43 柱/批**（最大 51）——43 柱/批正是「1 格位移的 trailing edge」。
与 §8.6 的 57% 重复率同量级，说明重投是**系统性**的，不是本柱特有。

**Q3 —— 不是 churn。【已验证】**

`pending authoritative` 全 run **8 650 行 / 6 222 去重柱 = 1.39×**，单柱最多 9 次。
`registerPendingAuthoritative:2410` 只在 `put` 返回 null 时打点 ⟹ 重复行 = 中间被摘过
（摘除点：`recheckPendingAuthoritative:2447` 柱已卸载、`:2462` 触发 re-light、`finishLight:2386` 转权威）。
本柱 3 次登记 = 被卸载 2 次后重新登记，与 Q2 同源。
`pending=N` 是**表大小**（344→600→604），随玩家推进单调增长属正常（去重柱 6 222 覆盖全 run）。
对照 §8.6：`[LIGHT_GATE] Enqueue` 22 498/4 736 = **4.75×**；本次 1.39× 不构成自激。
进展门未被绕过：本柱**没有**对应的 `re-light after neighborhood ready` 行。

**结论**

1. **Q1 不是 bug**。交付门这条链上无「迟」的来源——该柱落地比玩家接近早 10~16 s。
   「用户观感上的迟」若确实存在，须另立证据（判据：玩家已在 8 格内而该柱仍空）。
2. **Q2 是结构性竞态，不是写错某个门**：真服按**它自己 tick 时**的玩家柱算形状，Hassium 按
   客户端**当前**渲染帧的玩家柱算（`deliveryCenter:368-388` 读 `mc.player`）——两者几何同源、中心不同源，
   在 `|d| = serverVD + 1` 的擦边柱（m ≈ 400）上差 1 格就翻转 ⟹ 交付 → Forget → 重投 → ……
3. **Q3 正常**，不需要动。

**若裁决要修（最小方案，**先报告后动手**；本节未改任何代码）**——候选按风险升序：

- **(a) 收紧交付域 1 格**：`isDeliverableToClient` 改用 `contains(center, serverVD - 1)`，让影子端只交付
  真服**必定**仍在 track 的柱，最外圈交回真服自己的 vanilla 通道。**前提必须先确认**：`chunk.lightStrip=true`
  时真服是否仍会自己发最外圈整柱——若真服侧区块窗口被压制，收紧会在最外圈开洞
  （那 1 圈虽在 `LevelRenderer` 的 `lastViewDistance-3` 之外不渲染，但会留未落地柱）。
- **(b) unload 后重投防抖**：`onClientChunkUnloaded` 不立即清 `shadowApplyEpoch`，改为「待确认」+ N ms 后
  仍窗内才清。不改几何；副作用是真「客户端丢柱」的恢复慢 N ms。属**门控式**修法，与本项目
  「修源头状态、不门控拦异常样本」的既定口径冲突。
- **(c) 在 `applyReadyChunk` 丢弃「刚 unload 且在边界」的投递**——**不推荐**（叠甲拦异常样本）。

**未做**：任何代码改动、编译、冒烟或飞行回归；本节全部结论来自只读取证。
**未决**：(a) 的前置确认（真服是否仍自发最外圈）需要一次带 `[SHADOW_CHUNK] drop authoritative chunk …
outside vanilla view range` / B 族整柱包计数的 run 才能裁决。

### 8.10 §8.9 回归验证（通过）+ 影子读盘两项优化（2026-09-19 21:30）

**回归验证**（用户重跑并飞了很久；日志 `fabric/run/client/logs/latest.log`，跨度 361 s）：

| 指标 | 20:29 run（修复前） | 本次 run（修复后） |
|------|---------------------|-------------------|
| **纯等待型卡门 `waited≥5s`** | **129 柱**（最长 25.2 s） | **4 柱**（最长 **10.0 s**） |
| `Enqueue` 每柱重入 p50 / p90 / **max** | 3 / 8 / **151** | 1 / 2 / **5** |
| 重入 ≥50 次的柱数 | 33 | **0** |
| Promote 降级率 | 53/8630 = 0.61% | 111/20355 = **0.55%** |
| `[SHADOW_DISK] stuck reads` | 0 | 0（全日志无该字样） |

残余 4 柱：`(6,-77) 5007ms`、`(94,-83) 5020ms`、`(35,-39) 10005ms`、`(29,-106) 10024ms`。
⟹ **§8.9 的三处修复生效**，用户观感（「再也没见到迟迟不落地」）与日志一致。

**读盘优化（用户口径）**

1. **同槽单飞**（`ShadowStorageManager.readChunk`）：新增
   `ConcurrentHashMap<Long, CompletableFuture<byte[]>> inFlightReads`，键 = `ChunkPos.asLong`。
   同槽并发读只解压一次，后到者 `join()` 共享结果；owner 完成即摘登记（**不是缓存**，不改变
   「每次都从盘读」语义）。必要性：`readChunk` 有多个调用方（consumeLoop 磁盘优先 /
   processRemoteHashes 比对 / scheduleAsyncDiskPublish），而 `DISK_PUBLISH_INFLIGHT` 只挡了最后一条；
   `Image` 方法虽 `synchronized`（按 region 串行），重复解压仍白烧 CPU。
   `readChunk` 原体拆为 `readChunkUnshared`（行为不变）。

2. **读到后复检丢弃**（`ShadowLightCompute.scheduleAsyncDiskPublish` 回调）：读到结果后、
   `injectLoadedChunk` 之前，复检 `server.injectedChunk(dimension, pos) != null`；若已被注入
   ⟹ **丢弃本次盘上结果**（不 inject、不记 `DISK_READ_EMPTY`、不算 miss）。
   理由：读盘期间该柱可能已被别的路径注入，此时**内存里那份才是最新**，而盘上是**尚未 flush 的
   脏槽旧副本**，注入会把它覆盖回去。日志 `[SHADOW_LIGHT] disk read discarded, column already in
   shadow memory`。

**「读前检查影子端是否已加载」——已在每个生产调用点，无需再加**（附证据）：
- `ShadowChunkMapCompat:184`（`injectedChunk != null` → 直接返回）
- `ShadowTrackingSession:843`（同判据，OVD 路径）
- `ShadowLightCompute:1659`（`accountLightAtScheduleLoad`）
- `ShadowLightCompute:953`（`publishCachedChunk`：`chunk == null` 才进读盘分支）

**因此我最初在 `readChunk` 里加的那道读前短路已撤回**，两个理由（都是实测）：
- `ShadowStorageManagerTest:566` 明确断言「柱在 injected 集合里时 `readChunk` 仍能读到」
  （flush 退化路径不得丢柱）——加它会挂测试；
- 导出路径 `ShadowColumnStore.load` 没做该检查，加它会让已注入柱读不到 ⟹ 导出丢柱。

⟹ 真正的缺口是 **TOCTOU**（检查通过后、读盘完成前该柱被注入），由上面第 2 项关掉。
**未处理**：`ShadowColumnStore.load` 是唯一没做注入检查的 `loadFromDisk` 调用方——
若导出场景出现「读到脏槽」，从这里查（本次未改，未验证）。

编译：`common:compileJava` / `fabric:compileJava` / `common:test`（332 tests）均 BUILD SUCCESSFUL（`-Pmc_ver=1.20.1`）。**读盘两项未跑运行时验证。**
