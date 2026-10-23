# 服务端 Pull/hash 准备优化（2026-10-23）

> 当前验收状态（R11稳定源码）：两锚点聚焦/相关测试、Forge/Neo编译与版本边界扫描已通过；双版same/off/fail六场已通过，目标/全局/字节预算有限稳定归零且无expire修复。R9精确事务核心四场证据保留；R11 sleepresume随后暴露R12初始化/刷新竞态，R12修订已独立复审、集中检查与双版实际sleepresume通过；终版positive、换维/移动光照及性能继续由validation验收。下文按实施与审查顺序保存历史状态，旧“未构建/有效”字样须结合后续更正段阅读，不能恢复已撤回场次。

## T2 实现

`ChunkAuthorityHashes` 原本已是 65,536 柱有界、跨玩家共享的逐段 hash 缓存。本轮保留共享/LRU/防御性副本/影子端拒写/关服清表，新增 `SectionHashCache` 保存 dirty section 集合。`MixinLevelChunk#setBlockState RETURN` 将发生变更的位置换算为 `getSectionIndex(y)`；同段多次变更合并，读取整柱权威 hash 时 dirty 条目一律视为 miss。`resolveSections` 仅在真实服所有者线程扫描 dirty 段，cold miss 扫全柱，尾部空气继续规范化为零长度/截短数组。Pull 和 authority notifier 共用此刷新入口。

这次没有将活动 PalettedContainer 交给 worker，也没有新增异步 hash、Future 或线程池。缓存不保留世界/玩家/section 引用。刷新计算不持缓存锁；捕获期间 clear/失效/淘汰会导致结果被拒绝，不能恢复旧缓存。失败条目保持 dirty，可在下一次请求重算。

`PullDeltaAdmission` 仅通过已经算出的 section hash 和客户端 planes 求「必然 FULL 段数下界」。当该下界已达到现有 planner 的 75% 回退阈值时，不再准备注定被丢弃的 DELTA detached sections、heightmaps 和 BE 快照；直接保留 FULL 路径。它不会预测 DELTA 成功，也不会取消 worker 的实际规划、编码大小比较或 FULL 后备。

## 社区参考与版本

XERJ `community-*` 的 Minecraft/hash/cache 关键词检索未找到贴切 corpus（首轮结果 dragonboat/containerd/dagger 与本任务不匹配，未加载）。继续复用了已加载的 Bobby corpus，实际源码检索成功。设计参考：Johni0702/bobby，revision `241f973106ee4e5552a5950d0a12433c07921c93`，`src/main/java/de/johni0702/minecraft/bobby/FakeChunk.java:27` 独立光照/NBT 保存，`:75` 后续更新与后台序列化竞争的说明；`ChunkSerializer.java:153` 将非线程安全行为返回所有者的约束。实际 LICENSE 为 LGPL-3.0，仅采用线程边界经验，没有复制实现。

另用 minecraft-dev 核对 1.20.1、1.21.1、1.21.11 的 `ClientboundLevelChunkPacketData`：FULL 构造会调用活跃 BE 的 update tag；旧两版 heightmaps 是 CompoundTag，1.21.11 是 Map。未把不同表示硬拼成新的全版本共享快照。

## 逐项处置

| 项目 | 本轮处置与依据 |
|---|---|
| comparePull 未命中整柱重算 | 已改为 dirty section 刷新。原缓存不是 per-player，未新增一层玩家缓存。 |
| hash 异步 | 不实施；仍需主线程捕获完整 detached 数据，而当前采样只有 0.153% 均值。增量失效直接减少工作，并避开异步修订/排序成本。 |
| UNCHANGED 的 NBT 纯编码 | 保持同步。update-tag 必须由所有者读取；单独异步编码会扩大 NBT-only 更新先于旧快照到达的窗口。正式场景无密集 BE 画像，尚不能证明收益。 |
| DELTA 和 FULL 重复准备 | 已消除「依据 hash/planes 下界即可证明必然 FULL」时的 DELTA 准备。潜在成功 DELTA 仍保留两份准备：共用完整表示需覆盖 section/biome/heightmap/BE/light 和 mod packet 构造副作用，未用缺字段快照替代。 |
| 既有 worker/WorkBudget | 保留 128 全局/16 每玩家任务，128 MiB 全局/32 MiB 每玩家估算字节，租约跨 worker→owner 邮箱直到发送/丢弃。无主线程 get/join。任务执行/owner 返回/异常/关服既有释放路径未改变。 |
| 旧异步结果排序 | 审计发现普通 FULL/DELTA 缺少后续原版更新的围栏，作为 T7 必须闭环继续修复，不能把 session/level 检查当作完整保证。休眠恢复已有 snapshot→ACK→更新尾部回放。 |

## 测试与验证建议

已新增 `SectionHashCacheTest`：cold/hit、同段重复变更、dirty 不可见、顶部空气→非空→空气、500 轮随机增量与整柱重算对照、失败后仍 dirty、捕获期间失效/clear 拒绝旧结果、段数变化、非法索引、defensive copy 和 LRU。

已新增 `PullDeltaAdmissionTest`：75% 临界、空柱、补零形状以及 1,000 轮与实际 SectionDeltaPlanner 对照，保证 admission 绝不跳过仍可能成功的 DELTA。现有 `ChunkContentHashReuseTest`、`SectionDeltaPlannerTest`、`BlockEntitySnapshotTest`、`ShadowPullPacketTest`、`ShadowPullSessionsTest`、`WorkBudgetTest` 继续覆盖 hash 组合/DELTA/NBT/会话/失败释放等基础语义。本代理按分工没有运行 Gradle 或游戏；只做 `git diff --check`，通过。构建和运行由 validation 代理独占。

建议在 pwsh 7 分别运行（不加 `--no-daemon`）：

```pwsh
.\gradlew.bat common:test "-Pmc_ver=1.20.1" --tests '*SectionHashCacheTest' --tests '*PullDeltaAdmissionTest' --tests '*ChunkContentHashReuseTest' --tests '*SectionDeltaPlannerTest' --tests '*BlockEntitySnapshotTest' --tests '*ShadowPullPacketTest' --tests '*ShadowPullSessionsTest' --tests '*WorkBudgetTest'
.\gradlew.bat common:test "-Pmc_ver=1.21.1" --tests '*SectionHashCacheTest' --tests '*PullDeltaAdmissionTest' --tests '*ChunkContentHashReuseTest' --tests '*SectionDeltaPlannerTest' --tests '*BlockEntitySnapshotTest' --tests '*ShadowPullPacketTest' --tests '*ShadowPullSessionsTest' --tests '*WorkBudgetTest'
```

运行时需覆盖 1.20.1 Forge / 1.21.1 NeoForge：同柱多玩家缓存共享；负 Y 和 section 边界 y=-65/-64/-1/0/15/16；同值 setBlockState（vanilla 返回 null 不失效）；高段空气变实心/再清空；爆炸、活塞、结构放置的正常 setBlockState 收口；换维相同坐标、卸载重载和关服重开。直接改 PalettedContainer/sections 而绕过 LevelChunk#setBlockState 的第三方批量写原本就不在失效覆盖内，不能宣称此次已解决；新增这类集成时须显式调用全柱 invalidate。

可观测点：`ChunkPipelineProfile` 的 `HASH_COMPARE`、`DELTA_SNAPSHOT`、`FULL_SNAPSHOT`、`NBT_SNAPSHOT`、`ENCODE_WAIT`、`ENCODE_WALL`、`SEND_WAIT`，以及 `BACKPRESSURE_DEFERRED`/`QUOTA_DEFERRED`。`ChunkAuthorityHashes.statsLine` 的 hit/miss/put/invalidate 可核对刷新行为；invalidate 现在包含重复写入已 dirty 段的事件次数，不再等价于移除柱数。没有优化后收益数据。

## T7：旧快照覆盖修复（源码已完成，集成验证待运行）

普通 Pull 捕获 FULL/DELTA 后，主线程可能先发新的方块/BE 原版更新，worker 再返回旧快照；当前 `sendPullResponse` 只验证租约、connection/session、level 和休眠 responseAllowed，普通模式没有柱修订/更新围栏。NBT-only 变更也不一定经过 setBlockState，因此单用 block hash revision 不能修复完整问题。

已实现两段无新增线协议的排序修复：`PullSnapshotTailServer` 在 FULL/DELTA 捕获后复制实际发出的 native block/section/BE 更新（bundle 按孩子顺序），原包照常发出；owner 返回时复核 session/level/range/当前同柱 Token，先发送 snapshot，再恢复并发送这段尾部。后续同柱请求取代旧 token；clear/removePlayer/disconnect/shutdown 及失败释放只清自己的 token。超限不发送截断历史，而是回到已有 Pull 队列重捕获。

`PayloadHandlers` 在原有 owner 接收顺序、后台 decode 之前建立 `PullSnapshotTailClient` fence，不额外 hop。三类 native 更新暂存在同柱 fence 内，直到 FULL/DELTA 的影子注入、所需光照和真实可见应用（halo 在注入完成）后，render owner 才按包序回放；现有 metadata 转发将其送回影子 owner。回放尾部后跨一次 shadow mailbox 队尾再回 render；期间新到的 native 更新继续合并并再次 drain，空尾部时才完成/继续既有 sleep ACK。回放绕过只覆盖当前 render 调用栈。

