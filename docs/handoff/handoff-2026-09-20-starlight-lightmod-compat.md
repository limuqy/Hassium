# 交接：光照优化 mod（Starlight / ScalableLux）兼容

- **日期**：2026-09-20
- **状态**：缺陷 A **已修并提交**（`584884a6`）；缺陷 B（屋檐侧光）**已修并提交**（`04cf3bfd`）
- **相关记忆**：`project_lightmod_all15.md`、`project_eave_light_regression.md`
- **测试载体**：`1.21.1 fabric + ScalableLux-fabric-0.3.0-alpha.0.7-1.21.1.jar`（主）、`1.20.1 fabric + starlight-1.1.2`、`1.21.11 fabric + ScalableLux-0.3.0-alpha.0.3`（跨版本）

---

## §0 一句话结论

Starlight 血缘把光照**存在柱实例上**（`ExtendedChunk.getSkyNibbles()`），而原版把光照存在**引擎自己的 SectionPos 索引存储**里。
Hassium 的影子端从架构到代码都按「原版引擎」写的，于是踩了两个**独立**的坑：

- **A（已修）**：交付包里 93% 的柱整包 0 光 → 客户端读全 15（用户症状「装了光照 mod 后全光照都是 15」）。
- **B（未修）**：影子端算出的**屋檐侧光**缺失 → 地下/洞穴正常了，但屋檐偏暗（用户目视）。

A 修好之前 B **不可观测**（整包 0 光时客户端一律读 15），所以 B 是「解封的既有缺陷」，不是本次引入。

---

## §1 症状与口径

**用户症状**：装光照优化 mod 后「全光照都是 15」；远处区块全 15、出生点近处看着正常；`(-13,3)` y≈5 的地面洞穴内部全亮；手动挖洞封闭也全亮。

**量化口径（沿用）**：`[CHUNK_PROBE]` 的 `skyMid/skyW/skyE/skyN/skyS` 五点全 = 15 的比例，按「采样点是否在地表之下」分桶；脚本 `scripts/scan-light.ps1`。

| 场次 | Mod | 全 15 | 地表下全 15 |
|---|---|---|---|
| `1.21.1_fabric_I_mc_nomod_modcompat` | 无 | 5.0% | 5.0% |
| `1.20.1_fabric_I_mc_starlight` | Starlight 1.1.2 | 94.5% | 93.9% |
| `1.21.1_fabric_I_mc_lux` | ScalableLux a0.7 | 95.4% | 95.2% |
| `1.21.11_fabric_I_mc_lux` | ScalableLux a0.3 | 95.3% | 95.5% |

---

## §2 缺陷 A：交付包 0 光（**已修，已提交 `584884a6`**）

### §2.1 根因（两个独立成因，缺一即整包 0 光）【已验证】

1. **数据面**：`ShadowServerCompat.createNativeLightChunk` 造的是**一次性 ProtoChunk**。原版引擎的光在引擎自己的存储里，包构造器 `getLayerListener(SKY).getDataLayerData(sp)` 与柱实例无关，所以无所谓；**Starlight 血缘的光在柱实例自己的 nibble 数组上** → 算出的光落在 ProtoChunk，交付/注入柱 nibble 全 NULL。
2. **读取面**：`StarLightInterface.getDataLayerData` 用 `getAnyChunkNow → ServerWorldMixin.getAnyChunkImmediately → chunkMap.getVisibleChunkIfPresent(key)` 反查柱。**影子端注入柱大多不是「可见 holder」** → `chunk == null` → 26 段全返回 null → 整包省略。

客户端侧：Starlight 客户端 `setLightEnabled` / `lightChunk` / `propagateLightSources` / `checkBlock` 全被 `@Overwrite` 成 no-op，**客户端不再自算光**，保持 NULL nibble → `StarLightInterface.getSkyLightValue` 走「NULL 且 `emptinessMap == null` → return 15」出口 → 地下全亮。无 mod 时原版客户端自算光，所以一直看不出来。

### §2.2 修法（三处，+157 行）

