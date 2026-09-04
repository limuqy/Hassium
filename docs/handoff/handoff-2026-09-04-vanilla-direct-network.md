# 交接文档：回归直连拓扑（vanilla-direct-network）暂停点

日期：2026-09-04 ｜ 分支：`feature/vanilla-direct-network` ｜ 状态：**改动已全部 `git add -A` 暂存，未 commit**

计划文件：`C:\Users\xiaolin\.comate\plans\vanilla-direct-network_f2f9a646.plan.md`
本文档读者：下一个接手会话的 AI / 开发者。

---

## 一、如何暂停（当前已是安全暂停态）

- **无运行中进程**：冒烟已全部结束，无后台任务；`25565` 端口已释放。
- **代码**：所有改动已暂存（`git status` 应显示约 180 文件，含 6 个 `??` 新文件 + 大量删除）。未 commit 是有意的——恢复后先复核再提交。
- **运行目录残留**：`fabric/run/{client,server}/config/hassium/*.toml` 处于"诊断态"（见下文第三节第 4 点），不影响任何静态验证，冒烟重跑前可保留。

## 二、如何恢复（按顺序执行）

```powershell
# 1) 环境确认
git status --porcelain=v1 | Measure-Object -Line   # 应约 180
git log --oneline -3                               # 顶部应为 2493441 feat: client handshake ...

# 2) 快速编译验证（~10s）
.\gradlew.bat common:compileJava --console=plain

# 3) 直接从遗留问题（第四节）继续；不要重跑已验证项
```

---

## 三、本次会话已完成（全部已验证 ✅)

### 3.1 计划 Wave 1–3 实现确认（前一会话已完成，本次逐项核实）
- 握手包 `common/.../network/handshake/`（LoginHandshake/LoginCaps/LoginHandshakeManager/ClientLoginNegotiation/PlayInitClient/ServerHandshakeActivation）+ 3 个新 mixin 已注册进 `hassium.mixins.json`。
- `play_init_s2c` 三端注册齐；`INetworkManagerService` 已删 gateway/clientHandshake、增 sendPlayInit/sendCompressionReady/announcePreHandshake。
- 1.20.2+ 复用配置阶段 `PreHandshakePayload`（计划偏离点，已在代码 javadoc 说明）；`SeedGenTail` 保留为 seed/stem 编码工具（非死代码）。

### 3.2 本次清理（Wave2/3 残留）
- 删除死代码：`config/DataPlaneEndpointConfig.java`、`HassiumConfig` 内 `ReachableEndpoint/UdpListenerConfig/DataPlaneConfig` 三 record、`network/ServerLoadReporter.java`（无消费方）。
- 删除孤儿测试 ×6：`ResumeTicketTest`、`GatewayInfoCodecTest`、`ClientEndpointStoreTest`、`DataPlaneEndpointConfigTest`、`DataPlaneConfigSpecCodecTest`；修剪 `ConfigSnapshotAdapterTest`（删引用已删字段的用例）。
- 删除 lang 的 `master.migration*` 12 键（zh_cn/en_us）+ 修正 `net.enabled` 双语措辞。

### 3.3 本次修复
1. **`MasterCoreConfig.DEFAULT.enabled` false→true**（旧网关时代残留，否则全新 toml 生成 `master.enabled=false`，直连拓扑全关）。
2. **ConfigSchema 聚合默认对齐**：`aggregationMinBatchSize` 2→4、`aggregationMaxWaitTimeMs` 50→20（对齐 `HassiumConfig.DEFAULT` 与两处测试，修 2 个测试失败）。
3. **ForgeNetworkManager.java:498**：`PacketDistributor.sendToServer(...)`（1.21.x Forge 已无此 API）→ 改用类内已有 `sendToServer` helper —— 修复 forge 1.21.1/5/6/9 四个锚点编译。
4. **Fabric 客户端补 `LIGHT_DELTA_S2C` receiver**（Forge/Neo 有、Fabric 缺失）→ `ShadowLightCompute.submitLightDelta`。
5. **`ZstdContextDecoderFrameAwareTest`**：删除 coalesced 用例（frameAware 模式随网关退役），保留半包守卫用例。

