---
name: hassium-manifold
description: >-
  Applies Hassium Manifold version-segment rules: seven compile anchors, legal
  #if MC_VER whitelist, PacketId instead of ResourceLocation/Identifier in
  business code, and compat-layer wrapping. Use when editing #if MC_VER,
  versionProperties, scanVersionBoundaries, PacketId,
  HassiumChannels, Identifier, ResourceLocation, version-segments, 七段,
  1.20.1 vs 1.21.1, or adding a Minecraft version/API cliff.
---

# Hassium Manifold 规范

真相源：[docs/version-segments.md](../../../docs/version-segments.md)。交接背景：[docs/handoff/handoff-2026-08-22-manifold-consolidation.md](../../../docs/handoff/handoff-2026-08-22-manifold-consolidation.md)。

改任何 `#if MC_VER`、通道 id、跨版本 API、或 `versionProperties/` 时遵循本 skill。不要凭记忆写 `MC_1_20_5` / 九段 / 1.20.2–1.20.6。

## 产品矩阵（现行）

- **12** 个 `versionProperties`：`1.20.1` + `1.21.1`–`1.21.11`。**没有** 1.20.2–1.20.6。
- **7 编译锚点**：`1.20.1, 1.21.1, 1.21.2, 1.21.5, 1.21.6, 1.21.9, 1.21.11`。
- Forge：`1.20.1` + `1.21.1`/`1.21.3`–`1.21.10`（**无 1.21.2 userdev**；**1.21.11 sunset → NeoForge**）。
- 主缝：`#if MC_VER < MC_1_21_1`（1.20.1 旧栈）vs `≥ MC_1_21_1`。

合法 `#if` 白名单（`scanVersionBoundaries` 强制）：

```
MC_1_20_1
MC_1_21_1
MC_1_21_2
MC_1_21_5
MC_1_21_6
MC_1_21_9
MC_1_21_11
```

比较式用 `< MC_1_21_1` 表达 1.20.1。禁止新增 `MC_1_21_3` 等碎片常量；历史偶见 `MC_1_21_4` 清扫时并入 `MC_1_21_5`。

`-Pmc_ver`：pwsh **必须** `"-Pmc_ver=1.20.1"`（否则被截成 `1`）；Git Bash 写 `-Pmc_ver=1.20.1` 即可。

```powershell
# pwsh
.\gradlew.bat common:compileJava "-Pmc_ver=1.20.1"
.\gradlew.bat scanVersionBoundaries
```

```bash
# Git Bash
./gradlew common:compileJava -Pmc_ver=1.20.1
./gradlew scanVersionBoundaries
```

编译走 Gradle daemon。当前壳直接跑（pwsh → `.bat`，Git Bash → `./gradlew`），不要 `cmd /c`、不要 `--no-daemon`、不要 `pwsh -NoProfile`、不要 `| tail`。等 wrapper 退出 / `BUILD SUCCESSFUL|FAILED`，不要等 GradleDaemon PID。详见 [`AGENTS.md`](../../../AGENTS.md)。

## `#if` 放哪

| 允许 | 禁止 |
|------|------|
| `common/.../compat/`：Mojang 差的唯一归宿 | 业务类新增散落 `#if`（包字段、方法签名、局部变量类型） |
| Mixin **目标类 / 方法描述符 / 参数签名**（换类、换方法名） | 用热路径反射「万能适配」换掉 Mixin 目标 `#if` |
| 三端 `*NetworkManager` **整段**网络 API 块 | 每个 send 再套一层 `Identifier`/`ResourceLocation` |
| 加载器模块（fabric/forge/neoforge）的 loader API | common 里 `import` fabric/forge/neoforge |

`#if MC_VER < MC_1_21_1` 会长期存在，直到产品停更 1.20.1。**不要删 1.20.1 旧网络栈。**

**不要**把 1.21.2 / 1.21.5 / 1.21.9 合成一段（Mixin 目标与线格式是真断层）。

**不要**再引入 `MC_1_20_2` / `MC_1_20_5`。文档里「1.20.2+ PlayerChunkSender」是 Mojang 历史，不是支持矩阵。

## `#define` 不可用

manifold-preprocessor **2026.1.6 的 `#define` 不做文本替换**（已反编译验证）。

```java
// 无效：编译后不会变成 Identifier
#define ResourceLocation Identifier
```

`ResourceLocation` → `Identifier`（1.21.11）必须 **结构收口**，不能靠宏。

## PacketId（通道 / 聚合）

业务与自定义包通道用 `compat.PacketId`（namespace + path，**零 `#if`**）。

- 常量：`compat.HassiumChannels`（11 个 CHANNEL，全是 `PacketId`）。
- 转 vanilla：**仅加载器边界** `ResourceLocationCompat.vanilla(id)`。
- 从 vanilla 收回：`ResourceLocationCompat.toPacketId(...)`。
- 发自定义包：`PacketPayloadCompat.createClientboundPayload(PacketId, byte[])`。
- 聚合路径（`AggregatedSubPacket` / `PacketTypeHelper` / `NamespaceIndexManager` / `HassiumAggregation*`）持有 `PacketId`，不要把 RL/Identifier 当字段。

`ResourceLocationCompat.create()` **故意**仍返回 vanilla 类型（compat 内部 + 维度/注册表等仍要 RL）。**不要**把它的返回类型当业务字段；业务字段用 `PacketId` 或 `String`。

```java
// 业务
PacketId id = HassiumChannels.CHUNK_HASH_S2C;
Packet<?> pkt = PacketPayloadCompat.createClientboundPayload(id, body);

// 加载器注册/发送
ResourceLocation loc = ResourceLocationCompat.vanilla(id); // 或 Identifier，仅此边界
```

## 增删版本 = 重编号

`MC_VER` 下标由 `versionProperties/` **目录序**生成（`buildSrc/.../root.gradle`）。增删 properties **必须与 `#if` 常量同一变更集**，否则全仓比较错位。

同一 PR 还要改：`docs/version-segments.md`、扫描白名单、对外支持矩阵。

## 改完自检

1. `scanVersionBoundaries` 绿（无白名单外 token）。
2. 抽样编译至少：`1.20.1`、`1.21.1`、以及你碰到的锚点（常还要 `1.21.11` 验证 Identifier）。
3. 新业务文件 grep：不应出现 `ResourceLocation` / `Identifier` 字段或返回类型（compat 与 `*NetworkManager` 除外）。
4. 用户可见 docs/README/wiki **现行**口径：七段；不要写回九段或 1.20.2–1.20.6 支持表（archive/handoff 历史叙述可留）。
