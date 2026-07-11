# Hassium 开发进度

**最后更新**：2026-07-04

**项目状态**：Fabric 平台端到端验证通过（压缩传输 + 区块缓存 + Region 存储）

---

## 已完成的工作

### 1. 项目结构初始化

- [x] 创建包结构（api, storage, compression, cache, network, config, metrics, migration, benchmark）
- [x] 更新 Constants 类，使用正确的 mod_id 和 mod_name
- [x] 更新 CommonClass 初始化逻辑
- [x] 更新 mixin 配置文件
- [x] 添加 zstd-jni 依赖

### 2. API 层

- [x] `HassiumApi` - 公共 API 接口
- [x] `HassiumCapabilities` - 能力描述，用于握手协商

### 3. 存储层

- [x] `StorageMode` - 存储模式枚举（READONLY_VANILLA, MIRROR, HASSIUM_ONLY）
- [x] `ChunkStorageKey` - 区块存储键（包含 toCacheKey() 方法）
- [x] `ChunkStorageMetadata` - 区块存储元数据
- [x] `ChunkPayload` - 区块 payload 数据
- [x] `EncodedChunkPayload` - 编码后的区块 payload
- [x] `HassiumRegionStorage` - Region 存储接口
- [x] `HassiumRegionStorageImpl` - Region 存储实现（支持三种模式）
- [x] `ChunkPayloadCodec` - payload 编解码接口
- [x] `HassiumEnvelope` - Hassium 数据封套（已实现编解码）
- [x] `ChecksumUtils` - 校验和工具类（支持 CRC32C 和封套校验）
- [x] `StorageException` - 存储异常类

### 4. 压缩层

- [x] `CompressionAlgorithmId` - 压缩算法标识符
- [x] `CompressionOptions` - 压缩选项（包含工厂方法）
- [x] `CompressionCodec` - 压缩编解码器接口
- [x] `CompressionException` - 压缩异常类
- [x] `CompressionService` - 压缩服务（管理编解码器）
- [x] `DictionaryDescriptor` - 字典描述符
- [x] `DictionaryRegistry` - 字典注册表接口
- [x] `SimpleDictionaryRegistry` - 简单字典注册表实现
- [x] `VanillaZlibCodec` - 原版 Zlib 编解码器（已实现）
- [x] `ZstdCompressionCodec` - ZSTD 编解码器（已实现）
- [x] `ZstdDictionaryCompressionCodec` - ZSTD 字典编解码器（已实现）

### 5. 缓存层

- [x] `ChunkCacheMetadata` - 区块缓存元数据
- [x] `CacheDecision` - 缓存决策枚举
- [x] `ChunkRevisionTracker` - 区块版本追踪器接口
- [x] `SimpleChunkRevisionTracker` - 区块版本追踪器实现（线程安全）
- [x] `ChunkCacheService` - 区块缓存服务（处理缓存查询和决策）
- [x] `ClientChunkCache` - 客户端区块缓存（支持 LRU 清理）

### 6. 网络层

- [x] `HassiumPacketIds` - 数据包 ID 定义
- [x] `HassiumHandshake` - 握手数据包（已实现序列化/反序列化）
- [x] `ChunkCacheQueryPacket` - 缓存查询数据包（已实现序列化/反序列化）
- [x] `ChunkCacheDecisionPacket` - 缓存决策数据包（已实现序列化/反序列化）
- [x] `CompressedPayloadPacket` - 压缩区块数据包（已实现序列化/反序列化）
- [x] `NetworkManager` - 网络管理器接口
- [x] `FabricNetworkManager` - Fabric 平台网络实现（已注册通道）
- [x] `ForgeNetworkManager` - Forge 平台网络实现（已注册通道）

### 7. 配置层

- [x] `HassiumConfig` - 配置记录类（包含所有子配置）
- [x] `HassiumConfigService` - 配置服务类（支持热更新、回退开关、文件加载/保存）

### 8. 指标层

- [x] `HassiumMetrics` - 性能指标接口
- [x] `HassiumMetricsImpl` - 性能指标实现
- [x] `CompressionStats` - 压缩统计信息

### 9. 迁移工具

