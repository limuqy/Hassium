# spark 双端主线程与往返包负载对照（2026-10-23）

后续优化已完成，最终制品、双端回归和旧/新 Hassium 八场对照见[优化验证结果](spark-optimization-results-20261023.md)。下文保留最初同加载器无 Hassium 基线的独立测量口径，不与后续数据拼接。

本轮比较当前 `2.0.0` 工作区构建的 Hassium 与同版本加载器 + spark 基线，服务端、客户端都测。基线不安装 Hassium；这里的“原版”指同加载器下原版网络和区块路径，不是移除加载器的裸 Minecraft。

8 场正式测试全部完成，32 份 profile 已在本地保存。大量包阶段两轮均值：Forge 服务端 MSPT 增加 3.3%，NeoForge 增加 10.7%，均保持 20 TPS；客户端平均帧耗时变化较小，但 P99 增加约 1.2–1.3 ms。客户端区块/渲染数量存在差异，FPS 不能直接解释成相同画面工作量下的纯开销。

## 测试口径

- 1.20.1 Forge 47.4.0 / spark 1.10.53；1.21.1 NeoForge 21.1.216 / spark 1.10.124。使用用户提供的两份 spark JAR。
- Ryzen 9 8940HX（16 核 / 32 线程）、Windows、GraalVM JDK 21.0.5；每个 JVM `-Xms2G -Xmx6G -XX:ActiveProcessorCount=16`。一台机器串行运行一个专用服与一个客户端。
- 独立测试目录，每场从相同加载器的只读世界 ZIP 恢复；没有改正式存档、共享运行配置。每场新 JVM、新客户端缓存。
- 服务端 VD=8、simulation-distance=6；客户端 RD=8，854×480、fancy、关闭 VSync、`maxFps=260`（原版不限制帧率）。SeedGen、OVD、存档压缩、休眠和 debug 日志关闭；聚合窗口保持 50 ms，实体节流保持当前配置。
- 客户端模拟按住前进键，使用原版飞行逻辑在 X≈0..224、Y=140 的路线往返；只在准备阶段传送到起点。每帧检查边界后转向，持续记录位置、已加载柱数和渲染统计。
- 正式场次预热 60 s，测移动 60 s；再生成 256 个不可见、无重力的 marker 盔甲架，负载预热 15 s，测“移动 + 大量包”60 s。
- 每个版本、每组各两场独立启动。执行顺序为 Forge 基线→Hassium、NeoForge 基线→Hassium、NeoForge Hassium→基线、Forge Hassium→基线；不同时运行多场。每场两个阶段分别采集服务端、客户端，共 32 份正式 profile。
- 压力实体跟随玩家，每 tick 改变位置与 1 KiB 名称文本，通过原版数据包同步。两段固定伪随机文本交替、实体间共享，属于高重复度合成负载，不能外推真实整合包压缩比。实际收到的实体状态 / 运动包计数与线路字节另测。
- 两组安装相同的独立测试 agent。它控制输入、计量原版 tick / frame、累计线程和进程 CPU 时钟，并通过 spark 命令入口启动与本地保存采样。它不依赖 Hassium 测试入口。
- 正式 spark：主线程默认选择、Java sampler、4 ms；`stop --save-to-file`。没有上传采样到 spark 网站。校准时的 all-thread / 10 ms 采样、短预热数据不混入正式对照。

## 指标含义

