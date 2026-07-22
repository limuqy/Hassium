# 缓存存储光照数据设计方案

## 背景

Profiling 显示 `Minecraft.tick()` 中 Hassium 占 66% 时间（2844ms / 4320ms），核心瓶颈在 `ClientCacheLoadQueue.processQueueUntil`（2528ms）。根因：renderOnly 区块的光照重算完全同步在主线程，单区块 ~5-6ms。

原版 Anvil 格式存储光照数据，加载零开销。Hassium 缓存不存储光照（`is_light_on=0`），每次命中都触发 `ClientLightRecomputeService.applyLightEngineNow()`，包含 `propagateLightSources`（1212ms）和 `pullLightFromNeighborEdges`（596ms）。

## 目标

在磁盘缓存中存储光照数据，使缓存命中时直接应用预计算光照，消除同步重算开销。

## 约束

- **网络带宽不变**：服务器继续 `lightStrip`，不在网络传输光照数据
- **首次加载仍有重算开销**：这是可接受的一次性成本
- **chunkHash 不受影响**：hash 只覆盖 blockStates，排除 LightData
- **不需要向后兼容**：新项目，旧缓存可丢弃

## 安全性分析

### chunkHash 兼容性
`ChunkContentHashUtil` 明确排除 LightData（注释："输入域对齐 NEB：sections + 确定性 heightmap/BE，排除 LightData"）。hash 只覆盖 blockStates，添加光照存储不影响缓存命中逻辑。

### 方块变更光照更新
服务端方块变更后发送的 `ClientboundLightUpdatePacket` 未被 Hassium 拦截，但这不影响缓存：
- 该包只影响客户端已加载的活跃区块
- 缓存命中的区块是视距外的 renderOnly 区块
- 光照是方块状态的确定性函数：相同 block states = 相同光照
- 当玩家走近，区块重新进入视距时服务器会重新发送最新状态

### 跨区块光照传播
`ClientLightRecomputeService` 左前对目标区块 + 4 个邻居做 `propagateLightSources`，并通过 `pullLightFromNeighborEdges` 从邻居边缘拉取光照。存储光照后，这个开销只在首次计算时发生一次。

## 设计

### 数据流

```
首次加载（缓存未命中）:
  服务器 --[lightStrip]--> 客户端接收（空光照）
    -> handleLevelChunkWithLight（vanilla 应用方块数据）
    -> MixinLightRecompute 检测空光照
    -> applyLightEngineNow（同步重算，一次性开销）
    -> 写入缓存（含光照数据，is_light_on=1）

缓存命中:
  磁盘读取 NBT（含光照数据）
    -> nbtToPacketBytes（从 NBT 读光照，写入 packet）
    -> handleLevelChunkWithLight（vanilla 应用方块 + 光照）
    -> MixinLightRecompute 检测到光照已存在，跳过重算
    -> 零额外 CPU 开销
```

### NBT Schema 变更

**现状：**
```
sections: [
  {"data": <block state bytes>, "has_only_air": <bool>},
  ...
]
is_light_on: 0
```

**变更后：**
```
sections: [
  {
    "data": <block state bytes>,
    "has_only_air": <bool>,
    "sky_light": <byte[2048]>,    // 新增：天空光照
    "block_light": <byte[2048]>   // 新增：方块光照
  },
  ...
]
is_light_on: 1
```

- 每 section 增加 4 KiB（sky + block light 各 2048 bytes）
- Overworld 24 sections：+96 KiB/区块（未压缩）
- ZSTD 压缩后预估 +20-30 KiB/区块（光照数据高度可压缩）

### 修改的文件

#### 1. `ChunkDiskCodec.java`

**`packetBytesToNbt()`（读取路径 — 从 packet 构建 NBT）：**
- 现状：读取 block states 和 block entities 后停止，忽略光照数据
- 变更：从 packet 字节流读取光照数据（skyYMask, blockYMask, skyUpdates, blockUpdates）
- 将每 section 的 `byte[2048]` 存入 section NBT 的 `"sky_light"` 和 `"block_light"`
- 设置 `is_light_on = 1`

