# 轻量化网络流量与缓存监控

**开发日期**：2026-07-07

**实现状态**：✅ 已完成

---

## 1. 概述

实现零开销的网络流量监控，追踪缓存命中率、带宽节省率等关键指标。

### 1.1 核心能力

- 服务端：追踪压缩发送的字节数、压缩比、元数据开销
- 客户端：追踪接收字节数、缓存命中/未命中/过期
- 命令系统：`/hassium stats`（服务端）、`/hassiumc stats`（客户端）
- 运行时开关：`/hassium metrics on|off`

### 1.2 设计原则

| 原则 | 说明 |
|------|------|
| **零分配** | 全部使用 `AtomicLong` 计数器，无对象创建 |
| **无锁** | `addAndGet`/`incrementAndGet` 是 CAS 操作，开销约 10-20ns |
| **可关闭** | 通过配置项 `network.metricsEnabled` 控制，关闭时仅一个 boolean 读取 |
| **不侵入业务** | 埋点代码仅在关键路径加一行调用，不改变任何业务逻辑 |
| **线程安全** | 所有计数器使用 `AtomicLong`，支持多线程并发写入 |

---

## 2. 架构设计

### 2.1 服务端/客户端分离

服务端和客户端在不同 JVM 中运行，各自有独立的 `NetworkStats` 实例：

```
服务端 JVM                              客户端 JVM
┌─────────────────────┐                ┌─────────────────────┐
│ NetworkStats        │                │ NetworkStats        │
│  ├─ vanillaBytesSent│                │  ├─ vanillaBytesRecv│
│  ├─ actualBytesSent │                │  ├─ actualBytesRecv │
│  ├─ metadataSent    │                │  ├─ metadataRecv    │
│  ├─ dataReqReceived │                │  ├─ cacheHits       │
│  └─ chunksCompressed│                │  └─ chunksDecomp    │
└─────────────────────┘                └─────────────────────┘
         ↓                                       ↓
  /hassium stats                         /hassiumc stats
  (服务端命令)                            (客户端命令)
```

### 2.2 类结构

```
common/
├── metrics/
│   ├── HassiumMetrics.java          ← 接口：定义指标 getter 方法
│   ├── HassiumMetricsImpl.java      ← 实现：AtomicLong 计数器 + record 方法
│   └── NetworkStats.java            ← 门面：静态方法，volatile boolean 开关
├── command/
│   └── HassiumCommandHandler.java   ← 命令处理器（平台无关）

fabric/
├── command/
│   └── FabricHassiumCommand.java    ← Fabric 命令注册（服务端 + 客户端）

forge/
├── command/
│   └── ForgeHassiumCommand.java     ← Forge 命令注册
```

### 2.3 指标体系

#### 直接计数指标

| 指标 | 类型 | 含义 | 埋点位置 |
|------|------|------|----------|
| `vanillaBytesSent` | 服务端 | 原始区块大小（压缩前） | `ServerChunkPushManager.processPlayerQueue()` |
| `actualBytesSent` | 服务端 | 实际发送大小（压缩后） | 同上 |
| `vanillaBytesReceived` | 客户端 | 原始区块大小（从包头解码） | `ClientChunkHandler.handleCompressedChunk()` |
| `actualBytesReceived` | 客户端 | 实际接收大小（压缩数据） | 同上 |
| `metadataBytesSent` | 服务端 | 元数据包发送字节数 | `ServerChunkPushManager.sendMetadata()` |
| `metadataBytesReceived` | 客户端 | 元数据包接收字节数 | `ClientMetadataHandler.handleMetadataPacket()` |
| `dataRequestsSent` | 客户端 | 数据请求发送次数 | `ClientMetadataHandler.applyMetadataResult()` |
| `dataRequestsReceived` | 服务端 | 数据请求接收次数 | `ServerChunkPushManager.enqueueDataRequest()` |
| `cacheHits` | 客户端 | 缓存命中次数 | `ClientMetadataHandler.compareMetadata()` |
| `cacheMisses` | 客户端 | 缓存未命中次数 | 同上 |
| `cacheStale` | 客户端 | 缓存过期次数 | 同上 |
| `chunksCompressed` | 服务端 | 压缩的区块数 | `ServerChunkPushManager.processPlayerQueue()` |
| `chunksDecompressed` | 客户端 | 解压的区块数 | `ClientChunkHandler.handleCompressedChunk()` |

