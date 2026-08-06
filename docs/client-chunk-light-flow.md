# 客户端区块接收与光照应用流程

客户端从收到区块数据包到区块/光照落地主线程的完整链路：线程归属、队列、预算与时序。
服务端推送链见 [`chunk-cache.md`](chunk-cache.md) §3；磁盘缓存格式见 [`disk-nbt-cache.md`](disk-nbt-cache.md)。

**相关专文：**

| 主题 | 文档 |
|------|------|
| 服务端推送 / chunkHash / 缓存命中 | [`chunk-cache.md`](chunk-cache.md) |
| 并行光照引擎调优（capture 吞吐/完成率） | 见 hassium-parallel-light-capture-tuning skill |
| 虚拟线程超订（为什么光照用固定平台池） | hassium-virtual-thread-oversubscription skill |
| 缓存旧光预灌（R2 防黑块） | hassium-client-cached-light-restore skill |

## 1. 总览图

```mermaid
flowchart TD
    subgraph NET["网络线程 (Netty)"]
        A["Hassium 压缩通道<br/>ClientChunkHandler.handleCompressedChunk<br/>（BulkCompressedChunk / Data 通道 payload）"]
        B["原版通道<br/>ClientboundLevelChunkWithLightPacket"]
        C["LightDelta 增量包<br/>（服务端块变化，只发坐标+section 掩码）"]
    end

    subgraph BG["后台线程池 (HassiumTaskExecutor 客户端实例)"]
        D["ZSTD 解压 + NBT 转换<br/>（虚拟线程；Java 17 回退平台池）"]
        E["scheduleAsyncCacheIngest<br/>→ CacheSaveQueue 异步写盘"]
        F["MainThreadDispatcher.execute<br/>按玩家距离优先级入队"]
    end

    subgraph BG2["后台读盘 (ClientCacheLoadQueue)"]
        U["region 任务读磁盘缓存 (R2)<br/>每 region 至多一个在跑"]
        V["readyQueue 就绪队列"]
    end

    subgraph MAIN["主线程 (Render thread) — 每帧预算循环 (MixinClientTick)"]
        G["① flushClientUntil<br/>预算内出队<br/>（JoinBoost 30ms 窗口 / normal 15ms + hardCap 32）"]
        G2["② processQueueUntil<br/>预算内 apply 缓存读回"]
        H["applyChunkData<br/>→ Services.getClientChunkApplier<br/>.applyToLevelFromByteBuf<br/>区块落地（此刻可见）"]
        I{"包带光?"}
        J["权威光随包落地<br/>无需重算（帧尾兜底）"]
        K["applyLightEngineNow<br/>（三入口汇合）"]
        L{"并行光照引擎<br/>parallelLightEngineEnabled<br/>默认关"}
        M["submitRecompute 异步入队<br/>立即返回"]
        N["ClientLightBufferQueue<br/>统一异步缓冲队列<br/>帧尾预算消费（每帧部分预算）"]
        O["restoreCachedLightToEngine<br/>R2: 磁盘旧光先灌引擎（防黑块）"]
        P["handleLightDeltaPacket<br/>建层 + propagateLightSources 本地重算<br/>+ 标缓存脏"]
    end

    subgraph LIGHT["并行光照引擎 (Promethium ParallelLightEngineImpl)"]
        Q["capturePending<br/>帧预算 CAPTURE_BUDGET_NS=5ms<br/>MAX_CAPTURE_COLUMNS_PER_FRAME=24 柱<br/>从已 apply 区块读 blockState 采 3×3=9 柱<br/>等邻居 NEIGHBOR_WAIT_FRAMES=10 + 距离重排"]
        R["hassium-light 后台固定池<br/>（parallelLightEngineThreads 线程，NORM-1）<br/>纯数据 BFS solve (FIFO)"]
        S["drainCompletions<br/>帧预算内原子落地<br/>建层 + 核心柱/邻柱差异 memcpy"]
    end

    A --> D --> E
    D --> F
    B --> F
    U --> V
    F --> G
    V --> G2
    G --> H
    G2 --> H
    H --> I
    I -- "带光" --> J
    I -- "无光 (服务端剥离 / 缓存 is_light_on=0)" --> K
    K --> L
    K -. "仅 R2 缓存有光时" .-> O
    L -- "开" --> M
    L -- "关" --> N
    C --> P
    M --> Q --> R --> S
```

