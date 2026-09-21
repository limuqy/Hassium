# handoff — SeedGen 本地生成改「选柱显式驱动」（A1-③ 接线）

关联：[`handoff-2026-09-18-shadow-vanilla-align.md`](handoff-2026-09-18-shadow-vanilla-align.md) §1 A1 三条链（已拍板，**本文件不修改该模型**）、
[`chunk-cache.md`](../chunk-cache.md) §3.2 统一 ShadowPull、[`full-smoke-report-2026-09-20.md`](../archive/full-smoke-report-2026-09-20.md) §6。
C2ME 参照源：`D:\project\MC\C2ME-fabric-ver-1.20.1`（1.20.1 fabric）。

## 0. 问题

`seedgen` + 会话中切维 = **新维度零交付**（`1.21.1_fabric_I_dimension` FAIL 场，2026-09-20）。

**已验证现象**（产物 `build/smoke-test/logs/client_1.21.1_fabric_I_dimension.log`）：

- `:27575` reseat → `:27603` `tracking session started (dimension=minecraft:the_nether)`，`placeNewPlayer` 走完（有 `HassiumShadow joined the game`）。
- 此后 40s（→ `:28042` 退出）：**全日志 `the_nether` 只出现 8 次**（装配/reseat/断言），无任何 materialize；
  `probe/…_dimension/round2.json`：`loadedChunks=0`、trace 全 0、`disk.dimensions.nether.regionFileCount=-1`。
- 主世界侧 reseat 后仍产出约 1s（`[LIGHT_CALL]`/materialized 最后都在 `21:42:10`）后静默。
- 对照（seedGen=false，PASS）：下界靠**网络 pull**（round2 `rx=1705 / injected=1705 / locallyGenerated=0`）——**「切维后能否本地生成」在既有 PASS 场里从未被验证**。

**根因（已验证代码链）**：影子端不调用 `prepareLevels()`（`ShadowSeedServer.java:241` 明确注释），新维度是空世界；
玩家实体要等所在 section 变 TRACKED/TICKING 才走 `EntityCallbacks.onTrackingStart` → `ChunkMap.addEntity` → `updatePlayerStatus(player,true)` → **玩家票**
（1.21.1 mojmap：`ServerLevel.java:1625-1632`、`ChunkMap.java:911-930`）。拿不到票 ⇒ `ChunkMap.scheduleChunkLoad` 永不触发 ⇒ 门控开时的「放行原版生成」永不发生。

## 1. 现状与断点：A1-③ 被拆成两半，中间无人负责

| A1-③ 步骤 | 现实现 | 状态 |
|---|---|---|
| 选柱 | 波前 `ShadowTrackingSession.drainAuthorityAcquires`（200ms，每轮重扫计算域） | ✅ 在跑 |
| **seedGen 作基线** | **原版 tracking 隐式生成**：`MixinChunkMap.hassium$shortCircuitInjectLoad` 门控开时 `return` 放行原版生成（`MixinChunkMap.java:133-137` / `:157-161`） | ⚠️ 依赖玩家票 → 切维即死 |
| → compare | 物化桥 `onChunkMaterialized`（`ShadowTrackingSession.java:1342`）→ `SeedGenCompareGate.mark` + `requestPullEligible`（`:1396-1409`） | ✅ 在跑 |

波前对「门控开 + 无基线」的候选柱是 `continue`（`:609-612`，**这是主断点**——`continue` 后既不发 pull 也不生成，
等于「谁都不驱动」），`acquire` 里也短路成 `failedFuture`（`VanillaAlignedChunkProvider.java:111-115`，**次断点**）。
**两处都要改**：只改 `acquire` 没用（波前根本走不到它）——2026-09-20 首次实现即踩此坑（冒烟 `fix1`：`acquire -> local generate` 计数为 0）。

**澄清两条易误读的事实**：

1. `ShadowChunkAcquire.decideSelection` 的 `SEEDGEN_LOCAL` 分支（`:101-103`，javadoc 写着「SeedGen 门控开且无本地基线 → 本地生成」）**是死代码**——`tryAcquireForAuthority` 零调用者；`ShadowTrackingSession.emitPullGroups`（`:1288`）同样零调用者。**这只说明决策代码没接线，不代表 `chunk-cache.md` §3.2 过时**：§3.2 的「候选区块 → {本地生成 | 磁盘缓存} → ShadowPull」与 A1 是同一条链，模型不变。
2. 「影子端自己驱动的本地生成」**当前并不存在**：`ShadowSeedServer.generateChunkAsync`（`:367`）/`pinForcedTicket`（`:307`）零调用者，`ShadowChunkMapCompat.enterWorldgen()` 唯一调用者在这条死链上（`ShadowSeedServer.java:389`）。

## 2. 设计（接法）

**原则**：不改 A1 模型；只补上「谁驱动生成」。生成仍走**原版生成链**（`getChunkFuture(FULL)`）。

### 2.1 核心：只加显式触发，**不动** `MixinChunkMap` 的放行

`VanillaAlignedChunkProvider.acquire` 的 seedgen 分支（`:111-115`）从 `failedFuture` 改为「登记在途 + 交生成，返回未完成 future」：

```java
if (SeedGenExecutor.getInstance().isGenerationGateOpen()
        && !ShadowLightCompute.hasLocalPullBaseline(dimension, pos)) {
    CompletableFuture<LevelChunk> future = new CompletableFuture<>();
    inflight.put(key, future);                       // 与 pull 同款在途管理
    ShadowTrackingSession.getInstance().generateLocalAsync(dimension, pos);   // 新方法
    return future;
}
```

`ShadowTrackingSession.generateLocalAsync`（新增，影子主循环线程调用）：

```java
ShadowSeedServer server = boundServer;
server.generateChunkAsync(dimension, pos, (dim, chunk) -> {   // 回调已在主循环（:374-380）
    if (chunk == null) {
        // 生成失败/取消/超时 → A1-② 回退，禁止留洞
        VanillaAlignedChunkProvider.failAcquire(dim, pos);
        if (markPullInFlight(dim, pos, now)) ShadowPullClient.requestAuthoritativeFull(dim, List.of(pos));
    } else {
        onChunkMaterialized(dim, pos, chunk);                 // A1-③ 尾部：注入 + mark + compare
        ShadowChunkMapCompat.completeSuspendedLoad(dim, pos, chunk);  // 放行 holder（内部也会 completeAcquire）
    }
});
```

**为什么「门控开仍然放行原版生成」（即不关那条隐式路径）**：

1. **原版 holder 天然去重**：同一柱的两条驱动（tracking 触发 / 我们显式 `getChunkFuture(FULL)`）落在**同一个 `ChunkHolder`** 上，`getChunkFuture` 是 join 语义 ⇒ 不会重复生成。所以「驱动唯一化」没有收益。
2. **关掉会与 C2ME 的邻域锁打架**（见 §2.4）——那是本次调研最重要的发现。
3. 关掉会把「R1 行为变化」从风险变成现实（生成只由波前候选集合驱动）；保留则 R1 行为不变。
4. **最小改动**：`MixinChunkMap` 一行不改 ⇒ 无自锁分析、无放行窗口时序约束、无 C2ME 交互。

**这条推翻了本文件初版的「按柱放行窗口」方案**（初版要求 `MixinChunkMap` 判据收窄 + 新增 `GENERATING` 集合）。理由见 §2.4。

### 2.1b 为什么不能「关闭原版生成」（= 把门控开也改成悬置）——**自锁，不可实现**

用户口径澄清（2026-09-20）：「关闭影子端自己驱动的本地生成」= **关闭原版生成**，让生成只由我们显式驱动。
这条路在代码上**不成立**，不是取舍问题：

1. 要「关闭原版生成」，`MixinChunkMap.hassium$shortCircuitInjectLoad` 就必须在门控开时也**悬置**
   （返回一个只有 Hassium 才能 complete 的 future），否则原版链照跑。
2. 而我们的显式生成走 `getChunkFuture(pos, FULL, true)`——它**必然**驱动同一条 status 链，
   链的 EMPTY 步就是 `scheduleChunkLoad`。
3. ⇒ 一旦某柱**先**被原版链触发过（主世界 tracking 会，切维后的空世界不会），
   `SUSPENDED_LOADS[key]` 里就有一个未完成的 future；我们的 `getChunkFuture(FULL)` 是**join** 语义，
   会挂在这个我们自己悬置的 future 上等 ⇒ **唯一能放行它的是我们的生成回调，而我们的生成永远等不到回调** = 死锁。
