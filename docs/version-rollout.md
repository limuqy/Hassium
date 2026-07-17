# 多版本适配推进顺序（Rollout）

与 [`version-segments.md`](version-segments.md) 配套。按 **A → B → C → … → I** 推进，禁止并行铺满 9 段。

## 当前状态

**九段适配已全部完成。** 关键运行时回归矩阵（用户确认，2026-07-18）：

| 锚点 | 回归 |
|------|------|
| 1.20.1 | ✅ 通过 |
| 1.20.5 | ✅ 通过 |
| 1.21.1 | ✅ 通过（含 `PlayerCompat.getConnection` / 线程池缩容修复后） |
| 1.21.11 | ✅ 通过 |

| 段 | 锚点 | 状态 | 说明 |
|----|------|------|------|
| A | 1.20.1 | **已完成 / 已联调** | 回归通过 |
| B | 1.20.2 | **已完成 / 已联调** | NeoForge SimpleChannel；段内继承 |
| C | 1.20.5 | **已完成 / 已联调** | 回归通过；Forge 1.20.6 此前已测通 |
| D | 1.21.1 | **已完成 / 已测通** | 回归通过；**不构建 Forge** |
| E | 1.21.2 | **已完成 / Fabric+NeoForge 已测通** | 用户确认联调通过 |
| F | 1.21.5 | **已完成 / 用户接受** | CompoundTag / ProtocolInfo；heightmaps；**客户端缓存不跨 MC 版本兼容** |
| G | 1.21.6 | **已完成 / 已测通** | 段内 1.21.7/1.21.8 继承 |
| H | 1.21.9 | **已完成 / 已测通** | 段内 1.21.10 继承 |
| I | 1.21.11 | **已完成 / 已测通** | Identifier；PermissionCompat；回归通过 |

### 客户端缓存跨版本策略（自段 F 起）

自 **1.21.5** 起正式接受：客户端区块缓存存的是当前 MC 的 chunk packet 线格式，**不保证跨 MC 大版本读写兼容**。

- 同 MC 版本内（含 Fabric↔NeoForge）正常命中与覆盖写入
- 升版本后旧缓存可懒覆盖（MISS → 重拉 → `persist`），**不**做启动时整库按版本作废
- 不承诺也不实现跨版本迁移 / 格式协商；后续若要隔离可另开「按 protocol/mc_ver 分目录」任务

## 段 F checklist（1.21.5）

- [x] `CompoundTagCompat`：`getAllKeys`→`keySet`、标量 Tag `*Value()`（已有）
- [x] `PacketCodecCompat`：`ProtocolInfo.Unbound` → `SimpleUnboundProtocol` / `UnboundProtocol` + `GameProtocols.Context`
- [x] `ChunkPacketDataCompat`：heightmaps NBT→StreamCodec（修复 sectionHashes 解析 → 命中率 0）
- [x] 锚点编译：`fabric` / `neoforge` `-Pmc_ver=1.21.5`（2026-07-18）
- [x] Fabric `runServer` Done（2026-07-18）
- [x] 用户确认：段 F 可过；接受 1.21.5+ 客户端缓存不跨版本兼容（2026-07-18）

## 段 G checklist（1.21.6）

- [x] `PlayerCompat`：`serverLevel()`→`level()`（已有）；业务侧经 Compat 访问
- [x] `MixinServerPlayer`：`Player` 构造签名（已有）
- [x] `MixinConnection`：`send(Packet, PacketSendListener)` → `send(Packet, ChannelFutureListener)`
- [x] NeoForge：`EventBusSubscriber.bus` 移除（`HassiumNeoForgeClient`）
- [x] 锚点编译：`fabric` / `neoforge` `-Pmc_ver=1.21.6`（2026-07-18）
- [x] Fabric `runServer` Done（2026-07-18）
- [x] Fabric + NeoForge 进服联调通过（用户确认，2026-07-18）

## 段 H checklist（1.21.9）

- [x] `LevelChunkSectionCompat`：`PalettedContainerFactory.create`（已有）
- [x] NeoForge：`FMLLoader.getCurrent()`（已有）
- [x] `PlayerCompat.getMinecraftServer`：`Entity.getServer()` 移除
- [x] `MixinMinecraft.setLevel`：1.21.9+ 恢复单参数（去掉 ReceivingLevelScreen.Reason）
- [x] `BlockEntityCompat`：1.21.6+ `loadWithComponents(ValueInput)`（修复 NeoForge `[BLOCK_ENTITY]` 反射失败）
- [x] 锚点编译：`fabric` / `neoforge` `-Pmc_ver=1.21.9`（2026-07-18）
- [x] Fabric `runServer` Done（2026-07-18）
- [x] Fabric + NeoForge 进服联调通过（用户确认，2026-07-18）

