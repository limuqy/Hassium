# Handoff — 2026-08-22 Manifold 区段归拢 + compat 收口

日期：2026-08-22 · 状态：**Wave 1–2 完成；Wave 3 聚合 PacketId + 文档残渣 + Manifold skill 已收口**  
真相源：[`docs/version-segments.md`](../version-segments.md)（七段）· `AGENTS.md` 已改七段口径  
规范 skill：[`.cursor/skills/hassium-manifold/SKILL.md`](../../.cursor/skills/hassium-manifold/SKILL.md)

原 workflow 目录 `.omp/workflows/manifold-segment-consolidation/`（REQ / TASKS / work/*）会话后可能已不在工作区；**已完成项见下文，不要再按旧「后续待做」重做 P0–P2。**

## 需求（原拍板）

- **Wave 1**：砍 1.20.2–1.20.6 版本支持，把 `MC_1_20_2/3/4/5/6` 中间支路并成 `< MC_1_21_1` vs `≥ MC_1_21_1`。
- **Wave 2**：compat 全面收口——非 compat / 非网络适配器 / 非 Mixin 目标签名的 `#if MC_*` 趋零；CHANNEL 常量集中持有；业务签名去掉 `ResourceLocation`/`Identifier` 类型暴露。

拍板：全面收口 + 同会话执行。`#define` 文本别名 **不可用**（见下）。

## 关键约束（续做仍有效）

- Manifold 下标由 `versionProperties/` 目录序生成（`buildSrc/src/main/groovy/root.gradle`）→ 增删 properties 必须与 `#if` 常量同一变更集，否则 `MC_VER` 错位。
- **manifold-preprocessor 2026.1.6 的 `#define` 不做文本替换**（已反编译查证）。Identifier 重命名 **不能** 靠 `ResourceLocation=Identifier` 消掉；只能结构收口（自有类型 / Compat 不泄漏返回类型）。
- PowerShell：`"-Pmc_ver=1.20.1"`，否则会被截成 `1`。
- 合法 `#if` 白名单（Gradle `scanVersionBoundaries` / `scripts/scan-version-boundaries.ps1` / `.sh` 已对齐）：

```
MC_1_20_1
MC_1_21_1
MC_1_21_2
MC_1_21_5
MC_1_21_6
MC_1_21_9
MC_1_21_11
```

## 已完成（勿重做）

### Wave 1 — 版本收缩

- 删除 `versionProperties/1.20.{2,3,4,5,6}.properties`。现 **12** 个版本文件：1.20.1 + 1.21.1–1.21.11。
- Java 源码 **零** `MC_1_20_2/3/4/5/6` token；扫描任务应绿。
- 主段 **A, D–I 共 7 锚点**：`1.20.1, 1.21.1, 1.21.2, 1.21.5, 1.21.6, 1.21.9, 1.21.11`（`compile-anchors.ps1` / `.sh` 列表已是这 7 个）。
- NeoForge 网络 **4 段 → 2 段**（`< MC_1_21_1` SimpleChannel / `≥ MC_1_21_1` Payload+StreamCodec）。`NeoForgeNetworkManager` 约 3264 → ~2190 行，文件内 `#if` 约 16。
- `PacketPayloadCompat` **三路 → 两路**（1.20.1 `game` 包 vs 1.21.1+ `type().id()`）。中间 `payload().id()+write()` 已删。
- Fabric / Forge 网络同样两分界：`< MC_1_21_1` buf vs `≥ MC_1_21_1` StreamCodec。
- `#if/#elif` 总量约 **549 → 438**（105 → 98 文件）。最大头变成 `#if MC_VER < MC_1_21_1`（约 171 次）——这是 1.20.1 旧栈，不是漏删的中间态。
- `AGENTS.md` 口径已是「1.20.1 / 1.21.1–1.21.11、七段」。

### Wave 2 — 已做的结构收口

- `compat/HassiumChannels.java`：11 个自定义包 CHANNEL 常量集中持有；`ChunkHashS2CPacket` 等 record **不再**自带 RL/`#if`。
- 加载器注册处改引 `HassiumChannels.XXX`（如 `FabricPayloadRegistry` / 各 `*NetworkManager`）。
- 字符串通道名仍在 `HassiumPacketIds`（与 CHANNEL 类型是两套，有意分离）。

## 已完成的 P0–P2（勿按「待做」重开）

### P0 — 对外文档与真相源自洽 — 完成
docs/wiki/README/root.gradle/scripts 注释全部七段化；grep 现行口径零命中「九段/9 锚点/1.20.2–1.20.6」（archive / 本 handoff 的历史叙述可保留）。

同日第三波补漏（非用户可见矩阵）：`docs/network-core-followups.md` A5/C2 标作废；`docs/config-audit.md` NeoForge Spec 分界改为 1.20.1 vs 1.21.1+；`forge/build.gradle` 注释改为 1.20.1 + 1.21.1–1.21.10。

代码注释里「1.20.2+ PlayerChunkSender」等是 **Mojang 历史**，不是支持矩阵，保留。

### P1 — Identifier / 通道类型不再泄漏 — 完成
见文末「续做完成记录」P1 与 **Wave 3**（聚合路径 `PacketId`）。

### P2 — 业务 `#if` 继续收口 — 完成（ShadowSeedServer）
见文末 P2 记录。新业务仍禁止散落 `#if`；Mixin 目标类/签名 `#if` 不收。

### 明确不做

- **不要**再引入 `MC_1_20_2/5` 等已退役常量。
- **不要**把 1.20.1 旧网络栈删掉（用户盘；Java 17 岛）。`< MC_1_21_1` 会长期存在，直到产品决定停更 1.20.1。
- **不要**用热路径反射去「万能适配」方法名。
- **不要**等 manifold `#define` 文本替换；用 `PacketId` / Compat。
- 不把 1.21.2 / 1.21.5 / 1.21.9 合成一段（Mixin 目标类与线格式仍是真断层）。
- 加载器 `*NetworkManager` 允许整文件版本块；不要为压 `#if` 计数去改 Fabric 适配器。

## 现况数字（2026-08-22 核验，便于对照）

| 项 | 值 |
|----|-----|
| versionProperties | 12 |
| 发版格子（× builds_for） | 34 |
| 编译锚点 | 7 |
| Java `#if/#elif`（MC_） | ~418 / 95 文件（Wave 3 前快照；聚合 PacketId 后再降若干） |
| `MC_1_21_1` / `MC_1_21_11` `#if` | ~222 / ~73（Wave 3 前） |
| 白名单外 token | 0 |

加载器 `FabricNetworkManager` 约 42 条 `#if` **允许保留**（整文件网络适配器）。

## 续做完成记录（2026-08-22 晚，同日第二波）

### P0 文档对齐 — 完成
docs/wiki/README/root.gradle/scripts 注释全部七段化；grep 现行口径零命中「九段/9 锚点/1.20.2–1.20.6」（历史叙述保留）。

### P1 PacketId 类型包装 — 完成
- 新建 `compat/PacketId`（namespace+path record，零 `#if`）
- `HassiumChannels` 11 常量改 `PacketId`：**零 `#if`/零 RL/Identifier**（原 11 组类型切换全删）
- `ResourceLocationCompat.vanilla(PacketId)` / `toPacketId(...)` 边界双向转换
- `FabricSendCompat` 签名 `Object→PacketId`；`<1.21.1` 分支内部转 vanilla，调用方零改动
- `PacketPayloadCompat.getPayloadId` 改返 `PacketId`；`createClientboundPayload` 新增 PacketId 重载（vanilla 版更名 `createClientboundPayloadVanilla` 供边界）
- 业务调用点收口：ServerGatewayInfoSender / ClientGatewayBootstrap / PacketTypeHelper / HassiumAggregationPacket
- `MC_1_21_11` 条 `#if`：92 → **73**（余量在 Mixin 描述符 / 权限 API / GameRules / payloadType 注册边界）

### P2 ShadowSeedServer 收口 — 完成（cursor-agent 执行）
- `#if` ~30 → **3**（残留均为子类必须覆写的 protected/签名差异，各附豁免注释）
- 迁出：新建 `compat/ShadowServerCompat`、`SeedGenLevelCompat.chunkProgressArg`、`EntityPacketCompat.create` 等
- 卫星文件 SeedGenExecutor/ShadowLightCompute/SeedGenChunkCodec/OvdLocalGenerator 业务 `#if` 清零

### 终验证据（第二波）
- 编译：common/fabric/neoforge/forge @ 1.20.1、1.21.1、1.21.11 抽样全绿
- common:test 双锚点 BUILD SUCCESSFUL；scanVersionBoundaries 绿
- 全仓 `#if/#elif MC_VER`：437 → **417**

## Wave 3 — 聚合路径 PacketId + Manifold skill（2026-08-22 同日第三波）

- `AggregatedSubPacket` / `PacketTypeHelper.getPacketType` / `NamespaceIndexManager` vanilla 映射 / `HassiumAggregationManager.takeOver` / `HassiumAggregationPacket.handle`：字段与签名改为 `PacketId`；`createClientboundPayload(PacketId)` 替代 `createClientboundPayloadVanilla`。
- `PacketCodecCompat.PlayPacketEntry.id` 改为 `PacketId`（compat 内 `toPacketId`）。
- 项目 skill：`.cursor/skills/hassium-manifold/SKILL.md`；`AGENTS.md` Skills 节指向它。
- 文档残渣：followups A5/C2 作废、config-audit NeoForge Spec、forge/build.gradle 注释。

**后续不必再开** Identifier 包装或 1.20.2–1.20.6 支路清扫。新代码按 skill：Mojang 差进 `compat/`，通道用 `HassiumChannels`/`PacketId`，禁止散落 `#if`。