4. 唯一出路是「按柱放行窗口」让**我们自己**那次 EMPTY 步放行——但第 3 步的悬置发生在窗口打开**之前**
   （tracking 先到），窗口救不了它。

⇒ 结论：**「生成由谁驱动」这个问题的答案只能是「谁触发 `scheduleChunkLoad`」，不能是「谁能放行它」**。
本设计的做法正是前者：**不碰放行**，只在「无人触发」时补一个触发者（波前 → `generateChunkAsync`）。

**这与「驱动唯一」的目标差在哪**：差在「主世界里原版 tracking 也会触发一部分生成」。但那部分与我们的触发
**落在同一个 `ChunkHolder` 上**（`getChunkFuture` join），不产生重复生成；而**功能上原版触发已不再必需**——
切维 / 空世界下没有它，生成照跑（这正是本次要修的场景）。
若要连「行为可预测性」也一并解决（不依赖票），那是**另一件事**：让影子选柱不再依赖虚拟玩家，
已在本次后续改动中由显式 tracking 波前接管。

### 2.2 失败/取消/超时的回退（必须有）

生成失败（`chunk == null`）→ `failAcquire` + `requestAuthoritativeFull`（A1-②）。
**注意**：`generateChunk` 返回 null 的三条来源 —— 超时（`ShadowSeedServer.java:273`）、`awaitGeneratedChunk` join 异常（`ShadowServerCompat.java:113-118`）、维度未装配（`ShadowSeedServer.java:291-294`）。三者都要走回退，否则成永久洞。

### 2.3 `onChunkMaterialized` 幂等（**实现前必须先补**）

显式生成路径会让同一柱**两次**进入 `onChunkMaterialized`：① 生成回调；② holder 推到 FULL 后 `onChunkReadyToSend` 桥（`MixinChunkMap.java:180-192`；下界无玩家票时不会触发，主世界会）。

现状只能挡住「compare 仍在途」那一半：`SeedGenCompareGate.isAwaiting` 的 return（`:1411-1414`）。一旦权威裁决落地（`confirm` 清 AWAITING、置 CONFIRMED），二次进入会落到最后一块「先 mark 再请求」（`:1441-1456`）⇒ **多发一次 compare**。

**已验证 `confirm` 在三种裁决上都调用**：UNCHANGED → `ShadowPullClient.java:469-470`；FULL/DELTA → `ShadowSeedServer.injectChunk:463`。
⇒ 在 `onChunkMaterialized` 的「先 mark 再请求」块前加与泵同判据的短路（`drainAuthorityAcquires:578-580` 同款）：

```java
if (SeedGenCompareGate.isConfirmed(dimension, pos)
        || ShadowLightCompute.wasNetworkIngressAccounted(dimension, pos)) {
    return;   // 本会话已付过账：不再发 compare
}
```

### 2.4 C2ME 兼容（`D:\project\MC\C2ME-fabric-ver-1.20.1`）

**C2ME 的调度模型（已验证源码，可直接照抄语义）**：

| 关注点 | C2ME 做法 | 锚点 |
|---|---|---|
| 线程池 | `Executors.newFixedThreadPool(GLOBAL_EXECUTOR_PARALLELISM, C2MENormalWorkerThreadFactory("c2me", "C2ME worker #%d", NORM_PRIORITY-1))`，daemon + 独立 ThreadGroup | `c2me-base/common/GlobalExecutors.java:17-25` |
| 并行度 | **表达式自动求值，不手动指定**：`max(1, min( cpus/1.6-2(Windows) 或 cpus/1.2-2, 内存约束 ) - (客户端 ? 2 : 0))`；可用配置覆盖 | `c2me-base/ModuleEntryPoint.java:19-33` |
| **预算/队列** | `SchedulingManager`：**优先级队列 + `maxScheduled = 并行度 × 2` 的并发在途上限**；`enqueue` 丢给**单线程** `c2me-sched`（`ThreadPoolExecutor(1,1)` + 无界队列）串行决策；`scheduledCount < maxScheduled` 才取队首 | `c2me-base/common/scheduler/SchedulingManager.java:32-148`、`GlobalExecutors.java:34-37` |
| 优先级 | `min(票等级, 与 currentSyncLoad 的切比雪夫距离≤8 加权)` = **同步加载点附近优先** | `SchedulingManager.java:61-82/150-156` |
| **超时** | **没有超时**；用**取消**：`ChunkStatusUtils.isCancelled(holder, status)` = 票等级已低于目标状态 ⇒ 取消 + `releaseLightTicket` | `c2me-threading-worldgen/common/ChunkStatusUtils.java:92-94`、`mixin/MixinChunkStatus.java:88-93` |
| 邻域锁 | `NeighborLockingTask`：按 `lockRadius` 抢 `NeighborLockingManager` 的柱锁，**直到 action 的 future 完成才释放** | `c2me-base/common/scheduler/NeighborLockingTask.java:35-90` |
| 线程切换优化 | `invokingExecutor`：同 ThreadGroup 则 inline，否则丢池 | `GlobalExecutors.java:26-32` |

**与 Hassium 的交互面（逐条核过）**：

1. **无方法级碰撞**：C2ME 的 `@Overwrite` 目标是 `ThreadedAnvilChunkStorage`（= `ChunkMap`）的**synthetic 方法**（`method_17259/17252/19486/19487/20579`）与 `ChunkStatus.runGenerationTask`；Hassium 注入的是 `scheduleChunkLoad` / `playerLoadedChunk` / `onChunkReadyToSend` / `save`。**不重合**。
   - 注意：C2ME 的 `redirectGetChunkHolder` 用 `chunkHolders.get(pos)` 直读；Hassium 用 `getVisibleChunkIfPresent`。不同方法。
2. **⚠️ 悬置 = C2ME 邻域锁长持**：`MixinChunkStatus.runGenerationTask` 被 C2ME `@Overwrite`，把每个生成步包进 `NeighborLockingTask`，而**锁只在返回的 future 完成时释放**（`NeighborLockingTask.java:66-83`）。Hassium 的 `scheduleChunkLoad` 悬置 future **永不自证完成**（要等 Hassium 放行）⇒ **该柱的锁被持住**。
   - **量级（2026-09-20 复核，修正初版的夸大表述）**：悬置发生在 `scheduleChunkLoad` = **EMPTY 步**，而 EMPTY 的 `taskMargin = 0` ⇒ C2ME 的 `reducedTaskRadius = 0`（`MixinChunkStatus.java:55-57`）⇒ 锁集合 = **该柱自身**，不波及邻柱。故代价是「同柱的其它任务要排队」，不是「邻柱被堵」。
   - 但**方向不变**：把门控开也改成悬置 = 给 seedgen 路径新引入这条长持；而门控关时这条代价是设计要的（等 pull 数据，ms 级到达）。
   - ⇒ 配合 §2.1b 的自锁结论：**不关放行**是双重正确的选择。
3. **C2ME 会绕过 Hassium 的 executor 隔离（【已验证-代码】，【推断】运行期影响）**：Hassium 在 `MinecraftServer.<init>` 用 `@Redirect` 把 `Util.backgroundExecutor()` 换成 `ShadowWorldgenExecutor`（`mixin/server/MixinMinecraftServer.java:37-58`）；但 C2ME 的 `PARALLELIZED` 状态直接跑在 `GlobalExecutors.executor`（`ChunkStatusUtils.java:110-118`），**不经 server executor**。⇒ C2ME 在场时，影子端 worldgen 的实际执行池是 **C2ME 的**，Hassium 的 `workerCount` 只在无 C2ME 时生效。
   - 运行期确认口径：装 C2ME 的冒烟场里看 `[LIGHT_CALL] … thread=` 是 `C2ME worker #N` 还是 `hassium-shadow-worldgen-N`。
4. **`asyncScheduling=true`（默认）会把 `getRegion` 的 continuation 从 `mainThreadExecutor` 挪到 C2ME worker 或 inline**（`c2me-threading-worldgen/mixin/MixinThreadedAnvilChunkStorage.java:86-99`）⇒ `scheduleChunkLoad` **可能被 C2ME worker 线程调用**。
   - ⇒ 本次新增的状态必须线程安全（`ConcurrentHashMap`），且**不要在这条路径新增客户端状态读取**（现有 `deliveryCenter()` 读 `Minecraft.getInstance().player` 已在其中，本次不扩大）。
5. **可复用**：`invokingExecutor` 的同 ThreadGroup inline 模式；`isCancelled` 判据（比超时准）；`maxScheduled = 并行度 × 2` 的常量语义。

## 3. 参数化（按用户口径：能自动就不手动指定）

