# handoff — 影子端=专用服（S0 冻结）

> 状态：S0 接口已与用户对齐（2026-09-18）。实现从 **S1** 起；本文是唯一 S0 真相源。
> 用户确认：方向同意；「以前有个版本就是这样做的，影子端模拟专用服」——见 §1 git 历史。

## 0. 已拍板决策

| 项 | 决策 |
|----|------|
| 模型 | 影子端 = 进程内专用服；**唯一业务差异 = 区块来源** |
| 交付 | **接法 B**：拦截影子官方 `chunk+light` → 真实客户端 listener（不先做 Connection 整包转发） |
| 取块 | **始终异步 Provider**（2026-09-18 修订）：`scheduleChunkLoad` **不做「有盘/有注入 → Imposter 同步短路」**；无论有无本地数据，都走异步 future，且 **必须向真服 compare/FULL**（§3.2）。原版读盘本就是 `IOWorker` 异步 future，**允许**有盘也走异步。 |
| 盘的角色 | type126 / 注入表 = **materialize 来源**（UNCHANGED 后本地组装 / 比对基线），**不是**「跳过网络」的理由 |
| 光照 | **继续 lightStrip**：真服→影子 Pull 可剥光；影子 LightEngine 算光后出官方包 |
| 选柱 | 唯一 owner = 影子 `ChunkMap` 票；半径对齐 **ServerVD**（原版 tracking 几何） |
| 卸载 | 挂原版 `processUnloads` → `flushAndEvict`（type126）+ 摘注入表 |

## 1. Git 历史：这不是从零发明

| 提交 | 日期 | 与本模型的关系 |
|------|------|----------------|
| `0df7b1fd` feat(seedgen): 连服即起影子端 | 2026-08-21 | 影子 MinecraftServer 随连接启动 |
| `07638699` 注入柱进 ChunkMap 并用原版打包 | 2026-08-21 | 注入进 holder + 原版 packet 构造（专用服打包语义） |
| `52cb8e10` vanilla light pipeline + deferred persistence | 2026-08-25 | `initializeLight`+`lightChunk`、VISIBLE/HALO 角色、HashIndex 持久化 |
| `d1780185` 影子端原版保存 level.dat | — | 影子世界原版存档结构 |
| `8f3f862b` pull 域收口 vanilla 可见形状 | — | 与「只拉用户能看见的」一致 |
| `935b9ed6` vanilla-align client-shadow chunk bridge | 2026-09-10 | 卸载不拆注入表；tracking 进边沿有基线必 publish；redeliver 队列 |
| `e1a358da` purge server push，对齐 arch §6 | 2026-09-08 | 真服停主动推；数据侧 Pull |
| `5f7e31a7` / `10cab50a` 影子光对齐原版 LIGHT / 同 FIFO | — | 算光语义曾向原版收 |

**历史结论**：票 + ChunkMap + 原版打包 + 原版光管线 + Pull 数据源 **已经存在**；后来叠了 bootGrid/sweep/权威声明/OVD/复杂光门与自绘 reclaim，把「专用服」时序淹没了。S0 的工作是 **收回主路径**，不是重写影子服。

**历史教训（必须写进实现约束）**

1. 客户端 unload **不得**拆影子注入表（`935b9ed6`：会出永久洞）；回收只跟影子 tracking / 原版 processUnloads。
2. 注入柱必须进 **ChunkMap holder** + 原版打包（`07638699`），禁止只塞 `injectedChunks` 空壳旁路。
3. 算光走 **initializeLight + lightChunk**（`52cb8e10`），不要 Imposter 空光直推。
4. 真服 **PULL_MODE 停推**（`e1a358da`）；客户端只应有一个区块源 = 影子专用服产出。
5. 多驱动（bootGrid/权威整条）曾引入空洞/风暴；S1 起选柱只认影子票。

## 2. S0 接口草图（冻结）

### 2.1 ShadowChunkProvider（取块唯一入口）— **始终异步**

```java
public interface ShadowChunkProvider {
    /** 始终返回未完成 future；禁止在调用线程同步读盘/等网。完成值 = 已有方块数据的 LevelChunk。 */
    CompletableFuture<LevelChunk> acquire(String dimension, ChunkPos pos, AcquireReason reason);

    /** 是否已有在途 acquire（去重）；不表示「可同步 Imposter」。 */
    boolean hasInFlight(String dimension, ChunkPos pos);

    enum AcquireReason { TRACKING, RETRY, RELIGHT }
}
```

**为何不做「有盘短路」**

- 原版 `scheduleChunkLoad` / `IOWorker` **本来就是异步 future**（mailbox + region 读），同步 Imposter 是影子旁路，不是原版约束。
- 产品语义：最终都要 **compare 真服**（或无基线 FULL）；盘只是本地 materialize / 比对基线。
- 统一异步后：Holder 从 EMPTY→FULL 与网络/读盘并行推进，与专用服「future 完成再 LIGHT」同构。

