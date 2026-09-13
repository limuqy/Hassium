# 交接文档：权威边沿（authority edge）现状 + P5「裁掉影子端自绘选柱」判定 + 落位点空洞竞态

日期：2026-09-13 ｜ 分支：`master` ｜ HEAD：见 `git log -1`（**工作区干净**；文档所述一切改动均已提交）
本文档读者：下一个接手会话的 AI / 开发者。
配套阅读：[`client-chunk-flow-handover.md`](client-chunk-flow-handover.md) §9.5–§9.9（本次会话的原始推演与三次自我推翻）、
[`chunk-cache.md`](chunk-cache.md) §14、[`runtime-smoke-test.md`](runtime-smoke-test.md) 门禁全集。

---

## 一、一句话状态

**权威边沿在「内容裁决」层可用并默认开启；「选柱 / 装载」层的影子端自绘 pull 尚未在全部加载器上退场
（1.20.1/fabric 5 次、1.21.1/forge 4 次、1.21.1/neoforge 3 次饥饿）——替换条件未达成，见 §9.7。**
另：第三轮会话发现并修复了一个**既有 loader 级缺陷 F12**（neoforge 上整柱抑制与权威边沿全程未生效，根因 = NeoForge 把整柱包封进 `ClientboundBundlePacket`）。
`P5_TAKEOVER = false`（收尾态不变——第四轮补齐矩阵证据后**确认接管臂不能开**：classic 在三个 loader 上全绿，
但 `dimension` 与 `seedgen` 两个场景在接管态不成立；见 §五 F5）。
第一轮会话新增：**封闭空洞冒烟门禁**与**让位门静默丢数据的兜底**；
第二轮会话关闭 **F10**（dimension 纳入 P0 空洞门禁 + 超时默认值）；
第三轮会话关闭 **F1 / F2**（落位点 3x3 空洞根因 + 让位门契约）、**F12**（neoforge bundle 未解包）、
**F15**（声明被丢 → 快照重推自愈），**并修正了 §3.1/§3.2 的机理归因** —— 见 §九。
第四轮（收尾）关闭 **F11**（逐柱 pull → 按声明包聚合）、**F14**（移动会话门禁口径）、**F8**（定性结案）、
**F9**（§9 章首加速览）、**F4 余项**（`seedgen` 干净 / `modcompat` 暂不纳入）、**F5**（接管矩阵：仍不能开）、
**F7**（未复现，降为观察项），**并抓到本轮自己引入的 P0 —— F16**（F11 聚合把 C2S 载荷顶过 32 KiB，
1.20.1 客户端被踢；已修复 + 复测）；
`1.21.1_fabric_I_f11move` 是**首个 PASS 的移动会话**。

---

## 二、已落地（全部有实测证据）

### 2.1 封闭空洞门禁（`TRACE_ENCLOSED_HOLE`）

- 口径：`scripts/smoke/analyzer.py` → `_enclosed_holes` / `_enclosed_components` / `_hole_check`。
  取 `clientCache.actualPresent` 的包围盒外扩一圈，从盒外角 4-连通洪水填充；**填不到的缺席格 = 被围住的洞**，
  再算 4-连通分块大小。
- 分级（**仅 classic**）：最大分块 **≥4 格 → P0** `TRACE_ENCLOSED_HOLE`；1–3 格 → P1 `TRACE_ENCLOSED_HOLE_SMALL`。
  非 classic 场景按场景排除（`dimension` / `modcompat` / `seedgen` 的盘回填不走同一交付契约；
  `ovdgen` 已在 `8bee742` 随 `chunk.ovdLocalGeneration` 一起退役，不再是活场景）。
  **注意**：该排除理由已被 §五 F3 部分推翻——`dimension` 的 303 格空洞是**真缺陷**而非契约差异，
  见 F3 归因结论。
- **为什么必须有它**：原 `gaps.expectedNotPresent = expected − actual`，而 `expected` 的候选集就是
  `networkReceived`——**从未被投递的柱不在候选集里，结构上永远看不见**。`_spatial_check` 又只做一层邻域判断，
  对一个实心 3x3 空洞只能报出四只角，且只是 P1。于是真实的落位点 3x3 虚空以 `=== RESULT: PASS ===` 收场。
- 校准：`load_and_analyze` 回放**全部 164 个历史 result JSON** → 新增 P0 命中 **8 个会话 / 11 轮样本**
  （最大分块恒为 9），新增 P1 命中 **1 个会话**（`1.20.1_fabric_I_move2`）；其余零影响，**无非 classic 场景误报**
  （详见 §五 F3 / F4）。
- 单测：`scripts/smoke/test_analyzer.py::EnclosedHoleTest`（5 个新用例，全量 17 个通过）。

### 2.2 让位门「让路不让弃」（`ShadowTrackingSession.emitPullGroups`）

- 新增 `GATE_STARVE_GRACE_MS = 3_000L` 与等待表 `gateWaitingSinceMs`：
  被扣柱登记首扣时刻；**扣留中**清掉它的 `sweepInFlight` 在途标记（否则形状扫描把它当"已发出"，
  60s 内不再重新发现，宽限永远攒不满）；超宽限 **无条件补发一次 pull**。
- 补发时**撤销等待登记、保留在途标记**——避免同一拍里 `drainBootGrid` / `drainSelections` / `sweepVisibleShape`
  三个调用方对同一柱重复补发。
- 等待表**按年龄裁剪**（`2 × 宽限`）：不能按"是否出现在本拍"裁剪——同一拍里 drain 与 sweep 是两个
  互不可见的批次，按成员裁会互相抹掉计时。**这一条是在跑冒烟之前抓到的**，否则整场验证会是个假阴性。
- 对照：`ChunkAuthorityClient` 既有的 10s 断流看门狗同一设计哲学——不能因为等一个可能永远不来的声明，
  把兜底也一起关掉。

### 2.3 两次冒烟（1.21.1 / fabric / classic / Phase I）

| 会话 | `P5_TAKEOVER` | R1 observed | R2 observed | 封闭空洞 | `hash-hit` / `authoritative-full pull` | 判决 |
|---|---|---|---|---|---|---|
| `_p5fix1` | **true** | **1529**（完整盘） | **453** | **0 / 0** | 49 / **15** | `PASS` failures 0 warnings 0 |
| `_p5off2` | false（生产态） | 1636 | 453 | **0 / 0** | 37 / **13** | `PASS` failures 0 warnings 0 |
| 修复前的同场景会话（`_band2` / `_ot1`，`P5_TAKEOVER=true`） | true | 1520（缺 9） | 444（缺 9） | **9 / 9** | 443 / **0** | 曾 `PASS` |
| `_band1`（同为修复前，但**配置基线等价**） | false | 1520（缺 9） | 444（缺 9） | **9 / 9** | 442 / **0** | 曾 `PASS` |

- `_p5fix1` 确认驱动真的在跑（§9.8 的教训）：`[SHADOW_TICKET] bound instance` ＋
  `reconcile … center=(-3, 0) band=10..16 +64 -0 live=64 desired=636`。
- `_p5off2` 是驱动真没跑的哨兵：`[SHADOW_TICKET]` **0 行**（无条件首绑行不出现）。
- `_p5fix1` 连原先的 `SPATIAL_SNAPSHOT_INCOMPLETE`（那四只角）也消失 —— 独立旁证。

### 2.4 其它已验证

- `common:test` 通过；`scanVersionBoundaries: OK`；`common:compileJava` 在 **1.20.1 与 1.21.1** 均通过。
- `python -m unittest scripts.smoke.test_analyzer` → 17 passed。

---

## 三、最重要的结论（读这一节就够）

### 3.1 落位点空洞是**让位门的竞态**，不是 P5 接管的专属缺陷

| 会话 | `[SHADOW_TICKET]` | `hash-hit` | `authoritative-full pull` | 封闭空洞 |
|---|---|---|---|---|
| `_band1`（**配置基线等价**：`ENABLED=false` 且 tracking 未钝化） | **0** | 442 | **0** | **9** |
| `_band2` / `_ot1` | 3 / 有 | 443 / 442 | **0 / 0** | **9** |
| `_bandmove1` / `_nticketmove1` / `_otmove1` / `_otmove4` / `_bandmove2` | — | 46/45/46/17/23 | 0/0/1/0/0 | **9** |
| `1.21.1_fabric_I` / `_nticket1` / `_refactor` | 0 | 0/60/0 | 13/**1**/147 | **0** |
| `_p5fix1` / `_p5off2`（修复后） | 有 / 0 | 49 / 37 | 15 / 13 | **0** |

判据是「**基准盘铺盘那一刻让位门是否已经关上**」：有洞的会话 `authoritative-full pull` 全是 0
（这两轮里影子端**一条 pull 都没发出去**），没洞的会话是 1~147（影子端的 pull 确实发出去了）。

**机理**：服务端声明集合覆盖的是"服务端本来会推送的那批"，**不含登录期就已推送、此后不再声明的柱**
（实测 `_band2` 该轮 443 条声明恰好不含落位点那 9 格）。那 9 格本地无盘、未注入 → 唯一来源是影子端自绘 pull。
让位门一关，`emitPullGroups` 直接 `return`，而 `drainBootGrid` 的 `pollFirst` 已经把它们**不可逆地取走**了 →
永久空洞。所以门开合的**时机**决定装载主力是"声明"还是"影子端自绘 pull"——同一个二进制、同一场景、
同一份存档，两次跑法机理就不同（`_p5fix1` 整盘 1529 柱都走了一次超宽限补发）。

> **⚠ 机理已修正（2026-09-13 第三轮会话，见 §九）**：上段「不含登录期已推柱」的归因**不准确**。
> 实测声明集合是**全的**（两轮合计 1964 条，逐条带 hash）；缺失的是「**声明已入队、却被服务端自己的 epoch 记账抹掉**」
> ——`ChunkAuthorityNotifier.applyViewDistanceIfChanged` 在会话首个视距观测点（`-1 → 20`）执行 `pending.clear()`，
> 恰好抹掉原版**首批 9 柱**的声明（`PlayerChunkSender.START_CHUNKS_PER_TICK = 9.0F`，按 `distanceSquared` 取最近 9 格
> = 落位柱为中心的 3x3）。另：上段「443 条声明」是**误读**——443 是客户端 `hash-hit` 条数，不是声明条数。

**因此**：`band1` 这条记录（§9.8 当初被标注为"读数作废"的配置）现在有了新含义——**它的配置就是生产配置**，
它照样丢了 9 柱。**这个竞态在生产配置下同样成立**，本次让位门改动是对生产缺陷的修复，不只是为实验服务。

### 3.2 「影子端选柱可以裁掉」这个 P5 前提**不成立**

把空洞补上之后，填洞的仍然是影子端自绘的 pull（让位门补发），**而不是服务端声明**。
要裁掉影子端自绘选柱，前置条件是先把声明集合补全（见 §五 F1），而不是在客户端做减法。

> **✅ 该结论方向已被 F1+F2 翻案，但范围有限（第三轮，见 §9.7）**：**在 1.21.1/fabric 上**影子端自绘 pull 确实退场了——
> 实测生产态 ×2 + 接管态 ×2：`authoritative-full pull` = 0/0/0/1、`compare-pull` = 0、`starved` = 0、封闭空洞 = 0。
> 但 1.20.1/fabric（16 批 / 5 次饥饿）、1.21.1/forge（4 批 / 4 次饥饿）与 neoforge（修复后 4 批 / 3 次饥饿）**尚未退场**。
> 原判「不可删」的前提确实正是 F1 那个缺失的声明；但要真删，仍需先让声明流在全部版本/加载器上覆盖完备。

### 3.3 接管保持关闭，但**理由不是空洞**

