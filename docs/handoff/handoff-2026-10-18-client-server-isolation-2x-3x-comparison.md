# 客户端隔离：2.x / 3.x 修复前对照基线

日期：2026-10-18。以下为**修复前历史基线**；两线 I01–I05 / I04b 已修复，最终验证见 [双线修复与验收](handoff-2026-10-18-client-server-isolation-fixes.md)。旧行号、缺陷探针和数量保留，不能当作修复后的状态。

## 1. 对照基线与结论

| 项目 | 2.x | 3.x |
|---|---|---|
| 工作区 | `C:\Users\xiaolin\.codex\worktrees\bugfix-2x\Hassium` | `D:\project\MC\Hassium` |
| 分支 / HEAD | `2.0.0` / `7bdab012` | `master` / `c39c7c8f` |
| 当前模组版本 | **2.1.1**（分支名不代表当前版本号） | **3.0.1** |
| MC 锚点 | 1.20.1、1.21.1、1.21.2、1.21.5、1.21.6、1.21.9、1.21.11 | 26.1.2、26.2、26.3 |
| 加载器代码 | Fabric / Forge / NeoForge，具体组合看各 `builds_for` | Fabric / NeoForge，具体组合看各 `builds_for` |
| 主配置 Mixin 数量 | 公共 31 / client 12 | 公共 31 / client 14 |

**首轮审计的 I01–I04 两边均存在，不能只修 26.x。** 关键错误判据大多同源；两线应共享修复规则，分别保留各自的 Mixin 描述符、加载器和存储版本适配。

本次额外确认 **I04b（跨玩家 epoch 污染，P1）** 和 **I05（区块接管能力与通用协议标志混用，P1）**，应一并进入双线修复。2.x 的 1.20.1 有额外受影响路径，不能只移植现代 `PlayerChunkSender` 的修复。

本对照基线阶段未修改业务源码、版本号或发布设置。2.x 开工时工作区干净；3.x 已有首轮审计文档改动及用户的图片/video 文件，本轮保留。

## 2. 原四项的逐项对照

下面 Java 路径均相对 `common/src/main/java/io/github/limuqy/mc/hassium/`。

| 问题 | 2.x 证据 | 3.x 证据 | 对照结果 |
|---|---|---|---|
| I01 / P1：全局标志误代表操作归属 | `RuntimeServerContext:23–26,75,113`；`MixinRegionFile:161–217`；`SeedGenLevelCompat:160–169` | 对应 Context / RegionFile 行相同；`SeedGenLevelCompat:131–140` | Context 完全相同；RegionFile 仅 ChunkPos 字段/访问器不同。两边均可在非影子目录选 type 126、拒读普通槽；根目录解析发生在清理 try 之前 |
| I02 / P2：光照 null 补丁覆盖真实内置服 | `mixin/shadow/MixinLayerLightSectionStorage:34–54`，在 client 数组 | 相同行与相同数组归属 | 逻辑相同，仅 Lomka 注释不同；两边均未按光引擎所属实例门控，回退 getter 实测 2048 |
| I03 / P2：未过滤列表重复广播 | `mixin/shadow/MixinChunkHolder:23–42` | 相同行 | 规范化换行后全文相同；两边全未协商列表均先手动发，再执行原版发 |
| I04 / P2：Pull 入站没有能力/连接门复核 | `protocol/ShadowPullServer:27–34`；Forge receiver 另见 `ForgeNetworkManager:507–519` | `ShadowPullServer:27–34`，Fabric / NeoForge receiver | 公共入口仅 ChunkPos 访问方式不同；capabilityEnabled 均固定 true，expectedEpoch 均取请求自身。Forge 同样到达公共入口，没有补上该能力门 |

规范化 CRLF/LF 后，下列关键文件两线全文一致：

- `RuntimeServerContext`、`MixinChunkHolder`、`MixinLevelTicks`、`MixinReloadableServerResources`。
- `ServerNetworkGate`、`ServerHandshakeActivation`、`ShadowPoiGate`、`C2meChunkIoCompat`。
- `ShadowPullRequestLedger`、`ShadowPullHandler`、`ShadowPullRequestValidator`、`LoginCaps`。