## 2. 线程与队列全景

| 环节 | 队列 / 预算 | 线程 | 代码 |
|------|-------------|------|------|
| 收包 | —（payload 回调） | Netty | `ClientChunkHandler.handleCompressedChunk` |
| 解压 / 转 NBT | `HassiumTaskExecutor` 提交 | 后台虚拟线程 | `ExecutorFactory.create` |
| 写盘 | `CacheSaveQueue` | 后台 | `scheduleAsyncCacheIngest` |
| 主线程调度 | `PriorityBlockingQueue`（按玩家距离） | — | `MainThreadDispatcher.execute` |
| 区块 apply | `ClientMainThreadBudget`（JoinBoost 30ms 窗口 / normal `mainThreadChunkBudgetMs`）+ `maxChunksPerFrame` 硬顶 | Render thread | `MixinClientTick` + `MixinVanillaChunkApplyBudget` |
| R2 读盘 | region 级任务（每 region 至多一个在跑）→ `readyQueue` | 后台虚拟线程 + 主线程 | `ClientCacheLoadQueue` |
| 光照重算（官方引擎，默认） | `ClientLightBufferQueue` 统一异步缓冲：帧内收集、帧尾 `FRAME_BUDGET_NS`=5ms 预算消费（~2-3 块/帧），剩余留帧 | Render thread（入队任意线程） | `ClientLightBufferQueue` + `ClientLightRecomputeService.applyLightEngine` |
| 光照 capture | `CAPTURE_BUDGET_NS`=5ms、24 柱/帧 | Render thread（独占） | `ParallelLightEngineImpl.capturePending` |
| 光照 solve | `hassium-light` 固定平台池，FIFO | 后台（NORM-1） | `ParallelLightEngineImpl.ensurePool` |
| 光照落地 | `drainCompletions(frameDeadlineNs)` | Render thread | `ParallelLightEngineImpl.drainCompletions` |

## 3. 三条数据入口

1. **Hassium 压缩通道**（默认，走 ZSTD + 聚合）：Netty 收 payload → 后台解压 → `MainThreadDispatcher` 距离优先级排队 → 主线程 apply。
2. **原版通道**（未压缩 / 原版包）：`ClientPacketListener.handleLevelChunkWithLight`（主线程），`MixinVanillaChunkApplyBudget` 将其预算化（原版是"收到即 apply"、无每帧预算）。
3. **R2 磁盘读回**：`ClientCacheLoadQueue` 后台读盘 → `readyQueue` → 主线程 `processQueueUntil` 预算内 apply。

三条路最终都汇入 `applyChunkData` → `applyToLevelFromByteBuf`（平台抽象 `IClientChunkApplier`）。

## 4. 主线程每帧预算循环（MixinClientTick）

固定顺序：

```
flushClientUntil(帧预算)   →  MainThreadDispatcher 出队 apply
processQueueUntil(帧预算)  →  R2 缓存读回 apply
drainCompletions(帧预算)   →  并行引擎：capture 采样 + 已完成 solve 落地
bufferQueue.drainFrame()   →  官方引擎：统一异步缓冲队列预算消费
flushPendingCalibrations   →  渲染前兜底官方传播队列（原版方块变化）
```

光照永远排在区块 apply 之后、渲染之前——这是"区块先出现、光后到"的时序根源。

## 5. 光照入口与分支

`MixinLightRecompute`（`handleLevelChunkWithLight` TAIL）判定包是否带光：

