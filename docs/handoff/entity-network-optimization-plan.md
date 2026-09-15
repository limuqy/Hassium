# 规划：实体网络优化三件套（逐级降帧 / 热点降帧 / 实体平滑推送）

状态：**已实施**（2026-09-15；实施记录与四处设计修正见 §8）
日期：2026-09-15
前置：高频实体包已放开进聚合（`f7020f3`）；聚合 tick 尾冲刷 + 50ms watchdog 已就位。
定位：网络优化新卖点。与既有卖点（平滑推送=区块域、聚合=压缩域）正交叠加，本族覆盖**实体域**。

## 0. 三个功能的共用底座

三者本质是同一个问题的三个切面：**实体更新包的产生速率与投递形态**。

```
ChunkMap.tick() ─→ TrackedEntity.serverEntity.sendChanges() ─→ broadcast(packet)
                        │                                           │
        ┌───────────────┼───────────────────────────────────────────┼──────────────┐
        │ ② 逐级降帧    │ ③ 热点降帧            ① 实体平滑推送（削峰）           │
        │ 按观察者距离   │ 按局部密度             按每玩家每 tick 包量上限          │
        │ 降低产生速率   │ 降低产生速率            延迟投递、均匀分布               │
        └───────────────┴───────────────────────────────────────────┴──────────────┘
```

- ②③ 在**源头**减包（`sendChanges` 跳帧，lastSent 不推进 → 恢复时 delta 正确，无 desync）
- ① 在**投递**削峰（包已产生，错开/延迟投递）
- ②③ 削掉 ① 的压力；① 兜住 ②③ 无法削减时的形态（如战斗中大量实体近距离高帧率）

## 1. 逐级降帧（按距离）

### 1.1 vanilla 基线（已核实，1.21.1 mojmap）

| 实体 | updateInterval | 实际帧率 | 备注 |
|---|---|---|---|
| 牛/猪/村民/僵尸等常规生物 | 3（Builder 默认） | 20 帧/s | 每 3 tick 一帧 |
| 掉落物 `item`（静止/缓动） | 20 | 1 帧/s | delta < tolerance 时不出包 |
| **掉落物 `item`（物品流中）** | 20（**被绕过**） | **20 帧/s** | `hasImpulse` 绕过 interval（见下） |
| 经验球 `experience_orb` | 20 | 同掉落物 | 同样可被 impulse 绕过 |
| 玩家 | 不走 interval（`ServerGamePacketListenerImpl` 每 tick 直发） | 20 帧/s | 天然豁免 |

**掉落物流的 interval 绕过（关键修正，源码核实 1.21.1）**：`ItemEntity.tick()` 中
`this.hasImpulse |= this.updateInWaterStateAndDoFluidPushing()`（水中每 tick 置位）与
`deltaMovement 变化量 > 0.01 → hasImpulse = true`（重力/加速/摩擦每 tick 触发），
而 `sendChanges()` 的判定是 `tickCount % updateInterval == 0 || hasImpulse || ...`——
`hasImpulse` **完全短路 interval**。物品流（水道运输、世吞、全物品收集、大型合成机/
熔炉输出、高版本合成器）里每个掉落物实际以 **20 包/s** 直发，interval=20 形同虚设。
数百掉落物的物品流 = 每玩家每秒数千个位移包，是生电服实体流量的最大单一来源。
次生流量：`mergeWithNeighbours`（block 坐标变化时每 2 tick 执行）的合并 churn 产生
持续的 `remove_entities` + `add_entity`（~40B/个，含 UUID+坐标+速度）风暴。

### 1.2 配置形态（用户指定）

```toml
[master]
entityTieredUpdateEnabled = true   # 总开关

# 距离分挡（VD 单位；升序；末挡之外沿用末挡帧率）
entityTieredUpdateDistanceTiers = [5, 8, 12, 16]

# 每挡帧率（tick/帧；与距离挡一一对应；第 0 挡 = 最近）
entityTieredUpdateIntervalTiers = [3, 6, 10, 20]
```

- 距离 = 观察者到实体的欧氏距离（平方比较，无 sqrt），换算 VD：`blocks = tier × 16`
- 挡位语义：`dist ≤ 5×16` → interval 3（vanilla 级）；`5×16 < dist ≤ 8×16` → 6；以此类推；`> 16×16` → 20
- **per-observer**：每个观察者按自己的距离独立取挡（用户已拍板 per-observer 精确分级）
- 两数组长度必须相等，校验失败回落默认
- 默认值即用户示例 `(5,8,12,16)` + `(3,6,10,20)`

