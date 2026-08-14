# Handoff — 客户端进世界加载速度回归基线 + 冒烟指标达标

> 日期：2026-08-13
> 需求名：client-load-speed
> 工作流：skill://workflow-drive（阶段 3 已拍板：同会话执行）
> 真相源：`.omp/workflows/client-load-speed/REQ.md`（需求/约束/验收）+ `TASKS.md`（任务链/依赖图）
> 项目：D:/project/MC/Hassium（Minecraft 1.20.1-1.21.11 多加载器模组，2.0.0 网络核心）

## 背景一句话

08-13 chunk-chain-first-login 修复链解决「首登虚空」正确性，但引入性能回退：R1 全量区块 8s 超时重发风暴（过期 1593，基线 0-20）、R2 重连 ClassCastException 崩溃（Result FAIL）、光照缓存 0%、R2 缓存命中 0%、OVD loaded 34 vs 基线 1100。目标：回归基线 + 冒烟达标。

## 调研基线（REQ 表，节选）

| 指标 | 基线（改造前） | 08-09 final4（chunk-chain 前，健康） | 08-13 slm_final（当前，坏） |
|---|---|---|---|
| R1 新增/过期 | ~1600 / 0-20 | 1529-1569 / 0-20 | 234 / **1593** |
| 压缩比 | 1.76:1 | 1.5:1 | 1.19:1 |
| R2 缓存命中 | >99% | 0%（异常） | 0% + 增量 16 |
| 光照缓存 | >95% | 0%（异常） | 0% |
| OVD loaded | >1100 | 0 | 34 |
| 崩溃 | 无 | 无 | 0xC0000409（R2 重连） |

## 根因假设（T0 定位，编号稳定）

- P1 全量推送链延迟>8s 超时窗口（服务端限速/客户端排队/seedgen 竞合/超时窗口不匹配）
- P2 光照缓存全 0（hasCachedLight 全 false；剥光协商 A7 lightComputeSupported / 统计路径）
- P3 R2 全命中 0（影子端读回路径/统计口径）
- P4 R2 重连竞态（GatewayS2CRouter 在 listener 未切 Play 时 dispatch → ClassCastException）
- P5 OVD loaded 34（随 P3 查）

## 关键文件

`ClientMetadataHandler`（FULL_REQUEST_TIMEOUT_MS=8000 / PENDING_FULL_REQUESTS / SEED_REF / CHUNK_HASH）、`ServerChunkPushManager`（服务端推送限速/剥光）、`GatewayS2CRouter`（S2C dispatch）、`ShadowSeedServer`（影子端读回）、`NetworkStats`（统计口径）、`ShadowLightCompute`（光照回传）、`ClientChunkPipeline`（背压 CONSUME_BATCH_LIMIT=32）、`NetworkCore`（重连时序）。冒烟：`scripts/runtime-smoke-test.ps1` + `docs/runtime-smoke-test.md`。

## 任务链（与 TASKS.md 一致）

```
T0 根因定位（P1-P5，产出 work/FINDINGS.md + work/CONTRACTS.md）
  → T1 P1 修复 ∥ T2 P2 修复 ∥ T3 P3+P5 修复 ∥ T4 P4 修复
    → T5 冒烟三版本（1.20.1 → 1.21.1 → 1.21.11 fabric）
      → T6 编译矩阵 + 工作区收尾提交
```

## 工作区状态

- 30+ 未提交文件：chunk-chain 修复 + M 系列 review 修复 + gateway-bootstrap M3 新文件，**未 commit** = 调研基线
- stash：bisect-ncore-3 / bisect-full（诊断改动，无结论）
- 08-09 vs 08-13 差异定位：子代理用 git 历史对比（git log 找 final4/slm_final 提交点，无 tag 则按日期+提交信息定位）

## 执行方式

- 主会话只做派发与核验，**不自己实现**（实现走 task 子代理）
- 每个任务派一个子代理，任务描述自包含：目标 + 范围 + 验收，不依赖本文件以外上下文
- 独立任务可并行（task 批量），共享状态/有依赖的按序（T0 → T1-T4 并行 → T5 → T6）
- 子代理开工先写 work/<agent>-TASK.md（含 ETA 预估）；主会话等待期：有活干活（结果自动送达），无活 hub wait 带 timeoutMs = max(15min, ETA×2)
- 子代理自维护 .omp/workflows/client-load-speed/work/<agent>-TASK.md（每步更新，重启/交接时读取恢复）
- 并行任务契约先行：T0 产出 CONTRACTS.md（文件归属/共享接口）作为 T1-T4 batch context 注入
- 资源隔离纪律：gradle 一律 `--no-daemon`、独立端口、禁共享 daemon、只 kill 自己启动的进程、临时文件按 agent 隔离（work/<agent>/）
- 子代理不 commit/push（T6 主会话统一提交）；不跑全量冒烟（T5 主会话收口）
- 全部完成后主会话核验验收标准（看证据，不轻信自述），通过才收尾

## 约束（REQ 节选）

- 修复不得牺牲已验证功能：vanilla fallback、续流/迁移、影子端缓存/OVD/导出、seedgen 本地生成
- 业务逻辑进 common；禁止新散落 `#if MC_VER`；多版本九段编译矩阵
- 指标对比与基线同口径：同一 runtime-smoke-test.ps1、同参数（VD=20/10、DelayMs=10000）、同 stats 字段
- 临时文件收尾清理，REQ/TASKS 保留
