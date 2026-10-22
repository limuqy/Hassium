# 50 / 20 / 10 ms 聚合封批窗口对照（2026-10-22）

> 后续：[高压三轮复测与停止决定](egress-window-stress-20261023.md)。2026-10-23按用户决定停止后续场次，默认仍为50 ms；旧低压结果不作为主要资源优化依据。

已完成六场实跑，目标为 `2.0.0` 的 1.20.1 Forge / 1.21.1 NeoForge。六场均 PASS，每场两个独立客户端均逐包验证 36,480 条消息、顺序与执行次数，每连接完成 141 次发送回调，协议错误为 0。默认窗口仍为 50 ms；本次只增加测试参数与计量/汇总工具，没有调整业务封批策略。

## 主结果

主对照区间为 200 tick 预热结束到 40 tick 排空完成，约 22 秒，含 200 tick 稳态和 200 tick 突发。TCP 为两连接合计的成功写出载荷，含原版 framing/compression 前缀，不含 IP/TCP 头、重传；背景原版流量也包含在内。CPU 为该区间累计值，worker 是服务端 Hassium-EgressV3 池，进程 CPU 包含整个服务端，不含客户端 JVM。

P95/P99 为每个成功 PLAY 子包从原生编码字节进入出口到写完成的直方图桶上界，不含原生编码、RTT 或客户端执行。主表已排除预热，不能与上一轮包含预热的全窗口统计直接比较。

| 组合 | 窗口 ms | TCP KiB | 聚合帧 | worker CPU ms | 服务端进程 CPU s | 出口 P95 ms | 出口 P99 ms |
|---|---:|---:|---:|---:|---:|---:|---:|
| 1.20.1 forge | 50 | 843.7 | 1179 | 265.6 | 5.984 | 54.53 | 54.53 |
| 1.20.1 forge | 20 | 854.1 | 1587 | 921.9 | 7.156 | 23.07 | 23.07 |
| 1.20.1 forge | 10 | 844.7 | 1583 | 812.5 | 6.547 | 12.58 | 13.63 |
| 1.21.1 neoforge | 50 | 804.0 | 1126 | 359.4 | 5.812 | 54.53 | 54.53 |
| 1.21.1 neoforge | 20 | 865.3 | 1607 | 1125.0 | 7.609 | 23.07 | 25.17 |
| 1.21.1 neoforge | 10 | 833.3 | 1608 | 937.5 | 7.000 | 12.58 | 13.63 |

相对同加载器的 50 ms：

| 组合 | 窗口 ms | TCP 变化 | worker CPU 变化 ms | 进程 CPU 变化 s | 出口 P99 变化 |
|---|---:|---:|---:|---:|---:|
| 1.20.1 forge | 20 | +1.24% | +656.2 | +1.172 | -57.7% |
| 1.20.1 forge | 10 | +0.11% | +546.9 | +0.562 | -75.0% |
| 1.21.1 neoforge | 20 | +7.62% | +765.6 | +1.797 | -53.8% |
| 1.21.1 neoforge | 10 | +3.65% | +578.1 | +1.188 | -75.0% |

## 判断

两种加载器下，10 ms 均把本轮出口 P99 从 54.53 ms 降到 13.63 ms，约降低 75%。相对 50 ms，10 ms 的 TCP 增幅分别约 0.11% / 3.65%，但 worker CPU 分别增加约 547 / 578 ms（约 22 秒窗口）；整体服务端进程 CPU 分别增加约 0.56 / 1.19 秒。更短等待伴随更多小批次和更高编码分配成本。

本轮 20 ms 没有表现出相对 10 ms 的流量或 CPU 优势，因此当前数据更支持将 10 ms 作为低延迟候选，50 ms 作为 CPU/省流优先的参考；没有依据优先选 20 ms 作为折中。每档仅一场，不能将 20/10 ms 的小 CPU 差值解释为确定的效率排序，也不能把一类合成负载外推为全部整合包收益。更改默认值前应对选中的候选进行重复及真实业务负载验证；本轮按用户要求停在对照结果，不修改默认。

## 稳态与突发

阶段分位数由阶段边界的累计直方图桶相减后重新计算；没有相减累计 P95/P99。边界附近在途包按完成时间归属，快照近似一致。每阶段为 200 tick，约 10 秒。

| 组合 | 窗口 ms | 阶段 | TCP KiB | 帧数 | worker CPU ms | 出口 P95 ms | 出口 P99 ms |
|---|---:|---|---:|---:|---:|---:|---:|
| 1.20.1 forge | 50 | steady | 344.1 | 520 | 203.1 | 54.53 | 54.53 |
| 1.20.1 forge | 50 | burst | 492.4 | 570 | 62.5 | 54.53 | 54.53 |
| 1.20.1 forge | 20 | steady | 360.6 | 899 | 703.1 | 23.07 | 23.07 |
| 1.20.1 forge | 20 | burst | 486.4 | 601 | 156.2 | 23.07 | 23.07 |
| 1.20.1 forge | 10 | steady | 354.2 | 880 | 750.0 | 13.63 | 13.63 |
| 1.20.1 forge | 10 | burst | 482.5 | 605 | 46.9 | 12.58 | 12.58 |
| 1.21.1 neoforge | 50 | steady | 319.7 | 516 | 218.8 | 54.53 | 54.53 |
| 1.21.1 neoforge | 50 | burst | 478.3 | 535 | 140.6 | 50.33 | 54.53 |
| 1.21.1 neoforge | 20 | steady | 352.1 | 895 | 781.2 | 23.07 | 25.17 |
| 1.21.1 neoforge | 20 | burst | 503.7 | 627 | 265.6 | 23.07 | 23.07 |
| 1.21.1 neoforge | 10 | steady | 337.5 | 896 | 656.2 | 12.58 | 13.63 |
| 1.21.1 neoforge | 10 | burst | 487.0 | 622 | 234.4 | 11.53 | 12.58 |