| 项 | 现状 | 决定 |
|---|---|---|
| 线程数 | `ShadowWorldgenExecutor.workerCount = max(1, min(255, processors - 2))`（`:34-36`） | **不加配置键**；算式对齐 C2ME 形式（补内存约束 + 客户端 −2）。C2ME 在场时按 §2.4-3 实际用 C2ME 的池 |
| 单柱生成超时 | `GENERATION_TIMEOUT_NANOS = 15s`（`ShadowSeedServer.java:273`） | 保留**常量**（兜底）；主路径改用 C2ME 式取消判据（见 §2.2 的三条 null 来源） |
| 生成在途上限 | 现为「每轮 `authorityAcquireBudget` = `maxChunksPerFrame × 4`」 | **新增常量** `GENERATION_MAX_INFLIGHT = 并行度 × 2`（C2ME 语义），与波前每轮提交配额解耦 |

## 4. 明确不做（边界）

- **不改 A1 模型 / §3.2 文档语义**（本次只是接线）。
- **不改 `MixinChunkMap` 的门控开放行**（§2.1 的 4 条理由）。
- **不删玩家 tracking**（`ensureVirtualPlayer` / `moveVirtualPlayer` / `tickChunkSystem` 保留）：它仍是原版几何与 `deliveryCenter()` 的来源，且 `ChunkMap` 需要被 tick。
- **不动 `LightNeighborhoodGate` 与算光红线**；**不动交付域**（`isDeliverableToClient` = `serverVD` ∪ OVD 带）。
- **不复用 C2ME 的 `SchedulingManager` 类型**（跨 mod 反射耦合不值得）；只**照抄语义**（在途上限常量、取消判据、invokingExecutor 模式）。

## 5. 落地顺序（最小风险优先）

1. **S1**：`onChunkMaterialized` 补 `isConfirmed` 幂等短路（§2.3）——独立可验证，先落。
2. **S2**：`acquire` 接 `generateLocalAsync` + 三条 null 来源的回退（§2.1/§2.2）。
3. **S3**：`GENERATION_MAX_INFLIGHT` 常量 + C2ME 式取消判据 + 线程算式对齐（§3）。
4. **S4**：删死代码（`generateChunk(ChunkPos)`、`ShadowChunkAcquire.tryAcquireForAuthority/decideSelection`、`emitPullGroups`）——**先确认无回退需求再删**。
5. **S5**：跨维在途 compare 残留修复（§7.5）。
6. **S6**：`seedgen` + `dimension` 组合冒烟；**再加一场装 C2ME 的**（§2.4 的池/线程名要实测确认）。

## 6. 验收口径

| 场景 | 判据 |
|---|---|
| `seedgen` 单轮（不切维） | PASS；`locallyGenerated` 与既有基线同量级（1835±）；R1 交付 1529；compare 次数**不翻倍**（§2.3 生效）；`[LIGHT_CALL]` 线程池与改动前一致（证明「不关放行」= 行为不变） |
| `seedgen` + `dimension`（本 bug） | 下界轮 `loadedChunks>64`、`clientApplied>64`；`disk.dimensions.nether.regionFileCount ≥ 0`；日志出现 `(dimension=minecraft:the_nether)` 的 materialize |
| C2ME 在场 | 同上 + 无 `c2me` 相关异常；确认 worldgen 实际执行池（`C2ME worker #N` vs `hassium-shadow-worldgen-N`） |
| 回归 | `classic` 三锚点不退化；`seedGen=false` 的 `dimension` 场不变（A1-② 路径未被触碰） |

## 7. 风险与未决

1. **光环柱生成量**：门控开时波前对 halo 柱也 `continue`（无基线）——显式驱动后它们会被生成（本就需要算光）。量级需确认不爆。
2. **`onChunkReadyToSend` 在下界不触发**（无玩家票）⇒ 下界只有生成回调这一条入口，`completeSuspendedLoad` 是唯一放行点——**两条都要做**，只做一条会留洞。
3. **生成取消 vs 悬置的叠加**：C2ME 会取消已降级任务（§2.4-2），Hassium 的 `SUSPENDED_LOADS` 里的 future 需有人 complete。本次不新增悬置（§2.1），但**既有的门控关悬置路径**在 C2ME 在场时的行为未实测——列为观察项，不在本次改动范围。
4. **`generateChunkAsync` 的 FORCED 票**（`ShadowSeedServer.java:307-344`）：每柱一钉一撤，只在主循环增删票。生成在途上限（§3）就是它的并发闸门，**超上限会把票风暴**——必须实现该常量。
5. **独立缺陷（本次一并修，用户已确认）**：切维时在途的主世界 compare 被真服按 `player.level()` 拒（`[SHADOW_PULL] Request rejected … : dimension` 43 条），`SeedGenCompareGate.AWAITING` 残留（终场 `STALL-DIAG pendingAuth=109`）⇒ 返主世界时那些柱成永久洞。修法：切维时清本维在途 compare（`ShadowPullClient.onClientDimensionChanged` 侧）。

## 8. 实现记录（2026-09-20）

| 序 | 状态 | 落点 |
|---|---|---|
| S1 幂等短路 | ✅ 已落 | `ShadowTrackingSession.onChunkMaterialized` 的「先 mark 再请求」前加 `SeedGenCompareGate.isConfirmed(...) || wasNetworkIngressAccounted(...)` |
| S2 显式生成 + 回退 | ✅ 已落（**含首轮漏改，见下**） | ① `ShadowTrackingSession.drainAuthorityAcquires` **删掉 seedgen 的 `continue`**（主断点，`fix1` 实测 `acquire -> local generate` 计数为 0 即此）；② `VanillaAlignedChunkProvider.acquire` seedgen 分支改「登记 inflight + `generateLocalAsync` + 返回未完成 future」；计算域闸上移到该分支之前；③ 新增 `ShadowTrackingSession.generateLocalAsync`（成功 → `onChunkMaterialized` + `completeSuspendedLoad`；失败 → `failAcquire` + `requestPullEligible(authoritative)`） |
| S3 在途常量 + 线程 | ✅ 已落 | `ShadowSeedServer.generationMaxInFlight()` = `workerCount × 2`（C2ME `maxScheduled` 语义，下限 4）+ `generationInFlight()` 计数；`localGenExecutor` 线程数由固定 4 改为 `ShadowWorldgenExecutor.workerCount(processors)`；波前 `drainAuthorityAcquires` 在 acquire 前按该上限**跳过本轮**（不建 future，下一轮重试） |
| S5 跨维请求闸 | ✅ 已落 | `ShadowTrackingSession.requestPullEligible` 开头加维度闸：`dimension != currentDimension` → 直接 return（切维后旧维迟到链不再发 compare，消掉 `Rejection.DIMENSION` 浪费） |
| S7 不重复驱动 | ✅ 已落 | `drainAuthorityAcquires` acquire 分支前加 `gateOpen && shadowHolderPresent(pos) → continue`（holder 只因票而存在 = 原版已在驱动）——消除「tracking 已覆盖的柱被我们重复驱动」造成的本地物化变慢（偏差追查见 §8.1）。新增 `ShadowTrackingSession.shadowHolderPresent` |
| S4 删死代码 | ⏸ 未做 | 按计划先确认无回退需求 |
| S6 冒烟 | ✅ 两场 PASS | ① `1.21.1_fabric_I_dim_seedgen_fix2`（`-Scenario dimension` + 新 profile 显式开 seedGen）**PASS**：下界 `loaded/applied` **0 → 1529**、`netherReg -1 → 12`、三轮切维全过、`acquire -> local generate`=2706 / `local generated`=2706 / `local generate failed`=**0** / `Request rejected`=**0**（旧场 43）；② `1.21.1_fabric_I_seedgen_fix2`（单轮回归）**PASS**：`applied`=1529、`compare`=1529（基线 1542，未翻倍） |

**编译**：`common:compileJava` 在 1.20.1 / 1.21.1 / 1.21.11 三锚点 BUILD SUCCESSFUL；`scanVersionBoundaries` OK。

### 8.1 偏差追查（2026-09-20，**未闭环**）

**现象（已复现 3 次）**：单轮 seedgen 场 `rx/inj` 由基线 **0/3** 变为 **169/173 → 288/289 → 192/194**；`locallyGenerated` 由 **1835** 降到 **1650 / 1377 / 1470**。交付总数恒 1529、门禁 PASS，**非门禁项**。

**已验证的决定性事实**（`diag1` 场 + 跨场计数对照）：

