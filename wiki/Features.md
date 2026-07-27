# 特性

---

> **English**: [Features-en](Features-en) · 中文

Hassium 用一套客户端 + 服务端配合，从存档体积、网络带宽、进服流畅度三个维度优化 Minecraft。本页给出每条功能的速览与适用条件。

---

## 高效存储

- **目标**：缩小世界存档体积，仍兼容原版 `.mca` 布局
- **怎么做的**：服务端把每个 chunk payload 用 ZSTD 压缩并标记为 type 126；外层 Region（32×32）数据结构不变
- **配置**：`storage.enabled`（默认 `true`）、`storage.zstdLevel`（默认 `9`）
- **注意**：首次启用会改写区块落盘格式，**备份世界**。详见 [FAQ](FAQ)。

---

## 网络压缩

- **目标**：进服与跑图时下载等待更短、带宽占用更低
- **怎么做的**：
  - 自定义 `hassium:*` 通道用 ZSTD 传区块等数据
  - 可选全局管道 ZSTD 替换原版 Zlib（`globalPacketCompression`）
  - 聚合 + 紧凑包头 + 上下文压缩提升压缩比
- **配置**：`network.enabled`、`network.globalPacketCompression`、`network.compressionLevel`、`network.enablePacketAggregation`

---

## 客户端区块缓存

- **目标**：再次进入同一区域少传全量区块
- **怎么做的**：服务端在推送前算 chunkHash；客户端用本地缓存里的 contentHash 比对，命中直接走本地解压 apply，跳过原版全量下载
- **配置**：`clientCache.enabled`（默认 `true`）
- **细节**：客户端缓存以磁盘 NBT（`HBT1` magic + CompoundTag）存于 `hassium_cache`；按热度淘汰（不整文件删除 `.mca`）

---

## 分段增量

- **目标**：缓存过期（MISMATCH）时避免整块重传
- **怎么做的**：客户端拿 sectionHashes 与服务端比对，只请求变更分段（`SectionHashRequest` / `SectionDeltaS2C`），在本地与缓存 NBT 合并后写入磁盘；失败/超时自动回退全量
- **配置**：`clientCache.sectionDeltaEnabled`（默认 `true`；需同时 `clientCache.enabled`）

| 比对结果 | 关闭分段增量 | 开启（默认） |
| --- | --- | --- |
| HIT | 缓存队列 | 缓存队列 |
| MISS | 全量请求 | 全量请求 |
| MISMATCH | 全量请求 | `SectionHashRequest` → NBT merge（失败回退全量） |

---

## 超视渲染

- **目标**：多人服客户端 RD > 服务端视距时，本地缓存回填视距外环带，**仅渲染不参与模拟**
- **怎么做的**：解锁客户端滑块的 serverVD 钳制；本地缓存命中区块以 renderOnly 标记装配，不向服请求视距外区块/BE；真实区块到达时覆盖回正常
- **配置**：`clientCache.viewDistanceExtensionEnabled`（默认 `true`）、`clientCache.maxRenderDistance`（默认 `32`，范围 2–64）、`clientCache.ovdUnloadDelaySecs`（默认 `5`）
- **限制**：与 Bobby 互斥；单人服不启用；RD>32 时雾距跟随扩大可能穿帮（Fog Mixin 未实现）
- **专文**：[Beyond-View-Render](Beyond-View-Render)

---

## 光照优化

- **目标**：服务端省下光照数据传输；客户端避免每次同步重算
- **怎么做的**：
  1. 服务端可剥离光照，首次加载时空光照 → 客户端本地重算并回写缓存（`is_light_on=1`）
  2. 缓存命中：直接 apply 已存光照，跳过同步重算
  3. SectionDelta 合并后强制 `is_light_on=0` 避免假命中
- **配置**：`clientCache.lightCacheEnabled`（默认 `true`）
- **指标**：`/hassiumc stats` 显示 `光照优化：xx%（命中 N，重算 M）`

---

## 世界导出

- **目标**：把本地缓存导出为可进单机的原版 Anvil 世界
- **命令**：`/hassiumc export [<serverIp>] [seed]`
- **专文**：[World-Export](World-Export)

---

## 主控热切（TCP 控制 Failover）

- **目标**：TCP 主控断或卡时按候选自动重连，缓存暖续、隐藏断连界面
- **拓扑**：控制面（Master TCP，原版 login + Play）+ 数据面（UDP/KCP，多端点加权）
- **触发**：硬断连立即切下一候选；Master stall 6s + UDP 健康时服务端下发 `FailoverPermit`，客户端连接下一端点
- **恢复期保留**：磁盘缓存、`CacheSaveQueue`、`HassiumTaskExecutor` 与 dirty 标志全保留以承接下一候选会话
- **配置**：`network.dataPlane.controlStallMs`（默认 `6000`）、`failoverPermitTtlMs`（默认 `30000`）
- **专文**：[Data-Plane-and-Failover](Data-Plane-and-Failover)

---

## 加权分流

- **目标**：多 UDP/KCP endpoint 按 weight 加权轮询承载区块 bulk
- **配置**：每 endpoint 在 `network.dataPlane.udpEndpoints` 配置 `weight`
- **专文**：[Data-Plane-and-Failover](Data-Plane-and-Failover)

---

## 平滑加载

- **目标**：进服与视野扩展时降低卡顿尖峰
- **怎么做的**：
  - 服务端每玩家每 tick 序列化上限 `network.maxChunksPerTick`（默认 `10`）
  - 客户端每帧主线程 apply 预算 `clientCache.mainThreadChunkBudgetMs`（默认 `15`）
  - 进服约 10 秒走 JoinBoost 临时抬高预算，再线性退坡到默认
- **指标**：`/hassium stats` 与 `/hassiumc stats` 看吞吐与缓存

---

## 流量监控

| 命令 | 侧 | 输出 |
| --- | --- | --- |
| `/hassium stats` | 服务端 | 原始字节 / 发送字节 / 压缩节省 / 推送统计 |
| `/hassiumc stats` | 客户端 | 接收字节 / 缓存命中 / 超视渲染 / 光照优化 |

完整命令参考见 [Commands](Commands)。

---

[← Commands](Commands) · [Home](Home) · [→ Beyond-View-Render](Beyond-View-Render)
