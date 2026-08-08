# Chunk Pipeline Isolation + Seed-Gen 区块再生计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement remaining tasks task-by-task.
>
> 本计划是**架构级改造路线图**：Phase 0 先行（隔离重构），Phase 1–4 依次推进（seed 再生）。各 Phase 有独立验收，可单独落地、单独回滚；Phase 0 是其余全部的前置。

**Goal:** 两步走：

1. **隔离重构**：把客户端区块摄入从「static 工具类 + 散落 mixin 拦截」收敛为数据源无关的 pipeline（`ChunkSource → ChunkPayload → ChunkApplier`），消除对客户端代码的深度入侵，为后续新增数据源提供稳定边界。
2. **Seed-Gen（终极优化）**：针对跑图/飞行玩家，利用 worldgen 确定性（同 seed、同版本、同 registry → 同输出），服务端对 pristine（本会话生成且未修改）区块只发「新生成标识 + 坐标 + hash」，客户端本地复算区块；一致性由现有 chunkHash / sectionHash 体系闭环校验，任何不一致自动回退增量/全量。

**Architecture:** 区块数据源从三种扩为四种，全部汇入同一 pipeline 落地：

```
ChunkSource (接口：ServerPush / DiskCache / OVD / SeedGen)
   ↓ CompletableFuture<ChunkPayload>      ← 纯数据 DTO，不碰 MC API
ChunkIngestPipeline（后台：解压/解码/判读/hash/本地生成）
   ↓ ChunkApplyRequest（sections/heightmap/light/BE/chunkHash/isLightOn）
MainThreadDispatcher（距离优先 + 时间预算，既有设施）
   ↓
ChunkApplier（唯一碰 LevelChunk.replaceWithPacketData 的地方）
```

**Tech Stack:** Java 17, Manifold, Mixin, Fabric/Forge/NeoForge, JUnit 5, PowerShell smoke。

## Global Constraints

- `common` 模块不 import Fabric / Forge / NeoForge（既有例外维持不变）。
- **Phase 0 不得改变任何行为**：R1/R2 命中率、带宽、冒烟 markers 必须与改造前一致。隔离是纯结构移动，不是行为变更。
- 禁止关功能换绿；禁止破坏 1.20.1 fabric 已验证路径（基线：1.20.1 fabric Phase R 冒烟流畅运行）。
- 数据源无关性：pipeline 内部不得出现「if 来源 == ServerPush」这类分支；来源差异只存在于 Source 实现内。
- `ChunkApplier` 是唯一允许触碰 `ClientLevel` / `LevelChunk` / light engine 原版 API 的类；mixin 只保留收包入口与 tick 闸门两个触点。
- 版本分段沿用 `#if MC_VER` + `compat/` 既有规则；跨版本差异进 `common/.../compat/`。
- Windows host：构建 `cmd /c "gradlew.bat --no-daemon <module>:<task> -Pmc_ver=1.20.1 --console=plain"`，跑完必有 `taskkill /F /IM java.exe`。
- 每个 Phase 结束跑 `common:test` + 对应加载器 `compileJava` + 冒烟；全绿才算 Phase 完成。

## 1. 当前状态核查（HEAD `docs` 现状，证据）

### 1.1 已确认事实（代码证据）