**`levelChunkToNbt()`（Live-Unload 路径）：**
- 现状：传递 `emptyMask` 给 packet 构造函数
- 变更：从 `LevelLightEngine` 提取每 section 的 `DataLayer`，构建真实 BitSet
- 传递真实 bitmask 给 packet 构造函数，使光照数据被序列化

**`nbtToPacketBytes()`（重建路径 — 从 NBT 构建 packet）：**
- 现状：调用 `writeEmptyLightData(buf)` 写空光照
- 变更：检查 `is_light_on` 标志
  - `is_light_on = 1`：从 section NBT 读取 `"sky_light"` 和 `"block_light"`，调用新方法 `writeLightDataFromNbt()` 写入真实光照
  - `is_light_on = 0`：保持现有行为（写空光照，触发重算）

**`buildChunkNbt()`：**
- 参数化 `is_light_on`，由调用方决定值

#### 2. `ChunkPacketDataCompat.java`

**新增 `writeLightDataFromNbt(FriendlyByteBuf buf, ListTag sections, int sectionCount)`：**
- 遍历 sections，检查每个 section 是否有 `"sky_light"` / `"block_light"`
- 构建 BitSet（哪些 section 有天空/方块光照）
- 按 wire format 写入：
  ```
  skyYMask (BitSet)
  blockYMask (BitSet)
  emptySkyYMask (BitSet)  -- 有数据但全空的 section
  emptyBlockYMask (BitSet)
  skyUpdates (List<byte[2048]>)
  blockUpdates (List<byte[2048]>)
  ```

**新增 `readLightDataFromPacket(FriendlyByteBuf buf, int sectionCount, ListTag sections)`：**
- 从 packet 字节流读取光照数据
- 解析 BitSet 确定哪些 section 有数据
- 读取 `byte[2048]` 数组，存入对应 section 的 NBT

#### 3. `MixinLightRecompute.java`

- 无需修改。现有逻辑已正确：检测到 packet 有光照数据时跳过重算
- 缓存命中路径中，packet 包含真实光照 → Mixin 不触发重算

#### 4. `ClientChunkHandler.java`

- `applyChunkData()` 中对 renderOnly 区块的 `applyLightEngineNow()` 调用
- 变更：检查缓存是否已含光照（`is_light_on=1`），若已含则跳过重算
- 或者：由于 `nbtToPacketBytes()` 会写入真实光照，`MixinLightRecompute` 自然跳过，无需额外检查

#### 5. `docs/chunk-cache.md`

- 更新 section 11.2 的 NBT schema 文档
- 记录 `is_light_on` 的新含义和光照字段

### 光照提取方式

从 `LevelLightEngine` 提取光照数据（用于 `levelChunkToNbt` 写入路径）：

```java
LevelLightEngine lightEngine = level.getLightEngine();
LayerLightEventListener sky = lightEngine.getLayerListener(LightLayer.SKY);
LayerLightEventListener block = lightEngine.getLayerListener(LightLayer.BLOCK);

for (int sectionY = minSection; sectionY < maxSection; sectionY++) {
    SectionPos sectionPos = SectionPos.of(chunkPos.x, sectionY, chunkPos.z);
    DataLayer skyData = sky.getDataLayerData(sectionPos);
    DataLayer blockData = block.getDataLayerData(sectionPos);

    byte[] skyBytes = (skyData != null && !skyData.isEmpty()) ? skyData.getData() : null;
    byte[] blockBytes = (blockData != null && !blockData.isEmpty()) ? blockData.getData() : null;

    // 存入 section NBT
    if (skyBytes != null) sectionTag.putByteArray("sky_light", skyBytes);
    if (blockBytes != null) sectionTag.putByteArray("block_light", blockBytes);
}
```

### 缓存写入时机

有两个写入路径需要处理：

1. **`packetBytesToNbt()`**：从服务器接收区块时构建 NBT
   - 此时 packet 可能有光照（`lightStrip=false`）或无光照（`lightStrip=true`）
   - 有光照：直接读取存入 NBT，`is_light_on=1`
   - 无光照：不存光照，`is_light_on=0`（首次加载后由路径 2 补充）

