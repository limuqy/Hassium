# Push Counter 冒烟报告

日期：2026-08-26。会话前缀 `*_I_push_counter`。`DelayMs=20000`（R1 窗口 40s）、`MoveSeconds=0`（站桩）。

**合计：PASS 34 · SKIP 2 · FAIL 0**（36 格；Forge 1.21.2 无 userdev、1.21.11 sunset）。

全部 PASS 格：`ProbeGateFailures=0`、`LogAuditFailures=[]`、Gateway R1/R2=`ACTIVE`。门禁未要求 R1 喂满 VD20，也未要求 cacheHit 绝对值。

本轮 **未钉死客户端 RD 滑块**：Fabric `run/client/options.txt` 遗留滑块 ≥20，Forge/NeoForge 1.21 新目录默认 16。1.21+ 服务端跟踪半径 = `min(滑块, 服务器 VD)`，因此下表 R1 实际是两套几何，**不能把 Forge/NeoForge 1.21 的 1021/1057 当成相对 1529 的吞吐缺口**。harness 已补 `options.txt renderDistance=32`（三端同一滑块，≥ Vd1）；OVD 上界仍是 `chunk.maxRenderDistance=16`。复验需重跑矩阵。

## 几何口径（站桩满分）

原版推送不是 `(2N+1)²` 方阵。`ChunkMap.isChunkInRange` / `ChunkTrackingView.contains(..., true)` 是带外扩的圆柱。1.21.4 起 `isWithinDistance` 改为 `|d|-2` 欧氏，同半径多几十柱。Hassium `isServerChunkInRange` 仍用 1.20.1 公式，所以 R2 权威环全矩阵都是 453。

| 集合 | 几何 | 满分 |
|------|------|------|
| R1 权威 · VD20 · 旧公式（1.20.1–1.21.3） | 圆柱 extra=true | **1529** |
| R1 权威 · VD20 · 新公式（1.21.4+） | 同上改欧氏 | **1573** |
| R1 权威 · VD16 · 旧 / 新 | 滑块被钳 16 时 | **1021** / **1057** |
| R2 权威 · VD10 | Hassium 旧公式 | **453** |
| R2 OVD · RD16 方 − VD10 圆柱 | `33² − 453` | **636** |

计数定义：

- **landed** = `clientLandedChunkCount`（权威柱按坐标去重；OVD/renderOnly 不计）
- **applied** = 管线事件合计（全量请求 + 全命中 + 本地生成 + delta + 直推），可大于 landed
- **cacheHit** = `cacheHitFullChunkCount`（contentHash **整柱**命中），不是有效命中率
- **ovdLoaded** = 当前已 apply 的 renderOnly 环带；dump 只等到 `>0`

R2 离线窗口会砌石墙（约 20 柱 hash 必变，走 section delta）。整柱命中上限 ≈ `453 − 20 = 433`（约 95%），不是 100%。有效命中率 = `(全命中 + 部分命中 − 增量) / 应用`，本表未列 `cacheDeltaCount`。

## 矩阵

| 版本 | fabric | forge | neoforge |
|------|--------|-------|----------|
| 1.20.1 | PASS | PASS | PASS |
| 1.21.1 | PASS | PASS | PASS |
| 1.21.2 | PASS | SKIP | PASS |
| 1.21.3 | PASS | PASS | PASS |
| 1.21.4 | PASS | PASS | PASS |
| 1.21.5 | PASS | PASS | PASS |
| 1.21.6 | PASS | PASS | PASS |
| 1.21.7 | PASS | PASS | PASS |
| 1.21.8 | PASS | PASS | PASS |
| 1.21.9 | PASS | PASS | PASS |
| 1.21.10 | PASS | PASS | PASS |
| 1.21.11 | PASS | SKIP | PASS |

## R1：权威环是否喂满

基线 `1.20.1 fabric` landed=1529 / applied=1581（VD20 旧公式满分）。applied − landed 是重复 apply / 多路径记账，不是多出来的视野。

