# 小型主线程热路径交付（2026-10-23）

范围是实体 pacing、`MixinChunkMap.save` 的 RETURN 注入和 `TickMonitor`。基线参见 [spark 主线程报告](../spark-main-thread-20261023.md) 与 [方法汇总](../spark-main-thread-20261023-methods.csv)；正式样本来自 `build/analysis/spark-20261023/`。本代理没有运行 Gradle 或游戏，留给集中验证代理执行。

## 改动

- 新增 `EntityPacingConfigCache`，按不可变 `HassiumConfig` 快照的对象身份缓存已解析的实体档位、密度表及相关配置。`EntityUpdatePacing.build` 仍在每个活动服务端 tick 重建 pacing 快照；服务器/网络 gate、密度双缓冲翻页、连接包数 drain、pressure sample 和 observer 清理仍按原 tick 顺序执行。配置快照对象替换后解析新值；`EntityUpdatePacing.clear()` 清空缓存并解除旧配置引用。压力账本的倍率上限仍随配置变化重建。
- `MixinChunkMap.hassium$unlockShadowSave` 在读取 ThreadLocal 深度前，先排除非影子服务端和空 chunk。影子服务端仍按深度减一并对同一 chunk 解锁，配对与可重入行为保持原状。
- `TickMonitor` 的 server、Hassium tail、client 三处窗口日志先检查 `DebugLogger.LogType.DISPATCHER`。未启用时跳过 `String.format` 和日志字符串拼接，但仍采样、累计并在 20 tick 窗口结束时重置相同计数器。

## 对其余热点的评估与处置

- `EntityDensityIndex.rotate` 保留双缓冲交换与清空。两个 fastutil map 复用容量，避免逐 tick 分配；翻页是上一 tick 计数语义的边界，不能只清空当前页或跳过某些 level。CSV 中该方法覆盖 8/8 服务端 profile，inclusive mean 约 0.19%，但没有证据支持换成更复杂的事件式状态管理。level 生命周期结束时的移除需要额外生命周期钩子，收益不足以抵消语义与并发风险。
- `EntityPacketCounters.drainGameInto` 保留 ConcurrentHashMap 遍历、游戏态筛选和每连接 `getAndSet(0)`。计数由网络发送路径并发递增，逐连接原子交换定义了每 tick 的计数切面；批量快照或连接事件清理可能改变边界或漏计。调用方 Map 已复用，收益证据不足。CSV inclusive mean 约 0.13%，覆盖 8/8 profile。
- `EntityPressureBook.retainOnly` 保留按本 tick 活跃连接建立的观察者集合及 `retainAll`。它清理断连/非游戏态观察者，集合与账本规模按玩家数而非实体数增长；事件式删除需要额外映射与注销路径，且可能漏掉无效 listener。CSV inclusive mean 约 0.047%，覆盖 8/8 profile。未发现值得增加状态或改变清理时序的证据。

以上百分比是 spark 主线程采样归因，不是 CPU 损失或可回收收益估计；各项父子 inclusive 占比不能相加。

## 回归与验证建议

新增 `EntityPacingConfigCacheTest` 覆盖同一配置身份复用、配置对象替换后采用新间隔/预算，以及 clear 后对同一配置重新生成快照。集中验证代理建议至少执行：

```powershell
.\gradlew.bat "-Pmc_ver=1.20.1" common:test --tests io.github.limuqy.mc.hassium.server.entity.EntityPacingConfigCacheTest
.\gradlew.bat "-Pmc_ver=1.21.1" common:test --tests io.github.limuqy.mc.hassium.server.entity.EntityPacingConfigCacheTest
.\gradlew.bat "-Pmc_ver=1.20.1" forge:compileJava
.\gradlew.bat "-Pmc_ver=1.21.1" neoforge:compileJava
```

代码审查/验证还应确认：`EntityUpdatePacing.clear()` 调用配置缓存 `clear()`；网络 gate 与动态 pressure 仍每 tick 更新；非影子 RETURN 在 ThreadLocal `get()` 之前返回；dispatcher 日志关闭时 20 tick 窗口仍清零，打开时正文格式保持一致。`MixinChunkMap` 没有改动 HEAD 锁定逻辑。按任务分工，本代理不执行这些构建或运行检查。

## 参考与风险

按仓库要求先查 XERJ 社区目录。当前目录索引查询因服务端映射错误返回 `unknown field defs/name`；本机已有 `hassium-spark-reference` 只命中 spark 的 GPL 构建脚本，与配置缓存、计数窗口或锁生命周期无关。因此没有可引用的社区实现，也没有复制外部代码；本改动依据本仓库不可变配置快照、计数器并发模型及采样证据设计。已有报告中的 spark GPL 参考只描述采样工具，不作为这些生产代码改动的实现来源。

主要风险是未来若 `HassiumConfig` 变成可变对象，身份缓存不会看到对象内部原地修改；当前配置是 record 快照，`updateConfig`/reload 替换整个对象。另一风险是线程约束被未来改动破坏：该缓存沿用 pacing 已有的服务端 tick 主线程访问模型，`clear()` 也应保持在该生命周期线程。没有协议、`MC_VER` 分支、异步任务或光照路径改动。
