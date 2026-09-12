# 有效版本区间（Version Segments）

本文档是 Hassium 多版本适配的**唯一真相源**。工作单位不是「12 个 MC 版本 × 3 加载器」，而是 **7 个有效代码段 × `builds_for` 中的加载器**。

相关：Manifold `#if MC_VER`（多版本源码预处理，见 AGENTS.md 与 [`.omp/skills/hassium-manifold/SKILL.md`](../.omp/skills/hassium-manifold/SKILL.md)）、`versionProperties/*.properties`。

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
| `MC_1_21_1` | 1.20.1 vs 现代基线总缝：`CustomPayload` 包路径（game→common）、`createPacket`/`getPacketId` 移除（改 codec）、`onDisconnect` 上移、`Packet.write()` 移除、`BlockEntity.load()` 移除、`getPacketsByIds` 移除、Fabric 网络改 StreamCodec、NeoForge 改 Payload + `RegisterPayloadHandlersEvent`、`Component` → `DisconnectionDetails`；RL 构造私有化；`GameProtocols.CLIENTBOUND/SERVERBOUND` → `*_TEMPLATE`；`ChunkHolder.pos` 上移至 `GenerationChunkHolder`；`ProtocolInfo.Unbound.listPackets` | — | — | — |
| `MC_1_21_2` | `ChunkSerializer` → `SerializableChunkData`、`registryOrThrow` → `lookupOrThrow` | — | — | — |
| `MC_1_21_5` | `CompoundTag` API（`getAllKeys`→`keySet` 等）；`ProtocolInfo.Unbound` → `SimpleUnboundProtocol` / `UnboundProtocol`（SERVERBOUND 需 `GameProtocols.Context`）；`ClientboundLevelChunkPacketData` heightmaps NBT→StreamCodec | — | — | — |
| `MC_1_21_6` | `serverLevel()` → `level()`；`ServerPlayer` 构造精简；`Connection.send` 监听器 `PacketSendListener`→`ChannelFutureListener`；`BlockEntity.load*` → `ValueInput` | — | `SubscribeEvent` 包路径 | `EventBusSubscriber.bus` 移除（按事件自动选总线） |
| `MC_1_21_9` | `LevelChunkSection` 构造、`PalettedContainerFactory`；`Entity.getServer()` 移除；`Minecraft.setLevel` 去掉 Reason | — | — | `FMLLoader.getCurrent()` |
| `MC_1_21_11` | `ResourceLocation` → `Identifier`；`FriendlyByteBuf.read/writeResourceLocation` → `read/writeIdentifier` | import + 返回值 | import | — |

另：`MC_1_21_4` / `PermissionCompat` 等若与上表冲突，以 **compat 类内注释的实际切分点**为准，并应并入上表后再使用。

### 合法 `#if` 边界白名单（扫描用）

```
MC_1_20_1
MC_1_21_1
MC_1_21_2
MC_1_21_5
MC_1_21_6
MC_1_21_9
MC_1_21_11
```

（比较式用 `< MC_1_21_1` 表达 1.20.1。）


历史遗留：代码中偶见 `MC_1_21_4`（与 `MC_1_21_5` 等价边界）。新代码禁止新增；清扫时统一为 `MC_1_21_5`。

> **历史（2026-08-22 版本收缩前）**：`MC_1_20_2/3/4/5/6` 子分界已随 1.20.2–1.20.6 支持裁剪全部退役（原 NeoForge 网络 4 段 / Fabric·Forge 3 段归拢为「1.20.1 vs ≥1.21.1」两段；tick 计时、NbtIo 等微补丁并入 `< MC_1_21_1`）。

---

## 七段 × 锚点

| 段 | 锚点（必编 / 必测） | 段内其余版本 | 进入本段后的关键变化 |
|----|---------------------|--------------|----------------------|
| A | **1.20.1** | — | 基准：旧网络 + 全部旧 API（Java 17） |
| D | **1.21.1** | — | 现代基线：StreamCodec、`DisconnectionDetails`；RL 构造私有化；`GameProtocols.*_TEMPLATE`（Java 21） |
| E | **1.21.2** | 1.21.3, 1.21.4 | `SerializableChunkData`、`lookupOrThrow` |
| F | **1.21.5** | — | CompoundTag API；ProtocolInfo Unbound 拆分；chunk heightmaps 线格式；**客户端缓存不跨 MC 版本兼容**（见文末附录） |
| G | **1.21.6** | 1.21.7, 1.21.8 | `serverLevel()`→`level()`；Connection.send 监听器；NeoForge EBS.bus 移除 |
| H | **1.21.9** | 1.21.10 | LevelChunkSection / PalettedContainerFactory；getServer 移除；setLevel 单参 |
| I | **1.21.11** | — | Identifier |

