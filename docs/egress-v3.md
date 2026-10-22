# 服务端出口聚合协议 v3

## 接入与生命周期

仅服务端→客户端 PLAY。`Constants.CURRENT_PROTOCOL_VERSION=3`，能力 `LoginCaps.EGRESS_V3=1<<7` 必须与 `AGGREGATION` 同时协商。无 Hassium 客户端原版直发；旧 Hassium 协议拒绝并提示升级。字典、索引和 play_init 都就绪，客户端 EventLoop 安装字节接收器后才回 ACK。

服务端 `MixinPacketEncoder` 在原始 codec 完成后把实际协议绑定、类型/自定义通道、回调屏障与输出 ByteBuf 按身份关联。`EgressOutboundHandler` 位于原版编码器之后、compress 之前；无法关联的数据按有序屏障旁路。第三方 payload 不抽取、不重造 `RawCustomPayload`。

2.0.0 分支覆盖 1.20.1–1.21.11。`compat.EgressProtocol` 在 1.21.1+ 保留实际 `ProtocolInfo`，在 1.20.1 保留编码开始时的 `ConnectionProtocol` 和 `PacketFlow`。只有 Hassium 自有载体使用此绑定编码，原始 Mod encoder 仍只执行一次。Forge 使用原生 SimpleChannel 的 `hassium:main` 包头和消息 discriminator；自有空载体探测实际包 ID 与 discriminator，不猜测第三方格式。

NeoForge `GenericPacketSplitter` 的大小预编码会令小包 codec 执行两次。`MixinNeoForgePacketSplitter` 缓存其实际编码结果，`MixinPacketEncoder` 只在包身份和原生协议绑定完全一致时复用；拆分包分支清除缓存，保持加载器拆分流程。该桥由 NeoForge 存在性门控，客户端上行不接管。

出口 FIFO 保存原始 promise、聚合批次、直发项及 flush。压缩在共享后台池执行，但每连接同时最多一个作业；写出返回 EventLoop。聚合载体写出成功才完成其所有原始 promise，失败向原始 promise 传播并断开。控制/回调/Bundle delimiter/终结包形成屏障；未知写入不越过先前作业。原版每包 flush 可在等待窗内合并，tick 尾、屏障、批次上限和 watchdog 负责封批。队列计入载荷与每项固定管理成本，6 MiB 停止新聚合，8 MiB 硬断开。无前序等待任务的原样直发包不进入聚合等待队列，继续遵守原生 codec/传输大小限制；原生传输未完成的 promise 不因此成为聚合 backlog。接收端同理允许可立即交付的原版大包，等待解压或协议切换的字节仍受 8 MiB 上限约束。

客户端 `EgressInboundHandler` 在当前原版 decoder 前维护包含普通包、聚合载体和 read-complete 的 FIFO。识别载体时使用实际绑定 codec 得到 custom-payload 的数值 ID；解压结果逐个作为完整 native ByteBuf 回灌同一 decoder 和 bundler。常规直发包等待先前解压，不重新调用 Mod encoder。CRC/模板/长度全部验证通过才交付；native decoder 失败也立即断开。

终结包解除原生 decoder 绑定时，连同已经读入 FIFO 的后续字节一起暂停。1.21.1+ 的 `Connection.setupInboundProtocol` 完成后回 EventLoop 恢复，保留原版协议重配置的接收门控。正常远端断连导致在途写失败时，原始 promise 失败并释放状态；已关闭通道不误记为活跃协议损坏。

协议绑定改变、优化关闭后再开建立更大的会话代，旧批次仍使用封批时保留的绑定。重连创建独立 codec；不继承历史或模板。新代第一帧序号必须为 1，不能依赖历史。

## 帧格式

聚合 payload 载体仍为 `hassium:aggregation_s2c`，加载器 body codec 仍写 byte-array 长度前缀。内部格式如下，所有变长整数要求非负、最短编码。

| 字段 | 格式 | 约束 |
|---|---|---|
| version | VarInt | 固定 3 |
| sessionEpoch | VarLong | 正数，代际只能增加 |
| sequence | VarLong | 从 1 连续递增 |
| flags | byte | 低两位编码模式；bit 2 表示模板映射；其余必须 0 |
| logicalLength | VarInt | 完整原始逻辑批次长度，1–8 MiB |
| payloadLength | VarInt | 实际解压传输载荷长度，1–8 MiB |
| logicalCRC | uint32 big endian | 原始逻辑批次 CRC32C |
| dictionaryEpoch | VarInt | 仅 TRAINED，必须为正数 |
| data | 剩余字节 | RAW 或 magicless Zstd |

