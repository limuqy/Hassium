# 客户端 / 影子端与真实服务端隔离审计

> 本文为修复前历史基线。两线 I01–I05 / I04b 已完成修复和自动化验收，最终状态见 [双线修复报告](handoff-2026-10-18-client-server-isolation-fixes.md)。下文保留原始缺陷证据与数量。

日期：2026-10-18。基线：`c39c7c8f`，Hassium 3.0.1，Minecraft 26.1.2 / 26.2 / 26.3。

## 1. 结论与范围

**当前不能认定客户端功能已完全隔离真实服务端。** 专用服的客户端入口、单人本机连接、影子缓存目录已有隔离；但存在进程级影子标志误覆盖真实内置服、客户端光照补丁覆盖内置服、原版光照广播重复发送，以及 Pull 接收端缺少能力复核四类缺口。

本次核对主配置中的 31 个公共 Mixin、14 个客户端 Mixin，以及 3 个三方兼容 Mixin、双加载器入口与网络注册、影子装配/退出、区块装载与保存、光照、POI 调度、缓存、客户端摄入、OVD，以及真实服务端配套发送路径。业务源码未修改。通过 CodeGraph 查询当前源码；原版目标方法用 minecraft-dev 核对；三锚点均执行 `common:test` 与独立审计探针。

| 场景 | 当前隔离机制 | 审计判断 |
|---|---|---|
| 专用服进程 | 无 Fabric client 入口 / NeoForge CLIENT 事件；无 `client` Mixin；无客户端缓存定位，正常路径不创建影子实例 | 能隔离影子实例装配，但 common Mixin 仍会注入；I03 会影响原版光照发送，I04 是请求边界缺口 |
| 冷启动进入单人 | 登录、启动影子入口检查 `getSingleplayerServer()`；memory 连接不协商；真实存档写开关要求 dedicated | 通常不启影子、不接管原版区块；I02 的光照补丁仍生效 |
| 多人退出后进入单人 | 退出有有界保存等待；影子标志在异步关停末尾清除 | 等待超时或装配异常遗留标志时，I01 可误伤真实内置服；不能把“单人入口不新建影子”当成充分隔离 |
| 单人开 LAN，本机玩家 | memory 连接排除握手与推送抑制 | 本机正常保持原版区块路径；共享原版类上的 I02/I03 仍须处理 |
| 单人开 LAN，远程玩家 | `master.enabledOnLan` + `isPublished()`；每玩家协商状态 | 允许显式开启配套服务端传输优化；这是服务端功能。I04 尚未在请求入口落实相同门控 |

“不装配影子实例”和“不加载任何 shadow 包类”是不同保证。当前 common Mixin/compat 对 shadow 类型有直接引用，不能宣称专用服完全不解析或加载这些类；可以核实的是正常专用服流程没有客户端入口、缓存身份与创建调用链。包名不是侧别隔离机制。

## 2. 已有隔离如何工作

### 2.1 物理侧与注册侧

- `fabric/src/main/resources/fabric.mod.json:19` 分开 `main` 与 `client` 入口；客户端 receiver、命令、连接清理由 `HassiumClientMod.onInitializeClient` 注册。
- `neoforge/.../HassiumNeoForgeClient.java:27` 用 `@EventBusSubscriber(value = Dist.CLIENT)` 限制客户端初始化与登出处理。公共网络管理器同时持有两方向处理方法，接收方向由 `playToClient` / `playToServer` 区分，不等于整类仅客户端。
- `common/src/main/resources/hassium.mixins.json` 的 `client` 数组限制物理客户端加载；`mixins` 数组是双侧公共注入。`shadow.MixinLayerLightSectionStorage` 在前者；RegionFile / ChunkMap / ChunkHolder / ServerChunkCache 等在后者。
- `client.LightUpdatePacketAccessor` / `LevelChunkWithLightPacketAccessor` 虽放在公共数组，目标是两侧共用的协议类，不是 `net.minecraft.client.*`。前者当前为空壳；后者提供 lightData 替换方法，仅被调用时修改包。
- 两加载器各自的额外 Mixin 配置当前均为空；三方兼容配置独立，C2ME 按是否替换 IO 门控，NeoForge splitter 按加载器门控；这些门不是客户端/影子实例归属门。