- 服务端 tick duration：`MinecraftServer.tickServer(BooleanSupplier)` 方法体；另外单独报告整个 `Server thread` 的线程 CPU 时钟差，因此 tick 外的任务处理不会被误算成不存在。
- 客户端 frame duration：`Minecraft.runTick(boolean)` 方法体，包含渲染和帧内等待；FPS 用窗口内完整帧数 / 实际秒数。未限帧时主线程接近单核满载是正常现象，比较帧耗时更有意义。
- CPU 百分比的分母是一个逻辑核的墙钟时间。进程 CPU 可以超过 100%，不是整机 CPU 百分比。
- P95/P99 使用原始逐 tick / frame 样本的 nearest-rank，不对分位数做相减；性能变化使用同版本、同阶段配对。
- 线路字节：原版 `CompressionEncoder` 输出加 VarInt framing，S2C 读服务端、C2S 读客户端；不含 TCP/IP、ACK、重传。逻辑收包计数在客户端主线程执行具体原版实体包时记录，包含 Hassium 解聚合后恢复的原版包。
- spark 树是采样归因，不能把某函数占比直接称为 Hassium 相对原版的性能损失。Windows Java sampler 有安全点偏差；线程 CPU 与绝对 tick/frame 时间是对照指标。
- 本轮没有移除 spark / agent 的独立计时组，绝对数值含采样和控制器开销。两次重复用于检查方向和运行次序影响，不足以给出统计置信区间。负载只有一个客户端，不能外推多人、多维度或满载服务器。

## 复现与证据

```powershell
python scripts/spark-benchmark.py --loader forge --label forge-r1 --arms baseline hassium --warmup 60 --seconds 60 --entities 256
python scripts/spark-benchmark.py --loader neoforge --label neo-r1 --arms baseline hassium --warmup 60 --seconds 60 --entities 256
# 反向次序重复时使用新的 label，--arms hassium baseline。
```

本机证据根目录：`build/analysis/spark-20261023/`。每场保存 `inputs.json`（JAR、世界 ZIP、测试源文件 SHA-256）、启动参数、完整日志、逐帧/逐 tick CSV、CPU/包量 JSON、位置/区块场景 CSV、原始 `.sparkprofile`、本地解码 JSON 和热点汇总。目录已存在时拒绝覆盖。

`scripts/spark-report.py` 只接收完整 PASS 场次；本地 Java 解码器使用对应生产者 spark JAR 的 protobuf 类。校准、失败启动记录独立保留。

此处 PASS 表示测试运行、运动、采样和退出完成，不代替地形覆盖、移动光照或缓存一致性验收。尤其需要结合场景 CSV 判断客户端帧率是否具有同等渲染工作量。

设计参考：通过 XERJ 检索 `lucko/spark` `3e8745a76eaabe9a4f70aad6283876fb518d2ae8`，`spark-common/.../command/modules/SamplerModule.java:399`（本地导出）和 `spark-common/.../SparkPlatform.java:308`（命令入口）。已核对 GPL-3.0，仅参考接口和行为，没有复制实现。

