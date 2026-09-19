# 客户端区块接收与光照应用流程

客户端从收到区块数据包到区块/光照落地主线程的完整链路：线程归属、队列、预算与时序。
服务端推送链见 [`chunk-cache.md`](chunk-cache.md) §3；磁盘缓存格式见 [`chunk-cache.md`](chunk-cache.md) §11。

链路归属：客户端收包 → apply → 光照落地全链路属**区块核心**（客户端进程内区块域，影子端 = 其后端引擎）；传输走**唯一 vanilla TCP**（登录期握手 + Play 期自定义 payload）；服务端推送属**服务端传输面**。配置键 `chunk.*` 为区块核心配置族（2026-08-09 config-restructure：原 `clientCache.*` 重排为 `chunk.*`）。

**相关专文：**

| 主题 | 文档 |
|------|------|
| 服务端推送 / chunkHash / 缓存命中 | [`chunk-cache.md`](chunk-cache.md) |
| Hassium 引擎（影子端）总体说明 | [`architecture.md`](architecture.md) §5、§6 |

## 1. 总览图

```mermaid
flowchart TD
    A["原版区块包（vanilla chunk+light）"] --> B{"服务端是否剥光"}
    B -- "否：权威光随包" --> C["ClientPacketListener.handleLevelChunkWithLight"]
    B -- "是：仅握手声明引擎" --> D["ShadowVanillaLightPipeline / ShadowLightCompute"]
    D --> E["影子 ServerLevel 注入"]
    E --> F["原版 LightEngine 两阶段屏障"]
    F --> G["drainReady 构造官方带光区块包"]
    G --> C
    H["缓存 UNCHANGED / delta 回退 / 本地生成"] --> I["Compare + Pull 或 Generate + Validate"]
    I -- "缓存可 materialize" --> D
    I -- "FULL" --> D
    I -- "生成失败 / mismatch / delta 失败" --> J["无 baseline 的权威 FULL"]
    J --> D
    C --> K["ClientChunkCache.replaceWithPacketData"]
    K --> L["renderer 异步 mesh 编译"]
```

**生命周期边界**：Hassium 只选择/处理数据来源；`ClientChunkCache` 仍是区块驻留和卸载的唯一真相源。`clientAppliedChunkCount` 是会话累计成功 apply，`clientCache.loadedChunks` 是采样时刻完整驻留量；二者不能用 mesh trace 的一次快照互相替代。

## 2. 线程与队列全景

| 环节 | 队列 / 预算 | 线程 | 代码 |
|------|-------------|------|------|
| 收包 | —（payload 回调） | Netty / vanilla 网络线程 | `ClientChunkHandler` / `ClientChunkPipeline`（官方包 + 自定义 payload 统一入口） |
| 解压 / 转 NBT | `HassiumTaskExecutor` 提交 | 后台虚拟线程 | `ExecutorFactory.create` |
| 主线程调度 | `PriorityBlockingQueue`（按玩家距离） | — | `MainThreadDispatcher.execute` |
| 区块 apply | `ClientMainThreadBudget`（JoinBoost 30ms 窗口 / normal `mainThreadChunkBudgetMs`）；无数量硬顶 | Render thread | `MixinClientTick` |
| 光照投递 | `ShadowLightCompute` pending/generated/delta/inflight | 投递：Netty / 解压后台；消费：后台池 | `submit` + `ShadowLightCompute` |
| 影子端注入 + 收敛 | 注入表 + UNKNOWN FULL 票 → **齐套门 `LightNeighborhoodGate`（3×3 `INITIALIZE_LIGHT` 齐套）→** per-chunk `initializeLight` + `lightChunk`；**禁止去门直算**（2026-09-18 去门实验屋檐黑，已钉死） | 引擎 mailbox / 后台池 | `ShadowSeedServer.runMainLoop` + `ShadowLightCompute` + `LightNeighborhoodGate` |
| 光照落地 | 帧尾 `drainReady`（渲染前，预算内）把 ready 整柱包倒进 `handleLevelChunkWithLight` | Render thread | `MixinClientTick.drainReady` |

**线程纪律**：Netty 线程只做入队；`ClientMainThreadBudget` 是唯一主线程 apply 闸门（时间预算，无数量硬顶）；`maxChunksPerFrame` 只限缓存读取生产（影子入队 + 影子读盘），不限 apply。

