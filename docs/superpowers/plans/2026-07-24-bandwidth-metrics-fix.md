# 带宽压缩指标完善计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补全客户端/服务端带宽压缩指标埋点；修正服务端把 lightStrip 后字节当作「原版」的偏低问题；metrics 默认关闭；冒烟测试自动强制开启。

**Architecture:** 单一口径——`vanillaBytes*` 仅由应用层（区块 / 聚合子包 / section-delta）写入；`actualBytes*` 仅由 Netty 管线（`ZstdContextEncoder` / `SkipAwareZstdEncoder` / `ZstdContextDecoder`）的 `recordWire*` 写入。禁止应用层再写 actual，避免双重计数。紧凑包头不单独埋点（体现在聚合 vanilla vs 线缆 actual 之差）。

**Tech Stack:** Java, Netty, ZSTD, Mixin, JUnit 5, Fabric/Forge/NeoForge 多加载器（改动集中在 `common`）

## Global Constraints

- common 模块禁止 fabric/forge/neoforge import
- 热路径禁止无条件 `LOGGER.info`
- 指标开启时才计算真实原版包大小；关闭时跳过所有估算逻辑
- 不改 `CompressedChunkData` 线协议；客户端 vanilla 继续用 `originalSize`（strip 后）
- 不改 `ZstdPacketEncoder` / `ZstdPacketDecoder`（`ZstdPipelineSwitcher` 只装 Context/SkipAware）
- 用户已写进 toml 的 `metricsEnabled=true` 保持；只改代码默认，不写迁移脚本
- 新控制面包才加入黑名单；本次无新包

---

## Task 1: NetworkStats API cutover

**Files:**
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/metrics/NetworkStats.java`
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/metrics/HassiumMetricsImpl.java`
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/network/ServerChunkPushManager.java`（调用点签名，vanilla 完整逻辑在 Task 3）
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/network/ClientChunkHandler.java`
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/network/ClientMetadataHandler.java`

**Interfaces:**
- Produces: `recordChunkSent(int vanillaSize)`、`recordChunkReceived(int vanillaSize)`、`recordWireBytesSent/Received`、`recordVanillaBytesSent/Received`、两参 `recordSectionDeltaReceived`
- Consumes: 现有 `HassiumMetricsImpl.recordVanillaBytes*` / `recordActualBytes*` / `incrementChunks*`

- [ ] **Step 1: 改 `NetworkStats` 门面 API**

替换/新增（`enabled` 初始值暂仍 `true`，Task 5 再翻为 `false`）：

```java
/** 仅记原版等价字节 + 区块计数；actual 由管线层 recordWire* 写入 */
public static void recordChunkSent(int vanillaSize) {
    if (!enabled) return;
    if (vanillaSize > 0) metrics.recordVanillaBytesSent(vanillaSize);
    metrics.incrementChunksCompressed();
}

public static void recordChunkReceived(int vanillaSize) {
    if (!enabled) return;
    if (vanillaSize > 0) metrics.recordVanillaBytesReceived(vanillaSize);
    metrics.incrementChunksDecompressed();
}

/** 线缆出站帧字节（管线 encode 后 out 增量） */
public static void recordWireBytesSent(int wireBytes) {
    if (!enabled) return;
    if (wireBytes > 0) metrics.recordActualBytesSent(wireBytes);
}

/** 线缆入站帧字节（管线 decode 消费的 in 增量） */
public static void recordWireBytesReceived(int wireBytes) {
    if (!enabled) return;
    if (wireBytes > 0) metrics.recordActualBytesReceived(wireBytes);
}

/** 应用层原版等价字节（聚合子包等） */
public static void recordVanillaBytesSent(long bytes) {
    if (!enabled) return;
    if (bytes > 0) metrics.recordVanillaBytesSent(bytes);
}

public static void recordVanillaBytesReceived(long bytes) {
    if (!enabled) return;
    if (bytes > 0) metrics.recordVanillaBytesReceived(bytes);
}