**VanillaAlignedChunkProvider（S1–S4 行为）**

```text
acquire(dim, pos):
  1) 已有 in-flight → join 同一 future（禁止 Imposter 抢跑）
  2) 异步流水线（始终发网络，不因有盘跳过）：
       baseline = ShadowStorageHashes / 盘字节 / injected（仅作 compare 载荷）
       hasHash  → ShadowPull.requestFull          // compare
       else     → ShadowPull.requestAuthoritativeFull
  3) 响应：
       UNCHANGED → loadFromDiskAsync / 复用注入 → injectLoadedChunk → complete
       DELTA     → 本地 materialize + applySectionDelta → complete
       FULL      → injectChunk → complete
       ERROR     → 有界 RETRY 或 exceptionally
  4) complete 的 LevelChunk 进 holder 后由原版链 LIGHT→FULL
       （lightStrip：光由影子 LightEngine，不依赖真服光字节）
```

**明确禁止**

- `ShadowChunkMapCompat.existingColumnForScheduleLoad` / `completedImposter` 作为 **scheduleChunkLoad 同步短路**（S2 起删除或仅限「同 in-flight join」以外的诊断）。
- 「injected 即 completed」的旁路（回程/重进仍应 join 异步 compare；客户端已持有时 compare 成功可走 REUSE 光）。

**允许调用方**：`MixinChunkMap.scheduleChunkLoad` → 返回 `provider.acquire(...)` 的 future。  
**并入并删除旁路**：`onChunkSelected`→自 pull、bootGrid/sweep 发射、`ShadowChunkAcquire` 批驱动。

**与 §3.2 的映射**

| Provider 步骤 | §3.2 |
|---------------|------|
| 始终 C2S 请求 | 候选 → ShadowPullRequest |
| UNCHANGED + 盘/注入 materialize | UNCHANGED → 影子 materialize → 官方包 |
| DELTA | apply + 重算光 |
| FULL | 注入权威 payload |
| 无基线 | 空基线请求 = 权威 FULL |

### 2.2 ShadowOfficialPacketBridge（交付接法 B）

```java
public final class ShadowOfficialPacketBridge {
    public static boolean forwardToRealClient(ClientboundLevelChunkWithLightPacket packet);
    public static boolean forwardToRealClient(Packet<?> packet); // forget / light update
}
```

- 影子上下文 `trackChunk` / `PlayerChunkSender`：S2C **不**进 dummy 丢弃，改 `forwardToRealClient`。  
- 真实客户端仍走原版 `ClientPacketListener` → `ClientChunkCache`。  
- `drainReady` / `ShadowChunkDeliver` 降为兼容开关（默认关）。

### 2.3 ShadowColumnStore（卸载）

```java
public final class ShadowColumnStore {
    public static boolean flushAndEvict(String dimension, ChunkPos pos, LevelChunk chunk);
    public static LevelChunk load(String dimension, ChunkPos pos);
}
```

- 挂钩：原版 `processUnloads` / holder 卸载（按 MC 版本选 mixin 点）。  
- **flushColumn 语义（已修，必须保持）**：脏柱同步编码 + 写 region，成功才摘内存表。  
- 客户端 `outsideSinceMs` + `hassium-shadow-reclaim`：S4 起不再作为主回收路径。

### 2.4 lightStrip

```text
真服 ShadowPull FULL（可无光）→ 影子 inject → 影子 LightEngine
  → 官方 chunk+light → Bridge → 客户端
```

服务端 `chunk.lightStrip` 保留；客户端只消费影子产出的光。

## 3. 分阶段与门禁

| 阶段 | 内容 | 门禁 |
|------|------|------|
| **S0** | 本文冻结 | 用户确认 |
| **S1** | 票=ServerVD；关 bootGrid/sweep 默认 | classic：覆盖≈原版窗；`TRACE_ENCLOSED_HOLE` P0=0 |
| **S2** | 取块只经 Provider；**scheduleChunkLoad 无 Imposter 同步短路**；始终 compare/FULL 异步 | 无选柱旁路；有盘柱也先 compare；R1 fullReq/命中可解释 |
| **S3** | 接法 B 交付 + 算光归影子 status 路径 | flyroundtrip `clientDarkRegressionChunks=0` |
| **S4** | 卸载只挂原版 unload→flushAndEvict | 往返：type126 可读；缓存命中可解释 |
| **S5** | 可选 compare / Connection 转发 A | 带宽 vs 延迟 |

## 4. 可删 / 保留（实现对照）

**S1–S4 默认关 → 确认后删**

