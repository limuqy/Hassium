# MC 26.x 升级 + 移除 Forge + 分支分叉总体方案

> **文档定位**：本文档是「26.x 升级」多会话任务的**统筹真相源**。该工程预计跨越多个会话，任何会话开工前先通读本文档（尤其 §1 已定决策、§9 进度表），收工后必须更新 §8 决策记录与 §9 进度表；重大里程碑再写 `docs/handoff/` 交接（命名沿用 `handoff-YYYY-MM-DD-<主题>.md`）。
>
> 分段语义的长期真相源仍是 [`version-segments.md`](version-segments.md)——各阶段完成后由本文档驱动其同步修订。本文中的行号是 2026-09-30 快照，编辑时以内容定位为准。

---

## 0. 目标一句话

`master` 收缩为 **MC 26.1.2 / 26.2 / 26.3 × Fabric / NeoForge** 单线（移除 Forge 与全部 ≤1.21.11 适配）；1.21.11 以 `legacy-1.21` 分支冻结维护；升级按「移除 Forge → 收缩到 1.21.11 打绿色基线 → 切分支 → 升 26.x」四阶段提交阶梯推进，每步独立可验证、可回退。

---

## 1. 已定决策（拍板记录，勿重议）

| # | 决策 | 依据 |
|---|------|------|
| D1 | **master 走 26.x 单线**：删除所有 ≤1.21.11 版本适配后升级 26.x；不做单分支多段共存 | 26.1 起去混淆导致构建链断裂（architectury-loom issue #328 未修），单分支需双 DSL 条件切换，成本与风险更高 |
| D2 | **1.21.11-only 打基线后再升级**：先删 `<1.21.11` 适配、冒烟通过、提交「升版前基线」 | 机械清理（~119 文件）用现成可编译冒烟的 1.21.11 工具链验证；把「清理风险」与「升级风险」切成两个可二分的边界 |
| D3 | **移除 Forge**：三阶段内 forge/ 子项目整体删除；`legacy-1.21` 也不含 Forge | 用户拍板「不再兼容 forge」；1.21.11 本就无 Forge（sunset）；1.21.11-only 的 legacy 不需要 Forge |
| D4（已由 D8 更新） | **当时决定 1.20.1–1.21.10 整体 sunset**：不另建维护分支，已发布产物继续可用；以 `archive/pre-baseline` 保留历史 | 2026-10-16 用户明确现有 `2.0.0` 分支继续维护，见 D8 |
| D5 | **legacy = `legacy-1.21` 分支，从基线提交切出**：冻结功能，仅收 crash/安全修复；修复只从 master 单向 cherry-pick 落地，禁止反向合并 | 基线已坍缩为零版本块代码，cherry-pick 冲突最小化 |
| D6（版本号已确认 / 支持窗口待确认） | legacy 支持窗口 **6 个月**（或 26 线稳定即 EOL，先到为准）；26 线版本号从 **3.0.0** 起 | 2026-10-12 用户确认 master 改为 3.0.0；legacy 支持窗口仍待确认，不阻塞阶段①②③ |
| D7（已由 D9 更新） | **当时取消 NeoForge 测试版本支持**：26.2/26.3 仅 fabric；相关代码保留 | 2026-09-30 因警告页阻塞自动进服而取消；2026-10-13 已重开并修复入口，当前六组合见 D9 |
| D8 | **现有 `2.0.0` 分支持续维护**；Lomka 兼容及移动诊断修复同步到 master；版本分别升到 2.1.1 / 3.0.1 | 2026-10-16 用户明确；旧版 JAR 实测只归 2.x，26.x 不借用旧版结果 |
| D9 | **准备 2.1.2 / 3.0.2，先不发布**：3.x 按当前三锚点 × Fabric / NeoForge 六组合补验；2.x 全部版本编译、最终打包检查；补移动屋檐/洞口验收，通过后分别提交 | 2026-10-19 用户明确补跑 NeoForge 26.2/26.3、光照验收、升版与提交；随后强调 2.x 至少全部版本编译通过 |

---

## 2. 调研结论（已实证事实，后续会话无需重查）

### 2.1 上游版本事实

| | 26.1（.1 / .2） | 26.2 "Chaos Cubed" | 26.3 "Wilderness Bound" |
|---|---|---|---|
| 发布 | 2026-03-14（年份版本号首版） | 2026-06-16 | 2026-09-15 |
| Java | **25**（JDK 21→25 跳跃） | 25 | 25 |
| Fabric | Loader 0.18.4+ / API 0.144.0+ / Loom 1.15 / Gradle 9.4.0 | Loader 0.19.3 / Loom 1.17 / Gradle 9.5.1 | Loader 0.19.5 / Loom 1.17 / Gradle 9.6.0 |
| NeoForge | 四段版本号：26.1.0.x-beta 起；**26.1.2 → 26.1.2.70-beta** | 跟随 MC 前两段 | 跟随 MC 前两段 |

结构性变化：

- **去混淆（26.1 起）**：mojmap 官方名 = 运行时名；Yarn/intermediary 退役；mixin 不再需要 refmap；运行时反射可直接用官方名。Fabric 官方要求换新插件 id `net.fabricmc.fabric-loom`：无 mappings 块、`implementation` 替代 `modImplementation`、`jar` 替代 `remapJar`。
- **世界目录重排（26.1）**：维度从 `world/region`、`DIM-1`、`DIM1` 挪入 `world/dimensions/<ns>/<name>/`；**Anvil `.mca` 二进制格式未变**。
- **26.2**：实验性 Vulkan 后端与 OpenGL 并存；`Minecraft.screen`/`setScreen` 移入 `Gui`/`Hud`；`BlockIds/ItemIds` 注册 ID 拆分。
- **26.3**：LWJGL 换 SDL 输入；配方/进度/战利品表迁入可重载 datapack 注册表；`ClientChunkCache.replaceWithPacketData` 改签名（收 `ClientboundLevelChunkPacketData`）；新包 `ClientboundAddTransientBlockPacket`。
- 各 NeoForge 移植 primer 对自定义 payload、配置阶段协议、光照引擎、POI/SectionStorage、NBT **零提及**——网络与数据面延续「协议少变」。
- **协议/格式隔离**：vanilla 协议版本天然隔离 26.x 与 1.21.x 互连；type 126 存档与客户端缓存本就不跨 MC 版本迁移（段 F 起既定策略）→ 两条线无互操作义务，分叉安全。

### 2.2 钩子存活核对（26.2 反编译实证；26.3 抽查一致）

**健在、签名未变**（基线后的 1.21.11 路径可平移，仅改 import/target 包名）：

- 光照引擎全家：`LightEngine`、`LayerLightSectionStorage.getStoredLevel/setStoredLevel`、`ThreadedLevelLightEngine.lightChunk(ChunkAccess, boolean)`、`LevelLightEngine`、`SkyLightEngine`、`BlockLightEngine` —— 算光红线相关 mixin 目标完整
- `PoiManager.checkConsistencyWithBlocks(SectionPos, LevelChunkSection)`、`SectionStorage`、`SimpleRegionStorage`、`IOWorker`（+新 `RecreatingSimpleRegionStorage`）
- `SerializableChunkData`、`ValueInput`/`TagValueInput`、`ChunkTrackingView`
- `Connection`：`channelRead0`（L142）、`send(Packet)` / `send(Packet, ChannelFutureListener)` / `send(Packet, ChannelFutureListener, boolean)`、`disconnect(Component/DisconnectionDetails)` —— 与 `MC_1_21_6` 分支一致，**无新分界**
- `ClientPacketListener.handleLevelChunkWithLight(ClientboundLevelChunkWithLightPacket)`、`clearLevel()`、`Minecraft.disconnect(Screen, boolean[, boolean])`
- `LevelChunkSection(PalettedContainerFactory)` / `(PalettedContainer, PalettedContainerRO<Holder<Biome>>)` 构造、`GameProtocols`、`PacketSendListener`
- `PlayerChunkSender`：类还在（换包，见断点 2），26.2 与 26.3 逐行一致（132 行同构）
- `Identifier` 命名不变 —— `ResourceLocationCompat` 等的 1.21.11 else 路径平移即可

### 2.3 六处代码断点（26.x 相对 1.21.11，阶段④必修）

1. **登录握手**：`ServerLoginPacketListenerImpl.handleAcceptedLogin()` 已消失（逻辑内联进 `handleLoginAcknowledgement()`：setupOutboundProtocol → 建 `ServerConfigurationPacketListenerImpl` → `startConfiguration()` → state=ACCEPTED）。`mixin/server/MixinServerLoginPacketListenerImpl`（@Inject `handleAcceptedLogin` TAIL、`tick`、`handleCustomQueryPacket`）的 `handleAcceptedLogin` 注入点重挂到 `handleLoginAcknowledgement`。
2. **PlayerChunkSender 换包**：`net.minecraft.server.level` → `net.minecraft.server.network`（发生在 26.x 某处，**26.1.2 上待实证**）。`sendNextChunks(ServerPlayer)` 与静态 `sendChunk(ServerGamePacketListenerImpl, ServerLevel, LevelChunk)` 签名未变；26.2 起内部追加 `debugSynchronizers().startTrackingChunk` 调用。`mixin/server/MixinPlayerChunkSender` 改 target。
3. **ChunkMap.playerLoadedChunk 消失**：职责并入 `onChunkReadyToSend(ChunkHolder, LevelChunk)` → 逐玩家 `markChunkPendingToSend`。`shadow/MixinChunkMap` 的 `playerLoadedChunk` @ModifyVariable/cancel 钩子改挂 `markChunkPendingToSend` 或 `onChunkReadyToSend`（实施时先确认原窗口压制语义的等价挂钩点）；`scheduleChunkLoad(ChunkPos)`、`save(ChunkAccess)Z`（内含 `poiManager.flush`）仍健在。
4. **MinecraftServer 构造/数据存储**：构造可选 `GameRules` 入参、`ServerLevel` 不再收 `RandomSequences`、天气/随机序列移至 `MinecraftServer`、新增 `getDataStorage()`。`mixin/server/MixinMinecraftServer` 的 3 个 `<init>` @Redirect 重签。
5. **时间域 API**：`Level.getDayTime` → `getOverworldClockTime` / `clockManager()`；`ServerLevel.setDayTime/getDayCount` 移除；`ClientboundSetTimePacket` 改收 clocks map。审计 `SeedGenLevelCompat`、`ShadowServerCompat` 内全部时间引用。
6. **setScreen 迁移**：`Minecraft.screen`/`setScreen` → `Gui`（26.2）。`mixin/client/MixinMinecraft` L29 的 setScreen 钩子迁移目标类；`clearLevel`/`disconnect` 重载已核对健在。

**M0 实测新增断点（26.3 编译错误目录 200 行，poc 分支 `build/mc26.3-common-errors.txt` / `mc26.3-symbols.txt`）**：