/**
 * 记录收到的分段增量（只记 vanilla + 计数；actual 由管线统一记）
 *
 * @param chunks       区块数
 * @param vanillaBytes 若走全量时的原版等价字节（估算）
 */
public static void recordSectionDeltaReceived(int chunks, long vanillaBytes) {
    if (!enabled) return;
    metrics.recordSectionDeltaReceived(chunks, vanillaBytes);
}
```

删除旧两参 `recordChunkSent(int, int)` / `recordChunkReceived(int, int)` 与三参 `recordSectionDeltaReceived`。同步更新类头 javadoc 示例。

- [ ] **Step 2: 改 `HassiumMetricsImpl.recordSectionDeltaReceived`**

```java
public void recordSectionDeltaReceived(long chunks, long vanillaBytes) {
    if (chunks > 0) {
        sectionDeltaChunksReceived.addAndGet(chunks);
    }
    if (vanillaBytes > 0) {
        vanillaBytesReceived.addAndGet(vanillaBytes);
    }
    // 禁止再写 actualBytesReceived
}
```

- [ ] **Step 3: 更新全部调用点到新签名（最小可编译）**

| 文件 | 旧 | 新 |
|------|----|----|
| `ServerChunkPushManager.compressAndSend` | `recordChunkSent(work.chunkData().length, compressed.compressedData.length)` | `recordChunkSent(work.chunkData().length)`（Task 3 再换成 `vanillaSize`） |
| `ClientChunkHandler` | `recordChunkReceived(compressed.originalSize, compressed.compressedData.length)` | `recordChunkReceived(compressed.originalSize)` |
| `ClientMetadataHandler` | `recordSectionDeltaReceived(n, vanilla, actual)` | `recordSectionDeltaReceived(n, vanilla)`；可删 `actualBytes` 局部变量 |

全仓库确认无旧签名残留：

```bash
# PowerShell / 工具 grep 均可
# 期望：仅新单参/两参定义，无 compressedSize 写入 actual 的旧路径
```

- [ ] **Step 4: 编译**

```bash
./gradlew --no-daemon common:compileJava
```

期望：无 `recordChunkSent` / `recordSectionDeltaReceived` 签名错误。

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/io/github/limuqy/mc/hassium/metrics/NetworkStats.java \
  common/src/main/java/io/github/limuqy/mc/hassium/metrics/HassiumMetricsImpl.java \
  common/src/main/java/io/github/limuqy/mc/hassium/network/ServerChunkPushManager.java \
  common/src/main/java/io/github/limuqy/mc/hassium/network/ClientChunkHandler.java \
  common/src/main/java/io/github/limuqy/mc/hassium/network/ClientMetadataHandler.java
git commit -m "refactor(metrics): cutover bandwidth API to vanilla-only + wire actual"
```

---

## Task 2: 管线层 actual（wire）埋点

**Files:**
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/network/ZstdContextEncoder.java`
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/network/SkipAwareZstdEncoder.java`
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/network/ZstdContextDecoder.java`

**Interfaces:**
- Consumes: `NetworkStats.recordWireBytesSent/Received`

- [ ] **Step 1: `ZstdContextEncoder.encode` 记出站 wire**

在方法开头 `int outStart = out.writerIndex()`；所有成功写出路径（`closed` 透传 / 阈值下明文 / 压缩）结束前：

```java
import io.github.limuqy.mc.hassium.metrics.NetworkStats;