## 3. 收包 → apply 路径

```text
vanilla chunk+light 包 / hassium:* payload
        │
        ▼
MixinClientPacketListener.handleLevelChunkWithLight（HEAD / RETURN）
        │
        ├─ 无影子基线 → 原版 apply（首次建基线）
        └─ 有影子基线 → ShadowPull（Compare + Pull）
        ▼
ClientChunkHandler.applyShadowPullFull
        │  decode 原版包（后台）→ 原版 listener 落地
        ▼
ClientChunkCache.replaceWithPacketData → renderer
```

- `applyShadowPullFull` 是统一 apply 入口（ShadowPull FULL payload = 原版 `ClientboundLevelChunkWithLightPacket` 线格式，decode 后经原版 `handleLevelChunkWithLight` 落地；原 `applyChunkData` 与加载屏快路径已随退役 `chunk_payload` 通道删除）
- `storePendingContentHash` 在 apply 前登记 chunkHash，供影子端落表

## 4. 光照管线（影子端统一算光）

影子端 = 进程内完整 `MinecraftServer` 上下文（`ShadowSeedServer`），光照由原版 `LightEngine` 计算：

1. **注入**：`ShadowSeedServer.injectChunk` 把权威/缓存/本地生成数据注入影子 `ServerLevel`
2. **两阶段屏障 + 齐套门**：注入后 `initializeLight`；**非 REUSE 且未 promote 时必须经 `LightNeighborhoodGate`（3×3 邻柱均过 INITIALIZE）再 `lightChunk`**。齐套后屏障内**复用 phase-1 的 native chunk 只跑 LIGHT**（2026-09-19 起为生产语义，开关 `REUSE_PHASE1_INITIALIZE`）：phase-1 已对同一 ChunkPos 完成 INITIALIZE，引擎层存储按 SectionPos 索引，再跑一遍是重复劳动；无 phase-1 产物时回落到从零 `initializeLight+lightChunk`。**天光播种在 LIGHT 步**（`propagateLightSources` 读 `ChunkSkyLightSources`），INITIALIZE 不算任何亮度值——故屋檐黑不是 INITIALIZE 复用的问题。历史 2026-09-18 ⑤「复用 → 移动后屋檐柱全黑」判定已作废（归因混杂：当时树上正在去门回归、且尚无读盘柱光源表修复）。缺邻时引擎按 Bedrock 挡天光。**齐套门钉死**；验收须含**移动中**的屋檐/洞口，冒烟 PASS 不能代替目视。
   - **天光光源表不变量（2026-09-19 钉死）**：注入柱的 `ChunkSkyLightSources` 必须已填。网络路径由 `LevelChunk.replaceWithPacketData` 重填，**读盘路径 `ChunkSerializer.read` 不会**（`ShadowSeedServer.injectLoadedChunk` 已补 `initializeLightSources()`）。表全 0 时 `getHighestLowestSourceY()` 返回 `NEGATIVE_INFINITY`，`SkyLightEngine.setLightEnabled(pos, true)` 会把 `[minLightSection, maxLightSection)` 的整柱空层 `fill(15)`；而 `lightChunk(lit=true)` 跳过 `propagateLightSources`，坏光无人纠偏 → 落盘 `0xFF×2048` + R2 复用该缓存即整片异常亮。观测锚点：`debug.lightVerify` 的 `SKY-SOURCES highestLowestSourceY=`（`-2147483648` = 表未填）。
3. **交付 = vanilla 整柱包**：屏障完成后 `SeedGenChunkCodec.buildPacket` 构造
   `new ClientboundLevelChunkWithLightPacket(chunk, engine, null, null)`（blocks + sky + block 全柱枚举），
   经 `MixinPlayerChunkSender` / `MixinChunkMap` / `MixinServerPlayer` 的官方通道直接转发真实客户端。
   **光只随整柱包一次性下发**（对齐原版专用服语义：区块推一次，光就那一份快照；重新加载区块才更新）。
   **不存在** section 级光回传（原 `MixinServerChunkCache.onLightUpdate` → `collectLightUpdate` →
   `drainLightMasks` → `ClientboundLightUpdatePacket` 光桥已于 2026-09-19 整体删除，见 §7）。