模式：`RAW=0`、`ZSTD=1`、`HISTORY=2`、`TRAINED=3`。载体内部帧上限为 `1 MiB−512`，给原生 custom-payload 封装留余量。逻辑批次为 `[packetCount][packetLength][完整原始包字节]...`；最多 4096 包，每包包含原生数值 ID，长度至少 1。

双方维护选中载荷解压后字节的最后 65536 字节。映射帧更新映射载荷历史，普通帧更新逻辑载荷历史；旁路原版包不进入该历史。候选包含全部头、同步命令和数据才比较。原文与映射各尝试历史和训练字典；没有训练字典则尝试无字典 Zstd，另比较 RAW，最多四次压缩。相等时保留原文/先选候选。

嵌入 JNI 的字典装载 API 自动识别 Zstd 格式字典魔数。当不透明历史恰好以该保留魔数开头时，历史不作为可用压缩候选，继续比较其他候选；保留的 64 KiB 历史本身不改写。这一极少见输入也必须无损，回归测试专门覆盖。错误不能导致跳帧。

## 模板映射

映射载荷为 `[packetCount][commandCount][同步命令...][packetRecord...]`。

| 项 | 格式 |
|---|---|
| 定义 command=1 | `[id][length][base bytes][slotCount][offset][length]...` |
| 删除 command=2 | `[id]` |
| 原文 record=0 | `[length][完整包字节]` |
| 引用 record=1 | `[id][按槽位顺序拼接的变量字节]` |

发送后的模板不可变，精确引用是零变量槽模板。发送端按类型/自定义通道和长度建立样本桶，选择变量最少的可学习模型，跨帧保留模型并累计变量槽的并集。精确模型至少观察三次；变量模型至少观察到两次内容变化。定义前使用本批次的最终变量并集，引用仍先筛常量指纹再逐字节验证全部常量；已建模板查询和样本建模各检查最多 24 个候选。

仅学习 8–4096 字节的包，最多 8 个差异段、128 个变量字节，至少 8 字节常量。表最多 2048 项、2 MiB（包括字节和固定管理成本）；样本最多 256 个模型、512 KiB，基底、最新样本和管理成本均计入预算。新定义先按本批次引用次数估算收益，计入引用标志、编号、变量字节及完整定义成本，不能仅凭未来可能命中准入；按估算收益排序，每批最多 16 个新定义。完整帧仍作最终择优，只有映射胜出才提交定义/删除/LRU 事务；未选候选不能使接收端依赖未发送模板。ID 在会话代内递增，不复用已提交 ID。删除必须引用存在项，引用/槽位/预算均先验证再提交。

发送端在会话开始的前 32 个可学习批次持续试算。之后连续失败按 1/2/4/8/16/32 帧退避，最多跳过 32 帧；出现此前未见的包类型/长度类别，或完整原文帧与逻辑载荷的大小比变化至少 20 个百分点时恢复试算。失败退避只减少学习和映射候选计算，所有原包继续正常发送；成功映射清除退避。`EgressFrameCodec.templateStats()` 分别计量试算、跳过和胜出帧。模板策略属于发送端启发式，v3 线格式和接收端校验保持一致，无新增配置键。

## 训练字典与错误

`dictionary_sync`：`[isChunkDict][length][bytes][epoch][id:long]`；`aggregation_ready`：`[ready][epoch][id:long]`。v3 必须携带代与内容 hash。沿用现有初训、语料轮换、每日训练和全连接 ACK 后激活流程；保留最多五份字典快照覆盖在途帧。首次握手字典回执不符可在尚未启用聚合时重新同步；已启用后的缺字典、模板/历史失配、CRC 或序号错误都断开。旧 ready=false 重同步恢复路径已停用。

第一帧 RAW/无字典/训练字典可以独立还原，不能 HISTORY。先验证整个逻辑批次，再提交模板和历史，再交付包。v2 聚合类、对象级管理器和旧解码工作队列已删除；没有旧帧兼容分支。

## 指标与回放

`EgressPipeline.METRICS` 提供服务端 PLAY 原始编码量、聚合载荷、旁路编码量、成功写出的 TCP 载荷、帧/包数、定义成本、精确/模板命中、压缩墙钟/线程 CPU 和等待耗时。TCP 在 framing/compression 后按写 promise 成功计量；原始量不与载体量相加。该 TCP 口径包含 vanilla 长度与压缩前缀，不包含 IP/TCP 头和重传。`/hassium stats` 展示这些独立口径。