## 条件与计量边界

- 相同加载器各场从同一份只读世界 ZIP 恢复到新的 `smoke_fixture_*` 目录；原始 `parity_*` 存档未被本次游戏写入。字典文件固定，开始和结束的 SHA-256 相同；两加载器各用自己的原有 64 KiB 字典，不跨版本混用。输入哈希见 [inputs](egress-window-inputs-20261022.json)。
- 场景为 `egressload`，双客户端；预热每连接 16 包/tick，稳态 64 包/tick，突发每五 tick 各发 512 包。level 3，最大批次 262144 B，VD/RD 4；关闭同步 JSONL 导出。每场阶段日志同时核对实际生效的配置。
- CPU 为 AMD Ryzen 9 8940HX，32 个逻辑处理器，服务端聚合 worker 固定为 8。Forge 按 50→20→10 ms，NeoForge 按 10→20→50 ms，全部串行。不同加载器应各自对照，不能直接按绝对字节或 CPU 做加载器优劣排名。
- CPU 使用阶段边界的累计 worker 线程 CPU 与进程 CPU 差值；不使用逐小作业 CPU 差值累加作为主结果。Windows CPU 时钟仍有约 15.6 ms 量化，多个 worker 时钟相减及单场启动/JIT/调度差异都限制小差值的解释。进程 CPU 包含后台世界、夹具、JIT、GC 等工作，不等于 codec CPU。
- 固定的是夹具消息量和初始输入，背景原版包、会话字节、计时和模板/历史轨迹会随运行变化；完整原始编码字节和包数见 CSV。不能将所有出口差异都归因于批次大小。
- 直方图为每数量级八个桶，表中是桶上界；不同阶段样本单位不同，P95/P99 不能相加。客户端原生交付、解压等数据单列在 stages/runtime 报告，覆盖客户端完整窗口，未声称测得 RTT 或客户端主线程执行完成延迟。
- XERJ 参考为 Fortio `67ce9fa2a3a62fdb2f7a0d2b50acda1f7002d32b` 的 [`stats/stats.go:307`](https://github.com/fortio/fortio/blob/67ce9fa2a3a62fdb2f7a0d2b50acda1f7002d32b/stats/stats.go#L307)。实际文件头及 LICENSE 均为 Apache-2.0；本轮沿用本项目的直方图桶边界，没有复制其插值实现。

## 会话与复现

| 版本 | 加载器 | 窗口 ms | SessionId | 结果 |
|---|---|---:|---|---|
| 1.20.1 | forge | 50 | `egress_wait_1201_forge_50_r1_20261022` | PASS |
| 1.20.1 | forge | 20 | `egress_wait_1201_forge_20_r1_20261022` | PASS |
| 1.20.1 | forge | 10 | `egress_wait_1201_forge_10_r1_20261022` | PASS |
| 1.21.1 | neoforge | 50 | `egress_wait_1211_neoforge_50_r1_20261022` | PASS |
| 1.21.1 | neoforge | 20 | `egress_wait_1211_neoforge_20_r1_20261022` | PASS |
| 1.21.1 | neoforge | 10 | `egress_wait_1211_neoforge_10_r1_20261022` | PASS |

复现时修改 SessionId，避免覆盖原始证据；`-WorldZip` 指向同加载器的输入快照，先核对字典哈希。三个窗口均通过同一参数注入，须串行执行：

```powershell
.\scripts\runtime-smoke-test.ps1 -Ver 1.20.1 -Loader forge -Phase I -Scenario egressload -SessionId <new_session> -AggregationMaxWaitTimeMs 10 -Vd1 4 -Vd2 4 -ClientRenderDistance 4 -WorldZip D:/project/MC/Hassium/build/analysis/egress-window-20261022/world-forge.zip -Offline
.\scripts\runtime-smoke-test.ps1 -Ver 1.21.1 -Loader neoforge -Phase I -Scenario egressload -SessionId <new_session> -AggregationMaxWaitTimeMs 10 -Vd1 4 -Vd2 4 -ClientRenderDistance 4 -WorldZip D:/project/MC/Hassium/build/analysis/egress-window-20261022/world-neoforge.zip -Offline
python scripts/smoke/egress_window_report.py <SessionId>... --output-dir build/analysis/egress-window-20261022/report
```

主汇总：[summary CSV](egress-window-summary-20261022.csv)。分阶段及客户端统计（stages CSV / runtime JSON）、可重算区间分位数的原始阶段桶与 CPU/配置快照（phases JSONL）已归档到本机 `build/analysis/egress-window-20261022/evidence/`，保留原文件名，不纳入 Git。完整 harness、服务端/客户端日志、探针和结果留在 `build/analysis/egress-window-20261022/` 及 `build/smoke-test/`。

## 检查与收尾

基线提交 `39ac77d1f9677544ce97d6e88434377f40a4206a`；新增测试窗口覆盖参数、阶段累计桶、阶段 CPU 快照及汇总工具。1.20.1 common:test 共 552 项、1.21.1 共 545 项，各有 2 项既有跳过，均无失败/错误；对应 Forge/NeoForge 编译及版本边界扫描通过。Python 汇总/冒烟分析测试 49 项通过，PowerShell 语法检查与 git diff --check 通过。没有调整生产默认值。

六个游戏会话均由 harness 收尾，25565 已释放。四份运行目录 TOML 已从测试前备份逐字节恢复并校验哈希；字典与输入 ZIP 未变。2026-10-23收尾时清理了可重建的各场独立世界及观察者运行目录；输入ZIP、字典及原始日志仍保留，详见[清理记录](egress-window-stress-20261023.md)。