// encode 内：
int outStart = out.writerIndex();
// ... 现有写出逻辑 ...
NetworkStats.recordWireBytesSent(out.writerIndex() - outStart);
```

注意：`closed` 分支也要记。

- [ ] **Step 2: `SkipAwareZstdEncoder.encode` skip 分支记 wire**

```java
@Override
protected void encode(ChannelHandlerContext ctx, ByteBuf in, ByteBuf out) throws Exception {
    Boolean skip = ctx.channel().attr(HassiumPipelineAttributes.SKIP_PIPELINE_COMPRESSION).getAndSet(false);
    if (Boolean.TRUE.equals(skip)) {
        int outStart = out.writerIndex();
        FriendlyByteBuf friendlyBuf = new FriendlyByteBuf(out);
        friendlyBuf.writeVarInt(0);
        friendlyBuf.writeBytes(in);
        NetworkStats.recordWireBytesSent(out.writerIndex() - outStart);
        return;
    }
    super.encode(ctx, in, out); // 父类已记，禁止双重调用
}
```

- [ ] **Step 3: `ZstdContextDecoder.decode` 记入站 wire**

在读取前 `int inStart = in.readerIndex()`；成功产出 `out.add(...)` 或 `closed` 透传路径结束时：

```java
NetworkStats.recordWireBytesReceived(in.readerIndex() - inStart);
```

`readableBytes==0` 早退**不**记。

- [ ] **Step 4: 编译**

```bash
./gradlew --no-daemon common:compileJava
```

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/io/github/limuqy/mc/hassium/network/ZstdContextEncoder.java \
  common/src/main/java/io/github/limuqy/mc/hassium/network/SkipAwareZstdEncoder.java \
  common/src/main/java/io/github/limuqy/mc/hassium/network/ZstdContextDecoder.java
git commit -m "feat(metrics): record wire actual bytes in ZSTD pipeline handlers"
```

---

## Task 3: 服务端区块 vanilla 度量（metrics 门控）

**Files:**
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/network/ServerChunkPushManager.java`

**问题根因：** `buildChunkPacket` 在 `lightStrip=true` 时用空 `BitSet` 剥离光照；`recordChunkSent(work.chunkData().length, …)` 把剥离后字节当原版。

**优先方案：** `preparedChunkPackets` 的 value 改为 holder（比并行 map 清理更少出错）。

- [ ] **Step 1: 引入 `PreparedChunk` holder，改 map 类型**

```java
/** 已编码包字节 + metrics 时的原版大小（metrics 关则为 0） */
private record PreparedChunk(byte[] data, int vanillaSize) {}

private final Map<UUID, ConcurrentHashMap<Long, PreparedChunk>> preparedChunkPackets = new ConcurrentHashMap<>();
```

- [ ] **Step 2: 扩展 `SerializedChunkWork`**

```java
private record SerializedChunkWork(
        ServerPlayer player, ChunkPos pos, byte[] chunkData, int vanillaSize) {}
```

- [ ] **Step 3: 新增 `measureVanillaChunkPacketBytes`**

```java
/**
 * 度量「无 lightStrip」时的原版 ClientboundLevelChunkWithLightPacket 编码大小。
 * 仅应在 NetworkStats.isEnabled() 时调用。
 */
private int measureVanillaChunkPacketBytes(LevelChunk chunk, ServerLevel level) {
    ClientboundLevelChunkWithLightPacket full =
            new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null);
    byte[] encoded = encodeChunkPacket(full, level.registryAccess());
    return encoded != null ? encoded.length : 0;
}
```

- [ ] **Step 4: 改 `putPreparedChunkPacket` / `takePreparedChunkPacket`**

```java
private void putPreparedChunkPacket(UUID playerId, ChunkPos pos, byte[] data, int vanillaSize) {
    ConcurrentHashMap<Long, PreparedChunk> map =
            preparedChunkPackets.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
    map.put(ChunkPos.asLong(pos.x, pos.z), new PreparedChunk(data, vanillaSize));
    // 保留现有 MAX_PREPARED_PER_PLAYER 清理逻辑，清理 value 类型改为 PreparedChunk
}

