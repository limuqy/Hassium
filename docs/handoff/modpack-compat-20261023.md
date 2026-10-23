# 整包兼容性复测（2026-10-23）

分支 `2.0.0`，初始提交 `c1060ff1`，产品版本 `2.1.3`。本轮针对近期区块、快照、聚合和休眠改动，重新构建并测试完整整合包；历史 PASS 不计入本轮通过数。

## 正常窗口验收汇总

以下均为最终候选的正常窗口场次：11 包位于 `normal-final/`，R3 位于 `normal-final-r3-readstage/`，不使用隐藏窗口结果充当最终验收。脚本自动完成进服、断开、重连和退出，未控制桌面。

| 整包 | 原始严格结果 | 门禁 | 两轮驻留 | 两轮非空气采样 | R2 缓存命中 |
|---|---|---:|---:|---:|---:|
| Fabulously Optimized | PASS | 29/29 | 721 / 721 | 9 / 9 | 356 |
| XPlus | PASS | 29/29 | 721 / 721 | 9 / 9 | 360 |
| BMC4 | PASS | 29/29 | 721 / 721 | 9 / 9 | 353 |
| ATM9 | PASS | 29/29 | 609 / 525 | 9 / 9 | 253 |
| VerityCraft | PASS | 29/29 | 721 / 721 | 9 / 9 | 278 |
| All of Create | PASS | 29/29 | 721 / 721 | 9 / 9 | 300 |
| Pixelmon | PASS | 29/29 | 721 / 721 | 9 / 9 | 267 |
| ATM 3.0.2 | PASS | 29/29 | 721 / 721 | 9 / 9 | 286 |
| BMC5 | FAIL（原包分析日志） | 28/29 | 721 / 721 | 9 / 9 | 262 |
| Tensura | PASS | 29/29 | 721 / 721 | 9 / 9 | 321 |
| ATM10 | PASS | 29/29 | 721 / 721 | 9 / 9 | 350 |
| R3 固定清单（既有两项排除） | PASS_WITH_EXCLUSIONS | 35/35 | 1529 / 1529 | 9 / 9 | 1463 |

所有场次均恰好两次能力握手/聚合激活，双端正常退出 0；测试副本两端全部游戏类与成品逐字节一致。BMC5 的两个分析错误在正常窗口无 Hassium 对照中也完全复现，按用户范围不修第三方，严格 FAIL 原样保留，其余 28 项门禁通过。

三版本单测共 1919 项，零失败、6 项既有跳过；边界扫描、两重点版本打包及 1.21.11 Fabric/NeoForge 编译通过。Python 身份/读盘取证回归共 4 项通过；诊断代理的开关、状态保持及异常传播经真实 JVM 验证。完整 SHA256、场次路径和原始结果见[结构化汇总](../../build/modpack-retest-20261023/acceptance.json)及[构建记录](../../build/modpack-retest-20261023/verification.json)。

R3 固定清单、VD20→20 专项通过；第二轮读盘阶段成功恢复 2599 个权威方块实体，其中 Treasure2 刷怪器 3 个。保留原有两项模组排除，不宣称未排除原包全通过。

Pixelmon 正常窗口菜单入口已通过，隐藏阶段的 FancyMenu 异常本场未复现；没有证据确认隐藏窗口是直接或唯一诱因。FancyMenu 补丁和隐藏窗口插桩均已撤回，成品不含这些改动。

Sodium/Embeddium 系替代渲染器暂不声明休眠能力；普通缓存/网络保持启用。本轮是完整包双轮联机回归，未覆盖全部模组玩法、移动屋檐/洞口目视或正式性能对照。

PASS 表示列出的场景门禁通过，并不表示整包没有任何 ERROR 日志。收尾逐端归档了[错误日志清单](../../build/modpack-retest-20261023/final-log-error-inventory.json)，没有发现带 Hassium logger 或调用栈的 ERROR 块。XPlus 另有 CustomSkinLoader 14.19 的异步皮肤查询空指针（FakeSkinManager.java:83），线程名恰好为测试用户名 HassiumCompat；该堆栈没有 Hassium 类，联机门禁正常，未修改皮肤模组，也未将其记为完成独立对照的结论。

## 范围与证据

