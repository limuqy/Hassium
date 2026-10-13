# 服务端出口聚合协议 v3

## 接入与生命周期

仅服务端→客户端 PLAY。`Constants.CURRENT_PROTOCOL_VERSION=3`，能力 `LoginCaps.EGRESS_V3=1<<7` 必须与 `AGGREGATION` 同时协商。无 Hassium 客户端原版直发；旧 Hassium 协议拒绝并提示升级。字典、索引和 play_init 都就绪，客户端 EventLoop 安装字节接收器后才回 ACK。

服务端 `MixinPacketEncoder` 在原始 codec 完成后把实际 `ProtocolInfo`、类型/自定义通道、回调屏障与输出 ByteBuf 按身份关联。`EgressOutboundHandler` 位于原版编码器之后、compress 之前；无法关联的数据按有序屏障旁路。第三方 payload 不抽取、不重造 `RawCustomPayload`。

NeoForge `GenericPacketSplitter` 的大小预编码会令小包 codec 执行两次。`MixinNeoForgePacketSplitter` 缓存其实际编码结果，`MixinPacketEncoder` 只在包身份和原生协议绑定完全一致时复用；拆分包分支清除缓存，保持加载器拆分流程。该桥由 NeoForge 存在性门控，客户端上行不接管。

出口 FIFO 保存原始 promise、聚合批次、直发项及 flush。压缩在共享后台池执行，但每连接同时最多一个作业；写出返回 EventLoop。聚合载体写出成功才完成其所有原始 promise，失败向原始 promise 传播并断开。控制/回调/Bundle delimiter/终结包形成屏障；未知写入不越过先前作业。原版每包 flush 可在等待窗内合并，tick 尾、屏障、批次上限和 watchdog 负责封批。队列计入载荷与每项固定管理成本，6 MiB 停止新聚合，8 MiB 硬断开。

客户端 `EgressInboundHandler` 在当前原版 decoder 前维护包含普通包、聚合载体和 read-complete 的 FIFO。识别载体时使用实际绑定 codec 得到 custom-payload 的数值 ID；解压结果逐个作为完整 native ByteBuf 回灌同一 decoder 和 bundler。常规直发包等待先前解压，不重新调用 Mod encoder。CRC/模板/长度全部验证通过才交付；native decoder 失败也立即断开。

终结包解除原生 decoder 绑定时，连同已经读入 FIFO 的后续字节一起暂停。`Connection.setupInboundProtocol` 完成后回 EventLoop 恢复，保留原版协议重配置的接收门控。正常远端断连导致在途写失败时，原始 promise 失败并释放状态；已关闭通道不误记为活跃协议损坏。

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

模板不可变，精确引用是零变量槽模板。两个等长样本生成常量段和变量槽；后续先筛常量指纹，再逐字节验证全部常量。类型/自定义通道与长度隔离候选；已建模板查询和样本建模各检查最多 24 个候选。

仅学习 ≤4096 字节的包，最多 8 个差异段、128 个变量字节，至少 8 字节常量。表最多 2048 项、2 MiB（包括字节和固定管理成本）；样本最多 256 项、512 KiB。定义成本进入完整帧大小，只有映射胜出才提交定义/删除/LRU 事务；未选候选不能使接收端依赖未发送模板。ID 在会话代内递增，不复用已提交 ID。删除必须引用存在项，引用/槽位/预算均先验证再提交。

## 训练字典与错误

`dictionary_sync`：`[isChunkDict][length][bytes][epoch][id:long]`；`aggregation_ready`：`[ready][epoch][id:long]`。v3 必须携带代与内容 hash。沿用现有初训、语料轮换、每日训练和全连接 ACK 后激活流程；保留最多五份字典快照覆盖在途帧。首次握手字典回执不符可在尚未启用聚合时重新同步；已启用后的缺字典、模板/历史失配、CRC 或序号错误都断开。旧 ready=false 重同步恢复路径已停用。

第一帧 RAW/无字典/训练字典可以独立还原，不能 HISTORY。先验证整个逻辑批次，再提交模板和历史，再交付包。v2 聚合类、对象级管理器和旧解码工作队列已删除；没有旧帧兼容分支。

## 指标与回放

`EgressPipeline.METRICS` 提供服务端 PLAY 原始编码量、聚合载荷、旁路编码量、成功写出的 TCP 载荷、帧/包数、定义成本、精确/模板命中、压缩墙钟/线程 CPU 和等待耗时。TCP 在 framing/compression 后按写 promise 成功计量；原始量不与载体量相加。该 TCP 口径包含 vanilla 长度与压缩前缀，不包含 IP/TCP 头和重传。`/hassium stats` 展示这些独立口径。

调试 JSONL 的 `native_v3` 是实际 native encoder 输出，`agg_v3` 是成功写出的内部帧，`phase_v3` 是开发采集阶段。采集不会额外执行 Mod codec。

`EgressReplay` 使用二进制 EG3R 语料，分别运行独立字典、历史、历史+模板三个实际 codec，检查每包字节、顺序、完整帧和 native/vanilla 封装大小。历史旧版语料只检验不透明字节算法，不证明当前 26.x 原生兼容或当前收益。运行示例：

```powershell
.\gradlew.bat common:runJava "-Pmc_ver=26.3" "-PmainClass=io.github.limuqy.mc.hassium.protocol.egress.EgressReplay" "-Pargs=<corpus.eg3r>,<report.csv>"
.\scripts\runtime-smoke-test.ps1 -Ver 26.3 -Loader fabric -Phase I -SessionId v3_mod -Scenario egress
```

`egress` 专项仅开发环境注册 `egress_fixture:typed_state`：State/Event 同一通道，包含 biome holder、变长字符串/列表和一次性事件。验证 512 个包的具体类型、原生 encoder 单次调用、Bundle、回调及重连。`egressbench` 在同一路径/配置下按 warmup→idle→128 个移动实体→密集 Mod payload 分阶段采集；生产环境不启用夹具。

`EgressCaptureReplay <native.jsonl> <report.csv>` 从当前采集恢复字典、会话代及完整原生包，按实际批次对照三个模式。历史+模板模式必须逐字节重现实际选中帧；三个模式均检查原包字节和顺序。按成功 TCP 计量和聚合封装差值计算同一语料的独立字典出口基线，旁路流量保持原值。CPU 对照仅为 codec 部分；队列等待报告实际 v3 观测值，不将离线回放宣称为端到端延迟对照。