### 3.4 Wave4 冒烟门禁 + 测试
- `scripts/smoke/analyzer.py`：删 `GATEWAY_NOT_ACTIVE`/`MIGRATION_RESUME_NOT_ACCEPTED` 门，新增 **`HANDSHAKE_NOT_NEGOTIATED`（`Hassium: play init (caps=`）+ `ZSTD_NOT_ACTIVE`（`Hassium: Server ZSTD pipeline installed`）** 两门（均为 Constants.LOG 常驻输出，不依赖 debug 开关）。已用合成样例端到端验证。
- `runtime-smoke-test.ps1`：删死代码 `Reset-SmokeControlEndpoints`；Phase 0 注释更新。
- 新增 L0：`common/src/test/.../network/handshake/LoginHandshakeTest.java`（8 用例，PASS）。

### 3.5 文档（Wave4）
- `AGENTS.md`：直连拓扑速记（替换三核心速记）、配置表（删 dataplane/controlReachableEndpoints 行）、包地图、卖点、L0/L2 行、followups 归档标注。
- `docs/architecture.md`：§1/2/3/4（重写为直连拓扑+握手时序图）/§5 包地图/§8/§9/§12.2/§12.6（退役标注）/§13。
- `docs/config-audit.md` 顶部时效标注；`docs/network-core-followups.md` 归档横幅；chunk-cache/chunk-load-optimization 核查无需改。

### 3.6 验证结论
- `common:test`：**267 tests 全绿**。
- `compileAnchors`（逐锚点脚本）：fabric/neoforge **全段 OK**；forge 1.20.1 OK，1.21.1/5/6/9 修复后已单独重编 **exit 0**。
- 冒烟：**classic 1.20.1 fabric Phase I PASS**（两轮 login hello → play init → 双端 ZSTD installed → 重连复用，全门禁 PASS）。

---

## 四、遗留问题（恢复后第一优先）

### 4.1 已修复待复验：T2-91 登录停滞（slow_login）
- **现象**：seedgen 冒烟 4/4 复现——login hello 应答后登录状态机停滞 30s，`Took too long to log in`。
- **根因**（jstack + 逐 tick 反射探针实证）：vanilla 在 Netty IO 线程写非 volatile `state=READY_TO_ACCEPT`，server 线程 `tick()` 的 C2 编译代码对普通字段做 LICM 提升后永远读到陈旧 HELLO → `handleAcceptedLogin` 永不执行。纯可见性竞争（探针反射读可见新值、加日志屏障即 PASS）。
- **修复**（已实现于 `MixinServerLoginPacketListenerImpl`，本轮唯一实质改动）：`handleAnswer` 后经 `server.submit()` 在 server 线程投递一次受 `state==READY_TO_ACCEPT` 守卫 + 一次性标记的 `handleAcceptedLogin()` 兜底；与 vanilla tick 同线程串行，双重守卫不双发。
- **验证**：run9 中登录即时完成（`Player491 logged in` + `accept fallback executed`）✅。

### 4.2 🔴 当前阻塞项：seedgen 场景 ZSTD 切换竞态（run9 新暴露）
- **现象**：登录修复后 seedgen 跑通到 play 期，join 2s 后客户端双向报 `ZSTD decoder error: Badly compressed packet - size 26 below threshold 256`（`ZstdContextDecoder.decode:101`）→ 通道死亡 → LOG 门禁失败。
- **时间线**（run9 client log）：18:05:15 `play init (caps=[zstd,agg,...], seedGen=false)` → `Installed ZSTD pipeline` → `compression_ready sent` → **同秒** decoder/encoder error。
- **初步判断**：26 字节的某包（疑 dictionary_sync/index_sync 或首业务包）在 **server 切 ZSTD 与 client 已装 ZSTD 的窗口内以旧格式（vanilla zlib/raw）到达**，client 的 ZstdContextDecoder 按 raw 语义读 26 字节却发生帧漂移。classic 两轮 PASS 无此问题；seedgen 与 classic 的实质差异 = 服务端 `seedGenEnabled=true`（profile patch）+ CleanWorld。
- **下一步**：
  1. 在 client `ZstdContextDecoder.decode:101` 抛点 dump 包头（varint+前 8 字节 hex）定位是哪个包；
  2. 核对 server 侧 `ServerHandshakeActivation.handleCompressionReady` 切 ZSTD 与发 dictionary/index_sync 的顺序保证（切前 flush？）与 client 侧 `PlayInitClient` 安装时序的包格式约定（vanilla zlib 帧 vs zstd 帧的判定，`magicless=true` 是否覆盖）；
  3. 修复方向参考：切换瞬间包格式标记/首包锚定，或 client 安装延后一个 RTT；
  4. 修后复验：seedgen、classic（无回归）、1.21.11 neoforge Phase I（计划验收项，尚未跑过）。
