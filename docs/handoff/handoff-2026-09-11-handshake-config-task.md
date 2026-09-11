# 交接文档：1.21.1 网络握手可靠性评估 + 官方 Configuration Task 改造（方案 A）

日期：2026-09-11 ｜ 分支：`main` ｜ HEAD：`831804a`（工作区干净，**本次会话零代码改动**）
本文档读者：下一个接手会话的 AI / 开发者。

---

## 一、任务与已确认决策

### 需求原文（用户）

> 前面做 1.21.1 适配的时候，发现不同加载器握手有不少问题，评估当前网络握手是否可靠；可以找找有没有官方推荐的握手方案，减少造轮子产生的兼容性可维护性等问题。

### 已完成

1. **可靠性评估**（§二、§三）：完成，证据闭环。
2. **官方方案调研**（§四）：完成，全部经本地依赖 jar `javap` 实证 + 官方文档核对。
3. **neoforge 1.21.1 冒烟复现确认**（§三）：已跑，结论见下。

### 用户已拍板（两个 ask 的回答）

| 问题 | 用户选择 |
|---|---|
| 握手改造方向 | **方案 A：服务端主导配置任务**（NeoForge/Forge 改官方 Configuration Task；**Fabric 与 1.20.1 不动**） |
| neoforge 1.21.1 两个历史 FAIL 怎么处理 | **先复现确认** → 已执行：当前 HEAD 未复现那两个问题；冒烟 FAIL 的根因换成了**影子端关闭竞态 NPE**（非握手，见 §七） |

> 注：影子端 NPE（§七）会**阻塞 neoforge 冒烟验证**，动手改握手前先与用户确认它的处理方式（顺手修 or 另开任务）。

---

## 二、当前握手链路全貌（三载体）

协商协议本体（`LoginCaps` 按位与 + `PreHandshakeProtocol` + `LoginHandshakeManager`）是健全的，**问题全部在「客户端什么时候发」的载体层**。

| 段 | 载体 | 现状 | 文件 |
|---|---|---|---|
| **1.20.1** | vanilla login query `hassium:login_hello` | 服务端主导，已稳定（冒烟基线 PASS）。修过两个竞态：`state` 可见性（T2-91，fallback submit）、LoginCompression 时序（`483e1fb`） | `common/.../mixin/MixinServerLoginPacketListenerImpl.java`、`MixinClientHandshakePacketListenerImpl.java` |
| **Fabric 1.21.1+** | `ClientConfigurationConnectionEvents.START` → `ClientConfigurationNetworking.send` | **官方 API、零 mixin、零竞态**（fabric fix1 冒烟 PASS） | `fabric/.../HassiumClientMod.java:34-38`；服务端接收 `fabric/.../FabricNetworkManager.java:370` |
| **NeoForge 1.21.1+** | common mixin `ClientConfigurationPacketListenerImpl.tick()HEAD` 反射取 connection → 轮询 `ChannelAttributes.getPayloadSetup`（**internal API**）→ 直发 vanilla `ServerboundCustomPayloadPacket` | 竞态绕过方案，脆弱 | `common/.../mixin/MixinClientConfigurationPacketListenerImpl.java:34-53`；`neoforge/.../NeoForgeNetworkManager.java:674-695`（轮询 + 重试，:686 注释自认 internal API） |
| **Forge 1.21.1+** | 同一 common mixin → `CHANNEL.send(PreHandshakePayload, connection)`（SimpleChannel） | 修过 ClassCastException（直发 vanilla packet → `DiscardedPayload`），现可用 | `common/.../mixin/MixinClientConfigurationPacketListenerImpl.java`；`forge/.../ForgeNetworkManager.java:247-248`（CONFIGURATION_TO_SERVER 注册）、`:452-461`（发送） |

SPI 入口：`common/.../platform/services/INetworkManagerService.java:78`（`announcePreHandshake`，方案 A 后应删除）。

### 1.21.1 适配踩坑史（评估结论的支撑证据）

