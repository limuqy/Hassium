# 特性

---

> **English**: [Features-en](Features-en) · 中文

Hassium 用一套客户端 + 服务端配合，从**高效压缩、网络优化、区块缓存、光照优化、实用工具**五个方向优化 Minecraft。本页按大类给出每条功能的速览与适用条件。

---

## 高效压缩

### 存储压缩

- **目标**：缩小世界存档体积，仍兼容原版 `.mca` 布局
- **怎么做的**：服务端把每个 chunk payload 用 ZSTD 压缩并标记为 type 126；外层 Region（32×32）数据结构不变
- **配置**：`storage.enabled`（默认 `false`，仅专用服务器）、`storage.zstdLevel`（默认 `3`）
- **注意**：首次启用会改写区块落盘格式，**备份世界**。详见 [FAQ](FAQ)。

---

### 通道压缩

- **目标**：进服与跑图时下载等待更短、带宽占用更低
- **怎么做的**：
  - 聚合包内部字典 ZSTD（达到阈值后始终压缩）
  - 区块推送自有压缩（推送链独立压缩，不触碰原版压缩层）
  - 聚合 + 紧凑包头提升压缩比与包效率
- **配置**：`master.enabled`、`master.compressionLevel`、`master.enablePacketAggregation`
- **边界**：不触碰原版压缩层（管线级全局包压缩已退役），无跨 mod 管线冲突面

---

## 网络优化

### 平滑推送

- **目标**：进服与视野扩展时服务端不把主线程压满、客户端不出现卡顿尖峰
- **服务端怎么做**（推送侧）：
  - **tick 粒度限速**：`master.maxChunksPerTick`（默认 `5`）限制每玩家每 tick 的区块下发量（5×20 = 100/s 满 tick）；掉刻时每秒总量自然下降
  - **序列化后台化**：encode / ZSTD 压缩 / hash 计算 / 发送在 CPU 核数推送池（`availableProcessors()`）；主线程只做 packet 快照构建——与原版对齐（原版也是主线程构建 + netty 线程编码）
- **客户端怎么做**（加载侧）：
  - 每帧主线程 apply 预算 `chunk.mainThreadChunkBudgetMs`（默认 `15`）
  - 进服 30s 内走 JoinBoost 临时抬高预算（30ms 封顶窗口），之后回落默认
- **指标**：`/hassium stats` 与 `/hassiumc stats` 看吞吐与缓存

---

### 实体优化

- **目标**：大量生物/掉落物同 tick 齐发时压带宽与主线程；**原版客户端也能吃到**（只改下发节拍，不改协议）
- **怎么做的**（四层正交叠加，默认全开）：
  - **距离分档降频**：离得越远更新越稀（`master.entityTieredUpdateEnabled` + `entityTierIntervals`）
  - **物品流独立档位**：掉落物/经验球单独一张表（`entityItemTierIntervals`，默认 2/4/8/16 刻），避免被原版 20 刻空闲节拍压平导致闪现
  - **热点密度降频**：实体所在 chunk 过密时按档放大间隔（`entityDensityThrottleEnabled` + `entityDensityTierCounts/Factors`）
  - **包量反压**：某玩家持续超过每 tick 实体包预算时，他视野内实体自动变稀（`entityFrameBudgetPerPlayer`，默认 256）
  - **错峰推送**：同间隔实体按 UUID 错开发送时刻，3 刻总量不变、摊平齐发尖峰（`entitySmoothPushEnabled`）
- **边界**：只改复制（下发）节拍，不碰服务端实体 tick / 拾取 / 漏斗判定；玩家自身位移豁免
- **配置**：`master.entity*`（跟 `master.enabled` 总闸；全关 = 等同原版）

---

## 区块缓存

### 世界保存

- **目标**：再次进入同一区域少传全量区块
- **怎么做的**：客户端**全被动**，只收官方 chunk+light 包；比对在影子端完成。服务端推送时经权威边沿声明内容 hash：本地 hash 相同则**零请求本地交付**；不等/未知时走统一 ShadowPull（UNCHANGED 复用 / DELTA 只补变更 / 缺失才 FULL）
- **配置**：`chunk.enabled`（默认 `true`）
- **细节**：进服区块由影子端落盘 `hassium_cache/<serverId>/world`；分段增量、世界导出、热度淘汰都复用同一份缓存数据（见下）

---

### 热度淘汰

- **目标**：缓存占用不超过设定上限
- **怎么做的**：按 region 文件累计访问热度，超容量时优先删除较冷的整个 region 文件
- **配置**：`chunk.maxSizeMb`（默认 `4096`）、`chunk.hotScoreThreshold`、`chunk.cleanupIntervalTicks`

