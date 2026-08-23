# Handoff — 影子端预览光照（隔离官方引擎逐柱预计算）

> 日期：2026-08-23
> 工作流：`.omp/workflows/shadow-preview-light/`（REQ.md / TASKS.md）
> 执行方式：同会话派发（阶段 3），主会话只做派发与核验

## 执行方式

- 主会话只做派发与核验，**不自己实现**（实现走 task 子代理）
- 每个任务派一个子代理，任务描述自包含：目标 + 范围 + 验收，不依赖本文件以外上下文
- 独立任务可并行（task 批量），共享状态/有依赖的按序
- 子代理开工先写 work/<agent>-TASK.md（含 ETA 预估）；主会话等待期：有活干活（结果自动送达），无活 hub wait 带 timeoutMs = max(15min, ETA×2)
- 子代理自维护 `.omp/workflows/shadow-preview-light/work/<agent>-TASK.md`（每步更新，重启/交接时读取恢复）
- 并行任务契约先行：CONTRACTS.md（文件归属/端口/共享格式）作为 batch context 注入
- 资源隔离纪律：独立端口、禁共享 daemon、只 kill 自己启动的进程、临时文件按 agent 隔离
- 全部完成后主会话核验验收标准（看证据，不轻信自述），通过才收尾

## 背景速览（子代理必读）

影子端（客户端进程内 SeedGen）恢复邻居门槛修复屋檐缺光后，park 等待拖慢区块首现。方案：隔离官方 `LevelLightEngine(shim, true, true)` 对单柱预计算天光+方块光（原版 `updateSectionStatus → setLightEnabled(true) → propagateLightSources → runLightUpdates`，shim 的 `getChunkForLighting` 只返回本柱），拷出 `DataLayer` 后 `queueSectionData` 进真引擎并即时首推预览包；收敛整包随后替换。**欠估不变量**：预览 ≤ 终值（引擎只加不减，过亮永久残留）；隔离计算天然满足。

工作区已有未提交前置改动（保留，勿回退）：Fix A（shouldSkipUnchangedRepush 加 lightComplete 参数）、Fix B（sweep 超 waiter 豁免在途上限）、park 分支不推欠光包、回归测试断言更新。

## 关键事实（调研已证）

- 原版 1.21.1 `lightChunk(chunk,false)` 只播种不清光；`initializeLight(lighted=false)` 不填源上方 15——15 来自 `propagateLightSources`
- 客户端 `readSectionList`：两掩码都不在的 section 不动（「不提即不改」）；`applyLightData` 尾部客户端自跑 `setLightEnabled(true)` 自算源上方
- 引擎 `getDataLayerData` 先查 queuedSections——`queueSectionData` 后立即可打包进包，无需等 adoption
- 屋檐案例：(-13,3) secY5 z=0 lx=13..15 y87..93 应 ≥11；北邻 (-13,2) z=15 行为参照
- R1 慢的根因是 park 级联+在途上限饱和；预览解决视觉黑窗，Fix B 缓解饥饿

## 构建与验证命令

```powershell
.\gradlew.bat common:compileJava common:test   # daemon 构建，勿 --no-daemon
pwsh scripts/runtime-smoke-test.ps1 -Ver 1.21.1 -Loader neoforge -Phase I -SessionId <id>
# 结果: build/smoke-test/results/result_<id>.json；日志: build/smoke-test/logs/{client,server}_<id>.log
```

存档解析要点：type126 = 压缩字节0x7e + magic0x48 + 8B hash + ZSTD-dict body，字典
