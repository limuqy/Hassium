# handoff — common 包迁移（影子端 + client 一期完成，后续 server/protocol）

> 状态：2026-09-18 包迁移主线完成：0b SPI / 步0 测试 / client / server / protocol / mixin 子包；**12 版全 loader 编译矩阵 PASS**（§1.10）。
> 本文是**包迁移**交接真相源；S0 架构/门禁见
> [`handoff-shadow-as-dedicated-server-s0.md`](handoff-shadow-as-dedicated-server-s0.md)。

## 1. 已完成

### 1.1 影子端目标包（已存在）

```text
io.github.limuqy.mc.hassium.shadow
├── server/    ShadowSeedServer, ShadowServerRegistry, ShadowWorldgenExecutor,
│              SeedGenLevelCompat, SeedGenExecutor, ShadowChunkPersistenceRole
├── track/     ShadowTrackingSession, ShadowChunkProvider, VanillaAlignedChunkProvider,
│              ShadowChunkAcquire, ShadowChunkDeliver, ShadowOfficialPacketBridge,
│              ShadowColumnStore, ShadowChunkSource, ShadowTicketDriver
├── light/     ShadowLightCompute, ShadowLightProbe,
│              ShadowVanillaLightPipeline, SeedGenChunkCodec, SmokeChunkTrace
└── storage/   ShadowStorageManager, ShadowStorageHashes, ShadowRegionHeat,
               RegionCache, ShadowCacheEviction, HassiumType126Codec, HassiumChunkWriteBuffer
```

- 旧包 `network/seedgen` **已清空**（无 .java）
- 旧包 `storage/` 影子盘类已迁入 `shadow.storage`（主源 `storage/` 空）

### 1.2 影子端同步改动

| 项 | 说明 |
|----|------|
| FQCN | 全仓 `network.seedgen.X` / `storage.X` → `shadow.*` |
| 可见性 | 跨包子成员提升 `public` |
| 测试 | 见 §1.6 步 0（目录亦已对齐） |
| LoginCaps | `AUTHORITY_NOTIFY` 两端不协商 |
| P5 | `ShadowTicketDriver.P5_TAKEOVER = false` |

### 1.3 影子端验证【已验证】

- 编译矩阵 + `common:test` @1.20.1：BUILD SUCCESSFUL
- classic `1.20.1_fabric_I_shadowpkg`：R2 landed 444、缓存 100%；stats 双轮 PASS

### 1.4 0b 接口下沉（2026-09-18，已完成）

目标：对齐「shadow 不 import client」。

| 项 | 说明 |
|----|------|
| SPI 包 | `io.github.limuqy.mc.hassium.platform.client`：`TraceOrigin`、`ShadowClientApi`、`ShadowClientBridge` |
| 实现 | `ClientChunkPipeline implements ShadowClientApi`；`getInstance()` 时 `ShadowClientBridge.register(INSTANCE)` |
| TraceOrigin | 从 `ClientChunkHandler` 迁至 `platform.client.TraceOrigin` |
| shadow 调用 | 经 `ShadowClientBridge.get()`；未注册返回 no-op |
| 0b classic | `1.20.1_fabric_I_0b` **PASS**【已验证】 |

**脚本**：`scripts/tmp_0b_shadow_spi.py`（可删）。

### 1.5 client 一期（2026-09-18，已完成）

`cache/client/*` + `network.Client*` → `io.github.limuqy.mc.hassium.client`（与既有 smoke/scenario 同包）。

| 类 | 来源 |
|----|------|
| ChunkMeshCompileLog, ClientChunkCacheRadius, ClientLifecycleHelper, ClientMainThreadBudget, JoinWorldFocus, OvdClientLifecycle | `cache.client`（目录已空删） |
| ClientActivation, ClientChunkHandler, ClientChunkPipeline, ClientMetadataHandler | `network`（`network` 无 `Client*`） |
| ClientLifecycleHelperTest, ClientMainThreadBudgetTest, JoinWorldFocusTest | `test/.../client/` |

同步：全仓 FQCN 替换；`ShadowPullClient` / `ClientActivation` 补跨包 import；`isPendingPullApply` → `public`；forge/neoforge 补 `ClientActivation` import。

**未迁**：`cache.ChunkContentHashUtil`（非 client）。

**编译门禁**【已验证】：
- `common:compileJava` + `fabric:compileJava` + `common:test` @1.20.1 → BUILD SUCCESSFUL
- `common:compileJava` + `fabric:compileJava` + `neoforge:compileJava` @1.21.1 → BUILD SUCCESSFUL
- `forge:compileJava` @1.20.1 → BUILD SUCCESSFUL

