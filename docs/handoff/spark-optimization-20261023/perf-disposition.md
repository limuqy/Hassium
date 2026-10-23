# 最终性能处置：Pull / hash / 快照与排序路径

2026-10-23；只读核对冻结 v3 的八臂 summary、控制 CSV、既有离线解码 Spark JSON 与最终源码。没有修改源码/JAR/harness，没有新采样、构建或游戏运行。

结论是**当前没有足够证据提出一个必须再改的新增 Pull/hash/快照热点**，保留已验收实现及以下实测限制。这个结论不等于没有开销或没有退化：Forge 主线程 CPU、Forge FULL GATE p95、Neo SERVER_QUEUE p95 确实上升；现有数据不能把它们消去，也尚不能将其归因到特定新增调用。loop P99 改善与这些回退可以同时成立。

## 保留的实测回退

数值取各臂 `summary.json`，CPU 为两个对应 measured phase 的算术均值；stage 为该进程最后一组累计直方图的 p95，不是 phase 内的独立 p95。

| 指标 | Before 两臂 / 均值 | After 两臂 / 均值 | 处置 |
|---|---:|---:|---|
| Forge server main CPU，movement | 均值 5.77% | 均值 6.49% | 约 +0.71 个百分点，保留为测得的 CPU 回退 |
| Forge server main CPU，movement_load | 均值 14.29% | 均值 15.22% | 约 +0.93 个百分点，保留为测得的 CPU 回退 |
| Neo server main CPU，movement / load | 6.28% / 13.68% | 6.25% / 13.99% | 约 −0.03 / +0.31 个百分点 |
| Forge FULL GATE p95 | 1.141 / 1.208 s | 1.745 / 2.147 s | 两个 after 都高于两个 before；不能用其他 stage 或 Neo 的范围重叠抹成无退化 |
| Neo SERVER_QUEUE p95 | 130.0 / 117.4 ms | 184.5 / 167.8 ms | 两个 after 都高于两个 before，保留排队尾延迟上升 |

八臂的 server/client loop P99 改善、实体包量和柱覆盖维持，并不能证明上述 CPU/等待没有损失。这里只确认测量结果的方向，不声称两次重复足以估计一般分布或唯一因果。

## 新路径是否出现热点

直接遍历既有 `server-{movement,movement_load}.spark.json` 的 `Server thread` 节点；下表为 `ServerChunkPushManager.comparePull` 的 inclusive **采样毫秒**。同一个方法在不同调用路径的样本相加，但不把父子方法相加。每个 profile 约 60.7 秒，采样间隔 4000 微秒。

| 版本 / phase | Before 两臂 | After 两臂 |
|---|---:|---:|
| Forge movement | 104 / 112 | 8 / 12 |
| Forge movement_load | 104 / 92 | 20 / 24 |
| Neo movement | 96 / 80 | 20 / 12 |
| Neo movement_load | 68 / 64 | 12 / 28 |

把 ServerChunkPushManager、ChunkAuthorityHashes、ChunkContentHashUtil、SectionHashCache、PullDeltaAdmission、BlockEntitySnapshot 作为一组，只计每条路径第一次进入该组的 inclusive 样本、避免嵌套重复相加，Forge before 为 116–132 ms/profile、after 16–52 ms/profile；Neo before 92–124、after 28–44。它与减少重复整柱 section hash 的设计一致，但仍只是采样信号，不是用这些毫秒去抵扣 ThreadMXBean CPU 或宣称精确节省。

八个 after 服务端主线程 profile 中，PullSnapshotTailServer / ColumnSnapshotFence / SleepUpdateBuffer / PullUpdatePacketCompat 没有类名采样命中。Render thread 的 PullSnapshotTailClient / ColumnSnapshotFence / SleepTerrainClient / SnapshotContentCommit 合并采样仅 4–24 ms/profile。**零命中不等于零开销**：短于 4 ms 的分散调用、内联、低频尾部 capture/restore 都可能不可见；这些 main-only profile 也没有覆盖 seedgen-main、ChunkPush worker 与 native LIGHT worker。因此当前不能证明新增 tail/fence 没有成本，也不能用它解释多秒 GATE 尾部。

另有一个不属于本次 Pull 准备路径、但应交总报告保留的采样信号：Forge EntityPacketCounters.drainGameInto 的 self 样本，movement 从 before 80/56 ms 到 after 200/128 ms，load 从 92/64 到 136/184 ms。它在两次 after 都增加，较本次 Pull 路径更显著；这里没有进一步修改或扩审实体域，也没有把这一小量 Java 样本直接判成 +0.71/+0.93 pp CPU 的原因。该调用及其后代的采样、整线程 CPU、硬件执行成本是不同指标。

## 阶段数据与源码对照

最后累计直方图的 p95，单位 ms；每格按 before1/before2 → after1/after2。对应原始样本包含 warmup、movement、movement_load 和其间生命周期，不进行不同累计 p95 的相减。

| Stage | Forge | NeoForge |
|---|---:|---:|
| HASH_COMPARE | .655/.688 → .623/.623 | .590/.623 → .557/.524 |
| FULL_SNAPSHOT | .106/.106 → .102/.111 | .094/.086 → .090/.086 |
| NBT_SNAPSHOT | .0056/.0044 → .0056/.0056 | .0266/.0256 → .0215/.0225 |
| DELTA_SNAPSHOT | .311/.238 → .360/.360 | .246/.377 → .229/.426 |
| ENCODE_WAIT | .078/.063 → .061/.059 | .078/.094 → .078/.059 |
| ENCODE_WALL | 1.507/1.573 → 1.245/1.442 | .983/1.180 → 1.114/.950 |

