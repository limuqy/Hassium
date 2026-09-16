# 交接：往返飞行「已缓存区块变黑块」（flyroundtrip 专项）

状态：**交付侧护栏已实施并实测（部分修复）；上游「影子端半成品天光」未修**（保留两条下一步，见 §8）
日期：2026-09-16
定位：客户端光照流（`docs/client-chunk-light-flow.md` 域）／区块核心交付侧
症状原文：「飞行测试，从出生点飞出去再飞回来，出生点已缓存区块出现大片黑块」

一句话结论：黑块不是「光包把光算错了」，而是**影子端在重算/重交付窗口里只算了一半的天光被当成权威值下发**；
已在交付侧把「半成品/空 section 不得当真值下发」全部堵掉（`先亮后黑` 回归柱 5→0，两次复跑 0/0，一次 5），
病灶本身（影子端算完之前就交付）仍在，见 §7/§8。

---

## 1. 症状与复现

- 触发路径：进服 → 飞离出生点（视距外卸载）→ 飞回（重新投递 `redeliver / publishCached / 两阶段光照`）。
- 专项场景（本次新增）：`common/src/main/resources/hassium/smoke/scenario/flyroundtrip.scenario`
  join → settle(`${round1WaitMs}`) → `fly seconds=${moveSeconds} tag=OUT_` → `wait ms=${moveWaitMs}` →
  `command text="tp @s ~ ~ ~ ~180 ~"`（原地掉头）→ `fly seconds=${moveSeconds} tag=BACK_` →
  `wait ms=${moveWaitMs}` → `wait ms=${dimWaitMs}` → `dump label=ROUND1 round=1` →
  `assertProbe key=counters.clientDarkLightProbeChunks op=eq value=0` → `exit rounds=1`。
- 配置档：`scripts/smoke/profiles/flyroundtrip.profile.properties`（`chunk.seedGenEnabled=false`、
  `debug.lightVerify=true`、`debug.chunkApplyLogging=true`、`debug.asyncLogging=true`）。
- 命令（**必须带 `-MoveSeconds > 0`**，=0 只有 settle，不构成往返）：

```powershell
.\scripts\runtime-smoke-test.ps1 -Ver 1.21.1 -Loader fabric -Phase I `
    -SessionId "1.21.1_fabric_flyrtN" -Scenario flyroundtrip -MoveSeconds 20
