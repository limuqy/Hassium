# 配置系统重构实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Hassium 配置系统从三文件（client/common/server）重构为双文件（client/server），拆分巨型 NetworkConfig record，清理死代码配置项，硬编码 Bloom filter。

**Architecture:** 运行时 `HassiumConfig` record 拆为 `ClientCacheConfig`（吸收部分原 network 字段）+ `ClientNetworkConfig`（2 字段）+ `StorageConfig` + `ServerNetworkConfig`（19 字段）+ `CompatConfig` + `DebugConfig`。Forge/NeoForge 的 `HassiumConfigSpec` 从三个 spec（CLIENT/COMMON/SERVER）改为两个（CLIENT/SERVER）。Fabric 的 `HassiumTomlConfigIO` 重写为 `FabricTomlConfigIO`，只读写 client.toml 或 server.toml。

**Tech Stack:** Java 17, Manifold #if MC_VER, ForgeConfigSpec / ModConfigSpec, NightConfig TOML, Cloth Config 2

## Global Constants

- `Constants.CONFIG_CLIENT_FILE = "hassium/hassium-client.toml"`（不变）
- `Constants.CONFIG_SERVER_FILE = "hassium/hassium-server.toml"`（不变）
- ~~`Constants.CONFIG_COMMON_FILE`~~ → 删除
- Bloom filter 硬编码：`expectedInsertions=10000, fpp=0.01`
- 聚合包 `magiclessZstd` 硬编码：`true`
- 缓存压缩默认等级：`9`（可通过 `clientCache.cacheCompressionLevel` 配置）

---

### Task 1: 更新 Constants.java

**Files:**
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/Constants.java`

**Interfaces:**
- Produces: 删除 `CONFIG_COMMON_FILE`；其余常量不变

- [ ] **Step 1: 删除 CONFIG_COMMON_FILE**

```java
// 删除这一行：
public static final String CONFIG_COMMON_FILE = "hassium/hassium-common.toml";
```

保留 `CONFIG_CLIENT_FILE` 和 `CONFIG_SERVER_FILE` 不变。

- [ ] **Step 2: 编译验证**

```bash
./gradlew --no-daemon common:compileJava
```

Expected: 编译失败（其他文件引用了 CONFIG_COMMON_FILE），记录失败文件列表供后续 Task 使用。

- [ ] **Step 3: 提交**

```bash
git add common/src/main/java/io/github/limuqy/mc/hassium/Constants.java
git commit -m "refactor: remove CONFIG_COMMON_FILE from Constants"
```

---

### Task 2: 重构 HassiumConfig.java — 拆分 record

**Files:**
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/config/HassiumConfig.java`

**Interfaces:**
- Produces:
  - `HassiumConfig.ClientCacheConfig` — 18 字段（原 18 - 4 删除 + 4 吸收）
  - `HassiumConfig.ClientNetworkConfig` — 2 字段（新拆分）
  - `HassiumConfig.StorageConfig` — 3 字段（不变）
  - `HassiumConfig.ServerNetworkConfig` — 19 字段（新拆分）
  - `HassiumConfig.CompatConfig` — 2 字段（不变）
  - `HassiumConfig.DebugConfig` — 7 字段（不变）
- Consumes: 无

- [ ] **Step 1: 重写 HassiumConfig.java**

完整替换文件内容：