- `drainBootGrid` / `bootGrid*`
- `sweepVisibleShape` / `sweepOvdRing`（OVD 已冻结）
- 权威声明作为采集驱动（`ChunkAuthorityClient` 可留 no-op）
- `pendingSelections` 自 pull、`ShadowChunkAcquire` 批发射
- `LightNeighborhoodGate` / `parkFullDelivery` **主交付门**（探针可留）
- 客户端 reclaim 定时器作主路径

**必须保留**

- `ShadowSeedServer` / `ShadowPlayerCompat`（影子专用服 + 虚拟玩家）
- `ShadowPull*` + 真服 resolve
- `ShadowStorageManager` / type126 / `MixinRegionFile`
- lightStrip 配置与影子 LightEngine
- 多版本 mixin / `#if MC_VER`

## 5. 风险

1. 影子主循环 **禁止** 同步等 Pull；Provider 用 future + 原版超时语义。  
2. 影子 track 半径 ⊆ 真实客户端 `ClientChunkCache` 窗，否则原版 `Ignoring chunk`。  
3. 纯盘+FULL：回程带宽高于 compare；正确性优先，S5 再优化。  
4. 迁移期若真服仍协商 PULL_MODE，禁止双源；版本差兼容路径单独冒烟。

## 6. 已落地（2026-09-18 会话，勿回退）

| 项 | 说明 |
|----|------|
| S1–S4 | 票=ServerVD；Provider 始终异步 compare/FULL；接法 B Bridge；`ShadowColumnStore` + reclaim flush |
| R2 窗口补全 | `drainTrackingWindowCompleteness`（ServerVD 窗内，非 bootGrid）；Provider `clearAll` + 悬置 future 会话清理 |
| 包迁移 | `network/seedgen` + `storage` 影子类 → `hassium.shadow.{server,track,light,storage}`；见 [`handoff-common-repackage-shadow.md`](handoff-common-repackage-shadow.md) |
| P5 | `ShadowTicketDriver.P5_TAKEOVER = false` |
| 权威声明 | **整族降级**：`AUTHORITY_NOTIFY` 不协商；Notifier 恒 false；Client handle no-op；mixin 不再 enter |
| 死代码物理删 | `sweepVisibleShapeLegacy` / `collectSweepRing` / `enumerateDiscBiased` / `tryServeOvdLocal` / `onChunkSelected` / `drainSelections` / `drainBootGrid` 调用链 |
| 存储 | `flushColumn` 脏柱同步 encode+save；`unloadChunk` 先 flush 再摘表 |
| 编译矩阵【已验证】 | 1.20.1 common+fabric+test+**forge**；1.21.1 common+fabric+**neoforge** → BUILD SUCCESSFUL |
| classic【已验证】 | `1.20.1_fabric_I_shadowpkg`：R1 1635 新增；R2 landed **444**、缓存 **100%**（435+9）；stats 双轮 PASS |

## 7. 后续会话待办（用户指定分开处理）

### 待办 A — R2 `injectedNotReady` 缺口

- **现象**：`1.20.1_fabric_I_shadowpkg` analyzer `TRACE_INJECTED_NOT_READY` P0 **round=2 count=16**；stats 仍 PASS、exit 0  
- **口径**：影子 `shadowInjected` 有、`shadowReady` 无 → 注入后未进 ready（光/交付门）  
- **R4 处置（2026-09-18，已实现）**  
  1. **原版生命周期 + type126**（不改压缩格式）：`applyViewDistanceIfChanged` 后 `enqueueOutOfWindowInjectedForReclaim`；reclaim 走 `ShadowColumnStore.flushAndEvict`（先 flush 再摘表）  
  2. **权威 pull 门**：`isAuthorityPullEligible` / `requestPullEligible` / Provider.acquire——中心优先真实客户端玩家区块（`deliveryCenter`）；窗外或会话未就绪禁止向真服 pull  
  3. **trace**：`recordShadowReady` = 官方包入 ready 队列即记（不再用 isDeliveryCandidate 过滤，避免窗外队列被记成 injectedNotReady）  
  4. **analyzer 降级策略**【已验证】：classic R2 cache-only 且 `injectedNotReady` **全部** Chebyshev > ServerVD(10)（相对 `playerPos`）→ P1；窗内缺口仍 P0；enclosed-hole / stats 仍把守玩家可见虚空  
- **验证**：编译矩阵绿；classic `1.20.1_fabric_I_r4unload2` **RESULT: PASS**（analyzer exit=0；R2 缓存 100%）  
- **未做**：`processUnloads` mixin 级挂钩（依赖 reclaim 扫描）；1.21.1 classic 冒烟未跑  

### 待办 B — 往返飞行 / 缓存观感