### 1.3 可行性结论（继承前轮调研，全部已核实）

- 注入点：`MixinServerEntity.sendChanges` HEAD cancellable，1.20.1/1.21.1 双段 `analyze_mixin` 通过
- per-observer 的结构性难点：`sendChanges` 一次放行 = `seenBy` 全体收帧。分流方案（前轮设计文档 `local://entity-update-tiering-design.md` §3.2）：
  - **near 帧**（任一观察者按其挡位到期）→ 放行整个方法，远端观察者多收一帧（无害回退）
  - **far-only 帧**（只有远端观察者到期）→ mixin 手工编码 `ClientboundTeleportEntityPacket`（绝对坐标，绕开 delta）仅发远端集合
- 观察者集合：`level.players()` 过滤（不反射 `seenBy` 内部类私有集合）
- 滞回：interval 只在观察者帧边界重算 + factor 滞回带
- 客户端零改动；远端实体插值步数偏小 → 帧间静止（"轨迹变粗"，预期行为）
高。生电场景实体量大、多数处于观察者中远距离。两类主力：(a) 常规生物 interval=3（牛栏/村民繁殖厅/刷怪塔，~20 帧/s/实体，100 实体 = 2000 包/s/玩家）；(b) **物品流掉落物 20 帧/s/个（interval 被 hasImpulse 绕过）**——数百掉落物的世吞/全物品/大型合成机是最大单一流量源，降帧收益 20×（比生物的 6.7× 更大）。按挡位降到 10–20 tick/帧可砍 60–95% 位移包量。物品流的视觉平滑度在远端天然不重要（掉落物渲染本身有 bobbing 动画遮盖），降帧副作用最小。

### 1.5 hasImpulse 绕过对 gate 设计的影响

HEAD cancellable 注入天然覆盖绕过路径：`ci.cancel()` 跳过整个 `sendChanges`
（含 `hasImpulse ||` 短路分支），lastSent 不推进，恢复时 delta 正确。**无需对
`hasImpulse` 单独处理**——gate 判定 `tickCount % effectiveInterval != 0 → cancel`
即可，interval 语义对 impulse 类实体（掉落物/经验球/投掷物）同样生效。
<p>
注意：`sendChanges` 尾部 `this.entity.hasImpulse = false` 也在 cancel 范围内——
跳帧时 impulse 标志保留，下一帧放行时 vanilla 判定 `hasImpulse == true` 直接出帧，
不产生额外延迟。语义正确。

## 2. 热点降帧（按密度）

### 2.1 动机

刷怪塔落点、物品收集口、村民交易所等**单点高密度**：同一 chunk 内几十上百掉落物/生物。逐级降帧按距离处理不了"近而密"（玩家就站在收集口旁边，距离挡位取最近）。

### 2.2 机制草案

```
density(entity) = 该实体所在 chunk（或 3×3 chunk 邻域）内 tracked 实体数
interval_hot = vanillaInterval × hotFactor(density)
hotFactor: 阈值分挡（如 >50 → ×2，>200 → ×4，>500 → ×8）
```

- 与逐级降帧**相乘叠加**：`effectiveInterval = tierInterval(dist) × hotFactor(density)`
- 密度统计：per-chunk 计数器，`addEntity`/`removeEntity` 时增减（`ChunkMap` 已有 `entityMap`，mixin 计数器挂 chunk 维度 Map<ChunkPos, Integer>，O(1) 维护）
- 掉落物特判（修正）：堆叠点静止掉落物 `sendChanges` 不出位移包（delta < tolerance），收益在 metadata/出生包；但**物品流掉落物（世吞/水道/收集装置）每 tick 置 `hasImpulse`，实际 20 包/s/个**（§1.1 绕过修正）——热点降帧对它们是位移包主力收益，与密度计数器天然契合（物品流 = 高密度 chunk）。**结论：热点降帧同时覆盖高密度生物（村民/动物）与物品流掉落物，两者都是位移包大户。**

### 2.3 可行性

可行，与逐级降帧共用全部底座（同一 gate、同一 per-observer 状态、同一 mixin 注入点），只多一个 per-chunk 密度计数器和一个 factor 函数。**建议与逐级降帧同一专项实施**（共享 `EntityUpdateTiering` 类，两个 factor 相乘）。