`P5_TAKEOVER` 保持 `false`，其自身理由是 §9.8 已记录的：移动场景 OVD 补票速度不足
（`bandmove2` 28% 未命中；整方形驱动 `otmove4` R2 spatial 9/147）、收尾 `saveAll` 停滞后强退
（`0xC0000409`）、大票突发下的运行期原生终止（`0xCFFFFFFF`，512/拍触发、收到 64/拍消失）。
加上**矩阵证据缺口**（只有 1.21.1 fabric 的 classic + move 跑过接管）。

> **别再把空洞和接管绑在一起**。本会话第一版文档写了"与 P5 接管强相关"，那是把
> 「门开合时机」误当成了「接管与否」——已按 §3.1 表格作废修正。

---

## 四、树状态

```
P5_TAKEOVER = false                  common/.../seedgen/ShadowTicketDriver.java
GATE_STARVE_GRACE_MS = 3_000L        common/.../seedgen/ShadowTrackingSession.java
TRACE_ENCLOSED_HOLE (+_SMALL, 仅 classic)   scripts/smoke/analyzer.py
```

**新增文件（8，未跟踪）**
- `common/.../network/ChunkAuthorityClient.java`（客户端三分支解析 + 让位门）
- `common/.../network/ChunkAuthorityHashes.java`（共享 per-chunk hash 缓存，带 `hasEntries()` 短路）
- `common/.../network/ChunkAuthorityNotifier.java`、`ChunkAuthorityS2CPacket.java`（服务端声明）
- `common/.../mixin/MixinLevelChunk.java`（`setBlockState` 后失效 chunkHash，按 `MC_1_21_5` 分叉描述符）
- `common/.../seedgen/ShadowTicketDriver.java`（**接管臂，当前一行不执行**）
- 两个单测：`ChunkAuthorityS2CPacketTest`、`ShadowTrackingReclaimTest`

**本会话改动**：`ShadowTrackingSession.java`（让位门兜底）、`scripts/smoke/analyzer.py`、
`scripts/smoke/test_analyzer.py`、`docs/{client-chunk-flow-handover,chunk-cache,runtime-smoke-test}.md`。

**接管臂的调用点（只有两处，摘掉很容易）**
- `ShadowTrackingSession.consumeOnShadowLoop(...)` 内的 `ShadowTicketDriver.consumeOnShadowLoop(...)`
- `ShadowTrackingSession.applyViewDistanceIfChanged(...)` 内的
  `if (ShadowTicketDriver.trackingSelectionNeutralized()) desired = NEUTRALIZED_VIEW_DISTANCE;`

---

## 五、后续事项

按建议优先级排序。每条给：**目标 / 为什么 / 起点 / 验收判据**。

### F1（P0，阻塞 3.2 的收尾）→ **已结案（2026-09-13 第三轮会话）**：声明「别丢」而非「补发」

- **目标（原文）**：让声明集合覆盖客户端可见窗的**全集**，使影子端自绘 pull 真正可以退场。
- **归因修正**：声明**本来就是全的**，也不存在「绕过抑制点直接推」的路径（Explore 全仓核查：整柱推送
  只有 `MixinServerPlayer`(1.20.1 `trackChunk`) 与 `MixinPlayerChunkSender`(1.21.1+ `sendChunk`) 两个抑制点，
  `ChunkHolder.broadcast` 只承载光照/方块更新，ShadowPull 响应是独立数据面）。真正的缺陷是
  **声明入队后被服务端自己抹掉**：`ChunkAuthorityNotifier.applyViewDistanceIfChanged` 在会话首个视距
  观测点（`viewDistance` `-1 → N`）执行 `state.pending.clear()`。
- **改法（已实施，`ChunkAuthorityNotifier`）**：首个观测点**不清空** `pending`；真实视距变小改为
  `pruneOutOfRange` **只剔除越半径项**（半径内声明必须保留）。判据 `previous > viewDistance`：
  首观测 `previous = -1` 不触发剔除。
- **验收判据**：**部分达成**——✅ 空洞项在全部证据点成立（1.21.1/fabric、1.21.1/forge、1.20.1/fabric、接管态、移动场景
  封闭空洞均 0）；✅ `starved = 0` **仅在 1.21.1/fabric** 成立；❌ 1.20.1/fabric `starved = 5` + 影子自绘 16 批、
  1.21.1/forge `starved = 4` + 4 批 ⇒ **声明流尚未在全部版本/加载器上做到让影子端自绘 pull 退场**。
  另见 §9.7 与 **F12**（neoforge 连抑制都未生效）。
- **决定性旁证**：修复后首个声明包从 `entries=5` 变为 **`entries=9 hashed=9`**，两轮声明总数
  **1964 → 1982 = 1964 + 9×2**——增量精确等于被找回的落位点 3x3；R1 observed 1520 → **1529**（满窗）、
  R2 444 → **453**。详见 §九。

### F2（P0）→ **已结案（2026-09-13 第三轮会话）**：让位门契约 = 「声明流存活」+「声明到达即接管」

- **目标**：把「门什么时候关」从竞态变成契约。
- **选定方向（A，用户拍板）**：`让位以「声明流存活」为唯一条件 + 声明到达即解除该柱扣留 + 扣留宽限对齐断流看门狗`。
  未选 B（`bootGridArmed` 排空前不让位——开机阶段影子端仍要自绘整盘，与 F1「自绘退场」目标相悖）、
  未选 C（登录后固定 N 秒——引入新的魔法数）。
- **改法（已实施）**：
  - `ChunkAuthorityClient`：`AUTHORITY_WATCHDOG_MS` 提为 `public`（成为让位门的契约窗口）；新增并发
    `DECLARED_AT`（复合键 → 声明时刻，客户端线程写）与 `declaredAtMs(...)`（影子线程只读）；
    `handle()` 逐条登记声明时刻（**先登记后 resolve**，解析失败也算已声明）；`onClientDimensionChanged` 清表；
    表上限 16384，超限整体作废（声明会随快照重灌）。
  - `ShadowTrackingSession`：`GATE_STARVE_GRACE_MS` 3_000 → 对齐看门狗（10_000），并改引用该常量避免漂移；
    `releaseGateStarvation` 把**已声明柱的扣留起算点抬到声明时刻**——声明已接管者不计饥饿、不抢跑；
    只有「声明覆盖后再等满一个宽限仍没交付」才补发 + 记 `starved`。全程不新增跨线程写（影子线程只读并发表）。
- **为什么必须绑在一起**：3s 宽限 + 原版限速声明流（首批 9/tick，自适应上限 64；灌满 VD20 可见窗需数秒）
  ⇒ 扣留在声明到达前就到期 ⇒ **假饥饿**（`_p5fix1` 实测每场 15 次）⇒ 「starved = 0」永远不可能达成，
  兜底被当成常态、失去信号价值。
- **验收判据**：✅ 达成——同配置连续两轮（`f1f2_prod1` / `f1f2_prod2`）`hash-hit` 26 / 34（同量级）、
  `authoritative-full pull` 0 / 0、`compare-pull` 0 / 0、`starved` 0 / 0；接管态两轮（`p5` / `p5b`）
  `hash-hit` 60 / 37、`starved` 0 / 0。详见 §九。
  **但该判据只在 1.21.1/fabric 成立**：1.20.1/fabric `starved = 5`、1.21.1/forge `starved = 4`（见 §9.7）。

### F3（P1）→ **已结案（2026-09-13 第二轮会话）**：23 个样本无一悬案

**样本清单先被修正。** 文档原列 10 个非 classic 命中，实扫（`build/smoke-test/probe/*/round*.json`
逐份跑 `_hole_check`，160 个 probe 目录）为 **23 个样本**：12 个 classic 3×3（max=9）+ 11 个非 classic。
原清单**漏了 `1.20.1_fabric_I_dimension_world4/round4.json`**——最大分块 **107**、封闭 123 格、
observed 808，比清单里第二大的 35 大 3 倍。

**决定性过滤：按构建时间分区（probe JSON mtime）。** 原清单把不同世代的样本混在一起，所以"像真缺陷"。

| 桶 | 样本（mtime） | 相对修复 | 判决 |
|---|---|---|---|
| classic 3×3（max=9） | 12 个：`nticketmove1` 03:08 → `bandmove2` 04:23（均 09-13） | **早于**让位门兜底（`GATE_STARVE_GRACE_MS`） | **已修**：04:5x 的 `_p5fix1`/`_p5off2` 实测 0/0 |
| 非 classic 大洞 303 / 107 / 23 | 09-12 04:06 / 05:23 / 04:44（dimension 场景） | **早于** `2a89ead`（09-12 05:30） | **已修**，见下 |
| 非 classic 小洞 35 / 15 / 13 / 5 / 4 / 2 / 1 | 09-11 02:10 → 09-12 23:25 | **早于**权威边沿（09-13 落地） | 考古样本，不足以判决；随 F4 矩阵一并复核 |

**303 样本的机理已定：单柱失败关掉影子端 → 切维后客户端缺中心区。** 该场（`1.21.1_neoforge_I_dimension`）
下界轮内：`CHUNK_APPLY` 3058 → **4**、`CHUNK_MESH` 2020 → **4**、**51 条** `failShadowServer` 降级提示、
结束时 `loadedChunks=1226 < trackedCandidateCount=1529`；缺口是玩家所在柱为中心的 **19×17 内 303 格**
（外圈 96 格仍在）——即"引擎关掉后不再补中心"。修复提交 `2a89ead`「keep shadow worlds filled across
dimension changes」把 4–5 处直呼 `failShadowServer()` 改成受守卫的 `noteSingleColumnFailure` /
`shouldFailShadowOnInjectFailure`，其注释原文即：*"关引擎后切维（下界/末地/返主）会变成空 ClientChunkCache"*。
该场日志 mtime 09-12 04:07、文案 `chunk.hassiumEngineEnabled`（早于 09-12 17:25 的键改名）——确认为旧构建产物。

**当前代码复跑（新证据，会话 `1.21.1_fabric_I_dimf3b`，09-13）**：dimension 场景 4 轮 **0 封闭空洞**——
R1 overworld 1529/1529、R2 nether **1572/1572**（旧场 1226/1529）、R3 end 1529/1529、R4 back 1529/1529
（R4 `networkReceived=0`，走影子缓存重发）；`=== RESULT: PASS ===`，analyzer `failures=[] warnings=[]`。
**`2a89ead` 的修复在现有代码上确认有效。**

**F3 副产品：三个 harness 缺口（本次实测）**

1. **dimension 场景看不见这类空洞**：其自身门禁只有 `clientCache.loadedChunks > 64` /
   `chunkTrace.clientApplied > 64`。1226 驻留、缺 303 照样 `RESULT: PASS`——那个空洞当初是"合法通过"的。
   建议把 `dimension`（至少 P0 级）纳入封闭空洞门禁；`_hole_check` 对它的输出已在 `analysis.spatial.*.enclosed`。
   （**已修**：F10 已把 `dimension` 纳入 P0 门禁，见 §五 F10。）
2. **超时默认值漂移，使 scenario 复跑必假 FAIL**：`runtime-smoke-test.ps1` 默认
   `ClientTimeoutSec=120` / `ServerReadyTimeoutSec=60`，而 `docs/runtime-smoke-test.md` 表格写 `240`/`160`、
   `runtime-smoke-test-batch.ps1` 用 `600`/`300`。dimension 实测需要 ~186s，用默认值必然
   `客户端超时未退出，强制结束`，并伴随误导性的 `Negative index in crash report handler (13/21)` 门控失败。
   带 `-ClientTimeoutSec 300 -ServerReadyTimeoutSec 180` 即 PASS。**复跑 scenario 必须显式给足超时**。
   （**已修**：F10 已把默认值改为 180/300 并同步 `docs/runtime-smoke-test.md`，见 §五 F10。）
3. **`Round2Pass=false` 在 scenario 会话里是设计态，不是失败**：中段 dump 用 `gate=false`（validation skipped），
   总判决由 Python analyzer 给。别把它读成回归。

