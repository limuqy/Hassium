# Hassium 文档索引

> 结构：`docs/` 顶层为真相源（当前有效）；`docs/handoff/` 为交接文档；`docs/archive/` 为历史/一次性/已退役文档归档。
> 重组日期：2026-08-09（docs-2.0 工作流 T1）。功能域术语体系见 `.omp/workflows/docs-2.0/work/domain-naming.md`。

## 顶层真相源（状态：当前）

| 文档 | 主题 | 状态 |
|------|------|------|
| [architecture.md](architecture.md) | 架构总览：模块架构、存储格式、配置、命令、卖点特性 | 当前 |
| [chunk-cache.md](chunk-cache.md) | 区块缓存推送、chunkHash、超视渲染（§10）、磁盘 NBT（§11）、导出（§12） | 当前 |
| [client-chunk-light-flow.md](client-chunk-light-flow.md) | 客户端收包 → apply → 光照落地全链路 | 当前 |
| [version-segments.md](version-segments.md) | 多版本九段适配真相源 | 当前 |
| [mod-compat.md](mod-compat.md) | 多 Mod 兼容边界与配置逃生 | 当前 |
| [runtime-smoke-test.md](runtime-smoke-test.md) | 多版本运行时自检与网关双主控迁移冒烟 | 当前 |
| [config-audit.md](config-audit.md) | 配置项审计与清理记录 | 当前 |
| [network-core-followups.md](network-core-followups.md) | 网络核心未达项交接清单（后续波） | 当前 |
| [curseforge-description.md](curseforge-description.md) | CurseForge 发布描述草稿 | 当前 |

## handoff（状态：当前）

| 文档 | 主题 | 状态 |
|------|------|------|
| [handoff/handoff-2026-08-09-docs-2.0.md](handoff/handoff-2026-08-09-docs-2.0.md) | docs-2.0 工作流交接（事实基线、术语、T3-T9 任务） | 当前 |
| [handoff/handoff-2026-08-09-network-core.md](handoff/handoff-2026-08-09-network-core.md) | 网络核心 2.0.0 决策锚点交接 | 当前 |

## archive（状态：归档）

| 文档 | 主题 | 状态 |
|------|------|------|
| [archive/RELEASE-1.0.0.md](archive/RELEASE-1.0.0.md) | 1.0.0 发布说明 | 归档 |
| [archive/smoke-blackchunk-handoff-20260808.md](archive/smoke-blackchunk-handoff-20260808.md) | 冒烟黑块交接（一次性会话记录） | 归档 |
| [archive/findings-kcp-jij-stuck.md](archive/findings-kcp-jij-stuck.md) | kcp JiJ 内嵌卡点排查（已解决） | 归档 |
| [archive/storage-format-unification.md](archive/storage-format-unification.md) | 存储路径整理方案（已完成） | 归档 |
| [archive/server-chunk-surface-shell.md](archive/server-chunk-surface-shell.md) | 服务端空心区块壳层设计提案（未实现，已退役） | 归档 |
| [archive/multi-channel_network_research.md](archive/multi-channel_network_research.md) | 多通道数据面研究（TCP PoC 已退役，被 UDP/KCP 取代） | 归档 |
| [archive/stats-analysis.md](archive/stats-analysis.md) | 冒烟测试统计全分析（一次性报告） | 归档 |
| [archive/handoff-2026-08-09-entity-shadow.md](archive/handoff-2026-08-09-entity-shadow.md) | 实体接入影子端交接（已完成） | 归档 |
| [archive/superpowers/](archive/superpowers/) | superpowers 工作流产物：plans/ 12 份计划、specs/ 10 份规格、1 份状态记录（均已完成使命） | 归档 |
| [archive/bandwidth-comparison-zh.svg](archive/bandwidth-comparison-zh.svg) 等图片 | 带宽对比 / zstd-vs-zlib 性能 / 超视渲染截图 / logo（归档保存，无仓库内引用） | 归档 |

> 注：`ovd.md` / `disk-nbt-cache.md` 从未独立存在；超视渲染与磁盘 NBT 主题分别并入 [chunk-cache.md](chunk-cache.md) §10 / §11。2026-08-09 前指向这两个文件及已移动文件的链接已全部改链（见 `.omp/workflows/docs-2.0/work/T1-TASK.md`）。