| 文件 | 改动 |
|---|---|
| `compat/mods/ForeignLightEngine.java` | `nibbles(chunk, sky)` / `toVanillaNibble(nibble)` / `copyLightNibbles(from, to)`——**反射**实现，非 Starlight 血缘自动 no-op |
| `shadow/light/ShadowLightCompute.java` | 两处 `completeNativeLight(...)` 完成回调里调 `attachForeignLight(inf)`：把 LIGHT 步写在 native 柱上的 nibble 数组**搬回交付柱**（SWMR 对象，共享引用即可） |
| `shadow/light/SeedGenChunkCodec.java` | `writeForeignLight(packet, chunk, engine)`：包构造**之后**按**原版 `prepareSectionData` 同语义**（`toVanillaNibble()==null` → 两掩码都不置；`isEmpty()` → empty 掩码；否则 data 掩码 + `copy().getData()`；掩码升序 ↔ 载荷列表一一对应）用交付柱 nibble 重写 sky/block 两层掩码与载荷 |

**原版路径一行未动**：两个 helper 都以「拿得到 `getSkyNibbles()`」为门，非 Starlight 引擎下 `nibbles()` 返回 null，直接 return。

### §2.3 效果与复验

| 指标（1.21.1 fabric + ScalableLux a0.7，1400 包 / 1500 柱） | 修前 | 修后 |
|---|---|---|
| `[TEMP-DIAG-MASK] withLight` | 111 / 1400 | **1400 / 1400** |
| `avgSkyData / avgSkyEmpty / avgSkyOmitted` | 0 / 0 / 25 | **3 / 7 / 14** |
| 客户端 `noLightData` | 1431 / 1500 | **0 / 1500** |

`avgSkyOmitted=14` 正合预期：地表以上整段天光是 Starlight 的 NULL/HIDDEN，客户端保持 NULL → 读 15，本来就对。

| 场次 | 结果 |
|---|---|
| `1.21.1_fabric_I_mc_lux_verify` / `..._final` | **PASS**，`failures: []`，`clientDarkRegressionChunks=0` |
| `1.20.1_fabric_I_starlight_verify` | **PASS** |
| `1.21.1_fabric_I_nomod_modcompat` | PASS |
| `1.21.1_fabric_I_nomod_verify`（classic） | FAIL = **既有** `TRACE_ENCLOSED_HOLE`（无 mod 基线也中） |
| `1.21.11_fabric_I_lux_verify` | FAIL = **既有** `ClientNativeExitCode 0xC0000409` |

---

## §3 缺陷 B：Starlight 下屋檐侧光缺失（**未修**）

### §3.1 A/B（决定性）【已验证】

同场景、同种子、同 Hassium 影子端，**唯一变量 = 光照引擎**；探针从**交付包**里解 `(-13,3)` 东缘 `x=15 z=0 y87..93` 的天光：

```
无 mod（原版引擎）  87=13 88=13 89=13 90=13 91=13 92=13 93=13
装 ScalableLux      87=0  88=0  89=0  90=0  91=0  92=0  93=0
```

- 用户另做对照：**同种子原版 + 同版本 ScalableLux 屋檐正确** → 排除 mod 本身。
- `writeForeignLight` 只做搬运、不改亮度 → 排除交付侧。

### §3.2 根因链（源码级，仓库 `D:\project\MC\ScalableLux-ver-1.21.11`）

1. `StarLightEngine.light()`（L907–930）：**全量重算**——`getFilledEmptyLight()` 造新数组塞进 cache → `lightChunk(...)` → `setNibbles(chunk, nibbles)` 写回柱。
2. `setupCaches()`（L175–211）：邻柱经 `chunkProvider.getChunkForLighting(cx,cz)` 取（**不是** `getAnyChunkNow`，所以「邻柱找不到」不是机制），随后
   `this.setNibblesForChunkInCache(cx, cz, this.getNibblesOnChunk(chunk))`——**把邻柱当前 nibble 快照放进 cache**。
3. **`SkyStarLightEngine.canUseChunk`（L230–233）**：
   ```java
   return chunk.getPersistedStatus().isOrAfter(ChunkStatus.LIGHT)
       && (this.isClientSide || chunk.isLightCorrect());
   ```
   `setupCaches` 对 `!canUseChunk(chunk)` 的邻柱 **`continue`（不进 cache）**。

⟹ **Starlight 只认「已 LIGHT 且 `isLightCorrect()==true`」的邻柱**。而 Hassium 的 S5 只在 3×3 齐套时置真 → 「先算完的邻柱」在自己那次重算里看不到本柱的光 → 屋檐（光全靠邻柱横向进来）永远拿不到侧光。

**实测补充**：影子端注入柱的 `getPersistedStatus()` 全是 **`minecraft:full`** → 状态子句**成立**，约束完全在 `isLightCorrect` 上；且实测 `lightRan ⟺ isLightCorrect`（两者一一对应）。

