# 主线程优化独立审查（2026-10-23）

审查者为独立执行代理；仅做源码、差异与已归档运行证据的审查，没有修改生产代码，没有运行 Gradle 或游戏。集中构建、场景回归及性能结果由 validation 代理交付。审查范围以各实现代理正式交付为准，不提前检查仍在编辑的 T1/T2。

## 当前结论

T1/T2/T3及已交付R1–R13源码修复/fixture均已独立复审接受。旧污染场次撤回保持；R9四场严格正/负按原范围接受，R11/R12十二场语义/资源/退出门及最新双版移动屋檐/洞穴光照、Forge严格halo尾部后LIGHT三场证据均已核证，当前指定正确性runtime缺口已关闭。dev class/resource按逐场实际layout记录，跨场同一classes/JAR断言未获证明，不把compile output称为已打包产物；远处空间snapshot warning不冒称全引擎收敛。最终其他编译anchor、双重点remap制品身份和冻结v3八臂ABBA已做有界接受复核；CPU/loop P99/包率与报告相符，异步累计stage上升、Neo末票与单臂长尾等限制见末节，不能概括所有阶段无退化。最终总报告及disposition稳定稿的数字/结论亦已复核通过；无新生产修复或无因重跑要求。
## T3 审查依据

- `common/src/main/java/io/github/limuqy/mc/hassium/server/entity/EntityPacingConfigCache.java:14` 以整个配置快照身份缓存；`Values.from:52` 使用的开关、字符串与数值与旧 `HassiumConfigService` getter 的直接 record 访问一致，clamp 与 parser 不变。配置读取一次后构造一致快照；`HassiumConfigService.java:133` 的加载与 `:183` 的更新均替换配置引用，因此 reload 能使缓存失效。解析结果数组只在创建时写入，pacing 消费路径只读，未发现缓存数组被就地更新的入口。
- `EntityUpdatePacing.java:110` 的活动服务器检查、密度 rotate、每 tick Snapshot 发布、连接计数 drain 与 pressure sample/retain 顺序不变；`:293` 的网络 gate 仍每 tick 计算，没有把动态 gate 或压力倍率缓存进配置缓存。`:250` 的 clear 新增缓存 clear，旧配置引用可以释放。配置缓存的单线程约束沿用 pacing 原有的服务器 tick/lifecycle 模型，没有新增后台任务或跨线程共享写入口。
- `common/src/test/java/io/github/limuqy/mc/hassium/server/entity/EntityPacingConfigCacheTest.java:13` 与 `:26` 覆盖同身份复用、换身份采用新值和 clear 后重建；已阅读测试，未自行执行。parser 的容错和各开关映射本次没有改变，不要求重复为原 parser 添加镜像测试。
- `common/src/main/java/io/github/limuqy/mc/hassium/mixin/shadow/MixinChunkMap.java:306` 的 HEAD 与 `:315` 的 RETURN 现在使用相同的 `ShadowSeedServer`/null 判据。`level` 与该方法 chunk 参数在调用内稳定；非影子调用不会获得 HEAD 锁，提前 RETURN 不会遗漏解锁。影子路径仍取相同深度、减一并按相同 ChunkPos 解锁；可重入逻辑未变。原来异常退出无法命中 RETURN 的性质不由本次差异引入，不作为本次发现。
- `common/src/main/java/io/github/limuqy/mc/hassium/utils/TickMonitor.java:127`、`:141`、`:160` 只在格式化/输出外加 dispatcher gate。窗口采样、20 tick 触发以及计数清零仍在 gate 外；关闭日志不会保留旧窗口，重新开启后也不会跨窗口累计。正文格式与单位不变。
- 密度双缓冲、并发计数 getAndSet 切面和压力观察者清理均未改动。small-hotpaths.md 对不实施更复杂优化的依据与现有调用语义相符；这些采样占比不足以单独证明事件式替代有收益，不构成漏做的正确性修复。

## T2 审查依据

- `common/src/main/java/io/github/limuqy/mc/hassium/server/SectionHashCache.java:33` 的脏段集合使用不可变 State 身份加克隆 BitSet，同段重复失效合并但仍替换 State；`:22` 在任何脏段存在时拒绝整柱命中。`:49` 在锁外计算，`:73` 验证捕获 State 身份，失效、clear、淘汰或替换后不能发布旧数组；异常也没有提前清 dirty。Map 仅保留 hash/BitSet/段数，容量继续受 access-order LRU 限制；put/get/resolve 对外数组均有防御性克隆。
- cold miss 扫描全部段，已知 sectionCount 变化或索引超界按整柱 miss 处理。保留的 hash 数组与新段数补零，再仅刷新 dirty 段，尾部空气截短，与旧 computeSectionHashes→sectionHashesToArray 的规范形状一致。高段空气→非空→空气不会遗失增量失效；数组索引采用 `MixinLevelChunk.java:66` 的原版 getSectionIndex(y)，没有自行使用会在负 Y 上出错的除法。
- `ChunkAuthorityHashes.java:90` 拒绝影子端并验证服务器所有者线程和 chunk 所属 level，未把活跃 section 交给 worker。生产调用点只有 `ServerChunkPushManager.java:526` 和 `ChunkAuthorityNotifier.java:303`，均沿用 getSections 命中、miss 后 owner 刷新的流程。getSections/peek 不会对外暴露部分更新的权威柱 hash。新 helper 不增加玩家或世界引用，也不新增后台任务/队列。
- 本仓库生产失效入口仍是 `MixinLevelChunk#setBlockState RETURN`，返回 null 跳过，真实服变更按段失效，服务器关闭 `MixinMinecraftServer.java:147` 清表。BE-only 更新与 biomes 不参与原有方块内容 hash，本次没有把它们错误纳入或假定被 hash 保护。直接绕过 setBlockState 的第三方 section 写入及卸载期外部改盘也不是本次新增的覆盖保证；没有将这类既有边界列为差异缺陷。
- `PullDeltaAdmission.java:11` 只把服务端非空、hash 不同且客户端为空/planes 无效的段算入 guaranteedFull。它们逐一满足实际 `SectionDeltaPlanner.java:102` 的 FULL 判据，分母、整数百分比与 `:48` 的 75% 规则相同。真实 worker 还会通过编码大小比较增加 FULL 段，所以该下界只能提前发现必然回退，不会把仍可能成功的 DELTA 误删。全空气柱不触发，尾部省略 hash 被按零补齐与实际 snapshot 访问一致。
- `ServerChunkPushManager.java:568` 新 gate 仅令 deltaSnap 保持 null，`:576` 的完整原版区块包准备与 `:646` 的 FULL 编码/压缩仍执行，必要 section/biome/heightmap/BE/light 数据由既有 FULL 路径携带。UNCHANGED/NBT、准入配额、WorkBudget 和失败/返回释放路径没有由本次差异改动。
- 已阅读 SectionHashCacheTest 的 cold/hit、单段合并、顶部空气、随机完整对照、失败保脏、捕获中失效/clear、边界、克隆和 LRU 用例，以及 PullDeltaAdmissionTest 的临界值和对真实 planner 的随机保守性检查。未执行测试；运行验证由 validation 负责。源码核对同时确认了实际 planAndSerialize 的二次 FULL 比例检查，未仅以 helper 自己的测试替代真实调用路径审查。

T2 设计审查没有提出算法修改要求，可解除本次审查期间对 server_pull 共享生产文件的冻结，继续 T7。集中编译随后发现 `PullDeltaAdmission.java:21` 调用跨包的 package-private `SectionPlaneSyndrome.validPlanes`。修复改用 `planes == null || planes.length != SectionPlaneSyndrome.PLANE_COUNT`；已独立核对 `SectionPlaneSyndrome.java:182`，此判据与原方法精确等价，且 PLANE_COUNT 是 public static final，没有改变外部 API 可见性或协议。待 validation 重跑构建确认。T7 的待复现条件与修复验收见 server-pull.md，准确锚点为 `ServerChunkPushManager.java:662`：当前 session/level 检查尚不构成同柱更新顺序围栏。

## T1 审查与发现闭环

除下述 R1 外，已核对以下集成边界：确认从 renderer 的 drainReady 移到既有 ShadowSeedServer.runMainLoop；新队列没有新增 execute/Future 或外部持有的 LevelChunk 结果。主循环先推进 mailbox/tracking/各维光任务，再按 10ms 间隔消费最多32柱；chunkLock 内仅读层、surface 与原有持久化标志，不调用 POI/SectionStorage，也不持队列 monitor 访问世界。不同维度通过 DimensionKey 分开，converged 使用 ServerLevel identity memo；registry/实例 generation/ownShutdownInProgress 在消费及写位前复核，关停路径关闭实例队列。

已追踪网络注入（decode 先入 injectedChunks 后 mark）、读盘/生成注入、section delta、单方块/section 原版更新、relight、LIGHT 开始/完成与卸载的 mark，3×3 仅标驻留邻柱。检查中同柱再次 mark 的新 Ticket 身份不会被旧确认删除，失败检查保持票；溢出时用固定游标与 overflowRevision 防止漏掉扫描期间新溢出的变化。原版层完整性和 surface 探针没有放宽，syncLightCorrect 仍经 isColumnLightAuthoritative/wasPromotedClean 门；LightNeighborhoodGate 和客户端 pushReady/交付域没有变化。没有发现需追加修改的所有权、维度或光照门问题。屋檐/洞口移动目视验收和端到端等待仍交 validation，源码结论不代替场景结果。

### R1 / P2：软时间预算可令 overflow 永久饥饿（源码修复已复核，测试待运行）

位置：`common/src/main/java/io/github/limuqy/mc/hassium/shadow/server/LightConfirmationQueue.java:58` 与 `:82`（修复前）。drain 总是先运行 normalBudget 个常规票，之后才开始 overflow 游标；数量预算分半并不能保证时间预算分配。如果常规确认包含锁等待或层检查，首个/少数票即可耗尽 1 ms，且这些票因 incomplete/未收敛而保留，则每一轮都在 overflow 前截止。溢出但未能进入 pending 的驻留柱没有其他保存的逐柱票，可长期甚至永久不被确认。

最小确定性回归：capacity=1，mark(A) 后 mark(B) 触发 overflow，residentKeys=[A,B]；每次 drain 的 hasTime 仅允许一次 confirm，confirm(A)=false。重复多个批次，旧实现始终只访问 A，B 不会被访问。无需游戏或真实计时就能锁定公平性错误。

建议跨批次交替常规/overflow 优先或逐票交替，使预算只容纳一柱时两类工作仍前进；维持总检查数量与时间上界，并覆盖确认失败/抛异常后重试。已告 light_client 与 root，等待修复和回归交付。

关闭源码发现的依据：作者交付后重新通过 CodeGraph 阅读实际 helper 与11项测试。`LightConfirmationQueue.java:25` 的 preferOverflow 跨 owner pass 保留；`:60` 的单循环按柱切换常规/overflow，在调用 confirm 前就设置下次偏好，所以确认耗尽软预算或抛异常也不会令同一类工作每轮抢先。`:58` 只允许消费本轮起始常规票的数量，仍受总 budget 与 hasTime 限制；overflow 的失败重入、修订变化后再扫、关闭后不能复活票语义保留。新增 `LightConfirmationQueueTest.java:119` 精确覆盖 R1 的“一次检查耗尽软预算”，`:140` 覆盖 count budget=1，`:154` 覆盖多维持续 dirty 与后续 sweep 的公平性。四个 pass 内溢出 B 获得检查，常规 A 仍保留待后续完成，没有通过增加检查预算消除测试条件。R1 无需继续修改源码；回归执行与实际 backlog 仍由 validation 验证。

## 尚待交付的审查

- T1/T2：集中构建、回归与场景结果（源码审查已完成）。
- T7：普通 Pull 的旧 FULL/DELTA 覆盖后续 block/BE 更新，同柱多请求与更新排序、生命周期、失败回退及资源预算释放。

已读正式 `pull-ordering-design.md`。服务端快照期间捕获尾部、客户端异步摄入期间 fence 和有界单调 token 组合能覆盖仅服务器修订检查遗漏的窗口，当前没有必须改路线的设计矛盾。现有 1.20.1 Fabric receiver 经 client.execute、1.21.1 NeoForge receiver 经 context.enqueueWork 后进入 PayloadHandlers；新 fence 必须直接在此 owner 顺序建立，不能先后台解码或再增加一个使后续 native handler 抢先的主线程 hop。最终验收仍需实际实现的同柱版本淘汰/重拉、sleep切换、回放递归绕过、影子提交尾部与ACK、ABA及生命周期检查，以及可控暂停worker的运行时负对照/修复对照；设计文档和helper测试不构成已通过。

T1/T2/T3 此轮没有未修复的源码发现；后续T7正式审查发现与关闭状态见下节。集中测试与构建结果由validation交付。

## T7 首轮正式代码审查与修复复核

正式交付后审查了 ColumnSnapshotFence、两端 wrapper、SleepTerrainClient token桥、PayloadHandlers/ShadowPullClient、3类native handler 与Connection.send钩子，以及生产调用处和 smoke fixture。未运行 Gradle/游戏。发现 R2/R3 后先通知 validation 结束当前窗口并暂停后续执行，再交作者修复；未在构建中修改生产源码。legacy_1201 首轮由 R2 污染，不能算作完整有效旧序负对照；作者进一步检查发现 capture/native-before/receipt/release/FULL/native-after marker 实际存在，唯一 egress failure 是客户端断言 fail-fast 退出约1秒后的 Connection reset，没有独立编码/协议异常证据。修复后仍由 validation 重跑合格负对照。

### R2 / P1：官方 B 族 ready 仍用 token 0，被新通用门全部丢弃（源码修复已复核）