**classic**【已验证】`1.20.1_fabric_I_client1`：`=== RESULT: PASS ===`；analyzer exit=0；Round1/2 stats=True pass=True；client exit 0。

**脚本**：`scripts/tmp_client_phase1_repackage.py`（可删）。

### 1.6 步 0 测试包对齐（2026-09-18，已完成）

- `network.seedgen` 10 + `storage` 3 → `shadow.{server,track,light,storage}`；旧目录空删
- `common:test` @1.20.1 BUILD SUCCESSFUL【已验证】
- 脚本：`scripts/tmp_move_shadow_tests.py`

**未验证**：flyroundtrip；1.21.1 classic；protocol/mixin 迁包。

### 1.7 server 一期（2026-09-18 树内已就位，本轮验证）

当前树中下列类型 **package = `io.github.limuqy.mc.hassium.server`**【已验证】`Get-Content` package 声明：

`ServerChunkPushManager` `ServerNetworkGate` `PlayerCompressionTracker`
`ServerHandshakeActivation` `PullPacingValve`
`ChunkAuthorityHashes` `ChunkAuthorityNotifier` `ChunkAuthorityS2CPacket`
（+ 既有 `RuntimeServerContext` `ServerSmokeTest`）

| 项 | 说明 |
|----|------|
| 测试 | `ChunkAuthorityS2CPacketTest` / `ServerChunkPushValveTest` → `test/.../server/`（package 已对齐） |
| 未迁入 server | `ChunkAuthorityClient` → `hassium.client`（客户端协议壳，合理） |
| 仍留 network | `ConnectionChannelAccess`（`HassiumAggregationManager` 同包使用）、`NativeChunkMetrics`、协议/payload/Pull 类 |
| 残留 FQCN | `network.Server*` / `network.ChunkAuthority*` / `network.PullPacing*` / `network.PlayerCompression*` **零命中**【已验证】Select-String |

**本轮未由本会话执行文件移动**（盘点时已在 server 包；可能是并行会话/前序改动）。本会话完成的是**验证闭环**。

**编译门禁**【已验证】：
- `common:compileJava` + `fabric:compileJava` + `common:test` @1.20.1 → BUILD SUCCESSFUL
- `common:compileJava` + `fabric:compileJava` + `neoforge:compileJava` @1.21.1 → BUILD SUCCESSFUL
- `forge:compileJava` @1.20.1 → BUILD SUCCESSFUL

**classic**【已验证】`1.20.1_fabric_I_server1`：`=== RESULT: PASS ===`；analyzer exit=0；Round1/2 stats=True pass=True；client exit 0。

### 1.8 protocol 一期（2026-09-18，已完成）

`network` 协议类 → `io.github.limuqy.mc.hassium.protocol`（handoff §2.2 步 4）。

| 目标包 | 内容 |
|--------|------|
| `protocol` | Aggregation* Dictionary* PreHandshake* ShadowPull* SectionDeltaS2C LightDeltaS2C IndexSync* PayloadHandlers HassiumPacketIds/HassiumAggregation*/HassiumConnectionRegistry CompactHeaderCodec ConnectionChannelAccess PacketTypeHelper PacketCompressionBlacklist PullResponseDecodeQueue SeedGenTail NamespaceIndexManager |
| `protocol.handshake` | LoginCaps LoginHandshake LoginHandshakeManager ClientLoginNegotiation PlayInitClient |
| `protocol.sectiondelta` | SectionDeltaPlanner Snapshot Snapshots SectionPlaneSyndrome |
| 测试 | ShadowPull* / LightDelta / SectionDelta / NetworkOptimization / LoginHandshake → `protocol*` |

**仍留 `network`**：`NativeChunkMetrics` + `network.entity.*`（6，实体域降帧，handoff 未归类）。

**loader NetworkManager**：仍住 fabric/forge/neoforge `network` 包；补 `protocol.*` import（FQCN 重写 + 同包裸名）。

**编译门禁**【已验证】顺序执行（避免并行切 `-Pmc_ver` 造成假失败）：
- `common:compileJava` + `fabric:compileJava` + `common:test` `@1.20.1`（含 `--rerun-tasks`）→ BUILD SUCCESSFUL
- `forge:compileJava` `@1.20.1` → BUILD SUCCESSFUL
- `common:compileJava` + `fabric:compileJava` + `neoforge:compileJava` `@1.21.1` → BUILD SUCCESSFUL

**残留 FQCN**【已验证】`network.(handshake|sectiondelta|ShadowPull|Aggregation|…)` **零命中**。

