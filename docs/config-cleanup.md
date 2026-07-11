# 配置项清理与接入开发文档

> 日期：2026-07-11
> 目标：统一解决配置项"定义但未使用"问题，清理冗余字段，接入遗漏逻辑

## 一、总览

| 操作 | 数量 | 配置项 |
|------|------|--------|
| 改为从配置读取 | 2 | `compressionAlgorithm`, `zstdDictionaryId` |
| 接入遗漏逻辑 | 4 | `requireClientMod`, `allowHassiumOnlyStorage`, `compressionBlacklist`, `clientChunkLoadThreads` |
| 修复硬编码 | 1 | `targetFPS`（getCurrentFPS 硬编码返回 60） |
| 删除配置字段 | 11 | 见下方清单 |
| 保留标注 TODO | 1 | `enableCompactHeader` |

## 二、改为从配置读取

### 2.1 `compressionAlgorithm`

**现状**：4 处硬编码 `"hassium:zstd"` / `"hassium:zstd_dict"`
**目标**：改为从 `HassiumConfigService.getCompressionAlgorithm()` 读取，简化引用

涉及文件：
- `fabric/.../FabricNetworkManager.java:239-240` — 握手发送算法列表
- `forge/.../ForgeNetworkManager.java:218` — 握手发送算法列表
- `common/.../api/HassiumCapabilities.java:30,46` — 默认能力值
- `common/.../migration/MigrationTool.java:63` — 迁移工具算法标识

**方案**：
- `HassiumCapabilities` 的 `clientDefaults()`/`serverDefaults()` 改为调用 config getter
- 握手发送处从 capabilities 读取，不再重复硬编码
- MigrationTool 从 config 读取

### 2.2 `zstdDictionaryId`

**现状**：5 个文件硬编码 `"hassium-dictionary"`
**目标**：改为从 `HassiumConfigService` 读取

涉及文件：
- `common/.../storage/ChunkPayloadCodec.java:20`
- `common/.../mixin/MixinRegionFile.java:278,294`
- `common/.../cache/client/ClientHassiumStorage.java:549,564`
- `common/.../compression/ResourceDictionaryLoader.java:18`
- `common/.../network/DictionaryManager.java:114`

**方案**：
- 在 `HassiumConfigService` 添加 `getZstdDictionaryId()` getter
- 各处改为从 config 读取，消除硬编码

## 三、接入遗漏逻辑

### 3.1 `requireClientMod`

**现状**：getter `isRequireClientMod()` 存在但无调用
**目标**：在握手流程中检查，未安装则拒绝连接

**方案**：
- `FabricNetworkManager.registerServerChannels()` 的握手处理中，当 `requireClientMod=true` 且客户端未发送握手包时，拒绝连接
- `ForgeNetworkManager` 同理
- 握手超时逻辑中增加检查

### 3.2 `allowHassiumOnlyStorage`

**现状**：getter 存在但无调用，`MixinRegionFile` 只检查 `storage.mode`
**目标**：删除配置字段，统一使用 `hassium_only` 逻辑，仅保留原版区块可读性

**方案**：
- 删除 `compat.allowHassiumOnlyStorage` 配置字段
- `MixinRegionFile` 的读取逻辑保持不变（已支持 `hassium_only` 模式读取失败时回退原版）
- 写入逻辑：`hassium_only` 模式下只写 Hassium 格式，不写原版备份
- `mirror` 模式下同时写入两种格式

### 3.3 `compressionBlacklist`

**现状**：用户配置黑名单从未被调用，`MixinConnection` 使用 `isHardcodedBlacklist()`
**目标**：保留配置字段，接入用户自定义黑名单逻辑

**方案**：
- `MixinConnection.onSendPacket()` 中将 `isHardcodedBlacklist()` 改为 `shouldCompress()`（包含硬编码+用户配置）
- `PacketCompressionBlacklist.shouldCompress()` 中用户配置黑名单逻辑已实现，只需确保调用链正确

### 3.4 `clientChunkLoadThreads`

**现状**：getter `getClientChunkLoadThreads()` 存在但无调用
**目标**：保留配置并实现客户端区块加载线程池功能

**方案**：
- `ClientCacheLoadQueue` 中创建线程池时使用此配置值替代硬编码
- 确保线程池大小可配置

