# 区块加载优化技术方案

**日期**：2026-07-05

**目标**：通过方向性优先级调整和智能热点预加载，减少玩家移动时的区块加载延迟，提升游戏流畅度。

---

## 1. 背景与问题

### 1.1 当前架构

Hassium 已实现的区块缓存推送系统：
- 服务端发送元数据（位置+时间戳）
- 客户端比对本地缓存决定加载方式
- 使用 `ChunkTaskPriorityQueueSorter` 管理任务优先级

### 1.2 存在的问题

1. **被动加载**：只在玩家进入区块范围后才开始加载
2. **无方向性**：所有方向同等优先级，未考虑玩家移动方向
3. **无速度适应**：快速移动时前方区块加载不及时
4. **无历史记忆**：经常访问的区域每次都重新加载

### 1.3 优化目标

- 快速移动时，前方区块优先加载
- 经常访问的区域，提前预加载到内存
- 智能遗忘不再访问的区域，避免资源浪费

---

## 2. 技术方案总览

### 2.1 两阶段优化

| 阶段 | 目标 | 核心思路 |
|------|------|----------|
| 第一阶段 | 方向性优先级加载 | 根据移动方向和速度调整任务优先级 |
| 第二阶段 | 智能热点预加载 | 记录访问频率，预加载高频区域，智能遗忘旧数据 |

### 2.2 数据流

```
玩家移动
    ↓
┌─────────────────────────────────────────────────┐
│ 第一阶段：方向性优先级调整                        │
│ ChunkTaskPriorityQueueSorter.onLevelChange()    │
│ 根据方向 + 速度调整 queueLevel                   │
└─────────────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────────────┐
│ 第二阶段：热点预加载                              │
│ ServerChunkPreloader.preloadHotChunks()         │
│ 根据历史热度添加 PRELOAD ticket                  │
└─────────────────────────────────────────────────┘
    ↓
区块加载完成（优先级更高或提前加载）
```

---

## 3. 第一阶段：方向性优先级加载

### 3.1 核心原理

Minecraft 的区块加载优先级由 `level` 值决定（值越小优先级越高）：

```
level 31: ENTITY_TICKING（实体tick，最高优先级）
level 32: BLOCK_TICKING（方块tick）
level 33: FULL_CHUNK（完全加载）
level 34+: INACCESSIBLE（正在生成/加载）
```

任务队列 `ChunkTaskPriorityQueueSorter` 按 level 分桶排序，`pop()` 总是从最小 level 取任务。

### 3.2 注入点选择

**推荐方案**：修改 `ChunkTaskPriorityQueueSorter.onLevelChange()`

```java
// ChunkTaskPriorityQueueSorter.java
public void onLevelChange(ChunkPos chunkPos, IntSupplier p_140617_, int p_140618_, IntConsumer p_140619_) {
    this.mailbox.tell(new StrictQueue.IntRunnable(0, () -> {
        int i = p_140617_.getAsInt();
        this.queues.values().forEach((p_143155_) -> {
            p_143155_.resortChunkTasks(i, chunkPos, p_140618_);
        });
        p_140619_.accept(p_140618_);
    }));
}
```

**注入方式**：

```java
@Mixin(ChunkTaskPriorityQueueSorter.class)
public class MixinQueueSorter {
    @Inject(method = "onLevelChange", at = @At("HEAD"), cancellable = true)
    private void hassium$adjustLevelChange(
            ChunkPos chunkPos, IntSupplier getLevel, int newLevel,
            IntConsumer setLevel, CallbackInfo ci) {
        // 获取当前玩家
        ServerPlayer player = getNearestPlayer(chunkPos);
        if (player == null) return;

        // 计算方向性调整
        int adjustedLevel = DirectionalPriorityManager.adjustPriority(
            player, chunkPos, newLevel
        );

        // 如果调整了优先级，使用调整后的 level
        if (adjustedLevel != newLevel) {
            // 取消原版行为，手动执行调整后的逻辑
            ci.cancel();
            int oldLevel = getLevel.getAsInt();
            // ... 执行调整后的 resortChunkTasks
        }
    }
}
```

