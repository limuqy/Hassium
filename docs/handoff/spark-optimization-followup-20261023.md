# spark 主线程优化持续跟进（2026-10-23）

用户已授权完成 [spark 报告](spark-main-thread-20261023.md) 中的问题优化、持续复测和自主跟进，并允许建立目标；使用 [efficient-subagents](C:/Users/xiaolin/.codex/skills/efficient-subagents/SKILL.md)。本会话负责项目管理、协调和最终集成，执行代理负责调查、实现和验证，不递归派代理。

## 当前目标与范围

在 `2.0.0` 工作区先完成 1.20.1 Forge / 1.21.1 NeoForge 主线程优化。逐项落实报告中的候选：有可靠依据的项实施；已有异步或收益不足、风险过大的项给出源码/测量依据和最终处置。保持世界线程所有权、协议顺序、FULL 回退、回压及光照红线。实际完成前不把目标标为 complete。

基线证据：`build/analysis/spark-20261023/`；原始正式场次 8 场、32 份 profile。保留原始 JAR、散列、采样与 CSV，优化后使用独立目录和标签。

## 工作清单

| ID | 完整交付 | 执行代理 / 模型 | 状态 | 文件所有权与验收 |
|---|---|---|---|---|
| T1 | 光照确认去重、影子所有者调度；客户端落地/标脏/tracking/可见性候选处置 | `light_client` / GPT-6.1 Sol high | 完成；双版移动光照与阶段验证、独立审查通过 | ShadowLightCompute、ShadowSeedServer、ClientChunkHandler、ShadowTrackingSession、SleepTerrainClient/渲染 mixin、对应 helper/tests；保留邻柱门、光照正确性及生命周期 |
| T2 | Pull/hash 增量缓存、快照准备和可安全异步计算优化 | `server_pull` / GPT-6.1 Sol high | 完成；测试/运行/独立审查及有界性能处置通过 | ServerChunkPushManager、ChunkAuthorityHashes、ChunkContentHashUtil、BlockEntitySnapshot、MixinLevelChunk 的 hash 失效 hook、对应 helper/tests；失效/修订/回压/顺序必须正确 |
| T3 | 实体配置缓存、密度/计数/压力清理评估、保存钩子早退、关闭日志的格式化成本 | `small_hotpaths` / GPT-6 Luna high | 完成；实施项已验证，保留项有明确依据 | 实体 pacing/density/counters/pressure、MixinChunkMap、TickMonitor、对应 helper/tests；不改变 tick 与成对加解锁语义 |
| T4 | 集中构建、回归与优化前后性能复测 | `validation` / GPT-6 Luna high | 完成；七段编译/打包、正确性矩阵、八场v3 ABBA及清单通过 | 独占所有 Gradle/游戏执行；拥有 benchmark/report/agent 工具与验证报告；串行测量、冻结 instrumentation、校验输入散列 |
| T5 | 高风险独立审查、修复闭环 | `independent_review` / GPT-6.1 Sol high | 完成；源码、原始证据、逐场身份与最终结果独立接受 | 光照/所有权/缓存失效/异步结果、生命周期、资源预算；审查发现需修复并验证后关闭 |
| T6 | 最终集成与证据汇总 | root 项目管理 | 完成 | 总报告、候选处置、实测权衡与证据已整合，关闭本轮目标及持续跟进 |
| T7 | 普通 Pull 异步旧 FULL/DELTA 快照覆盖其间新 block/BE 更新的正确性修复 | 复用 `server_pull` / GPT-6.1 Sol high | 完成；排序/生命周期/兼容/阶段正确性闭环，性能权衡保留 | 最小失败复现、修复及生命周期/顺序/回压回归；独立方案和实现审查后纳入最终 JAR |

实现代理的独立交付说明位于 `docs/handoff/spark-optimization-20261023/`。T4 在集成就绪前准备验证工具，不检查/催促未完成实现；各实现代理不自行启动 Gradle 或游戏，避免构建竞态和性能测量污染。共享文件变更先协调。

## 验收条件

- 两个重点版本构建通过；实现代理指定的有意义回归与 `scanVersionBoundaries` 通过。
- 光照改动包含移动中的屋檐/洞口、邻柱未齐/变更、失败调度重试、断连与换维的正确性检查。
- hash/快照包含 dirty-section、其他失效入口、陈旧结果丢弃、FULL/DELTA/NBT 等价、预算释放和队列有界检查。
- 同版优化前/后各至少两场、反转顺序；复用往返＋256实体负载，比较绝对耗时、主线程 CPU、P99、实际包量、区块覆盖和交付情况，不以采样占比替代性能损失。
- 异步改动同时检查排队、在途资源和端到端交付，不能通过推迟工作获得表面收益。
- 独立审查发现已闭环；保留失败证据，说明仍存在的测量限制。

## 恢复与持续跟进

