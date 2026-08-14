# handoff — 网关 bootstrap（2026-08-12）

需求真相源：`.omp/workflows/gateway-bootstrap/REQ.md`（已确认）+ `TASKS.md`（T0→T5 任务清单）。
背景：1.20.1 实测指标全 0 = autoConnect T9 兜底连 vanilla 端口 25565 → 10s fault → fallback vanilla。根因 = 网关端点必须客户端本地配置（多服务器场景不成立，服务器下发机制在 729d92e 被退役）。
方案：M1 bootstrap 下发（vanilla 通道 gateway_info，含 token）+ autoConnect 全场景探测（配置→gateway_info→25566，无等待窗口）+ M2 端点存储（failover-endpoints.properties format=2）+ M3 失效恢复（仅网关登录，store 端点直连 + 登录桥）。
关键决策：token 下发；网关握手成功后启用（登录期全走 vanilla 壳）；影子端不 gate 于握手；单端点服务器功能完整（M3 纯增量）。

## 执行方式
- 主会话只做派发与核验，**不自己实现**（实现走 task 子代理）
- 每个任务派一个子代理，任务描述自包含：目标 + 范围 + 验收，不依赖本文件以外上下文
- 独立任务可并行（task 批量），共享状态/有依赖的按序（Wave 1 → Wave 2 → Wave 3）
- 子代理开工先写 `.omp/workflows/gateway-bootstrap/work/<agent>-TASK.md`（含 ETA 预估）；主会话等待期：有活干活（结果自动送达），无活 hub wait 带 timeoutMs = max(15min, ETA×2)
- 子代理自维护 `.omp/workflows/gateway-bootstrap/work/<agent>-TASK.md`（每步更新，重启/交接时读取恢复）
- 并行任务契约先行：`.omp/workflows/gateway-bootstrap/work/CONTRACTS.md`（T0 产出；文件归属/载荷格式/共享格式）作为 batch context 注入
- 资源隔离纪律：gradle 一律 `--no-daemon`；独立端口（探测/冒烟用端口主会话统一分配）；只 kill 自己启动的进程；临时文件按 agent 隔离
- 全部完成后主会话核验验收标准（看证据，不轻信自述），通过才收尾
