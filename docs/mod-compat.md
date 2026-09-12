# 多 Mod 兼容性

本文档描述 Hassium 与其他模组并存时的预期行为、已知边界与配置逃生口。跨版本 API 桥接见 [`version-segments.md`](version-segments.md)；区块推送见 [`chunk-cache.md`](chunk-cache.md)。

> 2.0.0 直连拓扑：客户端↔服务端**唯一 vanilla TCP**，全部自定义 payload 走 vanilla 通道；网络核心（进程内网关）、UDP 数据面、ViaFabric 兼容桥已裁剪。

## 1. 兼容边界

| 目标 | 策略 |
|------|------|
| Bobby / 同类客户端视距外缓存 | **不兼容**；Hassium 影子端自行管理缓存与重交付，勿与 Bobby 同装 |
| Immersive Portals | **不兼容** |
| 同类压缩 / 协议替换（改 Netty Zlib 等） | **不兼容**；Hassium 通道压缩虽不触碰 vanilla 压缩层，但同类 mod 若替换原版压缩管线仍有冲突面 |
| Starlight / ScalableLux | **主动兼容**（见 §7b）：光照引擎被整体替换，影子端 4 处控制面已适配 |
| 包聚合导致第三方包异常 | 关 `master.enablePacketAggregation`，或把包 ID 加入 `master.compressionBlacklist` |
| 反透视（改 chunk 发包内容） | **希望兼容**（miss 路径复用已构建包字节，见 §3） |
| Distant Horizons / Voxy | **希望兼容**（独立 LOD 通道；见 §4） |
| Sodium / Iris / Lithium / FerriteCore 等热门优化 | **冒烟通过**（Fabric 1.20.1，见 §6 / §10） |
| C2ME | **主动兼容**（读天然兼容 + 写侧接管，见 §7） |
| 文件级服务端备份（含 InstantBackup） | **兼容**（见 §8）；语义级解压 Anvil 的工具不兼容 |

## 2. 侵入面摘要

Hassium 主要改动：

1. **存档**：`RegionFile` payload type **126**（ZSTD+字典），`.mca` 外壳不变
2. **区块下发**：登录期能力握手 + Play 期激活后，服务端原版 tracking 推送 / 影子端统一 ShadowPull；全部以**原版包形态**走 vanilla TCP
3. **网络**：聚合包内部字典 ZSTD + 区块推送自有压缩 + 可选包聚合——均不触碰原版压缩层；未握手客户端零干扰

**第三方可见性**：Hassium 私有 payload（`hassium:*`）对第三方按原版 CustomPayload 可见、可拦截；区块数据本体以原版 `ClientboundLevelChunkWithLightPacket` 形态交付。

未握手 / 无 Hassium 客户端仍走原版 `ClientboundLevelChunkWithLightPacket`（`compat.requireClientMod` 默认 `false`）。

## 3. 反透视

反透视通常修改「即将发送的区块包」而非世界本身。

- **broadcast 路径**：拦截时持有的 `ClientboundLevelChunkWithLightPacket` 若已被混淆，Hassium 会**编码并缓存该包字节**；客户端 miss 全量请求时优先发送缓存字节，避免 `new Packet(LevelChunk)` 旁路混淆。
- **PlayerChunkSender / 无现成包路径**：在主线程按与原版相同构造路径构建一次包后再算 hash / 缓存，供后续 miss 复用。
- 若某反透视仅在 `Connection.send` 上改写且发生在 Hassium 取消之后，则仍可能旁路——此类实现需对方适配或关 Hassium 网络通道（`master.enabled = false`）。

## 4. Distant Horizons / Voxy

- DH 2.3+、Voxy 服务端伴生 mod 使用**独立 LOD / 自定义通道**，一般不依赖原版全量区块包，与 Hassium 区块链路正交。
- 风险：包聚合可能拖延其控制/数据通道。处理方式：
  - `master.enablePacketAggregation = false`，或
  - 将通道 ID 加入 `master.compressionBlacklist`（示例前缀，以实际包 ID 为准：`distant_horizons:`、伴生 mod 的 `namespace:`）。
- LOD 若经 `RegionFile.getChunkDataInputStream` 读盘：type 126 可由 Hassium Mixin 解压。若工具**裸解析 `.mca`** 且不认 126，会失败——属存档格式约束。

## 5. ViaFabric / 跨版本客户端

直连拓扑下 ViaFabric（ViaVersion 系）按原版路径工作：