**遗留（转入 F4）**：`modcompat` / `seedgen` 的小洞（max ≤ 15）自权威边沿落地后**未在任何当前代码会话中复跑**；
F4 的矩阵（含 scenario 锚点 `1.20.1/fabric`、`1.20.1/forge`、`1.21.1/neoforge`、`1.21.11/neoforge`）会覆盖它们。

- **起点**（已用）：`scripts/smoke/analyzer.py::_hole_check` + `build/smoke-test/probe/<id>/roundN.json` 的 mtime。
- **验收判据**：✅ 达成——23 个样本全部归到"已被修复（含提交号与复跑证据）"或"考古样本（早于相关修复）"，无悬案。

### F4（P1）用全矩阵重建门禁基线

- **目标**：`TRACE_ENCLOSED_HOLE` 落地后跑一次 L1 全矩阵（12 版 × fabric/neoforge），确立新基线。
- **为什么**：新门禁尚未在矩阵上跑过。历史回放只证明"除 **8 个会话 / 11 轮样本**外零新增 P0 命中"，
  **不能**证明矩阵全绿——而且按 §3.1，生产配置**有**可能亮灯（`_band1` 即证）。
  亮灯要看是门禁抓到了真问题，还是门禁太严——两者的处置完全不同。
- **起点**：`scripts/runtime-smoke-test.ps1` 批量跑法、`build/smoke-test/batch-results-I.csv`、
  `build/smoke-test/run-seedgen-matrix.ps1`。
- **验收判据**：`batch-results-I.csv` 里 `TRACE_ENCLOSED_HOLE` 的命中数，逐个给定性。

### F5（P1）接管态的矩阵证据缺口

- **缺**：1.20.1 fabric（`playerLoadedChunk` 物化钩子那条分支，与 1.21.1+ 的
  `ChunkMap.onChunkReadyToSend` 不同码路）、forge、neoforge、dimension / seedgen 场景、移动场景多轮。
- **为什么**：现在"接管可用"的证据只有 1.21.1 fabric 两个场景。**没有矩阵证据就不能开**。
- **起点**：同 F4，加 `-P5_TAKEOVER=true` 临时翻开关（**改源码前先冻结，见 §六.5**）。
- **第四轮已跑（6 场，`P5_TAKEOVER=true`；哨兵 `[SHADOW_TICKET] bound instance` 每场都 **= 1**
  ⇒ 驱动确实在跑，不是陈旧常量内联）**：

  | 会话 | 场景 | 判决 | R1 / R2 封闭空洞 |
  |---|---|---|---|
  | `1.20.1_fabric_I_p5_f5a` | classic | **PASS**（收尾 `save completed seq 3→4`） | 0 / 0（1561 / 453） |
  | `1.21.1_forge_I_p5_f5b` | classic | **PASS** | 0 / 0（1529 / 453） |
  | `1.21.11_neoforge_I_p5_f5c` | classic | **PASS** | 0 / 0（1573 / 454） |
  | `1.21.1_fabric_I_p5_move_f7` | classic `-MoveSeconds 12` | **PASS** | 0 / 0（1614 / 96） |
  | `1.21.1_fabric_I_p5_dim_f5d` | dimension | **FAIL** | R1 **0**；R2 probe 缺失 |
  | `1.20.1_fabric_I_p5_seedgen_f5e` | seedgen | **FAIL** | R1 **0** |

- **结论 —— F5 的答案仍是「接管臂不能开」，但现在有矩阵读数了**：
  1. **classic 在三个 loader 上全绿且 0 空洞**，这是此前缺的主证据；尤其 **1.20.1 的
     `playerLoadedChunk` 物化分支**（与 1.21.1+ 的 `ChunkMap.onChunkReadyToSend` 不是同一条码路）成立，
     移动场景也绿 ⇒ 「环带驱动覆盖经典装载」在**数据面**上成立；
  2. **但两个场景在接管态不成立，且各有明确读数**：
     - `seedgen`：`counters.locallyGenerated = 0`（场景断言 `gt 0` 失败）——接管的 `FORCED` 票
       让这些柱改由影子端装载/生成，**SeedGen 的本地生成触发路径被整个绕过**；
     - `dimension`：nether 段 `[SHADOW_TRACK] sweep missing=26 localGen=false center=(-1,0) radius=20`
       持续复现（26 柱始终补不上）；期间 `authority gate starved 12 chunks beyond 10000ms -> pull anyway`
       ——**让位门兜底按契约生效**（F2 的 10 s 宽限 + 回退自绘 pull），但**柱子仍然补不上**。
  3. ⇒ 「驱动覆盖 OVD 环带」的成立范围是 **classic**；**跨维 / SeedGen 两个场景不成立**。
     与 §9.7「先裁选柱推断、保留 `bootGrid`/`sweepVisibleShape` 两条 pull 驱动」的方向一致：
     **接管臂要开，先得让这两个场景成立。**
- **开关已翻回 `P5_TAKEOVER = false` 并重建**（`git diff` 对该文件为空 ⇒ 与 HEAD 逐字一致）。

### F6（P2）`ShadowTicketDriver` 去留决策

- **现状**：310 行，`P5_TAKEOVER=false` 下**一行不执行**（`consumeOnShadowLoop` 首行 `!ENABLED` 返回；
  生产轮 `[SHADOW_TICKET]` 实测 0 行），却挂在两个生产调用点上。
- **建议删除**：重测的前置是 F1（服务端先补声明），到那时这套环带几何很可能要重设计，留着未必能复用；
  git 留得下历史。若决定保留，至少把两处调用点注释成"实验臂，默认关闭"。

### F7（P2，非阻塞）P5-5 收尾 `saveAll` 停滞归因 → **未复现（第四轮），降为观察项**

- **现象**：接管开启时 R2 收尾 `saveAll` 停在 seq 1 → 等待超时 → harness 强退 → `0xC0000409`
  （`bandmove2` / `otmove4`）；接管关闭时均正常（含本次 `_p5fix1`）。
- **未定**：候选是「`FORCED` 票让柱常驻、改变了 `saveAll` 的等待条件」与「harness 强退本身」两种，**未做分离实验**。
- **第四轮分离实验（已做）**：`1.21.1_fabric_I_p5_move_f7`（**接管开** + 同款 `-MoveSeconds 12` 场景）——
  `=== RESULT: PASS ===`、收尾 `shadow save completed (seq 1 -> 2)`，**没有** `save wait timed out`。
  与接管关的 `f11move`（同样 PASS、收尾同样正常）逐项一致。
  ⇒ **「接管开启 ⇒ saveAll 停滞」这条相关性在当前代码上不成立**；此前那两场（`bandmove2` / `otmove4`）
  属于**不可复现的历史观测**，不能当作接管臂的固有缺陷来读。
- **剩余**：候选机理既未证实也未证伪（可能是那两场的 harness 时序），但它**不再阻塞任何判定**——
  接管臂已被 F5 挡在门外（场景不成立），与收尾停滞无关。结论记作「**未复现，观察项**」。
- 因接管默认关闭，该停滞不进生产路径。

### F8（P3）两个既有的小缺口 → **已定性结案（第三轮会话，靠 probe 复核）**

- **`1.20.1_fabric_I_move2` R1 的 1 格封闭空洞 `(-20,3)`**：在 trace 里**全链走完**
  （`networkReceived` → `shadowInjected` → `shadowReady` → `clientApplied` 都有它），但**不在
  `clientCache.actualPresent`** 里。即「交付成功但已不驻留」——移动会话飞离后的常驻衰减，
  与 **F14 同族**；且它是 1 格分量（< 4）⇒ 只触发 `TRACE_ENCLOSED_HOLE_SMALL`（**P1 告警，不阻塞**）。
  本场 `observed = loadedChunks = 1644`、`trackedCandidateCount = 2189`，与"已交付多于常驻"一致。
- **「移动场景既有 3 柱缺口 `(-4,-2) (-3,-2) (-2,-2)`」→ 该说法作废（勘误）**：复核
  `1.21.1_fabric_I_bandmove1` R1 的 probe，那 3 柱正是 **F1 落位点 3x3 空洞的整条下沿**
  （`enclosedHoles` = `[-4..-2] × [-2..0]` 共 9 格，`(-4,-2)(-3,-2)(-2,-2)` 即其中 z=-2 一行），
  **不是另一个独立缺口**——就是 F1 已修的那一类，且该场在 F1 之前。
  同一轮 `1.20.1_fabric_I_move2` R1 里这 3 柱**都在 `actualPresent` 中**（已交付：四个 stage 全有）。
  原文"全日志 0 次出现"说的是**日志文本**没有这三柱的专行，不等于 trace 里缺——据此得出的
  "既有缺口"结论是把日志口径当成了交付口径。
- **判决**：两项均**不需要修**（一项是 F14 同族的 P1 常驻衰减，一项是 F1 已修缺口的误记）。

### F9（P3，文档）§9 系列该整章重写了

`client-chunk-flow-handover.md` §9.5 → §9.9 之间**有三次自我推翻**（作废读数、配错开关、接管相关性记错）。
现在读起来会被中间那些作废结论误导。建议下次收尾把它整章重写成一份「权威边沿现状 + 已知竞态 + 覆写清单」，
把作废过程压成一条时间线附录。

### F10（P1，harness）→ **已结案（2026-09-13 第三轮会话）**：dimension 纳入 P0 空洞门禁 + 超时默认值对齐

- **现状**（原文）：`dimension` 场景自身门禁只有 `loadedChunks > 64` / `clientApplied > 64`，**看不见** 303 格中心空洞
  （那场当初就是 `RESULT: PASS`）；且 `ovdgen` 退役后，"非 classic 一律排除"的原始理由对 `dimension` 已不成立。
- **改法**（已实施）：
  - `scripts/smoke/analyzer.py`：新增场景口径常量
    `_ENCLOSED_HOLE_P0_SCENARIOS = {"classic", "dimension"}` / `_ENCLOSED_HOLE_P1_SCENARIOS = {"classic"}`。
    P0（`largest >= 4`）判 classic + dimension；P1（零散小洞）仍仅 classic（dimension 维边界/采样边缘噪声大于信号）。
    `seedgen` / `modcompat` 两档均排除（稀疏采样不走同一交付契约）。
  - `scripts/runtime-smoke-test.ps1`：`ServerReadyTimeoutSec` 60 → **180**、`ClientTimeoutSec` 120 → **300**。
  - `docs/runtime-smoke-test.md`：参数表默认改 `180` / `300`，并同步两处引用（快速开始的「内部上限」、退出码 3 的 180s）。
  - `AGENTS.md`：同步两处陈旧数字（默认 300、最坏 180+300）。
- **验收判据**：✅ 达成（实测，非推断）
  - `1.21.1_fabric_I_dimf3b`（当前代码、已知 0 空洞）在新口径下仍 `pass = True`，四轮 `largestComponent = 0`。
  - 回归哨兵：把 09-12 那份 `1.21.1_neoforge_I_dimension` 的 probe 喂给新口径 → `TRACE_ENCLOSED_HOLE` @ **round2 / largestComponent = 303**，`pass = False`。
  - 全量回放 166 份 `result_*.json`：新增 P0 命中只有 classic 的 8 会话 / 11 轮（与 F3 已知集合同）＋ dimension 两场
    （`1.21.1_neoforge_I_dimension` R2=303、`1.20.1_fabric_I_dimension` R2=23）——**两场 mtime 均 09-12，早于 `2a89ead`（09-12 05:30）**，
    即已修历史样本，无当前代码误报。`python -m unittest scripts.smoke.test_analyzer` → **19 passed**（新增 2 例：
    `test_dimension_enclosed_hole_fails` / `test_dimension_small_hole_is_not_gated`）。
