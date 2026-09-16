# 交接：往返飞行「已缓存区块变黑块」（flyroundtrip 专项）

状态：**已解决（1.21.1 fabric 实测回归 0，三轮专项 + classic 双轮全 PASS）**；残留项见 §7
日期：2026-09-16 立项 / 2026-09-17 收敛（提交 25339c7 → e03fbae，共 7 个）
定位：客户端光照流（`docs/client-chunk-light-flow.md` 域）／区块核心交付侧
症状原文：「飞行测试，从出生点飞出去再飞回来，出生点已缓存区块出现大片黑块」

一句话结论：黑块 = **Pull 改造新增的快路径绕过了旧推送流的「光屏障后才交付」权威性保证**，
活引擎的传播中间态被当权威值整柱下发。修法不是再打补丁，而是把这个不变量补回到全部交付路径：
门禁修真（Step 1）→ 空层/持久化堵漏（Step 2）→ 主线程背压解除（Step 3a）→ 全路径收敛停车门（Step 3b）。

---

## 1. 症状与复现

- 触发路径：进服 → 飞离出生点（视距外卸载）→ 飞回（重新投递 `redeliver / publishCached / 两阶段光照`）。
- 专项场景：`common/src/main/resources/hassium/smoke/scenario/flyroundtrip.scenario`
  （join → settle → `fly` 出去 → `tp @s ~ ~ ~ ~180 ~` 掉头 → `fly` 回来 → settle → dump → 断言）。
- 配置档：`scripts/smoke/profiles/flyroundtrip.profile.properties`（`chunk.seedGenEnabled=false`、
  `debug.lightVerify=true`、`debug.chunkApplyLogging=true`、`debug.asyncLogging=true`）。
- 命令（**必须带 `-MoveSeconds > 0`**，=0 只有 settle，不构成往返）：

```powershell
.\scripts\runtime-smoke-test.ps1 -Ver 1.21.1 -Loader fabric -Phase I `
    -SessionId "1.21.1_fabric_flyrtX" -Scenario flyroundtrip -MoveSeconds 20