### 3.3 方向性优先级计算

#### 核心算法

```java
public class DirectionalPriorityManager {
    /**
     * 计算方向性优先级调整
     *
     * @param player        玩家
     * @param chunkPos      目标区块
     * @param originalLevel 原始优先级
     * @return 调整后的优先级
     */
    public static int adjustPriority(ServerPlayer player, ChunkPos chunkPos, int originalLevel) {
        Vec3 velocity = player.getDeltaMovement();
        double speed = velocity.length();

        // 静止时不调整
        if (speed < 0.1) {
            return originalLevel;
        }

        // 计算方向夹角
        double angleDiff = calculateAngleDifference(player, chunkPos, velocity);

        // 速度扩展
        int extension = getSpeedBasedExtension(speed);

        // 优先级调整
        return calculateAdjustedLevel(originalLevel, angleDiff, extension);
    }

    /**
     * 根据速度计算扩展距离
     */
    private static int getSpeedBasedExtension(double speed) {
        if (speed > 2.0) {       // 鞘翅/传送
            return 8;
        } else if (speed > 1.0) { // 疾跑
            return 4;
        } else if (speed > 0.5) { // 行走
            return 2;
        }
        return 0; // 缓慢移动
    }

    /**
     * 计算与移动方向的夹角
     */
    private static double calculateAngleDifference(
            ServerPlayer player, ChunkPos chunkPos, Vec3 velocity) {
        // 区块相对于玩家的方向
        double dx = chunkPos.x - player.chunkPosition().x;
        double dz = chunkPos.z - player.chunkPosition().z;
        double chunkAngle = Math.atan2(dx, dz);

        // 玩家移动方向
        double moveAngle = Math.atan2(velocity.x, velocity.z);

        // 计算夹角（0-180度）
        double diff = Math.abs(normalizeAngle(chunkAngle - moveAngle));
        return Math.toDegrees(diff);
    }

    /**
     * 根据夹角和速度计算调整后的优先级
     */
    private static int calculateAdjustedLevel(int originalLevel, double angleDiff, int extension) {
        int adjustment;

        if (angleDiff < 30) {
            // 正前方：大幅提高优先级
            adjustment = -2 - (extension / 2);
        } else if (angleDiff < 60) {
            // 侧前方：中等提高
            adjustment = -1;
        } else if (angleDiff < 90) {
            // 侧面：不调整
            adjustment = 0;
        } else if (angleDiff < 120) {
            // 侧后方：轻微降低
            adjustment = 1;
        } else {
            // 后方：降低优先级
            adjustment = 2;
        }

        // 限制范围，避免极端情况
        int adjusted = originalLevel + adjustment;
        return Math.max(31, Math.min(44, adjusted));
    }

    /**
     * 归一化角度到 [-PI, PI]
     */
    private static double normalizeAngle(double angle) {
        while (angle > Math.PI) angle -= 2 * Math.PI;
        while (angle < -Math.PI) angle += 2 * Math.PI;
        return angle;
    }
}
```

### 3.4 玩家状态追踪

