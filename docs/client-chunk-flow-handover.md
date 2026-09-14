# 交接文档：客户端区块数据流对齐（统一 Compare+Pull）

> 状态基线：`feature/vanilla-direct-network` @ `109ba1b`（2026-09-05）。
> 2026-09-09 追加 §0.5：P2 推送抑制代码核对完成态回填；§6 探针「无基线 FULL」改读 `newFullChunkRequestCount`。
> **2026-09-09 空区块占位方案**：§0.2 提到的悬置 future 机制已被替换——
> `scheduleChunkLoad` 对未注入柱返回空 ProtoChunk（status=FULL，与 vanilla
> `createEmptyChunk` 同款），原版链立即完成推进到 `playerLoadedChunk`，
> pull 独立异步进行。悬置 future 会阻塞 vanilla 选柱链（ChunkMap 等待 future
> 完成才继续选新柱），导致移动后新区块不加载。详见 [`architecture.md`](architecture.md) §6.1。
> 目标真相源：[`architecture.md`](architecture.md) §6「客户端区块数据流」（**已标「已对齐（现状）」**）。
> 本文回答三个问题：**哪些已完成不许再动**、**现行过渡链路有哪些（对齐后要清理什么）**、**对齐开发怎么做、怎么验收**。

## 0. 2026-09-05 追加：R1 半径 / R2 虚空定位与修复（pull8 实证）

pull8 冒烟（`vdn_1_20_1_fabric_I_pull8`）双端日志定位出两个与 §3 清单无关的根因：

- **R1 只有 836 柱（vs 旧推送 1529）**：1.20.1 `placeNewPlayer` 把 viewDistance 只放在
  `ClientboundLoginPacket.chunkRadius`，join 时不发 `SetChunkCacheRadius` →
  `ShadowTrackingSession.serverViewDistance` 恒 -1 → 影子 tracking 半径回落默认 11 →
  选柱集合 836（客户端日志 `creating virtual player (serverRadius=-1)`、服务端 8 请求 = 836 条目）。
  **不是吞吐问题**：836 请求在 1s 内全部发出并在窗口内全部应用。
  **修复**：`MixinClientPacketListener.hassium$onLogin` 在 `onLogin()`（会话 reset）之后补
  `setServerViewDistance(packet.chunkRadius())`（1.20.1 与 1.21.1+ 登录包都带 chunkRadius，零 `#if`）；
  半径晚到/变化由影子主循环 `applyViewDistanceIfChanged` 补应用（`ChunkMap.setViewDistance`
  自带全 holder 重跟踪）。
- **R2 零请求（虚空）**：R1 worldgen 压制柱的悬置 load future 永不完成 → ChunkMap holder
  永卡 EMPTY（数据只进注入表）。R2 新虚拟玩家对这批柱：holder 已存在 → 无新
  `scheduleChunkLoad`；`getTickingChunk()` 为 null → move 的 `updateChunkTracking` 不触发
  `playerLoadedChunk` 桥 → 比对请求永不产生（服务端 R2 期间零 `[SHADOW_PULL] server request`）。
  **修复**：`ShadowChunkMapCompat` 悬置 future 登记 + `completeSuspendedLoad`——
  `ShadowSeedServer.injectChunk` / `injectLoadedChunk` 数据到位时以 Imposter 放行，
  原版链恢复 LIGHT→FULL 推进，R2 靠 move 转换重新触达桥（UNCHANGED → `publishCachedChunk`
  重交付 / FULL → 覆盖）；park 时 `clearSuspendedLoads`。
- **加固**：`ShadowTrackingSession.reset()` 的 vanilla 移除改为影子主循环线程执行
  （此前在客户端主线程直呼 `PlayerList.remove`，与影子簿记并发可自毁主循环）。
- **待推送队列（2026-09-05 用户拍板）**：`resolveShadowPull` 对未就绪柱（生成/算光中）
  不再阻塞主线程也不报错——登记 `ServerChunkPushManager.pendingPulls` 待推送队列，
  入队即按需加载（`TicketType.FORCED` FULL 票，引用计数，推送/失败后释放），
  `onServerTick` 泵就绪即主动推送（复用原 requestId，单 tick ≤128）；仅在柱已不在
  加载列表（vanilla 卸载/跑出视距）或超时（15s）才回失败，客户端对 ERROR **不重试**
  （终态拒绝重试只会复现，pull9 风暴实证）。范围校验改逐条目
  （`ShadowPullRequestValidator.inRange`），整批拒绝会牵连同批在范围内柱。
- **冒烟进展（pull8→pull12，1.20.1 fabric classic）**：R1 landed 836→1208→1268
  （目标 1529）、R2 489/489 全缓存重交付（新增 0、push 0，虚空修复）、全程无风暴。

## 0.1 2026-09-07 追加：R1 剩余缺口的实测归因与半径补环（bootgrid 系列实证）

对 pull12 起的 1268 vs 1529 残差的几何活检（`.Probe.Round1` 探针 + 空间快照）推翻早前
「range 拒绝为主」的判断——连续三轮（pull12 基线 / bootgrid1 / bootgrid2）得到**完全相同**的
轮廓特征，非随机噪声：

- **根因 = 飞行滞环（motion-tail hemisphere），非 range 拒绝**。脚印 = r≤14 chebyshev
  实心方阵（841）+ r15…r22 各环仅 ~36–47% 的半圆弧，空缺集中在下行方向的**身后一侧**；
  r22 之外为零。老推送链路靠服务端宽幅自主 push 兜住了整个圆周，新 PULL 驱动的选择窗口
  跟随移动中心，身后环来不及供给就在会战尾声被判负。
- **半径协调补环（本轮落地，全部编译绿、两轮 smoke PASS）**：
  1. 新增 `common/.../network/ShadowPullRadii.java`：`AUTHORITY_MARGIN=2` 单一真相源；
  2. 五处 loader validator `PlayerCompat.getViewDistance(player) + AUTHORITY_MARGIN`
     （fabric 两分支 / forge / neoforge）与 `ShadowTrackingSession#resolveViewDistance`
     同步 `+AUTHORITY_MARGIN`（shadow 选择窗口 ⊇ 服务端签发环）；
  3. `ShadowTrackingSession` 增设**静态基准光盘**：虚拟玩家坐下瞬间快照 homeChunk，
     以其为心把欧氏半径 r=22 的整圆柱逐环补齐（南北交替混合序 + 25ms 发射闸防洪峰），
     绕过移动窗口不对称：不进影子注入表的格子一律以无基线/FULL 形式进场。
- **净效应（2026-09-08 二次修正，bootgrid4 根治）**：早期「landed 1268→1384，
  残差 ~180 柱」的归因链条被两级修正：
  1. **口径问题**（bootgrid3 对账发现，2026-09-09 二次澄清）：`clientLandedChunkCount`
     是 position 去重的唯一落地数，**经 `applyReadyChunk` 已含 cacheHit 重交付**；
     `getClientAppliedChunkCount()` 才是来源事件和（fullReq+cacheHit+…），可因跨源
     同一坐标重复而 **大于** landed（shape4 R1：applied 2278 > landed 1635，差额 643
     为网络 FULL 与 cacheHit 双计）。R2 探针实证 cache 回放进 applied trace
     （R2: recv=0, applied=533=cacheHit 531+delta 2）。故 **landed+cacheHit 不可相加**；
     读数一律用 `landedTotal`（= landed，含 cacheHit）。
  2. **发射 bug 根治**（bootgrid4 实证）：`drainBootGrid` 的 `bootGridArmed=false` 写在
     **prime 填充块内部**（354 行），导致整张 1517 格光盘只在首轮发射了 ≤128 格
     （最内环），随后 `!bootGridArmed` 直接 return，**外环（r15–22 西弧/南北滞环）
     从未被请求**——这是 o 区 352 柱（老推送有/新 trace 无）的真实根因，与口径无关。
     修复：解除武装移到发射循环掏空之后（`bootGridCells.isEmpty()` 时）。
  3. **bootgrid4 结果（修复后，1.20.1 fabric classic PASS）**：R1 applied **1879** 柱
     （≫ 老推送基线 1529），老形状 cheb21@(1,0) ∩ euclid²≤490@(1,0) 覆盖 **1508/1529
     （98.6%）**，独缺 SE 楔形 21 柱（E:5+S:16，跨 bootgrid3/4 完全一致 = 系统性格局：
     盘心 (0,0) vs 老推送中心 (1,0) 的一格偏移所致，量级 ≤1.4%，可不追）；另加 371 柱
     bonus（东移窗口 cheb18@(4,0) + 盘面条纹）。R2 565 全缓存重交付（newFull=0, push=0）。
     服务端零 stall 零 ERROR 零超时；385 个 range 拒绝全在 cheb≥22 界外或为已送达柱的
     重复请求（44 个闸内侧拒绝柱全部另行 applied，无害）。
- **追溯结论**：bootgrid1 的 1384 与 bootgrid2 的 1270 都是在光盘被腰斩状态下
  （各轮只发首批 128 + 窗口贡献）测得的，「净效应」数字只有窗口贡献可比；光盘本体
  直到 bootgrid4 才真正全额射出。`repairPool` 预留位维持不接线（bootGridCells 全额
  发射已覆盖回充语义，无风暴）。
- **遗留（低优先级）**：① ~~`clientLandedChunkCount` 口径偏窄（不含 cacheHit 重交付），
  建议并入或单列 `landedTotal`~~ **已修（2026-09-09）**：实测该断言过时——`clientLandedChunkCount`
  （position 去重 AtomicLong）经 `applyReadyChunk → recordChunkApplied` **已含** cacheHit
  重交付（shape4 R2：landed=533=cacheHit 531+delta 2）；真正问题是与
  `getClientAppliedChunkCount()`（来源事件和，可含跨源同一坐标重复，R1 2278>landed 1635）
  混淆。现单列 `landedTotal`（probe/ScenarioEngine）并文档化「不得 landed+cacheHit 相加」。
  ② 老推送中心 (1,0) 相对 homeChunk 的一格 SE 偏移若需 100% 形状重合，可从 vanilla
  ChunkMap 追踪中心推导后对齐光盘中心；③ aggregation splits 24 次（启动高峰）属正常。

## 0.2 2026-09-09 追加：bootgrid5 复盘——OVD 证伪、影子端加载越界与光盘欧氏盲区

