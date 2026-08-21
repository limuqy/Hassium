# 特性

---

> **English**: [Features-en](Features-en) · 中文

Hassium 用一套客户端 + 服务端配合，从**高效压缩、网络优化、区块缓存、本地生成、光照优化、实用工具**六个方向优化 Minecraft。本页按大类给出每条功能的速览与适用条件。

---

## 高效压缩

### 存储压缩

- **目标**：缩小世界存档体积，仍兼容原版 `.mca` 布局
- **怎么做的**：服务端把每个 chunk payload 用 ZSTD 压缩并标记为 type 126；外层 Region（32×32）数据结构不变
- **配置**：`storage.enabled`（默认 `false`，仅专用服务器）、`storage.zstdLevel`（默认 `3`）
- **注意**：首次启用会改写区块落盘格式，**备份世界**。详见 [FAQ](FAQ)。

---

### 网络压缩

- **目标**：进服与跑图时下载等待更短、带宽占用更低
- **怎么做的**：
  - 自定义 `hassium:*` 通道用 ZSTD 传区块等数据
  - 可选全局管道 ZSTD 替换原版 Zlib（`master.globalPacketCompression`）
  - 聚合 + 紧凑包头 + 上下文压缩提升压缩比
- **配置**：`master.enabled`、`master.globalPacketCompression`、`master.compressionLevel`、`master.enablePacketAggregation`

---

## 网络优化

### 平滑推送

- **目标**：进服与视野扩展时服务端不把主线程压满、客户端不出现卡顿尖峰
- **服务端怎么做**（推送侧）：
  - **tick 粒度限速**：`master.maxChunksPerTick`（默认 `4`）限制每玩家每 tick 提交上限（4×20 = 80/s 满 tick）；掉刻时每 tick 提交量不变、每秒总量自然下降，即保护主线程
  - **序列化后台化**：encode / ZSTD 压缩 / hash 计算 / 发送全部在固定推送线程池（`master.serverChunkPushThreads` 默认 4）；主线程只做 packet 快照构建——与原版对齐（原版也是主线程构建 + netty 线程编码）
  - **反馈式渐进 admission**：full / SeedGen 投递按 `(dimension, chunk)` 去重入队；客户端 authoritative 落地后发 `ChunkApplyAck`（`CHUNK_APPLY_ACK` 帧）；首次 ACK 前仅一个未确认批次，之后最多 10 批；`GatewayChannel.isWritable` 为传输背压，不能替代 apply ACK
  - **验收状态**：实现与编译/单测已交付；Fabric 1.20.1 真实进服/移动生产–apply 曲线仍待独立运行窗口验收（见 [`network-core-followups.md`](../docs/network-core-followups.md)）
- **客户端怎么做**（加载侧）：
  - 每帧主线程 apply 预算 `chunk.mainThreadChunkBudgetMs`（默认 `15`）
  - 进服约 10 秒走 JoinBoost 临时抬高预算，再线性退坡到默认
- **指标**：`/hassium stats` 与 `/hassiumc stats` 看吞吐与缓存

---

### 进程内网关与无感迁移

- **目标**：客户端经进程内网关（网络核心）接入主控核心；主控断线或卡顿时无感迁移，缓存续流、断连界面隐藏，玩家全程看不到切换
- **怎么做的**：客户端进程内网络核心（`network/core/`：NetworkCore 状态机 / outbound 帧协议 / migration 迁移引擎 / viafabric 桥）经网关帧协议连接主控核心（`network/gateway/`，GatewayServer）；主控故障由 L1 迁移引擎按生效静默超时（默认 `master.migrationSilentTimeoutMs`=`10000`）判定后直接迁移——磁盘缓存、保存队列、任务执行器全保留，新会话直接续上，命中率不掉、地形不需重下，不弹「连接丢失」
- **UDP 数据面**：网关↔主控通道的 bulk 载体（UDP/KCP，AES-GCM 双向认证），默认关（`dataplane.enabled = false`）；关闭时全部流量走网关帧连接
- **配置**：`dataplane.enabled`（默认 `false`）、`master.migrationSilentTimeoutMs`（默认 `10000`）、`master.migrationFaultTimeoutMs`（legacy `60000` 回退）、服务端 `master.controlReachableEndpoints`（握手同步到客户端，未配置时兜底 `25566`）
- **专文**：[网络核心与主控迁移](Network-Core-and-Master-Migration)

---

### L1 负载均衡

- **目标**：高人数服区块下行的带宽瓶颈，用多线路分担
- **怎么做的**：主控核心监听控制可达端点；UDP 数据面可配置多个 UDP listener（`dataplane.udpListeners`），按 `weight` 加权轮询承载区块下行。一条线路打满或降级时流量自动压到其余线路；登录、命令、实体同步等“控制类”流量仍走网关帧连接，不受数据线路波动影响
- **默认**：关闭（与数据面同开关 `dataplane.enabled = false`）。需按线路配公网 UDP 端点
- **配置**：`dataplane.udpListeners`（weight 默认 `100`）
- **专文**：[网络核心与主控迁移](Network-Core-and-Master-Migration)

---

## 区块缓存

### 缓存命中

- **目标**：再次进入同一区域少传全量区块
- **怎么做的**：服务端在推送前算 chunkHash；客户端用本地缓存里的 contentHash 比对，命中直接走本地解压 apply，跳过原版全量下载
- **配置**：`chunk.enabled`（默认 `true`）
- **细节**：缓存由影子端承担——进服区块统一落盘原版存档 `hassium_cache/<serverId>/world`（type 126 + chunkHash；旧 HBT1 客户端缓存格式已裁剪）；按热度淘汰（`heat.idx` 跨会话累计）。分段增量、超视渲染、世界导出都复用同一份缓存数据（见下）