| 场 | `responseFull` | `rx` / `inj` | `responseUnchanged` | `responseDelta` |
|---|---|---|---|---|
| 基线 | **3** | 0 / 3 | **390** | 1149 |
| fix3 | **289** | 288 / 289 | 194 | 1046 |
| diag1 | **194** | 192 / 194 | 219 | 1116 |

1. **`rx ≈ inj ≈ responseFull`** —— `rx` 只是下游计数。链路已验证：
   `ShadowPullClient.java:281` → `ShadowVanillaLightPipeline.submitVisible` → `recordNetworkReceived` + `injectPreLight`。
2. **`rx` 的记账条件**（`ShadowVanillaLightPipeline.java:58-96`）：官方整柱/pull 响应到达时影子端**没有 `isLightCorrect` 的可复用柱**才记。
3. **临时诊断分型 100% 是 `light-not-correct`（192/192，`no-column` = 0）**：柱**都在**影子表里（我们生成了），只是光未齐。
4. ⇒ **偏差真因 = 服务端对 compare 的裁决从 UNCHANGED/DELTA 偏向 FULL**（3 → 194/289）。
   `ShadowTrackingSession.java:552-560` 的注释已写明该方向的机理：**缺逐段/平面基线 ⇒ 服务端只能整段判 FULL**。

**已被证伪的假设（记下来免得重走）**：曾假设「我们显式生成 1293 柱 = 重复驱动 → 拖慢本地物化 → 官方整柱先到」。
按此落的 **S7**（`shadowHolderPresent` 判据，见下表）**确实生效**（`acquire -> local generate` 1293 → 285），
但 `rx` **反而上升**（169 → 288）⇒ 假设证伪。**偏差与「谁驱动生成」无关**。

**下一步插桩点（接手直接照做）**：在 `ShadowPullClient.request(...)`（或 `materializeForCompare`）里，
对每条 compare 打印**请求带的基线形态**（有/无逐段 hash、有/无平面）+ 随后收到的裁决，
按「基线形态 × 裁决」交叉计数。判据预期：`无逐段/平面 → FULL`。
若成立，修法方向 = 让**本地生成柱在 compare 时带上逐段/平面基线**（对齐 `MATERIALIZE_BEFORE_COMPARE` 的既有意图，
见 `ShadowTrackingSession.java:552-570`）。

**另一条已知的临时诊断**：`ShadowVanillaLightPipeline.submit` 里曾加过
`[SHADOW_INJECT] network-source … reason=` 一行（本轮已删；要复现 diag1 的分型就照原样加回）。

## 9. 未决事项（接手用）

### 9.1 拆掉影子端虚拟玩家？（用户 2026-09-20 提出，**仅评估，未动手**）

**用户判断**：保留虚拟玩家「貌似就是为了本地生成」。**评估结论：技术上可行，形态更原版，但不解决 §8.1 的偏差**（偏差根在 compare 裁决，与驱动者正交）。

现在它承载 6 件事（file:line 已核）：

| # | 承载 | 拆掉后 |
|---|---|---|
| ① | 玩家票 → 原版 tracking 选柱 + 生成驱动（`ShadowPlayerCompat.placePlayer` → `ChunkMap.addEntity` → `updatePlayerStatus`） | **隐式驱动归零，生成 100% 靠波前显式**（正是本次方向；S7 那种绕开双驱动的补丁可删） |
| ② | `tickChunkSystem(level)`（`ServerChunkCache.tick` 推进 holder/票） | **与玩家无关，保留** |
| ③ | 物化桥 `playerLoadedChunk`(1.20.1) / `onChunkReadyToSend`(1.21+) | **失效**；生成柱入表只剩 `generateLocalAsync` 回调 + 网络注入 ⇒ 两代行为统一（好），需确认交付不依赖它 |
| ④ | **官方包桥** `ShadowOfficialPacketBridge.forwardToRealClient`（在 `playerLoadedChunk` 内，`MixinChunkMap.java:107-111`） | **少一条交付出口**，必须确认 `pushReady` / `deliverLocal` 覆盖 |
| ⑤ | `deliveryCenter()` fallback / `virtualPlayerChunk()` | 真实玩家优先，**本来就不需要它** |
| ⑥ | `currentDimension` 的写入（在 `ensureVirtualPlayer` 里） | **必须挪到 `applyState`**，否则 `isInComputeDomain` / `requestPullEligible` 的维度闸全失效 |

**最大未知项（必须先量）**：首进服 1749 柱**纯显式生成**的吞吐（`localGenExecutor` 阻塞等 future，并发上限 = 池大小）
——原版 tracking 是多线程推进，显式路径是 N 个线程阻塞轮询（见 §3 与 §8 的「生成吞吐」条）。

**建议顺序**：① 先按 §8.1 的插桩点把偏差追完（1 次 run）；② 再把本节落成独立 handoff 并单独一轮实施。

### 9.2 其它未决

- **C2ME 专项复跑**（§2.4-3 的池确认 + 无持锁异常）：未跑。口径见 §6 第 3 行。
- **`dimension` profile 取舍**：`scripts/smoke/profiles/dimension.profile.properties` 现为 **`chunk.seedGenEnabled=true`**（让「seedgen × 切维」成为常设覆盖）。是否保留，或拆独立场景、让 `dimension` 回 `false`——待用户定。
- **S4 删死代码**：未做。候选：`generateChunk(ChunkPos)`、`ShadowChunkAcquire.tryAcquireForAuthority/decideSelection`、`emitPullGroups`、`noteInflightTimeout` + `INFLIGHT_TIMEOUT_MS`（`timeoutCooldownUntil` 无人填充 ⇒ 冷却路径实为死路）。
- **§2.1b 的「让虚拟玩家不再产生区块请求」**（行为可预测性）：未做（与 §9.1 是同一件事的轻量版）。
- **§3 的 `workerCount` 算式对齐 C2ME**（内存约束 + 客户端 −2）：未做（现有 `processors − 2` 已自动、且单测钉死该语义）。
- **⚠️ `diag1` 场出现一次新 ERROR**：`hassium-shadow-flush-minecraft_overworld` 线程
  `Failed to create block entity minecraft:chest`（LogAudit 门禁 ⇒ 该场 FAIL；基线/fix2/fix3 三场均为 0 次）。
  未定性：可能是显式生成带来的 flush 并发/时序变化，也可能是既有偶发。**下次跑 seedgen 场时留意是否复现。**

### 9.3 工作树状态（未提交）

**⚠️ 本小节写于 2026-09-20 23:56，已被 09-21 的后续改动覆盖——现行状态见 §11.1。**

当时为 3 改 + 3 新增；现为 **20 改/删 + 4 新增**（含拆虚拟玩家、客户端拦截无基线原版包、
BE 剔除、analyzer 门禁收窄、指标语义改写）。§8 / §9.1 / §9.2 的多条结论也已被推翻，逐条见 §11.1。

## 10. 代码索引

- 选柱/波前：`shadow/track/ShadowTrackingSession.java:503-629`（`drainAuthorityAcquires`）、`:1342-1457`（`onChunkMaterialized`）、`:609-612`（seedgen `continue`）
- 取块裁决：`shadow/track/VanillaAlignedChunkProvider.java:71-141`（`acquire`）、`:111-115`（seedgen 短路）、`:150-271`（`drainPendingPulls`）
- 生成：`shadow/server/ShadowSeedServer.java:290-301`（`generateChunk`）、`:307-344`（`pinForcedTicket`/`unpin`）、`:346-385`（`generateChunkAsync`）、`:273`（超时常量）
- 线程池：`shadow/server/ShadowWorldgenExecutor.java:34-47`（`workerCount`/`service`）、`mixin/server/MixinMinecraftServer.java:37-58`（executor 隔离）
- 闸：`shadow/server/SeedGenCompareGate.java`、`protocol/ShadowPullClient.java:467-486`（UNCHANGED→confirm）、`shadow/server/ShadowSeedServer.java:436-470`（injectChunk→confirm）
- C2ME 参照：`c2me-base/common/GlobalExecutors.java`、`c2me-base/ModuleEntryPoint.java`、`c2me-base/common/scheduler/SchedulingManager.java`、`c2me-base/common/scheduler/NeighborLockingTask.java`、`c2me-threading-worldgen/common/ChunkStatusUtils.java`、`c2me-threading-worldgen/mixin/MixinChunkStatus.java`、`c2me-threading-worldgen/mixin/MixinThreadedAnvilChunkStorage.java`
- 死代码（待清）：`ShadowSeedServer.java:276-278`、`shadow/track/ShadowChunkAcquire.java:49-62/72-109`、`ShadowTrackingSession.java:1287-1290`

## 11. 核查与修复记录（2026-09-21 下午）

