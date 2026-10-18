# 客户端与真实服务端隔离：2.x / 3.x 修复与验收

日期：2026-10-18。两个工作区均直接修复；源码、回归测试及验收报告一并本地提交，不推送或发布。修复前证据保留在 [双线对照](handoff-2026-10-18-client-server-isolation-2x-3x-comparison.md)，3.x 首轮审计保留在主工作区。本文记录修复后的行为，旧报告中的行号和测试数量属于修复前基线。

## 修复范围

| 清单 | 修复后的隔离条件 | 两线差异 |
|---|---|---|
| I01 / P1：影子进程标志污染真实世界 | ChunkMap、ChunkCache、ServerPlayer、LevelChunk、权威 hash、worldgen executor 按所属服务器识别；LevelTicks 在 ServerLevel 构造完成时绑定归属；真实主服务器的 tick/stop 才能操作公共网络队列 | 保留 1.20.1 的 playerLoadedChunk、trackChunk、fudgeSpawnLocation、Either future，以及现代各段的描述符、TracingExecutor 和保存 API |
| I01 / P1：RegionFile 读写、早期装配和旧 saver | 装配访问世界前注册精确世界根目录令牌；RegionFile 构造时绑定令牌并保留旧实例引用。存储管理器按令牌所属服务器查找，不查询当前 Registry 实例；普通世界不因影子标志拒读或写 126 | 坐标字段/访问器仍按各分支实现；已有 type 126 兼容读取保留 |
| I01 / P1：资源加载与 POI | WorldLoader 同步调用及两个异步 executor 显式携带作用域，finally 恢复；普通资源加载不裁剪。POI/序列化转入传入 owner 的影子主循环 | 保留 1.20.1 注册表窗口；不新增锁，不改变先 dispatch 后 chunkLock 的次序 |
| I01 / P1：加载器全局 currentServer 槽 | 真实活动服务器存在时不登记影子；以 VarHandle CAS 原子登记空槽、释放仍属于本影子的槽，消除“先检查再写”与正常服务器启动/替换的竞态 | C2ME OpenCL 上下文仍按 MinecraftServer 实例字段保存和释放；不触发加载器真实服启动/停止事件 |
| I01 / P1：装配失败 | 根目录解析、目录注册、存储创建均进入异常清理；失败关闭本轮令牌，按代际清理生命周期状态及 executor | 两线均覆盖 resolveShadowWorldRoot 抛异常 |
| I04、I04b / P1：Pull 准入与会话 | 入口要求真实活动服务器、网络开关、非 memory 连接、PULL_MODE + SHADOW_PULL、存活且连接正常。v1 固定 epoch=0；幂等账本按连接生命周期分配，玩家 epoch 相互独立，断连/停服清理 | Fabric/Forge/NeoForge 共用同一准入入口；保留原线格式，不强加客户端没有的 epoch token |
| I04 / P1：排队和异步回包 | 排队项记录连接及 ServerLevel；泵送重新检查会话、能力、维度、范围，拒绝时释放票。网络停用时也撤销已排队票。异步编码后回所属服务器线程复核再发送 | 各段原版票 API 不变 |
| I05 / P1：压缩与区块接管混用 | ServerNetworkGate.shouldTakeOverChunks 独立判断区块接管；通用压缩或聚合能力不足以压制原版整柱/光照 | 现代保留 NeoForge bundle 检测；1.20.1 dummy 优化和 trackChunk 同用接管门，保留原版滴灌限速 |
| I03 / P2：重复广播与过度抑制 | 没有接管玩家时全部交原版；存在接管玩家时只向其余玩家发送一次并取消原版 body；方块/BE 等非 chunk/light 包始终交原版 | 两线同规则 |
| I02 / P2：光照空层补丁范围 | chunkSource.getLevel() 必须属于 ShadowSeedServer 才替换 null；真实内置服和普通客户端光照返回原值 | 保留历史 DataLayer(2048) 传播哨兵及 Lomka priority=1100，不把它改为 0 |