- **注**：`1.20.1_fabric_I_dimension_world4/round4.json` 的 107 格空洞仍在 R4，而 `dimension` 当前只分析 R1/R2
  （`round_numbers`），故新口径不会看到它；F3 已按 `2a89ead` 归为已修。是否把 dimension 扩到 4 轮是 F4 范畴，本次不动。

### F12（P1）→ **已结案（2026-09-13 第三轮会话）**：NeoForge 把整柱包封进 `ClientboundBundlePacket` 下发

- **根因（一次性诊断钉死）**：在 redirect handler 里临时打印入参运行时类型，neoforge 全程是
  ```
  [F12_DIAG] #1 entered packet=ClientboundBundlePacket compression=true pullMode=true shadowCtx=false
  ```
  —— redirect **被调用**、压缩门/pullMode/非影子态**全成立**，但 `packet` 不是
  `ClientboundLevelChunkWithLightPacket`。NeoForge 在 `PlayerChunkSender.sendChunk` 里把它包了一层
  （1.21.1 与 `1.21.x` 两个分支的 patch 实测一致）：
  ```java
  p.send(p.getAuxLightManager(p.getPos()).sendLightDataTo(new ClientboundLevelChunkWithLightPacket(...)));
  ```
  于是 `packet instanceof ClientboundLevelChunkWithLightPacket` **恒为假** → 落到 `listener.send(packet)`
  → 整柱照发、`onAuthoritativeEnter` 从不调用。fabric / forge 发的是裸包，故只有 neoforge 中招。
- **改法（已实施，`MixinPlayerChunkSender`）**：新增 `hassium$levelChunkPositions(Packet)` 解包
  `ClientboundBundlePacket.subPackets()`，按**内层**整柱包判定与取坐标；命中即逐柱发声明并抑制整个 bundle
  （连同其辅助光照子包，与 `lightStrip` 语义一致）。裸包路径行为不变（非整柱载荷照旧放行）；
  `!pullMode` 分支保持原有「不下发」语义以免顺带改变既有行为。
- **验收判据**：✅ 达成——`1.21.1_neoforge_I_f12fix`：
  `origin=server_push` **3068 → 0**；`decompressed/applied` **572/1624 → 1529/1529**；
  `new/stale` **1529/95 → 0/1529**；服务端 `[AUTHORITY] send` **0 → 154 行 / entriesSum 1982**
  （与 fabric/forge 完全一致）；首个声明包 `entries=9 hashed=9`；analyzer `failures=[]`、R1 1529 / R2 477、空洞 0。
- **影响**：neoforge 恢复「停发整柱」卖点并接上权威边沿 ⇒ **重新成为 F1/F2 的有效验证点**
  （§9.7 的 neoforge 行结论已随之更新）。**注意**：这不改变 §9.7 的总体结论——
  1.20.1/forge 的 `starved` 缺口与接管臂自身的 F5/F7 仍在。

- **现象**：全量 probe 回放（判据 `chunksDecompressed == clientAppliedChunkCount`，即"是否走压缩 pull 载荷落地"）：
  **11/11 个 neoforge classic 会话（1.21.1–1.21.10）都是"原生流"**，而 **fabric / forge 的 classic 会话（20+ 场，全部版本）全部抑制生效**。
- **因果链**：抑制点不生效 ⇒ `onAuthoritativeEnter` 从不入队 ⇒ 服务端 `[AUTHORITY] send` = 0 ⇒
  客户端 `authorityDeclared` 恒 false ⇒ 让位门恒开 ⇒ 影子端自绘整盘（`authoritative-full pull` 13 批 ≈1664 柱）。
  客户端靠「拦截 `tryInterceptForCompare` 原生包 + 影子端自绘 pull」照样铺满，**空洞 0、门禁 PASS** ⇒ 门禁看不见。
- **既有性**：`1.21.1_neoforge_I_dimension`（09-12，**权威边沿落地之前**）`decompressed = 0`；`1.21.10_neoforge_I`（历史
  classic）`applied=1642 / decompressed=574`。⇒ **早于本轮改动，非回归**，且文档无任何记载（`docs/classic-matrix-smoke-report-2026-08-28.md` 只写
  「两轮 failures=[]」）。
- **本次实测指纹**（`1.21.1_neoforge_I_f1f2` vs `1.21.1_fabric_I_f1f2_prod1` 的 R1）：
  `new/stale` = **1529/95** vs 0/1529；`decompressed/applied` = **572/1624** vs 1529/1529；
  `[AUTHORITY] send` = **0** vs 163；`authoritative-full pull` = **13** vs 0。
  机制说明：`new = 1529` 表示 1529 次请求无本地基线（应答 FULL、本应走 `decompressPullFull`），
  但 `decompressed` 仅 572 ⇒ 差额经 `pending.fallback()` 用**原生包数据**落地 = 原生包在流的指纹。
- **已排除**：mixin 登记齐全（`neoforge.mods.toml` 三份 config 含 `hassium.mixins.json`）；
  `play_init` 激活链正常（`ACTIVE_CAPS` 有值、`enableCompression` 已调）。
- **同类残留风险（未改，无观测症状）**：`MixinChunkHolder.hassium$onBroadcast` 也用裸 `instanceof`
  （`ClientboundLevelChunkWithLightPacket` / `ClientboundLightUpdatePacket`）。它拦的是 `ChunkHolder.broadcast`
  的**增量广播**路径，而 NeoForge 的 bundle 封装只出现在 `PlayerChunkSender.sendChunk` 的**初始下发**路径，
  故当前无症状、不动它；若日后在 neoforge 观察到多余光照下行，先查这里。
- **影响（已解除）**：修复前 neoforge 既没吃到「停发整柱」卖点、也没跑权威边沿；修复后两者均恢复。
- **方法与教训**：定性靠「一次性打印入参运行时类型」这一行诊断，比继续猜 patch 快得多——先前已排除的假设
  （mixin 未登记 / 注入未命中 / 压缩门失效 / 激活链没跑）被这一行全部推翻，真因是**参数类型**。
  反编译 patched jar 的路子本地不通（NFRT 缓存只有 1.21.11 产物），改为直接取 NeoForge 仓库的 patch 文本
  （`raw.githubusercontent.com/neoforged/NeoForge/<branch>/patches/net/minecraft/server/network/PlayerChunkSender.java.patch`）。

### F13（P2，第三轮新增）`dimension` 场景是 flaky 的（非本轮回归）

- **现象**：`1.21.1_fabric_I_f1f2_dim` R1 完成（主世界 **1529 柱 / 空洞 0**），R2 切下界时客户端以
  **`0xCFFFFFFF`（NTSTATUS 原生终止）** 猝死 → `round2=False`、`CLIENT_EXIT_NONZERO`、`PROBE_MISSING`、`SMOKE_PASS_MARKER_MISSING`。
- **既有性**：`1.21.1_fabric_I_dimf3`（09-13，**F1/F2 之前**）**完全相同的失败形态**；`dimf3b` 通过。⇒ 场景本身 flaky。
- **第四轮复跑（1.21.1/fabric × 3）**：`f13a` **PASS**、`f13b` **FAIL**、`f13c` **PASS** ⇒ flaky 复现率 **1/3**。
  但 `f13b` 的失败**形态与上面那条不同**（**至少两种失败模式**）：
  - 客户端**没有**原生猝死（`PROBE JSON: round1=True round2=True`），而是场景内 `assertProbe` 失败：
    `NETHER_CHUNKS key=clientCache.loadedChunks gt 64 (actual=0)`——切下界后**常驻缓存为 0**，
    而同一轮的 `clientApplied = 2432`照常计数（`CLIENT_CACHE_EMPTY`：`applied=2432, loaded=0`）；
  - 附带 R1 的 `TRACE_ENCLOSED_HOLE`（18 格，分块 `[5,4,2,1,1,1,1,1]`，坐标散布在 x∈[-21,-17] 与 x∈[7,16]）
    ——**分块 5/4 刚好越过 P0 的 4 格门槛**，而同配置的 `f13a` / `f13c` 是 0。
- **处置（未结）**：R1 读数仍有效（可作证据）；`dimension` 需要**可重试策略**（或在门禁里标注"需 ≥2 次取稳定态"）。
  **`f13b` 的两种现象（切维后常驻缓存为 0 / R1 边界 P0）都没定性**，不要在没有复现的前提下归因到权威边沿——
  它们与 `f13a`/`f13c` 的差异可能只是同一 flaky 的不同侧面。
- **相关**：生产态 classic 上也出现过一次同类**无 Java 痕迹的原生终止**，另立 **F17**（含速率读数 1/6 与判定）。
  两处的现象类别相同（无异常、无 `hs_err`、无 crash-report），但**归属未定**。

---

## 六、坑与教训（本会话踩过的，别再踩）

1. **门禁的候选集口径决定它能看见什么。** `expectedNotPresent` 的候选集是 `networkReceived` 本身，
   所以它对**从未投递**的柱**结构上盲**——不是"漏了检查"，是口径上不可表达。
   写/审门禁时自检一句：**这个门禁能看见"从未发生"吗？**
2. **`DebugLogger.LogType.ASYNC` 在冒烟 profile 不生效**（受 `debug.asyncLogging` 门控）。
   实验/热路径日志一律用 `LogType.NETWORK`。当初这个坑让"`add ticket failed` = 0 是正面证据"的推断直接作废。
3. **两个布尔开关一定会配错。** `ENABLED=false` 配 `NEUTRALIZE_TRACKING=true` 跑出来的其实是纯基线，
   却被当成环带设计的验证读了一轮。收成单开关 `P5_TAKEOVER`，让配错的中间态在结构上不存在。
   **哨兵**：留一条**无条件打印**的首绑行（`bound instance`），它不出现就等于"驱动真的没跑"。
4. **`static final boolean` 是编译期常量，会内联进依赖类。** 改完常量后依赖类可能不重编。
   本次用上面那条哨兵行 = 0 验证了没被陈旧内联，但下次改开关后要主动确认。
5. **跑冒烟时不要改/编源码**（AGENTS.md 红线）。本次在启动冒烟**之前**发现让位门的记账有 bug
   （等待表按"本拍成员"裁剪会被同拍两个批次互相抹掉计时），**主动 StopTask 重来**，
   而不是等一场注定无效的读数——这一步省下的是十几分钟和一次错误结论。
6. **PowerShell 里 `[regex]::Escape` 之前先确认字符串里没有已经写好的转义。**
   本会话写 `'redeliver \('` 再 `Escape` → 匹配字面 `redeliver \(` → 报 **0** 次，实际 **1214** 次，
   差点据此推出错结论。（更早一轮还用过 `-SimpleMatch` 配 `\(`，同样误报 0。）
   查日志计数用明确的坐标/字符串，别用带转义的半正则。
7. **无界的"下一帧重试"必须有界**（P4a 教训，仍在生效）：`ShadowLightCompute.applyReadyChunk`
   对原版不可自愈拒收的柱，连续 60 次（≈3s）即放弃该投递条目；无上限重试实测 21s / 102010 行 /
   日志 84MB / 渲染线程吃满。
8. **移动场景的单轮跨配置对比不可靠**，本次进一步发现**同配置跨轮机理也能不同**（§3.1）。
   判决尽量用**运行内量**（`spatial` / `injectedNotReady` / `expectedNotPresent` / 封闭空洞 /
   `hash-hit` vs `authoritative-full pull` 的成对读数），别用跨轮总数。

---

## 七、可复现命令

```powershell
# pwsh 7（当前壳）——编译 / L0 / 边界
.\gradlew.bat common:compileJava "-Pmc_ver=1.21.1"
.\gradlew.bat common:compileJava "-Pmc_ver=1.20.1"      # 跨段抽检
.\gradlew.bat common:test scanVersionBoundaries "-Pmc_ver=1.21.1"

# Python 门禁单测
python -m unittest scripts.smoke.test_analyzer -v

# 冒烟（生产态）。典型 2–12 min，脚本自己结束，等 "=== RESULT:" 后读退出码
.\scripts\runtime-smoke-test.ps1 -Ver 1.21.1 -Loader fabric -Phase I -SessionId "1.21.1_fabric_I_x"
```