2. **`levelChunkToNbt()`**：Live-Unload 时构建 NBT
   - 此时区块已在客户端加载，光照引擎已有数据
   - 从 `LevelLightEngine` 提取光照，`is_light_on=1`

对于路径 1 中 `lightStrip=true` 的情况，光照数据在首次加载后才可用。需要在 `applyLightEngineNow()` 完成后，将光照数据回写到缓存。这可以通过：
- 在 `ClientLightRecomputeService.applyLightEngineNow()` 完成后，调用缓存更新方法
- 或者：延迟缓存写入，等到光照计算完成后再序列化

**推荐方案**：在 `ClientChunkHandler.applyChunkData()` 中，`applyLightEngineNow()` 完成后，从 `LevelLightEngine` 提取光照数据，更新缓存中的 NBT（设置 `is_light_on=1`，添加 `sky_light` / `block_light` 字段）。

### 详细缓存写入流程

需要确认缓存写入的确切代码路径。有两个场景：

**场景 A：服务器发送区块（首次加载，缓存 MISS）**
1. 服务器发送 `ClientboundLevelChunkWithLightPacket`（lightStrip=true，空光照）
2. Hassium 拦截，发送 ChunkHashS2CPacket
3. 客户端请求完整区块数据
4. 服务器发送压缩区块数据
5. 客户端解压 → `packetBytesToNbt()` → 写入缓存（`is_light_on=0`，此时无光照数据）
6. `applyToLevelFromByteBuf()` 应用到世界
7. `applyLightEngineNow()` 重算光照 ← **瓶颈**
8. **新增步骤**：提取光照数据，更新缓存 NBT（`is_light_on=1`）

**场景 B：缓存 HIT**
1. 从磁盘读取 NBT（`is_light_on=1`，含光照数据）
2. `nbtToPacketBytes()` 从 NBT 读光照写入 packet
3. `applyToLevelFromByteBuf()` 应用到世界（方块 + 光照）
4. `MixinLightRecompute` 检测到光照已存在，跳过重算 ← **零开销**

**场景 C：Live-Unload（区块离开视距）**
1. 从 `LevelLightEngine` 提取光照数据
2. `levelChunkToNbt()` 构建 NBT（`is_light_on=1`）
3. 写入缓存

**关键点**：场景 A 的步骤 8 不需要立即回写缓存。光照重算只影响客户端内存中的 `LevelLightEngine`，缓存中的光照数据在区块卸载时（场景 C）由 `levelChunkToNbt()` 统一捕获。首次加载后，只需将缓存标记为 `is_light_on=0`（光照过时），下次加载时触发重算。

**简化后的场景 A：**
1. 服务器发送压缩区块数据（lightStrip=true，空光照）
2. 客户端解压 → `packetBytesToNbt()` → 写入缓存（`is_light_on=0`）
3. `applyToLevelFromByteBuf()` 应用到世界
4. `applyLightEngineNow()` 重算光照（一次性开销）
5. **无需回写缓存** — 光照数据在区块卸载时自然写入

## 光照更新拦截（方块变更路径）

### 背景

方块变更（放置/破坏）后，服务端光照引擎重新计算光照并发送 `ClientboundLightUpdatePacket`。该包携带完整光照数据（每 section 2048 bytes），Hassium 目前**未拦截**，直接透传给客户端。

### 设计

拦截 `ClientboundLightUpdatePacket`，剥离光照数据，仅通知客户端受影响的区块坐标。客户端本地标记 dirty 并重算光照。缓存不立即更新——光照不影响 hash，区块卸载时由 `levelChunkToNbt()` 统一写入最新光照。