```

- 门禁主锚：`counters.clientDarkRegressionChunks == 0`（「曾亮柱在诊断时刻仍黑」= 用户症状口径）。
  判定值一律取光包落地**下一帧复检**的 post-apply 采样（`ClientChunkHandler.runProbeRecheck`，
  `drainReady` 帧首执行）——探针即时读数是光队列 flush 前的旧值（`handleLightUpdatePacket` 只入队），
  首落地柱必然先采到一次 0（flyrt11 的门禁假阳性 FAIL 全属此类）。
  观测口径：`clientDarkLightProbeChunks`（复检后仍黑，含从未亮过）、
  `clientDarkLightProbeSamples`（全部即时 0 采样，含首落地瞬态）。
- 已知脚本坑：`fly` 非阻塞（后面必须 `wait`）；转向用相对 yaw；单轮场景必须在
  `scripts/smoke/analyzer.py` 的 `single_round_scenarios` 登记。

---

## 2. 判据基础（vanilla 语义，已核实；1.21.1 mojmap）

`ClientboundLightUpdatePacketData.prepareSectionData`：层存在且空 → emptyYMask → 客户端显式置 0；
层为 null → 两位都不置 → 客户端保留。**唯一能把已亮 section 打黑的两条路 = 「掩码内引擎层为空」
与「掩码内 payload 全 0」。** `DataLayer(2048)` 占位层 `isEmpty()==false` 但 `getData()` 全 0 字节
——**不要**改成 `new DataLayer()`（会让传播真的开跑 → lightTasks 越水位 → 卡死，2026-09-16 实测）。
vanilla `ChunkHolder.broadcastChanges`（156 行）对已发区块同样用 section 掩码增量——Hassium 的
增量桥对齐 vanilla，问题从来不在增量本身。

探针判据（`probeChunkState`）：`topY` = 列内最高方块之上第一格，正确天光必 15，`skyTop==0` 即缺陷。

---

## 3. 根因：Pull 快路径绕过「光屏障后才交付」

**历史脉络（用户指认，git log 已核实）**：

- 2026-08-21 `10cab50a`：黑环修复「影子回传区块与光包同 FIFO，避免旧空光后到盖暗」——修的是
  **顺序**（旧空光后到盖暗）。`dropQueuedLights`（入区块包丢该柱旧光）至今仍在 `offerReady`，未失效。
- 2026-09-01 起 `8a7e0f22` 等：Pull 改造为省流量新增四条**绕过光屏障**的快路径，每条都是
  「活引擎中间态当权威值」的新入口：
  1. UNCHANGED 缓存命中重推（`publishCachedChunk`）——读打包瞬间的活引擎，无屏障；
  2. 重入视距 redeliver（R2 move 转换 / `completeSuspendedLoad`）——flyrt7 的攻击路径
     （`origin=shadow_memory_cache`）；
  3. hash 命中 REUSE 跳过重算——旧判据裸 `isLightCorrect` 放行「层未安装/空层」；
  4. 分段增量光（section delta）——`fullOrigin=section_delta` 的黑包。

**为何交付侧补丁修不死**（9-16 的教训）：引擎层里占位层（2048，线上全 0）与算出的层无元数据可分，
wire 掩码只能挡全空、挡不住「有非 0 但整体偏暗」的半成品；且占位层会让传播提前停——
`isLightConverged` 存在「假收敛」（引擎空闲但光半成品）。收敛门+中间态扣包都拦不住假收敛，
只有逐格 `shadow ≥ client`（retainNoDowngrade）兜得住（见 §5 的 A/B/A 实证）。

**卡死约束**：判严 → 多排光屏障 → lightTasks 越过 `ENGINE_TASK_LOW_WATER=450` → 主线程
`injectChunk` 忙等 5s/柱 → 整卡死（2026-09-16 实测）。**主线程同步排水是锁死「容忍半成品」
的真约束**，3a 解除它之后 3b 才可能落地。

---

## 4. 修复提交链（2026-09-17，全部已验证）

| 提交 | 内容 | 验证 |
|---|---|---|
| `25339c7` feat(smoke) | 门禁真化：regression 主锚 + 1 帧复检（`runProbeRecheck`）+ 场景/分析器/探针基建 | flyrt12/13 PASS（旧口径同场次 flyrt11 假阳性 FAIL） |
| `6bd9e28` fix(seedgen) | 空层/半成品交付堵漏：`pushLightReady` 双层 wire 掩码（旧代码 block 掩码可为 null → 构造器枚举整列 → 空 block section 显式置 0）；`deliveryLightMasks` 整柱包同理；`isLightReusable` 替换裸 `isLightCorrect`（REUSE 空层/未装层拦截）；placeholder 补 INITIALIZE_LIGHT；`persistAfterClientLightPush`/`confirmLightsCorrectIfConverged` 加 `hasUsableEngineLight`（堵「空光落盘 isLightOn=1 → 磁盘复用永久黑」）；光桥收敛门+中间态扣包；`LevelHeightCompat.getMaxBlockYExclusive`（1.21.11 编译） | 编译三段绿；flyrt12/13 基线 regression=0 |
| `2d6c161` + `6a7f412` revert 对 | **A/B/A 归因实验**：撤 retainNoDowngrade → flyrt14 regression=**15**（FAIL）；恢复 → flyrt15 regression=0（PASS）。**no-downgrade 是光桥对「假收敛」态的唯一防线，承重，保留**；已知取舍（合法变暗不经光桥、主线程逐格扫）不变 | 归因闭合——本专项第一次拿到单变量证据 |
| `28added` fix(seedgen) | 主线程排水背压解除：`drainAfterClear` 主线程改投节流异步排水（SAFE_TO_CANCEL + 500ms 上限 + 中断即退）——flyrt16/17 实证 BEST_EFFORT 全额排水会在关机窗口 park 5s → 挂住优雅停止 → force exit + 非零退出码，双保险后 flyrt18 恢复优雅退出 | flyrt18 PASS 退出码 0；classic regress2 双轮 PASS |
| `e03fbae` fix(seedgen) | **全路径收敛停车门**：`pushReady`/`publishNativeLightResult` 在 `!isLightConverged(level)` 时停车（`PARKED_FULL_DELIVERIES`），`drainReady` 帧首冲刷；1s 超时兜底防饿死；apply-epoch 防倒替；standing 首包豁免（进服速度不变）；`cancelChunkWork`/切维清理 | flyrt19/20/21 三轮 PASS regression=0；classic regress3 双轮 PASS |

### 实测数据（1.21.1 fabric / `-Scenario flyroundtrip -MoveSeconds 20`）

| 轮次 | 配置 | regression | 门禁 |
|---|---|---|---|
| flyrt4–11（9-16 历史轮） | 交付侧补丁叠加期 | 0↔5 摆动，无归因 | 假阳性口径 |
| flyrt12/13 | 门禁真化基线 | **0 / 0** | PASS ×2 |
| flyrt14 | 撤 no-downgrade | **15** | FAIL（A/B 实验组） |
| flyrt15 | 恢复 no-downgrade | **0** | PASS（A/B 对照组） |
| flyrt16/17 | 3a 初版 | 0 / 0（但关机挂死，退出码 1） | FAIL（关机路径） |
| flyrt18 | 3a 双保险版 | **0** | PASS，退出码 0 |
| flyrt19/20/21 | +3b 停车门 | **0 / 0 / 0** | PASS ×3，退出码 0 |

- 3b 门控实测（flyrt19）：停车 **1696** 次、超时兜底 **9** 次（0.5%——99.5% 的停车在 1s 内等到收敛）、
  过期丢弃 **104** 次（epoch 防倒替真实生效）。
- classic 双轮：regress2（3a 后）、regress3（3b 后）双轮 PASS，退出码 0，优雅关机。
- 进服数据持平（flyrt19 applied 3896 / landed 2732 vs 基线 3840/2690）。

---

## 5. 已试过、实测更差、明确不做（勿重犯）

| 方案 | 结果 | 原因 |
|---|---|---|
| 「开天格必须 15」窄口径（flyrt8/9/10 系列） | dark 柱 6→24→35、回归 2/8 | 交付侧只能选「发半成品」或「什么都不发」，救不了客户端没有正确光的柱 |
| 判严 `isLightReusable` / 改 `EMPTY_LIGHT_LAYER(2048)` 为真空层 | **整卡死**（2026-09-16） | 多排光屏障 → lightTasks 越水位 → 主线程 5s 忙等/柱（3a 已解除该约束，但改 2048 本身仍会引爆传播工作量，维持不做） |
| 撤 retainNoDowngrade | regression 15（flyrt14） | 光桥对「假收敛」态的唯一防线，承重 |

---

## 6. 残留问题（已知、可接受、有升级路径）

1. **「假收敛」未根治**：占位层让传播提前停、`isLightConverged` 空闲但光半成品的窗口仍存在；
   现由 no-downgrade（光桥）+ 停车门（整柱）双重兜底。根治 = 修占位层停滞传播本身（上游），
   届时可撤 no-downgrade（撤除实验方法：参照 2d6c161/6a7f412 的 A/B/A 流程）。
2. **光泄漏无量化**：no-downgrade 使合法变暗不经光桥下发（等整柱重投递修正）；「应暗而亮」
   需新增采样口径才能评估。
3. **多版本运行时未测**：本轮只在 1.21.1 fabric 跑专项 + classic；1.20.1 / 1.21.11 仅编译级验证
   （三段 compileJava 绿）。L2 三锚点批量冒烟建议补一轮。
4. **探针口径**：`PROBE_LAST_SKY`/`EVERE_LIT` 按柱坐标键控（无维度），跨维场景同坐标会互相污染——
   flyroundtrip 单维不受影响；若扩展到 dimension 场景需加维度键。

---

## 7. 复现 / 取证手册

### 7.1 跑一轮并读门禁

```powershell
.\scripts\runtime-smoke-test.ps1 -Ver 1.21.1 -Loader fabric -Phase I -SessionId "1.21.1_fabric_flyrtX" `
    -Scenario flyroundtrip -MoveSeconds 20
(Get-Content build/smoke-test/probe/1.21.1_fabric_flyrtX/round1.json -Raw | ConvertFrom-Json).counters |
    ConvertTo-Json -Compress
```