- **带光**（`network.lightStrip=false` 或原版）：权威光照随包落地（apply 时 queueSectionData 生效），无需重算；先落地内圈块重算时缺失本块的边界差值——官方路径由帧尾 `flushPendingCalibrations` 兜底（官方队列跨块传播天然合并），并行路径由引擎后台传播域（核心柱 ±16 格）承担。
- **无光**（`network.lightStrip=true` 默认 / 缓存 `is_light_on=0`）：`applyLightEngineNow`：
  1. `restoreCachedLightToEngine`：R2 缓存有光时先灌旧光（内容过期没关系），重算完成前渲染不黑块；无缓存（R1）跳过。
  2. 并行引擎开 → `submitRecompute` 异步入队，立即返回。
  3. 并行引擎关（默认）→ 入 `ClientLightBufferQueue` 统一异步缓冲队列，帧尾按预算消费（每帧 5ms，~2-3 块；剩余留帧，不阻塞 apply 链路）。

`LightDelta` 是旁路：`handleLightDeltaPacket` 建层 + `propagateLightSources` 本地重算（跳过跨块传播），标缓存脏；residual 由后续渲染帧 drain。

## 6. 暗块窗口

"暗块窗口" = 区块落地（可见）→ 引擎有光（变亮）之间的墙钟延迟，**只出现在 R1 首次进服 + 网络剥离光照**的组合（无旧光可灌）。R2/重连有 `restoreCachedLightToEngine` 预灌，无感。

窗口由三个独立预算队列串行构成，主瓶颈是 capture：

```
capture 排队（~240 帧，R1 625 块 × 9 柱 ÷ 24 柱/帧）
  + solve FIFO（单任务实测最高 266ms）
  + NEIGHBOR_WAIT_FRAMES=10（0.5s，等螺旋序邻居 1-5 帧到达）
  + drain 落地（帧预算内）
```

官方引擎路径（默认）：缓冲队列 5ms/帧预算 → 加载风暴（32 块/帧）下积压多帧消化，暗块窗口随队列积压增长（每帧 ~2-3 块，625 块 ≈ 4-5s）；预算为部分主线程占用，帧时间不被单帧峰值击穿。

优化杠杆（已落地，R1 完成率 30%→98%）：`MAX_CAPTURE_COLUMNS_PER_FRAME=24`、`CAPTURE_BUDGET_NS=5ms`、`SNAPSHOT_CACHE_MAX=256`（命中 62%）、`NEIGHBOR_WAIT_FRAMES=10`。

消除窗口的路线（决策见 hassium 讨论记录）：

- **首选**：`network.lightStrip=false`（服务端带光发送）→ 客户端零重算零暗块，代价是带宽（光数据 ZSTD 压缩率高，需实测）。
- **R1 无缓存**：任何客户端重算方案都无法做到"apply 即有光"，除非服务端随包附光骨架（未实现）。

## 7. 关键组件索引

| 组件 | 职责 |
|------|------|
| `ClientChunkHandler` | 压缩通道收包、解压调度、`applyChunkData` 统一入口 |
| `MainThreadDispatcher` | 后台→主线程回调队列（距离优先级） |
| `ClientMainThreadBudget` | apply 帧预算（JoinBoost / normal / hardCap） |
| `MixinVanillaChunkApplyBudget` | 原版 chunk 包 apply 预算化 |
| `ClientCacheLoadQueue` | R2 磁盘读回（region 级并发） |
| `ClientLightRecomputeService` | 光照编排：预灌旧光、并行/官方分派、同步重算实现（缓冲队列消费用） |
| `ClientLightBufferQueue` | 官方引擎统一异步缓冲队列：帧内收集、帧尾预算消费 |
| `PromethiumLightBridge` | 并行光照引擎运行时桥接（反射发现 Promethium MOD；MOD 缺席自动降级官方引擎） |
| `ParallelLightEngine`（Promethium MOD 内） | 并行光照引擎：capture / solve / drain（`parallelLightEngineEnabled=true` 且 MOD 安装时） |
| `ClientMetadataHandler.handleLightDeltaPacket` | LightDelta 旁路本地重算 |
| `CacheSaveQueue` | 后台写盘（含光照回写） |
