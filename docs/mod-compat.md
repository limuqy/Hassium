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
| Twilight Forest | **主动兼容**（见 §7c）：自定义维装配 + 影子端写入全局 `currentServer` 供 TF `getOverworldSeed` |
| 包聚合导致第三方包异常 | 关 `master.enablePacketAggregation`，或把**第三方**包 ID 加入 `master.compressionBlacklist`（Hassium 控制面已硬编码排除，与本列表无关） |
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
  - 将**第三方**通道 ID 加入 `master.compressionBlacklist`（示例前缀，以实际包 ID 为准：`distant_horizons:`、伴生 mod 的 `namespace:`；Hassium 控制面已硬编码排除，与本列表无关）。
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
无 C2ME 时两个 hook 由 `HassiumModCompatMixinPlugin` 整体跳过；放行时计
`ModCompatStats.compatArmed`（probe `modCompat.c2meCompatArmed`，strict P0 结构锚）。

**逃生：** `storage.enabled = false`（默认已关；影子端不受该开关约束，故影子缓存始终受益）。

### 7.2b 影子单写者主路径不经过 wrap（改造后）

影子端持久化主路径是 `ShadowStorageHashes` 标脏 → `ShadowStorageManager.flushDirty`
→ `HassiumType126Codec.encodeSector` → `RegionCache.Image.save` 整文件重写 **.mca**
（见 [`chunk-cache.md`](chunk-cache.md) §11.3）。该链 **不调用** `RegionFileVersion.wrap`，
也不经过 C2ME `C2MEStorageThread` 的自拼 sector。

因此：

| 信号 | 含义 | 关 seedGen（网络-only） |
|------|------|-------------------------|
| `c2meCompatArmed` | modcompat mixin 已放行 | **应 = 1**（结构） |
| `c2meChunkIoReplaced` | C2ME replaceImpl 类存在 | **应 = 1** |
| `c2meHookHits` | `wrap` 接管命中（C2ME/ChunkMap 写过 chunk） | **可 = 0（预期）** |
| `type126Patched` | `RegionFile.write` 头补丁放行 | 影子下被收编短路，只观测 |

仅当 SeedGen 本地 worldgen / 其它仍走 `ChunkMap`+C2ME 的写发生时，§7.2 的 wrap 钩子
才会产生 `c2meHookHits`。`modcompat_strict` 以 `c2meCompatArmed` 作防空测 P0，
**不再**要求 `c2meHookHits > 0`。

**C 级双写加固（已落地）**：影子上下文解析到 `ShadowStorageManager` 后，
`MixinRegionFile.adoptShadowWrite` **一律 `ci.cancel()`**，禁止 `RegionFile` 自身落盘。
载荷处理：

- `0x48`（wrap 已接管）：直接 `adoptEncodedColumn` 收编进映像；
- 非 `0x48`（C2ME wrap 未接管时的 zlib/gzip/none）：`HassiumType126Codec.reencodeVanillaToHassium`
  解压后重编码 type 126 再收编；
- 重编码失败：放弃该柱并记 ERROR（宁可 cache miss / 重拉，也不与 `Image.save` 双写撕裂 .mca）。

主兼容仍是 §7.2 的 wrap 钩子（产出 `0x48`）；本条是 wrap 失败时的兜底，不再回落 `RegionFile.write`。

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
原有职责。**推论：`modCompat.type126Patched` 在影子上下文只作观测，不作门禁**；
`modCompat.c2meHookHits` 同为流量观测（§7.2b），strict 结构门禁用 `c2meCompatArmed`。

读侧同样改道映像（`ShadowSeedServer.loadFromDisk` 早已如此），否则过期扇区表仍会读出错位柱。
非 Hassium 载荷（vanilla zlib/gzip/lz4/none）不收编，回落原版写盘——单写者约束只为该柱让步，
不丢数据。需 >255 扇区的超槽位柱（>1 MiB 压缩载荷，Anvil 头 location 低 8 位表达不了）照收进
映像（本会话仍可服务），但 `RegionCache.Image.save` 整槽跳过：钳位会让 `arraycopy` 越界覆写后续
槽的数据，邻槽优先，该柱退化为下次会话 cache miss 重拉。

## 7.4 OpenCL accel 模块（`c2me-opts-accel-opencl`）

C2ME 0.4.0 起的可选模块（`c2me-fabric-opts-accel-opencl-mc<ver>-*.jar`，`depends java>=25`），
用 OpenCL 加速世界生成。与 §7.1–§7.3 的 IO 三层无关，单独处理。

