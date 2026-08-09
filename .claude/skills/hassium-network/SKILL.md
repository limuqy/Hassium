---
name: hassium-network
description: Hassium 网络核心技能。涉及 NetworkCore 状态机、outbound 帧协议（TCP 控制面 + UDP 数据面）、S2C handler 直调注入、C2S routeC2S 收口、续流票据 ResumeTicket、L1 迁移引擎 MigrationEngine、ViaFabric 桥、主控核心接入层 GatewayServer、chunkHash 推送、限流、指标命令或 network/ 包任务时使用。
---

# Hassium 网络核心与区块推送

存储格式见 [[hassium-storage]]；拦截点见 [[hassium-mixin]]；构建/多版本见 [[hassium-dev]]。流水线权威文档：`docs/chunk-cache.md`；架构与配置：`docs/architecture.md`；未达项清单：`docs/network-core-followups.md`。

## 三核心定位（本技能范围）

| 功能域 | 位置 | 进程 |
|--------|------|------|
| 网络核心（网关） | `network/core/`（NetworkCore 状态机 / `outbound/` 帧协议 / `migration/` 迁移引擎 / `viafabric/` 桥） | 客户端 |
| 主控核心（服务端网络与推送） | `network/gateway/`（GatewayServer 等）+ 服务端区块推送（ServerChunkPushManager / ChunkHashS2C 发送 / ChunkSender / SectionDelta 服务端 / ServerLoadReporter）+ 聚合与 ZstdPipeline 兼容链（HassiumAggregationManager / ZstdPipelineSwitcher） | 服务端 |
| 区块核心（客户端区块域） | `network/seedgen/` 影子端（= 区块核心后端引擎）+ `network/` 顶层客户端摄入管线 + `cache/` | 客户端 |

存储域（`storage/` `compression/`）、UDP 数据面（`network/dataplane/`）为支撑域，不立核心名。术语体系见 `.omp/workflows/docs-2.0/work/domain-naming.md`。

## 架构总览（2.0.0 网关形态）

```
客户端 world 侧（纯原版协议）── 网关 NetworkCore ── outbound 帧连接 ── 主控 GatewayServer
    S2C: dispatchS2C 注入 ──────┐                │  TCP 控制面（帧协议）+ ZSTD（帧外）
    C2S: routeC2S 收口 ─────────┘                └  UDP 数据面（bulk 区块，默认关）
```

- 客户端↔世界侧**零压缩 / 零聚合 / 零自定义包**（壳连接仅 keep-alive 响应走 vanilla TCP）
- 网关↔主控自有通道：TCP 控制面帧协议 + 帧外 ZSTD + UDP 数据面（默认关）
- 主控切换 = 换 outbound + 续流票据，客户端原版 Connection 状态零变化、零重载

## 网络核心（客户端进程内网关，`network/core/`）

### 状态机 `NetworkCoreState`

| 状态 | 进入条件 | 语义 |
|------|----------|------|
| IDLE | 登录前 / 断连后 | 未连接 |
| CONNECTING | `onLogin()`（关陈旧 outbound、清计数） | 正在建立到主控的 outbound 连接 |
| HANDSHAKING | `onOpen`（握手请求已发出） | 等待握手响应 |
| ACTIVE | `onHandshakeAccepted`（含迁移成功） | 网关会话激活，C2S/S2C 全量接管 |
| MIGRATING | `migrateTo` / `migrateToImmediate` | 切换 outbound 期间保持，世界侧无感 |

转移：`IDLE → CONNECTING → HANDSHAKING → ACTIVE`；`ACTIVE → MIGRATING → ACTIVE`；任意状态 `onDisconnect/onError → IDLE`。状态为原子引用（Netty event loop 与主线程并发安全）。

### outbound 帧协议（`network/core/outbound/`）

