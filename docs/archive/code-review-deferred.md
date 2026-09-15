# code-review 延后项清单（2.0.X 分级）

> 日期：2026-09-14
> 依据：`docs/archive/code-review-2026-09-13.md`（2026-09-14 核查修订）§八 处理优先级表 + 四路核查结论；`pre-2.0.0-must-fix` REQ/TASKS 拍板
> 目的：2.0.X 整个大版本冻结兼容面，破坏性改造必须全部在 2.0.0 上线前完成。本文记录**未在 2.0.0 修复、且被延后**的审查条目，并标注每个条目在 2.0.X 窗口内是否可做，防止上线后被误当「随时可做」。

## 分级规则

- **「2.0.X 内不可做」** = 触碰冻结兼容面（通道 ID 字符串 / toml 键集 / 存档与缓存格式 / `hassium_cache/<serverId>/world` / 聚合帧与索引表结构）或与 2.0.X 稳定目标冲突。
- **「2.0.X 内可做」** = 纯内部行为/结构修复，零冻结面触碰，风险可控。

冻结兼容面清单（2.0.0 起冻结，同 `docs/version-segments.md` 与 REQ 拍板）：

| 冻结面 | 内容 |
|---|---|
| 握手流程与能力位 | 登录期/配置期握手顺序、`LoginCaps` 位定义、`play_init_s2c` 激活序列 |
| 通道 ID 字符串 | `hassium:*` 通道名逐字节冻结（`HassiumChannels` 为真相源） |
| 配置键集 | toml 键集（`ConfigSchema` 为真相源），2.0.X 全小版本不可增减 |
| 存档与缓存格式 | type 126、chunkHash 元数据、`hassium_cache/<serverId>/world` 布局、`heat.idx` |
| 聚合帧与索引表结构 | 聚合包帧格式、dictionary/index 索引表结构（含 1.20.1 段 `hassium:chunk_payload_s2c` 等索引内容） |
| 字典存储位置 | 服务端 `<runDir>/config/hassium/hassium_aggregation_dict.bin` |

## 2.0.0 已修复核销

| 审查条目 | REQ | 任务 | commit | 状态 |
|---|---|---|---|---|
| §1.2 + §2.4 聚合 PENDING 缓冲无上限 / int 溢出 | 1 | T1 | `cd50114` | ✅ 已修复 |
| §1.3 PullResponseDecodeQueue 任意断连全局清空 | 2 | T2 | `c493a94` | ✅ 已修复 |
| §2.8 AggregationDecodeQueue enqueue/discard 竞态 | 3 | T3 | `dcc4460` | ✅ 已修复 |
| §2.7 shouldReuseParkedInstance 保守复用错配 | 4 | T4 | `5160397` | ✅ 已修复 |
| §2.2 Fabric ShadowPullHandler 非单例 | 5 | T5 | `796f812` | ✅ 已修复 |
| §2.1 resetStorage 漏清 serverSeedAvailable | 8a | T8a | `2e4ef24` | ✅ 已修复 |
| §2.5 ZSTD 字典句柄热替换不 close | 8b | T8b | `ed98e4b` | ✅ 已修复 |
| §3.3 字典路径未锚定 gameDirectory | 6 | T6 | `d709e22` | ✅ 已修复 |
| §6.4 通道 ID 常量散落 / 内联字面量 | 7 | T7 | `11ea847` + `cbfac68` | ✅ 已修复 |

> T6 附注：TASKS 原稿写 yarn 名 `getRunDirectory()`；仓库用 `loom.officialMojangMappings()`（mojmap），方法名为 `getServerDirectory()`（1.20.1 返回 `File`，1.21.1+ 返回 `Path`），实现已按 mojmap 名落地，File→Path 悬崖仍在 `MC_1_21_1` 边界（`PlayerCompat.getServerRunDirectory`）。

## 延后项表

### P1-纯删（§四 过度防御 / §五 重复实现死重）

| 编号 | 来源 | 位置 | 建议 | 分级 | 依据 |
|---|---|---|---|---|---|
| 8 | §4.1.1 | 永假 fallback 钩子 | 删除 | ✅ 2.0.X 内可做 | 纯删除，零冻结面触碰 |
| 9 | §5.2.3 | `NetworkCapability` 整类 | 删除 | ✅ 可做 | 纯删除 |
| 10 | §5.2.5 | bulk metrics | 删除 | ✅ 可做 | 纯删除 |
| 11 | §5.2.7 | `ensureChunkCacheRadius` | 删除 | ✅ 可做 | 纯删除 |
| 12 | §4.1.2-4.1.4 | SetCopy / 读锁 / resolveDim | 删除 | ✅ 可做 | 纯删除 |
| 13 | §3.6 | `HassiumAggregationManager.takeOver` 未用 `buf` | 删除 | ✅ 可做 | 纯删除；与 T1 同文件，留待独立批次避免混批 |
| 14 | §3.7 | 超时调度 `cancel` | 删除 | ✅ 可做 | 纯删除 |