```

- 已知脚本坑：`fly` **非阻塞**（只设 `moveUntilMs` 就返回 DONE），后面必须跟 `wait`，否则下一段 `fly` 会覆盖上一段；
  转向要用相对 yaw（`tp @s ~ ~ ~ ~180 ~`），写绝对值会转到世界朝向导致「没真往返」。

---

## 2. 判据基础（vanilla 语义，已核实；1.21.1 mojmap）

`ClientboundLightUpdatePacketData`（构造器 + `prepareSectionData`）：

```java
for (int i = 0; i < lightSectionCount; i++) {
    if (skyMask == null || skyMask.get(i))      // ← 传入掩码只决定「检查哪些 section」
        prepareSectionData(pos, engine, SKY, i, this.skyYMask, this.emptySkyYMask, this.skyUpdates);
}
private void prepareSectionData(...) {
    DataLayer layer = engine.getLayerListener(layer).getDataLayerData(SectionPos.of(pos, min + i));
    if (layer != null) {
        if (layer.isEmpty()) emptyMask.set(i);   // ← 客户端显式写 new DataLayer() = 全 0
        else { mask.set(i); updates.add(layer.copy().getData()); }
    }                                            // layer == null → 两位都不置 = 客户端该 section 不动
}
```

`ClientPacketListener.readSectionList`：只有 `mask.get(i) || emptyMask.get(i)` 才 `queueSectionData(...)`；
`applyLightData` 末尾 `setLightEnabled(pos, true)`。**结论：唯一能把客户端 section 打黑的两条路 =
「在掩码内且引擎层为空」与「在掩码内且 payload 全 0」。掩码外的 section 客户端原样保留。**

`DataLayer`（**最毒的一处**）：

```java
public DataLayer(int defaultValue) { this.defaultValue = defaultValue; }   // 不按位截断
public int get(...) { return this.data == null ? this.defaultValue : <nibble>; }
public boolean isEmpty() { return this.data == null && this.defaultValue == 0; }
```

于是 `new DataLayer(2048)`：`isEmpty()==false`（走 mask 分支）、`get()==2048`（传播视为「已满」→ 不排新任务，
这就是它在 `ShadowSeedServer.EMPTY_LIGHT_LAYER` / `MixinLayerLightSectionStorage` 里当占位层的原因），
但 `getData()` 走 `packFilled(2048)` → **全 0 字节**（= 线上 payload = 黑板）。
**不要「顺手」把它改成 `new DataLayer()`**：那会让 `isEmpty()` 为真（empty 掩码）且传播真的开始工作 →
`lightTasks` 越水位 → 主线程 `injectChunk` 5s 忙等 → 整卡死（2026-09-16 实测）。

探针判据（`ClientChunkHandler.probeChunkState`）：`topY = level.getHeight(WORLD_SURFACE, bx, bz)`、
`skyTop = getBrightness(SKY, topY+1)`；列内最高方块之上第一格按定义无遮挡 → **正确天光必 15**，
`skyTop==0` 即缺陷；再用 `topBlock=="block.minecraft.air"` + `topY > minY` 剔除取样点错。

---

## 3. 根因

### 3.1 光桥（`drainLightMasks`）把半成品 section 当权威下发（flyrt5 定位）

- 证据：变黑柱的 `source=light` 探针序列显示客户端先收到 `skyTop=15 skyMid=15`，随后收到 0；
  且 `fullApplyAgeMs` 高达 65–129 s（整柱包早已落地）→ **打黑的是 light-only 包**。
  同期 `Queued full light update` 全程只有 0–3 条，而光包落地探针 7000+ 条 → 生产者在光桥。
- 证据：变黑柱的包 `fullOrigin=section_delta`，影子侧该柱只是**部分点亮**：
  `skyTop=0` 而同 section `skyMid=1`、缝边 `skyN=0`（同一次探针里其它方向 15）。
- 旧过滤「线上有非 0 半字节才发」挡不住它（该 section 确实有非 0 值）。
- 不会自愈：整柱包对已落地柱被抑制（`hasClientApplyEpoch`），客户端只会继续收后续中间态。

### 3.2 `pushLightReady` / `deliveryLightMasks` 的空掩码漏洞

- `pushLightReady`：`work == null`（superseded 收口）时只把 **sky** 掩码替换成线掩码，
  `blockMask` 留 `null` → 包构造器**枚举整列 block section** → 空 block section 全被显式置 0（洞里/火把灭灯）；
  且旧判据写的是 `blockMask != null && blockMask.isEmpty()` → 该路径**恒不触发**扣包。
- `deliveryLightMasks`：block 侧同样传 `null`（同上后果）。
- 用户 epoch 只覆盖 seed 侧，且 epoch 会在客户端卸载时被摘掉（`Client unload invalidated` 单轮 2300+ 条）
  → 「只看 epoch」既挡不住、也会漏。

### 3.3 病灶：影子端「半成品天光」被交付（未修）

影子端在邻柱收紧 / 分段增量 / 卸载重投递窗口里会出现「引擎层已装但只算了一部分」的天光
（`hasUsableEngineLight` 的注释里叫它「只填了顶部的半成品柱」，并且**刻意保留**该短路：
判严会让 `isLightReusable` 大量为假 → 每柱多排 2 轮光屏障 → `lightTasks` 越水位 → 卡死）。
交付侧只能「不把半成品当真值下发」，无法让客户端自己长出正确光 → 见 §7.2。

---

## 4. 已实施改动（交付侧）

| 文件 | 改动 | 语义 | 代价 |
|---|---|---|---|
| `ShadowLightCompute.drainLightMasks`（2700+） | 已持有柱（epoch）且 `!ShadowSeedServer.isLightConverged(level)` → 本帧不打包、**不清掩码**（`Deferred light update for held chunk`，2931） | 只在引擎无在途光工作时回传；收敛帧打包的就是收敛值 | 数据新鲜度等待一个「引擎空窗」；判据只读队列，不排新任务 |
| 同上 | `retainNoDowngrade` + `isAssertSafe`（2866）：逐格 `shadow ≥ client` 才保留 section（sky+block） | **客户端已有光永不被光包打暗** | 影子侧合法变暗（放方块挡天光）不再经光桥下发（已知取舍，注释里写明） |
| 同上 | 客户端侧读 `RetainNoDowngrade` 用 `ClientLevel.getChunkSource().getLightEngine()`；`client==null` 退化为「有非 0 光即可」 | 只在客户端主线程调用（与写区块同线程） | render thread 每包少量引擎读（已实测未卡死） |
| `ShadowLightCompute.pushLightReady`（2386） | sky+block 掩码一律用 `wireLightMask` 收窄；不再传 `null` | 消除「整列 block 断言全 0」 | — |
| `SeedGenChunkCodec.deliveryLightMasks` | 已落地柱返回 `{wireLightMask(SKY), wireLightMask(BLOCK)}`（非天光维度仍 `null`） | 同上，针对整柱包 | — |
| `ShadowSeedServer.isLightConverged(ServerLevel)`（1699） | 按维度版本；只读 `hasLightWork()` + `lightTasks.isEmpty()`；异常返回 true（不推迟） | 供光桥判「收敛值 vs 传播中间态」 | — |
| `ShadowSeedServer.hasUsableEngineLight`（719） | 高度 API 改走 `LevelHeightCompat.getMaxBlockYExclusive`（1.21.11 段原本编译不过） | 跨段编译一致（1.21.1 数值不变：320/-64） | — |
| `LevelHeightCompat` | 新增 `getMaxBlockYExclusive` | 1.21.2+ `getMaxSectionY()+1 << 4` | — |
| `ClientChunkHandler`（217–263、336–350） | 三个计数器 + 卸载清理 | `clientDarkLightProbeSamples` / `clientDarkLightProbeChunks` / `clientDarkRegressionChunks` | 仅 `debug.lightVerify` 开启时统计 |
| `SmokeProbeWriter` | 三个计数器写入 `counters` | 供 `assertProbe` 使用 | — |
| `ScenarioEngine` / `ScenarioStep` | `${moveWaitMs}` 派生变量；引号感知 `splitTokens`（支持 `command text="..."`） | 场景 DSL 前置能力 | — |
| `scripts/smoke/analyzer.py` | `single_round_scenarios` 加 `flyroundtrip` | 否则 analyzer 按两轮判定 → `PROBE_MISSING` P0（**不是**模块缺陷） | — |
| `docs/runtime-smoke-test.md` | 场景行 + 黑块判据 + 单轮登记说明 | — | — |

---

## 5. 实测数据（1.21.1 fabric / `-Scenario flyroundtrip -MoveSeconds 20`）

| 运行 | 配置 | dark 采样 | dark 柱 | **回归柱（先亮后黑）** |
|---|---|---|---|---|
| flyrt4 | 改前（仅 sky 线掩码） | 31 | 5 | **5** |
| flyrt5 | + 收敛门 | 17 | 4 | **4** |
| flyrt6 | + no-downgrade（光桥）+ 空掩码补洞（pushLightReady/整柱） | 3 | 0 | **0** |
| flyrt7 | 同 flyrt6 | 16 | 5 | **5** |
| flyrt8 | +「开天格必须 15」窄口径 | 10 | 6 | 0 |
| flyrt9 | 同上，改宽口径（保留空 section 断言） | 67 | 24 | 2 |
| flyrt10 | 窄口径 + 去掉「无 epoch → vanilla 全柱」分叉 | 79 | 35 | 8 |
| flyrt11 | 回到 flyrt6 配置 | 24 | 3 | **0** |

- **当前代码 = flyrt6/flyrt11 配置**（flyrt8/9/10 的实验已全部回退，实测数字写进 `deliveryLightMasks` 注释防重犯）。
- 护栏计数（flyrt6）：`Deferred light update for held chunk` 47、`Suppressed light assert (empty/partial)` 45、
  `Light timeout` **0**（未复现卡死）、整轮 169 s 正常退出。
- 回归门：`1.21.1_fabric_regress1`（classic 双轮连服）**`=== RESULT: PASS ===`**。
- 编译/单测：`common:compileJava` 在 1.20.1 / 1.21.1 / 1.21.11 三段通过；`scanVersionBoundaries: OK`；
  `common:test`(L0) 通过；`scripts/smoke/test_analyzer.py` 24 项通过。
- 日志/探针：`build/smoke-test/logs/client_1.21.1_fabric_flyrtN.log`、
  `build/smoke-test/probe/1.21.1_fabric_flyrtN/round1.json`、`build/smoke-test/results/result_*.json`。

---

## 6. 已试过、实测更差、**明确回退**的判据（勿重犯）

| 判据 | 结果 | 为什么错 |
|---|---|---|
| 「开天格必须 15」窄口径（不含空 section，flyrt8） | 回归 0，但 dark 柱 6（vs flyrt6 的 0） | 跳过该 section ⇒ 客户端保留/自行兜底，首交付时它本来就没有光 |
| 同判据宽口径（保留空 section 断言，flyrt9） | dark 柱 24、回归 2 | 空 section 断言同样会把客户端显式置 0；影子侧的「空」在重交付窗口不权威 |
| 去掉「无 epoch → vanilla 全柱」分叉（flyrt10） | dark 柱 35、回归 8 | 首交付不给断面反而让客户端停在兜底值；且破坏「暗 section 照旧置 0」 |
| 判严 `isLightReusable` / 改 `EMPTY_LIGHT_LAYER` | **整卡死**（2026-09-16） | 多排光屏障 → `lightTasks > ENGINE_TASK_LOW_WATER(450)` → 主线程 `awaitEngineTaskDrain` 每次等 5 s |

---

## 7. 残留问题（两类，已分清）

### 7.1 探针时序假阳性（**不是**用户报的症状）

- 形态：`source=light`、`apply#=1`、`fullApplyAgeMs` ≈ 0–2 ms、`lightQueueDelayMs` ≈ 0、`origin=remote_pull`。
- 原因：`handleLightUpdatePacket` 只是 `level.queueLightUpdate(...)`（**排队**），探针在 `applyReadyLight` 里
  紧接着读 `getBrightness` → 读到的还是落地前的值。首交付柱由 0 变亮，于是必然先采到一次 0。