#### 派生指标（计算得出）

| 指标 | 公式 |
|------|------|
| 发送端带宽节省率 | `(vanillaBytesSent - actualBytesSent) / vanillaBytesSent × 100%` |
| 接收端带宽节省率 | `(vanillaBytesReceived - actualBytesReceived) / vanillaBytesReceived × 100%` |
| 缓存命中率 | `cacheHits / (cacheHits + cacheMisses + cacheStale) × 100%` |
| 压缩比 | `vanillaBytes / actualBytes` |

---

## 3. 命令系统

### 3.1 命令列表

| 命令 | 执行位置 | 权限 | 说明 |
|------|----------|------|------|
| `/hassium stats` | 服务端 | OP 2 | 显示服务端统计（发送、压缩比、元数据） |
| `/hassium stats reset` | 服务端 | OP 2 | 重置所有计数器 |
| `/hassium stats toggle` | 服务端 | OP 2 | 切换指标开关 |
| `/hassium metrics on` | 服务端 | OP 2 | 开启指标收集 |
| `/hassium metrics off` | 服务端 | OP 2 | 关闭指标收集 |
| `/hassiumc stats` | 客户端 | 无 | 显示客户端统计（接收、缓存命中率） |

> **注意**：指标关闭时，`/hassium stats` 和 `/hassiumc stats` 命令不可用，避免显示全 0 造成歧义。

### 3.2 输出示例

**服务端** `/hassium stats`：
```
=== Hassium 服务端统计 ===
发送: 4.8 MB (原版 36.4 MB) — 节省 86.7%
压缩比: 7.54:1
元数据发送: 38.7 KB
数据请求接收: 1159
区块压缩: 1159
```

**客户端** `/hassiumc stats`：
```
=== Hassium 客户端统计 ===
接收: 4.6 MB (原版 35.2 MB) — 节省 86.9%
压缩比: 7.65:1
缓存命中率: 73.2% (命中 142, 未命中 38, 过期 14)
元数据接收: 36.2 KB
数据请求发送: 52
区块解压: 1107
```

### 3.3 命令与指标的联动

```
metricsEnabled = true（默认）
  → /hassium stats 可用，显示统计数据
  → /hassiumc stats 可用，显示统计数据
  → 埋点代码正常执行

metricsEnabled = false
  → /hassium stats 不可用（命令不注册）
  → /hassiumc stats 不可用（命令不注册）
  → 埋点代码直接 return（~5ns 开销）
  → 可通过 /hassium metrics on 重新开启
```

---

## 4. 配置项

在 `HassiumConfig.NetworkConfig` 中：

```json
{
  "network": {
    "metricsEnabled": true,
    "metricsReportIntervalTicks": 0
  }
}
```

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `metricsEnabled` | boolean | `true` | 是否启用指标收集 |
| `metricsReportIntervalTicks` | int | `0` | 定期报告间隔 ticks（0=不自动报告） |

### 4.1 配置兼容性

新增字段通过 `CommentedJsonWriter.fromJson()` 的深度合并逻辑自动填充默认值。已有配置文件升级时，缺失的 `metricsEnabled` 字段会自动设为 `true`。

---

## 5. 数据流与埋点位置

```
服务端                                              客户端
──────                                              ──────
ChunkHolder.broadcast()                             收到 ChunkMetadataS2C
ServerPlayer.trackChunk()                               ↓
    ↓                                               [埋点6] recordMetadataReceived()
发送元数据                                              ↓
[埋点2] recordMetadataSent()                        compareMetadata()
    ↓                                               [埋点7] recordCacheHit/Miss/Stale
收到 ChunkDataRequestC2S                                ↓
[埋点3] recordDataRequestReceived()                 ┌───────┴───────┐
    ↓                                           缓存命中        缓存未命中
processPlayerQueue()                                ↓               ↓
  serializeChunk() → 原始大小                    loadQueue      发送 DataRequest
  compressChunkData() → 压缩后                   [埋点8] recordDataRequestSent()
  [埋点1] recordChunkSent(original, compressed)
    ↓
  sendCompressedChunk()
    ↓
                                    ────── 网络传输 ──────
                                                    ↓
                                            handleCompressedChunk()
                                            [埋点5] recordChunkReceived(original, compressed)
                                                    ↓
                                                decompress + apply
```