bootgrid5（1.20.1 fabric classic，R1 PASS / R2 PASS）的探针对账推翻了「extra 121 柱 +
range 拒绝 215 柱来自 OVD 超视渲染」的早期猜测。OVD（超视渲染）在当前链路**不启用**
（`chunk-cache.md` §10：影子端原版化后 admission/加载/卸载/推送全部由影子
`ServerChunkCache/ChunkMap` 管理，旧 `renderOnly` 环带逻辑不属于当前链路；
§10.2 明文「不向服务端请求影子玩家 tracking 范围之外的区块」），
`chunk.maxRenderDistance=16` 是退役代码的配置残留，与 VD20 场景的真实行为无关。

- **R1 观测 1640 柱的真实构成**：vanilla VD20 形状（`isChunkInRange` 圆角方形，
  1529 柱）内 1519（覆盖 99.3%，缺 10）+ 影子端方形加载交付 121。
  121 柱全部落在 `chebyshev≤22 方形 − vd20 形状` 的四角区（57/58 与该集合精确吻合，
  57 柱 bg4 存档命中）：影子端 `setChunkViewDistance(22)` 后 vanilla ticket 加载范围是
  **chebyshev 方形**（比 `isChunkInRange` 圆角方形更"方"），方形角柱被 ticket 加载 →
  读盘命中 → `playerLoadedChunk` 物化桥 → compare-pull 交付。**不是 OVD**。
- **range 拒绝 215 柱（R1）+ 44 柱（R2）的真实来源**：影子端 chunk 系统的加载范围
  **超出服务端校验范围**。影子 ticket 加载 chebyshev ≤ vd+1（R1: 23），
  光照计算邻域 ticket 外扩至最深 cheb 26（R1 实测拒绝柱分布 23:56 / 24:58 / 25:60 /
  26:41，全部 17:41:05 一秒内成串拒绝）；这些柱经 `scheduleChunkLoad` →
  `hassium$shadowSuppressGeneration` → `onChunkSelected` 悬置 → `drainSelections`
  批量 auth-full pull → 服务端 `ShadowPullRequestValidator` 按
  `maxDistance = vd + AUTHORITY_MARGIN = 22` 全拒。R2 同构验证：vd=10 时被拒柱
  恰好是 cheb=13 整齐一环（44 柱），与 `maxDistance=12` 完全对应。
  **`ShadowPullRadii` 的「window ⊇ request」契约被影子端加载范围违反**——
  违反方向与设计预期相反：不是客户端窗口太小，而是影子端 chunk 系统
  （ticket vd+1 + 光照邻域）比服务端校验（vd+2）更深。
- **vd20 形状内缺 10 柱的根因 = 光盘欧氏裁角盲区**：缺失柱
  `(-24,7) (-20,14) (-17,17) (-10,21) (4,21) (11,17) (14,-14) (14,14) (18,-7) (18,7)`
  全部欧氏 d²=485/490（> r22²=484），在 `enumerateDiscBiased` 的
  `dr*dr+dc*dc > radius*radius` 裁角线外 1–2 格，却在 `isChunkInRange(20)` 形状内
  ——vanilla 形状比欧氏 r22 盘「方」，bootGrid 从未请求它们。
  修复方向：bootGrid 遍历改 chebyshev 序或欧氏半径 +1（`radius+1` 平方裁角）。
- **bonus 缩水（bg4 380 → bg5 121，vd20 外）**：bg4 的东移窗口 cheb18@(4,0) bonus
  在 bg5 未复现，与 17:35 `ShadowTrackingSession` 改动后 bootGrid 发射行为变化相关；
  R1 目标形状覆盖不受影响（99.3% > bg4 的 98.0%），不追。
- **R2**：565 全缓存重交付（newFull=0, push=0），与 bg4 一致。
- **跟进项**：① 影子端加载越界（ticket vd+1 = 23 实测；cheb 24–26 深度按光照计算
  邻域 ticket 归因，机制推断）对 auth-full 的无效请求——服务端按 range 拒绝后
  客户端 `RETRIED` 防重放，无风暴但白耗校验；若收敛，可在 `onChunkSelected`/
  `drainSelections` 侧按服务端 `maxDistance` 预过滤。② bootGrid 欧氏盲区 10 柱
  （上条修复方向）。③ `repairPool` 仍只声明未接线（`ShadowTrackingSession:81`，
  bootgrid4 起维持不接线决策）。

### 0.3 2026-09-09 追加：三层形状对齐（跟进项 ①② 落地）

**方案**：形状判定收口原版 API，禁止业务自绘几何——

- 新增 `compat/ChunkShapeCompat.contains(cx,cz,range,x,z)`：1.20.1 走
  `ChunkMap.isChunkInRange`（public static，玩家 tracking 同款），1.21.1+ 走
  `ChunkTrackingView.of().contains()`（`#if MC_VER < MC_1_21_1` 分流；两者公式同族）。
- `enumerateDiscBiased` 欧氏裁角 → 原版圆角方形谓词；**radius = vd+1**（非 vd+2）：
  vanilla 形状 range r 含 cheb≤r+1 角区，r=vd+1 恰覆盖服务端签发域 cheb≤vd+2
  且零越界（r=vd+2 会多出 60 个 cheb=vd+3 角区柱被 RANGE 拒，`_shape` 冒烟实证）。
- `drainSelections` 按服务端 `maxDistance`（cheb vd+2）预过滤 + 已物化跳过：
  ticket 光照外扩柱（cheb 23–26）不再发出必拒请求。

**冒烟对照（1.20.1 fabric classic，`_shape2` vs bootgrid5）**：

| 指标 | bootgrid5 | shape2 |
|---|---|---|
| R1 landed | 1640（vd20 内 1519） | 1742（vd20 内 1521） |
| vd20 形状覆盖 | 99.3%（缺 10 盲区柱） | **99.5%（缺 8 盘尾瞬态柱）** |
| R1 range 拒绝 | 215 | **0** |
| R2 range 拒绝 | 44 | 44（视距切换瞬态：R1 尾批在 R2 maxDistance=12 下应答，非预过滤漏） |
- **移动语义核实（shape3 补验）**：拒绝非黑名单——`RETRIED` 只挡 retry 路径，
  `pendingSelections` 无去重，服务端校验中心逐请求取真实玩家 `chunkPosition()`；
  玩家移动后 ticket 重新选中被拒柱即可签发。据此发现并修复预过滤中心 bug：
  初版用 `homeChunk`（落座快照），玩家移动超 vd+2 后会把新区域柱全部错杀——
  已改为虚拟玩家实时位置（与服务端中心对齐，+2 边距吸收一拍延迟）。
  `homeChunk` 保留作 bootGrid 静态盘锚点（设计意图不变）。
- **shape3 冒烟（中心修复后）**：R1 落点 (-2,-1) 处 vd20 形状 **1529/1529 全覆盖**、
  R1 range 拒 0；R2 44 拒仍为视距切换瞬态（R1 尾批在 R2 maxDistance=12 下应答）。
  跟进项 ③ `repairPool` 不接线决策维持。

### 0.4 2026-09-09 追加：pull 域收口原版可见形状（光照邻域外扩退役，shape4）

影子端首次加载成本曾比原版客户端多一圈（vd20 冷启动 2025 柱 vs 1529，+32%）：外扩柱唯一
用途是给可见边缘柱当光照传播邻域。本轮收口为「最外层可见柱边缘光先错后自愈」：

- `drainSelections` 预过滤改 `ChunkShapeCompat.contains(虚拟玩家实时位, vd+1, sel)`——
  预过滤形状 = 原版玩家 tracking 圆角方形（轴深 vd+2 = 签发域上界，⊆ 服务端校验域），
  光照邻域方形外圈不再拉取。
- `resolveViewDistance` 从 vd+2 收到 **vd+1**（原版 `ChunkMap.setViewDistance` 玩家
  tracking 半径同款）；`drainBootGrid` radius 同步为 `resolveViewDistance()` 无 `-1`
  （几何不变：r=vd+1 覆盖 cheb≤vd+2 签发域且零越界）。
- 服务端 `ShadowPullRadii.AUTHORITY_MARGIN=2` 不动（校验上界恰好覆盖新形状轴向边缘）。
- **自愈依据**：光屏障从不死等邻柱（`NEIGHBOR_PACK_WAIT_MS` 系死代码已删）；邻柱后到
  → 引擎跨边界传播 → `MixinServerChunkCache.onLightUpdate` → `collectLightUpdate` →
  LightDelta 回传修正客户端。未收敛柱 `persistPartialLight` 强制 `isLightCorrect=false`
  （NBT 不写 `isLightOn`），重连必重算，错光不固化。LIGHT_ONLY 补光不进
  `lightCacheHit/Miss` 分母。
- **shape4 冒烟（1.20.1 fabric classic，两轮 PASS）**：R1 applied 1635（bg5 为 1640），
  玩家中心 vd20 形状 **1529/1529 全覆盖**（missingVisible=0），越形状柱仅 106
  （bootGrid 西侧尾部闭环，**已被 2026-09-09 收口取消**，见下），R1 range 拒绝 **0**，光照 ERROR 0。
  冷启动注入/算光/落盘柱数与原版持平；首载拉满耗时按 80 柱/s 折算约省 6s（vd20）。
- **代价**：最外圈可见柱（~200 柱）边缘光在邻柱到位前不准（屋檐/洞口/邻柱火把光的
  跨界传播缺失；天光垂直分量不受影响），移动触发 LightDelta 自愈，重连重算。
  同步删除死代码：`NEIGHBOR_PACK_WAIT_MS`/`packWaitStartMs`、
  `hasInitializeLightParent`（compat + server 两处，原版 LIGHT future 自管邻柱依赖）。

### 0.4.1 2026-09-09 追加：pull 域严格对齐原版可见形状（bootGrid/excess 收口）

shape4 后 R1 仍 ~1633（原版 `isChunkInRange(20)` = **1529**），+104 越形状主要来自
pull 域用了 `resolveViewDistance()=vd+1`（range=21 → 1665 格盘）而非通告视距。按
「影子端只拉用户能看到的、边缘光邻域齐全后自愈」收口：

- **统一谓词** `inVanillaVisibleShape(x,z)`：`ChunkShapeCompat.contains(实时中心, serverViewDistance, …)`
  （VD20 → 1529）；半径未知时放行交给服务端校验。
- **bootGrid**：盘半径 `resolveViewDistance()` → **`serverViewDistance`**（不再铺 authority 边距圈）。
- **drainSelections**：预过滤 `vd+1` → **`vd`**。
- **onChunkMaterialized**：影子 ticket 物化的越形状角区柱仍注入影子表（算光邻域），
  **不再**向真实客户端 compare-pull。
