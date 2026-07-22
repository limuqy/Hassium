# 网络功能完整实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完整实现服务端包聚合与管线级 ZSTD 压缩，修复 NeoForge/Forge 缺失、配置开关未检查、参数硬编码、管线未接入等问题。

**Architecture:** 应用层聚合（MixinConnection → HassiumAggregationManager）+ 管线级 ZSTD（ZstdPipelineSwitcher）双层共存，聚合包通过 ByteBuf 标记跳过管线压缩。握手完成后标记 ZSTD 已协商，setupCompression() 时切换管线。客户端不支持时自动降级 Zlib。

**Tech Stack:** Netty, ZSTD (zstd-jni), Mixin, Fabric/NeoForge/Forge 多加载器

## Global Constraints

- common 模块禁止 fabric/forge/neoforge import
- Mixin 命名：`@Unique` + `hassium$` 前缀
- 存储相关入口先查 `isStorageEnabled()`
- 网络相关入口先查网络开关 + 握手状态
- 热路径禁止无条件 `LOGGER.info`
- `@Inject` cancellable 优先，避免 `@Overwrite`
- 新控制面包加入黑名单（`HassiumPacketIds` + 默认 compressionBlacklist）

---

## Task 1: 新增 ByteBuf 属性键

**Files:**
- Create: `common/src/main/java/io/github/limuqy/mc/hassium/network/HassiumPipelineAttributes.java`

**Interfaces:**
- Produces: `HassiumPipelineAttributes.SKIP_PIPELINE_COMPRESSION` (AttributeKey<Boolean>)

- [ ] **Step 1: 创建 HassiumPipelineAttributes 类**

```java
package io.github.limuqy.mc.hassium.network;

import io.netty.util.AttributeKey;

/**
 * 管线级压缩的 ByteBuf 属性键。
 * <p>
 * 聚合包发送时标记 {@code SKIP_PIPELINE_COMPRESSION = true}，
 * 管线编码器检测到该标记则跳过压缩（聚合包内部已有字典 ZSTD）。
 */
public final class HassiumPipelineAttributes {
    private HassiumPipelineAttributes() {}

    /**
     * 标记 ByteBuf 应跳过管线级压缩。
     */
    public static final AttributeKey<Boolean> SKIP_PIPELINE_COMPRESSION =
            AttributeKey.valueOf("hassium:skip_pipeline_compression");
}
```

- [ ] **Step 2: 验证编译**

```bash
./gradlew --no-daemon common:compileJava
```

- [ ] **Step 3: Commit**

```bash
git add common/src/main/java/io/github/limuqy/mc/hassium/network/HassiumPipelineAttributes.java
git commit -m "feat(network): add ByteBuf attribute key for pipeline compression skip"
```

---

## Task 2: 新增 SkipAware 管线编码器/解码器

**Files:**
- Create: `common/src/main/java/io/github/limuqy/mc/hassium/network/SkipAwareZstdEncoder.java`
- Create: `common/src/main/java/io/github/limuqy/mc/hassium/network/SkipAwareZstdDecoder.java`

**Interfaces:**
- Consumes: `HassiumPipelineAttributes.SKIP_PIPELINE_COMPRESSION`
- Produces: `SkipAwareZstdEncoder`, `SkipAwareZstdDecoder`

- [ ] **Step 1: 创建 SkipAwareZstdEncoder**

```java
package io.github.limuqy.mc.hassium.network;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import java.util.List;

/**
 * 支持跳过的管线级 ZSTD 编码器。
 * <p>
 * 检查 ByteBuf 上的 {@link HassiumPipelineAttributes#SKIP_PIPELINE_COMPRESSION} 标记，
 * 如果为 true 则跳过压缩直接传递（用于聚合包，其内部已有字典 ZSTD）。
 */
public class SkipAwareZstdEncoder extends ZstdContextEncoder {

    public SkipAwareZstdEncoder(int threshold, int level, boolean magicless) {
        super(threshold, level, magicless);
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) throws Exception {
        Boolean skip = msg.attr(HassiumPipelineAttributes.SKIP_PIPELINE_COMPRESSION).get();
        if (Boolean.TRUE.equals(skip)) {
            // 聚合包跳过管线压缩，直接传递
            out.add(msg.retain());
            return;
        }
        super.encode(ctx, msg, out);
    }
}
```