---

### 分段增量

- **目标**：基线过期时避免整块重传
- **怎么做的**：影子端上报 section hash 与平面综合征；服务端稀疏只推变更方块（`BLOCKS`），过多或 paletted 更小则整段（`FULL`），变更段 ≥75% 则整块。失败/超时回退全量
- **配置**：`chunk.sectionDeltaEnabled`（默认 `true`；需同时 `chunk.enabled`）

| 比对结果 | 关闭分段增量 | 开启（默认） |
| --- | --- | --- |
| 权威 hash 命中 | 本地交付（零请求） | 本地交付（零请求） |
| UNCHANGED | 本地 materialize 回传 | 本地 materialize 回传 |
| 无基线 / FULL | 全量 | 全量 |
| DELTA（基线过期） | 全量 | 补变更方块 / 整段（失败回退全量） |

---

### 世界导出

- **目标**：把本地缓存导出为独立存档目录
- **命令**：`/hassiumc export [<serverIp>] [seed]`
- **专文**：[World-Export](World-Export)

---

### 本地生成

- **目标**：大片未探索地形不再逐块传输，本地生成省带宽
- **怎么做的**：双端同版本且开启时，服务端下发世界种子；客户端本地触发原版地形生成，生成后经服务端权威校验再落地；失败/校验不过自动回退全量请求
- **配置**：`chunk.seedGenEnabled`（默认 `false`，需双端同版本同开）
- **风险**：**服务端开启会向客户端下发世界种子，等同泄露服务端种子**（探图/种子地图/导出存档均可利用）

---

### 超视渲染

- **目标**：客户端渲染距离大于服务端视距时，用本地缓存回填视距外环带——**仅参与渲染、不参与模拟**
- **怎么做的**：视距外环带只从本地缓存回填，**禁止向服务端请求**；客户端抬高本地区块缓存半径并拦截 Forget
- **配置**：`chunk.viewDistanceExtensionEnabled`（默认 `true`；依赖 `chunk.enabled`）、`chunk.maxRenderDistance`（默认 `16`）
- **边界**：与 Bobby 互斥；详见 [Beyond-View-Render](Beyond-View-Render)

---

## 光照优化

### 统一算光（默认开启）

- **是什么**：进服后在客户端进程内统一承担区块光照计算与官方区块包打包——客户端不再自己算光，加载阶段主线程不再被光照重算占用；同时承担世界保存（缓存）
- **总开关**：`chunk.enabled`（默认 `true`）；关闭后光照随包自带，全程原版路径
- **启动失败自动降级**：启动失败时自动关闭客户端缓存 / 本地生成并在游戏内提示，网络与基础加载不受影响；服务端未装本模组时光随数据包自带，缓存 / 世界导出仍可用
- **世界种子**：使用服务端握手下发的世界种子（服务端已装本模组时），不自行生成世界

### 光照剥离

- **目标**：服务端省下光照数据传输
- **怎么做的**：服务端发包可剥离光照（`chunk.lightStrip` 默认 `true`，空 lightMask 构造，几乎零成本）；**剥光在握手协商**——仅客户端声明引擎可用（`chunk.enabled=true`）时服务端才剥，否则光随包自带；剥离的光照由客户端影子端计算并写回缓存
- **配置**：`chunk.lightStrip`

---

### 光照缓存

- **目标**：客户端避免重复算光
- **怎么做的**：算好的光照随区块一体落盘；后续缓存命中直接应用已存光照；分段增量合并后重新计算
- **指标**：`/hassiumc stats` 显示光照缓存命中率与重算耗时

---

## 实用工具

### 流量监控

| 命令 | 侧 | 输出 |
| --- | --- | --- |
| `/hassium stats` | 服务端 | 发送（原版 Zlib 等价）/ 节省% / 压缩比 / 元数据发送 / 数据请求接收 / 区块压缩 |
| `/hassiumc stats` | 客户端 | 带宽压缩 / 区块缓存（全命中+部分命中−增量 / 应用，按字节；本地生成不算缓存）/ 区块加载（新增+过期+本地）/ 光照缓存 / 光照重算 / 流量节省（实际 / 无MOD应收） |

完整命令参考见 [Commands](Commands)。

---

> **兼容性**：未安装本模组的客户端默认可连接（`compat.requireClientMod = false`），仅享受服务端侧压缩；缓存、协商压缩等高级特性需要双端都装。对照表见 [Compatibility](Compatibility)。

[← Commands](Commands) · [Home](Home) · [→ Beyond-View-Render](Beyond-View-Render)