- **保留**：`setChunkViewDistance(vd+1)` 影子内部 tracking 略宽，仅服务算光；不进真实客户端落地集。
- **预期**：R1 `landedTotal` 从 ~1633 收到贴近 **1529**（允许极少数瞬态/重复路径差）。

### 0.5 2026-09-09 追加：P2 推送抑制代码核对（对齐波完成态回填）

对照代码核对 §4 P2 与 §5 清理清单的实际落地（非冒烟，纯静态）：

- **P2 已完整落地（此前文档未单独勾账）**——协商键 `LoginCaps.PULL_MODE`，三层抑制：
  1. **1.20.1 源头拦截**：`MixinServerPlayer.hassium$onTrackChunk` pull 模式 `ci.cancel()`
     （原版 tracking 首包不外发）；
  2. **1.21.1+ 源头拦截**：`MixinPlayerChunkSender.hassium$onChunkPacketSend` pull 模式
     直接 `return`，不转推送队列（1.20.1 该 mixin 为空壳）；
  3. **队列双保险**：`ServerChunkPushManager.enqueuePushTask` 对
     `FULL_VISIBLE && isPullMode(player)` 拒绝入队。
  兼容路径保留：非 pull 模式仍走 `enqueueDirectPush` → `ChunkSender.sendCompressedChunk`。
- **P1 空基线路径与 T2 旁路的关系**：R1 空基线请求走
  `ShadowTrackingSession.emitPullGroups` → `requestAuthoritativeFull`，**不经**
  `tryInterceptForCompare` 的 `hasLocalPullBaseline` 旁路。旁路只影响「仍会收到
  vanilla 包」的场景（兼容期 / 非 pull 客户端）；对齐后 pull-mode 玩家整柱 vanilla
  包已在服务端源头掐断，旁路实际触达面收窄。
- **`architecture.md` §6 已标「已对齐（现状）」**（含 PULL_MODE 协商与 pull FULL zstd
  叙述），§5 清理清单「文档改标现状」一项在架构侧已完成；本文档仍待对齐收尾后归档。
- **§5 清理 6 项均未执行**（符合兼容期红线）：ChunkSender/推送段/`CHUNK_PAYLOAD_S2C`
  三 loader 注册、`handleCompressedChunk`/`ChunkCompressionHandler`、基线旁路、统计双轨
  全部存活。`SeedGenExecutor` 本地生成仍复用 `handleCompressedChunk` 解压/应用链——
  删通道前需先评估该本地路径迁出。
- **`fullChunkRequestCount` 语义收口结论（不改指标代码）**：`recordFullChunkRequests`
  已按 `staleOrFallback` 拆出 `newFullChunkRequestCount` / `staleFullChunkRequestCount`
  （probe 与 ScenarioEngine 均已暴露）；§6 探针断言「无基线 FULL」应对齐读
  **`newFullChunkRequestCount`**，而非宽口径 `fullChunkRequestCount`（= new+stale）。
  pull FULL 经 `accountAuthoritativeLanded` 落地时默认记 new（compare-stale 的 FULL
  与空基线 FULL 在该分量暂不可分，量级被 R2 UNCHANGED/DELTA 分流后通常可忽略）。
- **`landedTotal` 口径（2026-09-09 落地）**：单列 `getLandedTotalCount()` /
  probe `stats.landedTotal` / ScenarioEngine `stats.landedTotal`，与
  `clientLandedChunkCount` 同值（position 去重唯一落地，含 cacheHit 重交付）。
  shape4 实证：R1 landed 1635 vs applied 来源和 2278（跨源双计 643），R2
  landed 533 = cacheHit 531 + delta 2；`landed+cacheHit` 不得相加。

## 1. 背景与结论速览

冒烟实证（`vdn_1_20_1_fabric_I_final`，1.20.1 fabric classic）：R1 首进时 1529 个区块全部走 **chunk_payload 服务端推送**，shadow pull 零触发；R2 重连（有缓存基线）才出现 436 UNCHANGED + 9 DELTA。这与 §6 的目标态不符——**按文档，无基线的柱也必须进入统一 Compare+Pull（服务端答 FULL）**，实现却在无基线时放行推送包（见 §3.2 门控）。

- 握手、压缩、统计、shadow pull 服务端权威比较等**已完成**（§2），对齐开发不得回退或重构这些。
- 过渡链路**清单化**于 §3，对齐完成后按 §5 清理。
- 开发按 §4 阶段推进；每阶段验收以 §6 门禁为准。

## 2. 已完成项（对齐开发的前置依赖，不许再动）

| 模块 | 状态 | 关键位置 / 提交 |
|------|------|----------------|
| 登录期能力握手 1.20.1（login query） | ✅ | `MixinServerLoginPacketListenerImpl`；**query 已改在 LoginCompression 之后、GameProfile 之前发出**（帧化竞态修复 `483e1fb`，业界依据：Fabric API `ServerLoginNetworkAddon` 先 compression 后 query；Forge 用 NEGOTIATING 状态后置 compression） |
| 登录期能力握手 1.21.1+（配置阶段） | ✅ | `PreHandshakePayload`（loader 注册）；1.21.1+ 原生无此竞态 |
| Play 期激活链 | ✅ | dictionary_sync/index_sync → 聚合 PENDING → `play_init_s2c` → 客户端 ACK → 聚合 ENABLED |
| 通道压缩 | ✅ | 聚合包内部字典 ZSTD（算法固定，未压缩帧 flag=0）；shadow pull DELTA 内嵌分段增量自有 zstd；原版压缩层全程不触碰 |
| shadow pull 服务端权威比较 | ✅ | `ServerChunkPushManager.resolveShadowPull`（UNCHANGED/DELTA/FULL/ERROR）+ `ShadowPullHandler` + `ShadowPullResponseS2CPacket`（FULL=原版线格式；DELTA=内嵌 `SectionDeltaS2CPacket`） |
| 影子端 | ✅ | `ShadowSeedServer`（进程内 MinecraftServer）+ shadow tracking + pre-LIGHT（`ShadowLightCompute` / `ShadowVanillaLightPipeline`） |
| 客户端统计口径 | ✅ | 带宽压缩=聚合包压缩帧+shadow pull 分段增量（原始 vs 压缩后线缆，未压缩帧不计）；流量节省「数据包」=区块域锚点；probe JSON 含 `zstdOriginalBytes/zstdCompressedBytes`（`c419215`） |
| 冒烟门禁 | ✅ | 严重错误白名单含环境性网络噪音（`109ba1b`） |

## 3. 现行过渡链路清单（对齐后清理）

### T1 服务端自主推送（chunk_payload 全量通道）

- **驱动**：服务端自行 tracking 并推送——`ServerChunkPushManager:1011` → `ChunkSender.sendCompressedChunk`（loader 注册的 S2C 通道 `CHUNK_PAYLOAD_S2C`；C2S 侧**不存在**全量请求包，见 §3.4）。
- **接收**：`ClientChunkHandler.handleCompressedChunk:168`（解压 → 影子管线 `submitVisible`）。
- **实证**：final R1 `fullChunkRequestCount=1529`、`chunksDecompressed=1529`、`actualBytesReceived=8.4 MB` 全部来自此通道。
- **目标态**：采集决策权移交客户端影子 tracking；服务端对纳入 pull 集合的柱**停止主动推送**。

### T2 无基线旁路（shadow pull 未触发的直接原因）

- **门控**：`ShadowPullClient.tryInterceptForCompare:116` —— `if (!ShadowLightCompute.hasLocalPullBaseline(...)) return false;`；`ShadowPullClient.handleNativeChunk:130`（注释「缓存存在时由 ShadowPull 取代 FULL，否则保留原版包建立基线」）。
- **基线判定**：`ShadowLightCompute.hasLocalPullBaseline:859` = `ShadowStorageHashes` 登记过 **或** 本会话影子世界已注入该柱。
- **后果**：R1 全新缓存 → 无基线 → 1529 柱全部放行走推送；与 §6「无基线也进 Compare+Pull（服务端答 FULL）」及 stats 口径「新增 = 无本地 baseline FULL」相悖。
- **目标态**：无基线 = **空基线请求**（机制已有：`ShadowPullClient.requestAuthoritativeFull` 即空基线请求，目前仅作重试路径），服务端答 FULL。

### T3 chunk_payload 解压应用链（过渡载体，已随通道退役删除）

- 过渡链 `ClientChunkHandler.handleCompressedChunk` → `ChunkCompressionHandler` → `applyChunkData` 已删除（`chunk_payload` 通道退役后 `applyChunkData` 与加载屏快路径零调用方，整链移除；区块应用收敛到 `applyShadowPullFull`）。
- pull FULL 统一走 `ShadowPullClient` → `ClientChunkHandler.applyShadowPullFull` → vanilla listener → 影子管线。

### T4 统计口径过渡

- `fullChunkRequestCount`（`ShadowLightCompute:565 recordFullChunkRequests`）当前语义 =「影子引擎等待/接受的远端全量分类（新增/过期）」，并非字面 C2S 请求；doc §6/248 行口径下它应恰等于「Compare+Pull 无基线 FULL」。
- `NativeChunkMetrics.recordAppliedFullChunk` 的 server_push 归因在目标态应趋零（服务端不再自主推送整柱）。
- `recordChunkReceived / recordWireBytesReceived` 的 chunk_payload 调用点（`ClientChunkHandler:190-194`）需随通道退役迁移到 pull 链收口。

### T5 已知遗留（不影响对齐，随版本演进处理）

- ~~`classic.profile.properties` 不复位 `chunk.seedGenEnabled`~~ **已修（2026-09-11）**：classic profile 增加 `chunk.seedGenEnabled = false`；本地生成柱改 `dirty=false` 只进内存，避免 type126 落盘坏 section。
- 登录竞态已修（`483e1fb`）；若再现按该提交描述的帧化时序排查。

## 4. 对齐开发计划

> 原则（2026-09-14 改定）：**服务端权威边沿是内容裁决与 enter/leave 的真相源**；选柱由声明 + OVD 环带票（`ShadowTicketDriver`，`P5_TAKEOVER` 默认开）承担；影子 tracking 降为算光邻域与自愈扫描，**不再作为唯一采集决策者**。query 往返与压缩切换不重叠的握手不变；双端版本差期间旧通道保留（见 §5 兼容期）。

- **P1 影子 tracking 驱动 pull（无基线也发请求）**
  - 影子 tracking 选中柱 → 有基线：携带 `chunkPos+contentHash+sectionHashes+lightGeneration` 比对请求；无基线：空基线请求（复用 `requestAuthoritativeFull` 的批量/限流框架 `MAX_TRACKED_REQUESTS`）。
  - 服务端已有权威比较（resolveShadowPull），空基线必答 FULL——服务端侧基本零改动。