- 影响：`clientDarkLightProbeChunks`（口径 = 该柱最新采样仍为 0）被这一类污染；flyrt11 的 3 个 dark 柱全是这类。
- 处置建议（下一步 (i)）：把「判黑」延后 1 tick（或在收到下一次该柱采样时再判定），
  门禁主锚改为 `clientDarkRegressionChunks == 0`（= 「曾亮柱被打黑」，正是用户报的症状）。

### 7.2 真回归：整柱重投递时交付影子半成品（flyrt7 的 5 个）

- 形态：`source=shadow`（**整柱包**）`apply#=3`、`origin=shadow_memory_cache`、`ageMs≈350–950`，
  随即（或当场）`skyTop=0`；无 unload 记录 → 客户端仍持有该柱。
- 机制：卸载→重入视距的整柱重投递走「无 epoch → vanilla 全柱枚举」分支（或线掩码放行「有非 0 值但整体偏暗」
  的 section），影子侧此刻天光只算了一半 → 开天格被置 0。
- 为什么试过的交付侧判据都补不上：见 §6——**在交付侧只能选择「发半成品」或「什么都不发」，
  两者都救不了「客户端本来没有正确光」的柱**。

---

## 8. 下一步（两条，建议按序）

### (i) 探针时序 + 门禁口径（低风险，0.5–1 h，含 1 轮冒烟）