Forge DELTA_SNAPSHOT 有小幅上升，仍为约 .36 ms p95，count 从 67/75 变为 50/56；Neo 也有单臂 .426 ms。没有对应的主线程准备采样热点或 worker 排队 p95 激增证据，不能据此提出收益已知的大改。八臂最终 server counters 中 after 没有 BACKPRESSURE_DEFERRED 记录，Neo after 的 QUOTA_DEFERRED 各为 1；这未给出 byte/job 背压饱和导致 SERVER_QUEUE 上升的证据，不等于已经测得所有队列深度/每项等待原因。

CodeGraph 与当前源码边界核对如下：

- `ServerChunkPushManager.comparePull` 仍在服务端 owner 上查共享 section cache；`ChunkAuthorityHashes.resolveSections` 只重算 dirty section，`SectionHashCache.resolve` 不持表锁进行 section hash。缓存 miss 的 owner hash 没有盲目移交后台读取 live container。
- `preparePull` 的快照只在配额接纳后产生；`PullDeltaAdmission.mustUseFull` 已跳过可确定 FULL 的 DELTA 预备。仍可行的 DELTA 同时保留 detached DELTA 与 FULL fallback packet，这是既有防回退语义，不是本轮新增的第二份 live world 后台读取。worker `encodeClassifiedPull` 先 plan，成功返回 DELTA，失败才编码已捕获 FULL。可考虑未来共享 owner capture/projection，但现在没有大开销证据，不能删 FULL fallback 或在 worker 重读 live chunk 来省这一份。
- UNCHANGED 的 update NBT 采集/纯编码仍在 owner；如上 p95 为微秒级，且原始 block-entity 方法语义要求 owner 读取。没有证据支持为它再引入异步事务/尾部顺序与任务成本。FULL/DELTA 编码及 ZSTD 已在有界 worker，不能把已经异步的编码重新当作新增主线程优化目标。
- `sendPullResponse` 的 owner 回调校验 session/level/请求及 bounded tail；先发 snapshot payload，随后恢复并发送该柱捕获的 native updates，再释放工作租约。Neo sender 的 owner-inline 修复是这个顺序的必要部分，不能重新加第二次 execute 来“减少 owner 工作”。尾部复制/重放是正确性成本，目前没有按 key/包类型的持续性能样本来精确量化。
- SERVER_QUEUE 从 enqueue 到准备开始，包含 ready/lookahead ticket、每 tick hash/FULL/UNCHANGED 配额、待装载与背压等待，并非 ENCODE_WAIT 或 CPU 执行时长。GATE_WAIT 从 `gate(profile)` 到 `promoted(profile)`，等待邻域/引擎阶段而非仅一次 hash 或 NBT。最终 profile 没有将这两条尾延迟按 key/原因绑定到新增 fence、token 或 committed-LIGHT credential，不能据相关时间顺序编造因果。

Forge FULL GATE count 从 1175/1189 变为 792/811，Neo从1173/1165变为789/790；FULL实际响应数则近似保持（Forge708/716→708/718，Neo708/718→704/704）。因此“记录到的 GATE flow 集合”不是逐柱一一配对的同一群体；它限制对 p95 变化的因果解释，**并不撤销 p95 本身的上升**。尚无每 flow 的门状态、重用/重试路径与 warmup 分界，无法判定是等待本身增长、进入统计的路径组合改变，还是两者同时存在。

## 决策与证据边界

本次不修改最终源码/JAR，不开启新 benchmark。保留正确性修复的有界 snapshot/tail、owner 顺序及 LIGHT 阶段凭据；不因测量波动绕过 3×3 门或放宽 stale/session 检查。当前处置为“**无可证新增必修项；保留 CPU 与等待回退及未归因限制**”，而不是“性能无退化”。

此结论限定于每个 loader 两次 before、两次 after 的冻结 ABBA。Java 4 ms 线程采样会漏短调用，包含 park 等状态的 inclusive 时间不能当实际 CPU；主线程采样也看不到后台实际 LIGHT/内容队列。累计阶段包含 warmup，直方图 bucket p95 不能当精确单包延迟。若后续需要因果判定，应在单独授权的新轮次增加有限 per-flow 等待原因/阶段时间窗，先确认热点再改，不能在当前产物上回填不存在的观测。

未来若确证并修改 snapshot/fence/sender/LIGHT 生产路径，当前两个最终 JAR SHA 对应的四个 after 性能臂只能保留为旧 after，必须重新打包并运行新的对应 after；同样需要复核受影响的 FULL/DELTA、sleep/halo 顺序场。当前仅增加本说明，不改变已有运行制品或已接受的实测数据。

## 可复核输入

根目录 `build/analysis/spark-optimization-20261023/perf/` 下：

- Forge：`forge-before-early1b-before`、`forge-final-v3-after1-after`、`forge-final-v3-after2-after`、`forge-final-v3-before2-before`。
- NeoForge：`neo-before-early1-before`、`neo-final-v3-after1-after`、`neo-final-v3-after2-after`、`neo-final-v3-before2-before`。
- 每臂 `summary.json` 的 metrics/chunkPipelineProfile、server/client 两个 phase 的 `*.spark.json`、对应控制 CSV；最终输入与原始文件 hash 见 `final-inputs.json`、`final-v3-SHA256SUMS.txt`。这里没有重新解码或更改原始 JSON/CSV。

源码锚点：`server/ServerChunkPushManager.java` 的 comparePull/preparePull/encodeClassifiedPull/sendPullResponse；`server/ChunkAuthorityHashes.java` 的 resolveSections；`server/SectionHashCache.java` 的 resolve；`server/PullSnapshotTailServer.java` 的 observe/finish；`utils/ChunkPipelineProfile.java` 的 gate/promoted；`shadow/light/ShadowLightCompute.java` 的 submitLightReuseOrGate。