```java
package io.github.limuqy.mc.hassium.config;

import io.github.limuqy.mc.hassium.network.HassiumPacketIds;
import java.util.Set;

/**
 * Hassium 配置（运行时快照）。
 * <p>
 * 物理客户端从 client.toml 加载：ClientCacheConfig + ClientNetworkConfig + DebugConfig。
 * 专用服从 server.toml 加载：StorageConfig + ServerNetworkConfig + CompatConfig + DebugConfig。
 */
public record HassiumConfig(
        StorageConfig storage,
        ClientCacheConfig clientCache,
        ClientNetworkConfig clientNetwork,
        ServerNetworkConfig serverNetwork,
        CompatConfig compat,
        DebugConfig debug
) {
    public static final HassiumConfig DEFAULT = new HassiumConfig(
            StorageConfig.DEFAULT,
            ClientCacheConfig.DEFAULT,
            ClientNetworkConfig.DEFAULT,
            ServerNetworkConfig.DEFAULT,
            CompatConfig.DEFAULT,
            DebugConfig.DEFAULT
    );

    /**
     * 存储配置（仅专用服；server.toml storage.*）
     */
    public record StorageConfig(
            boolean enabled,
            String mode,
            int zstdLevel
    ) {
        public static final StorageConfig DEFAULT = new StorageConfig(true, "mirror", 9);
    }

    /**
     * 客户端缓存配置（仅物理客户端；client.toml clientCache.*）
     * <p>
     * 吸收了原 NetworkConfig 中客户端专属字段：loadThreads、lightStrip、maxChunksPerFrame、mainThreadChunkBudgetMs。
     * Bloom filter 参数硬编码（enabled=true, insertions=10000, fpp=0.01）。
     * maxAgeDays 已删除（热度评分隐式覆盖）。
     */
    public record ClientCacheConfig(
            boolean enabled,
            int maxSizeMb,
            int cacheCompressionLevel,
            // === 热度清理 ===
            double hotScoreThreshold,
            double recencyWeight,
            double frequencyWeight,
            int cleanupIntervalTicks,
            int targetCacheSizeMb,
            int minCleanupBatchSize,
            // === 超视渲染 ===
            boolean viewDistanceExtensionEnabled,
            int maxRenderDistance,
            int ovdUnloadDelaySecs,
            // === 分段增量 ===
            boolean sectionDeltaEnabled,
            // === JoinBoost ===
            boolean joinBoostEnabled,
            // === 实体快照 ===
            boolean entitySnapshotsEnabled,
            // === 从原 NetworkConfig 吸收的客户端字段 ===
            int loadThreads,
            boolean lightStrip,
            int maxChunksPerFrame,
            int mainThreadChunkBudgetMs
    ) {
        public static final ClientCacheConfig DEFAULT = new ClientCacheConfig(
                true,    // enabled
                2048,    // maxSizeMb
                9,       // cacheCompressionLevel
                0.3,     // hotScoreThreshold
                0.7,     // recencyWeight
                0.3,     // frequencyWeight
                6000,    // cleanupIntervalTicks
                0,       // targetCacheSizeMb (auto)
                100,     // minCleanupBatchSize
                true,    // viewDistanceExtensionEnabled
                32,      // maxRenderDistance
                5,       // ovdUnloadDelaySecs
                true,    // sectionDeltaEnabled
                true,    // joinBoostEnabled
                false,   // entitySnapshotsEnabled
                10,      // loadThreads
                true,    // lightStrip
                32,      // maxChunksPerFrame
                10       // mainThreadChunkBudgetMs
        );

        public long maxCacheSizeBytes() {
            return (long) maxSizeMb * 1024 * 1024;
        }

        public int resolvedTargetCacheSizeMb() {
            return targetCacheSizeMb > 0 ? targetCacheSizeMb : (int) (maxSizeMb * 0.8);
        }

        public long targetCacheSizeBytes() {
            return (long) resolvedTargetCacheSizeMb() * 1024 * 1024;
        }
    }

    /**
     * 客户端网络配置（仅物理客户端；client.toml network.*）
     */
    public record ClientNetworkConfig(
            boolean enabled,
            boolean metricsEnabled
    ) {
        public static final ClientNetworkConfig DEFAULT = new ClientNetworkConfig(true, true);
    }

    /**
     * 服务端网络配置（仅专用服；server.toml network.*）
     * <p>
     * 包含共享网络行为（压缩/聚合）和服务端专属推送设置。
     * 标记"实验性"的字段当前未生效（ZstdPipelineSwitcher.switchToZstd 无调用者）。
     */
    public record ServerNetworkConfig(
            boolean enabled,
            int compressionLevel,
            boolean magiclessZstd,
            // === 实验性：全局包压缩（管线未安装）===
            boolean globalPacketCompression,
            int globalCompressionLevel,
            int globalCompressionThreshold,
            // === 上下文压缩 ===
            boolean useContextCompression,
            // === 实验性：包聚合（管线未安装）===
            boolean enablePacketAggregation,
            int aggregationMinBatchSize,
            long aggregationMaxWaitTimeMs,
            int aggregationMaxSize,
            // === 实验性：紧凑包头（管线未安装）===
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
        public static final Set<String> DEFAULT_COMPRESSION_BLACKLIST = Set.of(
                HassiumPacketIds.CHUNK_PAYLOAD_S2C,
                HassiumPacketIds.SECTION_DELTA_S2C,
                "hassium:main",
                "hassium:aggregation"
        );

        public static final ServerNetworkConfig DEFAULT = new ServerNetworkConfig(
                true,              // enabled
                3,                 // compressionLevel
                true,              // magiclessZstd
                true,              // globalPacketCompression (experimental)
                3,                 // globalCompressionLevel (experimental)
                256,               // globalCompressionThreshold (experimental)
                true,              // useContextCompression
                true,              // enablePacketAggregation (experimental)
                4,                 // aggregationMinBatchSize (experimental)
                20,                // aggregationMaxWaitTimeMs (experimental)
                256 * 1024,        // aggregationMaxSize (experimental)
                true,              // enableCompactHeader (experimental)
                DEFAULT_COMPRESSION_BLACKLIST,
                true,              // metricsEnabled
                32,                // maxChunksPerTick
                8,                 // serverChunkPushThreads
                true,              // dynamicThreadPoolEnabled
                2,                 // minPushThreads
                8,                 // maxPushThreads
                true               // lightStrip
        );
    }

    /**
     * 兼容性配置（仅专用服；server.toml compat.*）
     */
    public record CompatConfig(
            boolean requireClientMod,
            boolean autoDowngradeOnError
    ) {
        public static final CompatConfig DEFAULT = new CompatConfig(false, true);
    }

    /**
     * 调试配置（双端各自 toml debug.*）
     */
    public record DebugConfig(
            boolean metadataLogging,
            boolean dispatcherLogging,
            boolean asyncLogging,
            boolean compressionLogging,
            boolean chunkApplyLogging,
            boolean networkLogging,
            boolean cacheLogging
    ) {
        public static final DebugConfig DEFAULT = new DebugConfig(
                false, false, false, false, false, false, false
        );
    }
}
```

