# 客户端缓存热度清理系统

**开发日期**：2026-07-06

**目标**：实现可配置的最大存储策略和基于热度的缓存清理机制，防止客户端缓存无限增长。

## 1. 系统概述

### 1.1 设计目标

- **可配置的最大存储限制**：用户可设置缓存大小上限
- **基于热度的清理机制**：自动清理长时间未访问的冷区块
- **LRU + 热度加权**：结合最近访问时间和访问频率的智能清理策略
- **不影响服务端**：使用独立的 SQLite 数据库存储热度信息，不修改 MetadataTable

### 1.2 架构设计

```
ClientHassiumStorage (存储层)
├── HassiumRegionFile (区块数据存储，保持不变)
├── MetadataTable (元数据表，保持不变)
├── ClientCacheDatabase (SQLite 数据库，新增)
│   └── cache_entries 表 (热度索引)
└── CacheEvictionManager (清理管理器，新增)
    └── 热度评分 + 清理策略
```

## 2. 热度评分算法

### 2.1 评分公式

```
hotScore = recencyWeight × (1 / (1 + timeDiff))
         + frequencyWeight × (1 / (1 + accessCount))

其中：
- timeDiff = currentGameTime - lastAccessGameTime (游戏 ticks)
- accessCount: 累计访问次数
- recencyWeight: 0.7 (最近访问权重)
- frequencyWeight: 0.3 (访问频率权重)
```

### 2.2 热度等级划分

| 等级 | hotScore 范围 | 说明 | 清理策略 |
|------|---------------|------|----------|
| 热区块 | > 0.7 | 玩家常驻区域 | 优先保留 |
| 温区块 | 0.3 - 0.7 | 偶尔访问 | 可考虑保留 |
| 冷区块 | < 0.3 | 长时间未访问 | 优先清理 |

### 2.3 评分示例

假设当前游戏时间为 100000 ticks：

| 区块 | lastAccessGameTime | accessCount | hotScore | 等级 |
|------|-------------------|-------------|----------|------|
| A | 99990 | 100 | 0.77 | 热 |
| B | 95000 | 10 | 0.24 | 温 |
| C | 50000 | 1 | 0.14 | 冷 |
| D | 10000 | 1 | 0.07 | 冷 |

## 3. SQLite 数据库设计

### 3.1 表结构

**表名**：`cache_entries`

```sql
CREATE TABLE cache_entries (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    chunk_x INTEGER NOT NULL,
    chunk_z INTEGER NOT NULL,
    dimension TEXT NOT NULL DEFAULT 'minecraft:overworld',
    region_x INTEGER NOT NULL,
    region_z INTEGER NOT NULL,
    timestamp INTEGER NOT NULL DEFAULT 0,
    access_count INTEGER NOT NULL DEFAULT 1,
    last_access_game_time INTEGER NOT NULL DEFAULT 0,
    file_path TEXT NOT NULL,
    file_size INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now')),
    UNIQUE(chunk_x, chunk_z, dimension)
);
```

### 3.2 字段说明

| 字段 | 类型 | 说明 |
|------|------|------|
| chunk_x | INTEGER | 区块 X 坐标 |
| chunk_z | INTEGER | 区块 Z 坐标 |
| dimension | TEXT | 维度名称（如 minecraft:overworld） |
| region_x | INTEGER | Region X 坐标 |
| region_z | INTEGER | Region Z 坐标 |
| timestamp | INTEGER | 区块的 inhabitedTime |
| access_count | INTEGER | 累计访问次数 |
| last_access_game_time | INTEGER | 最后访问的游戏时间（ticks） |
| file_path | TEXT | 缓存文件路径 |
| file_size | INTEGER | 缓存文件大小（字节） |
| created_at | INTEGER | 创建时间（Unix 时间戳） |

### 3.3 索引

```sql
-- 按位置查询
CREATE INDEX idx_chunk_pos ON cache_entries(chunk_x, chunk_z, dimension);

-- 按热度排序（用于清理）
CREATE INDEX idx_hot_score ON cache_entries(access_count, last_access_game_time);

-- 按 region 分组
CREATE INDEX idx_region ON cache_entries(region_x, region_z, dimension);
```

## 4. 配置系统

### 4.1 配置项

在 `HassiumConfig.ClientCacheConfig` 中添加：

