# 交接文档：客户端区块数据流对齐（统一 Compare+Pull）

> 状态基线：`feature/vanilla-direct-network` @ `109ba1b`（2026-09-05）。
> 目标真相源：[`architecture.md`](architecture.md) §6「客户端区块数据流」。
> 本文回答三个问题：**哪些已完成不许再动**、**现行过渡链路有哪些（对齐后要清理什么）**、**对齐开发怎么做、怎么验收**。

## 0. 2026-09-05 追加：R1 半径 / R2 虚空定位与修复（pull8 实证）

pull8 冒烟（`vdn_1_20_1_fabric_I_pull8`）双端日志定位出两个与 §3 清单无关的根因：

- **R1 只有 836 柱（vs 旧推送 1529）**：1.20.1 `placeNewPlayer` 把 viewDistance 只放在
  `ClientboundLoginPacket.chunkRadius`，join 时不发 `SetChunkCacheRadius` →
  `ShadowTrackingSession.serverViewDistance` 恒 -1 → 影子 tracking 半径回落默认 11 →
  选柱集合 836（客户端日志 `creating virtual player (serverRadius=-1)`、服务端 8 请求 = 836 条目）。
  **不是吞吐问题**：836 请求在 1s 内全部发出并在窗口内全部应用。
  **修复**：`MixinClientPacketListener.hassium$onLogin` 在 `onLogin()`（会话 reset）之后补
  `setServerViewDistance(packet.chunkRadius())`（1.20.1 与 1.21.1+ 登录包都带 chunkRadius，零 `#if`）；
  半径晚到/变化由影子主循环 `applyViewDistanceIfChanged` 补应用（`ChunkMap.setViewDistance`
  自带全 holder 重跟踪）。
- **R2 零请求（虚空）**：R1 worldgen 压制柱的悬置 load future 永不完成 → ChunkMap holder
  永卡 EMPTY（数据只进注入表）。R2 新虚拟玩家对这批柱：holder 已存在 → 无新
  `scheduleChunkLoad`；`getTickingChunk()` 为 null → move 的 `updateChunkTracking` 不触发
  `playerLoadedChunk` 桥 → 比对请求永不产生（服务端 R2 期间零 `[SHADOW_PULL] server request`）。
  **修复**：`ShadowChunkMapCompat` 悬置 future 登记 + `completeSuspendedLoad`——
  `ShadowSeedServer.injectChunk` / `injectLoadedChunk` 数据到位时以 Imposter 放行，
  原版链恢复 LIGHT→FULL 推进，R2 靠 move 转换重新触达桥（UNCHANGED → `publishCachedChunk`
  重交付 / FULL → 覆盖）；park 时 `clearSuspendedLoads`。
- **加固**：`ShadowTrackingSession.reset()` 的 vanilla 移除改为影子主循环线程执行
  （此前在客户端主线程直呼 `PlayerList.remove`，与影子簿记并发可自毁主循环）。
- **待推送队列（2026-09-05 用户拍板）**：`resolveShadowPull` 对未就绪柱（生成/算光中）
  不再阻塞主线程也不报错——登记 `ServerChunkPushManager.pendingPulls` 待推送队列，
  入队即按需加载（`TicketType.FORCED` FULL 票，引用计数，推送/失败后释放），
  `onServerTick` 泵就绪即主动推送（复用原 requestId，单 tick ≤128）；仅在柱已不在
  加载列表（vanilla 卸载/跑出视距）或超时（15s）才回失败，客户端对 ERROR **不重试**
  （终态拒绝重试只会复现，pull9 风暴实证）。范围校验改逐条目
  （`ShadowPullRequestValidator.inRange`），整批拒绝会牵连同批在范围内柱。
- **冒烟进展（pull8→pull12，1.20.1 fabric classic）**：R1 landed 836→1208→1268
  （目标 1529）、R2 489/489 全缓存重交付（新增 0、push 0，虚空修复）、全程无风暴。

## 0.1 2026-09-07 追加：R1 剩余缺口的实测归因与半径补环（bootgrid 系列实证）

对 pull12 起的 1268 vs 1529 残差的几何活检（`.Probe.Round1` 探针 + 空间快照）推翻早前
「range 拒绝为主」的判断——连续三轮（pull12 基线 / bootgrid1 / bootgrid2）得到**完全相同**的
轮廓特征，非随机噪声：

- **根因 = 飞行滞环（motion-tail hemisphere），非 range 拒绝**。脚印 = r≤14 chebyshev
  实心方阵（841）+ r15…r22 各环仅 ~36–47% 的半圆弧，空缺集中在下行方向的**身后一侧**；
  r22 之外为零。老推送链路靠服务端宽幅自主 push 兜住了整个圆周，新 PULL 驱动的选择窗口
  跟随移动中心，身后环来不及供给就在会战尾声被判负。