- [ ] **Step 2: 编译验证（预期大量错误，记录引用点）**

```bash
./gradlew --no-daemon common:compileJava 2>&1 | head -100
```

Expected: 编译失败，所有引用旧 `NetworkConfig` 的地方报错。

- [ ] **Step 3: 提交**

```bash
git add common/src/main/java/io/github/limuqy/mc/hassium/config/HassiumConfig.java
git commit -m "refactor: split NetworkConfig into ClientNetworkConfig + ServerNetworkConfig"
```

---

### Task 3: 重构 HassiumConfigSpec.java — 两个 spec

**Files:**
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/config/HassiumConfigSpec.java`

**Interfaces:**
- Produces: `CLIENT_SPEC`（client.toml 字段）、`SERVER_SPEC`（server.toml 字段）
- 删除: `COMMON_SPEC`、`Common` 内部类
- Consumes: `HassiumConfig` record（Task 2 产出）

- [ ] **Step 1: 重写 HassiumConfigSpec.java**

完整替换。关键变化：
- 删除 `COMMON_SPEC` 和 `Common` 内部类
- `Client` 内部类：使用新的 `ClientCacheConfig` 字段（含吸收的 loadThreads/lightStrip/maxChunksPerFrame/mainThreadChunkBudgetMs + 新增 cacheCompressionLevel），删除 bloom/maxAgeDays/backgroundThreads/maxCallbacksPerFrame
- `Server` 内部类：合并原 Common 的网络/存储/兼容/调试字段 + 原 Server 的推送字段
- `toHassiumConfig()` 构建新的 `HassiumConfig`（含 `ClientNetworkConfig` + `ServerNetworkConfig`）
- `applyFrom()` 写回两个 spec

（完整代码较长，实现时按 spec 中的字段清单逐项定义 builder。）

- [ ] **Step 2: 编译验证**

```bash
./gradlew --no-daemon common:compileJava
```

- [ ] **Step 3: 提交**

```bash
git add common/src/main/java/io/github/limuqy/mc/hassium/config/HassiumConfigSpec.java
git commit -m "refactor: rewrite HassiumConfigSpec with Client/Server two specs"
```

---

### Task 4: 重构 HassiumConfigService.java

**Files:**
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/config/HassiumConfigService.java`