| 版本 | loader | landed | 对照满分 | 缺口 | 几何 |
|------|--------|--------|----------|------|------|
| 1.20.1 | fabric / forge / neoforge | 1529 | 1529 | 0 | VD20 旧公式，喂满 |
| 1.21.1 | fabric | 1525 | 1529 | −4 | VD20，差 4 |
| 1.21.1 | forge / neoforge | 1016 / 1021 | 1021 | −5 / 0 | **VD16 旧公式**（滑块 16） |
| 1.21.2 | fabric | 1529 | 1529 | 0 | VD20 喂满 |
| 1.21.2 | neoforge | 1018 | 1021 | −3 | VD16 |
| 1.21.3 | fabric | 1526 | 1529 | −3 | VD20 |
| 1.21.3 | forge / neoforge | 1017 / 1020 | 1021 | −4 / −1 | VD16 |
| 1.21.4 | fabric | 1523 | 1573（新）/ 1529（Hassium 旧滤） | −50 / −6 | 贴旧公式 1529，未放到 1573 |
| 1.21.4–1.21.10 | forge / neoforge | 1051–1057 | **1057** | 0～−6 | **VD16 新公式，基本喂满** |
| 1.21.5–1.21.9 · 1.21.11 | fabric | 1529 | 1573 / 1529 | 0（对旧滤） | Hassium 旧公式拦住多出的 ~44 柱 |
| 1.21.10 | fabric | **1573** | 1573 | 0 | 新公式 VD20 喂满（+44 是几何，不是多推） |
| 1.21.11 | neoforge | 1055 | 1057 | −2 | VD16 新公式 |

要点：

- Fabric 1.21.x R1 按 VD20 看，**没有系统性缺环**；1.21.10 的 1573 是 1.21.4+ 原版公式满分。
- Forge/NeoForge 自 1.21.1 起 R1 少约 500，是 **客户端滑块 16 → 跟踪 VD16**，applied 紧贴 landed（例如 1057/1076），不是 admission / drain 卡死。
- 1.21.4+ 同滑块下满分从 1021→1057、1529→1573。三端要对齐，必须先统一滑块再比 loader。

## R2：VD10 + OVD16

R2 权威环 **一律 453**，与 loader / 版本无关，等于 VD10 旧公式满分。场景就是服务端 VD=10 + 客户端有效 RD=16（`min(滑块, maxRenderDistance)`）。

| 群体 | ovdLoaded | 对照 636 | 权威+环带 | 对照 1089 方阵 |
|------|-----------|----------|-----------|----------------|
| 1.20.1 三端、1.21.x fabric（除 1.21.10） | 632 | −4 | 1085 | 四角未灌完 |
| 1.21.10 fabric | **636** | 0 | 1089 | 环带满分 |
| 1.21.1–1.21.3 forge/neo | 516 | −120 | 969 | 环带约 81%，dump 不等扫完 |
| 1.21.4+ forge/neo | 544 | −92 | 997 | 环带约 86% |

516/544 不是「R2 变成了别的视距」——权威环仍是 453。OVD 灌队受 `OVD_LOAD_THRESHOLD=128` 和影子算光拖累，门禁只要求 `ovdLoaded>0`。

## R2 缓存命中

`cacheHit` 只计整柱 hash 命中。分母用 R2 landed=453 时，基线 364 ≈ **80%**；相对可命中的 ~433 约 **84%**。石墙那 ~20 柱应走 delta，G2 已要求 `sectionDeltaApplied` 或 `lightSegRecalc` > 0。

| 群体 | cacheHit | /453 | 读法 |
|------|----------|------|------|
| 1.20.1 三端 | 356–377 | ~80% | 基线；差额主要是光脏 / 未刷盘，外加石墙 delta |
| 1.21.x fabric（除 1.21.10） | 138–186 | 30–41% | R1 已盖住 VD10（1523+），**不是覆盖不足** |
| 1.21.10 fabric | 385 | 85% | 与基线同级，影子管线顺时可以回到 80%+ |
| 1.21.x forge/neo | 103–165 | 23–36% | 与 fabric 1.21 同类，再叠加 OVD 未灌完 |