- **P2 服务端推送抑制** — ✅ **已落地**（2026-09-09 代码核对回填，见 §0.5）
  - 协商键 `LoginCaps.PULL_MODE`；三层：mixin 源头拦截（1.20.1 `MixinServerPlayer.trackChunk` / 1.21.1+ `MixinPlayerChunkSender.sendChunk`）+ `enqueuePushTask` 对 `FULL_VISIBLE && isPullMode` 双保险。
  - 保留：原版整柱推送兼容路径（vanilla 客户端 / 旧客户端版本差期间）——非 pull 模式仍走 `enqueueDirectPush`。
- **P3 接收端收口**
  - pull FULL 统一 `applyShadowPullFull` → 影子管线；`ClientChunkHandler.handleCompressedChunk` 进入退役观察期（仅旧双端组合触达）。
  - 统计锚点迁移：`recordChunkReceived/recordWireBytesReceived` 收口到 pull 链；`serverPushAppliedCount` 目标态趋零。
- **P4 统计与带宽压缩口径联动**
  - 带宽压缩行（聚合包+shadow pull 分段增量）不变；~~pull FULL 响应载荷是原版线格式、无 hassium 压缩，对该行贡献为 0~~
    **（2026-09-05 用户拍板更正：pull FULL 响应载荷走固定 zstd，与包聚合同列通道压缩；解压点记账
    `zstdOriginal/CompressedBytes`，对齐后 R1 类场景该行以 pull FULL 帧为主属预期）。**
  - probe 断言更新（见 §6）。

## 5. 清理清单（对齐完成 + 最低支持版本 ≥ 对齐版本后执行）

> 2026-09-09 核对：6 项均未执行；`architecture.md` §6 侧文档项已完成（见 §0.5）。
> `SeedGenExecutor` 本地生成仍依赖 `handleCompressedChunk` 解压/应用链，删通道前需先迁出该本地路径。
> **2026-09-14 大扫除核对**：清单 1/2 项已随 P2 推送抑制落地后的改造自动消失（符号全仓零命中），
> 本清单实际剩余 2 项可执行（第 3 项部分收敛、第 6 项红线）。

- [x] ~~`ChunkSender` 接口 / `ChunkSenderHolder` / `ServerChunkPushManager` 推送段（`sendCompressedChunk` 调用点 ~:1145）/ 三 loader 的 `CHUNK_PAYLOAD_S2C` receiver 注册~~ —— **2026-09-14 核对已消失**：`ChunkSenderHolder`/`sendCompressedChunk`/`enqueueDirectPush`/`CHUNK_PAYLOAD_S2C` 全仓零命中；`ServerChunkPushManager` 现存代码仅剩 Pull 响应职责（`resolveShadowPull`/待推送队列），无旧推送段。
- [x] ~~`ClientChunkHandler.handleCompressedChunk` 及仅服务它的 `ChunkCompressionHandler` 分支（wire/vanilla 记账先迁移；**注意 SeedGenExecutor 本地生成复用此链**）~~ —— **2026-09-14 核对已删**：`handleCompressedChunk`/`ChunkCompressionHandler` 全仓零命中；`ClientChunkHandler` 现存为纯 Pull 工具类（`applyShadowPullFull` 等），本地生成与区块应用已收敛到 pull 链。
- [ ] `ShadowPullClient.tryInterceptForCompare` 与 `handleNativeChunk` 的 `hasLocalPullBaseline` 旁路（收敛为链路可用性判断；对齐后 pull-mode 触达面已收窄，见 §0.5）—— **2026-09-14 核对仍存活**：`MixinClientPacketListener:44` 活跃调用 `handleNativeChunk`；旁路属现行兜底，清理需与「双端版本差兼容路径」一起评估（见第 6 项）。
- [ ] 统计：chunk_payload 调用点的 `recordChunkReceived/recordWireBytesReceived` 迁移（pull 链 `decompressPullFull` 已并行记账）；`fullChunkRequestCount` 语义——**探针读 `newFullChunkRequestCount`**，指标代码已拆分无需再改
- [x] 文档：`architecture.md` §6 从「目标」改标「现状」（已完成：「已对齐（现状）」）；本文档仍待归档
- [ ] 兼容期红线：清理前必须确认最低支持客户端/服务端版本均已含对齐改动，否则 chunk_payload 通道不可删

## 6. 验收门

| 层 | 断言 |
|----|------|
| L0 | `common:compileJava common:test` 全绿（mixin AP 校验注入目标） |
| L1 冒烟 | `runtime-smoke-test.ps1` PASS；R1（无基线）`serverPushAppliedCount→0`、区块经 pull FULL 到达；R2（有基线）UNCHANGED/DELTA 占比与现值同量级 |
| probe | `zstdOriginal/CompressedBytes` 仅来自聚合帧+DELTA；**`newFullChunkRequestCount`** ≈ 无基线 FULL 数（`fullChunkRequestCount` = new+stale 宽口径，见 §0.5）；**`landedTotal`**（= 唯一落地，含 cacheHit）≥ `loadedChunks` 采样一致性；`applied` 来源和可 > landed，不作相等断言 |
| 带宽压缩行 | classic 下仍以聚合帧为主属预期（FULL 无 hassium 压缩）；不得出现 0/0 之外的异常归零 |
| 双端版本差 | 旧客户端连新服务端、新客户端连旧服务端各跑一轮 classic，chunk_payload 兼容路径可达 |

## 7. 运维注意（本次会话实证）

- 长 gradle 任务一律后台执行并等 `BUILD SUCCESSFUL|FAILED`；被 kill 的运行会留下僵尸 daemon 注册项（实测 9 busy），卡启动时 `.\gradlew.bat --stop` 清场。
- 多版本改动以 `common:compileJava` 抽查受影响段即可（compileAnchors 全矩阵任务已移除）。
- 冒烟前确认 server/client toml 的 `chunk.seedGenEnabled` 与场景匹配（classic 应为 false）。

## 8. 关键引用

- 提交：`c419215`（统计口径）、`483e1fb`（登录竞态修复）、`109ba1b`（门禁白名单）
- 代码：`ShadowPullClient`（Compare+Pull 客户端边界，两模式）、`ServerChunkPushManager.resolveShadowPull`（权威比较）、`ShadowLightCompute.hasLocalPullBaseline:859`（基线判定）、`MixinClientPacketListener`（vanilla 包三分支入口）、`ClientChunkHandler`（chunk_payload 过渡接收 + pull FULL 应用）
- 协议背景：Fabric API `ServerLoginNetworkAddon`（compression 先行注释）、Forge `HandshakeHandler`（NEGOTIATING 后置 compression）、wiki.vg《Minecraft Forge Handshake》

## 9. 权威边沿（服务端声明 enter + 权威 chunkHash）——2026-09-13

> ### ⚠️ 读这一章之前先看这张速览（2026-09-13 第三轮会话补）
>
> **§9.5–§9.9 是过程记录，中间有三次自我推翻**（作废读数、配错开关、接管相关性记错）。原文按时间顺序读会
> 被中间结论误导。当前态的**唯一权威记录**是
> [`docs/handoff/handoff-2026-09-13-authority-edge-p5-verdict.md`](handoff/handoff-2026-09-13-authority-edge-p5-verdict.md)；
> 下面这张表是它的最小摘要，本节其余部分保留为**一手读数与推导过程**，不做重写。
>
> **现状（当前代码）**
>
> - 协议：服务端对进入玩家真实 tracking 域的柱发 `chunk_authority_s2c`（每柱带权威 chunkHash，可带
>   `snapshot=true` 整体重推）；客户端三分支解析——hash 命中零请求 / 带基线 compare-pull / 无基线权威 FULL。
> - 让位门（`ChunkAuthorityClient.pullEmissionSuppressed`）：声明流存活期内，影子端**让路不让弃**——挂起自绘
>   pull，宽限 = `AUTHORITY_WATCHDOG_MS`（10s）；超时即回退自绘（兜底不是常态）。
> - 整柱抑制点只有两处：1.20.1 `ServerPlayer.trackChunk`、1.21.1+ `PlayerChunkSender.sendChunk`
>   （neoforge 经 `ClientboundBundlePacket` 封装，已解包）。
> - 声明集合在服务端**累计并按当前形状裁剪后整体重推**（join / 切维 settle / 真实视距变更），客户端丢弃即为自愈。
>
> **已知竞态 / 缺陷（均已修）**
>
> | 编号 | 一句话 |
> |---|---|
> | F1 | 会话首次视距观测点清空 `pending`，把**同 tick 刚入队、原版已计 ACK 的声明**抹掉 → 落位点 3x3 永久虚空 |
> | F2 | 让位门宽限（3s）短于声明流存活窗口 → `starved` 变假信号；已对齐看门狗并重设扣留起算点 |
> | F12 | neoforge 的整柱包是 bundle，`instanceof` 恒 false → 抑制与权威边沿在 1.21.11/neoforge 整条没生效 |
> | F15 | 客户端 level 未就绪窗口内的声明被丢弃 → 与 F1 同族；已由「快照重推」自愈 |
> | F11 | `resolve()` 逐柱发 C2S pull（一柱一包）；已按声明包聚合 |
>
> **覆写清单（读 §9.5–§9.9 时请对照）**
>
> - 「与 P5 接管强相关」→ **作废**：判据是「基准盘发射时让位门是否已关」，`_band1` 未开接管一样有洞；
>   这是**生产配置下的缺陷**，不是接管臂专属（§9.9 修订块）。
> - 「`band1` 与基线等价 / `bandmove1` 比 `otmove4` 改善」→ **作废**：那两轮 `ENABLED=false` 配
>   `NEUTRALIZE_TRACKING=true`，驱动一行没跑（`bound instance` = 0 条），测的是纯基线（§9.8 首块）。
> - 「`add ticket failed` = 0 是票生效的正面证据」→ **作废**：该日志级别在冒烟 profile 不生效（§9.7 末）。
> - 「移动场景既有 3 柱缺口 `(-4,-2)(-3,-2)(-2,-2)`」→ **作废**：复核 probe 后确认它就是 F1 落位点 3x3
>   空洞的整条下沿，不是独立缺口（handoff §五 F8）。
> - 「删虚拟玩家 / 把 `bootGrid`、`sweepVisibleShape` 列为可裁」→ **推回**：它们不是冗余兜底，而是权威窗
>   装载的实际主力（§9.7 方向修正）。

### 9.1 触发与根因