位置：`SleepTerrainClient.java:140` → `PullSnapshotTailClient.java:88` → `ColumnSnapshotFence.java:78`；实际生产者 `ShadowLightCompute.java:3157`。普通柱现在必须携带 fence 的正数 version；`offerBuiltChunkPacket` 却仍构造 sleepToken=0 的 ReadyItem。投递点 `ShadowLightCompute.java:3315` 的新门会丢掉所有这些官方整柱。`ShadowOfficialPacketBridge.java:66` 仍以该方法作为 B 族正常交付主出口，因此不是未使用的防御分支。

最小复现：普通模式下让 ShadowOfficialPacketBridge 转发一个合法官方整柱（没有sleep Column），ready入队成功后drain；acceptReady(key,0)必为false，客户端没有落地。修复必须在官方包产生/入队的正确边界捕获有效版本，覆盖无active fence、active fence与旧版本被替代；不能简单放行0，否则旧ready可绕过新围栏。root已授权作者定点修改该入队桥。

修复复核还需验证packet与版本确属同一快照：不能只查“此刻injectedChunk”给此前已构建的旧B包借新版本。`MixinChunkMap.java:96`可复用holder内已构建packet，且消费线程可在B构建与offer间替换注入对象。应覆盖“旧B已构建→新fence注入→旧B入队”顺序；在active fence期间拒绝不明来源旧B并由A完成或重建当前快照，均须保留非active合法B交付。

### R3 / P1：sleep已完成柱后续普通 Pull 无法绑定新 fence，尾部永不回放（源码修复已复核）

位置：`SleepTerrainClient.java:109`、`:117`、`:126`、`:146`。COLUMN_READY 后 c.done=true 的条目仍留在 columns。后续普通 FULL/DELTA request=20 建立新 fence，而 acceptResponse 在 done 分支只比较20>旧 c.request（如10），不更新 request；incomingToken 继续返回10，injectedRequest(key,10,chunk)无法绑定request20的fence。快照虽然可沿c.done/publish路径落地，startDrain因fence.chunk为空失败，native尾部持续缓冲；30秒repair后新请求再次命中同一路径。

最小复现：完成一次sleep恢复并收到COLUMN_READY，随后对同柱发普通FULL或DELTA，在worker/落地期间插入block/BE更新。断言新fence绑定本次注入对象、尾部回放完成、activeCount归零且不再生成sleep ACK。作者已获问题链与回归要求，待修复交付。

### R4 / P2：已有同hash柱的普通 FULL 复用路径不绑定fence（源码修复已复核）

位置：`ShadowLightCompute.java:2148`–`:2177`，与 `ColumnSnapshotFence.java:73`。普通空基线FULL或模式元数据缺失使nbtRepair=false，而FULL返回前影子端已有相同hash柱时，consumeLoop会复用existing或直接skip冗余投递；这些分支没有调用injected。active fence的chunk一直null，snapshotToken拒绝pack或整个分支直接跳过，尾部因此卡住到30秒repair。该条件与sleep无关。

建议通过现有forceReplace桥让active普通FULL走权威注入/绑定路径，或显式接通所有复用/无需重新可见应用的完成分支。回归需“已有同hash影子基线+空基线FULL+native尾部”，不能仅用helper手动调用injected掩盖真实生产分支。

### R5 / P2：连接内引擎关闭/失败后，旧fence没有vanilla降级收口（源码修复已复核）

位置：`PullSnapshotTailClient.java:28`、`:67`、`:124`；关联 `ShadowLightCompute.java:1398`、`:2619` 与 `ClientChunkHandler.java:119`。FULL envelope建立fence后，用户关闭chunk.enabled或影子端失败，shouldInterceptVanillaChunks变false，FULL会直接进入原版应用，或已有LIGHT完成因isEnabled=false退出，不经过injected/applied。wrapper仍截留后续native包，连接/level未变所以不会reset，只能等30秒repair；repair又可能在关闭状态重建fence。

最小复现：暂停FULL解码或LIGHT，保持连接，关闭chunk.enabled，再发送block/BE更新。恢复后应走可验证的原版降级并保留最终新值，fence/租约清空，不能反复完整重拉。需要显式恢复已暂存尾部、作废旧摄入/ready并停止关闭状态repair循环；不能仅丢尾部。已告作者与root。

其余已核对边界：服务端 token 由128/16 WorkBudget租约约束，tail capture只对放行的native block/section/BE及bundle孩子执行，快照返回先finish/discard token再回放，避免自收集；discard按对象身份不删新token，overflow回既有Pull队列重捕获。客户端 envelope 在现有owner顺序且decode hop前建立fence，clear不重置内部sequence，能够拒绝跨clear的旧非零版本；tail drain在回放后经shadow.execute→render边界并复查是否有后到更新。双gate fixture的SERVER/Client ENABLED均同时检查启动smoke开关和精确场景名，普通生产/tag不能启用latch或legacy旁路。上述边界初审未发现其他已确认缺陷，仍须核对修复后的代码及实际运行。

## T7 稳定修复复核结论

作者正式冻结生产/测试源码后复读实际差异（不是按方案推定通过）。本代理仍没有执行构建、测试或游戏，也没有修改生产文件。

- R2：`ShadowLightCompute.offerBuiltChunkPacket` 新的 LevelChunk 入口要求影子owner，持既有chunkLock后由 `SnapshotPacketAdmission.buildCurrent` 校验source/current对象，构建前后捕获并复核同一token；ReadyItem使用built.token，token0仍拒绝。active普通Pull期间B不抢交付，由A完成。旧packet重载只采用坐标查当前柱并重建内容，旧holder内容不会被贴上新token。`MixinChunkMap` 与 `MixinServerPlayer` 的生产调用都已改成柱入口；`ShadowOfficialPacketBridge.forwardChunkToRealClient` 保留维度/会话基本门。新增SnapshotPacketAdmissionTest覆盖真实KeyedPriorityQueue入队、旧B先构建后遇新fence、source身份/owner/构建中version变化。该测试验证共享生产helper和队列结构，实际MC构建/落地另由officialLanded运行探针证明。
- R3：sleep done柱在接纳更新的wire response时移除旧Column，后续incoming/injected/ready/applied均走普通fence。`SnapshotTokens` 是sleep与普通共有且不reset的AtomicLong opaque序列；sleep pending从wire c.request改为c.version，注入后c.injected仍记录wire request供本睡眠事务校验。UNCHANGED调用点明确改为injectedResponse/haloAppliedResponse，保留wire id桥；COLUMN_APPLIED及其他线消息仍发送c.request，未将opaque token放进线协议。旧sleep pending/ready不能与新普通token数值碰撞。
- R4：`ColumnSnapshotFence.requiresFullReplacement` 对active快照返回true，SleepTerrainClient.forceReplace合并此判据；因此普通FULL即使samehash/nbtRepair=false也进入原有权威注入、绑定和移除pending分支，绕开漏绑定的reuse/skip。纯观测的fullIngress回调有严格SAME gate，实际consumeLoop算法及光照门未改。helper测试检查active未绑定不能ready、实际fixture另要求same-hash-full matches=true/nbtRepair=false/replace=true。
- R5：owner tick检查engineAvailable true→false，将旧sleep ready版本失效，对active fence按nativeOnly同步恢复尾部、取消旧摄入/ready并请求一次当前FULL，随后清理idle表。后续关闭/失败状态的FULL由ClientChunkHandler在真实listener调用后直接完成native收口，尾部仍排在该FULL后，租约finish释放；不再依赖影子LIGHT完成。DELTA无法在关闭状态安全应用时转当前FULL。sleep直接完成桥在尾部结束后才按保存的wire request发ACK。由engineState边沿驱动降级，不会每tick重复触发同一轮切换；后续正常native FULL可结束fence，不固定30秒repair。off/fail fixture分别真实改运行时chunk配置或调用failShadowServer，未写用户配置文件。

五个精确场景均同时受启动smoke开关和场景名控制。pullorder在sleep恢复后要求recovered=true，再测试普通FULL/DELTA；pullordersame要求实际samehash摄入；off/fail检查nativeFulls、最终可见值与inflight归零；legacy是新JAR旁路负对照。fixture里的请求、世界改写、等待和设置变化都不能仅靠普通玩家tag触发。严格gate增加的观测调用不改变LightNeighborhoodGate、光照确认队列或交付预算。

当前没有需要追加源码修复的确认发现。尚未闭环的是集中构建/测试及上述运行时证据；若运行失败暴露新路径，应保留失败输入和marker后再修复复审，不能把helper测试或源码通过当作T7运行通过。

## 官方B fixture前置修正轻量复核

本次仅复核作者正式冻结的Probe/ScenarioEngine键、Bridge尝试计数和五个scenario，未重新检查未变业务算法、未运行构建/游戏。结论可接受，没有新增源码发现。

- 五场均先建y=139的bedrock平台，再tp至y=140，避免玩家掉落干扰sleep。准备目标柱并等待20秒后，发送(2,140,2) sentinel；显式B exercise断言先于后面的排序/sleep阶段。仍保留officialLanded，并增加officialExerciseLanded>=1和failures=0，未删除失效前置来制造通过。
- Client.ENABLED同时要求ClientSmokeTest启动gate及五个精确场景名；sentinel只在此gate内、owner上的nativeUpdate中触发一次。exerciseOfficial将当前level/connection捕获后转shadow.execute，在影子owner复查会话，并把当前注入(3,0)柱传入真实ShadowOfficialPacketBridge。owner/维度/当前对象、active fence、SnapshotPacketAdmission版本及chunkLock全部使用已审过的生产路径，没有绕开生产门或伪造成功值。
- exerciseBuild ThreadLocal仅包围这次真实B构建，finally remove；officialBuilt记录该次实际构建packet的WeakReference，officialApplied必须从B构建集合移除同一packet且引用身份相同才增加officialExerciseLanded。A族包和此前自然B包不能充数；ready持有成功入队的packet直到真实应用，弱引用不会把未落地包误判为成功。若包被替代/丢弃，断言会失败。
- Bridge新增officialAttempt只在严格Client.ENABLED内计数，用于区分自然B是否被尝试，未改变return/gate/构建/落地行为。ScenarioEngine新键只是读这个明确计数。显式exercise证明该次真实B路径可构建并落地，不证明自然holder路径一定触发，也不替代后续FULL/DELTA排序判据。
- 影子owner新增light诊断只读队列snapshot、3×3驻留/INIT/门状态。T1说明中的106是既有pendingAuthoritative表，不能与LightConfirmationQueue的pending混为一谈；目标(3,0)/(4,0)clean并曾实际落地支持fixture前置归因，但不证明全部引擎/边缘缺邻已收敛。本次没有为106修改光照算法。

前一场在排序前置assert处失败，仍不计入有效legacy排序负对照。validation需用修正后同一JAR重新确认B-exercise offered=true、B-applied exercised>=1，再取得预期旧序失败与修复正例；其余运行验收仍待闭环。

## B 前置通过后的排序完成链隔离复核

本轮只检查已冻结的 Probe、ScenarioEngine 四个只读键及五个 scenario 差异；未重审未变的 R1–R5 业务算法，未执行构建/游戏，未修改生产文件。源码结论可接受，没有新增确认缺陷；运行证据仍由 validation 交付。

- `PullOrderingProbeCompat.java:249`–`:252` 的 responses/afterReceipts/applications/checks 分别读取独立集合。response 在已武装目标柱收到 envelope 后计数；native-after 在实际收到带 tail-after 的 BE 更新后计数并发送回执，服务端 stage4 只有收到该回执才打印 acknowledged。completed 在真实完成回调计 applications，再要求 envelope 与 after 已到达才进入 checked；最终 passes/failures 仍取自影子 owner 和可见世界的实际状态比较，没有把阶段计数当作正确状态。
- `completed:285` 的 applications 表示完成回调已经发生，不能单独证明 afterReceived=true；`checked:288` 表示检查已排入执行，也不能单独证明检查成功。因此五场均在这些阶段门之后继续要求 passes 和 failures；运行验收还应核对 apply-completion 的 afterReceived=true、服务端 acknowledged 与最终 checked 日志，不能仅摘取累计计数。新增 probe 键只是 getter，没有改变生产完成时序。
- pullorder 与 pullorderlegacy 的 scenario 内容一致：B 前置、sleep 恢复、FULL/DELTA 请求、等待与四阶段断言均相同。两者 col3 都调用已有 requestNbtRepairFull；legacy 仍仅由已审过的精确 smoke/scenario gate 启用围栏旁路。显式 repair 不等待 native 更新、不新建尾部顺序或改写 snapshot，只要求实际权威替换，移除了 redundant FULL skip 这一控制干扰，仍会将 worker 捕获的旧 FULL 排在已收到的新 native 值之后，因而没有消除目标旧覆盖条件。
- pullordersame 的 col3 仍走 requestAuthoritativeFull，repair=false，并保留 sameHashFull>=1；不能用 repair 场的通过替代 R4 的真实 samehash 分支。off/fail 保留 nativeFulls、最终状态、failures=0 与 inflight=0，新增阶段门也未删掉其真实降级判据。
- legacy 没有“任意 FAIL 即成功”的源代码转换。它与正场一样先要求 responses/afterReceipts/applications/checks，再要求正确状态；缺 envelope、缺 native-after、未 apply、未检查或超时都只是无效负对照。有效负例须另外保留完整请求/更新/完成链，并确认实际旧 block/BE 值覆盖已到达的新值；checked visible=false/shadow=false 本身只证明状态不符，不能独自排除缺柱等其他原因。若当前运行输出只有布尔错误或阶段不全，仍不得登记排序复现通过。

上一场 B 已成功但 FULL 被 redundant skip、无 actual apply/checked，继续保留为 invalid，不因本次 fixture 修正追认其有效性。当前没有需先暂停 validation 才能修复的源码问题；等待同型正负运行的实际状态与阶段证据闭环。