private PreparedChunk takePreparedChunkPacket(UUID playerId, ChunkPos pos) {
    ConcurrentHashMap<Long, PreparedChunk> map = preparedChunkPackets.get(playerId);
    if (map == null) return null;
    return map.remove(ChunkPos.asLong(pos.x, pos.z));
}
```

- [ ] **Step 5: 所有 `putPreparedChunkPacket` 调用点写入 vanilla**

模式（主线程 prepare 路径，`encoded` 已算好）：

```java
int vanillaSize = 0;
if (NetworkStats.isEnabled()) {
    if (!HassiumConfigService.getInstance().isServerLightStrip()) {
        vanillaSize = encoded.length; // 未 strip：与原版一致，禁止重复 encode
    } else {
        // 需要 LevelChunk + ServerLevel：submitMetadataTask 等已有 chunk 上下文的路径调 measure*
        // 若仅有 packet 无 chunk：无法 unstripped 重编码时 vanillaSize 保持 0
        // 优先：在仍持有 LevelChunk 的路径（serializeChunk / buildChunkPacket 附近）算好再 put
        vanillaSize = measureVanillaChunkPacketBytes(chunk, level);
    }
}
putPreparedChunkPacket(playerId, pos, encoded, vanillaSize);
```

实现时按各 `put` 调用点是否仍持有 `LevelChunk` 选择：
1. **持有 chunk**：metrics 开 + lightStrip → `measureVanilla…`；metrics 开 + !lightStrip → `encoded.length`；metrics 关 → `0`
2. **仅有 packet 字节**（如广播拦截后只缓存 `encoded`）：若 `!isServerLightStrip()` 且 metrics 开 → `encoded.length`；若 lightStrip 且无法拿到 chunk → `vanillaSize=0`（宁可漏记也不二次错误偏低）；drain 回退 `serializeChunk` 路径会补算

- [ ] **Step 6: `drainPlayerQueueTick` + `compressAndSend`**

```java
// drain:
PreparedChunk prepared = takePreparedChunkPacket(playerId, task.pos());
byte[] chunkData = prepared != null ? prepared.data() : null;
int vanillaSize = prepared != null ? prepared.vanillaSize() : 0;
if (chunkData == null) {
    LevelChunk chunk = level.getChunk(...);
    // ...
    chunkData = serializeChunk(chunk, level);
    if (NetworkStats.isEnabled()) {
        if (!HassiumConfigService.getInstance().isServerLightStrip()) {
            vanillaSize = chunkData.length;
        } else {
            vanillaSize = measureVanillaChunkPacketBytes(chunk, level);
        }
    }
}
works.add(new SerializedChunkWork(player, task.pos(), chunkData, vanillaSize));

// compressAndSend:
sender.sendCompressedChunk(player, compressed);
NetworkStats.recordChunkSent(work.vanillaSize());
// 日志仍用 work.chunkData().length 与 compressed.compressedData.length 显示体压缩比
```

**硬约束：** metrics off 路径**不得**调用 `measureVanillaChunkPacketBytes`。

- [ ] **Step 7: 编译**

```bash
./gradlew --no-daemon common:compileJava
```

- [ ] **Step 8: Commit**

```bash
git add common/src/main/java/io/github/limuqy/mc/hassium/network/ServerChunkPushManager.java
git commit -m "fix(metrics): measure unstripped vanilla chunk size when metrics enabled"
```

---

## Task 4: 聚合路径 vanilla 埋点

**Files:**
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/network/AggregatedSubPacket.java`（可选 helper）
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/network/HassiumAggregationPacket.java`

**Interfaces:**
- Consumes: `NetworkStats.recordVanillaBytesSent/Received`
- Produces: `estimateVanillaSubPacketBytes`（完整 identifier 字符串，**不用** CompactHeader）

- [ ] **Step 1: 实现原版子包字节估算**

放在 `AggregatedSubPacket` 或 `HassiumAggregationPacket` private static：

```java
/**
 * 若走原版独立包：writeUtf(type) + VarInt(len) + data 的近似线大小。
 * 不含外层 Connection 帧；与紧凑头对比即可体现 compact header 节省。
 */
