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
移动屋檐/洞口验收。本轮只做分析，没有运行上述“移植后”验证。

## 7. 本机分析证据

2.x 工作区 `build/main-port-analysis/` 保存两提交补丁、逐文件 `apply-check.json`，以及
`CheckAgentAsm.java` / `agent-asm-check.log`。读取源码的主分支基线为 `db72ff40`；
Minecraft 源码经 minecraft-dev 核对的重点类为 ReloadableServerResources（J/K/L）、
ReloadableServerRegistries、RegistryDataLoader、WorldStem、PrimaryLevelData、LevelSettings
和 LevelChunk。本文保留关键签名，后续实施应按所用 loader patched API 再编译验证。