手工测试「走出十几个区块再走回出生点」时统计恒为 `区块缓存 0.0%` 且 `区块加载` 全为「新增」。逐层核对得到两个独立根因：

1. **权威集合在客户端被「推断」而非「被告知」**：`ShadowTrackingSession.drainSelections` 对已注入柱恒 `continue`，重入只能走 `sweepVisibleShape → drainRedeliver` 的本地 publish（不 compare）；唯一带 compare 的 vanilla 进范围桥要求 `!hasClientApplyEpoch`，重入时不成立。服务端能持续更新的柱 = 真实玩家 tracking 域；域外柱既收不到更新也不能当权威交付。
2. **注入表只增不减**：`ShadowServer.unloadChunk`（唯一的 flush + 摘表入口）**全仓零调用者**——`935b9ed`（vanilla-align client-shadow chunk bridge）删除了唯一的调用点（客户端卸载拆影子表会造永久洞，该修复本身正确），但影子端自身的回收路径随之断链，`injectedChunks` 会话内单调增长（内存无上限）。

历史对照：`b629fd0` / `8d69067` / `a45f9dc` 时代由**服务端主导**（推送 + hash 命/回执）；`14f63ab` 统一到 §6 Compare+Pull 时把**采集决策权**一并移到了客户端。本轮把「权威判定权」收回服务端，但**不**恢复服务端推送载荷与 per-player hash 表。

### 9.2 设计

```
服务端 ChunkMap tracking ──抑制点(trackChunk/sendChunk)──> chunk_authority_s2c(dim, epoch, snapshot, entries{pos, hash})
                                                            │
客户端 authoritySet ──> 三分支解析 ──┬─ hash 已知且本地相同 → 零请求本地交付 + 记「区块缓存全命中」
                                     ├─ 未知/不等          → requestFull（带基线比较）
                                     └─ 无基线            → requestAuthoritativeFull（或 SeedGen）
真实客户端 Forget（原版）──> 账本解锁 / 重发队列 / 注入表回收候选
```

- 载荷与发射：`ChunkAuthorityS2CPacket`（批量 128）、`ChunkAuthorityNotifier`（per-player 缓冲、每 tick 一包、hash 缓存 miss 现算但受哈希预算约束、预算不足附 0）、`ChunkAuthorityHashes`（共享 per-chunk 内容 hash 缓存 + 方块变更失效 + LRU）。
- **失效点（必须接线，否则静默内容错误）**：`MixinLevelChunk` 注入 `LevelChunk#setBlockState` 的 RETURN（返回 null = 内容未变则跳过），仅真实 `ServerLevel` 且非影子上下文时 `ChunkAuthorityHashes.invalidate(...)`。**描述符按段分叉**：1.21.5 起第三参由 `boolean isMoving` 改为 `int flags`，以 `MC_1_21_5` 为界两段共用一个实现体。热路径用 `ChunkAuthorityHashes.hasEntries()`（volatile 免锁）短路——未协商权威位时每次方块变更只多一次 volatile 读。
- 协商位：`LoginCaps.AUTHORITY_NOTIFY`（1<<6）；未协商客户端不消费该载荷，走原路径。
- 客户端：`ChunkAuthorityClient`（三分支）+ `PayloadHandlers.handleChunkAuthority`；`ShadowLightCompute.markAuthorityHashConfirmed` 解锁「卸载后再交付」的全命中记账（首轮双路径仍被 `accountedIngress` 挡住，R1 假命中红线不变）。
- 影子端让位：`ChunkAuthorityClient.pullEmissionSuppressed()`（协商位 + 权威包未断流 10s 看门狗）为真时，`ShadowTrackingSession.emitPullGroups` 与进范围桥的 compare-refresh 不再发 pull；影子端继续负责物化/交付/回收。
- 回收（事件化）：`onClientChunkUnloaded` 对「窗内」柱入重发队列并撤销离开标记，对「窗外」柱登记 `outsideSinceMs`；影子主循环 `reclaimOutOfRetainSet` 宽限 6s 后 flush + 摘表（在途 / 客户端重新持有 / 关停窗口跳过）。**禁止按影子端自绘几何推断回收**。

### 9.3 实测教训（必须保留）

- **几何驱动回收是错的**：按 `inVanillaVisibleShape || inOvdWindow` 摘表会把客户端尚未交付的柱提前摘掉——R1 `区块加载` 从 1636 掉到 585，且断连 teardown 期 `flushColumn` 悬挂（客户端 teardown TAIL 未完成、退出码 1）。回收触发源必须是客户端 leave（真服 Forget）+ 宽限 + 关停守卫。
- **客户端解析会被影子端抢跑**：R2 窗口仍有 589 批 `[SHADOW_PULL] server request`，因为 bootGrid 在 join 瞬间先发了 pull；P2（让位）是让 hash 命中真正生效的必要条件。
- **「原版忽略」的重试必须有限**：`ShadowLightCompute.applyReadyChunk` 在 `hasClientChunk` 判定失败时只做「下一帧原样重投」，**无次数上限、无放弃条件、每次还打一行 INFO**。而原版的 `Ignoring chunk since it's not in the view range` 是**不可自愈**拒绝（玩家不回该区域就永远进不去），于是快速移动后滞留在投递队列里的旧窗口柱在 `ready` 里每帧打转：`1.20.1_fabric_I_move2` 实测 21s 内 **102010 行**（日志被撑到 84MB），渲染线程被日志 + 无效 apply 吃满，R1 的 dump 都没跑到，客户端 120s 不退出被强杀。修法见 §9.5。
- **`LevelChunk#setBlockState` 的描述符跨段会变**：1.21.5 起第三参 `boolean isMoving` → `int flags`（`(...Z)L...` → `(...I)L...`）。写新 mixin 前用 `minecraft-dev` 的 `analyze_mixin` 逐段验证，别凭记忆写描述符——本次实测各段结果：≤1.21.4 为 `Z`、≥1.21.5 为 `I`（1.20.1/1.21.1/1.21.2/1.21.3/1.21.4 通过 `Z`；1.21.5/1.21.6/1.21.11 通过 `I`）。

### 9.4 验收（`1.20.1 fabric classic`，`SessionId=1.20.1_fabric_I_final2`）

| 轮次 | 读数（冒烟实际数据） |
|---|---|
| R1（VD20 冷启） | 带宽压缩 74.2%（31.5MB→8.1MB，3.87:1）；`区块缓存 0.0%`（应用 24.4MB）；`区块加载 1561（新增 1561/24.4MB）`；`超视渲染 0/0`；`光照缓存 0.0%`；`流量节省 79.0%` |
| R2（VD10 重连） | 带宽压缩 54.2%（7.6KB→3.5KB）；`区块缓存 100.0%`（全命中 1082/16.9MB + 部分命中 5/80KB，增量 10B）；`区块加载 0（新增 0/0B）`；`超视渲染 已加载 634 / 缺失 2`；`光照缓存 100.0%`（命中 1215/19.0MB，重算 0）；`流量节省 100.0%`（当前 2.6KB） |

`=== RESULT: PASS ===`（Round1/Round2 门禁均 True，exit 0）。端到端取证：服务端 **74 条 `[AUTHORITY] send`**（含 `snapshot=true epoch=3` 首包；`hashed` 与 `entries` 同量级），客户端 **46 条 `hash-hit zero-request`**（零请求本地交付并计全命中）。

**未覆盖项（如实记录）**：本轮 `[SHADOW_TRACK] reclaim` 计数 0 —— classic 场景 R2 仅 41s 且玩家不移动，不产生「窗外 leave」；注入表回收路径（§9.2 末条）由**移动型场景**（`-MoveSeconds`）取数，见 §9.5。同理，`[AUTHORITY] hash-hit` 占比受「权威包到达时影子尚未读盘/未有本地 hash」限制，仍是小头（R2 主体命中来自 pull 侧 UNCHANGED），进一步收敛需把比对时机下沉到影子 materialize 之后。

### 9.5 移动场景验收（`-MoveSeconds 12`）：活锁归因与修复

`final2` 只覆盖「进服 → 重连」不移动。往返移动（走出权威再回来）是权威边沿的**正确性主场景**，按它取数时暴露了一个活锁。

**修复（`ShadowLightCompute`）**：给「被原版忽略」加有界重试——连续被拒 `MAX_IGNORED_RETRIES=60`（≈3s @20fps）即**放弃该投递条目**（`release`），影子注入表与磁盘基线**不**清除，玩家回到该区域时由权威声明/形状扫描重新投递；重试日志按 `IGNORED_RETRY_LOG_EVERY=200` 节流。计数表在 `resetRequestDedupForReconnect` / `onClientDimensionChanged` / drain 断连分支 / `onDisconnect` 四处清空。

| 读数 | `move` / `move2`（FAIL，修复前） | `move3`（PASS，修复后） |
|---|---|---|
| R1 `区块加载` | 1691 / — | 1600（新增 1600/25.0MB） |
| R1 trace `injectedNotReady` | 0 / — | 0 |
| R1 spatial | 1691/1694 | **1600/1600，四向+对角洞 0** |
| R2 `区块缓存` | 96.5% | **100.0%**（全命中 1081/16.9MB + 部分命中 6/96KB，增量 6B） |
| R2 `区块加载` | 45（新增 45/720KB） | **0** |
| R2 `超视渲染` | 1059 / **缺失 353** | **634 / 缺失 2** |
| R2 `光照缓存` | 92.2%（重算 112/1.8MB） | **100.0%（重算 0）** |
| R2 trace `injectedNotReady` | **67** | **0** |
| R2 trace `expectedNotPresent` | **67** | **0** |
| R2 spatial | 45/112 | **550/550，洞 0** |
| `Vanilla ignored authoritative chunk` 行数 | 102010（21s） | **0** |
| `drop ... outside vanilla view range` 行数 | —（无上限，永不放弃） | **0**（重试根本没发生，上限未触发） |
| 收尾 | R2 `saveAll` 未跑 → 2s 等待超时 → 强退 → `0xC0000409` / 客户端 120s 不退出被强杀 | `shadow save completed (seq 3 → 4)` → 优雅退出 **0** |

`=== RESULT: PASS ===`（`move3`，exit 0；analyzer `failures=0`，7 项门禁全 PASS）。取证：服务端 189 条 `[AUTHORITY] send`（含 `snapshot=true epoch=3`），客户端 44 条 `hash-hit zero-request`。

