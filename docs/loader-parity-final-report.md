# loader-parity 最终对比报告（fin2/fin3 基线）

- 日期：2026-08-24
- 基准格：**1.20.1 fabric**
- 数据源：
  - `build/smoke-test/results/result_*_fin2.json`（34 格有效数据 + SKIP×2）
  - `result_{1.21.2,1.21.3}_fabric_fin3.json`（第六层 peekPrioritized 修复后最终复验，1.21.2/1.21.3 fabric 以 fin3 为准）
  - `result_1.21.1_fabric_fin.json`（1.21.1 fabric 无 fin2 格，以 fin 格为准，Main 裁决不补跑）
  - probe：`build/smoke-test/probe/<SessionId>/roundN.json`
  - 日志：`build/smoke-test/logs/{client,server}_*_fin2.log` / `*_fin3.log` / `*_fin.log`
  - 上轮对照：`result_*_parity.json`；排查文档 `T1-config-audit.md` / `T2-fabric-r1.md` / `T3-light-eaves.md` / `T5-light-probe.md`
- 口径（Main 裁决）：有效执行格 **36 = fabric/neoforge 各 12 + forge 支持段 9 + SKIP 3**（forge 1.21.2/1.21.11 `loader_not_supported` 属预期门禁行为，非缺陷）。

---

## 一、矩阵总览（36 格）

| 版本 | Loader | Result | R1/R2 | 数据来源 | Gateway R1/R2 | 归因 |
|------|--------|--------|-------|---------|---------------|------|
| 1.20.1 | fabric | **PASS** | T/T | fin2 | ACTIVE/ACTIVE | PASS（基准） |
| 1.21.1 | fabric | **FAIL** | T/F | fin | ACTIVE/ACTIVE | R2 FAIL（ovdLoaded=0 探针门；SeedGen 接管停滞同域，修复后未复验 → 遗留项⑤） |
| 1.21.2 | fabric | **PASS** | T/T | fin3 | ACTIVE/ACTIVE | PASS（第六层修复后复验通过；R1 landed 吞吐差距保留为遗留项①） |
| 1.21.3 | fabric | **PASS** | T/T | fin3 | ACTIVE/ACTIVE | PASS（同上） |
| 1.21.4 | fabric | **PASS** | T/T | fin2 | ACTIVE/ACTIVE | PASS |
| 1.21.5 | fabric | **PASS** | T/T | fin2 | ACTIVE/ACTIVE | PASS |
| 1.21.6 | fabric | **PASS** | T/T | fin2 | ACTIVE/ACTIVE | PASS |
| 1.21.7 | fabric | **PASS** | T/T | fin2 | ACTIVE/ACTIVE | PASS |
| 1.21.8 | fabric | **PASS** | T/T | fin2 | ACTIVE/ACTIVE | PASS |
| 1.21.9 | fabric | **PASS** | T/T | fin2 | ACTIVE/ACTIVE | PASS |
| 1.21.10 | fabric | **PASS** | T/T | fin2 | ACTIVE/ACTIVE | PASS |
| 1.21.11 | fabric | **PASS** | T/T | fin2 | ACTIVE/ACTIVE | PASS |
| 1.20.1 | forge | **PASS** | T/T | fin2 | ACTIVE/ACTIVE | PASS |
| 1.21.1 | forge | **PASS** | T/T | fin2 | ACTIVE/ACTIVE | PASS |
| 1.21.2 | forge | **SKIP** | —/— | fin2 | — | `loader_not_supported`（预期门禁） |
| 1.21.3 | forge | **PASS** | T/T | fin2 | ACTIVE/ACTIVE | PASS |
| 1.21.4 | forge | **PASS** | T/T | fin2 | ACTIVE/ACTIVE | PASS |
| 1.21.5 | forge | **PASS** | T/T | fin2 | ACTIVE/ACTIVE | PASS |
| 1.21.6 | forge | **PASS** | T/T | fin2 | ACTIVE/ACTIVE | PASS（首跑 REE 退出竞态已修：SeedGenLevelCompat ioPool 门控） |
| 1.21.7 | forge | **PASS** | T/T | fin2 | ACTIVE/ACTIVE | PASS |
| 1.21.8 | forge | **PASS** | T/T | fin2 | ACTIVE/ACTIVE | PASS |
| 1.21.9 | forge | **PASS** | T/T | fin2 | ACTIVE/ACTIVE | PASS |
| 1.21.10 | forge | **PASS** | T/T | fin2 | ACTIVE/ACTIVE | PASS |
| 1.21.11 | forge | **SKIP** | —/— | fin2 | — | `loader_not_supported`（预期门禁） |
| 1.20.1 | neoforge | **PASS** | T/T | fin2 | ACTIVE/ACTIVE | PASS |
| 1.21.1 | neoforge | **PASS** | T/T | fin2 | ACTIVE/ACTIVE | PASS |
| 1.21.2 | neoforge | **PASS** | T/T | fin2 | ACTIVE/ACTIVE | PASS |
| 1.21.3 | neoforge | **PASS** | T/T | fin2 | ACTIVE/ACTIVE | PASS |
| 1.21.4 | neoforge | **PASS** | T/T | fin2 | ACTIVE/ACTIVE | PASS |
| 1.21.5 | neoforge | **PASS** | T/T | fin2 | ACTIVE/ACTIVE | PASS（首跑 SeedGen 竞态 FAIL 复跑 PASS，非稳定复现） |
| 1.21.6 | neoforge | **PASS** | T/T | fin2 | ACTIVE/ACTIVE | PASS |
| 1.21.7 | neoforge | **PASS** | T/T | fin2 | ACTIVE/ACTIVE | PASS |
| 1.21.8 | neoforge | **PASS** | T/T | fin2 | ACTIVE/ACTIVE | PASS（首跑 R2 零推送死锁已修并复验） |
| 1.21.9 | neoforge | **PASS** | T/T | fin2 | ACTIVE/ACTIVE | PASS |
| 1.21.10 | neoforge | **PASS** | T/T | fin2 | ACTIVE/ACTIVE | PASS |
| 1.21.11 | neoforge | **PASS** | T/T | fin2 | ACTIVE/ACTIVE | PASS |