### 11.1 本文件 §8 / §9 已过时（先读这段）

工作树实际状态与 §8 / §9 不一致——09-21 01:20–11:45 那一波已把 §9.1 的**评估实施了**：

| 本文件旧表述 | 实际 |
|---|---|
| §9.1「拆虚拟玩家：**仅评估，未动手**」 | **已全拆**：删 `ShadowPlayerCompat` / `MixinPlayerChunkSender` / `MixinServerLevel` / `MixinShadowConnectionAccessor` |
| §4「**不删**玩家 tracking（`ensureVirtualPlayer`/`moveVirtualPlayer`/`tickChunkSystem` 保留）」 | 前两个已删；`tickChunkSystem` 改为影子循环直接调 `ServerChunkCache.tick` |
| §8「S2 已落：`acquire` seedgen 分支改登记 inflight + `generateLocalAsync`」 | 该分支**已退回** `failedFuture`（`VanillaAlignedChunkProvider.java:109-115`）；生成驱动唯一化到波前 `submitLocalGeneration`（`ShadowTrackingSession.java:1266-1282`） |
| §8「S7 `shadowHolderPresent` 已落」 | 随虚拟玩家一并删除 |
| §2.1「只加显式触发，不动放行」 | 放行确实没动（`MixinChunkMap` 只改了一行 javadoc）✔；但「触发」换成了**客户端拦截无基线原版包**（`ShadowPullClient.java:244-254`，新计数 `seedGenIntercepted`）——机制与 §2.1 不同，但 §2.1b 的自锁结论未被推翻（走的是拦截**包**，不是悬置 load future） |
| §8.1「下一步插桩点」 | 已实现（`protocol/CompareFullDump.java`），但**全仓没有任何地方设 `-Dhassium.compareFullDumpDir`** ⇒ 从未生效 |

### 11.2 本次核查发现并修复的两条

**① 1.21.11 锚点编译失败（已修）**
`pruneInconsistentPendingBlockEntities` 里 `CompoundTag.getString` 在 1.21.5+ 返回 `Optional<String>`、
`ResourceLocation` 在 1.21.9+ 改名 `Identifier`——两个符号在 1.21.11 段直接编译不过。
`scanVersionBoundaries` **不查符号/类型**，抓不到这类（它当时报 OK）。
修法按 manifold 规则收口到 `compat/`，业务类不加 `#if`：

- 新增 `BlockEntityCompat.canPromote(CompoundTag, BlockState)`：判据与 vanilla `BlockEntity.loadStatic` 同源，内部吸收 `getString` 的 Optional 缝；
- 新增 `ResourceLocationCompat.tryCreate(String)`：跨版本 tryParse 语义（非法 → `null`；复用既有 `create(String)` 的解析路径 + 归一异常）；
- `ShadowSeedServer.pruneInconsistentPendingBlockEntities` 改为只调 `canPromote`。

**② 剔除漏了「空/非法 id」（已修）**
旧实现 `if (id.isEmpty() || "DUMMY".equals(id)) continue;` 把空 id 交回 vanilla，
而 vanilla 对该分支打的是 **ERROR 级** `Block entity has invalid type`（`BlockEntity.java:157`；
`Failed to create block entity` 在 `:164`）——同样污染 LogAudit 门禁。
`DUMMY` 才是真正该交回 vanilla 的（它有独立分支、不查注册表，剔除会改变行为）。
新 `canPromote`：空 id → `false`（剔除）、`DUMMY` → `true`（交回 vanilla）、`tag == null` → `false`（剔除；
vanilla 对 null tag 会在 `promotePendingBlockEntity` 里 NPE，进而让整柱序列化失败——剔除反而更稳）。

### 11.3 验证（2026-09-21 14:08 本机）

- `common:compileJava` 三锚点（1.20.1 / 1.21.1 / 1.21.11）**全部真实执行**（非 FROM-CACHE）→ `BUILD SUCCESSFUL`。
- `scanVersionBoundaries` → `OK`；`common:test` → `BUILD SUCCESSFUL`（exit 0）。
- **未跑冒烟**：本改动是「等价前置剔除」（落盘 NBT 不变），且 chest ERROR 只在 `diag1` 场偶发一次、
  不可按需复现 ⇒ 冒烟无法证明它消失。**下次跑 seedgen 场时留意 `Failed to create block entity` 是否复现。**

### 11.4 仍未闭环

- **§8.1 的 `rx ≈ responseFull`**：最新 `seedgen_fix3b_nomod` R1 `responseFull=369` / `rx=398`。
  **新线索**：FULL 裁决的落地路径**必然**是 `pending.fallback().run() → ShadowVanillaLightPipeline.submitVisible`，
  而 `rx` 就在 `submitVisible` 里记账 ⇒ 两者强相关可能是**结构性**的（= 设计使然），不全是「偏差」。待验。
- **1.20.1 / 1.21.11 零运行时验证**（09-21 全天 19 场冒烟只有 1.21.1 fabric/neoforge）。
  尤其 1.20.1 的物化桥 `MixinChunkMap.hassium$shadowBridgeLoadedChunk`（`@Inject playerLoadedChunk`，`MixinChunkMap.java:87-95`）
  在无 `ServerPlayer` 后**结构性不可达**——正是 §9.1 ③④ 要求「必须确认」的两项，仍未确认。
- **冒烟门禁被放宽，需用户知情**：`analyzer.py` 的 `TRACE_ENCLOSED_HOLE` P0 由 `{classic, dimension}` 收窄为**仅 `classic`**，
  `test_analyzer.py::test_dimension_enclosed_hole_fails` 被改成 `_is_diagnostic`（断言**不再**亮灯）。
  残留不一致：`analyzer.py:25-26` 的 `_ENCLOSED_HOLE_P0_SCENARIOS={"classic","dimension"}` 已成**死常量**且与新行为矛盾。
- **两场 `PROCESS_FATAL` 未定性**：`1.21.1_fabric_I_seedgen_fix2_nomod_recheck`（R1 过、R2 崩）、
  `1.21.1_fabric_I_seedgen_c2me_fix1`（`CLIENT_EXIT_NONZERO + PROBE_MISSING`）。
  另有 `1.21.1_fabric_I_seedgen_fix3_nomod` = `server_not_ready`（基建，非代码）。
- **`ShadowTrackingSession.java:44-45`** 的 `MAX_VIEW_DISTANCE` / `DEFAULT_VIEW_DISTANCE` 随 `resolveViewDistance()` 删除后已无引用；
  `MixinChunkMap.java:64-65 / :83 / :259-261` 的注释仍写「虚拟玩家」，与现状矛盾。

### 11.5 2026-09-21 无 MOD `seedgen` 冒烟（L2 锚点集，14:38–14:43）

**命令**：`.\scripts\runtime-smoke-test-batch.ps1 -Phase I -Scenarios seedgen -SessionSuffix nomod`
**坑（静默缩覆盖）**：batch 默认 `-Loaders = @("fabric","neoforge")`（`runtime-smoke-test-batch.ps1:21`），
与硬编码锚点集取交集时会把 `1.20.1/forge` **过滤掉**——「L2 三锚点」这条命令**默认只跑两个**。
补跑：`-Versions 1.20.1 -Loaders forge`。

| SessionId | 结果 | loaded | localGen | seedGenIntercepted | cmpReq | rUnch | rDelt | rFull | rx |
|---|---|---|---|---|---|---|---|---|---|
| `1.20.1_forge_I_nomod_seedgen` | PASS | **256** | 1330 | 0 | 256 | 0 | 30 | 226 | 226 |
| `1.21.1_neoforge_I_nomod_seedgen` | PASS | 1439 | 1255 | 495 | 1448 | 286 | 847 | 313 | 338 |
| `1.21.11_fabric_I_nomod_seedgen` | PASS | 1512 | 437 | 451 | 2355 | 61 | 168 | 1152 | 1247 |

**BE 剔除修复已验证**：三场 `Failed to create block entity` = 0、`Block entity has invalid type` = 0。

**⚠️ 1.20.1 + seedGen 交付只有 256（异常，未归因）**

- 同版本**非** seedgen 会话（09-20 `1.20.1_forge_I_m1` / `1.20.1_fabric_I_m1/m2/light1`）round1 = **1529**。
- 影子侧不缺数据：`material=1705`（注入表满）。
- 末段 **48 轮稳态冻结**：`submitted=0 published=256 material=1705`（`published` 是**每轮新增交付数**，
  48 轮恒 256 ⇒ **同一批 256 柱被反复重投**，其余 ~1450 柱一轮都没投）。