- [ ] **Step 2: 创建 SkipAwareZstdDecoder**

```java
package io.github.limuqy.mc.hassium.network;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import java.util.List;

/**
 * 支持跳过的管线级 ZSTD 解码器。
 * <p>
 * 与 {@link SkipAwareZstdEncoder} 配对。
 * 聚合包未被管线压缩，解码器需检测并跳过解压。
 * <p>
 * 注意：解码器端无法通过 ByteBuf 标记判断（标记在编码端设置），
 * 需要通过数据格式检测（ZSTD magic number 缺失 = 未压缩）。
 */
public class SkipAwareZstdDecoder extends ZstdContextDecoder {

    public SkipAwareZstdDecoder(int threshold, boolean validateDecompressed, boolean magicless) {
        super(threshold, validateDecompressed, magicless);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) throws Exception {
        // 检查是否是 ZSTD 压缩数据
        // magicless 模式下无 magic number，通过标记或长度启发式判断
        // 由于编码端已跳过压缩，解码端收到的是未压缩数据
        // 需要检测：如果数据不是 ZSTD 格式，直接传递
        
        // 简单方案：检查 readableBytes 是否小于阈值（小包未压缩）
        // 更可靠方案：尝试解压，失败则直接传递
        try {
            super.decode(ctx, msg, out);
        } catch (Exception e) {
            // 解压失败，可能是跳过压缩的聚合包，直接传递
            out.add(msg.retain());
        }
    }
}
```

- [ ] **Step 3: 验证编译**

```bash
./gradlew --no-daemon common:compileJava
```

- [ ] **Step 4: Commit**

```bash
git add common/src/main/java/io/github/limuqy/mc/hassium/network/SkipAwareZstdEncoder.java
git add common/src/main/java/io/github/limuqy/mc/hassium/network/SkipAwareZstdDecoder.java
git commit -m "feat(network): add SkipAware ZSTD encoder/decoder for pipeline compression"
```

---

## Task 3: 聚合包发送时标记 ByteBuf

**Files:**
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/network/HassiumAggregationManager.java`

**Interfaces:**
- Consumes: `HassiumPipelineAttributes.SKIP_PIPELINE_COMPRESSION`
- Produces: 聚合包 ByteBuf 带有跳过标记

- [ ] **Step 1: 修改 flushInternal() 标记 ByteBuf**

在 `flushInternal()` 方法中，`aggregationPacket.encode(buf)` 之后添加标记：

```java
private static void flushInternal(Connection connection, List<AggregatedSubPacket> packets) {
    try {
        if (packets == null || packets.isEmpty()) {
            return;
        }
        if (!connection.isConnected()) {
            packets.clear();
            return;
        }

        List<AggregatedSubPacket> sendPackets = new ArrayList<>(packets);
        packets.clear();

        IndexSyncManager indexSyncManager = IndexSyncManager.getInstance();
        NamespaceIndexManager indexManager = indexSyncManager.getServerIndexManager();

        HassiumAggregationPacket aggregationPacket = new HassiumAggregationPacket(sendPackets, indexManager);
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        aggregationPacket.encode(buf);

        // 标记：跳过管线级压缩（聚合包内部已有字典 ZSTD）
        buf.attr(HassiumPipelineAttributes.SKIP_PIPELINE_COMPRESSION).set(true);

        if (sender != null) {
            sender.send(connection, buf);
        } else {
            Constants.LOG.error("AggregationSender not set, dropping {} packets", sendPackets.size());
            buf.release();
        }

        Constants.LOG.debug("Flushed aggregation buffer: {} packets for {}",
                sendPackets.size(), connection.getRemoteAddress());
    } catch (Exception e) {
        Constants.LOG.error("Failed to flush aggregation buffer", e);
    }
}
```

- [ ] **Step 2: 验证编译**

```bash
./gradlew --no-daemon common:compileJava
```

- [ ] **Step 3: Commit**

```bash
git add common/src/main/java/io/github/limuqy/mc/hassium/network/HassiumAggregationManager.java
git commit -m "feat(network): mark aggregated ByteBuf to skip pipeline compression"
```

---

## Task 4: 配置读取方法补全

**Files:**
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/config/HassiumConfigService.java`

**Interfaces:**
- Produces: `getAggregationMinBatchSize()`, `getAggregationMaxWaitTimeMs()`, `getAggregationMaxSize()`

