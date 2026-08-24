# Handoff — fabric R1 landed 修复（2026-08-24）

## 需求
排查修复 docs/loader-parity-final-report.md §7 遗留项①：MC>=1.21.2 fabric R1 landed ~325 vs neoforge ~1021。

## 执行方式
- 主会话只做派发与核验，不自己实现（实现走 task 子代理）
- 任务描述自包含：目标 + 范围 + 验收，见 `.omp/workflows/fabric-r1-landed/REQ.md` / `TASKS.md`
- T1+T2+T3 合并派一个子代理（同文件 `ChunkAdmissionController.java`，避免并行冲突）；T4 冒烟由主会话跑
- 子代理自维护 `.omp/workflows/fabric-r1-landed/work/<agent>-TASK.md`

## 调研结论（证据）
- 数据：`result_1.21.2_fabric_leftover.json` req=1178 / landed=325 / decompressed=339；
  neoforge fin2 req=1021 / landed=1021。
- 服务端 STALL-DIAG：fabric avg/max inFlight=34.9/43、expire 149 行、ack unknown 77 行；
  neoforge avg inFlight=2.3、expire 1 行。
- 根因三点见 REQ.md「根因」节。

## 状态
- [x] 调研 + REQ/TASKS 落盘 + 拍板（同会话派发）
- [ ] 实现 T1-T3
- [ ] 冒烟复验 T4
