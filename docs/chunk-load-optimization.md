# 区块加载优化（历史锚点）

> **历史语境（2026-08-21 基线波）**：本文记录 c/d 双锚点与「A 回到 d、B 优化回 c」的阶段纪律。2.0.0 直连拓扑裁剪（网关/UDP/admission 退役）与原版对齐交付（`47e4a4a` 波）后，§4/§5 提及的网关、`ChunkAdmissionController`、渐进推送等已不在现行链路；阶段目标已完成收敛。保留本文作为性能方法论与锚点参考；现行链路见 [`architecture.md`](architecture.md) §6、[`chunk-cache.md`](chunk-cache.md) §3。

进服首波（ROUND1 VD20）与重连缓存（ROUND2）的加载路径对照、回归点与优化阶段。流水线协议细节仍以 [`chunk-cache.md`](chunk-cache.md) 为准；客户端收包/光照落地见 [`client-chunk-light-flow.md`](client-chunk-light-flow.md)。冒烟口径见 [`runtime-smoke-test.md`](runtime-smoke-test.md)。

**阶段纪律：性能先回到 d，再优化回 c。** 不能跳过 d 直接抄 c 的直装链——影子端要保留。也不能在未恢复「一直在加载」之前加绑核、加 JoinBoost 硬顶、加每 tick 发送上限来掩盖回归。

## 1. 三个锚点

同一台机器、classic 1.20.1 fabric、不绑核。c / d 的 ROUND1 窗口都是 **30s**（`delayMs=15000×2`）。当时 master 冒烟常用 **20s**（`delayMs=10000×2`），比速率时用「块/秒」，不要直接比累计块数。

| 锚点 | Git | 会话 | 窗口 | R1 新增 | R1 速率 | R2 缓存 | 观感 |
|------|-----|------|------|--------:|--------:|--------:|------|
| **c 直装** | `b515eb3` | `mto-baseline-20260821c` | 30s / R2 VD8 | 2042 | **68/s** | **97.3%** | 最快、缓存最好；客户端无影子端 |
| **d 影子完成、网关未起** | `1e3d1c1` | `mto-baseline-20260821d` | 30s / R2 VD10 | 1529 | **51/s** | **59.2%** | **一直在加载**，接近喂满 1681 |
| **当时 master** | `0763869` 及之后冒烟（fix13–17） | 如 `1.20.1_fabric_I_fix17` | 20s / R2 VD10 | 912 | **46/s** | 48.9% | 中途停：`remaining=0`、mesh cliff、要靠绑核抬 TPS |

c→d 只隔 **9** 个提交（影子端 + `ClientChunkPipeline`）。d→当时隔网关、配置 2.0、渐进推送、admission、连服即起影子、邻柱光屏障、ChunkMap 注入等 **数十** 个提交。

VD20 满方阵 `(2×20+1)² = 1681`。d 在 30s 内 1529 ≈ 91% 方阵且过程连续；当时 20s 内 912 且出现整秒 0 apply。

## 2. 阶段目标（历史）

| 阶段 | 门禁 | 验收（1.20.1 fabric classic，不绑核） |
|------|------|--------------------------------------|
| **A 回到 d** | 必须先过 | ROUND1 **全程有 apply**（无 t+9 类整秒停死）；30s 窗口新增 **≥1500** 或 20s 窗口按比例 **≥1000 且曲线不掉零**；R2 能进世界，缓存不低于 d（全命中+增量合计观感 ≥50%） |
| **B 优化回 c** | A 通过后 | R1 速率向 **68/s** 靠（30s 喂满 1681）；R2 全命中向 **97%** 靠；光照统计口径接上影子路径 |

绑核、把 `maxChunksPerTick` 从 4 拉到 16，都不是 A 的验收手段。d 用默认 4/tick、不绑核就能持续加载。

## 3. c vs d（背景）

两边服务端都是 `master.maxChunksPerTick=4`（满 tick ≈ 80/s）。差在客户端落地。

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

c→d 掉速（68→51/s）和 R2 全命中崩（1319→50）的原因：

1. `consumeLoop` **整批等全局光照队列空** 才打包（最大串行点）。
2. 冷启动每柱 `loadFromDisk`（R1 缓存 0%，约 1500 次空读）。
3. 欠光 `markLightDirty` + 影子存档还没有 c 那套登出主线程 dump → R2 必 miss。
4. `drainReady` 无预算（d 仍「一直加载」，是因为收敛把 ready 流量掐小了）。

这些是 **B 阶段** 从 d 收向 c 的清单。

## 4. d vs 当时 master（主战场，历史）

d 已经有影子端、Bloom 分流、hash 查找链、客户端旧链清理。当时是在这之上叠加网关 / 2.0 配置 / 渐进推送 / admission / 原版对齐光照。冒烟上丢掉的是 **连续性**，不是「有没有影子」。

