# 2.x 缓存装配兼容修复：提交与主分支移植分析

日期：2026-10-19。对照基线：`2.0.0@2c4e57ff` 与 `master@db72ff40`（3.0.1）。
本轮完成 2.x 本地提交、主分支源码及 Minecraft API 对照；**26.x 业务移植尚未实施**。
本文与统筹进度更新仅为文档变更，未推送或发布。

## 1. 已提交内容与证据

| 提交 | 内容 | 本轮提交规模 |
|---|---|---|
| `e5464014` | 原生大包立即直送时不计作聚合积压；保留异步聚合前后的 FIFO、promise 与队列上限 | 4 文件，84 行新增、12 行删除 |
| `2c4e57ff` | 纯缓存装配、Play 激活门、权威 BE 快照、第三方兼容钩子、整合包冒烟工具和验证记录 | 56 文件，3421 行新增、100 行删除 |

两项提交后 2.x 工作区干净。验证沿用提交前实际运行的同一业务代码：七锚点 3262 项
测试、18 个 loader 编译组合及边界扫描通过；R3 报告清单下冷启动、重连与 Alfheim
创造模式切维通过，Treasure2 刷怪器 3/3 从磁盘恢复；Fabric 1.21.11 缓存和本地生成
均通过。详见 2.x 提交中的 `docs/mod-compat.md` §12。

R3 测试客户端 229、专用服 193 个 JAR，与用户报告成功用例逐项同哈希；双端仅排除
报告列明的 Opticores 和 Kaleidoscope 补丁，服务端另按报告去除客户端组件。
**这些是旧版验证，不能据此宣称 26.x 外部 Mod 兼容通过。**

## 2. 主分支现状与移植分类

主分支通过 CodeGraph 检查，Minecraft API 通过 minecraft-dev 的三锚点源码检查。
仅执行了 `git apply --check`，没有应用补丁或运行 cherry-pick：

- `e5464014` 的 4 个文件中，3 个可文本应用；`EgressInboundHandler` 因现代协议类型
  上下文变化需手工接入 `nativeReady()`，机制相同。
- `2c4e57ff` 的 56 个文件中，49 个可文本应用，7 个需手工处理；其中一个是主分支
  已移除的 Forge 模块。**文本可应用不代表 API、加载器或版本分界可直接使用。**
- 原许可与目录锁修复 `0c13d21a` 已由主分支 `db72ff40` 同步，本轮无需重复移植。

| 改动组 | master 现状 | 处理方式与相对工作量 |
|---|---|---|
| 原生大包与积压计量 | 仍把立即可交付的原生大包计入 8 MiB 积压限额 | 按方法移植并保留 Netty 顺序/引用计数测试；小 |
| Play 激活前的 tracking/pull | `consumeOnShadowLoop` 可提前消费 pending，`requestPullEligible` 可提前占在途位 | 将激活检查放在修改这些状态之前；按 `ShadowClientApi/Bridge` 接入，保留 pending；小 |
| 权威 BE 客户端 NBT 快照 | 网络注入后仍用完整服务端 BE 保存回调 | 搬运快照语义、4 处维护入口及 Mixin，适配 26.x packet API；中 |
| `seedGenEnabled=false` 缓存装配 | 仍贡献本地 mod 数据包、执行 WorldLoader，并解析生成器 | 新建 26.x bootstrap，接入现有装配生命周期；主要工作量与风险集中于此 |
| 自定义维度与模式固定 | 仍按服务端维度清单驱动完整生成器重建 | 改为观察实际 ClientLevel 类型；实例固定 cacheOnly，切换本地生成需重连；中 |
| 三方专用钩子 | 尚无本轮钩子，但旧版精确目标未证明存在于 26.x | 逐一核对 26.x JAR 后才启用；不整包搬旧 mixin |
| 冒烟工具 | 旧工具绑定 1.20.1/1.21.1、Java 21、remapJar 和旧加载器安装流程 | 复用清单、哈希、退出码和探针规则；重接 Java 25、jar 与 26.x launcher；中 |

## 3. 必须处理的 API 与架构差异

### 3.1 空资源构造不是保留 1.21.11 分支即可

已核对原版 `ReloadableServerResources` 构造器：

- **26.1.2 / 26.2**：`LayeredRegistryAccess, HolderLookup.Provider, FeatureFlagSet,
  CommandSelection, List<PendingTags<?>>, PermissionSet, List<PendingComponents<?>>`。
  相对 2.x 最新分支多了组件初始化列表。