### 3.5 `targetFPS` 硬编码修复

**现状**：`ClientCacheLoadQueue.getCurrentFPS()` 硬编码返回 60
**目标**：通过 Mixin 获取真实 FPS

**方案**：
- 在 `MixinMinecraft` 或 `MixinClientTick` 中捕获真实 FPS 到静态字段
- `getCurrentFPS()` 读取该字段

## 四、删除配置字段（11 个）

### 4.1 StorageConfig（3 个）

| 字段 | 默认值 | 原因 |
|------|--------|------|
| `writeVanillaBackup` | `true` | 备份逻辑由存储模式控制，不需要额外开关 |
| `regionCustomScheme` | `"hassium_legacy"` | 无任何引用，纯占位 |
| `zstdDictionaryId` | 改为从配置读取后保留 | ~~删除~~ → 保留并接入 |

> 注意：`zstdDictionaryId` 从"删除"改为"保留并接入"（见 2.2）

### 4.2 NetworkConfig（5 个）

| 字段 | 默认值 | 原因 |
|------|--------|------|
| `minPacketSize` | `1024` | 与原版 threshold 256 功能重复 |
| `customChannelOnly` | `true` | 无 getter 无引用 |
| `metricsReportIntervalTicks` | `0` | 有 getter 但无调用，指标命令已有 |
| `targetFPS` | ~~删除~~ → 保留并接入 | 修复 getCurrentFPS() |
| `maxSizeMb` | `2048` | 有 getter 但无直接调用 |

> 注意：`targetFPS` 从"删除"改为"保留并接入"（见 3.5）

### 4.3 ClientCacheConfig（3 个）

| 字段 | 默认值 | 原因 |
|------|--------|------|
| `renderRadius` | `24` | 有 getter 但无调用 |
| `serverAuthoritativeRadius` | `10` | 有 getter 但无调用 |
| `sendCacheMetadata` | `true` | 无 getter 无引用 |

### 4.4 CompatConfig（2 个）

| 字段 | 默认值 | 原因 |
|------|--------|------|
| `unknownPayloadPolicy` | `"reject"` | 有 getter 但无调用 |
| `allowHassiumOnlyStorage` | `false` | 统一使用 hassium_only 逻辑 |

## 五、保留标注 TODO

### 5.1 `enableCompactHeader`

**现状**：实现有 bug（CompactPacketEncoder 读取所有包的 ResourceLocation 导致 IndexOutOfBoundsException）
**处理**：保留配置字段，代码中注释标注 TODO，未来修复时将紧凑包头移到聚合包内部

## 六、实施顺序

1. ✅ **Phase 1 - 删除冗余字段**（低风险）
   - ✅ 删除 10 个未使用配置字段
   - ✅ 清理 `HassiumConfig`、`HassiumConfigService`、`CommentedJsonWriter` 中对应代码

2. ✅ **Phase 2 - 接入硬编码**（中风险）
   - ✅ `compressionAlgorithm` 改为从配置读取（`HassiumCapabilities`、`FabricNetworkManager`、`ForgeNetworkManager`）
   - ✅ `zstdDictionaryId` 改为从配置读取（`ChunkPayloadCodec`、`MixinRegionFile`、`ClientHassiumStorage`）
   - ✅ 新增 `storageCompressionAlgorithm` 配置字段

3. ✅ **Phase 3 - 接入遗漏逻辑**（中风险）
   - ✅ `requireClientMod` 握手检查（`PlayerCompressionTracker` 超时检测 + `MixinChunkHolder` 踢人）
   - ✅ `compressionBlacklist` 用户黑名单接入（`MixinConnection` 改用 `shouldCompress()`）
   - ✅ `clientChunkLoadThreads` 线程池实现（`MixinClientPacketListener` 使用配置值）
   - ✅ `targetFPS` 修复（`MinecraftAccessor` 获取真实 FPS）
   - ✅ `allowHassiumOnlyStorage` 已删除（统一使用 `hassium_only` 逻辑）

4. ✅ **Phase 4 - 验证**
   - ✅ 编译通过（common/fabric/forge 全部通过）
   - ⚠️ Javadoc 错误为预先存在的问题（缺少 @param 标签）
   - ⚠️ 单元测试失败为预先存在的问题（Minecraft 类在测试中不可用）