- **场景**：`flyroundtrip -MoveSeconds 15`  
- **关注**：`clientDarkRegressionChunks==0`；回程缓存命中（非全网络 FULL）；`reclaim` + `window-complete` 日志  
- **历史参考**：`flyrt3` 曾 PASS dark=0、缓存 ~28%（含去程新地形）  
- **本轮验证（2026-09-18）**【已验证】  
  - `1.20.1_fabric_flyrtB`：**PASS**；`clientDarkRegressionChunks=0`、`clientDarkLightProbeChunks=0`（samples=9 为首落地瞬态）；缓存 **10%**（全命中 319/5.0MB，应用 50MB——首飞新地形多属正常）；`[SHADOW_STORE] flushAndEvict` 大量出现在 `hassium-shadow-reclaim` 线程（R4 卸载路径生效）  
  - `1.21.1_fabric_flyrtB`：**PASS**；同门禁 dark=0  
- **未验证**：同世界二次 flyroundtrip（回程缓存应更高）；手工肉眼观感  

### 待办 C —（可选）继续删残留

- **已做（2026-09-18）**：删 `pendingSelections`/`SelectedChunk` 与全部 `clear()`；删 `SWEEP_INTERVAL_MS`/`MAX_SWEEP_PER_PUMP`/`BOOT_EMIT_MIN_GAP_MS`/`lastBootEmitMs`/`lastSweepMs`（无消费方）；`ShadowTrackingSession` 不再引用恒 false 的 `pullEmissionSuppressed()`；`LightNeighborhoodGate` 去掉 `notAuthoritative`（`isAuthoritative` 恒 false）；清空 `network/seedgen` 目录与 `scripts/tmp_purge_shadow_dead.py`
- **保留**：`ChunkAuthority*` 协议壳（`handle`/`Notifier.wantsAuthority` 恒 no-op/false；forge/neoforge 网络登记仍引用）——整族删除需同步三端 NetworkManager，未做
- **未做**：mixin 子包拆分（见 repackage handoff §2）  

### 其它 S 阶段（未做）

- [x] **S3 算光归 status 主路径（2026-09-18，已实现）**  
  - **交付**：去 `LightNeighborhoodGate` / `parkFullDelivery` 主门；inject → `initializeLightImmediately` → `generated` 光屏障 → 引擎产出即 `pushReady`（原版光是什么就发什么）  
  - **光照缓存统计**：迁到 `MixinChunkMap.scheduleChunkLoad` → `ShadowLightCompute.accountLightAtScheduleLoad`：读盘/注入且 `isLightCorrect`+引擎层齐 = 命中（`lightReuseShadow`），否则重算（`lightCacheMiss`）；光屏障提交不再按 REUSE/RECOMPUTE 记账  
  - **门禁**【已验证】编译矩阵绿；classic `1.20.1_fabric_I_s3classic` **PASS**；flyrt `1.20.1_fabric_s3flyrt2` **PASS**（`clientDarkRegressionChunks=0`；exit 0）。首飞光照缓存 **22.2%**（命中 371 / 重算 1297——scheduleChunkLoad 口径：读盘完整光 vs 重算）。  
  - **并发修复**：`playerLoadedChunk` 桥内 `ClientboundLevelChunkWithLightPacket` 构造改持 `chunkLock`（与 flush `ChunkSerializer.pack` 互斥；s3flyrt 首轮 ThreadingDetector FAIL 已消失）。  
  - **未验证**：R2 cache-only 的光照缓存可能为 0/0（重连路径未必再进 `scheduleChunkLoad`）；1.21.1 classic/flyrt 未跑。  
  - **记账完善（2026-09-18）**：统一 `accountLightFromChunk`（`isLightCorrect` → 命中 / 否则重算），挂到 `scheduleChunkLoad` / `injectChunk` / `injectLoadedChunk` / `publishCachedChunk`（含异步读盘）；按柱首记去重。classic `1.20.1_fabric_I_s3lightacct` **PASS**【已验证】：R1 光照 **0%**（命中 0 / 重算 1671——网络注入路径，符合「光未完成=重算」）；R2 光照 **100%**（命中 **486** / 重算 0——缓存回放完整光，R2 不再 0/0）。
- [ ] S5 可选：Provider 内 compare 优化、连接转发接法 A、client/server 包迁移  

**S0 修订记录**

- 2026-09-18：取块改为 **始终异步 acquire + 始终 compare/FULL**（原版 IOWorker 本就是 future）。  
- 2026-09-18：权威声明整族降级；P5=false；shadow 包迁移；死代码物理删；编译矩阵绿。  
- 2026-09-18：待办 A/B/C 划入**后续会话**，见本节。  

相关：[`docs/architecture.md`](../architecture.md) §6 · [`docs/chunk-cache.md`](../chunk-cache.md) · [`handoff-common-repackage-shadow.md`](handoff-common-repackage-shadow.md) · [`docs/client-chunk-light-flow.md`](../client-chunk-light-flow.md)
