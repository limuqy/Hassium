# Handoff：type-126 槽内持久化「逐段 hash + 平面综合征」基线（只客户端）

**状态**：规划完成，未开工。
**目标**：让客户端**不物化**（不解压整柱）就能发出**逐段 + 平面**的 Compare+Pull 基线，
从而进服/重连时省掉「每个被拉的柱都解压一次」的 CPU 开销。

---

## 1. 背景与已完成的修复（起点）

2026-09-20 追查「1.21.1 R2 重连重下 ~410 柱」，已落三个 commit：

| commit | 内容 |
|---|---|
| `b1542dca` | `hasLocalPullBaseline` 增加第三档：盘上嵌入的 8B 柱级 hash 也算基线（只读，不解压） |
| `cfddf107` | 服务端 `ChunkAuthorityHashes` 真正接上（逐段数组）+ 关停清理/统计 |
| `f501fabe` | `drainAuthorityAcquires` 里对「盘上有柱但未注入」的柱**先读盘物化**再 compare |
| `a964f33e` | ① `ShadowPullClient.request` 这个**唯一收口点**也补「先物化」（渲染线程跳过）；② `REQUEST_MODES` 改有界 LRU（旧「满了整体 clear」使 `comparedBaseline` 恒 null） |

**实测（1.21.1 fabric classic，VD20→10 重连，R2 轮）**：

| | 最初 | `f501fabe` | **`a964f33e`（清存档）** | **`a964f33e`（不清存档）** |
|---|---|---|---|---|
| `fullChunkRequestCount` | 257 | 73 | **0** | **0** |
| `cacheDeltaCount` | 21 | 182 | **289** | **11** |
| `actualBytesReceived` | 1.80 MB | 0.94 MB | **0.41 MB** | **6.8 KB** |

1.20.1 单独回归（`1.20.1_fabric_I_regress1`）：**PASS**，整柱 4→**0**，实收 47.8 KB→**32.7 KB**，无回归。

⇒ **功能已达标**；剩下的唯一代价是「compare 前要解压」。本 handoff 就是消掉它。

### 1.1 为什么现在做

用户口径：**先做这个，再跑 L1 全矩阵**（否则改完格式要重跑矩阵）。

### 1.2 三个已定决策（用户拍板）

1. **方案 B**：持久化 **section hash + 每非空段 48×u32 平面综合征**（磁盘容量可接受）。
2. **不兼容老格式**：2.0.0 未上线、开发阶段，区块文件本就不兼容 ⇒ **不需要双 magic 兼容读**，
   可以只认新格式（但仍保留 magic 字节作为**有效性校验**，见 §3）。
3. **只做客户端**：服务端内容变化频繁、维护成本高，不做；收益在客户端（进服更快、CPU 更省）。

---

## 2. 现状：要持久化的两个形状

`SectionDeltaSnapshot`（`protocol/sectiondelta/SectionDeltaSnapshot.java`）：

- `long[] sectionHashes` —— 长度 = **柱的 section 总数**（主世界 24）；空气段 = `0`
- `int[][] planes` —— 同长度；空气段 = `null`；非空段 = `int[48]`

产生方式（**唯一权威，不得另起口径**）：

- section hash：`ChunkContentHashUtil.computeSectionHash(section)`（内部走 `writeSectionForHash`：
  逐位置完整 BlockState、不含光照）；**为 0 时被替换成 1**（避免 0 当哨兵）
- 平面：`SectionPlaneSyndrome.compute(SectionDeltaSnapshot.readCells(section))`，
  `PLANE_COUNT = 48`（`[0..15]=X, [16..31]=Y, [32..47]=Z`），每格 `xxHash32(seed=0)`，
  单元顺序 `index(x,y,z) = (y<<8)|(z<<4)|x`

**体积**：`192 B`（24 段 hash）+ `201 B × 非空段数`（1 段索引 + 8 段 hash + 192 平面）。
典型 10 个非空段 ⇒ **≈2.0 KB/柱**。

