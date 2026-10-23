# Hassium 主线程优化验证结果（2026-10-23）

本轮按 CCD 隔离重跑 **1.20.1 Forge / 1.21.1 NeoForge 各四场 ABBA**，八场 runner 均 PASS。以服务端 MSPT 为主，四种组合的两臂 mean 下降 **1.51%–5.79%**，P95/P99 的组均值均下降，19,311 个测量 tick 均未超过 50ms。Forge movement 的 P99 仅下降 0.46%，接近持平；Forge 实体场景 mean 仅下降 1.51%。

**结论受工作量与交付限制约束**：Forge B1 的 loaded-chunk 中位数为 298，其余 Forge 臂为 313；同场有 NBT 基线缺失后重试 FULL，累计光照门等待也有上升。N=2 的样本均值改善不能证明等价工作量下普遍、显著提升，也不能概括成端到端交付全面改善。CPU 保留为辅助诊断。新八场、旧未隔离八场与 no-Hassium 基线分别保存。

## CCD 隔离 ABBA：本轮主结果

本次仅重测固定 JAR，没有修改或重新构建生产制品。A=旧 Hassium，B=上一轮最终 Hassium；每版按 **A1→B1→B2→A2** 串行运行。Ryzen 9 8940HX 的两个实际 L3 共享域各 32 MiB：服务端 CCD0=`0xFFFF`（逻辑 CPU 0–15），客户端及影子端 CCD1=`0xFFFF0000`（16–31）。八场均使用 `Required`，在 JVM 启动前继承限制，测量边界验证实际 mask，双端退出码均为 0。

使用原 frozen-v3 agent/bridge、同一 JDK 21.0.5、双端 2–6 GiB heap / ActiveProcessorCount=16。每臂 60s 预热，movement 与 256 实体 movement_load 各测 60s；同版四臂的世界 ZIP、配置输入与工具固定。MSPT 是 `tickServer` 墙钟耗时，未覆盖该方法外的主线程任务；各臂先独立求 mean / nearest-rank P95/P99，再等权平均，>50ms 数按两臂相加。

| Loader / 场景 | MSPT mean ms | Mean 变化 | MSPT P95 ms | MSPT P99 ms | >50ms ticks |
|---|---:|---:|---:|---:|---:|
| Forge movement | 1.610 → 1.539 | −4.40% | 2.919 → 2.616 | 7.552 → 7.518 | 0 → 0 |
| Forge movement + 256 entities | 5.392 → 5.311 | −1.51% | 7.406 → 6.757 | 11.653 → 11.080 | 0 → 0 |
| NeoForge movement | 1.689 → 1.594 | −5.66% | 3.554 → 2.753 | 6.912 → 5.535 | 0 → 0 |
| NeoForge movement + 256 entities | 5.010 → 4.720 | −5.79% | 7.051 → 6.241 | 11.559 → 10.134 | 0 → 0 |

Forge A1 的实体阶段为 1,206 ticks，其余服务端阶段各 1,207；循环速率均约 20/s。两版实体阶段的 B1 mean 均高于本版 A1，第二次 B2 较低，A2 又高于 A1，因此不声称逐臂一致改善或统计显著性。

### 工作量与仍需保留的限制

- 实体数据＋移动包为 Forge **10,247.4→10,244.7/s**、NeoForge **10,278.1→10,279.4/s**。NeoForge 两阶段 loaded 中位数均为 313；Forge A1/A2/B2 均为 313，**B1 两阶段为 298**，其 movement renderer 中位数也较低。该臂保留，不能把其客户端 frame 改善全部归为同等工作量提速。
- Forge B1 movement 内出现 4 次 `NBT snapshot has no material baseline` 后 retry FULL；Neo A2 warmup 有 10 次同类重试。覆盖差异与重试之间尚未建立因果。无 Light timeout、ERROR、ClassNotFoundException 或 outbound overflow 命中。
- 最后一组累计 **Forge FULL GATE p95 为 973/973→1208/1141ms**，两个 after 均升高。SERVER_QUEUE p95 为 Forge 122/122→159/105ms、Neo 96/134→122/126ms；本轮没有重复旧轮 Neo 两个 after 都高于两个 before 的模式。**Neo FULL request-response p95 为 243/336→336/436ms**，Forge B1 DELTA GATE p95 达 10.373s。它们包含 warmup 与测量期，GATE 统计群体大小也变化，保留上升与未归因限制。
- after 的光照确认快照无 API 错误或 overflow；但 load 最后一次周期快照 pending 为 Forge B1/B2 **104/0**、Neo B1/B2 **1/2**，最老票分别为 23.705/0、132.548/541.661ms，不能写成退出时均已结清。旧 API 缺失仍记 NA。
- 服务端主线程 CPU（辅助）为 Forge movement/load **2.90%→5.07% / 10.55%→12.93%**，Neo **4.52%→4.39% / 10.96%→10.12%**；范围大于 tickServer 耗时，未用于计算 MSPT 收益。

