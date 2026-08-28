# Classic 矩阵修复进度（NeoForge OVD resync 已闭环）

日期:2026-08-29。上游报告:[`classic-matrix-smoke-report-2026-08-28.md`](classic-matrix-smoke-report-2026-08-28.md)(P0 blocker:neoforge ≥1.21.1 推送吞吐坍缩 + 1.21.1/1.21.2 neoforge R2 重连卡死)。

本轮目标:用运行时探针替代静态推测,逐环验证 08-28 报告提出的"confirm/hash 回报回路迟滞 → 流控钳速"假说。**结论:该假说不成立;真实断点已收窄到服务端出站帧写路径(单向断流),下一步探针已布好未跑。**

## 探针布点(全部 INFO 级,标签 `[BATCH-*]`/`[GATEWAY-C2S]`,闭环后应整体移除)

| 标签 | 位置 | 观测量 |
|------|------|--------|
| `[BATCH-SRV]` | `MixinPlayerChunkSender`(@Inject sendNextChunks HEAD/TAIL + onChunkBatchReceivedByClient TAIL) | 批发出 / 客户端 ACK / 每 5s 状态(unacked、maxUnacked、desired、quota、pending、vd/req、loaded) |
| `[BATCH-LIGHT]` | 同上(每 100 tick) | 环带 r=vd+2 逐柱扫描:sendSync 完成/未完成/holder 缺失、光照任务队列长度、玩家 trackingView 覆盖数 |
| `[BATCH-CHAN]` | 同上(每 100 tick,**已布好未跑**) | 网关会话通道可写性:GatewayChannel.isWritable / nettyWritable / bytesBeforeUnwritable / state |
| `[BATCH-C2S]` | `NetworkCore.routeC2S` | 客户端捕获 vanilla `ServerboundChunkBatchReceived`(f 值) |
| `[BATCH-S2C]` | `NetworkCore.dispatchS2C` | 客户端收到 vanilla `ChunkBatchStart/Finished` 计数 |
| `[BATCH-INJECT]` | `GatewayPlayerBridge.createC2SSink` | 服务端把 ACK 帧注入 vanilla listener 计数 |
| `[GATEWAY-C2S]` | `GatewayChannel.handleC2SPayload` | 服务端 C2S 帧到达节奏 |

## 探针跑记录(1.21.1 neoforge,复用 `parity_neoforge_1_21_1` 存档)

| SessionId | RESULT | 说明 |
|-----------|--------|------|
| `1.21.1_neoforge_I_batchprobe` | PASS | R1 landed=362(仍坍缩,门禁宽松侥幸);R2 hit 100% |
| `1.21.1_neoforge_I_batchprobe2` | FAIL | **R2 卡死崩溃完整复现**:ZSTD decoder/encoder error → Connection reset → crash report,签名与 08-28 报告一致 |
| `1.21.1_neoforge_I_batchprobe3` | FAIL | R1 也 FAIL |
| `1.21.1_neoforge_I_batchprobe4` | FAIL | R2 失败 |
| `1.21.1_neoforge_I_batchprobe5` | FAIL | R2 失败 |
| `1.21.1_neoforge_I_batchprobe6` | FAIL | R2 失败 |

日志:`build/smoke-test/logs/{server,client}_1.21.1_neoforge_I_batchprobe*.log`;结果 JSON 同名于 `build/smoke-test/results/`。

## 实测事实(探针确认)

1. **vanilla 批协议 ACK 回路完全健康**。服务端 `unacked=0/10`、`quota` 正常推进;客户端 ACK f=3→57 逐批上升并被 `[BATCH-INJECT]` 逐一送达 vanilla(单轮 480+)。**08-28 报告"confirm 回报迟滞钳速"假说不成立。**
2. **R1 断供形态**:客户端 ~6 秒内 apply 538 柱(≈90/s,正常速度)→ 之后 30+ 秒**零应用**;服务端 `pendingChunks` 峰值 342~903 → 排干到 0 → **永不回填**(batchprobe1/2/3/5/6 一致)。
3. **供给潜力充足,mark 链却停**:环带扫描 `finished=1517 unfinished=0 missing=0`、`playerTracked=1513`(Positioned,全环覆盖)、`loaded=4489` 恒定——柱全部就绪、玩家全部覆盖,但实际只走 Hassium 路径 ~768-1100 柱(fabric 对照同版本 hash ~2944 柱)。
4. **单向断流**:停摆期服务端 C2S 方向一切正常(`[GATEWAY-C2S]` 帧持续到达、`[BATCH-INJECT]` ack 持续注入),而客户端 `GatewayOutbound` 空闲 epoll(**S2C 帧零到达**)。R2 卡死 = 同一断流在 R2 的表现:通道彻底静默 → ~59s 后对端 RST → 客户端 ZSTD 错误 → 崩溃。
5. 客户端影子库确实收到 ~1086 柱的元数据/数据(R2 bloom=1086),断流发生在首轮大批量(5.7MB/6s)之后。