### 2.4 必要性

中高。逐级降帧覆盖"远而多"，热点降帧覆盖"近而密"——生电服两者都常见。但实现增量大头（per-observer 分流）已被逐级降帧付掉，热点只加 ~20% 工作量。

## 3. 实体平滑推送（削峰）

> **状态更正（2026-09-16）**：本节原案的「延迟投递队列」在实施时因相对包不可自愈而废弃
> （见 §8.1 第 3 行），改为**只计数 + 源头压力反压**。用户后续明确要求的「平滑推送」是
> **时间错峰**（同 interval 总量不变、摊平齐发尖峰），不是降速、也不是延迟队列。
> 错峰实现见 **§9**；压力反压仍保留为超预算时的兜底降速。

### 3.1 动机（用户判断正确，已核实）

vanilla 实体包投递形态：`ChunkMap.tick()` 遍历 `entityMap`，所有 `tickCount % interval == 0` 的实体**同 tick 齐发**。interval=3 时每 3 tick 一大批（1/3 实体同 tick 出帧），叠加 `ClientboundBundlePacket`（出生包组）后单 tick 包量尖峰显著。对网络（瞬时带宽、聚合缓冲压力）和客户端主线程（`channelRead0` → 主线程 hop 的批处理）都是脉冲式压力。

### 3.2 机制草案

```
per-player per-tick 实体包预算（如 64 包/tick）
超预算 → 延迟投递（进下一 tick 的队列，保留相对顺序）
```

- **投递层**削峰（包已产生，不跳帧——与 ②③ 的"源头减包"正交）
- 实现点：`TrackedEntity.broadcast` 的 consumer 逐包走 `Connection.send` → mixin `MixinConnection.hassium$tryAggregate` 已是全包拦截点；**实体包预算在聚合 takeOver 前拦截**（或聚合缓冲入口处按连接计数）
- 与聚合天然协同：超预算包进聚合缓冲本就延迟 ≤50ms；平滑推送只需把"每 tick 冲刷量"也纳入预算（当前 `flushAll` 无 per-tick 包数上限，只有 256KB 大小上限）
- 借鉴既有 `PullPacingValve`（区块域平滑推送）的分挡阀门模式：`fullDeltaBudget`/`hashBudget`/`lookahead` 三段式 → 实体域对应 `entityPacketBudget`（每 tick 每玩家直发上限）+ `entityAggregationBudget`（每 tick 每玩家聚合冲刷子包上限）

### 3.3 可行性

高。全在 Hassium 已有拦截层（`MixinConnection`），不新增 vanilla mixin 面。风险点：

1. **顺序**：延迟投递不能打乱 `set_passengers`/`remove_entities` 等控制包与位移包的相对顺序 → 预算只作用于位移/旋转类包（`ClientboundMoveEntityPacket.*`、`ClientboundRotateHeadPacket`），控制包直通
2. **与 teleport 纠偏的交互**：位移包延迟 > teleportDelay 周期（400 tick）不可能（预算延迟是 tick 级），无风险
3. **客户端插值**：延迟 1–2 tick 投递 = 帧到达更不均匀，插值窗口内仍是平滑的（客户端 lerp 按 tick 步进）——比"同 tick 500 包"好

### 3.4 必要性

高，且是 ②③ 的**必要配套**：②③ 降帧后剩余帧的分布仍齐发（interval 对齐），不削峰则聚合缓冲每 3 tick 一次大冲刷，watchdog 形同虚设。三者共用"平滑推送"概念与用户已有心智（区块域平滑推送是既有卖点）。

## 4. 实施顺序建议（统一专项）

```
Phase A：EntityUpdateTiering 核心（逐级降帧 gate + per-observer 分流 + far-only 手工帧）
Phase B：热点降帧（密度计数器 + hotFactor 相乘，同 gate）
Phase C：实体平滑推送（投递层预算 + 聚合冲刷预算）
Phase D：实测校准（真实大型生电存档，用户自备；验证指标见 §8）
```

A→B 共享 90% 代码；C 独立但依赖 A/B 落定后的包量基线；D 在 A/B/C 全部就位后统一测。

## 5. 评估结论