- **客户端接收链已分段后台化**：netty/数据面 → `ClientChunkHandler.handleCompressedChunk`（后台线程 ZSTD 解压 + 异步入库）→ `MainThreadDispatcher.execute`（距离优先级 `KeyedPriorityQueue`）→ 主线程 `applyChunkData` → `applyChunkDataInternal`（自建 LevelChunk / replaceWithPacketData + 光照落地）。
- **onTick 非唯一闸门**：`MixinClientTick` HEAD 挂 `TickMonitor.beginClientTick` + `ClientMainThreadBudget.beginFrameCacheBudget`；TAIL 挂 L2 unfreeze。调度主体在 `MainThreadDispatcher.flushClient*`（预算内消费队列），tick 只是闸门。
- **入侵证据**：
  - `ClientChunkHandler` 全 static：`pendingContentHashes` / `pendingSectionHashes` / `clientStorage` 全局状态，断连靠 `resetStorage()` 手动清（`MixinClientPacketListener` 调）。
  - `MixinVanillaChunkApplyBudget`：HEAD 拦截 `handleLevelChunkWithLight` 重定向到预算队列，用 `ThreadLocal BUDGETED_APPLY` 重入旗标 + `isHassiumApplyInProgress()` 静态检查防循环——拦截原版方法 + 静态协作，入侵的直接痕迹。
  - mixin 触点散落：`ClientPacketListener`（apply 预算）、`ClientLevel`（freezeTick / freezeTickEntities / unload 落盘 / renderOnly）、`Minecraft.tick`（HEAD/TAIL）、`MinecraftServer.tickServer`（服务端 flush）。
  - `applyChunkDataInternal` 单方法兼任四职：缓存判读、光照处理、建 chunk、写 world。
- **已有隔离雏形**：`DataPlaneClientBundle.ChunkDispatcher` seam（注释明写「避免直接调 MC API」）、`PromethiumLightBridge` 反射桥（引擎零编译依赖）、`SectionDeltaDispatcher` seam。但默认实现仍直调 static `ClientChunkHandler`。
- **可复用设施**（Phase 1–3 全部吃到）：
  - 客户端：`MainThreadDispatcher` + `KeyedPriorityQueue`、`ClientMainThreadBudget`（JoinBoost 续期）、并行光照引擎（`LightComputeService` / Promethium 桥）、HBT1 磁盘缓存 + `ChunkDiskCodec`、`ClientCacheLoadQueue`。
  - 网络：`chunkHash` / sectionHash 体系、`SectionDeltaS2CPacket` 增量管道（≥75% 变更自动回退全量）、`LightDeltaS2CPacket`、Bloom 同步、`ChunkDataRequestC2S`。
  - 服务端：`ServerChunkPushManager`（拦截 `ChunkHolder` / `PlayerChunkSender`，已知区块生成完成时机）、`MixinChunkHolder`、握手尾部 append 惯例（`isReadable` 协同演进，不 bump protocolVersion）。

### 1.2 现状缺陷总结

1. 三种数据源（服务端全量 / 磁盘缓存 / OVD renderOnly）共享 static 工具类集合，第四种（SeedGen）再挤进来必炸。
2. 状态生命周期与连接解耦（static + 手动 reset），断连/重连的正确性靠调用方纪律。
3. apply 路径职责混合，无法单测（需要 Minecraft bootstrap）。

## 2. 目标架构

### 2.1 接口定义（Phase 0 交付物）

```java
// common/.../cache/client/ChunkSource.java
public interface ChunkSource {
    /** 异步取区块数据；失败/不可用返回 completedFuture(null) 由 pipeline 回退下一来源 */
    CompletableFuture<ChunkPayload> fetch(ChunkPos pos);
    /** 来源名（指标用）："server_push" | "disk_cache" | "ovd" | "seed_gen" */
    String name();
}

// common/.../cache/client/ChunkPayload.java — 纯数据，禁止持有 MC 对象
public record ChunkPayload(
    ChunkPos pos,
    @Nullable byte[] nbtOrPacketBytes,   // ServerPush/DiskCache：原始字节
    @Nullable CompoundTag nbt,            // 预解析 NBT（避免重复读盘）
    boolean isLightOn,                    // HBT1 is_light_on 标志
    long contentHash, long[] sectionHashes,
    boolean renderOnly,                   // OVD 专用
    // SeedGen 专用：本地生成的区块数据以 ChunkApplyData 形态承载（见 §5）
    @Nullable ChunkApplyData generated
) {}

// common/.../cache/client/ChunkApplyData.java — SeedGen 本地生成结果（纯数据）
// sections palette/state、heightmaps、light arrays、blockEntities 的不可变快照，
// 由 ChunkApplier 转成 LevelChunk。

// common/.../cache/client/ChunkApplier.java — 唯一碰原版 API 的类
public final class ChunkApplier {
    /** 主线程调用：把 payload 落地到 ClientLevel */
    public static boolean apply(ClientLevel level, ChunkPayload payload);
}
```