**Interfaces:**
- Produces: 更新后的 getter 方法集
- 删除: `getCacheMaxAgeDays()`, `getBackgroundThreads()`, `getMaxCallbacksPerFrame()`, `getGlobalCompressionLevel()`, `getGlobalCompressionThreshold()`, `isBloomFilterEnabled()`, `getBloomFilterExpectedInsertions()`, `getBloomFilterFpp()`
- 新增: `getCacheCompressionLevel()`, `getLoadThreads()`, `isLightStrip()`（客户端）, `isServerLightStrip()`（服务端）
- Consumes: `HassiumConfig`（Task 2）、`HassiumConfigSpec`（Task 3）

- [ ] **Step 1: 更新 getter 方法**

关键变更：
```java
// 删除：
// getCacheMaxAgeDays(), getBackgroundThreads(), getMaxCallbacksPerFrame()
// getGlobalCompressionLevel(), getGlobalCompressionThreshold()
// isBloomFilterEnabled(), getBloomFilterExpectedInsertions(), getBloomFilterFpp()

// 新增/修改：
public int getCacheCompressionLevel() {
    return config.clientCache().cacheCompressionLevel();
}
public int getLoadThreads() {
    return config.clientCache().loadThreads();
}
public boolean isLightStrip() {
    return config.clientCache().lightStrip();
}
// 服务端光照剥离（ServerChunkPushManager 用）
public boolean isServerLightStrip() {
    return config.serverNetwork().lightStrip();
}

// 修改网络 getter 路径：
public int getCompressionLevel() {
    return config.serverNetwork().compressionLevel();
}
public boolean isMagiclessZstd() {
    return config.serverNetwork().magiclessZstd();
}
public boolean isGlobalPacketCompressionEnabled() {
    return config.serverNetwork().globalPacketCompression();
}
// ... 其他 serverNetwork getter 类似

// 修改客户端 getter 路径：
public int getMaxChunksPerFrame() {
    return Math.max(1, config.clientCache().maxChunksPerFrame());
}
public int getMainThreadChunkBudgetMs() {
    int value = config.clientCache().mainThreadChunkBudgetMs();
    return value <= 0 ? 10 : Math.min(50, value);
}
public boolean isMetricsEnabled() {
    // 客户端从 clientNetwork，服务端从 serverNetwork
    if (tomlBackend.get()) {
        return config.clientNetwork().metricsEnabled();
    }
    return config.serverNetwork().metricsEnabled();
}

// loadFromToml 更新为新文件结构
public void loadFromToml() {
    // ... 使用 FabricTomlConfigIO.load() 替代 HassiumTomlConfigIO.load()
}

// saveToToml 更新
public void saveToToml() {
    // ... 使用 FabricTomlConfigIO.save() 替代 HassiumTomlConfigIO.save()
}
```

- [ ] **Step 2: 编译验证**

```bash
./gradlew --no-daemon common:compileJava
```

- [ ] **Step 3: 提交**

```bash
git add common/src/main/java/io/github/limuqy/mc/hassium/config/HassiumConfigService.java
git commit -m "refactor: update HassiumConfigService for new config structure"
```

---

### Task 5: 创建 FabricTomlConfigIO.java（替换 HassiumTomlConfigIO）

**Files:**
- Create: `common/src/main/java/io/github/limuqy/mc/hassium/config/FabricTomlConfigIO.java`
- Delete: `common/src/main/java/io/github/limuqy/mc/hassium/config/HassiumTomlConfigIO.java`