完整逐臂 MSPT/P50/P95/P99、JVM PID/mask、客户端指标、队列与光照快照见 [CCD ABBA 审计](spark-optimization-20261023/ccd-abba.md)。原始数据在 `build/analysis/spark-ccd-abba-20261023/`；[逐臂 CSV](../../build/analysis/spark-ccd-abba-20261023/summary.csv)、[分组 CSV](../../build/analysis/spark-ccd-abba-20261023/comparison.csv)、[校验记录](../../build/analysis/spark-ccd-abba-20261023/validation.json)及 SHA256SUMS 清单可复核。32 行指标已按原始 CSV 另算核对；本轮审计状态为 `COMPLETED_WITH_LIMITATIONS`，八场 runner 的 PASS 不覆盖上述等价工作量与交付限制。

## Forge 覆盖后续排查

已补关键路径日志，并修复“旧 compare 被取消、AWAITING 保留后扫描永久跳过”的失活路径。最终 Forge / NeoForge 常规复测两阶段 loaded 中位数均为 313；原 B1 的完整 298 现场尚未自然复现，不能唯一追溯其全部缺柱原因。状态级回归、可控取消回归及制品身份见 [加载柱覆盖排查](spark-optimization-20261023/forge-coverage-fix.md)。本次正确性复测不替代原 ABBA，也不更新其性能收益。

## 被测实现

- 客户端光照确认从 renderer 每帧扫描注入表改为影子主循环中的有界脏票队列，加入同柱合并、overflow 增量扫描和软预算公平轮换；3×3 光照门与既有落盘、推送判据保留。
- 服务端 Pull 按 dirty section 增量刷新共享 hash，并在确定会回退 FULL 时跳过无效的 DELTA 准备。Pull worker 使用有界预算；新增同柱版本围栏与原版更新尾部回放，避免旧快照覆盖新 block/BE 状态。
- 实体配置解析按不可变配置身份缓存；非影子 ChunkMap 保存 RETURN 快速返回；TickMonitor 关闭 dispatcher 日志时跳过字符串格式化。密度翻页、包计数 drain、压力表清理均保留原 tick 顺序。
- 不把活跃 PalettedContainer、BlockEntity、客户端可见世界或影子 POI/注册表任意交给后台线程。后台只处理所有者捕获的独立快照；结果回 owner 后校验版本并按序提交。

详细候选处置见 [disposition.md](spark-optimization-20261023/disposition.md)；Pull/hash 与快照性能证据及保留回退见 [perf-disposition.md](spark-optimization-20261023/perf-disposition.md)。实现、审查及正确性边界分别见 [light-client.md](spark-optimization-20261023/light-client.md)、[server-pull.md](spark-optimization-20261023/server-pull.md)、[small-hotpaths.md](spark-optimization-20261023/small-hotpaths.md) 和 [review.md](spark-optimization-20261023/review.md)。

## ABBA 冻结制品与构建锚点

源锚点为 `2.0.0` commit `edc4fd7213b10117aecd4e6b992a9fa2932d2757`，构建时工作树含本轮改动。正式性能臂使用 remap JAR；正确性 smoke 多为逐场 dev 输出，不能把它们表述成全部使用同一个打包 JAR。