pipeline 协调者 `ClientChunkPipeline`（实例化，生命周期随连接；替代 `ClientChunkHandler` 的 static 角色）：
- 持有来源链：ServerPush 优先 → DiskCache（R2）→ OVD；SeedGen 插入 ServerPush 之前（Phase 2 后）。
- 负责来源回退语义（如 DiskCache 命中但 hash MISMATCH → 请求增量，失败回退全量）。
- `MainThreadDispatcher` 仍是唯一落地闸门（距离优先 + 预算），pipeline 只决定「提交什么任务」。

### 2.2 mixin 触点收缩

| 触点 | 现状 | 目标 |
|---|---|---|
| `ClientPacketListener` chunk 收包 | HEAD 拦截 + 重入旗标 + 静态检查 | 仅一个入口注入：`handleLevelChunkWithLight` HEAD → `ClientChunkPipeline.onVanillaChunkPacket(packet)`（内部决定预算化或放行），重入旗标收敛进 pipeline 实例 |
| `Minecraft.tick` | HEAD/TAIL 各一组 | 保持（闸门），只调 pipeline 的 `tickFrame()` |
| `ClientLevel` unload/renderOnly/落盘 | 三个注入点 | 收敛为 `ChunkApplier`/pipeline 内部回调，mixin 只留 unload 一个转发点 |
| 服务端 `MinecraftServer.tickServer` | flush | 不动（服务端侧已收敛） |

### 2.3 状态生命周期

- `ClientChunkPipeline` 实例在 login 后创建、断连时销毁；所有 pending hash、storage 引用、队列归属随实例，删除 `resetStorage()` 类手动清理。
- `ClientHassiumStorage` 仍可静态持有（无连接语义），但引用只经 pipeline 暴露。

## 3. Phase 0 — 隔离重构（前置，必做）

**动机**：为 Phase 1–4 提供稳定边界；纯结构移动，零行为变更。

**步骤**：

1. 定义 `ChunkSource` / `ChunkPayload` / `ChunkApplier` 接口（§2.1），`ChunkApplyData` 先建骨架（Phase 2 填充）。
2. 拆 `applyChunkDataInternal`（现 `ClientChunkHandler.java:401`）：缓存判读 → `ChunkPayload` 构建 → apply 三段分离；apply 段移入 `ChunkApplier`。
3. static 状态收拢：`ClientChunkHandler` 的 pending hash 表 / storage / dirty tracker 归入 `ClientChunkPipeline` 实例字段；`ClientChunkHandler` 退化为薄门面（保留既有调用方签名，内部转发，Phase 4 后删除）。
4. `MixinVanillaChunkApplyBudget` 重写：去掉 `BUDGETED_APPLY` ThreadLocal + `isHassiumApplyInProgress()` 静态检查，改由 pipeline 实例内重入标志；vanilla 包与缓存 apply 共用同一预算队列（行为不变）。
5. `DataPlaneClientBundle.DEFAULT_DISPATCHER` 改指 pipeline 实例（不再直调 static）。
6. 断连/重连路径：`resetStorage()` 调用点改为实例销毁（`MixinClientPacketListener` / `ClientLifecycleHelper` 等既有调用方核对）。
7. 验收：
   - `common:test` 全绿（baseline 预存失败清单不变）。
   - 1.20.1 fabric Phase R 冒烟：R1/R2 命中率区间、Disconnect dump 计数、OVD 行为与改造前一致（对照 `smoke-blackchunk-handoff-20260808.md` 记录的基线）。
   - 1.21.11 fabric + neoforge 编译绿。

## 4. Phase 1 — seed 握手 + pristine 跟踪（服务端先行）

**动机**：SeedGen 的正确性前提 = 服务端能识别「可安全复算」的区块，并把 seed/指纹交给客户端。

### 4.1 握手扩展（沿用 append + `isReadable` 惯例，不 bump protocolVersion）

