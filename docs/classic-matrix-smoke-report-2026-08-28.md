# Classic 全矩阵冒烟报告（滑块对齐复跑轮）

日期：2026-08-28。会话前缀 `*_I_slider32`。`DelayMs=20000`（R1 窗口 40s）、`MoveSeconds=0`（站桩）、逐版小批量推进、`parity_<loader>_<ver>` 存档跨轮复用（各版本世界已隔离，不再 CleanWorld）、客户端滑块由 harness 钉死 `renderDistance=32`。基线 commit `0d4ca9a`（Harden smoke chunk gates，当日 20:27 提交）。

**合计：PASS 18 · FAIL 16 · SKIP 2**（36 格；Forge 1.21.2 无 userdev、1.21.11 sunset）。

FAIL 分解：

| 类别 | 格数 | 明细 |
|------|------|------|
| 仅 `R2_FULL_CHUNK_TRANSFER`（P0，流程完整两轮） | 13 | 1.20.1 三端、1.21.1 fabric/forge、1.21.3 forge、1.21.4 forge/neoforge、1.21.5 neoforge、1.21.6 neoforge、1.21.7 neoforge、1.21.8 neoforge、1.21.9 neoforge |
| R2 重连卡死（客户端崩溃，流程走不完） | 2 | 1.21.1 neoforge（3 次复现）、1.21.2 neoforge（2 次复现） |
| `SECTION_DELTA_OR_LIGHT_RECALC_ABSENT`（G2 未触发，吞吐坍缩的下游后果） | 1 | 1.21.11 neoforge |

全矩阵 `LogAuditFailures=0`（1.21.1/1.21.2 neoforge 崩溃会话的 7 条为崩溃本身）。网关两轮 `ACTIVE`、G1/G3/G4 在所有完成会话全绿。

## 本轮修过的根因

1. **影子端 `.mca` 跨 region 写串（严重，已修+回归测试）**：`ShadowStorageManager.writeBatch` 把 region 映像固定在批内首柱，而 `encodeDirtyOnThisThread` 的脏表批次不按 region 分组——后续柱带自己的 `localIndex(x&31,z&31)` 写进首柱 `.mca` 的同槽位，与相邻 region 镜像柱（差 32）互串。自定义读路径自洽所以统计全绿；vanilla 按坐标读取报 `wrong location; relocating`（1.20.1 fabric 首跑 114 条，Expected/got 恒差 32）且内容静默错位。修复：按柱 region 键解析映像、同 region 才复用（[ShadowStorageManager.java:732](../common/src/main/java/io/github/limuqy/mc/hassium/storage/ShadowStorageManager.java)）。回归测试 `mixedRegionFlushKeepsMirroredSlotsSeparate`（镜像槽位对 (19,4)/(-13,4)，清 HashIndex 表走映像+磁盘路径断言）通过；修复后全部会话 `process_fatal` 门禁转绿。
2. **1.21.1–1.21.5 neoforge 客户端 mod 构造即崩（严重，已修）**：commit `2424be4` 把 `HassiumNeoForgeClient` 的三段 `@EventBusSubscriber` 条件编译（`<1.21.1` / `1.21.1–1.21.5` 显式 `bus=Bus.MOD` / `≥1.21.6` 无 bus）错误合并为两段，1.21.2 落到无 bus 分支 → `FMLClientSetupEvent`（MOD 总线事件）按 Game 总线注册，bus-8.0.2 `EventBus.registerListener` 抛 `IllegalArgumentException` → `Failed to register automatic subscribers` → 客户端起不来。修复：恢复 `#elif MC_VER < MC_1_21_6` 分支（`neoforge/src/main/java/io/github/limuqy/mc/hassium/HassiumNeoForgeClient.java`）；1.21.2 编译通过，复跑客户端正常加载（R1 完成）。

## 已闭环 blocker：NeoForge ≥1.21.1 推送吞吐与 R2 OVD

初始复跑曾观察到 NeoForge R1 在 40s 窗口仅落地 49–91 柱，R2 OVD 仅加载 20 柱。根因是 Bloom full sync 到达时，`resyncTrackedChunks` 对 `getChunkNow()` 尚未生成的区块直接跳过，后续不再补登记。

修复后，NeoForge resync 保留视距范围内的 `ResyncEntry`，由后续 drain 重试，待区块生成后再提交 metadata/full chunk；Bloom full sync 同时触发当前维度 resync。

**下游后果**：