1. 配置期 `Minecraft.getConnection()` 恒 null → payload 静默不发（handoff-2026-09-04 §8.1）。
2. NeoForge 首个 config tick 与 `ModdedNetworkPayload` 协商竞态 → 服务端 kick "No Payload Setup" → 轮询 `ChannelAttributes` 绕过（commit `831804a`）。
3. Forge 直发 vanilla packet → `ClassCastException: DiscardedPayload` → 改 SimpleChannel（commit `831804a`）。
4. 1.20.1 两竞态（T2-91 / `483e1fb`）。

**共因：客户端主动猜时机。** 官方配置任务模型（服务端拉）从结构上消除该问题：S2C payload 能到达客户端 ⇔ payload setup 必已完成，客户端应答天然合法。

---

## 三、1.21.1 冒烟现状（2026-09-11 复现）

### 3.1 results 目录对照（关键：commit message 与证据不符）

| 会话 | 结果 | 备注 |
|---|---|---|
| `1.21.1_fabric_I_fix1` | ✅ PASS | |
| `1.21.1_forge_I` | ✅ PASS | |
| `1.21.1_neoforge_I_fix2` | ❌ `TRACE_INJECTED_NOT_READY` P0（R1 差 257） | 数据面问题，非握手 |
| `1.21.1_neoforge_I_slow` | ❌ R2 卡死 + 服务端 exit -1 | 数据面问题，非握手 |
| **`1.21.1_neoforge_I_head`（2026-09-11 复现，本次新增）** | ❌ 唯一 P0 = `PROCESS_FATAL`（影子端 NPE，见 §七） | **握手链两轮全绿；R1/R2 数据面 pass=True** |

> ⚠️ commit `831804a` message 声称 "Verified: 1.21.1 fabric/neoforge/forge smoke PASS"，但 results 目录中 **neoforge 至今无 PASS 记录**。以结果文件为准。

### 3.2 复现命令与结果（`1.21.1_neoforge_I_head`）

```powershell
.\scripts\runtime-smoke-test.ps1 -Ver 1.21.1 -Loader neoforge -Phase I -SessionId "1.21.1_neoforge_I_head"
```

- 结果：`=== RESULT: FAIL ===`；`ServerSwitched: True`、客户端退出码 0、Round1/Round2 `stats=True pass=True`。
- 唯一 P0：`PROCESS_FATAL` — 客户端 `Stopping!` 后影子主循环 NPE（§七）。
- P1 warning：`TRACE_READY_NOT_APPLIED`（R2 444 柱；与 fix2 同值，属已知 R2 OVD 语义，未阻塞门禁）。
- **握手链两轮完全闭环**（客户端 `[PRE_HANDSHAKE] announced (attempt=0)` → 服务端 `[PRE_HANDSHAKE] Negotiated [agg,delta,light,pull]` → `play init` → `Aggregation enabled`）：
  - `build/smoke-test/logs/client_1.21.1_neoforge_I_head.log:144/158/20110/20116`
  - `build/smoke-test/logs/server_1.21.1_neoforge_I_head.log:112/120/129/135`
- **fix2 / slow 的失败在当前 HEAD 未复现**（R1 trace 全干净：1529/1529/1529/1529，0 gap）。

证据文件：
- `build/smoke-test/results/result_1.21.1_neoforge_I_head.json` + `_analysis.json`
- `build/smoke-test/logs/client_1.21.1_neoforge_I_head.log`（崩溃栈在 27210-27221 行）
- `build/smoke-test/logs/server_1.21.1_neoforge_I_head.log`

---

## 四、官方方案调研结论（全部本地 jar `javap` 实证）

### 4.1 NeoForge（21.1.236 与 21.11.42 均实证同构）