```java
public class PlayerMovementTracker {
    private static final Map<UUID, MovementState> states = new ConcurrentHashMap<>();

    public static class MovementState {
        Vec3 lastPosition;
        float lastYRot;
        long lastMoveTime;
        boolean isMoving;
        double speed;

        public void update(ServerPlayer player) {
            Vec3 pos = player.position();
            float yRot = player.getYRot();

            // 计算速度
            if (lastPosition != null) {
                double distance = pos.distanceTo(lastPosition);
                speed = distance * 20; // 转换为 blocks/second
                isMoving = distance > 0.05;
            }

            // 更新朝向（只在移动时更新）
            if (isMoving) {
                lastYRot = yRot;
                lastMoveTime = System.currentTimeMillis();
            }

            lastPosition = pos;
        }

        public boolean isActivelyMoving() {
            return isMoving || (System.currentTimeMillis() - lastMoveTime < 500);
        }
    }

    public static void updatePlayer(ServerPlayer player) {
        states.computeIfAbsent(player.getUUID(), k -> new MovementState())
              .update(player);
    }

    public static MovementState getState(ServerPlayer player) {
        return states.get(player.getUUID());
    }

    public static void removePlayer(UUID playerId) {
        states.remove(playerId);
    }
}
```

### 3.5 Mixin 实现

```java
@Mixin(ChunkTaskPriorityQueueSorter.class)
public class MixinQueueSorter {

    @Inject(method = "onLevelChange", at = @At("HEAD"), cancellable = true)
    private void hassium$adjustLevelChange(
            ChunkPos chunkPos, IntSupplier getLevel, int newLevel,
            IntConsumer setLevel, CallbackInfo ci) {

        // 检查是否启用方向性优先级
        if (!HassiumConfigService.getInstance().isDirectionalPriorityEnabled()) {
            return;
        }

        // 获取最近的玩家
        ServerPlayer player = findNearestPlayer(chunkPos);
        if (player == null) return;

        // 更新玩家状态
        PlayerMovementTracker.updatePlayer(player);
        MovementState state = PlayerMovementTracker.getState(player);

        // 只在玩家移动时调整
        if (state == null || !state.isActivelyMoving()) {
            return;
        }

        // 计算调整后的优先级
        int adjustedLevel = DirectionalPriorityManager.adjustPriority(
            player, chunkPos, newLevel
        );

        // 如果有调整，替换原版行为
        if (adjustedLevel != newLevel) {
            ci.cancel();

            // 手动执行调整后的逻辑
            // 注意：这里需要访问私有字段，可能需要 @Shadow 或 Accessor
            int oldLevel = getLevel.getAsInt();
            // ... resortChunkTasks with adjustedLevel
            setLevel.accept(adjustedLevel);
        }
    }

    @Unique
    private ServerPlayer findNearestPlayer(ChunkPos chunkPos) {
        // 从服务端获取最近的玩家
        // 这需要访问 ServerLevel 或 DistanceManager
        // 实现细节取决于具体的注入方式
        return null; // 占位
    }
}
```

### 3.6 配置设计

```java
// NetworkConfig 新增
public record NetworkConfig(
    // ... 现有字段 ...
    boolean directionalPriorityEnabled,    // 方向性优先级开关（默认 true）
    double directionalFrontAngle,          // 正前方角度范围（默认 30°）
    double directionalSideAngle,           // 侧前方角度范围（默认 60°）
    int directionalFrontBonus,             // 正前方优先级加成（默认 2）
    int directionalBackPenalty,            // 后方优先级惩罚（默认 2）
    double directionalSpeedThreshold       // 速度阈值（默认 0.1）
) {
    public static final NetworkConfig DEFAULT = new NetworkConfig(
        // ... 现有默认值 ...
        true,   // directionalPriorityEnabled
        30.0,   // directionalFrontAngle
        60.0,   // directionalSideAngle
        2,      // directionalFrontBonus
        2,      // directionalBackPenalty
        0.1     // directionalSpeedThreshold
    );
}
```

### 3.7 预期效果

| 场景 | 原版行为 | 优化后行为 |
|------|----------|------------|
| 静止 | 圆形视距 | 圆形视距（不变） |
| 行走 | 圆形视距 | 轻微椭圆（前方 2 圈优先） |
| 疾跑 | 圆形视距 | 明显椭圆（前方 4 圈优先） |
| 鞘翅 | 圆形视距 | 大幅椭圆（前方 8 圈优先） |

---

## 4. 第二阶段：智能热点预加载