4. **帧尾落地**：`drainReady` 渲染前预算内把 ready 整柱包倒进 `handleLevelChunkWithLight`。
5. **失败降级**：影子端启动失败（`ShadowServerRegistry.failShadowServer`）→ 关缓存/SeedGen/影子光照，全程原版路径（服务端不剥光——剥光在握手协商）

## 5. 关键组件

| 组件 | 职责 |
|------|------|
| `ClientChunkHandler` / `ClientChunkPipeline` | 收包统一入口（官方包 + 自定义 payload）、解压调度、`applyShadowPullFull`、握手/影子端状态机 |
| `MainThreadDispatcher` | 后台→主线程回调队列（距离优先级） |
| `ClientMainThreadBudget` | 主线程 apply 时间预算（JoinBoost / normal）；`maxChunksPerFrame` 只限缓存读取生产 |
| `ShadowLightCompute` | 投递队列（pending/generated/delta/inflight）+ 屏障重试 + 帧尾落地编排 |
| `ShadowServerRegistry` | 影子端共享单例：握手后创建、失败降级、断连关闭 |
| `ShadowSeedServer` | 进程内 ServerLevel + 官方光照引擎：注入 / 清光（vanilla updateChunkStatus 同款）/ 增量清光 / 完整度校验 |
| `MixinServerChunkCache` | 影子端**区块取数桥**（`getChunkForLighting` / `getChunk`，F17 死锁修复）。原 `onLightUpdate` 光出口注入已随光桥删除 |

## 6. 退役引用说明

以下旧链路概念已随直连拓扑/管线退役，不再出现在现行数据流中（历史见 [`handoff/handoff-2026-09-04-vanilla-direct-network.md`](handoff/handoff-2026-09-04-vanilla-direct-network.md)）：

- 网关 outbound / UDP 事件循环收包（现走 vanilla Netty 线程）
- `NetworkCore.dispatchS2CBusiness`（`network/core/` 已裁剪）
- `GatewayS2CRouter` 原版包注入路由（现由 `MixinClientPacketListener` 直拦）
- `ClientCacheLoadQueue` / `CacheSaveQueue`（缓存存储与读盘由影子端承担）
- 管线级全局包压缩解压步骤（通道压缩 = 聚合包内部字典 ZSTD + 区块推送自有压缩）

## 7. 2026-09-19 交付侧叠甲清理

用户决策：**方块更新导致的光照变化客户端本地本就会算；影子端只推完整光，交付侧不应拦异常样本**。
影子端的光是**快照**（与原版专用服一致：区块推一次，不重新加载就不会刷新），客户端自身也在算光。
故一切「交付侧补票 / 拦异常样本」的叠甲均删除。

### 7.1 第一波：三项删除

- **LightDelta 全链（死代码）**：`HassiumChannels.LIGHT_DELTA_S2C` / `LightDeltaS2CPacket` /
  `PayloadHandlers.handleLightDelta` / `INetworkManagerService.sendLightDeltaPacket`（三端实现
  均无调用者）/ 三端注册与 receiver / `ShadowLightCompute.submitLightDelta` +
  `pendingLightUpdates` + `LightWork` + `LightSource.LIGHT_ONLY` + `ShadowSeedServer.invalidateLightSections`。
  直连拓扑下服务端从不发该包 → receiver 永不触达 → 该链恒不可达。
- **整柱包空层收窄**（原 `SeedGenChunkCodec.deliveryLightMasks`）：交付掩码一律走 vanilla 全柱枚举
  （`null/null`）。原收窄按「客户端已持有该柱」只下发「线上确有光」的 section，用于规避空 section
  被记成 `emptyYMask` → 客户端显式置 0（2026-09-16 flyroundtrip `skyTop=0` 采样 31→3）。删除依据：
  现影子端只推完整光（屏障完成后才打包），且 `7d335166` 已修读盘柱光源表未填。**回归风险**：
  该收窄有历史正向证据，回退需带 `skyTop` 采样的 A/B。
- **降级过滤的天光侧**（`ShadowLightCompute.retainNoDowngrade`）：天光层遇 `ASSERT_DOWNGRADE`
  不再清位（仍排除空层）；方块光侧保留。实测 4 轮天光降级 0 命中（方块光 27 命中、maxΔ=2），
  故该分支为死代码。

