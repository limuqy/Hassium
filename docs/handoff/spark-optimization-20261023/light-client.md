# 客户端光照与其余主线程候选处置（2026-10-23）

## 实施范围与证据

本次修改 `ShadowLightCompute`、`ShadowSeedServer`，新增纯 Java 的 `LightConfirmationQueue` 和 11 项回归测试。依据为 [完整基线报告](../spark-main-thread-20261023.md)、[方法表](../spark-main-thread-20261023-methods.csv) 及 `build/analysis/spark-20261023/` 中 8 场正式运行。此文记录实现与验收接口；构建、游戏和收益结果由统一 validation 执行并另行汇总，执行代理没有运行 Gradle 或游戏。

旧链 `drainReady → confirmLightsCorrectIfConverged` 在 8 份客户端 profile 均采到，inclusive 均值 2.3669%、最大 2.7983%，自身均值只有 0.0700%，主要是下游层完整性检查。它在引擎稳定时仍每帧扫描全部注入柱。百分比为线程墙钟采样，不能等同 CPU 收益；父子项不能相加。

按 AGENTS 先用 CodeGraph 追踪确认、LIGHT 完成、注入/替换/增量/方块更新/卸载与 tracking。XERJ community 目录没有相关 Minecraft/调度条目；尝试补充 Caffeine 参考 clone 失败后，复用已加载 Bobby corpus 获取真实实现：`Johni0702/bobby`，revision `241f973106ee4e5552a5950d0a12433c07921c93`，`src/main/java/de/johni0702/minecraft/bobby/mixin/ClientPacketListenerMixin.java:76`、`FakeChunkManager.java:170`。前者把光清理绑定实际队列尾部并保留未消费清理，后者用列事件与消费时真值处理失效队列。已读 `LICENSE.md` / `LICENSE.LESSER.md`（GPL-3.0 / LGPL-3.0），仅参考“事件触发、尾部消费、失效后不可丢工作”的思路，没有复制实现；也没有采用其直接执行 fallback。本次不提交第三方反馈。

## 实现和安全边界

- 渲染帧 `drainReady` 不再读取确认用的引擎状态或扫描注入表。确认直接由**既有影子主循环**消费，不新增 executor、Future 或每帧 execute 任务。
- 每个 `ShadowSeedServer` 独立拥有最多 4096 个脏票。同柱重复 mark 替换票据并保留最早等待时间；检查期间又被 mark 的新票不能被旧检查删除。队列只保存复合维度 key 和票据，不保存活动世界快照，也不向渲染器发布异步结果。
- 网络注入、读盘/生成注入、section delta、原版方块更新、relight、LIGHT 开始/完成、卸载都会标记本柱及**已驻留**的 3×3 邻柱。光照水平传播最多影响相邻柱；新邻柱进驻自身也会 mark 该 3×3。BE NBT 不改变发光状态的路径继续使用已有职责，不为纯 NBT 增加地形扫描。
- 每 10 ms 至多消费 32 柱，且在柱与柱之间检查 1 ms 截止时间。单柱的现有 chunkLock/检查不能被中断，因此 1 ms 是软预算；没有把锁改成无锁或超时绕过。引擎收敛状态每个维度每批读取一次。引擎/同柱内容或 LIGHT 工作尚忙的票保留到后续批次，FIFO 轮转避免某个维度挡住其余维度。
- 硬容量耗尽时只设置 overflow 修订号。所有者用一个弱一致 key 游标增量兜底，仍共享同一批次数量/时间预算，不复制全表，不生成无界工作队列。溢出期间的新溢出变更使扫描再走一轮；未完成项重新进入有界票表或维持 overflow。
- T1 独立审查 R1 指出首稿先消费常规票、再消费 overflow，若每轮软预算只够一张保留票，overflow 会永久饥饿。已改为逐柱交替两个来源，并把下一来源偏好保留到下一 owner 轮。即使每轮只够完成 1 柱，两个来源也跨轮公平推进；不提高总列预算、不绕过时间门。normalRemaining 限制常规票每轮最多检查起始票数，原有失败重试和修订比较保留。
- 新实例装配早于 registry 发布时保留票；断连/退出通过 `beginShutdownSave`、`stopMainLoop`、`joinMainLoop` 及主循环退出关闭实例队列。消费和实际写位前复核 registry 实例、shadow generation、关停状态；跨维同坐标由 `DimensionKey` 隔离，不把旧柱引用提交给新实例。
- 确认的引擎异常判据改为 fail-closed。原来的 `hasCompleteLightLayers`、地表光照探针与 `syncLightCorrect` 唯一权威门全部保留。同柱 pending/LIGHT/gate 未完成时不进行持久化确认；层安装尚不可见的权威柱保留票，无权威光环等下一次 LIGHT 变化重新入票。
- 没有修改 LightNeighborhoodGate、promotedClean、交付域、surface light 标准、客户端推送门、原生光照邻柱门禁、sleep token/尾部 ACK 或 renderer/客户端世界的所有者。`finishLight` 原有逐柱持久化/推送顺序仍运行；新队列是其后的确认收口，不增加交付等待条件。