**回收路径取证（`SessionId=1.20.1_fabric_I_move`）**：`-MoveSeconds 12` 下 R1 出圈 `CHUNK_UNLOAD=24` → **`reclaim=24`**，全部由独立线程 `hassium-shadow-reclaim` 打出，形如
`[SHADOW_TRACK] reclaim (-5, -12) -> flush+evict injected (dimension=minecraft:overworld)` —— §9.2 末条的回收路径**已被运行时触发并落盘**。（`move3` 该计数为 0：R2 仅 41s，宽限 6s 内玩家已回到原区域，离开标记被撤销。）

**归因边界（不要过度断言）**：`move3` 里 `Vanilla ignored` 与 `drop` 都是 0，说明修复后**连一次被拒都没发生**，上限从未触发。单跑无法区分是「节流掉了日志/CPU 自放大」还是「有界重试清空了队列」消除的拒绝；两条都在本次改动里，方向一致，不再细分。

### 9.6 P5 第 1 步：声明集合直出票 —— 并存试验与 tracking 钝化判定

**动机（对「虚拟玩家必须留」四条依据的重审）**：核代码后，原先那四条依据里三条的表述是错的——`tickChunkSystem` 就是 `ServerChunkCache.tick(haveTime,false)`（`ShadowPlayerCompat:162`，签名无玩家）、`setChunkViewDistance` 是 `ServerChunkCache.setViewDistance`（level 级）、`scheduleChunkLoad` 拦截点与 SeedGen 门控（`MixinChunkMap:111/137/219`）全无玩家参数。虚拟玩家真正不可替代的只剩两项：**(a) ticket 源**（`PlayerList.placeNewPlayer` 的 PLAYER 票 + `ServerChunkCache.move` 的重算）、**(b) 1.20.1 的物化桥挂点**（`playerLoadedChunk(player,…)`；1.21.1+ 已是无玩家的 `ChunkMap.onChunkReadyToSend(LevelChunk)`，只从 `protoChunkToFullChunk` 完成回调调用）。全仓唯一出票处只有 `ServerChunkPushManager:214/219`（真服务端 `FORCED`），影子端一处票都没有——`ShadowSeedServer:96` 说「随后加 `TicketType.UNKNOWN` FULL 级票」是**过期注释**。

**实现**（`ShadowTicketDriver`）：把 `ChunkAuthorityClient` 收到的声明逐条 `DimensionKey` 复合键翻成影子 `ServerLevel` 上的 `TicketType.FORCED` 票，复用 `ServerChunkPushManager` 已跑通的 1.21.5 分叉（`addRegionTicket` / `ticketStorage.addTicketWithRadius`）。网络线程只登记，出票/撤票一律在影子主循环（`vanilla DistanceManager` 单线程）。两个试验常量：`ENABLED`、`NEUTRALIZE_TRACKING`。

**1a 并存（1.20.1 fabric，虚拟玩家不动）**：`ticket1` classic + `ticketmove1` move 双 PASS exit 0。R2 全命中 1082/1085、`区块加载 0`、spatial 无洞、缺口全 0；收尾 `shadow save completed (seq 3 → 4)`。**但这只证明「无回归」，不能证明票生效**——tracking 已覆盖声明集合，票是叠加物，生效与否读数都一样。当时的间接正面证据只有三条：`handle` 确实执行（`[AUTHORITY]` 出自同一 entries 循环后半段）、影子主循环在跑、`add ticket failed` = 0 条（该日志级别在冒烟 profile 下开启）。

**1b 钝化（1.21.1 fabric，tracking 压到 3x3）**：选 1.21.1 是因为该段物化桥已无玩家参数，能把「票能否替代选柱」与「1.20.1 桥改造」解耦。

| 会话 | R1 | R2 | 判定 |
|---|---|---|---|
| `1.21.1_fabric_I_nticket1`（classic） | `区块加载 1529`，收/注入/ready/应用 **1529/1529/1529/1529**，spatial **1529/1529 无洞** | `区块缓存 100.0%`（全命中 1084 + 部分 21/336KB）、`区块加载 0`、`超视渲染 632/4`、光照 100%、spatial **453/453 无洞** | **PASS**（failures=0） |
| `1.21.1_fabric_I_nticketmove1`（move） | `区块加载 1520`，spatial **1520/1520 无洞**（**含 12s 飞行段**） | `区块缓存 97.1%`、`区块加载 38`、`超视渲染 1058/缺失 334`、光照 89.5%；`injectedNotReady 104`、`expectedNotPresent 107`、spatial 35/142 | **FAIL**（exit 1，混变量） |

对照 1.21.1 classic 基线：R1 1605 / R2 全命中 1074 / `区块加载 0` / `超视渲染 634/2`。

**结论**：**票可替代 tracking 的「选柱」职能，含移动窗**——`nticket1` 在 tracking 仅 3x3 时把 1092 柱声明集合完整交付且 R2 全命中 100%；`nticketmove1` 的 R1（含飞行）同样 1520/1520 无洞。**但「拆虚拟玩家」还不够火候**，因为 1b 的 R2 缺口混着两个变量：

1. **OVD 圈被锐化开关连坐饿死（实验设计缺陷）**：OVD 圈（`maxRenderDistance=16` > 权威边距）**不在声明集合内**，其装载原本靠同一个 ChunkMap tracking 半径；压到 3x3 后它同时断了票源 → `超视渲染 缺失 334`。同一旋钮既管「权威窗 tracking」又管「OVD 圈 tracking」，无法分离。**第 2 步的入口因此不是「删虚拟玩家」，而是「先给 OVD 圈独立出票」**，之后再做一次钝化判定才干净。
2. `injectedNotReady 104`：收了、注入了但没进 ready，需单列归因，暂未定位。

**本次修掉的自有缺陷**：`pendingAdds` 原本在会话边界被**整队 clear**，会把新会话刚登记的声明一起吞掉（影子实例重建分支同样如此）。已改为**带代数戳的队列**——登记时记 `generation`，`requestClear()` / 实例重建各自自增，泵里只消费同代条目、越代丢弃，`clearAll` 不再整清队列。形态与 R2「部分柱永不投递」吻合，但两次 1b 的 `[SHADOW_TICKET] cleared all` 都是 0 条（说明当时走的是实例重建分支、且该分支原本无日志），**故没有证据认定它是 R2 主因**。

**修复后回归**：`1.20.1_fabric_I_ticket2`（classic）PASS exit 0——R1 1551/spatial 1551:1551 无洞；R2 `区块缓存 100.0%`（全命中 1085 + 部分 2/32KB）、`区块加载 0`、`超视渲染 634/2`、缺口全 0。`NEUTRALIZE_TRACKING` 已回 `false`，不把饿死 OVD 圈的状态留在树里。

**流程教训**：`1a` 首次运行失败是我在冒烟运行**同时**改源码，gradle 重编撞上改到一半的中间态（`common:compileJava FAILED`）。再次确认 AGENTS.md 那条红线：构建/改码不得与运行中的游戏 JVM 并发。

### 9.7 P5 第 2 步：本地整方形票驱动（设计与判定边界）

**设计（`ShadowTicketDriver` 重写为对账式）**：输入不再是服务端声明，而是**本地几何**——中心 = 会话唯一位置真相源（`virtualPlayer.chunkPosition()`，与 `inVanillaVisibleShape` / `sweep*` 同源）、半径 = `resolveViewDistance() + 1`（vanilla `setViewDistance(X)` 内部再 +1 才是 tracking 形状半径，与 `ShadowPullRadii.AUTHORITY_MARGIN` 语义一致；实测打出的 `r=22` @VD20 印证）、形状 = `ChunkShapeCompat.contains`（原版谓词）。几何一变即「补齐缺失、撤掉越界」，**天然幂等可续**，故不再需要事件队列与会话代数戳。顺序复刻 vanilla：**增票由近及远**（`ChunkDistancePriority`）、**撤票由远及近**、且固定在增票之后（先增后删，避免边界抖动出瞬时空洞）。服务端 `enter` 声明退回**只做内容裁决**，不参与出票——票源只有一张方形，与今天同构。

**为什么不做「OVD 圈独立出票」**：OVD 从来不是独立机制，它就是同一张方形在 `serverVD` 之外那一环（range 取 `max`）。历史上「服务端出票 + OVD 独立出票」的割裂源于**两套票源的三个不同步**（中心 / 节奏 / 优先级）；再引入第二来源就是复制那个形态。

**已确认的结论**

1. **整方形驱动确实解决了 1b 的 OVD 环饿死**：`1.21.1_fabric_I_ot1`（classic，tracking 钝化到 3x3）R2 `超视渲染 632/缺失 4`，与 tracking 基线 `634/2` 逐项等价；而 1b（只翻服务端声明）是 `缺失 334`。机理：票的作用是让**没有其它驱动**的柱进入 `ChunkMap.scheduleChunkLoad`（→ 悬置 → pull）。权威窗内的柱另有驱动，OVD 环的柱只有票这一条路。
2. **批量出票必须节流**：`FORCED` 是 level 31（FULL），一拍塞几百张会在单次影子主循环迭代里触发大量同步装载/生成。`512/拍` 在 1.21.1 移动场景出现运行期原生终止（`0xCFFFFFFF`，无 Java 痕迹、无 `hs_err`、无 crash-report、无 OOM；`otmove2` / `otmove3` 各一次，均死在 R1 极早期，日志仅 1749 行）。收到 **64/拍 后消失**（`otmove4` 跑完两轮）。**这是上生产的必要条件，不是优化。**

**两条混淆已查清（P5-3）**

1. **影子端有四条装载驱动，其中三条不依赖票**：
   - `drainBootGrid`（`ShadowTrackingSession:900`）绕 `homeChunk` 铺静态盘，自己 `emitPullGroups` **直接发 pull**；
   - `sweepVisibleShape`（`:820`）注释原文"**本扫描不依赖 vanilla**"，周期枚举未注入柱直接发 pull；
   - `sweepOvdRing` → `tryServeOvdLocal`（`:1019`）："OVD 窗本地源：injected / disk。**绝不发 ShadowPull。缺盘柱交给原版 tracking**"；
   - ChunkMap 票（虚拟玩家 tracking / 整方形驱动）→ `scheduleChunkLoad` → 悬置 → `drainSelections`。
   前两条半径都只取 `serverViewDistance`，**故覆盖不到 OVD 环**；OVD 环的**有盘**柱走本地源（不依赖票），**缺盘**柱按设计交给原版 tracking（**依赖票**）。这就是 `otmove4` 的 R1 仅 256 张在售票却交付 1520 柱的原因。