- **注意**：双端 toml 的 `debug.networkLogging=true` 是诊断开启态，可在复现时继续用。

### 4.3 次要清理（非阻塞）
- `fabric/run/client/config/hassium/hassium-client.toml` 为旧生成物：`seedGenEnabled` 注释在、键缺失 → seedgen profile patch 对 client 端跳过（日志有提示）。下次客户端首启会自愈；如需彻底修，可在 ps1 的 patch 逻辑支持"键缺失时插入"。
- `/hassium migrate` 保留为退役提示 stub（计划要求删，实现者选择保留提示，已按现状认可——如需严格删除再处理）。

---

## 五、任务清单快照（Comate task list）

| # | 任务 | 状态 |
|---|------|------|
| 1 | 编译验证当前分支状态 | ✅ |
| 2 | 清理 Wave2/3 残留 | ✅ |
| 3 | 核查握手链路与三端 payload 注册 | ✅ |
| 4 | Wave4 冒烟门禁 + L0 测试 | ✅ |
| 5 | Wave4 文档收口 | ✅ |
| 6 | common:test + 七段编译矩阵 + 冒烟 | 🔄 in_progress（test ✅、classic/seedgen ✅、**1.21.11 neoforge ✅ §7.5**；compileAnchors 全矩阵待补跑，改动锚点已抽验 1.20.1/1.21.1/1.21.11） |

## 六、证据文件位置

- 冒烟结果：`build/smoke-test/results/result_vdn_*.json`（classic PASS：`vdn_1_20_1_fabric_I`；seedgen 各轮 FAIL 及 analysis）
- 日志：`build/smoke-test/logs/{server,client}_vdn_*.log`（seedgen5 含 tick-state 探针关键证据）
- 线程转储：`build/smoke-test/jstack_stall{1,2}.txt`
- 关键调试结论链：本文档 §4.1/§4.2 + `MixinServerLoginPacketListenerImpl` 类注释

---

## 七、续篇（2026-09-04 晚间会话）：§4.2 根因落定 + 全局包压缩退役

### 7.1 §4.2 根因链（证据闭环）

- 三会话对照：classic c2 / seedgen8（探针屏障，走 vanilla tick accept）均有 `Paused/updated Zlib outbound compression`；run9（T2-91 fallback 路径）**缺失** → vanilla `LoginCompression → thenRun(setupCompression)` 回调链未落管线（机制未逐行定位，但 1.20.1 源码确认 setupCompression 就在 `handleAcceptedLogin` 内，时序由 `run9` 日志 + `Paused` 缺失反推闭环）。
- 客户端装 ZSTD 后吃到裸帧：`packetId(26)` 被当作 `uncompressedLength` → `size 26 below threshold 256` → 通道死（run9 堆栈 + 服务端反向 `ServerboundAcceptTeleportationPacket ... 31 bytes extra` 双向印证）。
- 防御性 hotfix（F1 裸帧容错解码 / F2 入站自愈 / F3 诊断）曾在树中验证（267+ tests 绿），后被 7.2 退役波整体取代删除——根因与"切换窗口格式锚定"思路由 7.1 记录在案。

### 7.2 管线级全局包压缩退役（用户决策：彻底删除，不兼容老版本）

