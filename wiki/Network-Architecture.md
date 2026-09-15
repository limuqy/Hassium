# 网络架构（直连拓扑）

---

> **English**: [Network-Architecture-en](Network-Architecture-en) · 中文

> **2.0.0 起回归直连拓扑**：历史上的「进程内网关（网络核心）/ 无感主控迁移 / L1 负载均衡 / UDP 数据面 / 客户端 failover」均已裁剪退役（决策见仓库 [`docs/handoff/handoff-2026-09-04-vanilla-direct-network.md`](https://github.com/limuqy/Hassium/blob/master/docs/handoff/handoff-2026-09-04-vanilla-direct-network.md)）。

## 用户能感知的网络优化

| 能力 | 效果 |
| --- | --- |
| 平滑推送 | 每玩家每 tick 区块下发限速 + encode/压缩后台化；进服不卡主线程 |
| 实体优化 | 距离分档 / 热点密度 / 包量反压 / UUID 错峰；原版客户端也能吃到（只改下发节拍） |
| 通道压缩 | 聚合包内部字典 ZSTD + 区块推送自有压缩；不触碰原版压缩层 |
| 区块缓存复用 | 重连按 chunkHash 复用本地缓存：未变更 UNCHANGED、变更 DELTA、缺失才全量 |

详见 [Features](Features)。

## 现行拓扑：唯一 vanilla TCP

客户端与服务端之间**只有一条 vanilla TCP 连接**（游戏端口）。Play 期所有自定义 payload（区块/实体/业务）都走这条通道；原版压缩层不触碰。

公网部署只需放行游戏端口；无网关 / UDP 端口。

> 双端都装时，登录/配置阶段会有一次能力协商（内部机制，对玩家无感）；未装模组的客户端走原版路径，默认可连（`compat.requireClientMod = false`）。

---

## 配置项

现行网络相关键（完整表见 [Configuration](Configuration)）：

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `master.enabled` | `true` | 服务端网络优化总开关（压缩/聚合/区块推送/实体优化） |
| `chunk.enabled` | `true` | 客户端区块核心总开关（影子端 / 缓存 / 算光） |
| `master.maxChunksPerTick` | `5` | 每玩家每 tick 区块下发上限 |
| `master.enablePacketAggregation` | `true` | 包聚合 |
| `master.aggregationMaxWaitTimeMs` | `50` | 聚合最大等待（ms；ACK 超时 5s 自动降级直发） |
| `master.compressionBlacklist` | `[]` | 第三方包压缩 / 聚合排除（Hassium 控制面已硬编码排除） |
| `master.entity*` | 见 [Configuration](Configuration) | 实体优化 9 键（默认全开；原版客户端可生效） |

---

## 相关页面

[← Support-Matrix](Support-Matrix) · [Home](Home) · [→ FAQ](FAQ)