- 11 个既有 HMCL 完整包：BMC4、ATM9、VerityCraft、Fabulously Optimized 1.20.1、XPlus 1.20.1；All of Create、Pixelmon、ATM 3.0.2、BMC5、Tensura、ATM10。
- R3 按 `D:/MC/HassiumDiag211/r3_2x_fixed/runtime-inventory.json` 固定清单单列；只维持历史两项排除 `opticores-1.0.8+1.20.1.jar`、`kaleidoscope_compat-2.6.5-forge+mc1.20.1-Patch.jar`。
- 完整客户端 ↔ 同包专用服，两轮真实 TCP 登录、权威缓存装配、地形驻留、缓存重连、能力/聚合激活和双端正常退出。SeedGen 与服务端存档压缩关闭。服务端排除项沿用已审计客户端组件及既有准备例外，逐项留存。
- 原 HMCL 实例与个人存档只读，副本及日志位于 `build/modpack-retest-20261023/`；每个输出根固定成品和源码差异，不复用旧成品充当新回归。
- 本轮整包场景不包含移动屋檐/洞口目视、全部模组玩法或替代渲染器的休眠支持。

## 已发现问题与修复

### 替代地形渲染器导致休眠 Mixin 启动崩溃

首场 BMC4（`r1/bmc4/dedicated-runs/20261023_131124`）在客户端初始化阶段失败，服务端正常退出。`MixinSleepLevelRenderer` 的 `@Redirect` 尝试注入由 Xenon 的 Sodium `WorldRendererMixin` 覆盖的方法，同优先级下被 Mixin 拒绝。开关默认关闭不能阻止类变换，因此普通启动也受影响。

修复：启动期只检查类资源，发现旧/新 Sodium 或 Embeddium 时跳过原版地形休眠钩子；客户端同时不声明 `SLEEP`，避免开放没有 3×3 地形渲染适配的休眠入口。区块、光照和网络能力不受这个门控影响。原版渲染器继续使用原有强制注入检查。

