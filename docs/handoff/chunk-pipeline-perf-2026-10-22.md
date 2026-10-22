# 区块链路计时、取批与额度检查

分支：`2.0.0`，基准 HEAD：`d6769d80`。本轮按授权先接入计时，保留计时版基线，再修取批及服务端快照额度检查。

前半保留性能对照时的历史结果；随后已修复方块实体错误，以及 SeedGen 切换到纯缓存时的结构注册表错误，详见文末两节。

## 实现

- `ChunkPipelineProfile`：使用独立 JVM 参数 `-Dhassium.chunkProfile=true`，不依赖逐包日志。默认关闭，关闭时诊断方法在取锁前返回。固定桶直方图输出累计 n、mean、p50、p95、p99、max；分位数为桶上界（16 ns 以上相对误差不超过 6.25%）。每类在途表最多 16,384 项，容量淘汰单独计数。
- 客户端工作项携带不重复使用的诊断编号；状态含会话代际、维度、柱位置、来源。旧工作项回调使用自己的编号，不按“当前位置最新请求”结算。断连/切维清理旧代状态。
- 拆分请求往返、解码排队/执行、影子摄入排队、邻柱门等待、LIGHT future 墙钟、打包排队/执行、ready 等待、apply 墙钟、首次观察到 mesh 编译回调。服务端拆分排队、hash 比较、NBT/DELTA/FULL 快照、编码排队/执行及发回主线程等待；双端只使用各自的单调时钟。
- `fillDistanceFirst` 在名额截断之前应用资格谓词。保留脚下柱优先及其他柱的表迭代顺序；pending、generated、delta 的取批和重新唤醒使用一致条件，处理时仍复检并条件移除。
- `comparePull` 只比较 hash；`preparePull` 只能在额度准入后调用。UNCHANGED NBT 超限时，FULL 回退再次检查 FULL 额度。已准入 DELTA 仍准备原有 FULL 回退包，不跨 tick 缓存快照。
- `LightNeighborhoodGate`、`ShadowTrackingSession` 的光照门及 acquire 额度逻辑均未修改。