## 其余客户端候选的最终处置

| 候选 | 源码与采样依据 | 本阶段处置 |
|---|---|---|
| `applyReadyChunk` 重复落地 | inclusive 均值 0.0601%、最大 0.0922%，self 0。`offerReady` 已按 `(dimension, pos, op)` 使用 `KeyedPriorityQueue.REPLACE`，standing preview 使用 `SKIP_IF_PRESENT`；drain 在实际应用前再次 `isCurrent`，并保留跨维检查、sleep token 与有限忽略重试。 | 保留。已经合并尚未落地的同柱重复项；跨次落地未证明可丢弃。同坐标的后续 LIGHT/权威快照、sleep 恢复快照及原版 BlockEntity 更新存在顺序语义，额外按坐标吞包没有证据支持。统一重测保留 ready 等待与实际覆盖。 |
| 全柱渲染标脏 | `ClientChunkHandler.markChunkSectionsDirty:175` 逐 section 调 `setSectionDirtyWithNeighbors`；inclusive 均值 0.00988%、最大 0.01975%，self 0。调用发生在原版整柱应用成功后。 | 保留。整柱包可包含 section、heightmap 和光照变化；现有采样没有准确 changed-section 集合，也没有同帧重复标脏计数。跨 apply/mesh 编译去重会改变时序与边界邻柱依赖，当前无法证明收益或正确变化范围。 |
| tracking 发布 | `ShadowTrackingSession.onClientTick:206` 捕获玩家位置/朝向到 PendingState；`consumeOnShadowLoop:392` 才选柱、驱动 ChunkMap 和 acquire。采样均值 0.01564%、最大 0.05927%，self 均值 0.00247%。 | 保留现有职责分离。活动玩家对象继续只在客户端所有者读取；不新增 worker 往返。 |
| 休眠可见性 wrapper | `MixinSleepLevelRenderer` 的 compiled getter 包装 inclusive 均值 1.0513%、最大 1.2707%，self 全部 0。报告已逐份复核：这些样本全部落在下游原版 `getCompiledChunk/getCompiled`。`SleepTerrainClient.visible:47` 在 wake 后还会检查未完成柱。 | 保留。没有证据能将约 1% 算作 wrapper 自身成本；只因 `sleeping=false` 就放行会泄露恢复中未完成柱。未改休眠设计或可见性。 |

## 回归与观察接口

新增 `common/src/test/java/io/github/limuqy/mc/hassium/shadow/server/LightConfirmationQueueTest.java`，覆盖稳定零重扫、100 次同柱 mark 合并、检查途中并发失效、跨维公平轮转、无 owner/失败检查后的重试、旧会话关闭与新实例独立、固定容量溢出扫描、扫描中再次溢出与失败重试、预算耗尽、检查时断连不能复活票。R1 补充 `oneColumnSoftBudgetAlternatesNormalAndOverflowAcrossOwnerPasses`、`oneColumnCountBudgetAlsoServesBothLanesWithoutIncreasingTheBudget`、`sustainedDirtyArrivalAcrossDimensionsCannotStarveOlderOverflowOrNewSweep`：确定性复现 capacity=1、A永久保留且B溢出、每轮只能确认1柱，并断言B于第4轮被检查；另覆盖 count budget=1、多维持续新增脏票、后续扫描不漏晚到修订以及来源稳定后的最终清空。没有异步 dispatch，因此拒绝调度不会丢唤醒；工作一直保存在实例脏票表，owner 下次轮询即可重试。

验证方按顺序运行（PowerShell 7、daemon）：

