# 规划：实体网络优化三件套（逐级降帧 / 热点降帧 / 实体平滑推送）

状态：**规划评估**（未实施；评估结论见 §5）
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
Phase D：实测校准（真实大型生电存档，用户自备；验证指标见 §6）
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
3. 每玩家每 tick 实体包预算的合理默认值（64 是拍脑袋值）
4. 掉落物远端降帧的视觉阈值（物品流视觉平滑度要求低，末挡 interval 可否比生物更激进，如 40 tick/帧）
