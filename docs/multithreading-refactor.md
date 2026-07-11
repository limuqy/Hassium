# Hassium 多线程架构改造方案

## 一、背景

### 1.1 主线程阻塞问题

Hassium 存在多个主线程阻塞问题，会导致客户端卡顿：

#### 🔴 严重问题

| # | 问题 | 文件 | 主线程操作 | 影响 |
|---|------|------|-----------|------|
| S1 | 网络区块数据主线程解压 | `ClientChunkHandler.handleCompressedChunk()` | ZSTD 解压（1-3ms/块） | 一帧收到多块时累积卡顿 |
| S2 | 缓存保存 flush() 主线程同步执行 | `CacheSaveQueue.flush()` | 序列化 + ZSTD 压缩 + 文件 I/O + SQLite | 断开/切维度时卡顿 |
| S3 | 元数据处理主线程磁盘 I/O | `ClientMetadataHandler.handleMetadataPacket()` | Region 文件读取 + SQLite 初始化 | 首次连接新 region 时卡顿 |

#### 🟡 中等问题

| # | 问题 | 文件 | 主线程操作 | 影响 |
|---|------|------|-----------|------|
| M1 | 光照重算遍历全部方块 | `MixinLightRecompute` | 最多 98304 次方块检查 | 单区块数毫秒 |
| M2 | SQLite 初始化在主线程 | `ClientHassiumStorage.initializeDatabase()` | 连接创建 + PRAGMA + 表创建 | 首次连接 5-20ms |
| M3 | 后台线程访问主线程对象 | `CacheSaveQueue.processTask()` | LevelChunk/ClientLevel 读取 | 数据竞争风险 |