| API | 说明 |
|---|---|
| `net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent` | IModBusEvent；`getListener()`、`register(ConfigurationTask)`。服务端每连接触发 |
| `net.neoforged.neoforge.network.configuration.ICustomConfigurationTask` | `run(Consumer<CustomPacketPayload>)` |
| `net.neoforged.neoforge.network.handling.IPayloadContext` | `reply(CustomPacketPayload)`、`finishCurrentTask(ConfigurationTask.Type)`、`connection()`、`handle(...)` |
| 官方模式参考 | `NetworkRegistry`/`ConfigurationInitialization.configureModdedClient`：用 **`listener.hasChannel(CommonVersionPayload.TYPE)`** 判断客户端是否注册该 channel，命中才注册任务（原版客户端零干扰） |
| 关键时序证据 | `NetworkRegistry.initializeOtherConnection(ServerConfigurationPacketListener)` 在配置期早期即 `setPayloadSetup` + 发 `MinecraftRegisterPayload`；**配置任务运行在 payload setup 完成之后**（NeoForge 自身 `CommonVersionTask` 同模式） |
| 官方文档 | <https://docs.neoforged.net/docs/1.21.1/networking/configuration-tasks>。警告：**任务不 acknowledge → 服务端永久等待、客户端永远进不了游戏**（降级兜底必须无条件 finish） |

### 4.2 Forge（52.1.15 / 1.21.1 与 53.1.10 / 1.21.3 实证；1.21.10 未抽验但同族）

| API | 说明 |
|---|---|
| `net.minecraftforge.event.network.GatherLoginConfigurationTasksEvent` | `getConnection()`、`add(Consumer<ConfigurationTask>)` |
| `net.minecraftforge.network.config.SimpleConfigurationTask` | `(Type, Consumer<ConfigurationTaskContext>)` |
| `net.minecraftforge.network.config.ConfigurationTaskContext` | `send(Packet)`、`finish(Type)`、`getConnection()` |
| `net.minecraftforge.network.Channel.isRemotePresent(Connection)` | 判断对端是否注册 channel（原版客户端零干扰用） |

### 4.3 其他结论

- **Fabric**：`ClientConfigurationConnectionEvents.START` + `ClientConfigurationNetworking.send` 即官方推荐（START 在服务端 ready、双向通道建立后触发——`ClientConfigurationNetworkAddon.receiveRegistration → onServerReady → invokeStartEvent` 字节码实证）。**不需要改**。
- **1.20.1**：无配置阶段；login query 已是服务端主导模型且已稳定。**保留不动**。
- **vanilla cookie 方案（备选，已排除）**：`ClientboundCookieRequestPacket` 仅 1.20.2+ 存在（1.20.1 索引零命中），且客户端处理仍需 mixin vanilla listener——mixin 量不减反增，不采用。

---

## 五、方案 A 设计（未实现，交接核心）

### 5.1 目标流程（1.21.1+）

```
[配置阶段]
1. 服务端：RegisterConfigurationTasksEvent / GatherLoginConfigurationTasksEvent
   → hasChannel / isRemotePresent 判断「客户端是否有 hassium 通道」
   → 有：注册握手任务；无（原版/无 mod）：不注册，零干扰
2. 任务运行：发 S2C hello payload（hassium:<hello>，configuration 方向）
3. 客户端：注册的 configuration-to-client receiver 收到
   → 用 IPayloadContext.reply(PreHandshakePayload) / Forge 侧 CHANNEL 发送应答
4. 服务端：现有 PreHandshakePayload C2S receiver 收到（configurationToServer / CONFIGURATION_TO_SERVER 注册不变）
   → PreHandshakeProtocol.handlePreHandshake（协商逻辑零改动）
   → finishCurrentTask（NeoForge）/ ctx.finish（Forge）→ 配置阶段继续
5. 原版客户端 / 旧版 Hassium 客户端：任务不应答也要能退出（见兼容矩阵）
```

### 5.2 兼容矩阵

| 客户端 ↓ / 服务端 → | 新服务端 | 旧服务端（831804a 形态） |
|---|---|---|
| 新客户端 | 服务端拉（目标路径） | 需策略：等不到 hello → 降级原版路径；或保留客户端主动 announce 作 fallback（**待定，见 5.3**） |
| 旧客户端（主动 announce） | 保留现有 C2S receiver → 协商照常（**双路径共存，向前兼容**） | 现状 |
| 原版客户端 | hasChannel=false → 不注册任务 → 零干扰 | 现状（空应答=原版路径） |

### 5.3 待定设计点（动手前必须定）