### 锚点 × `builds_for`（编译矩阵）

以各 `versionProperties/<ver>.properties` 的 `builds_for` 为准；下表为当前快照：

| 锚点 | 加载器 |
|------|--------|
| 1.20.1 | fabric, forge |
| 1.21.1 | fabric, neoforge, forge |
| 1.21.2 | fabric, neoforge |
| 1.21.5 | fabric, neoforge, forge |
| 1.21.6 | fabric, neoforge, forge |
| 1.21.9 | fabric, neoforge, forge |
| 1.21.11 | fabric, neoforge |

> **1.20.1 不构建 neoforge**：NeoForge 47.x 原生兼容 Forge mod，NeoForge 用户直接使用 forge 子项目的 Forge 产物（2026-09-11 起）。
>
> **Forge 支持 1.20.1 / 1.21.1 / 1.21.3 / 1.21.4 / 1.21.5 / 1.21.6 / 1.21.7 / 1.21.8 / 1.21.9 / 1.21.10**；**1.21.11 起 sunset**（architectury-loom 在 merged jar 重映射阶段打散 anonymous inner class `$N` 编号，outer class `<clinit>` 调用方与实际 class 文件名错位：loom 1.13.469 崩点在 `ByteBufCodecs$N`，loom 1.17.491（2026-07-29 验证 + Gradle 9.6.1）漂移到 `CompoundTag$N` 且 1.21.10 runServer 证实到 `Done`，root cause 同源未修复，详见附录「Forge 1.21.x 适配」）。**1.21.2 上游未发布 Forge userdev**（官方跳过），1.21.11 及后续不构建 Forge，1.21.x 请用 NeoForge。Forge 1.20.6 支持已随 1.20.x 收缩退役（2026-08-22）。
>
> **Fabric 配置**：自管 toml + Cloth/Mod Menu，**不依赖 FCAP**。FCAP Forge 桥已随 Forge 1.20.6 退役。

本地 / CI：

```bash
./gradlew scanVersionBoundaries
```

推进顺序：按 **A → D → I** 锚点推进；禁止并行铺满七段。当前状态见文末附录。

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

直连拓扑（2026-09-04 回归，`handoff-2026-09-04-vanilla-direct-network.md`）：客户端↔服务端唯一 vanilla TCP；网络核心（进程内网关）、UDP 数据面、主控迁移/续流、客户端 failover 均已裁剪。网络层版本差异只剩「登录/配置期握手 + Play 期 payload 注册」两处。

| 分界 | 动作 |
|------|------|
| 1.20.1 | 基准：login custom query（`hassium:login_hello`）+ buf 收发。**历史**：UDP 数据面 + TCP 控制 Failover（1.1.2，commit `22c9c3f` / `931b393` / `e9a9e69`）、客户端 failover 退役（`729d92e`）均为网关时代记录，随直连拓扑整体退役 |
| ≥1.21.1 | 现代基线：STREAM_CODEC / `type()`、Payload + `RegisterPayloadHandlersEvent`（NeoForge）、Forge ChannelBuilder；配置阶段 `PreHandshakePayload` 预握手；聚合写包、原版包枚举等 common 能力 |
| 其后 | 多为 common API；网络协议少变 |

加载器内网络适配器允许整段实现块（两分界）：
- **NeoForge**：2 段（`< MC_1_21_1` SimpleChannel(forge 包名) / `≥ MC_1_21_1` Payload + StreamCodec）
- **Fabric / Forge**：2 段（`< MC_1_21_1` buf 收发 / `≥ MC_1_21_1` StreamCodec）

### 功能门控

`NetworkCapability.isCustomChannelFullySupported()` 恒为 true；`CommonClass.init()` 不因版本强制关闭网络。

- 各加载器 `registerChannels` / 握手入口仍尊重配置项 `HassiumConfigService.isNetworkCompressionEnabled()`（客户端解析 `chunk.enabled`，服务端解析 `master.enabled`）
- 实现细节见 `PacketCodecCompat`（StreamCodec / GameProtocols / IdDispatchCodec）

### 登录期握手：首批区块能力声明