- **26.3**：前两个上下文参数合并为 `ReloadableServerRegistries.LoadResult`，后接
  `FeatureFlagSet, CommandSelection, List<PendingTags<?>>, PermissionSet,
  List<PendingComponents<?>>`。该 LoadResult 是 `(layers, lookupWithUpdatedTags)` record。

因此应写仅含 J/K 与 L 两种签名的 Invoker/compat 入口，差异留在 compat 和 Mixin
签名层，不把旧七锚点分界带回 master。以当前 `docs/version-segments.md` 为准。

26.3 的配方、进度已进入 `RegistryDataLoader.RELOADABLE_REGISTRIES`，`listeners()`
只剩函数库。新 bootstrap 不能仅填 WORLDGEN 空表：还需提供 RELOADABLE lookup schema，
例如 RECIPE、ADVANCEMENT、LOOT_TABLE 等，避免 RecipeManager/命令构造期间缺注册表。
原版装配使用 `RegistryLayer.RELOADABLE`，不能把这一层的数据覆盖回已同步层。

建议的缓存装配方案：保持客户端真实 registry、Holder、标签和已应用组件状态优先；
仅补未同步的 schema，pending tags/components 采用空列表，跳过本地加载与全局重应用。
**这仍是待编译和运行验证的方案**，须增加“装配前后不改真实客户端标签/组件”的测试。
WORLD_CLOCK、TIMELINE 等已同步注册表也要保留原对象，不伪造时钟配置。

### 3.2 世界数据结构在三锚点均已变化

26.1.2 源码已确认 LevelSettings 是 record，并非只在 26.3 才变化；沿用主分支的
`DifficultySettings` 形式，不搬旧构造器里的 GameRules。

26.3 已核对：`PrimaryLevelData(settings, specialWorldProperty, lifecycle)` 不再含
WorldOptions；WorldStem 第四项是 `LevelDataAndDimensions.WorldDataAndGenSettings`。
主分支 `SeedGenLevelCompat.loadWorldStemWithRetry` 已使用
`WorldGenSettings(options, dimensions)` 包装。新 bootstrap 应复用这一结构；
保留主分支 MinecraftServer 构造、游戏规则与存档目录适配。

### 3.3 不能覆盖主分支区块解码与增量更新

当前 `ChunkPacketDataCompat.applyToChunk` 已把 section 离线解码后再发布，
`ShadowSeedServer.applySectionDelta` 也使用 `copyDetached`；整文件复制 2.x 实现会丢失
主分支防止 palette/storage 并发撕裂的修复。只在现有流程上补 BE 快照：

1. 整柱注入完成后捕获权威 BE 列表，包含空 tag；使用 `ChunkLightPacketCompat.chunkData`。
2. 分段增量替换 BE 全表时清旧快照、存新快照，保留离线 section 发布。
3. 单体更新同步替换对应快照；移除/清空 BE 时同步清理。
4. 缓存 NBT 解析、转完整柱后恢复带标记的快照。

26.3 使用 `forEachBlockEntityTag`，J/K 仍走现有 consumer 分支，应把遍历封装在 compat。
`ChunkPos.x/z` 改用 `x()/z()`。已核对 26.3 的保存钩子仍为
`getBlockEntityNbtForSaving(BlockPos, HolderLookup.Provider)`，可保留现代签名。
真实专用服和本地生成柱无权威快照时继续原版完整保存。

### 3.4 必须保留的隔离边界

- cache-only 需真实 ClientLevel 与当前连接，不能在登录注册表未稳定时先装配再标失败。
- cacheOnly 是实例属性；修改配置不能令现有空世界壳开始生成。
- 观察维度类型与当前连接绑定；真正断连时清理，同连接重建保留既有登录许可。
- 资源回调裁剪使用显式 `ShadowAssemblyScope`，不以进程级影子标志判断归属。
- currentServer 槽位的采用时序如要前移，保留 CAS、不覆盖真实集成服/专用服及失败清理。
- POI/存储仍转影子主循环；3×3 算光门、交付域和 acquire 预算语义均保持原样。
- 不新增自动降级或自动禁用 Mod；真正兼容失败保留可见错误。

## 4. 不应直接移入的旧版内容

- Forge 模块、1.20.1 注册表重建窗口及旧包名。主分支只支持 Fabric 与指定 NeoForge。
- 1.21.1 Fabric/Forgified Fabric API 的“保留 RecipeManager listener”分支；26.x 不走此版本段。
- Essential 旧 `GameRules.Value.onNotifyChange` 钩子：2.x 已对 1.21.11+ 关闭，26.x 不需照搬。
- LibX 1.20.1 / Summoning Rituals 的具体 CoreInteract 栈修复：当前旧版实证不足以说明
  26.x 也有同一 coremod；确认新版实际字节码仍有缺陷后才迁入。
