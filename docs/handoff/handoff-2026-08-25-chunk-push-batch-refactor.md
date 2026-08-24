# Handoff — 区块推送批量化重构续开发

日期：2026-08-25
状态：执行中

## 目标

完成当前未提交的区块推送批量化 clean cutover。该重构以 `ChunkDataRequestC2SPacket` 的 hit/miss 回执取代 `deliveryId`、`ChunkApplyAck` 和 AIMD admission 流控；不得以删除补发或兼容路径换取通过。

## 当前进度

- 协议、客户端与三加载器已基本迁移：packet record 为 `(dimension, chunks, result)`；ACK 聚合器/出站帧及网关路径已删除；full payload/SeedRef 已移除 deliveryId。
- `ServerChunkPushManager` 已有批队列和 pending-confirm 骨架，但仍需验证并完善 10 批上限、批内 fan-out、5 秒超时直推等完整行为。
- `ChunkAdmissionController` 及其 ACK/deliveryId/AIMD 测试仍在工作树中，必须 clean cutover 删除。
- ConfigSchema 与 `docs/config-audit.md` 有既存未提交改动；除非明确与 REQ 冲突，不能随手修改。
- 已知此前 `./gradlew` 在 Windows 宿主失败（Win32 error 193）；验证必须使用 `./gradlew.bat`，不可据旧错误推断源码状态。

## 权威输入

- 需求与验收：`.omp/workflows/chunk-push-batch-refactor/REQ.md`
- 任务分解：`.omp/workflows/chunk-push-batch-refactor/TASKS.md`
- 并行契约：`.omp/workflows/chunk-push-batch-refactor/work/CONTRACTS.md`
- 原始共享协议：`C:/Users/10885/.omp/agent/sessions/--D--project-MC-Hassium--/2026-08-24T13-30-22-572Z_01a033f7-07ec-765b-8411-a53fea0c71a9/local/push-refactor-contract.md`

## 执行方式

- 主会话只做派发与核验，**不自己实现**（实现走 task 子代理）。
- 每个任务派一个子代理，任务描述自包含：目标、范围、验收；直接读取上述权威文件补全细节。
- 独立任务才批量并行；当前 T1 → T2 → T3 有文件与验证依赖，严格串行。
- 子代理开工先写 `.omp/workflows/chunk-push-batch-refactor/work/<agent>-TASK.md`，记录 ETA；每步更新，重启/交接时读取恢复。
- 子代理执行 T1/T2 时不运行 Gradle、格式化、lint 或全项目测试；T3 独占 Gradle，不停止 daemon、不杀 Java、不与其他构建并发。
- 全部子任务完成后主会话核验 REQ 验收标准，不采信无证据自述。
