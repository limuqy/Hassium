# handoff — 影子端对齐原版专用服（M1 供给闭环 / M2 光照）

> 状态：**设计已与用户对齐（2026-09-18）**；本文为实现阶段唯一优先级与验收真相源。  
> 前置：[`handoff-shadow-as-dedicated-server-s0.md`](handoff-shadow-as-dedicated-server-s0.md)（模型与 Provider 异步契约）。  
> 关联：飞行脚下虚空 / 窗口停摆排查会话（2026-09-18），代码已改过若干补丁，见 §3。

## 0. 背景与问题

| 阶段 | 现象 | 结论（日志/代码） |
|------|------|-------------------|
| R1 飞行探索 | 脚下虚空、不落地 | pull_mode 压制原版整柱；影子算光后 **Halo skip** 丢交付；`(29,31)` 无 `CHUNK_APPLY` |
| R2 长飞 | 窗口「钉死」不前推 | 自建 **window pump**（64 格/200ms）角优先 + compare 在途；非原版选柱 |
| R3 近停摆 | `served=0 acquired≈36~64`、inflight 超时风暴 | Provider inflight 未 `completeAcquire`；8s 超时连环 re-pull 与真服 `maxChunksPerTick=5` 抢容量 |
| 审计 | 业务无关脱节点清单 | 见 §2；用户已拍板 A/B 条决策（§4） |

**目标类比**：`影子端 + 客户端 ≈ 单人/专用服 + 客户端`。

- 影子端选柱/加载/算光/出官方包 ≈ 专用服。  
- 客户端 **只接收区块**（原版 `ClientChunkCache`），unload 不反驱影子选柱。  
- **唯一业务差异 = 区块来源**（真服 Pull / 缓存 / SeedGen），不是选柱几何与交付时序。

## 1. 已拍板供给模型（A1，实现主轴）

**三条链，唯一「权威柱交付」出口**（等价原版 `trackChunk`：官方 `chunk+light` 进真实客户端）：

```text
A1-① 有缓存
  选柱 → 磁盘/注入基线 → compare
       → UNCHANGED / DELTA → 本地 materialize → 权威柱交付
       → FULL             → 注入真服数据     → 权威柱交付

A1-② seedGen 关 + 无缓存
  选柱 → authoritative FULL → 注入 → 权威柱交付

A1-③ seedGen 开 + 无缓存（有缓存统一走硬盘基线 → A1-①，不再本地 worldgen）
  选柱 → seedGen（真 seed）作基线 → compare
       → UNCHANGED / DELTA / FULL → 权威柱交付
```

**约束**

1. compare **必须挡首投**：走 compare 的路径（A1-① / A1-③）**必须在服务端权威裁决（UNCHANGED / DELTA / FULL）落地之后**才允许权威柱交付；禁止「先交付、后 compare 保鲜」。A1-③ 实现口径 = `SeedGenCompareGate`（物化 mark → 响应 clear → 才 deliver）。A1-② 的 authoritative FULL 本身已是权威应答，不存在本地基线盲投。  
2. 「权威柱交付」= **含可用光的官方柱包**（lightStrip 开：真服剥光 → 影子 LightEngine 算光后打包）。  
3. 客户端 `hasClientApplyEpoch` **不作**选柱/是否交付的控制输入（A3）。

> **2026-09-18 口径修订（用户拍板）**：原文 A1-③ 误写为「seedGen 开 + **有**缓存」；有缓存时磁盘/注入基线优先，统一走 A1-①，seedGen 只服务**无缓存**柱。原文约束 1「compare 不得挡首投」与 A1-③ 要求冲突，已更正为 **compare 必须挡首投**。

## 2. 审计清单（业务无关脱节点摘要）

完整讨论见会话；此处仅实现索引。

### A 区块流