### 4.1 服务端：trackChunk 从「立刻入队」改成「pending 定额 drain」（已随直连拓扑再收敛）

**d**（`MixinServerPlayer.hassium$onTrackChunk`）：压缩开启后 `submitMetadataTask` 并 `ci.cancel()`。

**当时**：只 `markChunkPendingToSend`，每 tick `drainPendingSends` 扫表、主线程算 hash、按 paced 预算入队；叠加 `ChunkAdmissionController`（`INITIAL_DESIRED_PER_TICK=9`、`MAX_PENDING_PER_PLAYER=384`）。**现行（2.0.0）**：admission 已退役，`ServerChunkPushManager` 回到原版 tracking 推送 + 每 tick 提交上限（`master.maxChunksPerTick`），主线程 hash 扫描不再存在。

### 4.2 客户端：从「批收敛后持续倒 ready」改成「邻柱光屏障 + 区块/光同 FIFO」

**d `consumeLoop`**：注入一批 → 等 `isLightConverged()` → 整批 `pushReady` → 下一轮。慢（51/s）但 ready 会持续有货。

**当时**：管道化两阶段（`initializeLight` → 等 8 邻建层 → `lightChunk`，`NEIGHBOR_PACK_WAIT_MS=2000`）+ 区块/光同 FIFO + `drainReady(frameDeadlineNs)`。三项各自有正确性动机（屋檐天空光、旧空光后到盖暗、主线程尖峰），合在一起导致进服首波邻柱互等、FIFO 旧光吃掉 JoinBoost 预算、mesh 编译打满双 JVM CPU。

**现行（2.0.0）**：`isChunkLightComplete` 不挡首包（欠光可先落地，光桥后补）；`drainReady` 帧尾预算内落地；光桥只对「影子区块包已落地且客户端未卸载」的柱发送。原版对齐交付（`47e4a4a` 波）后「进范围必交付」不再被防抖拦截。

### 4.3 明确不当补丁的改动（仍然有效的方法论）

下列在排障里试过或讨论过，**不要**当成「回到 d」的补丁（会换一类正确性回归）：

- 重新打开 spreading FULL ticket
- 把未注入的 `scheduleChunkLoad` 打成 UNLOADED 中止
- 恢复原版 `null,null` 打包
- **等 8 邻才发第一包**
- 在 `respreadNeighborLightSources` 里 `awaitEngineTaskDrain`
- 绑核 / 把定额加到 16 当验收

光照模型允许：**`initializeLight` + 一次 `lightChunk` 后立刻打包**，屋檐/天空不对也先落地，余光走 light 桥；空屋檐可从 sky mask 省略。

## 5. 冒烟怎么比（方法论仍有效）

```powershell
pwsh -ExecutionPolicy Bypass -File .\scripts\runtime-smoke-test.ps1 `
  -Ver "1.20.1" -Loader fabric -Phase I `
  -SessionId "<label>" -CleanWorld -DelayMs 15000 `
  -ServerReadyTimeoutSec 360 -ClientTimeoutSec 360
```

- **不要**默认绑核。
- 看客户端 `WAIT_JOIN_1` → `CLIENT_STATS ROUND1` 之间是否**每秒都有** apply / `Sent chunk` / `pushReady`，而不是只看公式 PASS。
- 服务端搜 `remaining=`：d 允许大于 0；恒为 0 且 `prepared` 突然变 0，记为停滞而不是「队列健康」。
- 锚点日志：`build/smoke-test/results/result_mto-baseline-20260821c.json`（c）、`result_mto-baseline-20260821d.json`（d）。

## 6. 相关代码（现行对照）

| 路径 | d 角色 | 现行（2.0.0） |
|------|--------|------------|
| `mixin/MixinServerPlayer` 1.20.1 `trackChunk` | 立刻 `submitMetadataTask` | `<init>` TAIL 消费协商位 + 压制原版区块窗口（`ServerHandshakeActivation.onPlayerInit`） |
| `network/ServerChunkPushManager` | tick drain 定额；无 admission | 原版 tracking 推送 + 每 tick 提交上限（admission 已退役） |
| `network/seedgen/ShadowLightCompute` | 批收敛 → 无预算 `drainReady` | 邻柱屏障不挡首包 + 带预算 `drainReady` + 光桥 |
| `network/seedgen/ShadowSeedServer.injectChunk` | 空壳 + `injectedChunks` | ChunkMap 正规加载 + 原版打包 + `onChunkMaterialized` 必交付 |
| `mixin/MixinClientTick` | flush 预算队列后无上限 drain | `drainReady(deadline)` 帧尾预算内落地 |

[← architecture](architecture.md) · [chunk-cache](chunk-cache.md) · [client-chunk-light-flow](client-chunk-light-flow.md) · [runtime-smoke-test](runtime-smoke-test.md)