- Pixelmon、MineColonies、Deep Void 的旧精确类名/事件描述符：先核对对应 26.x 模组。
  对 cache-only，前两者 reload 本就应被绕开；SeedGen 完整装配和世界加载事件仍需单独评估。
- 原 R3 / ATM9 等旧版 JAR、场次结果及全部 2.x 文档历史，不作为 26.x 测试输入或验收结果。

## 5. 建议的实施阶梯

| 阶梯 | 交付内容 | 独立验收 |
|---|---|---|
| A | 原生大包限制 + Play 激活前不消费状态 | 大包/FIFO/promise 测试；激活前 pending 与在途表不变、激活后可正常请求 |
| B | 权威 BE 快照及保存/读盘恢复 | 空 tag、嵌套 NBT、类型/方块匹配、替换/删除、FULL/DELTA/单体更新、普通服务器保存不变 |
| C | 26.x 缓存 bootstrap、模式固定、维度观察与资源 scope | J/K/L 编译；RandomState 最小常量；RELOADABLE 空 schema；标签/组件不被修改；切维与重连 |
| D | 26.x 运行工具与确有目标的三方适配 | 独立副本、JAR 固定与同哈希；创造模式 TP；原配置恢复；按实际 26.x 模组决定适配项 |

建议从最新 master 建隔离工作分支，分阶梯落地并引用来源提交，不反向合并整个 2.x。
当前 `db72ff40` 的重建修复、ShadowClientApi/Bridge、26.x 目录及节发布逻辑保留。
单凭 C 的单测绿色不代表三方模组已兼容。

## 6. 运行工具的实证阻碍与验证范围

旧 `scripts/modpack-compat.py` 固定 ASM 9.7.1。以它实际读取主分支已有
`ClientSmokeTest.class` 的结果为：class major=69，抛出
`IllegalArgumentException: Unsupported class file major version 69`。
因此诊断 Agent 不能原样带到 Java 25：优先沿用主分支 dev 冒烟入口；确需生产 JAR
探测时，使用能读取当前 class 的 ASM，并重新验证 Agent，不修改生产入口默认状态。
同时替换 Java 21、旧 loader 安装坐标、remapJar、旧 UI 重连调用及机器专用路径/代理。

实施后的验证矩阵：

- J/K/L 各跑 common:test、边界扫描；正式四组合编译：26.1.2 Fabric/NeoForge，
  26.2 Fabric，26.3 Fabric。NeoForge 26.2/26.3 不作为当前发布/运行验收门。
- 四组合冷启动/缓存重连：无本地数据包重载、localGen=0、维度类型一致、地形和退出码正常。
- 26.3 Fabric 与 26.1.2 NeoForge 优先补 SeedGen 开启对照，保证完整装配仍生成并比较。
- 在有同版三方维度 Mod 的组合上创造模式 TP、重连与多次同连接重建；旧 R3 结果不替代。
- BE 使用缺少服务端字段的夹具及实际磁盘恢复检查；可复用现有 26.1.2 工厂存档的大量
  漏斗/合成器验证普通 BE 连续性，该存档本身不等同于外部模组兼容整合包。
- 重跑真实专用服、单人/LAN 隔离检查：不因存在影子实例改写真实世界、回调或存档格式。

无需全量重跑旧 11 包。涉及交付/算光行为的修改仍受算光红线约束，冒烟不能替代
移动屋檐/洞口验收。以上为实施前的验收计划；实际实施与运行证据见 §8。

## 7. 本机分析证据

2.x 工作区 `build/main-port-analysis/` 保存两提交补丁、逐文件 `apply-check.json`，以及
`CheckAgentAsm.java` / `agent-asm-check.log`。读取源码的主分支基线为 `db72ff40`；
Minecraft 源码经 minecraft-dev 核对的重点类为 ReloadableServerResources（J/K/L）、
ReloadableServerRegistries、RegistryDataLoader、WorldStem、PrimaryLevelData、LevelSettings
和 LevelChunk。本文保留关键签名，后续实施应按所用 loader patched API 再编译验证。

## 8. 主分支实施与验收（2026-10-19）

用户批准后在 `master@a1b117cd` 逐项移植，未合并整条 2.x 分支。A/B/C 已落地：原生大包
与 Play 激活门、FULL/DELTA/单体 BE 原始快照、实际连接注册表的缓存 bootstrap。保留
主分支复制后发布区块节、ShadowClientApi/Bridge、既有重建登录许可修复、POI 主循环调度
和全部算光/交付红线。没有新增自动禁用 Mod 或自动降级。

