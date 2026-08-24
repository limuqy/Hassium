# handoff — smoke-finalize（loader-parity 收尾，2026-08-24）

需求：`.omp/workflows/smoke-finalize/REQ.md` / `TASKS.md`；契约：`work/CONTRACTS.md`
拍板：同会话执行（用户已离开电脑，按 REQ 既定方向自主推进，不再询问）

## 执行方式
- 主会话只做派发与核验，**不自己实现**（实现走 task 子代理）
- 每个任务派一个子代理，任务描述自包含：目标 + 范围 + 验收，不依赖本文件以外上下文
- 独立任务可并行（task 批量），共享状态/有依赖的按序
- 子代理开工先写 work/<agent>-TASK.md（含 ETA 预估）；主会话等待期：有活干活（结果自动送达），无活 hub wait 带 timeoutMs = max(15min, ETA×2)
- 子代理自维护 .omp/workflows/smoke-finalize/work/<agent>-TASK.md（每步更新，重启/交接时读取恢复）
- 并行任务契约先行：CONTRACTS.md 作为 batch context 注入
- 资源隔离纪律：子代理默认不跑 Gradle 编译（主会话统一收口）；25565 端口 T4/T5 错峰；只 kill 自己启动的进程；临时文件按 agent 隔离；**主会话至少每 10 分钟检查一次子代理状态**
- 全部完成后主会话核验验收标准（看证据，不轻信自述），通过才收尾

## 波次

### Wave 1（并行 3 片，无端口需求）
| Agent | 任务 | 文件归属 |
|---|---|---|
| ConfigAudit | T1 配置统一审计 | `common/**/ConfigSchema.java` 等 |
| FabricR1 | T2 fabric R1 排查修复 | `fabric/**`、ChunkAdmissionController |
| LightEaves | T3 屋檐光照机制审查 | 影子端光照链路 |

### Wave 2（编译收口后）
- 主会话统一跑受影响模块编译 → T4 SmokeMatrix 全矩阵冒烟（并发 ≤2，优先 MC≥1.21.2 fabric 格）→ T5 LightProbe 专项复跑（与 T4 错峰）

### Wave 3
- T6 Analysis 数据分析与报告 `docs/loader-parity-final-report.md`

## 关键背景
- loader-parity 主体已提交（041d755 等 5 commit），工作区干净
- fabric R1 遗留详情见 `docs/handoff/handoff-2026-08-23-loader-parity.md`「遗留项」
- 冒烟固定 level-seed=42；上轮 parity 结果在 `build/smoke-test/results/result_*_parity.json`