**脚本**：`scripts/tmp_protocol_phase1_repackage.py` + `scripts/tmp_protocol_finish.py`（可删）。

**classic**【已验证】`1.20.1_fabric_I_proto1`：`=== RESULT: PASS ===`；analyzer exit=0；Round1/2 stats=True pass=True；client exit 0。

**未验证**：flyroundtrip；1.21.1 classic；`network.entity` / NativeChunkMetrics 归属。

### 1.9 mixin 子包拆分（2026-09-18，已完成）

41 个 mixin → `mixin.{shadow,client,server}`；`mixin.modcompat` 不变。

| 子包 | 数量 | 代表 |
|------|------|------|
| `mixin.shadow` | 19 | MixinChunkMap / MixinServerChunkCache / MixinRegionFile / *Accessor（存储/光引擎） |
| `mixin.client` | 11 | MixinClientPacketListener / MixinMinecraft / MixinLevelRenderer / ClientLevelAccessor |
| `mixin.server` | 11 | MixinServerPlayer / MixinConnection / MixinMinecraftServer / MixinPlayerChunkSender / EntityAccessor |

**hassium.mixins.json**【已验证】：`package` 仍为 `...hassium.mixin`，类名改为相对子包路径（如 `shadow.MixinChunkMap`）——Mixin 会拼成 `...mixin.shadow.MixinChunkMap`。三端 loader json 列表为空，package root 未改。modcompat plugin FQCN 不变。

**编译门禁**【已验证】顺序执行：
- `common`+`fabric`+`common:test`+`forge` `@1.20.1` → BUILD SUCCESSFUL
- `common`+`fabric`+`neoforge` `@1.21.1` → BUILD SUCCESSFUL

**classic（mixin 运行时门禁）**【已验证】`1.20.1_fabric_I_mixin1`：`=== RESULT: PASS ===`；analyzer exit=0；Round1/2 pass=True；client exit 0——json FQCN 与包路径一致（否则 mixin 静默失败/启动崩）。

**脚本**：`scripts/tmp_mixin_subpackage_split.py`（可删）。

**未验证**：flyroundtrip；1.21.1 classic；1.20.1 forge/neoforge 运行时冒烟。

### 1.10 全矩阵编译（2026-09-18 收尾）

【已验证】按 `versionProperties.builds_for` 顺序编译 **12 版 × 对应 loader 全 PASS**：

| 版本 | loaders | 结果 |
|------|---------|------|
| 1.20.1 | common+fabric+forge | PASS |
| 1.21.1 | common+fabric+neoforge+forge | PASS |
| 1.21.2 | common+fabric+neoforge | PASS |
| 1.21.3–1.21.10 | common+fabric+neoforge+forge | PASS |
| 1.21.11 | common+fabric+neoforge | PASS |

收尾修复：
- loader `*NetworkManager` 补 `protocol.PreHandshake*` / `PayloadHandlers` / `ShadowPull*` / `DictionaryManager` / `Aggregation*` import
- `#if MC_VER < MC_1_21_1` 分支 `PreHandshakePayload`/`PreHandshakeHelloPayload` → **public**（import 穿透 #if）
- 清理 build 残留 `network/`、`cache/client/`、旧 mixin 根 class

`common:test` @1.20.1 BUILD SUCCESSFUL【已验证】。

`scripts/tmp_*.py` **已删除**（glob 0）【已验证】。

### 1.11 entity / NativeChunkMetrics 归属（2026-09-18）

| 类 | 目标包 | 依据 |
|----|--------|------|
| `NativeChunkMetrics` | `hassium.metrics` | 原版路径指标收口，写 `NetworkStats`/`SmokeChunkTrace`；调用方仅 client mixin |
| `network.entity.*`（6） | `hassium.server.entity` | `master.entity*` 服务端降帧；依赖 `server.ServerNetworkGate` / `RuntimeServerContext`；mixin.server 调用 |

测试：`NativeChunkMetricsTest` → `metrics`；`Entity*Test` → `server.entity`。common `network/` **空删**。

【已验证】`common`+`fabric`+`common:test`+`forge` `@1.20.1` BUILD SUCCESSFUL；`common`+`fabric`+`neoforge` `@1.21.11` BUILD SUCCESSFUL。

**未验证**：entity 降帧运行时冒烟（classic 不覆盖 `master.entity*` 行为）。

## 2. 建议后续包结构（主线迁包已完成）

