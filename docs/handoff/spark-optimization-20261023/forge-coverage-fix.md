# Forge 加载柱覆盖排查与 compare 重试修复（2026-10-23）

原 CCD ABBA 的 Forge B1 两阶段 loaded 中位数为 298，其余 Forge 臂为 313。**本次修复了可确认的 compare 等待失活缺陷；历史 B1 没有逐柱状态日志，尚不能唯一断言其全部 15 柱差异由此造成。**旧八场数据及工作量限制保留。

## 已确认的缺陷

`ShadowTrackingSession.refreshAuthorityEntries` 在柱进入服务端权威形状时调用 `ShadowLightCompute.invalidateAuthorityEntry`。后者通过 `ShadowPullClient.clearCompareRequested` 取消旧 receive credit、准备中的请求和快照围栏，并删除 compare 去重所有权；已有 `SeedGenCompareGate.AWAITING` 标记保留。

原 `drainAuthorityAcquires` 对“有 material、未确认、AWAITING”的柱无条件 `continue`。AWAITING 没有超时，旧请求已经取消，便没有响应能解除它，也没有新的请求会发出。`notePullFailure` 释放请求并保留等待标记时也存在同样的失活路径。它不是服务端 CPU 或 MSPT 计数问题。

修复后，AWAITING 柱在**已无 compare 请求所有权且失败冷却结束**时，可由原 tracking 扫描重新请求。重试计入原 acquire 预算，并保留同组 3×3 完成语义；真正排队、准备、接收或提交中的请求不被抢占。权威交付门始终保持关闭，直到正常 FULL/DELTA/UNCHANGED 成功确认；没有清闸盲投，也没有旁路光照邻柱门。

此外，两版各 21 项 NBT/快照提交、休眠恢复与 tracking 回收测试通过（合计每版 44 项、共 88 项），`scanVersionBoundaries` 通过。

## 直接日志

客户端 JVM 添加 `-Dhassium.chunkCoverageDiagnostics=true`，默认关闭。

- `[CHUNK-COVERAGE] center=... loaded=... expected=... missing=...`：Render 线程每秒读取原版客户端实际持柱，使用本版原版形状计算预期数量。
- `missing=(x,z)`：每秒最多 32 个缺柱详情，日志同时注明总缺柱数。只把坐标交给影子主循环，随后在所有者上读取 material / lightCorrect / compare awaiting 与 confirmed；另列请求 ID、receive 年龄、去重、NBT commit、apply epoch、光照队列、邻柱数量和快照围栏。
- `pack-rejected` / `apply-rejected` / `pull-failed` / `retry-orphaned-compare`：关键拒绝、失败与重试边沿。

日志采集不创建新请求或快照 token，不从后台线程读取客户端活世界。正常配置只有诊断开关分支。

## 验证与证据范围

两版首轮各 23 项相关测试通过（共 46 项）：`ShadowOvdGenerationTest` 13、`ShadowTrackingActivationTest` 1、`PullReceiveWindowTest` 3、`ColumnSnapshotFenceTest` 6。新增测试构造真实 queued receive admission，经过权威入窗取消后验证可重试、交付门仍关闭、替代请求仍去重；另验证失败后的 3 秒冷却。

| 最终常规运行 | movement loaded 中位数 | movement_load loaded 中位数 | runner |
|---|---:|---:|---|
| `forge-fixed-log1-after` | 313 | 313 | PASS |
| `neo-fixed-log1-after` | 313 | 313 | PASS |

双端退出码均 0；服务端 CCD0=`0xFFFF`、客户端及影子端 CCD1=`0xFFFF0000`，实际 affinity 校验通过。两版均沿用 60s 预热、60s 移动、15s 稳定、60s 移动＋256 实体场景。移动前沿仍有短暂少柱，因此“中位数 313”不表示每个瞬间全部齐套。两场常规运行未触发 `retry-orphaned-compare`，不能把它们当作该故障分支的运行时复现。