| 目标 | 最终性能 JAR | SHA-256 |
|---|---|---|
| Forge 1.20.1 | [forgeAfter.jar](D:/project/MC/Hassium/build/analysis/spark-ccd-abba-20261023/frozen-inputs/forgeAfter.jar) | `B2D53B47BE1BB474A1B0ABD8184863474627BAE5D015DA61822CBDBB4FAE4CBF` |
| NeoForge 1.21.1 | [neoAfter.jar](D:/project/MC/Hassium/build/analysis/spark-ccd-abba-20261023/frozen-inputs/neoAfter.jar) | `BF5AB94B76BB60E03831319F777CA02868C51D7C0391F84D70F05902CA93D62F` |

R12 focused 回归、R13 专项测试、两重点 loader 编译、`scanVersionBoundaries`、七段编译锚点及 native packaging verifier 均通过；R13 未声称重跑 R12 fullfocused 矩阵。额外覆盖 Fabric 的 1.20.1、1.21.1、1.21.11 SPI 编译；Forge 仅在有 userdev 的版本构建。完整证据、日志和逐场 manifest 见 [最终验证记录](spark-optimization-20261023/validation.md)，其哈希见 [validation-artifacts.sha256](spark-optimization-20261023/validation-artifacts.sha256)。

## 历史结果：未隔离 CCD 的 frozen-v3 八场

以下保留旧轮结果，与上面的新 CCD 八场不合并。旧轮最终 JAR 相对旧 Hassium JAR 的 server loop 均值、client frame 均值和两端 loop P99 在四种组合中均下降；平均主线程 CPU 未全面下降，Forge 两场景均上升。包量和 loaded-chunk 中位数接近基线，但 Forge FULL GATE_WAIT 与 Neo SERVER_QUEUE 的累计 p95 在两个 after 臂都上升，暂未归因。N=2 不能证明统计显著提升；no-Hassium→旧 Hassium 的画像也属于另一轮基线。

每版顺序为 before→after→after→before；每臂含 60 秒预热、movement 和 256 实体 `movement_load` 两个各 60 秒测量阶段。`N=2` 是两份臂结果的算术平均，不是置信区间。CPU 为相应主线程 CPU/墙钟；P99 为各臂 loop-duration P99 的均值，单位 ms。mean loop/frame 是 harness 控制循环的平均时长，不是端到端呈现延迟。

| Loader / 场景 | Server MSPT mean ms | Server main CPU（辅助） | Client mean frame ms | Client main CPU（辅助） | Render-thread CPU ms/loop |
|---|---:|---:|---:|---:|---:|
| Forge movement | 2.200 → 2.062 | 5.77% → 6.49% | 0.694 → 0.662 | 97.15% → 97.90% | 0.678 → 0.651 |
| Forge movement + 256 entities | 6.776 → 6.585 | 14.29% → 15.22% | 0.715 → 0.686 | 97.12% → 97.55% | 0.698 → 0.672 |
| NeoForge movement | 2.248 → 2.081 | 6.28% → 6.25% | 0.656 → 0.616 | 97.54% → 97.63% | 0.643 → 0.605 |
| NeoForge movement + 256 entities | 6.409 → 6.105 | 13.68% → 13.99% | 0.686 → 0.644 | 97.48% → 97.44% | 0.672 → 0.631 |

四种组合的 server/client loop P99 (ms) 均为：

| Loader / 场景 | Server MSPT P99 (ms) | Client loop P99 (ms) |
|---|---:|---:|
| Forge movement | 10.60 → 8.95 | 2.69 → 1.58 |
| Forge movement + 256 entities | 14.28 → 13.77 | 3.33 → 2.17 |
| NeoForge movement | 8.85 → 7.35 | 2.68 → 1.58 |
| NeoForge movement + 256 entities | 13.99 → 13.15 | 3.23 → 2.06 |

server mean loop、client mean frame 与 render-thread CPU/loop 均为两臂均值；每臂 server loop 计数 1207，客户端每臂约 82k–98k 个 frame/loop 样本。render-thread CPU/loop 包含 harness loop 边界，不代表 GPU 时间或最终显示延迟。主线程 CPU 百分点变化为 Forge movement/load +0.71/+0.93，NeoForge -0.03/+0.31；因此平均主线程 CPU 并未全面下降。