`ColumnSnapshotFence` 为所有 ordinary incoming/pack 分配单调版本，借现有 SleepTerrainClient 公共调用桥拒绝旧 pending/ready；R2 修订还定点接通官方 B 入队，光照门与确认算法保持原有设计。表上限 8,192 项，活跃上限 128；只淘汰 idle 条目，被淘汰的非零旧 token 不可重新有效。idle/tombstone 不保留 LevelChunk 引用。每柱 512 包、256 KiB payload；固定租约预留两批尾部和引用开销（640 KiB），客户端 WorkBudget 为 128 MiB，全局与任务数均有界。服务端在既有 encodeBudget 中增加同样的固定预留，仍保持 128/16 任务与 128/32 MiB 上限；这会让大快照更早背压，是显式的内存/并发权衡。超限、解码/回放失败、unload、断连/换维、替代及 30 s 未完成均取消旧版本并释放/修复；普通模式不新增 C2S ACK。

新增 `ColumnSnapshotFenceTest` 覆盖 FULL/DELTA 旧快照覆盖的受控负顺序、block/BE 两段尾部与 owner 后 ACK、drain 期间后到更新、同柱替代、clear/换维/重连复用 requestId、旧 ready 拒绝、count/bytes 背压、超限不回放残缺历史、取消释放和 idle LRU。它使用生产 helper，但不代替运行集成证明。

## T7 运行 fixture 与验证命令

新增 `pullorder` / `pullorderlegacy` 场景和 profile；由同一新构建运行。**legacy 是显式旁路新围栏的受控负对照，不是旧 JAR 实测。** 两者都要求启动时 smoke gate 与精确场景名，普通进程/仅远端玩家 tag 无法开启 latch、改写测试世界或绕过 fence。fixture 的世界改写只限隔离 smoke 世界指定的两柱。

sentinel native block 更新驱动客户端请求指定柱；FULL 用空基线，DELTA 在测试 compare 前 flags=0 修改一个格生成稀疏差异。worker latch 最多等待 20 s，服务端主线程继续 tick 并发送新的 block 和 BE 更新；客户端收到了这批原生更新后通过测试 command tag 回执，主线程据此解除 latch。response envelope 到达后另改一次 BE-only tag，普通可见应用由 gated probe 暂停直到这次更新到达。真实可见应用/尾部提交后分别在 render 与 shadow 所有者核对最终方块和 sign 文本。负对照应确定看到旧快照覆盖并 FAIL；修复场景应 FULL/DELTA 两柱均 current、无失败且活跃 fence 归零。

由 validation 代理独占执行；本代理没有运行这些命令：

```pwsh
.\gradlew.bat common:test "-Pmc_ver=1.20.1" --tests '*SectionHashCacheTest' --tests '*PullDeltaAdmissionTest' --tests '*ColumnSnapshotFenceTest' --tests '*ChunkContentHashReuseTest' --tests '*SectionDeltaPlannerTest' --tests '*BlockEntitySnapshotTest' --tests '*ShadowPullPacketTest' --tests '*ShadowPullSessionsTest' --tests '*WorkBudgetTest'
.\gradlew.bat common:test "-Pmc_ver=1.21.1" --tests '*SectionHashCacheTest' --tests '*PullDeltaAdmissionTest' --tests '*ColumnSnapshotFenceTest' --tests '*ChunkContentHashReuseTest' --tests '*SectionDeltaPlannerTest' --tests '*BlockEntitySnapshotTest' --tests '*ShadowPullPacketTest' --tests '*ShadowPullSessionsTest' --tests '*WorkBudgetTest'
.\gradlew.bat forge:compileJava "-Pmc_ver=1.20.1"
.\gradlew.bat neoforge:compileJava "-Pmc_ver=1.21.1"
.\gradlew.bat scanVersionBoundaries
.\scripts\runtime-smoke-test.ps1 -Ver 1.20.1 -Loader forge -Phase I -Scenario pullorderlegacy -CleanWorld -Vd1 8 -Vd2 8 -ClientRenderDistance 8 -SessionId 'pullorderlegacy_1201_forge'
.\scripts\runtime-smoke-test.ps1 -Ver 1.20.1 -Loader forge -Phase I -Scenario pullorder -CleanWorld -Vd1 8 -Vd2 8 -ClientRenderDistance 8 -SessionId 'pullorder_1201_forge'
.\scripts\runtime-smoke-test.ps1 -Ver 1.21.1 -Loader neoforge -Phase I -Scenario pullorderlegacy -CleanWorld -Vd1 8 -Vd2 8 -ClientRenderDistance 8 -SessionId 'pullorderlegacy_1211_neo'
.\scripts\runtime-smoke-test.ps1 -Ver 1.21.1 -Loader neoforge -Phase I -Scenario pullorder -CleanWorld -Vd1 8 -Vd2 8 -ClientRenderDistance 8 -SessionId 'pullorder_1211_neo'
```

结果路径沿用 `build/smoke-test/results/result_<SessionId>.json` 与 `build/smoke-test/logs/server|client_<SessionId>.log`。关键 marker：`PULL_ORDER captured` → `native-before` → `released ... native receipt acknowledged` → `response ... kind=FULL|DELTA tail=N` → `native-after ... BE-only` → `checked ... visible=true shadow=true passes=2 failures=0`。修复场景 `pullOrder.inflight=0`；negative 的 checked false 与 assertProbe FAIL 应作为预期失败证据保存。还应运行原 `sleepresume` 与 `dimension`，检验通用 token 桥与既有 sleep ACK / 换维路径。

本轮新增版本条件只在 `compat/PullOrderingProbeCompat`，采用 manifold 技能限定的 `< MC_1_21_2`（fixture 专门针对两个首要版本）；生产 packet adapter 使用本仓库既有全段都使用的 block/section/BE/bundle API，无新增版本断层或通道。首轮 T2 的 1.20.1 聚焦 94 测试由 validation 报告通过；T7 新增源码尚未构建和运行，不能把静态完成当作已验证修复。

## T7 独立审查 R2–R5 修订（当前冻结版）

此前交付被独立审查发现四个真实遗漏。本节覆盖前文初版描述，当前生产/测试/fixture/profile 已交 validation 冻结运行；本代理仍未执行 Gradle 或游戏。

| 审查项 | 修复与验证边界 |
|---|---|
| R2/P1 官方 B 包固定 token=0 被全部拒绝 | `MixinChunkMap` 和影子 `MixinServerPlayer` 桥传递实际 LevelChunk；`ShadowOfficialPacketBridge` 调用新的实际柱入队。`SnapshotPacketAdmission` 在影子 owner 与既有 chunkLock 内验证对象就是当前 injected 柱，捕获正版本，构造新包，再复核版本。旧 packet 重载只用坐标查当前柱并重建，不复用 holder 旧内容。active fence 的 B 被拒绝，由已绑定版本的 A 路径完成；没有重新放行 0。 |
| R3/P1 sleep done 后普通响应仍使用旧 sleep request | `acceptResponse` 对比旧 request 后退休已完成 sleep Column，让新 FULL/DELTA 进入普通 fence。sleep 与 ordinary 共用 `SnapshotTokens` 正单调 opaque ID；incoming/pending 不再混用 wire requestId。inline UNCHANGED 的 `injectedResponse`/`haloAppliedResponse` 显式保留 wire→opaque 桥，原 sleep ACK 顺序保持。 |
| R4/P2 同 hash FULL 复用未绑定 active fence | 既有 `forceReplace` 桥包含普通 active fence。因此空基线 FULL 即使 terrain hash 相同、且 nbtRepair=false，也走权威注入并绑定；不改 consumeLoop 的光照分支。active 未绑定对象的 ready 一律拒绝，包括 null/null。 |
| R5/P2 关核心或 shadow failed 后尾部扣留/循环重拉 | true→false 引擎切换在 render owner 立即按原序回放 held native 尾部，取消旧 sleep/ordinary pending/ready 版本，释放预算，重拉一次当前 FULL。随后 FULL 使用实际 vanilla handler 并在完成后 drain/关闭 native fence；不等待 shadow owner。DELTA 在此状态转 FULL。保留一个短时有界 native fence 是为了覆盖 FULL 后台解码到原生落地间的新更新，成功落地即释放，不每帧重建/重拉。direct 完成仍在尾部后接既有 sleep ACK。FULL handler 异常也释放/回退。 |

`SnapshotPacketAdmissionTest` 使用生产 admission helper 与真实 `KeyedPriorityQueue` 覆盖合法 B/cache 包落地凭证、非 owner 拒绝、当前对象不符、构造中版本变化，以及「旧 B 已构造→新 fence 注入→旧 B 入队」不能借新 token。扩展 `ColumnSnapshotFenceTest` 覆盖 sleep/ordinary opaque 身份不碰撞、普通 FULL/DELTA 连续尾部、same-hash 强制替换、无 shadow owner 的原生尾部提交/版本淘汰与资源归零。它们仍不代替运行场景。

实际 B 构造的包对象通过 gated weak set（最多 8192 项，不强持包内存）在 `applyReadyChunk` 真正成功后计数 `pullOrder.officialLanded`；场景要求 >=1，不能用 A 路径共享 token 误算 B 成功。`consumeLoop` 另有仅 `pullordersame` 生效的只读观测，记录真实 hashMatches/nbtRepair/forceReplace；两处都不改变光照门或确认算法。

新增/扩展运行场景如下。所有 fixture 仍要求 development + 启动 smoke + 精确场景名，普通进程不 latch、不改配置、不改世界、不旁路 fence。世界改写限指定隔离 smoke 世界；配置关闭只改内存 snapshot，不保存用户 toml。