两线已有的正常入口隔离也相近：客户端初始化按物理侧注册；单人启动入口排除集成服；memory 连接排除握手与推送接管；影子目录独立；ChunkCache 匹配还有对象身份复核。这些第一层门不能弥补公共原版类上的全局标志判断。

## 3. 对照补出的两项

### I04b / P1：请求 epoch 能污染其他玩家

两线 `ShadowPullClient.request` 都把正常请求的 epoch 固定写为 **0**。而各加载器都使用静态公共 `ShadowPullHandler(new ShadowPullRequestLedger())`：

- 2.x：Fabric `FabricNetworkManager:153`、Forge `ForgeNetworkManager:153–154`、NeoForge `NeoForgeNetworkManager:58–59`。
- 3.x：Fabric / NeoForge 为同样的进程级持有方式。

`ShadowPullRequestLedger:21,34–58` 只有一个 currentEpoch。收到任一玩家较高 epoch 后，会推进这个共享值；另一玩家后续 epoch=0 的正常请求被返回 STALE。入口把 expectedEpoch 设为 request.epoch，无法阻止这次推进。

**十个 MC 锚点的纯账本探针均确认**：A/epoch0 ACCEPT → B/epoch1 ACCEPT → A/epoch0 STALE。普通客户端当前不会主动发 epoch1；本次未做游戏内载荷注入，但跨玩家状态污染机制已确认。修复须同时覆盖连接/玩家会话隔离与可信 epoch 判据。

不能只在服务端要求“非零 epoch”：这会拒绝所有现行正常客户端。兼容现协议时应明确固定 0 的有效范围，并按连接会话隔离/清理去重状态；若改为服务端签发代际，必须连同握手和客户端载荷一起设计。

### I05 / P1：未协商区块接管仍会抑制原版区块

两线 `ServerHandshakeActivation.onPlayerInit:75–94` 对任意非零协商位调用 `enableCompression`；SHADOW_PULL 在双方能力声明中恒带，即使客户端关闭 chunk.enabled，也可以完成非零协商。

但现代 `MixinPlayerChunkSender.hassium$onChunkPacketSend` 只检查这个通用压缩标志，未检查 **PULL_MODE**。仅协商 SHADOW_PULL、未协商 PULL_MODE 的玩家仍会丢掉整柱包，而客户端关闭 chunk.enabled 后不启动影子接管。

**2.x 的六个现代锚点、3.x 三锚点均已用实际激活方法与发送钩子确认**：消费 SHADOW_PULL-only 协商 → compression=true / PULL_MODE=false → 整柱发送计数 0。探针使用发送接收器替身，未进游戏做虚空画面复现。

1.20.1 的路径不同：

- `mixin/server/MixinServerPlayer:72–78` 的直接整柱抑制已有 PULL_MODE 判断，应保留这个语义。
- 但其前面的 `mixin/shadow/MixinChunkMap.hassium$skipVanillaPacketBuild:65–80` 只检查 compression，再复用 `hassium$dummyChunkPacket`。非 Pull 玩家仍可能进入只适用于“后续必取消发送”的 dummy 包优化，必须同时收紧。此项是源码结论，尚未做实际包构建/画面复现。
- `MixinChunkHolder` 的 chunk/light 过滤同样只看 compression，需要与实际区块接管能力对齐；I03 的重复发送应单独修。

## 4. 两线修复涉及的版本差异