## 事件驱动 PULL_ORDER 原语与资源契约复核

本轮正式稳定交付后，仅复核新增 PULL_ORDER 原语、Probe 的 trigger/ready/chain、PullOrderScenarioContractTest 与五份 shipped scenario；没有运行构建/测试/游戏，没有修改生产文件。结论可接受，没有新增确认缺陷。以下语义更新并取代上一节关于 checks 仅表示“已排检查”的限制。

- `ScenarioStep.java:35` 增加独立原语，`ScenarioEngine.java:234` 在既有客户端 tick/步骤执行中直接调用 scenarioStep；ENABLED 仍同时要求 ClientSmokeTest 启动 gate 和五个精确场景名，scenarioStep 还拒绝未连接、非客户端 owner、非法柱及非正超时。普通场景和仅伪造 tag 不能调用 fixture 请求或启用旧序旁路。服务端 worker/世界更新仍受原有 SERVER 双 gate 约束。
- `PullOrderingProbeCompat.java:170` 的 ready 不再假定 wake 后20秒完成，而逐 tick 等待 completedSleepColumn；实际判据为 SleepTerrainClient 的 c.done && c.publish。done 仅由本 generation/本 wire request 的 COLUMN_READY 设置，publish 仍经已有 shadow owner 队列边界后置真。pullorder/legacy 等两柱(3,0)/(4,0)，same 等目标(3,0)；off/fail 不伪装做过 sleep。90秒超时只会使步骤异常失败，不能代替恢复成功。
- request 在 Render owner 上先通过同一连接 sendCommand 发送 arm tag，再同步调用既有 ShadowPullClient 请求 API。原有 API 可经 receive-window 延后真正请求发送，但不会在此 arm 调用之前发出本次请求；两者仍走唯一 vanilla TCP，服务端 capture/beforeCompare 继续要求已收到 arm。客户端请求前清理 compare 去重，FULL 两场同为显式 repair，same 仍 authoritative/repair=false，col4 仍携带既有本地基线。没有新增网络通道、手工注入结果或绕过生产 admission。
- `scenarioStep:191` 的 chain 等 received、after、applyCompletions 和 verified。completed 的 normal 分支先经 shadow owner 读取状态，再回 Render 完成可见值比较、更新 passes/failures，最后才 `verified.add(x)`；off/fail 也在实际 native 状态比较后设置 verified。`checks:287` 已改为 verified.size，旧 checked 集合只负责防重入。因此 chain 不会因刚排异步检查就推进，也不会靠阶段计数使错误结果通过；错误比较完成后由原有 passes/failures 断言明确失败，缺任一阶段则15秒超时失败，早于30秒生产 repair。
- 五份资源都删除依赖 sentinel 原版单包形状来触发排序的命令，显式按 b→（sleep enter/wake→ready）→request→chain→阶段/状态断言推进。b 仍调用真实 B 桥并等待特定 packet 实际应用；worker latch、native-before 实收回执、response、native-after 实收回执和实际 apply/检查均保留。pullorder 与 pullorderlegacy 的资源内容仍一致，旧序控制变量仍是严格 gate 下的 fence 旁路。
- `PullOrderScenarioContractTest.java:15` 通过 getResourceAsStream 读取五份实际 classpath shipped scenario，并交给生产 ScenarioStep.parse，而非在测试内复制脚本文本。它检查 B/睡眠/恢复/请求/chain/断言顺序、每柱唯一显式请求、链等待早于生产 repair，并对删掉 request 或 ready 的变体要求失败。已阅读两项测试但未执行；它是资源顺序预检，不能证明实际 COLUMN_READY、TCP 回执或覆盖行为已发生。

事件驱动步骤消除了“sleep恢复尾部比固定等待晚到、sentinel被合并/延迟”的 fixture 假设，没有改变待验证的快照/原版更新相对顺序。此前阶段不全的场次继续标 invalid。有效 legacy 仍必须有完整实际链和具体旧值覆盖证据；阶段超时、缺 verified 或只有 FAIL 不能登记为旧覆盖复现。源码复核无阻塞，实际负/正对照及其他场景结果仍待 validation 闭环。

## R6 / P1：UNCHANGED NBT 提交的 current 谓词仍把 wire id 当 opaque version（源码修复已复核）

来源为 readyrequest1 实际90秒恢复等待失败后的独立小范围因果核查。证据目录为 `build/analysis/spark-optimization-20261023/pullorderlegacy_1201_forge-ready-timeout/`（目录名称是 forge-ready-timeout，原任务给的下划线目录不存在）。没有运行构建/游戏或修改生产；已将发现直接同步作者与root，作者也独立定位了同一漏改caller并准备修复，最终复核等其正式稳定。

位置：`ShadowPullClient.java:739`–`:744` 的 UNCHANGED/PULL_NBT AsyncSnapshotCommit.current 仍调用 `SleepTerrainClient.acceptPending(key, response.requestId())`。R3 改动后 `SleepTerrainClient.java:124`–`:127` 对存在睡眠 Column 的判据是 token==c.version；c.version 为 SnapshotTokens 的 opaque 序列，而 response.requestId/c.request 是另一套 wire 请求序列，两者没有相等保证。即使会话、level、baseline、NBT_COMMIT identity 都正确，这个谓词也通常立即失败。`AsyncSnapshotCommit.java:20` 因而在执行 visible/commit/complete 之前直接 discarded；ShadowPullClient 的 discarded 分支释放receive credit并 notePullFailure，不会 completeUnchanged→injectedResponse→applied→COLUMN_APPLIED，服务端也就不能返回 COLUMN_READY。这是生产恢复缺陷，不是 ready 观察太早或单包触发假设。

最小回归：建立未完成 sleep Column，接纳 wire request=100，对应 opaque version=不同正数；返回具有有效baseline和NBT投影的UNCHANGED。保持同会话/同level/同baseline且不发生更新，断言真实共享提交路径会执行commit与完成/ACK；再分别替换版本、request、level或baseline，要求旧提交被拒绝。修复应在接纳response后捕获其opaque版本并验证，或建立明确wire-response桥；不得放宽0或让旧wire id冒充当前版本。injectedResponse/haloAppliedResponse 的 wire 参数仍须保留。

几何与观察已核对：本场 server 日志121行的视距为8，client日志11370行玩家chunk=(0,0)。目标(3,0)/(4,0)均在恢复交付域内、在 SleepChunkWindow.light 的保留5×5之外，SleepTerrainClient.enter 的形状枚举应为两者创建Column；tracking drainAuthorityAcquires 对 needsRefresh 柱调用 requestRefresh，后者发送 COLUMN_REQUEST 并走既有 requestFull。ready 的 c.done/c.publish 都是volatile；同generation/wire request的 COLUMN_READY 将done置真，再经shadow owner边界置publish，Render可观察，判据本身合法且无需降低。

实际日志支持的范围：server145–146行明确sleep entered/resumed；client11379–11384行明确进入、wake回IDLE并开始ready等待；11422–11423行两柱LIGHT Promote，随后没有目标实际apply或服务端相应snapshot tail ACK，最终13115行ready超时。该场未保留逐目标UNCHANGED请求marker，故不能仅凭日志断言两柱每次重试都走UNCHANGED或这是唯一运行断点；但上述wire/opaque错误独立成立，足以使合法未变柱的恢复不可达，必须先修复并增加明确回归/诊断。排序请求尚未发出，本场仍非有效排序负对照。

R6 稳定修复复核：`ShadowPullClient.java:738` 在 UNCHANGED 已完成 acceptResponse、创建 NBT_COMMIT 之前捕获 incomingToken(key) 为 pendingVersion，`:747` 的 current 谓词改为 acceptPending(key,pendingVersion)。AsyncSnapshotCommit 的 inline/owner/client 三次检查使用同一捕获 opaque，仍同时校验 NBT_COMMIT identity、sourceLevel、shadow实例和baseline对象；新response、重启/断连、旧baseline替换仍能取消旧commit。completeUnchanged 仍向 injectedResponse/haloAppliedResponse 传 wire response.requestId，sleep ACK仍发 c.request，未将opaque送入协议。其余两处 acceptPending caller 分别携带 PendingEntry.sleepToken 与 DeltaWork.sleepToken，没有残余 wire 参数。R6 因果链的源码错误已关闭，可交 validation 统一检查及实际恢复重跑。

新增 `ShadowPullUnchangedTokenTest.java:14` 使用实际 AsyncSnapshotCommit 和 ColumnSnapshotFence 版本表，先证明wire id误入门会立即discard，再证明捕获opaque后 visible→owner→client完成的顺序；`:38` 覆盖捕获后替换/clear取消且释放、无旧ACK事件；`:52` 另读取实际receiver源码，拒绝 response.requestId 再接回opaque门。已阅读未执行。这是共享异步helper/版本表与receiver接线回归，其中ACK/COLUMN_READY为完成回调内的事件标记，不是实际Minecraft睡眠协议；不能用测试事件代替新运行的 COLUMN_APPLIED-send/receive→COLUMN_READY-publish证据。

只读诊断与fixture观察复核：SleepTerrainClient.snapshotState只读取真实字段；ACK发送/接收、ready接收和publish日志不改变状态。新增 `PullOrderingProbeCompat.Client.beginSleep:288` 在真实睡眠enter时设置expectedSleepGeneration并清理旧观察；sleepPublished:292仅接受相同generation与目标key。唯一调用位于SleepTerrainClient.receive的真实COLUMN_READY分支，在原有 c.done && c.version==revision 校验后设置c.publish=true之后，因此观察不能把未发布柱或旧代回调伪装成ready。ready可读取该真实publish事件的记录，防合法更新response退休已完成Column后轮询漏掉完成；观察不写入生产Column、不替代版本门。当前场景没有重连步骤，观察按每次真实enter清理；旧generation在新enter后不能充数。

最终检查新增fingerprint在shadow owner记录实际block/BE，再在Render记录可见block/BE，明确MISSING_CHUNK/MISSING_BE，与预期DIAMOND（same为STONE）及tail-after并列。它保留实际错误原因，能够避免仅凭checked布尔false认作旧覆盖；chain仍等待verified且错误状态仍由原断言失败。本轮未发现需继续编辑的源码问题，集中测试与实际恢复/排序结果仍待validation确认，原ready-timeout场不追认为有效负对照。

## R7 / P1：NeoForge payload 二次 owner.execute 令 FULL 在线路上晚于其原版尾部（源码修复已复核）

来源为 NeoForge1.21.1 正例 `build/analysis/spark-optimization-20261023/pullorder_1211_neo_r6-failed-stale-block/` 的实际旧覆盖。client7150行接收FULL487、7258行收到native-after、7425行完成应用，7541–7542行检查与具体指纹显示visible和shadow都为STONE，而BE两侧都为tail-after-3；server141–149行证明captured→native-before实收→FULL/tail=4→native-after实收。这是正例真实失败；不是缺柱/缺BE或仅可见预测错误。Forge同型正例通过不覆盖此加载器路径。

可证因果：`NeoForgeNetworkManager.java:388`–`:393` 的 sendServerPayload 无条件 server.execute；sendShadowPullResponse:422调用它。`ServerChunkPushManager.sendPullResponse` 在worker返回后，已经通过owner.execute进入服务器任务（:683附近），随后:717调用平台sender、:721立即逐包player.connection.send尾部。Neo平台sender实际只再排一个payload发送任务，方法返回时尚未发送FULL。

已通过minecraft-dev读取1.21.1原版实现核对重入语义：ReentrantBlockableEventLoop.scheduleExecutables返回 runningTask() || super.scheduleExecutables()，doRunTask执行期间reentrantCount非零；MinecraftServer.scheduleExecutables仅在此结果上加 !isStopped。故“现在是server owner”并不保证server.execute立即执行，在此worker完成任务内部第二次execute会排队，导致后续直接发送的原版DIAMOND尾部先于FULL。客户端收到尾部时尚无本次envelope fence，随后FULL覆盖为STONE；fixture的BE-after依赖客户端FULL实收回执才产生，仍能在后面正确生效，精确解释STONE+tail-after的指纹。原版1.21.1 block/section handler只有ensureRunningOnSameThread与setServerVerifiedBlockState(...,19)，未发现其独有skip逻辑；不应从客户端放宽/绕过围栏来掩盖发送重排。

建议把“owner上立即发送、非owner才调度”的语义放到平台发送桥，或使snapshot与tail在同一个实际发送事务中排队。保持现有会话、预算和权威校验，不能仅凭调用次序假定线序。最小回归必须模拟runningTask=true的可重入owner：旧sender第二次execute会延后，断言native tail先于payload的旧行为；修复桥应保证payload先于tail，同时非owner仍排owner执行。作者已确认并接手三加载器sender审计/修复，validation窗口已释放；本代理不改生产、不运行构建或游戏，最终复审等正式稳定。

R7 正式稳定修复复核：新增 `OwnerThreadDispatch.java:9` 仅根据调用点实时owner判定选择action.run或owner.execute，无新队列、缓存或生命周期引用。`NeoForgeNetworkManager.java:392` 传入 server.isSameThread() 与同一server executor；在原sendPullResponse owner任务内即同步把FULL/DELTA交给connection，后续原版tail不会再超越它；后台编码线程仍只能排owner任务，不允许直接碰发送连接。null-server仍不发；非owner的拒绝/停止行为沿用该server.execute，没有新增吞异常或无限重试。

原异常边界核对：owner内发送异常现在回到调用方原有try/catch或sendPullResponse的try/finally，后者仍释放WorkBudget/清理租约；原先二跳中的异步异常不能被外层捕获，不应为保留此缺陷而恢复二跳。sendPlayInit/dictionary/index原有日志catch继续有效。sleep控制本来在server owner同步发送，改经同桥后仍同步；dictionary/index的快照/字节编码先于调度，owner调用次序保留，非owner只多一次必要owner调度，未改载荷内容、ACK或激活协议。createAggregationCarrier与C2S不经这个S2C桥，没有被不必要改写。

