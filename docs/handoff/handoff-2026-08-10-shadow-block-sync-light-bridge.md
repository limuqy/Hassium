# handoff — 影子端方块同步 + 光照更新桥梁 + 收敛取消 + 投送限流（2026-08-10）

需求全量：`.omp/workflows/shadow-block-sync-light-bridge/REQ.md`（已确认）+ `TASKS.md` + `work/CONTRACTS.md`。
上下文中断/换会话时先读这三个文件。

## 需求背景（一句话）

客户端首波区块加载 25-30s 全可见，根因 = consumeLoop 每批等全局光照收敛（5s 超时 × 5-6 批）；补光靠 watcher 轮询重推整块。方案：取消收敛等待（注入即回传 + 标脏）→ 欠光由影子端 light 出口事件驱动（onLightUpdate 拦截 → 官方 ClientboundLightUpdatePacket 回传）→ ready 队列化 + 每帧硬顶；同时影子端接收服务端方块更新（缓存内容 hash 始终最新，方块变动不再全量重拉）。

## 关键调研结论（已验证，勿重复调研）

- `ServerChunkCache.onLightUpdate(LightLayer, SectionPos)` 1.20.1/1.21.11 签名一致；light 传播必然触发（1.21.11 `LayerLightSectionStorage.onDataLayerUpdate` L270 实证）
- `ClientboundLightUpdatePacket(ChunkPos, LevelLightEngine, BitSet skyMask, BitSet blockMask)` 构造两版逐字一致；mask 位 = sectionY − minLightSection
- 三个方块 handler mojmap 全段同名：`handleBlockUpdate` / `handleChunkBlocksUpdate` / `handleBlockEntityData`；`ClientboundSectionBlocksUpdatePacket.runUpdates(BiConsumer)` 两版逐字一致 → 零 #if
- 影子端主线程投递：`MinecraftServer.execute()` → mainThreadProcessor 由 `runMainLoop` 驱动（generateChunk/loadFromDisk 先例，冒烟已验证）
- `KeyedPriorityQueue`：`offer(item, Key, double, OfferPolicy)` / `poll()` / `isCurrent` / `release` / `clear`；优先级 `MainThreadDispatcher.authoritativePriority(pos)`
- `MainThreadDispatcher` OP：CHUNK_APPLY=0 / REQUEST=1 / BLOCK_ENTITY=2（新增 LIGHT_UPDATE=3）
- `ClientMainThreadBudget.getHardCap()` = `chunk.maxChunksPerFrame`；`tryAcquireCacheApply`/`loadThreads` 零调用 = 死代码
- 注入链：injectChunk 清光（queueSectionData(null)）→ propagateLightSources → 引擎传播；`isLightConverged()` 全局采样；`ShadowStorageHashes.remove(pos)`/`markLightDirty(pos, bool)` 已存在

## 执行方式

- 主会话只做派发与核验，**不自己实现**（实现走 task 子代理）
- 每个任务派一个子代理，任务描述自包含：目标 + 范围 + 验收，不依赖本文件以外上下文
- 独立任务可并行（task 批量），共享状态/有依赖的按序：T1（影子端核心）→ T2（包转发层）；T3（配置清理）与 T1 并行；T4（验证）最后
- 子代理开工先报 ETA；主会话 hub wait 带 timeoutMs = min(max(15min, ETA×2), task.maxRuntimeMs)
- 子代理自维护 `.omp/workflows/shadow-block-sync-light-bridge/work/<agent>-TASK.md`（每步更新，重启/交接时读取恢复）
- 并行任务契约先行：CONTRACTS.md 作为 task batch 的 context 注入；契约变更走主会话仲裁
- 资源隔离纪律：gradle 全仓库同时只允许一个构建（--no-daemon）；禁共享 daemon；只 kill 自己启动的进程；临时文件按 agent 隔离
- 报备模式：ETA>48min 的任务每 ≤45min 主动 yield 写进度，主会话检查后续跑
- 全部完成后主会话核验验收标准（看证据，不轻信自述），通过才收尾

## 验收标准（REQ.md 六项）

1. common:compileJava 锚点 1.20.1/1.21.11 通过
2. consumeLoop 无收敛等待（常量与 watcher 全删）
3. ready = KeyedPriorityQueue，drainReady ≤ max(1, getHardCap())
4. 三 handler 转发 + MixinServerChunkCache 门控拦截
5. tryAcquireCacheApply/loadThreads grep 零结果；maxChunksPerFrame 描述更新
6. 冒烟 fabric 1.20.1 + 1.21.11：无崩溃、首波提速、方块变化光照无黑块、断连重进正常

## 风险

- 冒烟双版本耗时最长（T4 ETA 120min，报备模式）
- 并行 gradle 互踩：契约已限定串行，子代理开工先确认
- 方块更新线程投递延迟（execute 队列由 pollTask 间隙驱动）：极端繁忙时延迟应用，最终一致，可接受