- 线格式：`[varint 帧长(含 type+payload)] [type 1B] [payload]`，`ControlFrameCodec` 编解码，纯 Netty **零 MC 依赖**
- 帧类型 `ControlFrameType`（id 只增不减，新增必须 append）：`HANDSHAKE_C2S/S2C`、`PACKET_C2S/S2C`（PLAY 数据）、`AGGREGATED`、`PING/PONG`、`HEARTBEAT`（双向；迁移引擎存活判定输入）、`LOGIN_C2S/S2C`（登录阶段中继）、`CONFIG_C2S/S2C`（配置阶段中继）
- 管道（注册序）：`[zstdDecoder(握手后)] [zstdEncoder(握手后)] [frameDecoder] [inboundHandler]`；ZSTD 复用 `ZstdContextDecoder` / `SkipAwareZstdEncoder`（`OutboundConnection.installZstd`，不改其原挂载），由 `NetworkCore.applyHandshake` 在握手响应 `globalCompressionAccepted` 时安装
- 握手流：`channelActive → 发 HANDSHAKE_C2S（明文）→ onOpen → HANDSHAKE_S2C → accepted ? onHandshakeAccepted（随后装 ZSTD / 登记 UDP 数据面）: onHandshakeRejected`
- 测试缝：`openEmbedded`（EmbeddedChannel 跑完整帧/握手流）

### S2C 入站：`dispatchS2C` → `GatewayS2CRouter`（handler 层直调注入）

- 区块包（`ClientboundLevelChunkWithLightPacket`）：经官方 `ClientPacketListener.handleLevelChunkWithLight` 注入，第三方 handler 注入 mod 全可见；区块 apply 走 vanilla 主线程路径（无客户端预算注入）
- 实体包（7 类）：复用 `ClientMetadataHandler.forwardEntityPacket` 转发面——触发影子端 `ShadowSeedServer.applyEntityPacket` 进程内直传，放行原版（不调 vanilla handler）
- 其他原版包（含登录响应）：`packet.handle(listener)` 官方分发
- 线程：主线程直调；非主线程经 `MainThreadDispatcher` 排队（区块包 OP_CHUNK_APPLY 锚点 REPLACE 语义）
- 主控切换只换 outbound，注入路由不动（注入器注册于 NetworkCore 单例）

### C2S 出站：`MixinConnection` HEAD 截获 → `routeC2S` 收口

- PLAY 阶段（`ClientPacketListener`）：`GatewayPacketCodec.encodeVanilla` → `PACKET_C2S` 帧，成功则 cancel 原版发送（壳连接不承载数据）；**keep-alive 响应例外**（走 vanilla TCP 保活镜像）
- 登录阶段：`relayLoginPacket` 旁路中继 `LOGIN_C2S`（不 cancel——vanilla 登录照常，主控会话由网关独立复刻）
- 配置阶段（1.20.2+）：`relayConfigPacket` 旁路中继 `CONFIG_C2S`
- 未路由（outbound 未开 / 状态非 ACTIVE / 编码失败）→ 返回 false，原版放行降级，不吞包
- 服务端方向零开销：监听器类型先行判定直接放行（dedicated server 无客户端类，禁止解析）

### 续流票据 `ResumeTicket`（`network/ResumeTicket.java`）

- 内容：玩家 UUID + epoch（进程生命周期单调递增）+ HMAC-SHA256 签名；线格式 `varint len + [uuid(16) | epoch(8B BE) | sig(32B)]`，握手 append-only 尾（`HandshakeStateTail`）承载
- 密钥 = 主控 A/B 共享密钥（`setSharedKey` 部署覆盖；密钥分发属部署事项）
- 服务端验票：`ResumeTicketValidator.verifyAndAccept` 验签 + epoch 防重放（validator 表跨会话不清理，恒递增）；通过 → `ServerChunkPushManager.markPlayerResumeActive` + 注册 UUID-keyed 会话 + 响应尾 `resumeAccepted=true`
- 无票据 / 验票失败 → `resumeAccepted=false`，会话待登录桥 `attachPlayer` 附着（数据推送不流入，登录桥兜底）

### L1 迁移引擎 `MigrationEngine`（`network/core/migration/`）