```pwsh
.\gradlew.bat common:test "-Pmc_ver=1.20.1" --tests '*LightConfirmationQueueTest' --tests '*ShadowLightComputeTimingRegressionTest' --tests '*ChunkShapeDilationTest' --tests '*ShadowOvdGenerationTest' --tests '*Sleep*'
.\gradlew.bat common:test "-Pmc_ver=1.21.1" --tests '*LightConfirmationQueueTest' --tests '*ShadowLightComputeTimingRegressionTest' --tests '*ChunkShapeDilationTest' --tests '*ShadowOvdGenerationTest' --tests '*Sleep*'
```

两重点 loader 编译/完整已有测试、版本边界扫描及正式 8 场矩阵由 validation 统一排队，避免多个代理争抢 JVM。实现代理仅运行了针对改动文件的 `git diff --check`（通过，Git 提示旧 CRLF 文件后续规范化，不是空白错误）。

无需打开 debug 或改生产热路径即可用 agent 低频反射：

```java
io.github.limuqy.mc.hassium.shadow.server.ShadowServerRegistry
    .getInstance().get().lightConfirmationSnapshot()
```

返回 `io.github.limuqy.mc.hassium.shadow.server.LightConfirmationQueue$Snapshot`：

| accessor | 用法 |
|---|---|
| `pending()` / `highWater()` | 当前/最坏脏票数，均不得大于 4096。 |
| `overflow()` | 是否处于有界增量兜底；正式场景应记录是否发生及最终是否退出。 |
| `marked()` / `coalesced()` | 脏事件与同柱合并累计次数，可量化重复工作合并。 |
| `examined()` | 已执行的检查次数。无变化且完全收口后该计数应稳定。 |
| `maxWaitNs()` / `oldestNs()` | 从首次 dirty 起的最大已观察等待/当前最老等待，含引擎未收敛与同柱 pipeline 忙的时间；overflow 活跃年龄也计入。 |
| `closed()` | 旧实例在退出后应关闭且 `pending=0`；新实例指标独立重置。 |

snapshot 只遍历有界票表、读取队列元数据，不读取活动世界；建议每秒采样。`maxWaitNs` 为保守等待观察值，不是独立“owner 排队时延”，也不是层齐或真实交付延迟。不可把 backlog 一时非零直接当作失效：权威柱可能仍等引擎/地表光；必须结合 `isLightConfirmationBlocked` 和对应维度收敛状态解释。移动停止后确认计数应清空或收敛到可解释的稳定未完成集合，不能无限增长。

端到端另复用 `-Dhassium.chunkProfile=true` 的 `ChunkPipelineProfile`：比较 `REQUEST_RESPONSE / LIGHT_WALL / READY_WAIT / INGEST_APPLY / FIRST_MESH` 等已有分源延迟及覆盖计数；首次落地可配合现有 `hassium.smokeTest.probeDir` 下 `SmokeChunkTrace.snapshot(dimension)` 的 network/ready/applied/mesh 集合、时刻与进度。变化后同柱不能仅用首次到达 map，需以 flow 的 FULL/DELTA/CACHE 时延对比。新队列的等待下降不能替代实际交付延迟与画面工作量等价。

验收必须同时包含 1.20.1 Forge / 1.21.1 NeoForge：同世界同脚本重跑普通往返和包负载；核对主线程绝对耗时、P99、实际包量、loaded/C: 覆盖；补 section/方块更新（含边界光源增删）、切维、断连重连、休眠恢复及**移动中的屋檐/洞口**光照检查。队列/线程 CPU 的改善不能代替目视验收。当前没有优化后数据，不能承诺节省 2.37% CPU。

## 尚存限制

一次完整柱检查与现有 chunkLock 获取没有硬时间上界；有界批处理只能限制柱数并在柱间让出。overflow 在持续满载变更时允许重复增量扫以避免漏票，需要实际 backlog/龄期证明不会饥饿。权威但地表探针始终不满足的柱会保持 pending，这保留了旧确认收口的重试语义；其总影子线程成本需在相应维度/地形采样。第三方光照引擎沿用其既有 `ForeignLightEngine` 能力判据，此轮正式基线未安装这些模组，不能声称已运行验证。

## post-R2/R5 legacy 的 pendingAuth=106 只读诊断

证据目录：`build/analysis/spark-optimization-20261023/pullorderlegacy_1201_forge-r2r5-invalid/`。本节未修改生产源码、未运行构建或游戏，也未重复 B 族 fixture 前置条件调查。该轮在 `pullOrder.officialLanded >= 1` 前置断言失败，未执行睡眠/排序阶段，缺最终 probe；不能作为排序/光照完整验收 PASS。