### 问题：影子端不走 `runServer` → 上下文恒为 null

C2ME 把 OpenCL 全局上下文初始化挂在 **`MinecraftServer.runServer()` 的 HEAD**：

| C2ME 位置 | 行为（字节码实证） |
|-----------|--------------------|
| `MixinMinecraftServer.preRunServer`（`@Inject(method="method_29741", at=@At("HEAD"))`） | 字段非空即抛 `Context already exists?`；否则 `new CLServerGlobalContext()` → 写实例字段 `c2me$clContext` → `OpenCLDeviceLocator.enumerateAll()` 逐设备 `openDevice` → 无设备且 `allowIncompatibilityFallback=false` 时抛 |
| `MixinMinecraftServer.postStopServer` | 字段非空则 `closeAllDevices()` 并清空 |
| `MixinThreadedAnvilChunkStorage.postInit`（= `ChunkMap.<init>`） | 经 duck 接口 `MinecraftServerExtension.c2me$getCLContext()` 读该字段；为 `null` 时 `logger.warn("World {} cannot use OpenCL since the global context is not initialized")` 后 **`throw new IllegalStateException`** |

Hassium 影子端 `initServer` **不走 `runServer`**（见 [`chunk-cache.md`](chunk-cache.md) /
`SeedGenLevelCompat.assembleShadowServer`），故客户端侧该字段恒为 `null`：

```
Failed to create shadow seed server
  java.lang.IllegalStateException: OpenCL global context is not initialized
      at net.minecraft.server.level.ChunkMap.handler$...$c2me-opts-accel-opencl$postInit
      at net.minecraft.server.level.ChunkMap.<init>
      at ... ShadowSeedServer.loadLevel → initServer → SeedGenLevelCompat.assembleShadowServer
```

专用服不受影响（走 `runServer`，实测 `Found OpenCL platform NVIDIA CUDA` + kernel 编译成功）。

**后果不止"没有加速"**：影子端创建整体失败 → `shadowServerFailed` → 客户端不再拦截原版包，
但真服 `pull_mode` 已压制原版整柱、且 `chunk_payload` 全量请求通道已退役 → **客户端
`ClientChunkCache` 为空**（实测 `loadedChunks=0` / `vanillaBytesReceived=0`）。见 §7.5。

### 接管方式（`compat/mods/C2meOpenClCompat`）

按 C2ME 自身语义为影子实例补齐上下文，**不引用 C2ME 内部类**（纯反射）：

| 调用点 | 行为 |
|--------|------|
| `SeedGenLevelCompat.assembleShadowServer`，`ShadowSeedServer.create(...)` 之后、`initServer()` 之前 | `armFor(server)`：反射取 `MinecraftServer` 上的 `c2me$clContext`；为空则新建上下文并写回，再 `enumerateAll()` → 逐设备 `openDevice` |
| `SeedGenLevelCompat.shutdown`，各维度 chunk 源关闭之后 | `releaseFor(server)`：`closeAllDevices()` + 清字段（对应 C2ME `postStopServer`；不释放会在反复重连时累积 GPU 上下文） |

**失败兜底**：全程不抛异常。类/字段/方法缺失、枚举失败、无设备 → 回滚字段 + 记 WARN，
影子端沿用 C2ME 原有的失败路径（无 OpenCL 时 C2ME 自己也会抛，语义不变）。
即"接管成功则可用，接管失败不比现状更差"。

**验证**：`1.21.1_fabric_I_cc_lux_fix1` / `1.21.11_fabric_I_cc_opencl25_fix1`
（c2me + ScalableLux + opencl，`JAVA_HOME=<graalvm-25>`，场景 `modcompat_strict`）。

**逃生：** 不装 `c2me-fabric-opts-accel-opencl-*.jar`（C2ME 主 jar 不含该模块），
或 `chunk.enabled = false`（全程原版路径）。

### 7.5 影子端创建失败的后果（尚未闭环，见 §11 待办）

`failShadowServer()` 只置降级态 + 游戏内提示（`ShadowServerRegistry`），**不通知真服撤销
`pull_mode`**：服务端 `MixinServerPlayer.trackChunk` / `MixinPlayerChunkSender` 仍按
`ServerHandshakeActivation.hasCaps(uuid, PULL_MODE)` 取消原版整柱，而客户端已无影子可 pull →
本会话空 `ClientChunkCache`。这是**通用**缺陷（任何影子端创建失败都会触发，不止 OpenCL），
`ShadowLightCompute` 的注释已记录同类症状（切维后空 `ClientChunkCache`）。
修法需要新增 C2S「影子不可用」通知 + 服务端撤销能力位并重推视窗（`ChunkMap.playerMap`
已记「已发送」，需绕过或重建），属协议级改动。

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

