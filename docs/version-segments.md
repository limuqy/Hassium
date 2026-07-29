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
| `MC_1_20_2` | `onDisconnect` 上移、`CustomPayload` 包路径、`createPacket` | — | 旧 `newSimpleChannel` 断；1.20.6+ 用 `ChannelBuilder` | forge→neoforge 包名；1.20.2/1.20.3 仍用 SimpleChannel + `PlayNetworkDirection`（非 StreamCodec） |
| `MC_1_20_4` | —（仅 NeoForge 网络子分界） | — | — | **NeoForge 20.4 移除 SimpleChannel**：改用 `RegisterPayloadHandlerEvent`（注意 1.20.5+ 改名 `RegisterPayloadHandlersEvent`） + `CustomPacketPayload.write/id`（无 StreamCodec / Type\<T\>） |
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
MC_1_20_4
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

> **关于 `MC_1_20_4`**：这是 NeoForge 网络子系统在段 B 内的**子分界**（仅影响 neoforge 模块）。NeoForge 20.4 移除 SimpleChannel，1.20.4 必须改用 `RegisterPayloadHandlerEvent` + `CustomPacketPayload.write/id`；段 B 的其它版本（1.20.2/1.20.3）仍走 SimpleChannel。引入此子分界是技术必要，非"碎片边界"。

---

## 九段 × 锚点

| 段 | 锚点（必编 / 必测） | 段内其余版本 | 进入本段后的关键变化 |
|----|---------------------|--------------|----------------------|
| A | **1.20.1** | — | 基准：旧网络 + 全部旧 API |
| B | **1.20.2** | 1.20.3 | CustomPayload / NeoForge 包名；段内无 Forge builds_for。NeoForge 1.20.4 因移除 SimpleChannel 走独立子分界 `MC_1_20_4`（见上） |
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
| 1.21.1 | fabric, neoforge, forge |
| 1.21.2 | fabric, neoforge |
| 1.21.5 | fabric, neoforge, forge |
| 1.21.6 | fabric, neoforge |
| 1.21.9 | fabric, neoforge |
| 1.21.11 | fabric, neoforge |

> **Forge 支持 1.20.1 / 1.20.6 / 1.21.1 / 1.21.5**；**1.21.11 暂时搁置**（architectury-loom 在 merged jar 中 ByteBufCodecs$N inner class 与 `<clinit>` 调用签名全局错位，需 loom remap 链路级修复，详见附录「Forge 1.21.x 适配」）。其余 1.21.x 小版本不构建 Forge，1.21 请用 NeoForge。
>
> **Fabric 配置**：自管 toml + Cloth/Mod Menu，**不依赖 FCAP**。FCAP 仅 Forge 1.20.6 桥接保留。

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
| 1.20.1 | 基准：现有网络实现；**UDP 数据面 + TCP 控制 Failover（主控热切 + 加权分流）落地点**（Task 1-9 commit `22c9c3f`）；后续九锚适配由 `931b393`（Fabric launcher 跨版本守卫）与 `e9a9e69`（NeoForge 主控热切 + 加权分流接线 + kcp io.netty split-package 剥离）完成，Fabric + NeoForge × 九锚点 compile 矩阵全 BUILD SUCCESSFUL |
| 1.20.2 | CustomPayload 路径；段内无 Forge；1.20.6+ Forge 用 ChannelBuilder play() |
| 1.20.4 | **仅 NeoForge**：SimpleChannel 被移除，改用 `RegisterPayloadHandlerEvent` + `CustomPacketPayload.write/id`（1.20.5+ 才有 StreamCodec） |
| 1.20.5 | STREAM_CODEC / `type()`；聚合写包、原版包枚举等 common 能力 |
| 其后 | 多为 common API；网络协议少变 |

加载器内网络适配器允许整段实现块：
- **NeoForge**：4 段（`<1.20.2` / `1.20.2–1.20.3` / `1.20.4` / `≥1.20.5`）
- **Fabric / Forge**：3 段（`<1.20.2` / `1.20.2–1.20.4` / `≥1.20.5`）