> ⚠ **xxHash 输出是高熵的，ZSTD 压不动（≈1:1）**。所以这 2 KB 是**实打实的磁盘增量**，
> 不要指望「塞进同一个 ZSTD 帧就变小」。这是已接受的成本（决策 1）。

---

## 3. 格式规格（type 126 v2）

### 3.1 现状（v1，写在这里方便对照）

`HassiumType126Codec`：

```
sector  = [len(int32 BE)][type(1B)=126][payload...]
          len = 1 + payload.length            // 含 type 字节
payload = [0x48][hash(8B BE)][ZSTD(nbt)]      // contentHash != null
payload = [ZSTD(nbt)]                         // contentHash == null（更老的 126）
```

`HASH_MAGIC = 0x48`、`HASH_LENGTH = 8`；`payloadAfterType(sector)` 剥掉前 5 字节再取 `len-1` 字节。

### 3.2 v2（新 magic `0x49`）

```
payload = [0x49][hash(8B BE)][formatVersion(1B)][columnSectionCount(u16 BE)]
          [entryCount(u16 BE)][entry × entryCount][ZSTD(nbt)]

entry   = [sectionIndex(1B)][sectionHash(8B BE)][plane(48 × u32 BE)]   // 201 B
```

- `formatVersion = 1` —— **为将来改 `SectionPlaneSyndrome.index` 单元顺序/口径留失效口**
  （读到未知版本 → 当作无基线，绝不用旧表）
- `columnSectionCount` —— 必须是 `chunk.getSections().length`（读侧据此分配数组长度）
- `entry` 按 `sectionIndex` **升序**；只写非空段
- `plane` 是 `int` 的 **big-endian u32**；`plane == 0` 是**合法哈希值**，不得当「缺失」
  （存在性由 entry 决定）

**读侧数组重建**（必须与 `capture` 逐字等价）：

```java
long[] hashes = new long[columnSectionCount];
int[][] planes = new int[columnSectionCount][];
for (entry : entries) {
    hashes[entry.idx] = entry.sectionHash;      // 原样，不再做 0→1 替换
    planes[entry.idx] = entry.plane;            // 48 个 int
}
new SectionDeltaSnapshot(hashes, planes);
```

**有效性校验（不是兼容，是防误读）**：

- magic 不是 `0x49`（含 v1 的 `0x48`）→ **当作无基线**（`probeLocalHash` 返回 `NO_HEADER` 语义）
- `formatVersion != 1`、`entryCount`/`columnSectionCount` 越界（如 `> 64`）、
  载荷长度不够、`sectionIndex` 非升序/重复 → **当作无基线**（丢弃，绝不部分采用）
- 若读侧能拿到活柱/维度的真实 section 数且与 `columnSectionCount` 不符 → **丢弃**

---

## 4. 安全性不变量（这条错了会**静默内容错误**）

> **表必须与它所描述的那份内容同源同代，并写进同一个槽。**

反例后果：客户端存的段 hash == 真服段 hash，但客户端**本地那段内容** ≠ 真服 ⇒
服务端 `planSection` 在该段判 `SKIP` ⇒ 客户端**保留错内容**，没有任何报错。
（同一条不变量现有 8B 柱级 hash 也在依赖。）

### 4.1 怎么保证同代：**柱级 hash 相等即同代**

写入点已经有 `hash`（`write.hash` 或 `ShadowStorageHashes.get(dimension, pos)`），
而 memo 里的快照可以**现组合**出柱级 hash：

```java
long snapHash = ChunkContentHashUtil.combineSectionHashesFromArray(snap.sectionHashes());
if (snapHash == hash) {
    // 同代：安全写表
} else {
    // 不同代：不写表（或从「正在被序列化的那个活柱」现捕获一次）
}
```

两者是同一个函数、同一份内容 ⇒ 相等即同内容。**这一步是必须的**，不能因为
「memo 里有个快照」就直接写。

### 4.2 表的数据来源优先级