### 4.1 核心原理

记录玩家访问区块的频率，使用时间衰减算法计算热度，预加载高频区域，智能遗忘不再访问的区域。

### 4.2 数据结构

#### 热度表

```java
public class ChunkHeatMap {
    // 玩家ID → (区块位置 → 热度条目)
    private final Map<UUID, Map<Long, HeatEntry>> playerHeatMaps = new ConcurrentHashMap<>();

    // 热度条目
    public static class HeatEntry {
        private int visitCount;           // 访问次数
        private long lastVisitTime;       // 最后访问时间（毫秒）
        private long firstVisitTime;      // 首次访问时间（毫秒）
        private long totalDwellTime;      // 总停留时间（毫秒）

        public HeatEntry(long currentTime) {
            this.visitCount = 1;
            this.lastVisitTime = currentTime;
            this.firstVisitTime = currentTime;
            this.totalDwellTime = 0;
        }

        public void recordVisit(long currentTime) {
            this.visitCount++;
            this.lastVisitTime = currentTime;
        }

        public void addDwellTime(long dwellTime) {
            this.totalDwellTime += dwellTime;
        }

        // Getters
        public int getVisitCount() { return visitCount; }
        public long getLastVisitTime() { return lastVisitTime; }
        public long getFirstVisitTime() { return firstVisitTime; }
        public long getTotalDwellTime() { return totalDwellTime; }
    }
}
```

### 4.3 智能遗忘算法

#### 多维度衰减模型

```java
public class ChunkHeatMap {
    // 衰减参数
    private static final double HOURLY_DECAY_RATE = 0.1;      // 每小时衰减 10%
    private static final double DAILY_DECAY_RATE = 0.01;       // 每天额外衰减 1%
    private static final double FREQUENCY_WEIGHT = 0.5;        // 频率权重
    private static final double RECENCY_WEIGHT = 0.3;          // 新近度权重
    private static final double DWELL_WEIGHT = 0.2;            // 停留时间权重

    /**
     * 计算当前热度分数
     *
     * @param entry       热度条目
     * @param currentTime 当前时间
     * @return 热度分数（0.0 - 100.0）
     */
    public double calculateHeatScore(HeatEntry entry, long currentTime) {
        // 1. 时间衰减（基于最后访问时间）
        long hoursSinceVisit = (currentTime - entry.lastVisitTime) / 3600000L;
        double timeDecay = Math.exp(-HOURLY_DECAY_RATE * hoursSinceVisit);

        // 2. 年龄衰减（基于首次访问时间）
        long ageInDays = (currentTime - entry.firstVisitTime) / 86400000L;
        double ageDecay = Math.exp(-DAILY_DECAY_RATE * ageInDays);

        // 3. 频率分数（归一化到 0-1）
        double frequencyScore = Math.min(1.0, entry.visitCount / 100.0);

        // 4. 新近度分数（归一化到 0-1）
        double recencyScore = timeDecay;

        // 5. 停留时间分数（归一化到 0-1，假设 1 小时为满分）
        double dwellScore = Math.min(1.0, entry.totalDwellTime / 3600000.0);

        // 综合分数
        double rawScore = (frequencyScore * FREQUENCY_WEIGHT +
                          recencyScore * RECENCY_WEIGHT +
                          dwellScore * DWELL_WEIGHT) * ageDecay;

        // 转换到 0-100 范围
        return rawScore * 100.0;
    }
}
```

#### 清理策略