- **删除**：`ZstdContextDecoder/Encoder`、`SkipAwareZstdEncoder`、`ZstdPipelineSwitcher`、`ZstdNegotiationTracker`、`MixinConnectionSetupCompression`（+mixins.json 登记）、`HassiumPipelineAttributes`、`GlobalCompressionLevelBenchmarkTest`、`ZstdContextDecoderFrameAwareTest`、`Connection.setupCompression` 拦截；`LoginCaps.GLOBAL_COMPRESSION` 位删除并重排；`INetworkManagerService.sendCompressionReady` SPI + 三端 impl。
- **配置键删除**：`master.globalPacketCompression` / `globalCompressionLevel` / `globalCompressionThreshold` / `magiclessZstd`（write 侧 legacy `cfg.remove` 同步清理旧 toml 残留）。
- **激活链归拢**（`ServerHandshakeActivation`）：activate() 内 `dict/index sync → markPending(5s 超时降级) → play_init`；客户端 index_sync 后回激活 ACK（沿用 `compression_ready` 通道，语义=聚合确认）→ ENABLED。原版压缩层全程不触碰。
- **聚合强化**：`flushBatch` 改 EventLoop 单任务内阈值翻折（`setThreshold(MAX)→send→setThreshold(server.getCompressionThreshold())`，防 vanilla zlib 二次压缩聚合体内字典 ZSTD 帧）；`FLUSH_PERIOD_MS` 20→10（`aggregationMaxWaitTimeMs` 默认 20→50）；黑名单保留区块族/控制面排除与**高频实体包排除**（历史实证 bug 修复，注释在 `PacketCompressionBlacklist`）。
- **Fabric 客户端补齐缺失 receiver**：DICTIONARY_SYNC / INDEX_SYNC / AGGREGATION 三通道 1.20.1+1.21.1 双段补注册（此前 Fabric 聚合靠 5s 超时降级掩盖，从未真正工作）。
- **门禁**：analyzer.py `ZSTD_NOT_ACTIVE` → `AGGREGATION_NOT_ACTIVE`（marker `Hassium: Aggregation enabled for`）。
- **文档**：AGENTS.md / architecture.md / config-audit.md 同步（握手链、包地图、卖点、键表）。

### 7.3 冒烟战报（本会话）

| 场景 | 结果 | 备注 |
|---|---|---|
| seedgen10（退役波后） | ✅ PASS | 全门禁；seedGen=false（当时 profile 为空） |
| seedgen11 | ✅ PASS | seedGen 仍 false → 定位 profile 空文件 |
| seedgen12 | ✅ PASS | **seedGen=true 真协商**（caps 含 seed、seed=42、SEED_REF 95B/枚）；2 条 P1 警告为本地生成路径 trace 语义差异 |
| classic2 回归 | ✅ PASS | 两轮 1529+8 全量、零空洞 |
| 1.21.11 neoforge v1 | ❌ exit 1 | 服务端 60s 就绪超时（首次段内编译）+ classic profile 网关残留 `master.enabled=false` 污染 |
| 1.21.11 neoforge v2 | ❌ FAIL | 服务端启动/两轮/probe 全过；**配置期协商未达**（客户端 announced、服务端已注册但无任何 `[PRE_HANDSHAKE]` 痕迹） |

### 7.4 待办（下一会话第一优先）

1. ✅ **1.21.11 配置期协商断裂**（已修复并冒烟复验，根因/修复/证据见 §7.5；原文：v2 发现，疑似 1.21.11 从未被测的遗留缺口，非退役波回归）：
   - 现象：客户端 `pre-handshake announced (clientCaps=0xef)`、双端 payload 注册日志齐全；服务端无任何 `[PRE_HANDSHAKE]`/`play init`。
   - 注意：`PreHandshakeProtocol.handlePreHandshake` 全部分支为 DebugLogger（debug.networkLogging 门控）——先加一条 unconditional `Constants.LOG` 确认「handler 未被调用 vs 调用了但没日志」。
   - 若未调用：核对 NeoForge 1.21.9+ 配置期 payload 投递 API（`registrar.configurationToServer` 注册 + 客户端 `connection.send` 直发是否仍可用，1.21.9+ 可能要求 `PacketDistributor`/配置任务时序）。
   - 另查 `classic.profile.properties` 已删的网关残留 `master.enabled=false`（v1 曾借自愈插入造成 `[master]` 重复键）。
2. **ps1 toml 自愈已加固**：表内叶子键原位替换优先（防重复键），缺失才按 `[section]` 表尾插入裸叶子键；classic/seedgen profile 已清理；四份 run toml 已去重 + 客户端 toml 移除误插 [master] 段。
3. **compileAnchors 七段矩阵**：退役波后仅跑过 1.20.1（common/fabric/forge/neoforge 编译）与 1.21.11 neoforge；其余锚点待补跑。


## 八、续篇（2026-09-04 深夜会话）：§7.4.1 修复，1.21.11 neoforge 冒烟 PASS

### 8.1 根因（配置期协商断裂，证据闭环）

- **配置阶段 `Minecraft.getConnection()` 恒为 null**：`ClientPacketListener` 要到
  `handleConfigurationFinished` 才创建并挂到 `Minecraft.connection`；配置期客户端唯一活连接是
  `ClientConfigurationPacketListenerImpl`（`ClientCommonPacketListenerImpl.connection`）。