- R2 缓存命中基数塌陷（如 1.21.9 neoforge R2 hit=49）；部分格靠门禁宽松侥幸 PASS（1.21.10 neoforge L1=49 仍 PASS）。
- 1.21.11 neoforge G2 失败（`SECTION_DELTA_OR_LIGHT_RECALC_ABSENT`）：R1 仅 49 柱未覆盖石墙区，R2 无 delta 可算。
- **1.21.1 / 1.21.2 neoforge 附加 R2 重连卡死**（确定性：1.21.1 ×3 含一次 `-CleanWorld`、1.21.2 ×2 含一次 `-CleanWorld`；1.21.3–1.21.11 neoforge 均不卡）：R2 握手成功 → 服务端推送管线处理 ~1024 单位后停滞（SERVE-DIAG 停在 1024，PushConsumer/ChunkPush 池全部空闲等队列）→ 客户端零数据流入（applied=0）→ 双端网关线程均空闲 epoll（jstack 实证，无死锁无背压）→ ~59s 后连接 RST → 客户端 `ZSTD decoder/encoder error`（包装 `Connection reset`）→ 崩溃报告 → 客户端挂起被 harness 强杀。卡死会话 R2 bloom 客户端已发出（11952–11987 字节），服务端一次未处理（BLOOM_SYNC 缺席）。

**定位范围（已闭环）**：此前动态探针排除了 confirm/hash 回路迟滞，最终修复点是 NeoForge resync 构建阶段过早丢弃未生成区块；现由重试 drain 等待区块可用后继续提交。

## R2_FULL_CHUNK_TRANSFER（新门禁首战，13 格 P0）

`0d4ca9a` 新增门禁要求 R2 零整柱传输。本轮 13 格命中 `fullChunkRequestCount=1–9`：服务端对 hash 失配柱按 75% 规则回退整柱（`[SECTION_DELTA] Fallback to full for [-1,-2]: changed sections >= 75%`，复跑柱的整柱原始字节与 R1 完全一致）。行为在旧门禁时代已存在（旧会话 fullReq=1），非本轮引入；按既定决策**门禁保持原样、全量记录**。门禁口径与"fallback 可解释即放行"的取舍留待专项（服务端 fallback 日志可逐条解释整柱来源）。

## 矩阵

| 版本 | fabric | forge | neoforge |
|------|--------|-------|----------|
| 1.20.1 | FAIL¹ | FAIL¹ | FAIL¹ |
| 1.21.1 | FAIL¹ | FAIL¹ | FAIL² |
| 1.21.2 | PASS | SKIP | FAIL² |
| 1.21.3 | PASS | FAIL¹ | PASS |
| 1.21.4 | PASS | FAIL¹ | FAIL¹ |
| 1.21.5 | PASS | PASS | FAIL¹ |
| 1.21.6 | PASS | PASS | FAIL¹ |
| 1.21.7 | PASS | PASS | FAIL¹ |
| 1.21.8 | PASS | PASS | FAIL¹ |
| 1.21.9 | PASS | PASS | FAIL¹ |
| 1.21.10 | PASS | PASS | PASS |
| 1.21.11 | PASS | SKIP | FAIL³ |

¹ 仅 `R2_FULL_CHUNK_TRANSFER`。² R2 重连卡死（崩溃）。³ G2 未触发（吞吐坍缩下游）。

## 明细（R1 landed / R2：landed、整柱命中、delta、整柱请求、ovd）