- `ClientChunkHandler`：dark 判定延后一 tick（记录待判定采样，或只在同一柱的下一次采样时回填）；
  保留 `darkLightProbeSamples` 作观测。
- `flyroundtrip.scenario`：主锚改 `counters.clientDarkRegressionChunks eq 0`，
  `clientDarkLightProbeChunks` 降为观测；`docs/runtime-smoke-test.md` 同步。
- 验收：flyroundtrip 连跑 2–3 次，回归柱应稳定 0；classic 双轮保持 PASS。

### (ii) 上游「算完再交付」（真正把 0 做实；3–5 轮冒烟，**有卡死风险**）

- 目标语义：**对于「客户端已持有」的柱，整柱重投递与光桥都只在影子侧该柱光照收敛且引擎层非空时才交付**；
  否则推迟（保留掩码/包），等收敛帧再发。
- 现成可用件：`ShadowSeedServer.isLightConverged(ServerLevel)`（本轮新增）、`hasCompleteLightLayers`、
  `hasUsableEngineLight`（719，注意它刻意短路）、`LightNeighborhoodGate`。
- **负载预算红线（否则必卡死）**：`ShadowLightCompute.awaitEngineTaskDrain`
  （`ENGINE_TASK_LOW_WATER = 450`、`CONVERGENCE_WAIT_TIMEOUT_MS = 5_000`、`parkNanos(200_000)`）
  由 `ShadowSeedServer.injectChunk` 在**客户端主线程**同步调用；任何让影子端多排光任务的改动
  （判严 `isLightReusable`、把 `EMPTY_LIGHT_LAYER` 改成真空层、让传播真的开跑）都会越水位 → 5 s 忙等。
  必须同时：提高水位/加批量 drain/把 `awaitEngineTaskDrain` 改为非阻塞或后台化（三者选一并实测）。