```java
double hotScoreThreshold,         // 热度阈值，低于此值视为冷区块 (默认 0.3)
double recencyWeight,             // 最近访问权重 (默认 0.7)
double frequencyWeight,           // 访问频率权重 (默认 0.3)
int cleanupIntervalTicks,         // 清理检查间隔 ticks (默认 6000 = 5分钟)
int targetCacheSizeMb,            // 目标缓存大小 (默认 maxSizeMb * 0.8)
int minCleanupBatchSize           // 每次最少清理区块数 (默认 100)
```

### 4.2 配置示例

```json
{
  "clientCache": {
    "enabled": true,
    "maxSizeMb": 2048,
    "maxAgeDays": 30,
    "renderRadius": 24,
    "serverAuthoritativeRadius": 10,
    "sendCacheMetadata": true,
    "hotScoreThreshold": 0.3,
    "recencyWeight": 0.7,
    "frequencyWeight": 0.3,
    "cleanupIntervalTicks": 6000,
    "targetCacheSizeMb": 1638,
    "minCleanupBatchSize": 100
  }
}
```

### 4.3 配置说明

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| hotScoreThreshold | 0.3 | 热度阈值，低于此值的区块会被清理 |
| recencyWeight | 0.7 | 最近访问的权重（0-1） |
| frequencyWeight | 0.3 | 访问频率的权重（0-1） |
| cleanupIntervalTicks | 6000 | 清理检查间隔（游戏 ticks，1 tick = 50ms） |
| targetCacheSizeMb | maxSizeMb × 0.8 | 清理目标大小 |
| minCleanupBatchSize | 100 | 每次最少清理的区块数 |

## 5. 核心组件

### 5.1 ClientCacheDatabase

**职责**：使用 SQLite 存储缓存索引和热度信息

**主要方法**：

```java
// 插入或更新缓存条目
void upsertEntry(CacheEntryInfo entry)

// 更新访问信息
void updateAccessInfo(int chunkX, int chunkZ, String dimension, long currentGameTime)

// 获取冷区块列表（按热度排序）
List<CacheEntryInfo> getColdEntries(long currentGameTime, int limit)

// 获取缓存统计
CacheStats getStats()
```

### 5.2 CacheEvictionManager

**职责**：执行缓存清理策略

**主要方法**：

```java
// 计算热度评分
static double calculateHotScore(int accessCount, long lastAccessGameTime, long currentGameTime)

// 判断是否需要清理
boolean shouldCleanup(long currentSizeBytes, ClientCacheConfig config)

// 执行清理
int performCleanup(long currentGameTime, ClientCacheConfig config)

// 获取热度统计
HotStats getHotStats(long currentGameTime)
```

### 5.3 ClientHassiumStorage 集成

**职责**：在存储层集成热度追踪和清理逻辑

**关键改动**：

```java
// 保存缓存时更新数据库
boolean persist(ChunkPos pos, byte[] nbtData, long inhabitedTime)

// 加载缓存时更新访问信息
byte[] loadAndDecompress(ChunkPos pos)

// 定期执行清理
int performCacheCleanup(long currentGameTime)

// 手动触发清理
int manualCleanup(long currentGameTime)
```

## 6. 清理流程

### 6.1 自动清理流程

```
游戏运行中 (每 cleanupIntervalTicks 触发一次)
    ↓
CacheEvictionManager.shouldCleanup()
    ↓ (判断: 当前大小 > targetSize * 0.9)
查询 SQLite 获取冷区块列表
    ↓ (ORDER BY hot_score ASC)
批量删除冷区块，直到 currentSize <= targetSize
    ↓
记录日志：清理了多少区块，释放了多少空间
```

### 6.2 清理时机

1. **自动清理**：每 `cleanupIntervalTicks` 触发一次检查
2. **手动清理**：通过命令或 API 触发
3. **维度清理**：清理特定维度的所有缓存
4. **全量清理**：清空所有缓存

### 6.3 清理策略

```java
// 1. 检查是否需要清理
if (currentSize > targetSize * 0.9) {
    // 2. 获取冷区块列表
    List<CacheEntryInfo> coldEntries = database.getColdEntries(currentGameTime, batchSize);

    // 3. 批量删除冷区块
    for (CacheEntryInfo entry : coldEntries) {
        if (freedBytes >= sizeToFree) break;
        if (entry.hotScore() > threshold) continue;

        deleteCacheFile(entry);
        database.deleteEntry(entry);
    }
}
```

