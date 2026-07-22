# 网络功能完整实现设计

日期：2026-07-22

## 1. 目标

完整实现服务端包聚合与管线级 ZSTD 压缩，解决以下问题：

1. NeoForge/Forge 缺少聚合初始化
2. 应用层聚合不检查配置开关
3. 聚合参数硬编码
4. 管线级 ZSTD 未接入（`ZstdPipelineSwitcher.switchToZstd()` 零调用者）
5. ZSTD 协商未触发（`ZstdNegotiationTracker.markNegotiated()` 零调用者）

## 2. 架构总览

```
Connection.send()
    │
    ├─ 管线级 ZSTD 已启用？
    │   ├─ 否 → 原版 Zlib 压缩
    │   └─ 是 → 是聚合包？（ByteBuf 标记）
    │       ├─ 是 → 跳过管线压缩（内部已有字典 ZSTD）
    │       └─ 否 → 管线级 ZSTD 压缩
    │
    └─ MixinConnection 拦截
        └─ 聚合已启用？
            ├─ 否 → 直接发送
            └─ 是 → HassiumAggregationManager 缓冲 + 字典 ZSTD
```

### 模式矩阵

| 场景 | 管线级 ZSTD | 应用层聚合 | 效果 |
|------|-------------|-----------|------|
| 聚合开启 + 管线开启 | 非聚合包走管线 ZSTD | 聚合包内部字典 ZSTD | 全覆盖，聚合包跳过管线 |
| 聚合关闭 + 管线开启 | 所有包走管线 ZSTD | 无 | 小包也受益 |
| 管线关闭 | 原版 Zlib | 聚合包内部字典 ZSTD | 当前行为 |
| 客户端不支持 ZSTD | 自动降级 Zlib | 自动禁用 | 向后兼容 |

## 3. 握手与协商流程

### 3.1 流程

```
客户端 → 服务端: HandshakeC2S
    - protocolVersion, modVersion
    - supportedAlgorithms
    - clientCacheSupported, chunkRevisionSupported, scheme127Supported
    - globalPacketCompressionSupported, compactHeaderSupported

服务端处理:
    - PlayerCompressionTracker.enableCompression(player)
    - ServerChunkPushManager.resyncTrackedChunks(player)
    - 计算 useGlobalCompression = serverConfig && clientSupport
    - 计算 useCompactHeader = serverConfig && clientSupport

    if (useGlobalCompression):
        - DictionaryManager.init()
        - sendDictionarySyncPacket(player)
        - IndexSyncManager.initializeServerIndex()
        - sendIndexSyncPacket(player)
        - HassiumConnectionRegistry.markPending(connection)  // 新增
        - HassiumAggregationManager.init()                   // 新增
        - ZstdNegotiationTracker.markNegotiated(channel)     // 新增
        - 超时安全降级（5s）

服务端 → 客户端: HandshakeS2C
    - protocolVersion, accepted
    - useGlobalCompression, useCompactHeader

客户端处理:
    - 收到 IndexSync → HassiumConnectionRegistry.markEnabled(connection)
    - 发送 CompressionReadyPayload
```

### 3.2 修复点

**NeoForge/Forge `handleHandshake()` 补全：**
- 新增 `HassiumConnectionRegistry.markPending(connection)`
- 新增 `HassiumAggregationManager.init()`
- 新增 `ZstdNegotiationTracker.markNegotiated(channel)`

**Fabric 已完整，仅需新增 `ZstdNegotiationTracker.markNegotiated(channel)`**

## 4. 管线级 ZSTD

### 4.1 切换时机

在 `setupCompression()` 被调用时（Play 阶段），如果 ZSTD 已协商，切换管线：

```java
// MixinConnectionSetupCompression 改造
@Inject(method = "setupCompression", at = @At("HEAD"), cancellable = true)
private void hassium$switchToZstdWhenNegotiated(int threshold, boolean validate, CallbackInfo ci) {
    if (ZstdNegotiationTracker.isZstdNegotiated(this.channel)) {
        int level = HassiumConfigService.getInstance().getCompressionLevel();
        ZstdPipelineSwitcher.switchToZstd(this.channel, threshold, level);
        ci.cancel();
    }
}
```

### 4.2 聚合包跳过管线压缩

**ByteBuf 标记方案：**

```java
// 新增：HassiumPipelineAttributes.java
public class HassiumPipelineAttributes {
    public static final AttributeKey<Boolean> SKIP_PIPELINE_COMPRESSION = 
        AttributeKey.valueOf("hassium:skip_pipeline_compression");
}
```

**聚合包发送时标记：**

```java
// HassiumAggregationManager.flushInternal() 中
FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
aggregationPacket.encode(buf);
buf.attr(HassiumPipelineAttributes.SKIP_PIPELINE_COMPRESSION).set(true);
sender.send(connection, buf);
```

**管线编码器检查：**

```java
// 新增：SkipAwareZstdEncoder.java
public class SkipAwareZstdEncoder extends ZstdContextEncoder {
    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) {
        Boolean skip = msg.attr(HassiumPipelineAttributes.SKIP_PIPELINE_COMPRESSION).get();
        if (Boolean.TRUE.equals(skip)) {
            out.add(msg.retain());
            return;
        }
        super.encode(ctx, msg, out);
    }
}
```

### 4.3 ZstdPipelineSwitcher 改造

