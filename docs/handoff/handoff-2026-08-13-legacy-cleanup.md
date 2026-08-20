# Handoff — 处理 client-load-speed 遗留项（legacy-cleanup）

> 日期：2026-08-13
> 需求文档：`.omp/workflows/legacy-cleanup/REQ.md` + `TASKS.md`
> 基线：HEAD（9e775c0 / 4688175 / babdc5f / 1424583），工作区干净

## 需求摘要

1. **L1（用户拍板 A 深修）**：1.21.11 seedgen 本地 worldgen 与服务端不一致（R2 命中 17.8%、R1 全过期 49、R2 过期 241；服务端日志 90+ 条 `[SECTION_DELTA] Fallback to full 9/9 sections changed`）→ 定位根因（datapack/registry/feature flag）修复，命中率恢复。
2. **L2（用户拍板 A）**：1.21.1 R1 gatewayS2c=0 = onLogin()（NetworkCore.java:209）resetSessionCounters() 抹掉 pre-login 114 条 chunk hash 计数 → bootstrap 分支不 reset。
3. 验证：编译矩阵 + 三版本冒烟回归 + 提交。

## 执行方式

- 主会话只做派发与核验，**不自己实现**（实现走 task 子代理）
- 每个任务派一个子代理，任务描述自包含：目标 + 范围 + 验收，不依赖本文件以外上下文
- 独立任务可并行（task 批量），共享状态/有依赖的按序
- 子代理开工先写 work/<agent>-TASK.md（含 ETA 预估）；主会话等待期：有活干活（结果自动送达），无活 hub wait 带 timeoutMs = max(15min, ETA×2)
- 子代理自维护 .omp/workflows/legacy-cleanup/work/<agent>-TASK.md（每步更新，重启/交接时读取恢复）
- 并行任务契约先行：CONTRACTS.md（文件归属/端口/共享格式）作为 batch context 注入
- 资源隔离纪律：独立端口、禁共享 daemon（gradle --no-daemon）、只 kill 自己启动的进程、临时文件按 agent 隔离
- 全部完成后主会话核验验收标准（看证据，不轻信自述），通过才收尾

## 关键证据（T8 轮日志，build/smoke-test/logs/ 已 gitignore）

- L1：`server_1.21.11_fabric_clfix_T8_I.log`（Fallback 风暴 L148-249、R2 SEED_REF L478-558）；对照 `server_1.20.1_fabric_clfix_T8_I.log`（无风暴）
- L2：`client_1.21.1_fabric_clfix_T8_I.log`（ACTIVE 14:18:42 → onLogin 14:18:43 → dump s2c=0；R2 s2c=469）

## 任务状态

- [ ] T1 L1 深修（子代理 T1Worldgen）
- [ ] T2 L2 计数修复（子代理 T2S2cCount）
- [ ] T3 核验+编译+冒烟+提交（主会话）