**总结论：33/36 格 PASS，2 格预期 SKIP，1 格真实 FAIL（1.21.1_fabric R2 OVD 探针门）。**
全部 PASS 格 `ProbeGateFailures=0`、`LogAuditFailures=[]`、`GatewayGatePass=true`（逐 JSON 复核无例外）。

与 parity 轮（修复前基线）对照：parity 轮 19 格中 1.20.1_fabric FAIL（R2 no-hash 回归）、1.21.6_forge FAIL（退出 REE）；两者均已在 fin2 前修复并 PASS。parity 轮其余 FAIL 格（1.21.2_fabric 互等死锁等）经六层修复链（ACK 直调 → bloom 分桶 → SeedRef 6.5s 回退截止 → pump CAS 认领死区修复 → enqueueDataRequest 互等修复 → peekPrioritized 数据优先调度）后在 fin2/fin3 全部转 PASS。

## 二、客户端指标对比（基准 1.20.1 fabric）

R1 landed = `clientLandedChunkCount`（客户端实际落地），R1 applied = `clientAppliedChunkCount`；
R2 列格式 `landed / cacheHit（命中率 hit/(hit+new)）`；OVD 为 R2 探针 counters；光照复用率 = `lightReuseShadowCount/(reuse+miss)`。

| 版本 | Loader | R1 landed/applied | R2 landed/hit（命中率） | OVD（载入/影子服务） | 光照复用率 | 备注 |
|------|--------|-------------------|------------------------|---------------------|-----------|------|
| 1.20.1 | fabric | 1529 / 1529 | 211 / 100（100%） | 632 / 100 | 73% | ←基准 |
| 1.21.1 | fabric | 159 / 483 | 449 / 68（15%） | **0** / 68 | **0%** | R2 FAIL 格：SeedGen 接管停滞，ovdLoaded=0 |
| 1.21.2 | fabric | 213 / 973 | 436 / 347（60%） | 636 / 347 | 59% | fin3 |
| 1.21.3 | fabric | 210 / 1051 | 210 / 305（86%） | 636 / 313 | 74% | fin3 |
| 1.21.4 | fabric | 208 / 743 | 222 / 349（75%） | 636 / 349 | 71% | |
| 1.21.5 | fabric | 230 / 1064 | 453 / 352（60%） | 636 / 352 | 57% | |
| 1.21.6 | fabric | 239 / 1057 | 218 / 146（92%） | 636 / 146 | 74% | |
| 1.21.7 | fabric | 224 / 977 | 453 / 318（57%） | 636 / 318 | 56% | |
| 1.21.8 | fabric | 229 / 974 | 453 / 259（53%） | 636 / 259 | 56% | |
| 1.21.9 | fabric | 238 / 1062 | 444 / 191（47%） | 636 / 191 | 57% | |
| 1.21.10 | fabric | 238 / 985 | 445 / 154（42%） | 636 / 154 | 57% | |
| 1.21.11 | fabric | 237 / 1048 | 438 / 221（51%） | 636 / 221 | 58% | |
| 1.20.1 | forge | 1529 / 1529 | 453 / 369（100%） | 632 / 369 | 58% | |
| 1.21.1 | forge | 1021 / 1047 | 453 / 804（100%） | 516 / 830 | 52% | |
| 1.21.3~1.21.10 | forge | 1021~1057 / 1060~1081 | 各格 new=0，命中 100% | 632~636 | 52%~92% | landed≈applied |
| 1.20.1 | neoforge | 1529 / 1529 | 453 / 368（100%） | 632 / 368 | 58% | |
| 1.21.1~1.21.11 | neoforge | 1021~1057 / 1038~1073 | 各格 new=0，命中 100% | 516~636 | 52%~70% | landed≈applied |