**Interfaces:**
- Produces: `FabricTomlConfigIO.load()` / `FabricTomlConfigIO.save(HassiumConfig)`
- Consumes: `HassiumConfig`（Task 2）、`Constants.CONFIG_CLIENT_FILE` / `CONFIG_SERVER_FILE`

- [ ] **Step 1: 创建 FabricTomlConfigIO.java**

关键逻辑：
```java
public final class FabricTomlConfigIO {
    public static HassiumConfig load() {
        boolean physicalClient = Services.PLATFORM.isPhysicalClient();
        if (physicalClient) {
            return loadClient();
        } else {
            return loadServer();
        }
    }

    private static HassiumConfig loadClient() {
        // 读 client.toml → ClientCacheConfig + ClientNetworkConfig + DebugConfig
        // StorageConfig/ServerNetworkConfig/CompatConfig 用 DEFAULT
    }

    private static HassiumConfig loadServer() {
        // 读 server.toml → StorageConfig + ServerNetworkConfig + CompatConfig + DebugConfig
        // ClientCacheConfig/ClientNetworkConfig 用 DEFAULT
    }

    public static void save(HassiumConfig config) {
        boolean physicalClient = Services.PLATFORM.isPhysicalClient();
        if (physicalClient) {
            writeClient(config.clientCache(), config.clientNetwork(), config.debug());
        } else {
            writeServer(config.storage(), config.serverNetwork(), config.compat(), config.debug());
        }
    }
}
```

- [ ] **Step 2: 删除 HassiumTomlConfigIO.java**

- [ ] **Step 3: 编译验证**

```bash
./gradlew --no-daemon common:compileJava
```

- [ ] **Step 4: 提交**

```bash
git add common/src/main/java/io/github/limuqy/mc/hassium/config/
git commit -m "refactor: replace HassiumTomlConfigIO with FabricTomlConfigIO"
```

---

### Task 6: 更新客户端缓存相关文件

**Files:**
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/cache/client/ClientHassiumStorage.java`
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/cache/client/ChunkBloomFilter.java`
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/cache/client/ClientMainThreadBudget.java`
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/cache/client/ViewDistanceExtensionService.java`

**Interfaces:**
- Consumes: `HassiumConfig.ClientCacheConfig`（Task 2）、`HassiumConfigService`（Task 4）

- [ ] **Step 1: 更新 ClientHassiumStorage.java**

```java
// 修改构造函数中的 Bloom filter 初始化：
// 旧：
boolean bloomEnabled = HassiumConfigService.getInstance().isBloomFilterEnabled();
this.bloomFilter = bloomEnabled ? ChunkBloomFilter.fromConfig() : null;
// 新：
this.bloomFilter = ChunkBloomFilter.createDefault(); // 始终启用

// 修改压缩等级：
// 旧：
private static final int DEFAULT_COMPRESSION_LEVEL = 9;
byte[] compressed = compressWithDictionary(nbtData, DEFAULT_COMPRESSION_LEVEL);
// 新：
int level = HassiumConfigService.getInstance().getCacheCompressionLevel();
byte[] compressed = compressWithDictionary(nbtData, level);
```

- [ ] **Step 2: 更新 ChunkBloomFilter.java**

```java
// 删除 fromConfig() 方法
// 删除：
public static ChunkBloomFilter fromConfig() { ... }

// 保留 createDefault()（硬编码 10000, 0.01）
```

- [ ] **Step 3: 更新 ClientMainThreadBudget.java**

```java
// 旧：
int normalBudgetMs = HassiumConfigService.getInstance().getMainThreadChunkBudgetMs();
// 新：路径不变（getter 内部已改为读 clientCache.mainThreadChunkBudgetMs）

// 旧：
return HassiumConfigService.getInstance().getMaxChunksPerFrame();
// 新：路径不变（getter 内部已改为读 clientCache.maxChunksPerFrame）
```

此文件无需实际修改——getter 名称未变，只是内部路径变了。

- [ ] **Step 4: 更新 ViewDistanceExtensionService.java**

