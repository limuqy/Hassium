# Hassium 文档索引

> 结构：`docs/` 顶层为真相源（当前有效）；`docs/handoff/` 为交接文档；`docs/archive/` 为历史/一次性/已退役文档归档。
> 对齐日期：2026-09-16（能力表/配置说明对齐 ConfigSchema；冒烟报告与代码审查快照归档）。

## 顶层真相源（状态：当前）

| 文档 | 主题 | 状态 |
|------|------|------|
| [architecture.md](architecture.md) | 架构总览：直连拓扑、模块架构、客户端数据流（原版对齐交付）、存储格式、配置、命令 | 当前 |
| [chunk-cache.md](chunk-cache.md) | 区块缓存推送（ShadowPull 统一 Compare+Pull）、磁盘 NBT（§11）、导出（§12） | 当前 |
| [client-chunk-light-flow.md](client-chunk-light-flow.md) | 客户端收包 → apply → 光照落地全链路 | 当前 |
| [client-chunk-flow-handover.md](client-chunk-flow-handover.md) | 客户端区块数据流对齐（统一 Compare+Pull / 权威边沿 §9）交接与演进记录 | 进行中 |
| [chunk-load-optimization.md](chunk-load-optimization.md) | 进服/重连加载路径、c/d 速率锚点与优化阶段（**历史锚点**，方法论仍有效） | 当前（历史语境） |
| [version-segments.md](version-segments.md) | 多版本七段适配真相源 | 当前 |
| [mod-compat.md](mod-compat.md) | 多 Mod 兼容边界与配置逃生 | 当前 |
| [runtime-smoke-test.md](runtime-smoke-test.md) | 运行时冒烟：L0–L2 自动分层、PROBE JSON、场景引擎、门禁与会话判定 | 当前 |
| [config-audit.md](config-audit.md) | 配置项审计（52 键 + 退役键族清单；说明列 = ConfigSchema） | 当前 |
| [network-core-followups.md](network-core-followups.md) | 网络核心收尾核销（**已归档**：直连拓扑下仅存档参考） | 归档参考 |
| [curseforge-description.md](curseforge-description.md) | CurseForge 发布描述草稿 | 当前 |

## handoff（状态：交接/历史）

| 文档 | 主题 |
|------|------|
| [handoff/handoff-2026-09-04-vanilla-direct-network.md](handoff/handoff-2026-09-04-vanilla-direct-network.md) | **直连拓扑回归交接**（网络核心/UDP/迁移裁剪、全局包压缩退役——现行拓扑的决策锚点） |
| [handoff/handoff-2026-08-25-chunk-push-batch-refactor.md](handoff/handoff-2026-08-25-chunk-push-batch-refactor.md) | 区块推送批量重构 |
| [handoff/handoff-2026-08-24-smoke-finalize.md](handoff/handoff-2026-08-24-smoke-finalize.md) | 冒烟收尾 |
| 其余 `handoff-2026-08-*.md` | 2026-08 各波次交接（网关/渐进推送/legacy 清理等，历史语境） |

## archive（状态：归档）

| 文档 | 主题 | 状态 |
|------|------|------|
| [archive/RELEASE-1.0.0.md](archive/RELEASE-1.0.0.md) | 1.0.0 发布说明 | 归档 |
| [archive/ai-functional-test.md](archive/ai-functional-test.md) | AI 辅助游戏内功能测试（minecraft-mod-mcp，L3 已退役，dev 未接线） | 归档 |
| [archive/code-review-2026-09-13.md](archive/code-review-2026-09-13.md) | 全库代码审查快照（只读） | 归档 |
| [archive/code-review-deferred.md](archive/code-review-deferred.md) | code-review 延后项清单（2.0.X 分级） | 归档 |
| [archive/classic-matrix-smoke-report-2026-08-28.md](archive/classic-matrix-smoke-report-2026-08-28.md) | classic 矩阵冒烟报告（2026-08-28） | 归档 |
| [archive/classic-matrix-smoke-report-2026-08-29.md](archive/classic-matrix-smoke-report-2026-08-29.md) | classic 矩阵冒烟报告（2026-08-29） | 归档 |
| [archive/classic-matrix-fix-progress-2026-08-29.md](archive/classic-matrix-fix-progress-2026-08-29.md) | classic 矩阵 P0 修复进度 | 归档 |
| [archive/push-counter-smoke-report.md](archive/push-counter-smoke-report.md) | 推送计数冒烟报告 | 归档 |
| [archive/loader-parity-final-report.md](archive/loader-parity-final-report.md) | 加载器对等终报 | 归档 |
| [archive/chunk-pipeline-port-report-2026-08-30.md](archive/chunk-pipeline-port-report-2026-08-30.md) | 区块管线移植报告 | 归档 |
| [archive/smoke-blackchunk-handoff-20260808.md](archive/smoke-blackchunk-handoff-20260808.md) | 冒烟黑块交接（一次性会话记录） | 归档 |
| [archive/findings-kcp-jij-stuck.md](archive/findings-kcp-jij-stuck.md) | kcp JiJ 内嵌卡点排查（已解决） | 归档 |
| [archive/storage-format-unification.md](archive/storage-format-unification.md) | 存储路径整理方案（已完成） | 归档 |
| [archive/server-chunk-surface-shell.md](archive/server-chunk-surface-shell.md) | 服务端空心区块壳层设计提案（未实现，已退役） | 归档 |
| [archive/multi-channel_network_research.md](archive/multi-channel_network_research.md) | 多通道数据面研究（TCP PoC 已退役，被 UDP/KCP 取代；UDP/KCP 本身亦已随直连拓扑退役） | 归档 |
| [archive/stats-analysis.md](archive/stats-analysis.md) | 冒烟测试统计全分析（一次性报告） | 归档 |
| [archive/handoff-2026-08-09-entity-shadow.md](archive/handoff-2026-08-09-entity-shadow.md) | 实体接入影子端交接（已完成） | 归档 |
| [archive/superpowers/](archive/superpowers/) | superpowers 工作流产物：plans/ 12 份计划、specs/ 10 份规格、1 份状态记录（均已完成使命） | 归档 |
| [archive/bandwidth-comparison-zh.svg](archive/bandwidth-comparison-zh.svg) 等图片 | 带宽对比 / zstd-vs-zlib 性能 / 超视渲染截图 / logo（归档保存，无仓库内引用） | 归档 |