static int estimateVanillaSubPacketBytes(AggregatedSubPacket sp) {
    FriendlyByteBuf tmp = new FriendlyByteBuf(Unpooled.buffer());
    try {
        tmp.writeUtf(sp.getType().toString());
        tmp.writeVarInt(sp.getData().length);
        return tmp.readableBytes() + sp.getData().length;
    } finally {
        tmp.release();
    }
}
```

- [ ] **Step 2: `HassiumAggregationPacket.encode` 累加 vanilla**

在现有写子包循环内：

```java
int vanillaTotal = 0;
for (AggregatedSubPacket subPacket : subPackets) {
    vanillaTotal += estimateVanillaSubPacketBytes(subPacket);
    subPacket.encode(rawBuf, indexManager);
}
// 压缩/非压缩写出完成后：
NetworkStats.recordVanillaBytesSent(vanillaTotal);
```

**不要**在 encode 记 actual（管线 SkipAware 已记 wire）。

- [ ] **Step 3: `HassiumAggregationPacket.decode` 累加 vanilla**

解压得到 raw 后、拆子包循环：对每个 subpacket `estimateVanillaSubPacketBytes`，合计后 `NetworkStats.recordVanillaBytesReceived(vanillaTotal)`。

**不要**记 actual。

- [ ] **Step 4: 编译**

```bash
./gradlew --no-daemon common:compileJava
```

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/io/github/limuqy/mc/hassium/network/HassiumAggregationPacket.java \
  common/src/main/java/io/github/limuqy/mc/hassium/network/AggregatedSubPacket.java
git commit -m "feat(metrics): record aggregation vanilla bytes without compact header"
```

---

## Task 5: metrics 默认关闭 + 冒烟强制开启

**Files:**
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/config/HassiumConfig.java`
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/config/HassiumConfigSpec.java`
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/config/HassiumConfigService.java`
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/metrics/NetworkStats.java`
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/client/ClientSmokeTest.java`
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/server/ServerSmokeTest.java`
- Modify: `README.md`
- Modify（若写死 true）: `AGENTS.md` / 相关 docs 一句

- [ ] **Step 1: 代码默认 false**

```java
// HassiumConfig.ClientNetworkConfig.DEFAULT
public static final ClientNetworkConfig DEFAULT = new ClientNetworkConfig(true, false);

// HassiumConfig.ServerNetworkConfig.DEFAULT
// metricsEnabled 参数：true → false
                false,             // metricsEnabled

// NetworkStats
private static volatile boolean enabled = false;
```

`HassiumConfigSpec` 客户端与服务端两处：

```java
.comment("是否启用…指标收集（默认 false）")
.define("metricsEnabled", false);
```

- [ ] **Step 2: 冒烟属性扛住 config reload**

`HassiumConfigService.resolveMetricsEnabled` **最前**：

```java
if (Boolean.parseBoolean(System.getProperty("hassium.smokeTest", "false"))
        || Boolean.parseBoolean(System.getProperty("hassium.serverSmokeTest", "false"))) {
    return true;
}
// 其后保持现有 client/server 分支
```

- [ ] **Step 3: 冒烟 init 立即 setEnabled**

```java
// ClientSmokeTest.initIfEnabled() 末尾（LOGGER.info 之后）
NetworkStats.setEnabled(true);

// ServerSmokeTest.initIfEnabled(...) 末尾
NetworkStats.setEnabled(true);
```

**不要**改 `toggleStats` 命令语义。

- [ ] **Step 4: README / 文档**

`README.md` 表格：`network.metricsEnabled` 默认 `true` → `false`。  
`AGENTS.md` 配置红线表若写死 true，同步改一句；无则跳过。

- [ ] **Step 5: 编译**

```bash
./gradlew --no-daemon common:compileJava
```

- [ ] **Step 6: Commit**