- `NeoForgeNetworkManagerService/ForgeNetworkManagerService.announcePreHandshake()` 均 gate 在
  `Minecraft.getInstance().getConnection() != null` 上 → 配置期恒 false → **payload 静默不发送**，
  而 common mixin（`MixinClientConfigurationPacketListenerImpl`）随后照打
  `pre-handshake announced` —— v2 "客户端 announced、服务端零 `[PRE_HANDSHAKE]`" 完整解释。
  mixin 内部经 `ReflectionCompat.getFieldByTypeOrNull` 反射取到的配置监听器 Connection 本就可用
  （v2 日志证明走到了），只是 SPI 无形参接收它。
- NeoForge 21.11 事实核对（javap 补丁版 jar）：`Connection` 无 `send(CustomPacketPayload)`；
  `PacketDistributor` 只剩 S2C 系列（无任何 C2S API）；`ServerboundCustomPayloadPacket` 有公共
  `(CustomPacketPayload)` 构造器 + 补丁加的 `CONFIG_STREAM_CODEC`（经
  `CustomPacketPayload.codec(FallbackProvider, …, ConnectionProtocol, PacketFlow)` 按协议/方向分派，
  未注册 id 落 `DiscardedPayload`）→ 配置期 C2S 的官方载体就是 vanilla 包 + 直发 Connection。

### 8.2 修复（7 文件，接口签名变更）

- `INetworkManagerService.announcePreHandshake()` → `announcePreHandshake(Connection connection)`
  （default no-op 保留：1.20.1 不用；fabric 由 `ClientConfigurationConnectionEvents.START` 直发，
  SPI no-op 不变）。
- mixin 把反射取到的 `connection` 传入 SPI（注释同步改写，删除 "PacketDistributor" 过时说法）。
- `NeoForgeNetworkManager` / `ForgeNetworkManager`：
  `connection.send(new ServerboundCustomPayloadPacket(PreHandshakePayload.create()))`
  （两侧服务端 receiver 注册本就齐备：neoforge `configurationToServer`、forge
  `NetworkDirection.CONFIGURATION_TO_SERVER`）；两个 service 改为透传。
- `PreHandshakeProtocol.handlePreHandshake` 协商成功日志 `DebugLogger` → 无条件 `Constants.LOG`
  （一次性冷路径；区分「handler 未被调用 vs 调用了没日志」，v2 排障缺口）。

### 8.3 验证

- **编译**（daemon，增量）：`-Pmc_ver=1.21.11 common+neoforge`、`-Pmc_ver=1.21.1 forge+fabric`、
  `-Pmc_ver=1.20.1 四模块` 全部 BUILD SUCCESSFUL。
- **冒烟**：`.\scripts\runtime-smoke-test.ps1 -Ver 1.21.11 -Loader neoforge -Phase I
  -SessionId vdn_1_21_11_neoforge_I_fix1` → **=== RESULT: PASS ===**（Result/Round1/Round2=PASS，
  HasFail=False，analyzer 0 failures / 0 warnings）。
- **链路证据**（`logs/{server,client}_vdn_1_21_11_neoforge_I_fix1.log`）：两轮同秒闭环——
  client `pre-handshake announced (config phase, clientCaps=0xef)` → server
  `[PRE_HANDSHAKE] Negotiated [agg,hdr,push,delta,light,pull,ovd] for <uuid>`（server log 120/136 行，
  新无条件日志）→ client `play init (caps=[agg,hdr,push,delta,light,pull,ovd], seedGen=false)`
  （155/5481 行）→ server `Aggregation enabled for Dev (activation ready)`（128/142 行）。
  v1 的 60s 就绪超时未复现（段内编译已缓存）。
- run toml 双端 `debug.networkLogging=true` 诊断态保留。

### 8.4 遗留（下一会话）

1. **compileAnchors 七段全矩阵补跑**（§7.4.3 未变；本轮改动跨版本语义仅 pre/post-1.21.1 两类，
   1.20.1/1.21.1/1.21.11 已抽验编译）。
2. forge 1.21.1+ 配置期 announce 修复后仅编译验证，未跑冒烟（本波矩阵无 forge 冒烟门禁）；
   若后续跑 forge 冒烟，关注同一条 `[PRE_HANDSHAKE] Negotiated` 链。
3. 无运行中进程；改动已 `git add` 前状态（本次 7 文件改动未暂存，复核后随原批量一起 commit）。