| 场景 | 必须保存的断言/日志 |
|---|---|
| `pullorderlegacy` | 同一新构建旁路围栏的负对照；先真实 B 落地和 sleep enter/wake，再普通 FULL/DELTA，预期旧 block/BE 覆盖导致 checked false 与场景 FAIL。不得称为未修改旧 JAR 实测。 |
| `pullorder` | 先 sleep 恢复。sentinel 请求前打印 `ordinary-after-sleep recovered=true`（两柱均需）；普通 FULL、DELTA 各自 worker 暂停/前后 native 更新/可见与 shadow 一致，passes>=2、failures=0、inflight=0。 |
| `pullordersame` | sleep 完成后，对已有同 hash 基线发空基线 FULL；worker 暂停期间只更改 BE，保留相同 terrain hash。必须出现 `same-hash-full matches=true nbtRepair=false replace=true`，sameHashFull>=1、passes>=1，无超时重拉。 |
| `pullorderoff` | FULL envelope 建 fence 后在同一 connection/level 实际设置 chunk.enabled=false；必须有 `engine-disabled active=1 failed=false`、实际 native FULL 完成 nativeFulls>=1、15秒内 visible 当前/尾部释放，passes>=1、failures=0、inflight=0。 |
| `pullorderfail` | 同上但调用实际 `ShadowServerRegistry.failShadowServer` 进入 failed 出口，配置键仍开；记录 failed=true，其余 direct FULL/当前可见/资源断言相同。 |

补充命令（pwsh 7；validation 独占，两个锚点各运行）：

```pwsh
.\gradlew.bat common:test "-Pmc_ver=1.20.1" --tests '*SnapshotPacketAdmissionTest' --tests '*ColumnSnapshotFenceTest'
.\gradlew.bat common:test "-Pmc_ver=1.21.1" --tests '*SnapshotPacketAdmissionTest' --tests '*ColumnSnapshotFenceTest'
foreach ($pullCase in @('pullorderlegacy','pullorder','pullordersame','pullorderoff','pullorderfail')) {
    .\scripts\runtime-smoke-test.ps1 -Ver 1.20.1 -Loader forge -Phase I -Scenario $pullCase -CleanWorld -Vd1 8 -Vd2 8 -ClientRenderDistance 8 -SessionId "${pullCase}_1201_forge_r2"
    .\scripts\runtime-smoke-test.ps1 -Ver 1.21.1 -Loader neoforge -Phase I -Scenario $pullCase -CleanWorld -Vd1 8 -Vd2 8 -ClientRenderDistance 8 -SessionId "${pullCase}_1211_neo_r2"
}
```

旧 `pullorderlegacy_1201_forge-r2-invalid` 仅作为无效运行保存在 `build/analysis/spark-optimization-20261023/`：client 已打印 checked visible=false/shadow=false，随后 02:19:16 场景 assert 失败，02:19:17 fail-fast 跳过 graceful stop，server 02:19:18 egress 读异常是 SocketException: Connection reset。顺序支持它来自失败后强制停止的解释，未发现独立 codec/协议异常，但受 R2 污染的结果不能证明排序负对照成功；必须重跑保存有效的预期失败。

本次扩展路径：`client/ClientChunkHandler.java`、`client/sleep/SleepTerrainClient.java`、`client/scenario/ScenarioEngine.java`、`protocol/ShadowPullClient.java`、`protocol/ColumnSnapshotFence.java`、新增 `protocol/SnapshotPacketAdmission.java`/`SnapshotTokens.java`、`client/PullSnapshotTailClient.java`、`compat/PullOrderingProbeCompat.java`、`shadow/track/ShadowOfficialPacketBridge.java`、`mixin/shadow/MixinChunkMap.java` 的 shadow bridge、`mixin/server/MixinServerPlayer.java` 的 shadow track bridge，以及 `shadow/light/ShadowLightCompute.java` 的 B 入队和两处 gated 观测。真实服整柱抑制/每tick限速、T3 保存锁、T1 光照/确认算法没有被本代理改变。新增三个场景/profile 和 admission 测试，扩展 fence 测试。

仍需验证而未宣称完成：两锚点有效负/正对照、samehash/实际关核心/实际shadow失败、sleepresume、dimension 与移动光照回归。当前 `git diff --check` 通过；构建和运行的最终结论以 validation 保存的结果为准。

## R2–R5 首场运行后的 fixture 修正

`build/analysis/spark-optimization-20261023/pullorderlegacy_1201_forge-r2r5-invalid/` 仍是无效负对照：02:46:33 在排序前 `officialLanded>=1` 失败，02:46:34 fail-fast 退出，02:46:36 server SocketException: Connection reset。不能用它证明旧快照覆盖，也不能用 B 计数 0 推断整个柱交付失效。

因果定位：network Pull→injectChunk→LIGHT→pushReady 的正常 A 路径不必经过 vanilla holder 的 playerLoadedChunk/trackChunk B 桥。该场日志已证明 A 有实际交付：指定 `(3,0)` 在 client 日志3138/3197/3522行 `shadow_applied`；`(4,0)` 在3528/6239行 `shadow_applied`。单独对自然 B 次数设最低门槛没有触发依据，原先 fixture 的这一假设错误。

保留 B 成功门槛，并增加可控真实入口：五个场景在 setup 稳定后发送 `(2,140,2)` redstone sentinel。只有已启用的精确 smoke 场景才在收到该包后排一个 shadow owner 任务，调用实际 `ShadowOfficialPacketBridge.forwardChunkToRealClient(current injected (3,0), dimension)`；任务记录 naturalAttempts、owner/current 身份、active fence 与 offered 返回值。没有传旧 packet，也没有更改 admission 或放行 token0。构造边界用仅 fixture 的 ThreadLocal 标记具体测试包，weak reference 对象身份在真实 `applyReadyChunk` 成功时确认 `officialExerciseLanded`。场景同时要求 officialLanded>=1、officialExerciseLanded>=1、failures=0，防止早先自然 B 或 A 的成功充数。

有效前置日志必须包含 `B-exercise col=3 ... owner=true current=true fenced=false offered=true`，然后 `B-applied ... exercised=1`。若 offered=false，依照同时保存的 gate/fence/current/owner 诊断调查，不删除门槛强行继续排序。B 构造仍使用相同 owner/已有锁与版本复核；本轮生产代码只有桥入口的一行 gated 尝试计数，其余更改是 Probe、ScenarioEngine probe 键和五个 scenario。

另一实际干扰因素：原 setup 把玩家 TP 到 y=140，却未在脚下造平台；client 日志随后 playerBlock y 降到43。即使 creative 也会受重力持续下落，后续 sleep 会因此受到移动/唤醒影响。场景现先在隔离世界 y=139、x/z=-16..16 造 bedrock 平台，再 TP；该控制区域与 sentinel 属于 fixture 的隔离世界范围，不写用户世界。

T1 代理进一步只读核对了同一原始日志：126个不同 pendingAuth 注册柱中20个后续 clean，剩余106恰好是95个 outsideWindow>0 和11个 missingInWindow=1。pendingAuth 是既有“降级后等待邻域进展再 LIGHT”的表，既不是 LightConfirmationQueue，也不门控交付。指定两柱已有多次 clean Promote、实际应用且没有 pendingAuth 注册，现有证据不支持两柱光照交付停滞。新 sentinel 同时在影子 owner 只读记录 lightConfirmationSnapshot、两柱 clean/awaiting/full3x3/readyNeighbors/confirmationBlocked，以及各3×3邻柱 window/injected/init，供下一场解释边界尾部；未修改光照/确认算法。

命令参数不变，使用新 SessionId 重跑，先验证 Forge legacy 的 B/平台/sleep 前置，再判断排序预期失败。源码已再次稳定交 validation；静态 `git diff --check` 通过，等待实际运行，尚未声称上述前置或修复通过。

## B 前置通过后的排序完成链隔离

`pullorderlegacy_1201_forge-r2-no-fingerprint` 已通过真实 B（naturalAttempts=0、owner/current=true、offered=true、exercised=1）、两柱干净光照和 ordinary-after-sleep recovered=true。但其排序结果仍无效：服务端02:56:04已完整记录 captured request834→native-before→客户端 before tag→FULL response→客户端 response tag→native-after；客户端同一时刻打印 `Skip redundant full push (3,0): client already applied hashKnown=true`，之后没有实际快照 apply/checked。无完成不能认作旧排序覆盖的负对照。

原因是 legacy 旁路 active fence 后，新的 forceReplace 桥也为 false，FULL 复用/跳过路径会污染排序控制。当前 fixture 将排序正/负场的 FULL 同时改为既有显式 NBT repair FULL：仍为空基线 FULL 线协议，但客户端要求实际权威替换。两场请求模式相同，变量仍是围栏旁路；这不是更改生产算法。专门的 `pullordersame` 保持 `requestAuthoritativeFull`、nbtRepair=false 和真实 hashMatches 观测，继续独立验 R4。

五场景均增加独立阶段计数/断言：responses、afterReceipts、applications、checks。排序 FULL 的日志必须出现 `explicitFullRepair=true`、client `envelope`、client `native-after-received`、server `native-after acknowledged`、`apply-completion afterReceived=true`，最后才是 `checked visible=... shadow=...`。场景在 passes 断言前要求这些阶段全部发生，因此丢响应、缺后续 BE、没有应用或未检查都会明确失败，不会被算作负对照成功。有效 legacy 仍需真实 checked 的错误状态指纹；单纯超时或 stages 不全仍标 invalid。

本轮更改仅 `PullOrderingProbeCompat`、`ScenarioEngine` 四个只读 probe 键和五 scenario 的阶段断言。无生产排序/版本/光照/回压设计变更；静态检查通过，等待同型 fixture-only 窗口验证。