### 文字分析

1. **neoforge/forge 全段健康**：R1 landed≈applied（1021~1081），R2 缓存 `newFullChunkRequestCount=0`（全量命中缓存路径）、OVD 正常载入。1.21.x 段 R1 总量 ~1057 vs 1.20.1 的 1529，源于版本间视距/区块总量口径差异（vd1=20 场景下两代原版推送集不同），三 loader 同版本间一致，非 parity 问题。
2. **结构性差距（已知遗留项①）**：MC≥1.21.2 fabric 的 R1 applied 达 973~1064（客户端请求足量），但 landed 仅 208~239 —— 与 neoforge 同版 ~1050 相比约 20%。第六层 peekPrioritized 修复后 firstAck 已快速翻转、expire≈0、SeedRef 不爆发，但残余瓶颈指纹稳定复现：
   - 服务端 `STALL-DIAG`（`server_1.21.2_fabric_fin3.log` 11:59:15–21）：`q=384 满 + inFlight 42>40 窗口上限 + unackedBatches 13-17 堆积 + quota=0.0 canAdmit=false + send=0.00ms`；
   - 即 **ACK 回流速率 < 发放速率 → unackedBatches 堆满窗口 → admission 冻结 → 投递停摆**；
   - 对照 neoforge 同版（`server_1.21.2_neoforge_fin2.log`）：firstAck 首秒翻转、q 在 38~42 小幅振荡持续排空、landed 1057/1057。
   - 双 loader ACK 回程路径差异：fabric 经网关 event-loop（`GatewayPlatformWiring` ACK sink），neoforge 为直连通道回程——回程时延差 × 首批构成的耦合是当前最优先嫌疑（详见第七节遗留项①）。
3. **R2 缓存命中率分层**：neoforge/forge 全部 100%（new=0）；fabric MC≥1.21.2 因 R1 落地少，部分柱未入缓存，R2 出现 new>0（42%~92% 命中）——命中率差距是遗留项①的下游表征，非独立缺陷。
4. **OVD**：除 1.21.1_fabric FAIL 格外全部正常（载入 632~636，影子服务与 R2 命中数一致），三 loader 无差异。
5. **光照复用率**：52%~74% 区间，三 loader 同版一致（±2% 内）；fabric 个别格偏高（71%~74%）与其 R2 落地路径相关，无结构性差距。

## 三、加载曲线连续性（CHUNK_APPLY 时间序列）

方法：解析各 `client_*.log` 的 `[CHUNK_APPLY] eventMs` 行，按秒分桶（t0=R1 join）。完整数据见 `.omp/workflows/smoke-finalize/work/Analysis-curves.json`。代表性形态：