| ID | 脱节点 | 本 handoff 动作 |
|----|--------|-----------------|
| A1 | `scheduleChunkLoad` 悬置 + 多驱动 | 实现 §1 三条链；汇到 Provider/materialize |
| A2 | 客户端 intercept 过宽 | **保留**握手后真服不推整柱；**收窄**客户端 cancel（P2） |
| A3 | `hasClientApplyEpoch` 控制面 | 清理（M1） |
| A4 | 物化「重入必 compare」挡首投 | **保留并强化**：compare 必须挡首投（A1-①/A1-③）；禁「有数据即交付」 |
| A5 | `drainRedeliver` / `redeliverQueue` 死路径 | 删除（依赖 A1 覆盖） |
| A6 | bootGrid/sweep 过时注释 | 清理 |
| A7 | OVD 双窗 | 暂保留；默认 VD 相等时环带空；后续可关 |
| A8 | Provider 在途上限/冷却 | **保留**（pull 业务必要） |
| A9 | load 抑制 vs Forget 不对称 | **不对称保留**：load=Hassium，unload=原版 Forget+reclaim；文档写死 |

### B 光照

| ID | 脱节点 | 本 handoff 动作 |
|----|--------|-----------------|
| B1 | lightStrip + 影子算光 | **保留 lightStrip（核心，默认开）**；原版化=交付时序，不关剥光 |
| B2 | 打包前空光/地表 park | 柱级 light 完成即打包（M2） |
| B3 | 光桥等全局收敛 | 柱级就绪即发光包（M2） |
| B4 | placeholder 空气壳 | 删除 |
| B5 | `awaitEngineTaskDrain` 主线程风险 | 清理 |
| B6 | 交付门口径不一 | 统一 `isDeliverableToClient` = ServerVD+余量（已部分落地） |
| B7 | 客户端 shadow 光收集 | **保留** collectLightUpdate 配合 strip；首包光在官方柱包内 |

## 3. 本会话已落地代码（实现起点，非终态）

| 改动 | 状态 | 说明 |
|------|------|------|
| 接法 B 桥 `setApplyInProgress` | 已编译 | `ShadowOfficialPacketBridge` 官方包直通原版 apply |
| Halo 窗外仍 offerReady | 已编译 | `pushReady` / `publishNativeLightResult` 不再静默 return |
| `isDeliverableToClient` = ServerVD+**4** | 已编译 | `DELIVER_VIEW_MARGIN_CHUNKS=4` |
| `deliverLocal` 视距外 hard skip | 已编译 | 与 B6 统一 |
| **删除** `WINDOW_PUMP_*` 与 `drainTrackingWindowCompleteness` | 已编译 | 选柱不再全窗补扫 |
| Provider inflight 超时 + 并发上限 + 超时冷却 | 已编译 | 24 在途；15s 超时；20s 冷却；非终态业务逻辑 |
| UNCHANGED/ERROR `releaseProviderInflight` | 已编译 | 防 inflight 死锁 |
| unload 不再入 redeliver 队列 | 已编译 | A5 半清理；方法仍残留待删 |
| **权威驱动枚举漏环（R2 封闭虚空根因）** | 已编译 + 冒烟验证 | `drainAuthorityAcquires` 方形循环半径用了 `range`，而原版形状沿轴线可伸到 `range+1`（`ChunkMap.isChunkInRange` 的 `|d|-1` 折算；原版 `updatePlayerStatus` 循环即 `±(vd+1)`）→ `shape(VD)\cheb(VD)` 那 4 段（VD=10 时 44 柱）既不在权威驱动枚举里，又被 `inOvdBand` 判成「权威柱」→ 两侧都不投递。改 `ChunkShapeCompat.boundingRadius(range)`。实证：`1.20.1_fabric_I_ovdpath2` R2 `present` 1041→1085（= cheb(16) − 4 个 vanilla 切角），`TRACE_ENCLOSED_HOLE` 消失；R1 1469→1529 = \|shape(20)\| |

**未闭环**：A1 三条链未按 §1 实现；A3/A5/A4 未清完；B1–B5/B7 未做。

