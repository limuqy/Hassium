# CCD 隔离 ABBA 复测审计（2026-10-23）

本轮仅运行固定 JAR 的性能对照，没有修改或重新构建生产 JAR。八场 runner 均 PASS，双端正常退出，实际 JVM mask 校验通过；工作量与尾延迟限制保留。主结论见 [总报告](../spark-optimization-results-20261023.md)。

## 方法与身份

- Ryzen 9 8940HX，16 核 / 32 逻辑 CPU；Windows RelationCache 探测到两个 32 MiB L3 共享域。服务端 CCD0=`0xFFFF`（0–15），客户端及影子端 CCD1=`0xFFFF0000`（16–31）。启动前继承限制，每场及测量边界校验实际 JVM mask。
- Forge 1.20.1 / 47.4.0 和 NeoForge 1.21.1 / 21.1.216 各按 A1→B1→B2→A2 串行运行，A=旧 Hassium，B=最终 Hassium。每臂 60s 预热，movement / movement_load 各测 60s，后者 256 实体；两个 phase 之间 15s 稳定期。
- 同版每场从同一世界 ZIP 建立独立世界和冷客户端目录；配置输入 SHA 清单同版四臂完全一致。GraalVM JDK 21.0.5，双端 `-Xms2G -Xmx6G -XX:ActiveProcessorCount=16`。使用原 frozen-v3 agent/bridge，4ms Java sampler。
- MSPT 为 `tickServer` 方法的墙钟耗时，不含该方法外的主线程任务；P50/P95/P99 均从各臂原始 `duration_ns` CSV 求 nearest-rank 分位数，再对两臂等权平均。>50ms 计数按两臂相加，不合并分位数，不将 CPU 占比或 Spark sample 占比当性能损失。
- 新输出根：`build/analysis/spark-ccd-abba-20261023/`。`campaign.json` 保存固定输入，`summary.csv` 为逐臂逐阶段数据，`comparison.csv` 为分组对比，`analysis.json` 保留辅助队列和光照快照，`validation.json` 为校验记录。

| 制品 | SHA-256 |
|---|---|
| forgeBefore | `20f0526fca75c972fb6ee68e15f2b1aad1e5cb6fa3c97fbb8b0e9dc7ed9df12a` |
| forgeAfter | `b2d53b47be1bb474a1b0abd8184863474627bae5d015da61822cbdbb4fae4cbf` |
| neoBefore | `30f76029d7cec8c2fa6a8622fb5cec9e1a1093acb52bc1bb17eebde051c60626` |
| neoAfter | `bf5ab94b76bb60e03831319f777ca02868c51d7c0391f84d70f05902ca93d62f` |

Agent SHA-256：`5522a4ebb3e6e97a1339e788fb51a73ffdd913104d3fb09f0d6b3aea065c3e03`；bridge：`8ec73cac3331b925087789ef06df2aebf1427228af20576c4e20746b60ff828b`。本轮使用的 JAR 是 `frozen-inputs/{forge,neo}{Before,After}.jar`；来源路径与哈希保存在 campaign。测试脚本原文另存 `frozen-inputs/source/`。

## 服务端 MSPT 汇总

下表各数值单位 ms，before→after；N=2，百分比取未舍入均值计算。

| 版本 / 场景 | Mean | Mean 变化 | P50 | P95 | P99 | >50ms ticks |
|---|---:|---:|---:|---:|---:|---:|
| Forge 1.20.1 移动 | 1.610→1.539 | -4.40% | 1.383→1.337 | 2.919→2.616 | 7.552→7.518 | 0→0 |
| Forge 1.20.1 移动＋256实体 | 5.392→5.311 | -1.51% | 5.100→5.093 | 7.406→6.757 | 11.653→11.080 | 0→0 |
| NeoForge 1.21.1 移动 | 1.689→1.594 | -5.66% | 1.385→1.384 | 3.554→2.753 | 6.912→5.535 | 0→0 |
| NeoForge 1.21.1 移动＋256实体 | 5.010→4.720 | -5.79% | 4.671→4.453 | 7.051→6.241 | 11.559→10.134 | 0→0 |