继续本任务时先读本清单、当前 goal 和各代理交付说明，复用已有代理与证据。不要重跑已有效完成的调查/测试，除非后续改动、失败或具体风险使其失效。root 不接管执行代理的具体实现/命令。

用户离开后仍按授权推进；只有真实阻塞或关键决策才需要用户输入。进度变化和完成时汇报，未变化的定时检查保持安静。禁止外传源码、profile、日志或项目材料，不发布、不提交 PR、不改变无关用户文件。

## 最新协调记录

- 已建立持续 goal，未设置 token 预算。
- 已建立本会话 heartbeat 持续跟进，每 30 分钟检查，automation ID 为 `hassium`；目标实际完成后停用，避免重复工作。
- 三个实现代理与一个验证代理已启动，保留一个并发位置供独立审查/升级。
- T2 获准定点编辑 MixinLevelChunk 的 hash 失效 hook；T1 不编辑该文件。
- T2 同时获准定点切换 ChunkAuthorityNotifier.hashFor 到统一增量 hash 入口；此文件不由其他代理编辑。
- T3 已交付 [说明](spark-optimization-20261023/small-hotpaths.md) 与配置缓存回归；保留密度双缓冲/计数 tick 边界，独立审查和集中验证尚待完成。
- T4 已保存优化前 JAR 的路径和 SHA-256 到 `build/analysis/spark-optimization-20261023/preparation.json`，正在冻结前后复测工具。
- T4 已确认现成 `ovdlight.scenario` 和 `export_ovd_light.py` 覆盖跨区块屋檐/洞穴、往返和重连；两版独立 session 运行，保留 exporter 的严格样本量及光照/地面判据。
- T1 提供只读 `lightConfirmationSnapshot()`，T4 将以新的冻结 instrumentation v2 逐秒采集确认队列/高水位/等待；不覆盖 v1，正式前后组统一使用 v2 hashes。
- T7 发现依据：普通 Pull 的 worker 快照在返回时仅校验 session/level/WorkBudget，没有普通非休眠柱的更新顺序围栏；先到的新 block/BE 更新可能被旧 FULL/DELTA 覆盖。已要求执行代理提供失败回归并修复，不能仅列“已知问题”后完成目标。最终正式构建/性能验收等待 T7 闭环。
- T2 已交付 [说明](spark-optimization-20261023/server-pull.md)：dirty-section hash 缓存和确定 FULL 时跳过 DELTA 额外快照；独立审查期间 T7 暂不改动这批生产文件，先准备复现/设计。
- T4 已冻结 `build/analysis/spark-optimization-20261023/frozen-v2` 工具，含逐秒光照确认队列观测及对称的既有 ChunkPipelineProfile 阶段直方图；尚未启动正式前后场次。
- T1 已交付 [说明](spark-optimization-20261023/light-client.md)，有界脏票与所有者轮询、8项纯 Java 回归及只读观测接口；已交独立审查。
- T4 获准在与 T7 作者协调稳定源码窗口后先做聚焦单元/双 loader 编译，及早发现集成错误；游戏/正式性能仍等待 T7，后续变更使检查失效时只补跑受影响部分。
- 初始集中检查在测试前发现 `PullDeltaAdmission` 调用 package-private `SectionPlaneSyndrome.validPlanes` 的编译错误，作者修复，保留失败日志；尚不算测试断言失败。
- T1 独立审查发现 overflow 饥饿：每轮常规 retained 票可耗尽软预算，使 overflow 永不检查；作者需补跨轮公平调度和 capacity=1 的确定性失败回归。T4 等两处修复同时稳定后恢复聚焦检查。
- T2 可见性错误已由作者修复，初始 1.20.1 聚焦集共 94 测试通过；T1 overflow 新回归尚需加入后补跑。失败证据及成功输出均由 T4 保存。
- T7 获准编辑 MixinConnection、MixinClientPacketListener、PayloadHandlers、ShadowPullClient、SleepTerrainClient、必要 compat/helper/tests，以及原有 ServerChunkPushManager；T1 不再编辑 SleepTerrainClient，仍独占 light 核心。
- T7 方案采用既有协议下服务端快照期间有界更新尾部重放＋客户端 envelope 到真实落地的同柱围栏；独立审查需验证实际 Netty/main 顺序、递归、token、halo、ACK、超限与跨会话。必须有可控暂停 worker 的运行时旧缺陷复现，不能仅靠孤立 helper 测试宣称完整修复。
- T1 R1 已改为 normal/overflow 逐柱交替并跨 owner 轮保留偏好，新增三项回归（共11项），独立复核关闭 R1；最终构建/回归尚待执行。
- T7 新增隔离 `pullorder` / `pullorderlegacy` scenario、profile、专用 probe 和无副作用数值读取已获准。负对照受启动 smoke gate 和明确场景双重限制；它是同一新 JAR 旁路 fence 重现旧行为，不冒称未修改旧 JAR 实测。
- 初始构建冻结已释放，T7 正在实施；T4 等 T7 源码稳定后再统一构建/测试，避免作者和构建互等。T1/T2/T3 源码审查已通过，T7 的最终实现未验收。
- T7 首版正式交付，包含 ColumnSnapshotFenceTest 和 FULL/DELTA 两段顺序的负/正对照 runtime fixture；T4 开始两个重点版本的聚焦测试、loader 编译、scanVersionBoundaries，再做运行回归。独立审查并行检查已交付的稳定源码，发现需要编辑时先与 T4 协调构建窗口。
- 集成检查：1.20.1 与 1.21.1 的聚焦 common:test 各 101/101 通过，Forge 1.20.1 / NeoForge 1.21.1 compileJava 通过，scanVersionBoundaries OK。T4 正进入 T7负/正对照、sleepresume/dimension 与 ovdlight；尚无最终运行或性能结论。
- T7 审查 R2：普通 acceptReady 要求非零 token，但官方 B 族 `offerBuiltChunkPacket` 仍以 0 入队，导致合法包被丢。已正式 followup 作者修复，获准定点改该 light 入队桥，不能重新放行 0。T4 已暂停新运行，当前 legacy 1.20.1 安全收尾保存证据；此受影响场次不能当有效负对照，修后需以明确“旧快照覆盖新值”断言重跑。
- T7 审查 R3：sleep 的已完成 `c.done` 条目在后续普通新请求中未更新 request/token，fence 无法绑定 chunk，可能持续缓冲并每30秒重拉；已交作者同窗口修复，并需 sleep完成后普通FULL/DELTA 回归。
- R2影响的 legacy_1201 已安全结束且无probe，另有 egress connection failure；保存在 `build/analysis/spark-optimization-20261023/pullorderlegacy_1201_forge-r2-invalid/`，不计有效负对照。已要求作者具体排查该发送失败，不能默认全部由 R2 引起。当前构建/游戏均暂停、修复窗口已释放。
- 原始日志进一步核对：该场有 capture/native-before/receipt/release/FULL/native-after 与 `checked ... visible=false shadow=false`，但 R2 污染导致仍不合格；唯一 egress failure 为断言 fail-fast 退出之后的 Connection reset，未看到独立编码/协议异常。
- 审查新增 R4：已有同hash影子柱的 FULL reuse/skip 没有绑定新fence，导致尾部等到30秒repair；新增 R5：连接/level不变但 chunk/engine 关闭或 shadow降级时直接vanilla/丢弃出口未完成fence，可能持续缓冲/无限repair。均由 T7 同窗口修复与回归。
- T7 定点扩展到官方整柱真实构建边界：MixinChunkMap影子交付桥、MixinServerPlayer影子onTrackChunk、ShadowOfficialPacketBridge整柱重载、ShadowLightCompute B族入队；不得让预制旧packet借用新版本，不改T3保存锁或原版限速。必要的 packet/payload/bridge/fixture 变更可由作者自主完成，T1光照算法/门禁与T3锁语义的设计变更仍需协调。
- T4 在正式性能前发现旧JAR缺少新队列API，已要求改为一次能力探测、安静标记NA而不循环异常，新API真实调用失败不能吞掉；将以独立 frozen-v3 供全部前后场次，v1/v2保留。尚无正式性能数据需要丢弃。
- frozen-v3 已通过无API/正常API/调用失败三种探针验证并冻结；旧组的队列观测为 run-level NA，不当作队列为0。
- 旧JAR独立前测已开始：Forge `forge-before-early1b-before` PASS，NeoForge 旧组进行中；不依赖当前修复源码、不运行构建，后续各版仍按 before→after→after→before 补齐对照。
- 两版首场旧JAR前测均已 PASS（Forge `forge-before-early1b-before`、NeoForge `neo-before-early1-before`），共同 frozen-v3。首个Forge相对agent路径导致的启动失败单独保留为 invalid，成功场已用绝对路径，不混入性能数据。
- R2–R5 作者仍在同一窗口完成兼容性回归与fixture：官方B真实构建版本、sleep→ordinary FULL/DELTA、同hash FULL、实际关闭chunk引擎。尚未正式重新交付，T4 不构建当前中间状态。
- R2–R5 修复生产/测试源码现已正式稳定：SnapshotPacketAdmissionTest与扩展ColumnSnapshotFenceTest；pullorder/legacy先完成sleep恢复再普通FULL/DELTA，新增same/off/fail场景；B真实成功apply计数、samehash观测、direct FULL收口及15秒内资源归零门槛。T5复审已触发，T4可并行集中检查/隔离运行；正式after性能仍等待审查和正确性验收通过。
- R2–R5源码复审已通过，无剩余确认源码发现；但 `pullorderlegacy_1201_forge_r2r5` 在排序前的 `pullOrder.officialLanded>=1` 失败（实际0），STALL ready/pending/gen=0、pendingAuth=106。证据 `pullorderlegacy_1201_forge-r2r5-invalid/`，不计有效负对照。已再次正式触发作者区分fixture真实B触发条件、版本或光照/权威交付停滞的因果定位；不可直接删断言。T4已结束该窗口并暂停，等待稳定修复。
- 已因果定位该B前置假设错误：日志A有drain applied13/14，自然network Pull不必经vanilla holder的B回调。作者将保留真实B落地门槛，并以严格gated显式ShadowOfficialPacketBridge(actual injected chunk)取得覆盖，不删断言；T1作者已复用启动，只读判断pendingAuth106及指定两柱门状态是否真实未收敛，不凭单计数判bug。
- T1只读诊断完成：126个登记柱中20后来clean，剩106对应95窗外缺邻与11窗内缺material；这是既有pending-authority邻域再LIGHT表，不是新确认票、不挡pushReady。指定(3,0)/(4,0)均clean且多次实际落地，无T1漏失效证据；早停轮未证明11窗内缺柱最终补齐/全引擎收敛。无算法改动，详见light-client.md诊断节。
- 严格B夹具已交付稳定：平台先铺、20秒稳定后sentinel，owner显式调用真实桥并将具体包标记到实际成功apply的officialExerciseLanded；保留owner/current/fenced/offered及队列/3×3只读诊断。T5轻量核查fixture差异、T4并行必要检查+Forge legacy重跑。同型fixture-only稳定重试由作者与T4直接协调，不必每轮等待root批准。
- 严格Forge legacy 已证明真实B因果门通过（owner/current=true、fenced=false、offered=true、exercised=1），两柱clean/full3x3、无确认阻塞，sleep后ordinary recovered=true；但随后无native-after回执/无checked marker，passes断言失败，仍不是合格排序负对照。证据 `pullorderlegacy_1201_forge-r2-no-fingerprint/`。已正式followup作者沿response→native-after→apply/check定位断点；T4执行窗口已结束暂停。不能以无完成/超时当已复现旧覆盖。
- `logo-16x10.png`、`video/` 是无关用户文件；原有未跟踪 benchmark 与报告是本会话此前采集产物，予以保留。
- 完成链首个断点已定位：legacy旁路围栏同时使同hash FULL复用，未真正应用；排序正负FULL现同用既有requestNbtRepairFull，独立same场仍repair=false。新增response/native-after/server ACK/apply/check阶段标记，并在passes前要求完整链，已唤起T5轻量复核。
- 该修订后的严格Forge legacy仍invalid：真实B/light/sleep前置通过，但responses=0，server无capture/native-before。T4保存证据并核实触发点，T7作者已正式followup继续定位完整场景触发/等待；作者与T4直接协调稳定重试，根不接管具体执行。超时或缺完成仍不能计为旧覆盖复现。
- 此轮因果定位完成：sentinel在固定20秒等待后发出时，服务端sleep恢复尚未结束，更新被正确吸收/抑制而不保证触发单包probe。已改严格gated事件驱动PULL_ORDER原语：b等受控B实际落地、ready等两柱真实COLUMN_READY/publish、request在Render调用既有API并arm、chain等最终异步verified才断言；不改生产排序。新增PullOrderScenarioContractTest读取实际资源及缺request/ready负例；五场静态顺序自查通过。源码重新冻结，作者唤起T4重试，T5补审专用原语。
- T5事件原语复核通过；T4两锚点契约测试/编译/边界通过。运行readyrequest1的真实B stage通过，但sleep后ready等待90秒失败、尚未发排序请求，received/after/applied/verified/recovered均空；仍invalid。作者获释放窗口，必须区分真实恢复/publish不收敛与fixture漏观察，不能删除真实ready门；同时补实际block/BE值诊断，避免仅false指纹。作者负责可运行负/正闭环，与T4自主协调。
- T5独立因果核查与作者均确认R6生产缺陷：ShadowPullClient UNCHANGED的stillCurrent漏改，仍将wire requestId传要求opaque version的acceptPending，阻断未变柱完成/ACK/ready。目标3/4在VD8真实恢复域且5×5保留域外，判据可达；日志未开逐请求诊断，不能证明本场每个结果都是UNCHANGED，但该生产缺陷独立成立。作者同窗口修复并审计同类调用、补sleep UNCHANGED回归；不能删除ready门绕过。具体失败保存在pullorderlegacy_1201_forge_ready-timeout/。
- R6修复已稳定：acceptResponse后捕获incomingToken并贯穿owner/client；其余acceptPending两个caller已使用opaque。新增ShadowPullUnchangedTokenTest含实际AsyncSnapshotCommit负/正、替换/断连释放和receiver边界契约。另补真实恢复状态、generation匹配publish事件观察及具体visible/owner block/BE指纹；ready门不降。T5已正式唤起复审，T4恢复受影响集中检查，游戏等复审通过。前述仅归固定等待/夹具的解释不完整，ready90s暴露真实生产提交缺陷。
- R6独立复核、两版聚焦suite/编译/边界均通过。Forge strict legacy取得首个有效FULL旧覆盖负例：wire495→opaque2114、owner commit/ACK/ready均完成，request507 capture/native-before/ACK/response/native-after/ACK/envelope/apply/check链完整；expected diamond_block但visible+shadow均stone（非缺柱），BE visible空、shadow=tail-after-3。证据pullorderlegacy_1201_forge_r6-full-old-overwrite/，JSON SHA89A633F7B391E68308122826334CE7FA35AF73254DD3C67B2A8B7DAE0A8FBAB6。FAIL为具体旧覆盖负指纹；DELTA因FULL fail-fast未运行，不计验证。T4继续稳定源正例FULL+DELTA/兼容/双版回归，不为补负DELTA单独改源；如正例发现新问题再修。
- Forge正例pullorder_1201_forge_r6opaque1-pass/已通过：FULL request515/DELTA564两柱完整链，visible+shadow均diamond及tail-after-3/4，passes2/failures0，B真实落地。Neo有效FULL负例也已取得（pullorderlegacy_1211_neo_r6-full-old-overwrite/），DELTA负场未执行。
- Neo正例暴露新真实stale block问题：pullorder_1211_neo_r6-failed-stale-block/中FULL request487完整链、server tail4，visible+shadow却stone而非diamond，BE两端均tail-after-3；不是缺回调。T4暂停所有后续场景/after性能并释放源码；作者正式唤起修复，T5独立定位native block/section捕获/回放/版本差异。不能改期望值或降门掩盖。共用生产修复后需补Forge正例，原before继续有效。
- R7因果已确认：NeoForgeNetworkManager.sendServerPayload无条件server.execute；worker结果本已在owner任务中，MC的runningTask令再次execute延后，而调用方立刻发送native tail，导致新block先于旧FULL。BE-after等待FULL实收才产生，故BE仍正确。作者修复loader发送边界为owner内inline/非owner调度，审计同类发送/重入路径并补顺序回归；T5待稳定正式复审。不得通过非owner直接发送绕世界所有权。
- R7已稳定、独立复审及两版受影响检查通过，真实ReentrantBlockableEventLoop回归覆盖owner重入。Neo复验pullorder_1211_neo_r7-partial-fail-delta/中FULL已正确；DELTA request540完整链tail4后visible=diamond但shadow=gold，BE两端tail-after-4，passes1/failures1。T4再次停后续释放源码，作者和T5已正式唤起聚焦DELTA owner提交/尾部镜像顺序；不得以可见正确掩盖影子陈旧。最终双版正例与其余回归/性能仍待通过。
- 合并因果核查确认两处独立缺陷：native三handler HEAD在ensureThread前，Netty intercept返回false后仍mirror，先写shadow而后Render才受fence；DELTA消费线程token前检到锁内commit有越代窗口，成功后busy把原work回队会重复apply并可能put盖新work。尚无证据宣称其中任一独自解释本场gold。作者获准同窗口最小修复：mirror仅Render，ShadowPoiGate调度内token复核/锁/一次内容commit，busy只retry light且不覆盖新work，严格gated逐阶段实际状态诊断。T1作者已唤起只读核对接口/锁序，不改T1确认票或光照门算法；整体稳定再审/测。
- R8合并修复已稳定；T1边界已落实strict owner检查，callExclusive拒绝/超时inline不写内容，原有CAS防迟到任务重复执行，owner不可用原有界work冷却retry。新增SnapshotContentCommitTest真实owner/异步commit/fence回归，DELTA/GATE保留实例/token/baseline。T4两版完整受影响suite/编译/边界通过，T5源码复审通过可放游戏；开始优先Neo原失败FULL+DELTA正例。源码证明不能代替实际双端值正确。
- R8 Neo正例pullorder_1211_neo_r8commit1-pass/已通过FULL+DELTA完整链：visible/shadow均diamond及tail-after，诊断唯一owner content commit后才mirror，pending快照为0（maxWait年龄含pipeline busy，不冒认漏确认）。随后最终Forge正例pullorder_1201_forge_r8commit1-fail-shadow-mismatch/失败：FULL complete后visible diamond/BE正确，shadow stone/BE空，日志mirror曾正确但chunk identity从1882485538换至515880409。T4已停全部后续回归/after释放源码，作者与T5正式唤起定位同柱换代/晚FULL提交/围栏完成时机，保持真实检查对象和门；Neo通过不能代替Forge最终验收。
- 上一条Forge“生产失败/换对象覆写”只是初步候选，已被作者与T5独立日志因果推翻：Probe.completed只匹配x，(3,-2)于7075触发7076错误col3完成；真正(3,0)7082后才apply/tail，owner检查早取stone、Render迟打印，当前515880409在真实mirror后始终diamond。此场应为invalid fixture错关联，不能计新生产缺陷。作者修精确key/维度/代际/request关联与邻柱负例，保留当前shadow真实检查；T4须完成实际归档并重跑Forge正例。
- T5有界追溯：旧Forge legacy同样(3,-2)充数，撤严格负例；Neo R8positive的(3,1)/(4,-1)分别提前completed，实际目标最终diamond事实可保留，但撤严格PASS。Neo legacy可证目标(3,0)的旧stone事实，但旧completion未带完整request/token，最终也统一重跑。作者补精确fullkey/维度/generation/request/opaque关联及邻柱/旧轮负例后，T4获准双版strict legacy+positive共4场统一重验；先前历史记录仅事实/因果材料，不作为最终完整验收。两场frozen-v3旧性能before不受影响仍有效。
- R9已source-stable并通过T5最终有界复核、两版25类受影响suite/编译/边界：fullkey/level/connection/generation/wire→opaque完整scope、owner读与Render回调双复核；normal真finishDrain、legacy真实REMOTE_PULL/SECTION_DELTA Ready落地后observer，通用Sleep完成observer移除。legacy cancel仅淘汰旧idle凭据，仍inactive/nohold/noforceReplace，正负同repair；actual injectedChunk在owner现读。已正式放行T4四场统一严格重验；不能追认旧污染场。
- R9统一四场完成：双版pullorderlegacy_*_r9scope1-valid-negative/均exact目标scope后stone/stone覆盖expecteddiamond；runner FAIL来自正向passes断言，实验为有效预期负例，关闭日志在fail-fast后。双版pullorder_*_r9scope1-pass/均FULLcol3/DELTAcol4完整request/token/owner-check，双端diamond及tail-after，passes2/failures0、runner PASS。T5已唤起有界独立核证；T4继续same/off/fail、sleep/dimension/移动光照及其他必要编译锚点，随后剩余6场v3性能。负DELTA仍未执行；legacy是同新Jar旁路围栏，不是未改旧Jar。
- T5独立核证R9四场严格通过，44清单文件hash匹配；两个negative的Connection reset均在有效检查后的fail-fast退出才发生。Neo samehash语义已正确（matches=true/repair=false/replace=true、exact scope数据双端正确、passes1fail0），但pullordersame_1211_neo_r9scope1-fail-inflight/的全局wrapper.activeCount=6触发资源断言。作者与T5已正式唤起核具体key/状态/年龄，区分自然邻柱在途与真实孤儿，不能直接删门或把global计数等于目标泄漏。T4暂停剩余compat并释放源码；稳定资源观测/必要修复后再继续。核心四场不因仅fixture资源等待修订自动失效。
- R10资源修订已准备：15s硬界内global/target/bytes0稳250ms、active明细<=128/release原因<=512及不截断计数，SAME自B基线后任何repair/expire均FAIL防超时清理假收敛。目标旧租约已释放，原global6采样时邻柱仍在处理，原场不追认完整PASS。T5复审并行；另作者只读发现halo DELTA pre-LIGHT可合法ACK retire而R8新acceptPending或拒后续LIGHT，T1与T5已正式唤起给阶段接口严格结论，避免未定边界下半稳构建。尚未动光照算法/门。
- R11由T1/T5独立确认真实可达：haloApplied→startDrain立即draining使acceptPending=false，无需等ACK；busy/GATE等邻柱的已提交DELTA后续LIGHT会被丢，正常ACK换opaque/sleepversion后更无法恢复。需先修业务阶段凭据再稳定构建：只有已committed内容LIGHT可跨正常drain/ACK，以completedVersion/ACK前内容opaque、当前server/gen/injectedBaseline守卫；fresh内容仍acceptPending，新begin/cancel/unload/disconnect/clear即失效。保持全部T1邻柱门/确认算法/ready可见性。作者获准最小修+normal/sleep halo/busy/GATE/同柱新请求拒旧回归；T4等待整体稳定，最终正例/移动光照受影响需用新构建，旧精确FULL负指纹不自动失效。
- R10+R11整体已稳定，T1接口/T5源码最终复核均通过：generic正常finish保存completedVersion，sleep真实advanceAfterTail保留lightVersion，其余失效清除；字段仅既有有界primitive，无新队列引用。新增DeltaLightPhaseTest/StableQuiescenceTest等，真实MC owner/Sleep转换但INIT/LIGHT为模型，不等同真引擎验收。作者正式唤起T4集中checks，先Neo same有限归零/无repair，再compat、最终双版positive/移动LIGHT（gated admitted→engine-complete证据）、其他必要编译锚点与剩余性能。源已整体冻结。
- 初次R11 compileTest因Manifold将测试嵌套类名$Column插值而失败，作者最小改为declaredClasses/simpleName查找；失败日志保留，生产未变。修后双版聚焦七类+完整受影响suite/loader编译/边界均PASS。
- Neo same最终新场PASS：samehash FULL matches=true/repair=false/replace=true，exact目标双端stone及tail-after正确。资源首样target0/global7/4,587,520bytes，明细为背景柱；2,985ms后target/global/bytes均0稳250ms，repair/expiredRepair均0、15s内完成。原瞬时global6不构成目标泄漏证据，新场证明有限自然收敛；T4继续off/fail及其余最终回归，源冻结。
- 最终R11 off与fail四场双版均PASS：实际engine-disabled active=1，分别failed=false/true，均nativeFulls=1、精确native-after/apply完成，target/global/bytes0稳定；repair=1/expired=0，两次资源复查均有限完成，无30s循环。证据pullorderoff_*_r11stable1-pass/与pullorderfail_*_r11stable1-pass/。T4补Forge R11 same完整quiescent，再最终双版positive和移动光照；原R9 Forge same只保留语义事实。
- R11最终same双版PASS（Forge277ms、Neo2985ms收敛），因此same/off/fail六场语义及资源全通过；真实halo token4471/5047等有同key/token/对象admitted→engine-complete且pending=false/lightCurrent=true，不只模型。
- 新R11 Forge sleepresume真实恢复失败：L24 turn+using timeout，stateIDLE pending1 sample[-6,0]，client04:49:10。证据build/traffic-profile/sleepresume_1201_forge_r11stable1/已保8类材料/SHA256SUMS，result SHA9353B56BEF6F44027027C919ACE66324289BB61DE8642E47582149E7DB2423A1。wrapper已恢复配置/字典/cache并退出，egress错误仅failfast后派生。T4暂停其余运行，作者与T5已正式唤起定位目标柱capture/commit/ACK/ready/phase/邻柱，不降低完整恢复门。尚不能以stateIDLE判完成。
- sleepresume使用Gradle dev classpath，既有package JAR仍旧hash，不能归为已测二进制。T4已要求保实际classes/resources及source指纹；正式after前必须实际打包/重映射并校验两端mod JAR与最终artifact新hash，旧before副本不覆盖。
- 目标(-6,0)睡前LIGHT/落地齐，wake后无该柱新ingress/LIGHT/ACK，8邻均成功恢复；不支持T1缺邻堵门。作者/T5确认可达enter发布竞态：SLEEPING已生效时Terrain先sleeping=false逐列发布，shadow可reserve30s retry，而实际Render请求被5×5睡眠过滤；4s后wake仍冷却，10s恢复门超时。原日志无retryAt，不能声称唯一运行根因已直接捕获。作者最小修原子发布/refresh实际发送及旧record拒绝，补确定性交错；若用lifecycle monitor，T5须沿实际send调用核无间接owner等待/chunkLock/死锁。不加超时、不改5×5或光照门。整体稳定再双版实际sleepresume。
- R12首稳定候选两阶段permit已避开锁内hash/IO/chunkLock，但T5拦下旧失败按key取消的check-then-act会误撤replacement、平台void sender无连接/吞异常仍扣冷却两项；作者同窗口最小修token原子discard与可观察的发送接受结果，未要求同步远端ACK。候选未放运行，保持全部门与原失败证据。
- 为减少root上下文与重复批准，作者/审查/T4后续按现授权直接衔接同范围必要生产/fixture修复：stable后followup_task唤审查，明确通过后followup_task唤T4唯一checks/game；T4失败安全收尾后直接唤作者/审查。仅send_message不会唤idle，必须用followup_task。root接实质阶段结果/新风险/范围外决策并最终验收；T1光照算法设计变更仍协调，禁止递归新建代理，不降低既定门。
- R12两项阻塞均最小修复并独立通过；首轮测试因MC Bootstrap/诊断先触发Services准备缺失失败，修准备与纯属性诊断短路后两锚点聚焦+完整受影响suite通过，Forge1.20.1/Neo1.21.1及Fabric两版编译、scan均PASS。失败日志保留，不追认。
- 最终R12真实sleepresume双版通过：traffic-profile/sleepresume_{1201_forge,1211_neo}_r12stable1/，原canonical字典，双round PASS、无gate/audit失败、已保hash。旧目标(-6,0)实录installed→wake→reserved→wire-sent→response→UNCHANGED-ownercommit→injected→ACK→COLUMN_READY，含完整版本和retryMs；该场无halo phase marker，不能拿它替代R11 halo真实LIGHT证据。T4将因共享发送准入变更补最终双版FULL/DELTA正例、继续换维/移动光照/必要其他锚点，之后最终JAR和6场剩余性能；不无因重跑已验证legacy负例。
- 最终R12双版positive已PASS：FULL/DELTA各自exact scope/ownercheck，双端diamond与tail-after，passes2fail0、target/global/bytes0；各自dev classes/resources指纹及完整证据已归档。Neo有正常tail后halo admitted→engine-complete实证，Forge本场LIGHT先于tail故0marker，非失败也不补算；作者已提出严格gated单halo非阻塞延后补测方案，未改源，T4安排源码窗口。
- R12 dimension双版PASS：场景4段OW→Nether→End→OW与退出落盘门均过，无Dimension/Probe/Audit失败。generic Round2Pass=false对应故意gate=false的中间Nether轮，实际Result=PASS/Analysis.pass=true，不能误读。T4开始两版串行ovdlight往返屋檐/暗洞/光源导出严格验收，源继续冻结；之后仍有Forge halo定向补测/其他编译锚点/最终打包与6场性能。
- R12 ovdlight双版运行及严格导出均PASS：eaves sky6/cave sky0/block source10、每轮movement640/641，零leak/dark/missing/mismatch（完整样本/门见各export）。Forge仍未取得halo正常tail/ACK后LIGHT顺序证明，0marker不能补算覆盖。T4已获准在归档退出后直接唤作者fixture-only窗口：strictgated单一真实committed halo DELTA通过原pendingDeltas冷却非阻塞延后，实际tail/ACK后同身份/基线release，15s硬界与资源/repair0门；不改光照门/epoch/内容。review后只补Forge定向证明及必要检查，常规光照PASS保留。T5并行核已完成compat/恢复/positive/dimension证据矩阵，最终perf仍未开始。
- T5完成12场指定运行证据核证，164清单文件hash一致；逐场dev身份接受，跨场同classes/JAR未经证明，恢复后原路径逐字节hash未留的限制保留，不无因撤回实际门。R13 fixture首审旧future缺current baseline复核已修并通过复审/双版聚焦与主锚点编译/scan。
- Forge R13 pullorderhalo_1201_forge_r13stable1/最终PASS：key4503599358935031/token4201/server999498769/gen1/baseline1559258994/level1379373724/conn1443367938精确一致，真实committed/deferred→tail-finished（ACK/done/publish）→released→admitted→engine-complete约2s<15s，haloPhases1；FULL/DELTA双端diamond及BE正确，target/global/bytes0稳250ms，repair/expire0，wrapper配置/字典/cache恢复hash均match。T5仅补核ovd双场+此halo三场；T4继续其他必要编译锚点/实际最终jar与SHA/六场剩余v3性能，首after前通知冻结窗口，届时暂停源/测试/fixture/harness及不必要docs写入/重计算。
- T5最后ovd双场+Forge halo三场证据核证通过，83项归档SHA一致，指定正确性runtime缺口关闭。T4完成其他必要编译锚点及实际打包，进入冻结v3六场剩余性能；其他代理/根暂停源与docs写入和重计算。最终artifact前缀Forge B2D53B47…4ECBF、Neo BF5AB94B…3D62F。
- 八场正式v3性能矩阵已全部运行PASS（各版old-before→final-after×2→old-before），六场剩余任务完成；当前冻结窗口关闭、无活动构建/游戏。T4正在提取数值、检查8场inputs/NA与新APItelemetry语义并写validation.md。尚未接受性能提升结论，T5最终artifact/指标复核与T6结果整合待完成；goal保持active，heartbeat仍ACTIVE，实际最终验收后再complete/停用。
- 最终集成已完成：[总报告](spark-optimization-results-20261023.md)、[候选处置](spark-optimization-20261023/disposition.md)、[验证证据](spark-optimization-20261023/validation.md)、[独立审查](spark-optimization-20261023/review.md)及[有界性能处置](spark-optimization-20261023/perf-disposition.md)均已交付接受。validation最终SHA84F9224D5EC0148C77013ACB32402A77BDA7167A97918FAB0640E38EE3CD925E；424项性能清单核验由T4执行，独立核身份/计算/唯一项及关键正确性归档，未冒称重复全量hash。
- 实测结论为loop/frame均值与P99下降、CPU未全面下降；Forge CPU+0.71/+0.93pp、Forge FULL GATE与Neo SERVER_QUEUE两after上升、Neo末票及单臂长尾均保留。最后有界现有profile核查无可证新增Pull/hash/快照必修项，不以采样偏移盲改实体计数语义。源码/JAR保持最终状态；本轮规定验收无待办，下一步仅更新goal complete并停用heartbeat，不启动新研究或重复构建。
- 关闭已确认：goal状态complete；heartbeat `hassium` 已通过automation工具更新为PAUSED。工具记录本轮约5小时34分。无活动构建/游戏，全部代理交付已接受；未提交代码或对外发布。后续不自动重复已完成任务。
