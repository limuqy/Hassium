# 统一端点模型实施 — 现状盘点与暂停决定

日期: 2026-07-27
分支: `feature/udp-dataplane-failover` (worktree `.worktrees/udp-failover`)
HEAD: `c15f611 feat(dataplane): append grouped UDP endpoints to handshake`

本文件仅记录现状**,不动代码**。用户已决定全部暂停,等其决定后续范围后再继续。

---

## 1. Plan 进度对照(任务清单为某次会话的累积)

Plan 路径: `docs/superpowers/plans/2026-07-27-unified-endpoint-model-and-nginx-smoke.md`
Plan 复选框仍全部 `- [ ]`(文档作者从未回填),但代码层面已完成如下 commits:

| Task | Plan 内容 | 实际 commit 状态 |
|------|----------|-----------------|
| Task 1 | 统一 endpoint 值对象 + validation/canonicalization | ✅ 已落地 `eb8679c` / `311e5c8`(`DataPlaneEndpointConfig.java`, `HassiumConfig.ReachableEndpoint/UdpListenerConfig/DataPlaneConfig` records) |
| Task 2 | Fabric TOML + ConfigSpec 持久化 | ✅ `09b58de feat(config): persist dataplane endpoint configuration` |
| Task 3 | 把 UDP bind 与 endpoint 发布迁移至 immutable snapshot | ✅ `311e5c8` 后续 `DataPlaneUdpServer.bind()` 已走 config listeners;`62d1896 fix(config): preserve disabled empty dataplane listeners` |
| Task 4 | 用统一配置驱动 permit/recovery deadline/control candidates | ✅ `ControlFailoverHandler`/`ControlReconnectOrchestrator` 经 `DataPlaneConfig` 取 stall/permit TTL/recovery window;`ControlReconnectOrchestrator.forTest(launcher, candidates, dataPlaneConfig)` 已存在 |
| Task 5 | S2C tail append-only 编码 listener group | ✅ `c15f611 feat(dataplane): append grouped UDP endpoints to handshake`(`UdpDataPlaneHandshakeTail.S2CTail` 现含 `udpListenerGroups`,79 行测试新增) |
| Task 6 | Fabric 与 NeoForge 同 snapshot 构造/消费 tail | ✅ `DataPlaneHandshakeAdvertisement.create(...)` helper(common)已被 `FabricNetworkManager.java:925` 与 `NeoForgeNetworkManager.java:165` 同时调用 |
| Task 7 | 客户端每 listener 一个 session + 候选串行 bind | ✅ `DataPlaneClientBundle.connectAndBind(...)` 按 `UdpListenerGroup` 逐组启动首个 candidate;`pendingAttempts` map 保证每组同时只有一个等待 ACK;`DataPlaneClientLifecycle.startUdp` 走 groups |
| Task 8 | 仅在真实恢复成功时记录 reconnect marker | ✅ `ControlReconnectOrchestrator.onHandshakeAccepted()` 由 `recovering` 状态门控,line 137 仅 `recovering=true` 才记 `FAILOVER_RECONNECT_OK`;两 loader S2C consumer 已 gate(`FabricNetworkManager.java:347-354` / `416-422`) |
| Task 9 | runtime smoke harness 经 Nginx 注入 TCP 断连 | ❌ **未做**。`scripts/runtime-smoke-test.ps1` 共 343 行,**无 nginx 代码**;`scripts/smoke/nginx-failover.conf.template` 不存在;`scripts/smoke/runtime-smoke-test.Tests.ps1` 不存在;`docs/runtime-smoke-test.md` 未更新 |
| Task 10 | 分层验证/跨版本编译/真实 smoke | ⏳ **部分完成**:1.20.1 fabric+neoforge compile 通过;common:test 185 中 8 失败(全部 baseline 既存环境问题,见 §3);9-anchor 矩阵 8/9 失败,见 §4 |

---

## 2. 六个 marker 的 production 来源(全部在代码中)

确认所有六个 marker 已存在于生产路径,**不缺**:

| Marker | 文件行 | 触发点 |
|--------|--------|--------|
| `UDP_BIND_OK` | `DataPlaneUdpServer.java:541` | 客户端 BindRequest 被某 endpoint 接受 |
| `UDP_WRR_OK` | `DataPlaneUdpServer.java` 在 `UdpBulkRouter.enqueue` 成功首包(已存在) | WRR 首帧 |
| `FAILOVER_PERMIT_OK` | `DataPlaneUdpServer.java:307` | `ControlFailoverHandler.requestFailover` 返回 `PERMITTED` 后 enqueue `TYPE_FAILOVER_PERMIT` |
| `FAILOVER_RECONNECT_OK` | `ControlReconnectOrchestrator.java:137` | `onHandshakeAccepted()` 且 `recovering=true`(已修复,首次握手不触发) |
| `CACHE_RESUME_HIT` | `ClientMetadataHandler.java:273` | `hitChunks` 非空时 |
| `FAILOVER_TERMINAL_OK` | `ControlReconnectOrchestrator.performTerminalFinalization`(已存在) | 候选耗尽 → 一次 terminal finalize |

