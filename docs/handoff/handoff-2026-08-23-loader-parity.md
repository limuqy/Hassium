# handoff — 三加载器实现差异归拢（2026-08-23）

需求：`.omp/workflows/loader-parity/REQ.md` / `TASKS.md`（差异清单逐条核实记录在 REQ 表格）。
拍板：同会话执行；冒烟 = 6 重点格全套（classic + seedgen/migrate 追加场景）+ 五段锚点 10 格基础 classic。

## 执行方式
- 主会话只做派发与核验，**不自己实现**（实现走 task 子代理）
- 每个任务派一个子代理，任务描述自包含：目标 + 范围 + 验收，不依赖本文件以外上下文
- 独立任务可并行（task 批量），共享状态/有依赖的按序
- 子代理开工先写 work/<agent>-TASK.md（含 ETA 预估）；主会话等待期：有活干活（结果自动送达），无活 hub wait 带 timeoutMs = max(15min, ETA×2)
- 子代理自维护 .omp/workflows/loader-parity/work/<agent>-TASK.md（每步更新，重启/交接时读取恢复）
- 并行任务契约先行：CONTRACTS.md（文件归属）作为 batch context 注入
- 资源隔离纪律：**并行修复期子代理一律不跑 Gradle 编译**（主会话统一收口跑锚点矩阵）；只 kill 自己启动的进程；临时文件按 agent 隔离
- 全部完成后主会话核验验收标准（看证据，不轻信自述），通过才收尾

## 切片与波次（文件归属互斥）

### Wave 1（并行 5 片）
| Agent | 文件归属 | 覆盖 TASKS |
|---|---|---|
| ForgeNet | `forge/**/network/ForgeNetworkManager*.java`、`forge/**/HassiumMod.java`、可新增 forge payload 注册文件 | T1(forge)+T3+T6+T7(forge)+T8(forge) |
| NeoNet | `neoforge/**/network/NeoForgeNetworkManager*.java` | T1(neoforge) |
| ClothFix | `neoforge/**/client/HassiumNeoForgeConfigScreens.java` | T2 |
| Appliers | `fabric/**/platform/FabricClientChunkApplier.java`、`forge/**/platform/ForgeClientChunkApplier.java` | T5 |
| CmdsDist | `forge/**/command/*`、`neoforge/**/command/*`、`forge/**/HassiumForgeClient.java` | T4+T9 |

### Wave 2（Wave 1 后串行 1 片）
| Agent | 文件归属 | 覆盖 TASKS |
|---|---|---|
| FabricNet | `fabric/**/network/FabricNetworkManager.java`、`FabricPayloadRegistry.java`、`fabric/**/HassiumMod.java` | T7(fabric)+T8(fabric) |

### 收口（主会话调度）
- T10 锚点编译矩阵 + scanVersionBoundaries（主会话统一跑，失败定向打回对应 agent）
- T11/T12 冒烟（并发 ≤2 格；重点格追加 seedgen/migrate 场景）
- T13 问题修复循环 → 收尾

## 关键背景（子代理必读事实）
- 2.0.0 客户端预握手发送端已删（网关自有通道握手承担），TASKS 原 #8 不修
- Forge 构建由 neoforge 子项目 `loom.platform='forge'` 承载（1.21.x 段）；forge 模块源码为 Forge API 写法
- NeoForge `sendCompressedChunk(byte[])` 已是目标形态（T11-19），Fabric/Forge 对齐它
- LightDelta Forge 现状：service 回调链在，但 `ForgeNetworkManager.sendLightDeltaPacket` 仅 release buf；若核实三端客户端均不消费 vanilla 通道 LightDelta 则上报裁决，不得私自改丢弃

## 完成状态与遗留项（2026-08-24 收口）

已确定解决并提交：
- LightDelta 三端一致收口：vanilla 通道 S2C 不再发送（三端客户端均不消费，唯一消费在网关帧链路），发送端仅 release buf；`NetworkManager` 接口注释落档裁决口径
- gateway_info 无条件注册（先于 master.enabled 守卫）：Fabric `FabricPayloadRegistry.registerGatewayInfo()`；Forge 50+ 新增 `ForgeGatewayInfoRegistry`（channel 注册 + 出站 RawCustomPayload→ForgePayload 改写）
- Bloom 客户端同步三端补齐（Forge/NeoForge 由 default no-op 改为真发包）；`sendCompressedChunk` 复用已编码 payload（消除二次 encode）
- Fabric/Forge `ClientChunkApplier` 断连窗口防护（对齐 NeoForge 的 `packetListener.getLevel()==null` skip）
- NeoForge Cloth 包名修正（`me.shedaniel.clothconfig2.api`）+ 自身 UI 类可加载性探测
- Forge `/hassiumc` 客户端命令独立注册（新增 `ForgeHassiumClientCommand`，Dist.CLIENT）；migrate 子树合并为单一 greedyString 参数消 brigadier 歧义
- `ChunkAdmissionController` 首 ACK 前慢回程探测（fabric 网关 CHUNK_APPLY_ACK 回程慢一量级导致单批滴灌 + 8s 超时 requeue 风暴；两相邻 tick 确认后每 tick 放行一批，上限 8 批）+ L0 用例
- `ServerChunkPushManager` 批表方法 `synchronized`；`SeedGenExecutor` cacheServed 不计入 locallyGenerated（G4 门禁去抖）
- 影子端结构性互斥门：新增 `ShadowRegistryGate`（<1.21.1，序列化读门 vs forge `GameData.revertToFrozen()` 写锁），`MixinMinecraft.clearLevel`/`ShadowServerCompat.write`/`ShadowReloadListenersCompat`（1.21.1 段 listener 剪裁）接门
- 冒烟基建：服务端存档按 loader×MC版本 隔离（parity_<loader>_<ver>）、日志审计噪音豁免（Realms/profile key pair）、客户端日志 ANSI 剥离

验证：`common:test` 全绿（2026-08-24）；冒烟结果 JSON 在案 `build/smoke-test/results/result_*_parity.json`。

## 遗留项

### fabric R1 加载区块偏少（未解，待排查）
现象：MC >= 1.21.2 段 fabric R1 landed 约 415–469，同格 neoforge 906–1003（约一半）；1.20.1（1460 vs 1405/1412）与 1.21.1（1094 vs 870/899）三端正常。
证据：`result_{1.21.2,1.21.5,1.21.6,1.21.9,1.21.11}_fabric_parity.json` 对照同版本 neoforge/forge。
排查方向：1.21.2+ 类型化 payload 通道下 fabric CHUNK_S2C 接收/apply 路径；该段 admission ACK 回程实际 RTT（探测窗口是否仍不足）；VD 协商差异。注意与上面慢回程探测同域但不同因——探测已消除 requeue 风暴（stall-fix 系列 PASS），区块数缺口仍在。
