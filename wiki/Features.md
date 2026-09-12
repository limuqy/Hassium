# 特性

---

> **English**: [Features-en](Features-en) · 中文

Hassium 用一套客户端 + 服务端配合，从**高效压缩、网络优化、区块缓存、超视渲染、本地生成、光照优化、实用工具**七个方向优化 Minecraft。本页按大类给出每条功能的速览与适用条件。

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
  - 聚合包内部字典 ZSTD（上下文压缩，`master.useContextCompression`）
  - 区块推送自有压缩（推送链独立压缩，不触碰原版压缩层）
  - 聚合 + 紧凑包头提升压缩比与包效率
- **配置**：`master.enabled`、`master.compressionLevel`、`master.useContextCompression`、`master.enablePacketAggregation`
- **边界**：不触碰原版压缩层（管线级全局包压缩已退役），无跨 mod 管线冲突面

---

## 网络优化

### 平滑推送

- **目标**：进服与视野扩展时服务端不把主线程压满、客户端不出现卡顿尖峰
- **服务端怎么做**（推送侧）：
  - **tick 粒度限速**：`master.maxChunksPerTick`（默认 `5`）限制每玩家每 tick 完成的 Pull FULL/DELTA（5×20 = 100/s 满 tick）；UNCHANGED 另额 32；掉刻时每秒总量自然下降
  - **序列化后台化**：encode / ZSTD 压缩 / hash 计算 / 发送在 CPU 核数推送池（`availableProcessors()`）；主线程只做 packet 快照构建——与原版对齐（原版也是主线程构建 + netty 线程编码）
- **客户端怎么做**（加载侧）：
  - 每帧主线程 apply 预算 `chunk.mainThreadChunkBudgetMs`（默认 `15`）
  - 进服 30s 内走 JoinBoost 临时抬高预算（30ms 封顶窗口），之后回落默认
- **指标**：`/hassium stats` 与 `/hassiumc stats` 看吞吐与缓存

---

### 登录期能力握手 + Pull 模式

- **目标**：双端能力协商零超时依赖、原版客户端零干扰；协商通过后区块数据按需拉取
- **怎么做的**：
  - 1.20.1 服务端在 `handleAcceptedLogin` 内（LoginCompression 之后、GameProfile 之前）发 `hassium:login_hello` login query；1.21.1+ 走配置阶段 `PreHandshakePayload`（认证完成后）
  - 能力位按位与协商（agg/delta/seed/light/pull/shadow_pull/pull_mode）；空应答或无共同能力位 → 服务端原版路径（`compat.requireClientMod=true` 时登录期踢出）
  - Play 期激活链：`ServerPlayer <init>` TAIL 消费协商位（压制原版区块窗口）→ dictionary_sync/index_sync → 聚合 PENDING（5s 无 ACK 降级直发）→ `play_init_s2c` → 客户端 ACK → 聚合 ENABLED
  - **Pull 模式**（`pull_mode` 能力位）：协商通过后服务端对该玩家停发 chunk_payload 整柱推送（forget/元数据照常），区块数据全部由客户端影子虚拟玩家 tracking 驱动的统一 Compare+Pull 拉取（`ShadowPull`：UNCHANGED / DELTA / FULL / ERROR 四终态）
- **配置**：`master.enabled`（服务端门）、`chunk.enabled`（客户端门）

---

## 区块缓存

### 缓存命中（影子端世界保存）

- **目标**：再次进入同一区域少传全量区块
- **怎么做的**：服务端在推送前算 chunkHash；客户端影子端用本地缓存里的 contentHash 比对，命中直接走本地解压 apply，跳过原版全量下载
- **配置**：`chunk.enabled`（默认 `true`）
- **细节**：缓存由影子端承担——进服区块统一落盘原版存档 `hassium_cache/<serverId>/world`（type 126 + chunkHash；旧 HBT1 客户端缓存格式已裁剪）；按 region 文件热度淘汰（`heat.idx` 跨会话累计，整文件删除 `.mca`）。分段增量、世界导出都复用同一份缓存数据（见下）

---

### 分段增量

