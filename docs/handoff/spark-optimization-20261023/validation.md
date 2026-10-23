# Spark 优化最终验证（2026-10-23）

最终源状态：2.0.0，Git commit edc4fd7213b10117aecd4e6b992a9fa2932d2757，工作树含本次优化的未提交改动。validation 代理独占运行 Gradle/游戏；没有提交代码，也没有上传 profile。实际性能与正确性数据、逐场 manifest、运行日志、profile、原始控制 CSV、场景采样和 SHA 清单保存在 build/analysis/spark-optimization-20261023/。

## 结论

要求的功能、正确性、移动光照、构建锚点和最终打包检查均通过。严格 FULL legacy 负对照在 Forge 与 NeoForge 均证明旧快照覆盖新 block；FULL+DELTA 正例及 sleep、dimension、same-hash、关闭、影子引擎失败、移动屋檐/洞穴和 Forge 尾部之后继续 LIGHT 的专用场景通过。最终 remap JAR 已在两个 loader 的性能实跑中使用。

冻结 v3 的每版 ABBA 四臂测量完整：原版 Hassium JAR、最终 JAR 两场、原版 JAR 复测；全部 runner 结果为 PASS。服务端主线程 P99 样本和客户端循环 P99 在 after 两场都没有出现跨两次重复一致的退化，movement_load 实测实体数据与移动包总量维持约 10.2k 包/秒，客户端加载/渲染柱数接近基线。具体窗口中有噪声与一项待解释的尾延迟，见“性能观察与限制”；不把结果概括成零延迟或 CPU 采样占比的损失估计。

## 检查和构建

### 测试与扫描

最终 R12/R13 源状态使用 Gradle daemon，未传 --no-daemon。两锚点聚焦及相关回归套件通过；日志保存在 gradle-r11-*, gradle-r12-*, gradle-r13-*。最终新增/改动覆盖包括：LightConfirmationQueueTest、DeltaLightPhaseTest、StableQuiescenceTest、HaloLightProbeTest、PullOrderScenarioContractTest、PullOrderProbeScopeTest、SnapshotContentCommitTest、ColumnSnapshotFenceTest、OwnerThreadDispatchTest、SnapshotPacketAdmissionTest、ShadowPullUnchangedTokenTest、SleepRecoveryAdmissionTest、PreparedClientSendTest、PullReceiveWindowTest、SectionHashCacheTest、PullDeltaAdmissionTest、EntityPacingConfigCacheTest，以及原有 chunk hash、section delta、NBT、ShadowPull session/budget、light timing、shape/OVD、sleep 测试。

R12 两个 anchor 的 common:test fullfocused 均通过；R12 sleep 入口 bootstrap 的首次 test-discovery/初始化失败有原始日志保留，修正测试初始化后重跑通过。R13 改动由专项测试及两重点 loader 编译覆盖，未声称 R13 后重跑了全套 fullfocused。不存在把早期无效 fixture/旧测试启动问题追认为产品 PASS 的处理。

scanVersionBoundaries 最终 R13 扫描通过。版本锚点采用 Hassium Manifold 七段：

| 锚点 | 最终 compile 覆盖 | 证据 |
|---|---|---|
| 1.20.1 | Forge；Fabric（R12 SPI 边界） | gradle-r13-forge-compile-1.20.1.log、gradle-r12-fabric-compile-1.20.1.log |
| 1.21.1 | NeoForge；Fabric（R12 SPI 边界） | gradle-r13-neoforge-compile-1.21.1.log、gradle-r12-fabric-compile-1.21.1.log |
| 1.21.2 | NeoForge | gradle-anchor-neoforge-1.21.2.log |
| 1.21.5 | NeoForge | gradle-anchor-neoforge-1.21.5.log |
| 1.21.6 | NeoForge | gradle-anchor-neoforge-1.21.6.log |
| 1.21.9 | NeoForge | gradle-anchor-neoforge-1.21.9.log |
| 1.21.11 | NeoForge；Fabric | gradle-anchor-neoforge-1.21.11.log、gradle-anchor-fabric-1.21.11.log |

1.21.2 没有 Forge userdev；1.21.11 已转 NeoForge。额外锚点选择覆盖了本轮 common/compat 分段与三端 service 边界，未将扫描通过当成编译证据，也没有对同一段无差别全 loader build。

### 最终制品