新握手尾部字段（S2C）：
- `worldSeed`（long）：主世界 seed；下界/末地按原版派生规则由客户端自行推导（同一 `WorldgenRandom` 派生链），仅自定义维度需额外 seed（见下）。
- `dimensionSeeds`（可选 map：ResourceKey → long）：仅当维度 `LevelStem` 配置了独立 seed 时出现。
- `worldgenFingerprint`（long/byte[]）：服务端当前世界生成配置指纹 = hash(维度 generator settings 序列化 + 注册表版本 + 本 mod 版本)。客户端用它判定「本地能否复刻」。
- `seedGenEnabled`（bool）：服务端配置开关 + pristine 跟踪就绪标志。

客户端在握手尾部署位 `seedGenSupported`（本地 worldgen 指纹匹配才置位）。

### 4.2 pristine 跟踪（服务端）

**定义**：区块「本会话内生成完成（status FULL + 光照就绪）且此后从未被任何来源修改」。

- 生成完成 hook：`MixinChunkHolder` / `ServerChunkPushManager` 已知生成完成时机，此处登记 `PristineRegistry`（`ConcurrentHashMap<chunkKey, long generationEpoch>`）。
- 改动标记：注入 `ChunkAccess.setBlockState`（或 `ServerLevel.setBlock`，取侵入更小者）——对 pristine 区块首次改动时移除登记。结构放置、实体落盘（`LevelChunk` 写入时）不算「修改」？——**不算**：worldgen 管线内的放置发生在登记之前（先 FULL 后登记），登记后的一切 setBlock 均视为修改。此语义要写死在 PristineRegistry 注释里。
- 持久化：**不持久化**。存档重启后全部区块视为非 pristine（走缓存/全量）。跑图玩家连续在线场景覆盖；重启后首段加载成本由既有缓存兜底。工程取舍：避免改动标记写盘（Region 格式不动）。
- 修改后区块的后续同步：非 pristine 后不再发 SeedRef，后续按既有 chunkHash/sectionDelta 通道走（玩家改动本来就由原版 `ClientboundBlockUpdatePacket` / section update 同步——**「后续方块更新走原版逻辑」的落点**）。

### 4.3 SeedRef 包（S2C）

新包类型 `SeedRefS2CPacket`：`[chunkX, chunkZ, chunkHash, sectionHashes[]]`，几十字节。语义：**该区块是 pristine，客户端若支持 seedGen 请本地生成并按 hash 校验**。

- 服务端发送时机：`ServerChunkPushManager` 推送管线判定 pristine 且客户端 `seedGenSupported` 时，替代全量区块数据。
- 客户端不响应 → 服务端超时（复用既有 `ChunkDataRequestC2S` 超时机制）后回退全量直推。
- 客户端本地无生成能力（CPU 不足/生成器不匹配）→ 立即回 `ChunkDataRequest` 拉全量（现有通道）。

### 4.4 gate 清单

| gate | 条件 | 不满足时 |
|---|---|---|
| 配置开关 | `network.seedGen.enabled=true`（双端） | 全量直推 |
| 指纹匹配 | 客户端 `worldgenFingerprint` == 服务端 | 客户端不置位 `seedGenSupported` |
| pristine | 会话内生成 + 未修改 | 全量直推 |
| 客户端能力 | 本地生成上下文可用 | `ChunkDataRequest` 回退 |

**验收**：
- 服务端：pristine 区块发 SeedRef、非 pristine 发全量；改动标记后不再发 SeedRef。
- 客户端：握手尾部解析 + 指纹比对；收到 SeedRef 走回退路径（Phase 2 前：直接 `ChunkDataRequest` 全量，行为等价现状）。
- 1.20.1 / 1.21.11 各段冒烟握手不破。

## 5. Phase 2 — 客户端 headless worldgen

**动机**：客户端本地复算 pristine 区块。工作量大头，分两案。

### 5.1 方案 A：影子 `ServerLevel`（推荐先做）

