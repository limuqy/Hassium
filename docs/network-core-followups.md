# Network Core 未达项交接清单（后续波）

> 来源：T8/T9/T10/T11/T12 任务交接记录。网络核心 2.0.0 架构主体已落地并提交
> （`eb05ba5..11f3470`），以下为后续波待办，按类别排序，编号稳定可引用。
> 每项含：问题 → 现状 → 建议处理。

## A. 运行时正确性（需真实双端验证）

| # | 项 | 现状 | 建议 |
|---|---|---|---|
| A1 | 壳 keep-alive 饥饿 | 网关 ACTIVE 期壳连接收不到应答可能被服务端踢（keep-alive 应答镜像未做） | 冒烟短窗口不触发；真实双端长时间运行验证，做 keep-alive 应答镜像 |
| A2 | 主控 CONFIG_S2C 丢弃 | registry 数据刻意丢弃（防双 registry 处理），双路径正确性未验证 | 真实双端验证后决定：补 CONFIG_S2C 转发或确认丢弃正确 |
| A3 | custom-payload 直发 | `ClientMetadataHandler` 直发点不经 `routeC2S`，未知包 getId 错位由 try/catch 兜底 false 原版放行 | 收口直发点进 routeC2S |
| A4 | pending-attach TTL | 10s 超时仅告警移除，靠重连/续流兜底（vanilla 未物化属异常场景） | 保持告警语义，真实环境观察触发率 |
| A5 | 1.20.2–1.20.4 非锚点段 | 仅保编译，无单测 | 发布前补该段冒烟 |
| A6 | 续流玩家占位名 + 空数据 | 续流会话用占位名、玩家数据未加载 | 接 R2 磁盘加载链路（`EntitySnapshotCompat.loadFromTag` 等） |
| A7 | lightComputeSupported 未进帧握手 | 主控保守不剥光（Hassium 引擎协商降级） | 评估握手帧补字段，恢复剥光 |
| A8 | ZSTD 对称时序 | 平台 setZstd 与客户端配置同源 + CompressionReady ACK 已实现，未真实双端验证 | 真实双端压测 |

## B. 迁移引擎（L1 已交付，以下为运维面）

| # | 项 | 现状 | 建议 |
|---|---|---|---|
| B1 | 端点通告 | targets 目前编程注入 | 走 CONFIG 帧通告端点池 |
| B2 | 参数全链接线 | MigrationPolicy 默认值 + setter 齐备，未接配置 | 接 ConfigSchema（策略/心跳/空闲参数，故障超时复用 recoveryWindowMs） |
| B3 | 预热会话 TTL | B 侧预热会话无清理 | 加 TTL 清理 |
| B4 | 迁移命令注册 | 无平台命令入口 | 注册 `/hassium migrate` 类命令（演练触发） |
| B5 | UDP 数据面迁移 | udpTail 固定 `udpSupported=false`（帧连接即控制连接，beginControlConnection 未走） | UDP 会话迁移归后续波，先文档化决策 |

## C. 登录/会话语义

| # | 项 | 现状 | 建议 |
|---|---|---|---|
| C1 | 1.20.1 双 ServerPlayer | 登录桥物化 + vanilla TCP 登录物化同 UUID 潜在冲突；T10 定口径"会话为准"，未真实双端验证 | 真实双端联调时验证附着逻辑 |
| C2 | config 中继 1.20.2–1.20.4 | 该段 CONFIG 帧路径仅编译级保证 | 随 A5 一起验证 |

## D. ViaFabric 兼容（桥已落地，以下为深水区）

| # | 项 | 现状 | 建议 |
|---|---|---|---|
| D1 | 主控编码侧跨版本 | 需要服务端版本编解码器或原始 payload 字节路径（`translateBytes` 已暴露） | ViaVersion 5.x 实测后定 |
| D2 | C2S 方向转换 | via-encoder 同构未实现（仅 S2C decode 桥） | 按 D1 结果补 |
| D3 | ZSTD×ViaFabric 顺序 | 压缩与协议转换先后未定 | 与 A8 一起验证 |

## E. 验证缺口（阻塞项，优先级最高）

| # | 项 | 现状 | 建议 |
|---|---|---|---|
| E1 | 真实双端联调 | 全部冒烟为单测/桩测（GatewaySmokeTest 真实 TCP 仅本机环回） | 真客户端 + 真主控跑 `docs/runtime-smoke-test.md`；双主控迁移演练 |
| E2 | ViaFabric 运行时冒烟 | 未实测：装 → `ViaFabric detected + bridge installed` 日志；不装 → 无桥日志 | 随 E1 一起 |

---

关联文档：`.omp/workflows/network-core/REQ.md`、`TASKS.md`、`work/T8-TASK.md`、
`work/T9-TASK.md`、`work/T10-TASK.md`、`work/T11-TASK.md`、`work/T12-TASK.md`、
`docs/handoff/handoff-2026-08-09-network-core.md`。