使用 forge:remapJar forge:verifyHassiumNativeLibPackaging（-Pmc_ver=1.20.1）和 neoforge:remapJar neoforge:verifyHassiumNativeLibPackaging（-Pmc_ver=1.21.1）构建。两个 native packaging verifier 均通过，确认 zstd-jni 1.5.7-7 打包正常。NeoForge remap 有一条非阻断 warning：Cannot remap clearLevel because it does not exist in any of the targets [net/minecraft/client/Minecraft]；构建仍成功，原日志保留，未隐藏。

| 目标 | 最终文件 | SHA-256 | 字节 |
|---|---|---|---:|
| Forge 1.20.1 | forge/build/1_20_1/libs/hassium-forge-2.1.3-1.20.1.jar | B2D53B47BE1BB474A1B0ABD8184863474627BAE5D015DA61822CBDBB4FAE4CBF | 9,982,156 |
| NeoForge 1.21.1 | neoforge/build/1_21_1/libs/hassium-neoforge-2.1.3-1.21.1.jar | BF5AB94B76BB60E03831319F777CA02868C51D7C0391F84D70F05902CA93D62F | 9,292,257 |

性能测量另从这些文件复制到只读用途的 build/analysis/spark-optimization-20261023/perf/frozen-after/，SHA 与上表一致。保存的 before JAR 不曾覆盖：Forge SHA 20F0526FCA75C972FB6EE68E15F2B1AAD1E5CB6FA3C97FBB8B0E9DC7ED9DF12A；NeoForge SHA 30F76029D7CEC8C2FA6A8622FB5CEC9E1A1093ACB52BC1BB17EEBDE051C60626。每个性能臂 inputs.json 都记录 client/server 实际 mods SHA，运行中的两个副本与该臂指定 JAR 相同。

## 正确性与游戏运行

各场结果是逐场 dev 输出运行；只有最终性能臂运行的是上表的 remap JAR。R12 正确性场景不能被表述成全部使用同一个打包 JAR。review 与逐场结果/manifest 在 docs/handoff/spark-optimization-20261023/review.md、server-pull.md、light-client.md 和对应 build/analysis/.../<session>/ 归档内。

| 场景 | Forge 1.20.1 | NeoForge 1.21.1 | 验收范围 |
|---|---|---|---|
| FULL legacy 负例 | pullorderlegacy_1201_forge_r9scope1-valid-negative | pullorderlegacy_1211_neo_r9scope1-valid-negative | 使用同一新 dev 构建并在 strict-gated 场景旁路 fence，不是旧 JAR 实测。同一完整 key/request/token/身份链真实完成；具体新值 DIAMOND 被旧 FULL 覆盖为 STONE。legacy runner 因预期 probe 断言失败，单独按有因果指纹的 FULL 负对照判定有效。此前缺包/缺 completion/邻柱串 key 的 FAIL 都保留为 invalid。DELTA legacy 负对照未运行，不据此声称 DELTA 会同样失败。 |
| FULL+DELTA 正序 | pullorder_1201_forge_r12stable1-pass | pullorder_1211_neo_r12stable1-pass | 两柱实际 visible/shadow DIAMOND、BE=tail-after-*；尾部/ACK/apply/check 完整链。 |
| same-hash / 不修 NBT | pullordersame_1201_forge_r11stable1-pass | pullordersame_1211_neo_r11stable1-pass | matches=true, nbtRepair=false, replace=true；最终正确旧内容及 BE。目标/global/reservedBytes 归零并连续稳定 250ms、15 秒内完成，repair/expire 为 0。 |
| 运行时关闭 chunk | pullorderoff_1201_forge_r11stable1-pass | pullorderoff_1211_neo_r11stable1-pass | 实际 native FULL fallback 可见、目标资源归零；必要 repair=1、expired=0。 |
| 客户端影子引擎失败 | pullorderfail_1201_forge_r11stable1-pass | pullorderfail_1211_neo_r11stable1-pass | 实际 native fallback 可见、目标资源归零；必要 repair=1、expired=0。 |
| sleep/resume | sleepresume_1201_forge_r12stable1 | sleepresume_1211_neo_r12stable1 | 10 秒硬门不放宽；目标 (-6,0) 的 installed/reserved/wire/response/UNCHANGED owner admission/ACK/control 过程均被记录并通过。 |
| dimension | dimension_1201_forge_r12stable1-pass | dimension_1211_neo_r12stable1-pass | 实际 OW→Nether→End→OW 四次 gate/arrival。该场的 Round2Pass=false 是维度场景模式，不代表普通双轮 stats 通过，也不改变整场 smoke PASS。 |
| 移动屋檐/洞口光照 | ovdlight_1201_forge_r12stable1-pass | ovdlight_1211_neo_r12stable1-pass | 每轮 471 样本，屋檐 148、洞穴 192；client/shadow sky/block mismatch、漏光/暗源/缺地面均为 0；连续和移动样本通过。移动有效样本分别 641/641 与 640/641。 |
| FULL/DELTA 后由尾部继续 LIGHT | pullorderhalo_1201_forge_r13stable1 | N/A | Forge 专用 strict-gated 场：同一完整身份完成 content commit→延后 LIGHT→真实尾部/ACK/publish→释放→owner admission→engine-complete，约 2 秒，15 秒硬门内；haloPhases=1，稳定资源全零 250ms，repair/expire=0。没有旁路 3×3 门。Neo 同类自然尾部正例是 pullordersame_1211_neo_r11stable1-pass，token 4471 对应柱 (-6,-9)，token 5047 对应柱 (-2,-10)；不是 R12 场。 |

