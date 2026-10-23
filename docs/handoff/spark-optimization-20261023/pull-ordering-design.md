# T7：Pull 快照与后续更新排序（实现前设计）

本设计不增加线协议。服务端和客户端各补一段有界尾部，使 snapshot→后续原版更新保持所有者顺序；仅服务端修订检查无法覆盖客户端异步解码/算光/可见应用期间的乱序。

## 服务端

捕获 FULL/DELTA 快照前，为 `(connection identity, level identity, dimension/column, requestId)` 建立在途 token。`Connection.send` 的现有公共钩子先走 sleep suppress，只有实际放行的 block update / section blocks update / BE data 才进入新的观察器。原包照常发出，同时在所属服务端线程用既有 PacketCodecCompat 复制成独立字节（不保留活跃 BE/注册表可变状态）。bundle 按孩子原序观察。异步来源不能假定世界所有权，标记该 token 失效并重捕获。

worker 只计算原有 detached 数据，完成后回服务端所有者。必须校验 work lease、session/connection、level/dimension、当前同柱 token。成功先发送 Pull response，再按观察顺序恢复并发送尾部；关闭 token 后回放，避免自我收集。正常 vanilla 世界操作在同一所有者线程，发送 response 与 tail 之间没有另一个世界 tick。连接换代/换维/移出范围/编码失败/关服均释放 token/尾部/work lease。旧同柱 worker 不能关闭新 token。

每柱限制 512 包和 256 KiB payload，另外按包引用开销预留，捕获时在既有 encode WorkBudget 的估算字节中预留固定尾部额度；总量受原有 128 全局/16 每玩家任务约束。超限不回放残缺历史，而是释放当前结果、回到既有 Pull 队列重捕获当前柱。不存在后台 Future 等待，也不建立新的无界队列。

## 客户端

在 `PayloadHandlers.handleShadowPullResponse` 仍处于原版包的所有者顺序、且尚未提交后台 decode 时，只读 envelope，为 FULL/DELTA 建立同柱 fence。UNCHANGED NBT 继续现有 inline 可见更新与 AsyncSnapshotCommit，不增加后台编码。

三个原版 block/section/BE handler 在 render thread 检查 fence。若该柱尚未完成当前 snapshot，则将已经解码的包重新捕获为独立字节并取消当前 handler；不先转发给影子，也不先改可见世界。snapshot 注入和必要光照/可见交付完成后，在 render owner 按原包顺序重新派发，原有 metadata 转发将尾部放回影子所有者队列。回放的绕过只覆盖当前 owner 调用栈。

为了防旧摄入/旧 ready 在新尾部之后覆盖，通过已有 SleepTerrainClient 的通用调用桥补普通 Pull 的 token：incomingToken 捕获 opaque version（不是 wire requestId），acceptPending 拒绝旧版本，injected 绑定对象，snapshotToken 生成 ready 版本，acceptReady 拒绝旧 ready，applied/haloApplied 触发尾部。R2 修订需要接通 ShadowLightCompute 的官方 B 入队，定点更改实际柱构造/版本捕获桥；另增加 gated 运行观测，未改变光照/确认算法。普通可见柱必须在真正 applyReady 成功后回放，不能误用当前 FULL 的 ingested 回调（它只表示排入影子管线）。halo 在注入回调后回放；这些回调本来已经调回 Minecraft owner。

客户端尾部也按 count/bytes/active columns 有界，租约保持到可见应用/halo提交/丢弃。超限取消旧版本并触发当前完整重拉，不能让旧管线重新确认。断连/换维/unload/解码失败/新请求替换需要显式清理。保留完成版本的有界 tombstone，让早于当前 fence 的 ready 包不能在完成后重新变成有效。

## 运行时复现与回归

仅在 smoke/dev gate 下，为指定柱建立 worker latch。主线程捕获 snapshot 后释放观察标记，测试驱动在同柱更改方块和 BE tag（必须确保旧客户端已拿到相关柱），期间另改一次 BE-only tag。服务端日志先证明新 native updates 已送出，随后解除 worker。客户端还需可控延迟 decode/visible apply，以覆盖 response 后的新 native updates；逐阶段记录 requestId/token/捕获与回放计数。

断言客户端可见柱和影子基线均保持最后的 block/BE 值；尾部在 snapshot 应用后按序；旧同柱 job/ready 无法再落地；ACK/authority 完成不早于快照提交和尾部消费；预算/在途计数最终归零。使用同一 fixture 的 legacy bypass gate 先获得确定失败，再启用实现重跑通过。另覆盖断连重连、换维同坐标、编码失败和尾部超限重捕获，且不能改变正式 benchmark 参数。

当前文档是实现前方案，不是已完成/已通过声明。构建执行窗口期间源文件保持冻结。