```powershell
# 从既有 probe 直接读封闭空洞（不需要重跑冒烟）——推荐先用它对历史结果做归因
$env:PYTHONPATH='.'; python -c "from pathlib import Path; from scripts.smoke.analyzer import load_and_analyze; import json; r=load_and_analyze(Path('build/smoke-test/results/result_1.21.1_fabric_I_p5off2.json')); print(json.dumps(r['spatial']['round1']['enclosed'], ensure_ascii=False))"
```

```powershell
# 某会话的装载来源成对读数（判断"声明 vs 影子端自绘 pull"谁在干活）
$f='build/smoke-test/logs/client_<SessionId>.log'
(Select-String -Path $f -Pattern 'boot grid primed').Count
(Select-String -Path $f -Pattern 'hash-hit zero-request').Count
(Select-String -Path $f -Pattern 'authoritative-full pull').Count
(Select-String -Path $f -Pattern 'authority gate starved').Count
(Select-String -Path $f -Pattern 'SHADOW_TICKET').Count      # 0 = 驱动没跑（哨兵）
```

```powershell
# 临时翻接管臂跑冒烟（改完立即改回！跑之前冻结源码，跑完才动）
#   common/.../seedgen/ShadowTicketDriver.java:  P5_TAKEOVER = true
#   跑完务必改回 false 并重编 + 跑一次生产态回归
```

---

## 八、交接检查清单

- [x] 读 §三 三小节（结论）——2026-09-13 第二轮会话已读
- [x] 确认 `P5_TAKEOVER = false`（`ShadowTicketDriver:49`）、`common:compileJava` 通过
      （1.21.1 四模块 + 1.20.1 抽检）、`python -m unittest scripts.smoke.test_analyzer` 通过（19 tests OK）
- [x] 决定 F6（`ShadowTicketDriver` 保留；`ShadowTrackingSession` 三处调用点已注释为"实验臂，默认关闭"）
- [x] F3 已结案（23 个样本全部定性，含当前代码 dimension 复跑 0 空洞）
- [x] **F10（P1，harness）**：`dimension` 纳入封闭空洞门禁（P0）＋ 超时默认值对齐 180/300
      —— 2026-09-13 第三轮会话完成，见 §五 F10（含 dimf3b 仍 PASS + neoforge 303 哨兵 + 全量 166 份回放）
- [x] **F4 收敛矩阵已跑**（第三轮）：编译七锚点 × `builds_for` 全过；运行时 3 版本（1.20.1 / 1.21.1 / 1.21.11）× `builds_for` 9 组合。
      抓到 **F15**（1.21.11/neoforge R2 空洞 → 已定性为 **P0**（F1 同族、丢在客户端接收侧）并**按「快照重推 = 自愈」修复**，
      两轮复测空洞 0/0；丢弃现场证据待补）。见 §9.9 与 F15。
- [x] **F4 余项已复跑**（第四轮）：`seedgen` × 1.20.1/fabric（`_f4b` **PASS**、空洞 **0**）、× 1.21.1/fabric（`_f4` **PASS**）；
      `modcompat` × 1.20.1/fabric（**PASS**，但封闭空洞 **43 格**、最大分块 **31**——比历史 ≤15 大，见下）。
      **结论**：`seedgen` 口径干净、可纳入 P0 门禁候选；`modcompat` **暂不纳入**（洞大且未定性）。
      ——顺带抓到 **F16**（F11 聚合把 C2S 载荷顶过 32 KiB，1.20.1 客户端被踢），已修复并复测（见 §五 F16）。
- [ ] **F4 余项遗留**：`modcompat` 的 43 格封闭空洞**未定性**。已知：43 格在
      `networkReceived / shadowInjected / shadowReady / clientApplied` **四条链上一次都没出现**，
      且 `trackedCandidateCount == loadedChunks == observed == 806`（无"已跟踪却缺席"的证据）；
      该场景 `locallyGenerated = 1470`（SeedGen 开）⇒ 与「稀疏本地生成 + 单轮 dump 未 settle」一致，
      但**这只是相符、不是证明**。要纳入门禁须先给它一个 settle 判据（或多轮取稳定态）。
- [x] **F5 接管矩阵已跑**（第四轮，6 场，`P5_TAKEOVER=true`，哨兵每场 `bound instance=1`）：
      classic × 1.20.1/fabric + 1.21.1/forge + 1.21.11/neoforge + 移动场景 **全 PASS / 空洞 0/0**；
      `dimension`（nether 段 `sweep missing=26` 持续补不上）与 `seedgen`（`locallyGenerated=0` 断言失败）**FAIL**。
      ⇒ **接管臂仍不能开**（两个场景不成立），但缺的矩阵证据已补齐。开关已翻回 `false` 并重建。见 §五 F5。
- [x] **F7 分离实验已做**（第四轮）：接管**开** + 移动场景 `1.21.1_fabric_I_p5_move_f7` = **PASS**、
      收尾 `shadow save completed (seq 1 -> 2)`、无 `save wait timed out` ⇒ **「接管 ⇒ saveAll 停滞」不成立**，
      降为观察项。见 §五 F7。
- [x] **F13 已复现并加注**（第四轮）：1.21.1/fabric `dimension` × 3 = PASS / **FAIL** / PASS（**1/3**），
      且新失败形态**与文档里那条不同**（切下界后 `clientCache.loadedChunks = 0` + R1 边界 P0）。
      **未定性**，结论写「需要可重试策略」。见 §五 F13。
- [ ] **F15 残项仍未闭**：1.21.11/neoforge 累计 **5 轮 PASS / 空洞 0**，但**丢弃计数 5 场全 0**
      ⇒ 「丢过 → 自愈」的现场链路**没拿到**。继续盲跑性价比低，留给下一轮（定向制造窗口或换场景）。见 §五 F15。
- [x] **F1 / F2 已落地**（2026-09-13 第三轮；F2 契约方向由用户拍板选 A），但**验收仅部分达成**：
      空洞项全绿；`starved` 仅 1.21.1/fabric 为 0，1.20.1/fabric = 5、1.21.1/forge = 4、neoforge(修复后) = 3。
      判据与量化见 §9.7。
- [x] **F12 已结案**（同轮）：neoforge 整柱抑制/权威边沿未生效的根因是 `ClientboundBundlePacket` 封装，
      已修复并复测通过（§五 F12）。
- [x] **F11 已修复并复测**（同轮，`1.21.1_fabric_I_f11`）：C2S pull 由逐柱改为按声明包聚合，
      `ShadowPullClient.request` **1500 → 157 行**、与 `[AUTHORITY] send` 171 行 **≈1:1**、空洞 0/0。见 §9.5。
- [x] **F14 已改口径并复测**（同轮）：`MoveSeconds` 透传进 result JSON；移动会话的驻留口径两项降为诊断。
      端到端 `1.21.1_fabric_I_f11move` **PASS**（空洞 0/0；本场未触发降级，见该节的如实说明）；
      历史场次 `1.21.1_fabric_I_f1f2_move` 就地重判 **FAIL → PASS**；单测 4 条。
- [x] **F8 已定性结案**（同轮，probe 复核）：1 格空洞 `(-20,3)` 是「交付成功但不驻留」（F14 同族、P1 不阻塞）；
      「既有 3 柱缺口 `(-4,-2)(-3,-2)(-2,-2)`」是 **F1 落位点 3x3 的整条下沿**，不是独立缺口——原结论作废。见 §五 F8。
- [x] **F9 部分达成**（同轮）：未整章重写 `client-chunk-flow-handover.md` §9（保留一手读数与推导过程），
      改为在章首加 **「读前速览」**——现状 / 已知竞态表 / 覆写清单（5 条作废结论逐条标注）+
      指向本文档为唯一权威记录。

> **本轮已完成并提交**（`0fd7e28` 门禁 / `55350b2` 权威边沿 + 接管臂 / `4705d95` 文档 / `3257415` 关闭 F3）：
> 工作区此前 27 改 + 9 新增全部落盘；`ShadowTicketDriver` 调用点已标注；F3 结案写入本文档。
>
> **后续轮次**（2026-09-13，同一日两段）：先落地 F10（`dimension` 纳入 P0 空洞门禁 + 超时默认值 180/300 + 文档同步），
> 随后 **F1 / F2**（落位点 3x3 空洞根因 + 让位门契约；验收部分达成，见 §9.7）、**F12**（neoforge 整柱
> 抑制/权威边沿未生效，见 §五）、一个 **teardown GLFW 守卫**修正（§9.4）与 **F15**（声明被丢 → 快照重推自愈）。
> 再后一轮收尾：**F11**（逐柱 pull → 按声明包聚合，1500→157 包）、**F14**（移动会话门禁口径 + 端到端 PASS）、
> **F8**（结案：一项是 F14 同族 P1、一项是 F1 已修缺口的误记）、**F9**（§9 章首加速览，未整章重写）。
>
> 第四轮（2026-09-13 收尾）：**F11**、**F14**、**F8**、**F9**、**F4 余项**、**F5**（接管矩阵）、**F7**（降级为观察项），
> 并抓到本轮自引入的 P0 **F16**（F11 聚合顶破 C2S 32 KiB 上限，已修复 + 复测）；`P5_TAKEOVER` 已翻回 `false` 重建。
>
> 剩余未闭（第四轮结束时的真实状态）：
> - **F15 残项**：修复已 5 轮复测空洞 0/0，但「丢弃 → 自愈」的**现场观测证据**仍未拿到（5 场丢弃计数全 0）。
> - **F13**：`dimension` flaky 已复现（1/3），且出现**第二种失败形态**，**未定性**；需要可重试策略。
> - **F4 余项遗留**：`modcompat` 43 格封闭空洞**未定性**（暂不纳入 P0 门禁）。
> - **F5 后续**：接管臂在 `dimension`（nether 段 `sweep missing=26`）与 `seedgen`（`locallyGenerated=0`）
>   两个场景不成立——这是接管臂要开之前必须先解的两件事。
> - **F7**：已降为观察项（未复现），不阻塞任何判定。
> - **F17**（新）：F16 之后生产态 classic 出现一次**无 Java 痕迹的原生终止**，速率 **1/6**（F16 前同配置 0/10）。
>   样本量不足以定性 ⇒ **F16 记为「修好载荷越界、无已知回归」，但未经证「无副作用」**。见 §五 F17。

---

## 九、第三轮会话记录：F1 / F2 结案（2026-09-13）

### 9.1 落位点 3x3 空洞：完整因果链（已证）

1. vanilla `PlayerChunkSender` 首批 = `START_CHUNKS_PER_TICK = 9.0F`（`desiredChunksPerTick` 初值同），
   `collectChunksToSend` 按 `distanceSquared` 取**最近 9 格** → 恰好是玩家落位柱为中心的 **3x3**。
2. `MinecraftServer.tickChildren` 的 `"send chunks"` 段发出这批：每柱被 Hassium 抑制
   （`MixinPlayerChunkSender` 1.21.1+ / `MixinServerPlayer` 1.20.1）**并在抑制点调用
   `ChunkAuthorityNotifier.onAuthoritativeEnter` 入队声明**。
3. `MixinMinecraftServer` 把 `ChunkAuthorityNotifier.onServerTick` 挂在 **`tickServer` TAIL** —— 即
   **同一 tick 的 send-chunks 段之后**。
4. `applyViewDistanceIfChanged` 首次看到视距（`-1 → 20`）→ `state.pending.clear()` → **这 9 条声明原地蒸发**
   （日志 `epoch=3` 正是两次递增：dimension 首设 + 首次视距）。