1. **hello payload type**：复用 `PreHandshakePayload.TYPE`（双向注册）还是新增独立 S2C type（如 `hassium:pre_handshake_hello`）？注意旧客户端不注册新 type 时 S2C 会被 `DiscardedPayload` 静默丢弃——需要配合 5.2 的兼容策略。
2. **stall 兜底**：客户端有 hassium 通道但应答丢失时，官方模式会永久等待（文档警告）。方案：任务注册时调度延迟无条件 finish（超时降级原版路径）；或依赖连接中断自然解除（NeoForge 官方 `CommonVersionTask` 就这么做）。
3. **新客户端连旧服务端**的降级策略（保留主动 announce fallback 与否）。
4. 删除范围确认：`MixinClientConfigurationPacketListenerImpl`（1.21.1+ 段）、`INetworkManagerService.announcePreHandshake`（:78）及三端实现、NeoForge `sendWhenPayloadReady` 轮询（:681-695）、Forge mixin 触发路径。

### 5.4 参考的官方实现

- NeoForge 自用：`NetworkRegistry`（`CommonRegisterPayload`/`CommonVersionPayload` 协商任务）、`ConfigurationInitialization.configureModdedClient`。
- 本地 jar 路径（供 javap 复验）：
  - `~/.gradle/caches/modules-2/files-2.1/net.neoforged/neoforge/21.1.236/*/neoforge-21.1.236-universal.jar`
  - `~/.gradle/caches/modules-2/files-2.1/net.neoforged/neoforge/21.11.42/*/neoforge-21.11.42-universal.jar`
  - `~/.gradle/caches/modules-2/files-2.1/net.minecraftforge/forge/1.21.1-52.1.15/*/forge-1.21.1-52.1.15-universal-srg.jar`
  - `~/.gradle/caches/modules-2/files-2.1/net.minecraftforge/forge/1.21.3-53.1.10/*/forge-1.21.3-53.1.10-universal-srg.jar`

---

## 六、待办清单（todo 快照）

- [x] 复现确认 neoforge 1.21.1 冒烟（当前 HEAD）→ FAIL，根因 = 影子端 NPE（非握手）
- [x] 调研三端配置任务 API 细节（版本段差异）
- [ ] **设计服务端主导握手任务（协议/接口/降级语义）** ← 从这里继续（§五）
- [ ] NeoForge 实现配置任务握手
- [ ] Forge 实现配置任务握手
- [ ] 删除旧 mixin + `announcePreHandshake` SPI
- [ ] 编译验证七段锚点（`scanVersionBoundaries` + 各版本 compileJava）
- [ ] 三端 1.21.1 冒烟验证
- [ ] neoforge 影子端 NPE 排查（§七，**阻塞最终验证**）
- [ ] 文档收口：`AGENTS.md` 直连拓扑速记、`docs/architecture.md` §4 握手链描述
- [ ] 清理过期 handoff 文档引用

---

## 七、影子端 GLFW 时钟 NPE（neoforge 验证阻塞，非握手问题）

### 7.1 崩溃现场

```
[16:56:22] [hassium-smoke-shutdown] shadow save completed (seq 1 -> 2)
[16:56:22] [Render thread] Minecraft: Stopping!
[16:56:22] [hassium-seedgen-main/ERROR] [Hassium/]: [SHADOW_LOOP] shadow main loop crashed; session halted
java.lang.NullPointerException
  at org.lwjgl.system.JNI.invokeD(Native Method)
  at org.lwjgl.glfw.GLFW.glfwGetTime(GLFW.java:4811)
  at com.mojang.blaze3d.platform.GLX.lambda$_initGlfw$2(GLX.java:71)
  at net.minecraft.Util.getNanos(Util.java:138)
  at net.minecraft.server.MinecraftServer.haveTime(MinecraftServer.java:803)
  at MinecraftServer.pollTaskInternal(MinecraftServer.java:858)
  at MinecraftServer.pollTask(MinecraftServer.java:849)
  at io.github.limuqy.mc.hassium.network.seedgen.ShadowSeedServer.runMainLoop(ShadowSeedServer.java:1403)
```

（`client_1.21.1_neoforge_I_head.log:27210-27221`）

### 7.2 根因分析

