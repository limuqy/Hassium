# Handoff — 配置项整理（config-restructure）

日期：2026-08-09 · 状态：**预研完成，待新会话执行**
真相源：`.omp/workflows/config-restructure/REQ.md` + `TASKS.md`（本文件是接续索引，不重复 REQ 内容）

## 为什么做

2.0.0 三核心重构后配置键分类脱节：`clientCache.*`（20 键）= 区块核心整域却用 1.x 旧名；
`network.*` 混装客户端网络/SeedGen/压缩/聚合/推送/光照剥离；`network.dataPlane.*` 混装 UDP 数据面与旧 failover 恢复键。
部分键语义迁移（recoveryWindowMs → L1 迁移 faultTimeout）、部分键失去消费（recoveryFreeze / permit 链）。

## 用户已拍板决策（勿再问）

1. **键名重排本次做，不兼容 1.x toml、无迁移**（2.0.0 本就不兼容 1.X）；包名/类名/代码规范留给后续审查，本次不碰
2. **删键**：`network.dataPlane.recoveryFreeze`、`network.dataPlane.controlStallMs`、`network.dataPlane.failoverExpiryMs`、`storage.mode`（固定内部 mirror 字段）
3. **注释 7 处 + 客户端配置 UI 描述（cloth-config，lang `hassium.configuration.*` zh/en）本次修**

### 二轮调研补充决策（2026-08-09，本会话拍板，勿再问）

4. **键前缀定稿**：`chunk.*`（区块核心）/ `net.*`（网络核心）/ `master.*`（主控核心）/ `dataplane.*`（数据面）；`storage.*`/`compat.*`/`debug.*` 保留
5. **UI 重分 4 类（能力词）**：区块缓存（Chunk Cache，11 项）/ 渲染与生成（Rendering & Generation，10 项）/ 网络与连接（Network & Connection，3 项）/ 调试（Debug，9 项）；旧 `category.cache/render` 废弃；网络键归位
6. **UI 描述用户化**：显示名+tooltip 双用户化，内部术语（chunkHash/SeedRef/pristine/renderOnly/ZSTD/heat）仅括号注明；tooltip 含效果+默认值；修默认值错误 3 处（storage.enabled→false、network.metricsEnabled→false、network.maxChunksPerTick→5）
7. **lang 清理**：删无消费残留 key（category.clientNetwork/storageAndGeneral/compression/compatAndDebug、section.hassium.hassium.*×4、*.button×6、纯分组 key×6）
8. **domain-naming.md 同步**：「配置键族保留」表述改为键族已重排（T3 附做）

## 预研关键事实（代码出处已在 REQ「预研依据」，执行前可复验）

- ConfigSchema 75 键（CLIENT 34 / SERVER 41）；HassiumConfig 嵌套 record 即消费层分类
- 死键证据：recoveryFreeze 仅 ClientSmokeTest 打标；FAILOVER_REQUEST 无客户端发送方（DataPlaneClientBundle.java:333 仅日志）；ControlFailoverHandler.java:68-69,208,231 消费 controlStallMs/failoverExpiryMs（删字段后改固定常量）
- 语义迁移：MigrationEngine.java:29,115-120（recoveryWindowMs = faultTimeout 覆盖）；GatewayPlatformWiring.java:28,157 + GatewayPlayerBridge.java:87（controlReachableEndpoints = 网关监听/outbound 端点）
- 注释过时：CACHE_SECTION_DELTA_ENABLED（"no-op" vs GatewayPacketCodec/NetworkCore/DataPlaneClientBundle 活跃消费）；CLIENT_NETWORK_ENABLED（"自定义通道" vs 2.0.0 无）

## 任务清单（详情见 TASKS.md）

| # | 任务 | 依赖 |
|---|---|---|
| T0 | 键映射表定稿（work/key-mapping.md，75 键全映射 + domain 标注） | 无 |
| T1 | ConfigSchema+HassiumConfig 改造（重排/domain 元数据/删键/注释） | T0 |
| T2 | 消费点全量更新（src 全仓 grep 替换） | T0 |
| T3 | 文档 + UI（config-audit/wiki/architecture/README/AGENTS + lang zh/en） | T0 |
| T4 | 编译四模块 + 配置加载 round-trip | T1/T2/T3 |
| T5 | 全量核验（grep 零残留/键表对照/提交清单） | T1-T4 |

## 执行方式

- 主会话只做派发与核验，**不自己实现**（实现走 task 子代理）
- 每个任务派一个子代理，任务描述自包含：目标 + 范围 + 验收，不依赖本文件以外上下文
- 独立任务可并行（task 批量）：T0 先行 → T1+T2 并行（契约 = key-mapping.md，编译互斥由主会话调度）→ T3 → T4 → T5
- 子代理开工先报 `ETA: N 分钟`；主会话 hub wait 带 timeoutMs = min(max(15min, ETA×2), task.maxRuntimeMs)
- 子代理自维护 `.omp/workflows/config-restructure/work/<agent>-TASK.md`（每步更新，重启/交接时读取恢复）
- **报备模式**：任务描述写明"每 ≤45min 主动 yield 写 work/<agent>-TASK.md"，严禁被系统硬砍
- **git 纪律**：并行子代理不 commit，只留工作区改动；全部完成后主会话核验并按逻辑分批提交
- **资源纪律**：`gradle --no-daemon`；同一时间只允许一个构建任务（T1/T2/T4 编译互斥）

## 硬约束（违反即打回）

1. 键名重排无迁移逻辑；默认值/范围/作用域语义不变（仅改名/删键）
2. 包名/类名/代码规范不改（ControlFailoverHandler 字段删改属配置键连带，最小化处理）
3. 全仓旧键名 grep 零残留（历史语境注明可留）；中英双语同步（wiki/README/lang）
4. 编译必须通过（common + fabric + forge + neoforge）；配置加载 round-trip 有证据
5. 术语按 `.omp/workflows/docs-2.0/work/domain-naming.md`（三核心 + 支撑域；卖点语境用能力词「区块缓存」）
6. UI 必须 4 类分组且键归位（网络键不得挂区块缓存/渲染类）；lang 无无消费 key、默认值描述与 schema 一致
7. `.omp/workflows/docs-2.0/work/domain-naming.md` 配置键族表述须同步（不再称"键名不变"）
