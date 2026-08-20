# 渐进式区块推送执行交接

## 目标

参考 Minecraft 1.21.11 `PlayerChunkSender`，把 Hassium 服务端固定盲推改为由客户端 authoritative apply ACK 驱动的渐进式 admission，消除进服后近环快速、外环长期缓慢、随后积压爆发以及同坐标高频重放。

## 权威文档

- 需求：`.omp/workflows/progressive-chunk-push/REQ.md`
- 任务：`.omp/workflows/progressive-chunk-push/TASKS.md`
- 并行契约：`.omp/workflows/progressive-chunk-push/work/CONTRACTS.md`（执行前生成）

## 已确认事实

- 当前 `master.maxChunksPerTick=5` 满速约 100 chunk/s；客户端 authoritative apply 最近窗口平均约 75 chunk/s。
- 进服前 15 秒服务端压缩 1354 块，客户端应用 711 块，至少形成约 643 块管线积压。
- 最近一分钟 4521 次 `shadow_applied` 仅 3013 个不同 target，重复应用 33.4%。
- 约 8 视距不是硬编码阈值，而是距离优先下近环先落地、外环进入无背压管线后的视觉边界。
- 1.21.11 原版采用 pending、近距优先、渐进 quota、客户端反馈与未确认窗口；Hassium 不能复用 vanilla batch ACK，因为它不覆盖压缩、传输、影子算光和最终客户端落地，且 1.20.1 不存在该协议。

## 核心决策

1. 保留 vanilla `ChunkMap` 玩家加入、移动、视距差分和 chunk-ready 触发；Mixin 只进入 Hassium admission。
2. 服务端使用 per-player `(dimension, chunk)` keyed pending/in-flight 去重。
3. 每次 full delivery 分配单调 `deliveryId`，客户端仅在 authoritative 最终落地后批量 ACK。
4. 首次 ACK 前仅一个未确认批次；首次 ACK 后最多 10 个；每 tick 不超过 `master.maxChunksPerTick`。
5. resync/hash/full request 共用 admission；实时更新保持独立高优先级。
6. `GatewayChannel.isWritable` 是第二道传输背压，不能替代 apply ACK。
7. clean cutover，不保留无 ACK 的全量推送旁路。

## 执行方式

- 主会话只做派发与核验，**不自己实现**（实现走 task 子代理）。
- 每个任务派一个子代理，任务描述自包含：目标 + 范围 + 验收，不依赖本文件以外上下文。
- 独立任务可并行；共享状态或严格依赖按 `.omp/workflows/progressive-chunk-push/TASKS.md` 顺序执行。
- 子代理开工先写 `.omp/workflows/progressive-chunk-push/work/<agent>-TASK.md`，记录 ETA、进度和下一步；不运行 formatter、linter、项目级测试或 Gradle，统一验证由 T5 执行。
- 每完成一个文件/模块/验证里程碑主动 yield；主会话核验后续派。
- 并行共享接口先由 `.omp/workflows/progressive-chunk-push/work/CONTRACTS.md` 固定；契约变更必须上报主会话，禁止私自改变影响其他任务的 wire format 或接口。
- 并行任务禁共享 Gradle daemon/build 输出；T5 是唯一编译测试执行者，T6 是唯一运行时进程持有者。
- 子代理不 commit/push；所有等待带超时；只停止自己启动的进程。
- 全部完成后主会话逐条核验 REQ 验收标准，通过后才收尾。

## 验收基线

- 服务端 full 生产不再长期高于客户端 apply。
- 无多秒低谷后百级集中爆发。
- 重复 `shadow_applied` 相对 33.4% 显著下降。
- 近环优先且 VD20 最终完整。
- 公共测试、目标加载器/版本编译和 Fabric 1.20.1 运行时冒烟均通过。