## 事件驱动的最终 fixture 流程

`pullorderlegacy_1201_forge-completedchain1-no-response` 继续标为 invalid。该场03:03:49发出 redstone 触发更新，但03:03:59服务端才记录对应 sleep snapshot tail=0，客户端没有 ordinary-after-sleep/request。固定 wake 后20秒等待不保证 COLUMN_READY；休眠恢复会正确抑制或在恢复快照中吸收该更新，触发更新不保证再作为单独 block-update 到达。因此不能将“某一个 native 包形状”用作测试请求发起器。

当前五场景改为专用 `pullOrder` 步骤，仅在原有 development+smoke+精确场景 gate 下有效，普通进程不能调用：

1. `action=b`：等目标柱的既有 A 交付/普通 fence结束，再在影子 owner 调用真实 B 桥；等那一个具体包真正应用后继续，保留 B 成功断言。
2. sleep 场景先真实 enter/wake，再 `action=ready timeoutMs=90000` 等指定两柱（same场仅3,0）实际 COLUMN_READY/publish 被观察到；不使用固定20秒推断恢复完成。
3. `action=request x=3|4`：在 render owner 按原 TCP 顺序发送测试 arm tag，然后直接调用既有请求 API。排序 FULL 正/负都显式 repair；same FULL 保持非repair；DELTA保留基线。服务端仍在真实捕获后暂停 worker、发送实际 native block/BE、等待客户端前更新回执，再释放 worker。
4. `action=chain timeoutMs=15000`：等待 envelope、后更新接收、实际应用回调和最终异步 visible/owner 核对结果全部完成，再做原阶段/结果断言。`checks` 现在代表结果已回 render并记账，不是刚排队核对。超时是 invalid，不能当 negative 成功。

新 `PullOrderScenarioContractTest` 解析五个真实资源，验证 B→sleep enter/wake→实际ready→request→chain→断言顺序、每柱单次请求与小于30秒的完成期限。删除 request 或 ready 的负用例会被契约测试拒绝。另已用 pwsh 对五资源作静态顺序预检，全部 OK；本代理仍未运行 Gradle。validation 应先运行两锚点 `--tests '*PullOrderScenarioContractTest'`，编译后再游戏。

有效运行需同时有 `stage-complete phase=b:3`、sleep场 `ready:3`、指定 request/chain、完整服务器/客户端阶段和最终状态指纹。该修改不改变生产协议/排序/版本/光照设计；仅 fixture 专用原语、Probe 和脚本/契约测试。

## UNCHANGED 睡眠恢复的真实生产缺陷修复

`pullorderlegacy_1201_forge_ready-timeout` 在真实 ready 步等90秒仍 recovered=[]，忠实暴露了生产恢复未完成；不能再一律归咎 fixture 漏观察。独立审查与本代理并行确认 `ShadowPullClient` 的 UNCHANGED NBT `AsyncSnapshotCommit.stillCurrent` 遗留 `SleepTerrainClient.acceptPending(key,response.requestId())`。R3 将该入口切为 opaque c.version 后，这个 wire requestId 通常不相等，提交在 visible/owner 之前被 discard，无法走 completeUnchanged、ACK 或 COLUMN_READY。此前将未触发仅解释为固定等待时间不足是不完整的。

修复是在已通过 acceptResponse 之后、提交 NBT 异步工作之前捕获 `incomingToken(key)` 为 pendingVersion；仍以此捕获的 opaque 版本贯穿 owner/client 的三次 stillCurrent 复核。wire requestId 继续用于 RECEIVE_WINDOW、injectedResponse/haloAppliedResponse 与服务端 sleep ACK，没有放宽 token0，也没有在 callback 中重新查询新版本给旧任务借用。全仓审计另两个 acceptPending caller 分别使用 PendingEntry.sleepToken 和 DeltaWork.sleepToken，均来自 incomingToken 捕获；本次遗漏 caller 已消除。

新增 `ShadowPullUnchangedTokenTest` 以实际 AsyncSnapshotCommit 和生产版本表复现 wire 谓词会立即discard；opaque 谓词可以经历 visible→owner→client ACK。替换请求/断连仍取消旧提交和旧ACK、释放资源；另有实际 receiver 源码边界契约，禁止重新把 response.requestId 传入 opaque gate。测试不能代替真实 Minecraft sleep/NBT 流程，运行时仍须证明 UNCHANGED-owner-committed 与 COLUMN_READY。

已核查 ready 的真实置位/清除：acceptResponse 为未完成 sleep 柱更新 request 和 opaque 版本；注入绑定对象后，真实可见/halo完成及必要尾部消费才发送 COLUMN_APPLIED；服务端回 COLUMN_READY 必须匹配 generation/request；客户端先 done，再过 shadow owner队尾且版本未变才 publish。之后新的普通响应会退休 done Column，属于合法清除。fixture 仍要求真实 done/publish；另在这一实际 publish 分支保存仅 smoke 启用、generation 匹配的观察记录，避免完成后马上合法退休导致轮询漏掉瞬时记录。不会把“记录缺失”当成 ready。

诊断现在一次区分缺失与未完成：`sleep-state` 输出 sleeping/generation、request/version/floor/injected、chunk身份、ack/done/publish 或 record=absent；关键事件为 `UNCHANGED-admission wire=... opaque=...` → `UNCHANGED-owner-committed` → `COLUMN_APPLIED-sent` → `receive-COLUMN_READY` → `COLUMN_READY-observed/publish`。ready 等待每5秒记录两柱状态。最终 `fingerprint` 同时输出 expected block、actual visible/shadow block、visible/shadow chunk存在性、expected/actual BE文本，缺柱/缺BE分开；只有具体旧值覆盖新值且完整chain，才是有效legacy负对照。

validation 已开启下一 tests/compiles 窗口、游戏暂等独立复审。新增测试命令为两锚点 `--tests '*ShadowPullUnchangedTokenTest' --tests '*AsyncSnapshotCommitTest' --tests '*PullOrderScenarioContractTest'`，其余 suite/loader/scan 与五运行场景同前。源码冻结；静态 diff检查通过，本代理未运行构建/游戏。

## 已取得的有效 Forge FULL 负对照

独立复审已接受 R6 opaque 捕获、三阶段检查与真实 publish 观察。validation 随后运行 `pullorderlegacy_1201_forge_r6opaque1`，证据保存在 `build/analysis/spark-optimization-20261023/pullorderlegacy_1201_forge-r6-full-old-overwrite/`。这是同一新构建明确旁路围栏的控制场，仍不是旧 JAR 测试。

该场首先证明真实睡眠 UNCHANGED 恢复已接通：client日志12062/12063分别是 col3 wire495→opaque2114、col4 wire501→opaque2119；随后两柱 owner-committed、COLUMN_APPLIED发送、收到匹配 COLUMN_READY、done=true/publish=true，ready阶段252ms完成。其结果区分了原先90秒卡死与真实完成，未靠放宽 ready 或延迟猜测。

普通 FULL request507 的服务端 captured→native-before→before receipt ACK→FULL response(tail=0，legacy)→native-after→after receipt ACK 完整；客户端 envelope→native-after-received→apply-completion(afterReceived=true)→checked→chain完成也完整，chain仅247ms。client日志12773的具体指纹为 expectedBlock=diamond_block、visibleChunk=true/visibleBlock=stone、shadowChunk=true/shadowBlock=stone；expectedBE=tail-after-3，而 visibleBE为空、shadowBE=tail-after-3。确认不是缺柱或没有完成，而是实际旧 FULL 将新方块与可见 BE 状态覆盖。场景随后按预期在 passes断言失败。

**本场只计 FULL 负例。DELTA 因 fail-fast 尚未执行，不能计作已复现。** root决定保持稳定源码继续 positive FULL+DELTA 与兼容场景，不为补负 DELTA 改脚本。positive与两个版本的最终结论继续由 validation 保存，本代理未运行游戏。

## 已通过的 Forge FULL/DELTA 正例

validation 运行 `pullorder_1201_forge_r6opaque1`，`build/smoke-test/results/result_pullorder_1201_forge_r6opaque1.json` 为 PASS、HasFail=false，ProbeGateFailures 与 LogAuditFailures 均为空。本代理只读核对了保存结果与两端日志，没有启动游戏或修改验证中源码。

server FULL request515、DELTA request564 均携带尾部4包；client两柱 checked visible=true/shadow=true，passes=2/failures=0。具体 fingerprint 表明两柱 actual visible/shadow 均为 diamond_block，BE分别均为 tail-after-3 与 tail-after-4；chain分别约253ms/241ms完成。B 与实际 sleep ready 阶段也已先通过。此场完成了同一 Forge 构建的有效 FULL 负对照和 FULL/DELTA 正对照闭环。

NeoForge与samehash/关核心/shadow失败/sleepresume/换维/移动光照等兼容验证仍由 validation 继续，不将 Forge 正例外推为全部版本/路径通过。

## NeoForge 所有者重入发送重排（R7）

`pullorder_1211_neo_r6opaque1` 在完整 FULL487/tail4 链后仍真实失败，证据目录 `build/analysis/spark-optimization-20261023/pullorder_1211_neo_r6-failed-stale-block/`。client7150 envelope、7258 after receipt、7425 apply completion、7541 checked 与7542具体值指纹全部存在：两侧方块都是stone，两侧BE都是tail-after-3。该结果不是超时，也不能用Forge正例覆盖；本轮保留fixture全部期望与门禁。