- `STALL-DIAG` 终场 `pendingAuth=225`（含义见下条修正）。
- **门禁看不见**：`seedgen` 被排除在空间/空洞契约外（`analyzer.py:21-24`），只出 `SPATIAL_SNAPSHOT_INCOMPLETE@R1` 警告。
- **不能归因于本波改动**：1.20.1 + seedgen **历史零基线**（全仓只有今天这一条 probe），
  无法排除「从来如此」。**下次先取一条改动前的 1.20.1+seedgen 对照**（例如 stash 本波改动后跑同场）。

**❗ 修正本文件 §7.5 的一处事实错误**：`STALL-DIAG pendingAuth=N` 是
`ShadowLightCompute.pendingAuthoritative.size()`（**光照**迟到重触发登记，`ShadowLightCompute.java:3336`），
**不是** `SeedGenCompareGate.AWAITING`。§7.5 把这两个计数当成了同一个。

**1.21.11 形态与 1.21.1 差一个量级（未定性）**：1.21.11 `rFull=1152/1512`（**76% 靠服务端整柱下发**）
vs 1.21.1 `rFull=313/1439`（22%）⇒ 「本地生成作基线 → compare 复用」在 1.21.11 上**基本没生效**。
门禁 PASS、交付量正常，故非失败；方向候选：1.21.9+ 的 section 编码/基线差异，或本地生成内容与真服不一致。

### 11.6 ⚠️⚠️ seedGen 开 → 客户端驻留集有「玩家脚下中心空洞」（2026-09-21，**未闭环**）

对 `roundN.json` 的 `clientCache.actualPresent`（逐柱 `cache.getChunk(x,z,false)` 实测驻留）做
封闭空洞分析（口径同 `analyzer.py::_enclosed_holes`：bbox 内缺失且 4-连通无法到达 bbox 边界）。
脚本：`build/_gapcheck.py`（临时工具，`build/` 不入库）。

| 场 | seedGen | held | bbox 填充率 | **封闭空洞** | 最大块 |
|---|---|---|---|---|---|
| `1.21.1_fabric_I_baseline_1`（**对照**） | 关 | 1529 | 82.7% | **0** | 0 |
| `1.21.1_fabric_I_seedgen_baseline`（10:36） | 开 | 1480 | 80.0% | 49 | 49 |
| `1.21.1_fabric_I_seedgen_fix2_nomod` | 开 | 1448 | 78.3% | 81 | 81 |
| `1.21.1_fabric_I_seedgen_fix3b_nomod` | 开 | 1457 | 78.8% | 72 | 72 |
| `1.21.1_fabric_I_seedgen_c2me_fix2` | 开 | 1327 | 71.8% | **202** | 202 |
| `1.21.1_neoforge_I_nomod_seedgen` | 开 | 1430 | 77.3% | 99 | 99 |
| `1.21.11_fabric_I_nomod_seedgen` | 开 | 1503 | 81.3% | 70 | 70 |
| `1.20.1_forge_I_nomod_seedgen` | 开 | **256** | 13.8% | 12 | 12 |

**结论（【已验证-数据】）**：
- **空洞形状固定**：约 10 格宽 × 11 格高，**正好压在玩家所在柱上**（1.21.1 场实测 x≈-8..1、z=-5..5；`center=(-3,0)`）。
  即**玩家站在未驻留区块里**（客户端 `ClientChunkCache` 里真的没有这些柱）。
- **只在 seedGen 开时出现**：同版本同 VD 的 seedGen 关对照场空洞 = 0。
- **1.20.1 形态更糟**：不是「有洞」而是「只有外圈碎片」——held=256 全散在 x[-24,18] × z[-21,21] 的边缘，
  **中心整片无柱**（见 §11.5 的 48 轮 `published=256` 稳态）。
- **门禁看不见**：`seedgen` 被排除在空间/空洞契约之外（`analyzer.py:21-24`）。而
  `SmokeProbeWriter` 的候选优先序已改成 `clientApplied` 优先 ⇒ `actualPresent` 现在是**完整**驻留集、
  空洞测量**有效**——排除理由（「seedgen 稀疏采样不走同一契约」）**已经过时**。

**未验证（必须显式列出）**：
- **游戏内目视未做**（红线要求）。数据层证明柱不在 `ClientChunkCache`；是否表现为虚空/坠落/黑屏未确认。
- **成因未查**。候选方向（**均未取证**）：① 玩家所在柱被 `nativeBypassApplyInProgress` 旁路
  （1.21.1 实测 1879 次）而永不落地；② `seedGenIntercepted` 拦截后 `PENDING_COMPARE` 的 10s 超时回退
  对「近柱」太慢，玩家已走到别处；③ 1.20.1 的物化桥失效（§11.4）导致中心柱从未进入交付集。
- **1.20.1 是否本波引入**未定（历史零基线，见 §11.5）。

### 11.7 中心空洞根因与修复（2026-09-21，**已闭环**）

**根因 = `submitLocalGeneration` 与 `onChunkMaterialized` 争用同一张在途表（顺序 bug）**

`ShadowTrackingSession`：

```
drainAuthorityAcquires(seedgen 分支)
  └─ submitLocalGeneration()
       ├─ markPullInFlight(dim,pos)          ← ① 占 sweepInFlight
       └─ generateChunkAsync(... onDone → onChunkMaterialized)

回调（影子主循环）
  └─ onChunkMaterialized()                   :1171  localWorldgen=true → seedGen 分支 :1203
       ├─ SeedGenCompareGate.mark(dim,pos)   ← ② AWAITING 置位
       ├─ requestPullEligible(...,false)     → markPullInFlight 失败（键还在）⇒ false
       ├─ requestPullEligible(...,true)      → 同样失败      ← ③ 一个请求都没发出去
       └─ return
  └─ VanillaAlignedChunkProvider.completeAcquire()  ← ④ 到这里才 clearPullInFlight（:286）
```

`sweepInFlight` 是**生成与 pull 共用**的一张表，而 `SeedGenCompareGate` **没有超时、没有自愈**
（只有 `confirm` / `clear` / `clearAll` 会清）。⇒ 留下「AWAITING 已置位、却无请求在途」的不一致状态：
泵的 `isAwaiting → continue`（`:541-543`）与交付闸 `blockClientDelivery` **双双永久跳过本柱**。

**证据**（`1.21.1_neoforge_I_nomod_seedgen`，修复前）：
`seedGen compare requested` = **0**（而 `seedGen materialize` = 1197）、`authoritativeRequests` = 0、
`skip pull outside authority window` = 0、`HOLE:` = 0；空洞柱 `(-3,0)` 只有
`local worldgen materialized` + `seedGen materialize`，**无任何 `[CHUNK_APPLY]`**；
空洞柱是最早物化的一批（14:38:58），已交付柱晚 5–13 s。

**修复（一行，源头；不在闸上加超时/自愈）**：`submitLocalGeneration` 的成功回调里，
在 `onChunkMaterialized` **之前** `clearPullInFlight` —— 生成的在途语义到此结束，
随后的 compare 需要自己的在途位。

**验证**（`1.21.1_neoforge_I_nomod_seedgen_fixhole`）：

| | 修复前 | 修复后 |
|---|---|---|
| `clientCache.loadedChunks` | 1439 | **1529**（= seedGen 关的基线） |
| 封闭空洞 | **99** | **0** |
| 日志 `seedGen compare requested` | 0 | 1032（= `seedGen materialize`） |
| `compareRequests` | 1448 | 2618 |
| `rFull` / `rUnch` / `rDelt` | 313 / 286 / 847 | 485 / 184 / 981 |
| `rx` | 338 | 538 |

**副产物（需留意）**：`compareRequests` 由 1448 → 2618（≈1.7× 交付柱数）。1448 是**被 bug 压低的假读数**
（~1100 柱压根没发 compare），所以不是「翻倍回归」；但 1.7× 的冗余度值得单独看，
§6 的「compare 不翻倍」口径需要用修复后的数**重新定基**。

**1.20.1 forge 同样闭环**（`1.20.1_forge_I_nomod_seedgen_fixhole`）：

| | 修复前 | 修复后 |
|---|---|---|
| `clientCache.loadedChunks` | **256** | **1529**（= 基线） |
| 封闭空洞 | 12（且中心整片无柱） | **0** |
| bbox 填充率 | 13.8% | 82.7% |
| `compareRequests` | 256 | **1529**（= 交付柱数，**每柱恰好一次**） |
| `rFull` / `rUnch` / `rDelt` | 226 / 0 / 30 | 181 / 99 / 1249 |
| `rx` | 226 | 175 |

