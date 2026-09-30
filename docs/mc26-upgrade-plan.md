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
| D4 | **1.20.1–1.21.10 整体 sunset**：不为其建维护分支，已发布产物继续在 Modrinth/CurseForge 可用；以 `archive/pre-baseline` git 引用保留完整历史后手 | D2 的连带决策（基线 = 1.21.11-only，fork 点不再携带更老版本） |
| D5 | **legacy = `legacy-1.21` 分支，从基线提交切出**：冻结功能，仅收 crash/安全修复；修复只从 master 单向 cherry-pick 落地，禁止反向合并 | 基线已坍缩为零版本块代码，cherry-pick 冲突最小化 |
| D6（待确认） | legacy 支持窗口 **6 个月**（或 26 线稳定即 EOL，先到为准）；26 线版本号从 **3.0.0** 起 | 待用户最终确认，不阻塞阶段①②③ |

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

### 7.1 M0 构建链 PoC（先行，最大不确定度，scratch 分支/临时目录做，不污染 master）

按序尝试，成功即停：
1. **architectury-loom 1.17.491 直出 26.3**（最省：无构建手术）——空 mod + 现有 common 引用面 `compileJava`；失败（大概率，#328 开放）→
2. **fabric-loom 新插件（`net.fabricmc.fabric-loom`，Loom 1.17）+ ModDevGradle（NeoForge 26.3）**——验证点：无 mappings 块、`implementation` 坐标、`jar` 任务、Manifold javac 插件在 Java 25 下工作、mixin 无 refmap 生效。

PoC 结论回填 §8 决策记录（选定的插件组合与版本号），然后 master 硬切换。

### 7.2 版本段与边界

- `versionProperties/`：删 `1.21.11.properties`，加 `26.1.2.properties` / `26.2.properties` / `26.3.properties`，`builds_for=fabric,neoforge`。
- 边界常量（点→下划线，命名规则由 `root.gradle` L13–19 从文件名生成，实施时核对）：`MC_26_1_2` / `MC_26_2` / `MC_26_3`；白名单三处同步（root.gradle、scan 脚本 ×2）。
- 段语义：J=26.1.2（段内继承 26.1/26.1.1，**用户要求的支持起点**）、K=26.2、L=26.3。推进顺序 J→K→L，锚点编译通过即继承。
- 构建链：fabric 子项目 → fabric-loom；neoforge 子项目 → ModDevGradle（或 M0 结论 1）；`loom-fabric.gradle` / `loom-neoforge.gradle` 按新插件 DSL 重写（遵守 ListProperty runConfig 约束）；Java toolchain 25。
- 新增 `#if` 只允许出现在 compat 类与两加载器网络适配器（沿用既有约束）；预期新块很少（26.1/26.2/26.3 差异集中在客户端渲染/输入域）。

### 7.3 断点修复（§2.3 六项）+ §2.4 复核清单逐项打勾

锚点编译用 minecraft-dev 反编译核对签名（26.2/26.3 已缓存；26.1.2 需现反编译，`decompile_minecraft_version` 有 30s 超时特性，会后台续跑，多次调用直至就绪）。

### 7.4 验证门

- 每锚点：`fabric:compileJava` + `neoforge:compileJava`（`-Pmc_ver=26.1.2 / 26.2 / 26.3`；Git Bash 不加引号，pwsh 必须加引号）。
- 每锚点冒烟：`pwsh -File ./scripts/runtime-smoke-test.ps1 -Ver 26.1.2 -Loader fabric -Phase I -SessionId "26.1.2_fabric_I"`（neoforge 同理）；L2 场景 `seedgen,dimension` 走 batch。
- **光照红线验收必做**：移动屋檐/洞口场景（AGENTS.md 算光红线），冒烟绿不豁免。
- `common:test` + `scanVersionBoundaries`。
- 版本号 3.0.0（D6），发布走既有 publish 脚本。

---

## 8. 决策记录（滚动追加）