### 2.2 单人、握手与客户端区块接管

- `ClientLifecycleHelper.onLogin:80` 在本机集成服会话提前返回；`startShadowIfConfigured:211` 再检查本机集成服；`ShadowLightCompute.startShadowSpeculative:1406` 也有同样检查。
- `ShadowLightCompute.isEnabled:1369` 要求 `chunk.enabled`、握手完成且影子未失败；`shouldInterceptVanillaChunks:1376` 额外要求 ready。客户端原版区块包只在这些条件成立时转交影子链。
- `ServerNetworkGate.shouldUseForConnection:46` 对 memory 连接恒 false；`PreHandshakeProtocol:51` 忽略 memory 预握手；`ServerHandshakeActivation.onPlayerInit:75` 不给本机玩家开启压缩/区块抑制。
- OVD 的 `onClientTick:60` 明确排除本机集成服；Forget 保留要求 `ShadowLightCompute.isEnabled()`。它扩的是客户端缓存/渲染半径；OVD 外带由影子本地源提供，不能据此扩大真实服务端请求范围。

需要区分**装配**与**消费**：Fabric 允许连接期投机装配，`ShadowServerRegistry.getOrCreate:130` 并不要求握手已完成。无握手的远程连接也可能先创建影子，消费才受握手门控；源码中“握手后才启动”的部分旧注释不是当前实现事实。

### 2.3 实例、目录与状态归属

- `ShadowClientBridge` 未注册时返回 no-op，缓存定位为 null；`getOrCreate` 在没有 gameDir 时返回。专用服正常没有注册真实客户端 SPI 的调用链。
- `SeedGenLevelCompat.resolveShadowWorldRoot:476` 把世界限定在 `gameDir/hassium_cache/<serverId>/world`，不取真实服务端世界目录或 `saves/<单人世界>`。
- `ShadowSeedServer.dimensionOfCache:1472` 按 **ChunkCache 对象身份**识别维度；`MixinServerChunkCache` 未匹配返回原版。这是有效的第二层隔离，即使进程标志为 true，也不会把另一台服务端的 ChunkCache 直接映射到影子注入表。
- `storageForRegionDir:1517` 按规范化绝对目录匹配影子存储管理器；`MixinRegionFile.write` 的映像接管要求匹配成功。但读写输出流选择仍有 I01 的绕过路径。
- `MixinChunkMap.setChunkUnsaved:49` 已使用 `level.getServer() instanceof ShadowSeedServer` 判断所属实例；普通服务端不会被转发到影子队列。其余几个 ChunkMap 钩子尚未统一到这一标准。
- `EntityUpdatePacing.onServerTickStart:108` 要求主服对象等于 `RuntimeServerContext.activeServer`，实体 eligibility 还核对所属 server；不会仅因共享 JVM 就让影子 tick 覆盖主服实体快照。
- `ServerLifecycleHooksCompat.adoptCurrentServer:47` 只写空槽或本实例槽；release 只清仍指向本实例的槽，避免清掉已接管的真实内置服。此处是身份比较；不代表进程标志也具备实例隔离。

### 2.4 光照、POI 与退出

影子算光主体使用影子实例自己的 ServerLevel / LightEngine，邻柱由影子 ChunkCache 取数；`LightNeighborhoodGate` 的 3×3 门作用于影子提交链，不应拆除。主要越界点是共享原版类上的补丁门控，而不是这道邻域门。

真实服光照剥离处理的是**出站包快照**：`ServerChunkPushManager.buildChunkPacket:867` 用空 mask 构造包，`stripLightInPlace:850` 替换 packet.lightData；这两处不清除真实 ServerLevel / LightEngine 的光照数据。聚合实际启用要求该 Connection 已启用、聚合配置开启且服务端网络总闸开启（`EgressOutboundHandler:56`），memory 连接不安装 TCP 聚合管线。它们有意改变传输，不代表真实服采用影子求解器。