```bash
git add common/src/main/java/io/github/limuqy/mc/hassium/config/HassiumConfig.java \
  common/src/main/java/io/github/limuqy/mc/hassium/config/HassiumConfigSpec.java \
  common/src/main/java/io/github/limuqy/mc/hassium/config/HassiumConfigService.java \
  common/src/main/java/io/github/limuqy/mc/hassium/metrics/NetworkStats.java \
  common/src/main/java/io/github/limuqy/mc/hassium/client/ClientSmokeTest.java \
  common/src/main/java/io/github/limuqy/mc/hassium/server/ServerSmokeTest.java \
  README.md
git commit -m "feat(metrics): default metrics off; force-enable under smoke JVM flags"
```

---

## Task 6: 单测 + 静态核对

**Files:**
- Create: `common/src/test/java/io/github/limuqy/mc/hassium/metrics/NetworkStatsBandwidthTest.java`
- 不改: `MetricsTextFormatterTest`（公式不变）

- [ ] **Step 1: 写 `NetworkStatsBandwidthTest`**

```java
package io.github.limuqy.mc.hassium.metrics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NetworkStatsBandwidthTest {

    @BeforeEach
    void setUp() {
        NetworkStats.reset();
        NetworkStats.setEnabled(false);
    }

    @AfterEach
    void tearDown() {
        NetworkStats.reset();
        NetworkStats.setEnabled(false);
    }

    @Test
    void disabledSkipsAllRecording() {
        NetworkStats.recordChunkSent(1000);
        NetworkStats.recordWireBytesSent(200);
        NetworkStats.recordVanillaBytesSent(500);
        assertEquals(0, NetworkStats.getMetrics().getVanillaBytesSent());
        assertEquals(0, NetworkStats.getMetrics().getActualBytesSent());
        assertEquals(0, NetworkStats.getMetrics().getChunksCompressed());
    }

    @Test
    void enabledSeparatesVanillaAndWireActual() {
        NetworkStats.setEnabled(true);
        NetworkStats.recordChunkSent(1000);
        NetworkStats.recordWireBytesSent(200);

        assertEquals(1000, NetworkStats.getMetrics().getVanillaBytesSent());
        assertEquals(200, NetworkStats.getMetrics().getActualBytesSent());
        assertEquals(1, NetworkStats.getMetrics().getChunksCompressed());

        // 80% saving
        double saved = 1.0 - (200.0 / 1000.0);
        assertEquals(0.8, saved, 1e-9);
        assertEquals("5.00:1", MetricsTextFormatter.formatCompressionRatio(1000, 200));
    }

    @Test
    void sectionDeltaDoesNotWriteActual() {
        NetworkStats.setEnabled(true);
        NetworkStats.recordSectionDeltaReceived(2, 32_768L);
        assertEquals(32_768L, NetworkStats.getMetrics().getVanillaBytesReceived());
        assertEquals(0, NetworkStats.getMetrics().getActualBytesReceived());
        assertEquals(2, NetworkStats.getMetrics().getSectionDeltaChunksReceived());
    }

    @Test
    void resetClearsCounters() {
        NetworkStats.setEnabled(true);
        NetworkStats.recordChunkSent(100);
        NetworkStats.recordWireBytesSent(10);
        NetworkStats.reset();
        assertEquals(0, NetworkStats.getMetrics().getVanillaBytesSent());
        assertEquals(0, NetworkStats.getMetrics().getActualBytesSent());
    }
}
```

若 `getSectionDeltaChunksReceived` 非 public，用现有 public getter；无则只断言 vanilla/actual。

- [ ] **Step 2: 跑 metrics 单测**

```bash
./gradlew --no-daemon common:test --tests "io.github.limuqy.mc.hassium.metrics.*"
```

期望：`NetworkStatsBandwidthTest` + `MetricsTextFormatterTest` 全过。

- [ ] **Step 3: 静态核对**