### §3.3 已试无效（**勿重试**）

| 尝试 | 结果 |
|---|---|
| `expandEmptyLightSections`（empty 掩码段搬进 data 掩码） | **前提错**：`readSectionList` 里 `queueSectionData` 只有**一处**调用点，ScalableLux 的 `@Redirect(ordinal=0)` 把 data/empty 两种都覆盖了，empty 段本来就被正确写成 UNINIT=0 |
| `isLightCorrect` 临时置真（只为解开 reader 的读光门） | 必要但不充分，单打无效（`flipped=1290` 仍 `skyOmitted=26`）；A 修好后已删除 |
| `createNativeLightChunk` 里外部引擎下强制 `lit=false` | 客户端 `noLightData` 1459→1425，目视无变化 |
| 单点 `checkChunkEdges(x,z)`（Starlight 只在 `lit=true` 分支调它，我们走 `lit=false`） | 读数不变 |
| 本柱 + 3×3 全调 `checkChunkEdges` | `edge ok=3447 fail=0`（调用全部成功）读数仍 0——`checkChunkEdges` 内部同样走 `setupCaches`，邻柱照样被 `canUseChunk` 跳过 |
| 晋升后重算 3×3（走 `enqueueInjectedForLight`） | `pushed=4` 有推送，读数不变——**因为该入口对 L1 柱短路成 REUSE（只重发缓存包、不重算）** |
| 晋升后**强制 RECOMPUTE**（`initializeLightImmediately` + `LightNeighborhoodGate.enqueue(..., RECOMPUTE, ...)`） | 读数仍 0，且**探针柱全场只打了 1 行**（只被构建一次）→ 该柱没走到「带新光的重算-重交付」 |

### §3.6 修法（**已修，`04cf3bfd`**）

**核心洞察**：`canUseChunk` 要的是「**数据可用**」，S5 要的是「**可安全落盘**」——**同一个 `isLightCorrect` 被两种语义共用**。既然不能动 S5，就**只在 LIGHT 步期间**把 3×3 邻柱临时标成可用，跑完立刻还原。

`ShadowLightCompute`：

- `relaxNeighborLightFlags(inf)`：外部引擎下，把 3×3 邻柱（`server.injectedChunk`）中 `isLightCorrect()==false` 的**临时置真**，返回原值 map；
- `restoreNeighborLightFlags(prev)`：在 `completeNativeLight` 的完成回调里**第一件事**还原；
- 挂在**两处** LIGHT 入口：phase-1 复用分支（`relaxed1`）与从零分支（`relaxed2Box`——lambda 内赋值，用单元素数组持有）。

**为什么这样能成立**：邻柱 nibble 若确实还没算过（全 NULL），本柱照样拿不到那一侧的光——**那是对的**（无光可给）；一旦邻柱算过（哪怕未被标 clean），本柱就能拿到。于是「算光」不再依赖「邻柱先晋升」。

**验收（同场景同种子，`[TEMP-DIAG-EAVE]` 从交付包里解 `(-13,3)` 东缘 `x=15 z=0 y87..93`）**：

```
无 mod（参照）  87=13 88=13 89=13 90=13 91=13 92=13 93=13
修前 装 lux     87=0  88=0  89=0  90=0  91=0  92=0  93=0
修后 装 lux     87=13 88=13 89=13 90=13 91=13 92=13 93=13   ← 两场独立重复一致
```

`lightCorrect=false` 出现在修后读数里 → 标志确已还原，S5 语义未被动过。

**冒烟复验**：`1.21.1_fabric_I_mc_lux_final2` PASS、`1.21.1_fabric_I_nomod_final`（modcompat）PASS、`1.20.1_fabric_I_starlight_final` PASS。

### §3.7 已废弃的中间方案（**勿再试**）

「某柱被置 `isLightCorrect` 后重算它的 3×3」：实现后实测无效，原因是
① `enqueueInjectedForLight` 对 `isLightCorrect()==true` 的柱**短路成 REUSE**（只重发缓存包、不重算）；
② 改成强制 `RECOMPUTE` 后仍无效，且探针柱全场只被交付一次；
③ 更根本的是**探针柱的交付路径逐场不同**（走门 / 不走门只 pull / 完全不交付三种形态都出现过），
   在那种覆盖下做 A/B 极易得出「改法无效」的假结论。**该方案已从代码中删除**。

### §3.4 最后一个岔口（下一步就查这个）