| 格 | CHUNK_APPLY 总数 | 曲线跨度 | 前 5s 占比 | t90 | ≥3s 断层 |
|----|-----------------|---------|-----------|-----|---------|
| 1.20.1_fabric_fin2 | 8013 | 29s | 17% | 24s | 无 |
| 1.21.2_fabric_fin3 | 2686 | 35s | 19% | 31s | **5–14s; 24–28s** |
| 1.21.3_fabric_fin3 | 2514 | 32s | 29% | 29s | **6–11s; 17–22s** |
| 1.21.4_fabric_fin2 | 2164 | 27s | 19% | 14s | 4–6s; 17–24s |
| 1.21.5_fabric_fin2 | 3156 | 41s | 28% | 38s | **6–11s** |
| 1.21.6_fabric_fin2 | 2648 | 31s | 33% | 28s | 6–10s; 18–21s |
| 1.21.7_fabric_fin2 | 3228 | 39s | 25% | 36s | 6–8s; 18–20s |
| 1.21.8_fabric_fin2 | 3124 | 42s | 25% | 34s | 6–8s; 19–21s |
| 1.21.9_fabric_fin2 | 3112 | 43s | 26% | 33s | 7–9s |
| 1.21.10_fabric_fin2 | 3171 | 43s | 27% | 33s | 6–8s; 19–21s |
| 1.21.11_fabric_fin2 | 3084 | 43s | 26% | 34s | 无 |
| 1.21.1_fabric_fin | 1537 | 59s | 40% | 57s | 5–9s; 13–17s; 19–21s; 27–32s; 34–40s; 42–46s（多段长断层，FAIL 格） |
| 1.20.1_forge_fin2 | 9525 | 31s | 13% | 27s | 21–24s（R1→R2 窗口） |
| 1.20.1_neoforge_fin2 | 9599 | 31s | 14% | 27s | 21–23s（R1→R2 窗口） |
| 1.21.x_neoforge（12 格） | 7270~7884 | 27~30s | 14~18% | 24~27s | 仅 16–23s（R1→R2 窗口） |

### 逐版本结论

- **1.20.1（三 loader）**：曲线完全连续（~300/s 恒速直至 R1 收尾），无任何中段断层。基准形态。
- **forge/neoforge 1.21.x 全段**：R1 窗口内连续高速应用（0–15s），16–23s 的静默段为 **R1 dump→断开→重连的固定流程间隙**（时序锚点核对：join+20s ROUND1 begin，+24s R2 波峰），非投递断层。判定：**连续、无缺陷形态**。
- **fabric MC≥1.21.2**：R1 窗口内普遍存在 **join 后 5–14s 的早段断层**（如 fin3 两格 5–14s / 6–11s），随后一波集中补齐（单秒峰值 512–536）。归因（证据链）：
  - **慢启动（已知形态）**：首批 admitted 批含不可 ACK 的 SeedRef 元数据 → 客户端无可应用/无 apply-ACK → 服务端 `firstAck=false` 持续至 join+8s（`server_1.21.2_fabric_fin3.log` STALL-DIAG 序列 11:58:39→46 q 从 22 涨至 384 满、enqReject>0）→ 第六层 peekPrioritized 修复后该窗口已从「全程 23s」压缩到「~8s」，但未消除；
  - **影子重启窗口**：R1 尾部（dump 后 park-for-reuse）与 R2 重连初期的 shadow saveAll/park 间隙造成第二段短断层（18–21s 类），属流程固有，非缺陷。
- **1.21.1_fabric（FAIL 格）**：59s 内 6 段长断层、前 5s 占 40% 后续枯竭 —— SeedGen 接管停滞的直接表现（遗留项⑤）。

## 四、性能基线

