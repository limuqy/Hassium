# Handoff — 修复 1.21.11 区块缓存命中率低（2026-08-13）

真相源：`.omp/workflows/fix-12111-cache-hit/REQ.md` + `TASKS.md`（本文件为兜底，冲突时以 REQ/TASKS 为准）。

## 需求一句话

1.21.11 R2 区块缓存命中率 17.8%（1.20.1/1.21.1 为 100%）。根因链：P1 ChunkSender 抢跑原版直发（拦截条件等 enableCompression）+ P2 resync null 丢弃 + P4 摄入链风暴（并发崩溃/full 回退链/超时）。修复 F1/F2 + P1/P2/P3，命中率推送区 100%。

## 关键代码位置

- `common/src/main/java/io/github/limuqy/mc/hassium/mixin/MixinPlayerChunkSender.java` — 拦截条件（F1：`GatewayServer.getInstance().registry().get(uuid) != null` 才放行）
- `common/src/main/java/io/github/limuqy/mc/hassium/network/ServerChunkPushManager.java` — `drainPendingResync`（F2：null entry 重入队 + skipped 配额）
- `common/src/main/java/io/github/limuqy/mc/hassium/network/seedgen/ShadowLightCompute.java` — P1 chunk 锁 ×4 + P3 delta 自适应超时
- `common/src/main/java/io/github/limuqy/mc/hassium/network/seedgen/SeedGenExecutor.java` — P2 回退走 new 路径 + 去重 + 诊断埋点
- `common/src/main/java/io/github/limuqy/mc/hassium/network/gateway/GatewayServer.java` L61 `getInstance()` / L265 `registry()`

## 执行方式

- 主会话只做派发与核验，不自己实现（实现走 task 子代理）
- 子代理开工先写 work/<agent>-TASK.md（含 ETA 预估）；主会话等待期 hub wait 带 timeoutMs = max(15min, ETA×2)
- 串行纪律（T3/T4 实测教训）：**同 loader 冒烟共享 fabric/run 目录，跨 mc_ver 构建共享根 build.properties（root.gradle 配置期按 mc_ver 重写）→ 任何两个 gradle 构建/冒烟不得并行**
- 全部完成后主会话核验验收标准（看证据，不轻信自述）

## 状态

- [x] 调研完成（P1/P2/P3 证据链 + P4 摄入链风暴）
- [x] REQ/TASKS 落盘
- [x] 拍板：同会话执行
- [x] Phase 1 T1（F1）/ T2（F2）— 完成，编译通过
- [x] Phase 2 T3-T7 — 完成（1.21.11 R1 1514 新增/0 过期、R2 hash 推送区 100%、零崩溃；1.21.1 R1 1317/R2 100%；1.20.1 无回归；T6 编译矩阵 18/18）
- [x] 提交 98e9b7e（4 文件 +269 -42）

## 遗留（后续波，非本轮）

- ① ConsecutiveExecutor 关闭竞态（客户端 Stopping 时 executor shutdown 后 runMainLoop 仍提交 ChunkTaskDispatcher 被拒；断开时序：先停 shadow server runMainLoop 再关 executor）
- ② P4 clearChunkLight 光照任务路由（1.21.9+ ChunkTaskDispatcher 架构，R1 收敛 ~25s vs 1.21.1 ~4s；`#if MC_VER >= MC_1_21_2` 每 chunk 单条 dispatcher 任务）
- ③ OVD 环带缓存可达性（超视渲染环带无服务端 hash 推送 → 缓存不可达；用户拍板：口径排除，不计入命中率）
