# Handoff — loader parity 指标回归修复

- 日期：2026-08-25
- 需求与验收：`.omp/workflows/loader-parity-regression/REQ.md`
- 任务清单：`.omp/workflows/loader-parity-regression/TASKS.md`

## 当前证据

- `1.20.1_fabric_I_pushrefactor`：串行运行，R1 PASS；R2 FAIL，`cacheHitFullChunkCount=0`，但 `cacheDeltaCount=1628`、`ovdLoaded=632`。
- `1.21.1_fabric_I_pushrefactor`：串行运行 PASS；R1 landed=179，未达到用户确认的 `R1 clientLandedChunkCount > 1000` 全 1.21 格目标。
- 早先并行冒烟产生的端口抢占结果已作废，不能作为缺陷证据。

## 执行方式

- 同会话、主会话直接实现与核验；用户明确禁止使用子代理。
- 冒烟严格串行，一个 `runtime-smoke-test.ps1` 完整退出后才可启动下一个；不抢 25565/25566。
- 先定位并修复 1.20.1 full-hit 回归，再处理 1.21 R1 landed 吞吐；每次修复先执行相关 `common:test` 和定向 smoke。
- 完成后运行 33 个有效 classic Phase I 格；Forge 1.21.2/1.21.11 仅核对预期 SKIP。
- 不修改 smoke 阈值掩盖指标不足；指标源为 `build/smoke-test/probe/<SessionId>/round1.json` 与 round2.json。