```java
public class ChunkHeatMap {
    // 清理参数
    private static final int MAX_ENTRIES_PER_PLAYER = 1000;
    private static final double MIN_HEAT_THRESHOLD = 1.0;
    private static final long MAX_AGE_DAYS = 7;

    /**
     * 定期清理低热度数据
     */
    public void cleanup() {
        long currentTime = System.currentTimeMillis();
        long cutoffTime = currentTime - (MAX_AGE_DAYS * 86400000L);

        for (Map.Entry<UUID, Map<Long, HeatEntry>> playerEntry : playerHeatMaps.entrySet()) {
            Map<Long, HeatEntry> heatMap = playerEntry.getValue();

            // 移除过期和低热度条目
            heatMap.entrySet().removeIf(entry -> {
                HeatEntry heat = entry.getValue();

                // 规则 1：超过最大年龄
                if (heat.getLastVisitTime() < cutoffTime) {
                    return true;
                }

                // 规则 2：热度分数过低
                if (calculateHeatScore(heat, currentTime) < MIN_HEAT_THRESHOLD) {
                    return true;
                }

                return false;
            });

            // 规则 3：限制每个玩家的最大条目数
            if (heatMap.size() > MAX_ENTRIES_PER_PLAYER) {
                // 按热度排序，保留前 N 个
                heatMap.entrySet().stream()
                    .sorted((a, b) -> Double.compare(
                        calculateHeatScore(b.getValue(), currentTime),
                        calculateHeatScore(a.getValue(), currentTime)))
                    .limit(MAX_ENTRIES_PER_PLAYER)
                    .forEach(entry -> heatMap.put(entry.getKey(), entry.getValue()));
            }
        }
    }

    /**
     * 调度清理任务
     */
    public void scheduleCleanup() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Hassium-HeatMap-Cleanup");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::cleanup, 1, 1, TimeUnit.HOURS);
    }
}
```

### 4.4 热点预加载器

```java
public class ServerChunkPreloader {
    private static final ServerChunkPreloader INSTANCE = new ServerChunkPreloader();

    private final ChunkHeatMap heatMap = new ChunkHeatMap();
    private final Map<UUID, ChunkPos> lastPlayerPositions = new ConcurrentHashMap<>();

    // 预加载参数
    private static final int MAX_PRELOAD_COUNT = 32;
    private static final int PRELOAD_CHECK_INTERVAL = 20; // 每 20 tick 检查一次
    private static final double PRELOAD_DISTANCE_LIMIT = 128.0; // 最大预加载距离

    private ServerChunkPreloader() {
        heatMap.scheduleCleanup();
    }

    public static ServerChunkPreloader getInstance() {
        return INSTANCE;
    }

    /**
     * 记录玩家访问
     */
    public void onPlayerMove(ServerPlayer player, ChunkPos newChunk) {
        UUID playerId = player.getUUID();
        ChunkPos lastPos = lastPlayerPositions.get(playerId);

        // 记录新区块访问
        if (lastPos == null || !lastPos.equals(newChunk)) {
            heatMap.recordVisit(playerId, newChunk);

            // 记录停留时间
            if (lastPos != null) {
                heatMap.addDwellTime(playerId, lastPos, PRELOAD_CHECK_INTERVAL * 50);
            }

            lastPlayerPositions.put(playerId, newChunk);
        }
    }

    /**
     * 预加载热点区块
     */
    public void preloadHotChunks(ServerPlayer player) {
        UUID playerId = player.getUUID();

        // 获取该玩家的热点区块（按热度排序）
        List<ChunkPos> hotChunks = heatMap.getHotChunks(playerId, MAX_PRELOAD_COUNT);

        // 计算预加载优先级
        for (ChunkPos pos : hotChunks) {
            double distance = getDistance(player, pos);

            // 距离太远不预加载
            if (distance > PRELOAD_DISTANCE_LIMIT) {
                continue;
            }

            double heat = heatMap.getHeat(playerId, pos);

            // 距离近 + 热度高 = 高优先级
            int priority = calculatePreloadPriority(distance, heat);

            // 添加预加载 ticket
            addPreloadTicket(player, pos, priority);
        }
    }

    /**
     * 计算预加载优先级
     */
    private int calculatePreloadPriority(double distance, double heat) {
        // 基础优先级（距离越近越高）
        int basePriority = ChunkLevel.byStatus(FullChunkStatus.FULL) + (int)(distance / 16);

        // 热度加成（热度越高，优先级越高）
        int heatBonus = (int)(heat / 10);

        // 限制范围
        return Math.max(31, Math.min(44, basePriority - heatBonus));
    }

    /**
     * 添加预加载 ticket
     */
    private void addPreloadTicket(ServerPlayer player, ChunkPos pos, int priority) {
        // 使用自定义 TicketType 或修改现有 ticket 系统
        // 具体实现取决于注入方式
    }

    /**
     * 玩家断开连接时清理
     */
    public void onPlayerDisconnect(UUID playerId) {
        lastPlayerPositions.remove(playerId);
    }
}
```