| 功能 | 可行性 | 必要性 | 结论 |
|---|---|---|---|
| 逐级降帧（按距离） | 高（双段 mixin 验证通过；per-observer 分流方案已设计；HEAD cancel 天然覆盖 hasImpulse 绕过） | 高（生物 60–85% + 物品流最高 95% 位移包削减） | **做**，专项 Phase A |
| 热点降帧（按密度） | 高（与 A 共用底座，+20% 工作量） | 高（"近而密"生物 + 物品流掉落物都是位移包大户，§1.1/§2.2 修正后） | **做**，专项 Phase B |
| 实体平滑推送（削峰） | 高（全在既有拦截层） | 高（A/B 的必要配套；聚合冲刷削峰） | **做**，专项 Phase C |

三者统一为一个网络优化卖点族：**实体域分级更新**（tiered + density + pacing）。

## 6. 实测验证指标（Phase D，用户自备真实生电存档）

1. 包量：每玩家每秒实体位移包数（vanilla 基线 vs A vs A+B vs A+B+C）
2. 带宽：`NetworkStats.actualBytesReceived`（已有口径）
3. 客户端主线程：实体包处理耗时采样（`debug.*` 开关已有基建）
4. 视觉回归：远端实体移动平滑度（L3 手动）、村民繁殖厅行为正常性（AI 不受影响——降帧只影响网络层，服务端 AI tick 不变）
5. 物品流专项：掉落物位移包量（hasImpulse 绕过路径的实际包量，验证 §1.1 修正）+ add/remove 合并 churn 包量（验证 §7.1 是否需要第四机制）

## 7. 待用户实测后回填的开放问题

1. 物品流场景的 add/remove 合并 churn 占比（决定是否需要第四个机制：**合并抑制/批量 remove_entities**——数百掉落物流的 `mergeWithNeighbours` 每 2 tick 级联合并产生 add_entity+remove_entities 风暴，降帧不覆盖出生/移除包）
2. 真实存档的观察者-实体距离分布（决定默认挡位 `(5,8,12,16)` 是否合理）
3. 每玩家每 tick 实体包预算的合理默认值（64 是拍脑袋值；现为**每观察者独立**口径——该玩家自己的实测值 ÷ 该预算）
4. 物品流视觉阈值：**已由独立档位表解决**（默认近 2 / 中 4 / 远 8 / 边缘 16 刻；近档落在客户端 3 刻插值窗口内）。若实测仍嫌远端跳，直接调 `entityItemTierIntervals` 的第 4 个元素（该表与生物表解耦，互不影响）。

---

## 8. 实施记录（2026-09-15）

### 8.1 与规划原案的六处设计修正（均为正确性/语义驱动，依据是反编译源码与实测反馈）