```java
public static void switchToZstd(Channel channel, int threshold, int level) {
    // ... 移除原版 Handler ...
    
    boolean magicless = config.isMagiclessZstd();
    
    // 使用 SkipAwareZstdEncoder（支持聚合包跳过）
    addHandlerBefore(pipeline, "decoder", DECOMPRESS_HANDLER_NAME,
            new ZstdContextDecoder(threshold, true, magicless));
    addHandlerBefore(pipeline, "encoder", COMPRESS_HANDLER_NAME,
            new SkipAwareZstdEncoder(threshold, level, magicless));
    
    // 紧凑包头在聚合包内部实现，无需独立 Handler
    LOGGER.info("Installed ZSTD pipeline (level={}, threshold={}, magicless={})",
            level, threshold, magicless);
}
```

## 5. 配置开关与参数

### 5.1 应用层聚合检查配置

```java
// MixinConnection.hassium$tryAggregate() 增加检查
if (!HassiumConfigService.getInstance().isPacketAggregationEnabled()) {
    return;
}
```

### 5.2 聚合参数从配置读取

```java
// HassiumAggregationManager 改造
private static int minBatchPackets;
private static int maxWaitCycles;
private static int maxAggregationSize;

public static synchronized void init() {
    if (initialized) return;
    
    HassiumConfigService config = HassiumConfigService.getInstance();
    minBatchPackets = config.getAggregationMinBatchSize();
    maxWaitCycles = (int) (config.getAggregationMaxWaitTimeMs() / FLUSH_PERIOD_MS);
    maxAggregationSize = config.getAggregationMaxSize();
    
    // ... 初始化定时器 ...
}
```

### 5.3 新增配置读取方法

```java
// HassiumConfigService 新增
public int getAggregationMinBatchSize() {
    return config.serverNetwork().aggregationMinBatchSize();
}

public long getAggregationMaxWaitTimeMs() {
    return config.serverNetwork().aggregationMaxWaitTimeMs();
}

public int getAggregationMaxSize() {
    return config.serverNetwork().aggregationMaxSize();
}
```

### 5.4 聚合包大小检查

```java
// HassiumAggregationManager.flushInternal() 增加大小检查
private static void flushInternal(Connection connection, List<AggregatedSubPacket> packets) {
    // ... 复制并清空缓冲区 ...
    
    // 检查聚合包大小
    int estimatedSize = estimateAggregationSize(sendPackets);
    if (estimatedSize > maxAggregationSize) {
        // 分批发送
        List<List<AggregatedSubPacket>> batches = splitBatches(sendPackets, maxAggregationSize);
        for (List<AggregatedSubPacket> batch : batches) {
            sendAggregation(connection, batch);
        }
    } else {
        sendAggregation(connection, sendPackets);
    }
}
```

## 6. 断开连接清理

```java
// 连接断开时清理
public static void onDisconnect(Connection connection) {
    HassiumConnectionRegistry.markDisabled(connection);
    HassiumAggregationManager.discardConnection(connection);
    
    Channel channel = getConnectionChannel(connection);
    if (channel != null) {
        ZstdNegotiationTracker.removeChannel(channel);
    }
}
```

## 7. 代码清理

### 7.1 删除

- `AggregatedZstdEncoder` / `AggregatedZstdDecoder`：被 `SkipAwareZstdEncoder` 替代
- `PacketAggregator`：管线级聚合不再需要

### 7.2 保留

- `ZstdPipelineSwitcher`：接入后不再死代码
- `ZstdNegotiationTracker`：接入后不再死代码
- `MixinConnectionSetupCompression`：改造为切换而非阻止

### 7.3 更新配置注释

```java
// HassiumConfig.ServerNetworkConfig 注释更新
// === 全局包压缩（管线级 ZSTD 替换 Zlib）===
boolean globalPacketCompression,
// === 包聚合（应用层，MixinConnection 拦截）===
boolean enablePacketAggregation,
// === 紧凑包头（聚合包内部 VarInt 索引）===
boolean enableCompactHeader,
```

## 8. 关键类变更

| 类 | 变更 |
|----|------|
| `MixinConnection` | 新增 `isPacketAggregationEnabled()` 检查 |
| `MixinConnectionSetupCompression` | 改造：切换到 ZSTD 而非阻止 |
| `HassiumAggregationManager` | 参数从配置读取，新增大小检查 |
| `HassiumAggregationManager.flushInternal()` | 标记 ByteBuf 跳过管线压缩 |
| `ZstdPipelineSwitcher.switchToZstd()` | 使用 SkipAwareZstdEncoder |
| `NeoForgeNetworkManager.handleHandshake()` | 补全 markPending + init + markNegotiated |
| `FabricNetworkManager` | 新增 markNegotiated |

**新增类：**
- `HassiumPipelineAttributes`：ByteBuf 属性键
- `SkipAwareZstdEncoder`：支持跳过的管线编码器
- `SkipAwareZstdDecoder`：对应的解码器

## 9. 测试验证

1. **Fabric 服务端 + Fabric 客户端**：聚合 + 管线 ZSTD 正常工作
2. **NeoForge 服务端 + NeoForge 客户端**：聚合 + 管线 ZSTD 正常工作
3. **服务端开启 ZSTD + 客户端未装 Hassium**：自动降级到 Zlib
4. **配置关闭聚合**：所有包走管线 ZSTD
5. **配置关闭管线压缩**：聚合包仍走内部字典 ZSTD
6. **断开连接**：资源正确清理