- **半径协调补环（本轮落地，全部编译绿、两轮 smoke PASS）**：
  1. 新增 `common/.../network/ShadowPullRadii.java`：`AUTHORITY_MARGIN=2` 单一真相源；
  2. 五处 loader validator `PlayerCompat.getViewDistance(player) + AUTHORITY_MARGIN`
     （fabric 两分支 / forge / neoforge）与 `ShadowTrackingSession#resolveViewDistance`
     同步 `+AUTHORITY_MARGIN`（shadow 选择窗口 ⊇ 服务端签发环）；
  3. `ShadowTrackingSession` 增设**静态基准光盘**：虚拟玩家坐下瞬间快照 homeChunk，
     以其为心把欧氏半径 r=22 的整圆柱逐环补齐（南北交替混合序 + 25ms 发射闸防洪峰），
     绕过移动窗口不对称：不进影子注入表的格子一律以无基线/FULL 形式进场。
- **净效应**：独特柱 1268 → **1384**（峰值环突破到 r22），全程无风暴；后续圆柱裁角 +
  南北交替 + 间隙闸组合未再抬升（退化至 1270±），指向**下一级瓶颈在服务端幽灵生成
  的供应调度**（绕物理邻近优先的 FORCED-demand 泵），不属于本文会话改动的收敛区间。
- **会话收尾修复与复测（2026-09-07）**：`ShadowTrackingSession` 曾残留未定义符号
  `niOffset` 与孤立 `QQQX` 行（编译红）。已修复：南北交替改为棋盘奇偶 50/50 分桶
  （`((dr+dc)&1)==0`），`reset()` 补清 `repairPool`/`lastRepairSweepNs`（补给池字段当前
  仅声明未接线，属下一级供应调度实验的预留位）。复测 `bootgrid3`（classic，PASS）：
  **R1 landed 1350 / applied 1400**（pull12 基线 1268，+6.5%；bootgrid1 峰值 1384 的
  -2.5% 以内，波动属会话间噪声量级），R2 527 全缓存重交付（newFull=0、push=0），
  全程无风暴、零 ERROR。**1384/1350 双点确认光盘补环增益可复现，残差归因结论不变**。

## 1. 背景与结论速览

冒烟实证（`vdn_1_20_1_fabric_I_final`，1.20.1 fabric classic）：R1 首进时 1529 个区块全部走 **chunk_payload 服务端推送**，shadow pull 零触发；R2 重连（有缓存基线）才出现 436 UNCHANGED + 9 DELTA。这与 §6 的目标态不符——**按文档，无基线的柱也必须进入统一 Compare+Pull（服务端答 FULL）**，实现却在无基线时放行推送包（见 §3.2 门控）。

- 握手、压缩、统计、shadow pull 服务端权威比较等**已完成**（§2），对齐开发不得回退或重构这些。
- 过渡链路**清单化**于 §3，对齐完成后按 §5 清理。
- 开发按 §4 阶段推进；每阶段验收以 §6 门禁为准。

## 2. 已完成项（对齐开发的前置依赖，不许再动）

| 模块 | 状态 | 关键位置 / 提交 |
|------|------|----------------|
| 登录期能力握手 1.20.1（login query） | ✅ | `MixinServerLoginPacketListenerImpl`；**query 已改在 LoginCompression 之后、GameProfile 之前发出**（帧化竞态修复 `483e1fb`，业界依据：Fabric API `ServerLoginNetworkAddon` 先 compression 后 query；Forge 用 NEGOTIATING 状态后置 compression） |
| 登录期能力握手 1.20.2+（配置阶段） | ✅ | `PreHandshakePayload`（loader 注册）；1.20.2+ 原生无此竞态 |
| Play 期激活链 | ✅ | dictionary_sync/index_sync → 聚合 PENDING → `play_init_s2c` → 客户端 ACK → 聚合 ENABLED |
| 通道压缩 | ✅ | 聚合包内部字典 ZSTD（算法固定，未压缩帧 flag=0）；shadow pull DELTA 内嵌分段增量自有 zstd；原版压缩层全程不触碰 |
| shadow pull 服务端权威比较 | ✅ | `ServerChunkPushManager.resolveShadowPull`（UNCHANGED/DELTA/FULL/ERROR）+ `ShadowPullHandler` + `ShadowPullResponseS2CPacket`（FULL=原版线格式；DELTA=内嵌 `SectionDeltaS2CPacket`） |
| 影子端 | ✅ | `ShadowSeedServer`（进程内 MinecraftServer）+ shadow tracking + pre-LIGHT（`ShadowLightCompute` / `ShadowVanillaLightPipeline`） |
| 客户端统计口径 | ✅ | 带宽压缩=聚合包压缩帧+shadow pull 分段增量（原始 vs 压缩后线缆，未压缩帧不计）；流量节省「数据包」=区块域锚点；probe JSON 含 `zstdOriginalBytes/zstdCompressedBytes`（`c419215`） |
| 冒烟门禁 | ✅ | 严重错误白名单含环境性网络噪音（`109ba1b`） |