26.x 原版差异收口：J/K 使用 WORLDGEN 与 NormalNoise.NoiseParameters/LootDataType；
L 使用 WORLD、NormalNoise、BootstrapContextAccess 新方法与独立 RELOADABLE schema。
资源构造按 J/K 的 layers+lookup 与 L 的 LoadResult 分支；标签/组件待应用列表为空，
不执行静态全局重新绑定。Fabric 动态注册表清单的 L 新 API 已按实际依赖源码适配。
缓存实例 final 模式同时约束调度和直接生成入口，维度观察按实例模式判断。

D 沿用 26.x dev 冒烟入口，重连改用类型化 ConnectScreen 调用；新增 cachedimension /
customcache 场景及严格四轮门禁，切维先切创造模式。旧 Java21/ASM9.7.1 生产 Agent
和旧版特定 Mod 钩子未搬入。增加只读世界夹具生成器，复制26.x的 level.dat 与独立
world_gen_settings.dat，使用两个自定义维ID复用原版维度类型，验证同连接连续重建。

额外共装 Underworld 26.1.2-1.3.2（SHA256
`d955651afb228ad5cbc85c9cc57b808133721dc539dd40ec8265b9050ac11dcd`）试验在模组构造阶段
被它的 ModWhitelistChecker 主动拒绝 `[ hassium ]`；尚未创建世界，不能作为影子端兼容
通过。`port_JN_underworld` 失败证据与 REA 只读清单保存在 build/cache-port 与对应日志中。
未修改第三方 JAR；测试副本已回收。自定义维度功能随后以隔离数据包验证。
首版 `port_JN_custom` 夹具因遗漏独立 world_gen_settings.dat 启动失败，修正夹具后另起场次，
原失败证据保留。
用户随后提供 Underworld 1.4.1（SHA256
`3a954e1377cc079ce3f0296cf0db847f43481606a7107503604ecc8d6def7f7e`）：
`port_JN_underworld141` 已加载、进服和完成首轮缓存统计，但玩家 gamemode/execute 命令
被拒绝，切维超时。用户明确取消后停止该项，回收测试 JAR；不列入兼容通过。
后续若单独重启这项，应先解决测试命令权限策略，不据本次结果推断影子切维失败。

### 构建与测试

- 26.1.2 / 26.2 / 26.3：各 462 项 common:test，合计 1,386，失败/错误/跳过均为 0。
- 正式四组合 build 通过；抽查 Java25 major69、factory/snapshot 类和 Mixin 登记均入包。
- 三锚点 scanVersionBoundaries 通过，48 项 Python analyzer 测试与 PowerShell runtime-gates 通过。
- 构建日志与产物 SHA256：`build/cache-port/final_build_*.log`、`artifacts.json`。

### 运行证据

| session | 组合 / 场景 | 结果 | 关键证据 |
|---|---|---|---|
| port_JF_cache | 26.1.2 Fabric 冷启动/重连 | PASS | localGen=0；重连 BE 恢复73，OVD616 |
| port_JN_cache | 26.1.2 NeoForge 冷启动/重连 | PASS | localGen=0；重连 BE 恢复66，OVD583 |
| port_K_cache | 26.2 Fabric 冷启动/重连 | PASS | localGen=0；重连 BE 恢复73，OVD616 |
| port_L_cache | 26.3 Fabric 冷启动/重连 | PASS | localGen=0；重连 BE 恢复86，OVD616 |
| port_L_cachedim | 26.3 Fabric 纯缓存四轮切维 | PASS | 四轮各1573驻留柱；类型一致、创造模式、localGen=0；三维region落盘 |
| port_L_seedgen | 26.3 Fabric 完整生成 | PASS | 本地生成1455柱，cacheOnly=false |
| port_JN_seedgen | 26.1.2 NeoForge 完整生成 | PASS | 本地生成1440柱，cacheOnly=false |
| port_JF_integrated | 26.1.2 Fabric 真实单人隔离 | PASS | 4813条region记录均为type2，无影子实例；定时方块/流体tick均正常 |
| port_L_integrated | 26.3 Fabric 真实单人隔离 | PASS | 4874条region记录均为type2，无影子实例；定时方块/流体tick均正常 |
| port_JN_custom2 | 26.1.2 NeoForge 自定义维夹具 | PASS | 两次同连接重建；四轮各473驻留柱；维度ID与类型ID不同，类型/创造模式/落盘门均通过 |

