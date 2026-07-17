# 有效版本区间（Version Segments）

本文档是 Hassium 多版本适配的**唯一真相源**。工作单位不是「17 个 MC 版本 × 3 加载器」，而是 **9 个有效代码段 × `builds_for` 中的加载器**。

相关：Manifold `#if MC_VER`（见 `.claude/skills/hassium-dev/SKILL.md`）、`versionProperties/*.properties`。

---

## 原则

1. **一段只做一次代码适配**；段内版本默认「锚点编译通过即继承」，不单独排期。
2. **人力只跟锚点走**；发布仍可保留全部 `versionProperties`（Manifold 便宜）。
3. Mojang API 差异必须进 [`common/.../compat/`](../common/src/main/java/io/github/limuqy/mc/hassium/compat/)；业务 / Mixin **禁止**新增散落 `#if MC_VER`（网络适配器内的整段版本块除外，见下文）。
4. **合法分界常量**仅限下表；禁止随手引入 `MC_1_21_3` 等碎片边界（扫描任务会失败）。

---

## 有效分界点

| 分界常量 | common 变因 | fabric 特有 | forge 特有 | neoforge 特有 |
|----------|-------------|-------------|------------|---------------|
| `MC_1_20_2` | `onDisconnect` 上移、`CustomPayload` 包路径、`createPacket` | — | 旧 `newSimpleChannel` 断；1.20.6+ 用 `ChannelBuilder` | forge→neoforge 包名；仍用 SimpleChannel + `PlayNetworkDirection`（非 StreamCodec） |
| `MC_1_20_5` | `Packet.write()` 移除、`BlockEntity.load()` 移除、`getPacketsByIds` 移除 | 网络改 StreamCodec | ChunkPacket STREAM_CODEC（若构建） | Payload + StreamCodec（`RegisterPayloadHandlersEvent`） |
| `MC_1_21_1` | `Component` → `DisconnectionDetails`；RL 构造私有化；`GameProtocols.CLIENTBOUND/SERVERBOUND` → `*_TEMPLATE`；`ChunkHolder.pos` 上移至 `GenerationChunkHolder`；`ProtocolInfo.Unbound.listPackets` | — | — | — |
| `MC_1_21_2` | `ChunkSerializer` → `SerializableChunkData`、`registryOrThrow` → `lookupOrThrow` | — | — | — |
| `MC_1_21_5` | `CompoundTag` API（`getAllKeys`→`keySet` 等）；`ProtocolInfo.Unbound` → `SimpleUnboundProtocol` / `UnboundProtocol`（SERVERBOUND 需 `GameProtocols.Context`）；`ClientboundLevelChunkPacketData` heightmaps NBT→StreamCodec | — | — | — |
| `MC_1_21_6` | `serverLevel()` → `level()`；`ServerPlayer` 构造精简；`Connection.send` 监听器 `PacketSendListener`→`ChannelFutureListener`；`BlockEntity.load*` → `ValueInput` | — | `SubscribeEvent` 包路径 | `EventBusSubscriber.bus` 移除（按事件自动选总线） |
| `MC_1_21_9` | `LevelChunkSection` 构造、`PalettedContainerFactory`；`Entity.getServer()` 移除；`Minecraft.setLevel` 去掉 Reason | — | — | `FMLLoader.getCurrent()` |
| `MC_1_21_11` | `ResourceLocation` → `Identifier`；`FriendlyByteBuf.read/writeResourceLocation` → `read/writeIdentifier` | import + 返回值 | import | — |

另：`MC_1_21_4` / `PermissionCompat` 等若与上表冲突，以 **compat 类内注释的实际切分点**为准，并应并入上表后再使用。

### 合法 `#if` 边界白名单（扫描用）

```
MC_1_20_2
MC_1_20_5
MC_1_21_1
MC_1_21_2
MC_1_21_5
MC_1_21_6
MC_1_21_9
MC_1_21_11
```