```
服务端方块变更:
  LightEngine 重算 → 确定受影响区块
  → 拦截 ClientboundLightUpdatePacket
  → 发送 LightDeltaS2CPacket (仅坐标，无光照数据)

客户端接收 LightDeltaS2CPacket:
  → 标记对应 section dirty
  → 本地光照引擎重算 (跳过跨区块传播)
  → 标记缓存 is_light_on=0 (光照过时，不立即更新)

区块卸载:
  → levelChunkToNbt() 从 LevelLightEngine 提取最新光照
  → 写入缓存 is_light_on=1
```

**关键简化**：不需要在方块变更时更新缓存光照数据。理由：
1. `LightUpdatePacket` 只更新已加载区块，不影响缓存中的 renderOnly 区块
2. 光照不影响 chunkHash，缓存条目对 block states 仍然有效
3. `is_light_on=0` 标记光照过时，下次缓存加载触发重算
4. 区块卸载时 `levelChunkToNbt()` 自然捕获最新光照

### 新增包：`LightDeltaS2CPacket`

遵循现有 record 模式：

```java
public record LightDeltaS2CPacket(List<Entry> entries) {
    public static final ResourceLocation CHANNEL =
        ResourceLocationCompat.create(Constants.MOD_ID, "light_delta_s2c");

    public record Entry(int chunkX, int chunkZ,
                        BitSet skyYMask, BitSet blockYMask) {}
}
```

**Wire format：**
```
[entryCount:VarInt]
[chunkX:VarInt, chunkZ:VarInt,
 skyYMask:BitSet, blockYMask:BitSet] * N
```

- 无光照数据（`byte[2048]` 数组），仅坐标和 section 位掩码
- 可批量发送（类似 ChunkHashS2CPacket 的批次机制）

### 服务端拦截

在 `MixinChunkHolder.broadcast()` 中新增 `ClientboundLightUpdatePacket` 拦截：

```java
if (packet instanceof ClientboundLightUpdatePacket lightPacket) {
    // 分离 Hassium / 非 Hassium 玩家
    // 对 Hassium 玩家：取消原包，发送 LightDeltaS2CPacket
    // 对非 Hassium 玩家：发送原包
    ci.cancel();
}
```

提取信息：
- `chunkX`, `chunkZ`：从 packet 字段读取
- `skyYMask`, `blockYMask`：从 packet 的 light data 部分读取（哪些 section 有光照更新）

### 客户端处理

`ClientMetadataHandler` 新增 `handleLightDeltaPacket()`：

1. 遍历 entries
2. 对每个 (chunkX, chunkZ, skyYMask, blockYMask)：
   - 获取 `LevelLightEngine`
   - 对 bitmask 中每个 section：
     - 调用 `lightEngine.updateSectionStatus(sectionPos, false)` 标记非空
     - 调用 `level.setSectionDirtyWithNeighbors(sectionPos)` 标记脏
   - 调用 `lightEngine.propagateLightSources(chunkPos)` 重算目标区块光照
   - **跳过** `pullLightFromNeighborEdges`（服务端已处理传播，客户端只需本地重算）
3. 标记缓存 `is_light_on=0`（光照过时，不立即更新光照数据）

### 与缓存的交互

方块变更后，缓存中的光照数据过时。处理方式：

- **区块在活跃加载中**：收到 LightDeltaS2CPacket → 本地重算光照 → 标记缓存 `is_light_on=0`
- **区块在缓存中（renderOnly）**：不受影响（LightUpdatePacket 只影响已加载区块）
- **区块卸载时**：`levelChunkToNbt()` 从光照引擎提取最新光照 → 写入缓存 `is_light_on=1`
- **区块再次从缓存加载**：若 `is_light_on=0` → 触发 `applyLightEngineNow()` 重算

### 配置

新增配置项 `serverNetwork.lightDeltaStrip`（默认 true）：
- `true`：拦截 `ClientboundLightUpdatePacket`，发送 `LightDeltaS2CPacket`
- `false`：不拦截，透传原包（兼容模式）

### 新增/修改的文件

