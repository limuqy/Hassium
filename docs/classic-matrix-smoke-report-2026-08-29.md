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

## 未闭环问题

`1.20.1` 三端仍存在 R2 期间的全量区块请求：

- Fabric：`newFullChunkRequestCount=1`；
- Forge：`newFullChunkRequestCount=2`；
- NeoForge：`newFullChunkRequestCount=5`。

这不是通过放宽 analyzer 或降低门禁解决的问题。当前 `PENDING_CONFIRM_TIMEOUT_MS=60000` 保持生产行为不变；本轮证据表明 1.20.1 的 R2 hash 确认/全量回退链路仍需单独修复。

## 验证命令

```powershell
.\gradlew.bat common:compileJava "-Pmc_ver=1.21.2"
.\gradlew.bat common:test --tests io.github.limuqy.mc.hassium.network.ServerChunkPushManagerPacedPendingTest
```

两条命令均 `BUILD SUCCESSFUL`。