`pendingAuth` 对应 `ShadowLightCompute.pendingAuthoritative`，是既有的“降级 LIGHT 已完成，等待邻域新进展再重 LIGHT”登记表，**不是**新增 `LightConfirmationQueue`。`finishLight` 仅在 `promotedClean=false` 时登记，`recheckPendingAuthoritative` 要求窗内邻柱 INIT 齐且相对登记时有新增邻居进展才重新过门。它刻意不阻止 `pushReady`；持久化确认不能把一个缺邻柱变成 clean，也不消费这张表。

该轮客户端日志的独立核对结果：

- L10097–10156（02:46:04–13）`pendingAuth=80`，当时 ready/pending/generated/inflightLight 均为 0；后续窗口变化/新柱交付又增加至 106，并非仅一个首轮计数。
- 全日志共出现 126 个不同坐标的 `pending authoritative` 登记，其中 20 个坐标后来有 clean `Promote ... neighborhood ready`，且这 20 个最后 clean 的行号全部晚于最后登记。其余 106 个坐标的最后 Promote 恰好分成 **95 个 outsideWindow>0**、**11 个 missingInWindow=1 且 outsideWindow=0**，与 L12355–12410（02:46:27–33）的稳定计数 106 一致。共观察到 23 次邻域就绪重 LIGHT，说明既有修复事件链并未整体停摆。
- 11 个最后仍缺窗内邻柱的坐标为 `(4,8)`、`(6,-8)`、`(6,6)`、`(7,6)`、`(8,5)`、`(8,6)`、`(2,9)`、`(4,9)`、`(7,-6)`、`(8,4)`、`(8,-6)`；最后 Promote 的 `injectedNotInit=0`。这里的剩余条件是缺少窗内 material，不是已注入柱等新增确认票才能完成 INIT。95 个带窗外缺邻的边缘柱保留非权威符合既有落盘红线；11 个窗内缺柱是否稍后能补齐仍须独立观察，不能从该早停日志断言永久缺柱或全引擎已收敛。
- fixture 的目标 A=`minecraft:overworld (3,0)`：L3070/3094/3110/3177/3506 clean Promote；L3138/3197/3522 已 `shadow_applied`。B=`(4,0)`：L3489/6169 clean Promote；L3528/6239 已 `shadow_applied`。两柱都没有 `pending authoritative` 登记。L3107 曾有 A 的临时 withhold，随后 clean Promote 与实际落地证明它不是该轮最终阻塞点。
- `Light timeout`、`confirmation deferred`、`shadow main loop crashed` 模式均未出现。该日志没有新增确认队列的 snapshot，故不能证明其 pending 最终清空、engine queues 全空或所有层已齐；同样也没有指向新确认队列漏失效的证据。`ready=0` 仅表示当次没有待消费包，不能解释成没有交付。

结论：此轮 `pendingAuth=106` 有明确的既有边界/缺邻门状态解释，且两 fixture 柱均 clean 并多次落地；**不构成 T1 新确认队列未收敛或漏失效的证据**。目前不据此修改算法。下一次严格 gated fixture 应把确认队列和权威待修表分开观察。

已将以下只读点交给 server_pull / validation，建议在影子所有者执行的 fixture sentinel 中记录两柱及必要邻域（不加生产热路径日志）：

- `shadow.lightConfirmationSnapshot()`：pending/highWater/overflow/examined/oldestNs，区别 T1 票表与 pendingAuth。
- `ShadowTrackingSession.lightGateDetail(dim,pos)`、`LightNeighborhoodGate.wasPromotedClean(key)`、`isAwaiting(key)`、`isNeighborhoodFullyReady(dim,pos)`、`inWindowReadyNeighborCount(dim,pos)`、`ShadowLightCompute.isLightConfirmationBlocked(key)`。
- 3×3 每个邻柱的 `LightNeighborhoodGate.isInNeighborhoodWindow(nx,nz)`、`shadow.injectedChunk(dim,nx,nz)!=null`、`ShadowLightCompute.isLightInitPassed(nKey)`。不要用“窗内 ready”替代 clean 的真实 3×3 口径。
- 如需读真实层/地表，在影子 owner 的既有 `withChunkLock` 内取得当前柱，再调用 `shadow.hasCompleteLightLayers(pos,chunk)` / `isColumnSurfaceLightReady(pos,chunk,owner)`，并另记该维的 `isLightConverged(owner)`。后者异常沿用既有 permissive 语义，不能单独当 fail-closed 的正确性证明。检查过程中仍须核对当前实例和维度；不让 worker 或 renderer 直接读活动影子世界。