---

## 6. 实现细节

### 6.1 NetworkStats 门面

```java
public class NetworkStats {
    private static volatile boolean enabled = true;
    private static final HassiumMetricsImpl metrics = new HassiumMetricsImpl();

    public static void recordChunkSent(int originalSize, int compressedSize) {
        if (!enabled) return;  // 关闭时 ~5ns
        metrics.recordVanillaBytesSent(originalSize);
        metrics.recordActualBytesSent(compressedSize);
        metrics.incrementChunksCompressed();
    }
    // ... 其他 record 方法类似
}
```

### 6.2 配置同步

```java
// CommonClass.init() 中
configService.loadConfig();
NetworkStats.setEnabled(configService.isMetricsEnabled());
```

### 6.3 配置深度合并

`CommentedJsonWriter.fromJson()` 先解析为 JsonObject，再与 `HassiumConfig.DEFAULT` 深度合并，确保新增字段有正确默认值：

```java
JsonObject loaded = gson.fromJson(cleanJson, JsonObject.class);
JsonObject defaults = gson.toJsonTree(HassiumConfig.DEFAULT).getAsJsonObject();
JsonObject merged = deepMerge(defaults, loaded);  // 缺失字段从 defaults 补充
return gson.fromJson(merged, HassiumConfig.class);
```

---

## 7. 文件变更清单

| 文件 | 变更类型 | 说明 |
|------|----------|------|
| `common/.../metrics/HassiumMetrics.java` | 修改 | 新增 10 个 getter + 4 个派生指标 |
| `common/.../metrics/HassiumMetricsImpl.java` | 修改 | 新增 10 个 AtomicLong + record 方法 |
| `common/.../metrics/NetworkStats.java` | **新建** | 轻量门面，静态方法，开关控制 |
| `common/.../command/HassiumCommandHandler.java` | **新建** | 命令处理器（平台无关） |
| `common/.../network/ServerChunkPushManager.java` | 修改 | 3 处埋点 |
| `common/.../network/ClientChunkHandler.java` | 修改 | 1 处埋点 |
| `common/.../network/ClientMetadataHandler.java` | 修改 | 4 处埋点 |
| `common/.../config/HassiumConfig.java` | 修改 | 新增 metricsEnabled 配置 |
| `common/.../config/HassiumConfigService.java` | 修改 | 新增 getter |
| `common/.../config/CommentedJsonWriter.java` | 修改 | 深度合并逻辑 + 注释 |
| `common/.../CommonClass.java` | 修改 | 配置同步 |
| `fabric/.../command/FabricHassiumCommand.java` | **新建** | 服务端 + 客户端命令注册 |
| `fabric/.../HassiumClientMod.java` | 修改 | 注册客户端命令 |
| `forge/.../command/ForgeHassiumCommand.java` | **新建** | 服务端命令注册 |

---

## 8. 已知限制

1. **服务端/客户端指标分离**：在多人游戏中，`/hassium stats` 只显示服务端指标，`/hassiumc stats` 只显示客户端指标。单人游戏中两者共享同一 JVM，数据合并显示。

2. **原版等价字节的精度**：`vanillaBytesSent` 使用序列化后的 `ClientboundLevelChunkWithLightPacket` 大小，与实际原版传输可能有微小差异（光照剥离等因素）。

3. **元数据大小为估算值**：`metadataBytesReceived` 使用 `dimension.length + entries.size() * 18 + 8` 估算，未计算网络帧开销。

---

## 9. 后续扩展

- **定期日志报告**：`metricsReportIntervalTicks > 0` 时自动输出统计
- **每玩家统计**：独立计数器，支持 `/hassium stats <player>`
- **Prometheus 导出**：通过 JMX MBean 暴露指标
- **实时 HUD**：F3 调试屏幕叠加层
