# Handoff — 冒烟覆盖重构（2026-08-23）

## 需求与任务
- REQ: `.omp/workflows/smoke-coverage-redesign/REQ.md`
- TASKS: `.omp/workflows/smoke-coverage-redesign/TASKS.md`
- 决策已拍板：P1=dimension+seedgen；锚点=1.20.1 fabric/neoforge + 1.21.1 neoforge + 1.21.11 neoforge；minecraft-mod-mcp 仅人工辅助；过时测试直接删除

## 执行方式
- 主会话只做派发与核验，**不自己实现**（实现走 task 子代理）
- 每个任务派一个子代理，任务描述自包含：目标 + 范围 + 验收，不依赖本文件以外上下文
- 依赖序：T1→T2→T3 串行；T4 与 T5 并行；T6/T7/T8 在 T4 后并行；T9 全程可并行；T10 收尾
- 子代理开工先写 `.omp/workflows/smoke-coverage-redesign/work/<agent>-TASK.md`（含 ETA）；主会话等待期 hub wait 带 timeoutMs = max(15min, ETA×2)
- 重负载纪律：runServer/runClient 均 `--no-daemon`；同时只允许一套冒烟会话占端口（fabric 25565 / neoforge 25566 起）；只 kill 自己启动的进程
- 并行子代理不各自 commit；主会话核验后统一提交

## 关键事实（调研结论，子代理可直接引用）
- classic 门禁现状：两轮 stats OK + R2 全命中>0 + GATEWAY ACTIVE/c2s>0；OVD/LIGHT-SEG/delta 不 gate
- `ClientSmokeTest.java:74,241` dataplane 死分支；`ServerSmokeTest.java:89-109` dataplane/all 告警遗留
- 脚本退役残留：`runtime-smoke-test.ps1` 的 `-Phase UdpFailover`/`-NginxExePath`/`-InjectTcpClose`/`-DryRun`/`-SeamlessMode`、`scripts/smoke/UdpFailoverSmoke.psm1`
- PROBE 输出目标：`build/smoke-test/probe/<session>_roundN.json`
- 已知限制：1.21.5/1.21.7–11 fabric ROUND2 大概率 readerIndex 崩溃（锚点避开 fabric 新段）
- PowerShell 传 `-Pmc_ver=x.y.z` 必须带引号