独立审查定位并经本代理核对：`NeoForgeNetworkManager.sendServerPayload` 无条件 `server.execute`。Pull编码返回已在服务端owner task内，`sendPullResponse`先调用SPI发送FULL/DELTA，再立即 `player.connection.send` 原生尾部。Minecraft `ReentrantBlockableEventLoop.scheduleExecutables()` 在 `runningTask()` 时为true，即使当前正是owner也会把第二个execute入队。因此实际网络顺序变成native diamond尾部→旧FULL，客户端envelope围栏来不及截住前面的diamond；BE-after等客户端收到envelope回执后才发送，所以BE正确。此因果解释同时符合visible与shadow的stone指纹，而非仅可见预测问题。

修复新增 `concurrent/OwnerThreadDispatch.java`：已经在owner时inline执行，否则仍交owner executor。Neo的现有统一sender使用该桥；shadow response、authority、play_init均受益。审计其余Neo Play S2C payload后，将sleep control、dictionary、index也统一收口，避免不同sender边界产生相同重排或越owner。C2S与纯Connection载体不变。Forge/Fabric的Play sender直接提交发送，没有这个二次owner hop；其接收入口的调度用于首次进入owner，不移除。

新增 `OwnerThreadDispatchTest` 使用两锚点实际Minecraft `ReentrantBlockableEventLoop`，而非始终inline的假executor：负用例先运行一个owner任务，任务内旧execute把FULL入队，立即tail使值diamond，再poll出FULL得到stone；正用例用实际生产桥证明FULL/DELTA先于block/BE尾部且没有剩余二次任务。真实worker线程测试证明off-owner仍入队，连接动作只在owner执行。另有Neo实际sender源码接线契约，确保测试桥没有与生产脱节。没有增加线程池、Future、阻塞等待或改变工作预算/光照门/存储锁。

本轮XERJ复用已载入Bobby参考库，检索未找到直接匹配此重入发送边界的实现；不照抄其LimitedExecutor。根因依据为Minecraft 1.21.1官方反编译 `ReentrantBlockableEventLoop.java:11` / `BlockableEventLoop.java:88` 与本仓sender/尾部调用顺序。静态 `git diff --check` 通过；本代理仍未运行Gradle/游戏。

需validation先在1.20.1/1.21.1运行 `common:test --tests '*OwnerThreadDispatchTest'`，再两版完整受影响测试/Forge与Neo编译及scan；游戏先重跑Neo `pullorder` 正FULL+DELTA，再Forge同场确认无回归，之后继续same/off/fail/sleep/dimension/ovdlight。原Neo失败证据保留，修复成功必须重新取得完整chain及两柱实际diamond与tail-after文本，不能仅凭测试或静态因果关闭R7。

独立审查已正式接受R7源码的owner/off-owner边界、共享sender审计、异常与租约释放及实际Minecraft重入executor回归；review.md关闭的是源码发现，Neo新JAR运行证据仍待validation。

## NeoForge DELTA 影子方块差异与合并并发修复（R8）

R7新构建的 `pullorder_1211_neo_r7owner1` 已实际证明FULL修复有效：col3 request491完整链后visible/shadow都是diamond，BE都是tail-after-3。但col4 request540 DELTA/tail4在完整链后visible为diamond、shadow仍为gold，BE两侧tail-after-4；该场整体FAIL。证据保留在 `build/analysis/spark-optimization-20261023/pullorder_1211_neo_r7-partial-fail-delta/`，不可用FULL通过覆盖DELTA失败，也不改期望。

本轮静态确认了两个必须修的独立并发缺陷，但旧日志缺少逐包/commit次数，不能武断断言哪一个单独造成该场gold：

- 三个native handler HEAD先于原版ensureThread，Netty首次调用时intercept明确返回false，随后却立刻forwardBlockUpdate到shadow，绕过稍后Render才建立/执行的围栏，并在Render再镜像一次。现在ClientMetadataHandler在读取player/level或镜像之前要求Render owner；off-owner只留下严格gated包诊断，原版仍自行调度正常handler。
- DELTA消费线程直接修改注入section，提前token检查与锁内实际提交之间有换代窗口；成功apply后遇busy会把同一DeltaWork回队，下一轮重复应用旧内容，并可能put覆盖新work。现在复用ShadowPoiGate把内容提交转shadow owner，调度之内拿chunkLock；在owner入口与锁内复核opaque token、registry实例和generation，成功apply和一次light内容失效在同一owner动作内完成。SnapshotContentCommit保存同一个committed baseline，busy只重试LIGHT且putIfAbsent保留更新work。LIGHT任务携原work，普通DELTA及延迟邻域GATE在提交前均校验token/实例/generation/当前注入对象，不给旧retry借新内容身份。

T1作者只读核对了锁序与确认票接口，采纳其严格owner建议：既有runOnMainLoopAndWait在拒绝/超时/中断时会inline回落，新增supplier检查isMainLoopThread，回落消费线程时绝不碰world；仍有效的原work在既有有界pendingDeltas内冷却1秒后由正常frame pump重试，没有新增队列/Future池，也不把调度不可用当内容损坏发FULL。断连/换代/旧token直接丢旧work，保留既有围栏取消/超时回收。没有改3×3门、push门、确认票算法或T3保存锁。

新增SnapshotContentCommitTest使用实际Minecraft重入owner、生产AsyncSnapshotCommit、ColumnSnapshotFence和一次提交状态，覆盖异步snapshot→held native tail→owner写入→ACK/预算释放、busy不重写内容、原先GOLD再提交可重现owner单侧错误、消费预检后换代/断连拒绝、owner不可用冷却及新work不被旧重试覆盖、失败不假装提交成功。接线契约检查真实Render guard、owner调度先于chunkLock及token/generation复核，运行时仍需实际Minecraft场景验收，不能以模型测试替代。

严格development+smoke+精确场景gate下增加 `PULL_ORDER packet stage=off-owner-not-mirrored|held|replay|mirror-queued|mirror-applied`，记录目标方块/section实际state和BE NBT；`delta stage=content-before|content-after|light-only-retry|owner-unavailable-retry`记录opaque/current/owner线程，owner-state记录同一注入对象与实际目标方块。普通运行第一步即返回，不作逐包解析/状态遍历。下一游戏可区分native是否DIAMOND、一次DELTA是否GOLD、回放写入后是否被再次内容提交覆盖。

本轮XERJ在已载入Bobby语料中检索相关网络handler/forceMainThread，无直接匹配，不强行使用不相干参考。静态diff检查通过；本代理未运行构建/游戏。需两锚点 `common:test --tests '*SnapshotContentCommitTest' --tests '*OwnerThreadDispatchTest' --tests '*AsyncSnapshotCommitTest' --tests '*ColumnSnapshotFenceTest'`，再完整相关suite、两loader编译/scan；独立复审后重跑两版positive FULL+DELTA，要求两柱实际diamond与tail-after文本且完整阶段链。

独立review已正式接受R8稳定修复，复核了strict owner与claimed超时边界、原work冷却/正常frame pump、内容一次提交、busy的putIfAbsent、DELTA/GATE身份以及原版Render重入不漏镜像。源码发现关闭；模型测试不代替实际双端DIAMOND，集中构建与游戏仍由validation唯一执行。

## 精确事务测试关联修复（R9）与验收撤回

Forge `pullorder_1201_forge_r8commit1` 的失败记录保存在 `build/analysis/spark-optimization-20261023/pullorder_1201_forge_r8commit1-fail-shadow-mismatch/`。本代理与独立审查分别核对到确定的fixture因果：client7075是邻柱(3,-2) shadow_attempt，7076已经记col3 apply-completion；真正目标(3,0)的remote_pull应用到7082才开始，7083才回放tail。过早排队的目标检查捕获STONE/空BE，回Render打印7119时visible已DIAMOND。7109–7117及7120/7124实际mirror后当前注入对象515880409始终DIAMOND，没有已证旧FULL再次替换。这次失败不能据此虚构生产晚提交修复。

缺陷为Probe.completed/deferApply/fullIngress仅检查chunkX，邻柱可误启动目标检查/冻结/贡献samehash计数。新PullOrderProbeScope仅保存两柱严格目标，关联完整DimensionKey、level/connection对象身份、shadow generation、wire request及opaque token；拒绝旧请求、旧版本、旧会话/代际及重复完成。deferApply/fullIngress、包观察与owner状态现在用同一目标域；samehash计数还须当前token。owner读取和回Render记checks/verified两阶段再次复核事务身份。

完成事件也重新收口：普通正例仅在PullSnapshotTailClient真实finishDrain成功后，带原始opaque完成；通用SleepTerrainClient.appliedAfterTail不再向Probe发送不明确的事件，因此任何邻柱sleep/halo/cache完成不能启动普通Pull核对。legacy控制只在实际目标ReadyItem的REMOTE_PULL/SECTION_DELTA成功应用后、带原token完成；其envelope会淘汰此前idle版本，但仍不建active围栏、不扣native更新、不触发forceReplace，保留“同一新构建旁路围栏复现旧排序”的控制含义。native降级FULL计数也关联实际目标key，原有实际可见值/engine关闭/无在途条件保留。

完成与check-owner日志附key/request/token/server/generation/chunk身份，owner-state亦附同组身份。检查仍查当时真实注入表，不改取旧捕获对象，不加固定等待，不改diamond/BE期望。新增PullOrderProbeScopeTest覆盖同X不同Z、异维、旧level/connection/generation/request/opaque不能完成；实际Minecraft owner+ColumnSnapshotFence证明邻柱不能抢检查，目标尾部过owner且finishDrain后才记检查；实际接线契约禁止从通用sleep回调触发。场景步骤契约继续保留。