5. vanilla 已把这 9 柱从 `pendingChunks` 取走（`collectChunksToSend`）并计了 batch ACK → **永不再发 → 永不再声明**。
6. 客户端让位门已关（`authorityDeclared`）→ 影子端自绘 pull 被抑制 → **永久 3x3 虚空**。

**旁证（三条独立）**：
- 空洞坐标 = `[[-3,-1] … [-1,1]]`，中心 `(-2,0)`，与 `[SHADOW_TICKET] reconcile … center=(-2, 0)`（落位柱）**同一格**；
- 声明总数 **1964 = 443 hash-hit + 1521 单柱请求**（客户端把声明**一条不剩地消费完**）⇒ 声明确实是全的，只缺那 9 格；
- 修复后首个声明包 `entries=5` → **`entries=9 hashed=9`**，两轮总数 1964 → **1982 = 1964 + 9×2**。

> 这条链同时**推翻了 §3.1 的两处归因**（见该处勘误）：不是「没声明」，而是「声明被抹掉」；
> 也没有「绕过抑制点的直推路径」（Explore 全仓核查确认整柱推送只有那两个抑制点）。

### 9.2 改动清单

| 文件 | 改动 |
|---|---|
| `network/ChunkAuthorityNotifier.java` | 会话首个视距观测点**不再清空 `pending`**；真实视距变小改用 `pruneOutOfRange`（只剔除越半径项，半径内声明保留） |
| `network/ChunkAuthorityClient.java` | `AUTHORITY_WATCHDOG_MS` 提为 `public`；新增并发 `DECLARED_AT` + `declaredAtMs()`；`handle()` 逐条登记声明时刻（先登记后 resolve）；切维清表；表上限 16384 |
| `network/seedgen/ShadowTrackingSession.java` | `GATE_STARVE_GRACE_MS` 3s → 引用 `ChunkAuthorityClient.AUTHORITY_WATCHDOG_MS`（10s）；`releaseGateStarvation` 把已声明柱的扣留起算点抬到声明时刻 |
| `network/seedgen/ShadowSeedServer.java` | `isGlfwClockFailure` 由「`instanceof NullPointerException` + GLFW/GLX 帧」改为**帧签名**判定（见 9.4） |

### 9.3 实测（4 场，1.21.1 / fabric / classic / Phase I）

| 会话 | 配置 | `hash-hit` | `authoritative-full pull` | `compare-pull` | `starved` | `ShadowPullClient.request` | R1/R2 observed | 封闭空洞 R1/R2 | 判决 |
|---|---|---|---|---|---|---|---|---|---|
| `f1f2_prod1` | 生产 | 26 | 0 | 0 | **0** | 1545（全 1 柱/包） | 1529 / 453 | **0 / 0** | PASS |
| `f1f2_prod2` | 生产 | 34 | 0 | 0 | **0** | 1558 | 1529 / 477 | **0 / 0** | PASS |
| `f1f2_p5` | 接管 | 60 | 0 | 0 | **0** | 1538 | 1529 / 453 | **0 / 0** | FAIL（仅 `PROCESS_FATAL` = 9.4 的 teardown 竞态） |
| `f1f2_p5b` | 接管 | 37 | 1 | 0 | **0** | 1469 | 1529 / 453 | **0 / 0** | PASS |
| 对照：`band1`/`band2` | 接管（修复前） | 442 / 443 | 0 | 0 | 0 | — | 1520 / 444 | **9 / 9** | 曾 PASS |

- 接管态 `p5b` 的 `[SHADOW_TICKET] bound instance` 在场（1 行）⇒ 接管构建确实生效（§六.3 哨兵）。
- **影子端自绘 pull 全面退场**：4 场 `authoritative-full pull` + `compare-pull` = 0（`p5b` 的 1 次是宽限边沿），
  `starved` 恒为 0，装载全部由服务端声明经 `resolve()` 逐柱驱动。
- R2 observed 在 prod2 为 477（>453）：第二轮 OVD/缓存回填量随会话状态浮动，非缺陷。

### 9.4 附带修复：teardown GLFW 守卫的类型绑死（harness）

- **现象**：`f1f2_p5` 收尾被日志审计判 `PROCESS_FATAL` → `RESULT: FAIL`，但 R1/R2 stats 全 true、
  客户端退出码 0、两轮空洞 0。崩溃栈与 09-11 记录的 teardown 竞态**同一条链**：
  `MinecraftServer.haveTime → Util.getNanos → GLX.initGlfw lambda → GLFW.glfwGetTime`，
  发生在 `shadow save completed` → `Render thread: Stopping!` **之后**（GLFW 已销毁）。
- **根因**：`isGlfwClockFailure` 只认 `NullPointerException`，而同一竞态在 Fabric 下以
  **`IncompatibleClassChangeError`**（`KnotClassLoader` 与 LWJGL `CallbackI` 建关期错配）现形 → 守卫漏判 → `LOG.error` → 误 FAIL。
- **改法**：判据改为**帧签名**（同时出现 `GLX` 帧与 `GLFW` 帧），异常类型不再参与；调用方原有
  `isSharedIoPoolShutdown()` 前置条件不变。
- **前置条件已实证**：全量 `client_*.log` 扫描显示 `exited during client teardown`（该守卫的 INFO 分支）
  在 **30+ 场**出现，而 `crashed=1` 仅 3 场（09-11 的 NPE 历史案、`neoforge_I_diag2` 的 NPE、本次 ICCE）
  ⇒ 守卫前置条件成立且在正常收尾中一直在用；历史两例均为 NPE（旧守卫已覆盖），本次 ICCE 是新变体。
- **未直接实测**：`f1f2_p5b` 复跑时该竞态**没有发生**（`crashed=0`），所以本次修复是「按帧签名推证 +
  同窗口同栈的 ICCE 实例」，不是被观测触发的 `teardownExit`。**若要实测触发，需反复重跑收尾竞态。**

### 9.5 F11（P2，性能）→ **已修复；其"已验证"结论在第四轮被降级（载荷维度漏测，见下）**

- **原状**：`ChunkAuthorityClient.resolve()` 对**每条**声明调用 `ShadowPullClient.requestFull(dimension, List.of(pos))`
  / `requestAuthoritativeFull(dimension, List.of(pos))` —— 单元素列表 → **一柱一个 C2S 包**。
  实测每场 `ShadowPullClient.request` = 1469~1558 行，且 `chunksSum ≈ lines`（平均 1.00~1.09 柱/包）。
- **改法（已实施）**：`resolve` 改为返回 `Pull` 裁决（`NONE` / `COMPARE_PULL` / `AUTHORITATIVE_PULL`），
  `handle()` 在**同一条声明包**内把两种裁决各收进一个列表，循环结束后各发**一次**批量请求。
  判定粒度不变（**逐柱**判 hash / 基线，不是按包一刀切），`ShadowPullClient.request` 内部再按
  `ShadowPullRequestC2SPacket.MAX_ENTRIES`（384）分包——一条声明包最多对应 2 个 C2S 包。
- **验收判据**：`ShadowPullClient.request` 行数降到声明包数量量级，`chunksSum` 不变，空洞仍为 0。
- **复测（`1.21.1_fabric_I_f11`，1.21.1/fabric/classic）**：`=== RESULT: PASS ===`，`failures=[]`、`warnings=[]`、
  R1/R2 封闭空洞 **0/0**。
  - 客户端 `[DIAG] ShadowPullClient.request`：**1500 量级 → 157 行**（修复前 §9.5 记 1469~1558）；
    服务端 `[AUTHORITY] send` = **171 行** ⇒ **≈1:1**（修复前约 10:1）。
  - `chunksSum` = **1764**，单包最大 **109** 柱（修复前 `chunksSum ≈ lines`、平均 1.00~1.09 柱/包）。
  - `hash-hit zero-request` 35 行、`[SHADOW_TICKET]` 0 行（接管臂关，符合预期）。
- **⚠️ 验收口径被降级（第四轮补记）**：上面这组读数只覆盖了「包数 / 空洞」两个维度，
  **没有覆盖载荷大小**——而载荷恰恰是它引入缺陷的地方：1.20.1 复跑时聚合出的 compare 请求把
  C2S 顶过 vanilla 的 32 KiB 上限，客户端被踢（见 §五 **F16**）。故 F11 的结论应读作
  「包数目标达成、空洞无回归」，**不**读作「已验证无副作用」。
- **风险（已规避）**：批量后分组内 hash 未知/不等的判定仍是逐条语义，需保证「有基线→compare、无基线→权威 FULL」
  两个分组都按柱判定（不能按包判定），否则会退化成整包一刀切 —— 由 `resolve` 的返回值保证。

### 9.6 教训补充（补进 §六）

- **「已发出」≠「已到达」**：从产生到消费之间的**任何一处静默清空**都是永久丢失——而这类丢失在
  「有没有洞」的门禁下**看不见**（本轮的 3x3 就是这么活过 8 个会话的）。写管道时自检一句：
  **这条数据从生产到消费，中途有没有谁可以静默把它丢掉？**
- **守卫按「异常类型」判定 = 判据绑错维度**：同一故障会以不同异常类型现形（NPE / ICCE / ……），
  稳定的判据是**帧签名 / 调用链**。9.4 就是这个反例。
- **让位门的宽限必须 ≥ 声明流的存活窗口**：否则「兜底」被当成常态，`starved` 失去信号价值
  （3s 宽限下 `_p5fix1` 每场 15 次假饥饿）。

### 9.7 F1/F2 最小证据集（2026-09-13 第三轮，8 场）

| 会话 | 版本/加载器/场景 | R1/R2 空洞 | `starved` | 影子自绘 pull | 声明条目总数 | 首个声明包 | 判决 |
|---|---|---|---|---|---|---|---|
| `f1f2_prod1` | 1.21.1/fabric/classic | 0 / 0 | **0** | 0 | 1982 | `entries=9` | PASS |
| `f1f2_prod2` | 同上（跨轮） | 0 / 0 | **0** | 0 | — | — | PASS |
| `f1f2_p5` | 1.21.1/fabric/接管 | 0 / 0 | **0** | 0 | 1982 | `entries=9` | FAIL（仅 teardown 竞态，见 9.4） |
| `f1f2_p5b` | 同上（守卫修复后） | 0 / 0 | **0** | 1 | 1982 | `entries=9` | PASS |
| `f1f2_move` | 1.21.1/fabric/classic `-MoveSeconds 12` | R1 0（P1 最大 2） | 0 | 1 | — | — | FAIL（移动场景门禁口径，见 **F14**） |
| `f1f2_dim` | 1.21.1/fabric/dimension | R1 **0**；R2 崩 | — | — | — | — | FAIL（flaky，见 **F13**） |
| `1.20.1_fabric_I_f1f2` | **1.20.1**/fabric/classic | 0 / 0 | **5** | **16** | 1982 | `entries=384 hashed=40` | PASS |
| `1.21.1_forge_I_f1f2` | 1.21.1/**forge**/classic | 0 / 0 | **4** | **4** | 1982 | `entries=9` | PASS |
| `1.21.1_neoforge_I_f1f2` | 1.21.1/neoforge/classic | 0 / 0 | 0 | 13 | **0** | — | ~~机制未生效~~ → **F12 已修**：`f12fix` 复测 0 空洞、声明 1982、`server_push` 3068→0 |

**可下的结论**：

1. **空洞项全绿**，含此前必然丢柱的 1.20.1（其首个待发批 **384** 条 —— 旧代码在那个视距观测点会一次抹掉 384 条，
   远比 1.21.1 的 9 条严重）。F1 的修复在全部证据点成立。
2. **声明集合是确定且完备的**：`entriesSum` 在 1.21.1/fabric、1.21.1/forge、1.20.1/fabric 上**恒为 1982**
   （= 164 场基线的 1964 + 找回的 9×2）⇒ 与加载器/版本无关。