运行场追加发现并修复两项：

- Fabric 在功能全关时跳过 payload 类型注册、却仍注册客户端 receiver，导致启动崩溃。2.x Forge 的可选通道定义也从同类配置早退中分离。现将结构性注册与运行准入分开；类型和 receiver 正常注册，是否工作由功能/握手门决定。
- play_init 的维度清单回调可绕过 chunk.enabled=false 创建影子。现于 Registry 创建入口、seed/维度回调、异步重建执行前重新检查配置和单人/专用服条件，并核对重建所对应的客户端连接。

C2ME 的无 owner 压缩流入口只在专用服开启存储时接管。物理客户端保留该入口的原版压缩，由已确认目录归属的影子 RegionFile.write 解码、重编码并收编到映像；装配早期映像尚未建立时直接写完整 126 sector，类型守卫阻止递归；普通内置服 IO 不再受影子进程标志影响。该外部 IO 分支多一次解码/编码，属于隔离正确性的取舍。

## 当前功能如何隔离

Java 包名 `shadow` 和 mixins.json 的 `client` 数组不能代替逻辑服务端隔离。公共 Mixin 仍会应用于专用服的通用 Minecraft 类；`client` 数组只限制物理客户端，在同 JVM 的真实内置服上仍须检查 owner。

| 功能 | 专用服 | 单人 / LAN 主机本机 | 远程多人且 chunk.enabled=false |
|---|---|---|---|
| 客户端入口、渲染、客户端区块摄入 | 不运行客户端入口或 client Mixin | 原版 memory 会话，不接管原版区块 | 不启用区块/OVD/SeedGen/影子计算；原版包正常应用 |
| 公共 shadow Mixin | 按实例/目录检查后走原版；专用服独立的 storage.enabled 与网络功能仍按服务端配置工作 | 普通 ServerLevel、LevelTicks、RegionFile 不满足影子归属，影子全局标志即使为 true 也不能改变它们 | 同左；客户端开关不冒充服务端 storage/master 开关 |
| 影子创建和资源裁剪 | 无客户端 gameDir，且 dedicated 门拒绝创建 | singleplayer 门拒绝创建；资源加载不在影子作用域 | 配置门在 Registry 入口和异步回调重复校验 |
| 区块与光照下发 | 仅完整协商接管的远程连接被抑制 | memory 连接恒不接管 | PULL_MODE 未协商时保留整柱与光照，通用聚合可独立工作 |
| 存档格式 | storage.enabled 仅专用服生效；兼容读取已有 126 | 真实世界写原版格式 | 影子不创建；不改服务端的存档策略 |

主配置公共 Mixin 从 31 增至 32，新增 ServerLevel 构造归属绑定；client 数量仍为 2.x 的 12 和 3.x 的 14。新增绑定类及 ShadowTickOwner 接口没有客户端 API 引用。

配置关闭回退场景覆盖的是**关闭后重新连接/启动**。登录协商固定本连接的能力，本文不把热切开关后无需重连的在线协议撤销当作已实现功能。

## 验证规则与复跑

正式回归进入 common:test：实际 Mixin callback 的非影子/影子正反例、普通 region 读写、装配失败清理、显式异步作用域恢复、混合/原版广播、非 Pull 整柱、1.20.1 dummy、请求准入和重连幂等。build/client-isolation-audit 下旧探针仍断言缺陷基线，不能再作为修复验收任务。

新增两种运行场：

```powershell
.\scripts\runtime-smoke-test.ps1 -Ver 26.3 -Loader fabric -Phase I -Scenario fallback -SessionId "isolation_fallback"
.\scripts\runtime-integrated-isolation-test.ps1 -Ver 26.3 -SessionId "isolation_singleplayer"
```