1. `SectionDeltaSnapshots.get(dimension, pos)` 且 §4.1 校验通过 → 用它（**零成本**，首选）
2. 校验不过 → 若写入点手里有**正在序列化的那个 `LevelChunk`**，现
   `SectionDeltaSnapshot.capture(chunk)`（贵，但正确；flush 是后台/批量，可接受）
3. 两条都不行 → **写 v1 格式（`0x48`，无表）**，读侧自然回落 = 今天的行为

---

## 5. 改动清单（逐锚点）

### 5.1 `HassiumType126Codec`（`shadow/storage/`）

- 新增 `SECTION_MAGIC = (byte) 0x49`、`FORMAT_VERSION = 1`
- `encodeSector(byte[] rawNbt, Long contentHash, SectionDeltaSnapshot sections, int zstdLevel)`
  —— 新增重载；`sections == null` 时**退化为现有 v1 写法**（不写表）
- `probeHash(byte[] rawAfterType)` —— 扩为同时接受 `0x48` / `0x49`
  （只认 magic + 读 8B hash；**不解析表**，保持「不解压、O(1)」）
- 新增 `probeSections(byte[] rawAfterType)` → `SectionDeltaSnapshot or null`
  （只做 §3.2 的解析与校验；**不解压 ZSTD**）
- `decode(byte[] rawAfterType)` —— 按 magic 决定表头长度后再解压
- `reencodeVanillaToHassium(...)` —— 透传 `sections`
- **保持类纯净**：不读 `RuntimeServerContext`；是否写表由**调用方**决定（见 5.4 门控）

### 5.2 `RegionCache.Image`

- 先**确认** `probeHash(int index)` 的实现（`RegionCache.java` 里搜 `probeHash`）：
  - 若它是**解析 `payloads[index]` 字节** → `probeSections(index)` 就是
    `HassiumType126Codec.probeSections(payloads[index])`，**无需新增状态**（首选）
  - 若它读的是**旁挂数组** → 需要新增一个并行的 `SectionDeltaSnapshot[]` 槽位数组，
    并确保 `writePayload(...)` 与 `save(Path)` 一并落盘/重建（成本更高，务必先看清楚）
- `writePayload(...)`：若走「解析 payload」路线则**不需要改签名**（表在 payload 里）；
  否则要扩签名带上表

### 5.3 写入侧（两处）

| 位置 | 现状 | 要做的 |
|---|---|---|
| `MixinRegionFile.hassium$adoptShadowPayload`（≈:290-310） | `encodeSector(rawNbtData, storedHash, level)` | 传 `sections`（按 §4.2 取/校验/回落） |
| `MixinRegionFile.hassium$adoptShadowSector`（≈:336） | `reencodeVanillaToHassium(compressionType, payload, hash, level)` | 同上 |
| `MixinRegionFile` ≈:474 | `encodeSector(rawNbtData, storedHash, level)` | 同上 |
| `ShadowStorageManager.encodeDirtyOnThisThread`（≈:926） | `encodeSector(nbt, hash, zstdLevel)` | 同上；此处 `write.nbt` 可能由 `serializer.serialize(write.pos)` 现生成 —— **优先用 §4.1 的 hash 校验**，必要时现捕获 |

> 建议把「取表 + 校验 + 回落」抽成**一个** static helper（放在 `HassiumType126Codec` 之外的
> 一个小类，或 `ShadowStorageManager`），四处共用，避免四份近似逻辑漂移。

### 5.4 门控（只客户端）

- 服务端**不写表**：在 helper 里加
  `if (!RuntimeServerContext.isShadowServerContext()) return null;`
  （与 `ChunkAuthorityHashes.putSections` 的影子端拒写同一风格）
- 建议同时在 `encodeSector` 的 v2 分支里**再兜一层**拒写（把不变量从假设变强制），
  代价一次 volatile 读

### 5.5 读取侧