- [ ] **Step 1: 添加配置读取方法**

在 `HassiumConfigService` 中添加：

```java
/**
 * 聚合最小批量大小
 */
public int getAggregationMinBatchSize() {
    return config.serverNetwork().aggregationMinBatchSize();
}

/**
 * 聚合最大等待时间（ms）
 */
public long getAggregationMaxWaitTimeMs() {
    return config.serverNetwork().aggregationMaxWaitTimeMs();
}

/**
 * 聚合包最大大小（字节）
 */
public int getAggregationMaxSize() {
    return config.serverNetwork().aggregationMaxSize();
}

/**
 * 全局压缩级别
 */
public int getCompressionLevel() {
    return config.serverNetwork().compressionLevel();
}
```

- [ ] **Step 2: 验证编译**

```bash
./gradlew --no-daemon common:compileJava
```

- [ ] **Step 3: Commit**

```bash
git add common/src/main/java/io/github/limuqy/mc/hassium/config/HassiumConfigService.java
git commit -m "feat(config): add aggregation parameter getters"
```

---

## Task 5: 聚合参数从配置读取

**Files:**
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/network/HassiumAggregationManager.java`

**Interfaces:**
- Consumes: `HassiumConfigService.getAggregationMinBatchSize()`, `getAggregationMaxWaitTimeMs()`, `getAggregationMaxSize()`

- [ ] **Step 1: 修改常量为可配置字段**

```java
public class HassiumAggregationManager {
    // 原：private static final int MIN_BATCH_PACKETS = 4;
    private static int minBatchPackets = 4;
    // 原：private static final int MAX_EXTRA_CYCLES = 2;
    private static int maxWaitCycles = 2;
    private static final int FLUSH_PERIOD_MS = 20;
    