2. **`超视渲染` 是"本轮扫过的去重 OVD 柱"，不是窗口比率**：`HassiumMetricsImpl` 里是 `AtomicLong` 累加 + `recordOvdLoadedOnce/MissOnce` 的去重集合（`ovdCounted`/`ovdMissCounted`），`reset()` 清零；分子分母都随"扫了多少格"增长，故**跨场景不可比**（classic 636 vs 移动 R2 1586）。但**比值可比**：`otmove4` 443/1586 ≈ 28% 未命中，tracking 版 `move3` 2/636 ≈ 0.3% —— 差两个数量级，**移动 R2 的缺口是真实的**。

**由此得到的方向修正（重要，推翻 §9.5/§9.6 的裁剪清单一部分）**

- **票唯一不可替代的覆盖区间 = OVD 环中「缺盘」的那部分**（外加权威窗内两条 sweep 的周期/预算之外的残余）。这正是 1b（只翻服务端声明）`缺失 334`、而整方形驱动只 `缺失 4` 的原因。
- **把 `bootGrid`、`sweepVisibleShape` 列为"可裁"是错的**：它们不是冗余兜底，而是**当前权威窗装载的实际主力**（`otmove4` R1 仅 256 张票即交付 1520 柱即为证）。裁剪清单要重排：先裁**选柱推断**，保留这两条 pull 驱动。
- **B 档（删虚拟玩家）的精确前置条件**：驱动需覆盖"OVD 缺盘柱"，且填充速度跟得上移动。`ot1`（静态）已成立；`otmove4`（移动）28% 未命中说明**填充速度/覆盖不足**（64/拍、且仅在几何变化时对账，追不上移动中不断换新的 OVD 格）。


**树状态**：`ShadowTicketDriver.ENABLED = false`、`NEUTRALIZE_TRACKING = false`——判定未成立前不把未验证的驱动默认开启、也不把"饿死 tracking"的实验态留在树里；代码与插桩（`[SHADOW_TICKET] bound / reconcile / cleared`，走 `LogType.NETWORK`）保留供下一轮迭代。

**日志教训**：驱动的插桩最初用 `DebugLogger.LogType.ASYNC`，而该级别受 `debug.asyncLogging` 门控、**冒烟 profile 没开**——导致 §9.6 里"`add ticket failed` = 0 是正面证据"的推断**是错的，已撤回**（那些 `[SHADOW_TRACK]` 行走的是 NETWORK）。实验/热路径日志一律用 profile 会开的级别。

### 9.8 P5 第 2 步改定：票源接到 OVD 环带判据上（不再是整方形）

> **⚠️ 本节第一版读数（`band1` / `bandmove1`）已作废。** 那两轮跑的时候 `ENABLED=false` 配着
> `NEUTRALIZE_TRACKING=true`——驱动一行没跑、tracking 也没被钝化（`ENABLED && NEUTRALIZE_TRACKING` =
> false），测出来的是**纯基线行为**。铁证：`[SHADOW_TICKET]` 连无条件打印的首绑行 `bound instance`
> 都是 **0 条**。故"`band1` 与基线等价"是同义反复、"`bandmove1` 比 `otmove4` 改善"是"驱动开 vs 关"
> 的差别，**均不构成对环带设计的验证**。已把两个布尔收成单开关 `P5_TAKEOVER`（驱动出票 ⟺ 钝化
> tracking 恒等联动），杜绝配错的中间态，并以 `band2` / `bandmove2` 重测。

**改法**（比 §9.7 的整方形小得多，因为不再自算几何）：新增 `ChunkShapeCompat.inOvdBand`（把 `inOvdWindow` 原先手搓的「切比雪夫窗内 ∧ 不在 authority 形状内」收口，两处共用），`ShadowTicketDriver` 的目标集合改为**该环带**，入参与 `inOvdWindow` 同源（`serverViewDistance` / `effectiveClientVD`）；驱动不再需要 `VANILLA_TRACKING_MARGIN` 补正。理由：`tryServeOvdLocal` 的注释已写明"**缺盘柱交给原版 tracking**"——那一环就是唯一在等票的集合，票源与判据同源后"覆盖量"这个不确定量直接消失。

**读数（重测：`P5_TAKEOVER=true`，驱动确实在跑——`[SHADOW_TICKET]` 非零且含 `bound instance`）**

| | `band2` classic | tracking 基线 | `bandmove2` move | 整方形驱动 `otmove4` |
|---|---|---|---|---|
| R1 spatial | **1520/1520 无洞** | — | **1524/1524 无洞** | 1520/1520 |
| R2 spatial | **444/444 无洞** | — | **120/120 无洞** | 9/147 |
| R2 `区块缓存` / `区块加载` | 100.0% / 0 | 100% / 0 | 91.6% / 120 | 91.3% / 119 |
| R2 `injectedNotReady` / `expectedNotPresent` | 0 / 0 | 0 / 0 | **0 / 0** | **138 / 0** |
| 收尾 | `shadow save completed (seq 1→2)`，退出 0 | 同 | **`save wait timed out (seq still 1)` → 强退 `0xC0000409`** | 同左 |
| 判决 | **PASS failures=0** | PASS | **仅 `CLIENT_EXIT_NONZERO`** | `CLIENT_EXIT_NONZERO` + 138 |

**插桩交叉验证（`bandmove2`）**：`reconcile … band=10..16 … desired=636` —— `band=10..16` 恰为 R2 的 `(serverViewDistance=10, effectiveClientVD=16)`，`desired=636` 又恰与 classic R2 的 `超视渲染` 总数 636 吻合。**驱动目标集合与 OVD 判据/指标窗口三者对上了**，`inOvdBand` 收口正确。收敛轨迹也正常：`live` 64→502 随移动爬升，`-21/-23/-46/-50` 随环带滑移撤票。

**结论（P5 第 1 步等价性）**：**1.21.1 数据面已完全打平**——classic 逐项等价（`区块加载 0`、`超视渲染 632/4` vs 基线 634/2、spatial 无洞），move 的 `spatial 120/120`、`injectedNotReady 0`、`expectedNotPresent 0` 全绿（整方形驱动是 9/147 + 138）。**唯一残留失败是收尾**：R2 的 `shadow saveAll` 一直停在 seq 1 → 2s 等待超时 → harness 强退 → `0xC0000409`。

**残留归因（下一轮）**：驱动开启时 R2 收尾保存停滞（`bandmove2`/`otmove4` 两次），驱动关闭时四轮（`move3`/`bandmove1`/`bandoff1`/`band2`… 后两者中 `bandoff1` 为驱动关）都正常完成 → **与驱动的存在相关**。候选机理：驱动持有的 `FORCED` 票让柱常驻，`saveAll` 的等待条件（park/关停路径）被票改变。注意这与最初的 `move` 失败同族（`move` 是驱动不存在时代码就有的：R2 saveAll 未跑 → 强退 → `0xC0000409`），故要先分清"驱动诱发"与"harness 强退本身"。

**方法学注意（重要）**：移动场景**跨轮方差大于待测效应**——同为"驱动关、tracking 全速"的 `move3` 是 `spatial 550/550 / 区块加载 0 / 超视渲染 634:2`，而 `bandmove1` 是 `138/141 / 141 / 1112:456`（差别来自 R2 那 12 秒飞行覆盖的地形量随位置漂移）。**故移动场景的单轮跨配置对比不可靠**，此前基于单轮的"28% vs 0.3%"等比较均已打折看待；判决改用同轮内量（`spatial` / `injectedNotReady` / `expectedNotPresent`）。

**另记**：`(-4,-2) (-3,-2) (-2,-2)` 这 3 柱在**驱动完全没跑**的 `bandmove1` 里就缺、且全日志 0 次出现 → 是移动场景的**既有**缺口，与本驱动无关。


**读数陷阱（重要，别再被绕进去）**：`超视渲染 缺失` 是 `tryServeOvdLocal` 的**本地源未命中**计数（盘上没数据），不是"没加载"；`区块加载` 亦然。两者的跨轮差异主要由**本轮飞进了多少未缓存地形**决定——同轮内 `缺失 456` 与 `区块加载 141` 互相印证，而 `move3` 基线的 `缺失 2 / 区块加载 0` 是因为那轮飞行仍在已缓存盘内。**判决必须看 `spatial` / `injectedNotReady` / 退出码**，不能用这两个数。

### 9.9 P5 第 3 步：落位点 3x3 永久空洞——补上真空洞门禁，把让位门从「静默吞」改成「让路不让弃」

§9.8 的"数据面已完全打平"里漏了一件事：**门禁看不见「从未投递」的柱**。

**为什么看不见**：`analyzer.py` 的 `gaps.expectedNotPresent = expected − actual`，而
`expected = stages["networkReceived"] or stages["shadowReady"]`——**候选集本身就是交付过的事件**。一个
从头到尾没被投递过的柱不在候选集里，于是"缺柱"这件事在结构上不可表达（`SmokeProbeWriter` 的
javadoc 也已写明 `actualPresent` 只抽样 trace 候选项，其基数不能当"已加载计数"用）。
`_spatial_check` 只做**一层**邻域判断（某缺席格的 4 邻 ＋ 8 邻里够多已持有才算洞），于是对一个
**实心 3x3 空洞**只能看见四只角，看不见另外 5 格——报告的 `diagonalHoles` 恰好是 `(-3,-1) (-3,1) (-1,-1) (-1,1)`，
而 `SPATIAL_SNAPSHOT_INCOMPLETE` 只是 **P1 警告**。于是一次**真实的落位点 3x3 虚空**以
`=== RESULT: PASS ===` 收场。

**空洞是真的（同版本同场景对照，非跨版本猜测）**

用包围盒洪水填充（把已持有集合外扩一圈，从盒外角 4-连通填充，填不到的缺席格 = 被围住的洞）重算历史 probe：

**判据不是"接管与否"，而是"基准盘发射那一刻让位门是否已经关上"**

| 会话（1.21.1 fabric classic） | `[SHADOW_TICKET]` | `hash-hit` | `authoritative-full pull` | 封闭空洞 |
|---|---|---|---|---|
| `_band1` | **0** | 442 | **0** | **9** |
| `_band2` / `_ot1` | 3 / 有 | 443 / 442 | **0 / 0** | **9** |
| `_bandmove1` / `_nticketmove1` / `_otmove1` / `_otmove4` / `_bandmove2` | 有 / 0 / 有 / 有 / 有 | 46 / 45 / 46 / 17 / 23 | **0 / 0 / 1 / 0 / 0** | **9** |
| `1.21.1_fabric_I` | 0 | 0 | 13 | **0** |
| `_nticket1` | **0** | 60 | 1 | **0** |
| `_refactor` | — | 0 | 147 | **0** |
| `_p5fix1`（修复后，接管态） | 有 | 49 | 15 | **0** |
| `_p5off2`（修复后，生产态） | **0** | 37 | 13 | **0** |