`ShadowPoiGate.callExclusive:44` 在无影子上下文时原地执行；有影子上下文时转影子主循环。调用链主要来自影子的解析/序列化 helper，本次未发现它直接全局替换真实服务端 PoiManager/SectionStorage 的执行方式。不过它自身仍只看进程标志，若以后接入真实服通路，必须携带所属 server/level 验证。

独立 `ShadowWorldgenExecutor` 避免主动关掉原版共享后台池；但构造器选择 `ShadowServerCompat.shadowWorldgenExecutor:208` 也依赖进程标志。I01 的重叠窗口可能把新内置服构造误分到影子池，因此仍需实例/装配作用域隔离。代际检查解决旧影子退出清掉新影子状态的问题，不解决“真实服与影子服同时存在”的判断问题。

## 3. 发现的问题

### I01 / P1：进程级 shadow 标志不是所属服务器判断

**证据**：`RuntimeServerContext.java:23–26,75–87,113–114` 的 shadow 状态是全局 `static volatile boolean`。`isShadowServerContext()` 不检查线程、server、level、ChunkMap、LevelTicks 或文件目录。

**触发条件**：真实内置服启动/运行时，旧影子仍在后台关停，或者影子装配错误留下全局 true。`ClientLifecycleHelper.saveShadowOnDisconnect:394` 最多等 10 秒；`ShadowServerRegistry.awaitShutdownComplete:572` 超时返回 false 后继续后台完成，不阻止后续进入单人。另一个已复现的残留点是 `SeedGenLevelCompat.assembleShadowServer:131–140`：先 begin，再 resolveShadowWorldRoot，后面才进入 try；目录身份缺失等早期异常不会走该 catch 的清标志逻辑。`getOrCreate` 的前置检查仅要求 gameDir 非空，不要求 serverId 非空。

**可确认的后果**：

| 路径 | 当前行为与风险 | 定位 |
|---|---|---|
| 真实世界 region 写流 | shadow=true 即绕过 storage.enabled 与 dedicated 判定；目录不属影子、shadowMgr=null 时，仍回落 `writeHassiumPayload` 写 type 126 | `mixin/shadow/MixinRegionFile.java:185–217` |
| 真实世界普通压缩区块读取 | 非 type 126 时，进程 shadow=true 直接返回 null，未先确认目录归属；正常旧区块会被报为未命中 | 同文件 `161–180` |
| 方块/流体定时任务 | 任何 `LevelTicks.schedule` 都可被取消，没有 owner 校验 | `mixin/server/MixinLevelTicks.java:20–24` |
| 区块广播 | shadow 分支在包类型检查前取消，连方块/BE 等非光照广播也可受影响 | `mixin/shadow/MixinChunkHolder.java:23–30` |
| 区块装载/物化/保存 | 真实 ChunkMap 可被改成 suspended future、通知影子物化或使用影子保存锁 | `mixin/shadow/MixinChunkMap.java:58–78,96–102,192–208` |
| 数据包 reload | 真实服 listener 列表也可能被按影子策略裁剪 | `mixin/server/MixinReloadableServerResources.java:20–25` |
| 新服构造、hash 与握手旁路 | 后台池误选、真实权威 hash 失效被跳过、ServerPlayer 初始化被当作影子跳过 | `ShadowServerCompat:208`、`MixinLevelChunk:55`、`MixinServerPlayer:32` |
| C2ME IO | `takeoverEnabled` 对进程 shadow=true 直接允许接管，未检查当前输出所属存档 | `compat/mods/C2meChunkIoCompat.java:41–55` |

**验证程度**：三个锚点直接调用当前处理方法，均复现非影子目录写出 type 126、普通 type 2 槽被取消并返回 null、另一线程的定时任务被取消、非区块广播被取消、目录解析异常后 shadow 仍为 true。尚未在真实玩家存档上复现换会话损坏，也没有证据说明每次退出都会超时；不能把风险机制写成已发生的用户存档损坏。