| 拓扑 | 结论 |
|------|------|
| 服务端 Hassium + Via，客户端无 Hassium | **支持**：客户端走原版路径，Via 正常翻译原版协议；登录期握手空应答 → 服务端原版路径 |
| 双端 Hassium 但 MC 版本不同（靠 Via 桥） | **不承诺**：Hassium 能力协商假设双端同版本；跨版本场景未验证 |
| 服务端进程内 Via 与通道压缩 | 通道压缩仅对完成 Hassium 握手的玩家生效，未握手（含 Via 翻译目标）玩家走原版路径，无帧假设冲突 |

## 6. 热门优化 mod

Sodium / Iris / Lithium / FerriteCore / EntityCulling / ImmediatelyFast 等通常不抢 Region 压缩类型与区块广播接管。

常见摩擦：Hassium 引擎默认开启（进服启动影子端统一算光并回传官方光照包，客户端光照计算仍保持 vanilla 默认开启）。出现光照异常时关闭 `chunk.enabled` 即可（关闭后服务端不剥光，光照随包自带）。

## 7. C2ME

C2ME 与 Hassium 是「加速器与用户」关系：Hassium 影子端的 worldgen 走原版 `ServerChunkCache` /
`ChunkMap`，C2ME 的多核优化会**一并作用于影子端**（SeedGen 本地生成、缓存未命中加载）。

### 7.1 IO 三层，只有第三层需要处理

| C2ME 模块 | 配置键 | 默认 | 是否仍走 vanilla `RegionFile` |
|-----------|--------|------|------------------------------|
| `c2me-threading-chunkio` | `ioSystem.async` | true | 是 → Hassium 钩子全生效 |
| `c2me-opts-chunkio` | `ioSystem.chunkStreamVersion` | -1（不改） | 值为 -1 时对应 mixin 不加载，无影响 |
| `c2me-rewrites-chunkio` | `ioSystem.replaceImpl` | `globalExecutorParallelism >= 2`（多核机器默认**开**） | 否 → 见 §7.2 |

### 7.2 读天然兼容、写需接管（`ioSystem.replaceImpl`）

- **读已兼容**：`C2MEStorageThread.scheduleChunkRead` 调 `RegionFile.getChunkDataInputStream(ChunkPos)`
  （公开方法），Hassium `MixinRegionFile` 的 HEAD 注入正好覆盖 → type 126 可读。
- **写原本失效**：C2ME 自己拼 sector（`out.write(0)×4` → `out.write(format.getId())` →
  `format.wrap(out)` → `putInt(0, size-5+1)` → `invokeWriteChunk`），**绕过**
  `getChunkDataOutputStream`，而 Hassium 写钩子挂在后者 → type 126 永不产生；
  更严重的是影子端 `hassium_cache` 恒写 126，非 126 槽会被影子读侧拒绝
  （`ShadowChunkMapCompat.shouldSkipVanillaChunkParse`）→ 影子缓存永久 miss。

**接管方式（零重压，两个 hook 都落在 vanilla 类上，不引用 C2ME 内部类）：**

| hook | 位置 | 作用 |
|------|------|------|
| `MixinRegionFileVersion` | `RegionFileVersion.wrap(OutputStream)` HEAD | 压缩入口唯一汇合点；gate 放行时替换为 `HassiumPayloadStream`（从原始 NBT 一次性 ZSTD+字典压缩） |
| `MixinRegionFileWrite` | `RegionFile.write(ChunkPos, ByteBuffer)` HEAD | 所有写入者的唯一汇合点，且只有这里有坐标；按签名检测回填 type 126 与 chunkHash |

- 载荷长度沿用原版约定（length = 载荷 + 1），C2ME 与 vanilla 的 `size-5+1` 回填逻辑均无需改动。
- 签名检测（`0x48` + 全零 hash）保证对 vanilla 压缩槽与本 mod 已完成槽都是幂等 no-op。
- **零重压**：载荷在压缩入口即由 Hassium 直接 ZSTD+字典产出，`RegionFile.write` 只改 2 个头字段。

**门控**（与 `MixinRegionFile` 写路径同口径）：影子端恒接管；专用服需 `storage.enabled=true`。
无 C2ME 时两个 hook 由 `HassiumModCompatMixinPlugin` 整体跳过。

**逃生：** `storage.enabled = false`（默认已关；影子端不受该开关约束，故影子缓存始终受益）。