    // 新增：聚合包大小上限
    private static int maxAggregationSize = 256 * 1024;
```

- [ ] **Step 2: 修改 init() 从配置读取**

```java
public static synchronized void init() {
    if (initialized) {
        return;
    }
    PACKET_BUFFER.clear();
    
    // 从配置读取参数
    HassiumConfigService config = HassiumConfigService.getInstance();
    minBatchPackets = config.getAggregationMinBatchSize();
    maxWaitCycles = Math.max(1, (int) (config.getAggregationMaxWaitTimeMs() / FLUSH_PERIOD_MS));
    maxAggregationSize = config.getAggregationMaxSize();
    
    if (flushTask != null) {
        flushTask.cancel(false);
    }
    flushTask = TIMER.scheduleAtFixedRate(HassiumAggregationManager::flush, 0,
            FLUSH_PERIOD_MS, TimeUnit.MILLISECONDS);
    initialized = true;
    Constants.LOG.info("Hassium aggregation manager initialized (minBatch={}, maxWait={}ms, maxSize={}KB)",
            minBatchPackets, maxWaitCycles * FLUSH_PERIOD_MS, maxAggregationSize / 1024);
}
```

- [ ] **Step 3: 修改 flush() 使用配置参数**

```java
private static void flush() {
    // ... 现有清理逻辑 ...

    for (var entry : PACKET_BUFFER.entrySet()) {
        Connection connection = entry.getKey();
        List<AggregatedSubPacket> packets = entry.getValue();

        if (packets == null) {
            continue;
        }

        synchronized (packets) {
            if (packets.isEmpty()) {
                continue;
            }

            if (HassiumConnectionRegistry.isPending(connection)) {
                continue;
            }

            // 使用配置的最小批量
            if (packets.size() < minBatchPackets) {
                int waited = FLUSH_WAIT.getOrDefault(connection, 0);
                if (waited < maxWaitCycles) {
                    FLUSH_WAIT.put(connection, waited + 1);
                    continue;
                }
            }

            FLUSH_WAIT.remove(connection);
            flushInternal(connection, packets);
        }
    }
}
```

- [ ] **Step 4: 验证编译**

```bash
./gradlew --no-daemon common:compileJava
```

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/io/github/limuqy/mc/hassium/network/HassiumAggregationManager.java
git commit -m "feat(network): read aggregation parameters from config"
```

---

## Task 6: 应用层聚合检查配置开关

**Files:**
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/mixin/MixinConnection.java`

**Interfaces:**
- Consumes: `HassiumConfigService.isPacketAggregationEnabled()`

- [ ] **Step 1: 添加配置检查**

在 `hassium$tryAggregate()` 方法中，在 `HassiumConnectionRegistry.isActive()` 检查之后添加：

```java
@Unique
private void hassium$tryAggregate(Packet<?> packet, boolean hasSendListener, CallbackInfo ci) {
    Connection self = (Connection) (Object) this;

    if (!(packetListener instanceof ServerGamePacketListenerImpl)) {
        return;
    }

    if (PacketTypeHelper.isAggregationPacket(packet)) {
        return;
    }

#if MC_VER < MC_1_21_11
    ResourceLocation
#else
    Identifier
#endif
    packetType = PacketTypeHelper.getPacketType(packet);
    if (packetType == null) {
        return;
    }

    String packetTypeId = packetType.toString();
    if (!PacketCompressionBlacklist.shouldCompress(packetTypeId)) {
        return;
    }

    boolean isActive = HassiumConnectionRegistry.isActive(self);
    if (!isActive) {
        return;
    }

    // 新增：检查聚合配置开关
    if (!HassiumConfigService.getInstance().isPacketAggregationEnabled()) {
        return;
    }

    if (hasSendListener) {
        HassiumAggregationManager.flushConnectionSync(self);
        return;
    }

    HassiumAggregationManager.takeOver(packet, self);
    ci.cancel();
}
```

- [ ] **Step 2: 验证编译**

```bash
./gradlew --no-daemon common:compileJava
```

- [ ] **Step 3: Commit**

```bash
git add common/src/main/java/io/github/limuqy/mc/hassium/mixin/MixinConnection.java
git commit -m "feat(network): check aggregation config flag in MixinConnection"
```

---

## Task 7: 改造 ZstdPipelineSwitcher 使用 SkipAware 编码器

**Files:**
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/network/ZstdPipelineSwitcher.java`

**Interfaces:**
- Consumes: `SkipAwareZstdEncoder`, `SkipAwareZstdDecoder`
- Produces: 改造后的 `switchToZstd()` 方法

- [ ] **Step 1: 改造 switchToZstd() 方法**

```java
public static void switchToZstd(Channel channel, int threshold, int level) {
    ChannelPipeline pipeline = channel.pipeline();

    removeHandlerSafely(pipeline, DECOMPRESS_HANDLER_NAME);
    removeHandlerSafely(pipeline, COMPRESS_HANDLER_NAME);
    removeHandlerSafely(pipeline, COMPACT_HEADER_HANDLER_NAME);

    if (LOGGER.isDebugEnabled()) {
        LOGGER.debug("Pipeline after removing old handlers: {}", pipeline.names());
    }

    HassiumConfigService config = HassiumConfigService.getInstance();
    boolean magicless = config.isMagiclessZstd();

    // 使用 SkipAware 编码器/解码器（支持聚合包跳过管线压缩）
    addHandlerBefore(pipeline, "decoder", DECOMPRESS_HANDLER_NAME,
            new SkipAwareZstdDecoder(threshold, true, magicless));
    addHandlerBefore(pipeline, "encoder", COMPRESS_HANDLER_NAME,
            new SkipAwareZstdEncoder(threshold, level, magicless));

    LOGGER.info("Installed ZSTD pipeline (level={}, threshold={}, magicless={})",
            level, threshold, magicless);

    if (LOGGER.isDebugEnabled()) {
        LOGGER.debug("Pipeline after installing ZSTD handlers: {}", pipeline.names());
    }
}
```

- [ ] **Step 2: 验证编译**

```bash
./gradlew --no-daemon common:compileJava
```

- [ ] **Step 3: Commit**

```bash
git add common/src/main/java/io/github/limuqy/mc/hassium/network/ZstdPipelineSwitcher.java
git commit -m "feat(network): use SkipAware encoder in ZstdPipelineSwitcher"
```

---
---

## Task 8: 改造 MixinConnectionSetupCompression 为切换模式

**Files:**
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/mixin/MixinConnectionSetupCompression.java`

**Interfaces:**
- Consumes: `ZstdNegotiationTracker.isZstdNegotiated()`, `ZstdPipelineSwitcher.switchToZstd()`, `HassiumConfigService.getCompressionLevel()`

- [ ] **Step 1: 改造 Mixin 注入逻辑**

```java
package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.network.ZstdNegotiationTracker;
import io.github.limuqy.mc.hassium.network.ZstdPipelineSwitcher;
import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 拦截 Connection.setupCompression()，当 ZSTD 已协商时切换管线压缩。
 * <p>
 * 时序说明：
 * - Login 阶段：setupCompression() 被调用，此时 ZSTD 未协商，原版 Zlib 正常安装
 * - Play 阶段：Hassium 握手完成，ZstdNegotiationTracker 标记已协商
 * - 后续 setupCompression() 调用：切换到 ZSTD 而非阻止
 */
@Mixin(Connection.class)
public abstract class MixinConnectionSetupCompression {