（`MC_1_20_1` 为基准，通常只出现在注释；比较式用 `< MC_1_20_2` 表达 1.20.1。）

历史遗留：代码中偶见 `MC_1_21_4`（与 `MC_1_21_5` 等价边界）。新代码禁止新增；清扫时统一为 `MC_1_21_5`。

---

## 九段 × 锚点

| 段 | 锚点（必编 / 必测） | 段内其余版本 | 进入本段后的关键变化 |
|----|---------------------|--------------|----------------------|
| A | **1.20.1** | — | 基准：旧网络 + 全部旧 API |
| B | **1.20.2** | 1.20.3, 1.20.4 | CustomPayload / NeoForge 包名；段内无 Forge builds_for |
| C | **1.20.5** | 1.20.6 | StreamCodec；`Packet.write` 等移除（无 1.21.0 属性文件时以 1.20.6 为段尾） |
| D | **1.21.1** | — | `DisconnectionDetails`；RL 构造私有化；`GameProtocols.*_TEMPLATE` |
| E | **1.21.2** | 1.21.3, 1.21.4 | `SerializableChunkData`、`lookupOrThrow` |
| F | **1.21.5** | — | CompoundTag API；ProtocolInfo Unbound 拆分；chunk heightmaps 线格式；**客户端缓存不跨 MC 版本兼容**（见文末附录） |
| G | **1.21.6** | 1.21.7, 1.21.8 | `serverLevel()`→`level()`；Connection.send 监听器；NeoForge EBS.bus 移除 |
| H | **1.21.9** | 1.21.10 | LevelChunkSection / PalettedContainerFactory；getServer 移除；setLevel 单参 |
| I | **1.21.11** | — | Identifier |

### 锚点 × `builds_for`（编译矩阵）

以各 `versionProperties/<ver>.properties` 的 `builds_for` 为准；下表为当前快照：

| 锚点 | 加载器 |
|------|--------|
| 1.20.1 | fabric, forge, neoforge |
| 1.20.2 | fabric, neoforge |
| 1.20.5 | fabric, neoforge |
| 1.21.1 | fabric, neoforge |
| 1.21.2 | fabric, neoforge |
| 1.21.5 | fabric, neoforge |
| 1.21.6 | fabric, neoforge |
| 1.21.9 | fabric, neoforge |
| 1.21.11 | fabric, neoforge |

> **Forge 仅支持 1.20.1 与 1.20.6**（段 A / 段 C 段尾）。**1.21+ 不构建 Forge**（Loom SecureJar / Automatic-Module-Name 冲突适配成本过高；1.21 请用 NeoForge）。

本地 / CI：

```bash
./gradlew scanVersionBoundaries
./gradlew compileAnchors          # 或 scripts/compile-anchors.ps1 / .sh
```

推进顺序：按 **A → I** 锚点推进；禁止并行铺满九段。当前状态见文末附录。

---

## 分界线 Checklist（改完必勾）

每完成一条分界相关改动：

- [ ] **common**：差异是否已收入对应 `*Compat`（禁止业务新 `#if`）
- [ ] **fabric / forge / neoforge**：该分界「特有」列是否改过
- [ ] **Mixin**：目标方法签名是否用该版本 sources jar 核对
- [ ] **网络**：该段是否仍为「功能门控关闭」状态（见下）
- [ ] **锚点编译**：`./gradlew <loader>:compileJava -Pmc_ver=<锚点>` 通过

---

## 网络子系统分段

| 分界 | 动作 |
|------|------|
| 1.20.1 | 现有实现（Fabric 已测通） |
| 1.20.2 | CustomPayload 路径；段内无 Forge；1.20.6+ Forge 用 ChannelBuilder play() |
| 1.20.5 | STREAM_CODEC / `type()`；聚合写包、原版包枚举等 common 能力 |
| 其后 | 多为 common API；网络协议少变 |