第二条使用该锚点上一次专用服 Fabric 冒烟的独立 parity 世界副本，也可用 -SourceWorld 指定测试夹具。不会打开原存档写入，目标存在时拒绝覆盖；临时配置在 finally 恢复。真实内置服加载直到正常保存退出均故意保持旧进程影子标志，检查 memory、方块/流体 tick 入队、区块内容，以及正常退出后所有非空 region 槽的压缩类型。原版允许留下的零长度未使用 region 不计为损坏。

结果保存在各自工作区 build/smoke-test/results、logs、probe；单人测试副本保留在 fabric/run/client/saves/isolation_<SessionId>。classic/seedgen/dimension profile 显式复位 chunk.enabled=true，避免 fallback 场景污染下一场配置。

本次没有改动 LightNeighborhoodGate、交付窗/halo 形状、pushReady 门、isLightCorrect 完成判据或 authority-acquire 预算。C2ME 外部 IO 用实际回调回归覆盖；本次未加装 C2ME / Lomka JAR 做共装游戏验收。运行场通过不代替移动屋檐/洞口目视；这些不能由当前证据推定。

## 实际验收结果

全版本最终 `common:test` 合计 **4,365 项**，失败 / 错误 / 跳过均为 **0**；两边 `scanVersionBoundaries`、24 个加载器编译组合、`git diff --check` 通过。冒烟 analyzer 单测 3.x **47** 项、2.x **42** 项通过；新单人脚本在 pwsh 7 解析通过。

| 分支 | MC | 单测 | loader 编译 |
|---|---|---:|---|
| 2.x | 1.20.1 | 441 | Fabric / Forge |
| 2.x | 1.21.1、1.21.5、1.21.6、1.21.9 | 各 434 | 各 Fabric / Forge / NeoForge |
| 2.x | 1.21.2、1.21.11 | 各 434 | 各 Fabric / NeoForge |
| 3.x | 26.1.2、26.2、26.3 | 各 440 | 各 Fabric / NeoForge |

游戏运行合计 **25 场 PASS**：13 场 classic 双轮专用服矩阵，加 12 场针对回退、单人、SeedGen、切维及往返飞行的专项。classic 矩阵覆盖公共 Mixin 的各加载器启动与区块交付；后续范围终态回包、加载器槽 CAS、早期外部 IO 回归完善后，重新跑了全版本单测 / 编译，并由专项验证最终源码。

结果路径为对应工作区 `build/smoke-test/results/result_<SessionId>.json`；非单人场另有 `_analysis.json`，运行日志位于 `build/smoke-test/logs`。汇总为两工作区各自的 `build/client-isolation-audit/acceptance-results.json`。

### 3.x 运行记录

| MC / loader | 场景 | SessionId | 结果 |
|---|---|---|---|
| 26.3 / Fabric | classic | `iso3_classic` | PASS |
| 26.1.2 / NeoForge | classic | `iso3_J_nf_classic` | PASS |
| 26.1.2 / Fabric | classic | `iso3_J_f_classic` | PASS |
| 26.2 / Fabric | classic | `iso3_K_f_classic` | PASS |
| 26.2 / NeoForge | classic | `iso3_K_nf_classic` | PASS |
| 26.3 / NeoForge | classic | `iso3_L_nf_classic` | PASS |
| 26.3 / Fabric | fallback | `iso3_L_fallback_accept` | PASS |
| 26.3 / Fabric | seedgen | `iso3_L_seedgen_accept` | PASS |
| 26.3 / Fabric | dimension | `iso3_L_dimension_accept` | PASS |
| 26.3 / Fabric | flyroundtrip | `iso3_L_flyroundtrip_accept` | PASS |
| 26.3 / Fabric | integrated | `iso3_integrated_final` | PASS |

### 2.x 运行记录