⇒ 1.20.1 的「只剩外圈碎片」与 1.21.1 的「99 格中心洞」是**同一个根因**（§11.7 开头那条链）。
1.21.1 的 1.7× compare 冗余在 1.20.1 上**不存在**（1529/1529 = 1.0×）⇒ 该冗余是 1.21.1 段特有，另案。

**顺带回答 §11.4 的一个悬案**：1.20.1 两场 `nativeIntercepted` 都是 **0**（客户端拦截路径在 1.20.1 上
从不触发），却仍能交付 1529 ⇒ **1.20.1 的交付不依赖拦截路径**，所以那条「物化桥结构性不可达」
没有造成交付缺口（它只是让 1.20.1 的交付出口收敛到 compare/publish 一条）。

**1.21.11 fabric 同样闭环**（`1.21.11_fabric_I_nomod_seedgen_fixhole`）：

| | 修复前 | 修复后 |
|---|---|---|
| `clientCache.loadedChunks` | 1512 | **1573** |
| 封闭空洞 | **70** | **0** |
| bbox 填充率 | 81.3% | 85.1% |
| `compareRequests` | 2355 | 2807 |
| `rFull` / `rUnch` / `rDelt` | 1152 / 61 / 168 | 919 / 57 / 641 |
| `rx` | 1247 | 1003 |

**三锚点交付量都精确回到各自基线**（1573 是 1.21.11 的 VD20 基线——1.21.4 起 vanilla tracking
形状改为纯欧氏圆，VD20 = 1573 而非 1529，见 `docs/handoff/handoff-2026-09-20-*` 与
`project_1214_tracking_shape_cliff`）：

| 锚点 | 修复前 | 修复后 | 该锚点 VD20 基线 |
|---|---|---|---|
| 1.20.1 forge | 256 | **1529** | 1529 |
| 1.21.1 neoforge | 1439 | **1529** | 1529 |
| 1.21.11 fabric | 1512 | **1573** | 1573 |

**§11.5 的「1.21.11 形态差异」不受本修复影响**：`rUnch` 仍只有 57、`rFull` 仍占大头（919/1573）
⇒ 「本地生成作基线 → compare 复用」在 1.21.11 上依然没生效，仍是独立的待查项。

### 11.8 热复用场：缓存基线生效，但 **R1 确实重复 compare**（2026-09-21，**待排查**）

**新增开关**：`scripts/runtime-smoke-test.ps1 -WarmRepeat` —— 不清客户端影子缓存，且**压过**
seedgen/dimension/modcompat 场景的强制 `-CleanWorld`（状态只需清一次：首场默认口径建基线，
后续热复用）。默认行为不变。

**随之调整的 seedgen 场景断言**（2026-09-21）：`seedgen.scenario` 原第 3 条断言
`counters.locallyGenerated > 0` 是**冷场专属**读数——热复用场缓存已满，该计数必然为 0 ⇒ 误报 FAIL
（实测 `poigate_warm1`）。换成 `counters.authoritativeRequests == 0`：这是该场景首行声明的**核心不变量**
（门控开时无基线柱不得被抢成空基线权威 FULL；seedGen 关时该计数 ~2000），**冷/热两态都成立**
（实测 5 场 seedgen 全为 0）。「本地生成是否真的发生」改由冷场回归 + 脚本打印的 counters 覆盖。

**`1.21.11_fabric_I_seedgen_warm` vs 冷启动 `..._fixhole`**（同为 1.21.11 fabric seedgen）：

| | 冷启动 | 热复用 |
|---|---|---|
| `loadedChunks` / 交付 | 1573 | 1573 |
| `locallyGenerated` | 702 | **19** |
| `seedGenIntercepted` | 345 | **0** |
| `cacheHitFullChunkCount`（UNCHANGED 命中） | 57 | **611** |
| `fullChunkRequestCount`（网络整柱**按柱去重**落地） | 1003 | **38** |
| `responseUnchanged` / `rFull` / `rDelta` | 57 / 919 / 641 | **611 / 28 / 1386** |
| `rx` | 1003 | **38** |
| `compareRequests`（**入口计数，不去重**） | 2807 | **3847** |

**结论 1（好）**：缓存基线**确实生效** —— UNCHANGED 命中 57 → 611、网络整柱落地 1003 → **38**、
`rx` 1003 → 38。**修复后的代码只有在热复用下才兑现 seedGen 的省流量收益**；冷启动那 919 次 FULL
是「无盘基线」的代价。⇒ §11.5 的「1.21.11 偏 FULL」很可能**不是版本差异**，而是「冷启动」的必然结果
（同版本热复用下 rFull 只有 28）。**待验证**（需要在 1.21.1 上补一场热复用对照）。

**结论 2（问题）**：**R1 面对缓存基线时会重复 compare**。
`ShadowPullClient.request()` 里 `compareRequests.addAndGet(chunks.size())` 是**入口计数、不去重**；
`[DIAG] ShadowPullClient.request` 日志实测 **3847 次调用、每次 `chunks=1 baseline=true`**，
而交付只有 1573 柱（计算域 ~1749）⇒ **2.2–2.4× 重复**。冷启动是 2807 / 1573 = **1.78×**，
即热复用**更严重**。

- 注意与 `stats` 块的口径区分：`fullChunkRequestCount` 是**按柱去重**的网络落地数，
  说明服务端/网络侧没有重复下发；重复发生在**客户端发起侧**。
- `cacheMiss` / `cacheStale` 两场都恒 **0**：`REQUEST_MODES` 回查恒 miss（既有症状，09-20 亦有），
  与上面的重复可能是同一族问题，一并留待排查。

**根因（2026-09-21 实测取证）**：compare 有 **6–8 个发起入口，各自一张互不感知的在途表**
（`SeedGenCompareGate.AWAITING` / `sweepInFlight` / `PENDING_COMPARE` / `requestedMisses`）。
给 `[DIAG] ShadowPullClient.request` 加上坐标后按线程归因（`..._warm_diag` 场）：

| 发起线程 | 条数 | 对应入口 |
|---|---|---|
| `Netty NIO IO #0` | 1508 | 客户端拦截 `tryInterceptForCompare` |
| `hassium-seedgen-main` | 1236 | 影子泵 / `onChunkMaterialized` / `requestPullEligible` |
| `Render thread` | 721 | `ShadowLightCompute` publish/disk-miss 路径 |

3465 条 / 1693 柱（117 柱 ×1、**1380 柱 ×2**、196 柱 ×3）；2 次的柱的线程组合
674× `seedgen-main + Netty`、478× `Netty + Render`、150× `Netty + seedgen-main`、63× `seedgen-main + Render`
⇒ **同一柱被两条入口各发一次是结构性必然**。

**修复（2026-09-21）**：去重收口到三条入口的**共同出口** `ShadowPullClient.request()`。
- 新增 `COMPARE_REQUESTED`（按柱、本会话）+ `wasCompareRequested` / `clearCompareRequested`；
  `request()` 过滤掉已发过的柱，全发过就直接 return（一个包都不发）。
- 放行重试的口子（都是「重试/新机会」语义，先释放登记）：`notePullFailure`（已覆盖
  `retryAuthoritativeFullOnce`，它内部调它）、`submitLocalGeneration` 生成失败回退、
  `pull-injected` 物化失败重拉、`ShadowLightCompute.onClientChunkUnloaded`、
  `reset()` / `onClientDimensionChanged()`。
- **不改拦截语义**：拦截被去重挡下时包已挂在 `PENDING_COMPARE` 上，等另一条入口那份响应
  （或 10s 超时）回放 ⇒ **不丢包**。也不给 `SeedGenCompareGate` 加超时/自愈。

**验证**（`compareRequests` 是去重后真正发出的口径；`[DIAG]` 那行在 `request()` 顶部、
去重**之前**，故它数的是调用次数、不是发出次数）：

| 场 | `compareRequests` | 倍数 | `loadedChunks` | 封闭空洞 |
|---|---|---|---|---|
| `..._seedgen_warm`（修复前，热） | **3847** | 2.44× | 1573 | 0 |
| `..._seedgen_dedup_cold`（修复后，冷） | **1573** | **1.00×** | 1573 | **0** |
| `..._seedgen_dedup_warm`（修复后，热） | **1620** | **1.03×** | 1573 | **0** |
| `1.20.1_forge_I_seedgen_dedup`（修复后，冷） | **1529** | **1.00×** | 1529 | **0** |

⇒ 冷启动从 1.78× 降到 **1.00×（每柱恰好一次）**；热复用从 2.44× 降到 **1.03×**。
交付量与空洞均无回归。（1.20.1 本来就只有 1.00×——它的 `nativeIntercepted=0`，
拦截路径从不触发 ⇒ 只有一条入口，无重复可言；这也印证了「重复 = 多入口」这个归因。）