### 4.5 集成到服务端

```java
// MixinServerPlayer.java
@Mixin(ServerPlayer.class)
public class MixinServerPlayer {
    private int hassium$preloadTickCounter = 0;

    @Inject(method = "tick", at = @At("TAIL"))
    private void hassium$onTick(CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;

        // 记录玩家移动
        ServerChunkPreloader.getInstance().onPlayerMove(self, self.chunkPosition());

        // 定期预加载热点区块
        if (++hassium$preloadTickCounter >= 20) {
            hassium$preloadTickCounter = 0;
            ServerChunkPreloader.getInstance().preloadHotChunks(self);
        }
    }

    @Inject(method = "disconnect", at = @At("HEAD"))
    private void hassium$onDisconnect(CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        ServerChunkPreloader.getInstance().onPlayerDisconnect(self.getUUID());
    }
}
```

### 4.6 配置设计

```java
// NetworkConfig 新增
public record NetworkConfig(
    // ... 现有字段 ...
    boolean smartPreloadEnabled,           // 智能预加载开关（默认 true）
    int maxPreloadCount,                    // 最大预加载数量（默认 32）
    int maxHeatMapSize,                     // 最大热度表大小（默认 10000）
    double heatDecayRate,                   // 热度衰减率（默认 0.1/小时）
    int maxHeatAgeDays,                     // 最大热度年龄（默认 7 天）
    double minHeatThreshold,                // 最小热度阈值（默认 1.0）
    double preloadDistanceLimit             // 最大预加载距离（默认 128）
) {
    public static final NetworkConfig DEFAULT = new NetworkConfig(
        // ... 现有默认值 ...
        true,   // smartPreloadEnabled
        32,     // maxPreloadCount
        10000,  // maxHeatMapSize
        0.1,    // heatDecayRate
        7,      // maxHeatAgeDays
        1.0,    // minHeatThreshold
        128.0   // preloadDistanceLimit
    );
}
```

### 4.7 预期效果

| 场景 | 效果 |
|------|------|
| 经常往返于两个基地 | 两个基地周围区块常驻预加载 |
| 探索新区域 | 无历史数据，依赖第一阶段 |
| 长时间未访问某区域 | 自动遗忘，释放资源 |
| 多个玩家 | 每个玩家独立的热度表 |

---

## 5. 实现计划

### 5.1 第一阶段（3天）

| 任务 | 文件 | 说明 |
|------|------|------|
| DirectionalPriorityManager | 新建 | 方向性优先级计算核心 |
| PlayerMovementTracker | 新建 | 玩家移动状态追踪 |
| MixinQueueSorter | 修改 | 注入到 ChunkTaskPriorityQueueSorter |
| NetworkConfig 扩展 | 修改 | 添加方向性优先级配置 |
| HassiumConfigService 扩展 | 修改 | 添加配置 getter |
| 单元测试 | 新建 | 测试优先级计算算法 |

### 5.2 第二阶段（4天）

| 任务 | 文件 | 说明 |
|------|------|------|
| ChunkHeatMap | 新建 | 热度表和智能遗忘算法 |
| ServerChunkPreloader | 新建 | 热点预加载管理器 |
| MixinServerPlayer 修改 | 修改 | 集成预加载到玩家 tick |
| NetworkConfig 扩展 | 修改 | 添加智能预加载配置 |
| 集成测试 | - | 测试热度记录和预加载 |