### 7.2 离线判「哪条通道打黑的」

只在 `source=light` 流里做口径统计（**别把 `source=shadow` 的整柱包混进来**）；日志行带 ANSI ESC，
正则别锚 `^\[`；`Where-Object` 里 `$matches` 不可靠（用 `ForEach-Object`）。3b 停车门生效性看日志：
`Parked full delivery for convergence`（节流 1/s，total= 累计）、`timed out`（兜底频率）、
`stale-dropped`（防倒替频率）。

### 7.3 确认「跑的是新代码」（必做）

```powershell
javap -p -c -cp "common/build/1_21_1/classes/java/main" `
  io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute | Select-String "flushParkedFullDeliveries|parkFullDelivery"
Get-ChildItem -Recurse -Filter "ShadowLightCompute.class" -Path common/bin,common/out,fabric/bin,fabric/out -ErrorAction SilentlyContinue
```

---

## 8. 相关文件

修复链改动：`ShadowLightCompute.java`（停车门/复检挂点/排水中断检查）、`ShadowSeedServer.java`
（drainAfterClear/hasUsableEngineLight/persist 门控/placeholder 初始化）、`SeedGenChunkCodec.java`
（deliveryLightMasks/wireLightMask）、`ShadowVanillaLightPipeline.java`、`LevelHeightCompat.java`、
`ClientChunkHandler.java`（探针复检）、`SmokeProbeWriter.java`、`ScenarioEngine/Step.java`、
`flyroundtrip.scenario` + profile、`runtime-smoke-test.ps1`、`analyzer.py`/`test_analyzer.py`、
`docs/runtime-smoke-test.md`、本文档。

历史锚点：`10cab50a`（08-21 FIFO 黑环修复）、`8a7e0f22`（09-01 Pull 改造起点）、
`docs/client-chunk-flow-handover.md`（Pull 改造全程记录）。