**修复方向**：有 server/level/cache 的路径按所属实例判断；无所属信息的 LevelTicks、光照存储等应在构造时绑定 owner/标记；文件读写按目录或存储对象归属判定，不能用“JVM 有影子”选整个存档格式。装配前置操作纳入 try/finally，保留代际保护。客户端入口检查只能作第一层防线。

### I02 / P2：光照空层补丁覆盖真实内置服，且返回值可为 2048

`MixinLayerLightSectionStorage` 位于 `client` 数组，故专用服不会应用它；但物理客户端内，真实内置服与影子服用的是同一 `LayerLightSectionStorage` 类。

`MixinLayerLightSectionStorage.java:45–54` 无实例、`chunk.enabled`、握手或 shadow 判断，null 输入直接替换成 `new DataLayer(2048)`。三锚点直接调用读处理方法，shadow=false 时仍替换；`get(0,0,0)` 实测返回 **2048**，并非注释所称 0。原版 DataLayer(int) 的参数是默认光级，不是容量；未展开字节数组前 getter 原样返回该值。

非 null 层原样返回，所以本次没有证明正常齐全的内置服光照必然算错。确认的是异常/缺层分支的语义已被客户端补丁改变，而且该改变不受区块总开关约束。

修复应先把补丁限于所属影子光引擎。2048 现有注释包含历史性能约束，不应在本次审查里直接改成 0；需要独立核查回退语义，并保留 3×3 邻域门与移动屋檐/洞口验收。

### I03 / P2：未协商玩家的原版光照广播发送两遍

`MixinChunkHolder.java:32–42` 对未开压缩的玩家先调用 `player.connection.send(packet)`；只有发现至少一个压缩玩家时才取消原方法。若整个玩家列表都未协商，`filtered=false`，原版 `ChunkHolder.broadcast` 仍继续逐玩家发送。

因此单人本机或专用服全原版玩家列表中的光照广播会有两次发送；不要求启动影子，也不要求 master 已开启。混合列表会取消原方法，反而没有这一重复。

**三锚点均已验证**：调用当前 Mixin HEAD，发送计数为 1、未 cancel；继续调用当前原版 broadcast，计数变 2。测试替身只代替玩家和发送接收器，两个处理方法均来自当前实际编译产物。

修复方向：先确定是否需要过滤；无需过滤时完全交给原版，需要过滤时才手动发送并取消。此项改变的是发包次数与负载，不是服务端光照求解结果。

### I04 / P2：Pull 真实服务端入口没有复核玩家能力与网络门

`protocol/ShadowPullServer.java:27–34` 向验证器传入的 capabilityEnabled 固定为 true，expectedEpoch 则直接取 request.epoch；这里的 epoch 比较不是独立的服务端会话校验。入口未检查 `ServerNetworkGate.shouldUseForConnection` 或 `ServerHandshakeActivation.hasCaps(SHADOW_PULL)`。

Fabric receiver `FabricNetworkManager.java:297–307` 与 NeoForge 公共处理链均会到达此入口。`ServerChunkPushManager.resolveShadowPull:412–425` 只复核玩家/维度后入队；`dropOrKeepLive:336–359` 未补能力检查。

在接收通道已注册且网络 tick 泵开启的会话里，主动发送该载荷但未协商 Pull 的玩家仍可推动真实服区块需求票、hash 比较与应答。这不是影子代码在真实服执行，而是客户端请求进入真实服务端工作的边界未收紧。

仍存在的限制：玩家存活/连接、维度、逐条目距离（真实 VD + margin）、请求去重、总队列上限与 tick 预算。网络总闸关闭时 tick 泵不运行，不能据此宣称关闭 master 后仍会生成区块。普通原版客户端不主动发送此自定义载荷，本次只做源码调用链核对，未做游戏内请求注入。