7. **ChunkPos record 化**（132 处，量最大但机械）：26.3 为 `public record ChunkPos(int x, int z)` —— `pos.x`/`pos.z` 字段访问改 `pos.x()`/`pos.z()`；静态 `asLong(int,int)` 与实例 `toLong()` 移除（源码现见 `pack(int,int)`/`unpack(long)`，替代关系需对源核对）。
8. **authlib 重组**（2 处）：`com.mojang.authlib.yggdrasil.ServicesKeySet` 包不存在（authlib 10.0.77）——`ShadowServerCompat.noServices` 等按 26.3 authlib API 重写。
9. **零散符号缺失**（54 处）：`FriendlyByteBuf`（6 处）、`ClientboundLevelChunkWithLightPacket.getChunkData`、`Gui.getChat`（26.2 Gui/Hud 拆分延续）、`writeCollection/readCollection` 签名变化（`ChunkPacketDataCompat`）等，逐条对源核对。

### 2.4 实施期待复核清单（阶段④逐项打勾）

- [ ] `PlayerChunkSender` 在 26.1.2 的包名（决定段 J 是否已换包）
- [ ] `DimensionDataStorage` → `SavedDataStorage`（每维度独立）对影子端存档元数据的影响
- [ ] `Level#random` 转 protected（grep `level.random` 审计，改 `getRandom()`）
- [ ] 26.1 目录重排对 `hassium_cache/<serverId>/world` 自建目录打开方式的影响（自管布局预计无碍）
- [ ] `LevelChunkWithLightPacketAccessor.lightData` 字段名；`ClientboundLevelChunkWithLightPacket` 4 参构造
- [ ] `ClientChunkCache.replaceWithPacketData`（26.3 改签名）是否被摄入路径间接依赖
- [ ] NeoForge `RegisterPayloadHandlersEvent` / `RegisterConfigurationTasksEvent` / `ICustomConfigurationTask` 在 26.x beta 的形态
- [ ] Fabric API 网络门面命名（`ClientConfigurationNetworking` / `ServerConfigurationNetworking` / `ClientLoginNetworking` 等）
- [ ] Cloth Config 26.x 可用性（fabric GUI JiJ）
- [ ] modcompat mixin 目标（`MixinRegionFileVersion` / `MixinRegionFileWrite` → `RegionFile`）在 26.x 复验
- [ ] `MixinServerEntity.sendChanges` 字段 @Redirect ×4、`MixinClientPacketListener` 移动包 @Redirect 群（26.2 未逐个核对）
- [ ] `ServerPlayer` 构造重载数（`MixinServerPlayer` `<init>` ×2 + `trackChunk`/`untrackChunk`）
- [ ] 客户端缓存按既定策略懒覆盖（无迁移工作，确认即可）

### 2.5 构建链事实

- 现状：`buildSrc/build.gradle` L18–21 固定 `dev.architectury:architectury-loom:1.17.491`；loom 版本与 mc_ver **无关**（gradle.properties 注释已过时）。
- **architectury-loom issue #328（26.1+ 无映射不可用）至今开放、无关联 PR**；1.17 release note 有 unobf 字样但不能证明可用 → 阶段④ M0 必须 PoC。
- 备选链：Fabric 26.x = fabric-loom 新插件（Loom 1.15+）；NeoForge 26.x = ModDevGradle。master 分叉后可**硬切换**（无需按 mc_ver 条件 apply）——这正是 D1 分叉的核心收益。
- Gradle 9.6.1 满足 26.3 要求（≥9.6.0）；Java 25 toolchain 已开 auto-download（`gradle.properties`）。
- Manifold 2026.1.6 对 Java 25 的支持按惯例应无碍（2026.1.x 版本线在 JDK 25 发布之后），M0 一并验证。
- mixin/AMN 对 Java 25 字节码（class file 69）由 Loader（0.18.4+/0.19.5）与 NeoForge（26.1.0.2-beta 专门升过 mixin）携带，无自管版本。
- `buildSrc` 三份 `loom-*.gradle` 的 `runConfigs.configureEach` 必须 ListProperty API（`jvmArguments`/`programArguments`），换插件后写 runConfig 同样遵守（见 version-segments.md 附录）。
- 26.x 无 refmap：mixin json 的 refmap 字段失效但无害；mixin 目标即运行时名。

---

## 3. 总体路线：四阶段提交阶梯

```
当前 HEAD ──► ① 删 Forge ──► ② 收缩 1.21.11-only + 冒烟 ──► ③ tag baseline/1.21.11
                (绿)            (绿)                        ├─► 切 legacy-1.21（冻结维护）
                                                            └─► ④ master 升 26.x（PoC → 三锚点）
archive/pre-baseline = 当前 HEAD 的引用（1.20.1–1.21.10 完整历史后手）
```

每阶段一个（或一组）独立绿色提交；任何阶段失败可单独 revert，不影响前面阶段。

**全程红线不豁免**（大 diff 主要落在这些文件，清理属「保留 1.21.11 路径」的机械操作，禁止顺手改行为）：
- 算光红线（`LightNeighborhoodGate` 不拆门、交付域、`pushReady` 门、`drainAuthorityAcquires` 预算）——见 AGENTS.md「算光红线」
- 并发串行化（`ShadowPoiGate.callExclusive`、`ShadowRegistryWindow` 随基线删除——1.20.1 专属，删除时确认其调用点全部随版本块消失）
- 配置红线（`ConfigSchema` 键集不增不减；`master.maxChunksPerTick` 的 `MixinPlayerChunkSender` 钩子在阶段④换 target 不得丢）

---

## 4. 阶段①：移除 Forge（改动清单，已全部定位）

| 文件 | 动作 |
|------|------|
| `forge/` 整目录 | 删除（9 主源文件 + 1 测试，全部与 neoforge 同构，common 零直接引用） |
| `buildSrc/src/main/groovy/loom-forge.gradle` | 删除整文件 |
| `settings.gradle` | 删 `include ':forge'`；L11/L31/L35 forge maven 仓库与 includeGroup；L86–91 `beforeProject` loom.platform 注入的 forge 分支 |
| `buildSrc/src/main/groovy/root.gradle` | L35 扫描目录数组去 `'forge'`；L73 `includedLoaders` 去 `'forge'` |
| `buildSrc/src/main/groovy/common.gradle` | L81–83 `forge_loader/forge_version/forge_loader_version_range` 模板展开 |
| `buildSrc/src/main/groovy/publish-curseforge.gradle` | L17–24 `cfLoaderByProject` 去 forge；L72–78 1.20.1 双标逻辑 |
| `buildSrc/src/main/groovy/publish-modrinth.gradle` | L21–28 `mrLoaderByProject` 去 forge；L45–46 1.20.1 双标 |
| `versionProperties/*.properties`（10 个含 forge 的文件） | `builds_for` 去掉 forge（阶段②会再删其中 11 个文件，此处只为阶段①独立绿色） |
| `scripts/full-version-compile.ps1` | L18–29、L34–45 两张表去 forge 条目 |
| `scripts/runtime-smoke-test.ps1` | L11 ValidateSet；L144–169 SRG 映射预检查整段；L519 serverconfig forge 分支 |
| `scripts/runtime-smoke-test-batch.ps1` | L6 注释、L21 ValidateSet、L76 `1.20.1/forge` 锚点、L284–307 builds_for 过滤 |
| `gradle.properties` | forge/neoforge 相关注释顺手清理 |
| `AGENTS.md`、`docs/version-segments.md`、`docs/mod-compat.md` | forge 描述相应收缩（本阶段可最小化：标注「forge 已移除，见 mc26-upgrade-plan.md」） |

**验证门**：`./gradlew fabric:compileJava` + `./gradlew neoforge:compileJava`（默认 mc_ver 即可，不用 `-Pmc_ver`，当前默认 1.21.1 能编译 1.21.1）→ 通过即提交。
> 注意：阶段①先做，`ForgeNetworkManager`（29 个版本块）随之消失，避免阶段②白剥离。

---

## 5. 阶段②：收缩至 1.21.11-only 基线

### 5.1 剥离规则

- 所有 Manifold 块（`//#if MC_VER ... //#endif`）**保留 1.21.11 路径**（`>= MC_1_21_11` / else 侧），编译于 `MC_1_21_11` 时的形态即目标形态。
- 剥离后全仓库**零 `#if MC_VER` 块**。建议写一次性脚本解析行内注释指令批量剥离，top 重灾区人工复核（按块数排序）：`ShadowServerCompat`(52)、`FabricNetworkManager`(43)、`SeedGenLevelCompat`(26)、`PacketCodecCompat`(26)、`PacketPayloadCompat`(25)、`ResourceLocationCompat`(18)、`HollowBenefitBenchmark`(16)、`ChunkPacketDataCompat`(15)、`HassiumClientMod`(14)、`ShadowSeedServer`(14)、`PlayerCompat`(12)、`LevelChunkSectionCompat`(12)、`SeedGenTail`(10)、`MixinMinecraft`(9)。
- compat 类**坍缩为单路径、保留类壳**（不内联、不删类——「删 compat」留给阶段④随 26.x 路径自然死亡）。唯一整删：`ShadowRegistryWindow` + `ShadowRegistryWindowTest`（1.20.1 专属，编译期即剔除）。
- 嵌套块（如 `FabricNetworkManager` L32–123 的 Identifier 桥群）注意 else 路径语义，剥离错误由单锚点编译全量兜底。

### 5.2 配套收缩

- `versionProperties/`：删 11 个（1.20.1、1.21.1–1.21.10），留 `1.21.11.properties`（builds_for=fabric,neoforge 原样）。
- 边界白名单缩到 `MC_1_21_11`（三处同步）：`buildSrc/root.gradle` L24–26、`scripts/scan-version-boundaries.ps1` L4–10、`scan-version-boundaries.sh` L7。
- `gradle.properties`：`mc_ver=1.21.1` → `1.21.11`。
- `docs/version-segments.md`：标注「基线态：仅 1.21.11，历史段落见 git 历史/本文档 §2」。

### 5.3 验证门（基线 = 「零行为变更」声明的证明）

1. `./gradlew fabric:compileJava` + `./gradlew neoforge:compileJava`（mc_ver 默认已是 1.21.11）
2. `rm -rf common/build/*/classes/java/test common/build/*/test-results`（陈旧测试类，AGENTS.md 既有告诫）→ `./gradlew common:test`
3. `./gradlew scanVersionBoundaries`（白名单外的残留块会报错，即安全网）
4. 冒烟 ×2：`pwsh -Command '& "./scripts/runtime-smoke-test-batch.ps1" -Phase I -Scenarios seedgen,dimension'`（默认 loaders=fabric,neoforge；结果读 `build/smoke-test/results/result_<SessionId>.json`）——本阶段触碰 shadow/light/network 文件，Phase I 必跑；光照红线验收场景（移动屋檐/洞口）留到阶段④逐锚点做，基线因「零行为变更」以 Phase I + 单测为准。

全绿后提交（建议拆两笔：剥离一笔、properties/白名单一笔）。

---

## 6. 阶段③：基线提交 + legacy-1.21 分支