| MC / loader | 场景 | SessionId | 结果 |
|---|---|---|---|
| 1.20.1 / Fabric | classic | `iso2_A_classic` | PASS |
| 1.20.1 / Forge | classic | `iso2_A_fg_classic` | PASS |
| 1.21.1 / Fabric | classic | `iso2_D_f_classic` | PASS |
| 1.21.1 / Forge | classic | `iso2_D_fg_classic` | PASS |
| 1.21.1 / NeoForge | classic | `iso2_D_nf_classic` | PASS |
| 1.21.11 / Fabric | classic | `iso2_I_f_classic` | PASS |
| 1.21.11 / NeoForge | classic | `iso2_I_nf_classic` | PASS |
| 1.20.1 / Fabric | integrated | `iso2_A_integrated_final` | PASS |
| 1.21.11 / Fabric | integrated | `iso2_I_integrated_final` | PASS |
| 1.20.1 / Fabric | fallback | `iso2_A_fallback_final` | PASS |
| 1.21.1 / Forge | fallback | `iso2_D_fg_fallback` | PASS |
| 1.21.11 / NeoForge | fallback | `iso2_I_nf_fallback` | PASS |
| 1.20.1 / Forge | seedgen | `iso2_A_fg_seedgen` | PASS |
| 1.21.11 / NeoForge | dimension | `iso2_I_nf_dimension` | PASS |

### 关键判据

- 回退四场（3.x Fabric、2.x Fabric / Forge / NeoForge）均两轮加载真实区块、有非空气内容、Pull=0、shadowRunning=false；日志没有影子创建记录。Forge 1.21.1 场额外将客户端 master.enabled=false，验证客户端区块与网络功能全关时仍能注册并进入原版回退；测试后恢复该文件。
- dimension 沿用专用场景门禁：R1 统计及四轮 joined / dimension / loadedChunks / clientApplied 断言、退出后三维 region 校验。中间轮使用 gate=false，不套用 classic 重连缓存命中口径；2.x 结果中旧字段 Round2Pass=false 不参与此场景判定，客户端 PASS、四轮断言及 analyzer 均通过。原始 JSON 保留该字段和非致命诊断警告。
- 四轮切维验证主世界 → 下界 → 末地 → 主世界，包含旧维度在途请求隔离和退出后三维 region 校验。2.x Forge 1.20.1 SeedGen 场显式 `MoveSeconds=10`，覆盖旧注册表窗口和旧发送钩子的实际移动路径。
- 3.x 往返飞行每段 30 秒，最远离开锚点 **39 柱**，anchorUnloaded / anchorReturned / anchorPresent 均为 true，clientDarkRegressionChunks=**0**。保留 3×3 齐套、窗外邻柱和交付域门禁。

真实单人保存验证：均为 memory 连接、影子实例为 0、模拟进程影子标志从加载保持到保存退出、方块 / 流体 tick 入队各 1；所有非空 region 槽均未写 126。

| MC | SessionId | 检查记录数 | 压缩类型 |
|---|---|---:|---|
| 1.20.1 | `iso2_A_integrated_final` | 4,794 | 全部原版 type 2 |
| 1.21.11 | `iso2_I_integrated_final` | 4,762 | 全部原版 type 2 |
| 26.3 | `iso3_integrated_final` | 4,885 | 全部原版 type 2 |

验收边界：2.x 中间锚点 1.21.2 / 1.21.5 / 1.21.6 / 1.21.9 做全量单测和各自 loader 编译，游戏运行选择 A / D / I；单人自动化使用 Fabric，其他加载器由专用服 / 回退 / 切维与公共回归覆盖。未将未运行的组合写成游戏 PASS。未验证无需重连的在线协议撤销、C2ME / Lomka 共装或屋檐 / 洞口目视。

修复中曾出现 Fabric 全关启动、关闭区块仍创建影子、早期脚本后处理及 fallback analyzer 沿用握手门的失败；对应原始结果 / 日志保留，最终记录使用新 session 实际复跑，没有把失败 JSON 改成 PASS。版本号、发布配置和用户图片 / video 文件未改动，本次仅本地提交，不推送或发布。