无需修改——所有调用的 getter 名称未变（`isClientCacheEnabled()`、`isViewDistanceExtensionEnabled()`、`getMaxRenderDistance()`、`getOvdUnloadDelaySecs()`）。

- [ ] **Step 5: 编译验证**

```bash
./gradlew --no-daemon common:compileJava
```

- [ ] **Step 6: 提交**

```bash
git add common/src/main/java/io/github/limuqy/mc/hassium/cache/client/
git commit -m "refactor: update client cache for new config structure"
```

---

### Task 7: 更新网络层文件

**Files:**
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/network/HassiumAggregationPacket.java`
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/network/ChunkCompressionHandler.java`
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/network/SectionDeltaS2CPacket.java`
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/network/PacketCompressionBlacklist.java`
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/network/ServerChunkPushManager.java`

**Interfaces:**
- Consumes: `HassiumConfigService`（Task 4）

- [ ] **Step 1: 更新 HassiumAggregationPacket.java — 硬编码 magiclessZstd**

```java
// encode() 中：
// 旧：
boolean magicless = config.isMagiclessZstd();
// 新：
boolean magicless = true; // 聚合包统一 ZSTD，硬编码

// decode() 中：
// 旧：
boolean magicless = config.isMagiclessZstd();
// 新：
boolean magicless = true; // 硬编码
```

- [ ] **Step 2: 更新 ChunkCompressionHandler.java**

无需修改——`getNetworkCompressionLevel()` 和 `getNetworkCompressionAlgorithm()` getter 名称未变。

- [ ] **Step 3: 更新 SectionDeltaS2CPacket.java**

无需修改——`getNetworkCompressionLevel()` getter 名称未变。

- [ ] **Step 4: 更新 PacketCompressionBlacklist.java**

无需修改——`getCompressionBlacklist()` getter 名称未变。

- [ ] **Step 5: 更新 ServerChunkPushManager.java**

```java
// 旧：
boolean stripLight = HassiumConfigService.getInstance().isLightStripEnabled();
// 新：
boolean stripLight = HassiumConfigService.getInstance().isServerLightStrip();
```

其余 getter（`getServerChunkPushThreads()`、`getMinPushThreads()`、`getMaxPushThreads()`、`isDynamicThreadPoolEnabled()`）名称未变。

- [ ] **Step 6: 编译验证**

```bash
./gradlew --no-daemon common:compileJava
```

- [ ] **Step 7: 提交**

```bash
git add common/src/main/java/io/github/limuqy/mc/hassium/network/
git commit -m "refactor: update network layer for new config structure"
```

---

### Task 8: 更新 Mixin 文件

**Files:**
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/mixin/MixinLightRecompute.java`
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/mixin/MixinOptions.java`
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/mixin/MixinClientPacketListener.java`
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/mixin/MixinChunkHolder.java`
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/mixin/MixinRegionFile.java`

- [ ] **Step 1: 更新 MixinLightRecompute.java**

```java
// 旧：
if (!HassiumConfigService.getInstance().isLightStripEnabled()) {
// 新：
if (!HassiumConfigService.getInstance().isLightStrip()) {
```

- [ ] **Step 2: 更新其他 Mixin 文件**

检查所有 Mixin 中的 config getter 调用，确保使用新名称。大部分 getter 名称未变，只需关注：
- `isLightStripEnabled()` → `isLightStrip()`（MixinLightRecompute）
- 其余 Mixin（MixinOptions、MixinClientPacketListener、MixinChunkHolder、MixinRegionFile）的 getter 名称未变

- [ ] **Step 3: 编译验证**

```bash
./gradlew --no-daemon common:compileJava
```

- [ ] **Step 4: 提交**

```bash
git add common/src/main/java/io/github/limuqy/mc/hassium/mixin/
git commit -m "refactor: update Mixin files for new config getters"
```

---

### Task 9: 更新 CommonClass.java 和 DebugLogger.java

**Files:**
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/CommonClass.java`
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/utils/DebugLogger.java`

- [ ] **Step 1: 更新 CommonClass.java**

```java
// 旧：
NetworkStats.setEnabled(configService.isMetricsEnabled());
// 新：不变（getter 名称未变，内部路径已更新）
```