### P2-防御（§一/§二/§三 行为修复）

| 编号 | 来源 | 位置 | 建议 | 分级 | 依据 |
|---|---|---|---|---|---|
| 15 | §1.1 | `ServerHandshakeActivation.drainPending` connection 未挂载时不回队 | 回队 + 最大重试/超时兜底 | ✅ 可做 | 行为修复；现行不可达（join 时序保证），需冒烟覆盖 join 时序后实施 |
| 16 | §1.4 | 并发容器（PENDING 玩家集等） | 换并发容器 | ✅ 可做 | 行为修复 |
| 17 | §2.3 | `IndexSyncManager` 原子化 | 原子化 | ✅ 可做 | 行为修复 |
| 18 | §3.1 | 锁对统一 | 统一锁对 | ✅ 可做 | 行为修复 |
| 19 | §3.2 | 孤儿 UUID | 清理 | ✅ 可做 | 行为修复 |
| 21 | §3.5 | `ClientMetadataHandler.forwardBlockUpdate` 主线程阻塞（上界 ~42s） | 移出主线程/限流 | ✅ 可做 | 行为修复，风险较高 |

### P2-吞没（§4.2 补日志 / 收窄 catch）

| 编号 | 来源 | 位置 | 建议 | 分级 | 依据 |
|---|---|---|---|---|---|
| 22 | §4.2.1-4.2.6 | 空/`ignored` catch | 加 warn | ✅ 可做 | 纯日志，零冻结面 |
| 23 | §4.2.3 | accessor 失败 | fail-fast | ✅ 可做 | 行为修复 |
| 24 | §4.2.4 | 票 add | 加 warn | ✅ 可做 | 纯日志 |

### P2-下沉（§五 三端克隆收口，需 loader 冒烟）

| 编号 | 来源 | 位置 | 建议 | 分级 | 依据 |
|---|---|---|---|---|---|
| 25 | §5.1.1, §5.1.5 | 命令树 | 下沉 common | ✅ 可做 | 内部移动；需 loader 冒烟，2.0.X 稳定期优先不动 |
| 26 | §5.1.2 | ConfigBackend 模板 | 合并 | ✅ 可做 | 同上 |
| 27 | §5.1.3-4 | SPI send 样板 | 下沉 | ✅ 可做 | 同上 |
| 28 | §5.2.1 | Forge Wrapper 合并 | 合并 | ✅ 可做 | 同上 |
| 29 | §5.2.2 | chunkHash 助手 | 下沉 | ✅ 可做 | 同上 |
| 30 | §5.2.8 | Fabric receiver | 走 `PayloadHandlers` | ✅ 可做 | 同上 |

### P3-重构（高风险慎动，配套冒烟 seedgen/dimension）

| 编号 | 来源 | 位置 | 建议 | 分级 | 依据 |
|---|---|---|---|---|---|
| 31 | §5.2.9, §6.1 | 配置去透传 / 统一别名 | 重构 | ✅ 可做 | 重构，2.0.X 稳定期优先不动 |
| 32 | §6.3 | SPI 去 default 垫片 | 重构 | ✅ 可做 | 同上 |
| 33 | §6.5 | 压缩 API 收窄 | 重构 | ✅ 可做 | 同上 |
| 34 | §6.6 | **IndexSync 1.20.1 化** | 重构 | ❌ **2.0.X 内不可做** | 触碰 1.20.1 索引表/聚合帧结构 = 冻结面；见下方决策记录 |
| 35 | §六 | 连接状态单表（`HassiumConnectionRegistry` 三态 enum） | 重构 | ✅ 可做 | 重构，稳定期优先不动 |
| 36 | §6.10 | `ClientChunkPipeline` 拆分 | 重构 | ✅ 可做 | 同上 |

## 决策记录

1. **IndexSync 保留现状**：2.0.X 全系保留现行运行时索引同步方案（dictionary/index 索引表运行时同步已核实内容级兼容）。§6.6「IndexSync 1.20.1 化」**不可做**（触碰 1.20.1 索引表/聚合帧结构 = 冻结面）。REQ 拍板。
2. **P5 现状不动**：`ShadowTicketDriver` 服务端声明驱动选柱（`P5_TAKEOVER`）保持 `false`、一行不执行，仅保留代码（`docs/handoff/handoff-2026-09-13-authority-edge-p5-verdict.md` 定性：选柱/装载几何由影子端自绘，接管臂保留不启用）。REQ 拍板非目标。
3. **T9 文档本身**：`docs/archive/code-review-2026-09-13.md` 为只读审查快照，不改原文；本清单是延后项的活文档。
