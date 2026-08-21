# 区块加载优化

进服首波（ROUND1 VD20）与重连缓存（ROUND2）的加载路径对照、回归点与优化阶段。流水线协议细节仍以 [`chunk-cache.md`](chunk-cache.md) 为准；客户端收包/光照落地见 [`client-chunk-light-flow.md`](client-chunk-light-flow.md)。冒烟口径见 [`runtime-smoke-test.md`](runtime-smoke-test.md)。

**阶段纪律：性能先回到 d，再优化回 c。** 不能跳过 d 直接抄 c 的直装链——影子端要保留。也不能在未恢复「一直在加载」之前加绑核、加 JoinBoost 硬顶、加每 tick 发送上限来掩盖回归。

## 1. 三个锚点

同一台机器、classic 1.20.1 fabric、不绑核。c / d 的 ROUND1 窗口都是 **30s**（`delayMs=15000×2`）。当前 master 冒烟常用 **20s**（`delayMs=10000×2`），比速率时用「块/秒」，不要直接比累计块数。

| 锚点 | Git | 会话 | 窗口 | R1 新增 | R1 速率 | R2 缓存 | 观感 |
|------|-----|------|------|--------:|--------:|--------:|------|
| **c 直装** | `b515eb3` | `mto-baseline-20260821c` | 30s / R2 VD8 | 2042 | **68/s** | **97.3%** | 最快、缓存最好；客户端无影子端 |
| **d 影子完成、网关未起** | `1e3d1c1` | `mto-baseline-20260821d` | 30s / R2 VD10 | 1529 | **51/s** | **59.2%** | **一直在加载**，接近喂满 1681 |
| **当前 master** | `0763869` 及之后冒烟（fix13–17） | 如 `1.20.1_fabric_I_fix17` | 20s / R2 VD10 | 912 | **46/s** | 48.9% | 中途停：`remaining=0`、mesh cliff、要靠绑核抬 TPS |

c→d 只隔 **9** 个提交（影子端 + `ClientChunkPipeline`）。d→当前隔网关、配置 2.0、渐进推送、admission、连服即起影子、邻柱光屏障、ChunkMap 注入等 **数十** 个提交。

VD20 满方阵 `(2×20+1)² = 1681`。d 在 30s 内 1529 ≈ 91% 方阵且过程连续；当前 20s 内 912 且出现整秒 0 apply。

## 2. 阶段目标

| 阶段 | 门禁 | 验收（1.20.1 fabric classic，不绑核） |
|------|------|--------------------------------------|
| **A 回到 d** | 必须先过 | ROUND1 **全程有 apply**（无 t+9 类整秒停死）；30s 窗口新增 **≥1500** 或 20s 窗口按比例 **≥1000 且曲线不掉零**；R2 能进世界，缓存不低于 d（全命中+增量合计观感 ≥50%） |
| **B 优化回 c** | A 通过后 | R1 速率向 **68/s** 靠（30s 喂满 1681）；R2 全命中向 **97%** 靠；光照统计口径接上影子路径 |

绑核、把 `maxChunksPerTick` 从 4 拉到 16，都不是 A 的验收手段。d 用默认 4/tick、不绑核就能持续加载。

## 3. c vs d（背景，不是本阶段主战场）

两边服务端都是 `network.maxChunksPerTick=4`（满 tick ≈ 80/s）。差在客户端落地。

**c 直装**

```
解压（后台）
  → MainThreadDispatcher 预算队列
  → applyChunkData → handleLevelChunkWithLight
  → MixinLightRecompute TAIL 同步算光（算完即下一块）
Hash 命中 → ClientCacheLoadQueue 本地 NBT apply
```

**d 影子**

```
解压 → decode 官方包 → ShadowLightCompute.submit
  → consumeLoop：读盘？→ injectChunk 清光 + propagateLightSources
  → sleep 等到 isLightConverged()（最长 5s / 20ms 轮询）
  → buildPacket → ready
  → 帧尾 drainReady 一次倒进 handleLevelChunkWithLight
Hash → 影子内存/读盘比对 → 命中 pushReady；不中 requestFullChunks
```

c→d 掉速（68→51/s）和 R2 全命中崩（1319→50）的原因，已经能点名：

1. `consumeLoop` **整批等全局光照队列空** 才打包（最大串行点）。
2. 冷启动每柱 `loadFromDisk`（R1 缓存 0%，约 1500 次空读）。
3. 欠光 `markLightDirty` + 影子存档还没有 c 那套登出主线程 dump → R2 必 miss。
4. `drainReady` 无预算（d 仍「一直加载」，是因为收敛把 ready 流量掐小了）。

这些是 **B 阶段** 从 d 收向 c 的清单。A 阶段不要先改它们——当前相对 d 更差的回归不在这 9 个提交里。

## 4. d vs 当前（主战场）