直方图设计参考本地 XERJ 检索的 [Fortio](https://github.com/fortio/fortio)，revision `67ce9fa2a3a6`，`stats/stats.go:307`（Apache-2.0）；本项目采用固定对数桶，没有复制其实现。

## 计时解释

`INGEST_APPLY` 从本次影子工作项创建开始；官方已构建包入口从进入 ready 队列开始，不包含更早的读盘/生成过程。`FIRST_MESH` 是 apply 后首次观察到的编译回调，不证明全部 section 已编译，也不排除先前排队的编译任务。`LIGHT_WALL` 含引擎内部异步等待，不等于 CPU 用时。`DRAIN_INTERVAL` 是 drain 调用间隔，本项目调用点在客户端 tick，不能当作渲染帧时间或 FPS。

`FLOW_ABANDONED` 包括被替换、卸载、没有继续交付或等待 mesh 的诊断状态，不是丢块计数。`ELIGIBLE_AFTER_EMPTY` 在空批后重新扫描，因此可能包含取批后新到达工作的正常竞态。日志按进程/代际累计；不同代际的分位数不可求平均。

## 对照方法

使用现存 `ovdlight`，同版本前后均重新生成 seed=42 的测试世界并清空客户端缓存。服务端 VD=8/6、客户端 RD=12、每 tick FULL/DELTA 额度=5、UNCHANGED 额度=32；分段增量、SeedGen、lightStrip 开启，sleep 关闭。测试串行运行。保留该场景原有 debug 日志，不能把这里的耗时直接等同于关闭诊断后的生产耗时。

首个 Forge 尝试 `chunkperf_base_1201f` 被分析器以 `MOVEMENT_NOT_EXECUTED` 拒绝：失焦暂停菜单清掉了模拟按键。飞行步骤已复用现有 INPUT 测试的无人值守处理（关闭失焦暂停、关闭 PauseScreen、释放鼠标），未修改玩家生产输入逻辑。有效基线和修复版均使用修正后的场景执行器。

| 版本 | 计时版基线 | 修复版 |
|---|---|---|
| 1.20.1 Forge | `chunkperf_base2_1201f`：PASS | `chunkperf_fixed_1201f`：PASS |
| 1.21.1 NeoForge | `chunkperf_base_1211n`：FAIL，见下文 | `chunkperf_fixed_1211n`：光照检查通过，整体 FAIL（缓存方块实体错误） |

每次包含两段约 686 格实际飞行、屋檐/洞口连续采样及重连。自动探针不替代人工画面验收。

## 已取得的测量

| 1.20.1 Forge | 基线 | 修复版 |
|---|---:|---:|
| FULL 回退快照次数 | 4,045 | 1,581 |
| 编码执行次数 | 1,596 | 1,581 |
| 快照次数减编码次数 | 2,449 | 0 |
| DELTA 快照次数 | 4,038 | 1,567 |
| 两轮空批后检测到可执行工作的次数 | 22,737 | 6 |
| 服务端 tick p95（ms） | 13.107 | 11.534 |
| 客户端 tick p95，首轮/重连（ms） | 6.554 / 3.801 | 6.554 / 3.932 |
| DELTA 摄入→apply p95，首轮/重连（ms） | 503.316 / 243.270 | 419.430 / 301.990 |

一对场景的样本量及调度会变化；可确认的是重复快照和空转大幅减少，不能声称所有尾延迟或 FPS 都改善。

| 1.21.1 NeoForge | 基线 | 修复版 |
|---|---:|---:|
| FULL 回退快照次数 | 5,413 | 1,642 |
| 编码执行次数 | 1,680 | 1,642 |
| 快照次数减编码次数 | 3,733 | 0 |
| DELTA 快照次数 | 5,399 | 1,635 |
| 两轮空批后检测到可执行工作的次数 | 2,933 | 8 |
| 服务端 tick p95（ms） | 12.583 | 12.059 |
| 客户端 tick p95，首轮/重连（ms） | 6.291 / 3.932 | 7.078 / 4.063 |
| DELTA 摄入→apply p95，首轮/重连（ms） | 671.089 / 268.435 | 872.415 / 335.544 |

NeoForge 计时版基线首轮有 7 次屋檐 sky 异常采样（首例 `0,299,169 light=7`），修复版两轮此项均为 0。基线重连读缓存时出现 3 个 NBT 与方块状态不匹配错误：beehive 对应 air，brushable_block 对应 water，mob_spawner 对应 cobweb；修复版仍有 1 条 brushable_block 错误，因此不能标记整体验收通过。错误链为 `ChunkSerializer → runPostLoad → ShadowSeedServer.parseNbtBytes/loadFromDisk`。这类错误在两项优化之前已被观测到；没有豁免错误，也没有修改存储路径。样本不能证明该缓存问题的根因或发生概率变化。

对照结束后进一步修正两个诊断端点：`READY_WAIT` 止于首次调用 vanilla handler 前，`SERVER_QUEUE` 止于本次 hash/快照准备开始前。上述对照表没有使用这两个指标；原始 ovdlight 日志中的旧口径分别包含本次 apply、hash/快照时间，不应与最终版本的纯等待时间混用。随后在最终版本上执行 NBT 场景，关闭逐包日志验证 profiling 独立性。

最终版本的 `cachenbt` 补充验证：

| 版本 | SessionId | 结果 |
|---|---|---|
| 1.20.1 Forge | `chunkperf_nbt_1201f` | PASS，日志审计无错误 |
| 1.21.1 NeoForge | `chunkperf_nbt_1211n` | PASS，日志审计无错误 |

两组均覆盖写入后重连、UNCHANGED NBT 恢复和空快照修复。客户端分别输出 157 / 143 行 `[CHUNK-PROFILE]`，逐 pull 发送日志均为 0；`networkLogging/asyncLogging/chunkApplyLogging/lightLogging` 已在这些测试的配置中关闭。该结果不抵消 NeoForge 的 OVD 缓存重读失败；后者仍需要单独定位。

## 复现与证据

```powershell
$env:JAVA_TOOL_OPTIONS = "$env:JAVA_TOOL_OPTIONS -Dhassium.chunkProfile=true"
.\scripts\runtime-smoke-test.ps1 -Ver 1.20.1 -Loader forge -Phase I -SessionId <unique-id> -Scenario ovdlight -Offline -DelayMs 10000 -JoinTimeoutMs 180000 -ClientTimeoutSec 600
.\scripts\runtime-smoke-test.ps1 -Ver 1.21.1 -Loader neoforge -Phase I -SessionId <unique-id> -Scenario ovdlight -Offline -DelayMs 10000 -JoinTimeoutMs 180000 -ClientTimeoutSec 600
# NBT 补充验证：上述命令改为 -Scenario cachenbt，并添加：
# -Vd1 8 -Vd2 6 -ClientRenderDistance 12
python scripts/analyze-chunk-profile.py 'build/smoke-test/logs/*chunkperf*.log' > build/chunk-profile/report.json
```

使用完后恢复 `JAVA_TOOL_OPTIONS` 原值。原始日志在 `build/smoke-test/logs/`；结果与探针分别在 `build/smoke-test/results/`、`build/smoke-test/probe/<SessionId>/`。计时版源文件及 SHA-256 清单、补丁、配置副本和提取结果保存在 `build/chunk-profile/`（构建目录，不进入版本控制）。

编译/测试命令：

```powershell
.\gradlew.bat common:compileJava forge:compileJava common:test scanVersionBoundaries "-Pmc_ver=1.20.1" --offline
.\gradlew.bat common:compileJava neoforge:compileJava common:test "-Pmc_ver=1.21.1" --offline
```

最终测试同样启用 `JAVA_TOOL_OPTIONS=-Dhassium.chunkProfile=true`。两者通过：1.20.1 共 524 项、1.21.1 共 517 项，失败及跳过均为 0。新增用例验证直方图边界/分位数、容量上限、替换与旧会话回调隔离、被阻塞前缀不占批次名额、脚下优先、队列重叠与在途资格、37 次比较只调用 5 次快照供应器，以及 NBT 超限回退的额度边界。

## 后续：缓存方块实体错误修复（2026-10-22）

用户随后授权定位并修复上述缓存错误。确定性回归证明了遗漏：1.20.1 / 1.21.1 的 `LevelChunk.removeBlockEntity` 只有在 `loaded || level.isClientSide` 时才删除 live BE 表。影子柱是服务端 LevelChunk，通常不设 loaded；原有 DELTA 替换 section 后逐个调用 removeBlockEntity，看似清理了旧实体，实际 live 表仍可能保留旧实例。原版保存会继续序列化它，重连时 `runPostLoad → BlockEntity.loadStatic` 再拿新地形校验，就出现 brushable_block 对应 water、beehive 对应 air 等错误。此前的落盘清理只检查 pending NBT，没有覆盖 live 表。

修复内容：

- `MixinShadowBlockEntitySnapshots` 在 vanilla remove 的 RETURN，仅对所属服务器为 `ShadowSeedServer` 的柱补齐 live/pending 删除并标记旧实例 removed；先保留 vanilla 的 ticker/listener 清理。普通真实服务器和客户端不进入这段补偿。
- DELTA 改用已有的 `ShadowBlockEntityCompat.applySnapshot`，与 UNCHANGED NBT 共用整柱快照规则：先验证所有位置/类型，删除不在快照中的旧实体，清空旧 pending，再应用新类型和 NBT。保留类型匹配的现有实例，并更新其 BlockState。
- 旧缓存读盘在 postLoad 之前，对照已解码的方块层过滤不相容/越柱 NBT。正常柱不重复解析；仅修复过的柱再解析一次，让会复制 BE 列表的后续版本也使用清理后的回调。合法完整 NBT 与带 `hassium:client_nbt` 的投影不改写，修复柱标 content dirty，沿原流程落盘，并记录 WARN 修复计数。方块层及 chunkHash 不因此改变。
- DUMMY NBT 仅在当前方块确实有 BE 时保留；继续复用已有注册表类型校验。`CacheNbtProbeCompat` 增加影子 live/pending 表与地形一致性的运行时断言。
- 不改 type 126 布局、不删除整个缓存、不增加锁、不更改 `LightNeighborhoodGate` 或 tracking 交付条件。

社区参考使用 XERJ 中已加载的 [Bobby](https://github.com/Johni0702/bobby)，revision `241f973106ee4e5552a5950d0a12433c07921c93`，`FakeChunk.java:74` 和 `ChunkSerializer.java:70`。其冻结缓存副本、独立保存 BE 快照的思路用于核对保存边界；源码为 LGPL，仅作设计参考，未复制代码。

新增 `ShadowBlockEntityReplacementTest` 共 7 项。修复前空快照删除和同位置换类型两项失败，原版 detached 删除行为复现用例通过；修复后全通过。最终两版完整单测分别 531 / 524 项，失败、错误、跳过均为 0。七锚点 common 编译及两重点 loader 编译、`scanVersionBoundaries` 通过。

运行时验证记录在 `build/smoke-test/results/result_cachebe_*.json`；编译与红/绿测试日志在 `build/chunk-profile/be-*.log`。旧缓存故障注入仅操作已经停止的 `forge|neoforge/run/client/hassium_cache` 冒烟缓存：向 `(0,0)` 柱空气位置 `(1,319,1)` 增加一个 brushable_block NBT，保持地形字节和 hash 不变。原 region、注入元数据和复现脚本保存在 `build/chunk-profile/cachebe-*-before-orphan.mca`、`cachebe-*-orphan.json`、`inject-cache-orphan.py`，不操作真实用户存档。

| 修复后场景 | 1.20.1 Forge | 1.21.1 NeoForge |
|---|---|---|
| 原 ovdlight 冷缓存、飞行、重连 | `cachebe_fixed_1201f`：PASS | `cachebe_fixed_1211n`：PASS |
| NBT 数据保留、删除、类型替换及影子状态检查 | `cachebe_nbt_clean_1201f`：PASS，标准 cachenbt 冷缓存 | `cachebe_nbt_seed_1211n_b`：PASS，SeedGen 保持开启、热复用故障缓存 |

两版的两轮移动采样均没有缺地板、屋檐天空漏光或暗光源；日志审计失败列表均为空。两版 R2 均保留了分析器的非阻断 `SPATIAL_SNAPSHOT_INCOMPLETE` 警告（斜向两点），没有放宽或修改该门禁；这里只报告自动探针结果，不替代人工画面验收。

两组 NBT 场景均无分析器警告、无日志审计失败，R1/R2/R3 的 `invalidEntities` 均为 0。NeoForge 故障注入场在读盘时明确记录 `repaired 1 inconsistent cached block entities at [0, 0]`；退出后重新解压实际 region，故障标记由 1 变为 0，合法 BE 数为 4。该场临时把 cachenbt 的 `chunk.seedGenEnabled` 设为 true，以保持缓存生成与读取模式一致；profile 文件已原样恢复。修复过的 Forge 故障缓存也验证了相同的落盘结果，但其跨模式场整体失败，见下文；随后标准冷缓存场另行通过。

最终四个有效场次的精简结果见 `build/chunk-profile/cachebe-final-results.json`。NBT 验证的共同参数为 `-Scenario cachenbt -Offline -DelayMs 10000 -Vd1 8 -Vd2 6 -ClientRenderDistance 12 -JoinTimeoutMs 180000 -ClientTimeoutSec 600`；NeoForge 额外使用 `-WarmRepeat` 和上面的 SeedGen profile 变体。未追加任何日志豁免。

保留的补充测试失败：

- `cachebe_nbt_1201f`：故障 NBT 修复成功，R1/R2/R3 影子 live/pending 不匹配数均为 0，落盘故障标记从 1 变为 0，合法 BE 数为 4。但本场将 SeedGen 缓存热复用到默认关闭 SeedGen 的 cachenbt 配置，出现两条 `Unknown structure start`（mineshaft / ocean_ruin_cold）错误，**整体 FAIL**。这是额外发现的跨模式结构注册表问题，本次不修改该路径、不豁免日志，也不把此场算作通过。
- `cachebe_nbt_seed_1211n`：没有进入游戏。另一版本的编译与该冒烟启动编译重叠，Manifold 读取到不匹配的版本定义而编译失败；该场无运行时结论。后续全部构建与冒烟启动串行，缓存故障标记仍为 1，未被提前消费。

## 后续：纯缓存结构 NBT 透传（2026-10-22）

用户确认纯缓存不需要识别结构 ID，并授权跳过结构解析、保留原始 `structures` NBT，验证 1.20.1 Forge / 1.21.1 NeoForge 的两向切换。

实现沿用轻量注册表装配，不加载世界生成数据包：

- `ShadowServerCompat.parseChunkNbtUnlocked` 按所属影子端实例的 `isCacheOnly` 选择读入视图。纯缓存仅复制根 CompoundTag，排除 `structures` 后交给原版；sections、BE、生物群系、光照等仍使用原生读链。原始 structures 未被删除或改写；SeedGen 和普通世界使用原输入。
- `ShadowStructureDataAccess` 将原始 Tag 的副本附在 ChunkAccess 上，Imposter 使用被包装的 LevelChunk。Proto→FULL、权威 FULL 替换均传递这份数据，不增加全局坐标缓存或锁。
- 原版 `ChunkSerializer.write` RETURN 写回保留的原始 NBT，覆盖 holder 保存与影子存储保存两条入口；1.21.2 起在 `SerializableChunkData.copyOf` 捕获独立副本，再在 write RETURN 写回。未知 ID、额外字段、数组和类型均保留，不实例化结构。
- 光照邻柱门禁、tracking 发布条件、type 126 布局和 chunkHash 语义未调整。仅 `cachemodes` 开发场景输出结构读写证据；正常运行不输出这些探针日志。

社区参考沿用 XERJ 已加载的 Bobby，revision `241f973106ee4e5552a5950d0a12433c07921c93`，`FakeChunk.java:23` 独立保留序列化数据的做法；已查 LGPL 许可，仅参考设计，未复制实现。

新增 7 项 `ShadowStructureDataCompatTest`：纯缓存读入视图隔离、SeedGen 原生读入、缺省结构数据、未知 ID/额外字段透传及输入/输出隔离、Imposter/晋升/替换、异步保存副本独立性、空结构与原生保存兼容。完整单测 1.20.1 为 **538** 项、1.21.1 为 **531** 项，失败/错误/跳过均为 0（启用 `-Dhassium.chunkProfile=true`）；Python 冒烟分析器 **44** 项通过。

七个 common 编译锚点 `1.20.1 / 1.21.1 / 1.21.2 / 1.21.5 / 1.21.6 / 1.21.9 / 1.21.11` 全部通过；另通过 1.20.1 Forge、1.21.1 NeoForge、1.21.11 Fabric 编译及 `scanVersionBoundaries`。构建与冒烟启动均串行。日志为 `build/chunk-profile/structures-{1201,1211}-final.log` 和 `structures-compile-<version>.log`，最终 `git diff --check` 通过。

运行时使用新增 `cachemodes` 场景：同一个自然世界、同一份客户端磁盘缓存，依次开启 SeedGen、关闭 SeedGen、再开启 SeedGen。每阶段独立启动客户端，并在阶段内部再断开/重连一次，往返飞过 Y297 地板、屋檐和洞口。参数为 `-Offline -DelayMs 10000 -Vd1 8 -Vd2 6 -ClientRenderDistance 12 -JoinTimeoutMs 180000 -ClientTimeoutSec 600`；首场 `-CleanWorld`，后两场 `-WarmRepeat`。本专项关闭 OVD，保留移动采样、统计公式、区块内容与光照检查；允许首轮已有磁盘缓存，`dump coldCache=false` 仅排除“清缓存首进不应全命中”的断言，其他场景默认仍为 true。

| 阶段 | 1.20.1 Forge | 1.21.1 NeoForge |
|---|---|---|
| SeedGen 建立基线 | `structures_1201f_on1`：PASS | `structures_1211n_on1b`：PASS |
| 纯缓存热复用 | `structures_1201f_off`：PASS | `structures_1211n_off`：PASS |
| 切回 SeedGen | `structures_1201f_on2b`：PASS | `structures_1211n_on2`：PASS |

`scripts/smoke/inspect-cache-structures.py` 只读已停止的冒烟缓存，解压 type 126 并计算包含 NBT 类型、未知字段和数组的规范化摘要（仅忽略 Compound 的键排列顺序）。Forge 从 SeedGen 切到纯缓存后，**567** 个带结构数据的柱全部保留（31 柱有 starts，549 柱有 References，二者有重叠），缺失 0、摘要变化 0；其中 **231** 柱确实重写了区块 NBT，但结构摘要仍相同。原始清单、比较结果在 `build/chunk-profile/structures-1201f-{on1,off,on2b}.json`。切回 SeedGen 后结构 ID 重新由原版解析；该阶段允许正常生成/序列化更新元数据，不要求摘要保持冻结。

NeoForge 同样保留全部 **622** 个结构柱（36 柱有 starts，599 柱有 References），缺失 0、摘要变化 0；**318** 柱实际重写后结构摘要仍相同。覆盖的结构含 `minecraft:trial_chambers`。清单在 `build/chunk-profile/structures-1211n-{on1,off,on2}.json`。

六个有效场次全部 PASS，日志审计失败、分析器失败/警告、未知结构起点/引用日志均为 0。12 轮移动采样的地板缺失、屋檐/洞口天光异常、光源变暗及定点方块不匹配均为 0。纯缓存两版分别记录 898 / 884 次带结构 NBT 的读入、745 / 1032 次透传保存，原版结构表均为空；切回 SeedGen 后分别记录 889 / 892 次正常结构解析。计数包含同柱重复读写；磁盘去重口径为上面的 567 / 622 柱。汇总为 `build/chunk-profile/structures-mode-results.json`。本轮报告自动探针结果。

本轮保留的失败场：

- `structures_1201f_on2`：结构解析与两轮光照检查正常，R1 因沿用冷缓存来源断言失败（fullReq=0、cacheHitFull=1424 是本场合法热命中）。增加上述显式场景参数后重跑为 `on2b`，未增加错误日志豁免。
- `structures_1211n_on1`：客户端连接初始化的 Mojang 屏蔽列表请求 `Premature EOF`，没有进入世界。记录 FAIL 后仅停止该场已确认 PID 的测试客户端，再用新会话重启重试。
