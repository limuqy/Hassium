# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

Hassium 是一个 Minecraft 1.20.1 模组，目标是优化区块存储和网络传输。使用 ZSTD 压缩算法替代原版 Zlib，并实现客户端区块缓存以减少带宽占用。项目基于 multiloader 模板，同时支持 Forge 和 Fabric。

## 构建命令

```bash
# 构建所有平台
./gradlew build

# 仅构建 Fabric
./gradlew fabric:build

# 仅构建 Forge
./gradlew forge:build

# 运行开发客户端
./gradlew fabric:runClient    # Fabric 客户端
./gradlew forge:runClient     # Forge 客户端

# 运行开发服务端
./gradlew fabric:runServer    # Fabric 服务端
./gradlew forge:runServer     # Forge 服务端

# 反编译 Minecraft（首次需要）
./gradlew common:decompile

# 编译单个模块
./gradlew common:compileJava
./gradlew fabric:compileJava
./gradlew forge:compileJava
```

## 项目架构

### 多模块结构

```
Hassium/
├── common/          # 共享代码，仅依赖原版 Minecraft
├── fabric/          # Fabric 加载器特定代码
├── forge/           # Forge 加载器特定代码
└── buildSrc/        # Gradle 构建插件
```

### 核心设计原则

- **common 模块**：包含所有与加载器无关的逻辑（存储、压缩、缓存、网络协议）
- **加载器模块**：通过 Java ServiceLoader 机制注入平台特定实现
- **common 不能访问 forge/fabric 代码**，但反过来可以

### 已实现的包结构

```
io.github.limuqy.mc.hassium
├── api/             # 公共 API（HassiumApi, HassiumCapabilities）
├── storage/         # Region 兼容存储（接口和数据类）
├── compression/     # ZSTD 压缩与字典（接口和占位实现）
├── cache/           # 客户端区块缓存（接口和数据类）
├── network/         # 网络协议（数据包定义）
├── config/          # 配置管理（配置记录类）
├── metrics/         # 性能指标（接口和统计类）
├── migration/       # 格式迁移工具（接口和结果类）
└── platform/        # 平台抽象层（Services.java）
```

### 平台抽象

使用 `java.util.ServiceLoader` 实现跨平台抽象：
- 接口定义在 `common/src/main/java/.../platform/services/`
- 实现类在各加载器模块的 `platform/` 包下
- 通过 `META-INF/services/` 文件注册实现

## 关键技术细节

### 存储格式

- 保持 Region/Anvil 外层结构（`.mca` 文件，32x32 chunk per region）
- 使用 ZSTD 压缩区块数据
- 支持 CRC32C 校验和验证

### 压缩策略

- 默认 ZSTD level 3（无字典）
- 字典压缩使用独立训练的字典文件
- 字典缺失时拒绝写入 Hassium payload，回退原版
- **待实现**：需要添加 zstd-jni 依赖（com.github.luben:zstd-jni）

### 网络协议

- 自定义通道 `hassium:*` 传输压缩数据
- 客户端/服务端能力握手协商
- 缓存元数据（chunkRevision）校验避免重复传输
- 数据包 ID 定义在 `HassiumPacketIds`
- **区块缓存推送系统**（2026-07-05 改造）：
  - 拦截 `ChunkHolder.broadcast()` 和 `ServerPlayer.trackChunk()` 发送元数据
  - 服务端直接发送区块元数据（位置+时间戳），不排队
  - 客户端收到元数据后直接比对本地缓存
  - 缓存命中：加入 `ClientCacheLoadQueue`，线程池异步加载
  - 缓存未命中：发送 `ChunkDataRequestC2SPacket` 请求服务端数据
  - 服务端 `ServerChunkPushManager` 使用线程池+优先级队列处理数据请求
  - 可配置线程池大小（`serverChunkPushThreads`、`clientChunkLoadThreads`）

### 配置系统

- 使用 Java record 定义配置结构
- 默认配置：存储关闭、缓存启用、Hassium 通道压缩启用、全局包压缩启用
- 配置类：`HassiumConfig`（包含 StorageConfig、ClientCacheConfig、NetworkConfig、CompatConfig）
- **NetworkConfig 配置项**：
  - `enabled`: 启用 Hassium 自定义通道压缩（默认 true）
  - `globalPacketCompression`: 启用全局包压缩，用 ZSTD 替换原版 Zlib（默认 true）
  - `compressionAlgorithm`: 压缩算法（默认 "hassium:zstd"）
  - `compressionLevel`: 压缩等级（默认 9）
  - `minPacketSize`: 最小压缩包大小（默认 1024 字节）
  - `customChannelOnly`: 仅使用自定义通道（默认 true）
  - `maxChunksPerTick`: 每玩家每 tick 最大发送区块数（默认 10）
  - `serverChunkPushThreads`: 服务端区块推送线程数（默认 2）
  - `clientChunkLoadThreads`: 客户端区块加载线程数（默认 2）
  - `metricsEnabled`: 启用网络指标收集（默认 true）
  - `metricsReportIntervalTicks`: 指标定期报告间隔 ticks（默认 0 = 不定期）

### 网络流量监控

- 轻量级指标收集：`AtomicLong` 计数器，零分配，~15ns/次
- 服务端/客户端指标分离：各 JVM 独立统计
- 命令系统：
  - `/hassium stats` — 服务端统计（需 OP 2）
  - `/hassiumc stats` — 客户端统计
  - `/hassium metrics on|off` — 运行时开关
  - `/hassium stats reset` — 重置计数器
- 指标关闭时命令不可用，避免歧义
- 详细文档：`docs/network-monitoring.md`

## 开发注意事项