d 已经有影子端、Bloom 分流、hash 查找链、客户端旧链清理。当前是在这之上叠加网关 / 2.0 配置 / 渐进推送 / admission / 原版对齐光照。冒烟上丢掉的是 **连续性**，不是「有没有影子」。

### 4.1 1.20.1 服务端：trackChunk 从「立刻入队」改成「pending 定额 drain」

**d**（`MixinServerPlayer.hassium$onTrackChunk`）：压缩开启后 `submitMetadataTask` 并 `ci.cancel()`。视距内已加载柱会一窝蜂进推送管理器，`dataQueues` 可以堆出 `remaining>0`，每 tick 只 drain `maxChunksPerTick`。

**当前**：只 `markChunkPendingToSend`。每 tick `drainPendingSends` 扫整表 `pendingSends`、主线程算 hash、按 `pacedSendBudget`（`min(maxChunksPerTick, admission 余量)`）入队，再 `drainPlayerQueueTick`。冒烟里 `[PROCESS_QUEUE] remaining=0` 每 tick 都出现——不是「没有区块要发」，是 **本 tick 定额吃完队列就空**，吞吐 = 定额 × 实际 TPS。

叠加 `ChunkAdmissionController`（`INITIAL_DESIRED_PER_TICK=9`、`MAX_PENDING_PER_PLAYER=384`）：进服前几 tick 即使配置 16 也先按 9 放行。TPS 掉到 ~8–10 时，供给跟着掉，客户端更容易把 ready 抽干后进入 0 apply。

`drainPendingSends` 对尚未 `getChunkNow` 的柱会保留，但对已加载柱会在主线程 `computeSectionHashes`。d 的 hash 在 `pushPool`。这是「我们的逻辑是否吞噬 Server tick」的首选嫌疑，不是影子端（影子在**客户端进程**）。

### 4.2 客户端：从「批收敛后持续倒 ready」改成「邻柱光屏障 + 区块/光同 FIFO」

**d `consumeLoop`**：注入一批 → 等 `isLightConverged()` → 整批 `pushReady` → 下一轮。慢（51/s）但 ready 会持续有货，`drainReady` 每帧有东西落地。

**当前**（`0763869` 一带）变成管道化两阶段，对齐原版 `ChunkStatus`：

- `initializeLight`（range=0）
- 等 **8 邻也完成建层** 再 `lightChunk`（range=1），`NEIGHBOR_PACK_WAIT_MS=2000`
- 区块包与光包 **同一 FIFO** 回传；`drainReady(frameDeadlineNs)` 有时间预算

这三项各自有正确性动机（屋檐天空光、旧空光后到盖暗、主线程尖峰），合在一起会在进服首波出现：

- 邻柱互相等待 → 管道 `PIPELINE_MAX_INFLIGHT` 占满 → 长时间不 `pushReady`
- FIFO 里积压的 light / `drainLightMasks` 先吃掉 JoinBoost 预算 → 区块包 0 落地
- 网格编译（`CHUNK_MESH`）把双 JVM 的 CPU 打满 → 专用服 TPS 腰斩 → 服务端定额供给再降 → 客户端更空

d **不需要绑核**。当前冒烟绑 0–4 / 5–15 后 TPS 从 ~5 回到 ~10，R1 仍只有 912——说明绑核只救了供给的一半，没救「中途停」。

### 4.3 其它会改变首波形状、但不是第一刀的

| 提交（示意） | 变化 | 对「一直加载」 |
|--------------|------|----------------|
| `68f7263` 起网关 | 登录桥、S2C 路由、握手时序 | 可能拖晚第一包；d 无网关也能持续加载 |
| `486a3dd` 渐进推送 | 移动方向加权、近环优先 | 站桩 VD20 不应停；移动时才是卖点 |
| `0df7b1f` 连服即起影子 + 对齐 apply/网格日志 | 进服就 spin 影子端；apply 与 mesh 日志对齐 | 客户端 CPU 与专用服抢核，放大 4.2 |
| `0763869` 注入进 ChunkMap + 原版打包 | 不再只用 `injectedChunks` 空壳表 | 注入更重；正确性修复，A 阶段不要整段回退 |
| 配置 2.0 `master.maxChunksPerTick` | 默认/冒烟曾到 8–16 | **加大定额救不了 0 apply**；d 是 4 |

### 4.4 明确不作为 A 阶段手段的改动

下列在后续排障里试过或讨论过，**不要**当成「回到 d」的补丁（会换一类正确性回归）：

- 重新打开 spreading FULL ticket
- 把未注入的 `scheduleChunkLoad` 打成 UNLOADED 中止
- 恢复原版 `null,null` 打包
- **等 8 邻才发第一包**（这正是 4.2 相对 d 的加时，A 阶段应拿掉或变成非阻塞）
- 在 `respreadNeighborLightSources` 里 `awaitEngineTaskDrain`
- 绑核 / 把定额加到 16 当验收

