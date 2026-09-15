# handoff — 多维度兼容（multi-dimension-cache）

> 日期：2026-08-22｜状态：三维度已落地；自定义维度动态兼容 2026-08-23 实现
> 需求与任务：`.omp/workflows/multi-dimension-cache/REQ.md`、`TASKS.md`（唯一真相源，本文件不重复）

## 背景

排查结论：客户端缓存链路（影子端、注入表、hash 缓存、脏表、OVD、bloom）单维度设计；服务端协议已全程携带 dimension 但客户端消费点全部丢弃。进下界/末地 → hash 伪 MISMATCH + 跨维同坐标数据互覆 + OVD 用主世界生成器出错误地形。

用户拍板：一波到位（含 SeedGen 扩展三维度）；第三方自定义维度**先透传**。2026-08-23 二次拍板升级为**动态兼容**（见下）。

## 自定义维度动态兼容（2026-08-23）

**前提（社区共识，对照 TF / AoA / Underworld 源码）**：新增维度 mod 客户端必须安装；只改地形噪声、不新增维度的 mod（史诗地形类）服务端即可玩。

**方案（简化 Tier2，不发全量 LevelStem NBT）**：

1. `play_init` 追加服务端维度 id 列表（append-only，旧端不读）
2. 客户端用**本地 worldgen registry** resolve 每个维度的 `LevelStem`（装了 TF/AoA 即有）
3. resolve 成功 → 装配进影子 `WorldDimensions` → 缓存 + SeedGen + 落盘 `dimensions/<ns>/<path>/`
4. resolve 失败 → 该维继续原版透传
5. 主世界 stem NBT 解码失败（客户端缺生成器 codec）→ **硬关 SeedGen**（仅缓存）

**红线写进文档**：

- 新增维度：双端必须装同一 mod
- 只改地形/噪声：客户端未装同款生成器时不要开 `chunk.seedGenEnabled`

**关键改动**：

| 文件 | 变更 |
|------|------|
| `LoginHandshake.PlayInitPayload` | append `dimensionIds`（上限 256） |
| `SeedGenTail.collectDimensionIds` | 服务端 `getAllLevels()` 收集 |
| `ClientChunkPipeline` | 存清单；`disableSeedGen` 硬关 |
| `SeedGenLevelCompat` | 本地 resolve + 装配；decode 失败关 SeedGen；装配后 `markCacheable` |
| `DimensionKey` | 动态 `CACHEABLE` 集合（默认三维 + 装配成功自定义维） |
| `ShadowCacheEviction` | 扫描 `server.storageDimensions()` |

**验收**：`common:test`（DimensionKey / LoginHandshake）绿；1.20.1 fabric/forge + 1.21.11 neoforge 编译绿。

**实跑锚点（缓存优先，SeedGen 关）**：

- `1.21.1_neoforge_I_aoa3g` **PASS**：AoA3 十维 assemble；R1 `aoa3:abyss` 网络落地并落盘
  `dimensions/aoa3/abyss/region/`；R2 park 复用后 **cache 全命中 453、下行 0 B、光照 reuse 100%**。
- 补丁链：维度清单重建 → `datapackDimensions` lookup → NeoForge `ResourcePackLoader` 挂载 →
  unpark 恢复 cacheable → WorldLoader CME 重试。
- 场景文件：`common/src/main/resources/hassium/smoke/scenario/aoa3.scenario`；
  外部 jar 放 `neoforge/run/{client,server}/mods/`（文档 §外部 Mod 手动冒烟同款流程）。

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