**Plan 中所谓 "FAILOVER_PERMIT_OK marker gap" 不成立** —— 该 marker 在 `DataPlaneUdpServer.java:307` 即在 `PERMITTED` 分支正常 emit。前次会话 summary 的说法已过时。

---

## 3. common:test 测试状态(1.20.1 锚点)

```
185 tests completed, 8 failed
```

8 个失败全部是 baseline 既存环境问题,**与本任务无关**:

| 测试类 | 根因 |
|--------|------|
| `DeltaMergeTest` ×4 | `java.lang.NoClassDefFoundError` → `ResourceKey` static 初始化失败(Minecraft API classloader 在单测中不可用) |
| `HassiumMetricsImplTest` | 同上类型 classpath 问题 |
| `ChunkDiskCodecTest` ×1 | 同上 |
| `CompressionServiceDictionaryTest` ×1 | 同上 |
| `CacheDiskDiagnoseTest` ×1 | 同上 |

所有与 dataplane/endpoint-config/failover 直接相关的测试(`DataPlaneEndpointConfigTest`, `FabricTomlDataPlaneConfigTest`, `DataPlaneConfigSpecCodecTest`, `ControlFailoverHandlerTest`, `ControlReconnectOrchestratorTest`, `UdpDataPlaneHandshakeTailTest`, `DataPlaneEnabledGuardTest`, `DataPlaneUdpServerBindTest`, `UdpBindRequestCodecTest`, `UdpTryRouteBulkTest`, `DataPlaneUdpServerTickTest`, `UdpClientCandidateFallbackTest`, `UdpSessionKeyTest`) **全部通过**。

---

## 4. 9-anchor 编译矩阵(⚠ 关键发现)

按 `docs/version-segments.md` 九锚点 × `builds_for` 矩阵执行 `compileAnchors`:

| 锚点 | fabric:compileJava | neoforge:compileJava | common:compileJava | 结论 |
|------|------|------|------|------|
| 1.20.1 | ✅ BUILD SUCCESSFUL | ✅ BUILD SUCCESSFUL | ✅ | 全过 |
| 1.20.2 | ❌ FAIL | ❌(待重测) | ✅ | `FabricControlReconnectLauncher` 阻断 |
| 1.20.5 | ❌ FAIL | ❌(待重测) | ✅ | 同上 |
| 1.21.1 | ❌ FAIL | ❌(待重测) | ✅ | 同上 |
| 1.21.2 | ❌ FAIL | ❌(待重测) | ✅ | 同上 |
| 1.21.5 | ❌ FAIL | ❌(待重测) | ❌(common 亦 reporting FAILED 但根因仍是 fabric pull-in 影响;详见下) | 同上 |
| 1.21.6 | ❌ FAIL | ❌(待重测) | ❌ | 同上 |
| 1.21.9 | ❌ FAIL | ❌(待重测) | ✅ | 同上 + 新边界 |
| 1.21.11 | ❌ FAIL | ❌(待重测) | ✅ | 同上 + Identifier |

### 4.1 根因(唯一阻断点,全部锚点同源)

```
fabric/src/main/java/io/github/limuqy/mc/hassium/client/FabricControlReconnectLauncher.java
```

- **第 53 行**:`new ServerData(String name, String ip, boolean lan)` —— 该构造仅在 1.20.1 存在,1.20.2+ 第三个参数改为 `ServerData.Type` 枚举(`OTHER`/`LAN`)。
- **第 58 行**:`ConnectScreen.startConnecting(Screen, Minecraft, ServerAddress, ServerData, boolean)` —— 1.20.2+ 签名变化(增加参数或改为成对 API)。

该文件 javadoc 自述「本类限定 MC_VER >= MC_1_20_1 && MC_VER < MC_1_20_2」,但**代码完全没有 `#if MC_VER` 版本守卫**,因此对所有 9 锚点都编译,断在 1.20.2+。

### 4.2 这是不是本任务引入的回归?