### 5.3 测试计划

#### 单元测试

```java
@Test
void testDirectionalPriorityLinearMovement() {
    // 测试直线移动
    DirectionalPriorityManager manager = new DirectionalPriorityManager();
    Vec3 velocity = new Vec3(1, 0, 0); // 向东移动
    ChunkPos frontChunk = new ChunkPos(5, 0);
    ChunkPos sideChunk = new ChunkPos(0, 5);
    ChunkPos backChunk = new ChunkPos(-5, 0);

    int originalLevel = 33;

    // 正前方应提高优先级
    assertTrue(manager.adjustPriority(player, frontChunk, originalLevel) < originalLevel);

    // 侧面应保持或轻微调整
    assertEquals(originalLevel, manager.adjustPriority(player, sideChunk, originalLevel));

    // 后方应降低优先级
    assertTrue(manager.adjustPriority(player, backChunk, originalLevel) > originalLevel);
}

@Test
void testHeatMapDecay() {
    ChunkHeatMap heatMap = new ChunkHeatMap();
    long currentTime = System.currentTimeMillis();

    // 记录访问
    heatMap.recordVisit(playerId, chunkPos);

    // 立即检查热度
    double initialHeat = heatMap.getHeat(playerId, chunkPos);
    assertTrue(initialHeat > 0);

    // 模拟 1 小时后
    long oneHourLater = currentTime + 3600000;
    double decayedHeat = heatMap.calculateHeatScore(entry, oneHourLater);
    assertTrue(decayedHeat < initialHeat);
}
```

#### 集成测试

1. **直线飞行测试**：验证前方区块加载更快
2. **急转弯测试**：验证优先级平滑过渡
3. **往返测试**：验证热点预加载生效
4. **长时间测试**：验证智能遗忘机制

---

## 6. 性能考虑

### 6.1 CPU 开销

| 组件 | 开销 | 说明 |
|------|------|------|
| DirectionalPriorityManager | 低 | 向量运算，每区块一次 |
| PlayerMovementTracker | 低 | 每 tick 更新一次 |
| ChunkHeatMap | 低 | 查表操作 |
| ServerChunkPreloader | 中等 | 每 20 tick 检查一次 |

### 6.2 内存开销

| 组件 | 开销 | 说明 |
|------|------|------|
| PlayerMovementState | ~100 bytes/player | 玩家状态 |
| HeatEntry | ~50 bytes/entry | 热度条目 |
| ChunkHeatMap | ~500KB (10000 条目) | 每个玩家 |

### 6.3 网络开销

- 第一阶段：无额外网络开销
- 第二阶段：无额外网络开销（纯服务端优化）

---

## 7. 风险与缓解

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| 优先级调整过于激进 | 后方区块饥饿 | 限制调整范围（±2级） |
| 热点数据占用过多内存 | 服务端内存压力 | 限制每个玩家最大条目数 |
| 预加载触发过多区块加载 | 服务端 CPU/IO 压力 | 限制预加载距离和数量 |
| 与其他 mod 冲突 | 功能异常 | 提供配置开关，可随时禁用 |

---

## 8. 总结

本方案通过两阶段优化，实现智能区块预加载：

1. **第一阶段**：方向性优先级加载
   - 根据玩家移动方向调整任务优先级
   - 根据速度动态调整预加载范围
   - 实现简单，效果明显

2. **第二阶段**：智能热点预加载
   - 记录玩家访问区块的频率
   - 使用时间衰减算法计算热度
   - 预加载高频区域，智能遗忘旧数据

两阶段协同工作，可以显著减少玩家移动时的区块加载延迟，提升游戏流畅度。所有优化都提供配置开关，可以根据需要调整或禁用。