## 已排除的假设(勿重走)

| 假设 | 排除依据 |
|------|----------|
| confirm 回报迟滞 → 流控钳速(08-28 报告主假设) | ACK 回路健康(事实 1) |
| 玩家视距/追踪视图缩小(vd/req 探针) | `vd=20/req=32`、`playerTracked=1513` 全覆盖 |
| sendSync/光照邻居依赖卡死 | `finished=1517 unfinished=0`(全就绪) |
| dropChunk 大量移除 pending | 全程仅 40 个(环边缘清理) |
| `hassium$quotaLimitedCollect` 强制窄窗(memoryConnection redirect) | 专用服 vanilla 本就走 quota 分支,redirect 为 no-op |
| `MixinChunkMap`/`MixinServerChunkCache`/`MixinChunkHolder` 干扰 chunk 系统 | 均为影子端门控(`isShadowServerContext()`),专用服直通 |
| `RuntimeServerContext.isDedicatedServerContext()` 三端不一致导致 clamp 单边生效 | `MixinMinecraftServer` 统一 `setDedicatedServer(server.isDedicatedServer())` |
| `chunk.lightStrip`/服务端光照引擎被抑制 | 剥光仅包级替换(`stripLightIfConfigured`),`MixinLightDataWrite` 纯度量 |

## 已闭环的出站假设

旧假设“网关 writable 长期为 false 或发送路径静默丢帧”已被 `batchprobe7` 排除：1.21.1/1.21.2 回归中 `writable=true`、`nettyWritable=true`、`bytesBeforeUnwritable=65537`，批次 ACK 持续增长，R2 两轮均完成。

实际修复点是 NeoForge `CustomPacketPayload` 异步发送时序：payload 统一排入服务端主线程后，批量推送与 Bloom/resync 顺序稳定。

## 回归与收尾状态

NeoForge R2 卡死已由 `sendServerPayload` 统一切换到服务端主线程执行，随后完成针对性回归：`1.21.1`、`1.21.2`、`1.21.5`、`1.21.9`、`1.21.11` 两轮均通过。

关键指标：R1 均加载 1529 柱；R2 均无新增整柱；OVD 均已加载 632、缺失 0。`1.21.11` R2 光照缓存命中 1064/1085（98.1%），G2 已恢复；各分析器均为 `failures=[]`。

临时 `[BATCH-SRV]`、`[BATCH-LIGHT]`、`[BATCH-CHAN]`、`[GATEWAY-C2S]` 诊断探针已清理，业务逻辑保留。

1.21.11 编译暴露的版本 API 差异也已修复：`ServerPlayer.getServer()` 改为通过 `PlayerCompat.getMinecraftServer(player)` 获取服务端并切主线程执行 payload。

验证：`common:test`、`neoforge:compileJava -Pmc_ver=1.20.1`、`-Pmc_ver=1.21.1`、`-Pmc_ver=1.21.11` 均通过。

## 旧探针暂停点（历史记录）

旧探针曾用于确认停摆期 `writable`、`bytesBeforeUnwritable`、批次 ACK 与光照队列状态；结果已证明本次修复后的出站路径正常，因此不再保留探针代码。

## 后续闭环记录

静态与运行时证据确认：NeoForge 首轮 Bloom full sync 到达时，`resyncTrackedChunks` 会跳过尚未由 `getChunkNow()` 物化的区块；这些区块不会再次进入 resync 队列，导致 R1 缓存基数不足并连带造成 R2 OVD 缺失。

修复内容：NeoForge resync 队列现在保留视距范围内的全部 `ResyncEntry`，由 `drainPendingResync` 在区块可用后重试；Bloom full sync 继续触发当前维度 resync。

验证会话：`1.21.5_neoforge_I_resyncall`、`1.21.1_neoforge_I_batchprobe7`、`1.21.2_neoforge_I_batchprobe7`、`1.21.9_neoforge_I_resyncall2`、`1.21.11_neoforge_I_resyncall2` 均为 `PASS`。

提交：`50139d0 fix NeoForge OVD resync`；收尾提交 `7c5878b fix NeoForge payload thread compatibility`。