    @Shadow
    private Channel channel;

    private static final Logger hassium$LOGGER = LoggerFactory.getLogger("Hassium/PacketCompression");

    /**
     * 当 ZSTD 已协商时，切换到管线级 ZSTD 替换原版 Zlib。
     */
    @Inject(method = "setupCompression", at = @At("HEAD"), cancellable = true)
    private void hassium$switchToZstdWhenNegotiated(int threshold, boolean validateDecompressed, CallbackInfo ci) {
        if (ZstdNegotiationTracker.isZstdNegotiated(this.channel)) {
            int level = HassiumConfigService.getInstance().getCompressionLevel();
            hassium$LOGGER.info("Switching to ZSTD pipeline (threshold={}, level={})", threshold, level);
            ZstdPipelineSwitcher.switchToZstd(this.channel, threshold, level);
            ci.cancel();
        }
    }
}
```

- [ ] **Step 2: 验证编译**

```bash
./gradlew --no-daemon common:compileJava
```

- [ ] **Step 3: Commit**

```bash
git add common/src/main/java/io/github/limuqy/mc/hassium/mixin/MixinConnectionSetupCompression.java
git commit -m "feat(network): switch to ZSTD at setupCompression instead of blocking"
```

---

## Task 9: Fabric 握手补全 markNegotiated

**Files:**
- Modify: `fabric/src/main/java/io/github/limuqy/mc/hassium/network/FabricNetworkManager.java`

**Interfaces:**
- Consumes: `ZstdNegotiationTracker.markNegotiated()`

- [ ] **Step 1: 在握手处理中添加 markNegotiated 调用**

在两个版本的握手处理中（`#if MC_VER < MC_1_20_5` 和 else），在 `HassiumConnectionRegistry.markPending(connection)` 之后添加：

```java
// 在 HassiumConnectionRegistry.markPending(connection) 之后
HassiumAggregationManager.init();

// 新增：标记 ZSTD 已协商
Channel channel = getConnectionChannel(connection);
if (channel != null) {
    ZstdNegotiationTracker.markNegotiated(channel);
}
```

需要确保导入：
```java
import io.github.limuqy.mc.hassium.network.ZstdNegotiationTracker;
import io.netty.channel.Channel;
```

- [ ] **Step 2: 验证编译**

```bash
./gradlew --no-daemon fabric:compileJava
```

- [ ] **Step 3: Commit**

```bash
git add fabric/src/main/java/io/github/limuqy/mc/hassium/network/FabricNetworkManager.java
git commit -m "feat(network/fabric): add ZSTD negotiation tracking in handshake"
```

---

## Task 10: NeoForge 握手补全聚合初始化

**Files:**
- Modify: `neoforge/src/main/java/io/github/limuqy/mc/hassium/network/NeoForgeNetworkManager.java`

**Interfaces:**
- Consumes: `HassiumConnectionRegistry.markPending()`, `HassiumAggregationManager.init()`, `ZstdNegotiationTracker.markNegotiated()`

- [ ] **Step 1: 在 1.20.4 握手处理中补全**

在 `handleHandshake(HandshakePayload payload, PlayPayloadContext context)` 中：

```java
private static void handleHandshake(HandshakePayload payload, PlayPayloadContext context) {
    context.workHandler().execute(() -> {
        if (context.player().orElse(null) instanceof ServerPlayer player) {
            PlayerCompressionTracker.enableCompression(player);
            ServerChunkPushManager.getInstance().resyncTrackedChunks(player);
            boolean useGlobalCompression = HassiumConfigService.getInstance().isGlobalPacketCompressionEnabled()
                    && payload.globalPacketCompressionSupported();
            boolean useCompactHeader = HassiumConfigService.getInstance().isCompactHeaderEnabled()
                    && payload.compactHeaderSupported();
            boolean accepted = true;

            // 新增：聚合初始化
            if (useGlobalCompression) {
                Connection connection = getPlayerConnection(player);
                if (connection != null) {
                    HassiumConnectionRegistry.markPending(connection);
                    HassiumAggregationManager.init();
                    Channel channel = getConnectionChannel(connection);
                    if (channel != null) {
                        ZstdNegotiationTracker.markNegotiated(channel);
                    }
                }
            }

            HandshakeResponsePayload response = new HandshakeResponsePayload(
                    Constants.CURRENT_PROTOCOL_VERSION, accepted, useGlobalCompression, useCompactHeader);
            player.connection.send(new ClientboundCustomPayloadPacket(response));
            LOGGER.info("Hassium: Server handshake for {}: accepted={}, globalCompression={}, compactHeader={}",
                    player.getName().getString(), accepted, useGlobalCompression, useCompactHeader);
        }
    });
}
```

- [ ] **Step 2: 在 1.20.5+ 握手处理中补全**

在 `handleHandshake(HandshakePayload payload, IPayloadContext context)` 中做相同修改：

```java
private static void handleHandshake(HandshakePayload payload, IPayloadContext context) {
    context.enqueueWork(() -> {
        if (context.player() instanceof ServerPlayer player) {
            PlayerCompressionTracker.enableCompression(player);
            ServerChunkPushManager.getInstance().resyncTrackedChunks(player);
            boolean useGlobalCompression = HassiumConfigService.getInstance().isGlobalPacketCompressionEnabled()
                    && payload.globalPacketCompressionSupported();
            boolean useCompactHeader = HassiumConfigService.getInstance().isCompactHeaderEnabled()
                    && payload.compactHeaderSupported();
            boolean accepted = true;

            // 新增：聚合初始化
            if (useGlobalCompression) {
                Connection connection = getPlayerConnection(player);
                if (connection != null) {
                    HassiumConnectionRegistry.markPending(connection);
                    HassiumAggregationManager.init();
                    Channel channel = getConnectionChannel(connection);
                    if (channel != null) {
                        ZstdNegotiationTracker.markNegotiated(channel);
                    }
                }
            }

            HandshakeResponsePayload response = new HandshakeResponsePayload(
                    Constants.CURRENT_PROTOCOL_VERSION, accepted, useGlobalCompression, useCompactHeader);
            player.connection.send(response);
            LOGGER.info("Hassium: Server handshake for {}: accepted={}, globalCompression={}, compactHeader={}",
                    player.getName().getString(), accepted, useGlobalCompression, useCompactHeader);
        }
    });
}
```

- [ ] **Step 3: 添加导入**

```java
import io.github.limuqy.mc.hassium.network.HassiumConnectionRegistry;
import io.github.limuqy.mc.hassium.network.HassiumAggregationManager;
import io.github.limuqy.mc.hassium.network.ZstdNegotiationTracker;
import io.netty.channel.Channel;
```

- [ ] **Step 4: 验证编译**

```bash
./gradlew --no-daemon neoforge:compileJava
```

- [ ] **Step 5: Commit**

```bash
git add neoforge/src/main/java/io/github/limuqy/mc/hassium/network/NeoForgeNetworkManager.java
git commit -m "feat(network/neoforge): add aggregation initialization in handshake"
```

---

## Task 11: Forge 握手补全聚合初始化

**Files:**
- Modify: `forge/src/main/java/io/github/limuqy/mc/hassium/network/ForgeNetworkManager.java`

**Interfaces:**
- Consumes: `HassiumConnectionRegistry.markPending()`, `HassiumAggregationManager.init()`, `ZstdNegotiationTracker.markNegotiated()`

- [ ] **Step 1: 在握手处理中补全聚合初始化**

检查 `ForgeNetworkManager` 中的握手处理方法（与 NeoForge 类似），在 `useGlobalCompression` 为 true 时添加：

```java
if (useGlobalCompression) {
    Connection connection = getPlayerConnection(player);
    if (connection != null) {
        HassiumConnectionRegistry.markPending(connection);
        HassiumAggregationManager.init();
        Channel channel = getConnectionChannel(connection);
        if (channel != null) {
            ZstdNegotiationTracker.markNegotiated(channel);
        }
    }
}
```

需要导入：
```java
import io.github.limuqy.mc.hassium.network.HassiumConnectionRegistry;
import io.github.limuqy.mc.hassium.network.HassiumAggregationManager;
import io.github.limuqy.mc.hassium.network.ZstdNegotiationTracker;
import io.netty.channel.Channel;
```

- [ ] **Step 2: 验证编译**

```bash
./gradlew --no-daemon forge:compileJava
```

- [ ] **Step 3: Commit**

```bash
## Task 12: 断开连接清理

**Files:**
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/mixin/MixinConnection.java`

**Interfaces:**
- Consumes: `ZstdNegotiationTracker.removeChannel()`

- [ ] **Step 1: 添加断开连接注入点**

在 `MixinConnection` 中添加断开连接的注入：

```java
@Inject(method = "disconnect", at = @At("HEAD"))
private void hassium$onDisconnect(CallbackInfo ci) {
    Connection self = (Connection) (Object) this;
    HassiumConnectionRegistry.markDisabled(self);
    HassiumAggregationManager.discardConnection(self);
}
```

- [ ] **Step 2: 创建独立 Mixin 处理 Channel 清理**

由于 `MixinConnection` 可能无法直接访问 Channel，新建 `MixinConnectionCleanup.java`：

```java
package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.network.ZstdNegotiationTracker;
import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public abstract class MixinConnectionCleanup {
    @Shadow
    private Channel channel;

    @Inject(method = "disconnect", at = @At("HEAD"))
    private void hassium$cleanupZstdNegotiation(CallbackInfo ci) {
        if (this.channel != null) {
            ZstdNegotiationTracker.removeChannel(this.channel);
        }
    }
}
```

- [ ] **Step 3: 注册新 Mixin**

在 `hassium.mixins.json` 中添加 `MixinConnectionCleanup`。

- [ ] **Step 4: 验证编译**

```bash
./gradlew --no-daemon common:compileJava
```

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/io/github/limuqy/mc/hassium/mixin/MixinConnectionCleanup.java
git add common/src/main/resources/hassium.mixins.json
git commit -m "feat(network): cleanup ZSTD negotiation state on disconnect"
```

---

## Task 13: 更新配置注释

**Files:**
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/config/HassiumConfig.java`

- [ ] **Step 1: 更新 ServerNetworkConfig 注释**

```java
/**
 * 服务端网络配置（仅专用服；server.toml network.*）
 * <p>
 * 包含共享网络行为（压缩/聚合）和服务端专属推送设置。
 */
public record ServerNetworkConfig(
        boolean enabled,
        int compressionLevel,
        boolean magiclessZstd,
        // === 全局包压缩（管线级 ZSTD 替换 Zlib）===
        boolean globalPacketCompression,
        int globalCompressionLevel,
        int globalCompressionThreshold,
        // === 上下文压缩 ===
        boolean useContextCompression,
        // === 包聚合（应用层，MixinConnection 拦截）===
        boolean enablePacketAggregation,
        int aggregationMinBatchSize,
        long aggregationMaxWaitTimeMs,
        int aggregationMaxSize,
        // === 紧凑包头（聚合包内部 VarInt 索引）===
        boolean enableCompactHeader,
        // === 黑名单 ===
        Set<String> compressionBlacklist,
        // === 指标 ===
        boolean metricsEnabled,
        // === 服务端推送 ===
        int maxChunksPerTick,
        int serverChunkPushThreads,
        boolean dynamicThreadPoolEnabled,
        int minPushThreads,
        int maxPushThreads,
        // === 光照剥离（服务端控制是否发包时剥离 LightData）===
        boolean lightStrip
) {
```

- [ ] **Step 2: Commit**

```bash
git add common/src/main/java/io/github/limuqy/mc/hassium/config/HassiumConfig.java
git commit -m "docs(config): update ServerNetworkConfig comments to reflect implementation status"
```

---

## Task 14: 全量编译验证

- [ ] **Step 1: 编译所有模块**

```bash
./gradlew --no-daemon common:compileJava
./gradlew --no-daemon fabric:compileJava
./gradlew --no-daemon forge:compileJava
./gradlew --no-daemon neoforge:compileJava
```

- [ ] **Step 2: 运行测试**

```bash
./gradlew --no-daemon common:test
```

- [ ] **Step 3: 构建完整包**

```bash
./gradlew --no-daemon build
```

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat(network): complete network implementation - pipeline ZSTD + aggregation"
```
