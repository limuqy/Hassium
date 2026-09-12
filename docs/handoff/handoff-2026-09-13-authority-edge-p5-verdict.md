# 交接文档：权威边沿（authority edge）现状 + P5「裁掉影子端自绘选柱」判定 + 落位点空洞竞态

日期：2026-09-13 ｜ 分支：`master` ｜ HEAD：`4cbee50`（**工作区未提交**：27 个已改文件 + 8 个新文件）
本文档读者：下一个接手会话的 AI / 开发者。
配套阅读：[`client-chunk-flow-handover.md`](client-chunk-flow-handover.md) §9.5–§9.9（本次会话的原始推演与三次自我推翻）、
[`chunk-cache.md`](chunk-cache.md) §14、[`runtime-smoke-test.md`](runtime-smoke-test.md) 门禁全集。

---

## 一、一句话状态

**权威边沿在「内容裁决」层已经可用并默认开启；在「选柱 / 装载」层没有接过班——影子端自绘的 pull 驱动仍是装载主力，不可删。**
`P5_TAKEOVER = false`（收尾态）。本次会话新增了两样常驻资产：**封闭空洞冒烟门禁**与**让位门静默丢数据的兜底**。

---

## 二、已落地（全部有实测证据）

### 2.1 封闭空洞门禁（`TRACE_ENCLOSED_HOLE`）

- 口径：`scripts/smoke/analyzer.py` → `_enclosed_holes` / `_enclosed_components` / `_hole_check`。
  取 `clientCache.actualPresent` 的包围盒外扩一圈，从盒外角 4-连通洪水填充；**填不到的缺席格 = 被围住的洞**，
  再算 4-连通分块大小。
- 分级（**仅 classic**）：最大分块 **≥4 格 → P0** `TRACE_ENCLOSED_HOLE`；1–3 格 → P1 `TRACE_ENCLOSED_HOLE_SMALL`。
  非 classic 场景按场景排除（`dimension` / `modcompat` / `seedgen` / `ovdgen` 的盘回填不走同一交付契约）。
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

**因此**：`band1` 这条记录（§9.8 当初被标注为"读数作废"的配置）现在有了新含义——**它的配置就是生产配置**，
它照样丢了 9 柱。**这个竞态在生产配置下同样成立**，本次让位门改动是对生产缺陷的修复，不只是为实验服务。

### 3.2 「影子端选柱可以裁掉」这个 P5 前提**不成立**

把空洞补上之后，填洞的仍然是影子端自绘的 pull（让位门补发），**而不是服务端声明**。
要裁掉影子端自绘选柱，前置条件是先把声明集合补全（见 §五 F1），而不是在客户端做减法。

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

### F1（P0，阻塞 3.2 的收尾）把「登录期已推柱」补进服务端声明

- **目标**：让声明集合覆盖客户端可见窗的**全集**，使影子端自绘 pull 真正可以退场。
- **为什么**：现在声明只覆盖"服务端本来会推送的那批"，登录期直接推、之后不再声明的柱不在其中。
  这就是落位点 3x3 的唯一成因，也是接管无法收尾的唯一硬前置。
- **起点**：`ChunkAuthorityNotifier`（在整柱推送抑制点发声明）＋ 服务端登录期推送路径
  （`ServerChunkPushManager` / `MixinPlayerChunkSender` / `MixinServerPlayer`）——需要覆盖"绕过抑制点直接推"的那批。
- **验收判据**：接管态（`P5_TAKEOVER=true`）下 **`authority gate starved` 次数 = 0** 且两轮封闭空洞 = 0。
  即：影子端的 pull 一次都不需要补发。**这条判据是这次新增的**——以前只能看"有没有洞"，看不出"是不是兜底救了场"。

### F2（P0）给让位门一个确定的开合语义

- **目标**：把「门什么时候关」从竞态变成契约。
- **为什么**：现在同一二进制/场景/存档，两轮机理不同（§3.1），判决不可复现；
  §9.5 的"移动场景跨轮方差"警告要升级成"**同配置跨轮机理不同**"。
- **候选方向**（择一，需窄化）：登录后 N 秒内不让位；或 `bootGridArmed` 排空之前不让位。
- **起点**：`ChunkAuthorityClient.pullEmissionSuppressed()` + `ShadowTrackingSession.emitPullGroups`。
- **验收判据**：连续两轮同一场景的 `hash-hit / authoritative-full pull` 落在同一量级，且 `starved` 计数稳定。

