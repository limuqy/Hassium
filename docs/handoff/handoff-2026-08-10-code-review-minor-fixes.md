# Handoff — Minor 96 条全量修复 + 统一冒烟（2026-08-10）

> 主会话压缩/中断/换会话时，新上下文读本文件 + `.omp/workflows/code-review-minor-fixes/REQ.md` + `TASKS.md` + `work/CONTRACTS.md` 即可无缝接续。

## 需求一句话

全库代码审查 96 条 Minor（P2/P3）全部修复（用户拍板：协议级 3 条全修、T1-66 移除死逻辑、sensitive 4 项全修、其余全修含性能类），最后统一冒烟：三锚点 6 会话基线 + 影响区段专项，1.20.1/1.21.1 必覆盖。

## 背景（已完成的链条）

- **审查**：11 片 FINDINGS 落盘 `.omp/workflows/code-review-global/work/`；REPORT.md（5 Critical + 33 Major）；REPORT-MINOR.md（96 条 Minor 逐条含文件:行号+建议+规则）
- **Critical/Major 修复**：5 Critical 实锤修复 + 非业务 22 条 + 业务 6 组（A-F）——已全部提交：`14ba382`、`a490d49`、`8a01e55`、`ed3e686`、`ab4fbef`、`4b19312`（A+C）、`561231c`（B+D）、`cd7d9f9`（E+F）——**基线 = cd7d9f9，工作区干净**
- **验证记录**：三锚点×三端编译全过；common 283 测试全过；冒烟 6/6 PASS（T15 结果在 build/smoke-test/results/）

## 用户拍板（2026-08-10，勿重问）

|项|决策|
|---|---|
|协议级 T2-77/T2-72/T4-87|**全修**（双端同批在各任务文件内）|
|T1-66 isIdleWindow|**移除死逻辑**（删 IdleWindowDetector 等；配置键 `master.migrationIdleWindowMs` 保留 legacy）。用户理由：设计已从静止改回退，游戏高延迟本身有回退现象，回退天然防瞬移利用|
|T6-55 storageEnabled|修（默认构造同步 DEFAULT(false)；核验无配置加载前写盘依赖）|
|T7-60/65、T7-62|全修（mixin 描述符 + 光照踢出）|
|其余 ~70 条|全修含性能类，按域切分|
|冒烟|6 会话基线 + 影响区段专项；1.20.1/1.21.1 必覆盖|

## 执行方式

- 主会话只做派发与核验，**不自己实现**（实现走 task 子代理）
- 每个任务派一个子代理，任务描述自包含：目标 + 范围 + 验收，不依赖本文件以外上下文（条目详情指向 `.omp/workflows/code-review-global/REPORT-MINOR.md` 对应段）
- 独立任务并行（task 批量）：M1-M11 共 11 个，文件归属严格互斥（CONTRACTS.md 矩阵）
- 子代理开工先报 ETA；主会话 hub wait 带 timeoutMs = max(15min, ETA×2)；M9/M10/M11 每 ≤48min 主动 yield 报备
- 子代理自维护 `.omp/workflows/code-review-minor-fixes/work/<agent>-TASK.md`（每步更新）
- 并行契约：`work/CONTRACTS.md` 作为 batch context 注入全部任务
- 资源隔离：全部任务禁跑 gradle（编译/测试主会话 V1/V2 统一串行）；不 commit
- 全部完成后主会话核验：V1 编译（三锚点×受影响端）→ V2 测试（common 283+）→ V3 冒烟（6 会话 + 影响区段专项）→ V4 提交（按域分批）+ REPORT-MINOR.md 状态更新
- 冒烟基础设施（已 retain）：`scripts/runtime-smoke-test.ps1` 单会话直跑可靠（~100s/会话）；forge/neoforge 配置只认字符串列表 `["127.0.0.1,25567,100"]`；PowerShell Set-Content UTF8 带 BOM 崩 NightConfig → python utf-8-sig 读 + utf-8 写

## 关键文件

- 真相源：`.omp/workflows/code-review-global/REPORT-MINOR.md`（96 条）
- 需求：`.omp/workflows/code-review-minor-fixes/REQ.md`
- 任务：`.omp/workflows/code-review-minor-fixes/TASKS.md`
- 契约：`.omp/workflows/code-review-minor-fixes/work/CONTRACTS.md`
- 基线：`cd7d9f9`

## 风险与注意

- M6/M5 若需动 ConfigSchema/HassiumConfig（T6-55/T5-92）→ 禁私自改，走契约变更流程上报主会话（配置键删除影响用户兼容，倾向保留 legacy + deprecated 注释）
- M11 T11-14 退役 common NetworkManager.sendCompressedPayload → grep 三端确认无调用再删
- M7 mixin 描述符改动（T7-60/65）→ 1.21.1+ 双 disconnect 重载场景验证清理幂等性不回归
- M4 T4-87 KCP 鉴权 → 不得破坏 D-M1 per-player bindToken；conv 兼容
- 测试适配：各任务发现测试需改 → 记录 TASK.md，主会话 V2 前统一（或任务内改本片测试文件）