移动光照抽样具体指标、严格 halo 的逐行 marker/完整 identity、R9/R11/R12/R13 的阶段取舍及被撤回旧 fixture 说明都在 review/handoff 与原始 log 中。审查确认归档 83 个关键文件 SHA 一致。sleep/resume wrapper 保存了隔离与恢复流程凭据，但未独立保存恢复后原路径的逐字节 hash；不对 sleep 场宣称该项验证。R13 halo wrapper 有独立恢复后 hash，不能倒推 sleep 场也有。

## 性能矩阵（冻结 v3）

输入方法与两场初始 before 在 build/analysis/spark-optimization-20261023/preparation.json；最终 manifest 是 perf/final-inputs.json。每臂为 movement 与 movement_load 两个 60 秒 measured phase，60 秒 warmup，256 实体，往返移动场景。每个 loader 的顺序为 before→after→after→before；两份 before 都使用不可变旧 Hassium JAR，两份 after 都使用同一份最终 remap JAR。首个 before 输入记录 Forge/Neo 均为 471 个 production source 文件；final source 与后续 before 记录 Forge 为 489 个、Neo 为 488 个。这是 source-tree 归档字段随实现完成而变化。每臂 inputs.json 另外逐项记录实际安装的 client/server mods JAR SHA，旧 before 的两个副本始终与指定旧 JAR SHA 相同，故比较对象以安装制品 hash 为准。世界压缩包 hash 每版内固定：Forge f722f5a016d22a818c763f44a20df34b56814626f68db25e960c32b1b7a1e7e0，Neo 0b5ed5e1e4a2ded022b887bbac7f362739436fb5d5796789405f23b6cb04f2d3。

所有八个臂都 runner PASS：

| Loader | ABBA run IDs |
|---|---|
| Forge 1.20.1 | forge-before-early1b-before → forge-final-v3-after1-after → forge-final-v3-after2-after → forge-final-v3-before2-before |
| NeoForge 1.21.1 | neo-before-early1-before → neo-final-v3-after1-after → neo-final-v3-after2-after → neo-final-v3-before2-before |

同一冻结 instrumentation v3 用于八臂：agent 5522A4EBB3E6E97A1339E788FB51A73FFDD913104D3FB09F0D6B3AEA065C3E03，bridge 8EC73CAC3331B925087789EF06DF2AEBF1427228AF20576C4E20746B60FF828B，ASM 8cadd43ac5eb6d09de05faecca38b917a040bb9139c7edeb4cc81c740b713281。旧 JAR 没有 lightConfirmationSnapshot：每场只产生一次 capability probe 的 NA 行，不循环打异常；NA 不代表队列为零。After 两场每 phase 有 61 个快照、0 次反射/API 调用错误。

### CPU、主循环/渲染 P99 与包量

下表 CPU 为两次对应臂的算术平均；P99 为每臂记录的 loop-duration P99 再取两次平均，单位 ms。它们是 harness 控制循环分布，不是 Spark 火焰图占比。server/client main CPU 是各自主线程 CPU/墙钟；客户端各臂约 97–98%，Spark sample 占比不被换算成“损失”。

| Loader / phase | Server main CPU before→after | Client main CPU before→after | Server loop P99 before→after | Client loop P99 before→after |
|---|---:|---:|---:|
| Forge movement | 5.77% → 6.49% | 97.15% → 97.90% | 10.60 → 8.95 | 2.69 → 1.58 |
| Forge movement_load | 14.29% → 15.22% | 97.12% → 97.55% | 14.28 → 13.77 | 3.33 → 2.17 |
| Neo movement | 6.28% → 6.25% | 97.54% → 97.63% | 8.85 → 7.35 | 2.68 → 1.58 |
| Neo movement_load | 13.68% → 13.99% | 97.48% → 97.44% | 13.99 → 13.15 | 3.23 → 2.06 |