### 11.9 ⚠️ 影子端并发读盘 → POI `Long2ObjectOpenHashMap` 结构损坏（2026-09-21，**未修**）

热复用模式下偶发（**不是**本次去重修复引入）：

```
java.lang.ArrayIndexOutOfBoundsException: Index -1 out of bounds for length 4097
  at it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap.rehash(Long2ObjectOpenHashMap.java:1320)
  at net.minecraft.world.level.chunk.storage.SectionStorage.unpackChunk(SectionStorage.java:203)
  at net.minecraft.world.entity.ai.village.poi.PoiManager.checkConsistencyWithBlocks(PoiManager.java:210)
  at net.minecraft.world.level.chunk.storage.SerializableChunkData.read(SerializableChunkData.java:248)
```

线程 `hassium-seedgen-main`（影子端），随后 vanilla 打 `ERROR Failed to load chunk X,Z` ⇒ LogAudit 门禁 FAIL。

| 场 | `Failed to load chunk` | POI AIOOBE |
|---|---|---|
| `..._fixhole` / `..._warm` / 1.20.1 / 1.21.1 冷启动场 | 0 | 0 |
| `..._warm_diag`（第 2 次热复用） | **340** | 2505 |
| `..._dedup_warm`（第 1 次热复用） | **4** | 少量 |

⇒ 与「影子端并发读盘」同族（`PoiManager` 的 `SectionStorage` 用非并发 map，而影子端 chunk 读盘跑在
`ShadowWorldgenExecutor` 池上）；触发与**热复用次数**相关，冷启动场从未复现。
**未查根因、未修**（独立于 §11.8 的重复 compare）。

#### 根因（2026-09-21 定位，仍未修）

**原版把反序列化放在单线程主线程上跑，影子端把它挪到了池线程。**

- 1.21.1 mojmap `ChunkMap.scheduleChunkLoad`（`:519-527`）：
  `readChunk(pos).thenApply(...).thenApplyAsync(tag -> ChunkSerializer.read(level, poiManager, ...), this.mainThreadExecutor)`
  —— `mainThreadExecutor` 是**单线程**，故 `ChunkSerializer.read`（含 POI 更新）天然串行。
- 而这条链上的结构**全都没有同步**：`SectionStorage.storage` 是裸 `Long2ObjectOpenHashMap`（`:41`）、
  `DistanceTracker.levels` 是裸 `Long2ByteOpenHashMap`；`SectionStorage.get/getOrLoad/getOrCreate/readColumn`
  与 `PoiManager.checkConsistencyWithBlocks` 都不是 `synchronized`。
- 影子端：`ShadowSeedServer.DISK_READ_PERMITS = max(2, min(8, 核数/4))`，其 javadoc **自己就写明**
  「`loadFromDisk` 走完整 `ChunkSerializer.read`（含逐 section 的 `PoiManager.checkConsistencyWithBlocks`）」
  ⇒ **2~8 个池线程同时 `storage.put`** ⇒ `rehash` 里 AIOOBE。

**为什么只在热复用复现**：冷启动读盘基本 miss（`loadFromDisk` 早退，不碰 POI）；热复用一次要并发读上千柱
⇒ 竞态窗口打开。

**为什么不能只「把读串行化」**：`ChunkMap.tick` → `poiManager.tick`（`ChunkMap:414`）跑在**影子主循环**上，
`ChunkMap.save` → `poiManager.flush`（`:707`）同理 ⇒ 竞态还有「**池线程读 vs 主循环 tick**」这一半。
只把 `DISK_READ_PERMITS` 降到 1 挡不住它，反而把「卡死只烧 N 核」的保护变成「卡死 = 全停」。

**候选修法（未选，待拍板）**：
1. **把 `parseChunkNbt` 路由回影子主循环**（等价原版 `mainThreadExecutor`）：正确性最强、与 vanilla 同构；
   代价：要处理「已在主循环上」的自等待（inline 分支），且与 `DISK_READ_PERMITS` 的卡死保护相冲
   （主循环卡 = 读盘全停）。
2. **给 `PoiManager` 的变更点补锁**（mixin `synchronized`）：手术式、保留并行度；但要覆盖
   `checkConsistencyWithBlocks` + `tick`/`flush` + `add`/`remove` 多个入口，属于「给原版结构补锁」。
3. 降并发到 1：**不足以修**（见上）。

#### 修复（2026-09-21，**已闭环**）

**思路**：只把「我们自己」的入口串行化，**不给原版结构打补丁**（换并发容器不够——见下）。

- 新增 `compat/ShadowPoiGate`：一把 `ReentrantLock`，两种取法——
  - **阻塞** `callExclusive` / `runExclusive`：解码、序列化、`saveAll`；
  - **非阻塞** `runIfIdle`：影子主循环的 chunk tick —— 拿不到闸就**跳过本拍**。
    这个不对称是关键：保证「tick 拿到闸时没有解码在跑」，同时保证
    **主循环永不被解码阻塞**（不把 §8.5「读盘卡死」的爆炸半径放大成主循环停摆）；
    tick 本就有 `CHUNK_TICK_INTERVAL_MS` 节拍，跳一拍无害。
- `ShadowServerCompat.parseChunkNbt` / `serializeChunk`：公开入口过闸，方法体拆 `*Unlocked`。
  这两个是**唯一**的解码/编码漏斗（`ShadowServerCompat:176` + `ShadowSeedServer:1765`；`ShadowSeedServer:1647`）。
- `ShadowTrackingSession.consumeOnShadowLoop`：包住 `ServerChunkCache.tick(...)`，
  即 `ChunkMap.tick → poiManager.tick` 的入口。
- `ShadowSeedServer.loadFromDisk`：**「读+解码」按柱在途单飞**（新 `inFlightDiskDecodes`），
  与存储管理器的 `inFlightReads`（只管**字节**段）互补——解码段原先每个调用方各跑一遍。

**为什么「换线程安全 map」不够**（用户提案，已评估）：① 这条链上不止一个非并发结构
（`SectionStorage.storage` / `dirty` / `PoiManager.DistanceTracker.levels` / `PoiSection.records`）；
② 换容器只保「不崩」，保不住 vanilla 的**协议**（`getOrLoad` 的 get→readColumn→get、
`PoiSection.refresh` 读改写、`SectionTracker.update` 迁移）⇒ 会变成**静默的逻辑错误**；
③ 也覆盖不到 `poiManager.tick`。

**验证**（`1.21.11_fabric_I_poigate_*`，连做两次热复用——正是复现 340 条的序列）：

| 场 | `Failed to load chunk` | POI AIOOBE | `loaded` | 封闭空洞 | `compareRequests` |
|---|---|---|---|---|---|
| `..._seedgen_warm_diag`（修复前，热 ×2） | **340** | 2505 | 1551 | — | 2714 |
| `..._poigate_cold` | 0 | 0 | 1573 | 0 | 1573 |
| `..._poigate_warm1` | **0** | **0** | 1573 | 0 | 1648 |
| `..._poigate_warm2`（热 ×2） | **0** | **0** | 1573 | 0 | 1625 |

`poigate_warm1` 的 `RESULT: FAIL` **不是回归**：门禁只抓到 2 条 —— 一条是 vanilla 的
`Failed to retrieve profile key pair`（离线开发环境噪音），另一条是 seedgen 场景自带的
`assertProbe counters.locallyGenerated gt 0`（**全热复用下 `localGen=0`**，该断言对「缓存已满」的场
本就无意义）。交付量 / 空洞 / 去重倍数（1.00–1.05×）三场均无回归。

**仍未覆盖**：`SeedGenLevelCompat:645 server.saveAll()`（其 `ChunkMap.save → poiManager.flush`
也写同一份状态）。未包闸的原因：`saveAll` 可能跑数秒，而它的注释明确要求「saveAll 期间主循环仍在驱动
光照任务」；包住会让主循环 tick 在整段保存期间全跳过（tick 用的是 `runIfIdle`）。
本次崩在**会话中**（非关停期），故先不动；需要时再定粒度。

**仍未解释**：修复前 `localWorldgen=true` 的柱有 1197 个，按上述链条应全部中招，实测空洞只有 99 格
⇒ 其余 ~1100 柱被别的路径救回（最可能是同柱的原版包后来被拦截 → `tryInterceptForCompare` 有基线分支
发 `requestFull` → 响应 `confirm` 顺手清了 AWAITING）。**未取证。**