## 7. 日志输出

### 7.1 初始化日志

```
Hassium: Client cache database initialized for world mpserver_12345
```

### 7.2 清理日志

```
Hassium: [CACHE CLEANUP] Triggered: currentSize=2048MB, targetSize=1638MB, needToFree=410MB
Hassium: [CACHE CLEANUP] Removed chunk [100, -50] (hotScore=0.125, size=256KB)
Hassium: [CACHE CLEANUP] Removed chunk [101, -51] (hotScore=0.180, size=256KB)
Hassium: [CACHE CLEANUP] Complete: removed 150 chunks, freed 450MB
```

### 7.3 统计日志

```
Hassium: [CACHE STATS] Total: 500 chunks, Hot: 100, Warm: 250, Cold: 150, AvgHotScore: 0.45
```

## 8. 文件变更清单

### 新建文件

| 文件 | 说明 |
|------|------|
| `cache/client/ClientCacheDatabase.java` | SQLite 数据库管理 |
| `cache/client/CacheEvictionManager.java` | 缓存清理管理器 |
| `docs/client-cache-eviction.md` | 本文档 |

### 修改文件

| 文件 | 变更 |
|------|------|
| `common/build.gradle` | 添加 SQLite JDBC 依赖 |
| `config/HassiumConfig.java` | 添加热度相关配置项 |
| `config/HassiumConfigService.java` | 添加新配置的 getter 方法 |
| `cache/client/ClientHassiumStorage.java` | 集成 SQLite 和清理逻辑 |

## 9. 依赖

### 9.1 新增依赖

```gradle
implementation 'org.xerial:sqlite-jdbc:3.45.1.0'
```

### 9.2 依赖说明

- **sqlite-jdbc**: SQLite 的 JDBC 驱动，支持跨平台
- 版本 3.45.1.0 是稳定版本，支持 Java 17

## 10. 性能考虑

### 10.1 数据库性能

- SQLite 查询性能优秀，单次查询耗时 < 1ms
- 使用索引优化排序和过滤操作
- 批量操作减少数据库交互次数

### 10.2 内存占用

- 缓存索引存储在 SQLite，不占用堆内存
- 只在需要时加载区块数据
- 清理操作在后台线程执行，不阻塞主线程

### 10.3 磁盘 I/O

- SQLite 数据库文件大小约 1MB/10000 条记录
- 清理操作批量删除文件，减少碎片
- 定期执行 VACUUM 优化数据库大小

## 11. 扩展性

### 11.1 未来扩展

- **多维度支持**：支持不同维度的独立缓存策略
- **压缩策略**：根据热度选择不同的压缩级别
- **预加载优化**：基于热度预测预加载区块
- **云端同步**：热度信息云端同步，跨设备共享

### 11.2 API 扩展

```java
// 获取热度统计
HotStats getHotStats(long currentGameTime)

// 获取冷区块列表
List<CacheEntryInfo> getColdEntries(long currentGameTime, int limit)

// 手动触发清理
int manualCleanup(long currentGameTime)

// 清理指定维度
int clearDimension(String dimension)
```

## 12. 测试验证

### 12.1 单元测试

- 热度评分计算正确性
- 清理策略选择正确性
- 数据库 CRUD 操作

### 12.2 集成测试

- 启动游戏，验证配置加载
- 加载区块，验证 accessCount 递增
- 等待一段时间，触发清理
- 验证冷区块被清理，热区块保留

### 12.3 性能测试

- 大量区块（10000+）的热度评分计算性能
- 清理操作的执行时间
- 数据库查询性能

## 13. 注意事项

### 13.1 数据安全

- 清理操作不可逆，建议用户定期备份
- 清理前检查文件是否存在
- 清理失败时记录日志，不中断游戏

### 13.2 兼容性

- 旧版本缓存文件兼容，首次运行时自动创建数据库
- 配置项有默认值，无需用户手动配置
- SQLite 数据库文件向后兼容

### 13.3 调试

- 启用 DEBUG 日志查看详细清理信息
- 使用 `/hassium cache stats` 命令查看热度统计
- 手动触发清理测试清理逻辑