独立复读了Forge与Fabric sender：Forge SPI直接CHANNEL.sendTo/sendToPlayer，后者直接CHANNEL.send；Fabric SPI直接ServerPlayNetworking.send。两者未见Neo本次无条件二次server.execute；receiver首次owner hop仍保留，不应删除。

`OwnerThreadDispatchTest.java:22` 使用实际Minecraft ReentrantBlockableEventLoop在tell/pollTask的runningTask上下文复现“tail先发、FULL后发并覆盖”；`:39` 通过生产helper检查FULL/DELTA均先于block/BE且没有遗留第二跳；`:58` 真实worker线程只排队，owner poll后才执行发送；`:71` 读取Neo实际接线源码，防桥未用于生产或字典/index/sleep旁路。已读未执行。测试准确约束本次调度根因，不代替实际Netty/egress/Minecraft状态验收。

R7 源码发现已关闭，无需追加生产编辑，可交validation统一检查与Neo原失败正例复测。必须用新JAR取得FULL先于尾部且最终DIAMOND+tail-after的实际指纹，再完成两锚点相关场景；原Neo失败仍保留为真实缺陷证据，不能因源码已修追认通过。本代理未运行构建/测试/游戏，也未修改生产文件。

## R8：Neo DELTA 后影子端仍为旧 GOLD：小范围因果核查（合并修复源码已复核）

本轮证据 `build/analysis/spark-optimization-20261023/pullorder_1211_neo_r7-partial-fail-delta/`：req540/DELTA/tail4具备完整链；client8663–8664行的具体指纹为visible DIAMOND、shadow GOLD，两侧BE均tail-after-4，passes=1/failures=1。相同场次col3/FULL在7596–7597行双端均DIAMOND+tail-after-3，说明R7的FULL发送修复已有实际成功证据，但DELTA不能算通过。本代理只读相关源码/日志，不运行构建游戏，不编辑生产；作者拥有修复窗口。

与作者交叉确认的真实边界缺陷：`MixinClientPacketListener.java:155`–`:169` 三native handler在HEAD注入，早于原版PacketUtils.ensureRunningOnSameThread。`PullSnapshotTailClient.intercept:75` 对非Render调用返回false，随后Mixin仍无条件调用ClientMetadataHandler.forwardBlockUpdate；该方法没有owner检查，会立即安排ShadowSeedServer.applyBlockUpdate影子任务。于是同一包在Netty首次调用便越过Render fence提前镜像，随后Render才拦截/暂存，并在重放时再次镜像。围栏只约束了可见handler，未完整约束影子更新。应让三类包仅在客户端owner的正确handler边界被截留/镜像；不能用放行旧token解决。作者已确认会修此边界，但尚未把它单独认作本场最终GOLD的唯一原因。

独立检查的另一条待区分路径：consumeLoop:2216–2218校验DeltaWork token并移除队列，:2228提交applySectionDelta；应用成功后若isChunkBarrierBusy为真，:2265把原始DeltaWork直接放回pendingDeltas。重入会再次修改section而非仅等待LIGHT，且put可能覆盖并发加入的work；预检查到实际withChunkLock提交之间也没有在锁内复核opaque。finishDrain换版本通常能拒后来的旧work，但不能撤回已经提前通过2216检查、尚未进入提交的工作。需用同work apply次数、token/draining和实际状态前后证据区分重复提交/迟到提交，不能仅凭一次客户端applied计数排除它。

当前日志只显示目标(4,0)一次本次DELTA LIGHT及一次实际可见应用，没有busy/reapply或tail逐包内容marker，因此本代理没有将busy回队猜测登记为本场已证根因。最终GOLD恰为fixture捕获的旧DELTA快照值；如果实际尾部均为DIAMOND，那么再次/迟到的DELTA提交或旧对象替换是应进一步证实的方向，单纯光照完成并不写回方块内容。已将明确代码链与证据边界发作者和root；下一次验收须稳定修复后取得双端DIAMOND+tail-after-4，不把当前部分成功场次追认为整体通过。

R8 正式稳定合并修复独立复核结论：源码可接受，可以进入validation游戏复测；没有新增需修复的确认发现。本代理没有构建/运行测试/游戏或编辑生产。结论关闭的是已确认源码边界，不声称旧日志已经能唯一分辨两条竞态。

- ClientMetadataHandler.forwardBlockUpdate:41在读取player/level及转发之前拒绝非Render。三Mixin hook仍先intercept，原版三个handler的ensureRunningOnSameThread保留，故Netty第一次不镜像并不会丢包：原版正常排Render重入；无active fence时仅该Render调用镜像，有fence时持有到重放，再镜像一次。strict-gated packetStage先检查ENABLED才解析包，普通运行不增加逐包遍历；镜像仍通过原有gate/实例与shadow.execute入口。
- DeltaWork持有SnapshotContentCommit；consumeLoop在ShadowPoiGate.callExclusive之内先isMainLoopThread，再校验opaque/registry/generation；chunkLock也在调度内部，并在真正apply前复核。applySectionDelta成功与invalidateLightAfterContentChange在同一owner动作完成，不能让native owner镜像插进内容提交与一次失效之间。未持chunkLock或LIGHT_ENGINE_MUTEX等待owner，不新增T1所指出的锁序反转。
- SnapshotContentCommit只在严格owner动作里写committed，成功后返回同一baseline，busy重试不再次调用apply；消费端得到gate future完成结果后再读取，沿同步完成边界可见。新work有独立状态；putIfAbsent不让旧retry替换新work。已提交baseline若不再是当前注入对象则拒绝继续LIGHT，避免旧work借新对象身份。失败未设置committed，仍由原单柱FULL回退处理，不把partial失败当成功ACK。
- 已检查runOnMainLoopAndWait的超时/拒绝/中断路径：它用claimed.compareAndSet保证同一action只被owner或inline一方领取。尚未启动的任务超时后被inline领取，strict isMainLoopThread立即拒绝世界写入，排队的原task迟到会因claimed失败而不再执行；若owner已领取，inline不重入而等待该future结果。新work仅在ownerUnavailable且仍为有效token/实例/generation时回原pendingDeltas，并通过volatile retryAt冷却1秒；canStartDelta与既有每帧drainReady pump配合恢复，不为不可用owner额外发FULL或建立独立重试队列。
- 内容提交后的LightTask携原DeltaWork；startLightBarrier转GateContext继续保留token，GATE提升后submitLightBatch仍在入光前检查opaque、registry/generation与当前baseline身份，旧版本不会因延迟邻域门丢失身份。既有3×3门、LIGHT/push门与T1确认票判据不变。断连/换代由既有pending清理、版本失效和围栏预算释放处理；旧提交在owner等待后还会被复核，不仅依靠消费前预检。
- 已阅读SnapshotContentCommitTest五项：实际Minecraft owner队列+生产AsyncSnapshotCommit/ColumnSnapshotFence构造异步内容、held tail、owner镜像与完成/预算释放；重复busy不再改内容；消费预检后replace/clear拒绝；unavailable冷却/putIfAbsent及失败语义；真实接线契约。旧GOLD再提交用例是明确的顺序负模型，未直接运行原Minecraft坏路径。tests执行与实际MC包/level正确性继续由validation证明，不能将其事件标记当端到端ACK已通过。

下一场须保留strict-gated held/replay/mirror-queued/mirror-applied与content-before/content-after/light-only-retry实际值，确认每个合法DELTA只一次内容提交、native DIAMOND在snapshot后同时落入可见端和shadow owner，最终两柱fingerprint均DIAMOND+对应tail-after文本、完整chain及inflight归零。原req540部分失败场保留为真实证据；新JAR通过之前R8运行验收仍未闭环。

## R9 / fixture：completed只匹配x，邻柱提前触发目标检查（源码修复已复核）

独立核查 `pullorder_1201_forge_r8commit1`：原始client日志7075行正在apply target=(3,-2)，7076行就出现col3 apply-completion；真正目标(3,0)的remote_pull shadow_attempt在7082行，尾部replay从7083行才开始。`PullOrderingProbeCompat.completed:384`只提取chunkX，再按armed/received/after的x集合准入，完全没有z/完整DimensionKey/事务匹配。邻柱的SleepTerrainClient.appliedAfterTail因此把目标(3,0)的检查提前排到shadow owner；那时它读取待落地快照的STONE/空BE。异步回Render时目标已完成可见更新，7118–7119行便拼出visible DIAMOND+tail-after、captured shadow STONE+空BE的跨时点指纹。

同时7109–7117行及7120/7124行的owner-state持续显示当前同一注入对象515880409为DIAMOND，完整native block/section/BE replay和mirror-applied都存在。该证据不支持“正确尾部后current柱被晚FULL换成旧对象”的原候选；不能把本场登记为新的已证生产替换缺陷。也不能通过检查此前捕获的旧chunk引用或加固定等待掩盖它，正确修复是完整key/维度/会话/事务完成关联，仍读取当前真实注入表。

已同步作者修复完成观察，并检查同类只按x的deferApply/fullIngress。要求回归覆盖同x不同z、异维、旧session/旧request回调不能置completion/checks/verified或samehash计数；合法目标只有真实apply及尾部owner完成边界才能启动验证。最终源码复审等待作者正式稳定，本代理不改生产/fixture代码，不运行构建或游戏。父任务提供的归档目录在初查时为空，本轮使用仍在的 `build/smoke-test/logs/client_pullorder_1201_forge_r8commit1.log`；归档是否完成由validation处理。

### 对既有接受场次的有界证据审计

仅按父任务要求审计双版r6opaque1 legacy FULL负例与Neo r8commit1正例，没有重复检查其他旧invalid/旧正例。下表以各原始client日志一基行号为准；这些运行当时使用的completed没有完整key/request/token回执日志，不能事后用unit或新源码补齐缺证。

| 场次 | 实际触发链/可保留事实 | 严格验收处置 |
| --- | --- | --- |
| pullorderlegacy_1201_forge_r6opaque1 | 12686 target=(3,-2) attempt→12687 col3 completion；目标(3,0)12693才attempt。12773的实际旧值指纹仍是一次可见观察，但shadow检查提前。 | 明确邻柱错关联，撤回完整有效负对照接受，精确fixture后重跑。 |
| pullorderlegacy_1211_neo_r6opaque1 | 14433 envelope request515、14731 after；14768 target=(3,0) remote_pull attempt→14769 completion→14770 applied，14831为目标z0的STONE/空visible BE旧覆盖。未见这一回调由邻柱触发。 | 可保留目标z0实际旧覆盖事实；旧completion未记录完整key/对应request/token，无法补证统一精确事务契约。作为严格最终负对照仍条件不足，重跑。 |
| pullorder_1211_neo_r8commit1 FULL | 7853/7902目标(3,0)实际apply和尾部mirror存在；7905 target=(3,1) attempt→7908 completion。8037确有双端DIAMOND+tail-after-3状态。 | 实际状态事实保留，完成观察被邻柱充数，撤回严格目标事务PASS，重跑。 |
| pullorder_1211_neo_r8commit1 DELTA | 8586 envelope request556；8789/8803目标(4,0)实际apply及尾部mirror存在；8811 target=(4,-1) attempt→8812 completion。8917确有双端DIAMOND+tail-after-4。 | 实际状态事实保留，完成观察被邻柱充数，撤回严格目标事务PASS，重跑。 |

因此，本轮范围没有可无条件沿用为“完整key及本次request/token完成后正确/预期旧覆盖”的最终场次。Neo旧negative较强，能保留其目标z0旧覆盖事实；这不等于精确fixture验收已经补齐。待R9稳定并独立复审后，由validation重跑Forge与Neo的legacy FULL负例及positive FULL/DELTA，旧证据原样保留并标明条件不足/错关联，不能悄悄改结果或继续累计作最终通过。

### R9 正式稳定修订复核

本轮仅复核正式交付的精确完成关联、两条完成桥与对应回归，不重审 R1–R8 已关闭业务算法，没有运行构建/测试/游戏或编辑生产文件。结论：R9 源码发现已关闭，没有需要先释放构建窗口才能追加修复的问题，可交 validation 执行两锚点 legacy/positive 四场。此前证据审计表的撤回及条件不足判断继续有效。