- 客户端持有隐藏的 `ServerLevel` 实例（seed = 服务端 seed，同 registry/datapack 上下文），**关闭**：tick 调度、实体、`ChunkMap` 落盘、玩家追踪；**只开**：`ChunkGenerator` 生成管线 + 光照。
- 生成流程：后台线程池（`ExecutorFactory`，注意虚拟线程超订坑——纯 CPU 任务用固定平台线程池，见既有 `hassium-virtual-thread-oversubscription`）按距离优先级（复用 `KeyedPriorityQueue`）跑 `ChunkStatus` 全链（noise → structures → features → light），产出 `ChunkApplyData` 快照 → 主线程 `ChunkApplier.apply`。
- 优点：完整复用原版生成管线（结构/生物群系/高度图全对），代码量最小；单机模式客户端本就内置服务端逻辑。
- 缺点：影子世界内存/初始化开销；需要维护一个「生成专用上下文」的生命周期（随连接创建/销毁）。
- 关键点：影子世界**永不写盘、不 tick、不生成实体**；光照交给 Hassium 并行引擎（Phase 2 直接用既有 `LightComputeService` 路径，`ChunkApplyData` 的 light arrays 由它填充）。

### 5.2 方案 B：直接 `ChunkGenerator` 管线（备选，更干净更贵）

- 不建 ServerLevel，直接调 `ChunkGenerator` 各阶段（`fillFromNoise` / `buildSurface` / `applyBiomeDecoration` / `createStructures`）+ 手工构造 `WorldGenRegion` / `StructureManager` / `RandomState` 上下文。
- 优点：无影子世界开销；缺点：原版 `ChunkStatus` 管线状态机分散在 `ServerChunkCache` / `ChunkMap`，手工复刻易漏阶段（尤其 structure start 注册、feature 的 `WorldGenRegion` 依赖）。

**决策**：先 A 后评估；A 跑通后若内存/初始化开销不可接受再切 B。A/B 产出同一 `ChunkApplyData`，pipeline 侧无感知。

### 5.3 生成调度与体验

- 生成线程池并行度 = `clientCache.parallelLightEngineThreads` 同源配置（或新增 `seedGen.threads`，默认 2）。
- 优先级：近玩家先生成（`MainThreadDispatcher` 距离语义复用）；预生成方向：玩家速度向量 + 视距环带。
- 落后保护：玩家移动快于生成时，缺口区块直接回退 `ChunkDataRequest` 全量（SeedRef 已到而生成未完成 → 降级），保证画面无洞。
- 生成结果落磁盘缓存（HBT1）——SeedGen 区块同时是缓存写入源，后续重进直接命中。

**验收**：
- 对照实验：同 seed 固定世界，服务端生成的区块 NBT 与客户端本地生成逐字节/逐 section 一致（hash 比对 100% 匹配）。
- 生成吞吐 ≥ 跑图需求（基线：1.20.1 单机 100+ chunks/s 多核）。
- 冒烟：SeedGen 路径下区块无黑块、光照正常（并行引擎落地）。

### 5.4 影子服务端构造链（已确认 API 事实，2026-08-08 mojmap 反编译验证）

**结论先行**：方案 A 的实质是「客户端进程内构造一个不 spin 的迷你 `MinecraftServer` 子类」。1.20.1 与 1.21.11 的外层契约几乎一致，差异集中在 `WorldLoader.InitConfig` 与 `ChunkProgressListenerFactory`/`LevelLoadListener`。模板直接照抄 `IntegratedServer`（客户端进程本就有服务端全部类）。