修复方向：在入队之前检查所属真实 server、连接门、本机 memory、已协商能力和有效会话；队列消费时再复核。对 epoch 应明确它是客户端请求序列还是服务端会话凭据，不能把自比较当成隔离证据。

## 4. 相关 Mixin 逐项归类

以下路径均省略 `common/src/main/java/io/github/limuqy/mc/hassium/`。

| Mixin / 组 | 专用服是否应用 | 实际职责与隔离 |
|---|---|---|
| `shadow.MixinRegionFile` | 是 | 真实服存储 + 影子存储 + 旧格式兼容读取；目录接管有归属判断，流选择存在 I01 |
| `shadow.MixinChunkMap` | 是 | 影子装载/物化/保存桥；unsaved 分支按实例隔离，其余主要看全局标志 |
| `shadow.MixinServerChunkCache` | 是 | 影子注入柱取数；全局初筛后再按 cache 对象身份匹配，未匹配放行 |
| `shadow.MixinChunkHolder` | 是 | 影子广播取消 + 真实服玩家 chunk/light 过滤；I01/I03 |
| `shadow.MixinLevelChunk` | 是 | 实际是**真实服务端**权威 hash 失效钩子；不是客户端影子专属修改；不改 setBlockState 返回值 |
| `shadow.MixinChunkSerializer` | 是 | 所有世界序列化计数/debug；无 shadow 门，无取消/改 NBT，存在公共计数与日志开销 |
| `shadow.MixinThreadedLevelLightEngine` | 是 | debug.light 日志；无 shadow 实例门，但不改 future 或光照结果 |
| `shadow.MixinLightDataWrite` | 是 | 26.1.2/26.2 统计编码字节；26.3 无注入方法；不改光照数据 |
| `shadow.MixinLayerLightSectionStorage` | 否 | 客户端物理侧空层保护；仍覆盖真实内置服，见 I02 |
| RegionFile / ChunkMap / IOWorker / SectionStorage / SimpleRegionStorage / ServerChunkCache / ThreadedLevelLightEngine / LevelChunkPendingBe Accessor | 是 | 只开放字段/方法；修改行为取决于调用方；不能因名字含 shadow 就视为客户端专用 |
| `shadow.LevelLightEngineAccessor` / `LightEngineAccessor` | 否 | 客户端物理侧访问入口，本身不重写光照算法 |
| `server.MixinLevelTicks` | 是 | 影子定时任务取消；全局门不足，见 I01 |
| `server.MixinReloadableServerResources` | 是 | 影子 reload 裁剪；全局门不足，见 I01 |
| `server.MixinMinecraftServer` | 是 | 主服上下文、服务端 tick/退出、后台池选择；影子自建主循环不走正常 runServer 初始化；后台池选择有 I01 |
| `server.MixinServerPlayer` / `MixinPlayerChunkSender` | 是 | 真实服协商消费、整柱抑制与服务端发送限速；本机 memory 排除，抑制看玩家压缩状态 |
| `server.MixinServerEntity` | 是 | 有意启用的服务端实体复制降帧；按 activeServer / entity owner 隔离，不改变实体模拟 tick |
| Connection / PacketEncoder / 协议 Accessor / Login / 玩家断连钩子 | 是 | 服务端配套传输与连接生命周期；按连接方向、memory、协商/管线状态分流；客户端断连队列清理按 CLIENTBOUND 限定 |
| Minecraft / Gui / ConnectScreen / ClientPacketListener / ClientCommon / ClientHandshake / ClientLevel / ClientTick / LevelRenderer 与客户端世界渲染 Accessor | 否 | 目标均为客户端类；负责显示、客户端收包/预算/清理；单人判断与握手门见 §2；物理侧不能替代共享原版类的 owner 门 |
| modcompat RegionFileVersion / RegionFileWrite | 按 C2ME 检测 | 服务端存储与影子缓存共用；C2ME takeover 的全局 shadow 门仍有 I01 |
| modcompat NeoForgePacketSplitter | 按 NeoForge 检测 | 传输兼容，不是影子世界隔离门 |