- `PullOrderProbeScope.java:8` 按完整 DimensionKey 保存 level/connection 对象身份、shadow generation、最新 wire request 与其 opaque token。arm 限两目标；response 只接受本目标/本代且更大的 wire 请求与正 token；complete 按精确 token 至多一次。相同 x 的邻柱、异维柱、旧连接/level/generation、旧 request/opaque 均不能取得当前事务完成权。fixture request 仍在 Render 先 arm 再走既有请求 API；响应 envelope 建立 scope 映射，没有把 wire id 再送入生产 opaque 判据。
- `PullOrderingProbeCompat.java:405` 的 deferApply 先检查完整 target，`:381` 的 samehash FULL 计数检查 scope.matches，native fallback 的计数和 tick 按完整 key。`:410` 的 completed 拒绝 active fence，随后检查完整 scope、after 实收和一次完成权；不会再由同 x 邻柱或旧 ready 使 applyCompletions/checked 增长。`:442` 的 owner 阶段和 `:455` 的 Render 回调再次核 scope/session/generation，旧事务即便早已排队也不能写 passes/failures/verified。
- `PullSnapshotTailClient.java:145` / `:157` 的 normal/native 两个 observer 只在真实 finishDrain 成功之后携该事务原 opaque 调用；先前尾部已跨 shadow owner 边界，无未消费 native batch 才能完成。通用 SleepTerrainClient.appliedAfterTail 不再调用 fixture completed，halo、睡眠恢复、其他柱的完成不能充数。OFF 的 exact native 观察仍要求实际 native FULL、目标具体 DIAMOND/BE 和无在途围栏，不能用单纯超时充完成。
- legacy 唯一完成桥为 `ShadowLightCompute.java:3404`：通过维度/opaque 检查、实际调用原版处理并确认目标客户端柱存在后，携 ReadyItem 原 token/来源回报；`legacySnapshotApplied:374` 只准 col3/REMOTE_PULL 或 col4/SECTION_DELTA。它与正例都以目标 snapshot 实际可见应用为前提；正例额外等待其所验证的围栏尾部，而 legacy 保留无围栏的旧序条件，不能人为等正确状态才检查。显式 FULL repair 两场相同，仍保留 worker 旧快照晚于先到 native 更新的覆盖风险，独立 samehash 场仍 repair=false。
- 严格 smoke+精确 legacy scenario gate 下，envelope 的 fence.cancel 仅淘汰上一 idle version 的旧 ready；新状态仍 inactive，不预留 tail、不 hold 原版更新、不触发 active-fence forceReplace。与正例 begin 同样拒绝上一事务的旧 ready，避免用旧事务完成本次测试；没有改快照内容或人为重放/修正 native 值。普通运行不进入该分支。
- `PullOrderingProbeCompat.java:445` 仍在捕获的合法 shadow owner 上，从 injectedChunk(dimension,x,0) 读取当时当前注入对象，打印完整 key、wire/opaque、server/generation/chunk identity，再记录真实 block/BE。Render 回调复核身份后读取当前 visible 值。没有改查旧 chunk 引用来隐去实例替换；若当前对象缺失、方块/BE陈旧，具体 fingerprint 仍失败。chain 继续等 verified，未执行或被旧事务拒绝的异步检查只能超时失败，不能登记有效旧覆盖。
- 已阅读 `PullOrderProbeScopeTest.java` 四项回归：同 x 邻柱/异维与一次完成、旧 session/generation/request/opaque、真实 Minecraft ReentrantBlockableEventLoop 加 ColumnSnapshotFence 的尾部后检查顺序、实际生产 observer 接线契约。测试明确覆盖本次误关联及异步范围；测试内 block/事件模型不能代替实际网络/两端状态。执行结果由 validation 提供。

新运行须用 envelope 与 apply-completion/check-owner 的相同完整 key、request、opaque及当前 server/generation身份，证明目标真实完成后才检查。正例还须两柱实际双端 DIAMOND+对应 tail-after、完整 chain/inflight 归零；legacy 须完整阶段与具体旧 block/BE 覆盖指纹，超时、缺柱或邻柱回调仍是无效负对照。四场重跑完成前，R9 的源码通过不等于排序运行验收通过。
### R9 四场最终严格运行证据独立核证

本轮只读取父任务指定的四个归档目录及其原始 client/server 日志、result/analysis JSON、precheck 与 SHA256 清单，没有重复全套源码审查，也没有自行构建、运行游戏或修改生产。归档根为 `build/analysis/spark-optimization-20261023/`。两负例各8文件、两正例各14文件，44项清单文件 SHA256 逐项重算均一致；正例保留两轮 probe/stats，负例因预期 fail-fast 未进入后续 probe，不能将缺 probe 改写成普通 smoke PASS。

下表行号均为各归档 `logs/client_<session>.log` 的一基行号。key=201326592 对应 overworld(3,0)，key=268435456 对应 overworld(4,0)；每条事务的 envelope→apply-completion→check-owner 的完整 key/request/token 一致，不再靠相同x的邻柱完成。owner 检查日志给出 generation=1 与实际当前 shadow/chunk identity，检查在目标实际 REMOTE_PULL/SECTION_DELTA 应用及所需尾部边界之后，verified/chain 后才进入断言。

| 归档目录 | 精确事务与完成链 | 最终实际值与判定 |
| --- | --- | --- |
| pullorderlegacy_1201_forge_r9scope1-valid-negative | FULL col3，request500/token2286；envelope12473→after12541→completion12549→owner12551→checked12557，target(3,0)/REMOTE_PULL实际attempt12548。 | fingerprint12558：可见/影子柱都存在且都STONE，预期DIAMOND；visible BE为空文本，shadow BE=tail-after-3。chain12587及四阶段断言12588–12591均完成，随后12592的passes>=1因actual0失败。有效FULL旧覆盖负对照。 |
| pullorderlegacy_1211_neo_r9scope1-valid-negative | FULL col3，request507/token2261；envelope14607→after14929→completion14944→owner14951→checked14997。 | fingerprint14999：双端STONE而预期DIAMOND，两柱存在；visible BE为空文本，shadow BE=tail-after-3。chain15093及四阶段断言15125/15128–15130通过，15131的passes>=1因actual0失败。有效FULL旧覆盖负对照。 |
| pullorder_1201_forge_r9scope1-pass | FULL col3 request529/token3231：7412→7514→7515；DELTA col4 request578/token3354：7683→7918→7919。两柱均先收到native-after(7457/7736)。 | fingerprint7519/7935均为双端DIAMOND，BE均对应tail-after-3/4；checked后passes=2/failures=0(7934)，chain:4=7954，最终inflight=0(10237)、PASS10252、exit scheduling code0=10255、Stopping10264；result ClientExitCode=0。有效正例。 |
| pullorder_1211_neo_r9scope1-pass | FULL col3 request513/token3195：7666→8124→8132；DELTA col4 request565/token3498：8752→9088→9089。native-after分别7778/8968。 | fingerprint8150/9091均为双端DIAMOND与对应tail-after-3/4；checked9090后passes=2/failures=0，chain:4=9218，最终inflight=0(13612)、PASS13627、exit scheduling code0=13630、Stopping13641；result ClientExitCode=0。有效正例。 |

负例的“旧覆盖”不只来自FAIL：Forge client12418–12419及12471/12474、Neo14435–14436及14602–14603均记录本目标DIAMOND block/section native包先正常镜像到owner，然后本次FULL落地，最终精确目标读回STONE。server对应capture→native-before→真实receipt释放worker→FULL响应→native-after ACK链完整：Forge150–158行（request500）、Neo144–152行（request507），legacy响应tail=0。不是缺chunk、未完成检查或阶段超时造成的无效失败。

退出顺序也符合负对照解释：Forge先04:09:49具体指纹/断言失败，再04:09:50 fail-fast退出，server04:09:51才Connection reset；Neo先04:11:33具体指纹/断言失败及fail-fast，server04:11:35才Connection reset。这些后续egress关闭发生在已完成的错误状态验证之后，没有证据表明它们先打断了快照链。runner/analysis的Result=FAIL与非零客户端退出保持原样，它们是正向“应正确”断言下的预期负结果，不伪装成普通smoke PASS。

正例server的FULL/DELTA均tail=4，Forge请求529/578对应server150–168行，Neo请求513/565对应142–160行；真实native-held/replay/mirror-applied仍早于精确完成/owner检查，最后两柱真实值正确，随后完整断言、两轮probe及正常退出。Forge analysis另有远处空间快照边缘缺角的warning，本轮只接受指定目标排序事务，不把它解释为全视野/全引擎光照已经收敛。

范围边界：legacy是同一R9新构建内严格gate启用的围栏旁路控制，并非实测未修改的旧JAR。两场负例均在col3/FULL失败断言处结束，col4/DELTA负分支未执行；因此可接受的是“双版FULL严格负对照 + 双版FULL/DELTA正例”，不能宣称已有DELTA负对照。旧r6/r8污染证据的撤回表保持不变，不由本次新场次追认。此四场完成本次排序有界运行闭环；samehash、off/fail、其他兼容/光照与性能仍由validation另行交付。
## samehash FULL 后全局 inflight=6 的有界资源因果核查

本轮只读 `build/analysis/spark-optimization-20261023/pullordersame_1211_neo_r9scope1-fail-inflight/`，不重审已接受的core四场，不运行构建/游戏，不编辑生产。当前结论是：目标samehash权威替换语义正确，资源门尚未闭环；现有证据不能把6项登记为孤儿，也不能把本场登记整体PASS或删除全局门。

`ScenarioEngine.java:1069` 的 pullOrder.inflight 直接返回 PullSnapshotTailClient.activeCount，后者是 ColumnSnapshotFence 内 WorkBudget.jobs（每个active snapshot租约一项）。它覆盖所有普通FULL/DELTA envelope建立的活跃围栏，不限fixture目标、视野内柱或scope，也不是pendingAuth、LIGHT票或服务端tail。waiting snapshot、已绑定等待可见/halo应用、draining等待影子owner/后续batch的事务都会占用；idle版本表不计数。正常finishDrain、cancel/repair、clear及替换旧租约会释放，正常active超时路径在started年龄大于30秒时repair，cancel后重拉FULL，并非立即删掉计数即可证明内容/光照进展正常。

目标(3,0)本次request529/token3228的日志链完整：7647 envelope，7653 samehash matches=true/nbtRepair=false/replace=true，8050精确apply-completion，8051 owner check当前chunk1855648219，8085–8086双端STONE+tail-after-3、passes1/failures0。normal observer只有finishDrain成功后触发，因此该原目标租约已释放；不能把之后global6解释成此目标原事务漏释放。8130 chain完成后，同tick连续执行几项assert，8138立即检查global=0实际6，没有资源等待步骤。

同时8110–8114刚收到五条独立DELTA，8115–8129的(4,-1)、(4,-2)、(-3,5)、(-2,5)、(-1,5)正在等待邻域/INITIALIZE。失败之后8139–8153这五柱继续提升并算光；8564/8570/8598分别有(4,-1)/(4,-2)/(-2,5)实际shadow_applied，9305有(-3,5)落地。9636才在04:18:42 fail-fast退出，距资源失败约1秒，完全没有观察正常30秒expire或更长稳定期。这证明失败采样与邻柱仍有正常工作重叠，瞬时global0不是由目标chain完成必然推出的性质。

证据限制：现有非目标envelope没有逐柱key/request/version/age/tail/draining日志；五条新DELTA与五柱LIGHT是强候选，不能精确对等全部6项，也不能将四次shadow_applied直接认作六个租约均finishDrain。第六项、最老年龄、是否有draining被新batch延长、是否重试，以及退出前全部释放均无法从本归档独立证明。因此不能宣称“只是过早且资源已收敛”，也没有证据宣称“6个真实孤儿”。作者此前提出的halo完成换opaque可能拒合法LIGHT是另一条需要独立审查的源码边界，不能在缺逐项证据时强行归因为本场6项。

已将上述行号与范围直接同步作者/root。闭环要求保留global资源门，在严格gated只读诊断中记录active key/request/version、active/draining、bound chunk/tail、age及释放原因，并提供有界等待后的全局归零或逐原事务释放证据；不能人工clear或直接删断言。当30秒repair参与释放，须明确记录重拉及最终状态，不能把不断expiry/retry隐藏成正常尾部收敛。此场samehash内容事实保留，最终资源验收等待新证据。
## R10 资源观察修订源码复核（可接受；整体窗口待R11）

本轮只读正式稳定的资源fixture/helper/诊断和实际五份scenario，没有运行构建/测试/游戏、没有生产编辑。没有发现新增资源门漏洞；R10自身可接受，但下节halo阶段凭据的真实生产缺陷须先闭环，不能只构建半稳定组合。

- `PullOrderingProbeCompat.java:256` quiescent只有严格smoke/精确scenario gate及客户端owner能调用。硬界timeoutMs<=15000；即使迟到tick观察到零，`:262`仍将done置false，随后按超时失败。`StableQuiescence.java:6` 在target/global/bytes任一非零时重置观察，全部为零至少250ms才通过；没有改预算或主动clear。五份shipped资源在目标chain后等quiescent，随后仍分别assert target/global/bytes=0，原global门保留。
- SAME的repairBaseline在B开始时捕获；`:268`在quiescent成功之前检查repair计数增长，任何repair/expired-repair都FAIL，不能靠30秒旧项过期再伪装正常收敛。finished/cancelled/repair/expired-repair/native-fallback-repair均由实际现有释放/重拉入口观察，原子计数不受512细日志限制。off/fail预期native降级repair另有原因，未修改其生产恢复规则。
- `ColumnSnapshotFence.java:129` 的activeStates在原同步保护内按active收集primitive record，返回不可变分离列表；当前wrapper活跃预算128，故实际列表<=128，不保留chunk/world对象、不调Map.get改变LRU。wrapper的age从真实started时间读取。只有quiescent首轮/5秒/超时/完成按需构造诊断，普通帧不遍历状态。512条事件细日志受严格gate及resourcesArmed控制，没有新后台队列。
- 已阅读StableQuiescenceTest三项：硬界/SAME/日志上限真实接线契约；target/global/byte活动重置250ms；实际6个ColumnSnapshotFence租约逐个startDrain/takeTail/finishDrain后方可归零，诊断无副作用/不可变。PullOrderScenarioContractTest读取实际五份classpath资源，要求bounded quiescent先于原资源断言，删除等待变体失败。测试未执行；这些helper/资源接线不证明旧same归档六项已经释放，也不能恢复其整场PASS。

原R9四场精确目标证据结论保持，资源fixture纯观察变化不会反向污染已记录事实。新same资源仍须运行证明有限正常归零及repair增量0；下述R11与其旧global6尚无可证同一因果，不能混写。

## R11 / P1：halo DELTA 正常尾部完成误退休合法 LIGHT 凭据（源码修复已复核）

作者提出候选后，本代理按CodeGraph与实际源码独立确认。此缺陷不依赖旧same日志六项的身份，也不由四场可见FULL/DELTA正例覆盖；它发生在非deliverable内容已提交、尚须LIGHT的合法分支。已直接同步作者、root及T1；不修改生产，不运行构建/游戏。