运行日志、探针与结果按 session 保存在 `build/smoke-test/{logs,probe,results}`；本地简表为
`build/cache-port/runtime-summary.json`。上述10个完成场次退出码均为0、严重日志审计失败为空。自定义夹具 ZIP 的 SHA256 为
`151d8e797fe5c61a18d0214422fbb059d4952a1563dfbfea80d3c73a776d982b`，源世界只读。

### 收尾与覆盖边界

- 旧 R3 / 11 包的通过不计入 26.x 实跑。四组合基线为 dev 环境，不能据此宣称所有第三方
  26.x 模组都兼容。移动屋檐/洞口目视验收状态沿用主分支记录，本轮未修改算光策略。
- 最初配置备份误漏 `config/hassium/` 子目录，无法证明运行前 TOML 被逐字恢复。
  原 options/server.properties/eula/ops 及 NeoForge 警告设置有原始备份；TOML 后续采用完整
  post-classic 快照回收测试状态，保留明确标注的冒烟配置，不声称是运行前原样。
- 原始9份 options/server.properties/eula/ops/NeoForge设置已按哈希恢复；其余配置采用100份
  post-classic快照回收临时测试状态，恢复清单为 `build/cache-port/config-restore.json`。两版Underworld测试副本均已移除。
- 不推送、不发布，不提交用户原有 `logo-16x10.png` 或 `video/`。

## 9. 3.0.2 发布准备（2026-10-19）

用户要求补齐 NeoForge 26.2/26.3 与移动屋檐/洞口验收，升版 2.1.2 / 3.0.2，
最终打包检查通过后分别提交，先不发布。当前配置和实际发布任务矩阵为六组合，
本轮同步 README、AGENTS 与 version-segments，历史 D7 取消决定由新 D9 更新。

首次全矩阵构建发现 NeoForge 26.3 已将 `DataPackRegistriesHooks.getDataPackRegistries()`
改为 `getWorldRegistries()`。平台实现按 `MC_26_3` 分界调用对应 API，原有两锚点
保持直接调用；未增加异常吞掉或功能降级。修复后三锚点完整 build / 边界扫描通过，
common:test 共 1386 项，失败、错误、跳过均为 0。

六份 3.0.2 JAR 已核对版本、依赖、ServiceLoader、Mixin 类、缓存装配工厂、
Java 25 class major、内嵌 ZSTD 与开发资源剥离；Modrinth 版本、加载器及实际上传
目标均匹配，未调用上传任务。证据位于 `build/release-prep/` 的
`build-results.json`、`test-summary.json`、`metadata_<mc>.log`、`artifact-manifest.json`。

NeoForge 补验六场全部 PASS：`release302_26_2_neo_classic`、
`release302_26_2_neo_flyroundtrip`、`release302_26_2_neo_dimension`，以及相应
`release302_26_3_neo_*` 三场。客户端退出码、analyzer 均为 0；日志审计、
探针门禁及看门狗转储均无失败。两场 flight 的锚点均实际卸载并回载；
dimension 均以创造模式完成主世界→下界→末地→主世界及退出后磁盘检查。
汇总：`build/release-prep/neo-smoke-summary.json`。

3.x 光照专项 `release302_light_visual` 与补采帧场 `release302_light_frames`
均自动 PASS。后者使用原版输入连续飞行两段，每段 32 秒，实际位移分别为
Z=-48→637、Z=640→-46；中间真实断线重连。Computer Use 只读采集并观察
15 张前进、18 张回程运动采样帧（约 2 秒间隔），屋檐边缘天光过渡、洞口过渡、
洞内 glowstone 及缓存回程表现正常；观察帧未见整柱黑块或区块边界亮缝。
用户另行确认「我看着屋檐正常」。未用自动 PASS 替代目视。

证据：`build/release-prep/visual302/continuous/phase{1,2}_*.png` 及
`visual302/captures.json`；原始日志和探针在 `build/smoke-test/`。
采帧场 R2 驻留 1218、本地生成 0、cacheOnly=true、BE 快照恢复 84、
`clientDarkRegressionChunks=0`，齐套门 promote 计数 1396；未改变光照策略或绕过齐套门。

本轮 123 份原运行配置已恢复并逐项核对 SHA-256。最终全矩阵重打包后重新核对
六份成品及发布配置，全部通过；文件清单见 `build/release-prep/artifact-manifest.json`。
2.x 对应轮次也完成 12 版本 / 33 组合打包与原生 R3 光照验收，用户确认移动洞口、
洞内光源及重连回程正常；其范围与开发启动失败记录见 2.x `docs/mod-compat.md`。
本轮仅本地提交，不推送、打标签或发布。