- `ShadowStorageManager.probeLocalHash(dimension, pos)`
  —— 命中时**顺带**解析表并 `SectionDeltaSnapshots.put(dimension, pos, snapshot)`
  （`Kind` 枚举不变；`0x49` 与 `0x48` 都算 `OK`）
- `ShadowLightCompute.localPullEntry(dimension, pos)`
  —— `chunk == null` 分支**不再直接返回空基线**：

  ```java
  SectionDeltaSnapshot snap = SectionDeltaSnapshots.get(dimension, pos);
  if (snap != null) {
      // 与 chunk != null 分支同样的组装：sectionHashes + planes
      return new Entry(pos.x, pos.z, localHash, toList(snap.sectionHashes()), snap.planes(), 0);
  }
  // 否则维持现状（只有柱级 hash）
  ```

  ⚠ 注意 `Entry` 的 planes 字段类型（现在是 `int[]` 还是 `int[][]`？按
  `ShadowPullRequestC2SPacket.Entry` 实际定义填；`localPullEntry` 的 `chunk != null` 分支
  已是现成参照）
- `SectionDeltaSnapshots.get(String, ChunkPos)` —— **当前仓库里没有**，需要新增（只读 peek，
  不触发捕获）

### 5.6 可选的第二步：撤掉 A1 的物化（**单独一场验证**）

`ShadowPullClient.materializeForCompare` / `ShadowTrackingSession.MATERIALIZE_BEFORE_COMPARE`
在本改动落地后对**决策**已无必要（表直接给出基线）。但它在泵侧还影响**交付路由**
（先物化 → 走 material 分支的 compare-before-deliver）。所以：

- **不要和本改动同一 commit 撤**；本改动落地并验证后，再单独把
  `MATERIALIZE_BEFORE_COMPARE` 置 false 跑一场，看指标是否不变
- 若不变 → 删除该开关与 `materializeForCompare`（**这才是真正的 CPU 收益兑现**）

---

## 6. 验收口径（可量化，必须逐条给数）

1. **基线不再依赖物化**：`clientSentEmpty`（需要重新挂 §7 的 dump 探针或等价计数）→ **0**
2. **渲染线程路径也能发段基线**：新增计数「从盘表取到基线的 pull 条数」> 0，
   且该计数**包含**渲染线程来源（可加线程名到日志验证一次）
3. **不退化**：R2 `fullChunkRequestCount = 0`、`actualBytesReceived` 不高于
   `a964f33e` 对照（清存档 0.41 MB / 不清存档 6.8 KB）
4. **A1 可关**：`MATERIALIZE_BEFORE_COMPARE=false` 时指标与 true 一致（§5.6）
5. **回落正确**：手工造一个 v1（`0x48`）槽 → 读侧当无基线，行为 = 今天
6. **L0**：`common:test` 新增 codec 往返用例 —— 覆盖：空柱（全空气）、单非空段、
   `plane` 含 0 值、`sectionIndex` 乱序/越界被拒、`formatVersion` 未知被拒
7. **冒烟**：1.21.1 + 1.20.1 fabric classic 各一场 **PASS**，再跑 L1 全矩阵

### 6.1 复现命令

```powershell
.\gradlew.bat common:compileJava fabric:compileJava -Pmc_ver=1.21.1
.\scripts\runtime-smoke-test.ps1 -Ver 1.21.1 -Loader fabric -Phase I -SessionId 1.21.1_fabric_I_v2a -CleanWorld -DelayMs 20000 -ReconnectDelayMs 3000 -ClientTimeoutSec 600 -ServerReadyTimeoutSec 300 -ClientRenderDistance 20
# 不清存档对照（同一个 SessionId 前缀再跑一次，去掉 -CleanWorld）
```

指标读 `build/smoke-test/probe/<SessionId>/round2.json`。

---

## 7. 需要重新挂的探针（本次已撤）

- 服务端 `[DIAG-DELTA]`：`clientSecs / clientNonEmpty / serverNonEmpty / differing / deltaSnapNull`
  —— 验证 §6.1 时用来确认「客户端确实带了段基线」
