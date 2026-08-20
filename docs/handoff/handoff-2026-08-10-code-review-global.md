# Handoff — 全库代码审查（code-review-global）

> 日期：2026-08-10
> 需求文档：`.omp/workflows/code-review-global/REQ.md` + `TASKS.md`（已确认，勿重做调研）
> 来源：用户指令「使用 reviewer/security-reviewer/cavecrew-reviewer 全局代码审查，覆盖 coding-standards、java-standards」

## 为什么做

Hassium 2.0.0 网络核心重构后全库（common 222 文件 ~40k 行 + 三端 32 文件 ~8k 行）从未系统审查过。本轮全库只读审查，产出分级 findings 报告。

## 已确认决策（勿改）

- 范围：**全库一次审完**，11 片并行（T1-T11，见 TASKS.md），主代码全覆盖，test 抽查低优先
- 交付物：**报告 + 修复非业务逻辑 P0/P1**（用户 2026-08-10 补充）：审查先出报告（T1-T11）；T12 汇总时对 Critical/Major 逐条标注业务/非业务逻辑；T13 修复非业务逻辑 Critical/Major（实现子代理 + 编译验证）；T14 回归核验；T15 三加载器×三版本冒烟回归（fabric×1.20.1/1.21.1/1.21.11 + forge×1.20.1 + neoforge×1.21.1/1.21.11，按 docs/runtime-smoke-test.md）；主会话统一提交。业务逻辑类只报告不修，由用户另行定夺
- Agent 分工：安全敏感域（T1/T2/T4/T5）→ security-reviewer；业务域（T3/T6/T7）→ reviewer；薄壳杂项（T8/T9/T10/T11）→ cavecrew-reviewer
- 标准：coding-standards（审查维度/严重度/证据要求）+ java-standards（语义陷阱清单）
- 约束：只读不写 src、不跑构建、片间文件互斥、报告中文

## 执行方式

- 主会话只做派发与核验，**不自己实现**（审查走 task 子代理）
- 每个任务派一个子代理，任务描述自包含：目标 + 范围 + 验收，不依赖本文件以外上下文
- 11 片独立无依赖 → 一次 task 批量并行（≤32 上限内）
- 子代理开工先报 ETA；主会话 hub wait 带 timeoutMs = max(15min, ETA×2)，上限不超过系统硬上限
- 子代理自维护 `.omp/workflows/code-review-global/work/<agent>-TASK.md`（ETA 超 48min 报备模式下每 45min 更新；其余可不建）
- 契约先行：文件归属互斥（TASKS.md 每片范围）+ FINDINGS.md 统一格式（REQ.md「证据与输出要求」）已作为 batch context 注入
- 资源隔离：全部只读、无构建 → 无端口/daemon 冲突；FINDINGS.md 按 agent 独立文件名不互踩
- git 纪律：子代理不 commit；审查不改代码，收尾核验 git status 前后一致
- 全部完成后主会话核验验收标准（REQ.md 五条，看 FINDINGS 证据 + 抽查行号 + git status），通过才收尾

## 关键路径

1. 派发 11 个 task（security-reviewer ×4、reviewer ×3、cavecrew-reviewer ×4）
2. 等结果送达（带超时），逐个 `todo done`
3. 核验：每片 FINDINGS 覆盖 100%、抽查 ≥30 条行号、git status 无 src 改动
4. 汇总 `.omp/workflows/code-review-global/REPORT.md`（分片统计 + 分级 findings + 高风险域结论）
5. 打回策略：findings 缺证据/格式不合 → 按 workflow-drive 阶段 4b（≤2 次压缩 hub send 打回，≥3 次交接重派）

## 验收标准（REQ.md）

1. 每片 FINDINGS.md 落盘，分配文件覆盖率 100%
2. findings 每条含 文件:行号+问题+修复建议+严重度+规则引用
3. 主会话抽查 ≥30 条行号可复现
4. 无 agent 修改 src 文件（git status 前后对比）
5. REPORT.md 汇总落盘