- [x] `MigrationResult` - 迁移结果
- [x] `MigrationException` - 迁移异常
- [x] `MigrationTool` - 迁移工具接口
- [x] `HassiumMigrationTool` - 迁移工具实现
- [x] `Scheme127MigrationPlan` - Scheme 127 迁移计划

### 10. Mixin 类

- [x] `MixinMinecraft` - Minecraft 主类 Mixin（配置加载/保存）
- [x] `MixinRegionFile` - Region 文件读写 Mixin（Hassium 封套编解码）
- [x] `MixinChunkSerializer` - 区块序列化 Mixin
- [x] `MixinIOWorker` - IOWorker Mixin
- [x] `MixinConnection` - 网络连接 Mixin（拦截区块数据包，序列化+压缩）
- [x] `MixinChunkHolder` - 区块广播 Mixin（拦截广播，加入玩家队列）
- [x] `MixinServerPlayer` - 服务端玩家 Mixin（每玩家区块队列+限速发送）
- [x] `MixinServerGamePacketListenerImpl` - 连接断开清理
- [x] `MixinClientPacketListener` - 客户端缓存协商+决策处理
- [x] `MixinClientLevel` - 客户端世界扩展（renderOnly 追踪）
- [x] `MixinClientboundLevelChunkWithLightPacket` - 区块数据包 Mixin

### 11. 客户端缓存系统

- [x] `ClientHassiumStorage` - 客户端缓存存储层（Region 文件格式 .hsr）
- [x] `ClientRegionFile` - 轻量 Region 文件实现（sector 管理+offset table）
- [x] `RegionBitmap` - Sector 分配位图
- [x] `ClientChunkHandler` - 客户端区块处理（压缩接收+缓存写入+缓存加载）
- [x] `ClientChunkMetadata` - 缓存元数据（用于批量查询）
- [x] `CacheValidationService` - 服务端缓存验证（时间戳对比）
- [x] `ViewDistanceExtensionService` - 超视距缓存渲染（骨架）
- [x] `BatchCacheQueryPacket` / `BatchCacheDecisionPacket` - 批量缓存协商协议

### 12. 区块队列传输系统

- [x] `IChunkQueuePlayer` - 区块队列玩家接口
- [x] `ChunkQueueManager` - 队列管理器
- [x] `ChunkSendQueue` - 智能发送队列（距离优先+限速）
- [x] `ChunkCompressionHandler` - 区块压缩/解压处理
- [x] `ChunkSender` - 区块发送接口
- [x] `PlayerCompressionTracker` - 玩家压缩能力追踪

### 11. 基准测试工具

- [x] `CompressionBenchmark` - 压缩基准测试工具
- [x] `DictionaryTrainer` - 字典训练工具

---

## 阶段完成状态

### 阶段 1：离线压缩基准 ✅ 已完成

- [x] 添加 zstd-jni 依赖到 build.gradle
- [x] 实现 ZSTD 压缩/解压逻辑
- [x] 创建离线压缩基准测试工具（CompressionBenchmark）
- [x] 实现字典训练工具（DictionaryTrainer）

### 阶段 2：Region 兼容旁路存储 ✅ 已完成

- [x] 实现 Region 文件读写 Mixin（MixinRegionFile）
- [x] 实现 ChunkStorage Mixin（MixinChunkSerializer）
- [x] 实现 mirror 模式存储（HassiumRegionStorageImpl）
- [x] 实现校验和验证（ChecksumUtils）

### 阶段 3：缓存元数据与自定义传输 ✅ 已完成

- [x] 实现 ChunkRevisionTracker（SimpleChunkRevisionTracker）
- [x] 实现网络数据包序列化/反序列化（所有 Packet 类）
- [x] 实现握手协议（HassiumHandshake）
- [x] 实现缓存查询和决策逻辑（ChunkCacheService）

### 阶段 4：客户端视觉缓存 ✅ 已完成

- [x] 实现客户端缓存目录（ClientChunkCache）
- [x] 实现缓存索引（ClientChunkCache）
- [x] 实现容量清理策略（LRU 策略）
- [x] 实现维度隔离

### 阶段 5：网络压缩增强 ✅ 已完成

