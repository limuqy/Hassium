# 聚合封批窗口高压复测与停止决定（2026-10-23）

按用户最新决定停止后续测试，降低本方向优先级，**生产默认窗口保留 50 ms**。当前证据更支持“局部突发尾延迟优化”，不足以把它当作节省流量或显著降低 CPU 的优化重点。

正式计划原为两版本 × 50/10 ms × 三轮。停止时 **1.20.1 Forge 六场全部 PASS**；1.21.1 NeoForge 仅完成 1024 档 50 ms 校准，正式六场未启动。不能把 Forge 的重复结果外推成已完成 NeoForge 对照。所有游戏已退出，25565 无监听，四份运行配置已按测试前备份逐字节恢复；字典和输入世界 ZIP 哈希未变。

## 已完成的三轮结果

下表为 Forge 三轮中位数，方括号为最小/最大值；计量段排除预热，含持续60秒、突发60秒、排空5秒。线路载荷是成功写出的 Minecraft TCP 载荷，含 framing，不含 TCP/IP 头、ACK、重传。CPU 为服务端累计时钟差值；不包含客户端 JVM。

| 窗口 | 线路载荷 MiB | worker CPU s | 服务端进程 CPU s | 出口 P95 ms | 出口 P99 ms |
|---|---:|---:|---:|---:|---:|
| 50 ms | 4078.76 [4078.70, 4078.85] | 59.23 [57.08, 65.42] | 121.42 [121.23, 137.31] | 27.26 [25.17, 27.26] | 50.33 [50.33, 50.33] |
| 10 ms | 4078.87 [4078.78, 4078.92] | 58.12 [57.94, 60.08] | 118.33 [115.69, 118.58] | 25.17 [25.17, 25.17] | 29.36 [29.36, 31.46] |

相对50 ms，10 ms 的线路载荷中位数变化 **+0.0025%**，worker CPU 为 **-1.87%**，进程 CPU 为 **-2.55%**。worker CPU 逐轮配对变化为 -11.44%、+1.42%、+1.83%，没有一致下降；进程 CPU 三轮均下降，但中位差约3.1 CPU秒/125秒，且首轮差异明显大于后两轮。三次样本不支持精确的普遍收益估计。

| 阶段 | 窗口 | P95 中位数 ms | P99 中位数 ms | worker CPU 中位数 s |
|---|---:|---:|---:|---:|
| 持续（冲刷前） | 50 | 13.63 | 14.68 | 28.89 |
| 持续（冲刷前） | 10 | 13.63 | 14.68 | 28.62 |
| 突发（冲刷后） | 50 | 29.36 | 50.33 | 31.14 |
| 突发（冲刷后） | 10 | 27.26 | 31.46 | 30.41 |

正常持续段的 P95/P99 中位数相同；突发段 P99 下降约37.5%，是明确的局部收益。总体 P99 中位数下降约41.7%，但不能解读为整个游戏响应速度提高41.7%。统计单位是成功 PLAY 子包从原生编码字节进入出口到写完成，未覆盖原生编码、RTT或客户端执行；分位数是对数直方图桶上界。

## 压力、校准与边界

- 正式场景 `egressstress`：两条原生客户端连接，每连接1024包/tick，State/Event各半。State含3 KiB ASCII（1 KiB公共前缀+2 KiB固定伪随机文本），1024条有界语料缓存；客户端逐包验证顺序、字段与次数。
- 每连接先预热900 tick×256包，然后持续1200 tick×1024包，再突发1200 tick（每两tick发2048包），最后排空100 tick；每连接2,688,000包、10,459次发送回调。六场两客户端共校验32,256,000包，协议错误均为0。计量段各约125秒，两种发包阶段均保持约20 TPS。
- 持续段在服务端tick尾冲刷前发包，突发段在冲刷后发包。旧 `egressload` 两段都在冲刷后，容易突出等待窗口的收益；旧低压数据和已完成的三场低压重复没有混入本次三轮统计。
- 两版本1024档50 ms短校准均PASS，满速时worker约占单核54–55%，线路吞吐约34 MiB/s。这里是线程CPU之和除墙钟时间，不是整机CPU百分比；两连接不能证明8线程共享池已饱和。
- Forge1536档校准：持续段约51 MiB/s、单核83.4% worker CPU；突发段触发 `Outbound queue exceeds 8 MiB`，两连接断开。夹具检测到连接丢失后抛错结束服务端，harness为FAIL。它是压力边界证据，没有混入成功场的性能统计，也没有放宽6/8 MiB生产队列门限。证据：`build/analysis/egress-stress-20261022/calibration-forge-c2/crash-server.txt` 及对应服务端日志。

