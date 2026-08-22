# handoff — 多维度兼容（multi-dimension-cache）

> 日期：2026-08-22｜状态：执行中（同会话派发）
> 需求与任务：`.omp/workflows/multi-dimension-cache/REQ.md`、`TASKS.md`（唯一真相源，本文件不重复）

## 背景

排查结论：客户端缓存链路（影子端、注入表、hash 缓存、脏表、OVD、bloom）单维度设计；服务端协议已全程携带 dimension 但客户端消费点全部丢弃。进下界/末地 → hash 伪 MISMATCH + 跨维同坐标数据互覆 + OVD 用主世界生成器出错误地形。

用户拍板：一波到位（含 SeedGen 扩展三维度）；第三方自定义维度透传不缓存。

## 排查证据（关键位置）

- `SeedGenLevelCompat.overworldOnlyDimensions` L255-274：影子端只装 Overworld
- `ShadowStorageHashes`：HASHES/FLAGS 键 = 裸 ChunkPos.asLong
- `ShadowSeedServer.regionDir()` L1776 / `buildBloomFilter` L1448：磁盘与 bloom 固定 overworld
- `ShadowLightCompute.processRemoteHashes` L662+：收 dimension 不用；generated/shadowApplyEpochs/requestedMisses/unloadPending/pendingDeltas 全裸键
- `ClientChunkPipeline.pendingContentHashes/pendingSectionHashes` L28/L31：键 = (x,z)
- `OvdLocalGenerator.generateAndApply` L146：恒 server.overworld()
- `PristineRegistry` L68/L95：仅 OVERWORLD_KEY 门控
- 服务端侧（admission key/bloom 查询/包字段）已含维度，不动

## 执行方式

- 主会话只做派发与核验，**不自己实现**（实现走 task 子代理）
- 每个任务派一个子代理，任务描述自包含：目标 + 范围 + 验收，不依赖本文件以外上下文
- 独立任务可并行（task 批量），共享状态/有依赖的按序（T0→T1/T2 并行→T3/T4→T5）
- 子代理开工先写 work/<agent>-TASK.md（含 ETA 预估）；主会话等待期有活干活，无活 hub wait 带 timeoutMs = max(15min, ETA×2)
- 子代理自维护 `.omp/workflows/multi-dimension-cache/work/<agent>-TASK.md`（每步更新）
- 并行任务契约先行：work/CONTRACTS.md 作为 batch context 注入
- 资源隔离纪律：gradle 一律 --no-daemon、禁共享 daemon、只 kill 自己启动的进程、临时文件按 agent 隔离
- 全部完成后主会话核验验收标准（看证据，不轻信自述）

## 约束红线（对每个子代理重复）

1. 不以牺牲功能为代价；不覆盖老版本验证通过的代码语义
2. 主世界行为零回归
3. common 层实现，禁止业务散落新 #if MC_VER
4. 磁盘格式 type 126 + chunkHash 不变；旧单维度目录继续可读（作 overworld 数据）
5. PowerShell 下 gradle 属性写 "-Pmc_ver=1.20.1"（带引号）