独立追溯确认此前Forge legacy r6opaque1的12686(3,-2)→12687 completion，以及Neo R8 positive FULL的7905(3,1)→7908 completion、DELTA的8811(4,-1)→8812 completion均有同类污染。**撤回这些场的严格正/负验收结论**，保留原日志里实际状态和已证生产源码发现；上文“有效Forge负例/positive通过”仅为历史记录，均由本段更正。Neo legacy虽见目标z0旧STONE，也统一在R9后重跑，不能绕过事务关联验收。之前before性能臂不依赖该Probe，不受本缺陷影响。

需validation先两锚点 `common:test --tests '*PullOrderProbeScopeTest' --tests '*PullOrderScenarioContractTest' --tests '*SnapshotContentCommitTest' --tests '*OwnerThreadDispatchTest'`、相关suite及两loader编译/scan；独立复审后双版negative+positive四场。每场必须新完整key/request/token completion与check-owner，正例两柱双端DIAMOND/正确BE；负例完整目标事务链后实际旧STONE覆盖、按期望失败，超时/串柱仍invalid。本轮没有修改FULL生产注入/光照/锁语义；唯一控制行为改动是严格smoke legacy的旧ready版本淘汰。

R9独立源码复审已通过：完整scope与双阶段身份复核、真完成桥、legacy仍inactive/nohold及当前owner读法均接受，无追加编辑要求。测试已读但独立代理未执行；四场新事务证据仍待validation，旧排序验收不恢复。

### R9 新 Forge FULL 负对照的严格事务证据

本代理只读核对 `pullorderlegacy_1201_forge_r9scope1`：envelope为request500/key201326592/token2286；client12549的apply-completion、12551的check-owner均是相同完整key/request/token，shadow generation1、chunk262872875。12557检查双端false；12558具体值为visible/owner均STONE，visible BE空、owner BE tail-after-3。chain302ms完整完成后，原passes断言按预期失败，未将FAIL结果文件改成PASS。此场是R9精确完成桥的新负对照，仍是同一新构建明确旁路围栏，不是旧JAR。DELTA被fail-fast阻止，仍无负DELTA信用。其余三场及兼容路径待T4继续，最终归档/异常审计由validation提供。

### R9 新 NeoForge 严格正/负事务证据

只读核对 `pullorderlegacy_1211_neo_r9scope1`：FULL request507/key201326592/token2261的14944 completion、14951 check-owner一致（generation1、chunk338252568）；14999实际visible/owner均STONE、visible BE空、owner tail-after-3，完整事务后按原期望失败。此场同样仅计FULL负例。

`pullorder_1211_neo_r9scope1` 结果为PASS、HasFail=false，ProbeGateFailures与LogAuditFailures为空。FULL request513/token3195与DELTA request565/token3498分别有各自完整key的completion/check-owner；8150/9091具体指纹均是双端DIAMOND及tail-after-3/4，passes2/failures0。此为R9精确完成边界的新正例，不恢复旧R8混淆场次。正式归档由validation继续，Forge新positive与兼容矩阵仍待完成。

### R9 新 Forge 正例完成核心四场闭环

`pullorder_1201_forge_r9scope1` 结果PASS、HasFail=false，ProbeGateFailures和LogAuditFailures均为空。FULL request529/token3231的7514 completion→7515 check-owner→7519具体指纹，DELTA request578/token3354的7918→7919→7935分别独立对应目标完整key。两柱actual visible/shadow均DIAMOND，BE均tail-after-3/4，passes2/failures0。相同shadow实例1113225182/generation1下，具体当前柱对象分别1154236719/290986000。

至此R9精确scope的新双版FULL负对照与双版FULL/DELTA正对照四场都已取得对应实际事务证据；各负场只是FULL，绝不额外宣称负DELTA已跑。此结论不恢复旧受串柱污染的验收。validation继续samehash FULL、引擎关闭/失败、睡眠、换维与移动光照等兼容场景，本代理维持源码冻结，仅只读核证和写docs，未自行运行Gradle/游戏。

## 同hash场资源收敛门（R10）

`pullordersame_1211_neo_r9scope1` 目标request529/token3228的same-hash matches=true、nbtRepair=false、replace=true，精确目标完成后双端STONE及BE tail-after-3正确，passes1/failures0；但随后同tick全局inflight==0断言实为6，整场仍FAIL。证据 `build/analysis/spark-optimization-20261023/pullordersame_1211_neo_r9scope1-fail-inflight/`。全局counter确为ColumnSnapshotFence WorkBudget.jobs，目标自身已经finishDrain，不能将6误写成目标租约泄漏。

独立尾端核到同时5条DELTA与(4,-1)/(4,-2)/(-3,5)/(-2,5)/(-1,5)进入INIT/LIGHT；断言失败后(4,-1)/(4,-2)/(-2,5)/(-3,5)继续实际落地。现存日志没有六项完整state/age，不能伪造原场明细，也不能把几个apply等同全部六租约已归零。旧same语义事实保留，资源未通过不追认整场。

修订保留global0硬门，五脚本在全部目标chain后增加quiescent：目标scope active=0、全局active=0与reservedBytes=0须连续250ms，总等待严格小于15秒；超过截止的晚到零也不能通过。其后仍分别assert target0/global0/bytes0。每次首次等待、每5秒、失败/完成才按需输出budget所限<=128个active primitive状态（key/dim/pos/request/opaque/age/bound/draining/tail count/bytes/overflow）。快照不保留world/chunk引用、不改变版本、队列或Lru，不在普通帧热路径分配。

另仅在明确smoke gate下记录finished/cancelled/repair/expired-repair/native-fallback-repair事件（细行最多512，原子计数不截断）。SAME以B阶段开始为观察基线，只要repair计数增长就FAIL，即便global已0，也不能靠已老化fence的30秒expire修复遮掩孤儿；off/fail可把预期native降级repair单列核验。故15秒界本身不是“没有expire”的替代证据。

新增StableQuiescenceTest：活动/字节变化重置完整250ms，实际6个ColumnSnapshotFence租约逐个真实startDrain/takeTail/finishDrain后才归零，诊断是不可变分离快照且无版本副作用。脚本契约新增quiescent顺序/<=15秒与原global0/target0/bytes0前置，删除等待的负例会在游戏前失败；接线契约保留硬截止与SAME无repair门。该变更是测试/只读观测，不改变生产LIGHT、围栏时序、预算或释放策略；已接受的R9核心四场不因纯资源门修订自动撤回。

需两anchor新增 `--tests '*StableQuiescenceTest' --tests '*PullOrderScenarioContractTest' --tests '*PullOrderProbeScopeTest' --tests '*ColumnSnapshotFenceTest'`，loader编译/scan后先重Neo same取得非目标有限正常归零，再继续off/fail。原六项明细只能由新运行真实采样，不能承诺重现恰好相同六项。本代理未运行Gradle/游戏。

## 已提交内容的独立 LIGHT 阶段凭据（R11）

T1作者与独立审查均确认R8引入的LIGHT谓词过严，是本次方案集成过程的新缺陷，不应归入未修改原版本：DELTA成功提交后，非可交付光环柱会先haloApplied；startDrain立即使generic pending拒旧token，finishDrain/合法sleep ACK还会换opaque。但同一个已提交baseline的INIT/LIGHT可能仍在等邻柱或busy队列，因此用acceptPending会丢合法剩余光照。这是源码可达顺序，不能据此宣称它已解释same场原六项在途。

最小修复保持内容/ready/ACK门原语义：ColumnSnapshotFence仅新增primitive completedVersion；正常finishDrain保存原opaque供LIGHT继续，begin/cancel/clear清掉它。lightCurrent只接受当前/正常完成的非零opaque，pending仍拒draining/完成旧token。Sleep Column同理保存ACK前lightVersion，在新response、restart/unload、cancelSnapshot、invalidateReadySnapshots及reset失效；COLUMN_READY清chunk不清正常LIGHT凭据。无world引用进入新token表，无放行0、所有旧token或仅凭done/非active的漏洞。

DeltaWork只有content.applied后才能用acceptLight；fresh owner提交仍严格acceptPending。已提交的busy retry入口、owner返回后的续段、DELTA及延迟GATE入LIGHT统一使用phase凭据，并同时核registry实例、shadow generation、当前injected对象等于该work保存的committed baseline。正常尾部后只继续LIGHT，不再次apply section/清INIT，不改变3×3门、push门或确认票算法。

新增DeltaLightPhaseTest使用实际Minecraft owner、实际ColumnSnapshotFence与SnapshotContentCommit，确定性安排halo先drain/ACK、延迟owner阶段后到；验证pending/ready旧token继续拒绝，而同baseline LIGHT可继续、content只apply一次。另通过实际SleepTerrainClient私有Column及抽出的真实advanceAfterTail状态转换（不发线ACK）验证ACK换opaque后只保留LIGHT，正常COLUMN_READY清chunk仍可继续延迟owner阶段，新response/cancel/重置/引擎失效会撤销。替换baseline、新请求/取消/清表与0 token都拒旧phase；接线契约检查DELTA/GATE确实使用新桥且fresh仍pending。这里是阶段/所有者排队测试，不启动原版光照引擎，实际移动光照仍由游戏回归验。

严格smoke gate增加有限 `light-phase stage=admitted|engine-complete`（最多128细行），只记录pending=false但lightCurrent=true的正常尾部后阶段，可把实际halo继续LIGHT与生产门放宽区分；普通运行不解析/分配详细观测。R10资源观察同时保留，无需将原6项计数武断归因为本修复。最终产物仍需双锚点正例、same/off/fail以及睡眠/换维/移动光照集中检查。