```text
hassium.core/          Constants, utils.*, config.*, metrics.*, platform.*
hassium.protocol/      【1.8 已完成】LoginCaps, PreHandshake*, Aggregation*, Dictionary*,
                       ShadowPull*, SectionDelta*, LightDelta*, IndexSync*, handshake/sectiondelta 子包
hassium.shadow.*       【已完成】
hassium.client/        【1.5 已完成】ClientChunkPipeline, ClientChunkHandler,
                       ClientMetadataHandler, ClientMainThreadBudget,
                       OvdClientLifecycle, ClientLifecycleHelper, scenario/*（冒烟）
hassium.server/        【1.7 已就位】ServerChunkPushManager, ServerNetworkGate,
                       PlayerCompressionTracker, ServerHandshakeActivation,
                       ChunkAuthority*（客户端壳在 client）
hassium.mixin.shadow/  【1.9】MixinChunkMap, MixinServerChunkCache, MixinRegionFile, …
hassium.mixin.client/  【1.9】MixinClientPacketListener, MixinClientTick, MixinClientLevel, …
hassium.mixin.server/  【1.9】MixinServerPlayer, MixinPlayerChunkSender, …
hassium.mixin.modcompat/ 【不变】三方兼容 mixin
```

### 2.1 依赖约束

```text
core ← protocol ← shadow / client / server
shadow 不 import client（除测试/冒烟诊断）
  【0b 后已对齐】shadow 只 import platform.client SPI + protocol 侧 network 类型
client 可 import shadow（读 tracking 状态、publish）
mixin 子包变更必须同步：
  common/src/main/resources/hassium.mixins.json
  fabric / forge / neoforge 三端 mixin 登记
```

### 2.2 迁移步骤（建议）

0. ~~步 0 测试包对齐~~ **已完成**
1. **编译矩阵门禁**（每迁一包后）：
   ```powershell
   .\gradlew.bat common:compileJava fabric:compileJava common:test "-Pmc_ver=1.20.1"
   .\gradlew.bat common:compileJava fabric:compileJava neoforge:compileJava "-Pmc_ver=1.21.1"
   .\gradlew.bat common:compileJava forge:compileJava "-Pmc_ver=1.20.1"
   ```
2. ~~client 一期~~ **已完成（§1.5）**
3. ~~server 一期~~ **树内已就位并验证（§1.7）**：`ServerChunkPushManager` 等已在 `hassium.server`
4. ~~protocol 一期~~ **已完成（§1.8）**：协议类 → `hassium.protocol`
5. ~~mixin 子包~~ **已完成（§1.9）**：shadow/client/server 子包 + mixins.json 相对路径
6. 可选收尾：`network.entity` 归属；`NativeChunkMetrics` → metrics/core；tmp 脚本清理
7. 每步 classic 冒烟；移动/flyroundtrip 由专项会话门禁

**主线状态**：shadow / client / server / protocol / mixin 子包 **均已落地**（§1.4–§1.9）。

### 2.3 network 顶层未归类（主线已清空）

**已完成（§1.11）**：`NativeChunkMetrics` → `hassium.metrics`；`network.entity.*` → `hassium.server.entity`。

common 下 **无** `network` 源码目录（loader 侧仍有 `fabric/forge/neoforge` 的 `network` 包装 `*NetworkManager`）。

### 2.4 脚本残留

迁包临时脚本 `scripts/tmp_*.py` **已全部删除**（2026-09-18 收尾）。需要复用时按 git 历史找回。

| 参考 | 说明 |
|------|------|
| [`handoff-shadow-as-dedicated-server-s0.md`](handoff-shadow-as-dedicated-server-s0.md) | S0 架构 + 待办 |

## 3. 真正公共 vs 业务（判断口诀）

| 放 core/protocol | 放 shadow/client/server |
|------------------|-------------------------|
| 无线程/无 MC 世界实例 | 有 ServerLevel / ClientLevel / 连接 |
| 协议编解码、能力位 | 选柱、算光、推送、UI |
| DimensionKey、DebugLogger、config 读 | Provider、LightCompute、PushManager |
| `platform.client.ShadowClientApi`（SPI） | `ClientChunkPipeline`（实现） |

## 4. 风险

- Manifold `#if MC_VER` 文件改包时 **不要** 合并版本分支
- mixin json FQCN 与 Java 包不一致 → 运行时 mixin 静默失败
- 测试同包 package-private：迁包后必须 `public` 或测试同包
- SPI 未注册时 shadow 读到 no-op——classic 已证物理路径可用

相关：[`handoff-shadow-as-dedicated-server-s0.md`](handoff-shadow-as-dedicated-server-s0.md)