加载器内网络适配器允许 **≤3 个整段实现块**（`<1.20.2` / `1.20.2–1.20.4` / `≥1.20.5`），禁止每个 send/receive 再套一层碎片 `#if`。

### 功能门控（段 C 已完成）

段 C 完成后 `NetworkCapability.isCustomChannelFullySupported()` 恒为 true；`CommonClass.init()` 不再因版本强制关闭网络。

- 各加载器 `registerChannels` / 握手入口仍尊重配置项 `HassiumConfigService.isNetworkCompressionEnabled()`
- 实现细节见 `PacketCodecCompat`（StreamCodec / GameProtocols / IdDispatchCodec）

运行时验证优先级：**1.20.1 → 1.20.5 → 1.21.11**；其余锚点以编译 + 短冒烟为主。

---

## Compat 对照表

| 类 | 分界 | 职责 |
|----|------|------|
| `PacketPayloadCompat` | 1.20.2 / 1.20.5 / 1.21.11 | CustomPayload ID / 数据 / 构造 |
| `ResourceLocationCompat` | 1.21.1 / 1.21.11 | RL / Identifier 创建 |
| `RegistryCompat` | 1.21.2 | registryOrThrow / lookupOrThrow |
| `DisconnectCompat` | 1.21.1 | onDisconnect 参数 |
| `PermissionCompat` | 1.21.11 | 命令权限 API |
| `PlayerCompat` | 1.20.2 / 1.21.6 / 1.21.9 | `serverLevel()` / `level()`；`getServer()`→`level().getServer()`；`getConnection` 沿继承链取 `connection` |
| `BlockEntityCompat` | 1.20.5 / 1.21.6 | `load` / `loadWithComponents(CompoundTag)` / `ValueInput` |
| `LevelChunkSectionCompat` | 1.21.9 | Section 构造 |
| `CompoundTagCompat` | 1.21.5 | keys / 标量读取 |
| `ChunkPacketDataCompat` | 1.21.5 | chunk packet heightmaps 跳过/复制（NBT→StreamCodec） |
| `ChunkDataCompat` | 1.21.2 | Mixin 目标类说明（序列化入口） |
| `NetworkCapability` | 1.20.5 | 自定义通道是否完整可用（段 C 后恒 true） |
| `PacketCodecCompat` | 1.20.5 / 1.21.1 / 1.21.5 / 1.21.11 | StreamCodec 聚合写包 / GameProtocols 包枚举 / ProtocolInfo bind / Payload 提取；`listPackets` 自 1.21.1（此前走 IdDispatchCodec）；`readResourceLocation`→`readIdentifier` |

---

## 明确不做什么

- 不引入按版本的 Gradle source set / 子模块复制
- 不追求每个小版本手测
- 不在 `builds_for` 不含 forge 的版本上硬撑 Forge 网络
- **不构建 Forge 1.21+**（已正式取消）
- 不把 Identifier rename 散落到业务文件

---

## 附录：九段适配状态（2026-07-18）

**九段适配已全部完成。** 关键运行时回归：1.20.1 / 1.20.5 / 1.21.1 / 1.21.11 通过。

| 段 | 锚点 | 状态 |
|----|------|------|
| A–I | 见上表 | 已完成 / 已联调 |

### 客户端缓存跨版本策略（自段 F / 1.21.5）

客户端区块缓存存的是当前 MC 的 chunk packet 线格式，**不保证跨 MC 大版本读写兼容**。

- 同 MC 版本内（含 Fabric↔NeoForge）正常命中与覆盖写入
- 升版本后旧缓存可懒覆盖（MISS → 重拉 → persist），不做启动时整库作废
- 不实现跨版本迁移 / 格式协商

### Forge 支持范围

| MC 版本 | Forge |
|---------|-------|
| 1.20.1 | ✅ `builds_for` 含 forge |
| 1.20.6 | ✅（段 C 段尾） |
| 1.21+ | ❌ 使用 NeoForge |