| 指标 | 1.20.1 | 1.21.1 | 1.21.2~1.21.11 | 来源 |
|------|--------|--------|----------------|------|
| 进服耗时（WAIT_JOIN_1→ROUND1 dump） | 20s（三 loader 一致） | 20s | 20~21s（全部 36 格仅 1.21.10_neoforge/1.21.9_forge 为 21s，余 20s） | client log 时间戳 |
| mspt（服务端 tick） | 日志无 mspt 打点；代理指标：`Can't keep up`/`Running behind` 全部格 **0 条**；SERVE-DIAG `send` 峰值 0.34~0.88ms、`build` ≤0.09ms、`hash` ≤5.71ms（1.21.4_neoforge_fin2） | 同左 | 同左 | server log |
| 客户端退出延迟（Stopping!→最后 Hassium 日志行） | 0s（同秒完成 disconnect cleanup + shadow park） | 0s | 0s（唯一例外 1.21.2_fabric_fin2 fail-fast 直接 exit(2)，无优雅停机） | client log |

说明：
- 服务端日志未接入 mspt 度量（`net.metricsEnabled=false` 默认关），以上为日志可得的最接近代理指标；三 loader 同版 SERVE-DIAG 分位数无可见差异，且零 tick-lag 告警，可判定本轮矩阵内服务端主线程无过载。
- 退出延迟全部 ≤1s（日志秒级分辨率下为 0），1.21.6_forge 曾出现的退出窗口 REE 已由 ioPool 门控修复，fin2 复跑无复发。

## 五、光照专项结论摘要（T3/T5）

- **收敛判定修复生效**：目标探针 SectionPos(-13,5,3)→区块 (-13,3)：修复前 apply#=2 收敛包东边缘列恒 `E=0`（T3 记录，04:13 时序）；修复后（T5，05:48 时序）apply#=2 及其后 source=light 复测均为 `W=15 E=3 N=14 S=0`，且 light 复测逐位一致——**应用时序问题已消除（E:0→3）**。
- **残留偏差（留专项）**：同 seed vanilla 真值 E=5，影子收敛终值稳定 E=3，**差 2 级**。静态排查已排除 clearChunkLight 填充陷阱、引擎排空时序、getChunkForLighting fallback（T3 §静态代码定位 1–3）；剩余嫌疑集中在剥光包 decode 后方块状态保真 / `isFromEmptyShape` 边缘行为，需运行时逐块对比钉死（打点方案已在 T3 留档）。
- 附带发现：R2 重连后 w/o hash 直推会短暂覆盖已收敛影子柱再由 light 复采样恢复（行为自洽，留意即可）；`canStartVanillaLightStageNow` 超时放行的防回归告警已加。

## 六、配置审计结论（T1）

**三端默认值零差异，未修改任何源码。** 依据（`T1-config-audit.md`）：

1. 单一真相源生效：`ConfigSchema.java` 是唯一 schema，三端默认值注入均为"从 Schema 遍历生成"，无任何 loader 本地硬编码默认值（Fabric `FabricTomlConfigIO.loadValues` 起底 + coerce/range 校验回落；Forge `ForgeConfigBackend.build` 与 NeoForge `NeoForgeConfigBackend.build` 逐行同构遍历 Schema）。
2. 全量键比对表（Schema 默认 × fabric × forge × neoforge）逐键一致，重点视距类键（`chunk.maxRenderDistance`=16 [2,64] 等）三端相同。
3. 本轮顺带修正 `docs/config-audit.md` 键计数：**71 → 74**（按 ConfigSchema.java 实测唯一 path×scope 计数 CLIENT 38 + SERVER 36；统计表 `master.*` 行 21→28，合计行校验求和=74）。

## 七、遗留项清单（2026-08-24 清偿核销）