movement_load 客户端实收实体数据+移动包量：Forge before 约 10,247/s、after 约 10,244/s；Neo before 约 10,277/s、after 约 10,279/s。数据包与移动包分别由 harness 计数，数量维持在约 5.12k/s 每类。客户端控制样本 encodedBytes movement_load 为 Forge before 1.39 MiB、after 1.36 MiB；Neo before 两臂均值 1.43 MiB（1.44998/1.40508 MiB）、after 1.40 MiB。服务端 encodedBytes 在 Forge 两个 before 为 1.93/3.31 MiB、after 为 3.35/0.66 MiB，随区块发送量明显波动；Neo before 为 0.83/0.87 MiB、after 为 0.94/1.01 MiB。保留这些绝对值，不把波动解释成节省或丢包。

### 区块覆盖、等待和异步队列

场景 CSV 每秒采样 61 次，移动 x 约 0→225。下列为 client loaded_chunks 与 renderer C: 数的中位数范围，按两臂汇总，不等同于全世界覆盖：

| Loader | Phase | Loaded median before→after | Renderer median before→after |
|---|---|---:|---:|
| Forge | movement | 311–312 → 311 | 198–199 → 196–198 |
| Forge | movement_load | 312 → 312 | 208–209 → 207–208 |
| NeoForge | movement | 311–313 → 312 | 201–216 → 211–213 |
| NeoForge | movement_load | 312–313 → 313 | 208–223 → 216 |

所有臂都有 client movement / movement_load scene CSV、双方控制 CSV 和 chunk pipeline profile。server queue p95/p99（movement+load 生命周期内的累计 stage profile）：Forge before 130–159/319 ms，after 143/268–302 ms；Neo before 117–130/285–369 ms，after 168–185/285–302 ms。另有两个具体 stage 在两次 after 都高于两次 before：

| Loader / stage（source） | before arm 1 / 2 p95 | after arm 1 / 2 p95 |
|---|---:|---:|
| Forge FULL GATE_WAIT（client FULL） | 1.141 / 1.208 s | 1.745 / 2.147 s |
| Neo SERVER_QUEUE（server） | 130 / 117 ms | 185 / 168 ms |