- [x] 实现 CompressedPayloadPacket
- [x] 实现压缩统计（HassiumMetricsImpl）
- [x] 实现回退开关（HassiumConfigService）
- [x] 调研 Netty pipeline

### 阶段 6：1.20.5+ 迁移 ✅ 已完成

- [x] 实现 Scheme127MigrationPlan
- [x] 实现 legacy envelope 到 scheme 127 的映射
- [x] 实现迁移工具（HassiumMigrationTool）

### 阶段 7：区块压缩传输系统 ✅ 已完成 (Fabric)

- [x] MixinConnection 拦截 `Connection.send(Packet, PacketSendListener)`
- [x] 区块序列化使用 `chunkPacket.write(friendlyBuf)` 完整序列化
- [x] MixinChunkHolder 拦截区块广播加入玩家队列
- [x] MixinServerPlayer 实现每玩家队列+距离优先+限速发送
- [x] ChunkCompressionHandler 压缩/解压处理
- [x] FabricNetworkManager 通过自定义通道发送压缩数据
- [x] 客户端接收、解压、应用区块数据
- [x] 端到端验证通过（Fabric 平台）

### 阶段 8：客户端区块缓存 Region 存储 ✅ 已完成 (Fabric)

- [x] ClientRegionFile 轻量 Region 文件实现（.hsr 格式）
- [x] RegionBitmap sector 分配位图
- [x] ClientHassiumStorage 改用 Region 存储（32x32 分组）
- [x] 缓存写入：区块接收后写入 .hsr 文件
- [x] 缓存加载：断开重连后从本地缓存加载区块
- [x] 缓存决策状态管理（断开时清理、NOT_READY 不写入 pendingDecisions）
- [x] 端到端验证通过（Fabric 平台）

---

## 编译状态

- ✅ common 模块编译成功
- ✅ fabric 模块编译成功
- ✅ forge 模块编译成功
- ✅ 完整项目构建成功
- ✅ 单元测试通过（4/4）

---

## 端到端测试结果（Fabric 平台）

### 测试环境

- Minecraft 1.20.1
- Fabric Loader 0.16.9
- 本地服务器 + 客户端

### 测试场景

| 场景 | 结果 | 说明 |
|------|------|------|
| 服务端启动 | ✅ | 压缩系统初始化成功 |
| 客户端连接 | ✅ | 握手协商成功，压缩启用 |
| 区块压缩传输 | ✅ | ZSTD 压缩比 6:1 ~ 9:1 |
| 区块正确显示 | ✅ | 客户端正常渲染世界 |
| 移动加载新区块 | ✅ | 未生成区块正常生成+传输 |
| 断开重连-缓存写入 | ✅ | .hsr Region 文件正确生成 |
| 断开重连-缓存加载 | ✅ | 从本地缓存加载区块 |
| 缓存决策状态清理 | ✅ | 断开时清理 pendingDecisions |
| 未生成区块处理 | ✅ | NOT_READY 不阻塞后续传输 |

### 日志优化

- 高频日志（ChunkQueue、压缩/解压、区块应用）降级为 DEBUG
- 一次性初始化日志保留为 INFO
- 控制台不再刷屏

---

## 文件统计

**总计**：65+ 个 Java 文件

### 按模块分类

| 模块 | 文件数 | 说明 |
|------|--------|------|
| api | 2 | 公共 API |
| storage | 12 | 存储层（接口和实现） |
| compression | 11 | 压缩层 |
| cache/client | 8 | 客户端缓存（Region 存储+验证+扩展） |
| network | 10 | 网络层（数据包+队列+压缩处理） |
| config | 2 | 配置层 |
| metrics | 3 | 指标层 |
| migration | 5 | 迁移工具 |
| mixin | 12 | Mixin 类（服务端+客户端） |
| benchmark | 2 | 基准测试工具 |
| platform | 4 | 平台抽象（接口+Fabric/Forge 实现） |
| 其他 | 3 | Constants, CommonClass 等 |

---

## 测试结果

### 压缩测试

- ZSTD 压缩：5800 bytes → 77 bytes (1.33% 压缩率)
- Zlib 压缩：5800 bytes → 95 bytes (1.64% 压缩率)
- ZSTD 比 Zlib 更高效，压缩率提高约 19%