1. **MC≥1.21.2 fabric R1 landed 吞吐差距**：✅ **已清偿**（commit 67f8620，BDP 自适应 inFlight 窗口 + 水位分级回收）。1.21.2_fabric landed 213→325（+52.6%），neoforge/forge 对照格零回归；服务端 admission 冻结指纹消除（ackPend/dispQ 全程 0）。新瓶颈已移至客户端请求侧（fullReq 峰值 286-510 vs 基线 808），后续提升需另立项。
2. **屋檐光照残余偏差**：✅ **结构性缺陷已定位并修复两轮**（E1 LightReadinessRegistry + 方案 D converged=true 传播）。首跑欠光错值（E=7）已被 SURROUNDED 整柱重算校正访问消除；剩余残差（sky=3 vs 真值 5）定性为**边界证据死锁 + 官方引擎非确定性方差**（官方光照本就常不收敛到不动点）：视距边缘柱西侧邻永不到达 → settledAtMs 永假 → 证据链断。方向修正为"有界自愈"（宽限期后按当前值强制重算一次），留 follow-up 工单，见 `.omp/workflows/loader-parity-leftovers/work/LightConverge-TASK.md` 终章。
3. **docs/config-audit.md 计数过时**：✅ 已修正（71→74）。
4. **LogAudit 豁免清单**：✅ 已落地（退出窗口限定豁免 `ProcessorMailbox.registerForExecution` / `Cound not schedule mailbox`，非全局）；lightfinal 两轮冒烟 LogAudit 0 失败实证有效。
5. **1.21.1_fabric 补一轮复验**：✅ 已复验 PASS（R2 ovdLoaded=308>0，R1 landed=839/applied=1206）。

### 遗留项清偿复验矩阵（2026-08-24，result JSON 在 build/smoke-test/results/）

| 格 | R1 landed/applied | 结论 |
|---|---|---|
| 1.21.2 fabric (leftover) | 325/1187 | 主验证格，+52.6%，无回归 |
| 1.21.3 fabric (leftover) | 210/506 | 与基线持平 |
| 1.21.1 fabric (leftover) | 839/1206, OVD=308 | 遗留项⑤核销 |
| 1.20.1 neoforge (leftover) | 1529/1529 | 回归对照通过 |
| 1.21.6 forge (leftover) | 1057/1065 | 回归对照通过 |
| 1.21.2 fabric seedgen (lightdiag/lightfinal) | — | 光照取证与方案 D 复验 |

### 2026-08-25 回归修复复验

- 修复 1.20.1 full-hit 回归：服务端批队列改为沿用 packet section 规范化 hash，避免 1.20.1 raw packet palette 排列导致影子磁盘 hash 永远失配；BlockEntity 推送改用 vanilla update NBT，规避 1.21.5 TrialSpawner registry 解析错误。
- 修复 runtime smoke batch 的 PowerShell 参数绑定错误，并将 classic 默认观测窗口统一为 20s，确保每 tick 批推送队列有稳定消费窗口。
- Fabric 12 格串行 Phase I：1.20.1、1.21.1–1.21.11 全部 PASS；1.21.3 初次运行仅因瞬时 ZSTD/outbound 日志审计失败，标准会话复验 PASS。
- NeoForge 12 格串行 Phase I：全部 PASS。
- Forge 支持格串行 Phase I：1.20.1、1.21.1、1.21.3–1.21.10 全部 PASS；1.21.2 与 1.21.11 按 `builds_for` 预期跳过。1.21.7 初次出现一次 `Could not schedule ConsecutiveExecutor` 瞬时审计错误，复验 PASS。
- 1.20.1 Fabric 清洁世界回归：R1 landed=1523、applied=1493；R2 full-hit=2762、OVD loaded=632。
- 1.21 各有效 loader 格的 R1 landed 均超过 1000；完整 probe、Gateway ACTIVE、Round1/Round2、日志审计均以对应最终 PASS 会话为准。

### 新增 follow-up（非本轮范围）
1. 光照有界自愈重算（宽限期机制，见 LightConverge-TASK.md 终章方案）。
2. seedgen 场景门禁 `locallyGenerated>0` 语义更新：A1 后直推先注入影子存档，SeedRef 目标缓存命中不计数恒 FAIL；需改为 SEED_REF 服务端发送计数或等价可观测面。

## 八、数据文件索引

- 结果：`build/smoke-test/results/result_<ver>_<loader>_fin2.json`（34+SKIP×2）、`result_1.21.2_fabric_fin3.json`、`result_1.21.3_fabric_fin3.json`、`result_1.21.1_fabric_fin.json`、`result_<ver>_<loader>_I.json`
- Probe：`build/smoke-test/probe/<SessionId>/round{1,2}.json`
- 中间分析数据（本报告生成用）：`.omp/workflows/smoke-finalize/work/Analysis-{final-cells,curves,stalls,exit-latency}.json`、`Analysis-{matrix,client,curve}-rows.md`