## 7c. Twilight Forest

TF 自定义维 `twilightforest:twilight_forest` 经 `SeedGenLevelCompat.resolveCustomDimensionStems`
本地 LEVEL_STEM 装配进影子端；`ModResourcePackCreator` 挂载见 chunk-cache 手记。

**seedGen 本地生成与 `WorldUtil.getOverworldSeed`**：TF Fabric 经 Porting Lib
`ServerLifecycleHooks.getCurrentServer()`（NeoForge 同名 hooks）取主世界种子。原版集成服由
`SERVER_STARTING` / `handleServerAboutToStart` 写入该静态槽；影子端 `initServer` 直接调用、
不走 `runServer`，多人客户端槽位恒为 null → `requireNonNull` NPE（HollowTree 等结构生成）。

Hassium 在 `SeedGenLevelCompat.assembleShadowServer` / `shutdown` 经
`compat.ServerLifecycleHooksCompat` **只写/清**该槽（空槽才写，不覆盖真服；不 fire 生命周期
事件）。冒烟：`tf` 场景 + `chunk.seedGenEnabled=true`，见 `1.21.1_fabric_I_tf_seedgen_re1`。

**逃生：** `chunk.seedGenEnabled = false`（TF 维退化为 pull，不本地生成）。

### 7c.1 切维后的跨维度投递（2026-09-19 修复）

**现象**：TP 进暮色森林后，TF 维度里出现**主世界区块**。

**根因**（日志实证，`client_1.21.1_fabric_I_cl_tf.log`）：影子端是**一个 MinecraftServer 带多个
ServerLevel**；投递点 `ShadowLightCompute.applyReadyChunk` 直接
`connection.handleLevelChunkWithLight(item.chunkPacket)` 落进**客户端当前 level**，而原版
`ClientboundLevelChunkWithLightPacket` / `ClientboundLightUpdatePacket` **不带维度字段**
（维度由 `ClientboundRespawnPacket` 切换的 level 隐含）。

代码里**已有** publish 门禁 `clientDimensionMismatch(resolved)`（`ShadowLightCompute:852`），
但它只挡**入队**：柱可能在 `ready` 队列里等到**客户端切维之后**才被投递 → 旧维度柱落进新维度。
证据：切维后旧维度仍在跑完光门（`[LIGHT_GATE] Promote ... dim=minecraft:overworld`），
`[CHUNK_APPLY] phase=shadow_attempt origin=remote_pull` 是该路径的实际投递出口
（`[SHADOW_BRIDGE]` 行数为 0，桥不是本场景的投递路径）。

```
[04:57:10] [SHADOW_TRACK] reseating virtual player minecraft:overworld -> twilightforest:twilight_forest
[04:57:10..04:58] 仍有 119 行 dim=minecraft:overworld（含 [LIGHT_GATE] Promote ... (no placeholders)）
```

**修复**：在**投递点**补同一道门 —— `applyReadyChunk` 开头
`if (clientDimensionMismatch(entry.key().dimension())) return true;`（丢弃且不重试，`true` = 可 release）。
另外给桥 `ShadowOfficialPacketBridge.forwardToRealClient(packet, sourceDimension)` 也加了来源维度参数
（防御纵深；三个桥出口各传 `player.level()` / `self.level()`）。

**丢弃是安全的**：影子虚拟玩家离开旧维度时会 `untrackChunk`，重进时重新 track → 重新投递
（与 `flyroundtrip` 覆盖的重入重交付同一条链）。

**验证口径**：`reseating` 之后日志中应出现
`[SHADOW_CHUNK] drop cross-dimension chunk ... (client switched)` 行
（`DebugLogger.CHUNK_APPLY` 在冒烟模式自动开）。

**实测（2026-09-19，`1.21.1_fabric_I_dimfix2_tf`，PASS）**：命中 **29 次**，全部
`dim=minecraft:overworld`——即修复前这 29 个主世界柱会被落进暮色森林维度。

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
| `master.compressionBlacklist` | 排除指定**第三方**包 ID（Hassium 控制面已硬编码排除） |
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

### 已冒烟（2026-09-19，Fabric，外部 Mod 矩阵；HEAD=e2965698）