- **目标**：缓存过期（MISMATCH）时避免整块重传
- **怎么做的**：影子端上报 section hash 与平面综合征；服务端稀疏只推变更方块（`BLOCKS`），过多或 paletted 更小则整段（`FULL`），变更段 ≥75% 则整块。失败/超时回退全量
- **配置**：`chunk.sectionDeltaEnabled`（默认 `true`；需同时 `chunk.enabled`）

| 比对结果 | 关闭分段增量 | 开启（默认） |
| --- | --- | --- |
| HIT | 缓存队列 | 缓存队列 |
| MISS | 全量请求 | 全量请求 |
| MISMATCH | 全量请求 | 补变更方块 / 整段（失败回退全量） |

---

### 世界导出

- **目标**：把影子端世界目录导出为独立存档（保留 type 126 + chunkHash 格式，原版翻译后续提供）
- **命令**：`/hassiumc export [<serverIp>] [seed]`
- **专文**：[World-Export](World-Export)

---

### 本地生成（SeedGen）

- **目标**：大片未探索地形（pristine 区块）不再逐块传输，零带宽生成
- **怎么做的**：服务端在 Play 激活（`play_init_s2c`）下发世界种子（`LevelStem` NBT）；门控开时客户端影子端 vanilla tracking 直接触发原版 worldgen 本地生成 pristine 区块，生成后经服务端权威 compare-pull 校验，再走与远程区块相同的（算光 → 打包官方包 → 官方通道落地）链，断连一并 `saveAll` 落盘；失败/校验不过自动回退全量请求
- **配置**：`chunk.seedGenEnabled`（默认 `false`，需双端同版本同开）
- **风险**：**服务端开启会向客户端下发世界种子，等同泄露服务端种子**（探图/种子地图/导出存档均可利用）

---

## 超视渲染

### OVD（影子双窗）

- **目标**：多人服客户端渲染距离（RD）大于服务端视距（serverVD）时，用本地缓存回填视距外环带——**仅参与渲染、不参与模拟**
- **怎么做的**：影子 tracking 扩到 effective clientRD；权威窗（`dist ≤ serverVD`）走统一 Compare+Pull，OVD 窗只从本地源（盘 / 注入）回填，**禁止向真服请求**；客户端只抬 `ClientChunkCache` 半径并拦截 Forget
- **配置**：`chunk.viewDistanceExtensionEnabled`（默认 `true`；依赖 `chunk.enabled`）、`chunk.maxRenderDistance`（默认 `16`）
- **边界**：与 Bobby 互斥；详见 [Beyond-View-Render](Beyond-View-Render) 与 [`docs/chunk-cache.md`](../docs/chunk-cache.md) §10

---

## 光照优化

### Hassium 引擎（默认开启）

- **是什么**：进服后在客户端进程内启动影子端（完整 MinecraftServer），统一承担世界保存（缓存）+ 区块光照计算 + 打包官方区块包——客户端不再自己算光，加载阶段主线程不再被光照重算占用
- **总开关**：`chunk.enabled`（默认 `true`）；关闭后影子端不启动，服务端在握手时不剥光（未声明引擎可用），光照随包自带，全程原版路径
- **启动失败自动降级**：影子端启动失败时自动关闭客户端缓存 / SeedGen 并在游戏内提示，网络与基础加载不受影响；服务端未装 Hassium MOD 时影子端不启动（无世界种子），光随数据包自带，缓存 / 世界导出仍可用
- **世界种子**：影子端使用服务端握手下发的 worldSeed（服务端已装 MOD），不自行生成世界

### 光照剥离

- **目标**：服务端省下光照数据传输
- **怎么做的**：服务端发包可剥离光照（`chunk.lightStrip` 默认 `true`，空 lightMask 构造，几乎零成本）；**剥光在握手协商**——仅客户端声明引擎可用（`chunk.enabled=true`）时服务端才剥，否则光随包自带；剥离的光照由客户端影子端计算并写回缓存
- **配置**：`chunk.lightStrip`

---

### 光照缓存

- **目标**：客户端避免重复算光
- **怎么做的**：影子端算好的光照写回影子端存档（随区块数据一体存储）；后续缓存命中直接 apply 已存光照；SectionDelta 合并后交由影子端重新计算
- **指标**：`/hassiumc stats` 显示 `光照缓存：xx%（命中 N，影子复用 M，重算 K）` 与 `光照重算：主线程 x ms，后台 y ms`（影子端本会话重算光统一计入重算 K）

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
