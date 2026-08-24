# handoff — loader-parity 最终报告遗留项清偿（2026-08-24）

## 执行方式
- 主会话只做派发与核验，**不自己实现**（实现走 task 子代理）
- 每个任务派一个子代理，任务描述自包含：目标 + 范围 + 验收，不依赖本文件以外上下文
- 独立任务可并行（task 批量），共享状态/有依赖的按序
- 子代理开工先写 work/<agent>-TASK.md（含 ETA 预估）；主会话等待期：有活干活（结果自动送达），无活 hub wait 带 timeoutMs = max(15min, ETA×2)
- 子代理自维护 .omp/workflows/loader-parity-leftovers/work/<agent>-TASK.md（每步更新，重启/交接时读取恢复）
- 并行任务契约先行：work/CONTRACTS.md 作为 batch context 注入
- 资源隔离纪律：独立端口、禁共享 daemon、只 kill 自己启动的进程、临时文件按 agent 隔离
- 全部完成后主会话核验验收标准（看证据，不轻信自述），通过才收尾

## 需求与任务

- REQ/TASKS：`.omp/workflows/loader-parity-leftovers/{REQ,TASKS}.md`
- 契约：`.omp/workflows/loader-parity-leftovers/work/CONTRACTS.md`
- 上轮真相源：`docs/loader-parity-final-report.md`（§5 光照专项、§7 遗留项）

## 拍板记录

- 2026-08-24 用户拍板：同会话执行；A1 手段 = 自适应窗口 + 加速回收。
- 派发顺序：A1 ∥ B1 → B2 → C1 → D1。

## 关键调研结论（2026-08-24 主会话核对）

1. ACK 回程链路 loader 无关（common）：`ClientChunkPipeline`（tick 尾 flush）→ `NetworkCore.sendChunkApplyAck:1128` → `OutboundConnection.sendFrame:362`（每批 writeAndFlush）→ `GatewayPlatformWiring` ACK sink → `ServerChunkPushManager.handleChunkApplyAck` → `ChunkAdmissionController.acknowledge/finishBatchMember` 释放窗口。
2. 已有机制：FIRST_ACK_PROBE_INTERVAL_NANOS=500ms 探针；DELIVERY_TIMEOUT_NANOS=8s 回收；MAX_PENDING_DELIVERY_IDS=2560 fail-fast。
3. 稳定指纹：服务端 STALL-DIAG `q=384 满 + inFlight 42>10 窗口`、unackedBatches 13-17 堆积冻结 admission；landed≈208-239 vs neoforge 同版≈1050。
4. 原 T3-light-eaves.md / T5-light-probe.md 已随 smoke-finalize 收尾清理，光照打点需按报告 §5 摘要重建（探针 SectionPos(-13,5,3)→区块 (-13,3)，W=15 E=3 N=14 S=0 vs vanilla E=5）。