环境：`fabric/run/{client,server}`，jar 见 §外部 Mod 冒烟矩阵；场景 `modcompat_strict`（带 mod 组）
/ `modcompat`（无 mod 基线）/ `tf`；结果文件 `build/smoke-test/results/result_*.json`。
**每场强制清档 + 清 C2ME 落盘 + 清 Hassium 配置残留**（口径见
[runtime-smoke-test.md](runtime-smoke-test.md) §外部 Mod 手动冒烟 → 清档要求）。

| 会话 | 版本 | Mod 集合 | JDK | 判定 | 说明 |
|------|------|----------|-----|------|------|
| `1.20.1_fabric_I_cl_lux` | 1.20.1 | c2me 0.2.0 + Starlight 1.1.2 | 21 | **PASS** | `c2meCompatArmed=1`、`foreignLightEngineActive=true`、零 ERROR |
| `1.20.1_fabric_I_cl_base` | 1.20.1 | 无 | 21 | **PASS** | 基线 |
| `1.21.1_fabric_I_cl_lux` | 1.21.1 | c2me 0.4.0-a0.27 + ScalableLux a0.7 | 21 | FAIL | 仅 `PROCESS_FATAL`：C2ME chunk system 在影子端 `Error upgrading chunk`（下节）；场景断言全过 |
| `1.21.1_fabric_I_cl_base` | 1.21.1 | 无 | 21 | **PASS** | 基线 |
| `1.21.11_fabric_I_cl_lux` | 1.21.11 | c2me 0.4.0-a0.26 + ScalableLux a0.3 | 21 | FAIL | 同 chunk system ERROR |
| `1.21.11_fabric_I_cl_base` | 1.21.11 | 无 | 21 | FAIL | 场景断言全过（`PASS` marker），但游戏 JVM 以 `0xC0000409` 退出 → `CLIENT_EXIT_NONZERO`；无 hs_err / crash-report |
| `1.21.1_fabric_I_cl_opencl` | 1.21.1 | + opencl accel | 25 | FAIL | 同 chunk system ERROR + 退出码 `0xC0000409` |
| `1.21.11_fabric_I_cl_opencl` | 1.21.11 | + opencl accel | 25 | FAIL | 同 chunk system ERROR |
| `1.21.1_fabric_I_cl_tf` | 1.21.1 | TF fabric 4.8.486 | 21 | FAIL（门禁噪音） | TF 自定义维场景断言全过；唯一 ERROR = Mod Menu `UpdateCheckerUtil` 的 `ConcurrentModificationException`（dev 环境外网更新检查，与本 mod 无关，allowlist 未覆盖） |
| `1.21.1_neoforge_I_cl_tf` | 1.21.1 | TF universal 4.8.3345 | 21 | FAIL | 受 `seedGenEnabled` 残留污染（`locallyGenerated=2418`、`ClientChunkCache` 空） |
| `1.21.1_fabric_I_cl_tf2` | 1.21.1 | TF fabric 4.8.486 | 21 | **PASS** | 钉死 `seedGenEnabled=false` + 清档后复跑；Mod Menu 噪音未复现 |
| `1.21.1_neoforge_I_cl_tf2` | 1.21.1 | TF universal 4.8.3345 | 21 | **PASS** | 同上；确认前一格 FAIL 是配置残留而非产品缺陷 |
| `1.21.1_fabric_I_dimfix2_tf` | 1.21.1 | TF fabric | 21 | **PASS** | 跨维度投递修复复验：`[SHADOW_CHUNK] drop cross-dimension` 命中 29 次（全部 overworld） |

**OpenCL accel（§7.4）修复前后对照**：`1.21.1_fabric_I_cc_lux`（修前）影子端创建失败、
`loadedChunks=0`；`1.21.1_fabric_I_cc_lux_fix1`（修后）`C2ME OpenCL 上下文已为影子端初始化`、
`loadedChunks=1529`、`c2meHookHits=0→4310`。剩余 FAIL 仅为下述 chunk system ERROR。

**C2ME chunk system 在影子端的 ERROR（1.21.1/1.21.11，带 c2me 即出现，与是否 OpenCL 无关）**：

```
[hassium-seedgen-main/ERROR] Error upgrading chunk [3, -8] to "minecraft:full, Border, Chunk Sending"
java.lang.IllegalStateException: Should always be able to create a chunk!
  at net.minecraft.world.level.Level.getChunk(Level.java:210)      # getChunkSource().getChunk(...) 返回 null
  at net.minecraft.world.level.Level.setBlock
  at com.ishland.c2me.rewrites.chunksystem.common.statuses.ServerAccessibleChunkSending...upgradeToThis$1$1
```