> **⚠️ 修订（本轮纠正，前面基于 `band1`/`nticketmove1` 的"与 P5 接管强相关"说法作废）。**
> 有洞的会话 `authoritative-full pull` 全是 **0**（少数 1），没洞的会话是 **1~147**；而 `[SHADOW_TICKET]`
> 根本不是判据——`_band1` / `_nticket1` 都是 **0**（驱动没跑）。关键是 **`_band1` 的配置是基线等价**的
> （§9.8 记录的 `ENABLED=false` 且 tracking 未被钝化，故 `[SHADOW_TICKET]` 为 0），它**照样有洞**。
> 所以这是**让位门的竞态**、不是接管的专属缺陷：门在 boot grid 铺盘期间关上时，影子端的 pull 一个都发不出去
> （`authoritative-full pull = 0` 即"这两轮一条 pull 都没发"），而落位点 3x3 本地无盘、声明又不覆盖 →
> 没有第二个来源。**这条竞态在生产配置下同样成立**，本轮的让位门改动因此是对生产缺陷的修复，不只是为实验服务。

- 全部 `observed = 完整盘 − 9`：R1 是 `1529 − 9`、R2 是 `453 − 9`，且 9 格恒为**以玩家所在柱为中心的 3x3**。
  注意它与 `NEUTRALIZED_VIEW_DISTANCE = 1` 同形**只是巧合**（`band1` 并未钝化 tracking，洞一样是 3x3）——
  真正的成因是"声明不覆盖的那一批恰好就是登录期已推的落位点邻域"。
- 三条独立日志证据（取自 `_band2`）：① 服务端该轮共 `[AUTHORITY] send` 443 条，**恰好不含**这 9 格；
  ② `boot grid primed around (-2,0) radius=10 cells=9 redeliver=444`——盘面枚举出了这 9 格"未注入"，
  它们被 `drainBootGrid` 取走后**再也没有第二次出现**；③ 整份客户端日志里对这 9 格的**非 `target=`
  引用为 0 条**（无 `materialized` / `OVD local serve` / `pull-injected` / `reconcile`），
  `phase=shadow_applied target=(x,z)` 计数也全为 0。

**当前判断（诚实标注未定部分）**

可以确定的是**故障形态**：一个位于本地可见窗口内、本地无盘也未注入、且**服务端声明集合不覆盖**的柱，
在权威让位门关闭期间**被驱动取走并丢弃**（`drainBootGrid` 的 `pollFirst` 是不可逆的），此后没有任何
补偿路径——`emitPullGroups` 当时是 `return`（静默吞），`sweepInFlight` 又被标成在途 60s，
于是 `sweepVisibleShape` 也不会再发现它。

具体落点：**最可能是 `emitPullGroups` 的那句 `return`**——依据是这些轮次 `authoritative-full pull` 为 **0**，
即影子端在整个跑动里**一条 pull 都没发出去**；能绕过它的另外三个静默分支
（`injectedChunk != null` / `preferLocalGeneration()` / `markPullInFlight` 失败）都需要**另一个动作方**
先碰过这 9 柱，而那种触碰都会留日志，日志里没有。**但这仍是推断，没有做单点插桩确认**——四个分支的
可见表象完全相同（零日志痕迹）。故本轮**只改结构上无争议的那一段**：让位门不能吞掉已入队的工作项。

**改法（一处收口，不新增驱动）**

所有 pull 都从 `ShadowTrackingSession.emitPullGroups` 出去，故只改这一个点：

1. **让位期间仍然登记、但不丢弃**：每柱记录首次被扣时间；**扣留中**的柱顺手清掉它的 `sweepInFlight`
   在途标记（否则形状扫描把它当"已发出"，60s 内不再重新发现，宽限永远等不到第二次登记）。
2. **超过 `GATE_STARVE_GRACE_MS = 3s` 即无条件补发一次**（compare-pull / 权威 FULL），并把该柱从等待表
   移除——保留它的在途标记，避免同一拍里 `drainBootGrid` / `drainSelections` / `sweepVisibleShape`
   三个调用方对同一柱重复补发；补发失败的话，等 `sweepInFlight` 自然过期后会重新走一遍宽限。
3. **让位解除即清等待表**（`gateWaitingSinceMs.clear()`），宽度有界、无泄漏；等待表每拍按本拍实际
   扣留集合 `retainAll` 裁剪。

这与 `ChunkAuthorityClient` 既有的 10s 断流看门狗同一个设计哲学：**等一个可能永远不来的声明，
不能把兜底也一起关掉**。

**门禁补上（口径与证据都在 `scripts/smoke/analyzer.py`）**

新增 `_hole_check`：包围盒洪水填充求**封闭空洞**，再算 4-连通分块大小。分级：

- `TRACE_ENCLOSED_HOLE`（**P0**，仅 classic）：最大分块 **≥ 4 格**——成片的实心虚空（≥2x2）。
- `TRACE_ENCLOSED_HOLE_SMALL`（P1，仅 classic）：1–3 格，多为采样/边界效应。
- **仅 classic**：其它场景的盘回填不走同一交付契约（`dimension` / `modcompat` / `seedgen`
  的稀疏采样本来就会产生成片"填不到"区域）。`ovdgen` 已随 `chunk.ovdLocalGeneration` 退役（`8bee742`）。
  **`dimension` 的例外已于 2026-09-13 复核推翻**：那里的 303 格空洞是「单柱失败关掉影子端 → 切维后
  客户端缺中心区」的真缺陷（`2a89ead` 修复），不是契约差异。

用**全部 164 个历史 result JSON** 回放校验：新增 P0 命中 **8 个会话 / 11 轮样本**（最大分块恒为 9），
`1.20.1_fabric_I_move2` 只触发 P1（1 格），其余零影响；非 classic 场景的 10 个 probe 级命中样本
（最大分块 4/5/13/15/22/23/35/303）按场景排除。**无非 classic 误报**。

**验证（`1.21.1_fabric_I_p5fix1`，`P5_TAKEOVER = true`）**

驱动确实在跑（避免重演 §9.8 的"配错开关"）：`[SHADOW_TICKET] bound instance` ＋
`[SHADOW_TICKET] reconcile dim=… center=(-3, 0) band=10..16 +64 -0 live=64 desired=636`。

| | R1 | R2（旧口径对照） |
|---|---|---|
| `clientCache.actualPresent` | **1529**（完整盘：`isChunkInRange(20)` 全量） | **453**（完整盘） |
| 封闭空洞 | **0** | **0** |
| `boot grid primed` | `around (0,0) radius=20 cells=1529 redeliver=0` | `around (-3,0) radius=10 cells=0 redeliver=453` |
| 修复前的同一颗种子（`band1`/`band2`/`ot1`） | 1520 ＝ 缺 9 | 444 ＝ 缺 9 |

`=== RESULT: PASS ===`（exit 0，analyzer exit 0，failures 0 / warnings 0——连原先的
`SPATIAL_SNAPSHOT_INCOMPLETE`（那四只角）也一起消失，是独立旁证）。补发确实生效：
`[SHADOW_TRACK] authority gate starved N chunks beyond 3000ms -> pull anyway`，R1 里从 128/批递减到 7/批。

**回归（`1.21.1_fabric_I_p5off2`，`P5_TAKEOVER = false` 生产态）**：`=== RESULT: PASS ===`，
failures 0 / warnings 0，R1 1636 / R2 453、封闭空洞均 0；`[SHADOW_TICKET]` **0 行**（§9.8 留下的哨兵——
无条件首绑行不出现即证明驱动真的没跑，也证明常量内联没有把 `false` 陈旧地留在依赖类里），
`authority gate starved` **0 次**。这一轮 `hash-hit = 37` 而 `authoritative-full pull = 13`——**基准盘铺盘时
让位门还开着**，影子端自己的 pull 正常发出去了，所以新兜底没被唤醒。

**一个必须记下来的观察（它也解释了两次修复后跑法的机理为何不同）**：`p5fix1` 那轮 `hash-hit = 49` 而
`authoritative-full pull = 15`，补发日志显示**整盘 1529 柱都走了一次「超宽限补发」**——那一轮让位门在
基准盘还在发的时候就关了。同一个二进制、同一个场景、同一份存档，两轮的门开合时机不同，于是"谁把地铺满"
就不同。这既坐实了**落位点空洞本质是竞态**（见上表），也说明**兜底不是罕见安全网**：在门关上的那些轮次里
它就是初始填充的主力。判定因此更硬——把地铺满的仍然是影子端自绘的 pull，服务端声明在这条路径上没有承担装载。
`p5fix1` 的 R2 `cells=0` 是 R1 已把缺的 9 柱补齐并落盘的结果，故那一轮没有直接重演「服务端声明 443 柱、
恰好不含落位点 3x3」那一段（R1 已覆盖同一机理：盘面枚举出"未注入"、让位门扣住、超宽限补发）。

**树状态**：`P5_TAKEOVER = false`（收尾态，见下）。`GATE_STARVE_GRACE_MS` 与 `_hole_check` 常驻——
前者是生产路径上的静默丢数据兜底（让位门在正常运行时同样会关，只是时机随机），后者是常驻冒烟门禁。

**判定（P5 系列到此收束）**：§9.8 的"数据面打平"是在**影子端自己的 pull 驱动仍然在场、且让位门恰好没拦住**的
前提下测出来的；把这场空洞补上后，填洞的仍然是影子端自绘的 pull，**而不是**服务端声明。也就是说
「权威边沿之后，影子端的选柱/拉取是冗余的、可以裁掉」这个 P5 前提**不成立**——声明集合覆盖的是
"服务端本来会推送的那批"，登录期已推、此后不再声明的柱不在其中。

> **不要把空洞记到接管账上**：`_band1` 是基线等价配置（`[SHADOW_TICKET] = 0`、tracking 未钝化）却同样有洞
> ——那是**让位门的竞态**，接管不是成因（上一轮"与 P5 接管强相关"的说法已按上表作废）。
> 接管保持关闭另有其自身理由：§9.8 记录的移动场景 OVD 补票速度不足（`bandmove2` 28% 未命中）、
> 收尾 `saveAll` 停滞、以及大票突发下的运行期原生终止（`0xCFFFFFFF`）。

故 `P5_TAKEOVER` 保持 **false**，且**没有跑满矩阵**（只有 1.21.1 fabric 的 classic + move）。驱动与插桩代码
保留但默认关闭；下一轮若要做，前置条件是把"登录期已推柱"纳入服务端声明（或明确让客户端对这批柱保留兜底拉取）。