有意的服务端功能不应混为客户端越界：存储开启后专用服写 type 126、协商玩家整柱/光照转 Pull、实体复制降帧，以及原版整柱限速，均会改变真实服的存储/传输行为。尤其 `ServerNetworkGate.shouldRateLimitChunkSend:93` 的专用服原版限速**不依赖 master.enabled/握手**，单人本机排除，LAN 远程按发布状态启用。这是现有服务端策略，不是关闭客户端 chunk.enabled 就能关闭的行为。

## 5. 验证记录与边界

| 锚点 | 既有 `common:test` | 审计探针 |
|---|---|---|
| 26.1.2 | 422 / 0 failed / 0 errors / 0 skipped | 8 / 0 failed |
| 26.2 | 422 / 0 failed / 0 errors / 0 skipped | 8 / 0 failed |
| 26.3 | 422 / 0 failed / 0 errors / 0 skipped | 8 / 0 failed |

**探针 PASS 表示成功确认当前行为，其中包含隔离缺陷；不表示隔离验收通过。** 探针直接反射调用实际 Mixin 方法，没有启动加载器进行字节码注入或进入游戏；广播探针还调用实际原版方法。没有用用户真实单人存档做写入实验。

本地临时探针保存在被 Git 忽略的 `build/client-isolation-audit/`，没有接入正式测试源集/测试输出目录：

- 源码：`src/audit/ClientServerIsolationAuditTest.java`。
- 单独 Gradle 任务定义：`audit.init.gradle`；编译输出按锚点隔离。
- 结果：`<26_1_2|26_2|26_3>/results/TEST-audit.ClientServerIsolationAuditTest.xml`，HTML 位于对应 `report/index.html`。
- 复跑（pwsh 7）：`.\gradlew.bat common:isolationAudit -I build/client-isolation-audit/audit.init.gradle "-Pmc_ver=26.3"`，替换锚点即可。

首次独立探针在 26.1.2 因测试夹具未先 Bootstrap 而初始化失败；按仓库现有测试的 SharedConstants/Bootstrap 顺序修正夹具后复跑，最终三锚点 8/8。此失败不是业务断言失败。早期 init 脚本的 buildSrc/编译插件接线错误也已修正，最终以各锚点结果 XML 为准。

本次未启动游戏冒烟、双加载器运行场或实际换会话测试；I01 的真实退出超时频率、I02 的游戏内光照影响及模组共装行为仍需后续验证。移动屋檐/洞口目视验收状态不变。

## 6. 修复顺序建议

1. I01：先保证真实服按实例/目录永远不选影子行为，并补装配失败清理；覆盖影子关停延迟与立即进入单人的重叠场景。
2. I03：消除未过滤列表的重复广播，覆盖全原版、全协商与混合玩家。
3. I02：绑定光引擎归属，再核查空层回退值，保留既定算光红线。
4. I04：统一真实服务端 Pull 入口与消费时的连接/能力/会话复核。

后续回归应包含纯专用服 + 原版客户端、冷启动单人、多人退出超时后进入单人、装配取消/失败后进入单人、LAN 主机与远程混合，以及 chunk.enabled=false；不能仅靠三锚点单测与常规多人冒烟宣称隔离完成。

## 7. 同日 2.x / 3.x 对照补充

后续按用户要求与 `2.0.0` 分支（当前 2.1.1，`7bdab012`）对照，I01–I04 均为两线共有；另确认 I04b（P1，共享 epoch 可污染其他玩家）与 I05（P1，未协商 PULL_MODE 仍抑制原版整柱）。两线修复分组、旧版特有路径及验收条件见 [双线对照与修复准备](handoff-2026-10-18-client-server-isolation-2x-3x-comparison.md)。

本报告 §5 的 8 项是首轮结果；探针现已扩为现代锚点 10 项、1.20.1 为 9 项。后续三锚点 3.x 均为 10/10，2.x 七锚点也全部确认对应缺口。新增内容仍属审查与修复准备，业务修复未实施。