| 规划原案 | 实施改为 | 原因 |
|---|---|---|
| `@Inject(HEAD, cancellable=true)` 取消 `sendChanges()`，门条件用 `tickCount % effectiveInterval` | `@Redirect` 门条件里的两个**字段读取**（`this.updateInterval` 与 `entity.hasImpulse`），方法结构与调用时机不动 | `this.tickCount++` 在**方法体内、门条件之外**（1.20.1 L198 / 1.21.1 L216 / 1.21.11 L211）。HEAD 取消会冻结 `tickCount` ⇒ `0 % interval == 0` 恒真、闸门自锁失效，并跳过尾部 `hurtMarked` 分支 |
| far-only 帧由 mixin 手工构造绝对 teleport 包，只发给远端观察者集合 | 取消 per-observer 分流，改**并集语义**：帧间隔取最近观察者的挡位间隔，任一观察者到期即整帧放行全体 | `ServerEntity.positionCodec` 的 base 是**每实体单例**，位置包是相对 base 的 1/4096 量化增量。给子集发相对包（服务端 base 推进、该客户端未收到）或补发绝对包（客户端 base 跳到服务端不知道的值）都会错位，且只有下一次绝对包能自愈。真 per-observer 帧率必须接管整条位置/旋转发送路径（自维护 per-observer lastSent），跨 7 段重写 vanilla 风险不可接受 |
| 预算 = 延迟投递队列（每 tick 上限，溢出顺延 1–2 tick） | `Connection.send` **只计数**（实测每玩家每 tick 实体包数），tick 起点喂纯逻辑压力控制器（快攻慢放 + 回差），倍率反压回间隔 | 丢相对包不可自愈（同上），而有界延迟队列又降不下持续速率；把压力反压到发送源头才既安全又有效 |
| 挡位距离 `[5,8,12,16]` 区块（绝对） | **固定比例挡位** `0.25 / 0.5 / 0.75 / 1.0 × 有效跟踪范围`，四挡间隔可配 | `EntityType$Builder` 默认 `clientTrackingRange=5`（80 格）、`updateInterval=3`，观察者判定用 `getEffectiveRange()`：绝对挡位对绝大多数实体不可达（牛/猪/村民只被追踪 80 格） |
| 物品流与生物共用 `entityTierInterval*` 四挡表 | 物品流（`ItemEntity` + `ExperienceOrb`）**独立一张表** `entityItemTierInterval*`（2/4/8/16），且**原版间隔下限对物品流取 1**（`EntityUpdateTiering.intervalFloor`） | `EntityType.ITEM` / `EXPERIENCE_ORB` 的 `updateInterval = 20` 是它们的**空闲/元数据**节拍，位置节拍实际由每 tick 被置位的 `hasImpulse` 驱动（1 刻）。取 `max(原版 20, 挡位)` ⇒ 物品恒 20 刻 = **1 包/s**、与距离无关，而客户端插值窗口只有 3 刻（`lerpTo(..., 3)`；1.21.11 `InterpolationHandler.DEFAULT_INTERPOLATION_STEPS = 3`），画面上就是「近距离掉落物闪现」。共用表 + 原样 `max` 会让整张档位表被 20 压平 |
| 密度 = 单一全局阈值 + 连续超量比 `1 + count/阈值`；压力 = 全服一条控制器，输入取**最忙连接** | 密度改**每档阈值 + 每档倍率**（`entityDensityTierCounts` / `entityDensityTierFactors`，逗号分隔两条键，支持小数、单步不叠加）；压力改**每观察者一份**账本（`EntityPressureBook`，每条已协商连接各自反压，取最近观察者那一份作用于实体） | 用户实测反馈两点：(1) 热点应能逐档调阈值/倍率；(2) 压力取全服最忙连接会把**远端热点连坐**到近处实体（实测窗口里 `40tx11800` 即此路径），与「谁超预算降谁的帧」直觉不符。密度本身是局部的（只数实体自己所在 chunk），无需改统计口径 |

### 8.2 落地清单

新增（`common/src/main/java/io/github/limuqy/mc/hassium/`）：
- `network/entity/EntityUpdatePacing.java` — 引擎接线：tick 快照、观察者扫描、间隔合成、门控与豁免
- `network/entity/EntityDensityIndex.java` — 每 tick「chunk → 该 chunk 内发生 sendChanges 的实体数」双缓冲索引
- `network/entity/EntityPacketCounters.java` — 每连接实体包实测（只计数，不改投递）
- `network/entity/EntityPressureBook.java` — 纯逻辑：**每观察者一份**的压力账本（独立反压 + 断连清理）
- `network/entity/EntityUpdateTiering.java` — 纯逻辑：挡位/每档热点阈值与倍率/间隔收口
- `network/entity/EntityFramePressure.java` — 纯逻辑：单观察者的压力控制器（EWMA + 快攻慢放 + 回差）
- `mixin/MixinServerEntity.java` — 两个字段读取的 redirect

改动：`MixinMinecraftServer`（tick 起点调用 + 停止清理）、`MixinConnection`（实体包计数 + 断连清理）、`hassium.mixins.json`（登记 `MixinServerEntity`）。

配置（**8 键**，`ConfigScope.SERVER` + `Domain.MASTER_CORE`，默认全开；CLIENT 无新键故不需 lang；分档表统一逗号分隔以压缩配置量）：`master.entityTieredUpdateEnabled`、`entityTierIntervals`（`"3,6,10,20"`，逗号分隔按 近/中/远/边缘，须非降序）、`entityItemTierIntervals`（`"2,4,8,16"`，物品流独立表）、`entityDensityThrottleEnabled`、`entityDensityTierCounts`（`"32,64,96,128"`，每档热点阈值）、`entityDensityTierFactors`（`"1.0,1.5,2.0,3.0"`，每档热点倍率，支持小数）、`entityMaxThrottleFactor`（4 = 密度×压力总上限）、`entityFrameBudgetPerPlayer`（128，0=关压力）。四条列表键的容错口径一致：单个元素写坏只回落该元素、元素个数不为 4 则整表回落默认、间隔 ≤ 0 视作未配置（间隔 0 会比原版还费）、倍率 < 1 夹到 1（只降速）。