### 7.3 影子端：`RegionFile.write` 收编而非落盘（单写者）

影子存档（`hassium_cache/<serverId>/world`）的磁盘写者**必须只有** `ShadowStorageManager`
的 region 映像（整文件重写，按槽位重新紧凑扇区偏移）。原版 `RegionFile` 的 8KB 扇区表只在
内存维护、每次写入整表回写磁盘且从不重读——两边都写同一 `.mca` 时偏移互相错位：原版按旧
偏移读回别的柱 → `wrong location; relocating`，或把半截载荷当 type126 → ZSTD 解压失败。

因此 `MixinRegionFile` 在影子上下文把写入**收编进映像**，两条缝都堵：

- `getChunkDataOutputStream(ChunkPos)` HEAD（vanilla `ChunkBuffer` 路径）→ 返回收编缓冲。
- `RegionFile.write(ChunkPos, ByteBuffer)` HEAD → 收编后 `cancel()` 落盘。C2ME
  `ioSystem.replaceImpl` 绕过上一条缝，只走这条（§7.2）。

`cancel()` 同时短路挂在同一注入点的后续 HEAD 注入，故影子上下文里 C2ME 的 type126/hash
补丁不再执行（冒烟实测：2363 次影子写只放行 21 次补丁）。这是冗余而非缺失——收编路径已按
坐标归一化嵌入 hash，且映像落盘自己写 type 字节；该补丁只在专用服存储路径（非影子）保留
原有职责。**推论：`modCompat.type126Patched` 在影子上下文只作观测，不作门禁**（门禁改用
`c2meHookHits`，见 runtime-smoke-test §外部 Mod 手动冒烟）。

读侧同样改道映像（`ShadowSeedServer.loadFromDisk` 早已如此），否则过期扇区表仍会读出错位柱。
非 Hassium 载荷（vanilla zlib/gzip/lz4/none）不收编，回落原版写盘——单写者约束只为该柱让步，
不丢数据。需 >255 扇区的超槽位柱（>1 MiB 压缩载荷，Anvil 头 location 低 8 位表达不了）照收进
映像（本会话仍可服务），但 `RegionCache.Image.save` 整槽跳过：钳位会让 `arraycopy` 越界覆写后续
槽的数据，邻槽优先，该柱退化为下次会话 cache miss 重拉。

## 7b. Starlight / ScalableLux（外部光照引擎）

两者同源（ScalableLux `provides: ["starlight"]`，与 Starlight 互斥）：都在 `LevelLightEngine` 上
实现 `StarLightLightingProvider` 并整体替换光照引擎。

### 天然兼容（无需适配）

- **出光**：Hassium 走 `new ClientboundLightUpdatePacket(pos, level.getLightEngine(), masks)`，其内部
  `ClientboundLightUpdatePacketData.prepareSectionData` 用的正是公开入口
  `LevelLightEngine.getLayerListener(layer).getDataLayerData(sp)`，而它被替换为返回对方 reader
  → 回传给客户端的即是对方算好的光。
- `getMinLightSection` / `getLightSectionCount` / `tryScheduleUpdate` 未被覆写，语义不变。
- `chunkHash` 输入域是纯 BlockState（不含 LightData），故对方改存档 NBT 光标签不影响缓存身份。

### 已适配的 4 处控制面（`compat.mods.ForeignLightEngine`）

| 控制面 | 外部引擎下的失效原因 | Hassium 降级语义 |
|--------|----------------------|------------------|
| 清光 `queueSectionData` | 被覆写为 no-op | 跳过清光；调用方随后以 `lit=false` 全量重算（对方 `lightChunk` 会整柱覆写 nibble） |
| 重算入参 `lightChunk(chunk, lit)` | `lit=true` 只做 `forceLoadInChunk`，不重算 | 语义已由既有 `LightMetric` 决定：增量/重算路径本就传 `lit=false` |
| 收敛 `ThreadedLevelLightEngine.lightTasks` | 不再被填充（走对方自己的队列） | 跳过水位判定，只看 `hasLightWork()` |
| 天光源 `ChunkSkyLightSources` | `update` 被 `@Redirect` 成 no-op，数值过期 | 跳过 `getLowestSourceY` 子检查（天空层判空仍由 `getDataLayerData` 承担） |

**收益**：对方算光更快 → 影子端光照重算更快完成 → 区块更快在客户端显示。
ScalableLux 另引入 FlowSched 多线程算光，批处理多柱时收益更明显。