- `ShadowSeedServer.runMainLoop`（`common/.../network/seedgen/ShadowSeedServer.java:1395-1423`）每轮调 `this.pollTask()`；`MinecraftServer.haveTime()` 源码：`return this.runningTask() || Util.getNanos() < (...nextTickTimeNanos)`。
- 客户端 `Util.getNanos()` 走 GLX（`GLX.getTime()` → `glfwGetTime`）。客户端 `Stopping!` 后 GLFW 已销毁/函数指针失效 → NPE。
- 影子主循环按设计**必须活到 `saveAll` 结束**（`SeedGenLevelCompat.java:464-470`：先 `saveAll()` 后 `server.halt(false)`；不能提前 `stopMainLoop`，否则光照收敛判定失效）。
- 为什么只有 neoforge 崩、fabric/forge 同轮不崩：**未定论**（可能关闭时序差异；fabric/forge 的 shadow 循环或更早退出/GLFW 更晚销毁）。这是排查的第一现场问题。
- 门禁：analyzer 将 `[SHADOW_LOOP] ... crashed` 判为 `PROCESS_FATAL` P0（`scripts/smoke/analyzer.py`），直接 FAIL。

### 7.3 修复候选方向（未验证）

1. **runMainLoop 提前感知退出窗口**：复用 `ShadowServerCompat.isSharedIoPoolShutdown()`（`SeedGenLevelCompat.java:449` 已用）或新增「客户端 Stopping」标志，在退出窗口内跳过 `pollTask()`（只 `LockSupport.parkNanos` / 服务剩余队列），避免调 `Util.getNanos()`。
2. **catch 分支识别该 NPE**：现有 catch 已把 `InterruptedException` 视为正常退出；可将「客户端退出窗口中由 GLX 时钟引发的 NPE」也归入正常退出（区分对待，避免吞真实崩溃）。
3. **shadow 端替换时钟依赖**：让影子 `MinecraftServer` 的 `haveTime` 路径不依赖 GLX（如 shadow 专用 flag 使 `nextTickTimeNanos` 恒大于 nanoTime——注意 `Util.getNanos()` 仍是短路后的第二个求值项，需保证不触达）。
4. **不修代码、改门禁**：明确该崩溃发生在「两轮已 PASS、客户端已 Stopping」之后，把它降为 warning——**不推荐**（真实缺陷不应被门禁掩盖；且 fabric/forge 不崩的事实说明结构上可避免）。

> 任一修复完成后跑 neoforge 冒烟复验（命令见 §八）。**该问题会挡住方案 A 的 neoforge 验证，建议先处理或与用户确认并行策略。**

---

## 八、环境与命令备忘

```powershell
# 冒烟（pwsh 7；前台给足 block_until_ms，或用 async）
.\scripts\runtime-smoke-test.ps1 -Ver 1.21.1 -Loader neoforge -Phase I -SessionId "<id>"
# 三个 loader 依次（fabric / forge / neoforge），验证握手改造
.\scripts\runtime-smoke-test.ps1 -Ver 1.21.1 -Loader fabric  -Phase I -SessionId "1.21.1_fabric_I_<id>"

# 编译 / 边界扫描
.\gradlew.bat common:compileJava fabric:compileJava forge:compileJava neoforge:compileJava
.\gradlew.bat scanVersionBoundaries
.\gradlew.bat common:test
```

- 日志：`build/smoke-test/logs/{client,server}_<SessionId>.log`
- 结果：`build/smoke-test/results/result_<SessionId>.json` / `_analysis.json`
- 握手 grep 标记：`[PRE_HANDSHAKE] Negotiated`（服务端）、`[PRE_HANDSHAKE] announced` + `play init`（客户端）、`Aggregation enabled`（服务端）
- MC 源码查询：minecraft-dev MCP（`get_minecraft_source` / `search_indexed`，version=1.21.1, mapping=mojmap）

---

## 九、风险与注意事项

