# 2026-10-13 服务端出口聚合 v3

本次按用户批准的无损出口计划实施。服务端下行 PLAY 在实际原生/加载器 codec 完成编码后捕获，客户端在 decoder 前还原；保留每包字节、顺序和执行次数。协议为 v3，两端须同时升级；未安装 Hassium 的客户端仍走原版。生产开关沿用 `master.enablePacketAggregation`，没有增加配置键。

## 实现与关键边界

- `protocol/egress` 提供有序发送/接收 FIFO、完整原生帧 codec、64 KiB 历史、自动模板、事务提交、最终完整帧择优、服务端出口计量和回放工具。
- 每连接串行压缩/解压，实际写入和包交付回 EventLoop。原始 promise 等待载体写成功；回调、控制包、Bundle 边界、协议切换和未知写入形成有序屏障。6 MiB 停止新增聚合，8 MiB 硬断开；不静默丢包。
- CRC32C、长度、会话代、序号、字典和模板先校验再交付；原生 Mod decoder 失败立即断开。重连从新状态开始，没有旧聚合帧恢复或兼容路径。
- NeoForge 大小分片器会预编码。新增加载器兼容 mixin 复用同一包对象、同一实际绑定的预编码字节，避免第三方 Mod codec 二次执行；分片结果不冒充原包缓存。
- 开发夹具同一通道同时承载 State/Event，涵盖注册表 holder、变长字符串/列表、Bundle、发送回调和一次性事件。夹具 codec 二次执行、接收错序或重复执行都会报错。
- Pull、实体频率、登录/配置压缩、上行及存储/光照业务保持原路径。原版压缩层保持既有算法，聚合载体仍使用防双重压缩处理。

完整帧格式、模板同步命令与预算见 [egress-v3.md](../egress-v3.md)。JNI 的 `loadDict` 会识别格式字典魔数；不透明历史恰好带此保留前缀时跳过历史压缩候选，保留原始历史并择优其余候选。这一输入已有无损回归测试。

## 自动验证

三个版本的 common 单测各 417 项（每版本新增出口测试 25 项），六个加载器组合编译及 `scanVersionBoundaries` 通过。Python 冒烟分析器 35 项通过。

出口测试覆盖：随机原始字节往返、最终大小基线、训练字典和缺字典、实际模板命中及未选事务、缺模板/重用 ID/重叠槽、CRC/错序、完整帧大小和逻辑长度上界、promise 成功与失败、直发/Bundle 顺序、开关关闭排空和重开代际、协议绑定替换、原生 decoder 暂停、断连时后台作业收尾、软限旁路和硬限断开。

六组合的 classic / flyroundtrip / dimension / dictionary / egress 合计 30 个通过会话，另有 26.3 两加载器 egressbench 通过。精确 session 见 [冒烟矩阵 CSV](egress-v3-smoke-matrix-20261013.csv)，没有通过修改错误白名单放行失败。

| 版本 | 加载器 | classic | flyroundtrip | dimension | dictionary | Mod payload |
|---|---|---|---|---|---|---|
| 26.1.2 | Fabric | PASS | PASS | PASS | PASS | PASS |
| 26.1.2 | NeoForge | PASS | PASS | PASS | PASS | PASS |
| 26.2 | Fabric | PASS | PASS | PASS | PASS | PASS |
| 26.2 | NeoForge | PASS | PASS | PASS | PASS | PASS |
| 26.3 | Fabric | PASS | PASS | PASS | PASS | PASS |
| 26.3 | NeoForge | PASS | PASS | PASS | PASS | PASS |

初始批次保留失败证据：三项 classic 在开发夹具 Pig 类型常量跨版本迁移期间编译失败，改为 compat 注册表查询后补跑通过；Fabric dictionary 正常断连的已关闭通道写失败误记 ERROR，补充 promise/清理回归并修复后通过；NeoForge egress 首次失败来自 Mojang 公钥服务请求，后一次客户端因并行其他版本 Gradle 重写开发类路径缺失 `SmokeChunkTrace$Snapshot`。禁止与游戏并行构建后，独立会话 `26.3_neoforge_egress_v3_isolated` 双轮通过。后两项未降低业务、CRC 或错误门禁。

## 历史语料回放

8 份 2026-09-25 采集合计 68,453 帧、2,848,956 个子包，三个模式逐包逐字节还原及顺序检查全部通过；历史和历史+模板的每帧均不超过同协议独立字典基线。输入是旧采集解出的不透明子包字节，不将它们宣称为当前 26.x 原生包或收益承诺。

| 采集后缀 | 独立字典出口 B | 历史出口 B | 历史+模板出口 B | 对独立字典降幅 |
|---|---:|---:|---:|---:|
| 020932 | 2,132,981 | 1,583,897 | 1,582,903 | 25.79% |
| 021758 | 2,751,748 | 2,142,945 | 2,141,893 | 22.16% |
| 022451 | 2,017,651 | 1,565,889 | 1,564,332 | 22.47% |
| 031319 | 3,175,131 | 2,428,095 | 2,420,115 | 23.78% |
| 031744 | 10,750,634 | 3,929,576 | 3,710,899 | 65.48% |
| 153053 | 420,055 | 112,401 | 112,401 | 73.24% |
| 193706 | 5,546,589 | 2,091,258 | 2,091,258 | 62.30% |
| 202430 | 1,383,704 | 847,358 | 847,358 | 38.76% |

`031744` 中模板进一步减少历史模式出口 5.56%，提交 67,286 次精确引用、375,788 次变量模板引用及 154,831 B 定义。其余数据模板收益较小或为零，最终择优仍保留更小的历史/原文候选。CPU 成本须使用下节当前采集评估。

## 当前 26.3 复采

