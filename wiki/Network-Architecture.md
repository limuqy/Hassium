# 网络架构（直连拓扑）

---

> **English**: [Network-Architecture-en](Network-Architecture-en) · 中文

> **2.0.0 起回归直连拓扑**：历史上的「进程内网关（网络核心）/ 无感主控迁移 / L1 负载均衡 / UDP 数据面 / 客户端 failover」均已裁剪退役（决策见仓库 [`docs/handoff/handoff-2026-09-04-vanilla-direct-network.md`](https://github.com/limuqy/Hassium/blob/master/docs/handoff/handoff-2026-09-04-vanilla-direct-network.md)）。

## 现行拓扑：唯一 vanilla TCP

客户端与服务端之间**只有一条 vanilla TCP 连接**（游戏端口）：

| 阶段 | 承载 | 说明 |
| --- | --- | --- |
| 登录期（1.20.1） | vanilla login custom query（`hassium:login_hello`） | 服务端主动发 query（LoginCompression 之后、GameProfile 之前），客户端应答能力位；原版客户端恒空应答 → 服务端原版路径，**无超时依赖、零干扰** |
| 配置阶段（1.21.1+） | vanilla configuration payload（S2C `hassium:prehandshake_hello_s2c` / C2S `PreHandshakePayload`） | NeoForge / Forge 由服务端配置任务下发 hello，客户端 handler 内同步应答；Fabric 客户端在配置阶段 START 主动声明 |
| Play 期 | vanilla 通道上的 `hassium:*` 自定义 payload | 区块 / 业务全部走 vanilla 通道；原版压缩层不触碰 |

### 能力协商与激活链

1. 登录期 / 配置期能力位按位与协商（agg/delta/seed/light/pull/shadow_pull/pull_mode），结果入 `PlayerCompressionTracker`
2. `ServerPlayer <init>` TAIL 消费协商位（压制原版区块窗口）
3. tick 泵激活：dictionary_sync/index_sync → 聚合 PENDING（5s 无 ACK 降级直发）→ `play_init_s2c`（协商位 + SeedGen 种子）
4. 客户端 index_sync 后回激活 ACK → 聚合 ENABLED

### 通道压缩

- **仅两处，均不触碰 vanilla 压缩层**：聚合包内部字典 ZSTD（发送时 EventLoop 阈值翻折防双重压缩）+ 区块推送自有压缩
- 控制面（握手、index sync、chunkHash 等）在压缩黑名单，不进 PENDING 聚合缓冲

### Pull 模式

协商通过后（`pull_mode` 能力位）服务端对该玩家停发 chunk_payload 整柱推送（forget / 元数据照常），区块数据全部由客户端影子虚拟玩家 tracking 驱动的统一 Compare+Pull 拉取（`ShadowPull`：UNCHANGED / DELTA / FULL / ERROR 四终态）。详见 [Features](Features)。

### 断连重连

断连 → 原版重连流程 → 影子端世界缓存（`hassium_cache/<serverId>/world`）按 chunkHash 复用：未变更区块走缓存命中（UNCHANGED），变更区块走分段增量（DELTA），仅缺失区块走全量。

---

## 配置项

现行网络相关键（完整表见 [Configuration](Configuration)）：

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `master.enabled` | `true` | 服务端网络通道总开关（登录期握手 / 聚合的门） |
| `chunk.enabled` | `true` | 客户端区块核心总开关（Pull 模式 / 影子端 / 缓存的门） |
| `master.enablePacketAggregation` | `true` | 包聚合 |
| `master.aggregationMaxWaitTimeMs` | `50` | 聚合最大等待（ms；ACK 超时 5s 自动降级直发） |
| `master.compressionBlacklist` | `[]` | 第三方包压缩 / 聚合排除（Hassium 控制面已硬编码排除） |

公网部署只需放行游戏端口（vanilla TCP）；无网关 / UDP 端口。

---

## 相关页面

[← Support-Matrix](Support-Matrix) · [Home](Home) · [→ FAQ](FAQ)