| 文件 | 变更 |
|------|------|
| `LightDeltaS2CPacket.java` | **新增**：光照增量包 record |
| `HassiumPacketIds.java` | 新增 `LIGHT_DELTA_S2C` 通道 ID |
| `FabricPayloadRegistry.java` | 注册新 payload type |
| `NetworkManager.java` | 新增 `sendLightDeltaPacket()` 抽象方法 |
| `FabricNetworkManager.java` | 实现发送 + 客户端接收注册 |
| `MixinChunkHolder.java` | 新增 `ClientboundLightUpdatePacket` 拦截 |
| `ClientMetadataHandler.java` | 新增 `handleLightDeltaPacket()` |
| `ClientLightRecomputeService.java` | 新增轻量重算方法（跳过传播） |
| `PacketCompressionBlacklist.java` | 新包加入黑名单（自有轻量格式） |
| `HassiumConfigService.java` | 新增 `lightDeltaStrip` 配置 |

## 客户端统计：光照缓存

### 新增指标

在 `HassiumMetrics` / `HassiumMetricsImpl` 中新增光照缓存计数器：

| 指标 | 类型 | 含义 |
|------|------|------|
| `lightCacheHitCount` | AtomicLong | 缓存含光照数据（`is_light_on=1`）的区块数 |
| `lightCacheMissCount` | AtomicLong | 缓存不含光照数据（`is_light_on=0`）需重算的区块数 |
| `lightRecomputeTimeNs` | AtomicLong | 光照重算总耗时（纳秒） |
| `lightDeltaReceivedCount` | AtomicLong | 收到 LightDeltaS2CPacket 的条目数 |

### 录入点

| 调用点 | 录入 |
|--------|------|
| `ClientChunkHandler` 缓存 apply 且 `hasCachedLight` | `recordLightCacheHit()` |
| `MixinLightRecompute` 空光照触发重算 | `recordLightCacheMiss()` |
| `ClientLightRecomputeService.applyLightEngine()` | `recordLightRecomputeTime(elapsedNs)` |
| `ClientMetadataHandler.handleLightDeltaPacket()` | `recordLightDeltaReceived(count)` |

（`nbtToPacketBytes` 不再记 hit/miss，避免暖缓存假 100%。）

### 显示

在 `/hassiumc stats` 输出中新增第 5 节「光照缓存」：

```
§e光照缓存：§r%s（命中 %s，更新 %s，重算 %s）
```

### 计算比率

`HassiumMetrics` 接口新增默认方法：
- `getLightCacheHitRate()` = `hitCount / (hitCount + missCount)` — 缓存中光照数据的命中率
- `getLightRecomputeTimeMs()` = `lightRecomputeTimeNs / 1_000_000` — 重算耗时（毫秒）

### 修改的文件

| 文件 | 变更 |
|------|------|
| `HassiumMetrics.java` | 新增 4 个 getter + 2 个计算比率 |
| `HassiumMetricsImpl.java` | 新增 4 个 AtomicLong 字段 + recorder 方法 + reset |
| `NetworkStats.java` | 新增 4 个 `recordLightXxx()` 静态方法 |
| `HassiumCommandHandler.java` | 新增「光照缓存」显示节 |
| `ClientSmokeTest.java` | `validateStats()` 新增「光照缓存」校验 |

### 单元测试

在 `common/src/test/java/.../metrics/` 新增或扩展测试：
- `lightCacheHitCount` / `lightCacheMissCount` 递增和 reset
- `lightCacheHitRate` 计算（全命中 / 全未命中 / 混合 / 零除保护）
- `lightRecomputeTimeNs` 累加和 reset
- `lightDeltaReceivedCount` 递增

## 冒烟测试

### 已完成：1.20.1 Fabric

**结果**：PASS ✅

| Round | 光照缓存命中 | 需重算 | 重算耗时 | 命中率 |
|-------|-------------|--------|----------|--------|
| 1（首次加载） | 0 | 0 | ~1200ms | 0% |
| 2（缓存命中） | 1150 | 319 | 467ms | **78.3%** |

**修复记录**：
- 初始实现依赖卸载时从光照引擎提取数据，命中率仅 ~20%
- 修复：在 `applyLightEngineNow()` 完成后立即回写缓存，命中率提升至 78.3%
- 剩余 21.7% 未命中：部分区块在光照回写前已卸载，或 renderOnly 区块不写回缓存