**2026-09-18 追加闭环（已验证）**：R2「权威窗外一圈封闭虚空」的根因即上表末行——权威驱动枚举
少一圈（`range` vs 形状外接盒 `range+1`），与 OVD/光照/候选几何无关。复现口径：
`1.20.1_fabric_I_ovdpath1`（FAIL，44 洞 / comp=11@r2，连续 6 场同签名）→ 改后 `ovdpath2`（PASS，
封闭洞 0）。**残余（不触门禁）**：R2 `ovdMiss=4` = `cheb(clientVD)` 的 4 个对角柱，落在
`shape(clientVD)` 之外（vanilla 也不会送），且 R1 交付过却未落盘——成因未查，另外单列。

**残留风险（【推断】）**：WINDOW_PUMP 删除后，若 pull 响应/materialize/桥都未覆盖「inject/盘有柱且客户端无该柱」，可能再出现脚下/前缘空洞 → **M1 必须先于继续删路径**。

## 4. 用户决策（冻结）

| 项 | 决策 |
|----|------|
| A1 | §1 三条链；交付=生成权威柱；A1-③ = seedGen 开 + **无缓存** |
| A1/约束1 | **compare 必须挡首投**（A1-①/A1-③）；权威裁决前不得交付本地基线/seedGen 柱 |
| A2 | 握手后关原版直推 **合理**（兼容原版服=无握手不 intercept）；实现上 **收窄** cancel，不取消 pull |
| A3 | 清理干净 |
| A5 | 清理干净（与 A1 绑定） |
| A6 | 清无用注释 |
| A9 | 生命周期：**load 走 Hassium，unload 仍原版 Forget**；供给侧由 A1 保证对称，不 cancel Forget |
| B1 | **保留 lightStrip**（核心省带宽）；原版化 = 打包/交付时序，不是关剥光 |
| B2/B3 | 打包/光桥 **改原版节奏**（柱级完成即发） |
| B4 | placeholder **不要** |
| B5 | 清理 `awaitEngineTaskDrain` |
| B6 | 统一交付门口径 |
| B7 | **保留**影子 light 收集以配合 strip；官方柱包内带光 |

**对 S0 的修订**：S0「继续 lightStrip」**仍然有效**；本 handoff 曾误读 B1 为关剥光，已更正——**lightStrip 默认开**，原版化只作用于交付时序（B2/B3）与占位/排水清理（B4/B5）。

## 5. 优先级与里程碑

### M1 — 供给闭环（P0）

| 序 | 内容 | 验收 |
|----|------|------|
| P0.1 | 实现 A1 三条链；`scheduleChunkLoad` / Provider / materialize 汇到权威柱交付 | 长飞/停住后 enter 窗内柱持续 `CHUNK_APPLY`/原版 apply |
| P0.2 | compare **必须挡首投**（A1-①/A1-③）；A1-② 权威 FULL 落地后交付 | seedGen/缓存基线柱在 UNCHANGED/DELTA/FULL 前无 `CHUNK_APPLY`/桥 forward；A1-③ 无 `origin=local_generation` 盲投 |
| P0.3 | 清 A3 epoch 控制面 + 删 A5 `drainRedeliver` 死路径 | 代码无 epoch 短路选柱；无 redeliver 队列 |
| P0.4 | B6 交付门统一（VD+余量）全入口一致 | 同一柱不出现「一处 skip 一处仍交付」 |

**建议顺序**：P0.1 → P0.2 →（编译+飞行冒烟）→ P0.3 → P0.4。

### M2 — 光照对齐原版（P1）

| 序 | 内容 | 验收 |
|----|------|------|
| P1.1 | B2：柱级 light 完成即打包 | 无空光长期 requeue 挡首包 |
| P1.2 | B3：柱级光就绪即发光包 | `Withheld/Deferred` 显著下降 |
| P1.3 | **保留 lightStrip**；柱包内带影子算光；collectLightUpdate 作增量光 | 进服/飞行无长黑柱；带宽统计仍体现 strip |
| P1.4 | B4 删 placeholder + B5 清 awaitEngineTaskDrain | 无 SHADOW_PLACEHOLDER 路径 |

### P2 — 接口收窄与文档