| 日期 | 决策 | 备注 |
|------|------|------|
| 2026-09-30 | D1–D5 定案（见 §1） | 本文档建立 |
| 2026-09-30 | 阶段①执行补充：① SRG 生产映射预检（`Invoke-MappingFieldPrecheck` 整段）随 Forge 移除删除（neoforge mojmap 原生、fabric 类型匹配免疫，原为 Forge SRG 专属）；② batch 非 classic 锚点 `1.20.1 forge` → `1.20.1 fabric`（保留段 A 覆盖）；③ gradle.properties 工作区已有的 mod_version 2.0.2→2.0.3 外部改动随阶段①提交一并带入 | 阶段①提交 |

## 9. 进度表（滚动更新，收工必更）

| 阶段 | 任务 | 状态 | 会话备注 |
|------|------|------|----------|
| ① | forge/ + loom-forge.gradle 删除 | ✅ 完成 | 2026-09-30；含 mods.toml/ServiceLoader/mixins 元数据 |
| ① | settings/buildSrc/脚本/发布 map forge 条目 | ✅ 完成 | 含 SRG 预检整段删除；batch 锚点改 1.20.1 fabric |
| ① | versionProperties builds_for 去 forge | ✅ 完成 | 1.20.1→fabric；11 文件含死键清理 |
| ① | 双 loader 编译验证 + 提交 | ✅ 完成 | fabric+neoforge compileJava(1.21.1) BUILD SUCCESSFUL；scanVersionBoundaries OK；ps1 解析 OK |
| ② | 剥离脚本 + ~119 文件版本块剥离（保留 1.21.11 路径） | ☐ 未开始 | |
| ② | compat 坍缩 + ShadowRegistryWindow 整删 | ☐ 未开始 | |
| ② | versionProperties/白名单/mc_ver 默认收缩 | ☐ 未开始 | |
| ② | 双 loader 编译 + common:test + scanVersionBoundaries | ☐ 未开始 | |
| ② | Phase I 冒烟 ×2（fabric/neoforge）+ 提交 | ☐ 未开始 | |
| ③ | tag baseline/1.21.11 + archive/pre-baseline + 切 legacy-1.21 | ☐ 未开始 | |
| ③ | legacy MAINTENANCE.md + （可选）基线发布 | ☐ 未开始 | |
| ④ | M0 构建链 PoC + 选型回填 §8 | ☐ 未开始 | |
| ④ | 删 1.21.11 properties + 加 26.x 三段 + 插件切换 | ☐ 未开始 | |
| ④ | 六断点修复 + §2.4 复核清单 | ☐ 未开始 | |
| ④ | 三锚点编译 + 冒烟 + 光照红线验收 | ☐ 未开始 | |
| ④ | 文档修订（version-segments/AGENTS/mod-compat）+ 3.0.0 发布 | ☐ 未开始 | |

## 10. 风险与回退

| 风险 | 等级 | 缓解/回退 |
|------|------|-----------|
| architectury-loom 26.x 不可用（#328 开放） | 高 | M0 PoC 先行；回退 = fabric-loom + ModDevGradle 硬切换（默认可行路径，分叉后无条件切换负担） |
| mixin 断点超出 §2.3 六项（深钩子：sendChanges 字段重定向、移动包 Redirect 群、RegionFile） | 中 | 每锚点编译 + 反编译逐个核对；断点预算外溢则该锚点延后，不阻塞其他锚点 |
| 剥离嵌套块出错 | 中 | 单锚点编译全量兜底 + top14 人工复核 + Phase I 冒烟；出错 revert 单笔提交 |
| Java 25 字节码 × 混淆后旧工具（自管 ASM/reflection 桥） | 低-中 | `ReflectionCompat` 26.x 段直接用官方名（去混淆收益）；`forgeMajor` ASM 资产随 forge/ 删除 |
| 影子端 POI/序列化行为回归 | 中 | Phase I + L2 场景 + 光照红线验收；并发串行化红线照常 |
| 26.1.2 与 26.2/26.3 间未实证差异（26.1.2 未反编译） | 低 | 段 J 锚点编译即实证；有差异走 compat/新边界 |
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