**老配置自动补齐 + 键序规范化**：`FabricTomlConfigIO` 以前只在「文件不存在」时写出 toml，既有文件永不补键——于是新增键族在老 run 目录里根本不出现（用户会以为没实现）。现在加载后比对 `ConfigSchema`：**缺键或键序与 schema 不一致**时，从空表按 schema 顺序**整表重写**一次（值取自本次读到的快照，用户改过的值原样保留，只有缺键补成默认值），并打一条 INFO。只补键是不够的——在已加载的表上逐键插入会把新键插进旧键之间，文件看着「散落一地」（2026-09-15 用户实测）。实测：`fabric/run/server/config/hassium/hassium-server.toml` 原先停在 7 月的 13 键，现在补齐且按 schema 顺序排列（6325 字节，`[master]` 段自 `enabled` 起顺序与 schema 一致）。

**密度与压力的分工（两张闸，别混）**：
- **密度**（局部）：只数「实体**自己所在 chunk** 内本 tick 有实体包的实体数」，与该玩家视距/加载范围无关；逐档配置阈值与倍率（`count ≥ counts[tier]` ⇒ `× factors[tier]`，倍率可为小数，单步不叠加）。⇒ 十几区块外的热点不影响近处实体：近处实体只要自己 chunk 未达该档阈值，密度倍率恒 1。
- **压力**（每观察者独立）：每条**游戏态连接**一份账本（含原版客户端——引擎对全体玩家生效），输入是该连接**自己**当帧的实体包数 ÷ `entityFrameBudgetPerPlayer`；EWMA 比值 > 1.25 升挡（×2，上限 `entityMaxThrottleFactor`），< 0.5 且连续 20 次采样才降 1 挡（回差防抖）。⇒ 一个玩家身边的热点不再连累其它玩家。
- 单实体帧率取「**最近观察者**的距离档 + **最近观察者**的压力档」（并集语义的必然结果，见 §8.1）；两者相乘后仍受 `entityMaxThrottleFactor` 收口。玩家→连接走 `Connection.getPacketListener() → ServerGamePacketListenerImpl.player`（三版皆 public，不新增 mixin）。

**服务端行为不变的证据（用户关心的「漏斗吸不到掉落物」面）**：本族只改 `ServerEntity.sendChanges()`（唯一调用点 `ChunkMap.TrackedEntity.updatePlayer`）是否构造并广播 `Clientbound*` 包与它自己的 base/lastSent 记账；实体 tick（`ServerLevel.tickNonPassenger` → `ItemEntity.tick` 物理与 `age`）、拾取、漏斗判定（`HopperBlockEntity.getItemsAtAndAbove` 走 `level.getEntitiesOfClass(ItemEntity.class, ...)` 的服务端实体列表）都读不到本族状态。即「只改推送给客户端的频率，不改服务端实体频率」。

### 8.3 门控与豁免（安全边界）

- **不要求客户端握手**（2026-09-16 修正）：实体域只改 vanilla 复制节拍，原版客户端完整兼容，
  门控只有「**主服务器实例** + `ServerNetworkGate.isNetworkServerActive()`（专用服 `master.enabled` /
  LAN `enabledOnLan`）+ entity* 配置」；**不再**要求 `HassiumConnectionRegistry.activeCount() > 0`。
  压力输入同步改为采样全部游戏态连接（`EntityPacketCounters.drainGameInto`）。
- 客户端进程里的影子端世界 tick 时直接早退，不改共享状态。
- 玩家实体（`ServerPlayer`）豁免（`PLAYER.updateInterval=2`，他人视角的玩家位移不可降帧）。
- `vanillaInterval > 40` 的实体（如 ItemFrame 的 `Integer.MAX_VALUE`）原样透传，避免把「原版几乎不发」变成「按我们的间隔发」。
- 观察者判定**故意取宽**（乘客跟踪范围取较大者、跟随 `entity-broadcast-range-percentage`）：多算观察者只会多发帧（退回原版），少算才会欠帧。

### 8.4 效果边界（写清天花板）