- 验收：flyroundtrip 连跑 3 次回归柱稳定 0；`Light timeout` 计数 0；classic 双轮 PASS；
  进服时长不劣化（对比 `build/smoke-test/stats/*_round1_VD20.txt`）。

---

## 9. 复现 / 取证手册

### 9.1 跑一轮并定位变黑柱

```powershell
.\scripts\runtime-smoke-test.ps1 -Ver 1.21.1 -Loader fabric -Phase I -SessionId "1.21.1_fabric_flyrtX" `
    -Scenario flyroundtrip -MoveSeconds 20
# 看门禁输入（这三个数就是场景 assert 的输入）
(Get-Content build/smoke-test/probe/1.21.1_fabric_flyrtX/round1.json -Raw | ConvertFrom-Json).counters |
    ConvertTo-Json -Compress
```

### 9.2 离线判「哪条通道打黑的」（本次全靠这段定位）

只在 `source=light` 流里做口径统计（**别把 `source=shadow` 的整柱包混进来**，口径会差几十倍）：

```powershell
$log = "build/smoke-test/logs/client_1.21.1_fabric_flyrtX.log"
$rows = Select-String -Path $log -Pattern "source=light" | ForEach-Object {
  $l = $_.Line -replace '\x1b\[[0-9;]*m',''                       # 日志行带 ANSI ESC，别用 ^\[
  if ($l -match '\[(\d\d:\d\d:\d\d)\]') { $t=$matches[1] } else { $t='?' }
  if ($l -match 'pos=\((-?\d+),(-?\d+)\)') { $p="$($matches[1]),$($matches[2])" } else { $p='?' }
  $sky = if ($l -match 'skyTop=(\d+)') { [int]$matches[1] } else { -1 }
  $tb  = if ($l -match 'topBlock=block\.minecraft\.(\w+)') { $matches[1] } else { '?' }
  $topY= if ($l -match 'topY=(-?\d+)') { [int]$matches[1] } else { -999 }
  $o   = if ($l -match 'fullOrigin=(\w+)') { $matches[1] } else { '-' }
  [pscustomobject]@{t=$t;p=$p;sky=$sky;tb=$tb;topY=$topY;o=$o}
}
# 每柱：最新采样仍黑 且 曾亮过 = 回归柱；再看「暗之前是否插入过整柱 apply」= 判通道
```

再对可疑柱拉完整时间线（含 `shadow_attempt/shadow_applied/Client unload invalidated/CHUNK_PROBE`）：
`Select-String -Path $log -Pattern "\(-4,7\)" | Where-Object { $_.Line -match "shadow_|unload|CHUNK_PROBE" }`。

### 9.3 确认「跑的是新代码」（必做，否则结论无效）

```powershell
javap -p -c -cp "common/build/1_21_1/classes/java/main" `
  io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute | Select-String "retainNoDowngrade|noteBridgeDeferHeld"
# 另：确认没有 IDE 陈旧产物抢 classpath（loom 会把 bin/main、out/production 也列进 MOD_CLASSES）
Get-ChildItem -Recurse -Filter "ShadowLightCompute.class" -Path common/bin,common/out,fabric/bin,fabric/out -ErrorAction SilentlyContinue
```