3. **但影子端自绘 pull 只在 1.21.1/fabric 完全退场**（0 批 / 0 饥饿）。1.20.1/fabric = 16 批 / 5 次饥饿；
   1.21.1/forge = 4 批 / 4 次饥饿 ⇒ **声明流未在这些点上于看门狗窗口内覆盖全部可见柱**，
   `starved` 这个信号在说真话（不是假饥饿）。neoforge 在 F12 修复后复测（`f12fix`）为 **4 批 / 3 次饥饿**——
   同样**未完全退场**。（修复前 neoforge 连机制都没跑，见 F12。）
4. ⇒ **「达到替换条件」的答案：没有。** F1 消除的是"永久空洞"这一类硬缺陷；要让影子端自绘 pull 真正可裁，
   还差「声明流在全部版本/加载器上覆盖完备且及时」（本轮量化出 1.20.1 / forge / neoforge 的缺口）
   + 接管臂自身的 F5/F7。

### F14（P2，第三轮新增）→ **已修复（口径）**：`-MoveSeconds > 0` 的会话在 `classic` 门禁下永不可能 PASS

- **现象**：`-MoveSeconds 12` 的会话被判 `TRACE_EXPECTED_NOT_PRESENT`（R1 405 / R2 169），配
  `TRACE_READY_NOT_APPLIED` + `SPATIAL_SNAPSHOT_INCOMPLETE`。成因是门禁的候选集口径：
  `expected = networkReceived` 假设「收到即常驻」，而移动场景走开后会正常 `CHUNK_UNLOAD`（本场 472 次）。
- **既有性**：**6/6 场历史移动会话全部 FAIL**，且多为同一失败码（`bandmove1`/`nticketmove1`/`otmove1`/
  `otmove4`/`move2`…）⇒ **移动场景实际上从未被门禁覆盖过**（与 §六.8「移动场景单轮跨配置对比不可靠」同源）。
- **改法（已实施，选了「透传 MoveSeconds」这条）**：
  - `scripts/runtime-smoke-test.ps1`：result JSON 新增 `MoveSeconds = $MoveSeconds`。
  - `scripts/smoke/analyzer.py`：`mobile_session = MoveSeconds > 0` 时，把**驻留口径**的两项
    （`TRACE_EXPECTED_NOT_PRESENT` / `TRACE_READY_NOT_APPLIED`）降为 `skipped` 运行内诊断
    （`_MOBILE_TRACE_DIAGNOSTIC_CODES`），并把**投递链**缺口（`TRACE_RECEIVED_NOT_INJECTED` /
    `TRACE_INJECTED_NOT_READY`）明确拆出来**继续把守**——它们与「收到后是否常驻」无关。
  - **`TRACE_ENCLOSED_HOLE`（真正的虚空门禁）不受此影响**：它按「被已持有柱完全包围」判定，
    与走开无关，本场它干净（P0 = 0），历史移动场景则都有 9 格 P0。
- **验收判据**：历史移动会话在新口径下不再因"走开"而亮 P0；真空洞（如历史 3x3）仍然亮。
  已由 4 条单测覆盖（`MobileSessionTraceTest`）：静止会话驻留缺口仍 P0、移动会话降为诊断、
  移动会话投递链缺口仍 P0、移动会话成片封闭空洞仍 P0。`python -m unittest scripts.smoke.test_analyzer` = **23 tests OK**。
- **历史回放（就地验证口径）**：对 §9.7 里那场 `1.21.1_fabric_I_f1f2_move` 的 result JSON 做两次判定——
  按记录原样（无 `MoveSeconds`）**FAIL**（唯一 failure = `TRACE_EXPECTED_NOT_PRESENT`，即 F14 的假失败）；
  手工补 `"MoveSeconds": 12` 后 **PASS**（该码与 `TRACE_READY_NOT_APPLIED` 一起降到 `skipped`）。
  两次都**没有** `TRACE_ENCLOSED_HOLE`（本场 P0 = 0）⇒ 新口径只摘掉假失败，没放过真空洞。
- **端到端复测（`1.21.1_fabric_I_f11move`，1.21.1/fabric/classic `-MoveSeconds 12`）**：`=== RESULT: PASS ===`，
  `failures = []`、R1/R2 封闭空洞 **0/0**（R1 `observed 1529`、R2 `observed 518`）、`MoveSeconds = 12` 已随
  result JSON 落盘。warnings 仅 `SPATIAL_SNAPSHOT_INCOMPLETE`（P1，移动中视图外沿的既有噪声，非本轮引入）。
  **⚠️ 如实说明**：本场 `expectedNotPresent` 两轮都是 **0**，即**这次压根没触发降级路径**——
  它证明的是「新口径不引入回归、移动会话不再假 FAIL」，**不是**降级确实生效。降级生效的证据在
  「历史回放」与单测两处（下）。
- **已知限制（如实记）**：口径靠 `MoveSeconds` 字段，**历史**移动会话的 result JSON 没有这个字段
  （客户端日志也不打印该属性，无法从日志反推），故**不会被自动重判**——要回放历史场次需手工在该 JSON 里补
  `"MoveSeconds": 12`。本轮按"显式信号优先"取值，没用 `CHUNK_UNLOAD > 0` 之类的间接推断（会误伤任何走过路的会话）。

### 9.8 附带硬化：待发缓冲溢出的静默丢声明（同一缺陷类）

`ChunkAuthorityNotifier.MAX_PENDING_PER_PLAYER` 溢出分支原本是 `return`（**静默丢声明**，且丢的是刚入队的、
原版已计 ACK 不再重发的那条 ⇒ 与 F1 同一缺陷类），而旧注释「客户端漏收时靠 self-heal 扫描/pull 补齐」是**错的**。
本轮改为：计数 `PENDING_OVERFLOW` + 首次触发 `Constants.LOG.warn`（正常路径不可达，一旦出现即为真缺陷信号），
并把注释改成如实描述。实测 8 场 `AUTHORITY pending overflow` = 0 —— 与「不可达」的判断一致。

### F15（P0）→ **已定性并修复（第三轮会话）：与 F1 同一缺陷类，丢在客户端接收侧**

- **修法（已实施，`ChunkAuthorityNotifier`）**：按「**快照 = 把累计声明集合按当前视距形状整体重推**」实现——
  `PlayerState.declared`（本维度累计已声明集合）+ `RESEND_SETTLE_MS = 3s`：加入世界 / 切维 settle 后重推一次；
  真实视距变更时也重推（`requeueDeclared`，用 `ChunkShapeCompat` 裁剪越界项以保持集合有界）。
  重推**幂等**：客户端 `resolve()` 对已持有柱零动作、在途柱由 `markPullInFlight` 去重。
  客户端侧只加**丢弃计数留痕**（`DROPPED_NOT_READY` + `[AUTHORITY] declarations dropped before level ready`），
  **不做本地重试队列**——重推已让它自愈。
- **为什么不是「挂起 + 重放」**（原先的提案）：重放只是把丢失窗口盖住；**重推是自愈**，同时覆盖丢包 / 握手竞态 / 切维，
  且复用现有 `snapshot` / `epoch` 字段与幂等的 `resolve()` —— 无新协议、无新队列、无新的跨线程状态。
  代价是每会话多 1~2 次全量重推（实测 `entriesSum` 2046 → 3117~3267，`snapshot=true` 2 → 4），
  且只在 join / 切维 / 视距变化时发生，**非周期**（稳态零开销）。
  **注意**：必须按当前形状裁剪——通知器按设计不跟踪 leave（leave 交原版 Forget），累计集合会随移动无限增大，
  不裁剪就会把越界柱声明出去（客户端 pull → 服务端 range 拒绝）。
- **验收判据**：⚠ **部分达成**（第四轮补记后仍为部分）—— `1.21.11/neoforge` 累计 **5 轮全 PASS**
  （`f15a` / `f15b` / `f15c` / `f15d` / `f15e`）、`TRACE_ENCLOSED_HOLE = 0`、空洞 0/0；
  **但这 5 轮的丢弃计数全部为 0**（`[AUTHORITY] declarations dropped before level ready` 一行都没出现），
  所以「丢过 → 被治好」的现场链路**仍未拿到**（5 场 0 次，窗口没撞上）。
  当前证据 = 「重推确实在跑（每轮 `snapshot=true` ×4、`entriesSum` 抬高）」+ 按构造覆盖该窗口 + 幂等性设计。
  **判定：F15 残项保持未闭**——继续盲跑性价比低；要拿到现场证据需能**定向制造** level 未就绪窗口（例如临时注入）
  或换一个更容易撞窗的场景，这条留给下一轮定。

<details><summary>原始定性过程（含一次自我推翻）</summary>

- **现象**：`1.21.11_neoforge_I_f4` R2 `TRACE_ENCLOSED_HOLE`（largest **4**，components `[4,3,1]`，共 8 格）；
  同配置重跑 `f4b` **0 空洞**（声明条目两轮均 2046）⇒ **flaky**。
- **❌ 已推翻的初判**：曾判为「窗口外沿 OVD 环 best-effort 稀疏、门禁过严（F14 同族）」。
  **计算推翻**：玩家柱 (-3,-1)、R2 服务端 VD=10 ⇒ 权威窗谓词（原版 `ChunkTrackingView.isWithinDistance`，`contains` 取 `off=2`）
  `max(0,|dx|-2)² + max(0,|dz|-2)² < 100`；8 格代入判定值 **50 / 52 / 61 / 68 / 72 / 72 / 73 / 80**，
  **全部 < 100 ⇒ 全在权威窗内**。`ovdMiss = 17` 是 OVD 环自身指标，这 8 格不属于它。⇒ **不是 OVD 边界产物，是真·窗内空洞。**
- **机理（与 F1 同族，换到客户端接收侧）**：
  1. 服务端侧完好——声明集合确定（`entriesSum` 2046 = 1573+473，加载器无关），R2 声明 473 = 权威窗全窗，这 8 格在窗内 ⇒ **服务端应当声明了**；
  2. 客户端侧零踪迹——8 格坐标在客户端日志出现 **0 次**（无 `[CHUNK_APPLY]` / `shadow_attempt`），
     且 `Request rejected` / `Failed to apply FULL` / `Cache baseline unavailable` / `drop response` **全为 0**
     ⇒ 客户端**从未请求**它们（不是请求失败，是根本没被驱动）；
  3. 存在**静默丢弃路径**（代码实证，`ChunkAuthorityClient.handle()`）：
     ```java
     if (minecraft == null || minecraft.level == null) return;   // ← 加入世界窗口期内静默丢弃，无重试
     if (clientDim == null || !clientDim.equals(packet.dimension())) {
         Constants.LOG.debug("Hassium: drop authority edges ...");  // ← debug 级，默认不可见
         return;
     }
     ```
  4. **不可恢复**：vanilla 已把这批柱移出 `pendingChunks` ⇒ 服务端**永不再声明**；客户端让位门已关
     ⇒ 影子端自绘 pull 被抑制 ⇒ 该柱再无来源（**与 §9.1 的第 5/6 步完全同构**）；
  5. flaky 吻合——是否撞上「level 未就绪」窗口取决于时序，所以两轮同配置一丢一不丢。
- **结论**：**F1 只修了服务端发送侧的缓冲作废；客户端接收侧的静默丢弃没修。** 门禁（F10/F1）没错，
  它抓到的是一次真实的永久空洞。
- **修法（候选，需确认范围）**：
  - `ChunkAuthorityClient.handle()` 不得静默丢：**早退改为挂起到 `level` 就绪后重放**（小队列），
    至少也要计数 + `warn`；
  - 让位门的**武装时机**应与「声明流已被消费」绑定（或服务端对已声明集合做有界周期的重声明），
    否则任何一次接收侧丢失都不可恢复——这条与 F2 的契约同源；
  - `TRACE_*` 类门禁在声明驱动路径下**结构失明**（R2 实测 `networkReceived = 0`、`shadowInjected = 0`，
    故 `expectedNotPresent` 恒为 0）⇒ **封闭空洞门禁是当前唯一能看见这类空洞的门禁**，其可靠性已成为硬依赖。