根因（字节码级）：C2ME `c2me-notickvd` 的 `MixinServerAccessibleChunkSending.upgradeToThis` 实现了
`Config.suppressGhostMushrooms`（MC-276863 的 workaround，默认 `true`）——在「Chunk Sending」状态升级时
对扫出的「幽灵蘑菇」位置调 `ServerLevel.setBlock(pos, AIR, 20)`；影子端 `ChunkSource` 不提供按需
`getChunk(FULL, true)`（worldgen 压制）→ 该柱升级失败。**影响**：C2ME 自己的 `sendChunkToPlayer`
对这些柱不生效（Hassium 走自有交付路径，故场景断言仍全过），但 ERROR 行触发日志审计门禁。
**逃生口**：C2ME 侧 `config/c2me.toml` 的 `suppressGhostMushrooms = false`（会放弃 MC-276863 workaround）。
**历史对照**：9/18 同 mod 集合的 strict 场次有同样的 10/21 条 ERROR，却因审计时日志尚未落盘而误判 PASS
—— 门禁口径本身待加固（见待办）。


### 仍建议覆盖

- [ ] §7.5 影子端创建失败 → 空 ClientChunkCache（协议级修复）
- [ ] C2ME chunk system × 影子 `ChunkSource` 的 `Error upgrading chunk`（影子端是否需支持按需造柱）
- [ ] 1.21.11 客户端 JVM `0xC0000409`（场景已 PASS 却判 FAIL）。**根因已定位（2026-09-19）**：
  断连/退出拆除期会**凭空新建一台影子端**——`ShadowLightCompute.drainLightMasks` 在
  `lightUpdates` 非空时**先** `ShadowServerRegistry.getOrCreate()`，**后**才判
  `connection == null` 丢弃；于是队列残留光更新触发一次无人使用的新装配。日志实证
  （`1.21.1_fabric_I_dimfix2_tf`，05:21:24）：`disconnecting` → `shadow shutdown waited 19ms`
  → `Client disconnected` → **同一秒 `Shadow packs available` → `Shadow seed server started`**
  → 1s 后才 `reconnecting`；R2 因此撞上「还没有 TF 维」的半成品实例
  （`[SHADOW_TRACK] shadow level not assembled for twilightforest:twilight_forest; session deferred`）。
  退出期同理：`mc.stop()` 期间再起一台装配，冒烟 2s 后强退 → JVM 异常退出
  （seedgen 单轮无 R2 也崩，证明不需要 R2 就能触发）。
  **修法探索（已试，回退）**：把断连判断前移到 `getOrCreate()` 之前（`drainLightMasks` 4 行重排）
  确实让「断开窗口内不再出现 `Shadow packs available` / `Shadow seed server started`」（口径 1 通过），
  但 **R2 直接变全黑**——对照实测：改前 R2 `applied` 1162/1236/1128（fabric/neoforge 三场），
  改后 **0**，且 `[SHADOW_TRACK] shadow level not assembled for twilightforest` 从零星涨到 **910 行**。
  **即断连拆除期那次「多余」的创建，实际上是 R2 复用实例的来源**（park/reuse 语义）。
  结论：这不是「顺手删掉一个多余创建」就能解决的问题——**须先理清 R2 的创建/复用路径**
  （谁在 R2 登录时创建、TF 维何时装配、park 实例如何跨会话复用），再决定断连期该不该创建。
  该改动已回退（`ShadowLightCompute.drainLightMasks` 恢复原顺序，仅留注释）。
- [ ] TF NeoForge：`applied` 计数与 `ClientChunkCache` 不一致
- [ ] 冒烟日志审计与客户端日志落盘的竞态（ERROR 可能漏判）
- [ ] 反透视 + Hassium 客户端：矿石仍应被混淆
- [ ] DH 双端 / Voxy+伴生：LOD 正常；必要时关聚合或加黑名单
- [ ] Via：无 Hassium 旧客户端能进服（登录期空应答 → 原版路径）
- [ ] C2ME **chunkio rewrite** 开/关与 `storage` 开/关对照
- [ ] Sodium + 影子端开/关（光照异常时）
- [ ] 文件级备份：热备份 → 改区块 → 导出还原 → 带 Hassium 进服可读
- [ ] Forge / NeoForge 同等优化包冒烟

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