| 序 | 内容 |
|----|------|
| P2.1 | A2：无握手=原版直通；有握手=pull/影子管线 + 桥直通 apply |
| P2.2 | A9：文档+断言「抑制 load ⇔ A1 必交付」；Forget/reclaim 保持 |
| P2.3 | A6：删 bootGrid/sweep 等过时注释；实现路径与 §1 一致 |

### 明确不做

- cancel 原版 Forget（A9）  
- M1 未完成先只删交付路径  
- M2 未完成先只删 placeholder 而包内无光  
- 禁止把 lightStrip 默认改成关（核心省带宽）  

## 6. 关键代码索引

| 区域 | 路径 |
|------|------|
| 选柱会话 / 交付门 | `common/.../shadow/track/ShadowTrackingSession.java` |
| Provider / 在途 | `.../shadow/track/VanillaAlignedChunkProvider.java` |
| 取块裁决 | `.../shadow/track/ShadowChunkAcquire.java` |
| 本地交付出口 | `.../shadow/track/ShadowChunkDeliver.java` |
| 接法 B 桥 | `.../shadow/track/ShadowOfficialPacketBridge.java` |
| scheduleChunkLoad 短路 | `.../mixin/shadow/MixinChunkMap.java` |
| 客户端 intercept | `.../mixin/client/MixinClientPacketListener.java` |
| 光管线 / 打包 / 光桥 | `.../shadow/light/ShadowLightCompute.java` |
| Pull 响应 | `.../protocol/ShadowPullClient.java` |
| 真服 pull_mode | `.../mixin/server/MixinServerPlayer.java`、`ServerHandshakeActivation` |

## 7. 构建与验证

```powershell
# 编译（pwsh，daemon，勿 --no-daemon）
.\gradlew.bat common:compileJava fabric:compileJava

# 运行时冒烟（可选；长飞问题需人工飞行路径）
.\scripts\runtime-smoke-test.ps1 -Ver 1.20.1 -Loader fabric -Phase I -SessionId "1.20.1_fabric_I_shadow-align"
```

**飞行验收清单（M1 后）**

1. 无 `window-complete` 泵日志（已删）。  
2. 长飞后停住 ≤ 数秒：脚前/近处仍有区块加载（apply/mesh）。  
3. 服务端 `SHADOW_PULL` 不出现整窗 compare 风暴；客户端无成片 `inflight timeout`。  
4. 原版 `Ignoring chunk since it's not in the view range` 仅偶发（边界+余量外）。  

**M2 后额外**

5. 无长时间黑柱；`Withheld mid-compute` / `Deferred held` 不再刷屏。  
6. 客户端不依赖影子光 mask 才显示地形。

## 8. 未验证 / 风险

| 项 | 级别 | 说明 |
|----|------|------|
| M1 未实现 | 高 | 当前为补丁态；飞行回归未在 A1 完整实现后跑过 |
| WINDOW_PUMP 删除后交付覆盖 | 高 | 【推断】inject/盘命中路径可能无人投递，见 §3 残留风险 |
| lightStrip 保留 | 低 | 默认开；toml 可选关，但 `isServerLightStrip` 读配置 |
| seedGen 开路径 | 低→中 | **已跑** 1.20.1 fabric `seedgen` 冒烟 `1.20.1_fabric_I_seedgen_gateA` PASS：15 柱 compare-before-deliver，落地 origin=section_delta/shadow_memory_cache，`local_generation` 盲投=0，闸日志 96 次；1.21+ / forge-neoforge 锚点未跑 |
| 多 loader（forge/neoforge） | 低 | M1/M2 至少 fabric 1.20.1 验证后再扫 loader |

## 9. 实现时纪律

1. 每条结论带【已验证】/【推断】；行为类问题必须复现日志或单测。  
2. 修复闭环：复现 → 定位 → 改 → 复现消失 → 相关 compile/冒烟。  
3. 禁止业务散落新 `#if MC_VER`；版本差进 `compat/`。  
4. 与 S0 冲突时 **以本文 §4 用户决策为准**，并在 PR/commit message 引用本文路径。  

---

[← handoffs](.) · [architecture](../architecture.md) · [chunk-cache](../chunk-cache.md) · [client-chunk-light-flow](../client-chunk-light-flow.md)
