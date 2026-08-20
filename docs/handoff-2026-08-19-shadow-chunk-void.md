# 影子端区块大片虚空修复交接

- 日期：2026-08-19
- 执行方式：同会话执行
- 需求与任务：`.omp/workflows/shadow-chunk-void/REQ.md`、`.omp/workflows/shadow-chunk-void/TASKS.md`

## 已确认根因

网络核心 bootstrap 可在 `ClientLifecycleHelper.onLogin()` 前分发 S2C。剥光 `ClientboundLevelChunkWithLightPacket` 在 `GatewayS2CRouter.routeChunk()` 被 `ShadowLightCompute.submit()` 接管。此时握手已完成但 `gameDir/serverId` 未由 `recordCacheLocationSync()` 设置，`ShadowServerRegistry.getOrCreate()` 返回可恢复的 `null`。`ShadowLightCompute.consumeLoop()` 却清空所有 pending 队列并退出，首批权威全量区块没有回退，形成永久虚空。

## 执行方式

- 主会话只做派发与核验，不自己实现。
- 每个任务派一个子代理；任务描述必须含目标、范围和验收。
- T1 → T2 → T3 串行：T2 依赖时序测试，T3 依赖修复。
- 子代理开工先维护 `.omp/workflows/shadow-chunk-void/work/<agent>-TASK.md`，记录范围、进度、验证与下一步。
- 不并行运行 Gradle/客户端；所有 Gradle 命令用 `--no-daemon`；不启动共享 watcher；仅停止自己启动的进程。
- 主会话收到结果后先同步 todo，再核验证据；失败返回原代理修复。

## 不变量

1. 权威全量区块绝不能因为影子端“暂未创建”而丢失。
2. “暂未满足创建条件”与真正 `failShadowServer()` 失败必须区分。
3. 真创建失败仍按既有降级语义处理，不能把剥光包直接应用为无光块。
4. SeedGen 与 renderOnly 继续低优先级，不得抢占权威区块。
5. 不修改服务端协议、握手契约或 type 126 存储格式。