### 9.4 坑清单

- 日志行以 ANSI ESC 开头 → 正则别锚 `^\[`；`Where-Object` 里 `$matches` 不可靠（用 `ForEach-Object`）。
- PowerShell 哈希表键大小写不敏感：同时用 `n` / `N` 会 ParserError。
- 探针口径：只有 `source=light` 参与计数；`topY == minY`（overworld = -64，`topBlock=bedrock`）的样本会被剔除。
- `clientDarkRegressionChunks` 依赖 `PROBE_EVER_LIT`（跨卸载保留），`PROBE_LAST_SKY` 在卸载时清除
  （`ClientChunkHandler.onProbeChunkUnloaded`，由 `ShadowLightCompute.onClientChunkUnloaded` 调用）。
- `fly` 非阻塞；`-MoveSeconds=0` 时场景只剩 settle。
- 单轮场景必须在 `scripts/smoke/analyzer.py` 的 `single_round_scenarios` 登记，否则 `PROBE_MISSING` P0。
- harness 的 `=== RESULT: FAIL` 里若只有 `PROBE_MISSING round=2` / `SMOKE_PASS_MARKER_MISSING`，
  先看客户端退出码与 `assertProbe` 那行，别当成模块缺陷。

---

## 10. 相关文件与未验证项

**改动文件**（本轮，未提交）：`ShadowLightCompute.java`、`SeedGenChunkCodec.java`、`ShadowSeedServer.java`、
`LevelHeightCompat.java`、`ClientChunkHandler.java`、`SmokeProbeWriter.java`、`ScenarioEngine.java`、
`ScenarioStep.java`、`ShadowVanillaLightPipeline.java`、`MixinLayerLightSectionStorage.java`、
`ShadowLightComputeTimingRegressionTest.java`、`flyroundtrip.scenario`、`flyroundtrip.profile.properties`、
`runtime-smoke-test.ps1`、`analyzer.py`、`test_analyzer.py`、`docs/runtime-smoke-test.md`。

**未验证 / 明确不做**：

- 只在 **1.21.1 fabric** 上跑了专项场景 + classic 双轮；其它版本/加载器仅编译级验证（1.20.1 / 1.21.11 通过）。
- flyroundtrip 门禁当前口径仍是 `clientDarkLightProbeChunks == 0`（会被 §7.1 假阳性卡住）→ 见 §8(i)。
- 「光泄漏」类副作用（影子合法变暗不经光桥下发、空 section 不再断言）**没有量化手段**：现有探针只测「偏暗」，
  不测「偏亮」。若要评估，需要新增一个「应暗而亮」的采样口径。
- 上游 §8(ii) 未动：`EMPTY_LIGHT_LAYER(2048)` 保持原样（改它会卡死）。