可证链：`ShadowLightCompute.java:2287` 以原opaque绑定已成功提交baseline，`:2289` 对非deliverable柱排Render haloApplied。`SleepTerrainClient.java:196`→applied:178→PullSnapshotTailClient.applied:125调用fence.startDrain，立刻将draining=true。generic `ColumnSnapshotFence.pending:43`要求同version且!draining，所以无需等尾部ACK，仅正常开始drain就令acceptPending失败；正常finishDrain:111随后还换opaque。存在sleep Column时，appliedAfterTail:192也在正常ACK时换c.version。

R8新增的 `ShadowLightCompute.java:2371` 在DELTA及保留DeltaWork的延迟GATE入光时仍用acceptPending。于是Render合法haloApplied先执行，或LIGHT等待邻域后再提交时，即使registry、generation、当前注入baseline均未变，也会continue丢掉该合法LIGHT。busy回队同一已提交work时，消费入口`:2225`同样因该正常退休丢掉原work。内容已经一次提交且清理旧光/失效，丢LIGHT不是安全地拒绝旧内容重放，而可能使halo欠光及邻域后续进展无对应重算。

最小回归应实际先commit同baseline，再执行正常halo startDrain/finishDrain（分别覆盖draining及完成后）、busy重试与延迟GATE，要求内容不重写且LIGHT仍获准；对比cancel/newresponse/unload/断连/新shadow generation/registry替换/当前baseline替换仍必须拒绝。修复要区分内容提交/尾部ACK资格与后续LIGHT资格，不能用!active或全局放宽旧opaque解决，也不能以正常ACK后简单换一个当前token让旧work冒领新内容。

R10可接受的源码结论不等于当前整体可放行；R11最小生产修复与回归正式稳定并独立复审后，才交validation统一窗口。原3×3门、确认票、push门及非影子owner锁序仍是不可放宽边界。
### R10 + R11 整体正式稳定候选最终复审

本轮只读正式稳定交付的阶段桥、实际调用点和DeltaLightPhaseTest；没有运行构建/测试/游戏，没有生产编辑。结论：R10保持上一节可接受结论；R11源码发现已关闭，当前没有需要继续编辑的确认缺陷，可交validation一次集中checks/游戏窗口。以下新结论取代上一节“整体窗口待R11”的暂停条件，不追认未验证的资源/光照运行。

- `ColumnSnapshotFence.java:48` lightCurrent仅接受当前或正常完成的正opaque；`:112`只在完整draining且无overflow/剩余tail时保存completedVersion，再关闭租约/清bound并换current版本。begin先close，cancel/clear同样清completedVersion；普通abort、replace及LRU淘汰不能留下正常完成凭据。pending仍要求!draining，Ready仍拒已完成旧opaque。仅增加既有8192容量版本记录内的一个primitive，不保留旧chunk/world，不增历史列表或新队列。
- `SleepTerrainClient.java:203` 的真实advanceAfterTail只在原request尚未done/ack且token匹配时保存lightVersion并换current版本，wire ACK仍发c.request。正常COLUMN_READY清chunk不清该阶段凭据，避免halo ACK先完成时无故丢LIGHT；新acceptResponse、restart/unload、cancelSnapshot、invalidateReadySnapshots及reset均撤销旧阶段。done Column退休转generic后不能借旧sleep opaque进入新版本表，SnapshotTokens仍全局唯一非零。
- `ShadowLightCompute.java:705` 的deltaLightCurrent要求本work确实content.applied、阶段仍合法、registry为同server、generation当前，且当前injectedChunk对象正是work保存的committed baseline。消费入口`:2232`、owner调度内部`:2254`、owner返回`:2272`与DELTA/延迟GATE提交`:2382`采用同一分阶段判据；因此Render在等待间合法drain/ACK不会误取消LIGHT，新响应/cancel/实例或baseline替换仍不能冒领。fresh内容始终acceptPending，真正apply supplier锁内`:2259`仍严格pending；已committed的applyOnce返回原baseline，不再次覆盖section/失效光照。
- busy仍putIfAbsent原work、保留已有1秒owner不可用冷却，未创建第二重试队列；GateContext保留同DeltaWork，入光前再次复核phase与baseline，不以取当前token替换原opaque。调度仍在chunkLock之外，内容写入仍严格shadow owner；LIGHT/3×3/push/确认票门未变。normal tail退休允许的是剩余LIGHT，不允许旧内容、Ready或ACK再执行。
- 已阅读DeltaLightPhaseTest四项：真实MC owner+ColumnSnapshotFence+SnapshotContentCommit在draining及finish后延迟继续LIGHT、只写一次；newrequest/cancel/clear/零token及baseline替换拒旧；实际Sleep私有Column和真实advanceAfterTail在正常ACK/READY清chunk后保留LIGHT，再由response/cancel/invalidReady/reset撤销；实际源码接线契约确保fresh仍pending、DELTA/GATE走phase。Sleep测试不发wire ACK，INIT/LIGHT是测试事件计数，不是启动原版光照引擎或端到端睡眠验收。
- strict gated phase诊断只在pending=false而lightCurrent=true时记录admitted/engine-complete，最多128细行；不写生产状态。R10的15秒硬截止、250ms目标/全局/字节归零、SAME自B起repair增量0及原global门维持，未因R11放松资源验收。

缺陷来源边界：R11是本次围栏方案集成中R8把内容谓词复用于后续LIGHT而引入的阶段回归，不冒称未改原版本的缺陷。core四场原严格目标结果保留；它们的可见柱成功不覆盖非deliverable halo路径。新集中验证仍须正常尾部后phase-admitted/engine-complete、same资源有限正常归零/无repair，以及sleep/dimension/移动光照等实际证据；旧same的六项在途身份仍不能事后补造或强归因为R11。
## Forge sleepresume 单柱未完成的独立因果核查（enter发布竞态，待正式修复复核）

本轮只读 `build/traffic-profile/sleepresume_1201_forge_r11stable1/` 的原始日志/traffic及相关完成链，不构建/游戏、不改生产，不重做core四场。client14088在04:49:10的L24 turn+using超时：state=IDLE、pending1、sample[-6,0]。IDLE来自连接ACTIVE恢复，不代表所有Column完成；ScenarioEngine:575–589仍按真实SleepTerrainClient.visible统计，wake后visible要求Column.done。因此该pending不是仅因SleepClient状态名变化而误判，应保留恢复资源/显示门。

运行事实：client8966于04:48:56进入sleep，9024于04:49:00收到active；server145/149也分别记录enter/resume。目标(-6,0)只有睡前04:48:26的cache实际apply(1111–1112、1172–1173)，wake后没有该柱LIGHT/实际apply，只有mesh重编译，后者不能代替快照/尾部完成。本代理按实际Forge wrapper与SleepControl编码离线读原agg，526条sleep_control_s2c为PREPARED1、SLEEPING1、ACTIVE1、COLUMN_READY523，未有detail以目标-6,0开头的READY/RETRY。其他柱大量已完成，目标未收到READY，与done=false一致。server151在04:49:13才egress Connection reset，晚于已记录失败退出，不能据此另外认作先发协议错误。

与作者交叉核对的真实源码竞态：SleepClient.receive:73先发布state=SLEEPING；当时SleepTerrainClient.enter:72先设置新window并令sleeping=false，再逐柱columns.put/invalidate，到83才sleeping=true。影子tracking的drainAuthorityAcquires:501可在Render填表时看到刚发布的新Column；allowsChunk在sleeping=false窗口放行，requestRefresh:102也通过，103先写retryAt=now+30s，再send COLUMN_REQUEST及requestFull。若Render在其后置sleeping=true，ShadowPullClient.request:148–149或drainQueuedRequests:193会把外5×5柱过滤，请求没有发出而30秒冷却已经留在Column。4秒后wake并不会清该retryAt，后续10秒fixture窗口内无法重新请求；正常此刻没有内容提交，不能用halo阶段凭据或缺邻LIGHT解释这一无响应路径。

此竞态是可达生产错误且足以解释“个别新Column无响应、其他523柱完成、10秒内仅一柱pending”的形态；原场没有目标Column.retryAt/请求拒绝时序日志，故不宣称已唯一捕获本场具体交错。作者离线response解析亦报告目标只有睡前request50/DELTA、wake无新响应；本代理独立核实的是SleepControl完成缺口，不用未保留的C2S/c.retryAt事实补齐运行证据。

已将最小修复意见直接发作者与root：进入时先volatile sleeping=true，再发布新Column，避免任何新Column在可刷新状态被并发观察；CHM新记录发布可确保跟踪读者看到此前睡眠门。不要以放宽visible/降低完成门或单纯增加fixture等待掩盖。确定性回归应在首个新Column发布后暂停enter，由真实刷新资格/请求准备路径尝试进入，要求无retryAt预消费、无COLUMN_REQUEST/Pull；wake后立即可发请求，无须30秒。修复及测试正式稳定后再复审；该候选不影响R11已验源码结论，也不能直接冒认原six-fence场的因果。
发布修复的并发边界补充（方案审查，尚无稳定源码）：父任务要求核查作者拟用lifecycle monitor包住reservation/发送的路径。本代理确认整段requestFull不是轻量元数据动作：ShadowPullClient.request→drainQueuedRequests→dispatchRequests→fullRequest→ShadowLightCompute.localPullEntry会tryWithChunkLock并捕获hash/snapshot；非Render还经materializeForCompare→materializeFromDiskForCompare→loadFromDisk（已有decode可join）→injectLoadedChunk（withChunkLock）。故若把原requestFull放monitor内，不能声称没有chunk锁/存储/调度依赖。当前实际tracking caller在shadow owner，不能仅此断言必然死锁，但不应引入这类大锁扩张。已同步root与作者，要求锁外准备、使用record/generation permit二检，或让Render串行执行轻量实际发送；监视器内不得等待影子owner/IO，也不得反转既有chunk/POI锁序。最终接受仍以正式稳定源码实际边界为准。
### R12 首次稳定候选复审：异常清理仍待收口

正式只读审查了SleepTerrainClient两阶段permit、ShadowPullClient队列/编码/发送、PullReceiveWindow及SleepRecoveryAdmissionTest五项；未执行测试/构建/游戏，未编辑生产。先关sleep门再发布新Column、旧观察在reserve及send二次核generation/window/Column、未发不预扣retry、prepare/hash/IO/编码移到monitor外，这些部分均符合本次发布竞态的最小修复方向。最终通过当前仍阻塞于下列真实异常边界，已同步作者/root，当前尚未放行构建。

确认异常清理竞态：`ShadowPullClient.java:223`先调用RECEIVE_WINDOW.matchesAdmission(key,oldToken)，返回时已释放窗口锁，下一行才notePullFailure；后者`:95`调用按key的cancelReceive。若在两者之间旧slot被取消且同key新admission安装，旧任务prepare/send失败便会取消新的credit/gate与围栏。原子token discard只用于discardUnsent正常拒发路径，不能让异常路径的check-then-cancel也获得事务安全。要求异常清理同样以admission token原子摘除旧事务，不能先检查再调用全key清理；回归需在旧检查后插入同坐标replacement，证明新credit、gate和围栏仍保留。

实际发送边界另外需要收口：`SleepTerrainClient.java:151`按Runnable正常返回就认wire发送并设置30秒retryAt；但Forge sendShadowPullRequest:436及Neo:413会吞发送异常，Neo在connection=null时也正常返回。因此现有failedWireSend测试只证明“会抛出的假wire”不收费，不能支持所有真实sender未发送返回也不收费的声明。需定义并沿实际平台桥报告成功/拒绝，或在保持原普通路径的同时提供严格的prepared发送出口；不能靠新的假Runnable事件替代实际桥接线。该问题未被用来冒认旧[-6,0]运行的唯一原因。

锁/资源已核的范围：final control及已编码wire的Forge1.20.1/Neo1.21.1出口均直接调用既有连接发送，未再排owner任务或同步等待；未见准备回到lifecycle monitor内。receive-window自身方法返回后才调用gate.cancelled，metadata→窗口表/围栏的路径没有由窗口monitor反向回调lifecycle。active/deferred仍沿128/8192预算，一个Column最多一个permit；clear/reset随Column表退役，旧permit对象最终检查不能借同坐标新Column。普通request未携gate时沿既有分组，sleep permit独立分组；这些结论不替代上述异常路径的精确取消修复。

新诊断保留10秒完整恢复门，在严格sleepresume/目标(-6,0)/128行限制下记录真实状态。原场retryAt未捕获的归因限制保持，旧same六项与core四场结论不因本候选改变。待作者定点修复正式稳定后，再有界复审本节异常边界并决定放行。
### R12 最终稳定修订复审：源码通过，可交集中验证

本轮只读正式修订的异常清理、boolean发送SPI/三端实现、permit与buffer交接及新增测试；没有运行Gradle/测试/游戏、没有生产编辑。上节初审两项发现均已关闭，没有新增确认阻塞。已按root授权直接告作者可唤validation一次集中checks与双版sleepresume，无须再等root批准；以下结论取代首次候选的暂停条件。