**逃生：** `chunk.enabled = false`（全程原版路径，含原版光照）。

## 8. 服务端备份

| 方式 | 结论 |
|------|------|
| 整文件 / 目录复制、zip、增量 blob（不解析 compression type） | **兼容**；126 对备份器透明 |
| 解压 chunk → 改 NBT → 再压，或只认 type 1/2/3 | **不兼容** |
| 回档后可读 | 需安装匹配版本的 Hassium（及字典） |

**InstantBackup**（文件级增量 + `RegionFileStorage` COW）：与 Hassium `RegionFile` Mixin 层级不冲突，可直接配合。可选加固（如检测到 Hassium 时 `chunk.full_hash=true`）在 InstantBackup 侧处理，**不在本仓库范围**。

## 9. 配置 GUI 与逃生口

| 模组 | 关系 |
|------|------|
| **Mod Menu**（Fabric） | 软兼容：单独安装即可打开 Cloth 配置屏 |
| **Cloth Config** | Fabric / Forge / NeoForge 均 **jiJ**；配置屏主路径 |
| **Configured** | Forge/NeoForge 可选；Fabric 不依赖 |
| **Forge Config API Port** | Fabric **不使用**（Night Config 自管 toml）；FCAP Forge 桥已随 Forge 1.20.6 退役；NeoForge 原生 Spec |

配置文件（双文件模型）：物理客户端读 `config/hassium/hassium-client.toml`；专用服读 `config/hassium/hassium-server.toml`（旧三文件 `common.toml` 模型已废弃）。

| 配置 | 作用 |
|------|------|
| `storage.enabled` | 关存档 type 126 |
| `master.enabled` | 关服务端网络通道 / 推送 |
| `master.enablePacketAggregation` | 关包聚合 |
| `master.compressionBlacklist` | 排除指定包 ID（第三方通道） |
| `chunk.enabled` | 关区块核心（影子端/缓存/Pull 模式；服务端不剥光，光照随包自带） |
| `chunk.sectionDeltaEnabled` | 关分段增量（过期改走全量） |
| `compat.requireClientMod` | 是否强制客户端装模组 |

## 10. Mixin refmap（`hassium.refmap.json`）

Mixin 在开发映射名与运行时混淆名之间需要对照表，构建时由 Loom 生成并打进发行 jar。

| 环境 | 行为 |
|------|------|
| 正式客户端 / 服务端 | jar 内带 refmap，正常解析注入目标 |
| `runClient` / Loom 开发 | 常已处于映射后环境；可能 WARN「refmap could not be read」——**可忽略**，一般不影响功能 |

排查真正的注入失败时，再核对目标方法名、`hassium.mixins.json` 登记与对应 MC sources。

## 11. 联测记录与待测清单

### 已冒烟（2026-07-19，Fabric 1.20.1）

环境：`fabric/run/client`，约 50 模组（FO 风格优化包：Sodium / Iris / Lithium / FerriteCore / C2ME / EntityCulling / ImmediatelyFast / Mod Menu / Cloth 等；**未**装 Bobby / ViaFabric / Immersive Portals）。

| 检查项 | 结果 |
|--------|------|
| 启动与进服 | 通过；握手 `accepted=true` |
| 客户端缓存 | 影子端存档 / heat / 断开清理正常 |
| 运行时统计 | `/hassiumc stats` 有压缩节省与缓存命中（示例会话约节省 83%、命中率约 73%） |
| `latest.log` 中 Hassium | 无 ERROR / Exception；仅有开发环境 refmap WARN（§10） |
| 第三方 ERROR | Debugify / CITResewn / Controlify 等与 Hassium 无关 |

本地联测目录：`fabric/run/client/mods`（勿把第三方 jar 提交进 git）。

### 仍建议覆盖

- [ ] 反透视 + Hassium 客户端：矿石仍应被混淆
- [ ] DH 双端 / Voxy+伴生：LOD 正常；必要时关聚合或加黑名单
- [ ] Via：无 Hassium 旧客户端能进服（登录期空应答 → 原版路径）
- [ ] C2ME **chunkio rewrite** 开/关与 `storage` 开/关对照
- [ ] Sodium + 影子端开/关（光照异常时）
- [ ] 文件级备份：热备份 → 改区块 → 导出还原 → 带 Hassium 进服可读
- [ ] Forge / NeoForge 同等优化包冒烟