共 19,311 个服务端 tick 样本，均未超过 50ms。Forge A1 的实体阶段为 1,206 ticks，其余 15 个服务端阶段各 1,207；所有阶段采样循环速率约 20/s。Forge 移动 P99 变化仅 −0.46%，应视为接近持平。Forge 实体 mean 仅下降 1.51%，两版实体阶段的 B1 mean 均高于本版 A1；较高的 A2 拉高 before 均值，不能把组均值当成逐臂一致收益或统计显著性。

## 逐臂结果与实际进程

每格 mean / P95 / P99，单位 ms。时间为 Asia/Shanghai。所有列出的服务端/客户端 mask 都已实际校验为 `0xFFFF` / `0xFFFF0000`，退出码均 0，无强制结束。

| 顺序 / run | 北京时间起止 | Server PID / Client PID | movement | movement_load |
|---|---|---|---|---|
| 1. `forge-ccd-a1-before` | 09:31:00–09:34:43 | 14348 / 35716 | 1.607 / 2.792 / 7.766 | 5.211 / 7.180 / 11.917 |
| 2. `forge-ccd-b1-after` | 09:34:46–09:38:30 | 18116 / 20608 | 1.571 / 2.592 / 7.372 | 5.455 / 7.018 / 11.379 |
| 3. `forge-ccd-b2-after` | 09:38:32–09:42:14 | 36632 / 36168 | 1.506 / 2.641 / 7.663 | 5.166 / 6.496 / 10.781 |
| 4. `forge-ccd-a2-before` | 09:42:17–09:45:59 | 45896 / 34340 | 1.613 / 3.045 / 7.338 | 5.573 / 7.633 / 11.389 |
| 5. `neo-ccd-a1-before` | 09:46:02–09:49:44 | 48604 / 19336 | 1.639 / 3.099 / 6.320 | 4.727 / 6.262 / 10.441 |
| 6. `neo-ccd-b1-after` | 09:49:47–09:53:26 | 29984 / 18828 | 1.607 / 2.772 / 5.462 | 4.834 / 6.093 / 9.710 |
| 7. `neo-ccd-b2-after` | 09:53:29–09:57:08 | 39604 / 47444 | 1.581 / 2.735 / 5.608 | 4.606 / 6.389 / 10.559 |
| 8. `neo-ccd-a2-before` | 09:57:11–10:00:50 | 23980 / 11568 | 1.740 / 4.009 / 7.505 | 5.293 / 7.841 / 12.677 |

## 工作量与客户端辅助数据

loaded / rendered 为各臂该阶段的中位数；同一格按 A1/A2→B1/B2 排列。client frame 为 runTick 墙钟，不能当端到端显示延迟或 GPU 时间。

| 版本 / 场景 | loaded 中位数 | renderer C: 中位数 | Client frame mean | Client frame P99 |
|---|---|---|---:|---:|
| forge movement | 313/313→298/313 | 211/209→177/208 | 0.699→0.608 | 2.598→1.511 |
| forge movement_load | 313/313→298/313 | 211/210→208/211 | 0.726→0.674 | 3.173→2.347 |
| neoforge movement | 313/313→313/313 | 216/213→215/216 | 0.647→0.637 | 2.442→1.566 |
| neoforge movement_load | 313/313→313/313 | 222/223→217/223 | 0.677→0.662 | 2.958→1.968 |

实体数据＋移动包（movement_load）为 Forge 10,247.4→10,244.7/s，NeoForge 10,278.1→10,279.4/s；客户端编码字节均值分别 1.444→1.427 MiB、1.445→1.441 MiB。全部客户端阶段往返 3 次，名义路线 x=0..224，实际边缘有约 1–2 格 overshoot，距离硬门通过。

**Forge B1 两阶段 loaded 中位数均为 298，其他 Forge 臂均为 313。**其 movement renderer 中位数也从 before 的 211/209 降到 177；B2 为 208。Forge B1 movement 内有 4 次 `NBT snapshot has no material baseline` 后 `retry FULL`，发生在 09:36:19；Neo A2 在 warmup 的 09:57:56 有 10 次同类重试。日志完整保留，不将重试与覆盖差异直接归为同一因果。前者限制对 Forge 客户端及完全等价工作量的性能推断；未删除或替换该臂。