## 3. 现行过渡链路清单（对齐后清理）

### T1 服务端自主推送（chunk_payload 全量通道）

- **驱动**：服务端自行 tracking 并推送——`ServerChunkPushManager:1011` → `ChunkSender.sendCompressedChunk`（loader 注册的 S2C 通道 `CHUNK_PAYLOAD_S2C`；C2S 侧**不存在**全量请求包，见 §3.4）。
- **接收**：`ClientChunkHandler.handleCompressedChunk:168`（解压 → 影子管线 `submitVisible`）。
- **实证**：final R1 `fullChunkRequestCount=1529`、`chunksDecompressed=1529`、`actualBytesReceived=8.4 MB` 全部来自此通道。
- **目标态**：采集决策权移交客户端影子 tracking；服务端对纳入 pull 集合的柱**停止主动推送**。

### T2 无基线旁路（shadow pull 未触发的直接原因）

- **门控**：`ShadowPullClient.tryInterceptForCompare:116` —— `if (!ShadowLightCompute.hasLocalPullBaseline(...)) return false;`；`ShadowPullClient.handleNativeChunk:130`（注释「缓存存在时由 ShadowPull 取代 FULL，否则保留原版包建立基线」）。
- **基线判定**：`ShadowLightCompute.hasLocalPullBaseline:859` = `ShadowStorageHashes` 登记过 **或** 本会话影子世界已注入该柱。
- **后果**：R1 全新缓存 → 无基线 → 1529 柱全部放行走推送；与 §6「无基线也进 Compare+Pull（服务端答 FULL）」及 stats 口径「新增 = 无本地 baseline FULL」相悖。
- **目标态**：无基线 = **空基线请求**（机制已有：`ShadowPullClient.requestAuthoritativeFull` 即空基线请求，目前仅作重试路径），服务端答 FULL。

### T3 chunk_payload 解压应用链（过渡载体）

- `ClientChunkHandler.handleCompressedChunk` → `ChunkCompressionHandler` → `applyChunkData` / `ShadowVanillaLightPipeline.submitVisible`。
- 目标态下 pull FULL 统一走 `ShadowPullClient` → `ClientChunkHandler.applyShadowPullFull`（已存在）→ vanilla listener → 影子管线；本链路退役。

### T4 统计口径过渡

- `fullChunkRequestCount`（`ShadowLightCompute:565 recordFullChunkRequests`）当前语义 =「影子引擎等待/接受的远端全量分类（新增/过期）」，并非字面 C2S 请求；doc §6/248 行口径下它应恰等于「Compare+Pull 无基线 FULL」。
- `NativeChunkMetrics.recordAppliedFullChunk` 的 server_push 归因在目标态应趋零（服务端不再自主推送整柱）。
- `recordChunkReceived / recordWireBytesReceived` 的 chunk_payload 调用点（`ClientChunkHandler:190-194`）需随通道退役迁移到 pull 链收口。

### T5 已知遗留（不影响对齐，随版本演进处理）

- `classic.profile.properties` 不复位 `chunk.seedGenEnabled`，seedgen 场景残留会带偏 classic 运行（本次已实证；建议 profile 加 `chunk.seedGenEnabled = false`）。
- 登录竞态已修（`483e1fb`）；若再现按该提交描述的帧化时序排查。

## 4. 对齐开发计划

> 原则：影子端 vanilla tracking 是**唯一采集决策者**；query 往返与压缩切换不重叠的握手不变；双端版本差期间旧通道保留（见 §5 兼容期）。

- **P1 影子 tracking 驱动 pull（无基线也发请求）**
  - 影子 tracking 选中柱 → 有基线：携带 `chunkPos+contentHash+sectionHashes+lightGeneration` 比对请求；无基线：空基线请求（复用 `requestAuthoritativeFull` 的批量/限流框架 `MAX_TRACKED_REQUESTS`）。
  - 服务端已有权威比较（resolveShadowPull），空基线必答 FULL——服务端侧基本零改动。