**构造链（两版本通用）**：
1. `WorldStem = WorldLoader.load(InitConfig, WorldDataSupplier, ResultFactory, Executor, Executor)`（1.20.1/1.21.11 签名一致；内部 1.21.11 走 TagLoader→RegistryDataLoader 链，外部不可见）。`WorldStem = record(resourceManager, dataPackResources, registries, worldData)`（两版本同）。
2. 子类构造：`super(Thread, LevelStorageAccess, PackRepository, WorldStem, Proxy, DataFixer, Services, Listener)` —— 1.20.1 第 8 参 `ChunkProgressListenerFactory`，1.21.11 换 `LevelLoadListener`（`MinecraftServer` 同 8 参）。
3. **playerList 必须先于 createLevels**：`createLevels` 内调 `getPlayerList().addWorldborderListener(...)`。模板：构造里直接 `setPlayerList(new IntegratedPlayerList(this, this.registries(), this.playerDataStorage))`（两版本 `IntegratedServer` 均如此，无 `createPlayerList` 抽象）。
4. 不走 `spin`/`runServer`：构造后手工调 `initServer()`（内部 `loadLevel()` → `createLevels()` + `prepareLevels()`），随后即可 `overworld().getChunkSource().getChunk(pos, ChunkStatus.FULL, true)` 驱动生成管线。
5. `createLevels` 差异：1.20.1 带 `ChunkProgressListener` 参数；1.21.11 无参（`levelLoadListener.updateFocus` 直接取 `selectLevelLoadFocusPos()`）。

**必须规避的副作用**：
- `setInitialSpawn`（`worldData.overworldData().isInitialized()==false` 时触发）会立即生成 11×11 spawn 区域 + 找 spawn 点 + 可能放 bonus chest —— 影子世界应在构造 `WorldDataServer` 后置 `setInitialized(true)` 跳过，spawn 语义对本课题无意义。
- `prepareLevels` 等 `getTickingGenerated()==441`（START ticket 半径 11 全生成）—— 生成专用上下文不需要 START ticket；跳过 `prepareLevels` 只调 `createLevels`。
- 临时 `LevelStorageAccess`（`LevelStorageSource.createDefault(tmpPath).createAccess(...)`）会写磁盘（region/level.dat）；用完 close + 删临时目录。`ReloadableServerResources` 会跑完整 datapack 资源加载（配方/战利品/loot），是构造最重的部分——初始化成本实测后再评估缓存/懒加载。

**`SeedGenLevelCompat` 分段点**（初步，实施时再核对）：
- `WorldLoader.InitConfig` 构造：1.20.1 `functionCompilationLevel`（int） vs 1.21.11 `functionCompilationPermissions`（`PermissionSet`）。
- `MinecraftServer` 构造第 8 参类型 + `createLevels` 签名（见上）。
- `ChunkStatus.FULL` 引用（1.20.5+ 包位移）与 `getChunk` 生成入口（1.21.2+ 管线重构但公共入口不变）——复用既有 `MC_1_20_5`/`MC_1_21_2` 边界。
- `LevelStorageAccess` 构造路径两版本差异（1.21.x `RegionStorageInfo` 引入）——实施时核对。

**与单机模式的关系**：单机/局域网时客户端进程内已有现成 `IntegratedServer`，影子服务端仅多人连接需要；影子实例随连接创建/销毁，与 `Minecraft.getMinecraft().getSingleplayerServer()` 互斥（`hassium-singleplayer-lan-gating` 语义）。

## 6. Phase 3 — hash 校验闭环

**动机**：任何确定性破口（战利品 roll、插件 setBlock、datapack 差异、mod side-only 生成）都必须被检测并自动修正。**本 Phase 是 SeedGen 的安全网，未完成前 SeedGen 不允许默认开。**

流程：
1. 客户端本地生成完成 → 主线程 apply → 等待服务端 SeedRef 携带的 `chunkHash`/`sectionHashes`。
2. 比对：全匹配 → 计入 `seedGen.hit`；section 级不匹配 → 走既有 `SectionDeltaS2CPacket` 增量修正；chunkHash 级不匹配 → 整块 `ChunkDataRequest` 回退全量。
3. 修正落地后该区块按普通区块对待（写缓存、后续原版逻辑同步）。
4. 指标：`NetworkStats` 增 `seedGenHit` / `seedGenMismatch` / `seedGenFallback` 三计数（Phase 4 用）。

**注意**：SeedRef 的 hash 是**服务端生成时的 hash**；服务端在区块生成完成时计算（`ServerChunkPushManager` 已有 hash 管线，复用），不是推送时现算——保证「客户端复算 vs 服务端原始」而非「vs 当前」。