1. **版本兼容性**：当前目标 1.20.1，设计需预留 1.20.5+ compression scheme 127 升级路径
2. **存档安全**：存储功能默认关闭（`storage.enabled = false`），启用前提示备份
3. **Mixin 注入点**：
   - 存储：`RegionFile`、`ChunkStorage`、`IOWorker`、`ChunkSerializer`
   - 网络：`ChunkHolder`（区块广播拦截）、`ServerPlayer`（trackChunk 拦截）
4. **配置热更新**：部分配置可运行时修改，部分需要重启
5. **已完成**：
 - ✅ 添加 zstd-jni 依赖
 - ✅ 实现 ZSTD 压缩/解压逻辑
 - ✅ 实现 Hassium 封套编解码
 - ✅ 单元测试通过
 - ✅ 网络压缩系统（ZSTD level 3 + 字典，6-9:1 压缩比）
 - ✅ 区块缓存推送系统（服务端元数据推送 + 客户端自主决策 + 线程池异步处理）
 - ✅ 客户端/服务端握手协议
 - ✅ 网络流量监控（`/hassium stats` 服务端、`/hassiumc stats` 客户端）
 - ✅ 清理旧代码（移除 ChunkCacheService、旧缓存查询协议等 7 个文件）
 - ✅ 压缩算法优化（默认 level 3，速度提升 2-3 倍）
 - ✅ 动态线程池调整（ThreadPoolExecutor 自动扩缩容）
 - ✅ Bloom 预筛（客户端 ChunkBloomFilter 减少无效 IO）
 - ✅ sectionHashes → chunkHash 推导（combineSectionHashes，避免客户端重复计算）
 - ✅ 区块卸载缓存写入（MixinClientLevel.unload + CacheSaveQueue 含 sectionHashes 计算）
 - ✅ section 数据合并（replaceSectionsInPacket 精确 section 级别替换）
 - ✅ blockEntity 请求优化（BlockEntityRequestC2SPacket + BlockEntityDataS2CPacket 专用通道）
 - ✅ GlobalPalette 解析修复（skipPalettedContainer 支持 bits>=9 的 GlobalPalette）
 - ✅ 线程安全修复（submitMetadataTask 从 packet 数据计算 hash，避免并发读 LevelChunk）
6. **待办事项**：
 - 集成测试：在实际 Minecraft 环境中测试新区块缓存流程
 - 性能优化：批量元数据发送优化
 - 区块预加载：基于玩家移动方向预测性加载区块

## 依赖版本

- Minecraft: 1.20.1
- Java: 17
- Forge: 47.2.30
- Fabric Loader: 0.16.9
- Fabric API: 0.92.1+1.20.1
- Mixin: 0.8.5

## 资源文件

- `gradle.properties`：版本号、模组 ID、作者等配置
- `settings.gradle`：项目名称和子模块声明
- `buildSrc/`：自定义 Gradle 插件处理多模块构建
- `hassium.mixins.json`：Mixin 配置文件

## 文档

- `docs/hassium-requirements.md`：功能需求和可行性分析
- `docs/hassium-development.md`：详细开发文档和架构设计
- `docs/chunk-cache-refactor.md`：区块缓存系统改造技术文档
- `docs/chunk-preload-optimization.md`：区块加载优化技术方案（方向性优先级 + 智能热点预加载）
- `docs/network-monitoring.md`：网络流量与缓存监控方案

## 区块缓存推送系统架构（2026-07-05 改造）

### 核心组件

1. **ServerChunkPushManager** (`common/src/main/java/.../network/ServerChunkPushManager.java`)
   - 服务端区块推送管理器
   - 管理数据请求队列（`PriorityBlockingQueue`，按距离排序）
   - 使用线程池异步压缩和发送区块数据
   - `sendMetadata()`：直接发送元数据（不入队）
   - `enqueueDataRequest()`：数据请求入队

2. **MixinChunkHolder** (`common/src/main/java/.../mixin/MixinChunkHolder.java`)
   - 拦截 `ChunkHolder.broadcast()` 方法
   - 对 Hassium 客户端发送元数据（位置+时间戳）
   - 对原版客户端走原版发送路径

3. **MixinServerPlayer** (`common/src/main/java/.../mixin/MixinServerPlayer.java`)
   - 拦截 `trackChunk()` 方法（新区块开始跟踪时）
   - 对 Hassium 客户端发送元数据
   - 断开连接时清理 ServerChunkPushManager

4. **ClientCacheLoadQueue** (`common/src/main/java/.../cache/client/ClientCacheLoadQueue.java`)
   - 客户端区块加载队列
   - 使用线程池异步加载缓存
   - 主线程每帧应用区块到世界

### 队列处理流程

```
服务端:
ChunkHolder.broadcast() / ServerPlayer.trackChunk()
    ↓ (拦截)
MixinChunkHolder / MixinServerPlayer
    ↓ (直接发送元数据)
ChunkMetadataS2CPacket → 客户端

客户端:
收到 ChunkMetadataS2CPacket
    ↓ (直接比对缓存)
┌────────┴────────┐
缓存命中         缓存未命中
↓                   ↓
ClientCacheLoadQueue  发送 ChunkDataRequestC2SPacket
↓                       ↓
线程池加载            服务端收到请求
↓                       ↓
主线程应用            ServerChunkPushManager
                          ↓ (线程池)
                      压缩+发送 ChunkPayloadS2C
```

### 关键算法

- **距离排序**：`sqrt((chunkX - playerChunkX)² + (chunkZ - playerChunkZ)²)`，近距离优先
- **时间戳比对**：客户端缓存时间戳 >= 服务端时间戳 = 缓存命中
- **兼容性检查**：`PlayerCompressionTracker.isCompressionEnabled(player)`