1. `git tag baseline/1.21.11`（打在阶段②完成提交）。
2. `git branch archive/pre-baseline <阶段①之前的 HEAD>`——1.20.1–1.21.10 完整状态的后手引用（仅 git 引用，零维护）。
3. `git branch legacy-1.21 baseline/1.21.11` 并切回 master。legacy 分支上补：
   - `MAINTENANCE.md`：冻结范围（仅 crash/安全修复）、热修流程（legacy 上修 → 验证 → 按需前向移植 master）、发布门禁（仅显式 bump mod_version 时发版）、支持窗口（D6 待确认后填写）。
   - 分支上 `gradle.properties` 默认已是 1.21.11，无需改；Manifold 插件保留（零块无成本）。
4. **发布一次基线版**（可选但建议）：让 1.21.11 用户拿到清理后版本，同时验证发布链路仍通（`scripts/publish-mods.ps1` 按 builds_for 生成，无需硬编码改动）。

---

## 7. 阶段④：26.x 升级（master）

### 7.1 M0 构建链 PoC（✅ 2026-09-30 已完成，scratch 分支 `poc/mc26-build`，提交 `175abc93`）

| 候选 | 结果 | 证据 |
|------|------|------|
| architectury-loom 1.17.491 直出 26.3 | **✗ 定论** | `Failed to find official mojang mappings for 26.3`；去掉 mappings 行则 `Configuration 'mappings' has no dependencies`——loom 硬性要求映射依赖而 26.x 无映射（issue #328 实锤） |
| fabric-loom 新插件 `net.fabricmc.fabric-loom` 1.17.21（fabric 线） | **✅ 工具链可行** | 无 mappings 配置通过；`:common` 编译进到真实 26.3 API 差异层（200 错误行 = 阶段④断点目录，见 §2.3 补充）；Manifold 预处理 + 零 refmap + JDK 25 全部工作 |
| ModDevGradle 2.0.148（neoforge 线） | **✅ 全绿** | neoforge 26.3.0.37-beta neoForm 管线完整走通、record API 编译通过；官方模板 examplemod-template-26.3 同款接线（settings 无需额外仓库） |

**环境前置（已验证）**：
- **JDK 25 供给**：`gradle/gradle-daemon-jvm.properties`（`toolchainVersion=25`）选择预装 / 已缓存的 JDK 25；本机须预先安装，CI 用 `actions/setup-java` 显式安装 25。settings 的 `foojay-resolver-convention 1.0.0` 负责构建 toolchain，不能替代 daemon 启动前的 JDK（当前 daemon 配置没有平台下载 URL）。本机也可钉 `D:/app/graalvm-jdk-25.0.1+8.1`（tpamod 方案）。
- **网络**：maven.neoforged.net 直连 TLS 不可达（重试/降级全 reset，WebFetch 亦超时）→ **本机构建需走本地代理 `127.0.0.1:7890`**（在本机 `~/.gradle/gradle.properties` 配置 `systemProp.http(s).proxyHost/Port` + `systemProp.jdk.tls.client.protocols=TLSv1.2`，tpamod 同款）；forgecdn 镜像可解 neoforge 主构件但**缺** `net.neoforged:minecraft-dependencies`。CI（GitHub Actions）默认直连，2026-10-12 已从仓库移除本地代理与 TLS 强制设置。
- **坑**：settings 级 `dependencyResolutionManagement.PREFER_SETTINGS` 会剥掉 MDG 的内部解析仓（本机曾因此误判 minecraft-dependencies 不存在）——26.x 接线**禁止** settings 级仓库接管。
- **已否决/备选**：Unimined（tpamod 用其构建 26.1.2 三加载器全通，可行但需全量 DSL 迁移 + smoke runConfig 重写）→ 列 B 方案。情报：**Forge 64.1.0 在 26.1.2 有 userdev 可构建**（不改变 D3 移除 Forge 决策，仅修正「Forge 已死」认知）。
- 26.3 依赖：fabric-api `0.161.0+26.3`、Mod Menu `21.0.0`、Cloth Config `26.3.159`；2026-10-20 已恢复 GUI 接入，不能继续沿用 M0 阶段的“尚未接入配置屏”状态，验证见 §8 与 §9 的同日记录。

### 7.2 版本段与边界

- `versionProperties/`：删 `1.21.11.properties`，加 `26.1.2.properties` / `26.2.properties` / `26.3.properties`，`builds_for=fabric,neoforge`。
- 边界常量（点→下划线，命名规则由 `root.gradle` L13–19 从文件名生成，实施时核对）：`MC_26_1_2` / `MC_26_2` / `MC_26_3`；白名单三处同步（root.gradle、scan 脚本 ×2）。
- 段语义：J=26.1.2（段内继承 26.1/26.1.1，**用户要求的支持起点**）、K=26.2、L=26.3。推进顺序 J→K→L，锚点编译通过即继承。
- 构建链：fabric 子项目 → fabric-loom；neoforge 子项目 → ModDevGradle（或 M0 结论 1）；`loom-fabric.gradle` / `loom-neoforge.gradle` 按新插件 DSL 重写（遵守 ListProperty runConfig 约束）；Java toolchain 25。
- 新增 `#if` 只允许出现在 compat 类与两加载器网络适配器（沿用既有约束）；预期新块很少（26.1/26.2/26.3 差异集中在客户端渲染/输入域）。

### 7.3 断点修复（§2.3 六项）+ §2.4 复核清单逐项打勾

锚点编译用 minecraft-dev 反编译核对签名（26.2/26.3 已缓存；26.1.2 需现反编译，`decompile_minecraft_version` 有 30s 超时特性，会后台续跑，多次调用直至就绪）。

### 7.4 验证门

- 每锚点：`fabric:compileJava` + `neoforge:compileJava`（`-Pmc_ver=26.1.2 / 26.2 / 26.3`；Git Bash 不加引号，pwsh 必须加引号）。编译门含 neoforge 全锚点（D7 取消的只是发布/冒烟，代码兼容保留）。
- 每锚点冒烟：`pwsh -File ./scripts/runtime-smoke-test.ps1 -Ver 26.1.2 -Loader fabric -Phase I -SessionId "26.1.2_fabric_I"`；L2 场景 `seedgen,dimension` 走 batch。**冒烟矩阵按 D7**：26.1.2 fabric+neoforge、26.2/26.3 仅 fabric。
- **光照红线验收必做**：移动屋檐/洞口场景（AGENTS.md 算光红线），冒烟绿不豁免。
- `common:test` + `scanVersionBoundaries`。
- 版本号 3.0.0（D6），发布走既有 publish 脚本。

---

## 8. 决策记录（滚动追加）

| 日期 | 决策 | 备注 |
|------|------|------|
| 2026-09-30 | D1–D5 定案（见 §1） | 本文档建立 |
| 2026-09-30 | 阶段①执行补充：① SRG 生产映射预检（`Invoke-MappingFieldPrecheck` 整段）随 Forge 移除删除（neoforge mojmap 原生、fabric 类型匹配免疫，原为 Forge SRG 专属）；② batch 非 classic 锚点 `1.20.1 forge` → `1.20.1 fabric`（保留段 A 覆盖）；③ gradle.properties 工作区已有的 mod_version 2.0.2→2.0.3 外部改动随阶段①提交一并带入 | 阶段①提交 |
| 2026-09-30 | 阶段②执行补充：① 提交拆分微调——`gradle.properties` 默认 mc_ver=1.21.11 提前并入提交 1（剥离后旧锚点不可编译，保持每笔提交默认配置可构建）；② 剥离以一次性脚本 `build/strip-manifold.mjs`（未入库）按 MC_VER=11 求值，254 块/70 文件；③ ShadowRegistryWindow + 测试确认为整文件 `#if <MC_1_21_1` 门控，随剥离整体删除；④ 基线冒烟 `1.21.11_fabric_I_dimension` 首跑触发影子世界 `MissingPaletteEntryException`（hassium-shadow-worldgen 线程），同代码 neoforge PASS + fabric 重跑 PASS ⇒ 判定既有偶发竞态、非剥离回归（风险表 R8） | 阶段②提交 |
| 2026-09-30 | 阶段③执行：tag `baseline/1.21.11`@`570ab7f3`、分支 `archive/pre-baseline`@`c589088a`（阶段①前最后提交，即 mod_version 2.0.3 bump）、分支 `legacy-1.21`@baseline + `MAINTENANCE.md`（`41a81aa1`）。基线发布（可选步骤）暂缓——对外发布需显式批准。注：仓库另有用户自建 `2.0.0` 分支亦指 `c589088a`，语义近似，未触碰 | 阶段③提交 |
| 2026-09-30 | **M0 定论**：26.x 构建链 = `net.fabricmc.fabric-loom` 1.17.21（fabric）+ ModDevGradle 2.0.148（neoforge）；architectury-loom 实证死刑（#328）；Unimined 列 B 方案（tpamod 验证可行）；环境 = JDK 25 自动供给 + 本机代理 7890（CI 直连正常）；新增断点 7/8/9 见 §2.3 | poc/mc26-build@`175abc93` |
| 2026-09-30 | 阶段④接线落地（poc 分支）：构建链工程化 + 三段 properties + 机械清扫完成（ChunkPos record 1092+ 等，编译器兜底）；**教训两条**：① loom 类插件必须走 `plugins{}` 声明，晚置 `apply plugin:` 缺 ClassLoaderScope 直接崩；② javac 默认 100 错误上限，阶段④一开始就要 `-Xmaxerrs` 放开，否则目录严重失真 | poc/mc26-build@`e3414a6d` |
| 2026-09-30 | 阶段④编译门收官（poc 分支）：① 结构断点 29 处清零（MinecraftServer 族 ctor+抽象方法、LevelSettings/PrimaryLevelData/WorldStem 链、光包 record、replaceWithPacketData、write/readCollection、Gui/Hud chat-screen 迁移、GameProtocols.Context、authlib）→ 26.3 三模块编译绿（`83558c64`/`5c333110`/`6bd85c73`）；② 跨锚点实测 **26.1.2↔26.2↔26.3 漂移 13 类**——§7.2「差异集中在客户端渲染/输入域」估计偏乐观，实际含三包 record、MinecraftServer ctor 尾参（NotificationManager 26.2+ 才有）、Gui/Hud 屏幕聊天迁移（K 过线）、authlib 包（yggdrasil→services）、FML 12 枚举（COMMON→LOCAL）、PositionPath 插值；③ 按 hassium-manifold 收口：compat 门面（ChunkLightPacketCompat/ClientHudCompat/EntityInterpCompat/ChunkPacketDataCompat.applyToChunk/ShadowServerCompat.datapackWorldGen）+ Mixin 目标 #if（Vec3↔PositionPath、setScreen 分家）+ NeoForge FML 枚举 #if + spam 抽象方法去 @Override 零 #if 跨 J；④ **三锚点编译全绿**（26.1.2/26.2/26.3 × fabric/neoforge，`db54fa18`）+ scanVersionBoundaries OK + common:test 绿（测试源补扫 135 处 pos.x→x()、asLong→pack）；⑤ 测试源漏扫教训：机械清扫要含 `src/test` | poc/mc26-build@`db54fa18` |
| 2026-09-30 | 阶段④运行时波折（poc 分支）：① fabric.mod.json `depends.fabric`→`fabric-api`（26.x umbrella 移除 `provides:['fabric']`）+ java 下限 25；② **mixin 注入目标/描述符/字段是编译盲区**——冒烟逐个揭：`LevelRenderer.addRecentlyCompiledSection` 26.3 移除（编译完成通知迁 `RenderSection.setSectionMesh`，MixinLevelRenderer 按段分家）、`MixinLightDataWrite.write` 随 record 化消失（STREAM_CODEC lambda 不可注入 ⇒ 指标停采门控 <MC_26_3，ESTIMATED_LIGHT_BYTES 兜底）、`setSectionMesh` 非 void 要 `CallbackInfoReturnable`、`ServerEntity.updateInterval` 26.3 变 `UpdateInterval` 对象（实体降帧门条件单点重定向 `test()` = 旧 tickCount 相位 + interval 除数双替换，`EntityUpdatePacing.pacedGateTest` 复用既有路径）；③ **教训**：@Shadow/@Accessor 字段名与注入目标名必须对照反编译源核 DECLARATION（宽松正则会被参数/局部名骗过），上锚点前批量审计 mixin 名单；④ 修复后 **26.3 fabric Phase I PASS**（bootcheck4，两轮 round 全过 + ServerSwitched） | poc/mc26-build@`4088a656` |
| 2026-09-30 | **D7 拍板：取消 NeoForge 测试版本支持**（26.2/26.3 `builds_for=fabric`，NeoForge 仅 26.1.2）：L2 批跑揭出 26.2/26.3 neoforge 六连 FAIL 共同根因——**非正式版本 NeoForge 启动弹窗警告挡自动进服**（客户端停在弹窗、`ConnectScreen` 从不出现、JOIN_WAIT 120s 超时；26.1.2 neoforge 无弹窗双场景 PASS）；NeoForge 兼容代码/编译保留（`neoforge:compileJava` 三锚点仍绿），仅收窄发布与冒烟矩阵。batch 锚点表同步（4 组合：26.1.2×2 + 26.2/26.3 fabric） | 用户拍板；poc 分支随冒烟修复提交 |
| 2026-09-30 | **R8 同族竞态根因定案 + 修复**（poc `b25e271d`）：dimension 偶发崩溃/断供 = **palette 撕裂**——`PalettedContainer.read` 在 bits 未变时复用旧 `Data`，`palette.read`/`storage.getRaw()` 原地改写**已发布**对象，而影子注入柱在 decode 前已入 `injectedChunks` 表（`decodeInjectedPacketLocked` 设计如此），光引擎/世界生成工作线程并发 `getBlockState` 读到撕裂 palette：HashMapPalette 抛 `MissingPaletteEntryException`（26.3 dimension 光引擎线程未捕获异常 → consecutive executor 停摆 → 下界 `loadedChunks=0`）、LinearPalette 抛 AIOOBE（26.1.2 dimension applyStep 崩溃、fast-throw 空栈）。**修复**：`ChunkPacketDataCompat.applyToChunk` 改写时复制换节（新节离线填充后逐槽换新，读者要么旧要么新），尾段与原版同构、BE 迭代 API 差异 `#if` 收口；连带修 `getReadBuffer` 游标统一 `readerIndex(0)`。诊断基建：冒烟 JVM `-XX:-OmitStackTraceInFastThrow`（空栈根因）、probe 目录清旧 roundN.json（PROBE 假阳性） | poc/mc26-build@`b25e271d` |