实体数据与移动包合计约 10.2k/s：Forge 10,247→10,244/s，NeoForge 10,277→10,279/s；两类分别约 5.12k/s。客户端 `movement_load` 编码字节为 Forge 1.39→1.36 MiB，NeoForge 两个 before 平均 1.43→1.40 MiB。服务端字节数随区块发送量明显波动，未解释为稳定节省或丢包。Forge 的 `EntityPacketCounters.drainGameInto` self 采样两次 after 高于两次 before，但总样本小且不是纯 CPU 成本，不能据此归因主线程 CPU 百分点上升；未改其计数切面语义，详见性能处置说明。

Loaded-chunk 中位数范围在 Forge movement 为 311–312→311、load 为 312→312；NeoForge movement 为 311–313→312、load 为 312–313→313。renderer `C:` 中位数范围也接近但不相同（见 validation）；因此这些数据只支持“未观察到 after 持续减少”，不证明全视野覆盖完全等价。loop/frame 均值与 P99 在本窗口降低，平均主线程 CPU 变化方向不同，重复数有限且没有统计显著性检验。

### 等待与队列限制

服务端在 movement+load 生命周期累计的 queue stage p95/p99：Forge before 130–159/319 ms，after 143/268–302 ms；NeoForge 两场 before 的 SERVER_QUEUE p95 为 130.0/117.4 ms，after 为 184.5/167.8 ms，两个 after 臂均高于两个 before 臂。Forge FULL GATE_WAIT p95 也从 before 的 1,140.9/1,208.0 ms 升到 after 的 1,744.8/2,147.5 ms。两组都是 N=2、profile 包含 warmup 和测量期的累计阶段数据；上升真实可见但尚未归因，不能用“宽范围重叠”或其他阶段的下降抹掉，也不能据此证明系统性回归。客户端 GATE/INGEST 另有多秒长尾，不能概括成所有队列阶段都无退化。NeoForge after2 DELTA request-response p95 为 1.526 s（38 个样本），after1 为 302.0 ms；旧 before2 的 DELTA p99 为 1.50 s，是不同分位数，不作等价对照。

光照确认快照 high-water：Forge after1/after2 为 177/178，最终 pending=0；NeoForge 为 222/268，overflow 和 API 错误均为 0。准确阶段限制是：NeoForge after movement 结束时 after1 有 1 张、886 ms，after2 为 0；movement_load 结束时两臂各 1 张、301/306 ms。快照未记录票据 identity/state/reason，不能确认它在等什么，也不能写成已结清。旧 JAR 不支持该 API，harness 只记一次 capability `NA`；`NA` 不等于零。

## 既有正确性验收与失效证据

本轮 CCD 重测沿用固定 JAR 与此前正确性证据，未重新执行下面的完整 correctness 矩阵或独立源码审查。

R12 两锚点 fullfocused 回归通过；R13 另通过专项测试和双 loader 编译，不声称重跑 fullfocused 矩阵。限定正确性场景包括：双版精确 FULL/DELTA 正例；same-hash 且不修 NBT；运行时关闭 chunk 与客户端影子引擎失败时的 native fallback；sleep/resume；OW→Nether→End→OW；移动屋檐/洞穴与光源检查。另有 Forge R13 strict-gated halo 场证明同一完整凭据在真实尾部/ACK 后仍能完成延后 LIGHT；Neo 自然尾部续 LIGHT 的记录来自 `pullordersame_1211_neo_r11stable1-pass`。

FULL legacy 负对照在两版以完整 key/request/token/身份链确认：新 DIAMOND 状态被旧 FULL 覆盖成 STONE，预期断言失败作为有效因果负例。DELTA legacy 负对照没有运行。早期因为邻柱只匹配 x 坐标而误触发目标完成检查的旧 PASS 已撤回；较早 same-hash 后 `global inflight=6` 的旧归档也无法证明六项身份和释放。后续 R9 精确事务场和 R10 有界 quiescence 场提供新证据；没有把旧污染 PASS 追认。

历史 correctness/run 证据的阶段边界、失败含义和归档位置见 [validation.md](spark-optimization-20261023/validation.md) 与 [独立审查](spark-optimization-20261023/review.md)。旧轮正式 8 臂输入、profile、控制 CSV、场景 CSV 和 SHA 清单位于 `build/analysis/spark-optimization-20261023/perf/`；此前独立最终数据复核已通过。本轮新 CCD 数据与校验单独归档，仍保留两次重复、有限路线/单客户端、覆盖差异、阶段等待及确认票观测限制。