这些是累计 profile 分布，窗口覆盖 warmup 和测量期，不能解释成单个 60 秒 phase 的等待；两次 after 均高于两次 before 的方向仍应保留为待归因的阶段上升，现有样本不足以确认原因或判定其是否构成用户可见退化。client FULL/DELTA REQUEST_RESPONSE、INGEST_WAIT、GATE_WAIT、READY_WAIT、LIGHT_WALL、FIRST_MESH 的完整样本分布在各臂 summary.json 与 client-control/*.csv。其中 Forge after FULL request-response p95 226–252 ms、DELTA 193–220 ms；Neo after FULL 302 ms，DELTA 两臂为 302 ms 与 1.526 s。Neo after2 这条 DELTA 尾部样本数 38；after1 为 302 ms，而旧 before2 的 DELTA p99 也达到 1.50 s。它是单场尾延迟，不足以证明系统性 after 回归，但保留为限制。GATE/INGEST 等待亦有多秒 p95 长尾。profile 是 warmup 与测量共同累计，不能将其当单独 60 秒阶段延迟。

每帧/每循环平均值来自同一 frozen-v3 八臂控制数据。server loop mean 与 client frame mean 是每次被插桩循环的 `duration_ns` 平均值；client render-thread CPU/loop 是整段 `threadCpuNs / count`，count 为结束循环采样数，包含循环边界的 harness 工作，作为平均线程 CPU 成本估计，不是 GPU 时间。before/after 分别对两臂等权平均：

| Loader / phase | Server loop mean ms before→after | Client frame mean ms before→after | Render-thread CPU ms/loop before→after |
|---|---:|---:|---:|
| Forge movement | 2.200 → 2.062 | 0.694 → 0.662 | 0.678 → 0.651 |
| Forge movement_load | 6.776 → 6.585 | 0.715 → 0.686 | 0.698 → 0.672 |
| Neo movement | 2.248 → 2.081 | 0.656 → 0.616 | 0.643 → 0.605 |
| Neo movement_load | 6.409 → 6.105 | 0.686 → 0.644 | 0.672 → 0.631 |

每臂 server loop 样本均为 1,207；client frame 样本数为 Forge before 82,458–88,263 / after 86,319–90,918，Neo before 84,368–94,807 / after 92,091–98,033。逐臂 count 与分组 mean 可由各 run 的 `summary.json` metrics 及 `client-control/{phase}.json` 复查。

light confirmation snapshot：

| Loader / after arm（movement_load 结束） | High-water | Cumulative maxWait | 末次 pending / oldest | overflow / API error |
|---|---:|---:|---:|---|
| Forge after1 | 177 | 21.57 s | 0 / 0 | false / 0 |
| Forge after2 | 178 | 21.54 s | 0 / 0 | false / 0 |
| Neo after1 | 222 | 11.71 s | 1 / 301 ms | false / 0 |
| Neo after2 | 268 | 11.73 s | 1 / 306 ms | false / 0 |

maxWait 是该连接期间记录的最大 dirty age，不是 CPU loss，也不能单独证明票据遗失。Neo movement_load 结束时两臂各有 1 个 pending，末票年龄分别为 301.1505ms 和 305.7229ms；movement 结束时 after1 有 1 个 pending、886.311ms，after2 为 0。snapshot 没有每个票据的 identity/state/reason，因此当前数据无法判定残留票据在等缺邻柱、引擎忙还是尚未推进。它不应写成已结清。after 两臂均未 overflow，错误计数为 0。正确性场景有独立的 FULL/DELTA 内容、ready/ACK、phase 和 quiescence 硬门；它们通过并不能把性能快照中的末票改成 0。

### 如何解读这些性能数

冻结 v3 中，主线程 CPU、server loop mean、client frame mean、render-thread CPU/loop 和主要 loop P99 在 after 组总体改善或变化较小，packet throughput 与客户端 loaded/rendered 柱数接近基线。CPU 主线程变化小而方向不同：Forge movement/load 分别约 +0.7/+0.9 个百分点；Neo 约 -0.03/+0.31 个百分点。与此同时，Forge FULL GATE_WAIT 与 Neo SERVER_QUEUE 的累计 stage p95 在两个 after arm 都高于两个 before arm；目前无法归因，不能用 loop P99 改善或其它来源的等待范围重叠来抵消这一信号。该结论只适用于冻结 v3 的旧 JAR vs 新最终 JAR ABBA 窗口。

仍有需要审慎保留的量：Neo after2 DELTA request-response p95 单臂 1.526 秒、Neo after movement_load 末尾各有 1 张约 0.3 秒的确认票、多秒的累计 stage wait 长尾，以及上述两个在两次 after 都上升的阶段 p95。对应旧 JAR 也有高尾延迟（Neo before2 DELTA p99 1.50 秒），但这不足以解释 FULL GATE_WAIT / SERVER_QUEUE 的双 after 上升；现有观测无法把阶段上升归因于具体工作，也不足以确认其用户可见影响。Neo 末票没有逐票状态证明已完成，仍是独立的可观测性限制。若以后要对末票作因果结论，需要暴露与票据绑定的有限状态/identity，而不是把 pending=1 计为丢失或把 NA 当 0。

原始早期“no-Hassium 对 Hassium”八臂画像属于另一 harness/比较问题，与此处冻结 v3 的旧 Hassium JAR→最终 Hassium JAR ABBA 数字分开；不拼接成“相对原版损失”。

## 归档与范围限制

- perf/final-inputs.json 记录本轮 JAR/world/tool hash、场景、ABBA 顺序及限制。
- perf/final-v3-SHA256SUMS.txt 列出 424 项性能原始文件/输入/工具/制品 SHA-256；逐臂 result.json、inputs.json、summary.json、logs、CSV、spark profiles 原样保留。
- gradle-final-*-package-*.log、所有最终锚点日志及 runtime 归档保留。R13 专用 Forge halo 归档有逐场 SHA256SUMS.txt 和 wrapper restore-verification。
- spark-report.py 首次离线解析因 frozen-v3 目录没有 SparkProfileJson.class 而退出；随后只在独立的 build/analysis/spark-optimization-20261023/report-tools/classes/ 编译该只读 profile decoder 并成功处理全部八臂。agent.jar、bridge.jar、benchmark 脚本与任何运行臂没有因此改变。首个失败解析没有改写运行原始数据。
- 已记录的产品/JAR SHA 和每场 dev-output 指纹是不同证据。不能声称 12 个历史 correctness dev 场均使用同一 classes 树；最终性能矩阵则明确记录了相同最终 JAR hash。
- 没有 Fabric runtime 场景，因为本轮重点 runtime 目标是 Forge 1.20.1 与 NeoForge 1.21.1；Fabric 通过 SPI compile 锚点和 version-boundary scan 验证。
