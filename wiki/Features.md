# 特性

---

> **English**: [Features-en](Features-en) · 中文

Hassium 用一套客户端 + 服务端配合，从**高效压缩、网络优化、区块缓存、光照优化、实用工具**五个方向优化 Minecraft。本页按大类给出每条功能的速览与适用条件。

---

## 高效压缩

### 存储压缩

- **目标**：缩小世界存档体积，仍兼容原版 `.mca` 布局
- **怎么做的**：服务端把每个 chunk payload 用 ZSTD 压缩并标记为 type 126；外层 Region（32×32）数据结构不变
- **配置**：`storage.enabled`（默认 `false`，仅专用服务器）、`storage.zstdLevel`（默认 `9`）
- **注意**：首次启用会改写区块落盘格式，**备份世界**。详见 [FAQ](FAQ)。

---

### 网络压缩

- **目标**：进服与跑图时下载等待更短、带宽占用更低
- **怎么做的**：
  - 自定义 `hassium:*` 通道用 ZSTD 传区块等数据
  - 可选全局管道 ZSTD 替换原版 Zlib（`globalPacketCompression`）
  - 聚合 + 紧凑包头 + 上下文压缩提升压缩比
- **配置**：`network.enabled`、`network.globalPacketCompression`、`network.compressionLevel`、`network.enablePacketAggregation`

---

## 网络优化

### 平滑推送

- **目标**：进服与视野扩展时服务端不把主线程压满、客户端不出现卡顿尖峰
- **服务端怎么做**（推送侧）：
  - **tick 粒度限速**：`network.maxChunksPerTick`（默认 `5`）限制每玩家每 tick 提交上限（5×20 = 100/s 满 tick）；掉刻时每 tick 提交量不变、每秒总量自然下降，即保护主线程，主线程峰值 ≤ ~8ms/tick
  - **序列化后台化**：encode / ZSTD 压缩 / hash 计算 / 发送全部在推送线程池（`serverChunkPushThreads` 默认 2，可动态伸缩）；主线程只做 packet 构建——与原版对齐（原版也是主线程构建 + netty 线程编码），1.20.x/1.21.1 甚至整条序列化链都在后台
- **客户端怎么做**（加载侧）：
  - 每帧主线程 apply 预算 `clientCache.mainThreadChunkBudgetMs`（默认 `15`）
  - 进服约 10 秒走 JoinBoost 临时抬高预算，再线性退坡到默认
- **指标**：`/hassium stats` 与 `/hassiumc stats` 看吞吐与缓存

---

### 主控热切

- **目标**：TCP 主控断或卡时按候选自动重连，缓存暖续、隐藏断连界面；恢复期默认画面定格（tick 暂停、过渡画面仅隐藏渲染），也可切无感模式（世界继续运行、恢复后回退），玩家全程看不到切换 UI
- **怎么做的**：服务端预先把控制面候选端点列表随握手下发客户端；主控硬断或卡顿超过阈值且 UDP 数据面健康时，客户端按候选自动连下一个可达端点，不弹"连接丢失"。切会话期间磁盘缓存、保存队列、任务执行器全保留，新会话直接续上——命中率不掉、地形不需重下；恢复期间过渡画面（连接/加载/接收世界）保持 vanilla 驱动但隐藏绘制；定格模式（`network.dataPlane.recoveryFreeze=true`，默认）世界 tick 暂停，屏幕保持冻结世界 + 「正在切换主控…」浮层，恢复成功画面直接续动；无感模式（false）世界照常运行、输入被恢复窗口吞掉，恢复成功后位置回退到断线点、刚挖的方块还原，体感如同突然延迟变高卡了一下
- **默认**：关闭（`network.dataPlane.enabled = false`，模组默认走原版 TCP 单连接）。需运维能力，启用前请确认 Nginx / 公网防火墙 / NAT 规则
- **配置**：`network.dataPlane.controlStallMs`（默认 `6000`，主控卡顿多久触发 failover）、`failoverPermitTtlMs`（默认 `30000`，服务端下发 FailoverPermit 有效期）、`network.dataPlane.recoveryFreeze`（客户端默认 `true`；false=无感切换）
- **专文**：[主控热切与加权分流](Data-Plane-and-Failover)

---

### 加权分流

- **目标**：高人数服区块下行的带宽瓶颈，用多线路分担
- **怎么做的**：区块下载走 UDP/KCP 数据面，可配多个 endpoint（多条线路），按 `weight` 加权轮询承载。一条线路打满或降级时流量自动压到其余线路；登录、命令、实体同步等"控制类"流量仍走原版 TCP，不受数据线路波动影响
- **默认**：关闭（与主控热切同开关 `network.dataPlane.enabled = false`）。需按线路配公网 UDP 端点
- **配置**：每个 endpoint 在 `network.dataPlane.udpEndpoints` 配置 `weight`（默认 `100`），可设 `priority` 控制候选顺序
- **专文**：[主控热切与加权分流](Data-Plane-and-Failover)