## 固定条件与复现

`2.0.0`，基线提交 `39ac77d1f9677544ce97d6e88434377f40a4206a`；Ryzen 9 8940HX，32逻辑处理器、8聚合worker；level=3，maxSize=262144 B，VD/RD=4，关闭同步包导出。每场从同加载器的只读ZIP恢复独立世界，字典固定。仅开发环境 `egressstress` 冻结每日字典重训，已核对跨日后哈希不变。Forge次序为50→10、10→50、50→10；每场新JVM，全部串行。

阶段分位数由累计桶相减后重算，没有相减或相加P95/P99。CPU用累计线程/进程时钟差，避开逐小作业CPU时钟量化；阶段快照近似一致，在途包按完成时间归属。背景原版包仍有轻微变化。单机合成载荷不能替代真实整合包、多连接竞争、远端网络验证。

XERJ设计参考：Fortio `67ce9fa2a3a62fdb2f7a0d2b50acda1f7002d32b` 的 [fhttp/httprunner_test.go:465](https://github.com/fortio/fortio/blob/67ce9fa2a3a62fdb2f7a0d2b50acda1f7002d32b/fhttp/httprunner_test.go#L465)（固定速率、请求总数、错误门禁）及 [stats/stats.go:307](https://github.com/fortio/fortio/blob/67ce9fa2a3a62fdb2f7a0d2b50acda1f7002d32b/stats/stats.go#L307)。已核对Apache-2.0；沿用本项目直方图实现，未复制参考代码。

未安排自动续跑。后续若明确恢复，可使用新的SessionId，并核对输入字典哈希：

```powershell
./scripts/runtime-smoke-test.ps1 -Ver 1.20.1 -Loader forge -Phase I -Scenario egressstress -SessionId <new_session> -AggregationMaxWaitTimeMs 10 -EgressStressRate 1024 -EgressStressWarmupTicks 900 -EgressStressMeasureTicks 1200 -Vd1 4 -Vd2 4 -ClientRenderDistance 4 -ClientTimeoutSec 420 -HangDumpMax 0 -WorldZip D:/project/MC/Hassium/build/analysis/egress-window-20261022/world-forge.zip -Offline
```

校准用WarmupTicks=400、MeasureTicks=400；NeoForge替换版本、加载器与对应ZIP。运行前保存配置，完成后恢复；勿覆盖已有证据。

## 证据与检查

- [逐场汇总](egress-window-stress-20261023-summary.csv)
- [输入哈希](egress-window-stress-20261023-inputs.json)

详细阶段/客户端统计、完整运行统计、中位数/配对差值、原始累计桶与CPU快照已归档到本机 `build/analysis/egress-stress-20261022/evidence/`，文件名前缀为 `egress-window-stress-20261023-`；这些可重建明细不纳入 Git。归档时逐文件核对 SHA-256，记录在同级 `archive-manifest.json`。

原始会话、矩阵状态、停止原因、配置备份、校准失败证据在 `build/analysis/egress-stress-20261022/`，游戏日志/结果/探针在 `build/smoke-test/`。主矩阵状态为 `stopped_after_current`，completedCount=6/12，configsRestored=true。

新增高压夹具、测试参数透传、阶段区分、计量与分析门禁；没有改变生产封批、队列门限或默认窗口。1.20.1 common:test 555项、1.21.1 548项，各2项既有跳过，无失败/错误；对应Forge/NeoForge编译及版本边界扫描通过。提交前修正汇总工具对未覆盖窗口（0/缺省）的兼容，Python分析/汇总测试53项通过，PowerShell语法检查通过。停止后确认四份TOML、两份字典和两份ZIP哈希；无本次压力测试Java进程，25565无监听。

2026-10-23收尾清理：删除本轮及前序窗口测试的18个观察者运行目录、16份可从ZIP重建的隔离世界，共1241.03 MiB；保留当前两个 `server.properties` 引用的世界、输入ZIP、字典、共享日志/探针/结果和失败证据。具体目录清单留在本机 `build/analysis/egress-stress-20261022/cleanup-completed.json`。