应用层不得再写 `recordActualBytes*`：

```text
grep recordActualBytes common/src/main
# 期望：仅 HassiumMetricsImpl 内部 + NetworkStats.recordWire* 调用链
# ServerChunkPushManager / ClientChunkHandler / ClientMetadataHandler / HassiumAggregationPacket 不得出现
```

- [ ] **Step 4: Commit**

```bash
git add common/src/test/java/io/github/limuqy/mc/hassium/metrics/NetworkStatsBandwidthTest.java
git commit -m "test(metrics): cover vanilla/wire bandwidth counters and disabled no-op"
```

---

## 依赖顺序

```
Task 1 (API cutover)
  ├─ Task 2 (管线 wire)     ─┐
  ├─ Task 3 (区块 vanilla)  ─┼─ 可并行
  └─ Task 4 (聚合 vanilla)  ─┘
         ↓
      Task 5 (默认 false + 冒烟)
         ↓
      Task 6 (单测 + 静态核对)
```

## Critical files & anchors

| 文件 | 符号/区域 | 原因 |
|------|-----------|------|
| `metrics/NetworkStats.java` | `recordChunkSent` / `recordWire*` | API 口径切换 |
| `metrics/HassiumMetricsImpl.java` | `recordSectionDeltaReceived` | 去掉 actual 双计 |
| `network/ZstdContextEncoder.java` + `SkipAwareZstdEncoder` + `ZstdContextDecoder` | `encode` / `decode` | actual 唯一写入点 |
| `network/ServerChunkPushManager.java` | `SerializedChunkWork`、`PreparedChunk`、`compressAndSend` | unstripped vanilla 门控 |
| `network/HassiumAggregationPacket.java` | `encode` / `decode` | 聚合 vanilla |
| `config/HassiumConfig*.java` + `*SmokeTest` | `metricsEnabled` / `resolveMetricsEnabled` / `initIfEnabled` | 默认关 + 冒烟强开 |

## Verification

1. **单测**（仓库根）：
   ```bash
   ./gradlew --no-daemon common:test --tests "io.github.limuqy.mc.hassium.metrics.*"
   ```
2. **编译 common**：
   ```bash
   ./gradlew --no-daemon common:compileJava
   ```
3. **行为核对**：
   - 应用层区块/聚合路径不得 `recordActualBytes*`
   - metrics off 时 `ServerChunkPushManager` 不得调用 `measureVanillaChunkPacketBytes`
   - `hassium.smokeTest=true` 或 `hassium.serverSmokeTest=true` 时 `resolveMetricsEnabled` 恒 true，且 `initIfEnabled` 后 `NetworkStats.isEnabled()==true`
   - 新生成配置 `metricsEnabled=false`；用户旧 toml 显式 true 保持
4. **命令展示**：`HassiumCommandHandler` 公式不变；数据源改正后「带宽压缩」反映全层（区块体 + 管线 + 聚合）

## Assumptions & contingencies

- **假设**：带宽 UI 用单一 vanilla/actual 对，不拆 Layer 分项。分项另开任务。
- **假设**：客户端区块 vanilla 用 `compressed.originalSize`（strip 后），不扩展线协议。端到端「真实节省」以服务端 stats + 客户端 actual（管线）为准。
- **假设**：未装 Hassium ZSTD 管线时 actual 可能为 0（仅原版 zlib）——metrics 默认关可接受；`globalPacketCompression=false` 仍开 metrics 时 saving 虚高，本次不修 zlib mixin。
- **若** prepare 路径无法拿 `LevelChunk` 且 lightStrip：vanilla=0 优于用 strip 后字节冒充原版。
- **若** 用户 toml 已写 `metricsEnabled=true`：不迁移、不覆盖。

## Out of scope

- 分项压缩层百分比 UI
- 扩展 `CompressedChunkData` 携带 unstripped vanilla
- 原版 Zlib 管线埋点
- 配置文件迁移脚本
