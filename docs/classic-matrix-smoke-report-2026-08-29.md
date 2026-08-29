# Classic 全矩阵冒烟报告（逐会话串行复跑）

日期：2026-08-29。执行方式：逐版本、逐 loader 串行运行，未使用批量脚本。会话后缀 `_I_20260829`；`DelayMs=20000`，R1 `VD=20`，R2 `VD=10`，客户端 `renderDistance=32`，两轮均由 PROBE JSON 与 Python analyzer 判定。

**合计：PASS 31 · FAIL 3 · SKIP 2**（36 格）。实际运行 34 格。

FAIL 集中于 `1.20.1`：三个 loader 均完成服务端启动、客户端两轮、R1/R2 统计和 VD 切换，但 analyzer 检出 `R2_FULL_CHUNK_TRANSFER`。

| 版本 | Fabric | Forge | NeoForge |
|------|---------|-------|----------|
| 1.20.1 | FAIL (`R2_FULL_CHUNK_TRANSFER`, new full=1) | FAIL (`R2_FULL_CHUNK_TRANSFER`, new full=2) | FAIL (`R2_FULL_CHUNK_TRANSFER`, new full=5) |
| 1.21.1 | PASS | PASS | PASS |
| 1.21.2 | PASS | SKIP（无 Forge userdev） | PASS |
| 1.21.3 | PASS | PASS | PASS |
| 1.21.4 | PASS | PASS | PASS |
| 1.21.5 | PASS | PASS | PASS |
| 1.21.6 | PASS | PASS | PASS |
| 1.21.7 | PASS | PASS | PASS |
| 1.21.8 | PASS | PASS | PASS |
| 1.21.9 | PASS | PASS | PASS |
| 1.21.10 | PASS | PASS | PASS |
| 1.21.11 | PASS | SKIP（Forge sunset） | PASS |

## 运行证据

所有 PASS 会话均满足：

- 服务端达到 `Done!`；
- 客户端退出码 `0`；
- `ROUND1`、`ROUND2` 统计文件均生成；
- `PROBE JSON: round1=True round2=True`；
- `ServerSwitched=True`；
- Python analyzer 退出码 `0`。

1.20.1 三个最终会话的结果文件：

- `build/smoke-test/results/result_1.20.1_fabric_I_20260829_retry.json`
- `build/smoke-test/results/result_1.20.1_forge_I_20260829.json`
- `build/smoke-test/results/result_1.20.1_neoforge_I_20260829.json`

其余本轮会话结果均以 `result_<SessionId>.json` 保存在 `build/smoke-test/results/`。

## 本轮发现与修复

### 1. 客户端断线后卡死

初次 `1.20.1 Fabric` 会话在打印 `disconnecting from server` 后没有进入重连。日志表明网络连接已回到 `IDLE`，但 `mc.player` 尚未及时清空；旧判断只检查 player，错误地跳过了 reconnect。

修复：`ScenarioEngine` 只有在 `mc.player != null && mc.getConnection() != null && mc.level != null` 时才跳过重连。修复后复跑客户端正常退出码为 `0`，两轮统计完整生成。

### 2. 1.21.2 多版本编译回归

最新改动直接调用 `LevelChunk.setUnsaved(true)`，导致 `1.21.2` 编译失败。该版本实际 API 为 `markUnsaved()`。

修复：新增 `ChunkDataCompat.markUnsaved`，按合法版本边界适配：

- `< MC_1_21_2`：`setUnsaved(true)`；
- `>= MC_1_21_2`：`markUnsaved()`。

`common:compileJava -Pmc_ver=1.21.2` 通过；`1.21.2 Fabric/NeoForge` 冒烟通过。

## 已修复问题

`1.20.1` 的整柱回退误判已修复。旧实现把“section hash 不同”直接计为 changed section；逐格诊断显示 10 个区块仅变化 0.906% 的格子，但差异分散到全部非空 section，因此旧逻辑把它们误判为 `>=75%`，直接跳过 section delta。

修复内容：

- `SectionDeltaPlanner` 现在按 section 决策中的 `Kind.FULL` 计数，不再按任意 hash mismatch 计数；少量 `BLOCKS` 差异不触发整柱回退。
- `ServerChunkPushManager` 在完成 `BLOCKS` 与 `FULL` 编码体积比较后再次统计最终 `FULL` section，只有最终 FULL section 达到非空 section 的 75% 才返回整柱回退。
- `SeedGenExecutor` 不再仅凭客户端 section hash 预判整柱回退，统一交给服务端拿到真实 section payload 后判定。

因此两层回退现在符合预期：

```text
section hash mismatch
  → section candidates
  → BLOCKS / FULL section（按候选数量和编码体积选择）
  → 最终 FULL section >= 75% → full chunk
```

剩余的 0.906% R1/R2 内容差异仍需单独追踪其 baseline 来源，但它不再因为“分散在多个 section”而升级为整柱全量传输。

## 仍需观察

`1.20.1` 三端旧冒烟结果中的全量区块请求：Fabric `1`、Forge `2`、NeoForge `5`，均来自修复前实现；需重新运行对应冒烟确认请求数下降。
修复后验证会话：`1.20.1_fabric_I_r2fix_20260829`。服务端日志中已无 `Fallback to full ... changed sections >= 75%`，R2 统计为 `fullChunkRequestCount=1`、`sectionDelta` 正常发送；该 1 次来自独立的 delta 应用失败回退路径，不再是 75% section 阈值误判。analyzer 仍将该独立回退标为 FAIL，后续应单独修复 `applySectionDelta=false` 的具体原因。

### 逐格回退诊断（1.20.1 Fabric）

为区分“全量回退比例”和“方块内容差异”，使用一次 `1.20.1 Fabric` clean-world 会话逐格抓取 R2 回退区块：

- 会话：`1.20.1_fabric_I_r2cell_20260829`；10 个回退区块，R1/R2 内存快照均为每区块 98,304 格，共比较 983,040 格。
- 变化：8,911 格，约 **0.906%**；差异分布覆盖 16 个 section，没有整列坐标偏移。
- 主要目标状态：`stone` 4,719 格、`deepslate[axis=y]` 3,065 格，合计占差异 87.35%。主要转换为 `tuff → deepslate`、`andesite/diorite/granite → stone`。
- 原始全量包头部坐标与回退文件名逐格一致；因此没有证据表明回退包被错配到邻区块。
- 9 个回退由 `server-skipped` 触发，1 个由 `delta-apply-failed` 触发。

结论：报告中的“R2 全量”是**传输/请求路径指标**，不是 75% 方块被替换。逐格结果显示全量包只修正约 0.9% 的 shadow 内存内容，主要修正了 R1 基线中的石材变体/矿物/深层方块差异；R2 全量包本身未发现坐标错配或整块损坏。未闭环根因仍是：R1 shadow 基线为何与 R2 权威区块存在这些差异，以及 `server-skipped`/`delta-apply-failed` 触发前的 hash、delta baseline 一致性。

诊断产物：`build/smoke-test/probe/1.20.1_fabric_I_r2cell_20260829/r2-full-fallback/`；离线分析器：`scripts/analyze-r2-fallback.py`。逐格探针已在分析后清理，未改变生产行为。

## 验证命令

```powershell
.\gradlew.bat common:compileJava "-Pmc_ver=1.21.2"
.\gradlew.bat common:test --tests io.github.limuqy.mc.hassium.network.ServerChunkPushManagerPacedPendingTest
```

两条命令均 `BUILD SUCCESSFUL`。