## 自动测试验证计划

### 单元测试（无 Minecraft 依赖）

| 测试类 | 测试内容 |
|--------|----------|
| `HassiumMetricsImplTest` | 扩展：lightCacheHitCount/MissCount 递增、reset、hitRate 计算 |
| `ChunkDiskCodecTest` | 扩展：`is_light_on=1` 时 nbtToPacketBytes 写入真实光照；`is_light_on=0` 时写空光照 |
| `ChunkPacketDataCompatTest` | **新增**：writeLightDataFromNbt / readLightDataFromPacket 的 round-trip |
| `LightDeltaS2CPacketTest` | **新增**：encode/decode round-trip、空列表、批量 entries |

### 集成测试（需要 Minecraft 环境）

| 测试类 | 测试内容 |
|--------|----------|
| `LightCacheIntegrationTest` | **新增**：模拟区块加载 → 光照重算 → 缓存写入 → 缓存读取 → 验证光照数据一致 |

### 验证矩阵

| 维度 | 单元测试 | 集成测试 | 冒烟测试 |
|------|----------|----------|----------|
| 光照存储/读取 | ChunkDiskCodecTest, ChunkPacketDataCompatTest | LightCacheIntegrationTest | 冒烟用例 1,2 |
| 光照缓存命中率 | HassiumMetricsImplTest | — | 冒烟用例 6 |
| 光照更新拦截 | — | — | 冒烟用例 3,4,7,8 |
| renderOnly 命中 | — | LightCacheIntegrationTest | 冒烟用例 5 |
| 多版本兼容 | — | — | 阶段 2 全版本矩阵 |

### CI 集成

- 单元测试：`./gradlew common:test` — 每次提交自动运行
- 冒烟测试：手动执行，阶段 1 在 PR 合并前必须通过，阶段 2 在发版前必须通过

## 实施步骤

1. ✅ 修改 `ChunkPacketDataCompat`：新增 `writeLightDataFromNbt()` 和 `readLightDataFromPacket()`
2. ✅ 修改 `ChunkDiskCodec.packetBytesToNbt()`：从 packet 读取光照存入 NBT
3. ✅ 修改 `ChunkDiskCodec.nbtToPacketBytes()`：从 NBT 读光照写入 packet
4. ✅ 修改 `ChunkDiskCodec.levelChunkToNbt()`：从 LevelLightEngine 提取光照
5. ✅ 修改 `ChunkDiskCodec.buildChunkNbt()`：参数化 `is_light_on`
6. ✅ 新增 `LightDeltaS2CPacket` 及其完整注册链
7. ✅ 修改 `MixinChunkHolder`：拦截 `ClientboundLightUpdatePacket`
8. ✅ 新增客户端 `handleLightDeltaPacket()` + 轻量重算方法
9. ✅ 新增 `lightDeltaStrip` 配置项
10. ✅ 新增光照缓存指标（HassiumMetrics + NetworkStats + 显示）
11. ✅ 更新 `docs/chunk-cache.md` 文档
12. ✅ 冒烟测试：1.20.1 Fabric PASS（命中率 78.3%）
13. ✅ 收口：renderOnly `hasCachedLight` 跳过同步重算；hit/miss 改在 apply/Mixin 决策点录入
14. ✅ 收口：`markCacheLightStale` 真写盘并保留 contentHash；`CacheSaveQueue.flushAsync` 非 interrupt 排空

## 验证结果

| 验证项 | 结果 |
|--------|------|
| 1.20.1 Fabric 冒烟测试 | ✅ PASS |
| 光照缓存命中率 | ✅ 78.3%（Round 2，收口前；指标语义已修正，需复测） |
| `/hassiumc stats` 光照缓存节 | ✅ 正常显示 |
| 编译（common + fabric + neoforge） | ✅ 全部通过 |
| 指标单元测试 | ✅ 通过 |
| renderOnly 有光照跳过重算 | ✅ 已实现（待冒烟确认重算耗时下降） |