1. **不要动 Fabric 与 1.20.1 的握手路径**（用户已明确）。Fabric 是当前唯一「官方 API 无竞态」的参照实现。
2. 方案 A 改的是**触发方向**（客户端拉 → 服务端拉），协商协议与 `play_init` 激活链（`ServerHandshakeActivation`）不动。
3. 配置任务**必须保证 finish**：忘记 finish 会让服务端永久等待（官方文档明文警告），比现状更糟——降级兜底是硬要求。
4. `PreHandshakePayload` C2S receiver（三端 `configurationToServer`/`CONFIGURATION_TO_SERVER` 注册）保留：它是旧客户端向前兼容的通道。
5. commit message 曾出现「声称 PASS 但无证据」的情况（§3.1），验证一律以 `results/*.json` 为准。
6. 影子端 NPE（§七）与握手正交，但会挡住 neoforge 的验收门禁；勿在握手改造中把它「顺手吞掉日志」了事。

---

## 十、实现落地（2026-09-11 续篇）

### 10.1 落地清单

| 动作 | 文件 |
|---|---|
| 新增 S2C hello payload（`hassium:prehandshake_hello_s2c`，空载荷） | `common/.../network/PreHandshakeHelloPayload.java` |
| NeoForge：配置任务注册（`hasChannel` 过滤）+ hello 注册（`configurationToClient` → `ctx.reply`） | `neoforge/.../NeoForgeNetworkManager.java`（`onRegisterConfigurationTasks` / `PreHandshakeTask`）；`HassiumNeoForge.java`（mod bus 监听） |
| Forge：配置任务注册（`isRemotePresent` 过滤）+ hello 注册（`CONFIGURATION_TO_CLIENT` → `CHANNEL.send` 应答） | `forge/.../ForgeHandshakeEvents.java`（新）；`forge/.../ForgeNetworkManager.java`（`PRE_HANDSHAKE_TASK_TYPE` / `sendPreHandshakeHello` / `onPreHandshakeHello`） |
| 删除旧路径 | `MixinClientConfigurationPacketListenerImpl.java`（整类）+ `hassium.mixins.json` 条目；`INetworkManagerService.announcePreHandshake` + NeoForge/Forge service 覆写；NeoForge `announcePreHandshake`/`sendWhenPayloadReady`（`ChannelAttributes` internal API 轮询）；Forge `announcePreHandshake` |
| 影子端 teardown NPE 修复（§七） | `common/.../network/seedgen/ShadowSeedServer.java`：catch 分支识别 `isSharedIoPoolShutdown() && isGlfwClockFailure(t)` → INFO 正常退出；真实崩溃仍走 ERROR（`isGlfwClockFailure` 辅助方法校验 GLFW/GLX 栈帧） |
| Fabric / 1.20.1 握手路径 | **零改动**（用户约定） |

### 10.2 对 §5.3 待定点的收口

1. **hello type**：独立 S2C `hassium:prehandshake_hello_s2c`（不复用 C2S type）。`hasChannel` 过滤保证只对声明了该通道的客户端下发——旧客户端根本收不到，无 `DiscardedPayload` 兼容面。
2. **stall 兜底**：任务 **fire-and-forget**（`run` 内发 hello 后立即 `finishCurrentTask`），不等应答。依据：TCP 全序下客户端 hello handler 内同步应答先于 `FinishConfiguration` 的客户端 ACK 到达服务端，而 ServerPlayer 在 ACK 之后创建——协商登记必然先于 `ServerHandshakeActivation` 消费。**比官方 wait-ack 形态更保守**：客户端异常不应答只降级原版路径，不 stall 登录。
3. **新客户端连旧服务端**：纯降级（旧服务端无 hello 任务 → 客户端不声明 → 原版路径）。不保留客户端主动 announce fallback（那需要保留 mixin，即本次删除的对象）。
4. **旧客户端连新服务端**：保留 C2S receiver → 旧客户端主动 announce 仍被接收（向前兼容）。
5. **删除范围**：全部完成（见 10.1）。

### 10.3 验证

- 编译：1.20.1（common/fabric/forge）、1.21.1（common/fabric/forge/neoforge）、1.21.3 / 1.21.6 / 1.21.10（forge）、1.21.11（common/fabric/neoforge）；`scanVersionBoundaries` OK。
- 冒烟（1.21.1 三端）：以 `build/smoke-test/results/result_1.21.1_*_task*.json` 为准。
- 文档：`AGENTS.md` 直连拓扑速记、`docs/architecture.md` §4 时序与包地图已同步。