XERJ 参考：[Bobby `241f973106ee4e5552a5950d0a12433c07921c93` 的 `MixinConfigPlugin.java:36`](https://github.com/Johni0702/bobby/blob/241f973106ee4e5552a5950d0a12433c07921c93/src/main/java/de/johni0702/minecraft/bobby/MixinConfigPlugin.java#L36)，借鉴按渲染器门控 Mixin 的设计；实际 LICENSE.md / LICENSE.LESSER.md 为 GPL-3.0 / LGPL-3.0，独立实现，没有复制代码。

修复前两版 common 单测分别 632 / 625 项，均零失败；修复新增 5 项资源检测回归，两重点版本重新打包、common:test 和 scanVersionBoundaries 通过。构建日志为 `build-1201-fix1.log` / `build-1211-fix1.log`。

### 正常断连后的迟到写入误报为 v3 协议故障

`r2/bmc4/dedicated-runs/20261023_132054` 首轮驻留 721 柱、非空气采样 9/9，随后场景主动断开；其他 Mod 仍提交已排队的自定义包。原生写入返回 `StacklessClosedChannelException`，Hassium 将该关闭错误误记为 `Hassium egress v3 connection failed`，触发整包门禁。

修复仅排除“channel 已失活 + ClosedChannelException”这一组合。新增 3 项真实 EmbeddedChannel 回归，确认迟到写入不增加 codec 错误，而活动 channel 的关闭异常及已关闭 channel 的畸形帧仍增加错误计数并执行失败流程；未放宽整包日志门禁。

`r2/bmc4/dedicated-runs/20261023_131639` 曾在首次 QuickPlay 连接时长时间无握手进展，已保留中止状态和双端转储；未发现 Java 死锁，下一场未再次出现，根因未确认。最终矩阵使用正常菜单资源就绪后的连接入口（`--wait-resources`），关闭详细包跟踪，不把该变化写成 QuickPlay 根因修复。

### 新增测试夹具的跨版本编译问题

1.21.11 的 TaskScheduler 将 wrapRunnable/scheduleExecutables 公开，并将 tell 改为 schedule。近期 5 组测试中的旧式 Owner 因此无法编译。测试侧加入 QueuedTestEventLoop，通过稳定 execute 入口显式排队，仅在入队调用期间强制调度；任务内部仍使用原版 ReentrantBlockableEventLoop 的重入策略。没有修改生产调度器或放松顺序断言。修复后的 1.21.11 common:test、Fabric/NeoForge 编译及边界扫描通过，两个重点版本也重新通过单测和打包。

### 离线联测身份不一致导致 FTB Teams 玩家缺失

审计首个 BMC4 PASS 时发现，客户端启动参数使用任意 UUID `80d5a03a-f2c2-3bb3-b0d6-a53e7920bc41`，而离线专用服按用户名计算的真实 UUID 是 `7043208f-6fb8-3607-8935-eb556f107d3a`；FTB Teams 因此报本地玩家不在已知玩家表。2026-10-19 的旧通过日志中也存在该问题。

测试客户端现在按 Minecraft UUIDUtil.createOfflinePlayerUUID 的 UTF-8 / MD5 UUIDv3 规则生成同一身份，新增以真实服务端日志 UUID 为向量的 Python 回归，并增加 noPlayerIdentityMismatch 门禁。每场另记录基础 harness 哈希及实际用户名/UUID。该问题属于测试夹具，未改正式用户身份或第三方 Mod。首个 BMC4 PASS 与中止的 Create 场保留，修正身份后重新执行矩阵。

## 诊断记录

### ATM9 重连虚空：影子端重建期间的自身关闭等待

`final/bmc4/20261023_133823` 与 `final/create/20261023_134343` 已通过两轮测试，缓存命中分别 349 / 296，均无身份错误、双端退出 0。这两场是下述生命周期修复之前的成品，不作为最终修复后全矩阵证据。

`final/atm9/dedicated-runs/20261023_134747` 在第二轮失败。用户观察到虚空；自动门禁随后确认 ClientChunkCache 驻留为 0（applied=13），保留 FAIL_RUNTIME，未计 PASS。第二轮延迟处理权威维度信息后触发缓存影子端重建；旧主循环在完成保存前仍进入全局创建入口，等待它自身关闭，使保存所需的 POI 调度无法及时执行，连续出现 15 秒调度超时。保存耗时 59808ms，最终 timedOut=true。

修复增加主循环 owner 作用域：旧 owner 被摘除后，全局 getter 返回空，不进入创建/关闭等待；主循环继续排空旧实例自己的保存与原生队列。tracking 显式传 owner，旧实例不能消费或改绑替代会话。新回归验证自身关闭未完成时旧 owner 不等待、不绕过关闭门，以及作用域嵌套/异常清理和替代会话状态保持。框架在首次 POI 调度超时时抓转储并停止，保留区块实际驻留门禁。

`fix3b/atm9/dedicated-runs/20261023_140853` 证实旧 owner 保存恢复为 0–91ms、没有 POI 超时，但第二轮仍为 0 驻留，继续保留失败。进一步对照服务端日志发现第二轮发生两个同 UUID 登录并踢出前一连接：场景的 15 秒自愈只判断 Play connection 为空，错误打断仍在 Forge 登录握手中的 ConnectScreen。修正为登录/地形加载界面仍在进展时不重试，并新增 noDuplicateLogin 门禁；保留原有空闲/失败界面的一次有界重试。

用户要求不控制桌面，测试曾使用只影响窗口显示的诊断插桩隐藏客户端。随后用户允许正常显示游戏窗口、由用户自行放到后台；隐藏窗口插桩与开关已撤回。后续场景继续自动执行，不手动控制桌面或发送键鼠。历史隐藏场次保留原始参数与检查，不作为目视光照验收或性能对照。

`background/atm9/dedicated-runs/20261023_143002` 定向复测通过：两轮驻留 609 / 525 柱、中心非空气均 9/9，重连缓存命中 242，两次握手/聚合激活，无重复登录，双端退出 0，hiddenClientWindow 和全部门禁为 true。该结果支持本场重连虚空已消除。

不含第三方菜单补丁、包含下述 Lithostitched 隔离修复的构建验证：1.20.1 / 1.21.1 / 1.21.11 分别 645 / 638 / 636 项测试，零失败、各 2 项既有跳过；两个重点版本成品、最新段 Fabric/NeoForge 编译、边界扫描通过。Python 身份回归通过。重建证据与成品 SHA256 见 `build/modpack-retest-20261023/verification.json`。

### Pixelmon 主菜单 FancyMenu 字符串缓存竞态

旧候选在 ATM9、BMC4、All of Create、VerityCraft、Fabulously Optimized、XPlus 六包通过后，Pixelmon 两场 `background/pixelmon/dedicated-runs/20261023_150034` / `20261023_150500` 均在登录前被异常门禁拦截。堆栈为 FancyMenu 3.9.9 的 `TextEditorWindowBody.compileSingleLineString` → `HashMap.computeIfAbsent`，由多个 `FancyMenu-TickerElement-AsyncTicker` 线程触发。

首次从已运行副本移除 Hassium 的 75 秒主菜单对照未复现；随后从原 HMCL 包重新建立干净副本，第二场无 Hassium 对照复现相同堆栈：`pixelmon-menu-clean/pixelmon/no-hassium-menu-2/result.json`。对照只启动隐藏菜单，不进入世界；由诊断工具停止，不计作退出/联机通过。证据支持该竞态可在无 Hassium 时发生，原失败记录未删除、门禁未放宽。

REA 对 FancyMenu 成品身份的归档检查为 `ev_b45117d1ffd6f42be1920e86f1bb72c879392b7fd972d6c94c053eaa504f3209`（SHA256 `23236908b3ef362e4a3bd48cf2610247ac9d974ba44003c509d70063471dec77`）；该静态清单不证明运行行为。minecraft-dev 反编译后确认普通静态 HashMap（`TextEditorWindowBody.java:59`）、无同步的 compute/clear（2310–2316）和异步 ticker（`TickerElement.java:144–158`）。只借鉴问题结构，没有复制第三方实现。

曾编写独立 optional Mixin 尝试修复该缓存，但用户随后明确「跟我们 mod 无关的兼容问题不需要动」。已完整撤回 FancyMenu 兼容类、Mixin、登记和测试，正式候选不包含该补丁；`verified` 下带补丁的诊断场已中止，不计入验收。原包和用户配置没有修改。此问题只作为第三方既有问题归档，不作为 Hassium 故障修复。

撤回并重建后，当时 Fabric 1.20.1 / Forge 1.20.1 / NeoForge 1.21.1 的 SHA256 与 `background` 中已通过六包的成品逐字节相同。已检查其全部门禁、恰好两次握手/聚合和双端退出 0；但这六包使用过隐藏窗口，用户随后指出这可能改变焦点、帧率限制与启动时序。最终已使用含 Lithostitched 修复的新候选、正常可见窗口重跑，不将隐藏场次直接等同于正常 GUI 行为。

Pixelmon 可见窗口直接连接场 `pixelmon-direct/pixelmon/dedicated-runs/20261023_151926` 已通过：两轮 721 柱、均 9/9 非空气、缓存命中 250，两次握手/聚合、双端退出 0。该场证明直连联机路径，不覆盖正常菜单入口。

正常窗口队列、R3、先前隐藏的六包与 Pixelmon 正常菜单入口均已完成复测，结果见顶部。此前无 Hassium 的 FancyMenu 菜单对照用了隐藏窗口，只支持“在该隐藏条件下异常不依赖 Hassium”；正常窗口 Pixelmon 本场没有复现。ATM9 最初虚空失败发生在引入隐藏插桩前，最终正常窗口也通过。

### BMC5 原包 Mixin 分析警告（正常窗口）

`background/bmc5/dedicated-runs/20261023_152737` 两轮 721 柱、均 9/9 非空气、缓存命中 274、两次握手/聚合且双端退出 0；严格原始状态仍为 FAIL，唯一失败门禁为 noMixinLocalAnalysisFailure。客户端启动打印 `Error at instruction 160` / `4356: Expected an object reference, but found .`。

从原包另建无 Hassium、正常可见窗口对照，`bmc5-no-hassium/bmc5/normal-menu-baseline/result.json` 在启动阶段复现逐字相同的两条分析错误。因此这两条日志不依赖 Hassium，也不依赖隐藏窗口；按用户范围只归档，不修补第三方。主场的 Hassium 联机/缓存/退出门禁均通过，但不将原始严格 FAIL 改写为全门禁 PASS。

### Tensura：缓存影子端误触发 Lithostitched 世界生成修饰器

`background/tensura/dedicated-runs/20261023_153329` 为正常窗口场次，首次创建影子端失败。Regions Unexplored 通过 Lithostitched 1.8.0 的 `AddWorldgenModifiersEvent` 读取 `minecraft:spring_water`，缓存影子端的未同步 placed_feature 表刻意为空，因此抛出 Missing key。真实专用服正常启动；这是 Hassium 缓存装配与第三方世界生成入口的兼容问题，属于本轮修复范围。

修复将 cacheOnly 装配作用域覆盖到 initServer/createLevels（此前只覆盖 WorldStem 资源创建）。独立 optional/client Mixin 按 ModifierManager 类资源存在性门控，在 cacheOnly 作用域内返回空修饰器表；普通世界、真实服务端和 SeedGen 装配均不拦截。旧 Lithostitched 1.4/1.5 没有该类，检查后跳过；当前三份 1.8/beta6 实现具有同一入口。没有补齐本地世界生成数据包或旁路光照门。

XERJ 参考官方仓库 [Lithostitched `cd5482eeb09d` 的 ModifierManager.java:50](https://github.com/Apollounknowndev/lithostitched/blob/cd5482eeb09d8bc7a1db33a3fa1df057db40160b/src/common/main/java/dev/worldgen/lithostitched/impl/worldgen/modifier/ModifierManager.java#L50)，核对修饰器事件入口；仓库未找到许可文件，仅作设计参考，不复制实现。新增缓存限定、SeedGen/真实世界保留、嵌套与异常作用域清理回归。

新候选的 11 包正常窗口整包回归使用 `normal-final/`，均已执行完成。此前成品的通过不自动记为本候选通过。

### 正常 GUI 复核与 R3 读盘取证

新候选在正常窗口下已通过 Tensura（缓存命中 321）、ATM10（350）、ATM9（253）和 Pixelmon 正常菜单入口（267）。ATM9 场 `20261023_155115` 两轮驻留 609/525、非空气均 9/9，无重复登录/POI 调度超时，双端退出 0。Pixelmon 场 `20261023_160010` 两轮 721/721、非空气均 9/9，FancyMenu 并发异常本场未复现，双端退出 0。证据尚不能证明隐藏窗口是该菜单异常的直接或唯一原因；最终矩阵只使用正常窗口场次。

R3 首场 `normal-final-r3/r3/dedicated-runs/20261023_163442` 在 Forge 构造阶段等待 HammerLib `ModSourceAdapter` 外部 HTTPS 读取，未到服务端就绪。自有进程的转储显示该 worker 停在 SSLSocketInputRecord/HttpRequest.body，其他 loader 线程等待其类初始化；未观察到 Hassium 调用路径。该场按 STOPPED 保存，未记通过。只对重试测试进程设置现有本机 HTTP/HTTPS 代理 `127.0.0.1:7890`，不改 HammerLib、第三方配置或原整包；代理仅作用于该 shell 的子进程，结束后恢复 JAVA_TOOL_OPTIONS。

随后 `normal-final-r3/r3/dedicated-runs/20261023_164123` 两轮均驻留 1529 柱、非空气均 9/9，命中 1470 次，双端退出 0；唯一失败是 `treasureSpawnerRestoredFromDisk`。磁盘离线解码确认三只刷怪器均保存在 type 126 NBT 中，带 `hassium:client_nbt` 标记。生产快照的 `restoredCount` 只计算采样时仍由读盘产生的当前条目；后续 `ShadowBlockEntityCompat.applySnapshot` 会清除旧条目并写入最新权威投影，因此采样为 0 不能证明未恢复。原场严格 FAIL 保留，没有直接改判。

测试代理新增显式 `--trace-cache-restores`：只在 `ShadowBlockEntitySnapshots.restore` 正常返回后记录 BE 类型和位置，不记录 NBT、不改变输入/返回/异常/控制流。R3 门禁采用第二轮 `assembly=connection` 之后、`PROBE_WRITTEN ROUND2` 之前的成功返回；缺失、重复或乱序的 owner/采样边界直接失败。三项 Python 回归排除首轮、owner 切换前和采样后事件；独立 JVM 验证代理关闭时无日志、开启时只记成功恢复，状态与异常传播均保持。没有给生产计数强行保留过期来源标记，也没有更改成品。

最终场 `normal-final-r3-readstage/r3/dedicated-runs/20261023_165318` 为冷缓存 R1→断开落盘→R2 重连，35/35 门禁通过，双端退出 0。R1 读盘恢复 0 个；R2 成功恢复 2599 个，包含 `treasure2:proximity_mobset_spawner` 三个，坐标 `(140,46,31)`、`(144,39,27)`、`(146,39,27)` 与磁盘记录一致。两轮均驻留 1529 柱、中心非空气 9/9，重连缓存命中 1463 次。原清单两项排除原样保留，结果为 PASS_WITH_EXCLUSIONS；原始采样值与读盘阶段证据分开保存，见[本场结果](../../build/modpack-retest-20261023/normal-final-r3-readstage/r3/dedicated-runs/20261023_165318/result.json)。