后续已补直接日志并修复 compare 等待失活路径，详见 [加载柱覆盖排查](forge-coverage-fix.md)。原 B1 数字与限制继续保留；新增正确性复测不替换该臂。

## 累计排队与端到端限制

以下为每进程最后一组累计直方图的 p95，单位 ms；包含 warmup、两个阶段与间隔，不是独立 60s 窗口。按 A1/A2→B1/B2 排列。

| 指标 | Forge | NeoForge |
|---|---|---|
| server SERVER SERVER_QUEUE | 121.6/121.6→159.4/104.9 | 96.5/134.2→121.6/125.8 |
| client FULL GATE_WAIT | 973.1/973.1→1208.0/1140.9 | 1208.0/1073.7→1409.3/1208.0 |
| client CACHE GATE_WAIT | 0.2/0.2→26.2/3.7 | 0.1/0.1→26.2/17.8 |
| client FULL REQUEST_RESPONSE | 260.0/234.9→260.0/251.7 | 243.3/335.5→335.5/436.2 |
| client DELTA REQUEST_RESPONSE | 218.1/209.7→209.7/192.9 | 251.7/268.4→1255.9/318.8 |
| client DELTA GATE_WAIT | 3010.6/3034.8→10373.3/3022.0 | 2984.7/3087.0→2997.4/2981.3 |

Forge FULL GATE p95 两个 after 均高于两个 before；Neo FULL request-response p95 也升高。Forge B1 DELTA GATE p95 达 10.373s（52 条累计样本），不能以 MSPT 改善概括为交付延迟全面改善。FULL GATE 样本数本身变化：Forge 1174/1194→831/823，Neo 1179/1192→820/821，统计群体不是逐柱配对；这些观测保留为未归因限制。

## 光照确认快照与辅助 CPU

表中 pending / oldest(ms) 是对应阶段最后一次约 1Hz 快照，不能当退出后的 quiescence。

| After 臂 | high-water | movement 末 pending / oldest | load 末 pending / oldest |
|---|---:|---:|---:|
| forge-ccd-b1-after | 214 | 0 / 0.000 | 104 / 23.705 |
| forge-ccd-b2-after | 191 | 0 / 0.000 | 0 / 0.000 |
| neo-ccd-b1-after | 194 | 1 / 896.922 | 1 / 132.548 |
| neo-ccd-b2-after | 236 | 0 / 0.000 | 2 / 541.661 |

四个 after 均无 API 错误或 overflow，但末尾仍有未结清票据，尤其 Forge B1 load 最后快照有 104 张新票（最老 23.705ms）。未记录票据身份/等待原因，不能认定全部结清，也不能仅据该瞬间计数判永久卡死。所有 before 的 API 缺失记为 NA，不能当零。

服务端主线程 CPU（辅助）两臂均值：Forge movement 2.90%→5.07%、load 10.55%→12.93%；Neo movement 4.52%→4.39%、load 10.96%→10.12%。这是整测量期间的线程 CPU/墙钟，范围大于 tickServer 插桩，不参与 MSPT 收益计算。

## 验收边界与归档

另按原始 CSV 重算全部 32 个 side/phase metric 行的 count、mean、nearest-rank P50/P95/P99、>50ms 数，与 summary 一致；32 份 raw profile 与 32 份解码 JSON 齐备，采样间隔均 4ms，线程名称符合双端。八场实际安装 mods 哈希与输入清单一致，同版配置输入完全一致；所有控制 ACK 无 ERROR，双端退出码均 0。`validation.json` 的状态为 `COMPLETED_WITH_LIMITATIONS`，区别于 runner 的 PASS。

总报告只引用本轮四组样本均值，不与旧未隔离 CCD 的八场合并，也不把旧 correctness 独立审查冒称为本轮重新审查。N=2、有限路线/单客户端、Forge B1 覆盖差异、NBT 重试、累计阶段尾延迟与残留确认票限制仍保留。较低 MSPT 本身不证明没有交付回退或普遍显著提升。

固定输入与原始证据位于上述输出根；SHA256SUMS 清单覆盖报告快照、campaign/validation/summary/comparison、工具/JAR、逐臂输入、控制数据、原始/解码 profile、日志、配置及实际 mods。校验结果另存 `hash-verification.json`。