| 接入点 | 2.x 必须保留/补验的部分 | 3.x 对应处理 |
|---|---|---|
| ChunkMap 物化与选柱 | 1.20.1 `playerLoadedChunk`、`Either` future；1.21.1 / 1.21.2+ `onChunkReadyToSend` 描述符不同 | 现代 holder + chunk 描述符；现有 unsaved 调度已按 `level.getServer() instanceof ShadowSeedServer` 判断 |
| 整柱出口 | 1.20.1 `ServerPlayer.trackChunk` + dummy 包优化；现代 `PlayerChunkSender`；保留原版滴灌限速 | `PlayerChunkSender`，包含 NeoForge bundle 识别 |
| 出生点逻辑 | 1.20.1 `MixinServerPlayer.hassium$skipFudgeSpawn:138–146` 仍看全局标志，真实内置服会被误跳过；纳入 I01 | 无该旧版专有 hook |
| 后台 executor | `<1.21.2` 返回 ExecutorService；其后 TracingExecutor；Util 的包名也有分界 | TracingExecutor；依旧需改成所属实例/装配作用域判断 |
| 注册表与退出 | 保留 1.20.1 Forge `ShadowRegistryWindow`，窗口内跳过/关窗后保存，禁止改回锁 | 该旧窗口已移除；不能用 3.x 文件覆盖旧版退出路径 |
| 数据包 reload | 无 owner 参数的 listener 过滤要显式绑定装配作用域，并覆盖 WorldLoader 异步任务 | 同样需求；普通 ThreadLocal 不能自动传播异步上下文 |
| 光引擎归属 | 已核对 1.20.1 / 1.21.11 的 LightChunkGetter.getLevel()；LayerLightSectionStorage 持有 chunkSource | 已核对 26.3，同样接口，可共享 owner 判定思路 |
| 配置期投机创建 | 当前 2.x 只因 SeedGen 等待而跳过预创建；没有 3.x 的平台能力门 | 3.x 已有 `supportsShadowPreloadDuringLogin()`，NeoForge 等 Play 再装配；双线修复时保留此差异，旧版是否补入需按旧 loader 验证 |
| 通道处理 | Fabric / Forge / NeoForge 都到同一公共 Pull 入口；Forge 两段载体均需负向测试 | Fabric / NeoForge；协议能力与连接状态应在 common 统一判定 |

2.x 的 1.20.1 还存在 `MixinServerPlayer.trackChunk` 在全局 shadow=true 时把真实包转给影子官方包桥并 cancel 的分支（`:61–70`），以及 `MixinChunkMap.playerLoadedChunk` 的影子分支。它们都是 I01 的旧版额外入口，不能遗漏。

## 5. 修复分组与验收目标

建议优先收住 I01 的存档/实例边界，再处理 I04/I04b 与 I05；I03、I02 分别保留独立、可审查的改动。两线按问题逐组同步实现，各自保留版本适配。

| 分组 | 改动范围 | 修复后必须成立的断言 |
|---|---|---|
| R1 / I01 | Context、装配 try/finally、RegionFile/C2ME 文件归属、ChunkMap/ChunkHolder/ServerPlayer/LevelTicks、reload、executor、hash/POI helper | JVM 存在影子或旧影子关停时，真实服仍读原版槽、按配置写盘、正常排 tick/广播/生成/出生点；旧影子保留自己的保存能力与代际清理 |
| R2 / I04 + I04b | 公共 Pull 入站、队列消费复核、按连接会话的 ledger 与断连清理 | 网络关闭、memory、未协商者不能入真实服工作队列；正常 epoch0 兼容；玩家 B 与重连不能污染玩家 A；丢弃请求时释放已有票据 |
| R3 / I05 | 明确区块接管判据；整柱、chunk/light 过滤、1.20.1 dummy/trackChunk 与相关 authority 门同步采用该判据 | 无 PULL_MODE 的协议客户端仍收到正确原版区块与光照；chunk.enabled=false 能完整回退；PULL_MODE 玩家仍走替代交付；原版限速继续生效 |
| R4 / I03 | 广播过滤流程 | 全原版、全协商、混合列表均不重复发送；不需要过滤时交给原版方法 |
| R5 / I02 | 光存储补丁按所属影子引擎判断，保持 Lomka 优先级与既有邻域门 | ClientLevel / 真实内置服不吃影子 null 补丁；影子仍有必要保护；2048 回退值作为单独光照语义问题验证，不直接替换成 0 |

R1 的归属准则：