服务端剥光不是由 packet 类别决定，而是由 `chunk.lightStrip` 与客户端 Hassium 能力共同决定。为避免在 Play 握手完成前错过首批区块，具备 pre-Play API 的载体提前声明 Hassium；`ServerPlayer` 创建时由 `MixinServerPlayer` 的 `<init>` TAIL → `ServerHandshakeActivation.onPlayerInit` 消费协商位并压制原版区块窗口，首圈 `trackChunk` 即进入 Hassium 链（剥光 + 限流 + hash 元数据）。ZSTD/聚合协商仍在 Play 完整握手完成。

| 段 | 当前首批区块策略 | 能力声明 |
|----|-----------|-----------|
| fabric 1.20.1 | 首圈直接进入 Hassium 链 | `ClientLoginNetworking` 回复 login query；服务端 `ServerLoginConnectionEvents.QUERY_START` / `ServerLoginNetworking` 接收 |
| fabric ≥1.21.1 | 支持配置阶段预握手 | `ClientConfigurationNetworking.send(PreHandshakePayload.create())`；服务端 `ServerConfigurationNetworking` 接收 |
| 无 pre-Play API 的 loader/version | 不等待、不阻塞；首批发送带完整光照的 vanilla 包，Play 握手完成后切换 Hassium 优化链 | 仅使用 Play 阶段能力上报 |

**当前语义**：不得使用固定 10 秒区块门控。无法在 pre-Play 阶段识别客户端时，立即走完整光照回退；已识别的 Hassium 客户端从首圈进入优化链。预握手只是能力提前声明，不替代 Play 期 `play_init_s2c` 完整激活。

共用载体：`PreHandshakeProtocol`（1.20.1 legacy buf 编解码 / 1.21.1+ payload，StreamCodec 为 FriendlyByteBuf 级，无 registry 依赖）。能力字段：协议版本（`CURRENT_PROTOCOL_VERSION`）、mod 版本（格式校验）、客户端能力位（`LoginCaps`：agg/delta/seed/light/pull/shadow_pull/pull_mode）。
运行时验证优先级：**1.20.1 → 1.21.1 → 1.21.11**；直连拓扑下无 UDP 断链冒烟（`UdpFailover` harness 与 `FAILOVER_*` / `UDP_*` marker 已随数据面退役）；其余锚点以编译 + 短冒烟为主。详见 [`runtime-smoke-test.md`](runtime-smoke-test.md)。

---

## Compat 对照表

| 类 | 分界 | 职责 |
|----|------|------|
| `PacketPayloadCompat` | 1.21.1 / 1.21.11 | CustomPayload ID / 数据 / 构造 |
| `ResourceLocationCompat` | 1.21.1 / 1.21.11 | RL / Identifier 创建 |
| `RegistryCompat` | 1.21.2 | registryOrThrow / lookupOrThrow |
| `DisconnectCompat` | 1.21.1 | onDisconnect 参数 |
| `PermissionCompat` | 1.21.11 | 命令权限 API |
| `PlayerCompat` | 1.21.1 / 1.21.6 / 1.21.9 | `serverLevel()` / `level()`；`getServer()`→`level().getServer()`；`getConnection` 沿继承链取 `connection` |
| `BlockEntityCompat` | 1.21.1 / 1.21.6 | `load` / `loadWithComponents(CompoundTag)` / `ValueInput` |
| `LevelChunkSectionCompat` | 1.21.9 | Section 构造 |
| `CompoundTagCompat` | 1.21.5 | keys / 标量读取 |
| `ChunkPacketDataCompat` | 1.21.5 | chunk packet heightmaps 跳过/复制（NBT→StreamCodec） |
| `ChunkDataCompat` | 1.21.2 | Mixin 目标类说明（序列化入口） |
| `NetworkCapability` | — | 自定义通道是否完整可用（恒 true，历史 1.20.5 分界已退役） |
| `PacketCodecCompat` | 1.21.1 / 1.21.5 / 1.21.11 | StreamCodec 聚合写包 / GameProtocols 包枚举 / ProtocolInfo bind / Payload 提取；`listPackets` 自 1.21.1（此前走 IdDispatchCodec）；`readResourceLocation`→`readIdentifier` |

---

## 明确不做什么