### 7.2 第二波：光桥整体删除

**「方块更新 → 光回传」的 section 级链路整体删除**（用户决策：单独的光桥已无必要；光照应参考原版
专用服，随整柱包下发一次）。

删除清单：

- `MixinServerChunkCache.hassium$onLightUpdate`（唯一 `onLightUpdate` 注入点）
- `ShadowLightCompute.collectLightUpdate` / `drainLightMasks` / `pushLightReady` / `offerLightReady` /
  `applyReadyLight` / `ReadyItem.lightPacket` / `LightMask` / `lightUpdates` / `fullApplyTraces` /
  `retainNoDowngrade` / `classifyAssert` / `isLightMidCompute` / `lightFollowUps` +
  `requeueLightFollowUp` + `MAX_LIGHT_FOLLOW_UPS` / `shouldApplyLightThisFrame` /
  `shouldPackLightMaskThisFrame` / 全部 `probeMask*` / `probeAssert*` / `probeDowngrade*` / `probeWireLight*`
- `SeedGenChunkCodec.wireLightMask`（及其 `probeWireLightScan` 计数）
- `ShadowLightCompute` 的 `LIGHT_BRIDGE_DEFER_TIMEOUT_MS` / `bridgeDeferSinceMs`

**保留**：`SeedGenChunkCodec.hasWireLight`（调用方是 `ShadowSeedServer.isColumnSurfaceLightReady` 的
柱地表光就绪判定，与光桥无关）；`MixinServerChunkCache` 的 `getChunkForLighting` / `getChunk`
两处取数桥（F17 死锁修复）；`LightNeighborhoodGate` 齐套门。

**删除依据**：影子服务端没有真实玩家连接，vanilla `ChunkMap.broadcast` 无收件人，故当初自建 section
级回传；而 vanilla 原生通道早已在跑——`MixinPlayerChunkSender:117-130`（`sendChunk` 的 `send`
`@Redirect`）/ `MixinChunkMap:87-112`（`playerLoadedChunk` HEAD）/ `MixinServerPlayer:65` 均调
`ShadowOfficialPacketBridge.forwardToRealClient`，带光整柱包直达真实客户端。光桥是重复通道。

**量化**（删除前探针 run，`build/smoke-test/probe/<SessionId>/round{1,2}.json`）：
`maskCollected` 414154 vs `maskDrained` 4951（98.8% 白收集）；`maskDiscarded/maskDrained ≈ 9.0`；
`wireLightFullScans` 848657/866756/850924（≈3.5e9 格读/run）；`deliveryEmptyClientLit` 三轮均 0
（被删的空层收窄从未拦住真实风险）；`downgradeSky` 三轮均 0（天光降级分支是死代码）。

**回归风险**：光桥曾是「假收敛态」的兜底（2026-09-16 flyroundtrip：撤 `retainNoDowngrade` →
regression=15）。删除后若出现「客户端停在 standing 首包欠光（skyTop=0）」，按用户口径属**投喂/算光侧**
问题，应修源头（读盘柱光源表、齐套门、屏障收敛），不在交付侧补票。

**未删除**（仍活跃）：`LightNeighborhoodGate` 齐套门、`ShadowOfficialPacketBridge` 官方通道转发、
`isLightConverged` 收敛判据（`drainReady` → `confirmLightsCorrectIfConverged`）。

## 8. 后续设计：齐套自驱动 + 唯一交付出口（进行中）

光桥删除后交付变成**一次性快照**（错了就一直在），由此暴露三处已确认的不一致：光环常量
`ShadowPullRadii.LIGHT_HALO_RADIUS` 是死代码、**交付域比计算域宽 4 环**、交付侧没有「3×3 已就绪」门
（降级放行也算 `promoted`）。实测缺邻降级占放行总数 **11.0%**（2026-09-19 手动 run）。

设计已拍板（三区状态机 / 参数化光环 / 200ms / 3×3 域分组排序 / 7 个交付入口收敛为 1），
见 [`handoff/handoff-2026-09-19-light-halo-selfdriven-delivery.md`](handoff/handoff-2026-09-19-light-halo-selfdriven-delivery.md)。