此文件无需实际修改。

- [ ] **Step 2: 更新 DebugLogger.java**

无需修改——`config.debug()` 路径未变。

- [ ] **Step 3: 编译验证**

```bash
./gradlew --no-daemon common:compileJava
```

Expected: common 模块编译通过。

- [ ] **Step 4: 提交**

```bash
git add common/src/main/java/io/github/limuqy/mc/hassium/CommonClass.java
git commit -m "refactor: verify CommonClass compiles with new config"
```

---

### Task 10: 更新 Forge 加载器入口

**Files:**
- Modify: `forge/src/main/java/io/github/limuqy/mc/hassium/HassiumMod.java`

- [ ] **Step 1: 更新 spec 注册**

```java
// 旧：
registerConfig(ModConfig.Type.CLIENT, HassiumConfigSpec.CLIENT_SPEC, Constants.CONFIG_CLIENT_FILE);
registerConfig(ModConfig.Type.COMMON, HassiumConfigSpec.COMMON_SPEC, Constants.CONFIG_COMMON_FILE);
registerConfig(ModConfig.Type.SERVER, HassiumConfigSpec.SERVER_SPEC, Constants.CONFIG_SERVER_FILE);

// 新：
registerConfig(ModConfig.Type.CLIENT, HassiumConfigSpec.CLIENT_SPEC, Constants.CONFIG_CLIENT_FILE);
registerConfig(ModConfig.Type.SERVER, HassiumConfigSpec.SERVER_SPEC, Constants.CONFIG_SERVER_FILE);
```

删除 COMMON_SPEC 注册行。FCAP 路径同理。

- [ ] **Step 2: 编译验证**

```bash
./gradlew --no-daemon forge:compileJava
```

- [ ] **Step 3: 提交**

```bash
git add forge/src/main/java/io/github/limuqy/mc/hassium/HassiumMod.java
git commit -m "refactor: update Forge entry for two-spec config"
```

---

### Task 11: 更新 NeoForge 加载器入口

**Files:**
- Modify: `neoforge/src/main/java/io/github/limuqy/mc/hassium/HassiumNeoForge.java`

- [ ] **Step 1: 更新 spec 注册**

同 Task 10，删除 COMMON_SPEC 注册行。

- [ ] **Step 2: 编译验证**

```bash
./gradlew --no-daemon neoforge:compileJava
```

- [ ] **Step 3: 提交**

```bash
git add neoforge/src/main/java/io/github/limuqy/mc/hassium/HassiumNeoForge.java
git commit -m "refactor: update NeoForge entry for two-spec config"
```

---

### Task 12: 更新 Fabric 加载器入口

**Files:**
- Modify: `fabric/src/main/java/io/github/limuqy/mc/hassium/HassiumClientMod.java`

- [ ] **Step 1: 检查 FabricClientMod / FabricServerMod**

确认 Fabric 端的 config 加载逻辑（`loadFromToml()` 调用点）。如果存在独立的服务端入口类，也需要更新。

- [ ] **Step 2: 编译验证**

```bash
./gradlew --no-daemon fabric:compileJava
```

- [ ] **Step 3: 提交**

```bash
git add fabric/src/main/java/io/github/limuqy/mc/hassium/
git commit -m "refactor: update Fabric entry for new config structure"
```

---

### Task 13: 更新 Cloth 配置屏幕

**Files:**
- Modify: `cloth-ui/src/main/java/io/github/limuqy/mc/hassium/client/HassiumClothConfigScreen.java`

**Interfaces:**
- Consumes: `HassiumConfig.ClientCacheConfig`、`HassiumConfig.ClientNetworkConfig`、`HassiumConfig.DebugConfig`（Task 2）

- [ ] **Step 1: 重写配置屏幕**