光照模型允许：**`initializeLight` + 一次 `lightChunk` 后立刻打包**，屋檐/天空不对也先落地，余光走 light 桥；空屋檐可从 sky mask 省略。这是 B 阶段向 c 收的方向，也兼容「不要等 8 邻」。

## 5. A 阶段工作顺序（先回到 d）

按「能不能单独证明」排序，一次只动一类，每步用同一 classic 冒烟（建议 30s 窗口与 d 对齐：`-DelayMs 15000`，不绑核，预生成世界）。

1. **回传不要让光包饿死区块包。** `drainReady` 在 JoinBoost 期间优先 chunk、限制 light mask 占用；不要用「整帧 0 chunk」去消化 FIFO 旧光。验收：ROUND1 秒级 apply 不掉到 0。
2. **第一包不要等 8 邻。** 邻柱补光改为包已落地之后的桥接，而不是打包门槛。验收：`consumeLoop` / 管道日志里出现稳定 `pushReady`，不再卡在 `readyNeighbors`。
3. **查服务端 tick 是否被 drainPendingSends 吃掉。** `TickMonitor` 的 `[MSPT] server` 挂在 `tickServer` TAIL 之后，**不含** Hassium TAIL 自身耗时；要在 `hassium$onServerTick` 内对 `drainPendingSends` / `drainPlayerQueueTick` / `flushServer` 分段计时。若主线程 hash 扫描占数毫秒以上，把比对移回 d 的 `pushPool`。验收：不绑核 TPS 稳定在可发完定额的水平（不必 20，但不要掉到 8 还外加 0 apply）。
4. **admission / paced pending 不要把进服队列抽空后停转。** `remaining=0` 本身只说明定额=入队；若 pending 里还有已加载柱却连续多 tick `prepared=0`，才是卡住。对照 d：允许 `dataQueues.remaining>0`。
5. **连服即起影子不要和网格编译抢死主线程预算。** 影子可后台创建（d 的 `onLogin` 已是后台 `getOrCreate`）；不要把 apply 门控在 mesh 完成上。

A 阶段通过后，才允许动 B。

## 6. B 阶段工作顺序（再优化回 c）

影子链保留。把 d 相对 c 多付的税减掉：

1. 注入后 **立刻** `pushReady`，不等 `isLightConverged()`；欠光后续覆盖 / LightDelta。立刻打包后 `drainReady` **必须**走 `ClientMainThreadBudget`（否则会重现当前的 mesh cliff）。
2. 全量直推路径 **禁止**每柱 `loadFromDisk`；读盘只留在 `handleRemoteHashes`（R2）。
3. 登出 `saveAll` 对齐 c 的主线程 dump；不要把第一包欠光打成 R2 必 miss（收敛超时才 `markLightDirty`）。
4. `consumeLoop` 流水线：边注入边让引擎跑、按柱 pack，而不是「全注入 → 全等待 → 全打包」。
5. 把影子命中/重算记进 `NetworkStats`，否则 ROUND1 光照 0 次无法和 c 比。

## 7. 冒烟怎么比

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\scripts\runtime-smoke-test.ps1 `
  -Ver "1.20.1" -Loader fabric -Phase I `
  -SessionId "<label>" -CleanWorld -DelayMs 15000 `
  -ServerReadyTimeoutSec 360 -ClientTimeoutSec 360
```

- **不要**默认绑核（脚本若已加 CPU pin，加 `-NoCpuPin`）。
- 看客户端 `WAIT_JOIN_1` → `CLIENT_STATS ROUND1` 之间是否**每秒都有** apply / `Sent chunk` / `pushReady`，而不是只看公式 PASS。
- 服务端搜 `remaining=`：d 允许大于 0；当前若恒为 0 且 `prepared` 突然变 0，记为停滞而不是「队列健康」。
- 锚点日志：`build/smoke-test/results/result_mto-baseline-20260821c.json`（c）、`result_mto-baseline-20260821d.json`（d）。

## 8. 相关代码

| 路径 | d 角色 | 当前相对 d |
|------|--------|------------|
| `mixin/MixinServerPlayer` 1.20.1 `trackChunk` | 立刻 `submitMetadataTask` | `markChunkPendingToSend` |
| `network/ServerChunkPushManager` | tick drain 定额；无 admission | `drainPendingSends` + `ChunkAdmissionController` |
| `network/seedgen/ShadowLightCompute` | 批收敛 → 无预算 `drainReady` | 邻柱屏障 + FIFO 光包 + 带预算 `drainReady` |
| `network/seedgen/ShadowSeedServer.injectChunk` | 空壳 + `injectedChunks` | ChunkMap 正规加载 + 原版打包 |
| `mixin/MixinClientTick` | flush 预算队列后无上限 drain | `drainReady(deadline)` 与 dispatcher 抢同一帧 |

[← architecture](architecture.md) · [chunk-cache](chunk-cache.md) · [client-chunk-light-flow](client-chunk-light-flow.md) · [runtime-smoke-test](runtime-smoke-test.md)