- 不引入按版本的 Gradle source set / 子模块复制
- 不追求每个小版本手测
- 不在 `builds_for` 不含 forge 的版本上硬撑 Forge 网络
- **不在 1.21.11 上构建 Forge**（暂时搁置，根因见附录「Forge 1.21.x 适配」）
- **Fabric 不引入 FCAP**（自管 toml + Cloth）；FCAP Forge 桥已随 Forge 1.20.6 支持退役
- 不把 Identifier rename 散落到业务文件

---

## 附录：适配状态

**历史（2026-07-28，九段时代）九段适配曾全部完成**；关键运行时回归：1.20.1 / 1.20.5 / 1.21.1 / 1.21.11 通过。  
多 Mod 冒烟（Fabric 1.20.1 优化包 + C2ME）见 [`mod-compat.md`](mod-compat.md) §11。  
**2026-08-22 起版本收缩为 1.20.1 + 1.21.1+（七段），以下涉及段 B/C 与 1.20.2–1.20.6 的记录均为历史语境。**

### Forge 1.20.6 重新兼容（2026-07-28）

Forge 1.20.6（段 C 段尾）此前因 `ForgeNetworkManager` 的 Manifold `#endif` 缺失等问题从 `builds_for` 暂时移除，今已重新兼容并通过 `forge:compileJava -Pmc_ver=1.20.6`。修复点：

- `versionProperties/1.20.6.properties`：`builds_for` 恢复 `fabric,neoforge,forge`
- `ForgeNetworkManager.java`：补齐 89 行 `#if MC_VER < MC_1_20_2` 块缺失的 `#endif`（1.20.6 走 `#else` 方暴露 EOF 报错，即当初"一直解决不了"的根因）；`Channel` 局部变量改 `io.netty.channel.Channel` 全限定名，避免与新 import 的 `net.minecraftforge.network.Channel` 歧义；`sendIndexSyncPacket` 改用 `IndexSyncManager.createSyncPacket()` 对齐 fabric/neoforge
- `ForgeNetworkManagerService.java`：补 `sendLightDeltaPacket` 委托实现接口
- `ForgeNetworkManager.java`：补齐 `LightDeltaWrapper` / `DictionarySyncWrapper` / `IndexSyncWrapper` / `CompressionReadyWrapper` 通道；握手改为与 Fabric/NeoForge 同构的 CompressionReady ACK（客户端装 ZSTD 后暂停出站，待 IndexSync 再恢复），修复重连 `incorrect header check` 断连
- `forge/build.gradle`：FCAP Forge JiJ 的 `mixinextras-forge`（内嵌 `MixinExtras`）与传递依赖 `mixinextras-common` 在 Forge 50 JPMS 下双模块同包导出 → `ResolutionException`；`stripFcapMixinExtrasJij` 剥除 FCAP 内嵌 JiJ，只保留单一 `mixinextras-common`

| 段 | 锚点 | 状态 |
|----|------|------|
| A–I | 见上表 | 已完成 / 已联调 |

### Forge 1.21.x 适配（1.21.1–1.21.10 通过；1.21.11 搁置）

Forge 自 1.21.x 起仍为 Forge 风格 API，与 NeoForge 不兼容；Hassium 通过 `neoforge` 子项目以 `loom.platform = 'forge'` 覆盖承载 Forge 构建（1.21.2 上游无 Forge userdev；1.21.3–1.21.10 均有 userdev，loom 1.17.491 + Gradle 9.6.1 下 `:forge:compileJava` 通过，2026-07-29 九连 Phase R smoke 全 PASS）。

**1.21.1（段 D，commit 1461705）**：`HassiumMod.java` 改用 `ForgeConfigRegistration.register(...)` + 原生 `ModConfigEvent`；forge-only jar prepend 策略（仅 forge packages + 资源 + AMN，不含 mc class）让 ClasspathLocator 不抢 minecraft 路径。

**1.21.5（段 F，commit 6114071）**：`forge/build.gradle` 在 `cloth_config_version` major > 17 时跳过 `cloth-ui/src/main/java` srcDir（cloth-config-forge 18+ 缺失，反射探测 fallback）；`HassiumForgeConfigScreens` 改用反射调 `HassiumClothConfigScreen.create`；architectury-loom 1.13.469 `launch.cfg` 新增 `clientdataArgs` section 但 dev-launch-injector 0.2.1+build.8 `parseConfig` 只认 `Args`/`Properties` 后缀 → `IOException` pass-through → 客户端 args 丢失，`task.doFirst` 将 `clientdataArgs` 重命名为 `xclientdataArgs` 规避。

