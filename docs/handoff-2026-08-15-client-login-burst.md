# Handoff — 客户端进服首波加载提速（client-login-burst）

> 日期：2026-08-15 ｜ 需求来源：用户主诉服务端区块已就绪、客户端登入→看到世界（加载页面）慢
> 真相源：`.omp/workflows/client-login-burst/REQ.md` + `TASKS.md`（本文件是需求落实兜底）

## 需求

客户端进服首波加载提速。瓶颈 = 服务端 `master.maxChunksPerTick=5`（100/s）双流限速（hash 批次 + 数据直推），VD=20 → 1681 块 ≈ 17s+ 加载页。历史 `client-load-speed/`（08-13）已闭环正确性回归，本次是正常路径提速，不得回归其验收指标（过期 ≤100、缓存/光照命中、无崩溃、矩阵绿）。

## 方案

- A 服务端首波动态提速：join 后首波窗口（或队列深度）双流限速动态放宽 ×3-4，回落默认；限速取值收敛单一 getter。
- B 首连流水化：首连且影子盘空时客户端提前批量 requestFullChunks，服务端 resync bloom miss 直推补齐；重复请求去重/REPLACE 吸收。
- C 客户端吞吐确认（副瓶颈排查，T0 定，不扩 scope）。

## 任务

T0 scout 现状确认 + CONTRACTS（依赖前置）→ T1 服务端 burst ∥ T2 首连流水化（共享 ServerChunkPushManager，契约隔离）→ T3 冒烟三版本 + 编译矩阵 + 提交。详见 TASKS.md。

## 执行方式

- 主会话只做派发与核验，**不自己实现**（实现走 task 子代理）
- 每个任务派一个子代理，任务描述自包含：目标 + 范围 + 验收，不依赖本文件以外上下文
- 独立任务可并行（task 批量），共享状态/有依赖的按序
- 子代理开工先写 work/<agent>-TASK.md（含 ETA 预估）；主会话等待期：有活干活（结果自动送达），无活 hub wait 带 timeoutMs = max(15min, ETA×2)
- 子代理自维护 .omp/workflows/client-login-burst/work/<agent>-TASK.md（每步更新，重启/交接时读取恢复）
- 并行任务契约先行：CONTRACTS.md（文件归属/共享接口）作为 batch context 注入
- 资源隔离纪律：独立端口、禁共享 daemon（gradle --no-daemon）、只 kill 自己启动的进程、临时文件按 agent 隔离
- 全部完成后主会话核验验收标准（看证据，不轻信自述），通过才收尾
- 并行子代理不 commit/push，提交由主会话核验后统一做

## 关键文件与已知风险

- 关键文件：`ServerChunkPushManager`（flushPlayerHashBatchIfDue:1051 / drainPlayerQueueTick / resync:78 / bloom 直推:624-640）、`ConfigSchema`（maxChunksPerTick:61）+ 配置接线、`ClientMetadataHandler`（requestFullChunks:366）、`ClientChunkPipeline`（shadowServerReady）、`ShadowLightCompute`（consumeLoop/drainReady）。
- **外部会话并行编辑中**（ServerChunkPushManager/ClientMetadataHandler/ShadowLightCompute 等 2026-08-15 有改动）：所有子代理以磁盘最新为准，T0 不得引用过期行号；实现任务动文件前先 read 最新。
- 冒烟：`scripts/runtime-smoke-test.ps1` 同参数（VD=20/10、DelayMs=10000）、`--no-daemon`、独立端口、只 kill 自己进程。