1.21.x 全命中掉下来，优先怀疑影子端：PalettedContainer 跨线程导致 hash 不稳、`scheduleTick` 洪水拖慢落盘/算光、更多柱 `lightDirty` 不能整柱命中。其中一部分会改走 delta，**有效命中率可能高于本表**；下轮应同时记 `cacheDeltaCount` / `newFullChunkRequestCount`。

## 明细（相对 1.20.1 fabric 观测值）

Δ 仅便于扫表。R1 的 −472～−513 在滑块统一前 **不要当回归**。

| 版本 | loader | R1 landed (Δ) | R1 applied | R2 cacheHit (Δ) | R2 ovdLoaded (Δ) | gw |
|------|--------|---------------|------------|-----------------|------------------|-----|
| 1.20.1 | fabric | 1529 (基线) | 1581 | 364 (基线) | 632 (基线) | ACTIVE |
| 1.20.1 | forge | 1529 (0) | 1595 | 356 (−8) | 632 (0) | ACTIVE |
| 1.20.1 | neoforge | 1529 (0) | 1573 | 377 (+13) | 632 (0) | ACTIVE |
| 1.21.1 | fabric | 1525 (−4) | 1545 | 138 (−226) | 632 (0) | ACTIVE |
| 1.21.1 | forge | 1016 (−513) | 1036 | 112 (−252) | 516 (−116) | ACTIVE |
| 1.21.1 | neoforge | 1021 (−508) | 1045 | 103 (−261) | 516 (−116) | ACTIVE |
| 1.21.2 | fabric | 1529 (0) | 1537 | 149 (−215) | 632 (0) | ACTIVE |
| 1.21.2 | neoforge | 1018 (−511) | 1032 | 151 (−213) | 516 (−116) | ACTIVE |
| 1.21.3 | fabric | 1526 (−3) | 1541 | 168 (−196) | 632 (0) | ACTIVE |
| 1.21.3 | forge | 1017 (−512) | 1031 | 135 (−229) | 516 (−116) | ACTIVE |
| 1.21.3 | neoforge | 1020 (−509) | 1038 | 137 (−227) | 516 (−116) | ACTIVE |
| 1.21.4 | fabric | 1523 (−6) | 1536 | 186 (−178) | 632 (0) | ACTIVE |
| 1.21.4 | forge | 1057 (−472) | 1076 | 139 (−225) | 544 (−88) | ACTIVE |
| 1.21.4 | neoforge | 1056 (−473) | 1075 | 136 (−228) | 544 (−88) | ACTIVE |
| 1.21.5 | fabric | 1529 (0) | 1539 | 167 (−197) | 632 (0) | ACTIVE |
| 1.21.5 | forge | 1057 (−472) | 1075 | 151 (−213) | 544 (−88) | ACTIVE |
| 1.21.5 | neoforge | 1057 (−472) | 1078 | 143 (−221) | 544 (−88) | ACTIVE |
| 1.21.6 | fabric | 1529 (0) | 1538 | 155 (−209) | 632 (0) | ACTIVE |
| 1.21.6 | forge | 1057 (−472) | 1087 | 165 (−199) | 544 (−88) | ACTIVE |
| 1.21.6 | neoforge | 1057 (−472) | 1057 | 150 (−214) | 544 (−88) | ACTIVE |
| 1.21.7 | fabric | 1529 (0) | 1548 | 161 (−203) | 632 (0) | ACTIVE |
| 1.21.7 | forge | 1055 (−474) | 1073 | 159 (−205) | 544 (−88) | ACTIVE |
| 1.21.7 | neoforge | 1057 (−472) | 1073 | 144 (−220) | 544 (−88) | ACTIVE |
| 1.21.8 | fabric | 1529 (0) | 1550 | 156 (−208) | 632 (0) | ACTIVE |
| 1.21.8 | forge | 1056 (−473) | 1076 | 151 (−213) | 544 (−88) | ACTIVE |
| 1.21.8 | neoforge | 1057 (−472) | 1080 | 145 (−219) | 544 (−88) | ACTIVE |
| 1.21.9 | fabric | 1529 (0) | 1542 | 165 (−199) | 632 (0) | ACTIVE |
| 1.21.9 | forge | 1057 (−472) | 1067 | 156 (−208) | 544 (−88) | ACTIVE |
| 1.21.9 | neoforge | 1056 (−473) | 1073 | 126 (−238) | 544 (−88) | ACTIVE |
| 1.21.10 | fabric | 1573 (+44) | 1606 | 385 (+21) | 636 (+4) | ACTIVE |
| 1.21.10 | forge | 1054 (−475) | 1072 | 141 (−223) | 544 (−88) | ACTIVE |
| 1.21.10 | neoforge | 1051 (−478) | 1068 | 152 (−212) | 544 (−88) | ACTIVE |
| 1.21.11 | fabric | 1529 (0) | 1541 | 143 (−221) | 632 (0) | ACTIVE |
| 1.21.11 | neoforge | 1055 (−474) | 1069 | 132 (−232) | 544 (−88) | ACTIVE |