### 缺陷来源与证据表述边界

原始目标是解决既有异步快照旧覆盖新native状态、准备成本及native HEAD早于owner的镜像边界。R2–R6的opaque/官方B/完成/降级接线、R7新快照+tail事务遇既有Neo二次调度、R11内容与LIGHT凭据分离，是本次围栏方案集成期间发现并修复的问题，不一概写成原版本缺陷。R9/R10是测试关联与资源收敛观察的缺陷，非产品旧覆盖证据；被撤回和失败的场次全部保留。R8既有DELTA重复内容提交/晚校验与native镜像边界有独立源码依据，但旧日志不足以把其中任一条唯一归因为当场GOLD。最终摘要只用稳定构建验收与明确未测限制，不用中间问题夸大原版本收益。

R11最终独立源码复审与T1接口复审均通过，无需追加生产编辑。已明确单元INIT/LIGHT只是阶段计数模拟、实际Sleep转换不发线ACK；最终游戏仍需gated admitted→engine-complete与资源收敛。整体R10+R11交validation集中两版测试/编译/scan及运行，生产/测试/fixture源码冻结，本代理仅docs。

集中检查首轮1.20.1 compileTest曾因测试嵌套类字符串含美元符被Manifold插值解析而失败，原日志gradle-r11-focused-1.20.1.log保留；仅将DeltaLightPhaseTest查找改成getDeclaredClasses按simpleName过滤，保留同一真实Sleep状态转换语义。扫描本轮新增/改动测试无其他美元符字面量，生产未变，diffcheck通过后重新交T4检查。

### R10/R11 集中检查与真实halo阶段证据

validation重跑后，两anchor R11新聚焦及fullfocused、Forge与Neo编译、scanVersionBoundaries日志均BUILD SUCCESSFUL（gradle-r11-*.log）。`pullordersame_1211_neo_r11stable1` 结果PASS、HasFail=false、ProbeGateFailures与LogAuditFailures均空。14307资源观察为targetActive=0/globalActive=0/reservedBytes=0，连续稳定后总2985ms完成，repairs=0/expiredRepairs=0；不是靠30秒repair或删全局门通过。此新场证明非目标资源有限正常归零，不追认旧场原六项完整身份。

还取得R11实际原版LIGHT future证据：client13248 halo(-6,-9) key4503599291826167/token4471/chunk1609610655是admitted，13280同一三元身份engine-complete，均pending=false/lightCurrent=true；13863→13896的(-2,-10) key4503599560261622/token5047/chunk1648266527同样。其线程分别是消费阶段与light引擎完成线程，显示正常尾部后剩余LIGHT实际继续，不仅是单元计数模拟。其他移动光照/兼容结论仍由后续场景决定，不由本证据外推。

### 终版 Forge 运行时关闭引擎验证

`pullorderoff_1201_forge_r11stable1` PASS且门禁/audit无失败。初FULL476/token1456收到后真实关闭chunk.enabled，随后仅有限native fallback FULL477/token1461；6792精确完成、6793 visible=true/inflight0。6811资源在284ms达到target/global/bytes全0、repairs1/expired0；三秒后第二个稳定窗口仍同值、296ms完成。该场把预期一次native降级repair与expire重拉区分，证明尾部释放和实际native可见内容，而非仅等30秒清空。其他版本/失败出口及移动场仍待validation。

Neo `pullorderoff_1211_neo_r11stable1` 与Forge `pullorderfail_1201_forge_r11stable1` 也均PASS、HasFail=false、gate/audit为空。两者actual native-fallback visible=true、inflight0，两个稳定资源窗口target/global/bytes全0，repairs恰1、expiredRepairs0，约250–283ms；分别覆盖实际运行时关闭chunk与实际shadow失败出口。Neo fail及终版positive/移动矩阵继续以validation最终记录为准。

终版compat六场现均有新运行PASS：Neo `pullorderfail_1211_neo_r11stable1` 的7097 native-fallback visible=true，资源稳定窗口290ms及三秒后252ms，repairs1/expired0，target/global/bytes均0；Forge `pullordersame_1201_forge_r11stable1` 的7433实际same-hash matches=true/nbtRepair=false/replace=true，7688双端STONE及tail-after-3正确，7836在277ms稳定全资源0、repairs0/expired0。两场HasFail=false、gate/audit为空。连同上述Neo same及双版off、Forge fail，完整保留global0/bytes0/目标释放与无expire门，未追认旧same瞬时全局6那场。终版双版positive及睡眠/换维/移动光照仍待T4运行。

## 睡眠初始化与刷新准入竞态（R12）

终版Forge traffic-profile `sleepresume_1201_forge_r11stable1` 在L24转向恢复时真实超时：04:49:10 state=IDLE，但(-6,0)的Column仍未done，不能把状态IDLE当作恢复完成。原证据 `build/traffic-profile/sleepresume_1201_forge_r11stable1/` 保留。该柱睡前已clean Promote/实际LIGHT及两次cache apply；恢复后没有该柱新的LIGHT/actualapply，八邻柱均继续正常落地。原始agg的Forge discriminator5逐包离线解析显示2342条Pull响应里目标只有睡前request50 DELTA，恢复期无该柱响应；523个其他COLUMN_READY中没有-6,0。后续egress错误发生在客户端fail-fast之后，是退出派生事件。

确认了一个可达初始化发布竞态：旧enter先令sleeping=false，再逐个发布新Column，最后才sleeping=true。并行tracking可在中间刷新一个新记录，先扣30秒retryAt；随后请求被睡眠5×5过滤或服务端睡眠门拦下，wake后10秒完整恢复门仍被未发送请求的冷却挡住。该链符合本场缺目标响应事实，但旧日志没有原retryAt值，不能宣称唯一运行归因。它是既有enter/refresh时序问题，不归入R11 LIGHT门修复；没有证据要求修改3×3门或确认票。

修复先在lifecycle metadata monitor内关闭sleep门、设置新generation/window，再发布Column。refresh捕获的旧读者必须在reserve时重新核对完整key、generation、window对象、Column对象、done与sleep状态；读者曾见false或同坐标旧记录均不足以准入。新permit只占一个有界PullIntent，不提前扣冷却。hash、物化、磁盘、NBT及包编码仍全部在monitor外；permit随现有接收窗口流转、独立分组，已编码wire出口才在monitor内再次核身份，先COLUMN_REQUEST再Pull，成功调用发送后才设置30秒retryAt。过滤/过代/队列取消/发送抛错清permit且不扣冷却；精确admission token的discard不能取消后来的接收credit。没有在该monitor内等待影子owner、拿chunkLock或做快照准备。

新增SleepRecoveryAdmissionTest覆盖：实际installWindow在首Column发布暂停时真实requestRefresh不能抢先发送/扣冷却；wake立即可reserve/send；旧false读者跨新generation/window/Column不能借用新记录；锁外准备后换代拒旧wire与旧admission清理；发送失败不留permit/冷却。反射调用生产真实状态转换与实际PullReceiveWindow，无线网络/游戏世界操作。原10秒完整恢复门、5×5限制及T1光照门保留。新增sleepresume严格smoke-only、目标(-6,0)、最多128行的install/reserve/wire/response/UNCHANGED/inject/ACK/control诊断，超时sample附实际request/version/ack/done/publish/refresh/retry剩余时间，便于下次区分未请求、未提交与未ACK。

需validation双anchor `common:test --tests '*SleepRecoveryAdmissionTest' --tests '*PullReceiveWindowTest' --tests '*DeltaLightPhaseTest' --tests '*ShadowPullUnchangedTokenTest'`、相关suite、双loader compile及scan后，双版实际sleepresume重跑；不得调长10秒门或按mesh认为当前快照已落。源码还需独立复审，尚未构建/运行，本代理未运行Gradle/游戏。

R12复审收口了两条新增边界：准备异常也统一用admission token原子discard，移除旧matchesAdmission检查后按key notePullFailure/cancel的竞态；回归直接调用实际清理入口，在旧检查后插入同key新credit，验证新slot和新gate不被撤销。另SPI原void sender在无连接/吞异常时也正常返回，不能当作已发；现新增trySendShadowPullRequest布尔接受桥（旧void入口委托），三端都核同一个live本地连接、实际通道可用、发送调用正常返回，已知无发送/异常返回false。不等网络或远端ACK。PreparedClientSendTest覆盖missing/closed/replaced/throw/success及默认SPI释放和三端ServiceLoader接线；SleepRecoveryAdmissionTest额外验证实际false桥不扣冷却，共6项。ByteBuf接管标记区分“gate未调用SPI”与“SPI接管后返回false”，避免Fabric已交给通道的包被调用者再次释放。

R12扩展路径完整清单：`client/sleep/SleepTerrainClient.java`、`protocol/ShadowPullClient.java`、`protocol/PullReceiveWindow.java`、`client/scenario/ScenarioEngine.java`（仅失败状态观察）、`platform/services/INetworkManagerService.java`、新增`network/PreparedClientSend.java`、三端`network/*NetworkManager.java`请求发送方法，以及新`protocol/SleepRecoveryAdmissionTest.java`、`protocol/PreparedClientSendTest.java`。ServiceLoader注册文件未变。平台适配沿既有`< MC_1_21_1`主缝，无新MC_VER常量/新线协议；采用hassium-manifold规则。验证建议在上列聚焦加`--tests '*PreparedClientSendTest'`，并编译Fabric两anchor以覆盖第三SPI实现，再双版主要loader与scan。待独立复审通过才由本代理正式唤醒T4唯一构建窗口。