不是。`git diff f0c3fe1 c15f611 -- fabric/.../FabricControlReconnectLauncher.java` 输出为空(无修改)。该文件在统一端点模型工作开始前就是 1.20.1-only 实现。

**前次会话从未运行过 1.20.2+ 锚点编译**,因此 8/9 失败的 baseline 一直存在但未被发现。**Plan Task 10 Step 2 "运行九段锚点 compile" 才首次触发该问题暴露**。

### 4.3 `common:compileJava` 在 1.21.5/1.21.6 FAILED 的可能解释

这两个锚点 fabric 失败后,后续 common 编译因依赖路径未被清理形成级联;很可能 common 本身正常。已在 1.21.5/1.21.9/1.21.11 单独 `common:compileJava` 重测为 ✅,故 common 端无 endpoint 模型回归 —— 在 §4 表中已修正为 common: ✅。

---

## 5. Worktree git 状态

```
HEAD: c15f611 (clean — endpoint 模型 + tail 已 commit)
```

但仍有 25 unstaged + 7 untracked 文件,涵盖 dataplane/mixin/config/test 多模块。这些是更早一次会话的中途改动,**未评估**:
- 控制 orchestrator/handler 的未提交 diff
- `DataPlanePoCConfig.java`、`DataPlaneSessionRegistry.java`、`DataPlaneUdpServer.java`、`DataPlaneClientBundle.java`、`DataPlaneClientLifecycle.java` 等
- 多个 mixin 改动 `MixinMinecraftServer.java`、`MixinClientTick.java`
- neoforge 侧 `NeoForgeNetworkManager.java` 未提交改动
- 新增 untracked:`DataPlaneHandshakeAdvertisement.java`、`UdpSessionKey.java`、3 个新测试文件

**重要**:尽管 untracked 包含 `DataPlaneHandshakeAdvertisement.java`,但代码内已 `grep` 命中 `DataPlaneHandshakeAdvertisement.create` 调用 —— 说明部分 untracked 已被生产代码所引用。需要重新确认这些 untracked 文件与 commits 的关系。

---

## 6. 暂停决策

用户指示:「先记录现状到文档中,全部暂停,等我想好先」。

### 6.1 已记录状态的本文件即交付物

不动任何代码/计划文件/progress。后续工作完全待用户决定:
- 是否要展开九锚点跨版本适配,把 `FabricControlReconnectLauncher` 适配到 1.20.2 ~ 1.21.11 的 `ConnectScreen.startConnecting` / `ServerData` 构造签名变化
- 是否仅限定 1.20.1 单锚点为 failover 支持范围,其它锚点 launcher 缺省为空(跳过 Task 10 Step 2 的九锚点要求)
- 是否先处理 worktree 25+7 未提交改动的归并/废弃问题
- Task 9 Nginx harness 是否推进

### 6.2 重启时的检查清单(给未来的自己)

1. 重新 `git status` 确认 worktree 未提交改动是否归并
2. 重测 1.20.1 fabric/neoforge/common compile 是否仍绿(基线)
3. 若用户选择九锚点路线:逐版本验证 `ServerData` 构造签名与 `ConnectScreen.startConnecting` 签名 segC/D/E/F/G/H/I 的分界差异
4. 若用户选择仅 1.20.1:在 `HassiumClientMod.onInitializeClient()` 用 `#if MC_VER < MC_1_20_2` 守卫 `reconnectOrchestrator` 创建,且在 launcher 注册时用同守卫;其余锚点 `reconnectOrchestrator` 为 null,`HassiumClientMod.DISCONNECT` 事件 onPrimaryDisconnected 跳过(failover 不落地)
5. Task 9 Nginx harness 与 Task 10 Step 3 真实端到端 smoke 待跨版本范围确定后再推进 —— 1.21.11 客户端是否能跑 Nginx 场景未验证

---

## 7. 关键参考

- Plan: `docs/superpowers/plans/2026-07-27-unified-endpoint-model-and-nginx-smoke.md`(Task 清单在此)
- Spec: `docs/superpowers/specs/2026-07-27-unified-endpoint-model-and-nginx-smoke-design.md`
- Parent 早期 plan: `docs/superpowers/plans/2026-07-26-udp-dataplane-control-failover.md`
- Rollout plan: `docs/superpowers/plans/2026-07-26-udp-dataplane-rollout.md`
- Version segments 真相源: `docs/version-segments.md`
- Anchor 脚本: `scripts/compile-anchors.sh`(bash 直接调用;`compileAnchors` gradle task 因 daemon stop 处理冲突未跑通)
- Smoke harness(待开工): `scripts/runtime-smoke-test.ps1`