| 触发 | 条件 | 路径 |
|------|------|------|
| 故障 | outbound 入站静默超时（`MigrationPolicy.faultTimeoutMs`，沿用 `network.dataPlane.recoveryWindowMs` 语义） | `onFault → migrateToImmediate`（不预热） |
| 策略 | `ServerLoadReporter` 负载上报（TPS 阈值 / 负载均值 / 维护窗口）经 `evaluatePolicy` | `onPolicyTrigger → migrateTo`（预热） |
| 演练 | 手动调用 `NetworkCore.migrateTo` | 预热 |

- 编排：策略路径先 `prewarm`（`PrewarmSession` 携续流票据握手 → 目标侧物化 + `resyncTrackedChunks`）→ `takePrewarm` 接管；连接尝试上限 3 次，失败降级关连接 → IDLE
- `IdleWindowDetector`：无移动（位移阈值 0.5 方块/s）且无 hash 活动 ≥10s 判定空闲窗口
- 纯逻辑无 MC 依赖：玩家状态 / 身份 / 时钟 / 连接工厂全部注入式（可单测）；心跳由守护线程周期 tick
- 客户端 outbound 地址源 = 迁移引擎（非配置直读）

### ViaFabric 桥（`network/core/viafabric/`）

- 检测（双通道任一命中）：classpath 探测（`com.viaversion.viaversion.api.Via` 等 4 类）+ 平台 mod 列表（`viafabric` / `viaforge` / `viafabricplus` / `viaversion`）
- 接入：`ViaFabricCompat.onLogin()` 每次登录重探测并接线 `NetworkCore.setS2CTranslator`——S2C 先经 `ViaDecodeBridge` 协议转换再进注入器
- 失败安全：ViaFabric 缺席 / 桥不可用 / 转换异常一律返回原包，注入器照常收到 Packet；检测结果供注入层查询 `isActive()`

### UDP 数据面（`network/dataplane/`，支撑域，默认关）

- 客户端：网关握手响应 `udpTail.hasUdpDataplane()` → `UdpDataPlane.start` → `DataPlaneClientLifecycle.deferUdpStart`（`NetworkCore.applyHandshake` 接线）→ `MixinClientTick` tick TAIL 周期启动/驱动
- 服务端：`DataPlaneUdpServer`（KCP-over-UDP 单点，NioDatagramChannel）生命周期接 `MixinMinecraftServer`（bind 失败仅告警 / tick / shutdown）；非 dedicated 跳过；`network.dataPlane.enabled=false` 时跳过
- **控制连接 = 网关帧连接**（GatewayChannel）：不另行 beginControlConnection；数据面启停由网关握手承载
- bulk 区块：`DataPlaneClientBundle`（ReliableDatagramSession + AES-GCM + epoch/endpointId 双向认证）→ ChunkDispatcher 注入缝（`NetworkCore#dispatchS2C`）
- 服务端 per-player 控制活动 / 断连记录仍由 `MixinServerGamePacketListenerImpl` 驱动（服务端 permit 判定链保留：`controlStallMs` / `failoverExpiryMs` 仍消费；客户端侧消费链已删）

## 主控核心接入层（`network/gateway/`）

- `GatewayServer`：接受客户端 outbound 帧连接（复用 `ControlFrameCodec`/`HandshakeCodec`），桥接为 UUID-keyed 玩家会话；生命周期接平台 `MixinMinecraftServer`（start/stop，与 DataPlaneUdpServer 同模式）；停止时 `GatewayPlayerRegistry.removePlayer` 一键清空
- **监听端口**：`network.controlReachableEndpoints[0].port()`（0<port<65536 时）→ 否则兜底 **25566**（`GatewayPlayerBridge.DEFAULT_GATEWAY_PORT`，与 vanilla 端口错开）并 warn；host 空 → `0.0.0.0`
- `GatewayChannel`：帧连接的对称端（同管道序 + `installZstd` 帧外）；握手分支 = 验票续流 / 登录桥 `attachPlayer`；数据桥 `sendS2CPayload`（S2C 推送经 PACKET_S2C 帧回网关）/ `handleC2SPayload`（分发到会话 sink）
- `GatewayPlayerBridge`（`server/` 装配）：创建假 Connection（`MixinConnectionGatewayServer` accessor 注入 EmbeddedChannel——`Connection.hasDisconnected()` 依赖非 null 且 open 的 channel）；`routeS2C` 查桥状态表，非网关连接原样放行（客户端侧零影响）
- `GatewayPlatformWiring`：装配（bind/start、握手响应服务端字段组装、ZSTD 接线）；帧连接即控制连接（不 beginControlConnection）