- **验收判据**：连续 ≥2 轮 1.21.11/neoforge 冒烟 `TRACE_ENCLOSED_HOLE` = 0，且新增的「接收侧丢弃」计数 = 0。

（**已按上面的「重推 = 自愈」实施并复测**：`f15a` / `f15b` 两轮 `TRACE_ENCLOSED_HOLE = 0`、
`snapshot=true` 2→4、`entriesSum` 2046→3117/3267；两轮丢弃计数均为 0 = 未撞上窗口。）

</details>

---

### F16（P0，第四轮复跑新增）**F11 的聚合把 C2S 载荷顶过 vanilla 的 32 KiB 硬上限**（自己引入、自己抓到）

- **现象**：`1.20.1_fabric_I_seedgen_f4`（F4 余项复跑）R1 客户端被踢：
  `Player527 lost connection: Internal Exception: ... IllegalArgumentException: Payload may not be larger than 32767 bytes`，
  门禁报 `SMOKE_PASS_MARKER_MISSING` + `PROBE_MISSING`（场景没跑完就被断开）。
- **根因链**：
  1. `ShadowPullRequestC2SPacket` 的 `MAX_ENTRIES = 384` 是**条数**上限，不是**载荷**上限；
  2. 单柱的载荷是「每段 8 字节 hash + 非零段再带 `PLANE_COUNT(48)` 个 int」⇒ 单段 **200 字节**、
     单柱最多 64 段 ⇒ **单柱可达 12.8 KB**；
  3. vanilla 的 C2S 上限是 `ServerboundCustomPayloadPacket.MAX_PAYLOAD_SIZE = 32767`
     （**S2C 是 1 MiB**，这个不对称是关键）；超限由 vanilla 解码器抛异常并踢掉客户端；
  4. F11 之前每柱一条请求（1 条/包）永不触顶；**F11 聚合后**才可达 ⇒ 这是本轮**自己引入**的缺陷。
- **实测触发点**：客户端日志 `chunks=384 false`（空基线，极小，安全）与 **`chunks=160 true`**
  （带分段基线的 compare 请求）——超限的就是后者；1.21.1 那两轮最大批次只有 109，所以没暴露。
- **修法（已实施）**：
  - `ShadowPullRequestC2SPacket` 新增 `MAX_PAYLOAD_BYTES = 32767` 与
    `batchesByEncodedSize(dimension, entries, maxBytes)`——**按实际 `encode()` 后的字节数二分切分**
    （每批同时 ≤ `MAX_ENTRIES`），单条自身超限且不可再切时原样返回、由调用方决定策略；
  - `ShadowPullClient.request()` 改用它，预算 = `MAX_PAYLOAD_BYTES - 1 KiB`
    （余量给 forge `ShadowPullRequestWrapper` / neoforge `ByteArrayPayload` 的封装）；
  - 单测 `ShadowPullPacketTest.batchesAreBoundedByEncodedSize`：40 条「每柱 64 段」的重条目被切成多批
    且每批 `encode()` 后都在预算内；同样 40 条无分段载荷的轻条目仍是 **1 批**（证明切分由**字节**驱动而非条数）。
- **回归哨兵**：`1.20.1_fabric_I_seedgen_f4b`（同配置复跑）——见下。
- **回归哨兵（已跑，`1.20.1_fabric_I_seedgen_f4b`，同配置同场景）**：`=== RESULT: PASS ===`、`failures=[]`、
  `warnings=[]`、R1 空洞 **0**（`observed 1529`）。服务端日志里唯一的 `lost connection` 是场景收尾的正常
  `Disconnected`，**无** `Payload may not be larger` 命中。
  客户端最大批次：空基线那批仍是 **384**（空条目 ~14 B/柱 ≈ 5.4 KB，本来就不用切），
  **带分段的批次最大 190**（切分由字节驱动，所以条数不再是固定数——这正是"按条数猜"会错的地方）。
- **教训（补进 §六）**：**「条数上限」不是「载荷上限」。** 引入任何**聚合/批处理**时，必须同时引入**字节**预算，
  并确认**两个方向的上限不一样**（本例 C2S 32 KiB vs S2C 1 MiB，只有一侧会踩）。F11 的原始验收只看
  「行数降下来、空洞为 0」，**结构上看不见载荷大小**——矩阵复跑（F4 余项）才是抓到它的那条路径。
- **⚠️ 未定性遗留（F16 之后新出现，见 F17）**：翻回 `P5_TAKEOVER=false` 后的生产态哨兵
  `1.21.1_fabric_I_p5off_sentinel` **原生终止**（见 §五 F17）。**不能**因为「F16 是纯缓冲区算术」就假定无关——
  结论要由读数定。

---

### F17（P1，第四轮新增，**未定性**）F16 修复后，生产态 1.21.1/fabric classic 出现一次原生终止

- **现象**：`1.21.1_fabric_I_p5off_sentinel`（`P5_TAKEOVER=false`、classic、与 HEAD 逐字一致的代码）——
  客户端日志**停在 1267 行**（正常同类场次 12k~18k 行），最后一行是
  `[SHADOW_TRACK] sweep missing=128 localGen=false center=(-2,0) radius=20`；
  **无 Java 异常、无 `Stopping!`、无 `hs_err`、无 crash-report**（`fabric/run` 下唯一的 `hs_err` 是 8/26 的服务端残留）。
  门禁：`PROBE_MISSING` ×2 + `SMOKE_PASS_MARKER_MISSING` + `CLIENT_EXIT_NONZERO`。
- **为什么不能直接归到已知 flaky**：今日（2026-09-13）同一日志目录里，**原生终止全部集中在 move / 接管臂场景**
  （`move` / `move2` / `otmove1..4` / `nticketmove1` / `bandmove2`），**classic + `P5_TAKEOVER=false` 在 F16 之前
  一次都没有过**。F16 之后第一次跑（就是本场）就出现 ⇒ 时间上可疑。
- **反向证据（不足以结案，但要一起看）**：
  - 同批第 2 场 `p5off_sentinel2`（**同代码同配置**）**PASS**（`round1=True round2=True`、空洞 0）⇒ 不是必现；
  - F16 之后接管态 classic 在 **1.20.1/fabric、1.21.1/forge、1.21.11/neoforge 三个 loader 上全部 PASS**
    （`p5_f5a` / `f5b` / `f5c`）⇒ 不是「F16 后 classic 全挂」；
  - F16 改动面是 `ShadowPullClient.request` 的分批 + `ShadowPullRequestC2SPacket` 的纯编码测量
    （无反射、无 JNI、无 Unsafe）——**从代码面上不支持**「引起原生终止」，但这是推理、不是读数。
- **速率读数（已跑）**：同一代码同一配置 `1.21.1/fabric + classic + P5_TAKEOVER=false` 共 **6 场**：
  `p5off_sentinel` **FAIL（原生终止）**、`sentinel2` / `sentinel3` / `s4` / `s5` / `s6` 全 **PASS**
  ⇒ **1/6 ≈ 17%**。对照：**F16 之前**同配置（今日）约 **10 场**（`f1f2_prod1/prod2`、`band1`、`band2`、
  `nticket1`、`ot1`、`p5fix1`、`p5off1`、`p5off2`、`f11`）**0 次**原生终止。
- **判定（如实）**：`1/6` vs `0/10` 这个样本量**不足以区分**——Fisher 精确检验 p≈0.38，不显著。
  ⇒ **F16 与本终止的因果关系「不支持、也不排除」**，本轮**不结案**。
  同日其它原生终止（`otmove1..4` / `nticketmove1` / `bandmove2` / `move` / `move2` / `dimf3` / `f1f2_dim`）
  说明 1.21.1/fabric 上**本来就有**这类无 Java 痕迹的终止，故「与既有 flaky 同族」是**相符**解释，**不是证明**。
- **下一步（不要再盲跑）**：该速率下 A/B（回退 F16 再跑 6 场）同样分辨不出（`0/6` vs `1/6` 更弱）。
  真正能定性的是**拿到原生层证据**：给客户端 JVM 打开崩溃产物（`-XX:+CreateCoredumpOnCrash` / Windows WER
  local dump），下次撞上就有现场；否则只能是「低频未解释的原生终止」。
- **结论**：**未定性，按 P1 挂着（低频）**。在此之前，不要把 F16 记成「已完全验证无副作用」——它被证明的只有
  「修好载荷越界」+「1.20.1 seedgen 与三 loader 接管态无回归」。

---

### 9.9 F4 收敛矩阵（2026-09-13 第三轮，按 `version-segments` 收敛）

**编译层：七锚点 × `builds_for` 全加载器全部通过**（E/F/G/H 为本轮新跑，A/D/I 由本轮冒烟与 F12 验证附带）：

| 段 | 锚点 | 加载器 | 结果 |
|---|---|---|---|
| A | 1.20.1 | fabric, forge | ✅ |
| D | 1.21.1 | fabric, forge, neoforge | ✅ |
| E | 1.21.2 | fabric, neoforge | ✅ |
| F | 1.21.5 | fabric, forge, neoforge | ✅ |
| G | 1.21.6 | fabric, forge, neoforge | ✅ |
| H | 1.21.9 | fabric, forge, neoforge | ✅ |
| I | 1.21.11 | fabric, neoforge | ✅ |

⇒ 「三个版本证明全版本适配」在**运行时**维度成立（`docs/version-segments.md` 明载「运行时验证优先级：1.20.1 → 1.21.1 → 1.21.11」）；
**编译**维度仍需七锚点各自过一遍——段边界的 API 悬崖（`SerializableChunkData` / CompoundTag / `level()` / `PalettedContainerFactory` /
Identifier）不会在 A/D/I 上暴露（仓库自身反例：1.21.10 forge 能到 `Done`、1.21.11 forge 起不来）。两者合起来才是「全版本适配」的实证。

**运行时：3 版本 × `builds_for` = 9 场组合**

| 版本 / 加载器 | 判决 | R1 / R2 observed | 空洞 | `starved` | 声明条目 | 首个声明包 |
|---|---|---|---|---|---|---|
| 1.20.1 / fabric | PASS | 1551 / 573 | 0/0 | 5 | 1982 | `entries=384` |
| 1.20.1 / forge | PASS | 1600 / 550 | 0/0 | — | 1982 | `entries=384` |
| 1.21.1 / fabric | PASS ×2 | 1529 / 453 | 0/0 | **0** | 1982 | `entries=9` |
| 1.21.1 / forge | PASS | 1529 / 453 | 0/0 | 4 | 1982 | `entries=9` |
| 1.21.1 / neoforge | PASS ×2 | 1529 / 477 | 0/0 | 2~3 | 1982 | `entries=9` |
| 1.21.11 / fabric | PASS | 1573 / 473 | 0/0 | **0** | 2046 | `entries=9` |
| 1.21.11 / neoforge | PASS / **FAIL**（flaky，F15） | 1573 / 454~465 | 0 / **4** | 4~5 | 2046 | `entries=9` |

**观察**：`entriesSum` 在每一版本上恒定（1.20.1 / 1.21.1 = **1982**；1.21.11 = **2046** = 1573+473），且**加载器无关**
⇒ 声明集合由（版本可见窗 × 轮次）唯一决定。影子端自绘 pull 完全退场的只有 **1.21.1/fabric 与 1.21.11/fabric**（`starved` = 0 且自绘 0 批）。
**注意**：本轮 3 版本矩阵是**收敛口径**（F4 的"全矩阵"按 `version-segments` 收缩）——它照样抓到了 F15，即收敛没有牺牲发现力。