发送端只将 native ByteBuf 复制到一份自有数组，公共 `PacketBytes` 构造与读取仍防御性复制。训练采样复用 codec 已生成的不可变逻辑批次，不再逐包重建语料；原有首训和分钟采样门保持生效。候选先计算完整帧的准确大小，最后只装配胜出的帧；模板定义收益按字段长度计算并缓存排序值，失败试算可复用已提交模板的查询索引，没有可用引用/定义时提前结束。历史、择优顺序、模板事务与 v3 字节格式保持一致。

开发测量可在游戏 JVM 设置 `-Dhassium.egressProfile=true`；`egressload` 专项自动启用。`EgressProfiler.snapshot()` 提供有界直方图的次数、总耗时、P50/P95/P99 上界及最大值。每个二进制数量级有八个桶，分位数为桶上界（最高约 12.5% 桶宽），不是精确排序值。生产默认关闭；线程分配计数器按需初始化。

| 阶段 | 范围与样本单位 |
|---|---|
| `BATCH_WAIT` | 每个聚合子包：原生编码字节进入出口 → 封批 |
| `FIFO_WAIT` | 每个批次：封批 → 开始转换自有数组 |
| `PREPARE` | EventLoop 上复制字节、组织批次与获取字典 |
| `WORKER_QUEUE` | 提交后台作业 → worker 开始执行 |
| `CODEC` / `SAMPLE` | 编码及候选择优 / 原有字典采样钩子，分开计量 |
| `EVENT_LOOP_RETURN` | 后台处理结束 → EventLoop 开始写出回调 |
| `WRITE` | 聚合载体装配开始 → 对应写 promise 完成 |
| `OUTBOUND_TOTAL` | 每个成功 PLAY 子包：编码字节进入出口 → 写 promise 成功；含旁路，不含原生编码、RTT 或客户端处理 |
| `INBOUND_WORKER_QUEUE` / `DECODE` | 客户端解压作业排队 / 解压和完整帧验证 |
| `INBOUND_EVENT_LOOP_RETURN` / `NATIVE_DELIVERY` | 解压后返回 EventLoop / 整批回灌原生 decoder；不含客户端主线程任务的后续执行 |

`PREPARE`、`CODEC`、`SAMPLE`、`DECODE`、`NATIVE_DELIVERY` 同时记录当前线程的 Java 堆分配字节。`allocationSamples=0` 表示未测量或平台不支持，不能当作零分配；不包含 native/Zstd 内存、其他线程或存活堆大小。直方图快照在活跃记录时近似一致，不同样本单位的分位数不能相加。现有 `queueNanos` 继续保持「入队到写完成」累计口径，压缩计时排除语料采样。

调试 JSONL 的 `native_v3` 是实际 native encoder 输出，`agg_v3` 是成功写出的内部帧，`phase_v3` 是开发采集阶段。采集不会额外执行 Mod codec。

`EgressReplay` 使用二进制 EG3R 语料，分别运行独立字典、历史、历史+模板三个实际 codec，检查每包字节、顺序、完整帧和 native/vanilla 封装大小。历史旧版语料只检验不透明字节算法，不证明目标版本的原生兼容或当前收益。运行示例：

```powershell
.\gradlew.bat common:runJava "-Pmc_ver=1.21.11" "-PmainClass=io.github.limuqy.mc.hassium.protocol.egress.EgressReplay" "-Pargs=<corpus.eg3r>,<report.csv>"
.\scripts\runtime-smoke-test.ps1 -Ver 1.21.11 -Loader fabric -Phase I -SessionId v3_mod -Scenario egress
```

`egress` 专项仅开发环境注册 `egress_fixture:typed_state`：State/Event 同一通道，包含 biome holder、变长字符串/列表和一次性事件。验证 512 个包的具体类型、原生 encoder 单次调用、Bundle、回调及重连。`egressbench` 在同一路径/配置下按 warmup→idle→128 个移动实体→密集 Mod payload 分阶段采集；生产环境不启用夹具。

`egressload` 由同一 harness 启动两个独立客户端 JVM、用户名、runDir 和 probe。双方聚合激活后进行 200 tick 预热（16 包/tick/连接）、200 tick 稳态（64 包/tick/连接）、200 tick 突发（每五 tick 各发 512 包）、40 tick 排空；每连接精确交付 36,480 个有序类型化包及 141 次回调。客户端逐包校验内容/顺序/执行次数；分析器同时要求两端 PASS、服务端完整阶段和发送量、零协议错误及双方分阶段测量。同步 JSONL 调试导出关闭；两个重点加载器必须串行运行：

