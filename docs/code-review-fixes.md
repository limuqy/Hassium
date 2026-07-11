# 代码审查问题修复文档

> 审查时间：2026-07-11
> 审查范围：common/、fabric/、forge/ 模块（~45 个 Java 源文件）
> 问题总数：16 个（严重 2 / 高 4 / 中 6 / 低 4）

---

## 目录

- [严重问题](#严重问题)
  - [CRITICAL-1: ClientHassiumStorage.openRegions 线程安全](#critical-1)
  - [CRITICAL-2: HassiumRegionFile 读方法缺少同步](#critical-2)
- [高优先级问题](#高优先级问题)
  - [HIGH-1: ClientCacheDatabase PreparedStatement 跨线程复用](#high-1)
  - [HIGH-2: ChunkSender.instance 缺少 volatile](#high-2)
  - [HIGH-3: ServerChunkPushManager.serializeChunk ByteBuf 泄漏](#high-3)
  - [HIGH-4: MainThreadDispatcher 距离计算优化](#high-4)
- [中等问题](#中等问题)
  - [MEDIUM-1: StorageConfig 注释与值不一致](#medium-1)
  - [MEDIUM-2: PlayerCompressionTracker 条目未清理](#medium-2)
  - [MEDIUM-3: ClientChunkHandler.pendingContentHashes 无限增长](#medium-3)
  - [MEDIUM-4: HassiumAggregationManager.TASKS 生命周期管理](#medium-4)
  - [MEDIUM-5: FabricNetworkManager.sendMetadataPacket 抛异常](#medium-5)
  - [MEDIUM-6: FabricNetworkManager 反射访问私有字段](#medium-6)
- [低优先级问题](#低优先级问题)
  - [LOW-1: 热路径 INFO 级别日志](#low-1)
  - [LOW-2: ClientCacheLoadQueue 迭代未快照](#low-2)
  - [LOW-3: ChunkContentHashUtil 临时对象分配](#low-3)
  - [LOW-4: Metrics reset() 非原子操作](#low-4)

---

## 严重问题

### CRITICAL-1: ClientHassiumStorage.openRegions 线程安全 {#critical-1}

**文件**: `common/src/main/java/.../cache/client/ClientHassiumStorage.java:44`

**问题描述**:
`openRegions` 使用普通 `HashMap`，但被多线程并发访问：
- 主线程：`readMetadata()` → `getRegionFileOrNull()` — 读取元数据
- HassiumTaskExecutor 后台线程：`persist()` → `getRegionFile()` — 写入缓存
- CacheSaveQueue 后台线程：`processTask()` → `getRegionFile()` — 区块卸载保存
- 主线程：`close()` / `clearAll()` — 迭代并清空

并发读写 `HashMap` 可能导致无限循环、丢失条目或 `ConcurrentModificationException`。

**影响范围**: 客户端缓存读写，影响所有使用区块缓存的场景

**解决方案**:

| 方案 | 描述 | 优点 | 缺点 |
|------|------|------|------|
| **A. ConcurrentHashMap** | 替换为 `new ConcurrentHashMap<>()` | 简单直接，无锁并发读 | 写入时仍有微小竞争（get+put 非原子） |
| **B. ConcurrentHashMap + computeIfAbsent** | 使用 `computeIfAbsent()` 替代 get+put 模式 | 原子操作，完全消除竞争 | 需要重构 `getRegionFile` 方法 |
| **C. synchronized 块** | 对所有访问加 `synchronized(openRegions)` | 最安全，完全串行化 | 性能较差，读操作也被阻塞 |

**推荐**: 方案 B — `computeIfAbsent` 是最正确的实现，且项目中已有类似模式（`HassiumAggregationManager.PACKET_BUFFER`）

---

### CRITICAL-2: HassiumRegionFile 读方法缺少同步 {#critical-2}

**文件**: `common/src/main/java/.../storage/HassiumRegionFile.java:89-167`

**问题描述**:
- `writeChunk` / `deleteChunk` 是 `synchronized`（实例锁）
- `readChunk` / `hasChunk` / `readContentHash` **没有** `synchronized`

这些方法共享访问 `offsets`（IntBuffer）和 `FileChannel`。`IntBuffer` 不是线程安全的，读写并发可能返回脏数据或抛出 `BufferUnderflowException`。

**影响范围**: 客户端缓存读取，高并发场景下可能读到损坏数据

**解决方案**:

| 方案 | 描述 | 优点 | 缺点 |
|------|------|------|------|
| **A. 统一 synchronized** | 给 `readChunk`、`hasChunk`、`readContentHash` 加 `synchronized` | 简单，与现有写方法一致 | 读操作串行化，性能下降 |
| **B. ReadWriteLock** | 使用 `ReentrantReadWriteLock`，读锁/写锁分离 | 读操作可并发，写操作独占 | 代码复杂度增加 |
| **C. 只保护 offsets** | 读取 `offsets` 时加 `synchronized(offsets)` | 粒度更细 | FileChannel 并发仍不安全 |

**推荐**: 方案 A — 客户端缓存读取频率不高，简单 synchronized 足够；方案 B 可作为后续优化

---

## 高优先级问题

### HIGH-1: ClientCacheDatabase PreparedStatement 跨线程复用 {#high-1}

**文件**: `common/src/main/java/.../cache/client/ClientCacheDatabase.java:379-391`

**问题描述**:
- 写入方法通过 `submitWrite()` 在单一 writerThread 串行执行
- 读取方法（`getEntry` 等）在调用者线程直接执行，复用共享的 `getEntryStmt`
- JDBC `Connection` 和 `PreparedStatement` 不是线程安全的

**影响范围**: 缓存查询，可能返回错误数据或抛出 SQLException

**解决方案**:

| 方案 | 描述 | 优点 | 缺点 |
|------|------|------|------|
| **A. 读方法也走 writerThread** | 将读取操作提交到 writeQueue，通过 Future 返回结果 | 完全串行化，最安全 | 读操作变为异步，增加延迟 |
| **B. 读方法创建局部 PreparedStatement** | 每次读取创建新的 PreparedStatement（像 `deleteByServer` 那样） | 简单，不影响现有架构 | 每次创建开销，可缓存 |
| **C. 独立读连接** | 为读操作创建单独的 SQLite 连接 | 读写完全分离 | 需要管理两个连接，WAL 模式下可行 |

**推荐**: 方案 B — 与项目中 `deleteByServer` 等方法的模式一致，开销可接受

---

### HIGH-2: ChunkSender.instance 缺少 volatile {#high-2}

**文件**: `common/src/main/java/.../network/ChunkSender.java:30-31`

**问题描述**:
```java
class ChunkSenderHolder {
    static ChunkSender instance;  // 无 volatile
}
```
写入（mod 初始化）和读取（ChunkPush 线程池）之间无 happens-before 保证。

**影响范围**: 理论上可能读到 null，实际因类加载时序问题概率极低

**解决方案**:

| 方案 | 描述 | 优点 | 缺点 |
|------|------|------|------|
| **A. 加 volatile** | `static volatile ChunkSender instance` | 最简单 | 仅解决可见性 |
| **B. 使用 Holder 模式** | 利用类加载机制保证线程安全 | 懒加载 + 线程安全 | 需要重构 |
| **C. 使用 AtomicReference** | `static final AtomicReference<ChunkSender>` | 功能最全 | 过度设计 |

**推荐**: 方案 A — 最简单直接，一行改动

---

### HIGH-3: ServerChunkPushManager.serializeChunk ByteBuf 泄漏 {#high-3}

**文件**: `common/src/main/java/.../network/ServerChunkPushManager.java:383-389`

**问题描述**:
```java
ByteBuf tempBuf = Unpooled.buffer();
FriendlyByteBuf friendlyBuf = new FriendlyByteBuf(tempBuf);
chunkPacket.write(friendlyBuf);  // 可能抛异常
byte[] data = new byte[tempBuf.readableBytes()];
tempBuf.getBytes(0, data);
tempBuf.release();  // 异常时不会执行
```

**影响范围**: 区块序列化失败时 Netty ByteBuf 内存泄漏

**解决方案**:

| 方案 | 描述 | 优点 | 缺点 |
|------|------|------|------|
| **A. try-finally** | 包裹在 try-finally 中确保 release | 标准做法 | 无 |
| **B. try-with-resources** | 如果 ByteBuf 实现 AutoCloseable | 更简洁 | ByteBuf 不实现 AutoCloseable |

**推荐**: 方案 A — 标准 Netty 资源管理模式

```java
ByteBuf tempBuf = Unpooled.buffer();
try {
    FriendlyByteBuf friendlyBuf = new FriendlyByteBuf(tempBuf);
    chunkPacket.write(friendlyBuf);
    byte[] data = new byte[tempBuf.readableBytes()];
    tempBuf.getBytes(0, data);
    return data;
} finally {
    tempBuf.release();
}
```

---

### HIGH-4: MainThreadDispatcher 距离计算优化 {#high-4}

**文件**: `common/src/main/java/.../concurrent/MainThreadDispatcher.java:235-238`

**问题描述**:
每次调用 `execute(task, chunkPos)` 都重复执行 `hassium$playerX / 16.0` 除法运算。

**影响范围**: 性能优化，非功能性问题

**解决方案**:

| 方案 | 描述 | 优点 | 缺点 |
|------|------|------|------|
| **A. 预计算** | `updatePlayerPosition` 时直接存储 chunk 坐标 | 消除重复计算 | 需要额外字段 |
| **B. 忽略** | 除法开销极小（~1ns），不值得优化 | 无改动 | 微小性能损失 |

**推荐**: 方案 B — 优先级最低，可忽略

---

## 中等问题

### MEDIUM-1: StorageConfig 注释与值不一致 {#medium-1}

**文件**: `common/src/main/java/.../config/HassiumConfig.java:38`

**问题描述**:
```java
true,  // enabled: 默认关闭  ← 注释说关闭，值是 true
```
与 CLAUDE.md 中"存储功能默认关闭（`storage.enabled = false`）"矛盾。

**解决方案**:

| 方案 | 描述 |
|------|------|
| **A. 修改注释** | `// enabled: 默认开启` |
| **B. 修改值** | `false,  // enabled: 默认关闭` |

**推荐**: 需要确认意图 — 如果存储功能已稳定，保持 `true` 并修正注释；如果仍需谨慎，改为 `false`

---

### MEDIUM-2: PlayerCompressionTracker 条目未清理 {#medium-2}

**文件**: `common/src/main/java/.../network/PlayerCompressionTracker.java:13-14`

**问题描述**:
- `clear()` 方法存在但从未被调用
- 如果 `removePlayer()` 因 mixin 注入失败未执行，条目永久残留

**解决方案**:

| 方案 | 描述 | 优点 | 缺点 |
|------|------|------|------|
| **A. 在 ServerLifecycleEvents 中调用 clear()** | 服务器停止时清理 | 简单 | 不处理异常断开 |
| **B. 定期清理** | 每 N 次操作检查并移除过期条目 | 自动清理 | 需要额外逻辑 |
| **C. 使用 WeakReference** | 玩家对象被 GC 时自动清理 | 自动化 | 实现复杂 |

**推荐**: 方案 A + 在更多断开连接路径中调用 `removePlayer()`

---

### MEDIUM-3: ClientChunkHandler.pendingContentHashes 无限增长 {#medium-3}

**文件**: `common/src/main/java/.../network/ClientChunkHandler.java:29`

**问题描述**:
- `storePendingContentHash` 存储条目，`consumePendingContentHash` 取出并移除
- 如果区块数据从未到达，条目永久残留
- 无 TTL、无大小限制

**解决方案**:

| 方案 | 描述 | 优点 | 缺点 |
|------|------|------|------|
| **A. TTL 清理** | 存储时记录时间戳，定期清理超过 N 秒的条目 | 自动过期 | 需要额外字段和定时器 |
| **B. 大小限制** | 超过阈值时清空最旧条目 | 简单 | 可能丢失有效数据 |
| **C. 随 metadata 重置** | 在 `resetStorage` 时已清理，保持现状 | 无改动 | 断开连接前可能积累 |

**推荐**: 方案 A — 添加时间戳和懒清理

---

### MEDIUM-4: HassiumAggregationManager.TASKS 生命周期管理 {#medium-4}

**文件**: `common/src/main/java/.../network/HassiumAggregationManager.java:42`

**问题描述**:
- `TASKS` 使用普通 `ArrayList`，虽然当前只在 `synchronized init()` 中访问
- `TIMER` 是 `static final`，永不关闭
- 无 shutdown/destroy 生命周期方法

**解决方案**:

| 方案 | 描述 | 优点 | 缺点 |
|------|------|------|------|
| **A. 添加 shutdown()** | 参考 `ServerChunkPushManager.shutdown()` 实现 | 完整生命周期 | 需要调用点 |
| **B. 使用 daemon 线程** | 已经是 daemon 线程，JVM 退出时自动终止 | 无改动 | 热重载时残留 |
| **C. 改为单个 ScheduledFuture** | 只需要一个定时任务，不需要 List | 简化设计 | 需要重构 |

**推荐**: 方案 C — 简化为单个 `ScheduledFuture` 字段

---

### MEDIUM-5: FabricNetworkManager.sendMetadataPacket 抛异常 {#medium-5}

**文件**: `fabric/src/main/java/.../network/FabricNetworkManager.java:255-258`

**问题描述**:
```java
public void sendMetadataPacket(FriendlyByteBuf buf) {
    throw new UnsupportedOperationException("Use FabricNetworkManagerService instead");
}
```
接口声明了方法但实现抛异常，调用错误重载会崩溃。

**解决方案**:

| 方案 | 描述 |
|------|------|
| **A. 从接口删除此方法** | 如果只通过 `INetworkManagerService` 调用 |
| **B. 实现正确逻辑** | 委托给 `FabricNetworkManagerService` |

**推荐**: 方案 A — 清理接口定义

---

### MEDIUM-6: FabricNetworkManager 反射访问私有字段 {#medium-6}

**文件**: `fabric/src/main/java/.../network/FabricNetworkManager.java:37-60`

**问题描述**:
使用反射访问 `Connection.channel` 和 `ServerPlayer.connection`，版本更新可能失效。

**解决方案**:

| 方案 | 描述 | 优点 | 缺点 |
|------|------|------|------|
| **A. Mixin Accessor** | 使用 `@Accessor` 注解 | 编译时检查，映射自动更新 | 需要额外 Mixin |
| **B. 保持反射** | 已有 try-catch 保护 | 简单 | 维护成本 |

**推荐**: 方案 A — 更稳定，Fabric/Forge 映射自动处理

---

## 低优先级问题

### LOW-1: 热路径 INFO 级别日志 {#low-1}

**文件**: 多个文件

**问题描述**:
`ServerChunkPushManager`、`ClientCacheLoadQueue` 等在每个区块操作时输出 INFO 日志。

**解决方案**: 将热路径日志降级为 DEBUG，保留 INFO 给启动/关闭/重大状态变更

---

### LOW-2: ClientCacheLoadQueue 迭代未快照 {#low-2}

**文件**: `common/src/main/java/.../cache/client/ClientCacheLoadQueue.java:293`

**问题描述**:
迭代 `PriorityBlockingQueue` 使用弱一致性迭代器，结果可能不精确。

**解决方案**: 用于自适应节流，近似值可接受，添加注释说明即可

---

### LOW-3: ChunkContentHashUtil 临时对象分配 {#low-3}

**文件**: `common/src/main/java/.../cache/ChunkContentHashUtil.java:82`

**问题描述**:
每次哈希计算创建新的 `ByteArrayOutputStream`。

**解决方案**: 使用 `ThreadLocal<ByteArrayOutputStream>` 复用，或直接流式计算哈希

---

### LOW-4: Metrics reset() 非原子操作 {#low-4}

**文件**: `common/src/main/java/.../metrics/HassiumMetricsImpl.java:185-213`

**问题描述**:
`reset()` 逐个重置 AtomicLong，其他线程可能读到部分重置的值。

**解决方案**: 指标数据非关键，可接受；如需原子性，使用 generation counter 模式

---

## 修复优先级建议

```
第一批（必须修复）:
  CRITICAL-1  → openRegions 改 ConcurrentHashMap + computeIfAbsent
  CRITICAL-2  → HassiumRegionFile 读方法加 synchronized
  HIGH-3      → serializeChunk ByteBuf try-finally

第二批（应该修复）:
  HIGH-1      → PreparedStatement 读方法创建局部实例
  HIGH-2      → ChunkSender.instance 加 volatile
  MEDIUM-1    → 修正注释/值不一致

第三批（考虑修复）:
  MEDIUM-2    → PlayerCompressionTracker 清理逻辑
  MEDIUM-3    → pendingContentHashes 过期机制
  MEDIUM-4    → TASKS 生命周期简化
  MEDIUM-5    → 清理接口定义
  MEDIUM-6    → 反射改为 Mixin Accessor

第四批（可选优化）:
  LOW-1 ~ LOW-4 → 日志级别、性能优化
```

---

## 修复状态（2026-07-11）

| 编号 | 方案 | 状态 | 改动文件 | 说明 |
|------|------|------|----------|------|
| CRITICAL-1 | B: computeIfAbsent | ✅ 已修复 | ClientHassiumStorage.java | HashMap→ConcurrentHashMap + computeIfAbsent |
| CRITICAL-2 | A: synchronized | ✅ 已修复 | HassiumRegionFile.java | readChunk/readContentHash/hasChunk 加 synchronized |
| HIGH-1 | B: 局部 Statement | ✅ 已修复 | ClientCacheDatabase.java | getEntry() 使用局部 PreparedStatement |
| HIGH-2 | A: volatile | ✅ 已修复 | ChunkSender.java | ChunkSenderHolder.instance 加 volatile |
| HIGH-3 | A: try-finally | ✅ 已修复 | ServerChunkPushManager.java | serializeChunk ByteBuf 加 try-finally |
| HIGH-4 | B: 忽略 | ⏭️ 跳过 | — | 除法开销 ~1ns，不值得优化 |
| MEDIUM-1 | A: 改注释 | ✅ 已修复 | HassiumConfig.java | "默认关闭"→"默认开启" |
| MEDIUM-2 | A: 生命周期清理 | ✅ 已修复 | MixinMinecraftServer.java | 服务器停止时调用 PlayerCompressionTracker.clear() |
| MEDIUM-3 | A: TTL 过期 | ✅ 已修复 | ClientChunkHandler.java | pendingContentHashes 添加 30s TTL 懒清理 |
| MEDIUM-4 | C: 单个 Future | ✅ 已修复 | HassiumAggregationManager.java | List\<ScheduledFuture\>→单个 ScheduledFuture |
| MEDIUM-5 | A: 清理接口 | ✅ 已修复 | FabricNetworkManager.java | sendMetadataPacket 改为 warn+release |
| MEDIUM-6 | A: Mixin Accessor | ⏭️ 跳过 | — | 需额外 Mixin 文件，当前反射有 try-catch 保护 |
| LOW-1 | — | ⏭️ 跳过 | — | 用户未选择 |
| LOW-2 | — | ⏭️ 跳过 | — | 近似值可接受 |
| LOW-3 | 流式哈希 | ✅ 已修复 | ChunkContentHashUtil.java | ByteArrayOutputStream→StreamingXXHash64 + HashingOutputStream |
| LOW-4 | — | ⏭️ 跳过 | — | 指标数据非关键 |