- 客户端 `FullDumpProbe`（两侧快照落 `%TEMP%\hassium_full_dump\`）—— 若要逐段比对
  **注意**：不要用 `comparedBaseline`（`REQUEST_MODES` 回查）当门，它在旧实现下**恒 null**；
  按「本地有没有那份」自筛（`a964f33e` 已修 LRU，但仍建议自筛更稳）

---

## 8. 风险与回退

| 风险 | 处置 |
|---|---|
| **陈旧表 → 静默内容错误**（最严重） | §4.1 柱级 hash 同代校验 + §3.2 全量校验 + 服务端拒写 |
| 槽位膨胀（+≈2 KB/柱，扇区数上升） | 已接受；但**必须**检查 `RegionCache.Image.save` 的 `locations` 计算、`needsExternalSectors`（>1 MiB）阈值、以及 region 文件大小上限是否仍安全 |
| `SectionPlaneSyndrome` 单元顺序将来若变 | `formatVersion` 失效口（§3.2） |
| 写入点四处逻辑漂移 | 抽一个共用 helper（§5.3） |
| 表解析开销 | 解析是 O(非空段数×48) 的纯字节读，**不解压**；相比省掉的整柱解压是净赚 |

**回退**：把 helper 的门控置 false（只写 v1）即可整体退回；读侧天然忽略无表槽。

---

## 9. 待确认项（开工前先看代码，不要凭本文档假设）

1. **`RegionCache.Image.probeHash` 是解析 payload 还是读旁挂数组** —— 决定 5.2 的工作量
   （搜 `RegionCache.java` 里的 `probeHash` / `writePayload` / `payloads`）
2. **`ShadowPullRequestC2SPacket.Entry` 的 planes 字段类型** —— 决定 5.5 的组装写法
3. **`ShadowStorageManager.encodeDirtyOnThisThread` 里 `serializer.serialize(pos)` 与
   `write.hash` 的关系** —— 决定 §4.2 的 2/3 档怎么走
4. **`SectionPlaneSyndrome.validPlanes`** 的判据（长度？非 null？）—— 读侧重建的数组必须过它，
   否则服务端会把该段判 `Kind.FULL`（不致命，但白丢 BLOCKS 收益）

---

## 10. 锚点速查

| 作用 | 位置 |
|---|---|
| type 126 编解码 | `shadow/storage/HassiumType126Codec.java`（`encodeSector` / `decode` / `probeHash` / `payloadAfterType`） |
| region 映像槽 | `shadow/storage/RegionCache.java`（`Image.probeHash` / `writePayload` / `save` / `readDecompressed`） |
| 影子写槽三处 | `mixin/shadow/MixinRegionFile.java` ≈:297 / :336 / :474 |
| 影子批量写槽 | `shadow/storage/ShadowStorageManager.java` ≈:926（`encodeDirtyOnThisThread`） |
| 盘基线探活 | `ShadowStorageManager.probeLocalHash` + `Kind` 枚举 |
| 快照 memo | `protocol/sectiondelta/SectionDeltaSnapshots.java`（`getOrCapture` / `put`；`get` 待新增） |
| 快照本体 | `protocol/sectiondelta/SectionDeltaSnapshot.java`（`capture` / `readCells`） |
| 平面综合征 | `protocol/sectiondelta/SectionPlaneSyndrome.java`（`PLANE_COUNT=48` / `index` / `compute`） |
| 请求组装 | `shadow/light/ShadowLightCompute.localPullEntry` |
| 唯一发请求收口点 | `protocol/ShadowPullClient.request`（+ `materializeForCompare`，A1） |
| 服务端裁决 | `server/ServerChunkPushManager.classifyPull` → `encodeClassifiedPull` → `planAndSerialize` |
| 服务端判据 | `protocol/sectiondelta/SectionDeltaPlanner.planSection` / `shouldFallbackFullChunk`（75% 规则） |
| 影子上下文门控 | `server/RuntimeServerContext.isShadowServerContext()` |