- **物品流（掉落物/经验球）**：原版位置节拍 = 每 tick（`hasImpulse` 驱动，≈20 包/s/个）。压掉冲量后改由**物品流档位表**决定：近挡 2 刻（10 包/s，仍在客户端 3 刻插值窗口内 ⇒ 视觉连续）、中 4 / 远 8 / 边缘 16 刻。**注意这与首版行为不同**：首版误把原版 20 刻当下限取 `max`，导致物品恒 1 包/s（即「近距离掉落物闪现」），见 §8.1 末行与 §8.5 实测。
- **生物**（原版间隔 3）：近挡仍 3（不降），中/远/边缘挡 6/10/20；热点 chunk 按**该档**阈值/倍率再放大（默认 32/64/96/128 个活跃实体触发，对应 ×1.0/1.5/2.0/3.0），总收口 40 tick。
- 原版间隔 4–10 刻的实体（`primed_tnt`=10、`eye_of_ender`=4）**保持原版下限**：它们仍被原版间隔压住，本轮不动、行为与本轮之前一致。
- **多观察者时远端拿不到独立帧率**：帧率 ≈ 最近观察者挡位（并集语义的必然结果，见 §8.1）。压力同理取最近观察者那一份。要突破必须接管发送路径。
- **热点倍率是单步的**：`count ≥ counts[tier]` ⇒ `× factors[tier]`，没有「超量比连续增长」；要更狠就把该档倍率调大（受 `entityMaxThrottleFactor` 收口）。

### 8.5 验证

| 载体 | 范围 | 结果 |
|---|---|---|
| **运行时实测（真实服务端 + 真实客户端 + 真实掉落物）** | 1.20.1 fabric，`runServer`+`runClient`（冒烟客户端自动连服），控制台 `/execute at @a run summon minecraft:item` 投放掉落物，临时探针每 100 tick 打印物品流实际生效间隔 vs 「沿用原版 20 刻下限」的反事实 | 连续窗口 `itemDecisions=16800, actual=2tx100 8tx500 16tx4400 40tx11800, legacyFloor20=20tx5000 40tx11800` ⇒ **近身掉落物每 tick 一次判定、稳定落在 2 刻档**；同一批判定在旧取法下全部落 20 刻（1 包/s）。即「近距离掉落物闪现」的直接复现与修复确认 |
| `common:test`（L0） | 1.20.1 / 1.21.1（每轮修订冻结后复跑） | 全绿（304 项）。纯逻辑覆盖：`EntityUpdateTieringTest`(13，挡位/物品表回落/物品下限/每档热点表/两条列表键解析/小数倍率取整)、`EntityPressureBookTest`(5，**观察者互不影响**/升档上限/回差退档/断连清理/零配额)、`EntityFramePressureTest`(7)；`ConfigRestructureRoundTripTest` 键数断言 43→52→56→57 同步 |
| `common:compileJava` | 7 锚点段（1.20.1/1.21.1/1.21.2/1.21.5/1.21.6/1.21.9/1.21.11） | 全部 exit=0 |
| `minecraft_dev_analyze_mixin` | 7 段静态校验 | 全部 `isValid: true`（无 error/warning） |
| **运行时冒烟 L1 classic（父修订：三件套主体，全矩阵）** | fabric × 12 版、forge × 10 版、neoforge × 11 版 | `=== RESULT: PASS ===` 全部 33 场；服务端日志出现 `Hassium: entity update pacing active (tiered=true, density=true, budgetPerPlayer=64, maxFactor=4)`。注意：2026-09-16 起门控已去掉握手依赖，master 总闸开且 entity* 配置开即 active，**不再**随 Hassium 客户端断连→重连 inactive→active |
| **运行时冒烟 L1 classic（物品流档位修订，3 锚点）** | 1.20.1 fabric / 1.21.1 neoforge / 1.21.11 neoforge | 3/3 `PASS`（SessionId `*_entityitem`） |
| **运行时冒烟 L1 classic（热点分档 + 每观察者反压修订，3 锚点）** | 1.20.1 fabric / 1.21.1 neoforge / 1.21.11 neoforge | 3/3 `PASS`（SessionId `*_entitydensity`）。两轮修订均不触碰版本区段（无新增 `#if MC_VER`、不涉 `PacketId`/`Identifier`/AT/AW），按仓库惯例只回三锚点；服务端 toml 生成路径由 `ConfigRestructureRoundTripTest` 的 `saveServer/loadServer` 往返断言覆盖（新增键用非默认值断言）。**未自动验证的部分**：「远端热点不连坐」的性质由 `EntityPressureBookTest`（观察者隔离）+ 每实体取最近观察者那一份的结构保证；双客户端远/近热点对比未做（需两台客户端 + 可复现热点），留作 L3 |