```powershell
.\scripts\runtime-smoke-test.ps1 -Ver 1.20.1 -Loader forge -Phase I -Scenario egressload -SessionId egress_load_forge -Vd1 4 -Vd2 4 -ClientRenderDistance 4 -Offline
.\scripts\runtime-smoke-test.ps1 -Ver 1.21.1 -Loader neoforge -Phase I -Scenario egressload -SessionId egress_load_neoforge -Vd1 4 -Vd2 4 -ClientRenderDistance 4 -Offline
```

`egressstress` 复用双客户端校验，提高持续载荷并区分 tick 尾冲刷前的持续发包与冲刷后的突发发包。默认每连接1024包/tick，预热900 tick（1/4速率）、持续1200 tick、突发1200 tick（每两tick发双倍）、排空100 tick；State含3 KiB有界缓存文本，Event保持轻量。仅此开发场景冻结每日字典重训；生产环境不启用。可通过 `-EgressStressRate`、`-EgressStressWarmupTicks`、`-EgressStressMeasureTicks`、`-EgressStressDrainTicks` 调整压力与时长；客户端超时应覆盖预热、两段计量、排空和进服时间，默认工作量建议 `-ClientTimeoutSec 420`。1536档曾在突发时触发8 MiB队列保护，并非所有合法参数均保证成功。

封批窗口对照可传 `-AggregationMaxWaitTimeMs 50|20|10`，在场景 profile 之后覆盖运行目录 TOML；不传或传 0 时保留 profile 值。每场 `[EGRESS_LOAD]` 同时记录实际窗口、批次大小、压缩等级、累积直方图桶和累积 worker/进程 CPU 时钟。相同加载器的各场应从同一只读存档 ZIP 和字典开始，保持负载、视距与配置一致，并串行运行。测试结论见[初轮低压对照](handoff/egress-window-20261022.md)及[高压复测与停止决定](handoff/egress-window-stress-20261023.md)：Forge 50/10 ms各三轮完成，资源收益有限，后续NeoForge正式轮次已停止，默认仍为50 ms。

`scripts/smoke/egress_window_report.py <SessionId>... --output-dir <目录>` 校验 harness PASS、实际窗口、两端交付/回调及稳定的 worker 数量后生成 CSV/JSON。服务端主对照区间为预热结束到排空完成，另输出稳态、突发和排空区间；分位数由累积直方图桶相减后重新计算，不能相减累计 P95/P99。阶段边界附近的在途包按完成时归属，快照近似一致。CPU 采用阶段边界的累积线程/进程时钟差，避免逐小作业 CPU 差值累加的量化偏差；worker CPU 包含该池内的编码、采样等工作，进程 CPU 包含整个服务端。客户端快照仍覆盖完整客户端窗口，不能与服务端预热后区间混用为 RTT。

`EgressAllocationReplay <report.csv> [baseline.csv]` 使用四组确定性不透明输入，每组 180 帧，预热一遍、测量四遍。测量范围包含 native 字节自有化模拟、真实 v3 codec 和非采样路径；原始输入生成、native codec、解码验证、SHA-256 计算在计时外。选中帧的长度与 SHA-256 必须与可选基线 CSV 一致。归档旧 codec 时通过 `common:runJava -PprependClasspath=<旧 classes 根目录>` 串行对照；这是离线分配/耗时对照，不代表线上 TPS、GC 或端到端收益。

`EgressCaptureReplay <native.jsonl> <report.csv> [repeats=1] [verifySelected=true]` 从采集恢复字典、会话代及完整原生包，按实际批次对照三个模式。默认要求历史+模板逐字节重现实际选中帧；比较新发送策略与旧采集时显式传入 `false`，仍逐包检查原始字节、顺序、CRC 和同协议独立字典大小上限。`repeats>1` 丢弃首遍预热，报告后续连续阶段的平均 CPU/墙钟；恢复和校验位于计时外。旧编码器类可归档后放在回放 classpath 前端，使同一个回放工具对照旧策略。CSV 追加试算、跳过及胜出帧，旧编码器无该接口时为 -1。按成功 TCP 计量和聚合封装差值计算同一语料的独立字典出口基线，旁路流量保持原值。CPU 对照仅为 codec 部分；队列等待报告实际 v3 观测值，不将离线回放宣称为端到端延迟对照。

2.x 回放支持 Forge 消息前缀：native_v3 可携带 envelopePrefixBytes，agg_v3 携带 envelopeFixedBytes 与 compressionPrefixBytes；完整封装大小使用采集时的实际值。旧记录保留 Fabric/NeoForge 默认封装。26.1.2 工厂存档不能直接用于 1.x 原生测试；其采集仅用于无损字节回放，历史 CSV 不作为 2.x 的收益承诺。factoryegress 场景要求 1.21.1+ 的 tick 管理器和与目标版本兼容的存档。