## 服务端 vanilla 路径（主控核心网络行为）

三件套（`HassiumAggregationManager` / `ZstdPipelineSwitcher` / `ZstdNegotiationTracker`）**全部保留且活跃**，作用于主控侧 vanilla Connection 路径（玩家/推送）：

- **管线级 ZSTD**：`ZstdPipelineSwitcher` 运行时替换 vanilla 管道 Zlib→ZSTD（`switchToZstd` 装 `ZstdContextDecoder` + `SkipAwareZstdEncoder`）；`MixinConnectionSetupCompression` 在协商后 `setupCompression` 时切换兜底；`markSkipNextPipelineCompression` 防双重压缩
- **应用层聚合**：`HassiumAggregationManager` 缓冲（20ms 周期 flush / minBatch / 超限分批 / 聚合包内部字典 ZSTD）；入口 `MixinConnection.hassium$tryAggregate`——**仅 `packetListener instanceof ServerGamePacketListenerImpl` 时生效（聚合只在服务端进行，客户端不聚合）**；网关通道不聚合（聚合在帧协议之外）
- **紧凑包头**：仅聚合包内部子包标识符编码（`CompactHeaderCodec` 两级 VarInt 索引，索引从 1 起、0=未索引回退 Utf 全名）；索引建表/同步 `IndexSyncManager` + `NamespaceIndexManager` + `IndexSyncPacket`；握手协商 `compactHeaderSupported × enableCompactHeader`
- 客户端侧保留聚合包解码链（`handleAggregationClient`）与 `tryInstallClientZstdPipeline`——主控若对网关玩家发 AGGREGATION_S2C，经网关帧（kind=0 原版包中继）仍可达客户端解码；**客户端↔世界侧不再承载压缩/聚合数据流**（壳连接零数据面流量），文档表述按此口径，不写"组件已删除"

### 关键类（服务端侧）

| 类 | 职责 |
|----|------|
| `ZstdPipelineSwitcher` | switchToZstd / switchToZstdWhenReady / switchToZlib / markSkip / setOutboundCompressionThreshold |
| `ZstdNegotiationTracker` | per-channel 协商状态；断连清理接 `MixinConnection.hassium$onDisconnect` |
| `SkipAwareZstdEncoder` / `ZstdContextDecoder` | magicless 管线编解码；skip 用 **Channel.attr**（`HassiumPipelineAttributes.SKIP_PIPELINE_COMPRESSION`），非 ByteBuf.attr |
| `HassiumAggregationManager` | 缓冲、定时 flush、分批、字典聚合包（`HassiumAggregationPacket` / `AggregatedSubPacket`） |
| `CompactHeaderCodec` / `NamespaceIndexManager` / `IndexSyncManager` | 聚合内 identifier 编解码与索引同步 |
| `HassiumConnectionRegistry` | PENDING（缓冲不 flush）/ ENABLED |
| `DictionaryManager` | 区块/聚合字典 |
| `PacketCompressionBlacklist` | 控制面/独立通道不进聚合（默认 10 项黑名单，见 `HassiumConfig`） |

## 现行推送流（chunkHash，存续）

```
Mixin 拦截 broadcast/trackChunk/PlayerChunkSender
  → pushPool 算 sectionHashes → chunkHash
  → 批量 ChunkHashS2C（控制面黑名单）
  → 客户端 readChunkHash（MetadataTable / SectionHashStore）比对
  → 命中：ClientCacheLoadQueue；未命中：全量 ChunkDataRequestC2S
  → MISMATCH 且 sectionDeltaEnabled → SectionDeltaS2C 合并写盘再 apply
  → onServerTick 主线程序列化 ≤ maxChunksPerTick → pushPool 压缩发送
  → 客户端 MainThreadBudget apply（JoinBoost ~10s）
  → persist：contentHash=combine(sectionHashes) + SectionHashStore
```