官方文档：[命令](https://spark.lucko.me/docs/Command-Usage)、[采样器平台与限制](https://spark.lucko.me/docs/misc/Using-async-profiler)、[查看调用树](https://spark.lucko.me/docs/Using-the-viewer)、[原始数据格式](https://spark.lucko.me/docs/misc/Raw-spark-data)。

## 结果

以下“基线→Hassium”均为两轮独立测量的算术均值。表内 P95/P99 是每轮分位数的均值；完整逐轮数值见 [CSV 汇总](spark-main-thread-20261023.csv)。耗时增幅按两组均值计算，配对范围另列。

### 服务端

| 版本 / 场景 | 平均 MSPT，基线→Hassium | 平均耗时增幅 | P95 ms，基线→Hassium | 主线程 CPU，基线→Hassium |
|---|---:|---:|---:|---:|
| 1.20.1 Forge / 往返 | 2.156 → 2.379 | +10.3% | 3.275 → 3.910 | 5.5% → 5.8% |
| 1.20.1 Forge / 往返＋包负载 | 6.893 → 7.121 | +3.3% | 8.809 → 9.648 | 16.1% → 14.8% |
| 1.21.1 NeoForge / 往返 | 2.088 → 2.314 | +10.8% | 3.277 → 3.888 | 3.5% → 7.8% |
| 1.21.1 NeoForge / 往返＋包负载 | 5.984 → 6.625 | +10.7% | 7.482 → 9.106 | 11.3% → 15.2% |

大量包场景的逐轮平均 MSPT 增幅：Forge 为 +5.7%、+0.8%；NeoForge 为 +8.7%、+12.8%。两版普通往返均约增加 0.22 ms/tick；大量包时分别增加约 0.23、0.64 ms/tick。这是本场景的 tick 开销变化，不等同于 TPS 降低或满载吞吐损失。

Forge 普通往返的整个主线程 CPU 有较大轮间波动：基线 4.8% / 6.2%，Hassium 4.1% / 7.6%；大量包时则两轮均低于基线。tick 方法体耗时与整条线程 CPU 的方向不同，说明不能只用一个指标归因，也不宜由两场数据给出稳定的 Forge 总 CPU 损失比例。NeoForge 的主线程 CPU 增加在两轮中方向一致。所有阶段均约 20 TPS，没有采到超过 50 ms 的 tick。

### 客户端

| 版本 / 场景 | 平均帧耗时 ms，基线→Hassium | 平均耗时变化 | P99 ms，基线→Hassium | 整个客户端进程 CPU，基线→Hassium |
|---|---:|---:|---:|---:|
| 1.20.1 Forge / 往返 | 0.703 → 0.704 | +0.2% | 1.683 → 2.801 | 203.7% → 189.5% |
| 1.20.1 Forge / 往返＋包负载 | 0.738 → 0.726 | −1.7% | 2.223 → 3.452 | 214.3% → 187.9% |
| 1.21.1 NeoForge / 往返 | 0.656 → 0.671 | +2.2% | 1.619 → 2.771 | 140.3% → 153.4% |
| 1.21.1 NeoForge / 往返＋包负载 | 0.686 → 0.704 | +2.6% | 2.116 → 3.414 | 143.1% → 152.2% |

渲染线程在各组均约占满一个逻辑核（约 97%），因为没有限帧；上表进程 CPU 包含影子端和其他工作线程，100% 表示一个逻辑核。大量包阶段平均 FPS：Forge 1348→1371，NeoForge 1451→1414。平均值接近，但两版 P99 都增加约 1.2–1.3 ms；本轮没有超过 50 ms 的帧。这些数值仅适用于本机低分辨率、未限帧场景。

**画面工作量尚未证明等价。** 每阶段约 61 次场景采样，Hassium 已加载柱数中位数通常为 312，最小 280–293；基线中位数为 313，Forge 少数瞬态为 301，NeoForge 少数瞬态为 294。以普通往返为例，Forge 两轮 Hassium 有 38/61、42/61 次低于 313，基线仅 0/61、1/61 次；NeoForge 为 36/61、31/61 对 2/61、3/61。渲染器 `C:` 计数也不同。计数差异可能涉及交付时序、缓存保留或可见性路径；这些计数本身不能证明漏渲染，但足以说明不能从当前 FPS 直接得出纯优化收益或固定性能损失。尤其不能把 Forge 的平均 FPS 上升当作已确认的渲染加速。

### 实际包负载与线路字节

256 个压力实体每秒触发约 5120 个状态包、5120 个运动包，叠加背景实体后客户端主线程实际处理约 1.02–1.04 万包/s。两组压力实体状态包量基本一致，压力确实到达客户端；普通往返的背景运动包在 Hassium 下有节流，属于功能行为差异。

| 大量包阶段 / 每约 60.35 s | S2C MiB，基线→Hassium | C2S MiB，基线→Hassium |
|---|---:|---:|
| 1.20.1 Forge | 243.21 → 0.62 | 0.03 → 1.39 |
| 1.21.1 NeoForge | 233.13 → 2.03 | 0.03 → 1.41 |

NeoForge Hassium 的 S2C 两轮分别为 3.10、0.97 MiB，存在明显轮间差异。这里实体共享两段反复交替的文本，重复度很高；线路字节下降不能外推真实整合包压缩率。Hassium 的 C2S 约增加 1.4 MiB/分钟，也应与 S2C 一起计入成本，而不只看下行。

### 证据检查

- 8 场均 PASS；客户端、服务端退出码均为 0，测试 JVM 已退出。
- 32 份正式采样均仅包含对应的 `Server thread` 或 `Render thread`，间隔为 4000 μs。校准数据未混入。
- 同版本使用相同世界 ZIP、spark JAR、Hassium JAR 和测量源文件散列；每场位置跨度约 227 格，每阶段转向 3 次。
- 正式日志中 `NBT snapshot failed`、`Light timeout (`、`ClassNotFoundException`、错误级日志和 outbound queue 超限的检查计数均为 0。这是已检查模式的结果，不是对所有潜在问题的证明。
- 较早的 NeoForge 短预热校准场曾出现 11 次 NBT 快照回退 FULL，未断线；两轮正式测试未复现。保留在 `neo-cal1-hassium/client.log`，与正式结果分开。
- 本轮包含两版生产 JAR 构建验证和完整运行测试；没有修改生产行为。

测试时记录的 HEAD：`edc4fd7213b10117aecd4e6b992a9fa2932d2757`。实际测量产物以每场 `inputs.json` 中的 SHA-256 为准。可继续查看本机 `build/analysis/spark-20261023/comparison.json`、各场 `summary.json`、原始 CSV 与 `.sparkprofile`。

## 调用树与源码对应

以下是主线程采样中值得先排查的路径。占比是整个线程的墙钟采样占比，包含下游调用；同一调用链的父子占比不能相加，也不能直接当作优化收益。

Hassium 入口去重归因约占客户端主线程采样的 2.9–4.2%、服务端的 0.9–2.5%。客户端大头仍是 OpenGL / JNI 渲染调用；服务端包含大量 `Unsafe.park` 等待样本，因此这里的占比尤其不能当作 CPU 开销百分比。`confirmLightsCorrectIfConverged` 约占 1.6–2.8%，实体 `onServerTickStart` 约占 0.6–1.5%。休眠可见性包装钩子的 0.8–1.3% 包含原版 getter，详见下方归因修正。

1. **客户端光照重复确认**：`MixinClientTick → ShadowLightCompute.drainReady → ShadowSeedServer.confirmLightsCorrectIfConverged → hasCompleteLightLayers`。源码中 `drainReady` 在收敛条件满足时触发确认，确认遍历 `injectedChunks`，再逐 section 检查光照层。候选方向是按状态变化/脏标记避免稳定柱重复全量检查；必须保留 `LightNeighborhoodGate` 的 3×3 邻柱条件、表层光照与落盘完整性判据，不能旁路光照门禁。
   源码：[`ShadowLightCompute.java:3182`](../../common/src/main/java/io/github/limuqy/mc/hassium/shadow/light/ShadowLightCompute.java#L3182)、[`ShadowSeedServer.java:2124`](../../common/src/main/java/io/github/limuqy/mc/hassium/shadow/server/ShadowSeedServer.java#L2124)、[`hasCompleteLightLayers:833`](../../common/src/main/java/io/github/limuqy/mc/hassium/shadow/server/ShadowSeedServer.java#L833)。
2. **服务端每 tick 解析实体配置**：`EntityUpdatePacing.onServerTickStart → build → EntityUpdateTiering.parse* / split4`。每 tick 创建快照，解析逗号分隔的档位字符串并分配数组。候选方向是缓存不可变的解析结果，在配置更新时失效；动态实体密度和计数仍按原来的 tick 语义更新。
   源码：[`EntityUpdatePacing.java:108`](../../common/src/main/java/io/github/limuqy/mc/hassium/server/entity/EntityUpdatePacing.java#L108)、[`build:290`](../../common/src/main/java/io/github/limuqy/mc/hassium/server/entity/EntityUpdatePacing.java#L290)、[`EntityUpdateTiering.split4:229`](../../common/src/main/java/io/github/limuqy/mc/hassium/server/entity/EntityUpdateTiering.java#L229)。
3. **客户端休眠可见性钩子（归因修正）**：本轮休眠关闭，但 `LevelRenderer...hassium$visible` 仍进入每次取 compiled section 的路径。复核全部 8 份 Hassium 客户端 profile，该包装节点的采样时间全部落在下游原版 `getCompiledChunk/getCompiled`。因此不能把包装节点的约 1% 视为 Hassium 额外开销，也没有证据说明加快该条件判断能省下约 1%。可以单独测量 inactive 分支，但暂列低优先级；休眠后的恢复阶段仍有未完成柱，不能只按 `sleeping=false` 就全部放行。
   源码：[`MixinSleepLevelRenderer.java:12`](../../common/src/main/java/io/github/limuqy/mc/hassium/mixin/client/MixinSleepLevelRenderer.java#L12)。

本轮只增加测量工具和报告，没有实施上述生产优化。实际优化收益须在修复后以同一矩阵重测，并补充移动中的屋檐/洞口光照检查。

## 主线程逻辑完整复核与异步评估

补查范围为 4 场 Hassium 正式运行的双阶段、双端调用树，共 16 份 profile；基线用于前面的耗时对照。本轮运行环境仅安装 spark / Hassium，没有其他第三方模组的性能画像。原始调用树提取到 269 个带 Hassium 标识的方法名，包含父子调用、Mixin 与 Lambda，完整明细见 [方法采样表](spark-main-thread-20261023-methods.csv)。这不是 269 个独立热点，父子节点不可累加。

下表为每端 8 份 profile 的墙钟采样占比“均值 / 单份最大值”；未采到的方法在均值计算中按 0 记，但不代表实际零开销。P0 表示当前优先验证，P1 表示有明确改进方向但需补测，P2 表示当前收益证据较弱。优先级不是实施授权或收益保证。

| 端 / 逻辑 | 采样占比：均值 / 最大 | 优化判断 | 异步判断 / 优先级 |
|---|---:|---|---|
| 客户端：光照收敛后的全表确认 | 2.367% / 2.798% | 避免每帧遍历全部稳定柱，改为状态变化触发、同柱合并和有界批处理 | 确认涉及影子世界，应在影子主循环串行完成；渲染线程消费带版本的结果，保持光照门禁。**P0** |
| 服务端：实体配置 `build` | 0.670% / 0.830% | 缓存已解析的不可变配置，配置实例/版本变化时重建；动态压力数据仍逐 tick 更新 | 保留主线程发布即可，拆小任务异步的收益证据不足。**P0** |
| 服务端：Pull 比较 `comparePull` | 0.153% / 0.211% | 缓存未命中时逐段重算 hash；已有跨玩家共享缓存。先评估按脏 section 增量失效、减少整柱重算 | 可对主线程捕获的 detached 快照异步 hash；必须校验柱修订号、会话/维度，防陈旧 hash 被发布。不可让 worker 直接读活动 `PalettedContainer`。**P1** |
| 服务端：实体密度 `rotate` | 0.189% / 0.277% | 已有前后两张表交换；可检查高水位容量、清表与迭代开销，减少无变化状态的重复工作 | 翻页参与同一 tick 的判断，不宜直接并行；先优化结构和分配。**P2** |
| 服务端：包计数 `drainGameInto` | 0.133% / 0.217% | 样本包含连接表迭代；可复用活跃连接集合和临时容器，避免重复扫描 | 计数生产者可并发，tick 边界结算/发布应保持一致；不要为小额归集增加后台往返。**P2** |
| 服务端：压力表 `retainOnly` | 0.047% / 0.072% | 可按连接生命周期清理，减少每 tick 集合保留操作；需覆盖重连与断线 | 事件驱动清理比异步扫描更直接。**P2** |
| 服务端：保存解锁钩子 | 0.121% / 0.487% | 仅 Forge 第一轮两个阶段采到；峰值在 `ThreadLocal.get → getEntryAfterMiss`，并非存盘等待。非影子服务器的 RETURN 路径也读取 ThreadLocal，可评估上下文快速返回 | 不应删除锁或把原版保存整体扔进线程池；先隔离钩子成本并复测。**P1，局部峰值** |
| 客户端：`applyReadyChunk` | 0.060% / 0.092% | 包括原版区块落地与渲染更新；可合并同柱重复交付，保留最新有效版本 | 原版客户端世界变更保留渲染线程；解码/快照准备可在后台，落地必须有序。**P1，关注交付延迟** |
| 客户端：全柱 `markChunkSectionsDirty` | 0.010% / 0.020% | 当前逐 section 标记及其邻居；可评估同帧去重和准确的变化范围，避免重复标脏 | 标脏状态属于客户端/渲染所有者；不直接异步调用。边界、光照和邻居依赖不能漏。**P2** |
| 客户端：Tracking 状态发布 | 0.016% / 0.059% | 渲染端只捕获位置等状态；实际选柱/簿记在影子主循环，已有职责分离 | 当前快照发布应保留；无需把读取活动玩家对象再搬到 worker。**P2，已分离** |
| 服务端：聚合入口 / `flushAll` | 0.030% / 0.086%；0.009% / 0.020% | 主线程仅见少量准入/提交；FIFO 由 Netty 管理，压缩已交 worker | 已异步，当前主线程数据不支持把压缩当作主要阻塞点；需另外采 worker / Netty 才能评估总 CPU 和排队。**P2，已异步** |
| 诊断、回调调度 | `TickMonitor.finishHassiumTick` 0.008% / 0.020%；服务端 `flushServer` 0.007% / 0.013% | debug 关闭时仍会预先构造/格式化部分参数，可加调用点门控；服务端回调循环当前无时间上限，需额外压队列测尖峰 | 小额诊断先跳过无用工作；回调中的世界操作保留所有者线程，可拆分大任务。当前未形成大热点。**P2** |

以上多项是前述大入口的子路径，例如配置 `build`、密度翻页和包计数均属于实体 tick 起点；`markChunkSectionsDirty` 属于区块落地的下游。表格只帮助定位与判断线程边界，不能把百分比相加估算总收益。

### 可拆分的计算与已有异步路径

服务端 Pull 当前是“主线程比较/快照 → `pushPool` 做 DELTA 规划、编码及 FULL ZSTD → 主线程校验并发送”。仍可评估两处准备成本，但本轮稳定往返对它们的采样很少，不能据此承诺收益：

- `preparePull` 的 UNCHANGED 分支在主线程执行 `collectBlockEntities` 和 `BlockEntitySnapshot.encode`。必须在所有者线程调用活跃 BlockEntity 的 update-tag；得到独立 NBT 副本后，纯字节编码可以评估交给既有工作池。要补“密集 BlockEntity + NBT 更新”场景，并保证响应顺序、旧会话丢弃和后续原版更新不会被旧快照覆盖。
- DELTA 候选当前同时准备 detached section 快照和 FULL 后备包，worker 才决定 DELTA 是否成功。可研究让同一份完整不可变快照派生两种表示，减少重复准备；快照必须同时覆盖生物群系、heightmap、BlockEntity 与所需光照，且保持同一修订。不能直接延迟读取活动区块，也不能去掉 FULL 回退。

客户端 NBT 已使用 `AsyncSnapshotCommit`：可见世界的更新先在渲染线程按包序执行，影子世界提交转其所有者，成功后再返回客户端完成 ACK/发布；`current` 条件对会话、level、基线与 token 再校验。不能为了“全异步”把可见 BlockEntity 应用移走或提前 ACK。Tracking 的重工作也已在 `consumeOnShadowLoop`，不是每帧在渲染线程扫描整个权威窗。

### 异步化的实施边界

1. **主线程捕获，后台纯计算，所有者提交。** worker 只接收独占或不可变数据；返回时重新校验 session / dimension / column revision / token。过期结果丢弃或按既有协议重试，不能写回新世界。
2. **复用有界任务预算。** Pull 已有计数与字节 `WorkBudget`；不能另起无界 `CompletableFuture` 或每帧提交一个全表扫描。要记录排队、在途字节、淘汰与回压，避免把主线程耗时转成延迟或内存增长。
3. **主线程不等待后台完成。** 不在渲染/服务端 tick 中加入 `Future.get/join`；后台结果通过已有邮箱/队列提交，并保持同柱顺序和确认尾部。
4. **世界状态按所有者串行。** 渲染/OpenGL、客户端世界应用、服务端实体/区块操作保留原有线程；影子 POI / SectionStorage / 注册表遵守 `ShadowPoiGate` 和影子主循环规则。光照 3×3 邻柱门、交付窗与推送门保持原语义。

建议顺序：先做配置解析缓存与光照重复确认的去重/所有者调度，再单独验证保存钩子快速返回和 hash 未命中的准备成本；随后根据专门场景决定 NBT 编码、快照复用和队列预算。每一步同时对 1.20.1 Forge / 1.21.1 NeoForge 复测；保留同场景的主线程 CPU、绝对耗时、P99、实际收包量及区块覆盖，另外加入异步队列和端到端交付延迟，防止只是把工作推迟。

### 源码锚点与覆盖空白

- Pull 比较/准备/worker/发送：[`ServerChunkPushManager.java:516`](../../common/src/main/java/io/github/limuqy/mc/hassium/server/ServerChunkPushManager.java#L516)、[准备:545](../../common/src/main/java/io/github/limuqy/mc/hassium/server/ServerChunkPushManager.java#L545)、[工作任务:600](../../common/src/main/java/io/github/limuqy/mc/hassium/server/ServerChunkPushManager.java#L600)、[返回所有者:663](../../common/src/main/java/io/github/limuqy/mc/hassium/server/ServerChunkPushManager.java#L663)、[共享 hash 缓存:82](../../common/src/main/java/io/github/limuqy/mc/hassium/server/ChunkAuthorityHashes.java#L82)。
- 实体密度翻页：[`EntityDensityIndex.java:50`](../../common/src/main/java/io/github/limuqy/mc/hassium/server/entity/EntityDensityIndex.java#L50)；保存钩子：[`MixinChunkMap.java:306`](../../common/src/main/java/io/github/limuqy/mc/hassium/mixin/shadow/MixinChunkMap.java#L306)。
- 区块标脏：[`ClientChunkHandler.java:175`](../../common/src/main/java/io/github/limuqy/mc/hassium/client/ClientChunkHandler.java#L175)；可见性条件：[`SleepTerrainClient.java:47`](../../common/src/main/java/io/github/limuqy/mc/hassium/client/sleep/SleepTerrainClient.java#L47)。
- NBT 提交：[`AsyncSnapshotCommit.java:17`](../../common/src/main/java/io/github/limuqy/mc/hassium/concurrent/AsyncSnapshotCommit.java#L17)、[`ShadowPullClient.java:721`](../../common/src/main/java/io/github/limuqy/mc/hassium/protocol/ShadowPullClient.java#L721)；Tracking 发布/消费：[`ShadowTrackingSession.java:206`](../../common/src/main/java/io/github/limuqy/mc/hassium/shadow/track/ShadowTrackingSession.java#L206)、[消费:392](../../common/src/main/java/io/github/limuqy/mc/hassium/shadow/track/ShadowTrackingSession.java#L392)。
- 聚合提交/worker：[`EgressOutboundHandler.java:83`](../../common/src/main/java/io/github/limuqy/mc/hassium/protocol/egress/EgressOutboundHandler.java#L83)、[压缩任务:201](../../common/src/main/java/io/github/limuqy/mc/hassium/protocol/egress/EgressOutboundHandler.java#L201)；诊断格式化：[`TickMonitor.java:139`](../../common/src/main/java/io/github/limuqy/mc/hassium/utils/TickMonitor.java#L139)；服务端回调：[`MainThreadDispatcher.java:325`](../../common/src/main/java/io/github/limuqy/mc/hassium/concurrent/MainThreadDispatcher.java#L325)。

本轮没有测多人、持续新地形探索、密集 BlockEntity/NBT、活跃休眠/恢复或专用服 type-126 存档压缩；正式 profile 也没有覆盖 Netty、压缩 worker 和影子主循环。因此“主线程未形成热点”不代表这些功能总 CPU 很低；低频峰值与未采到路径须用对应场景、线程和阶段计时补证。本节完成的是源码和已有采样的优化可行性评估，尚无优化后收益数据。
