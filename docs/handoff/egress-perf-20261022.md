# 聚合链路分配优化与分阶段测量（2026-10-22）

已完成，目标分支 `2.0.0`。基线为 `8a9ebbfbe05f2ce158fb1921689aae95ff1772cc`；修改前通过 Gradle 编译，旧出口 classes 与源码保存在 `build/analysis/egress-perf-20261022/baseline`、`source`。首轮设计和真实客户端验收使用 1.20.1 Forge / 1.21.1 NeoForge。

## 实现

1. **语料复用**：codec 通过内部 observer 交出已经生成的不可变逻辑批次。字典采样钩子仍在编码完成后、提交发送前调用，保留原有采样资格、大小上限和异步落盘门；不再逐包克隆、拼接一份往往不用的原始语料。采样接收方可以保留，不能修改数组。
2. **数组所有权**：native ByteBuf 仅复制到一份新数组，随后由内部 `PacketBytes.owned` 接管。公共构造器和 `bytes()` 仍防御性复制；原始 ByteBuf 按原有生命周期释放。原生 Mod codec 继续只编码一次。
3. **候选装配**：按完整头部的准确 VarInt/VarLong 长度比较候选，保留原有模式和相等时的优先级，只装配胜出的完整帧。逻辑载荷按准确大小预分配。模板定义成本直接由字段长度计算，排序收益只计算一次；查询索引在事务提交后失效，没有引用或盈利定义时提前返回。事务提交、历史更新和协议字节不变。
4. **阶段测量**：`EgressProfiler` 用固定数量的直方图桶记录等待、处理和写完成分布，另用 ThreadMXBean 记录当前线程 Java 堆分配。默认关闭；`egressload` 专项自动启用，其他开发场景可设置游戏 JVM 属性 `hassium.egressProfile=true`。范围及分位数上界语义见 [协议文档](../egress-v3.md)。