1. 有 server / level / cache 参数的地方使用所属对象，不问进程是否存在影子。
2. 没有 owner 的对象（如 LevelTicks）在构造/绑定期携带标记；绑定位置需逐锚点核对。
3. 文件按拥有它的存储对象/目录与影子代际识别；覆盖装配早期、旧实例异步保存、同目录新代际与 C2ME 外部 IO，不仅依赖当前 Registry.get()。
4. 装配输入先取稳定身份；根目录解析也纳入异常清理；异步作用域显式传播。全局标志和代际可以保留作生命周期记录，不能继续作真实服行为开关。
5. POI/SectionStorage 保持转所属影子主循环的调度规则；保留 1.20.1 注册表窗口和不加锁约束。

R2 / R3 使用不同能力含义：SHADOW_PULL 是请求通道能力，PULL_MODE 是替代原版区块交付的模式。保留通用聚合/协议状态，另行判断是否接管区块；不能把两个能力或 compression 标志互相代替。

## 6. 本次验证基线

| 分支 | 锚点 | 既有 common:test | 独立审计探针 |
|---|---|---:|---:|
| 2.x | 1.20.1 | 423 | 9 |
| 2.x | 1.21.1 | 416 | 10 |
| 2.x | 1.21.2 | 416 | 10 |
| 2.x | 1.21.5 | 416 | 10 |
| 2.x | 1.21.6 | 416 | 10 |
| 2.x | 1.21.9 | 416 | 10 |
| 2.x | 1.21.11 | 416 | 10 |
| 3.x | 26.1.2 | 422（首轮审计） | 10 |
| 3.x | 26.2 | 422（首轮审计） | 10 |
| 3.x | 26.3 | 422（首轮审计） | 10 |

以上最终结果全部 0 failure / 0 error / 0 skipped。2.x 既有单测在本轮实际执行，3.x 既有 422 项沿用同日首轮、相同业务源码的结果；本轮重跑三锚点审计探针。2.x `scanVersionBoundaries` 实际执行通过。

**探针通过表示成功确认缺口，不表示修复通过。** 1.20.1 没有现代 PlayerChunkSender 探针，故 9 项；其余为 10 项。测试数量差异按实际 XML 记录，没有套用另一分支的数量。

两工作区的探针均位于 Git 忽略的 `build/client-isolation-audit/`，输出独立于正式测试目录：

- 源码：`src/audit/ClientServerIsolationAuditTest.java`；2.x 仅测试夹具的 Bootstrap/构造器有版本分支。
- 任务：`audit.init.gradle`。
- XML：`<锚点下划线化>/results/TEST-audit.ClientServerIsolationAuditTest.xml`。
- 汇总：3.x `build/client-isolation-audit/comparison-results.json`。
- pwsh 7 复跑：`.\gradlew.bat common:isolationAudit common:test -I build/client-isolation-audit/audit.init.gradle "-Pmc_ver=1.21.1"`；在相应工作区替换锚点。

最初的 1.20.1 独立广播夹具因注册表未 Bootstrap 失败；补上该版本的 tryDetectVersion / Bootstrap 初始化后通过。未改业务代码规避失败。

## 7. 实施后的回归范围

- 所有十个锚点：归属/目录/装配失败与代际、非 Pull 协议客户端、全原版/全协商/混合广播、两玩家不同请求与重连的负向回归；再跑各分支边界扫描和 `builds_for` 编译。
- 2.x 运行场至少先 A → D → I：1.20.1 Fabric/Forge、1.21.1 Fabric/Forge/NeoForge、1.21.11 Fabric/NeoForge；中间锚点按改到的 Mixin 描述符补运行验证。
- 3.x 运行场按现行 `builds_for` 与既定矩阵覆盖。两线均加入原版客户端、chunk.enabled=false、冷启动单人、旧影子关停延迟后进单人、装配失败后进单人、LAN 主机 memory 与远程混合。
- 光照保持 3×3 门、交付形状与窗口判据；Lomka 共装和移动屋檐/洞口验收单列，常规冒烟通过不替代目视。

本次没有启动游戏或加载器运行场，没有对真实存档做写入实验，I01 的完整换会话、I04b 的网络注入与 I05 的画面表现尚未做端到端复现。修复实施完成前，本表仍是缺陷基线。