---

### 分段增量

- **目标**：缓存过期（MISMATCH）时避免整块重传
- **怎么做的**：客户端上报 section hash 与平面综合征；服务端稀疏只推变更方块（`BLOCKS`），过多或 paletted 更小则整段（`FULL`），变更段 ≥75% 则整块。失败/超时回退全量
- **配置**：`chunk.sectionDeltaEnabled`（默认 `true`；需同时 `chunk.enabled`）

| 比对结果 | 关闭分段增量 | 开启（默认） |
| --- | --- | --- |
| HIT | 缓存队列 | 缓存队列 |
| MISS | 全量请求 | 全量请求 |
| MISMATCH | 全量请求 | 补变更方块 / 整段（失败回退全量） |

---

### 超视渲染

- **目标**：多人服客户端 RD > 服务端视距时，本地缓存回填视距外环带，**仅渲染不参与模拟**
- **怎么做的**：解锁客户端滑块的 serverVD 钳制；本地缓存命中区块以 renderOnly 标记装配，不向服请求视距外区块/BE；真实区块到达时覆盖回正常
- **配置**：`chunk.viewDistanceExtensionEnabled`（默认 `true`）、`chunk.maxRenderDistance`（默认 `16`，范围 2–64）、`chunk.ovdUnloadDelaySecs`（默认 `5`）
- **限制**：与 Bobby 互斥；单人服不启用；RD>32 时雾距跟随扩大可能穿帮（Fog Mixin 未实现）
- **专文**：[Beyond-View-Render](Beyond-View-Render)

---

### 世界导出

- **目标**：把影子端世界目录导出为独立存档（保留 type 126 + chunkHash 格式，原版翻译后续提供）
- **命令**：`/hassiumc export [<serverIp>] [seed]`
- **专文**：[World-Export](World-Export)

---

### 本地生成（SeedGen）

- **目标**：大片未探索地形（pristine 区块）不再逐块传输，零带宽生成
- **怎么做的**：服务端对 pristine 区块只发引用（seed + 坐标 + hash，几十字节）替代区块数据；客户端影子服务端用同种子本地生成，与远程区块同链（算光 → 打包官方包 → 官方通道落地），断连一并 `saveAll` 落盘；失败/超时自动回退全量请求
- **配置**：`chunk.seedGenEnabled`（默认 `false`，需双端同版本同开）、`chunk.seedGenThreads`（2）

---

## 光照优化

### Hassium 引擎（默认开启）

- **是什么**：进服后在客户端进程内启动影子端，统一承担全部区块光照计算——客户端不再自己算光，加载阶段主线程不再被光照重算占用
- **总开关**：`chunk.hassiumEngineEnabled`（默认 `true`）；关闭后服务端在握手时不剥光（未声明引擎可用），光照随包自带
- **启动失败自动降级**：影子端启动失败时自动关闭客户端缓存 / 超视渲染 / SeedGen 并在游戏内提示，网络与基础加载不受影响；服务端未装 Hassium MOD 时影子端不启动（无世界种子），光随数据包自带，缓存 / OVD / 世界导出仍可用
- **世界种子**：影子端使用服务端握手下发的 worldSeed（服务端已装 MOD），不自行生成世界

### 光照剥离

- **目标**：服务端省下光照数据传输
- **怎么做的**：服务端发包可剥离光照（`chunk.lightStrip` 默认 `true`，空 lightMask 构造，几乎零成本）；**剥光在握手协商**——仅客户端声明引擎可用（`hassiumEngineEnabled=true`）时服务端才剥，否则光随包自带；剥离的光照由客户端影子端计算并写回缓存
- **配置**：`chunk.lightStrip`

---

### 光照缓存

- **目标**：客户端避免重复算光
- **怎么做的**：影子端算好的光照写回缓存（`is_light_on=1`，随区块数据一体存储）；后续缓存命中直接 apply 已存光照；SectionDelta 合并后强制 `is_light_on=0` 交由影子端重新计算
- **指标**：`/hassiumc stats` 显示 `光照缓存：xx%（命中 N，影子复用 M，重算 K）` 与 `光照重算：主线程 x ms，后台 y ms`（影子端本会话重算光统一计入重算 K）

---

## 实用工具

### 流量监控

| 命令 | 侧 | 输出 |
| --- | --- | --- |
| `/hassium stats` | 服务端 | 发送（原版 Zlib 等价）/ 节省% / 压缩比 / 元数据发送 / 数据请求接收 / 区块压缩 |
| `/hassiumc stats` | 客户端 | 带宽压缩 / 区块缓存（全命中+部分命中−增量 / 应用，按字节；本地生成不算缓存）/ 区块加载（新增+过期+本地）/ 光照缓存 / 光照重算 / 超视渲染 ON\|OFF / 流量节省（实际 / 无MOD应收） |

完整命令参考见 [Commands](Commands)。

---

> **兼容性**：未安装本模组的客户端默认可连接（`compat.requireClientMod = false`），仅享受服务端侧压缩；缓存、协商压缩等高级特性需要双端都装。对照表见 [Compatibility](Compatibility)。

[← Commands](Commands) · [Home](Home) · [→ Beyond-View-Render](Beyond-View-Render)