**1.21.11（段 I，暂时搁置）**：`compileJava` + `@Mod` 迁移（EventBus 7 将 `SubscribeEvent` 从 `net.minecraftforge.eventbus.api` 移到 `.api.listener`，条件编译 `#if MC_VER < MC_1_21_6` 处理）已完成；**runServer 启动崩溃根因**为 architectury-loom 在 forge 1.21.11 merged jar 中 `ByteBufCodecs$N` inner class 与 `<clinit>` 调用签名全局错位：

- `<clinit>` 期望 `ByteBufCodecs$11.<init>()V`（BYTE_ARRAY 无参 anon，vanilla 1.21.11 源码 L150 `new StreamCodec<ByteBuf,byte[]>(){...}` 无 `maxSize`），但 merged jar `$11` 是 `byte[]` `(int)` ctor + `val$maxSize` field（1.21.5 `byteArray(int)` 风格）
- 修 `$11` 后崩 `ByteBufCodecs$20.<init>(int)`（`stringUtf8(int)` 期望 `(int)` anon），但 merged jar `$20` 是 `Vector3fc` `()` anon
- vanilla obf `aam$N` 编号与 merged `ByteBufCodecs$N` 完全不一致；Forge `patches/.../codec/ByteBufCodecs.java.patch` 只改局部变量名（SRG `f_315847_` 等），不引入 `maxSize`/重排编号
- 结论：系 loom SRG remap/merge 阶段混入 1.21.5 风格 inner class 布局导致，非 Forge userdev 自身 bug；需 loom remap 链路级修复或从 vanilla obf jar 重映射整套 `ByteBufCodecs$N.class` 覆盖 merged jar，逐个 ASM patch `$N` ctor 不可持续（编号会随上游重排再次错位）

**2026-07-29 loom 1.17.491 + Gradle 9.6.1 实测补充**：loom release notes 虽含 "Forge 1.21.x + 26.x support (#343/#349)"，但未根治 inner-class 重映射 bug——崩点从 `ByteBufCodecs$N`（loom 1.13.469）漂移到 `CompoundTag$2.<init>()V not found`（loom 1.17.491，崩于 `CustomData.<clinit>` → `CompoundTag.<clinit>` 链；javap 实证 merged jar 里 `CompoundTag$1` 是 `TagType$VariableSize` 真匿名、`CompoundTag$2` 是 `$SwitchMap$StreamTagVisitor$*` 合成类，`<clinit>` 字节码引用 `$2` 期望匿名但实际是合成，编号错位机制同 ByteBufCodecs）。对照实测 1.21.10 forge runServer（loom 1.17.491）到 `Done (0.293s)` 全 bootStrap 安然通过，证明 sunset 边界就在 1.21.10↔1.21.11，1.21.11 段 I CompoundTag API 重排触发该沉淀已久的 loom bug。Forge 兼容意愿低，不逐类打补丁；待 loom 上游修或 MC 26.x 自然绕开。

已落地但暂未激活的 1.21.11 forge 适配资产（保留在工作区，待 loom 侧修复后重新启用 `builds_for`）：

- `forge/build.gradle`：`hassiumPatchByteBufCodecs11Ctor`（`forgeMajor >= 61` 时 ASM 给 `$11` 加 `<init>()V`，MAX_SIZE 让 encode/decode 不误报）、`hassiumStripMergedForgeAmn` / `hassiumExtractForgeOnlyJar`、`gradle.ext.forgeMajor`、`launch.cfg` clientdataArgs → xclientdataArgs doFirst
- `HassiumMod.java`：`#if MC_VER < MC_1_21_6` 处理 EventBus 7 `SubscribeEvent` 包名迁移
### buildSrc loom-*.gradle ListProperty API 改造（loom 1.13.469 → 1.17.491，2026-07-29）

`f8ec29b` 升 loom 1.13.469 → 1.17.491 + Gradle 8.14.5 → 9.6.1 时漏改了三份 precompiled script plugin（`loom-fabric.gradle` / `loom-forge.gradle` / `loom-neoforge.gradle`），导致任何带 `-PhassiumSmokeTest=true` 的 `runServer`/`runClient` 在 4s 内 BUILD FAILED（`loom-fabric` apply `UnsupportedOperationException`），1.21.1–1.21.10 九连 Phase R 全部 exit 3 server_not_ready。根因与修复：

