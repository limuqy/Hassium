# handoff — common 包迁移（影子端已完成，后续 client/server）

> 状态：2026-09-18 影子端一期迁包完成并 classic 冒烟（stats PASS）。
> 本文是**包迁移**交接真相源；S0 架构/门禁见
> [`handoff-shadow-as-dedicated-server-s0.md`](handoff-shadow-as-dedicated-server-s0.md)。

## 1. 已完成（影子端一期）

### 1.1 目标包（已存在）

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
- 旧包 `storage/` 中影子盘类已迁入 `shadow.storage`（`common` 下 `storage/` 仅当仍有残留时再查）

### 1.2 同步改动

| 项 | 说明 |
|----|------|
| FQCN | 全仓 `network.seedgen.X` / `storage.X` → `shadow.*` |
| 可见性 | 跨包子成员提升 `public`（`LIGHT_ENGINE_MUTEX`、`shouldRebuildFor*`、`reclaimEligible`、`RECLAIM_GRACE_MS`、`RegionCache.SECTOR_SIZE`、`testWriteDelayMs` 等） |
| 测试 | `common/src/test/.../network/seedgen/*`、`storage/*` import 已改 |
| LoginCaps | `AUTHORITY_NOTIFY` 两端不协商 |
| P5 | `ShadowTicketDriver.P5_TAKEOVER = false` |

### 1.3 验证【已验证】

- `common:compileJava` + `fabric:compileJava` + `common:test` @1.20.1：BUILD SUCCESSFUL
- classic `1.20.1_fabric_I_shadowpkg`：R1 1635 新增；R2 landed **444**、缓存 **100%**（435+9）；stats 双轮 PASS
- analyzer 仅 `TRACE_INJECTED_NOT_READY` P0 ×16（R2）——见 S0「后续会话」

## 2. 建议后续包结构（未做）

```text
hassium.core/          Constants, utils.*, config.*, metrics.*, platform.*
hassium.protocol/      LoginCaps, PreHandshake*, Aggregation*, Dictionary*,
                       ShadowPull*Packet/Handler/Validator, SectionDelta*, LightDelta*
hassium.shadow.*       【已完成】
hassium.client/        ClientChunkPipeline, ClientChunkHandler, ClientMainThreadBudget,
                       OvdClientLifecycle, ClientLifecycleHelper, scenario/*（冒烟）
hassium.server/        ServerChunkPushManager, ServerNetworkGate, PlayerCompressionTracker,
                       ServerHandshakeActivation, ChunkAuthority*（若保留协议壳）
hassium.mixin.shadow/  MixinChunkMap, MixinServerChunkCache, MixinRegionFile, …
hassium.mixin.client/  MixinClientPacketListener, MixinClientTick, MixinClientLevel, …
hassium.mixin.server/  MixinServerPlayer, MixinPlayerChunkSender, …（真服）
```

### 2.1 依赖约束（迁 client/server 时必须遵守）

```text
core ← protocol ← shadow / client / server
shadow 不 import client（除测试/冒烟诊断）
client 可 import shadow（读 tracking 状态、publish）——若要解耦，改为接口进 core/protocol
mixin 子包与业务包可互相引用，但 mixin 包名变更必须同步：
  common/src/main/resources/hassium.mixins.json
  fabric / forge / neoforge 三端 mixin 登记
```

### 2.2 迁移步骤（建议）

1. **编译矩阵门禁**（每迁一包后）：
   ```powershell
   .\gradlew.bat common:compileJava fabric:compileJava common:test "-Pmc_ver=1.20.1"
   .\gradlew.bat common:compileJava fabric:compileJava neoforge:compileJava "-Pmc_ver=1.21.1"
   .\gradlew.bat common:compileJava forge:compileJava "-Pmc_ver=1.20.1"  # 若 builds_for 含 forge
   ```
2. **client 一期**：`cache/client/*` + `network/ClientChunk*` → `hassium.client.chunk`；改 import + 测试
3. **server 一期**：`network/ServerChunkPushManager`、`ServerNetworkGate` → `hassium.server.network`
4. **protocol 一期**：`network/*Payload`、`ShadowPull*Packet`、`Aggregation*` → `hassium.protocol`
5. **mixin 子包**：最后做；改 FQCN + 三端 mixins.json
6. 每步 classic 冒烟；移动/flyroundtrip 由专项会话门禁

### 2.3 脚本残留（可复用/清理）

| 路径 | 用途 |
|------|------|
| `scripts/tmp_move_shadow_packages.py` | seedgen→shadow 迁包（已完成，可删） |
| `scripts/tmp_fix_shadow_imports.py` | 跨包 import / 可见性修复 |
| `scripts/tmp_make_public.py` | package-private → public |
| `docs/handoff/handoff-shadow-as-dedicated-server-s0.md` | S0 架构 + **待办 1/3** |

## 3. 真正公共 vs 业务（判断口诀）

| 放 core/protocol | 放 shadow/client/server |
|------------------|-------------------------|
| 无线程/无 MC 世界实例 | 有 ServerLevel / ClientLevel / 连接 |
| 协议编解码、能力位 | 选柱、算光、推送、UI |
| DimensionKey、DebugLogger、config 读 | Provider、LightCompute、PushManager |

## 4. 风险

- Manifold `#if MC_VER` 文件改包时 **不要** 合并版本分支
- mixin json FQCN 与 Java 包不一致 → 运行时 mixin 静默失败
- 测试同包 package-private：迁包后必须 `public` 或测试同包

相关：[`handoff-shadow-as-dedicated-server-s0.md`](handoff-shadow-as-dedicated-server-s0.md)