关键变化：
- 删除 `Draft` 中的：`cacheMaxAgeDays`、`cacheBloomFilterEnabled`、`cacheBloomFilterExpectedInsertions`、`cacheBloomFilterFpp`、`backgroundThreads`、`maxCallbacksPerFrame`、所有服务端字段（storage*、serverNetwork*、compat*）
- 新增 `Draft` 中的：`cacheCompressionLevel`、`loadThreads`、`lightStrip`（在 clientCache 下）
- `toConfig()` 只构建客户端 config（`ClientCacheConfig` + `ClientNetworkConfig` + `DebugConfig`），服务端字段用 DEFAULT
- 删除 Category 4（存储与通用）、Category 5（压缩与聚合）中的服务端字段
- 重新组织 category：客户端缓存（14项）→ 超视渲染与分段增量（6项）→ 客户端线程与应用（4项）→ 网络开关（2项）→ 调试（7项）

- [ ] **Step 2: 编译验证**

```bash
./gradlew --no-daemon cloth-ui:compileJava
```

- [ ] **Step 3: 提交**

```bash
git add cloth-ui/src/main/java/io/github/limuqy/mc/hassium/client/HassiumClothConfigScreen.java
git commit -m "refactor: update Cloth config screen for client-only fields"
```

---

### Task 14: 更新语言文件

**Files:**
- Modify: `common/src/main/resources/assets/hassium/lang/en_us.json`
- Modify: `common/src/main/resources/assets/hassium/lang/zh_cn.json`

- [ ] **Step 1: 删除废弃键**

删除以下键：
- `hassium.configuration.clientCache.maxAgeDays` / `.tooltip`
- `hassium.configuration.clientCache.bloomFilterEnabled` / `.tooltip`
- `hassium.configuration.clientCache.bloomFilterExpectedInsertions` / `.tooltip`
- `hassium.configuration.clientCache.bloomFilterFpp` / `.tooltip`
- `hassium.configuration.clientNetwork.backgroundThreads` / `.tooltip`
- `hassium.configuration.clientNetwork.maxCallbacksPerFrame` / `.tooltip`

- [ ] **Step 2: 新增/修改键**

新增：
- `hassium.configuration.clientCache.cacheCompressionLevel` = "缓存压缩等级"
- `hassium.configuration.clientCache.loadThreads` = "加载线程数"
- `hassium.configuration.clientCache.lightStrip` = "光照剥离"

修改（移动到 clientCache 分类下）：
- `hassium.configuration.clientNetwork.clientChunkLoadThreads` → `hassium.configuration.clientCache.loadThreads`
- `hassium.configuration.clientNetwork.lightStripEnabled` → `hassium.configuration.clientCache.lightStrip`
- `hassium.configuration.clientNetwork.maxChunksPerFrame` → `hassium.configuration.clientCache.maxChunksPerFrame`
- `hassium.configuration.clientNetwork.mainThreadChunkBudgetMs` → `hassium.configuration.clientCache.mainThreadChunkBudgetMs`

- [ ] **Step 3: 提交**

```bash
git add common/src/main/resources/assets/hassium/lang/
git commit -m "refactor: update lang files for new config keys"
```

---

### Task 15: 全量编译验证 + 清理

**Files:**
- 全项目

- [ ] **Step 1: 全量编译**

```bash
./gradlew --no-daemon common:compileJava fabric:compileJava forge:compileJava neoforge:compileJava
```

Expected: 全部通过。如有错误，逐个修复。

- [ ] **Step 2: 运行测试**

```bash
./gradlew --no-daemon common:test
```

- [ ] **Step 3: 清理残留引用**

搜索代码中残留的旧引用：
- `NetworkConfig` → 应全部替换为 `ClientNetworkConfig` / `ServerNetworkConfig`
- `COMMON_SPEC` / `CONFIG_COMMON_FILE` → 应全部删除
- `HassiumTomlConfigIO` → 应全部替换为 `FabricTomlConfigIO`
- `isBloomFilterEnabled` / `getBloomFilterExpectedInsertions` / `getBloomFilterFpp` → 应全部删除
- `getCacheMaxAgeDays` / `getBackgroundThreads` / `getMaxCallbacksPerFrame` → 应全部删除

- [ ] **Step 4: 最终提交**

```bash
git add -A
git commit -m "refactor: config restructure complete — client.toml + server.toml"
```