禁止每个 send/receive 再套一层碎片 `#if`。

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
- **不在 1.21.11 上构建 Forge**（暂时搁置，根因见附录「Forge 1.21.x 适配」）
- **Fabric 不引入 FCAP**（自管 toml + Cloth）；不在 Forge 1.20.6 以外硬撑 FCAP
- 不把 Identifier rename 散落到业务文件

---

## 附录：九段适配状态（2026-07-28）

**九段适配已全部完成。** 关键运行时回归：1.20.1 / 1.20.5 / 1.21.1 / 1.21.11 通过。  
多 Mod 冒烟（Fabric 1.20.1 优化包 + C2ME）见 [`mod-compat.md`](mod-compat.md) §11。

### Forge 1.20.6 重新兼容（2026-07-28）

Forge 1.20.6（段 C 段尾）此前因 kcp-netty 依赖未接入与 `ForgeNetworkManager` 的 Manifold `#endif` 缺失等问题从 `builds_for` 暂时移除，今已重新兼容并通过 `forge:compileJava -Pmc_ver=1.20.6`。修复点：

- `versionProperties/1.20.6.properties`：`builds_for` 恢复 `fabric,neoforge,forge`
- `ForgeNetworkManager.java`：补齐 89 行 `#if MC_VER < MC_1_20_2` 块缺失的 `#endif`（1.20.6 走 `#else` 方暴露 EOF 报错，即当初"一直解决不了"的根因）；`Channel` 局部变量改 `io.netty.channel.Channel` 全限定名，避免与新 import 的 `net.minecraftforge.network.Channel` 歧义；`sendIndexSyncPacket` 改用 `IndexSyncManager.createSyncPacket()` 对齐 fabric/neoforge
- `ForgeNetworkManagerService.java`：补 `sendLightDeltaPacket` 委托实现接口
- `ForgeNetworkManager.java`：补齐 `LightDeltaWrapper` / `DictionarySyncWrapper` / `IndexSyncWrapper` / `CompressionReadyWrapper` 通道；握手改为与 Fabric/NeoForge 同构的 CompressionReady ACK（客户端装 ZSTD 后暂停出站，待 IndexSync 再恢复），修复重连 `incorrect header check` 断连
- `forge/build.gradle`：移植 neoforge 的 `kcpIncoming` 配置 + `stripKcpNettyBootstrapPackage` 剥离任务，剥除 `io.netty.bootstrap.*` 后进 compile/game-layer/JiJ，规避 Forge 50+ SecureJarHandler 包占有冲突
- `forge/build.gradle`：FCAP Forge JiJ 的 `mixinextras-forge`（内嵌 `MixinExtras`）与传递依赖 `mixinextras-common` 在 Forge 50 JPMS 下双模块同包导出 → `ResolutionException`；`stripFcapMixinExtrasJij` 剥除 FCAP 内嵌 JiJ，只保留单一 `mixinextras-common`

| 段 | 锚点 | 状态 |
|----|------|------|
| A–I | 见上表 | 已完成 / 已联调 |

### Forge 1.21.x 适配（1.21.1 / 1.21.5 通过；1.21.11 搁置）

Forge 自 1.21.x 起仍为 Forge 风格 API，与 NeoForge 不兼容；Hassium 通过 `neoforge` 子项目以 `loom.platform = 'forge'` 覆盖承载 Forge 构建（Forge 仅 1.20.1 / 1.20.6 / 1.21.1 / 1.21.5 有 userdev）。

**1.21.1（段 D，commit 1461705）**：`HassiumMod.java` 改用 `ForgeConfigRegistration.register(...)` + 原生 `ModConfigEvent`；forge-only jar prepend 策略（仅 forge packages + 资源 + AMN，不含 mc class）让 ClasspathLocator 不抢 minecraft 路径。