> **注意**：S2 和 M3 耦合在同一个组件 `CacheSaveQueue` 中——`flush()` 和后台 `saveExecutor` 都调用 `processTask()` → `serializeChunk()`。正确的解决顺序是**先修 M3（主线程序列化快照），再修 S2（flush 异步化）**。详见 [Phase 4](#s2m3)。

### 1.2 现有异步设施（改造前）

#### 线程池（4 个，各自独立）

| 组件 | 类型 | 用途 | 生命周期 |
|------|------|------|---------|
| `CacheSaveQueue.saveExecutor` | 单线程 | 缓存压缩+写入 | 随连接 |
| `ClientCacheLoadQueue.loaderPool` | 多线程(可配置) | 缓存加载+解压 | 随连接 |
| `ServerChunkPushManager.pushPool` | 多线程(可配置) | 服务端区块推送 | 随模块 |
| `HassiumAggregationManager.TIMER` | 单线程定时 | 包聚合刷新(20ms) | 随模块 |

#### 队列（6 个）

| 组件 | 队列类型 | 用途 |
|------|---------|------|
| `ServerChunkPushManager.dataQueues` | `ConcurrentHashMap<UUID, PriorityBlockingQueue>` | 每玩家区块数据请求 |
| `ClientCacheLoadQueue.pendingTasks` | `PriorityBlockingQueue` | 缓存命中待加载 |
| `ClientCacheLoadQueue.readyQueue` | `PriorityBlockingQueue` | 已加载待应用 |
| `CacheSaveQueue.taskQueue` | `LinkedBlockingQueue` | 待保存区块 |

#### 其他并发模式

| 类型 | 数量 | 典型用途 |
|------|------|---------|
| `ReadWriteLock` | 3 处 | 配置服务、客户端区块缓存、版本追踪器 |
| `synchronized` | 17 处 | Region 文件、单例初始化、连接注册、聚合管理 |
| `AtomicBoolean` | 7 个 | 初始化标志、开关状态 |
| `AtomicLong` | ~32 个 | 性能指标计数器 |
| `volatile` | 16 个 | 单例、配置、字典、线程池引用 |
| `CompletableFuture` | 1 处 | 字典异步训练 |

---

## 二、架构设计

### 2.1 设计目标

1. **统一管理**：共享后台线程池，所有异步任务共用
2. **生命周期感知**：随游戏连接创建/销毁，避免资源泄漏
3. **主线程回调**：异步结果通过 `MainThreadDispatcher` 回到主线程
4. **Java 版本自适应**：Java 17 用平台线程，Java 21+ 用虚拟线程（反射调用）
5. **可配置**：线程数由配置文件控制

### 2.2 虚拟线程 vs 平台线程

| 维度 | 平台线程 | 虚拟线程 |
|------|---------|---------|
| 适用场景 | CPU 密集 | I/O 密集 |
| 内存开销 | ~1MB/线程 | ~1KB/线程 |
| 创建开销 | 高 | 极低 |
| 最大数量 | 数千 | 数百万 |
| ZSTD 压缩 | ✅ 适合 | ⚠️ 受 synchronized 限制 |
| 文件 I/O | ✅ 适合 | ✅ 非常适合 |
| SQLite | ✅ 适合 | ✅ 适合 |

**结论**：Hassium 的异步任务以 I/O 为主，**虚拟线程更优**。通过反射调用 Java 21 API，Java 17 编译兼容。

### 2.3 核心架构

```
┌─────────────────────────────────────────────────────────┐
│                    主线程 (Minecraft)                      │
│  ┌──────────┐  ┌──────────┐  ┌───────────────────────┐  │
│  │ Mixin    │  │ Handler  │  │ MainThreadDispatcher  │  │
│  │ 注入点   │  │ 处理器   │  │ (flushClient 每帧)    │  │
│  └────┬─────┘  └────┬─────┘  └───────────▲───────────┘  │
│       │              │                    │               │
│       ▼              ▼                    │               │
│  ┌────────────────────────────────────────┴────────────┐  │
│  │            HassiumTaskExecutor                      │  │
│  │  ┌───────────────────────────────────────────────┐  │  │
│  │  │  BackgroundExecutor (共享线程池)                │  │  │
│  │  │  Java 17:  newFixedThreadPool(backgroundThreads)│  │  │
│  │  │  Java 21+: newThreadPerTaskExecutor(virtual)   │  │  │
│  │  └───────────────────────────────────────────────┘  │  │
│  │                                                     │  │
│  │  submit(Runnable)          submit(Callable) -> Future│  │
│  │  submitAndCallback(task,   submitAndJoin(task,      │  │
│  │    callback)                 timeout)               │  │
│  │  submitAllAndJoin(tasks,   submitAndWait(task,      │  │
│  │    timeout)                  timeout)               │  │
│  └─────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

---

## 三、已实现的组件

### 3.1 新增文件（`common/.../concurrent/`）

#### ExecutorFactory — 线程池工厂

- Java 版本检测：反射调用 `Thread.isVirtual()` 判断是否支持虚拟线程
- 平台线程：`Executors.newFixedThreadPool(backgroundThreads)`，daemon 线程
- 虚拟线程：反射调用 `Thread.ofVirtual().name().factory()` + `Executors.newThreadPerTaskExecutor()`
- 反射失败时回退到平台线程，使用配置的线程数

#### HassiumTaskExecutor — 统一异步任务执行器

API：
| 方法 | 说明 |
|------|------|
| `submit(Runnable)` | 提交任务，不关心结果 |
| `submit(Callable) -> Future` | 提交任务，返回 Future |
| `submitAndCallback(Callable, Consumer)` | 提交任务，完成后主线程回调 |
| `submitAndJoin(Callable, timeout)` | 提交任务，阻塞等待结果 |
| `submitAllAndJoin(tasks, timeout)` | 批量提交，阻塞等待全部完成 |
| `submitAndWait(Runnable, timeout)` | 提交任务，阻塞等待完成，返回是否超时 |

生命周期：
| 方法 | 调用时机 |
|------|---------|
| `initClient(threads)` | 连接时，从配置读取线程数 |
| `shutdownClient()` | 断开连接时 |
| `initServer(threads)` | 服务器启动时 |
| `shutdownServer()` | 服务器关闭时 |

#### MainThreadDispatcher — 主线程回调调度器

- 后台线程通过 `execute(Runnable)` 提交回调到 `ConcurrentLinkedQueue`
- 主线程每帧通过 `flushClient()` 批量执行所有待处理回调
- 断开连接时 `clearClient()` 清空队列

> **待改造**：当前实现无优先级、无单帧上限、无可取消机制。详见 [5.1 MainThreadDispatcher 改造](#mtd-priority)。

### 3.2 配置项

```json
{
  "network": {
    "backgroundThreads": 8
  }
}
```

- **Java 17（平台线程）**：默认 8 线程
- **Java 21+（虚拟线程）**：配置忽略，按需创建

### 3.3 生命周期集成

| 事件 | 操作 |
|------|------|
| `handleLogin` (连接) | `HassiumTaskExecutor.initClient(threads)` |
| `onDisconnect` (断开) | `CacheSaveQueue.flush()` → `HassiumTaskExecutor.shutdownClient()` → `MainThreadDispatcher.clearClient()` |
| `setLevel` (维度切换) | `CacheSaveQueue.flush()` |
| `MixinClientTick` (每帧) | `MainThreadDispatcher.flushClient()` |

**存在问题**：当前 `onDisconnect` 的时序为 `flush()`（同步阻塞，可能数百ms）→ `shutdownClient()` → `clearClient()`。`shutdownClient()` 和 `clearClient()` 之间可能有后台任务通过 `MainThreadDispatcher.execute()` 入队回调，这些回调会被丢弃，导致区块光照引擎未正确启用就进入下一局游戏。

**修正方案**（详见 [5.2 登出任务取消机制](#cancel-on-disconnect)）：
```
onDisconnect
  → CacheSaveQueue.flushAsync(timeout=3000ms)      // 异步等待必须完成的任务
  → HassiumTaskExecutor.cancelAll(SAFE_TO_CANCEL)   // 取消光照扫描等
  → HassiumTaskExecutor.shutdownClient(timeout=5000ms) // 等待剩余任务
  → MainThreadDispatcher.clearClient(keepMissionCritical=false)
```

---

## 四、已改造的问题

### ✅ M1: 光照重算异步多线程化

**改造前**：主线程遍历 24 sections × 4096 blocks = 98304 次检查

**改造后**：
```
handleLevelChunkWithLight (主线程)
  → 检测光照被剥离（4 个 BitSet 全空）
  → 提交 N 个 SectionScanTask 到后台线程池并行扫描光源位置
  → CompletableFuture.allOf() 等待全部扫描完成（不占用额外线程）
  → MainThreadDispatcher.execute(() -> applyLightEngine(), chunkPos, SAFE_TO_CANCEL)
    → 主线程每帧 flush:
      → 按距离优先级出队（近距离 chunk 先处理）
      → 单帧上限 maxCallbacksPerFrame（默认 5）
      → setLightEnabled + updateSectionStatus + checkBlock + propagateLightSources
```

- 光源扫描（CPU 密集）并行化到后台线程池
- 光照引擎更新（必须主线程）通过 MainThreadDispatcher 按优先级回调
- 使用 `CompletableFuture.allOf().thenRun()` 替代两级提交，消除平台线程饥饿风险
- 回调标记为 `SAFE_TO_CANCEL`，登出时可安全取消
- 执行器未初始化时回退到主线程同步处理

---

## 五、待改造的问题

### S1: 网络区块数据主线程解压 ✅ 已完成

**改造前**：
```
网络线程 → client.execute() → 主线程
  → handleCompressedChunk()
    → ZSTD 解压 (阻塞主线程 1-3ms/块)
    → applyChunkData()
```

**改造后**：
```
网络线程 → client.execute() → 主线程
  → handleCompressedChunk()
    → decode（轻量，主线程）
    → HassiumTaskExecutor.submit(() -> decompress, SAFE_TO_CANCEL)
      → 解压完成
        → MainThreadDispatcher.execute(() -> applyChunkData(), chunkPos)
```

- 解码（纯字节操作）在主线程完成
- ZSTD 解压提交到 `HassiumTaskExecutor` 后台线程
- `applyChunkData()` 通过 `MainThreadDispatcher` 带距离优先级回到主线程
- 标记 `SAFE_TO_CANCEL`：登出时可安全取消未完成的解压
- 新增 `ChunkCompressionHandler.decompressChunkDataFromRaw()` 直接从原始字节解压
- 新增 `isRunning()` / `isShutdown()` 方法到 `HassiumTaskExecutor`
- 执行器未初始化时回退到主线程同步解压

### S2 / M3: 缓存保存 flush 异步化 + 主线程对象数据竞争 ✅ 已完成 <a id="s2m3"></a>

> **耦合关系**：S2 和 M3 必须一起解决。`flush()` 和后台 `saveExecutor` 都调用同一个 `processTask()` → `serializeChunk()`。如果只修 S2 不改 M3，后台线程仍存在数据竞争；如果只修 M3 不改 S2，flush 仍在主线程阻塞。正确顺序：**先修 M3（enqueue 时主线程序列化快照），再修 S2（flush 异步化）**。

**改造后**：
```
// 步骤 1：在 enqueue() 时（主线程）序列化快照
enqueue(chunk)  // 主线程
  → serializeChunk(chunk, level) → 字节数组（byte[]）
  → SaveTask 只存储 byte[]，不再持有 LevelChunk/ClientLevel 引用
  → 入队到 taskQueue

// 步骤 2：后台消费者从队列取任务 → 提交到 HassiumTaskExecutor
processLoop() (daemon 线程)
  → taskQueue.take()
  → HassiumTaskExecutor.submit(() -> processTask(task), MISSION_CRITICAL)
    → processTask() 只处理 byte[]：压缩 + region 写入 + SQLite

// 步骤 3：flush 异步化
flushAsync(timeout=3000ms)
  → 停止消费者线程
  → 收集 taskQueue 中所有剩余任务
  → 提交到 HassiumTaskExecutor (每任务 MISSION_CRITICAL)
  → 主线程阻塞等待 Future.get()，总超时 3000ms
```

**关键变更**：
- `SaveTask` 从持有 `LevelChunk` + `ClientLevel` → 只持有 `byte[] serializedData`
- 新增 `serializeChunk(LevelChunk, ClientLevel)` 主线程私有方法
- 后台消费者使用专用 daemon 线程 + `HassiumTaskExecutor`，保持单消费者语义
- `flush()` 现在内部委托给 `flushAsync(3000)`，兼容旧调用点
- 新增 `flushAsync(long timeoutMs)` 和 `flushAsync()` 公开 API
- 新增 `shutdown()` 方法用于运行时可重置
- `HassiumTaskExecutor` 新增 `isRunning()` / `isShutdown()` 状态查询

### S3: 元数据处理主线程磁盘 I/O ✅ 已完成

**改造后**：
```
handleMetadataPacket()
  → 如果 storage 未初始化（异步初始化尚未完成）→ 同步初始化（回退路径）
  → HassiumTaskExecutor.submitAndCallback(() -> compareMetadata(...), applyMetadataResult, BEST_EFFORT)
    → 后台线程：readMetadata() 磁盘 I/O + 时间戳比对
    → MainThreadDispatcher → applyMetadataResult()（主线程）
      → 缓存命中 → ClientCacheLoadQueue.enqueue()
      → 缓存未命中 → 发送 ChunkDataRequestC2SPacket 到服务端
```

**关键变更**：
- `handleMetadataPacket()` 拆分为三部分：
  1. 入口：检查 storage 状态，提交异步任务
  2. `compareMetadata()`：后台线程执行 region 文件 I/O
  3. `applyMetadataResult()`：主线程应用结果（队列操作 + 网络请求）
- 新增 `MetadataResult` record 封装比对结果
- 标记 `BEST_EFFORT`：登出时可以取消
- 执行器未初始化时回退到同步路径

### M2: SQLite 初始化异步化 ✅ 已完成

**改造后**：
```
MixinClientPacketListener.handleLogin (主线程)
  → HassiumTaskExecutor.initClient(threads)
  → hassium$initializeCacheAsync()
    → HassiumTaskExecutor.submit(() -> initStorage(...), BEST_EFFORT)
      → 后台线程：ClientHassiumStorage 构造函数（含 SQLite 初始化 ~5-20ms）
```

**关键变更**：
- `hassium$initializeCache()` → `hassium$initializeCacheAsync()`
- `ClientHassiumStorage` 构建（含 `initializeDatabase()`）移至后台线程
- 标记 `BEST_EFFORT`：初始化应尽量完成
- 初始化完成前元数据包处理通过回退路径同步初始化

### Phase 6: ClientCacheLoadQueue 线程池整合 ✅ 已完成

**关键变更**：
- 移除 `ClientCacheLoadQueue` 独立的 `loaderPool` (`ExecutorService`)
- `enqueue()` 时通过 `HassiumTaskExecutor.submit()` 提交加载任务（`SAFE_TO_CANCEL`）
- `processNextTask()` 在统一线程池中执行
- 移除 `initialized` / `ensureInitialized()` / `clear()` 中的线程池管理
- `processQueue()`（主线程应用）保持不变

---

## 5.1 MainThreadDispatcher 改造 <a id="mtd-priority"></a>

### 5.1.1 问题

当前 `MainThreadDispatcher` 使用 `ConcurrentLinkedQueue`，无优先级、无单帧上限、无可取消机制：

1. **无优先级**：当多个异步任务同时完成时（如进入新区域触发 20+ 个光照重算），回调按 FIFO 顺序执行，远处的 chunk 可能先于近处的 chunk 得到光照
2. **无单帧上限**：`flushClient()` 在一帧内处理所有待处理回调，可能造成帧卡顿
3. **无可取消机制**：`disconnect` 时只能全量清空，无法区分"必须完成"和"可安全取消"的任务

### 5.1.2 改造：基于距离的优先级队列

参考 `ClientCacheLoadQueue` 设计（`PriorityBlockingQueue`，按玩家距离排序），将 `MainThreadDispatcher` 的回调队列改为带优先级的：

```
改造前：
  CLIENT_QUEUE = ConcurrentLinkedQueue<Runnable>
  flushClient() → while-poll 全量执行

改造后：
  CLIENT_QUEUE = PriorityBlockingQueue<CallbackTask>
  flushClient(maxPerFrame) → 按优先级排序出队，单帧上限 maxCallbacksPerFrame

  record CallbackTask(Runnable action, double priority, TaskCategory category)
    implements Comparable<CallbackTask>
```

**优先级计算**：参照 `ClientCacheLoadQueue` 和 `ServerChunkPushManager`，使用玩家到 chunk 的欧几里得距离作为优先级（距离越小越优先）：

```java
double playerChunkX = player.getX() / 16.0;
double playerChunkZ = player.getZ() / 16.0;
double priority = Math.sqrt(dx * dx + dz * dz);
```

**API 变更**：

| 方法 | 说明 |
|------|------|
| `execute(task)` | 保持兼容，默认优先级 `Double.MAX_VALUE`（最低），category=`MISSION_CRITICAL` |
| `execute(task, chunkPos)` | 新增，基于 `chunkPos` 自动计算距离优先级 |
| `execute(task, priority, category)` | 新增，手动指定优先级和任务类别 |
| `flushClient(maxPerFrame)` | 修改，单帧最多执行 `maxCallbacksPerFrame` 个回调 |
| `clearClient(keepMissionCritical)` | 修改，`true` 时保留必须完成的任务，`false` 全部清空 |
| `cancelTasks(predicate)` | 新增，按条件取消尚未执行的特定任务 |

### 5.1.3 配置项

```json
{
  "network": {
    "maxCallbacksPerFrame": 5
  }
}
```

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `maxCallbacksPerFrame` | `5` | 每帧最多执行的主线程回调数 |

> **参考**：`ClientCacheLoadQueue.MAX_CHUNKS_PER_FRAME` 当前硬编码为 20。建议在 Phase 6 中统一为可配置项 `maxChunksPerFrame`（默认 10），详见 [5.3](#phase6-config)。

### 5.1.4 日志规范

`flushClient()` 中当前使用 `System.err.println` 输出错误，应改为 `Constants.LOG.error`，与项目日志体系统一。

---

## 5.2 登出时任务取消机制 <a id="cancel-on-disconnect"></a>

### 5.2.1 任务可取消性分类

并非所有后台任务在登出时都值得完成。将 `HassiumTaskExecutor` 提交的任务分为三类：

| 类别 | 示例 | 登出时行为 |
|------|------|-----------|
| **必须完成** (MISSION_CRITICAL) | `CacheSaveQueue` 已入队的待保存区块 | flush 等待完成，不允许取消 |
| **可安全取消** (SAFE_TO_CANCEL) | 光照重算扫描（`MixinLightRecompute`）、网络解压（S1） | 登出时取消，结果不需要了 |
| **尽力完成** (BEST_EFFORT) | 元数据读取（S3）、SQLite 初始化（M2） | 可以取消，但初始化应尽量完成以利下次连接 |

### 5.2.2 实现方案

在 `HassiumTaskExecutor` 中维护一个 `ConcurrentHashMap<String, Future<?>>` 任务注册表，`submit()` 时返回带标记的 `Future`，`shutdownClient()` 时遍历取消所有标记为 `SAFE_TO_CANCEL` 的任务：

```java
public CancellableFuture<T> submit(Callable<T> task, TaskCategory category)
```

`TaskCategory` 枚举：

```java
public enum TaskCategory {
    MISSION_CRITICAL,  // 必须完成
    SAFE_TO_CANCEL,    // 可安全取消
    BEST_EFFORT        // 尽力完成
}
```

**登出时序（修正后）**：

```
onDisconnect
  → CacheSaveQueue.flushAsync(timeout=3000ms)       // 等待必须完成的任务
  → HassiumTaskExecutor.cancelAll(SAFE_TO_CANCEL)    // 取消光照扫描等
  → HassiumTaskExecutor.shutdownClient(timeout=5000ms) // 等待 MISSION_CRITICAL + BEST_EFFORT
  → MainThreadDispatcher.clearClient(false)          // 清空所有剩余回调
```

**关键点**：`cancelAll(SAFE_TO_CANCEL)` 在 `shutdownClient` 之前调用，确保可取消的任务不会因 `Future.get()` 阻塞 `shutdown` 过程。

---

## 5.3 Phase 6 补充：统一配置项 <a id="phase6-config"></a>

当前 `ClientCacheLoadQueue` 的 `MAX_CHUNKS_PER_FRAME = 20` 是硬编码常量，应在 Phase 6 中统一为可配置项：

```json
{
  "network": {
    "maxChunksPerFrame": 10,
    "maxCallbacksPerFrame": 5
  }
}
```

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `maxChunksPerFrame` | `10` | 每帧最多加载（apply）的缓存区块数，替代硬编码 `ClientCacheLoadQueue.MAX_CHUNKS_PER_FRAME` |
| `maxCallbacksPerFrame` | `5` | 每帧最多执行的主线程异步回调数（光照引擎更新等） |

两个值独立配置，总上限为 `maxChunksPerFrame + maxCallbacksPerFrame`。默认值 10 + 5 = 15，与原版 chunk loading 批处理行为接近。

---

## 六、实施进度

| Phase | 任务 | 状态 |
|-------|------|------|
| 1 | 创建 `ExecutorFactory`（Java 版本自适应） | ✅ 完成 |
| 1 | 创建 `HassiumTaskExecutor`（统一执行器） | ✅ 完成 |
| 1 | 创建 `MainThreadDispatcher`（主线程回调） | ✅ 完成 |
| 1 | 配置项 `backgroundThreads`（默认 8） | ✅ 完成 |
| 1 | 执行器生命周期集成（连接/断开/每帧） | ✅ 完成 |
| 2 | M1 光照重算异步多线程化 | ✅ 完成 |
| 2a | M1 修复：`CompletableFuture` 替代两级提交 | ✅ 完成 |
| 2b | `MainThreadDispatcher` 优先级化改造（[5.1](#mtd-priority)） | ✅ 完成 |
| 2c | 登出任务取消机制（[5.2](#cancel-on-disconnect)） | ✅ 完成 |
| 2d | 修正 `onDisconnect` 时序 | ✅ 完成 |
| 6（部分） | 配置项 `maxChunksPerFrame` / `maxCallbacksPerFrame`（[5.3](#phase6-config)） | ✅ 完成 |
| 3 | S1 网络解压异步化 | ✅ 完成 |
| 4 | S2 flush 异步化 + M3 主线程序列化（[S2/M3](#s2m3)） | ✅ 完成 |
| 5 | S3 元数据异步化 + M2 SQLite 异步初始化 | ✅ 完成 |
| 6 | 整合 `ClientCacheLoadQueue` 线程池到 `HassiumTaskExecutor` | ✅ 完成 |
| 6 | 整合 `ServerChunkPushManager` 线程池到 `HassiumTaskExecutor` | 待后续评估 |

---

## 七、风险评估

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| 虚拟线程 synchronized 碰撞 | ZSTD 内部可能用 synchronized，导致虚拟线程 pin | 反射失败自动回退平台线程 |
| 主线程回调延迟 | 异步结果可能延迟 1 帧 | 可接受，与原版区块加载一致；优先级队列确保近处 chunk 先处理 |
| 线程安全 | 后台线程访问共享状态 | 在主线程序列化快照，后台只处理字节数组 |
| 超时放弃 | flush 超时丢失缓存 | 设置合理超时（3s），丢失少量缓存可接受 |
| 反射调用开销 | 虚拟线程创建时反射调用 | 仅创建时调用一次，运行时无开销 |
| 平台线程饥饿（Java 17） | 两级提交模式可能导致 8 线程池被协调者全部占用 | Phase 2a 改为 `CompletableFuture` 消除额外线程等待 |
| 回调单帧堆积 | 大量异步完成时主线程一帧处理过多回调 | `maxCallbacksPerFrame` 限制单帧上限，优先级队列按距离排序 |
| 登出资源泄漏 | 后台任务在 disconnect 后继续执行 | TaskCategory 分类 + `cancelAll` + `shutdownClient(await)` |