## 本轮修过的根因

1. **PalettedContainer 跨线程**（1.21.1+）：`finishLight` 后台 `buildPacket` 与影子主循环 `applyBlockUpdate`/`setBlock` 并发 → ThreadingDetector ERROR，R2 drain 卡死。`pushReady` 改派回影子主线程；section 更新改走注入柱 `setBlockState` 并持 chunk 锁。
2. **影子端 scheduleTick 洪水**（1.21.2+）：邻柱未进 `LevelTicks.allContainers` 时 `Util.logAndPauseIfInIde` 刷 ERROR。新增 `MixinLevelTicks`，影子上下文短路 `schedule`。
3. **1.21.5 CompoundTag API**：`ShadowChunkNbtCompat` 改为 `CompoundTagCompat.getList` / `getCompound`（`#if MC_VER < MC_1_21_5`）。
4. **LogAudit**：豁免原版 OpenAL `Sound engine.*Stop: Invalid name parameter`（断连停声，与 mod 无关）。
5. **1.21.5+ `setBlockState` flags**：影子写入用 `0`（UPDATE_NONE），对齐旧版 `false`。

## 重试（非代码）

- 1.21.5 forge：上一格残留 25566 / `session.lock`，清理后 PASS。
- 1.21.7 fabric：首跑 R1 仅 49 landed、R2 ovdLoaded=0；热编译重跑 PASS。
- 1.21.9 neoforge：25565 被上一格占用，清理后 PASS。
- 1.21.11 fabric：loom cache lock（`ACQUIRED_PREVIOUS_OWNER_DISOWNED`），`gradlew --stop` 后重建缓存再 PASS。

## 残留与后续

- `build/smoke-test/pregen-world/fabric-1.21.1.stale-pregen`：本轮为避开过期 pregen 自动恢复而改名，未删。
- 未再跑 PregenOnly（此前 forge 1.20.1 pregen 超时）；一律 `-CleanWorld` 现场 worldgen。
- Gradle daemon 仍在（正常）。25565/25566 应已空。
- **harness**：`runtime-smoke-test.ps1` 现于起客户端前钉死 `options.txt` `renderDistance=32`（` -ClientRenderDistance`，且 ≥ Vd1）。本轮数字仍是滑块不齐下的观测。
- 本轮改动未提交：`ShadowLightCompute` / `ShadowSeedServer` / `ShadowServerCompat` / `MixinLevelTicks` / `hassium.mixins.json` / `CompoundTagCompat` / `ShadowChunkNbtCompat` / `runtime-smoke-test.ps1`。

建议下轮：滑块钉死后重跑 classic 矩阵；R1 按 1529（1.21.4+ 允许 1573）判喂满；R2 仍期望 landed=453、ovdLoaded→636；探针同时看 `cacheDeltaCount` 才能谈 1.21 有效命中率。
