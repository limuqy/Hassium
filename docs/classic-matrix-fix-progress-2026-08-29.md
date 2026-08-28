# Classic 矩阵修复进度(探针定位轮,暂停点)

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

## 当前最强假设(下一轮先验证)

**服务端 → 客户端的网关帧写路径在首轮大批量(5.7MB/6s)之后停发。** 两个候选机制:

- **(a) netty 出站水位闸**:`GatewayChannel.isWritable()`(netty 默认高水位 64KB)在批量推送后长期不回落 → `isFullDeliveryChannelWritable`(`ServerChunkPushManager.java:2827`)与 hash/整柱发送被全部闸死。需解释为何不回落(客户端读取停滞?未发现 autoRead gate)。
- **(b) 发送路径静默丢帧**:某处 writable 判 false 后直接 drop(无日志)。

**已布好 `[BATCH-CHAN]` 探针(writable/nettyWritable/bytesBeforeUnwritable/state,每 5s 采样)但未跑——这是暂停点的第一步。**

## 建议下一步(按序)

1. 跑 `.\scripts\runtime-smoke-test.ps1 -Ver 1.21.1 -Loader neoforge -Phase I -SessionId "1.21.1_neoforge_I_batchprobe7"`,读 `[BATCH-CHAN]`:停摆期 `writable` 是否长期 false、`bytesBeforeUnwritable` 是否 ≤0。
2. 若 unwritable:查 GatewayServer bootstrap 的 `WRITE_BUFFER_HIGH_WATER_MARK` 配置、客户端读路径、full-push 对 unwritable 的处置(静默丢 or 排队等恢复——现状疑似"排队但永不恢复")。
3. 若 writable:在 `ChunkSender`/`tryRouteS2C`/`routeS2C` 写帧处加计数,定位静默丢帧点。
4. R1 修复后复验 R2 卡死(大概率同根因:通道断流 → idle → RST)。
5. **清理全部 `[BATCH-*]`/`[GATEWAY-C2S]` 探针**(`MixinPlayerChunkSender`、`NetworkCore`、`GatewayPlayerBridge`、`GatewayChannel`),并把 `ThreadedLevelLightEngineAccessor` 移回 mixins.json client 段。
6. 补跑受影响格:1.21.1/1.21.2/1.21.9 neoforge + 一个 `R2_FULL_CHUNK_TRANSFER` 门禁格;`compileAnchors` + `common:test`。

## 工作区状态(暂停时,未提交)

- **探针代码(临时)**:`MixinPlayerChunkSender.java`、`NetworkCore.java`、`GatewayPlayerBridge.java`、`GatewayChannel.java`、`hassium.mixins.json`(ThreadedLevelLightEngineAccessor 移至 common 段)。
- **独立修复(应保留)**:`GatewayPacketCodecTest.java` 移除白名单外 `MC_1_21_4` 碎片段(改测试内反射取 `getSlot`/`slot` accessor)。HEAD 上 `scanVersionBoundaries` 本就红,此修复使其转绿。
- 编译:`common:compileJava -Pmc_ver=1.21.1` 与 `-Pmc_ver=1.20.1` 绿(含 [BATCH-CHAN] 探针)。
- 端口/daemon:无遗留占用;Gradle daemon 正常保留。
