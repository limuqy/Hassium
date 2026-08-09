# Handoff — 网络核心（进程内网关 + 纯原版客户端协议 + 无感主控切换）（2026-08-09）

需求定稿：`.omp/workflows/network-core/REQ.md` + `TASKS.md`（读这两个文件为主，本文档为执行方式与上下文摘要）。

## 背景摘要

现状：压缩/聚合/握手编解码器挂原版 Connection pipeline，UDP 数据面直灌影子端，failover 断连重连重走 login（世界重载）。三大问题：第三方 mod 兼容性（UDP 绕 handler、聚合破坏管道、命中只发 hash）、主控切换世界重载、网络逻辑散落。

目标：客户端引入**网络核心（进程内网关）**完全接管客户端↔主控收发。客户端世界侧只见纯原版 Packet 对象（handler 层注入，管道零 Hassium 字节）；主控切换在网关内部完成（B 侧无 login 续流），客户端 Connection 不断、世界零重载；叠加 L1 负载均衡（策略驱动整池/分组主动迁移）。

## 已确认决策（用户拍板，2026-08-09 多轮）

- **注入方案**（非回环）：网关接管客户端网络收发；入站 = 自有通道收包解码后 handler 层注入（`ClientPacketListener.handleXxx`），出站 = C2S 截获转自有通道。调研结论：pipeline 层拦截 mod 几乎没有热门（唯一例外 ViaFabric，场景特殊），handler 层是生态主流
- **客户端↔世界侧纯原版协议**：无压缩/无聚合/无自定义包；管道层 mod、handler 层 mod 全部兼容
- **网关↔主控自有通道**：ZSTD/聚合/自定义协议/UDP 数据面/握手全部保留（用户原话"无所谓"）；所有优化在此段
- **区块核心（影子端）保留**：OVD 影子端生成经原版区块加载通道推送；命中时读盘/生成仍走原版通道（第三方可见）；网关↔区块核心进程内对象直传
- **B 侧无 login 续流**：票据 + epoch 防重放 + 会话/hash 表同步 + 只发增量；网关侧剥包逻辑全省
- **L1 负载均衡**：策略驱动整池/分组迁移（故障 + 负载阈值/维护窗口/演练），复用续流；预热 + 空闲窗口；L2 逐玩家路由不做（多写冲突）
- **ViaFabric 兼容**：检测到 ViaFabric → 注入点改挂其取包处
- **删除**：管道编解码器、UDP 客户端、failover 全套、MixinVanillaChunkApplyBudget、预握手 mixin、MixinLightRecompute（详见 REQ A6）
- **保留**：区块核心全套、实体转发、OVD、生命周期、renderOnly mixin（详见 REQ A7）

## 关键技术锚点（调研阶段要确认，T0-T3）

- 注入工程形态：handler 直调 vs LocalChannel 替换（原版 Connection 状态保持）；现状 `applyToLevelFromByteBuf → handleLevelChunkWithLight` 已有 handler 直调先例（含重入标志/线程语义）
- 登录桥接：客户端→主控的 status/login/加密/压缩/config/play 全生命周期经网关；token 转发主控（正版/离线两模式）
- 自有通道：现有压缩/聚合/握手/UDP 代码从"原版 pipeline 挂载"改造为"mod 自建连接"（T2 定改造面）
- 续流：服务端推送状态（ServerChunkPushManager per-player 队列/hash 表/玩家会话/UDP session）随热备同步到 B；B 验票后无 login 续 play 流
- 主控 A/B 同世界热备是前提（世界同步为独立工程，不在本次范围）

## 执行方式

- 主会话只做派发与核验，**不自己实现**（实现走 task 子代理）
- 每个任务派一个子代理，任务描述自包含：目标 + 范围 + 验收，不依赖本文件以外上下文
- 独立任务可并行（task 批量），共享状态/有依赖的按序
- 子代理开工先报 ETA；主会话 hub wait 带 timeoutMs = max(15min, ETA×2)
- 子代理自维护 `.omp/workflows/network-core/work/<agent>-TASK.md`（每步更新，重启/交接时读取恢复）
- 全部完成后主会话核验验收标准（看证据，不轻信自述），通过才收尾