| 版本 | loader | R1 landed | R2 landed | R2 hit | R2 delta | R2 full | ovd | 结果码 |
|------|--------|-----------|-----------|--------|----------|---------|-----|--------|
| 1.20.1 | fabric | 1529 | 453 | 440 | 11 | 2 | 632 | P0 |
| 1.20.1 | forge | 1529 | 453 | 433 | 17 | 3 | 632 | P0 |
| 1.20.1 | neoforge | 1529 | 453 | 381 | 69 | 3 | 632 | P0 |
| 1.21.1 | fabric | 1529 | 453 | 452 | 6 | 5 | 632 | P0 |
| 1.21.1 | forge | 1529 | 453 | 437 | 26 | 9 | 632 | P0 |
| 1.21.1 | neoforge | 91 | — | — | — | — | — | 卡死 |
| 1.21.2 | fabric | 1529 | 453 | 437 | 34 | 0 | 632 | PASS |
| 1.21.2 | neoforge | 80 | — | — | — | — | — | 卡死 |
| 1.21.3 | fabric | 1529 | 453 | 464 | 20 | 0 | 632 | PASS |
| 1.21.3 | forge | 1529 | 453 | 417 | 38 | 4 | 632 | P0 |
| 1.21.3 | neoforge | 585 | 339 | 302 | 37 | 0 | 132 | PASS |
| 1.21.4 | fabric | 1529 | 453 | 452 | 26 | 0 | 632 | PASS |
| 1.21.4 | forge | 1529 | 453 | 446 | 33 | 1 | 632 | P0 |
| 1.21.4 | neoforge | 69 | 453 | 68 | 1 | 384 | 20 | P0 |
| 1.21.5 | fabric | 1529 | 453 | 469 | 5 | 0 | 632 | PASS |
| 1.21.5 | forge | 1529 | 453 | 447 | 32 | 0 | 632 | PASS |
| 1.21.5 | neoforge | 49 | 80 | 45 | 4 | 31 | 20 | P0 |
| 1.21.6 | fabric | 1529 | 453 | 454 | 27 | 0 | 632 | PASS |
| 1.21.6 | forge | 1529 | 453 | 468 | 48 | 0 | 632 | PASS |
| 1.21.6 | neoforge | 49 | 81 | 48 | 1 | 32 | 20 | P0 |
| 1.21.7 | fabric | 1529 | 453 | 459 | 24 | 0 | 632 | PASS |
| 1.21.7 | forge | 1529 | 453 | 455 | 32 | 0 | 632 | PASS |
| 1.21.7 | neoforge | 68 | 118 | 66 | 2 | 50 | 20 | P0 |
| 1.21.8 | fabric | 1529 | 453 | 447 | 37 | 0 | 632 | PASS |
| 1.21.8 | forge | 1529 | 453 | 449 | 43 | 0 | 632 | PASS |
| 1.21.8 | neoforge | 56 | 67 | 52 | 4 | 11 | 20 | P0 |
| 1.21.9 | fabric | 1529 | 453 | 444 | 29 | 0 | 632 | PASS |
| 1.21.9 | forge | 1529 | 453 | 447 | 30 | 0 | 632 | PASS |
| 1.21.9 | neoforge | 49 | 52 | 49 | 0 | 3 | 20 | P0 |
| 1.21.10 | fabric | 1529 | 453 | 441 | 33 | 0 | 632 | PASS |
| 1.21.10 | forge | 1529 | 453 | 447 | 26 | 0 | 632 | PASS |
| 1.21.10 | neoforge | 49 | 49 | 47 | 2 | 0 | 20 | PASS |
| 1.21.11 | fabric | 1529 | 453 | 455 | 18 | 0 | 632 | PASS |
| 1.21.11 | neoforge | 49 | 49 | 49 | 0 | 0 | 20 | G2 缺失 |

几何对照：R1 权威环 VD20 满分 1529（1.21.4+ 新公式 1573，本轮 fabric 均按 1529 观测到）；R2 权威环 453、OVD 环 632（`1.21.4+` 圆柱差异未在 R2 体现）。fabric/forge R1 全部喂满 1529——**上轮"滑块不齐导致 forge/neo R1 少 ~500"的问题已被 `-ClientRenderDistance 32` 钉死消除**（本轮 neoforge 1.21.x 的缺口是吞吐坍缩，性质不同）。

## 重试记录（非代码）

- 1.21.1 neoforge：首跑 R2 卡死（复用存档）→ `_retry`（CleanWorld）卡死 → `_diag`（CleanWorld）卡死 → `_diag2`（CleanWorld，jstack 采样）卡死。共 4 次，全复现。
- 1.21.2 neoforge：首跑客户端 mod 构造崩（root cause 2，修复）→ 修复后重跑 R2 卡死 → `_retry`（CleanWorld）卡死。共 3 次，后两次全复现。
- 1.21.2 fabric / 1.21.3 fabric：首跑即 PASS。

## 残留与后续

- **已闭环**：NeoForge resync 未生成区块丢登记导致的 R1 吞吐坍缩与 R2 OVD 缺失。针对性会话 `1.21.5_neoforge_I_resyncall` 通过：R1=1529 柱，R2 全命中 438，OVD 已加载 632、缺失 0。
- 1.21.1/1.21.2 R2 重连卡死及其它版本矩阵仍需独立专项验证，不因本次 1.21.5 修复宣称全矩阵闭环。
- **P0 门禁口径**：`R2_FULL_CHUNK_TRANSFER` 继续保持现有严格口径；fallback 仍全量记录并由分析器单独判定。
- 验证：`common:test`、`neoforge:compileJava -Pmc_ver=1.21.5` 均通过；修复提交为 `50139d0`。
- Gradle daemon 保留（正常）；25565/25566 已释放。
- 诊断探针已移除临时 payload 计数日志；`.comate/` 未纳入提交。