- 分段增量：`clientCache.sectionDeltaEnabled`（默认 true）。开启时 MISMATCH → `SectionHashRequest` → NBT merge；关闭走全量。服务端按索引比对（空气 hash=0）；超视距进 `skipped` 并始终回包；客户端对 skipped/超时回退全量
- 推送入口统一 `ServerChunkPushManager`；`MixinMinecraftServer.onServerTick` 每 tick flush hash 批次 + 按真实 tick 限流（仅 dedicated：`RuntimeServerContext.isDedicatedServerContext()`，影子端世界不受推送管理）
- 服务端推送线程池：`serverChunkPushThreads`（默认 2）+ `dynamicThreadPoolEnabled`（默认 true，min 2 / max 8）；光照剥离 `lightStrip`（默认 true）：发包不带 LightData，光路由影子端统一承担（现状以 `docs/chunk-cache.md` 与 `docs/client-chunk-light-flow.md` 为准）

### 关键类（推送 + 客户端摄入）

| 类 | 职责 |
|----|------|
| `ServerChunkPushManager` | hash 批量、数据队列、tick、pushPool；拦截时缓存包字节供 miss 复用 |
| `ClientMetadataHandler` | hash 比对、全量请求、section delta、实体包转发（`forwardEntityPacket`） |
| `ClientChunkPipeline` / `ClientChunkHandler` | 客户端摄入管线（区块核心） |
| `ClientCacheLoadQueue` | 后台读缓存 |
| `ClientMainThreadBudget` / `MainThreadDispatcher` | 主线程 drain（`clientCache.mainThreadChunkBudgetMs`） |
| `ChunkHashS2CPacket` / `ChunkDataRequestC2SPacket` / `SectionDeltaS2CPacket` / `LightDeltaS2CPacket` / `BlockEntityDataS2CPacket` | 推送与请求面 |
| `NetworkStats` | 零分配指标 |
| `CompressionReadyPayload` | 客户端 ZSTD/压缩就绪 ACK（自定义通道路径存续） |

平台发送：`Services.NETWORK_MANAGER`（`INetworkManagerService`）；各端 `Fabric/Forge/NeoForgeNetworkManager`。网关帧协议与三端 CustomPayload 通道互不相干。

## 限流配置

| 项 | 默认 | 含义 |
|----|------|------|
| `network.maxChunksPerTick` | **5**（ConfigSchema；HassiumConfig 死默认 4 无调用方） | 每玩家每 server tick 提交后台序列化上限（满 tick ≈ 5×20/s = 100/s） |
| `clientCache.mainThreadChunkBudgetMs` | 15 | 客户端每帧 apply 预算（JoinBoost ~30） |
| `clientCache.maxChunksPerFrame` | 6 | 客户端每帧 apply 缓存区块硬顶，非主限流 |

## 配置键归属（须与代码默认一致）

**网络核心（CLIENT）**：`network.enabled`（true，网关通道总开关）、`network.metricsEnabled`（false）、`network.metricsAutoReset`（true）。

**主控核心（SERVER）**：`globalPacketCompression`（true）/ `globalCompressionLevel`（3）/ `globalCompressionThreshold`（256）/ `magiclessZstd`（true）/ `useContextCompression`（true）/ `enablePacketAggregation`（true）/ `aggregationMinBatchSize`（4）/ `aggregationMaxWaitTimeMs`（20）/ `aggregationMaxSize`（262144）/ `enableCompactHeader`（true）/ `compressionBlacklist`（默认 10 项）；推送与限流键见上表；`network.controlReachableEndpoints`（网关监听端口源，双端消费）。

**归属注明**：`network.seedGen.enabled`（双端）属**区块核心**的 SeedRef/本地生成机制（键名因历史留在 network.*，与 `clientCache.seedGenThreads` 相互独立）；`network.dataPlane.*` 属数据面域，其中 `recoveryWindowMs` 的 2.0.0 语义 = L1 迁移引擎故障静默超时（`MigrationPolicy.faultTimeoutMs`）。