修复前另有四场冻结 JAR 诊断运行（`forge-diag3..6-after`）和一场仅加日志的 `forge-log1-after`，两阶段中位数也均为 313。`forge-diag4-after` 有 16 次 NBT 基线缺失警告，覆盖仍恢复，故不能把 NBT 警告单独当作原 B1 少柱的因果证明。`forge-diag1/2` 是诊断 agent 启动失败，未进入游戏，不计作产品失败或有效复测；最终方案直接使用仓库日志。

## 可控取消回归

在独立世界副本中，临时测试构建于进世界约 65 秒、当前持柱齐全时，对玩家附近 3×5 的 15 柱执行原版客户端卸载，随后走实际 `mark → requestFull → invalidateAuthorityEntry` 生命周期，取消尚未收到响应的比较。这与普通自然路线分开，明确属于故障注入。

两份测试 JAR 的解包内容仅 `ShadowTrackingSession.class` 不同：一份保持原 AWAITING 无条件跳过，一份启用修复后的重试。测试入口相同；它只存在于这两份临时诊断 JAR，最终源码已恢复，最终 Forge JAR 重建后的 SHA 与常规复测制品完全相同。最终 Forge / NeoForge JAR 均不含该注入入口。

- 旧逻辑：11:44:12 从 313 降到 298；之后能直接看到 `material=true correct=true awaiting=true receive=none dedupe=false`，且没有 NBT 提交、光照任务或活动围栏。1376 条同组缺柱记录持续至 11:46:23，重试数为 0。柱在视野外时 loaded 可回到 313，返回后仍缺；不能把离开缺柱区域时的 313 当成恢复。
- 修复版：11:47:57 同样主动降到 298；11:47:58 对全部 15 个取消坐标各记一次 `retry-orphaned-compare`，下一条 1 Hz 客户端状态为 `loaded=313 missing=0`。该组未再出现等待已取消请求的记录。

| 可控回归 | movement loaded 中位数 | movement_load loaded 中位数 | 取消柱的重试数 | 覆盖判定 |
|---|---:|---:|---:|---|
| 原跳过逻辑 `forge-cancel-before` | 298 | 298 | 0/15 | FAIL |
| 修复逻辑 `forge-cancel-after` | 313 | 313 | 15/15 | PASS |

两场 runner 均 PASS、双端正常退出；旧逻辑的覆盖判定仍为 FAIL，runner 完成不能代替覆盖验收。注入以当时玩家中心为基准：两场中心分别为 (13,0) / (14,0)，绝对坐标集保存在 JSON，并非逐坐标配对的性能试验。逐阶段汇总见 `controlled-comparison.json` 与 `validation.json`。这组回归证明“取消请求留下 AWAITING”确实能产生并修复持续漏柱；历史 B1 未记录相应逐柱状态，因此仍不唯一追溯其全部现场原因。两份诊断包均沿用 CCD 隔离，日志与故障注入耗时不计入 MSPT 性能结论。

## 制品

输出根：`build/analysis/chunk-coverage-fix-20261023/`。`validation.json` 保存运行结果、PID/mask、JAR 哈希与测试清单；`controlled-comparison.json` 保存取消坐标、逐柱重试集合和等待记录，`fixture-jar-diff.txt` 保存两份测试 JAR 的差异清单；`tests/` 保存对应 XML；每场的 `inputs.json`、`client.log`、`client-control/*-scene.csv` 保留原始证据。

| 最终 JAR | SHA-256 |
|---|---|
| `forge-fixed.jar` | `7cd671c24ae1a1fd6ee04a9686171c43365770ef643011d67247df197b9fda35` |
| `neo-fixed.jar` | `9487eb6bc07b65f69fcef04103bcd11af1733b5e781bb5d59e4dea84b66e2513` |

这两份 JAR 与历史 ABBA 的 after JAR 身份不同。本次是带诊断日志的正确性复测，没有重做八臂 ABBA，不更新或继承旧 MSPT 收益百分比。