探针柱 `(-13,3)` **只被交付过一次**，所以要么没被推到重算、要么被门扣住没走到打包。两种可能，**一条日志可分辨**：

- **A**：`(-13,3)` 从未被推（关键邻柱一直没晋升到 L1，或它不在被推的 3×3 里）；
- **B**：被推了，但 `LightNeighborhoodGate.enqueue` 把它扣在门里等自己的邻柱，直到 85s 跑完都没放行（注意门里可能还有 `wasPromotedClean` 之类的短路）。

**诊断做法**：
1. 把**被推的坐标**全打出来（现有 `TEMP-DIAG-RELIGHT` 被 `<= 3` cap 掉了，改成只对 `(-13,3)` 及其实时邻域打）；
2. 给 `(-13,3)` 单独打一行时序：`被推 → 进门 → 出包`（三个点各一行，带时间戳）。

**实测补充（2026-09-20 续，eaveI / eaveJ）**：

- 探针柱 `(-13,3)` 的交付**路径逐场不同**：`eaveH` 走门（`[LIGHT_GATE] Enqueue → Blocked → Promote` + `barrier reuse phase-1`），
  而 `eaveI` / `eaveJ` **全场没有该柱的任何 `[LIGHT_GATE]` 行**（只有一条 `[SHADOW_PROVIDER] enqueue pull ... reason=TRACKING`）。
  ⟹ **A/B 的「lux = 0」是在哪条路径上量的必须先钉死**，否则改了也可能量到另一条路径。
- 探针柱的交付事实（eaveJ）：`lightCorrect=false ran=true` —— 即 **LIGHT 跑过、但从未被标 `isLightCorrect`**（S5 扣下 / 从未 clean）。
  这同时意味着它自己**不是** Starlight 眼中的可用邻柱，而它要拿侧光靠的是**它的邻柱**；
  邻柱若普遍还是 `L0`（实测 8 邻中只有 2~4 个 `L1`），每次重算都拿不到侧光。
- **待判定**：邻柱陆续晋升时，探针柱是否真的被推重算、以及推了是否出包（本场它只被构建 1 次）。


### §3.5 可能的修法方向（**尚未验证，需先判定 §3.4**）

- 若为 A：关键邻柱的晋升时机太晚/不存在 → 要么让晋升更快（当前 `pushed` 只覆盖 8 邻中已 L1 的部分），要么接受「前沿柱结构性偏暗」（与既有 `outsideWindow` 残差同源）。
- 若为 B：门内的短路把重算挡掉 → 给「外部引擎 + 本次由晋升触发」的重算开一条**显式的强制通道**（仍走齐套门，但跳过 `wasPromotedClean` 短路）。
- **反向做法（把门的判据改成要求邻柱 `isLightCorrect`）会死锁**：非 clean 柱不置真 → 链上无起点 → 只能等超时降级，且降级柱不置真 → Starlight 永远跳过它们。

---

## §4 复现配方

```powershell
# 装/卸 mod：fabric/run/{client,server}/mods/
#   ScalableLux-fabric-0.3.0-alpha.0.7-1.21.1.jar   （1.21.1）
#   starlight-1.1.2+fabric.dbc156f.jar              （1.20.1）

# 主复现场景（lux）
.\scripts\runtime-smoke-test.ps1 -Ver 1.21.1 -Loader fabric -Phase I `
    -SessionId "1.21.1_fabric_I_mc_lux_X" -Scenario modcompat -CleanWorld