### 2026-10-12 适配复核记录（不改变 D1–D7）

- 检查基线：`master`@`ea2e9222`。本次三锚点 `common:test` 各 **382 tests / 0 failures / 0 errors / 0 skipped**；`scanVersionBoundaries` 全绿。正式支持的四组合（26.1.2 fabric+neoforge、26.2/26.3 fabric）均 `build` 成功，内嵌 zstd-jni 包装检查通过；实际产物版本仍为 **2.0.3**，未切 3.0.0。
- 发布链路待修（静态检查，未运行 GitHub Actions / 未上传）：① `gradle.properties` 将本地 HTTP/HTTPS 代理 `127.0.0.1:7890` 写为仓库默认，CI 未覆盖；② 发布 workflow 仍只显式安装 JDK 21，daemon 要求 25，且 `gradle-daemon-jvm.properties` 只有 `toolchainVersion=25`、没有平台下载 URL，冷环境不能依赖 settings 的 Foojay 替 daemon 启动前供给 JDK（[Gradle daemon provisioning](https://docs.gradle.org/current/userguide/gradle_daemon.html#sec:daemon_jvm_auto_detection_and_auto_provisioning)）；③ 两份 `publish-mods` 脚本的 `AnchorsOnly` 仍列旧七锚点，选此模式会引用已删除的 properties。
- D7「保留 NeoForge 三锚点编译」尚缺可执行入口：`settings.gradle` 仅 include `builds_for`，26.2/26.3 的 `projects` 实测只含 common/fabric。本次未临时修改发布矩阵来编译取消支持的 NeoForge；历史六组合编译绿不能当作当前六组合已复验。
- 配置 GUI 仍未恢复：两加载器未编入 `cloth-ui`，Fabric 配置工厂仍返回 null，NeoForge 探测因缺自身 UI 类而跳过注册；26.3 的 `cloth_config_version` 有值也不会启用它。本地 `hassium-manifold` skill 仍写旧七段/旧白名单，需要同步现行 J/K/L。
- 本次未重跑游戏冒烟；工作区没有 `build/smoke-test/results` 可供复核，§9 的 12 场 PASS 保留为 2026-09-30 历史记录。移动屋檐/洞口目视验收仍未完成，不能以本次编译/单测/打包替代。

### 2026-10-12 发布链路修复

- 用户确认 master 版本号改为 **3.0.0**；旧版本 BUG 修复与 **2.x** 发布使用 `2.0.0` 分支。该分支未修改（仍为 `mod_version=2.0.3`），master push / 对外上传仍未执行。
- 仓库移除本地代理及强制 TLS 设置，保留用户级配置示例；CI 改为显式安装 JDK 25；PowerShell / Bash 发布脚本的 AnchorsOnly 改为 26.1.2 / 26.2 / 26.3，workflow 输入说明与 README 同步分支发布路径。
- 本地验证：正式四组合 `build` 与 zstd-jni 包装检查通过，JAR 文件名及包内元数据均为 3.0.0；三锚点各 382 项单测全过、边界扫描通过。四组合 Modrinth 版本号 / MC 版本 / loader 配置断言通过，`publishAll --dry-run` 任务图按 D7 正确选择加载器。
- 两份发布脚本语法检查与隔离模拟执行通过：AnchorsOnly 确实执行三锚点，两平台 debug 参数均传到 Gradle。模拟使用假 Gradle 与占位 token，未访问发布平台；GitHub Actions 冷环境 / 真实平台上传尚未实跑，移动光照验收状态不变。

### 2026-10-12 冒烟门禁修复

- 经典、往返、切维按 D7 四组合复验。26.2/26.3 的原始 FAIL 包含 OSHI 启动采集 Perflib 009 环境错误与退出线程造成的看门狗误报；仅精确匹配该条环境错误，退出宽限后继续取证。
- 26.x 影子存档探针改为 `dimensions/minecraft/<dimension>/region`；切维 analyzer 必查 OW→Nether→End→OW 四份探针及本场退出后的磁盘快照，缺失证据按 FAIL。天空光历史切维时清理，无天空光维度不套用天空光回黑判据。
- 往返须有真实卸载回调、缓存消失、回程重新到达及最终距离 ≤4 chunk 的证据。NeoForge 首次加强门禁发现复用存档场次未实际移动，失败日志保留；补上空中出发、掉头命令等待和持续按键注入。首次 dump 前的场景 FAIL 不再按启动失败重试，启动重试前归档每次日志与结果。
- 新门禁复验发现 NeoForge 26.1.2 与 Fabric 26.2 在切末地时实际崩溃：`ChunkMap.setChunkUnsaved` 的裸 `LongLinkedOpenHashSet` 被并发写坏，`rehash` 出现 index=-1。三锚点源码确认同一入口；`MixinChunkMap` 将非主循环的影子端标记提交到所属影子端主循环，异步入队以避免持 chunkLock 同步等待，真实服务端不改变路径。26.3 编译、边界扫描及 common:test 通过，最终代码用 suffix=`gatefix2_20261012`、`MaxRetries=1` 完整复验。
- `gatefix2` 的 NeoForge 切维四轮已完成、未再崩溃，但磁盘门禁拒绝了其 `gameDirectory=.` 相对路径；探针改为绝对规范路径，harness 兼容相对路径时以 ClientRunDir 为基准并保留缓存目录边界检查。相对路径与越界路径测试通过；该场用 suffix=`gatefix3_20261012`、`MaxRetries=1` 单独补跑，原始失败结果保留。
- `gatefix3` 的四轮和三维度磁盘门禁通过，但严格日志审计发现算光线程 `MissingPaletteEntryException`：增量路径仍对已发布 section 原地 `read`，与已修整柱路径同族。`applySectionDelta` 的 FULL/BLOCKS 均复制基线，在副本解码或修改后发布完整新节，避免新增注册表访问；保留既有光屏障与 chunk 锁。suffix=`gatefix4_20261012`、`MaxRetries=1` 再跑完整三场景四组合，历史失败不覆盖。
- 35 项 analyzer 测试、PowerShell 辅助门禁与脚本语法检查通过；运行时结果及复验 session 见 §9。此专项不代替移动屋檐/洞口目视验收，不改变 D1–D7。
- 最终 `gatefix4_20261012` 批次：**classic 4/4、flyroundtrip 4/4、dimension 4/4 PASS**，有效场次均 Attempts=1；NeoForge 26.2/26.3 按 D7 SKIP。12 场致命日志与看门狗转储均为 0；28 轮原始探针、往返真实卸载/回填与最终距离、四轮切维以及退出后的三维度 region 快照独立复核通过。汇总：`build/smoke-test/smoke-summary-26x-fixed-2026-10-12.csv`；完整批次：`batch-results-R-gatefix4_20261012.csv`。三锚点单测各 382 项通过，历史失败完整保留。

### 2026-10-13 代码 BUG 审查（检查阶段记录，修复见下）

- 基线：`master`@`babb6087` 加现有工作区配置；默认 `mc_ver=26.2`，工作区已恢复 26.2/26.3 的 `builds_for=fabric,neoforge`。本次保留这些既有改动，不以编译结果替代支持矩阵或发布决策。业务源码未修改。
- 验证：26.1.2 / 26.2 / 26.3 的 `common:test --rerun` 各 **382 tests / 0 failures / 0 errors / 0 skipped**；三锚点 Fabric + NeoForge `compileJava` 均成功；26.2 `scanVersionBoundaries` 通过。代码审查阶段未运行游戏冒烟；后续 NeoForge 补验见本节下一小节。原有光照目视验收状态不变。
- 隔离复现：`build/bug-audit-20261013/BugAudit.java` 与 `audit.init.gradle`（本地构建目录，不入库）在 26.2 上复现配置合并、影子代际、共享池关停及超大区块格式错误四项底层机制；仅使用临时 region 文件，没有访问游戏存档。命令：`.\gradlew.bat common:bugAudit "-Pmc_ver=26.2" -I build/bug-audit-20261013/audit.init.gradle`。其余发现由当前源码与 minecraft-dev 原版源码调用链核实，未声称完成游戏内复现。

| 编号 | 优先级 | 定位与触发条件 | 影响 / 修复关注点 |
|------|--------|----------------|------------------|
| A01 | P1 | `MixinRegionFile.hassium$onGetChunkDataInputStream`：曾写 type 126 后关闭 `storage.enabled`，或用单人模式打开该世界 | 读取也被配置/专用服门挡住；原版不识别 126，旧柱不可读并可能重生成覆盖。格式读取兼容须独立于新格式写开关 |
| A02 | P1 | `MixinRegionFile.hassium$writeHassiumPayload`：压缩后槽超过 255 扇区，仍委托原版 `RegionFile.write` | 原版 external stub 使用 `this.version`，写出 type 130，而 `.mcc` 内容仍为 126 载荷；隔离复现读取报 `incorrect header check` |
| A03 | P1 | `ConfigSnapshotAdapter.fromMerged/seedGenValue`：LAN 网络面开，client seedGen=true、server seedGen=false | 合并丢弃服务器种子开关；`isSeedGenEnabled` 消费客户端值，握手仍可发送真实种子。需分别保存客户端/服务端开关 |
| A04 | P1 | `MixinConnection.hassium$tryAggregate`：聚合连接收到新玩家信息包后，出生 Bundle 直接发送 | Bundle 可越过已缓冲的 PlayerInfo，客户端缺 PlayerInfo 时拒建玩家实体；需要保持聚合与直发包的应用顺序，单独 flush 仍须考虑客户端异步解码 |
| A05 | P1 | `RuntimeServerContext.setShadowServer`：旧端关停未结束，新端在有界等待后创建 | true→true 不增加 generation，旧端仍可清除新端存储 gate、hash 与增量/热度状态；隔离复现新旧 generation 相同 |
| A06 | P1 | `SeedGenLevelCompat.shutdown`：与 A05 相同的生命周期交叠 | 旧端无条件关闭静态 `ShadowWorldgenExecutor`；新端已复用该池，随后停止主循环/拒收光照任务。代际修复不能单独解决此项 |
| A07 | P2 | `HassiumAggregationManager.flushInternal`：同一批超过 4096 个小包，但未超字节阈值 | 发送端只按字节拆批，接收端拒绝 >4096 子包整帧；原包已取消发送，更新丢失 |
| A08 | P2 | `EntityUpdatePacing.gateImpulse`：26.3 NEVER 周期实体只变位置，没有 metadata dirty（如 `/data merge` 移动末地水晶） | `needsSync` 被吞，周期门也恒 false，客户端位置持续陈旧；豁免周期降帧的实体应保留强制同步 |
| A09 | P2 | `ServerHandshakeActivation.handleActivationReady`：候选字典 offer 发出后新玩家入服，只 ACK active 字典 | ACTIVE ACK 未转交 `onDictionaryAck` 的候选补发分支；新连接进入等待集合却收不到候选，本轮热更超时且无自动重试 |

### 2026-10-13 九项 BUG 修复：2.0.4 → 3.0.0

- 用户要求先修 `2.0.0` 并更新小版本，再移植到 master。2.x 从 `c589088a` 起修复 A01–A09，版本改为 **2.0.4**，提交 `cb23e2ea`；master 保持 **3.0.0** 并移植同一修复，26.3 的 NEVER 周期另按对象型 UpdateInterval 适配。
- 关键行为：读旧 126 不随写开关关闭；external stub 跟随实际载荷类型（兼容旧错误标记）；LAN SeedGen 两端值分离；Bundle 发送与客户端应用均有序；影子端代际/执行器按实例隔离；聚合拆批遵守 4096 子包上限；豁免实体保留强制同步；ACTIVE ACK 能补发热更候选。
- 新增 **11 项回归测试**。2.x 七锚点共 18 加载器组合编译与边界扫描通过，单测 1.20.1 为 **394**、其余六锚点各 **387**；3.x 三锚点各 **394** 单测、六组合编译与边界扫描通过。失败、错误、跳过均为 0。2.0.4 的 1.21.1 三加载器、3.0.0 的 26.3 双加载器打包与内嵌 zstd-jni 检查通过。
- 运行时 classic 双轮连服/断连/重连：2.x Fabric 1.21.1、Forge 1.20.1 PASS；3.x Fabric 26.3、NeoForge 26.3 PASS，最后的 Bundle 异步冲刷收尾代码在 Fabric 26.3 再跑双轮 PASS。2.x 冷启动脚本的运行目录创建顺序已补齐，首轮 EULA 准备失败结果保留。
- 详细测试范围与 session 见 [九项修复交接](handoff/handoff-2026-10-13-nine-bugfixes.md)。本次未推送/发布，光照目视验收状态不变。

### 2026-10-13 NeoForge 26.2/26.3 冒烟补验

- 按用户请求使用当前工作区配置补跑 26.2/26.3 NeoForge 的 classic、flyroundtrip、dimension；工作区原有 `builds_for=fabric,neoforge` 与加载器版本 26.2.0.90 / 26.3.0.76 均保留。
- classic：26.2 在 `JOIN_WAIT` 120 秒超时；26.3 两轮统计与 `ServerSwitched` 通过，但日志审计因 OSHI `Perflib 009` 错误判 FAIL。
- flyroundtrip：两版都启动并进入 outbound 步骤，但 `MOVE_CRUISE_START` 与 `MOVE_END` 的玩家方块 x/z 坐标相同，`flight.anchorUnloaded=0`，未完成实际离开/返回门禁。
- dimension：两版四轮探针与 post-exit 三维 region 门禁均通过（overworld/nether/end = 10/12/10）。26.2 退出时因 `ReflectionCompat`、`EntityPacketCounters` 的 `NoClassDefFoundError` 崩溃；26.3 客户端退出码 0，但日志审计仍被两条 `Perflib 009` 错误判 FAIL。六场整体结果均为 FAIL，session 与结果文件见 §9。
- 首轮仅记录运行证据；后续修复与复验见下节。

### 2026-10-13 NeoForge 冒烟失败修复

- Perflib：原白名单仅匹配 Fabric 的 `(HkeyPerformanceDataUtil)`；补充 NeoForge 缩写/完整 logger 格式，仍要求 `main/ERROR` 和同一条 `Perflib 009` 正文。FATAL、其它线程、其它 logger 和其它注册表错误的负例均保留。
- 自动进服与飞行：仅在所选 loader 的开发 `run/client` 目录固定 `showLoadWarnings = false`（NeoForge）与 `pauseOnLostFocus:false`。NeoForge 仍记录加载警告；失焦不会再打开暂停菜单并使 `IN_GAME` 按键失效。首次修复复验 `26.2_neoforge_I_flyrt_fix1_20261013` 已实测飞出 39 chunk、锚点卸载/回填、返回距离 1 chunk、回黑 0。
- 额外实测修复：`26.3_neoforge_I_nf_fix1_20261013` 的 R2 出现 `Registry is already frozen`，NeoForge `RegistryManager.applySnapshot` 正在重排 ID 时，提前启动的影子 WorldLoader 冻结了共享注册表。新增平台能力 `supportsShadowPreloadDuringLogin`，NeoForge 返回 false；影子装配延至现有 Play `onLogin` 入口。Fabric 保留预载，未新增锁、未削弱光照/卸载/日志门禁。该失败日志与原六场失败均保留。
- 26.2 首轮 dimension 退出缺类：日志显示运行时使用 `neoforge/build/26_2/classes/java/main`；同时间其它构建正在切换锚点，事后该目录的目标类确实缺失。并发输出改写是当前证据支持的原因；本轮串行六场及 26.2 dimension 退出均未复现，类加载异常仍受日志门禁检查。
- 验证：三锚点各 383 项 `common:test`、双 loader 编译与 `scanVersionBoundaries` 通过；35 项 Python 门禁测试、PowerShell 配置/日志辅助门禁回归通过。最终六场均一次执行 PASS，客户端退出码全 0、日志审计失败/看门狗转储全 0；14 份原始探针已交叉核验，经典重连无补救重试。汇总见 `build/smoke-test/neoforge-fix2-20261013.csv` / `.json`，各场日志、结果和探针按下表 session 保留。

| 版本 | 场景 | 结果 | 核心证据 | Session ID |
|------|------|------|----------|------------|
| 26.2 | classic | PASS | R1/R2 通过，VD 20→10 | `26.2_neoforge_I_classic_fix2_20261013` |
| 26.2 | flyroundtrip | PASS | 最远 41 chunk，返回距离 0；卸载/回填/存在均 true，回黑 0 | `26.2_neoforge_I_flyrt_fix2_20261013` |
| 26.2 | dimension | PASS | OW→Nether→End→OW 四轮通过；三维 region 数 10/12/10，正常退出 | `26.2_neoforge_I_dimension_fix2_20261013` |
| 26.3 | classic | PASS | R1/R2 通过，VD 20→10，注册表同步错误未复现 | `26.3_neoforge_I_classic_fix2_20261013` |
| 26.3 | flyroundtrip | PASS | 最远 40 chunk，返回距离 1；卸载/回填/存在均 true，回黑 0 | `26.3_neoforge_I_flyrt_fix2_20261013` |
| 26.3 | dimension | PASS | OW→Nether→End→OW 四轮通过；三维 region 数 10/8/10 | `26.3_neoforge_I_dimension_fix2_20261013` |

### 2026-10-13 出口聚合 v3 实施

用户批准服务端→客户端 PLAY 无损字节传输、64 KiB 历史、自动模板及完整帧择优；保留单 TCP、Pull、上行和实体频率。旧聚合对象路径退役，旧 Hassium 协议拒绝并提示升级。NeoForge 大小预编码须与原生编码器复用，防止 Mod codec 二次执行。协议详见 [egress-v3.md](egress-v3.md)。

### 2026-10-13 实际红石工厂复测

按用户提供的 26.1.2 氙氪重工 ZIP，每次启动恢复独立副本；预热冻结后由客户端定位并解除冻结开始测量。修复工厂负 Y 出生点被 JOIN 的旧正高度条件阻塞的问题。目标 TPS 必须同时报告实测 TPS，原有机器、加载器、实体 pacing 及出口算法不改动；依据实际字节回放比较三个模式。结果见 [工厂复测](handoff/handoff-2026-10-13-factory-egress-v3.md)，不把普通世界或旧版历史语料比例当作通用收益。

### 2026-10-13 出口模板学习与准入改进

按工厂候选诊断改进发送端学习和定义准入：跨帧多样本变量并集、按当前批次定义回本粗筛、16 个新定义上限、失败退避与周期探测；线格式与最终完整帧择优不变。对同一三档采集使用旧/新 codec 串行六遍回放，首遍预热剔除。运行期模板仍无额外带宽贡献；完整编码 CPU 降约 52–56%，整段含预热/冻结增加约 0.6–1.0 KiB。具体权衡见 [工厂报告后续分析](handoff/handoff-2026-10-13-factory-egress-v3.md)，不将未验证的模板收益写成承诺。

### 2026-10-16 Lomka 兼容与移动 trace 复核

- 用户明确 `2.0.0` 分支继续维护（D8），master 补丁版本改为 **3.0.1**。master 的 `LayerLightSectionStorage` Mixin 优先级从默认 1000 提到 1100，使已有 `getStoredLevel` / `setStoredLevel` 空层保护可装饰 Lomka 对 `setStoredLevel` 的覆写；光照邻域门禁及交付域均未改。Lomka 上游当前 0.7.0 源码仍默认优先级 1000 并覆写该方法；用户给的 Forge 1.20.1、NeoForge 1.21 JAR 只用于 2.x 联测，不能作为 master 26.x 共装证明。
- 2.x 先前四场 `-MoveSeconds 10` Forge / NeoForge 复跑实际上没有飞行：脚本传入 Gradle 属性，但旧 2.x loader 配置未映射到客户端 JVM；`execFly` 得到 0 秒而跳过，日志没有 `MOVE_START` / `MOVE_END`。其 PASS 不能作为移动验收。旧 probe 中 NeoForge 176、Forge 159 个 `injectedNotReady` 均在最终交付形状外，且最终 ready/applied/actualPresent 覆盖当时完整权威形状；缺口来源不从未飞行的会话推断。修复透传及飞行执行后，Forge 1.20.1 / NeoForge 1.21.1 的 seedGen 关闭 classic 新会话各两轮真实高空 TP 后水平跨 10–12 柱并 PASS；seedGen 开启的 seedgen 新会话各单轮水平跨 6–7 柱并 PASS，四场客户端退出码均为 0。
- analyzer 修复只在 classic 第一轮、VD≥20 且最终 `actualPresent` 与当前完整原版形状精确一致时，将窗外差集记为 INFO。复核旧 R2 缓存重连与静止 `halo_only` 两个分支后，均以最终 `serverVD` 原版形状与 OVD16 方窗的并集为交付域；第二轮 VD10/OVD16 的有效交付带仍判 P0，域外真正光环仍可记 INFO。分类使用原始全量集合，避免报告仅显示前 64 个坐标时漏判第 65 个窗内缺口。master 同步判据并加 26.3 新圆形视距（VD20=1573 柱）反例测试。
- master 同步高空 TP、等待客户端位置确认、水平段独立计时与真实位移 P0 门禁；NeoForge 26.1.2 / Fabric 26.2 / Fabric 26.3 的 common 与 loader 编译成功，NeoForge 26.1.2 / Fabric 26.3 `build` 重新生成含新场景代码的 **3.0.1** JAR，内嵌 zstd-jni 检查通过。先前 26.3 `common:test` **422 tests / 0 failures / 0 errors / 0 skipped**、`scanVersionBoundaries` 通过；analyzer 43 项测试通过。未运行 26.x Lomka 共装游戏测试，移动屋檐/洞口目视验收仍待完成。

### 2026-10-18 客户端 / 真实服务端隔离审计

- 完成当前 3.0.1 客户端入口、影子端装配/关停、公共 Mixin、区块/光照/存储/POI、LAN 与服务端配套收发的隔离审查；业务源码未修改。完整清单与定位见 [隔离审计](handoff/handoff-2026-10-18-client-server-isolation-audit.md)。
- I01（P1）：进程全局 shadow 标志不能代表当前操作归属，重叠/残留时可误改真实内置服 region 读写、定时任务、装载/广播/reload；I02（P2）：物理客户端光照 null 补丁覆盖真实内置服，回退 getter 实测为 2048；I03（P2）：全未协商玩家光照广播重复发送；I04（P2）：Pull 服务端入口未复核网络门/玩家协商能力，epoch 传入请求自身。
- 三锚点各 422 项既有单测通过；本地独立探针三锚点各 8 项通过，确认 I01/I02/I03 的当前缺口行为（并非隔离验收通过），I04 为源码调用链结论。探针位于 Git 忽略的 `build/client-isolation-audit/`。未做真实存档破坏性实验、游戏冒烟或修复，不改变既有升级/发布决策与光照目视验收状态。
- 同日追加 2.x / 3.x 对照：`2.0.0` 工作区 `7bdab012`（版本 2.1.1）同样存在 I01–I04；补出 I04b（P1，跨玩家 epoch 污染）和 I05（P1，通用协议标志误作为区块接管条件）。2.x 七锚点既有单测全过（A=423，其余=416），独立探针 A=9、其余=10；3.x 三锚点探针均 10/10。五组修复范围、旧版 Forge/trackChunk/dummy/出生点路径及验收计划见 [双线修复准备](handoff/handoff-2026-10-18-client-server-isolation-2x-3x-comparison.md)。两边均未改业务源码。

### 2026-10-18 双线客户端隔离修复与验收

- 2.x / 3.x 已完成 I01–I05（含 I04b）双线修复：实例 / 目录归属、资源异步作用域、公共生命周期隔离、光照 owner 门、原版广播与区块接管门、Pull 准入及连接账本。实际回退另修 Fabric / Forge 结构性注册与配置关闭后的影子回调创建；保留各线适配和全部光照红线。
- 最终全版本单测 4,365 项（A=441、2.x 其余各 434、3.x 各 440），失败 / 错误 / 跳过 0；24 个 loader 编译组合、两线版本边界扫描、89 项 analyzer 测试通过。游戏 25 场 PASS：13 场专用服 classic、4 场回退、3 场真实单人、2 场 SeedGen、2 场四轮切维、1 场往返飞行。
- 单人刻意保持旧进程影子标志到保存退出：三个锚点共 14,441 个 region 记录全为原版 type 2；memory 路径、tick 入队和无影子实例通过。26.3 往返最远 39 柱，锚点卸载 / 返回成立，黑块回归数 0。完整 session、机制、证据路径及验收边界见 [双线修复与验收](handoff/handoff-2026-10-18-client-server-isolation-fixes.md)。未做外部 mod 共装及屋檐 / 洞口目视；本次修复、回归与文档一并本地提交，不推送或发布。

### 2026-10-19 同连接影子重建修复

- 用户要求将 R3 复现修复提交 `2.0.0` 并检查主分支。2.x 已提交 `0c13d21a`；主分支确认共有“重建撤销登录许可”与“本地化文件锁异常漏重试”两处缺陷，已同步相同修复及回归测试，保留 26.x 适配，不修改版本号或传输协议。
- 26.x 三锚点各 444 项全量单测、四个当前支持的 loader 组合编译及版本边界扫描通过；26.3 Fabric 四轮切维会话 `r3fix_L_dimension` PASS（客户端与 analyzer 退出码均为 0，四轮驻留/应用断言及退出后磁盘检查通过）。R3 1.20.1 实测与主分支验证分别记录，26.x 未共装外部自定义维度模组；见 [重建生命周期交接](handoff/handoff-2026-10-19-shadow-rebuild-login-permit.md)。

### 2026-10-19 2.x 缓存装配修复提交与主分支移植分析

- 用户要求先提交 2.x，再分析如何移植到主分支。2.x 已本地提交 `e5464014`（原生大包与积压计量）和 `2c4e57ff`（缓存装配隔离、权威 BE 快照、三方兼容与冒烟工具），工作区干净，未推送。
- 对照 `master@db72ff40`：确认仍有相关机制缺口；仅执行补丁 `--check` 与源码/API 核对，未移植业务代码。26.1.2/26.2 资源构造新增组件列表，26.3 改用 LoadResult，RELOADABLE 注册表需单独补 schema；主分支离线节发布、客户端 Bridge 和既有重建修复必须保留。旧 Agent ASM 9.7.1 实测无法读取主分支 major 69 class。
- 建议按传输/激活、BE 快照、26.x bootstrap、工具/实际三方适配分阶梯实施；不带回 Forge 或旧七段补丁，不以 R3 旧版实跑替代 26.x 验收。详细范围、API 签名、排除项与四组合验证计划见 [移植分析](handoff/handoff-2026-10-19-cache-bootstrap-2x-main-port.md)。当前状态为分析完成、实施待开展。

### 2026-10-19 缓存装配兼容修复移植完成

- 用户批准后从 master@a1b117cd 手工移植 A/B/C/D：原生大包与激活门、权威BE快照、
  26.x分层注册表缓存装配和定向冒烟工具；保留现有Bridge、复制后发布、POI串行化和算光红线。
- 三锚点各462项单测、正式四组合build及版本边界扫描通过；48项Python门禁和PowerShell辅助测试通过。
  10场定向运行通过：四组合缓存重连、L纯缓存四轮切维、L/JN完整生成、JF/L单人隔离、JN两自定义维连续重建。
- Underworld1.3.2白名单拒绝Hassium；用户后供1.4.1可进服，因玩家命令限制未完成切维，用户取消该项。
  两版不标为通过，记录与本轮构建/运行指标见[移植交接](handoff/handoff-2026-10-19-cache-bootstrap-2x-main-port.md) §8。
- 原始9份运行文件已哈希恢复；最初备份漏config/hassium，TOML回收至post-classic冒烟快照，未声称逐字恢复运行前状态。
  未推送/发布，未修改用户原有logo/video；移动屋檐/洞口目视验收状态不变。

### 2026-10-19 2.1.2 / 3.0.2 发布准备

- 用户要求补齐剩余验收、升版、最终打包检查后提交，先不发布；3.x 恢复六组合的当前矩阵按 D9 同步到 AGENTS、README 与 version-segments。
- master 升到 3.0.2；修正 NeoForge 26.3 datapack schema 枚举 API。三锚点 1386 项测试、六组合最终 build / 边界扫描 / JAR 内容 / Modrinth 配置检查通过。
- NeoForge 26.2、26.3 的 classic / flyroundtrip / dimension 六场均 PASS，日志审计、探针门禁及挂起转储均无失败；维度场景保持创造模式。
- `release302_light_visual`、`release302_light_frames` 两场光照专项 PASS；33 张实际往返运动采样帧覆盖屋檐、洞口、洞内光源和重连缓存回程，目视通过；用户另确认屋檐正常。未拆除/旁路齐套门。完整指标与截图路径见[移植交接 §9](handoff/handoff-2026-10-19-cache-bootstrap-2x-main-port.md)。
- 本轮主分支 123 份运行配置已完整备份并逐项恢复到原始 SHA-256；未复用上一轮缺漏的 TOML 备份。最终文件清单及哈希在 `build/release-prep/artifact-manifest.json`。

### 2026-10-20 恢复 3.x Cloth 配置界面

- Fabric / NeoForge 恢复共享 `cloth-ui` 源码和真实配置工厂；Cloth 为可选依赖、不内嵌，开发运行带入对应版本。三锚点依赖版本继续取自 `versionProperties`，未升版。
- 三锚点完整 `build scanVersionBoundaries`、六组合 JAR 内容和原生库包装检查通过；1386 项 Java 测试无失败、错误或跳过。
- 六组合均经真实 Mod Menu / NeoForge 模组列表打开、修改、保存、重新打开，客户端值落盘且服务端配置 SHA-256 均不变。使用独立运行目录，没有加载世界或重跑网络/光照场景。
- 26.3 两加载器分别排除 Cloth 运行依赖后正常启动：Fabric 配置入口安全返回，NeoForge 不注册配置按钮；两场正常退出，加上六场 GUI 共八场退出码 0。
- 清理停用 GUI、返回 null、Cloth 内嵌和覆盖服务端字段等过时说明；完整验证与初次资源缺失失败记录见 [GUI 交接](handoff/handoff-2026-10-20-cloth-config-gui.md)。

## 9. 进度表（滚动更新，收工必更）

| 阶段 | 任务 | 状态 | 会话备注 |
|------|------|------|----------|
| ① | forge/ + loom-forge.gradle 删除 | ✅ 完成 | 2026-09-30；含 mods.toml/ServiceLoader/mixins 元数据 |
| ① | settings/buildSrc/脚本/发布 map forge 条目 | ✅ 完成 | 含 SRG 预检整段删除；batch 锚点改 1.20.1 fabric |
| ① | versionProperties builds_for 去 forge | ✅ 完成 | 1.20.1→fabric；11 文件含死键清理 |
| ① | 双 loader 编译验证 + 提交 | ✅ 完成 | fabric+neoforge compileJava(1.21.1) BUILD SUCCESSFUL；scanVersionBoundaries OK；ps1 解析 OK |
| ② | 剥离脚本 + ~119 文件版本块剥离（保留 1.21.11 路径） | ✅ 完成 | 实测 254 块/70 文件（含 forge 删除后基数）；提交 `e8104135` |
| ② | compat 坍缩 + ShadowRegistryWindow 整删 | ✅ 完成 | 确认整文件 `<1.21.1` 门控；6 处 javadoc MC_1_* token 改写 |
| ② | versionProperties/白名单/mc_ver 默认收缩 | ✅ 完成 | mc_ver 并入提交 1；properties×11 删除；白名单 ×3 = MC_1_21_11 |
| ② | 双 loader 编译 + common:test + scanVersionBoundaries | ✅ 完成 | 提交 1 前后各一轮全绿（默认配置 MC_1_21_11=0） |
| ② | Phase I 冒烟 ×2（fabric/neoforge）+ 提交 | ✅ 完成 | seedgen×2 PASS；dimension neoforge PASS、fabric 首跑偶发 FAIL 重跑 PASS（R8）；batch 锚点收缩为 1.21.11×双 loader |
| ③ | tag baseline/1.21.11 + archive/pre-baseline + 切 legacy-1.21 | ✅ 完成 | tag@`570ab7f3`；archive@`c589088a`；legacy-1.21@baseline |
| ③ | legacy MAINTENANCE.md + （可选）基线发布 | ✅ 完成（发布暂缓） | MAINTENANCE.md=`41a81aa1`；对外发布待显式批准 |
| ④ | M0 构建链 PoC + 选型回填 §8 | ✅ 完成 | `poc/mc26-build`@`175abc93`；结论见 §7.1，断点 §2.3/7/8/9 |
| ④ | 构建链接线到 26.x（buildSrc 双插件 + 三段 properties + 代理/JDK25） | ✅ 完成（poc 分支） | `poc/mc26-build`@`e3414a6d`；loom 门控（common/fabric）+ MDG（neoforge，含 runs 烟测接线移植）；注意事项见 §7.1 |
| ④ | 26.3 API 机械清扫 + 结构断点修复 + 跨锚点 #if 矩阵 | ✅ 完成 | 机械 ~1350 + 结构 29 + 跨锚点 13 类收口；poc `83558c64`/`5c333110`/`6bd85c73`/`db54fa18` |
| ④ | 三锚点编译门（compileJava ×3 锚点 ×2 loader + scan + test） | ✅ 完成 | 26.1.2/26.2/26.3 全绿；scanVersionBoundaries OK；common:test 绿 |
| ④ | 冒烟 + 光照红线验收 | ✅ 本轮完成 | 2026-10-19：NeoForge 26.2/26.3 × classic/flyroundtrip/dimension 六场 PASS；26.3 NeoForge 光照专项两场 PASS，33 张往返运动采样帧目视通过，用户另确认屋檐正常；见 3.0.2 发布准备记录。历史 D7 结果保留于 §8 |
| ④ | 文档修订 + 合入 master + 3.0.0 发布 | ◐ 合入✅ / 发布待批 | version-segments/AGENTS/mod-compat/README/runtime-smoke-test 均已同步 26.x + D7；**poc/mc26-build 已合入 master**（`2fc0f990`）；3.0.0 发布与 master push **待显式批准** |

### 2026-10-12 复核增量

| 任务 | 状态 | 证据 / 剩余工作 |
|------|------|-----------------|
| 26.x 冒烟门禁修复与三场景复验 | ✅ 修复与复验完成 | 待保存集合主循环串行化、增量复制换节及门禁修复；最终 `gatefix4_20261012` classic/flyroundtrip/dimension 各 4/4 PASS，全部一次执行，致命日志/看门狗转储 0；三锚点各 382 项单测、35 项 Python 门禁测试、PowerShell 辅助测试与 28 轮原始探针复核通过。汇总见 §8；历史 gatefix/gatefix2/gatefix3 失败保留；移动屋檐/洞口目视仍待完成 |
| 当前 master 三锚点单测、边界扫描与正式四组合打包 | ✅ 本次复验 | 三锚点各 382 项单测全过；四组合 build 与 zstd-jni 包装检查全过；见 §8 复核记录 |
| 发布链路 26.x 收尾 | ✅ 本地修复与验证 / CI 待实跑 | master=3.0.0；仓库默认直连、CI 显式 JDK 25、两脚本三锚点；四组合打包/元数据/发布任务图与脚本模拟通过；2.0.0 分支未改；未运行远端 Actions / 未发布 |
| NeoForge 26.2/26.3 当前工作区运行入口 | ⚠ 已重开并运行 | 当前用户工作区 `builds_for=fabric,neoforge`；加载器为 26.2.0.90 / 26.3.0.76，`runServer` / `runClient` 均启动。D7 决策记录仍见 §1；本次冒烟结论见下行 |
| 用户请求补跑 NeoForge 26.2/26.3 × classic/flyroundtrip/dimension（首轮） | ⚠ 6 场完成，0 场整体 PASS | 2026-10-13：26.2 classic join timeout；26.3 classic 两轮与切服通过、被 Perflib 门禁挡下；两版 flight 均未移动且未卸载锚点；两版 dimension 的 4 轮探针和三维 region 门通过，26.2 退出 CNFE、26.3 仅 Perflib FAIL。结果 JSON / 日志按 session ID 保存在 `build/smoke-test/{results,logs}`：`26.2_neoforge_I_nf_20261013`、`26.3_neoforge_I_nf_20261013`、`26.2_neoforge_I_flyrt_20261013`、`26.3_neoforge_I_flyrt_20261013`、`26.2_neoforge_I_dimension_20261013`、`26.3_neoforge_I_dimension_20261013` |
| NeoForge 26.2/26.3 冒烟失败修复与重跑 | ✅ 修复完成，最终 6/6 PASS | Perflib 格式、警告页、失焦暂停及额外发现的注册表同步竞争已修；三锚点各 383 项测试/六组合编译/边界扫描、35 项 Python 测试和 PowerShell 辅助测试通过。`fix2_20261013` 六场一次通过，14 探针已核验；日志错误/看门狗转储全 0，客户端退出码全 0；26.2 退出缺类未复现。原失败证据保留，具体 session 与指标见 §8 |
| GUI 与适配 skill 同步 | GUI 已恢复；skill 待单独更新 | 2026-10-20：cloth-ui、两加载器工厂与可选依赖恢复，六组合构建和实际 GUI 验证通过；hassium-manifold 的历史七段口径本轮未改，当前矩阵以 version-segments 为准 |
| 当前 master 工作区运行时冒烟与光照红线验收 | ✅ 三场景冒烟复验 / 目视仍待完成 | 本轮修复后 D7 四组合 × classic/flyroundtrip/dimension 12/12 PASS；早前适配复核未启动游戏的状态已补验。移动屋檐/洞口目视验收仍未完成，冒烟 PASS 不替代该项 |

### 2026-10-13 审查增量

| 任务 | 状态 | 证据 / 剩余工作 |
|------|------|-----------------|
| 当前工作区代码 BUG 检查 | ✅ 本轮检查完成 / 9 项待修复 | §8 A01–A09：6 项 P1、3 项 P2；4 项底层机制已隔离复现。三锚点各 382 项单测实际重跑通过，当前配置的六组合编译及边界扫描通过；未修改业务源码。代码审查阶段未运行游戏冒烟，后续 NeoForge 补验见上方记录 |

### 2026-10-13 修复增量

| 任务 | 状态 | 证据 / 剩余工作 |
|------|------|-----------------|
| A01–A09 修复与两版本线验证 | ✅ 2.0.4 修复 / 3.0.0 移植完成 | 2.x 提交 `cb23e2ea`；11 项新增回归，七旧锚点 + 三 26.x 锚点单测与编译通过；classic 双轮运行时通过，最终 session 见九项修复交接。未推送/发布，移动屋檐/洞口目视状态不变 |

### 2026-10-13 出口聚合 v3 进度

| 任务 | 状态 | 证据 / 剩余工作 |
|---|---|---|
| 服务端出口无损节流 v3 | ✅ 实施与验收完成 | 实际 native 字节管线、promise/FIFO、历史/模板择优、严格校验和出口计量已落地；每锚点 417 项单测、六组合编译、边界扫描与 30 个场景会话通过。8 份旧采集逐包回放；26.3 两加载器复采实际帧重现通过，对独立字典基线出口降约 12–22%，codec CPU 增加。完整指标、补跑失败证据及范围限制见 [出口 v3 交接](handoff/handoff-2026-10-13-egress-v3.md)。随本次实现提交，未推送/发布。 |
| 26.1.2 实际红石工厂复测 | ✅ 采集与回放完成 | 三次新副本有效场次 PASS，实测 20.19/94.88/103.89 TPS，对独立字典出口降 33.23–34.68%；每包/顺序及线上帧重现通过，错误 0。计量期模板引用 0，完整候选 CPU 高于历史。目标 200 TPS 未达到，不作为满速容量证明。测试工具与 [报告](handoff/handoff-2026-10-13-factory-egress-v3.md) 随本次提交。 |
| 出口模板学习、准入与 CPU 改进 | ✅ 实施与工厂回放验证完成 | 三锚点各 422 项单测、六加载器组合编译、边界扫描通过；三采集/三模式/旧新策略各六遍逐包校验与独立基线上限通过。运行期模板引用仍为 0，完整 CPU 降约 52–56%；预热/冻结使整段多 632–994 B，不能承诺额外省流量。未重新跑游戏；源码及 [报告](handoff/handoff-2026-10-13-factory-egress-v3.md) 随本次提交。 |

### 2026-10-16 Lomka 与 trace 修复进度

| 任务 | 状态 | 证据 / 剩余工作 |
|------|------|-----------------|
| master 光照 Mixin 优先级与 3.0.1 | ✅ 源码、文档、打包完成 | 1100 保留两处空层保护；三锚点对应加载器编译、26.1.2 NeoForge / 26.3 Fabric 3.0.1 打包通过。26.x 对应版本 Lomka 共装尚未测试，不引用两份旧版 JAR 的实测。 |
| 移动 trace 门禁误报复核与同步 | ✅ 2.x 四场真移动通过；master 静态修复完成 | 旧 2.x Forge / NeoForge 四场实际未飞行，原 PASS 撤回；新 seedGen 关闭 classic 两加载器各两轮、seedGen 开启 seedgen 两加载器各单轮高空 TP 后水平跨区块并 PASS。master 已同步 TP/独立计时、日志真实水平位移 P0、全量集合分类及 R2 `serverVD ∪ OVD16` P0 保护（含静止 halo 分支）；43 项 analyzer 测试通过，两个主锚点 3.0.1 JAR 已重打。26.x 游戏场未跑。 |

### 2026-10-18 客户端隔离审计进度

| 任务 | 状态 | 证据 / 剩余工作 |
|------|------|-----------------|
| 客户端 / 影子端与专用服、真实内置服隔离审计 | ✅ 历史基线已确认；缺口现已修复 | I01 P1、I02–I04 P2，详见 §8 与 [完整报告](handoff/handoff-2026-10-18-client-server-isolation-audit.md)。三锚点各 422 项单测、8 项独立探针通过；未修改业务源码，未进行游戏端到端复现。这是修复前证据；最终状态见下方修复验收。 |
| 2.x / 3.x 隔离对照与双线修复准备 | ✅ 对照基线保留；双线业务修复及验收完成 | 原四项均共有，新增 I04b/I05 P1；2.x 七锚点既有单测全过，3.x 三锚点与 2.x 七锚点均确认对应机制。探针 A=9、其余=10。见 [双线对照](handoff/handoff-2026-10-18-client-server-isolation-2x-3x-comparison.md)，本行保留修复前清单与探针基线；最终运行结果见下一行。 |
| 双线隔离修复与最终验收 | ✅ 代码、回归及运行验收完成 | 全版本 4,365 单测 / 0 失败错误跳过、24 loader 编译、两线边界扫描、89 analyzer；25 场游戏 PASS，覆盖专用服、单人和关闭客户端区块功能回退。详细隔离机制、session 与未覆盖范围见 [修复报告](handoff/handoff-2026-10-18-client-server-isolation-fixes.md)。 |

### 2026-10-19 影子重建生命周期修复

| 任务 | 状态 | 证据 / 剩余工作 |
|------|------|-----------------|
| R3 虚空根因修复与主分支同步 | ✅ 两线修复、本地提交与验证完成 | 2.x `0c13d21a`；R3 冷启动及缓存重连通过。master 同机制确认并移植，三锚点各 444 单测、四 loader 编译、边界扫描通过；`r3fix_L_dimension` 四轮切维 PASS。26.x 本轮未共装外部自定义维度模组。详见 [交接](handoff/handoff-2026-10-19-shadow-rebuild-login-permit.md)。 |

### 2026-10-19 缓存装配兼容修复与后续移植准备

| 任务 | 状态 | 证据 / 剩余工作 |
|------|------|-----------------|
| 2.x 大包、缓存装配与 BE 兼容修复提交 | ✅ 本地提交完成 | `e5464014` / `2c4e57ff`；旧七锚点 3262 测试、18 loader 编译与 R3/Fabric 定向运行通过，详见 2.x `docs/mod-compat.md` §12。 |
| 26.x 移植范围与原版 API 对照 | ✅ 分析完成 / 业务实施待开展 | 构造器、WorldStem、RELOADABLE、BE packet API 与 Java 25 Agent 阻碍已定位；四阶梯实施与四支持组合验证见 [移植分析](handoff/handoff-2026-10-19-cache-bootstrap-2x-main-port.md)。本轮仅更新文档，未把旧版结果标成 26.x 通过。 |

### 2026-10-19 主分支缓存装配移植验收

| 任务 | 状态 | 证据 / 覆盖边界 |
|---|---|---|
| 传输/激活、BE快照、26.x缓存bootstrap与定向工具 | ✅ 移植完成 | 源自2.x e5464014/2c4e57ff；保留master现有适配及区块节发布逻辑，未带回旧版精确Mod补丁 |
| 三锚点测试、正式四组合build、扫描与门禁 | ✅ 通过 | 1386项Java测试/0失败错误跳过、48项Python及PowerShell辅助检查；产物与日志见build/cache-port |
| 定向运行验证 | ✅ 10场通过 | 冷/重连、缓存切维、完整生成、单人隔离、类型ID不同的两个自定义维及连续重建；详细session见移植交接§8 |
| Underworld共装验证 | 已按用户要求停止 | 1.3.2白名单阻挡；1.4.1首轮进服缓存通过、玩家命令受限使切维超时，不列入兼容通过 |

### 2026-10-19 2.1.2 / 3.0.2 最终发布准备

| 任务 | 状态 | 证据 / 覆盖边界 |
|---|---|---|
| 2.1.2 全版本打包 | ✅ 通过 | 12 个 Minecraft 版本、33 个加载器组合完整 build / 边界扫描；5587 项测试全过；最终 JAR 与发布配置复核通过。原生 R3 / Forge 47.4.23 光照专项 32 项门禁全过，用户确认移动洞口、洞内光源及重连回程正常；Forge 47.4.21 开发启动失败记录保留在 2.x 文档，不计通过。 |
| 3.0.2 六组合打包与补验 | ✅ 通过 | 三锚点 / 六组合完整 build / 边界扫描、1386 项测试、最终 JAR 与发布配置通过；NeoForge 26.2/26.3 六场补验及光照专项通过，详见 §8 和移植交接 §9。 |
| 配置恢复与交付 | ✅ 本地提交范围已复核 | 两分支 123 / 57 份配置恢复并逐项核对原始 SHA-256；仅本地提交，不推送、打标签或发布；用户原有 logo / video 不纳入提交。 |

### 2026-10-20 图形配置界面恢复

| 任务 | 状态 | 证据 / 覆盖边界 |
|---|---|---|
| Fabric / NeoForge GUI 接线与说明清理 | ✅ 完成 | 三锚点可选 Cloth Config、共享 UI 源码及真实工厂恢复；README 与兼容文档同步 |
| 三锚点构建、测试与六组合 GUI | ✅ 通过 | 完整 build / 边界扫描、1386 测试、六 JAR 内容；六场实际打开/保存/重开且服务端哈希不变，详见 [GUI 交接](handoff/handoff-2026-10-20-cloth-config-gui.md) |
| 可选 Cloth 缺失回退 | ✅ 通过 | 26.3 Fabric / NeoForge 无 Cloth 正常启动与退出，入口安全返回 / 不注册；共八场客户端退出码 0 |

## 10. 风险与回退

| 风险 | 等级 | 缓解/回退 |
|------|------|-----------|
| architectury-loom 26.x 不可用（#328 开放） | ~~高~~ **已定论（M0）** | fabric-loom 1.17.21 + ModDevGradle 2.0.148 双插件方案验证可行，见 §7.1 |
| mixin 断点超出 §2.3 六项（深钩子：sendChanges 字段重定向、移动包 Redirect 群、RegionFile） | 中 | 每锚点编译 + 反编译逐个核对；断点预算外溢则该锚点延后，不阻塞其他锚点 |
| 剥离嵌套块出错 | 中 | 单锚点编译全量兜底 + top14 人工复核 + Phase I 冒烟；出错 revert 单笔提交 |
| Java 25 字节码 × 混淆后旧工具（自管 ASM/reflection 桥） | 低-中 | `ReflectionCompat` 26.x 段直接用官方名（去混淆收益）；`forgeMajor` ASM 资产随 forge/ 删除 |
| 影子端 POI/序列化行为回归 | 中 | Phase I + L2 场景 + 光照红线验收；并发串行化红线照常 |
| 26.1.2 与 26.2/26.3 间未实证差异（26.1.2 未反编译） | 低 | 段 J 锚点编译即实证；有差异走 compat/新边界 |
| ~~R8 影子世界 `MissingPaletteEntryException` 偶发竞态~~ | ~~低（偶发）~~ **已定案（已修 `b25e271d`）** | 根因 = `PalettedContainer.read` 复用路径原地改写已发布 palette（注入柱先入表后 decode），光/生成线程并发读撕裂（26.3=MissingPaletteEntry、26.1.2=AIOOBE 同族）。修复 = `applyToChunk` 写时复制换节；`-XX:-OmitStackTraceInFastThrow` 保下次异常带全栈 |
| legacy cherry-pick 冲突 | 低 | 基线已坍缩零版本块；冲突点集中在 compat 壳，机械可解 |

## 11. 会话协作约定

1. **开工**：读本文件（§1 决策、§3 阶段、§9 进度）+ AGENTS.md；找到进度表中第一个未完成项作为本次会话目标。
2. **Shell 纪律**：本仓库约定见 AGENTS.md「关键构建命令」——Git Bash 下 gradle 不加引号、冒烟走 `pwsh -File/-Command`、禁 `--no-daemon`（runClient/runServer 除外）、禁硬等/杀 java；构建等待 `BUILD SUCCESSFUL|FAILED`，冒烟等 `=== RESULT:`。
3. **收工**：更新 §9 进度表（含会话备注一句话）与 §8 决策记录（有新决策时）；中途停下的阶段在 §9 备注标明断点位置；重大里程碑（每阶段完成）写 `docs/handoff/handoff-YYYY-MM-DD-<主题>.md`。
4. **代码纪律**：阶段②只做机械剥离（保留 1.21.11 路径），禁止顺手重构/改行为；阶段④新增 `#if` 只进 compat 与网络适配器；红线章节（§3 末尾）全程有效。
5. 查 MC 源码用 minecraft-dev（26.2/26.3 已缓存；26.1.2 需现场反编译，注意 30s 超时后台续跑特性）；查本仓库优先 codegraph。

## 12. 参考资料

- [Fabric for Minecraft 26.1](https://fabricmc.net/2026/03/14/261.html) / [26.2](https://fabricmc.net/2026/06/15/262.html) / [26.3](https://fabricmc.net/2026/09/15/263.html)
- [NeoForge 1.21.11→26.1 移植 primer](https://docs.neoforged.net/primer/docs/26.1)（26.2/26.3 primer 在 neoforged/.github `primers/` 仓库）
- [neoforged.net 下载页](https://neoforged.net)（26.1.2 → 26.1.2.70-beta 对应关系）
- [architectury-loom issue #328](https://github.com/architectury/architectury-loom/issues/328)
- [minecraft.wiki Java Edition 26.1](https://minecraft.wiki/w/Java_Edition_26.1)
- 本仓库：`docs/version-segments.md`（分段语义）、`docs/architecture.md`（握手时序）、`docs/client-chunk-light-flow.md`（光照红线）、`docs/runtime-smoke-test.md`（冒烟门禁）
