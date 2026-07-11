# 调试日志配置指南

## 概述

Hassium 添加了细粒度的日志开关系统，可以控制不同类型的调试日志输出。默认情况下，所有调试日志都是关闭的，以减少日志噪音。需要调试时，可以按需开启特定类型的日志。

## 配置文件位置

- **客户端**: `config/hassium/hassium.json`
- **服务端**: `config/hassium/hassium.json`

## 中文注释支持

配置文件支持中文注释，每个配置项都有详细的中文说明。注释以 `//` 开头，在加载时会自动忽略。

示例：
```json
{
  // 调试日志配置（开启会产生大量日志，仅调试时使用）
  "debug": {
    // 元数据日志：接收、比对、应用
    "metadataLogging": false,
    // 主线程调度器日志：回调队列操作
    "dispatcherLogging": false
  }
}
```

## 配置项说明

在配置文件的 `debug` 部分，可以控制以下日志类型：

```json
{
  "debug": {
    "metadataLogging": false,
    "dispatcherLogging": false,
    "asyncLogging": false,
    "compressionLogging": false,
    "chunkApplyLogging": false,
    "networkLogging": false,
    "cacheLogging": false
  }
}
```

### 各配置项详细说明

| 配置项 | 日志前缀 | 说明 | 典型场景 |
|--------|----------|------|----------|
| `metadataLogging` | `[CLIENT_METADATA]`, `[COMPARE_METADATA]`, `[APPLY_METADATA]` | 元数据接收、比对、应用相关日志 | 调试区块缓存命中率、元数据比对逻辑 |
| `dispatcherLogging` | `[MAIN_DISPATCHER]` | 主线程回调调度器日志 | 调试回调队列积压、优先级排序问题 |
| `asyncLogging` | `[ASYNC]` | 异步任务提交和执行日志 | 调试后台任务执行情况、回调链 |
| `compressionLogging` | `[HANDLE_COMPRESSED]` | 区块压缩/解压相关日志 | 调试压缩比、解压性能、数据损坏 |
| `chunkApplyLogging` | `[APPLY_CHUNK]` | 区块应用到世界相关日志 | 调试区块加载失败、数据格式问题 |
| `networkLogging` | `[SEND_CHUNK]`, `[RECEIVED]` | 网络传输相关日志 | 调试网络延迟、丢包、数据包大小 |
| `cacheLogging` | `[CACHE_LOAD]`, `[CACHE_APPLY]` | 缓存操作相关日志 | 调试缓存读写、LRU 淘汰策略 |

## 使用场景示例

### 场景 1：区块加载不显示

**问题**: 玩家移动时新区块不加载

**调试步骤**:
1. 开启 `metadataLogging` 检查元数据是否接收
2. 开启 `dispatcherLogging` 检查回调是否执行
3. 开启 `compressionLogging` 检查数据是否解压

```json
{
  "debug": {
    "metadataLogging": true,
    "dispatcherLogging": true,
    "compressionLogging": true
  }
}
```

### 场景 2：性能问题

**问题**: 区块加载卡顿

**调试步骤**:
1. 开启 `asyncLogging` 检查后台任务执行时间
2. 开启 `dispatcherLogging` 检查队列积压情况
3. 开启 `compressionLogging` 检查解压耗时

```json
{
  "debug": {
    "asyncLogging": true,
    "dispatcherLogging": true,
    "compressionLogging": true
  }
}
```

### 场景 3：缓存问题

**问题**: 缓存未命中或缓存损坏

**调试步骤**:
1. 开启 `metadataLogging` 检查缓存比对结果
2. 开启 `cacheLogging` 检查缓存读写操作

```json
{
  "debug": {
    "metadataLogging": true,
    "cacheLogging": true
  }
}
```

## 日志输出示例

### metadataLogging 输出

```
[21:34:17] [Render thread/INFO] [CLIENT_METADATA] Received metadata packet, dimension=minecraft:overworld, entries=1
[21:34:17] [hassium-client-1/INFO] [COMPARE_METADATA] Starting comparison: 1 entries, dimension=minecraft:overworld
[21:34:17] [hassium-client-1/INFO] [COMPARE_METADATA] MISS chunk [12, -6] (not cached)
[21:34:17] [hassium-client-1/INFO] [COMPARE_METADATA] Result: 0 hits, 0 stale, 1 misses (total 1)
[21:34:17] [Render thread/INFO] [APPLY_METADATA] Applying result: 0 hits, 1 misses, dimension=minecraft:overworld
[21:34:17] [Render thread/INFO] [APPLY_METADATA] Requesting 1 chunks from server: [[12, -6]]
```

### dispatcherLogging 输出

```
[21:34:17] [Render thread/INFO] [MAIN_DISPATCHER] Flushing client queue (queueSize=23, maxPerFrame=5)
[21:34:17] [Render thread/INFO] [MAIN_DISPATCHER] Executing callback (priority=1.7976931348623157E308, category=MISSION_CRITICAL)
[21:34:17] [Render thread/INFO] [MAIN_DISPATCHER] Callback executed successfully
[21:34:17] [Render thread/INFO] [MAIN_DISPATCHER] Flushed 5 callbacks, remaining=18
```

### compressionLogging 输出

```
[21:34:17] [Render thread/INFO] [HANDLE_COMPRESSED] Received compressed chunk data (4972 bytes)
[21:34:17] [Render thread/INFO] [HANDLE_COMPRESSED] Decoded chunk [9, 6] (originalSize=29077, algorithm=hassium:zstd, compressedSize=4942)
[21:34:17] [hassium-client-47/INFO] [HANDLE_COMPRESSED] Decompressing chunk [9, 6] in background
[21:34:17] [hassium-client-47/INFO] [HANDLE_COMPRESSED] Decompressed chunk [9, 6] (4942 -> 29077 bytes)
[21:34:17] [Render thread/INFO] [HANDLE_COMPRESSED] Applying chunk [9, 6] to world
[21:34:17] [Render thread/INFO] [HANDLE_COMPRESSED] Successfully applied chunk [9, 6] from server
```

## 性能影响

- **关闭状态** (默认): 几乎零性能开销，日志检查在配置读取后缓存
- **开启状态**: 会有一定的 I/O 开销，特别是高频日志（如 dispatcherLogging）

## 注意事项

1. **配置热更新**: 修改配置文件后需要重启游戏或使用 `/reload` 命令
2. **日志级别**: 所有调试日志使用 INFO 级别，确保在默认日志配置下可见
3. **错误日志**: ERROR 级别的日志不受开关控制，始终输出
4. **日志量**: 开启多个日志类型会产生大量输出，建议只开启需要的类型

## 推荐配置

### 生产环境（默认）

```json
{
  "debug": {
    "metadataLogging": false,
    "dispatcherLogging": false,
    "asyncLogging": false,
    "compressionLogging": false,
    "chunkApplyLogging": false,
    "networkLogging": false,
    "cacheLogging": false
  }
}
```

### 开发调试环境

```json
{
  "debug": {
    "metadataLogging": true,
    "dispatcherLogging": true,
    "asyncLogging": false,
    "compressionLogging": true,
    "chunkApplyLogging": true,
    "networkLogging": false,
    "cacheLogging": false
  }
}
```

### 性能分析环境

```json
{
  "debug": {
    "metadataLogging": false,
    "dispatcherLogging": true,
    "asyncLogging": true,
    "compressionLogging": true,
    "chunkApplyLogging": false,
    "networkLogging": false,
    "cacheLogging": false
  }
}
```