- **P2 服务端推送抑制**
  - `ServerChunkPushManager` 对「客户端已纳入 pull 集合」的柱停止主动 chunk_payload 推送；抑制协商可复用握手能力位（`LoginCaps`/play_init）或动态声明。
  - 保留：原版整柱推送兼容路径（vanilla 客户端 / 旧客户端版本差期间）。
- **P3 接收端收口**
  - pull FULL 统一 `applyShadowPullFull` → 影子管线；`ClientChunkHandler.handleCompressedChunk` 进入退役观察期（仅旧双端组合触达）。
  - 统计锚点迁移：`recordChunkReceived/recordWireBytesReceived` 收口到 pull 链；`serverPushAppliedCount` 目标态趋零。
- **P4 统计与带宽压缩口径联动**
  - 带宽压缩行（聚合包+shadow pull 分段增量）不变；~~pull FULL 响应载荷是原版线格式、无 hassium 压缩，对该行贡献为 0~~
    **（2026-09-05 用户拍板更正：pull FULL 响应载荷走固定 zstd，与包聚合同列通道压缩；解压点记账
    `zstdOriginal/CompressedBytes`，对齐后 R1 类场景该行以 pull FULL 帧为主属预期）。**
  - probe 断言更新（见 §6）。

## 5. 清理清单（对齐完成 + 最低支持版本 ≥ 对齐版本后执行）

- [ ] `ChunkSender` 接口 / `ChunkSenderHolder` / `ServerChunkPushManager:1011` 推送段 / 三 loader 的 `CHUNK_PAYLOAD_S2C` receiver 注册
- [ ] `ClientChunkHandler.handleCompressedChunk` 及仅服务它的 `ChunkCompressionHandler` 分支（wire/vanilla 记账先迁移）
- [ ] `ShadowPullClient.tryInterceptForCompare:116` 与 `handleNativeChunk` 的 `hasLocalPullBaseline` 旁路（收敛为链路可用性判断）
- [ ] 统计：chunk_payload 调用点的 `recordChunkReceived/recordWireBytesReceived` 迁移；`fullChunkRequestCount` 语义对齐 doc 248 行
- [ ] 文档：`architecture.md` §6 从「目标」改标「现状」；本文档归档
- [ ] 兼容期红线：清理前必须确认最低支持客户端/服务端版本均已含对齐改动，否则 chunk_payload 通道不可删

## 6. 验收门

| 层 | 断言 |
|----|------|
| L0 | `common:compileJava common:test` 全绿（mixin AP 校验注入目标） |
| L1 冒烟 | `runtime-smoke-test.ps1` PASS；R1（无基线）`serverPushAppliedCount→0`、区块经 pull FULL 到达；R2（有基线）UNCHANGED/DELTA 占比与现值同量级 |
| probe | `zstdOriginal/CompressedBytes` 仅来自聚合帧+DELTA；`fullChunkRequestCount` ≈ 无基线 FULL 数；`clientApplied==landed` |
| 带宽压缩行 | classic 下仍以聚合帧为主属预期（FULL 无 hassium 压缩）；不得出现 0/0 之外的异常归零 |
| 双端版本差 | 旧客户端连新服务端、新客户端连旧服务端各跑一轮 classic，chunk_payload 兼容路径可达 |

## 7. 运维注意（本次会话实证）

- 长 gradle 任务一律后台执行并等 `BUILD SUCCESSFUL|FAILED`；被 kill 的运行会留下僵尸 daemon 注册项（实测 9 busy），卡启动时 `.\gradlew.bat --stop` 清场。
- `compileAnchors` 为重量级多版本任务，空闲时段单独跑；单版本段改动用 `common:compileJava` 校验即可。
- 冒烟前确认 server/client toml 的 `chunk.seedGenEnabled` 与场景匹配（classic 应为 false）。

## 8. 关键引用

- 提交：`c419215`（统计口径）、`483e1fb`（登录竞态修复）、`109ba1b`（门禁白名单）
- 代码：`ShadowPullClient`（Compare+Pull 客户端边界，两模式）、`ServerChunkPushManager.resolveShadowPull`（权威比较）、`ShadowLightCompute.hasLocalPullBaseline:859`（基线判定）、`MixinClientPacketListener`（vanilla 包三分支入口）、`ClientChunkHandler`（chunk_payload 过渡接收 + pull FULL 应用）
- 协议背景：Fabric API `ServerLoginNetworkAddon`（compression 先行注释）、Forge `HandshakeHandler`（NEGOTIATING 后置 compression）、wiki.vg《Minecraft Forge Handshake》
