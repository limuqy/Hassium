# 全局代码审查报告

> 日期：2026-09-13（2026-09-14 核查修订）  
> 范围：`common` + `fabric` / `forge` / `neoforge`  
> 方法：4 路并行（bug/并发、过度防御、重复实现、过度设计）+ 关键结论源码抽验  
> 性质：只读审查，未改任何代码；2026-09-14 核查修订仅改报告文本  
> 核查（2026-09-14）：四路只读核查，40 项断言逐条对照当前源码 + 原版反编译源码（1.20.1/1.21.1/1.21.11）——29 项属实、8 项部分属实、**3 项不属实**（均为线程模型误判：vanilla 已把 `onDisconnect`/`activate` 推迟到 server 线程串行）。覆盖：§一全部、§二全部、§三 3.1-3.8、§四抽样（4.1.1/4.1.3-4.1.5/4.2.1/4.2.5/4.2.6）、§五 5.2.1-5.2.9、§六 6.7-6.9/6.14；其余条目未核查。带【核查】标注处为修订结论；§八优先级已重排。

---

## 目录

1. [高严重度 bug](#一高严重度-bug)
2. [中等 bug](#二中等-bug)
3. [低严重度边角问题](#三低严重度边角问题)
4. [过度防御 / 异常吞没](#四过度防御--异常吞没)
5. [重复实现](#五重复实现)
6. [过度设计](#六过度设计)
7. [值得肯定、不建议简化的部分](#七值得肯定不建议简化的部分)
8. [处理优先级建议](#八处理优先级建议)

---

## 一、高严重度 bug

### 1.1 `ServerHandshakeActivation.drainPending`：connection 未挂载时玩家永久丢失【核查：部分属实，现行不可达】

- **位置**：`common/.../network/handshake/ServerHandshakeActivation.java:100-114`
- **问题**：  
  `onPlayerInit` 在 `ServerPlayer <init>` TAIL 入队时 connection **尚未挂载**（类注释已写明）。`drainPending` 每 tick `poll` 后调 `activate`；`activate` 在 `connection == null || !isConnected()` 时直接 `return`，**不回队**。  
  玩家从 PENDING 永久消失：激活序列（markPending / play_init / seed 下发）永不执行；`requireClientMod` 分支因 connection 为 null 也无法踢出无 mod 客户端。
- **核查（2026-09-14）**：机制逐条属实（`drainPending` L100 `poll` 取出即移除、`activate` L112-114 失败分支不回队），但**后果在当前 join 流程不可达**：原版 `handleAcceptedLogin` 在同一 server 线程调用栈内 `new ServerPlayer`（<init> TAIL 入队）→ `placeNewPlayer` 紧接着挂载 connection；`drainPending` 挂在 tickServer TAIL（`MixinMinecraftServer.java:74`），永远观察不到 null 中间态。唯一能触发失败分支的是登录中止（此时丢弃是正确行为）。**定性：潜伏设计缺陷**——若未来版本把 <init> 与 connection 挂载拆到跨 tick 即成真 bug。
- **建议**：`connection == null` 时回队（或延迟到 connection 就绪后再 activate），并加最大重试/超时兜底。优先级降为 P2-防御。

### 1.2 PENDING 窗口内聚合缓冲无上限 → 内存膨胀

- **位置**：`HassiumAggregationManager.java:158-167, 243-266`；`MixinConnection.java:125-126`
- **问题**：  
  `isPending` 时 `flush` 直接 `continue`，不做体积检查。`takeOver` 只 `list.add`，无条数/字节上限。`maxAggregationSize` 仅在 `flushInternal` 生效，PENDING 期间永不触发。客户端 ACK 迟到（最长 5s）期间 `Connection.send` 全部被 `ci.cancel` 吞进缓冲，单连接可膨胀到数百 MB。
- **触发条件**：聚合协商成功 + 客户端 `aggregation_ready` 延迟或丢失。
- **核查（2026-09-14，属实）**：`takeOver` 无上限（L131-134）+ `isPending` 时 flush 跳过 + `isActive` 含 PENDING，全部成立。补充：5s 无 ACK 的降级动作是**丢弃缓冲**而非直发，膨胀窗口有界 ≤5s（「数百 MB」为理论上限）。维持 P0。
- **建议**：`takeOver` 与 PENDING 缓冲加硬上限（字节/条数）；超限降级直发或丢弃并 warn。

### 1.3 `PullResponseDecodeQueue.discard()` 全局清空，任意 `Connection.disconnect` 都触发

- **位置**：`MixinConnection.java:141`；`PullResponseDecodeQueue.java:40-44`
- **问题**：  
  队列是**进程级单例**，但 `Connection.disconnect` HEAD 无条件 `discard()`。专用服上每个玩家断连都会清空（通常无害）；**集成服/LAN 主机**上远程玩家断连会清掉主机客户端的 pull 解码队列与 `ALIVE`，导致在途 `shadow_pull_response` 丢失、工人线程退出后短暂无法 `enqueue`（与 `STARTED` 竞态窗口）。
- **触发条件**：LAN/集成服 + 远程玩家断连 + 主机仍有在途 pull 响应。
- **核查（2026-09-14，属实）**：进程级单例 + `MixinConnection` disconnect HEAD 无条件 `discard()`，无 logical side gating，LAN/集成服场景成立。维持 P0。
- **建议**：改为 per-connection，或仅在客户端连接断连时 `discard`。

### 1.4 `ServerChunkPushManager` 的 `ArrayDeque` / `HashMap` 跨线程并发【核查：不属实，现行无竞争】

- **位置**：`ServerChunkPushManager.java:130-134, 161-163, 201-253, 922-931`；`MixinServerGamePacketListenerImpl.java:25-36`
- **原断言**：  
  - `demandTicketRefs` 为普通 `HashMap`，`merge`/`get`/`put`/`remove` 无同步。  
  - `pullQueues` 值为 `ArrayDeque`（非线程安全）：`pumpPendingPulls` 在 server tick 线程 `poll`/`add`，`removePlayer` 在 `onDisconnect` 路径遍历/`remove`。  
  - `onDisconnect` 在部分 loader/断连路径可由 Netty 线程进入，与 tick 竞态 → CME、票引用计数错乱（FORCED 票泄漏或提前释放）。
- **核查（2026-09-14）**：数据结构未加锁属实，但**原版把 `onDisconnect` 推迟到 server 线程执行**（`Connection.disconnect` → `channel.close` → listener 回调经 `ServerConnectionListener.tick` 在 server 线程派发；1.20.1 / 1.21.1 / 1.21.11 反编译源码均核验）。`removePlayer` 与 tick 泵在 server 线程内串行，无实际竞争。**定性：未来脆弱性**——若未来出现非 server 线程的断连回调调用面即触发。
- **建议**：作为一致性加固可选 `ConcurrentHashMap` / `ConcurrentLinkedQueue`，非必须修复；移出 P0。

---

## 二、中等 bug

| # | 问题 | 位置 | 触发条件 |
|---|------|------|----------|
| 2.1 | `ClientChunkPipeline.resetStorage` 未清 `serverSeedAvailable`【核查⚠️：缺清理属实，但门禁不可放行——`SeedGenExecutor.isEnabled()` 要求 `serverSeedGenEnabled && serverSeedAvailable` 同真，前者已被 resetStorage 清除且两标志在 `setServerSeedInfo` 同步重写；补一行清理即可】 | `ClientChunkPipeline.java:118-128` | 先连 Hassium+SeedGen 服，再连非 SeedGen 服且消费方在 `setServerSeedInfo` 前读该标志 |
| 2.2 | Fabric 每请求 `new ShadowPullHandler(new ShadowPullRequestLedger())`，requestId 幂等层完全失效（Forge/Neo 为进程级单例）【核查✅】 | `FabricNetworkManager.java:385, 402` | Fabric 上同 requestId 重发 |
| 2.3 | `IndexSyncManager.initializeServerIndex` 非原子初始化，双玩家并发激活可双跑【核查❌：不属实——全部调用面（`activate` ← `drainPending` ← tickServer TAIL；`sendIndexSync` ← activate 串行触发）在 server tick 单线程，不可能并发；仅防未来并发调用面】 | `IndexSyncManager.java:59-71` | ~~首两个 Hassium 玩家几乎同时过 `activate`~~ 核查：不可达 |
| 2.4 | 聚合 `flushInternal` 的 `totalSize` 用 `int`，叠加 1.2 可溢出为负 → 跳过分批 → 巨包编码【核查✅：`int totalSize`（L244）确认，依赖 §1.2 无上限积压放大触发】 | `HassiumAggregationManager.java:244-247` | PENDING 积压或异常大子包批量 |
| 2.5 | ZSTD 字典热替换不 `close()` 旧 native 句柄【核查⚠️：原 unregister/register 路径已随 T5-94 重构消失；新路径（compress L87 / decompress L117）覆盖旧句柄仍不 close，zstd-jni 1.5.5-7 有 Cleaner 兜底，属非确定性滞留而非无界泄漏】 | `ZstdDictionaryCompressionCodec.java:87, 117` | 字典 contentVersion 热替换后继续压缩 |
| 2.6 | `flushBatch` sender 缺失时 `buffer.addAll(batch)` 到队尾，与 `takeOver` 新入队交错，破坏同连接包序【核查❌：不属实——回队 `addAll` 与 `takeOver` 的 add 争同一把 per-connection list 锁，互斥且 FIFO 保持；且回队分支不可达（`sender` 只赋值一次从不置 null，`flushInternal` L234 已提前 return）】 | `HassiumAggregationManager.java:274-292` | ~~`setSender` 晚于首次 `flushBatch`~~ 核查：不可达 |
| 2.7 | `shouldReuseParkedInstance`：任一 id 为 null 即「保守复用」，换服竞态可能把上一服影子实例（含 seed/世界）给新会话【核查✅：`ShadowStartupOptimizeTest:22-24` 甚至固化了该行为】 | `ShadowServerRegistry.java:546-554` | 快速换服 + `setCacheLocation` 晚于 `getOrCreate` |
| 2.8 | `AggregationDecodeQueue.enqueue` 与 `discard` 竞态可丢帧/拿到已死 `ConnState`【核查✅：三种竞态路径（复活死 ConnState / POISON 错序丢帧 / 死连接排空）均真实，无代际守卫】 | `AggregationDecodeQueue.java:27-102` | 快速断连重连 + 在途聚合帧 |

---

## 三、低严重度边角问题

| # | 问题 | 位置 |
|---|------|------|
| 3.1 | `HassiumConnectionRegistry` 锁对不对称，理论瞬时不一致窗口【核查⚠️：窗口真实存在，但来自 `markDisabled` 而非 `markPending`——原断言归因对象错误】 | `HassiumConnectionRegistry.java:30-32, 70-74` |
| 3.2 | `PlayerCompressionTracker`：`removePlayer` 后迟到路径可能重新 put，留孤儿 UUID 条目 | `PlayerCompressionTracker.java:43-45, 111-117` |
| 3.3 | `DictionaryManager.AGGREGATION_DICT_PATH` 用相对 CWD 路径，非 `gameDirectory` | `DictionaryManager.java:55, 328-331` |
| 3.4 | `ShadowPullRequestLedger` 进程级共享 + 全局 `currentEpoch`；若未来 epoch 改为 per-session 会互相 STALE【核查⚠️：forge/neoforge 属实；fabric 端例外——每请求新建 ledger（见 2.2）】 | `ShadowPullRequestLedger.java:21, 34-39` |
| 3.5 | `ClientMetadataHandler.forwardBlockUpdate` 每方块包同步 `getOrCreate` 影子端，内部等待/重试阻塞主线程【核查⚠️：实际上界 ~42s，比原估 ~10s 更严重】 | `ClientMetadataHandler.java:44-45` |
| 3.6 | `HassiumAggregationManager.takeOver` 创建未使用的 `FriendlyByteBuf`（热路径无谓分配） | `HassiumAggregationManager.java:104, 138-140` |
| 3.7 | `ServerHandshakeActivation` 超时调度不 cancel，重连可残留旧 timeout task | `ServerHandshakeActivation.java:143-148` |
| 3.8 | `MixinConnection.hassium$tryAggregate` 依赖 payload id 精确匹配；跨 mod 同 id 冲突面存在但被命名空间部分隔离 | `MixinConnection.java:79-127` |

---

## 四、过度防御 / 异常吞没

全库 `common` 主代码 `catch (Exception|Throwable)` **约 246 处**。主要问题不是「有 catch」而是 **`ignored` 无日志 + 空体吞没**。

### 4.1 纯删除类（零/极低风险）

| # | 问题 | 位置 | 建议 |
|---|------|------|------|
| 4.1.1 | `shouldFailShadowWhenServerUnavailable` / `shouldFailShadowOnInjectFailure` 恒 `return false` 的策略钩子 + 调用点永假分支 | `ShadowVanillaLightPipeline.java:31-67` | 删除方法与对应 `if` |
| 4.1.2 | 同一 `dimension == null ? currentDimension() : dimension` 复制 8 次；`enqueueInjectedForLight` 对同一 `origin` 判 null 两次 | `ShadowLightCompute.java:688,727,774,777,914,1007,1328,1340-1343` | 抽 `resolveDim` / `resolveOrigin` |
| 4.1.3 | `ConfigSnapshotAdapter.SetCopy` —— 为一行 `Set.copyOf` 建私有静态类 | `ConfigSnapshotAdapter.java:123-127` | 直接 `Set.copyOf` |
| 4.1.4 | `HassiumConfigService.getConfig()` 对已 `volatile` 字段再加读锁 | `HassiumConfigService.java:160-167` | 直接读 volatile |
| 4.1.5 | `ConfigEntry` + `ConfigKey` 构造器 5 个 `Objects.requireNonNull`（常量工厂自产自销） | `ConfigEntry.java:19-26`，`ConfigKey.java:11-13` | 可删 |

### 4.2 静默吞没（需补日志 / 收窄）

| # | 问题 | 位置 | 建议 |
|---|------|------|------|
| 4.2.1 | `setServerSeedInfo` 第二个 `catch (Throwable ignored)` 完全空体——种子到达却没起影子端会「静默不工作」 | `ClientChunkPipeline.java:141-152` | 至少 `warn`；失败应显式 `setShadowServerFailed` |
| 4.2.2 | `applyVanillaDirect` 外层 empty catch + 内层 `DebugLogger.warn` 未传 `t`（有日志的吞没） | `ShadowLightCompute.java:1380-1399` | 删外层或改窄；内层传 `t` |
| 4.2.3 | Mixin accessor 失败 `catch Throwable → false`——映射错误被误判成「holder 不存在」，触发错误磁盘回退且无日志 | `ShadowChunkMapCompat.java:139-148,184-188`；`ShadowSeedServer.java:1183-1189` | ClassCast/Assertion 应 error 或 fail-fast |
| 4.2.4 | 票 **add** 失败静默 = Pull 永远拉不到 chunk | `ServerChunkPushManager.java:216-222` | add 至少 warn 或 metrics；remove 可宽 catch |
| 4.2.5 | `resolveNetworkEnabled` 对 `Services.PLATFORM` 包 `Throwable` 且回落 server 侧语义——静默用错开关 | `HassiumConfigService.java:338-346` | 去掉 try/catch 让异常冒泡 |
| 4.2.6 | `MixInClientTick` 每 tick 每步一个空 `catch (Exception e) {}`（冒烟/driver/expiry/tracking/bounds…） | `MixinClientTick.java:38-147` | 职责隔离合理，但完全无痕；至少 debug 一行 |
| 4.2.7 | `ShadowLightProbe` 诊断路径全量 `catch (Throwable)`，包括纯日志与 `enabled()` | `ShadowLightProbe.java:56-258`（全文件 8 处） | `enabled()` 去 try/catch；hook 最多 `RuntimeException` |
| 4.2.8 | `ClientLifecycleHelper` 多处 `Minecraft.getInstance() == null` + `catch (Exception ignored)` | `ClientLifecycleHelper.java:87-234` | 去掉不可能 null；收窄 catch |
| 4.2.9 | `SeedGenLevelCompat.assemble` 把 `RuntimeException` 也包成 `IOException`，调用方无法区分磁盘满和代码 NPE | `SeedGenLevelCompat.java:225-238` | 按类型分流 |

### 4.3 不可能 null 叠防御

| # | 问题 | 位置 |
|---|------|------|
| 4.3.1 | `ClientMetadataHandler.currentDimension` 在唯一调用点已保证非 null 后仍判 `mc == null || level == null`；`LevelCompat.getDimensionId` 再包一层 | `ClientMetadataHandler.java:36-58`；`LevelCompat.java:33` |
| 4.3.2 | `PayloadHandlers.handleAggregation` overload 双层判空重复 | `PayloadHandlers.java:146-169` |
| 4.3.3 | `ShadowSeedServer.storages` 用可空 volatile + 处处 `map == null` 模拟未初始化窗口 | `ShadowSeedServer.java:130,191,1156-1171` |
| 4.3.4 | `ServerHandshakeActivation.hasCaps` 对通常非 null 的 UUID 三元判空 | `ServerHandshakeActivation.java:87-89` |
| 4.3.5 | `ClientChunkPipeline` 等对 `Minecraft.getInstance()` 恒非 null 路径判空 | 多处 |

### 4.4 模式级观察

- `ShadowSeedServer` 24 处 `catch (Throwable)`、`ShadowLightCompute` 15 处、`ScenarioEngine` 13 处——影子/世界线程边界有合理理由，但 **empty / `ignored` 无日志** 比例偏高。
- 真正的空 `catch {}` 几乎为 0（好），问题主要是 `ignored` + 可选 debug。

---

## 五、重复实现

### 5.1 三端 loader 本可下沉 common

| # | 面 | 相似度 | 位置 | 建议 |
|---|-----|--------|------|------|
| 5.1.1 | 命令树（服务端 stats/metrics + 客户端 hassiumc） | ~95% | `FabricHassiumCommand` / `ForgeHassiumCommand` / `NeoForgeHassiumCommand` 及三端 ClientCommand | 抽 common `HassiumCommandTree`；loader 只传 dispatcher + feedback 适配。约消 300 行 |
| 5.1.2 | Forge / NeoForge ConfigBackend | ~95% | `ForgeConfigBackend.java:46-111` vs `NeoForgeConfigBackend.java:44-106` | 模板合并或 1.20.1 退役后统一 |
| 5.1.3 | SPI 发送路径（dict/index/play_init/aggregation ready/字典热推） | ~90% | `FabricNetworkManager:213-316` / `ForgeNetworkManager:624-693` / `NeoForgeNetworkManager:388-454` | common 收「业务编码+目标玩家」；loader 只 `sendRaw` |
| 5.1.4 | 聚合发送器 + 字典热推回调 | ~90% | 三端 NetworkManager | 与 5.1.3 合并 |
| 5.1.5 | 客户端命令 Forge vs NeoForge | ~98% | `ForgeHassiumClientCommand` vs `NeoForgeHassiumClientCommand` | 抽 common ClientCommandTree |
| 5.1.6 | Cloth 配置屏 `isClothAvailable` + register 结构 | ~95% | `HassiumForgeConfigScreens` vs `HassiumNeoForgeConfigScreens` | `isClothAvailable` 下沉 |
| 5.1.7 | 配置注册「物理端二选一」 | ~95% | `HassiumMod(Forge):26-31` / `NeoForgeConfigRegistration` | 下沉 helper |

### 5.2 common 内部克隆

| # | 问题 | 位置 | 建议 |
|---|------|------|------|
| 5.2.1 | Forge 七个 `byte[] Wrapper` record 全是「varint 长度 + bytes + 校验」 | `ForgeNetworkManager:498-622` | 合成一个 record + 可选 maxLen；约 80 行 |
| 5.2.2 | `combineSectionHashes(computeSectionHashes(chunk))` 内联 6+ 处 | `ShadowSeedServer`×4、`ShadowLightCompute`×2、`ChunkAuthorityNotifier`、`ServerChunkPushManager` | `ChunkContentHashUtil.computeChunkHash` 一行助手，防语义漂移 |
| 5.2.3 | `NetworkCapability.isCustomChannelFullySupported()` 恒 `true` + 永不使用的 `unsupportedReason()` | `NetworkCapability.java:16-18`；`CommonClass.java:48-50` | 整类删除 |
| 5.2.4 | metrics 三层（接口 608 行 + Impl 1233 行 + 门面 592 行【核查修正：原报告 512/1080/523 不实】），接口无第二实现 | `HassiumMetrics` / `HassiumMetricsImpl` / `NetworkStats` | 合并接口进 Impl；收窄门面 |
| 5.2.5 | bulk/per-port metrics 生产零调用（UDP 已裁剪） | `NetworkStats:108-147`；`HassiumMetricsImpl:1065-1116`；`SendPerPortMetricsTest` | 整族删除 |
| 5.2.6 | `ClientChunkHandler` 注释写「Phase 4 完成后删除本门面」，实际仍持大量非转发逻辑且 5+ 处静态引用 | `ClientChunkHandler.java:21-304` | 完成 Phase 4 或改注释承认永久双层 |
| 5.2.7 | `OvdClientLifecycle.ensureChunkCacheRadius` 死代码 | `OvdClientLifecycle.java:121-131` | 删除 |
| 5.2.8 | Fabric shadow_pull receiver 未走 `PayloadHandlers.handleShadowPullRequest`（Forge/Neo 已走） | `FabricNetworkManager:346-412` | 改为转调 PayloadHandlers |
| 5.2.9 | 同一 `chunk.enabled` 在 Service 上至少 4 个别名：`isClientCacheEnabled` / `isHassiumEngineEnabled` / `isJoinBoostEnabled` / seedGen 双名 | `HassiumConfigService.java:193-200, 322-335`；`LoginCaps.java:53-90` | 统一 `isChunkCoreEnabled()`；别名注释写死 |

### 5.3 能力门散落

- 早期 return 模式（`isClientCacheEnabled + ShadowLightCompute.isEnabled + LoginCaps.has(SHADOW_PULL)`）在 `ShadowPullClient`、`OvdClientLifecycle`、三端 loader 等处重复——建议抽 `ShadowPullAccess.isAvailable()` 等语义门。

---

## 六、过度设计

| # | 问题 | 位置 | 建议 |
|---|------|------|------|
| 6.1 | **配置四重镜像**：Schema → ConfigValues map → HassiumConfig record → Service ~40 个透传 getter；`ConfigValues.with` 每次整表拷贝 HashMap（load 路径 O(n²)） | `HassiumConfig` / `ConfigSchema` / `ConfigSnapshotAdapter` / `HassiumConfigService` | 运行时只留 record + 少量门闩；删同义 getter |
| 6.2 | **`IConfigBackend` 空接口** + Fabric 纯转调 | `IConfigBackend.java:5`；`FabricConfigBackend.java:9-18` | `Services.CONFIG` 直接 load `ConfigBackend` |
| 6.3 | **`INetworkManagerService` default 空实现/release**：漏实现 = 静默丢包（NeoForge 注释自承） | `INetworkManagerService.java:13-73` | 非可选方法全部 abstract |
| 6.4 | **通道 ID 三套常量**：`PacketId`/`HassiumChannels` vs `HassiumPacketIds` String vs loader `ResourceLocation` | `HassiumChannels` / `HassiumPacketIds` / loader | `HassiumPacketIds` 只留 `fullId()` 派生或全删 |
| 6.5 | **压缩「插件框架」**：生产几乎固定 ZSTD；Registry + AlgorithmId + Options 装配对热路径是多余间接 | `CompressionService` / codecs | 收窄为 `compressZstd` / `compressWithDict` 函数 |
| 6.6 | **`IndexSyncManager` + 紧凑包头**：1.21.1+ 聚合已是 body+channel id，索引机器主要服务 1.20.1；`registerHassiumPackets` 里还有疑似过期 id `hassium:chunk_payload_s2c` | `IndexSyncManager.java:15-79` | 文档化「1.20.1 独占」；从 1.21.1+ 剥离 |
| 6.7 | **`DebugLogger.isEnabled` 的 `smokeTest` 分支每次读 `System.getProperty`**【核查⚠️：配置位图已缓存（T8-26，`cachedEnabledBits` volatile）；仅 CHUNK_APPLY 的 `hassium.smokeTest` 属性分支每次调用读一次，且 CHUNK_APPLY 是冒烟热路径】 | `DebugLogger.java` isEnabled | smokeTest 属性缓存进 `static final boolean` |
| 6.8 | **聚合自建 10ms 单线程调度器**——已有 server tick 泵 | `HassiumAggregationManager.java:41-84` | 改 server tick 或复用统一 executor |
| 6.9 | **`Domain` 枚举 + ConfigEntry 宽 record**：`entry.domain()` 运行时 0 引用 | `Domain.java` / `ConfigEntry.java` | Domain 只留 docs 脚本；运行时去 domain |
| 6.10 | **`ClientChunkPipeline` 上帝对象**：seed/ready/failed/apply 一堆字段；`ShadowServerRegistry` 变成「改 pipeline 布尔的遥控器」 | `ClientChunkPipeline.java` / `ShadowServerRegistry` | 拆 `ClientSessionState` 与 `ShadowLifecycle` |
| 6.11 | **`KeyedPriorityQueue` + `ChunkDistancePriority` + `TaskCategory`**：`OfferResult` 调用方几乎不读；`Tier` 两值 enum 更像 int 常量 | `concurrent/` | 队列算法保留；周边类型收薄 |
| 6.12 | **Fabric NetworkManager：9 次 `#if` 只为 `ResourceLocation` vs `Identifier` 类型名** | `FabricNetworkManager.java:58-122` | 一处 helper 或整段文件替换 |
| 6.13 | **Forge NetworkManager：legacy/modern 双实现整类共存（~700 行 `#if`）** | `ForgeNetworkManager.java` | 拆 Legacy/Modern 分段 |
| 6.14 | **`DictionaryRegistry` 仅一个实现** | `DictionaryRegistry.java` | 收窄或 final 类 |

---

## 七、值得肯定、不建议简化的部分

| 项 | 原因 |
|----|------|
| `PacketId` + `ResourceLocationCompat` | 正确用稳定值类型收口 1.21.11 rename |
| `DebugLogger` 位图缓存 | 已针对热路径优化；问题在键面而非包装本身 |
| `ShadowPullHandler.Resolver` | 单策略接口，有真实分发需求，规模合适 |
| `KeyedPriorityQueue` 核心算法 | 针对移动锚点冻结键问题，有实 bug 背书 |
| 三端 `IPlatformHelper` | 多加载器下合理；API 真不同，已是合理最小壳 |
| 真正的空 `catch {}` 几乎为 0 | 问题主要是 `ignored` + 可选 debug，不是完全无痕 |
| `instanceof` 模式匹配已在用 | 健康，无大段旧式 cast 链 |

---

## 八、处理优先级建议

> 2026-09-14 核查后重排：§1.4 / §2.3 / §2.6 不属实（线程模型误判）移出 bug 清单；§1.1 现行不可达降为防御项；§2.1 / §2.5 影响有限降级；补入核查确认的 §3.2 / §3.3 / §3.5 / §3.6 / §3.7。

### P0 — 高严重度 bug（行为修复，需冒烟）

1. PENDING 聚合缓冲加硬上限并强制 flush 分批（§1.2 + §2.4）——注意 5s 降级语义是**丢弃**非直发
2. `PullResponseDecodeQueue` 改 per-connection 或仅客户端 discard（§1.3）
3. `AggregationDecodeQueue` enqueue/discard 竞态加代际守卫（§2.8）
4. `shouldReuseParkedInstance` 保守复用收紧（§2.7）
5. Fabric `ShadowPullHandler` 单例化对齐 Forge/Neo（§2.2）

### P1-bug — 中等 bug（影响有限）

6. `resetStorage` 补清 `serverSeedAvailable`（§2.1，一行，无行为风险）
7. ZSTD 字典句柄热替换补 close（§2.5，zstd-jni Cleaner 兜底存在，非紧急）

### P1-纯删 — 零/极低风险

8. 永假 fallback 方法（§4.1.1）
9. `NetworkCapability` 整类（§5.2.3）
10. bulk metrics 死 API + 测试（§5.2.5）
11. `ensureChunkCacheRadius`（§5.2.7）
12. `SetCopy`、无用读锁、`resolveDim`/`resolveOrigin` 提取（§4.1.2-4.1.4）
13. `takeOver` 未使用 `FriendlyByteBuf`（§3.6）
14. `ServerHandshakeActivation` 超时调度残留 cancel（§3.7）

### P2-防御 — 潜伏缺陷，现行不可达（自 P0/P1 降级）

15. `activate` 失败回队 + 超时兜底（§1.1：connection 同栈挂载，现行不可达；防未来版本拆栈）
16. `ServerChunkPushManager` 数据结构换并发容器（§1.4：onDisconnect 已被 vanilla 推迟到 server 线程，现行串行；属一致性加固）
17. `IndexSyncManager.initializeServerIndex` 原子化（§2.3：单线程调用面；防未来并发调用面）
18. `HassiumConnectionRegistry` 锁对统一（§3.1：注意窗口在 `markDisabled`，非 `markPending`）
19. `PlayerCompressionTracker` 孤儿 UUID 条目（§3.2）
20. `DictionaryManager` 字典路径改 `gameDirectory`（§3.3）
21. `ClientMetadataHandler.forwardBlockUpdate` 主线程阻塞（§3.5：上界 ~42s，比原估严重）

### P2-吞没 — 补日志 / 收窄 catch

22. 空/`ignored` catch 加 warn（§4.2.1-4.2.6）
23. accessor 失败 fail-fast（§4.2.3）
24. 票 add 加 warn（§4.2.4）

### P2-下沉 — 三端克隆收口（需 loader 冒烟）

25. 命令树下沉 common（§5.1.1, §5.1.5）
26. ConfigBackend 模板合并（§5.1.2）
27. SPI send 样板下沉（§5.1.3-4）
28. Forge Wrapper 合并（§5.2.1）
29. chunkHash 助手（§5.2.2）
30. Fabric receiver 走 PayloadHandlers（§5.2.8）

### P3-重构 — 高风险慎动（配套冒烟 seedgen/dimension）

31. 配置去透传 / 统一别名（§5.2.9, §6.1）
32. SPI 去 default 垫片（§6.3）
33. 压缩 API 收窄（§6.5）
34. IndexSync 1.20.1 化（§6.6）
35. 连接状态单表（`HassiumConnectionRegistry` 三态 enum）
36. `ClientChunkPipeline` 拆分（§6.10）

---

*本报告为只读审查产出；2026-09-14 核查修订仅改报告文本，未改任何代码。修复实施前请按项目规范走冒烟（L1 classic 矩阵 + seedgen 场景）。*