### 测试覆盖

- ZSTD 压缩/解压测试
- ZSTD 不同压缩等级测试
- Zlib 压缩/解压测试
- Hassium 封套编解码测试

---

## 下一步工作建议

### 短期（1-2 周）

1. **Forge 平台适配**：将 Fabric 端验证通过的压缩传输和缓存系统移植到 Forge
2. **缓存容量管理**：实现 .hsr 文件的大小统计和 LRU 清理策略
3. **缓存失效完善**：将 `CacheValidationService` 接到真实区块保存/修改链路
4. **修复全局压缩**：修改握手流程，先移除原版 Zlib，再安装 ZSTD
5. **修复紧凑包头**：改为在聚合包内部使用，而不是 Pipeline 层

### 中期（2-4 周）

1. **renderOnly 超视距渲染**：在缓存闭环稳定后推进
2. **性能测试**：压缩率、耗时、内存使用、带宽节省的量化测试
3. **字典压缩集成**：将训练好的字典用于网络传输压缩
4. **网络包字典训练**：使用真实网络包数据训练字典
5. **代理服务器支持**：处理 Velocity 等代理服务器场景

### 长期（1-2 月）

1. **兼容性测试**：不同 Forge/Fabric 版本、常见 MOD 兼容性
2. **文档完善**：用户文档和开发者文档
3. **统计命令**：`/hassium stats` 查看压缩率、缓存命中率等
4. **统计监控**：添加压缩率和 CPU 开销统计

---

## 注意事项

1. **存档安全**：存储功能默认关闭（`storage.enabled = false`），启用前提示备份
2. **Mixin 注入**：需要仔细测试，避免存档损坏
3. **配置热更新**：部分配置可运行时修改，部分需要重启
4. **网络兼容**：未安装 Hassium 的客户端应能正常连接
5. **字典缺失**：字典缺失时拒绝写入 Hassium payload，回退原版
6. **全局压缩**：已启用，原版区块数据包已加入黑名单避免双重压缩
7. **紧凑包头**：当前已禁用，需修复实现后再启用

---

## 网络优化开发记录（2026-07-04）

### 已实现的优化

1. **上下文压缩**：Per-connection ZstdCompressCtx 复用，提升压缩率 10-30%
2. **Magicless ZSTD**：去掉 4 字节魔数头，每包节省 4 字节
3. **包聚合**：多个小包合并后再压缩，减少包数量和提升压缩率
4. **紧凑包头框架**：用 VarInt 索引替换 ResourceLocation 字符串（待修复）

### 已知问题

1. **全局压缩双重压缩**：原版 Zlib + Hassium ZSTD 冲突，需修改握手流程
2. **紧凑包头实现**：错误处理所有包，需改为只处理自定义 Payload 包
3. **存档数据冲突**：测试时写入的 Hassium 格式区块需清理

### 参考资料

- 详细文档：`docs/hassium-network-optimization.md`
- NEB 项目：`D:\project\MC\NotEnoughBandwidth-1.20.1`

---

## 关键技术修复记录

### MixinConnection 方法签名（2026-07-04）

**问题**：Mixin 注入 `Connection.send(Packet)` 但实际调用链走的是 `Connection.send(Packet, PacketSendListener)`

**根因**：`ServerGamePacketListenerImpl.send(Packet)` 内部调用 `send(Packet, null)`，绕过了单参数版本

**修复**：注入目标改为 `send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V`

### 缓存数据格式（2026-07-04）

**问题**：`loadAndDecompress()` 使用 `NbtIo.readCompressed()` 读取数据，但缓存中存的是原始网络数据包字节

**修复**：`loadAndDecompress()` 返回 `byte[]`，`loadFromCacheAndApply()` 使用 `applyChunkData()` 处理

### 缓存决策状态泄漏（2026-07-04）

**问题**：`hassium$pendingDecisions` 是 static Map，断开连接时未清理，旧决策跨连接残留

**修复**：在 `onDisconnect` 中清理 `pendingDecisions`；`NOT_READY` 不写入 `pendingDecisions`