- `ShadowPullClient.java:222` 的准备/发送catch统一调用真实discardUnsent(admission)，通过receive-window.discard(key,token)原子判定并摘除旧slot，不再matches后调用按key的notePullFailure。旧失败无论先前见过什么slot，都不能删除replacement credit或取消其gate/围栏。只在原事务确被摘除时记录该准备失败；旧gate.cancelled只解除其Column上相同permit，不借同坐标新记录身份。PreparedClientSendTest:38实际调用生产discard，在旧检查后安装同key新credit/gate，要求旧清理false、新request11仍匹配且新gate不取消。
- `INetworkManagerService.java:41` 增加boolean trySendShadowPullRequest，默认false并消费buffer；原void入口委托保留。Fabric/Forge/Neo三个已注册实现均实现strict桥，使用PreparedClientSend:10检查同一当前连接及live/channel资格，异常/无连接/拒绝返回false；成功表示同一live本地连接已接受发送动作，不是远端ACK。Forge/Neo复制既有已编码body后沿原平台channel/connection直接发送，Fabric沿原legacy/modern载体发送，没有新增owner跳转、等待或chunk准备。
- `SleepTerrainClient.java:147` 的sendRefresh仅在boolean wire桥true后扣30秒retry；false或异常都在finally清相同permit，因此未发/过滤/过代拒发不留下30秒冷却。control仍先于Pull，通过同一轻量生命周期发送边界，正常重试不改3×3/5×5或sleep完成判据。prepare/hash/物化/disk/NBT/编码仍在monitor外，未重新引入大锁。
- buffer所有权按“是否已调用SPI”独立于“是否发送true”跟踪：`ShadowPullClient.java:287` 的transferred只在调用SPI前置真；若gate在调用前拒绝/控制发送抛错则caller释放；已进入SPI则由默认/具体实现接管，即使返回false也不由caller再释放。Forge/Neo在finally释放原buffer；Fabric的handedOff在成功交给原发送API后成立，后续连接变化导致bridge false也不双释已交付buffer。未见新增retain或未界定的响应payload保留。
- 已阅读PreparedClientSendTest四项：真实生产helper的null/closed/replaced/异常/成功返回、默认SPI拒绝与refCnt归零、真实旧失败清理不动replacement、三端实现与ServiceLoader实际接线契约。SleepRecoveryAdmissionTest现六项，新增实际PreparedClientSend false穿过真实sendRefresh不扣retry/不留permit。初始化暂停/旧false读者/准备后换代/真实receive-window token清理与原五项仍保留。这些测试未自行执行；helper事件和本地channel admission不是实际C2S到达、COLUMN_READY或原版世界恢复已通过。

最终验收仍需validation集中测试/两loader编译与版本边界检查，以及双版实际sleepresume在原10秒门内完成；特别核新(-6,0) install/reserve/wire→response/injected/ACK/READY链及retry剩余状态。原Forge失败没有捕获retryAt，仍仅登记为“源码可达竞态与运行缺目标响应相符”，不追认唯一具体交错。core四场严格历史证据与R10/R11已审范围保留，当前R12源码接受不替代新产物的相关运行回归。
R12集中focused首轮的测试启动边界补丁复核：本轮只核正式稳定的两点最小修订，未运行构建/测试。SleepTerrainClient:34以纯JVM属性 `hassium.smokeTest && scenario==sleepresume` 构造RECOVERY_DIAGNOSTICS；recoveryEvent:217首先!flag短路，再调用ClientSmokeTest.isEnabled，普通/单测路径不再仅因诊断触发Services初始化，实际smoke仍保留原dev gate、精确目标及128行限制。SleepRecoveryAdmissionTest@BeforeAll先setVersion(DetectedVersion.BUILT_IN)再Bootstrap.bootStrap，与既有ChunkShapeDilationTest的真实几何bootstrap模式一致，没有改恢复状态转换或断言期望。该源码小修可接受，已直接告作者唤validation原窗口复测；首次XML失败原样保留，测试真正通过仍待validation执行，不以静态短路结论冒称测试成功。
## 当前运行矩阵的有界独立汇总（R11/R12）

本轮仅核父任务指定的12场已完成归档JSON、必要原始日志及清单；没有重审已接受的R9四场，没有运行构建/游戏或触碰runtime配置。analysis目录根为 `build/analysis/spark-optimization-20261023/`，sleepresume两场在 `build/traffic-profile/`。十个analysis归档加两个traffic包的SHA256清单共164项逐项重算一致；结果均Result=PASS、HasFail=false、ClientExitCode=0，ProbeGateFailures/LogAuditFailures为空，analysis.pass=true。以下接受的是逐场实际边界，不恢复任何旧污染/失败场次。

| 归档/会话 | 已独立核到的实际验收证据 | 接受范围 |
| --- | --- | --- |
| pullordersame_1201_forge_r11stable1-pass | client7433 matches=true/nbtRepair=false/replace=true；7688双端STONE+tail-after-3；7836 target/global/bytes0稳定277ms，repairs0/expired0；9938 PASS。 | 同hash FULL真实替换、目标内容与有限正常资源收敛。 |
| pullordersame_1211_neo_r11stable1-pass | 7355同hash分支；7656双端STONE+正确BE；7691先记录7个非目标active、14307在2985ms稳定全零且repair0/expired0，14399 PASS。 | 同上，证明有非目标在途仍有限正常归零；不补造旧same六项。 |
| pullorderoff_1201_forge_r11stable1-pass | 6793实际native-fallback visible=true；6811/6835两次全资源稳定零(284/296ms)，repair恰1、expired0；6854 PASS。 | 运行时关闭chunk后的实际原版收口/有限repair。 |
| pullorderoff_1211_neo_r11stable1-pass | 7082 native-fallback；7099/7124稳定全零(283/253ms)，repair1/expired0；7143 PASS。 | 同上。 |
| pullorderfail_1201_forge_r11stable1-pass | 7197 native-fallback；7214/7238稳定全零(282/252ms)，repair1/expired0；7257 PASS。 | 实际shadow失败后的原版收口/有限repair。 |
| pullorderfail_1211_neo_r11stable1-pass | 7097 native-fallback；7114/7139稳定全零(290/252ms)，repair1/expired0；7158 PASS。 | 同上；analysis有远处SPATIAL_SNAPSHOT_INCOMPLETE warning，不外推全视野完整。 |
| sleepresume_1201_forge_r12stable1 | turn/move/esc/sprint的visiblePending0分别4571/4199/4144/4102ms，原10s门保留；23955真实mining stage6；24120 PASS。 | 四种同连接恢复与原有输入/实体/挖掘检查；旧(-6,0)新请求/ACK链已到达。 |
| sleepresume_1211_neo_r12stable1 | 四种恢复4653/4199/4042/4143ms；30054 mining stage6；30221 PASS。 | 同上。 |
| pullorder_1201_forge_r12stable1-pass | FULL req510/token3190 key201326592：7324→7602→7603；DELTA req559/token3337 key268435456：7774→7890→7891。7605/7893双端DIAMOND+对应BE；passes2/fail0；9900资源全零稳定251ms、repair0/expired0；9919 PASS。 | R12新dev运行的精确FULL/DELTA正序及资源。 |
| pullorder_1211_neo_r12stable1-pass | FULL req515/token3244：7883→8266→8267；DELTA req564/token3543：8915→9410→9412；8269/9417双端DIAMOND+对应BE；passes2/fail0；14688稳定全零253ms、repair0/expired0；14707 PASS。 | 同上。 |
| dimension_1201_forge_r12stable1-pass | 四份probe真实dimension为OW→Nether→End→OW；实际arrived5211/10925/16368；DimensionGateFailures为空，18866 PASS。 | 真换维与该scenario维度门；不声称普通双轮stats全通过。 |
| dimension_1211_neo_r12stable1-pass | 四probe同顺序；arrived5364/10639/15341；维度gate空失败，17932 PASS。 | 同上。 |

表中positive箭头分别为envelope→apply-completion→check-owner，完整key/request/token一致，owner日志有当前shadow/generation/chunk identity；两柱对应具体fingerprint均存在，不只依赖passes计数。dimension的Round2Pass=false/后续stats gate=false是其场景口径，四次dump不是普通重连双轮；本轮同时核了实际arrived与probe维度，不能把全局Result=PASS改写成四轮stats/光照指标全部通过。两版所有probe joined=true且实际clientApplied均313；这只证明该snapshot的交付，不等于全引擎收敛。

R11实际halo阶段附证已独立核Neo same原始日志：13248→13280为同key4503599291826167/token4471/chunk1609610655的admitted→engine-complete；13863→13896为key4503599560261622/token5047/chunk1648266527，均pending=false、lightCurrent=true。可保留Neo正常尾部退休后真实LIGHT future继续的事实。T4报告Forge移动场没有phase行，不能用资源finished或普通LIGHT通过冒称Forge已覆盖同一严格延后交错；作者正准备独立strict-gated halo fixture，正式源码/运行另审。本矩阵不接受其尚未交付的证明。

### R12睡眠原失败目标、字典与隔离边界

两版旧目标均为完整key4503599224717312=(-6,0)，新的安装记录明确sleeping=true、retryMs0；wake后reserve不扣冷却，wire-sent后才约29999ms。Forge第一轮request708：9853 reserved→9854 wire→9856 response→9861 injected（opaque4427）→10020 COLUMN_APPLIED→10037 matching COLUMN_READY；其generation1792703649384与该轮server enter/resume一致。Neo第一轮request729：10549→10550→10712 response→10713真实UNCHANGED owner commit→10714 injected（opaque4526）→10968 ACK→10998 READY，generation1792703830495一致。完整visiblePending0随后在原10秒内发生；没有把打印在更新前的done=false误当最终未完成，也没有用睡前generation0响应/mesh充数。四种恢复日志分别确认identity/input/near-entity保留，实际server resumed usingItem=true与尾部记录存在。

两场dictionary.bin实物、dictionary-sha256.json及runtime-manifest字典hash都为 `E393FA8D66693E71A5440DD01EA0C438B097B3041B70D596654304A981A7C9CA`。两包都有before/effective、restore-manifest及隔离client-cache证据；所有manifest标existed的原文件备份均存在，harness/result与外层流程正常退出。已读traffic-profile.ps1的finally：隔离缓存证据移出/原缓存移回，逐项恢复原文件、删除原先不存在的临时配置；T4执行确认已恢复。**没有保留恢复后原路径逐字节hash快照**，后续smoke已再次写配置，无法无干扰追溯；因此本轮接受隔离/恢复流程与执行确认，不称独立逐字节还原验证，也不读取正在运行的配置来补造证明。

### dev来源指纹与跨场同产物限制

R12各场run-manifest/runtime-manifest明确version/loader/scenario/command与各自class/resource tree指纹。它们是dev run输出，不是已打包/重映射JAR，也没有两端mods artifact SHA可供本轮认证。相同版positive与sleep/dimension的loader main/resources及common resources记录一致，但common指纹root曾混用main与main+test：positive Forge main560、Neo main564；sleep/dimension的java根分别151/714，不能直接比较不同scope的hash。当前实际Forge common main为空、loader main585且包含SleepTerrainClient/PreparedClientSend/ShadowPullClient；Neo common main564/java根714、loader main581。该layout须按实际开发运行解释，不能把Forge151个test残留当作游戏common指纹。

T4还确认后续ovdlight同版tree hash与早先positive不同（文件数相同），旧记录无per-file快照可唯一识别差异内容。**跨这12场“同一classes/JAR”断言未获证明**；各场实际新recovery/精确scope/quiescent日志证明相应修订路径确曾运行，但不证明所有编译字节一致。现无业务关键类混入旧实现或污染PASS的具体证据，不因此无因撤回有效场次或要求全重跑；原日志/manifest保持，T4按实际layout补指纹说明。最终性能臂将另外实际打包/重映射两锚点、核两端mods/artifact SHA并冻结，最终artifact归属届时单独审查。

本汇总接受12场上述有界事实；原R9负例仅FULL、legacy是新构建gate旁路、旧污染撤回等限制不变。最终移动光照汇总、Forge严格halo交错、其他anchor与性能/最终包输入仍pending；不把未交付范围写成已完成。
## Forge专用halo阶段fixture复审（候选，尚未放行）

本轮只读正式稳定候选HaloLightProbe、桥接/DELTA消费与真尾部observer、场景/profile及六项helper/脚本契约；未构建、未运行游戏，未改生产。该fixture限定开发smoke+精确pullorderhalo+Forge，普通场不启用；真实B及sleep两目标ready后arm开始15秒总deadline。只从fresh DELTA内容已成功commit且injected后的真实非交付分支选择一个完整key/token/server/generation/baseline/level/connection；重试只延后原work的LIGHT，50ms cooldown、putIfAbsent不盖新work，slot冲突即撤fixture并失败，没有owner等待、chunk锁扩张或3×3门旁路。

真实ordinary finishDrain与sleep COLUMN_READY-publish分开观察：wrapper的generic opaque与sleep Column opaque不同，前者不能为sleep selected work冒认完成；sleep须实际收到匹配request/generation的COLUMN_READY后在shadow队尾publish，以原lightVersion匹配。无真实halo/过代/换会话/超时均不能通过；helper完成/失败清world refs，场景仍保留FULL+DELTA精确正序、全资源归零和repair0。helper使用真实MC owner/fence/once，但未启动原版LIGHT，实际证据仍待T4。

确认一处fixture假通过边界，已同步作者/root，当前阻塞放行：PullOrderingProbeCompat.Client.lightPhase:439–441构造事件身份时只取当前server和传入的旧inf.chunk，没有核当前injectedChunk是否仍为该baseline；HaloLightProbe.phase的engine-complete成功后立即清selected并置complete，后续validate因complete直接返回。若admitted后future完成前同柱baseline被替换，旧token仍有合法LIGHT阶段凭据，旧inf.chunk事件会借完整的旧identity记为完成，随后永远不能再发现替换。现有different-baseline事件测试只传另一对象，不覆盖“事件仍携旧对象，真实current已换”。要求phase实际桥在计数前重核registry/server/generation/current injected baseline及会话/token，失败撤fixture delay；补旧future回调遇current替换的回归。不要求改生产LIGHT/POI/内容算法，已接受12场不受此新fixture问题影响。

### R13 phase-current定点修订：源码通过，可交集中验证