## 段 I checklist（1.21.11）

- [x] `ResourceLocationCompat`：业务侧经 Compat；返回类型 Identifier
- [x] `PacketPayloadCompat` / `PacketCodecCompat`：ID 类型 Identifier；`readResourceLocation`→`readIdentifier`
- [x] `PermissionCompat`：`hasPermission(int)` → `permissions().hasPermission(...)`
- [x] 锚点编译：`fabric` / `neoforge` `-Pmc_ver=1.21.11`（2026-07-18）
- [x] Fabric `runServer` Done（2026-07-18）
- [x] Fabric + NeoForge 进服联调通过（用户确认，2026-07-18）
- [x] 关键回归矩阵通过：1.20.1 / 1.20.5 / 1.21.1 / 1.21.11（用户确认，2026-07-18）

### Forge 支持范围

| MC 版本 | Forge |
|---------|-------|
| 1.20.1 | ✅ `builds_for` 含 forge |
| 1.20.6（段 C 段尾） | ✅ `builds_for` 含 forge |
| **1.21.0+** | ❌ **已取消**（Loom/SecureJar 模块冲突适配成本过高；1.21 用 NeoForge） |

## 段 C 门控（已解除）

`NetworkCapability.isCustomChannelFullySupported()` 在全部目标版本返回 true。此前当其为 false（`MC_VER >= MC_1_20_5`）时：

1. `CommonClass.init()` 强制 `networkCompressionEnabled = false` 并打 WARN
2. 各加载器 `registerChannels` / `registerClientChannels` / NeoForge `registerPayloads` 跳过注册

### 解除门控条件（段 C Done）

全部满足后，将 `NetworkCapability.isCustomChannelFullySupported()` 改为对 1.20.5+ 返回 true：

- [x] `HassiumAggregationManager`：原版包可用 StreamCodec / RegistryFriendlyByteBuf 序列化（`PacketCodecCompat`）
- [x] `NamespaceIndexManager`：不再依赖已移除的 `getPacketsByIds`（`GameProtocols` + `IdDispatchCodec`）
- [x] `PacketPayloadCompat.extractPayloadData`：非 RawCustomPayload 也可经注册 codec / `data()` 提取
- [x] 锚点 1.20.5 Fabric + NeoForge 运行冒烟通过（Fabric：进服握手成功；NeoForge：Payload 注册 + runServer Done）
- [x] Forge 1.20.6：`ChannelBuilder` + `play()` 注册 4 C2S / 6 S2C；客户端握手与 `/hassiumc stats` 联调通过
- [x] 联调确认（2026-07-18）：Fabric/NeoForge 1.20.5、Forge 1.20.6 测试通过

## 段 D checklist（1.21.1）

- [x] `DisconnectCompat` / Mixin `onDisconnect(DisconnectionDetails)` 签名核对
- [x] `ResourceLocationCompat`：业务侧无散落 `new ResourceLocation(...)`
- [x] `PacketCodecCompat`：`GameProtocols.CLIENTBOUND/SERVERBOUND` → `*_TEMPLATE`（`MC_1_21_1`）
- [x] `MixinChunkHolder`：不再 `@Shadow pos`（改从 packet `getX/getZ`）
- [x] 锚点编译：`fabric` / `neoforge` `-Pmc_ver=1.21.1`（2026-07-18）
- [x] Fabric `runServer` Done（2026-07-18）
- [x] Fabric + NeoForge 进服联调通过（用户确认）
- [x] 决定取消 1.21+ Forge 支持（2026-07-18）
- [x] `PlayerCompat.getConnection`：沿继承链取 `connection`（修 1.20.2+ `NoSuchFieldException`）
- [x] `ServerChunkPushManager`：缩容先降 core，避免 `max < core`
- [x] 段 I 后回归再测通过（用户确认，2026-07-18）

## 段 E checklist（1.21.2）

- [x] Fabric + NeoForge 进服联调通过（用户确认）

## 推荐验证命令

```bash
# 非法分界
./gradlew scanVersionBoundaries

# 段 I 锚点（无 forge）
./gradlew fabric:compileJava neoforge:compileJava "-Pmc_ver=1.21.11"

# 全锚点矩阵（耗时长；各锚点按 builds_for，1.21+ 不含 forge）
./gradlew compileAnchors
# 或: powershell -File scripts/compile-anchors.ps1
```

运行时优先级锚点 **1.20.1 / 1.20.5 / 1.21.1 / 1.21.11** 均已回归通过（2026-07-18）；九段收官。