### F3（P1，最可能有真货）解释非 classic 场景的 10 个封闭空洞样本

- **现象**：门禁按场景排除了它们，但**没有解释**。以下是 **probe 级**扫描（`build/smoke-test/probe/*/round*.json`
  逐份跑 `_hole_check`）按最大分块降序的结果：
  `1.21.1_neoforge_I_dimension` **303**、`1.20.1_fabric_I_ovdgen2` 35、`1.20.1_fabric_I_dimension` 23、
  `1.21.1_fabric_I_modcompat_nomods` 15、`1.21.11_fabric_I_baseline2` 13、
  `1.21.11_fabric_I_modcompat_b` 5、`1.21.8_fabric_I_seedgen` 4、`1.21.6_fabric_I_seedgen` 1、
  `bench_1_21_1_mods` 2、`1.21.1_fabric_I_modcompat_nomods2` 1。
- **为什么**：303 格的**成片**空洞很可能不是采样边缘而是真缺陷（dimension 场景的切维/flush 时序、
  modcompat 的注册时序）。这条是本次新增门禁带来的**新信息**，白捡的。
- **起点**：`scripts/smoke/analyzer.py::_hole_check` + 对应 `build/smoke-test/probe/<id>/roundN.json`。
- **验收判据**：每个样本归到"真缺陷（→ 修）"或"采样边缘（→ 在 analyzer 里写明为什么可以排除）"二选一，不留悬案。

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

### F6（P2）`ShadowTicketDriver` 去留决策

- **现状**：310 行，`P5_TAKEOVER=false` 下**一行不执行**（`consumeOnShadowLoop` 首行 `!ENABLED` 返回；
  生产轮 `[SHADOW_TICKET]` 实测 0 行），却挂在两个生产调用点上。
- **建议删除**：重测的前置是 F1（服务端先补声明），到那时这套环带几何很可能要重设计，留着未必能复用；
  git 留得下历史。若决定保留，至少把两处调用点注释成"实验臂，默认关闭"。

### F7（P2，非阻塞）P5-5 收尾 `saveAll` 停滞归因

- **现象**：接管开启时 R2 收尾 `saveAll` 停在 seq 1 → 等待超时 → harness 强退 → `0xC0000409`
  （`bandmove2` / `otmove4`）；接管关闭时均正常（含本次 `_p5fix1`）。
- **未定**：候选是「`FORCED` 票让柱常驻、改变了 `saveAll` 的等待条件」与「harness 强退本身」两种，**未做分离实验**。
- **起点**：`build/smoke-test/logs/client_*.log` 的 `save wait timed out (seq still N)` +
  `runtime-smoke-test.ps1` 的 save wait 段 + `ShadowSeedServer.saveAll`。
- 因接管默认关闭，该停滞不进生产路径；**它仍然是"未完成"，不是"已完成"**。

### F8（P3）两个既有的小缺口

- `1.20.1_fabric_I_move2` R1 有 1 格封闭空洞（P1，未定性）。
- 移动场景的既有 3 柱缺口 `(-4,-2) (-3,-2) (-2,-2)`（§9.8 记，与驱动无关，全日志 0 次出现）——解释或判定可接受。

### F9（P3，文档）§9 系列该整章重写了

`client-chunk-flow-handover.md` §9.5 → §9.9 之间**有三次自我推翻**（作废读数、配错开关、接管相关性记错）。
现在读起来会被中间那些作废结论误导。建议下次收尾把它整章重写成一份「权威边沿现状 + 已知竞态 + 覆写清单」，
把作废过程压成一条时间线附录。

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

- [ ] 读 §三 三小节（结论），再决定要不要读 §9 系列的原始推演
- [ ] 确认 `P5_TAKEOVER = false`、`common:compileJava` 通过、`python -m unittest scripts.smoke.test_analyzer` 通过
- [ ] 决定 F6（`ShadowTicketDriver` 删除 / 保留）
- [ ] F3 / F4 是**不需要动生产代码**就能推进的两条，建议先做
- [ ] F1 / F2 是真正阻塞"裁掉影子端自绘选柱"的两条，动手前与用户确认范围