**（父修订全矩阵期间）唯一一次 FAIL 已定位为环境陈旧数据，与改动无关**：`1.21.6 fabric` 首跑命中「严重错误门控」——`Failed to parse saved data for 'SavedDataType[random_sequences]': No key salt`。该错误是**读取**复用存档 `fabric/run/server/parity_fabric_1_21_6/data/random_sequences.dat` 时抛出的（文件 mtime 2026-09-12 23:41，早于本次改动），而 1.21.1–1.21.11 全段 `RandomSequences.codec` 都要求 `salt`（`Codec.INT.fieldOf("salt")`），即该文件不可能由当前版本矩阵内任一版本写出；本轮不触碰任何存档数据路径。加 `-CleanWorld` 重跑同一组合 → `PASS`。后续若其它复用存档再报同类错误，同样是存档残留，不是回归。

### 8.6 遗留与后续

1. **人眼验证仍待用户**：物品流近档已是 2 刻（视觉连续），但**压力倍率介入时物品最高被收口到 40 tick/帧**（实测窗口里 `40tx11800` 占多数——该测试世界里约 50 个掉落物 + 常规生物已超 `budgetPerPlayer`（现值 128），控制器按设计顶到倍率上限）。若要物品在任何负载下都保持近档手感，可：调大 `entityFrameBudgetPerPlayer`、调小 `entityMaxThrottleFactor`，或（需改代码）让物品流不吃压力倍率。
2. **出生/移除包风暴未覆盖**（§7 议题 1）：`mergeWithNeighbours` 级联合并产生的 `add_entity`/`remove_entities` churn 不在本族范围内，降帧只管 update 帧。
3. **per-observer 精确帧率**为并集语义的天花板所限，若要突破需接管 `ServerEntity` 的位置/旋转发送路径（含 per-observer lastSent），风险与收益需单独立项评估。
4. 真实存档的观察者-实体距离分布、`entityFrameBudgetPerPlayer` 的合理默认值仍属拍脑袋值（§7 议题 2/3），需实测校准。

---

## 9. 实体错峰推送（2026-09-16）

### 9.1 需求澄清

用户要的「平滑推送」是**时间摊平**，不是源头降速：

| | 齐发（vanilla） | 错峰（本节） | 压力反压（§8） |
|--|----------------|-------------|---------------|
| interval=3 时 tick 0 | 100 个实体全发 | ~33 个发 | 可能更少（总量也砍） |
| tick 1 / 2 | 全静默 | 各 ~33 个发 | — |
| **3 tick 总量** | 100 | **100（不变）** | < 100 |

压力反压与错峰正交叠加：错峰管分布，压力管总量上限。

### 9.2 机制

```
门条件: tickCount % interval == 0
      → (tickCount + phase) % interval == 0
phase  = floorMod(entity.getUUID().hashCode(), interval)
```

- 任意连续 `interval` 个 tick 内每实体仍只发一次（总量不变）
- 不同实体按 UUID 稳定落在不同刻，齐发尖峰被摊平
- **无队列、无延迟积压**——只是相位差
- 首包仍走 `addPairing` 绝对包，不受影响

### 9.3 实施

- `EntityUpdateTiering.phaseOffset(hash, interval)` — 纯函数
- `EntityUpdatePacing.staggeredTickCount` — 门条件 tickCount 替换；先算生效间隔并缓存到 SCRATCH，
  供紧随其后的 `updateInterval` redirect 复用（避免密度索引 double observe）
- `MixinServerEntity` 新增 `tickCount` GETFIELD `ordinal=0` redirect（门条件第一处；
  方法内后面的 `% 60` 绝对包与 `> 0` 首帧判定保持原版）
- 配置：`master.entitySmoothPushEnabled`（默认 true；关掉退回齐发）
- 顺带修 P1：压力读取不再绑死 `entityTieredUpdateEnabled`——关掉距离分层时，
  密度 + `entityFrameBudgetPerPlayer` 压力仍独立生效（`computeEffectiveInterval` 在
  `tiered || budget > 0` 时都做最近观察者扫描）

### 9.4 与既有门控的关系

```
最终间隔 = max(原版下限, 距离档) × 密度 × 压力   ← §1/§2/§8，管总量
发送时刻 = (tickCount + uuidHash % 间隔) % 间隔   ← 本节，管分布
```

玩家豁免 / 无 Hassium 客户端**不影响启用**（vanilla 兼容，只跟 master 总闸）/ 影子端不参与 / `hasImpulse` 已被 gate 压住，
与既有安全边界一致；`isDirty()` 元数据仍直通。