| API 位 | loom 1.13.469 | loom 1.17.491 |
|---|---|---|
| 单条 run config 类 | `RunConfig`（含 `public List<String> vmArgs/programArgs = new ArrayList<>()` 裸字段）| `RunConfig` 删，换为 `RunConfiguration`（`ListProperty<String> getJvmArguments()/getProgramArguments()`）+ `RunConfigSettings` 实现 |
| `getVmArgs()` / `getProgramArgs()` | 无 | `@Deprecated` facade，内部 `getJvmArguments().get()`——配置阶段 property 未 finalize 拋 `UnsupportedOperationException` |
| DSL 合法调用 | `vmArgs.addAll([...])` / `programArgs.add(...)` | `jvmArguments.addAll([...])` / `programArguments.add(...)`（ListProperty 原生 addAll/add，不 `.get()`）|

修改点（三份同构）：`buildSrc/src/main/groovy/loom-fabric.gradle` / `loom-forge.gradle` / `loom-neoforge.gradle`，把 `runConfigs.configureEach { ... }` 内的 `vmArgs.*` → `jvmArguments.*`、`programArgs.*` → `programArguments.*`；修复后九连 Phase R runtime smoke 全 PASS。

> **升级 loom 的硬约束**：未来升 architectury-loom 时必须同步检查这三份 `loom-*.gradle` 给 `runConfigs.configureEach` 内赋的值是否调到 deleted/改名/走 Provider 阀的 API。grep `vmArgs\.` / `programArgs\.` 是快速检查点；发现即需重写为 ListProperty 原生 path 或带 varargs 的 deprecated method（`vmArgs(String...)` 直接走 `getJvmArguments().addAll(...)` 不 `.get()`）。

### 客户端缓存跨版本策略（自段 F / 1.21.5）

客户端区块缓存存的是当前 MC 的 chunk packet 线格式，**不保证跨 MC 大版本读写兼容**。

- 同 MC 版本内（含 Fabric↔NeoForge）正常命中与覆盖写入
- 升版本后旧缓存可懒覆盖（MISS → 重拉 → persist），不做启动时整库作废
- 不实现跨版本迁移 / 格式协商

### Forge 支持范围

| MC 版本 | Forge |
|---------|-------|
| 1.20.1 | ✅ `builds_for` 含 forge |
| 1.21.1 | ✅（Phase R pass，commit 1461705） |
| 1.21.5 | ✅（Phase R pass，commit 6114071） |
| 1.21.3 / 1.21.4 | ✅ `builds_for` 含 forge（loom 1.17.491 `:forge:compileJava` 通过 2026-07-29） |
| 1.21.6 / 1.21.7 / 1.21.8 / 1.21.9 | ✅ `builds_for` 含 forge（loom 1.17.491 `:forge:compileJava` 通过 2026-07-29） |
| 1.21.10 | ✅ runServer `Done (0.293s)`（loom 1.17.491，2026-07-29） |
| 1.21.11 | ⏸ sunset（CompoundTag$2 `<init>()` 错位，loom 1.17.491 同源未修，见附录） |
| 1.21.2 | ❌ **上游未发布 Forge userdev**（官方跳过） |
| 其余 1.21.x | ❌ 使用 NeoForge |
> **2026-07-29 补充：1.21.1 / 1.21.3 / 1.21.4 / 1.21.5 / 1.21.6 / 1.21.7 / 1.21.8 / 1.21.9 / 1.21.10 九连 Phase R runtime smoke 全 PASS**（loom 1.17.491 + Gradle 9.6.1，仅在同步修 `buildSrc/src/main/groovy/loom-{fabric,forge,neoforge}.gradle` 的 `runConfigs.configureEach { ... }` 内 `vmArgs/programArgs` 从 deprecated 裸 List getter 改为 `ListProperty<String>` 原生 `jvmArguments/programArguments.addAll/add` 后，见附录「buildSrc loom-*.gradle ListProperty API 改造」）；每个会话均上 `Done` → 客户端两轮连服 → round1/round2 统计真出 + `ServerSwitched=True` + 客户端 `exit=0`，结果 CSV 见 `build/smoke-test/forge-21x-smoke-results.csv`。

### Fabric / FCAP 配置策略

| 加载器 | 配置后端 | GUI |
|--------|----------|-----|
| Fabric | Night Config toml（自管） | Mod Menu + Cloth（jiJ） |
| NeoForge | 原生 ModConfigSpec | Cloth（jiJ，模组列表配置） |