正式复审新lightPhase→haloPhaseOnOwner及phaseCurrent和新增旧future回归后，上节唯一阻塞关闭。HALO分支捕获精确事件，只有所选identity才非阻塞execute到对应shadow；实际入口严格isMainLoopThread，重新读取registry实例、runtime generation、当前injected baseline、mc level/connection与pending/light凭据，再在同一helper metadata monitor中validate实际current后phase。旧inf.chunk即使仍有合法LIGHT token也不能借事件对象替代真实当前baseline；完成前替换会置失败、清selected/world refs并撤fixture defer。没有callExclusive/同步等待，未将chunk操作/owner等待放进LIGHT_ENGINE_MUTEX，普通phase日志及业务LIGHT门保持原语义。

新增回归实际用Minecraft Reentrant owner队列和CompletableFuture：先admitted，队列中替换current基线，再旧future仍携原event回调，要求complete=false/failure非空/selected清除，beforeLight不再DEFER。正常owner模型也改走phaseCurrent；接线契约要求生产桥确实owner后lookup再phase。共七项helper测试只读审阅，未由本代理执行，也没有真实原版LIGHT计算。其余strict dev+smoke+exact scene+Forge、fresh非交付DELTA选择、真实ordinary/sleep尾部完成点、15秒总界、原work有界回队及repair0/global/target/bytes0门沿上节结论。

本轮源码可放行作者直接唤T4集中checks和Forge独立pullorderhalo游戏，不需新增生产改动。最终接受仍需同一真实identity出现content-committed→实际tail-finished→released-light→admitted→engine-complete，在15秒内且pending=false/lightCurrent=true、无repair并全资源稳定零；没有真实halo DELTA即应FAIL。此前12场矩阵与Forge此严格交错尚待运行的限制不变。


## 最终三场正确性runtime补证：移动光照及Forge严格halo已接受

仅核本次正式交付三场归档JSON、导出CSV、必要原始日志及manifest，未重审十二场/源码、未启动构建/游戏，未读取或修改runtime配置。两版ovdlight各17项、Forge halo 49项，共83项归档SHA重新计算全部匹配。三场均Result=PASS、HasPass=true、HasFail=false、ClientExitCode=0、两轮Pass=true，ProbeGateFailures/LogAuditFailures为空，analysis.pass=true。

| 归档 | 实际采样/阶段证据 | 接受范围 |
| --- | --- | --- |
| ovdlight_1201_forge_r12stable1-pass | light-data两轮各471条CSV，屋檐148/洞穴192，与summary一致；四类双端漏光/暗光源及缺地面标志均0，逐CSV client/shadow sky/block mismatch0。continuous sampled42/60、movementSamples641/641，movement缺地面/漏光/暗光源与mismatch均0。原始client155120 PASS。 | 两轮实际移动屋檐/洞穴、光源与地面严格exporter门通过。 |
| ovdlight_1211_neo_r12stable1-pass | 两轮同样471/148/192，CSV异常标志/mismatch均0；continuous42/60，movement640/641，移动各异常与mismatch均0；client153909 PASS。 | 同上。 |
| pullorderhalo_1201_forge_r13stable1 | 原始client9235在06:12:25 arm；12308 committed→12309实际DEFER→12311真实tail-finished/ACK/done/publish→12398 release→12400 owner admitted→12407 owner current engine-complete，均06:12:27；13664 haloPhases1，13667 target/global/bytes0连续250ms、repairs0/expired0，13686 PASS。 | Forge先完成真实尾部/凭据退休、后继续同baseline LIGHT的严格交错与有限资源收敛。 |

移动光照的planned_positions=753是计划坐标数，不能写成每轮753个实测点；每轮实际471均含148屋檐与192洞穴。两版屋檐client/shadow sky都是6，洞穴都是0，两实际光源(0,299,320)/(0,299,448)的block light均10，满足minimum10。CSV重算与原probe.lightFixture的42/60及640–641移动样本一致，firstMismatch与movementFirstMismatch均null。analysis仍各有round2 SPATIAL_SNAPSHOT_INCOMPLETE warning（Forge三处、Neo两处对角snapshot缺口），不据此宣称全视野/全引擎完全收敛；这些warning没有掩盖目标采样缺失或上述严格exporter失败。

Forge halo完整identity始终为key4503599358935031（OW -5,-9）、token4201、server999498769、shadow generation1、baseline1559258994、level1379373724、connection1443367938。commit时Sleep request725/version4201/injected725、ack/done/publish=false；tail-finished后version4203、ack/done/publish=true，原4201的pending=false且lightCurrent=true。owner验证后的engine-complete仍同完整identity，arm至实际完成约2秒，未以随后halo步骤elapsed0伪称零耗时。原始engine future marker及owner current确认均存在；重复普通admitted日志不算第二完成，helper严格完成计数仍恰1。该事实关闭此前Forge缺phase证据，不能用普通finished计数替代。

同场普通正序也核到：FULL col3 key201326592/request487/token3127的check-owner9723与fingerprint9726，DELTA col4 key268435456/request536/token3346的check-owner10404与fingerprint10406，都在shadow999498769/gen1上读取真实当前柱，visible/shadow均DIAMOND且BE分别tail-after-3/4；10419 passes2、10420 failures0。server同request记录FULL/DELTA tail4及native-after ACK。所有断言在13686 PASS前成立，harness解析客户端exit0后才停止服务端并记录RESULT PASS，未借退出后修复改写结果。

本次halo wrapper额外留有restore-verification：九个原配置/字典相关文件的before实物hash、原manifest及恢复后记录逐项一致，matchesOriginal全true；固定运行dictionary.bin与effective字典实物均E393FA8D66693E71A5440DD01EA0C438B097B3041B70D596654304A981A7C9CA。wrapper-result的缓存before/after聚合hash均2819E166773A29BE9589B655D633CE13BE8F798CF9A8FA762216B11A4CA70B80、cacheRestored=true、exit0。只接受此场当时保存的还原后hash证据，不读取现runtime目录或倒推旧sleep场也保存过该证明。

三场manifest均是逐场dev输出来源。Forge halo明确dirty worktree、非package JAR，保留关键源码指纹及当时class/resource树；R12移动场与其他R12场的跨场同产物未证明限制继续有效，不因新增R13补证撤销或补造。当前正式指定正确性runtime缺口已闭环，无新确认阻塞；其他编译anchor、实际打包/两端mods SHA身份和性能仍pending。本代理结束相关文件扫描，后续性能窗口不做重计算或大规模读取。

## T5最终产物、八臂ABBA与报告接受复核

T4确认六个后续性能臂均退出、冻结窗口已关闭后，本代理有界只读核validation.md、八臂inputs/result/summary、最终输入清单及相关Gradle日志；未运行Gradle/游戏、未新增基准、未修改生产。T4后续明确不批量重hash，本轮只确认final-v3-SHA256SUMS格式有效且424条路径唯一；424项重算0 mismatch是T4执行确认，不冒称本代理独立完成另一轮424项重算。此前正确性独立SHA164项及后补83项结论保持，不重复运行。

产物身份接受：Forge最终remap SHA B2D53B47BE1BB474A1B0ABD8184863474627BAE5D015DA61822CBDBB4FAE4CBF/9982156字节；Neo BF5AB94B76BB60E03831319F777CA02868C51D7C0391F84D70F05902CA93D62F/9292257字节。八臂inputs逐项表明两端mods相同；两after用对应同一最终制品，两before仍分别用20F0526F…DF12A/30F76029…C60626旧制品。每版四臂world与完整config hash表一致，八臂agent/bridge源码及制品hash一致、各60秒warmup与两phase60秒测量/256实体一致，八份result均PASS且双方exit0。source-tree字段不能替代安装JAR身份：早before各471文件，最终及后before是Forge489/Neo488，后before本地源虽新仍实际装旧JAR。旧无Hassium画像、Forge无效early1与其他harness数据未混入这八臂。

独立重算两before/两after算术均值与报告主要CPU、loop P99、loaded/rendered中位数范围及实收包率表一致。Forge server CPU movement/load为5.7736→6.4855%/14.2914→15.2236%，增加0.7119/0.9322个百分点；Neo为6.2785→6.2526%/13.6832→13.9939%。server loop P99分别Forge10.60495→8.9489ms、14.2805→13.77335ms；Neo8.84925→7.3527ms、13.9893→13.1511ms。client loop P99均值Forge2.69335→1.57945、3.32745→2.16595ms；Neo2.67945→1.5836、3.2293→2.055ms。它们是harness记录的循环时长分位数之均值，不是合并样本P99、Spark sample损失率，也不能无额外观测称为端到端显示帧时长。movement_load实收数据+移动包率Forge10246.79→10244.37/s、Neo10277.42→10279.45/s，loaded/rendered未见after持续少柱。

累计ChunkPipelineProfile不能当单独measured60s阶段。报告所列最后累计SERVER_QUEUE和FULL/DELTA request-response范围匹配summary；Neo after2 DELTA p95=p99=1525.7751ms、count38，after1 p95=301.989887ms，旧before2 p99=1503.2908ms。这些不是同分位数等价或无退化证明。确认存在需要保留的重复样本阶段上升：Forge FULL GATE_WAIT p95两before1140.850687/1207.959551ms、两after1744.830463/2147.483647ms；Neo SERVER_QUEUE p95两before130.023423/117.440511ms、两after184.549375/167.772159ms。跨source宽范围重叠不能抹掉这些上升，N=2也不足以统计排除回归或归因到确认队列。主循环P99较好、功能门通过不意味着所有异步stage都更快；目前没有由该有限累计profile证明必须再改生产的具体正确性/性能bug，不要求无因重跑，但最终结论须保留可见阶段上升、噪声与归因未决。

LIGHT观测范围：旧JAR API缺失每场仅一次NA，不是pending0；after每phase61个client快照，error0/overflow=false。Forge末0/0；Neo最后movement_load的after1/2分别pending1、oldest301.1505/305.7229ms。前一movement实际after1 pending1/886.311ms、after2 pending0，不能写“每phase末都有1张约0.3s”。无逐票identity/state/reason，既不能证明丢票，也不能借正确性quiescence将性能末票说成已完成。Cumulative maxWait和high-water按原表保留，不换算CPU开销。

其他compile anchor日志1.21.2/1.21.5/1.21.6/1.21.9/1.21.11 Neo与1.21.11 Fabric、R12两重点Fabric SPI编译、R13 Forge/Neo编译及scan均BUILD SUCCESSFUL。两重点remap/native verifier均成功、zstd-jni1.5.7-7实际打包记录存在；Neo clearLevel remap warning公开保留。测试执行范围须区分R12 fullfocused与R13专项，不能声称R13改动后又跑了同一fullfocused。正确性引用沿已接受R9/11/12/13逐场证据，legacy明确同新dev构建strict gate旁路、仅FULL负例，历史污染撤回保持；dev场不冒认最终remap JAR。

本轮已向T4定点纠正文档中的Neo源数489→488、Neo before客户端wireMiB均值1.45→1.43、pending所属phase三处实数/范围错误，并核修订已正确；要求补明fullfocused时点与上述stage上升限制。最终接受为“正确性及有界产物/性能验收通过，保留观测限制”，不升级为所有stage无退化、同跨场dev制品、全引擎收敛或已归因CPU收益。无新增生产修复阻塞；总报告和disposition稳定后仅核其结论不越过这些边界。



### T5稳定总稿与补充均值最终收口

最后只读复核正式稳定的spark-optimization-results-20261023.md、disposition.md及validation新增mean表。八臂summary.metrics的server/client mean与各client-control phase.json的threadCpuNs/count独立重算匹配：server mean movement/load分别Forge2.199545→2.061804、6.775858→6.584626ms，Neo2.248227→2.081024、6.409229→6.104948ms；client mean分别Forge.694334→.661507、.715149→.685571ms，Neo.655826→.616021、.685521→.644430ms。render-thread CPU/loop均值同表四组合.678216→.651270、.698104→.672243、.643169→.604924、.671567→.631256ms。server每臂1207样本，client约82k–98k，边界/harness工作包含在aggregate CPU/loop中，非GPU或端到端呈现延迟。

稳定总稿/处置已同时陈述均值/P99本窗口下降、CPU未全面下降、Forge FULL gate与Neo queue重复样本p95上升及N=2/未归因限制；没有用宽区间重叠或旧p99抵消新p95。R12 fullfocused与R13专项范围分开，Neo自然halo确来自R11 same，客户端影子引擎失败没有冒充权威服务端故障，正确性旧污染撤回保持。两份稳定稿结论可接受，已直接告T3可关闭“独立复核pending”，不需新增生产修订或运行。

perf-disposition.md的有界处置理由已读：零采样不等于零成本、4ms/main-only/N2/warmup局限、GATE统计群体不同不撤销p95上升、不为削开销绕过内容/线程/3×3门，均与本审查接受边界相符。该作者另读32个现成Spark JSON所得comparePull/实体self样本数不是本代理逐节点重算，引用归其执行结果；它们不能抵扣ThreadMXBean CPU或建立新的唯一因果。本轮没有追加这类重计算。

尚有validation文档两个标签收尾交T4：失败对象应精确叫客户端影子引擎；R11引文4471/5047是token而非三元空间坐标。实际运行/阶段身份无误，属于文档命名更正，不构成新的生产/基准阻塞；T3使用小SHA清单链接避免硬编码陈旧报告hash。最终接受结论为**要求的有界优化、正确性、编译打包和冻结对照已闭环，保留测得回退与观测限制**，绝非全面性能无退化或所有未来研究项已实现。

T4最后两处事实标签现已只读确认正确：validation:54为客户端影子引擎失败，:58明确token4471/柱(-6,-9)、token5047/柱(-2,-10)，小清单记录validation SHA84f9224d5ec0148c77013acb32402a77bda7167a97918fab0640e38ee3cd925e。总稿及disposition末段已注明独立最终复核通过，主数据与接受限制一致。最终无未闭环生产/正确性/基准阻塞；disposition开头尚有一句旧“性能数据小段待复核”，属于状态文字残留，已交root最终整合时同步，不影响本次最终接受。