---

## 区块缓存

### 客户端区块缓存

- **目标**：再次进入同一区域少传全量区块
- **怎么做的**：服务端在推送前算 chunkHash；客户端用本地缓存里的 contentHash 比对，命中直接走本地解压 apply，跳过原版全量下载
- **配置**：`clientCache.enabled`（默认 `true`）
- **细节**：客户端缓存以磁盘 NBT（`HBT1` magic + CompoundTag）存于 `hassium_cache`；按热度淘汰（不整文件删除 `.mca`）。分段增量、超视渲染、世界导出都复用同一份缓存数据（见下）

---

### 分段增量

- **目标**：缓存过期（MISMATCH）时避免整块重传
- **怎么做的**：客户端拿 sectionHashes 与服务端比对，只请求变更分段（`SectionHashRequest` / `SectionDeltaS2C`），在本地与缓存 NBT 合并后写入磁盘；失败/超时自动回退全量
- **配置**：`clientCache.sectionDeltaEnabled`（默认 `true`；需同时 `clientCache.enabled`）

| 比对结果 | 关闭分段增量 | 开启（默认） |
| --- | --- | --- |
| HIT | 缓存队列 | 缓存队列 |
| MISS | 全量请求 | 全量请求 |
| MISMATCH | 全量请求 | `SectionHashRequest` → NBT merge（失败回退全量） |

---

### 超视渲染

- **目标**：多人服客户端 RD > 服务端视距时，本地缓存回填视距外环带，**仅渲染不参与模拟**
- **怎么做的**：解锁客户端滑块的 serverVD 钳制；本地缓存命中区块以 renderOnly 标记装配，不向服请求视距外区块/BE；真实区块到达时覆盖回正常
- **配置**：`clientCache.viewDistanceExtensionEnabled`（默认 `true`）、`clientCache.maxRenderDistance`（默认 `32`，范围 2–64）、`clientCache.ovdUnloadDelaySecs`（默认 `5`）
- **限制**：与 Bobby 互斥；单人服不启用；RD>32 时雾距跟随扩大可能穿帮（Fog Mixin 未实现）
- **专文**：[Beyond-View-Render](Beyond-View-Render)

---

### 世界导出

- **目标**：把本地缓存导出为可进单机的原版 Anvil 世界
- **命令**：`/hassiumc export [<serverIp>] [seed]`
- **专文**：[World-Export](World-Export)

---

## 光照优化

### 光照剥离

- **目标**：服务端省下光照数据传输
- **怎么做的**：服务端发包可剥离光照（`network.lightStrip` 默认 `true`，空 lightMask 构造，几乎零成本）；客户端首次加载时空光照 → 本地重算并回写缓存
- **配置**：`network.lightStrip`

---

### 光照缓存

- **目标**：客户端避免每次同步重算
- **怎么做的**：首次加载重算后把光照数据写入缓存（`is_light_on=1`）；后续缓存命中直接 apply 已存光照，跳过同步重算；SectionDelta 合并后强制 `is_light_on=0` 避免假命中
- **配置**：`clientCache.lightCacheEnabled`（默认 `true`）

---

### 并行光照

- **目标**：重算光照不再占用主线程
- **怎么做的**：重算提交到后台线程池并行执行（默认 4 线程，虚拟线程模式不限），主线程只做 9 柱快照捕获与提交；完成回调在主线程预算内排程
- **配置**：`clientCache.parallelLightEngineEnabled`（默认 `true`）、`clientCache.parallelLightEngineThreads`（默认 `4`）
- **指标**：`/hassiumc stats` 显示 `光照优化：xx%（命中 N，重算 M）`

---

## 实用工具

### 流量监控

| 命令 | 侧 | 输出 |
| --- | --- | --- |
| `/hassium stats` | 服务端 | 原始字节 / 发送字节 / 压缩节省 / 推送统计 |
| `/hassiumc stats` | 客户端 | 接收字节 / 缓存命中 / 超视渲染 / 光照优化 |

完整命令参考见 [Commands](Commands)。

---

> **兼容性**：未安装本模组的客户端默认可连接（`compat.requireClientMod = false`），仅享受服务端侧压缩；缓存、协商压缩等高级特性需要双端都装。对照表见 [Compatibility](Compatibility)。

[← Commands](Commands) · [Home](Home) · [→ Beyond-View-Render](Beyond-View-Render)