**验收**：人为制造不一致（服务端改一个方块后仍发 SeedRef 的测试 hook）→ 客户端必走增量/全量修正，画面最终一致；`seedGenMismatch` 计数正确。

## 7. Phase 4 — 观测与收敛

- 指标扩展（`HassiumMetricsImpl` / `NetworkStats`，遵循 `hassium-metrics-add-fields` 流程）：`seedGenHit/Mismatch/Fallback`、SeedRef 字节数 vs 全量字节数节省量、本地生成耗时分布。
- 对照实验：同 seed 世界，跑图飞行 5 分钟：
  - 基线（SeedGen 关）：总下行字节、区块平均延迟、客户端 mspt（`[MSPT]` 锚点）。
  - 实验（SeedGen 开）：同上三值 + 命中率。
  - 目标：下行字节降 ≥90%（跑图场景），区块延迟不劣于基线，mspt 无恶化。
- 配置收敛：默认关（`network.seedGen.enabled=false`），发布后按实测决定默认值。
- 多版本矩阵：1.20.1 fabric（基线流畅段）优先，1.21.x 分段跟进；每段独立验收（worldgen 注册表差异在 compat/ 收口）。

## 8. 风险与兜底

| 风险 | 等级 | 兜底 |
|---|---|---|
| 本地生成 ≠ 服务端（datapack/mod/战利品 roll/插件） | 高 | Phase 3 hash 闭环：只退化带宽，**不可能静默错误**；指纹 gate 挡已知不一致 |
| 客户端 CPU 不足，生成慢于移动 | 中 | 落后保护回退全量；线程数可配；近处优先 |
| 影子 ServerLevel 内存/初始化开销 | 中 | 方案 B 备选；不 tick 不写盘不生成实体，开销可控 |
| seed 泄露争议（反作弊/探图） | 低 | 服务器 owner 开关决定；seed 本可经 `/seed`/存档获取；默认关 |
| 自定义维度/独立 seed 维度 | 低 | 握手维度 seed 表；不支持的维度不置位 `seedGenSupported` |
| pristine 误判（插件静默改方块不走 setBlockState 路径） | 低 | hash 校验兜底（同第 1 行）；`inhabitedTime==0` 辅助判定 |
| 多版本 worldgen 注册表差异（1.20.1 vs 1.21.11） | 中 | 指纹含注册表版本；逐段独立验收，compat/ 收口 |

## 9. 顺序依赖与里程碑

```
Phase 0（隔离重构）───────┬── Phase 1（seed 握手 + pristine）
                          ├── Phase 2（headless worldgen）── Phase 3（hash 闭环）── Phase 4（观测收敛）
                          └── 独立可回滚
```

- Phase 0 完成即可独立收益（可维护性、单测可行性），不依赖后续。
- Phase 1 服务端侧与 Phase 2 客户端侧可并行开发（接口：SeedRef 包 + 指纹字段先定死）。
- Phase 3 未完成前 `seedGen.enabled` 恒 false，Phase 2 产出只走「生成→hash 比对→修正」的试验通道。
- 每 Phase 一个 commit 链，冒烟 marker 必须随 Phase 更新（`runtime-smoke-test.md` 新增 SeedGen phase 断言）。

## 10. 参考文档

- [`docs/architecture.md`](../../architecture.md) — 模块架构（Phase 0 完成后更新 §9.3 数据源表格）
- [`docs/chunk-cache.md`](../../chunk-cache.md) / [`docs/disk-nbt-cache.md`](../../disk-nbt-cache.md) — 缓存/hash/sectionDelta 设施
- [`docs/client-chunk-light-flow.md`](../../client-chunk-light-flow.md) — 客户端光照流程（Phase 2 复用并行引擎）
- [`docs/runtime-smoke-test.md`](../../runtime-smoke-test.md) — 冒烟 harness（各 Phase 验收）
- `docs/superpowers/plans/2026-07-26-udp-dataplane-rollout.md` — 计划格式与 Global Constraints 惯例