Config 读取：`HassiumConfigService`（`isNetworkCompressionEnabled()` / `isGlobalPacketCompressionEnabled()` / `isPacketAggregationEnabled()` / `getAggregation*()` / `isMagiclessZstd()` 等）；三端 backend 均由 `ConfigSchema` 生成（无手写三端 Spec 表）。

## 命令

- 服务端：`/hassium stats`、`/hassium stats reset`、`/hassium stats toggle`、`/hassium metrics on|off`（level 2）
- 客户端：`/hassiumc stats`、`/hassiumc export [serverIp] [seed]`（导出影子端世界 → `hassium_exports/<cacheId>`）
- `network.metricsEnabled=false` 时 stats 类命令不可用；`/hassiumc export` 实现为影子端世界目录整体拷贝（`hassium_cache/<serverId>/world`），种子参数保留但不再参与

## 日志

默认安静。热路径用 `DebugLogger`（`debug.networkLogging` / `metadataLogging` / `cacheLogging` / `dataplaneLogging` 等，默认 false）。INFO 仅生命周期：网关状态转移、握手摘要、续流结果（resumeAccepted）、迁移触发、断开清理。

## 改动检查清单

1. 新帧类型 → `ControlFrameType` **append**（id 只增不减），并核对三端/网关编解码对称
2. 新控制面包 → 加入黑名单（`HassiumPacketIds` + 默认 `compressionBlacklist`；聚合包自身亦在黑名单）
3. 新可聚合包类型 → 纳入 `NamespaceIndexManager` / `initializeServerIndex`，保证紧凑头可索引
4. 网关握手 append-only 尾改动 → `HandshakeCodec`（C2S 编码 / S2C 解码）+ `HandshakeStateTail`（readC2S/readS2C）同步，旧尾字段只增不减
5. S2C 新注入分类 → 挂 `GatewayS2CRouter.route`（区块包走官方 handler、实体包走转发面、其余官方分发），保持注入器失败安全（单包异常不得打断扇出）
6. C2S 新路由分类 → `MixinConnection.hassium$routeC2S` 按监听器阶段分发；路由成功才 cancel，未路由必须原版放行
7. 续流票据改动 → `ResumeTicket` 签名/编码 + `ResumeTicketValidator` 验签与 epoch 防重放同步；epoch 恒递增
8. 迁移改动 → 保持纯逻辑（状态/身份/时钟/连接工厂注入），测试直接 `tick` 手动驱动；故障路径不预热
9. 聚合/管线改动 → 仅主控侧 vanilla 路径生效（`ServerGamePacketListenerImpl` 判定）；聚合发送必须 `markSkipNextPipelineCompression`
10. 断开路径清理 Registry / Aggregation / NegotiationTracker / 网关会话（`GatewayPlayerRegistry.removePlayer`）
11. 热路径禁止无条件 `LOGGER.info`；common 禁止 loader API，平台差异放 `*NetworkManager`

## 常见坑

- **resumeAccepted=false 时推送不流入**：会话未附着（登录桥未配对），属预期降级，不是推送链 bug
- **MIGRATING 卡死**：`migrateTo` 只能从 ACTIVE 进入（`transition` 校验）；预热中重复迁移被 `prewarmInFlight` 拒绝，需等回退/复位
- **一边 Zlib 一边 ZSTD 炸包**：管线切换缺少安全窗口（`pauseOutboundCompression` / `switchToZstdWhenReady`）或过早 markNegotiated
- **聚合包双重压缩**：漏 `markSkipNextPipelineCompression`，或误用 ByteBuf.attr（会丢，skip 标记必须走 Channel.attr）
- **紧凑头解码失败 / 子包 type 错乱**：IndexSync 未到或双方索引表不一致；未注册类型应走 `0 + Utf` 回退
- **误加独立紧凑头 Pipeline Handler**：紧凑头仅聚合包内部，`enableCompactHeader` 不对应管线 handler
- **网关端口被占**：端口 = `controlReachableEndpoints[0]`，兜底 25566；看日志 port-source 打标
- **影子端世界被推送管理误伤**：推送管理器仅 dedicated 激活（`RuntimeServerContext` 判定），客户端进程内 MinecraftServer 不接网络