# 参照场（无 mod，同场景同种子）
# 先把 mods 下 jar 挪走，再跑同一命令，SessionId 换 nomod
```

**关键探针**（临时加在 `SeedGenChunkCodec.buildPacket` 里，定位完必须删）：

```
[TEMP-DIAG-MASK]   built=N withLight=M noLight=K avgSkyData/SkyEmpty/SkyOmitted ...
[TEMP-DIAG-APPLY]  received=N withLight=M ...                       （客户端 handleLevelChunkWithLight 入口）
[TEMP-DIAG-CLIENT] chunks=N noLightData=K (near<=8 / far>8) ...     （客户端逐柱 sky nibble 非 NULL 计数）
[TEMP-DIAG-EAVE]   (-13,3) packet sky x=15 z=0: 87=.. .. 93=..      （屋檐口径，**从包里解**）
[TEMP-DIAG-RELIGHT] promoted (x,z) self=status/L? pushed=N [...邻柱 facts...]
```

---

**离线取证脚本**（影子缓存 type-126 落盘字节，不需要跑游戏）：

```bash
python scripts/dump-shadow-light.py <region_dir> [limit]
# region_dir 例：fabric/run/client/hassium_cache/server_127.0.0.1_25565/world/region
```

用 `common/src/main/resources/assets/hassium/hassium-dictionary.bin` 作 zstd 字典解 type 126 →
走 NBT → 统计 `sections[].SkyLight`（all-15 / all-0 段占比 + nibble 直方图）。
**注意口径**：Starlight 血缘**不把光写回 chunk section 数组**，所以这个脚本适合看「落盘产物形态」，
**不适合**判断「交付包里有没有光」——后者必须用上面的 `[TEMP-DIAG-*]` 探针。

---

## §5 测量纪律（**本次踩过的坑，务必遵守**）

1. **两侧同时量**：只量一侧分不清「包没带光」与「带了但客户端没落地」。交付点掩码 + 客户端 nibble 状态必须成对读。
2. **指标必须对得上机制**：曾用「影子缓存带光 section 数（217→216）」判定修复无效——但 Starlight **不把光写回 chunk section 数组**，该指标对「包里有没有光」完全不敏感，等于白测。
3. **一次性探针会抓错时机**：必须按柱累计 + 多点汇总（1/200/600/1000/1400）。
4. **`SWMRNibbleArray.isNullNibbleVisible()` 只看 `INIT_STATE_NULL`（HIDDEN 不算 NULL）**，所以「非 NULL nibble 数」≠「包内段数」；要分 UNINIT/INIT/HIDDEN 就得按四态分别数。
5. **影子侧 `ShadowLightProbe` 在 Starlight 下是坏的**：`LevelChunk.getSkyLightSources()` 返回 null（Starlight 的 `ChunkAccessMixin` 把 `skyLightSources` 置 null 且 `@Redirect` 掉 `initializeLightSources`）→ NPE 自停。屋檐这类判据**必须从交付包里解**。
6. `LIGHT_CALL=0` 不能判断 `lit`（打点在父类，被 `@Overwrite` 抹掉）；`foreignLightEngineActive` 探的是**客户端**引擎，不是影子端。
7. **不要只看冒烟 PASS**：本次 5 场光照 mod 冒烟有 3 场 PASS。

---

## §6 工作区状态与回退

- **已提交**：`584884a6 fix(shadow): deliver light from the chunk that actually holds it`（A 的修复，三文件）。
- **未提交**（B 的探查 + 临时诊断）：
  - `shadow/light/ShadowLightCompute.java`：`relightNeighborsAfterPromotion`（强制 RECOMPUTE 版）+ `TEMP-DIAG-RELIGHT` / `statusAndFlag`
  - `shadow/server/ShadowSeedServer.java`：`syncLightCorrect` 置真分支里的调用
  - `shadow/light/SeedGenChunkCodec.java`：`TEMP-DIAG-EAVE` 探针
- **整体回退 B 的探查**：
  ```powershell
  git checkout -- common/src/main/java/io/github/limuqy/mc/hassium/shadow/server/ShadowSeedServer.java `
                  common/src/main/java/io/github/limuqy/mc/hassium/shadow/light/ShadowLightCompute.java `
                  common/src/main/java/io/github/limuqy/mc/hassium/shadow/light/SeedGenChunkCodec.java
  ```
- `gradle.properties` 的 diff 是**本次会话开始前就有的**，与本次改动无关。

---

## §7 待办

1. **[B-1]** 按 §3.4 打「被推坐标 + 探针柱时序」，判定 A / B。
2. **[B-2]** 按判定结果选 §3.5 的修法；验收用 `[TEMP-DIAG-EAVE]` 从 `0` 回到 `13`，且 `clientDarkRegressionChunks=0`。
3. **[B-3]** 修好后跑跨版本复验（1.20.1 + Starlight、1.21.11 + ScalableLux）与无 mod 回归。
4. **[清理]** 删除全部 `TEMP-DIAG-*` 探针与 `relightNeighborsAfterPromotion` 的 `statusAndFlag` 诊断。
5. **[文档]** 修好后把 `docs/mod-compat.md` §7b 的「外部光照引擎」一节补齐（`canUseChunk` 语义 + `copyLightNibbles` / `writeForeignLight` 两条适配）。
6. **[遗留]** 1.21.11 `0xC0000409`、无 mod classic `TRACE_ENCLOSED_HOLE` 是既有缺陷，与本次无关，另行处理。