R12最终独立源码复审已明确通过（含两条异常/发送收口），未运行检查；已由本代理按root授权直接followup_task唤醒validation，集中检查与双版sleepresume窗口开始冻结。source模型和本地发送接受桥不等于实际wire ACK/恢复成功，仍以新运行验收为准。

R12首轮1.20.1 focused有6/20失败：诊断先触ClientSmokeTest导致common测试加载不到平台ServiceLoader，真实installWindow首次调用ChunkShapeCompat又要求注册表bootstrap，后续NoClassDefFoundError及并发callback超时均是失败初始化级联。保留原日志/XML，未进入游戏。最小修正将静态smoke+scenario短路置于dev服务检查之前，并在新增测试@BeforeAll沿既有ChunkShapeDilationTest执行真实注册表bootstrap；不放宽状态/shape/10秒门或改业务准入。两锚点原focused需重新运行。

R12启动修正后，T4原focused两anchor重跑、两anchor fullfocused、Forge1.20.1/Neo1.21.1/Fabric双anchor编译以及scanVersionBoundaries九份新日志均BUILD SUCCESSFUL（gradle-r12-*.log，首轮失败除外）；新SleepRecoveryAdmissionTest实际6项0失败。Forge实际sleepresume_1201_forge_r12stable1已启动，运行结果待验，本代理源码持续冻结。

R12实际Forge sleepresume_1201_forge_r12stable1现PASS、HasFail=false、gate/audit空。原目标(-6,0) first installed:9075 sleeping=true/retry0，wake:9133 retry0；9853 reserve仍retry0，9854实际本地接受wire后才retry29999，response request708→injected opaque4427→10020 COLUMN_APPLIED→10037 COLUMN_READY。后续恢复还有实际UNCHANGED request2280→owner-committed/injected→ACK/READY（21935–22007），不是只测FULL。四个完整恢复门turn/move/esc/sprint分别4571/4199/4144/4102ms，visiblePending全0，同连接/持输入/近实体均通过；原10秒门未改。Neo实际恢复待T4，旧场无原retryAt的归因限制继续保留。

R12实际Neo sleepresume_1211_neo_r12stable1也PASS、HasFail=false、gate/audit空：目标(-6,0)在睡眠安装时retry0，wake后发送request729，经实际注入、ACK、COLUMN_READY；turn/move/esc/sprint完整可见恢复分别4653/4199/4042/4143ms，visiblePending全0且同连接/输入/近实体检查通过。R12新源码已取得双anchor检查与双版真实sleepresume闭环，原失败/首轮初始化失败及唯一归因限制全部保留。后续终版positive、换维/移动光照和性能继续由T4独占。

## Forge halo 尾后 LIGHT 专用验收 fixture（R13）

R12终版双版pullorder的FULL/DELTA两端内容均正确，资源正常归零；但Forge没有命中现有仅`!pending && lightCurrent`才打印的phase日志，不能把普通LIGHT完成算成尾后阶段覆盖。Forge观察开启后15条DELTA仍0phase，已对齐的halo(9,-6) LIGHT在9580/9620，wrapper finish才9654；Neo(-6,-9)则明确finish12580→admitted13982→同identity engine-complete14011，后者是真覆盖。普通双版ovdlight PASS仅计移动光照证据，不代替该阶段。

新增独立Forge-only `pullorderhalo`场景/profile，普通pullorder不改。双startup smoke/dev gate、精确场景名、平台名Forge全部满足才启用干预；仅远端tag不可触发。真实B和sleep COLUMN_READY两柱恢复后，专用haloArm启动15秒总deadline，再选择第一个真实非交付halo DELTA的已提交内容，避免选入睡前将被正常淘汰的work。身份为完整key、opaque、server实例/generation、committed baseline、真实level/connection；不伪造DELTA或尾部。无真实halo DELTA即precondition FAIL。

新HaloLightProbe最多保留一个work身份，完成/失败清world引用；capture位于fresh content提交且原injected绑定后。只有这个work在创建LIGHT task之前被fixture非阻塞暂缓，复用现有pendingDeltas.putIfAbsent及SnapshotContentCommit的50ms cooldown；重试不再写内容、不重置INIT或epoch，槽已被新work占则明确取消旧fixture、停止defer，绝不覆盖新work。没有owner等待、持chunk/light锁等待或绕过3×3门。

尾部观察只来自普通wrapper两处实际finishDrain成功（原opaque），或真实匹配sleep COLUMN_READY之后的原shadow队尾publish（原lightVersion）；不能poll done或从draining冒认完成。tailDone、pending=false、acceptLight=true且全identity当前，才放回原DELTA/GATE LIGHT路径，记录同identity admitted和实际原版engine future completeLight。新request/opaque、server/gen/baseline/level/conn变化或15秒截止均失败并先撤fixture defer，正常生产继续。场景仍包含原两柱FULL/DELTA内容、全局/目标/字节稳定0硬门，另外haloPhases==1且repair0/expire0。

新增HaloLightProbeTest七项：实际Minecraft owner队列+ColumnSnapshotFence+SnapshotContentCommit安排真实tail先finish再放阶段，内容applyOnce=1、预算归零；draining未完成拒冒尾；邻柱/旧token/异baseline/代际/连接不能借用事件；生命周期、token和新retry槽取消后不继续fixture delay；无DELTA/15秒晚到engine明确失败；实际owner队列先替换当前baseline，再接原identity的旧CompletableFuture回调，必须取消而非完成。此处phase为模型，native engine完成仍须新场marker。PullOrderScenarioContractTest解析全部六场，专用场缺haloArm或halo等待、阶段在trigger前、删全局稳定门均在游戏前失败。测试未由本代理运行，源码diffcheck通过，无美元符插值字面量。

建议T4两anchor聚焦：

```pwsh
.\gradlew.bat common:test "-Pmc_ver=1.20.1" --tests '*HaloLightProbeTest' --tests '*PullOrderScenarioContractTest' --tests '*DeltaLightPhaseTest' --tests '*SnapshotContentCommitTest' --tests '*ColumnSnapshotFenceTest' --tests '*PullOrderProbeScopeTest'
.\gradlew.bat common:test "-Pmc_ver=1.21.1" --tests '*HaloLightProbeTest' --tests '*PullOrderScenarioContractTest' --tests '*DeltaLightPhaseTest' --tests '*SnapshotContentCommitTest' --tests '*ColumnSnapshotFenceTest' --tests '*PullOrderProbeScopeTest'
.\scripts\runtime-smoke-test.ps1 -Ver 1.20.1 -Loader forge -Phase I -Scenario pullorderhalo -CleanWorld -Vd1 8 -Vd2 8 -ClientRenderDistance 8 -SessionId 'pullorderhalo_1201_forge_r13stable1'
```

相关双loader编译与scan后再运行，继续T4唯一窗口。要求`halo-phase armed→content-committed→deferred-light→tail-finished→released-light→admitted→engine-complete`同identity，最后haloPhases1、全资源0/repair0；任一步缺失或超时FAIL，不能仅按进程退出/普通LIGHT成功验收。实际首个halo坐标以捕获marker为准，不承诺自然走固定(-6,-9)。新增/扩展路径：client/scenario/HaloLightProbe、HaloLightProbeTest、PullOrderScenarioContractTest、ScenarioEngine数值key、compat/PullOrderingProbeCompat、ShadowLightCompute专用fixture hook、PullSnapshotTailClient与SleepTerrainClient真尾观察、新scenario/profile。无新协议/MC_VER分支，不改T1门/确认算法或T3保存锁。

R13首轮源码复审拒绝了一条fixture假通过边界：仅按future事件携带的旧inf.chunk构造identity，admitted之后当前注入对象被替换仍可能给旧future记complete。现仅HALO专属阶段回调非阻塞排到实际shadow owner；严格owner检查后重新取当前registry、generation、injected baseline、level/connection及token凭据，再在同一metadata决策内validate与phase。baseline替换和阶段验收在同一owner串行，事件旧对象不能代替当前注入表；不在LIGHT mutex内等待，不改变普通运行/光照门。修订候选已重新交独立复审，未启动构建/游戏。

R13定点修订已获独立源码复审通过，review.md关闭该阻塞；已正式唤T4运行上述两anchor聚焦检查、相关编译/scan和Forge专用场，源码冻结。此处只记录源码审查，不预报实际native LIGHT、15秒总界或资源/repair门已通过。

R13最终T4检查与Forge定向场现已PASS：两anchor聚焦、Forge/Neo compile与scan均通过；pullorderhalo_1201_forge_r13stable1的真实key=4503599358935031、token4201、server999498769/gen1、baseline1559258994、level1379373724、connection1443367938在06:12:25–27完成同身份content-committed→deferred-light(pending=true)→实际tail-finished(ACK/done/publish=true、pending=false、lightCurrent=true)→released→admitted→engine-complete，15秒总界内。haloPhases1、FULL/DELTA两端diamond与BE正确、250ms稳定目标/global/bytes0、repair/expire0，gate/audit空。归档build/analysis/spark-optimization-20261023/pullorderhalo_1201_forge_r13stable1/含完整结果/marker/manifest/checksums及配置/字典/cache恢复hash。

最终冻结v3八臂的只读性能处置见[perf-disposition.md](perf-disposition.md)。没有修改最终源码/JAR或追加运行；当前没有可证新增Pull/hash/快照必修热点，但明确保留Forge主线程CPU、Forge FULL GATE p95与Neo SERVER_QUEUE p95上升，不以其他范围重叠或loop P99改善抹除回退。Java4ms采样、每组仅两次重复及累计阶段包含warmup的因果限制保留。