**1.21.5（段 F，commit 6114071）**：`forge/build.gradle` 在 `cloth_config_version` major > 17 时跳过 `cloth-ui/src/main/java` srcDir（cloth-config-forge 18+ 缺失，反射探测 fallback）；`HassiumForgeConfigScreens` 改用反射调 `HassiumClothConfigScreen.create`；architectury-loom 1.13.469 `launch.cfg` 新增 `clientdataArgs` section 但 dev-launch-injector 0.2.1+build.8 `parseConfig` 只认 `Args`/`Properties` 后缀 → `IOException` pass-through → 客户端 args 丢失，`task.doFirst` 将 `clientdataArgs` 重命名为 `xclientdataArgs` 规避。

**1.21.11（段 I，暂时搁置）**：`compileJava` + `@Mod` 迁移（EventBus 7 将 `SubscribeEvent` 从 `net.minecraftforge.eventbus.api` 移到 `.api.listener`，条件编译 `#if MC_VER < MC_1_21_6` 处理）已完成；**runServer 启动崩溃根因**为 architectury-loom 在 forge 1.21.11 merged jar 中 `ByteBufCodecs$N` inner class 与 `<clinit>` 调用签名全局错位：

- `<clinit>` 期望 `ByteBufCodecs$11.<init>()V`（BYTE_ARRAY 无参 anon，vanilla 1.21.11 源码 L150 `new StreamCodec<ByteBuf,byte[]>(){...}` 无 `maxSize`），但 merged jar `$11` 是 `byte[]` `(int)` ctor + `val$maxSize` field（1.21.5 `byteArray(int)` 风格）
- 修 `$11` 后崩 `ByteBufCodecs$20.<init>(int)`（`stringUtf8(int)` 期望 `(int)` anon），但 merged jar `$20` 是 `Vector3fc` `()` anon
- vanilla obf `aam$N` 编号与 merged `ByteBufCodecs$N` 完全不一致；Forge `patches/.../codec/ByteBufCodecs.java.patch` 只改局部变量名（SRG `f_315847_` 等），不引入 `maxSize`/重排编号
- 结论：系 loom SRG remap/merge 阶段混入 1.21.5 风格 inner class 布局导致，非 Forge userdev 自身 bug；需 loom remap 链路级修复或从 vanilla obf jar 重映射整套 `ByteBufCodecs$N.class` 覆盖 merged jar，逐个 ASM patch `$N` ctor 不可持续（编号会随上游重排再次错位）

已落地但暂未激活的 1.21.11 forge 适配资产（保留在工作区，待 loom 侧修复后重新启用 `builds_for`）：

- `forge/build.gradle`：`hassiumPatchByteBufCodecs11Ctor`（`forgeMajor >= 61` 时 ASM 给 `$11` 加 `<init>()V`，MAX_SIZE 让 encode/decode 不误报）、`hassiumStripMergedForgeAmn` / `hassiumExtractForgeOnlyJar`、`gradle.ext.forgeMajor`、`launch.cfg` clientdataArgs → xclientdataArgs doFirst
- `HassiumMod.java`：`#if MC_VER < MC_1_21_6` 处理 EventBus 7 `SubscribeEvent` 包名迁移

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
| 1.21.1 | ✅（Phase R pass，commit 1461705） |
| 1.21.5 | ✅（Phase R pass，commit 6114071） |
| 1.21.11 | ⏸ 暂时搁置（ByteBufCodecs$N inner-class 错位，见附录） |
| 其余 1.21.x | ❌ 使用 NeoForge |

### Fabric / FCAP 配置策略

| 加载器 | 配置后端 | GUI |
|--------|----------|-----|
| Fabric | Night Config toml（自管） | Mod Menu + Cloth（jiJ） |
| NeoForge | 原生 ModConfigSpec | Cloth（jiJ，模组列表配置） |
| Forge 1.20.1 | 原生 ForgeConfigSpec | Cloth（jiJ） |
| Forge 1.20.6 | ModConfigSpec + FCAP Forge jiJ | Cloth（jiJ） |