编码前按仓库要求查询 XERJ，参考 [Fortio `67ce9fa2` 的 `stats/stats.go:307`](https://github.com/fortio/fortio/blob/67ce9fa2a3a62fdb2f7a0d2b50acda1f7002d32b/stats/stats.go#L307) 按分布报告分位数的做法。实际源文件头和 LICENSE 为 Apache-2.0；本项目采用独立实现的有界对数分桶，未复制 Fortio 代码。

## 离线配对结果

四组输入各 180 帧，每次运行新建 codec 状态，丢弃一遍预热后测四遍。输入覆盖短实体类数据、较大 Mod 状态、不可压缩数据和模板负载，并跨越序号、会话代、字典代的变长整数边界。旧/新实现分别从归档 classes / 当前源码加载，串行运行；解码逐包核对内容与顺序，所有选中帧的长度与 SHA-256 完全一致。最终复跑会读取基线 CSV，任何选中帧差异直接失败。

| 负载 | 原 Java 分配 MiB | 新 Java 分配 MiB | 降幅 | 选中内部帧总字节（两者相同） |
|---|---:|---:|---:|---:|
| tiny | 29.99 | 26.24 | 12.5% | 12,712 |
| mod | 169.35 | 75.49 | 55.4% | 49,841 |
| random | 222.11 | 112.52 | 49.3% | 11,802,113 |
| templates | 324.87 | 137.38 | 57.7% | 121,001 |

原始数值与帧摘要：[修改前](egress-perf-before-20261022.csv)、[修改后](egress-perf-after-20261022.csv)。分配量为每组一遍的四遍平均值，包含 native 字节自有化模拟、真实 codec 与非采样路径；不包括输入生成、原生 Mod codec、解码核对、摘要计算、native/Zstd 内存或其他线程。JIT/逃逸分析可使跨进程计数略有变化。墙钟结果没有在每组负载上一致改善，不能由这些分配降幅推导服务器总 CPU、GC、TPS 或在线延迟改善。

复现（绝对路径按 checkout 调整；不要同时运行）：

```powershell
.\gradlew.bat common:runJava "-Pmc_ver=1.21.1" "-PmainClass=io.github.limuqy.mc.hassium.protocol.egress.EgressAllocationReplay" "-PprependClasspath=D:/project/MC/Hassium/build/analysis/egress-perf-20261022/baseline" "-Pargs=D:/project/MC/Hassium/build/analysis/egress-perf-20261022/before.csv" --offline
.\gradlew.bat common:runJava "-Pmc_ver=1.21.1" "-PmainClass=io.github.limuqy.mc.hassium.protocol.egress.EgressAllocationReplay" "-Pargs=D:/project/MC/Hassium/build/analysis/egress-perf-20261022/after.csv,D:/project/MC/Hassium/docs/handoff/egress-perf-before-20261022.csv" --offline
```

## 真实加载器双连接与突发负载

`egressload` 通过同一 smoke harness 启动两个独立客户端 JVM，分别使用独立用户名、runDir 和 probe。双方完成真实登录/Play/聚合激活后，运行 200 tick 预热、200 tick 稳态（每连接每 tick 64 包）、200 tick 突发（每五 tick 每连接 512 包）、40 tick 排空。夹具使用原生加载器注册的类型化 State/Event，包含 biome holder、变长字符串、列表和一次性事件；逐包检查内容、顺序和执行次数，并保留编码器单次执行断言。

| 组合 | 有效会话 | 每客户端已验证消息 | 每连接已完成回调 | 协议错误 | harness |
|---|---|---:|---:|---:|---|
| 1.20.1 Forge | `egress_perf_1201_forge_r2_20261022` | 36,480 | 141 | 0 | PASS |
| 1.21.1 NeoForge | `egress_perf_1211_neoforge_20261022` | 36,480 | 141 | 0 | PASS |

两组均使用 level 3、50 ms 等待、262,144 B 批次、VD/RD 4，关闭同步调试导出，串行运行。语料字典从既有测试配置加载。世界背景原版流量也进入指标；fixture 计数独立，不用总包数反推消息数。性能统计包含仪表及夹具开销。服务器起始统计复位与仍在完成的激活帧可能重叠，快照计数不作为精确流量会计；发送量和回调门禁来自每连接独立状态。

以下为服务端完整负载窗口的 P95 桶上界，单位 ms；每包/每批次的样本单位不同，不能逐项相加：

| 阶段 | 1.20.1 Forge | 1.21.1 NeoForge |
|---|---:|---:|
| 封批等待 `BATCH_WAIT` | 54.526 | 54.526 |
| 后台排队 `WORKER_QUEUE` | 0.082 | 0.074 |
| 编码择优 `CODEC` | 1.704 | 1.704 |
| 语料钩子 `SAMPLE` | 0.0041 | 0.0036 |
| 入出口至成功写完成 `OUTBOUND_TOTAL` | 54.526 | 54.526 |

本窗口的已有字典、非采样目标路径中，`SAMPLE` 的 Java 分配为 0，且 allocationSamples 分别为 1,628 / 1,668，不是以「未支持测量」冒充零分配。客户端排队、解压、返回 EventLoop、回灌原生 decoder 的数据同时保存于 [阶段 CSV](egress-perf-stages-20261022.csv) 和 [完整阶段/客户端报告](egress-perf-runtime-20261022.json)。客户端快照在收到最后一条夹具消息时记录，服务端 done 在排空后记录，两个采样终点不完全相同。

该负载下，封批等待明显高于 worker 排队和编码时间。没有在线旧实现对照，不能宣称延迟已经下降；本次仅验证双连接与指定突发档位，不证明工作池饱和时的任意连接容量。50 ms 仍然只是封批 watchdog，不是 RTT 或客户端执行完成上限。

首个诊断会话 `egress_perf_1201_forge_20261022` FAIL：测试 profile 错将 `chunk.enabled=false`，使物理客户端网络握手门关闭，夹具收到 0 包。失败日志和结果保留；改为 true 后以新 session 完整复跑通过。未更改握手实现或放宽协议错误门禁。

## 验证与位置

- 1.20.1：`common:test` 551 项（2 项既有跳过），0 失败/错误；Forge 编译与版本边界扫描通过。
- 1.21.1：`common:test` 544 项（2 项既有跳过），0 失败/错误；NeoForge 编译与版本边界扫描通过。
- 聚合专项继续覆盖乱序/损坏/超限、关闭与重新激活、Bundle、回调、原版大包旁路和软限排空；新增公共数组隔离、逻辑语料 observer 和直方图分位数/不支持分配计数的回归。
- Python smoke 分析器 46 项通过，包括必须验证第二客户端、完整突发阶段、发送/回调计数和分配测量存在性的门禁。
- PowerShell 语法解析通过。所有本任务游戏进程由 harness 收尾，25565 已释放。

构建与回放日志：`build/analysis/egress-perf-20261022/`。原始运行日志、结果及 probe：`build/smoke-test/logs/`、`results/result_<SessionId>.json`、`probe/<SessionId>/`（第二客户端在 `observer/`）。复现游戏命令见 [协议文档](../egress-v3.md) 的 `egressload` 段。