两加载器均运行同一个 `egressbench` 路径与 profile：level 3、50 ms 等待、262144 B 批次、SeedGen 关闭、实际字节导出开启。25 秒预热后，三个阶段各约 20 秒。实体阶段为站桩玩家附近 128 个确定性移动的 NoAI 猪；Mod 阶段每 tick 100 个 State/Event，具体类型和顺序持续校验。每加载器沿用自身隔离世界与训练字典，各模式共享该加载器同一份采集和实际批次；不将跨加载器差异解释为算法差异。

成功写出的 PLAY TCP 载荷包括 vanilla 长度/压缩前缀，排除 IP/TCP 头和重传。独立字典及历史模式使用同一实际原始包回放，其总出口计算为 `实际 TCP − 实际聚合封装 + 对照聚合封装`，旁路流量不变。每包字节和顺序通过校验，历史+模板逐字节重现采集的实际选中帧；每帧均不超过独立字典基线。

| 加载器 / 场景 | 独立字典 TCP B | 历史 TCP B | 实际历史+模板 TCP B | 对独立字典降幅 | 实际平均队列等待 ms |
|---|---:|---:|---:|---:|---:|
| Fabric 空闲 | 91,937 | 81,068 | 81,071 | 11.82% | 3.87 |
| Fabric 实体 | 194,775 | 162,158 | 161,420 | 17.13% | 8.72 |
| Fabric Mod | 677,893 | 526,884 | 526,894 | 22.28% | 38.97 |
| NeoForge 空闲 | 95,932 | 83,442 | 83,442 | 13.02% | 4.16 |
| NeoForge 实体 | 221,192 | 181,487 | 181,487 | 17.95% | 7.92 |
| NeoForge Mod | 669,543 | 523,428 | 523,487 | 21.81% | 38.65 |

模板实际进入发送路径：Fabric 三阶段分别命中 10/187/108 次变量引用，定义 83/19/229 B；NeoForge Mod 阶段命中 1 次精确引用、115 次变量引用，定义 762 B。此夹具的主要收益来自历史；模板对实体只带来少量增益。模板模式会改变后续历史内容，因此其整个轨迹可能比“关闭模板”的另一条历史轨迹略大（本表 Mod 差 10/59 B），仍满足逐帧不超过同协议独立字典基线的要求。

### CPU 与延迟

离线 CPU 对照按每模式整个连续阶段计量，排除解析、校验及还原，避免 Windows CPU 时钟约 15.6 ms 分辨率对微小交错样本的误差。下表为每帧平均值（µs）；整阶段 CPU 总量仍有约一个时钟刻度的不确定性，单次开发机采集不作为生产容量承诺。

| 加载器 / 场景 | 独立字典 CPU | 历史 CPU | 历史+模板 CPU | 独立字典耗时 | 历史+模板耗时 |
|---|---:|---:|---:|---:|---:|
| Fabric 空闲 | 139.8 | 349.6 | 629.2 | 119.4 | 625.0 |
| Fabric 实体 | 90.6 | 271.7 | 588.8 | 77.5 | 590.7 |
| Fabric Mod | 99.9 | 166.6 | 433.1 | 86.0 | 416.6 |
| NeoForge 空闲 | 74.8 | 299.0 | 598.1 | 84.9 | 591.0 |
| NeoForge 实体 | 65.7 | 306.4 | 590.9 | 75.2 | 592.1 |
| NeoForge Mod | 69.8 | 174.4 | 453.4 | 88.4 | 422.6 |

完整择优增加 codec CPU，约 0.43–0.63 ms/帧，基线约 0.07–0.14 ms/帧。实际在线三个 20 秒阶段计得压缩线程 CPU：Fabric 422/313/313 ms，NeoForge 78/109/250 ms；这些逐作业累加量受时钟粒度、在线调度和上下文预热影响，不能与离线值直接相减。实际队列等待见上表，包含现有聚合等待窗、调度及写入；密集 Mod 约 39 ms，没有改变 50 ms 配置。未实跑在线基线，故不宣称端到端延迟改善或无变化。

两次 benchmark 均 PASS、各阶段错误增量为 0。Fabric 采集 `agg-20261013-173100.jsonl`，NeoForge 采集 `agg-20261013-173311.jsonl`。详细 CSV：[Fabric 三模式](egress-v3-current-fabric-20261013.csv)、[NeoForge 三模式](egress-v3-current-neoforge-20261013.csv)、[出口/CPU/等待汇总](egress-v3-current-summary-20261013.csv)、[历史语料](egress-v3-historical-20261013.csv)。

## 复现与证据位置

```powershell
.\gradlew.bat common:test fabric:compileJava neoforge:compileJava "-Pmc_ver=26.3"
.\gradlew.bat scanVersionBoundaries
.\scripts\runtime-smoke-test-batch.ps1 -Phase I -Scenarios classic,flyroundtrip,dimension,dictionary,egress -SessionSuffix v3 -MaxRetries 1
.\scripts\runtime-smoke-test.ps1 -Ver 26.3 -Loader fabric -Phase I -Scenario egressbench -SessionId 26.3_fabric_egressbench_v3_final
.\gradlew.bat common:runJava "-Pmc_ver=26.3" "-PmainClass=io.github.limuqy.mc.hassium.protocol.egress.EgressCaptureReplay" "-Pargs=<native.jsonl>,<report.csv>"
```

本地编译/回放日志在 `build/analysis/aggregation-20261013/`，运行时结果与日志在 `build/smoke-test/results/`、`build/smoke-test/logs/`。原始 JSONL 位于各加载器 `run/server/logs/hassium-aggregated-packets/`。结果属于本机开发夹具；不作为所有 Mod 整合包的收益保证。

本交接随出口 v3 实现一并提交，未推送或发布。用户原有 `logo-16x10.png` 和 `video/` 未修改。
