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
| Lomka 0.7.0 | **主动兼容**（光照注入优先级；见 §6.1） |
| C2ME | **主动兼容**（读天然兼容 + 写侧接管，见 §7） |
| 文件级服务端备份（含 InstantBackup） | **兼容**（见 §8）；语义级解压 Anvil 的工具不兼容 |

## 2. 侵入面摘要

Hassium 主要改动：

1. **存档**：`RegionFile` payload type **126**（ZSTD+字典），`.mca` 外壳不变
2. **区块下发**：登录期能力握手 + Play 期激活后，服务端原版 tracking 推送 / 影子端统一 ShadowPull；全部以**原版包形态**走 vanilla TCP
3. **网络**：聚合包内部字典 ZSTD + 区块推送自有压缩 + 可选包聚合——均不触碰原版压缩层；未握手客户端零干扰

**第三方可见性**：Hassium 私有 payload（`hassium:*`）对第三方按原版 CustomPayload 可见、可拦截；区块数据本体以原版 `ClientboundLevelChunkWithLightPacket` 形态交付。

未握手 / 无 Hassium 客户端仍走原版 `ClientboundLevelChunkWithLightPacket`（`compat.requireClientMod` 默认 `false`）。

### 聚合 v3 与第三方自定义包

v3 在实际加载器 codec 完成编码后收取完整原生字节，客户端回灌实际绑定的 decoder；不构造 `RawCustomPayload` 替代第三方类型。自动模板按通道、长度及常量分组，结构改变可直接传原文，详见 [egress-v3.md](egress-v3.md)。

NeoForge `GenericPacketSplitter` 会先编码检查大小；`MixinNeoForgePacketSplitter` 配合 `NeoForgeEncodedPacketReuse` 复用这一编码结果，避免未分片包再次调用任意 Mod codec。仅在 NeoForge 且已激活的客户端下行 PLAY 连接中生效，校验包对象与实际协议绑定一致；已分片或无法可靠关联时放弃缓存，沿用加载器结果。

开发专项 `egress_fixture:typed_state` 在六组合验证同通道 State/Event 具体类型、注册表 holder、变长字符串和列表、原始 codec 单次调用、顺序、一次性执行、Bundle 和重连。这证明加载器原生 codec 恢复路径；不将历史第三方 Mod 的旧版联测记录冒充当前 26.x 已验证名单。

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

### 6.1 Lomka 0.7.0

Lomka 用默认优先级 1000 覆写 `LayerLightSectionStorage.setStoredLevel`。Hassium 的
`MixinLayerLightSectionStorage` 原来也使用 1000，Mixin 会拒绝向同优先级的覆写方法注入
`hassium$nullSafeSetStoredLevel`，导致客户端进服时崩溃。`chunk.seedGenEnabled=false`
同样受影响，因为影子端缓存与光照仍需装配该类。

Hassium 将这个 Mixin 的优先级设为 **1100**，使原有局部变量空值保护可以注入 Lomka
的替换方法。读光照保护、写光照两个分支的保护和 Lomka 的收集器缓存均保留；不关闭
Lomka 优化，也不移除 `LightNeighborhoodGate` 或改动交付域。

兼容范围按实际共装冒烟记录确认，不将这些旧版 JAR 的结果外推到其他 MC 版本。

2026-10-16，在 `2.0.0` 分支基线 `493a3d64` 加上述修复后，使用用户提供的两份
Lomka 0.7.0 成品 JAR（经 Loom 转为开发环境映射），双端保留 Lomka 默认开启的优化：

| MC / 加载器 | seedGen 关闭（classic 两轮） | seedGen 开启（seedgen 冷场） | 开启组本地生成次数 |
|---|---|---|---|
| 1.20.1 / Forge 47.4.21 | PASS | PASS | 1643 |
| 1.21.1 / NeoForge 21.1.236 | PASS | PASS | 1576 |

开启组 `authoritativeRequests=0`，关闭组 `locallyGenerated=0`；四场均客户端退出码 0、
无探针失败或 P1 告警。结果会话名为 `lomka_070_{1201_forge,1211_neoforge}_{seedoff,seedon}_fixed`，
JSON、probe、双端日志与配置位于验证用隔离工作树的 `build/smoke-test/`。

最初的额外 `MoveSeconds=10` 记录**没有实际飞行**：2.x Forge/NeoForge 开发启动配置
漏传客户端 JVM 的 `hassium.smokeTest.moveSeconds`，Java 侧 `fly seconds=0` 跳过；
这些旧结果不作为移动验收。其 R1 累计注入 1705、最终 ready/applied/actualPresent
各 1529 的 176 差集全部在最终 VD20 形状外，属窗口中心下的计算候选；原分析器将报告
截为前 64 个坐标再判光环，既误报，又可能漏掉第 65 个真正的窗内缺口。

2.1.1 修复 Forge/NeoForge 冒烟飞行参数透传。客户端先请求服务端 TP 到安全高空
（本轮 y=256，重连后 y=272），确认客户端实际到达后才独立计时 10 秒水平飞行；
前进输入每 tick 维持，未到达高空、没有 Java TP/起点/终点日志、或 TP 后没有水平跨区块
位移均判 P0。新增真实共装移动回归（2026-10-16）如下；classic 两轮，seedgen 单轮：

| MC / 加载器 | seedGen 关闭 classic | seedGen 开启 seedgen | Java 水平 z 位移 |
|---|---|---|---|
| 1.20.1 / Forge | PASS（R1+R2） | PASS（R1） | 0→202、213→383；开启场 0→124 |
| 1.21.1 / NeoForge | PASS（R1+R2） | PASS（R1） | 9→199、210→390；开启场 1→107 |

四场客户端退出码均 0，开启场本地生成分别 1616/1567 柱且
`authoritativeRequests=0`。会话名 `lomka_070_{1201_forge,1211_neoforge}_{seedoff,seedon}_tpflight2_211`，
配置、结果 JSON、probe、双端日志在验证用隔离工作树 `build/smoke-test/`。
分析器现在使用全量 trace 集合；classic R1 只有最终实测驻留精确覆盖完整 VD20
形状且差集全在最终交付域外时，才将累计候选记 INFO。R2 缩为 VD10 时仍有 OVD16
方窗，任何权威形状或 OVD 有效带内的未 ready 柱保持 P0；窗外的 cache-only 残差
保留原有 P1 诊断。

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
  断连/退出拆除期会**凭空新建一台影子端**——原 `ShadowLightCompute.drainLightMasks` 在
  `lightUpdates` 非空时**先** `ShadowServerRegistry.getOrCreate()`，**后**才判
  `connection == null` 丢弃；于是队列残留光更新触发一次无人使用的新装配。日志实证
  （`1.21.1_fabric_I_dimfix2_tf`，05:21:24）：`disconnecting` → `shadow shutdown waited 19ms`
  → `Client disconnected` → **同一秒 `Shadow packs available` → `Shadow seed server started`**
  → 1s 后才 `reconnecting`；R2 因此撞上「还没有 TF 维」的半成品实例
  （`[SHADOW_TRACK] shadow level not assembled for twilightforest:twilight_forest; session deferred`）。
  退出期同理：`mc.stop()` 期间再起一台装配，冒烟 2s 后强退 → JVM 异常退出
  （seedgen 单轮无 R2 也崩，证明不需要 R2 就能触发）。
  **⚠ 该触发路径已消失（2026-09-19 光桥删除）**：`drainLightMasks` / `lightUpdates` 随光桥整体删除
  （见 [`client-chunk-light-flow.md`](client-chunk-light-flow.md) §7.2），故「队列残留光更新 → 断连期
  `getOrCreate()`」这条链不复存在。**但症状是否消失未验证**——需在删光桥后的树上重跑
  `1.21.1_fabric_I`（1.21.11 / NeoForge）确认 `0xC0000409` 与断开窗口内 `Shadow seed server started`
  是否同时归零。若仍复现，说明另有创建路径。
  **修法探索（已试，回退）**：把断连判断前移到 `getOrCreate()` 之前（4 行重排）
  确实让「断开窗口内不再出现 `Shadow packs available` / `Shadow seed server started`」（口径 1 通过），
  但 **R2 直接变全黑**——对照实测：改前 R2 `applied` 1162/1236/1128（fabric/neoforge 三场），
  改后 **0**，且 `[SHADOW_TRACK] shadow level not assembled for twilightforest` 从零星涨到 **910 行**。
  **即断连拆除期那次「多余」的创建，实际上是 R2 复用实例的来源**（park/reuse 语义）。
  结论：这不是「顺手删掉一个多余创建」就能解决的问题——**须先理清 R2 的创建/复用路径**
  （谁在 R2 登录时创建、TF 维何时装配、park 实例如何跨会话复用），再决定断连期该不该创建。
  该改动已回退。
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

## 11. HMCL 完整整合包联测（2026-10-19 开始）

`2.0.0` 工作区（当前产品版本 `2.1.1`）已接入用户本机 HMCL 中的 11 个完整包，
排除 Hassium Community Reconstruction R3 和四个仅启用少量 Mod 的单测实例。
1.20.1：Fabulously Optimized、XPlus、BMC4、All the Mods 9、VerityCraft；
1.21.1：Pixelmon、All of Create、ATM 3.0.2、BMC5、Tensura、All the Mods 10。

入口为 `scripts/runtime-modpack-compat-test.ps1`（PowerShell 7）。首次需先有项目冒烟生成的
同版本/加载器 `parity_*` 存档；夹具只取项目测试存档的 `level.dat`，重新生成区块。
所有 Mod 和包配置复制到 `build/modpack-compat/<包ID>/client`，原 HMCL 实例只读，
不复制个人存档，不自动启用 `.disabled` 文件；已有 Hassium 替换为当前工作区成品 JAR。
`setup.json` 记录包内 JAR 哈希、Hassium 哈希、源码提交及替换项。

```powershell
.\scripts\runtime-modpack-compat-test.ps1 -Action setup
.\scripts\runtime-modpack-compat-test.ps1 -SkipBuild -Packs fo1201,pixelmon,bmc4
.\scripts\runtime-modpack-compat-test.ps1 -Action status
```

本轮首阶段为完整客户端启动 → 单人测试世界 → 等待 15 秒 → 实测区块驻留及非空气地形 → 请求退出。
`PASS_INTEGRATED` 要求退出码 0、原有冒烟 PASS marker、JSON 探针、驻留区块 >64、
周围非空气区块 >0；**不代表多人握手、影子端缓存、重连、移动光照通过**。
诊断 javaagent 仅移除既有冒烟入口的开发环境限制，保留显式 JVM 属性开关；
测试 JAR 只新增场景文本，所有游戏逻辑 class 与工作区成品逐字节一致。
Pixelmon 隔离副本设 `starter-on-join=false` 延后初始选择界面，避免该界面暂停内置服；
覆盖项记录在 `setup.json`。每次运行创建新的测试世界，保存/退出兜底强退信息另记结果 JSON。
大包 harness 设置 `hassium.smokeTest.gracefulStopGraceMs=60000`，等待正常保存和退出；
原有默认仍为 10 秒，可配置上限 120 秒，退出码 0 门禁保持不变。

2026-10-19 首轮 11 包均完成上述阶段。ATM10 初场已驻留 453 柱、9 个采样柱有非空气地形，
但保存期间被 10 秒退出兜底打断，进程返回 `0xC0000409`，该场保留 FAIL。
仅增加冒烟退出宽限的构建在 60 秒设置下复测：保存约 22 秒，正常退出码 0、未触发强退，
结果为 PASS_INTEGRATED。两份 NeoForge 成品逐 class 比对，只有 `ScenarioEngine.class` 改变。
该复测位于 `build/modpack-compat-exitwait/atm10`，主报告通过 `setupFile` 指向其准确构建。
VerityCraft 另修复测试中止留下的 GML 映射 ZIP 截断后通过；未更改第三方 Mod。

原始日志与每次运行结果在 `build/modpack-compat/<包ID>/runs/<时间>/`；
实时汇总为 `build/modpack-compat/report.md` 和 `summary.json`。
创建 `build/modpack-compat/STOP` 可请求当前测试及队列停止；harness 仅清理自己启动的 JVM。
重新执行 `setup` 复用已有副本，**不会**自动更新已记录的 Hassium；换构建应选新的 `--root`。


### 客户端连接专用服阶段

用户要求的联测拓扑为完整包客户端 ↔ 同包专用服，使用 `-Stage dedicated`：

```powershell
.\scripts\runtime-modpack-compat-test.ps1 -Stage dedicated -Action setup
.\scripts\runtime-modpack-compat-test.ps1 -Stage dedicated -SkipBuild -Packs fo1201,pixelmon
.\scripts\runtime-modpack-compat-test.ps1 -Stage dedicated -Action status
```

独立目录为 `build/modpack-dedicated/<包ID>/{client,server}`。客户端保留全部原包启用的 JAR，
服务端仅排除清单声明不支持服务端、客户端元数据、显示/输入/渲染及 LAN 共享组件；
`dedicated-setup.json` 逐项记录文件、Mod ID 和排除理由。原实例和个人存档只读。
服务端离线模式仅绑定 `127.0.0.1`，端口默认 25579；使用与原包相同的加载器版本。
Forge/NeoForge 服务端由对应官方安装器生成运行库，Fabric 使用原始专用服 JAR。

双端安装同一工作区成品，测试 JAR 只附加场景文本。诊断 agent 在两端启用显式冒烟属性。
SeedGen 和存档压缩关闭，服务端初始 VD=12、客户端 RD=16；首轮驻留 90 秒后断连，
服务端切至 VD=8 并注入原有冒烟石墙，重连后驻留 60 秒。
门禁要求两轮均为非内嵌服、非内存连接、影子端运行；驻留区块 >64、非空气采样 >0；
两次能力激活，配置启用聚合时有两次聚合激活，R2 缓存命中 >0，双端正常退出。
包原配置或兼容策略关闭聚合时明确记录，不强制覆盖已有兼容设置。
这轮没有检验移动中洞口/屋檐光照，不能作为该项验收证据。

原有重连工具曾通过 Mojmap 字符串反射 Minecraft 类，在 Fabric 成品环境报
`ClassNotFoundException: ServerAddress`。现改为 `SmokeConnectCompat` 的编译期类型调用，
由构建工具重映射，版本签名差异收口到 compat；实际游戏连接流程保持原版。

每场原始日志、两轮探针和结果在 `dedicated-runs/<时间>/`，汇总为 `dedicated-report.md`
和 `dedicated-summary.json`。旧失败和中止场保留；创建根目录 `STOP` 文件可终止队列。


诊断对照使用独立根目录，明确标记 `PASS_CONTROL`，不并入完整功能通过数：

```powershell
python scripts/modpack-dedicated.py run --control --packs pixelmon --port 25580
```

对照保持完整客户端及同样的专用服 Mod 清单，仅将客户端区块核心关闭；场景验证两轮
原版 TCP 连接、地形驻留与正常退出。关闭区块核心时部分加载器不会发送 `play_init`，
故握手/聚合和缓存命中不属于此对照的通过条件。
服务端准备的额外绕过项（Verity 的 GML、ATM 3.0.2 的 Palladium）单独记录在排除清单，
不得误称为原始服务端全部 Mod 同时通过。内容 Mod 保留；不能通过移除内容 Mod 获得 PASS。


### 故障优先与 Forgified Fabric API 回归

专用服 harness 默认在第一个失败后退出队列。先修复故障，在新 `--root` 中以一个已失败包
完成目标回归，再扩展到其他包；仅显式传 `--continue-on-failure` 才连续盘点失败。
每个根目录固定其成品哈希，禁止把旧副本直接当作新构建复测。

1.21.1 的原有 RecipeManager 保留策略只检测 Fabric ID `fabric-resource-loader-v0`；
Forgified Fabric API 在 NeoForge `ModList` 中注册的是 `fabric_resource_loader_v0`。
因此 All of Create、ATM 3.0.2、BMC5 的影子端把 RecipeManager 误剪掉，触发
`No RecipeManager found in listeners!`。修复仅补齐该模块 ID 的识别，保持既有加载器策略：
检测到对应资源加载器才保留 RecipeManager。回归新增两个真实 ID 及无此模块的单测。
`common:test` 1.21.1 共 441 项通过；1.20.1、1.21.11 编译及版本边界检查通过。
目标运行根目录为 `build/modpack-dedicated-recipe-fix`；新旧 NeoForge 成品只有
`ShadowReloadListenersCompat.class` 的字节发生变化。

All of Create 首个目标回归已通过：两次能力/聚合激活，R1 驻留 721 柱、非空气采样 9 柱，R2 缓存命中 290，双端退出码 0、未兜底强退。旧失败场与新构建场分别保留。


ATM 3.0.2 在 RecipeManager 修复后继续暴露 Mekanism `Current server is null`：
世界加载事件早于原有 `adoptCurrentServer`。将仅空槽登记移到 `initServer` 前，保留
不覆盖真实集成服/专用服的 CAS 保护；初始化异常路径同时释放本实例，避免留下死服务端引用。
目标回归根目录 `build/modpack-dedicated-lifecycle-fix`，先验证 ATM 3.0.2 后才继续其他包。


ATM 3.0.2 和 BMC5 已在 currentServer 提前登记构建下完成两轮 PASS，R2 缓存命中分别 246/282。
Pixelmon 另修 Essential 1.4.1.1 对 GameRules.Value 回调中服务器的错误集成服强转：
可选兼容 mixin 在 Essential 回调合并后，仅给该回调添加 ShadowSeedServer 保护，
不取消原版 gamerule 通知；ASM 回归测试实际加载变换后的类，验证非影子及其他回调保持执行。

Pixelmon 的共享玩法表重载是另一根因。其 `PixelmonLoaders.addReloadListeners` 会注册
清空/重建招式、物种、掉落、配置等全局表的任务。影子 WorldLoader 触发同一批任务，
与登录期物种解码、JEI 配方注册并发，分别复现 HashMap/ArrayList 的 ConcurrentModificationException。
仅在显式 ShadowAssemblyScope 中跳过 Pixelmon 的该注册回调；正常客户端同步、
集成服/专用服 datapack 重载和原版 tag 流程保持正常。单测验证作用域退出后不泄漏。
目标回归根目录 `build/modpack-dedicated-pixelmon-isolation`。

Pixelmon 目标回归已完成 PASS_DEDICATED（20261019_145246）：两轮真实 TCP 连接，
R2 缓存命中 301，双端正常退出；完整日志未再出现上述强转或并发修改异常。
原包 JEI rock_smash 分类缺失在区块核心关闭对照中也出现，独立保留为包自身问题。

ATM9 的 MineColonies `CrafterRecipeListener.apply` 同样会重置全局殖民地配方表；
影子端不消费该表，且客户端不一定具备专用服 KubeJS 添加的物品，重载会因空 ItemLike 失败。
可选兼容钩子仅在显式影子装配作用域取消该 apply；真实世界重载及原版 tag 保持执行。
回归单测同时覆盖包装 executor 中的作用域传播及退出后的真实重载。

ATM9 下一场在登录时被出口 v3 的 8 MiB 限额断开：聚合尚未 ACK，原生同步字节也被
当成等待聚合的 backlog。无前序任务的原样直发现在直接交给 Netty，保留原生包的大小限制；
有前序压缩/解压或协议切换时仍排队并受 8 MiB 硬限约束。单测覆盖激活前后大包直发、
未完成原生 promise 不占聚合预算、接收端旁路以及不得越过在途帧。1.20.1 共 454 项通过。
双端日志均纳入立即停止门禁，出口 v3 错误也会停止本场与队列。

`build/mp-f6/atm9` 出口修复回归完成两轮 PASS_DEDICATED：R2 缓存命中 251，
双端退出码 0，无 ConcurrentModificationException、影子创建失败或出口 v3 错误。
Forge 1.20.1 握手的 `GameData.injectSnapshot` 会替换共享注册表，连接界面投机装配
可能与该过程并发（曾出现连原版 diamond 工具都暂时解析不到的日志）。因此补充
`ShadowStartupCompat`：该加载器等到原生 handleLogin 建立 ClientLevel 后才装配；
其他版本/加载器的投机启动不变。最终候选回归根目录 `build/mp-f7`，同包关闭核心
对照根目录 `build/mp-c7`；二者使用不同本机端口，不测试其他包。

上述两场并行运行在重连 JEI 索引阶段被用户报告卡死，均中止并保留 STOPPED。
现场可用物理内存仅约 1.8 GiB；两个客户端工作集各约 12 GiB，两个服务端各约 10 GiB。
两份主线程转储均为 RUNNABLE，分别位于 JEI 提示索引/Silent Gear、MineColonies 配方枚举，
未报告 Java 死锁；不能把这两场计为最终候选通过。
修复测试调度：单人/专用服入口共享工作区运行互斥锁，对照也必须串行；独立进程验证了
锁占用时拒绝第二场、释放后可重新获取。专用服 harness 记录最低可用内存，低于 4 GiB
立即抓转储并停止。后续只同时运行一个客户端和其专用服。
中止对照已在关闭区块核心时复现属性修饰器重复、Utilitix/Generator Galore JEI 分类缺失；
这些包自身问题与核心运行门禁分开记录，对照本身没有完成双轮，不标 PASS_CONTROL。

ATM9 最终候选串行复测 `build/mp-f7/atm9/dedicated-runs/20261019_152523` 已完成
PASS_DEDICATED：两次能力/聚合激活，R2 缓存命中 246，双端退出码 0；
最低可用物理内存 28,843,573,248 bytes（约 26.9 GiB）。重连 JEI 索引阶段曾短暂无响应，
随后恢复并于 15:32:17 再次进入世界；15:32:22 转储主线程已在实体渲染，保持响应并完成
60 秒驻留。没有把加载阶段暂时的主线程阻塞误判为 Java 死锁，也没有删除 JEI 来通过测试。

BMC4 在同一最终 Forge 成品下完成 PASS_DEDICATED（`build/mp-f7/bmc4`），
两次能力/聚合激活，R2 缓存命中 352，双端正常退出。服务端排除客户端 Particle Effects 后
解决原有启动阻断，完整客户端仍保留该 Mod。

Verity 的无 Hassium 服务端对照也曾在 Undergarden 自动订阅时因 PoseStack 失败。
新增显式诊断属性仅记录 Forge 正在检查的订阅类及 Entity 的接口形状，未改变事件行为。
实测落点为 Dweller，但真正来源是 Perception 0.1.4 的通用 EntityMixin：Entity 实现
ITrailConfigProvider → OctoLib TrailProvider → RenderProvider，最后者公开 render(PoseStack, …)。
Forge getMethods 检查 Dweller 的继承方法时解析客户端类型而失败。Perception 元数据说明为
视觉特效，因此只从专用服副本排除并记录精确原因，客户端及 Undergarden 内容保留。
目标新夹具根目录 `build/mp-f8/verity`；旧失败保留，不改写为通过。

Verity 开服修复后在首连暴露 Deep Void 1.99.2.1 的 CultTemplePlacementProcedure：
LevelEvent.Load 调用其私有 execute(Event, LevelAccessor)，会在新世界主动放置神庙并写入
全局变量；影子端关闭地形生成时无法为该放置创建区块，导致整个装配失败。
可选客户端钩子只对归属 ShadowSeedServer 的 LevelAccessor 取消该过程，保留真实服生成，
缓存仅接收权威区块。回归直接执行实际注入处理器，分别传真实专用服、影子端和客户端
世界代理，并禁止处理器访问其他世界数据；只有影子世界被取消。
1.20.1 单测 457、1.21.1 单测 450 全通过，1.21.11 编译及版本边界扫描通过；
新构建目标根目录 `build/mp-f9/verity`，日志已确认跳过影子世界的放置回调。

VerityCraft 目标回归 `20261019_154901` 已完成 PASS_DEDICATED：R2 缓存命中 275，
两次能力/聚合激活，双端退出码 0，最低可用内存约 29.2 GiB。
Perception 保留于完整客户端，Undergarden 和 Deep Void 内容模组双端保留；
专用服仅排除已审计的客户端组件和单列的 GML 语言提供器。

Tensura Neo Otherworld 在同一最终 NeoForge 构建下完成 PASS_DEDICATED
（`build/mp-f9/tensura`，20261019_155501），R2 缓存命中 288，双端正常退出，
最低可用物理内存约 34.8 GiB。专用服移除错误混入的客户端通知、粒子等组件后正常开服；
完整客户端仍保留这些组件。

ATM10 在同一最终 NeoForge 构建下完成 PASS_DEDICATED（`build/mp-f9/atm10`，
20261019_155953）：两次能力/聚合激活，R2 缓存命中 318，双端退出码 0，
最低可用内存约 25.3 GiB，未触发退出兜底。专用服排除 Dark Mode Everywhere 等客户端组件。

截至 2026-10-19 16:05，11 个目标包各自最新运行均为 PASS_DEDICATED（1.20.1 五包、
1.21.1 六包）。跨阶段总览为 `build/modpack-dedicated/overview.md` / `overview.json`，
`status` 会重建总览。各包按故障优先顺序在不同修复构建下完成回归，成品哈希逐场保留，
**并非同一个最终 JAR 再次跑完全部 11 包**。历史失败、中止以及关闭核心对照均独立保留。

最终只读审计 `build/modpack-dedicated/final-source-readonly-audit.json`：2761 个原始第三方
JAR 与其最新客户端副本哈希均未变化；每场固定成品哈希匹配，双端测试 JAR 的全部游戏
逻辑 class 与其固定成品逐字节相同。ATM 3.0.2 单列的 Palladium 是 Mr.Toad 优化组件
（去重、着色器缓存与内存控制），不是同名的英雄玩法框架；Toadlib 为其配套依赖。
当前单测为 1.20.1 的 457 项和 1.21.1 的 450 项全部通过，1.21.11 编译、版本边界扫描
与差异空白检查通过。测试进程均已退出；没有验收移动中的屋檐/洞口光照或全部模组玩法。

## 12. SeedGen 关闭时的缓存装配隔离（2026-10-19）

保留影子端的原版区块、票据、存储与光照实现；`chunk.seedGenEnabled=false` 时使用独立的
缓存装配入口 `ShadowCacheBootstrap`。不增加自动降级或第三方 Mod 自动禁用。

- 等原生登录建立 ClientLevel 后，复用该连接同步的注册表、标签和维度类型；不再运行
  WorldLoader、启用本地 Mod 数据包、解析 NORMAL preset 或自定义 LevelStem/生成器。
- 未同步的生成注册表只按加载器声明的 schema 建立空查询表，不读取本地生成数据。
  Forge/NeoForge 的额外 datapack registry 和 Fabric 动态 registry 均经平台接口提供键集；
  真实连接已经同步的 registry 优先，保持原 Holder 和标签身份，不重绑全局静态注册表。
  原版 Flat ChunkMap 构造仍会创建 RandomState/SurfaceSystem；仅为该构造保留原版 NoiseData
  内建噪声常量，不读取 Mod 噪声数据包，不运行地形生成。
- 世界壳使用无地层、无结构的 FlatLevelSource 占位，实际区块仍来自权威服务器或缓存。
  实例固定记录 cacheOnly；在重连前热开 SeedGen 也不能让该占位生成器参与生成。
- 自定义维度按玩家实际进入时收到的 DimensionType 装配，维度 ID 不被假定等于类型 ID。
  首次进入未装配维度或类型改变时，同连接重建影子端并保留登录许可；后续已知类型随同
  连接保留，断连清理。纯缓存模式不因服务端公布整份维度清单而装配本地所有生成器。
- 空资源对象仍需原版构造过程。构造器直接调用的第三方 Mixin void CallbackInfo 回调，
  仅在显式 cache-only 装配作用域中跳过；原版字段初始化、Hassium 回调、非构造回调和
  作用域外的真实世界/SeedGen 回调照常执行。执行变换后的类的回归覆盖该边界和异常恢复。

ServerLevel/ChunkMap 等仍为原版对象，世界构造事件与第三方对底层类的 Mixin 仍可能运行；
已有 Essential、MineColonies、Pixelmon、Deep Void、C2ME 等兼容保护保留。上述改造不改变
3×3 算光门、交付域、POI 主循环串行化或缓存格式。SeedGen 开启时保留原有装配路径。

定向运行选择 ATM9、Pixelmon、VerityCraft，并新增 R3；每场固定成品和独立测试根目录，
原 HMCL 实例只读。`--expect-cache-bootstrap` 另验两轮均走连接注册表装配、未进入本地
数据包装配、本地生成数为零、影子端与客户端维度类型一致。`--dimension <id>` 可验证
首次进入自定义维度及在该维度重连。历史失败照实保留，最终结果补记于本节。

缓存装配加速后，VerityCraft 的 Undergarden 重连暴露了原有激活时序缺口：影子 tracking
在 `play_init` 前物化缓存并登记 compare 等待，但请求尚不能正常进入活动会话，激活后
中心柱被等待闸持续挡住，仅外围 OVD 可见。缓存 NBT 与真服对应柱的非空气数量一致，
并非落盘丢块。tracking 主循环、原版物化回调和权威 acquire 入口现统一等待引擎激活；
保留待同步状态和已注入内容，激活后再校验，未发送请求不得占用在途槽。回归直接调用
真实 tracking 消费方法，验证激活前状态保留、请求不登记以及激活后正常绑定。

最终静态验证见 `build/cache-bootstrap-final-verification/`：七锚点共 3,227 项单测
（1.20.1 为 467，其余每锚点 460），零失败、错误和跳过；18 个加载器编译组合及
`scanVersionBoundaries` 全部通过。

维度 TP 回归使用创造模式：整合包隔离服设 `gamemode=creative` 和 `force-gamemode=true`，
通用 `dimension` 冒烟原语也先按连接顺序发送 `gamemode creative`，再发送 TP。
每轮探针增加 `isolation.creativeMode`，整合包门禁要求其为真，避免传入岩石后的窒息、
死亡和重生影响缓存结论。旧 Verity 场次 `cache-bootstrap-verity-activation` 中已记录
`suffocated in a wall`，保留其失败结果，另起创造模式场次复核。

截至下班暂停前，运行证据应分开解读：

- ATM9：`build/cache-bootstrap-p5/atm9` 为 PASS_DEDICATED，R2 缓存命中 241；该构建
  已含缓存装配隔离，早于后续激活门与创造模式冒烟调整，不能当作最后成品重跑结果。
- Pixelmon：`build/cache-bootstrap-pixelmon-creative/pixelmon` 的 `20261019_175803`
  为 PASS_DEDICATED（18:01:40 完成）。两轮均为创造模式、721 柱驻留、中心 3×3 全有
  非空气内容；R2 缓存命中 280，两次能力与聚合激活。两轮均走连接注册表缓存装配，
  未走本地数据包装配、本地生成数为零、维度类型一致；双端退出码 0，无强退。
  固定 NeoForge 成品 SHA-256：`831f9e1715ab2a39335c541a011c40d3c238ab0606842036e27bc414a877e73b`。
- VerityCraft：已验证首次进入 Undergarden 时维度类型一致、客户端有实际地形且本地
  生成计数为零；R2 中心缺柱尚未验收。生存场次有窒息死亡，不能据此确认激活门修复效果。
  创造模式场次 `build/cache-bootstrap-verity-creative/verity` 的 `20261019_174729`
  在 ClientLevel 建立前连接关闭，取证后中止；同一固定构建重试 `20261019_175313`
  原生 Forge 登录超时且未创建影子端，结果 FAIL。Forge debug 日志记录服务器分发
  76 条登录消息，客户端没有后续 login wrapper 接收记录，需继续定位，不能算缓存通过。
- R3：原始启用的 230 个第三方 JAR 已完整准备于 `build/cache-bootstrap-r3-creative/r3`，
  尚未开始运行；Opticores 的非法大写 modId 仅为元数据预检发现，尚无本轮运行结论。

用户要求完成当前 Pixelmon 双轮后暂停，不再启动其余场景。恢复工作时先读
`build/cache-bootstrap-work-state.json`；待验还包括 VerityCraft 创造模式下的自定义维度
重连，以及 Fabric 1.21.11 纯缓存和 SeedGen 开启场景。本轮不追加 11 包全量回归。

18:01 已按用户要求暂停，测试进程全部退出。最终原始包/客户端副本审计见
`build/cache-bootstrap-final-verification/artifacts.json`：1,045 对第三方 JAR 哈希均未变化，
诊断 JAR 的游戏逻辑类与对应固定成品一致。源码改动及测试根目录均保留，尚未提交。

2026-10-19 18:49 用户恢复工作后的补充结果：

| 目标 | 实跑结果 | 关键证据 |
|------|----------|----------|
| VerityCraft / Undergarden | PASS_DEDICATED | `build/cache-bootstrap-verity-resume/verity`，`20261019_190027`；两轮创造模式、721 柱驻留、中心 3×3 全有地形，R2 缓存命中 314，本地生成 0、维度类型一致，双端退出 0 |
| Fabric 1.21.11 / 纯缓存 | PASS | `cache_bootstrap_fabric_12111_cache`；两轮 cacheOnly=true，R1/R2 驻留 1573/1089，本地生成 0，R2 缓存命中 285 |
| Fabric 1.21.11 / SeedGen 开启 | PASS | `cache_bootstrap_fabric_12111_seedgen`；冷场 cacheOnly=false，实际本地生成 1478 柱、驻留 1558，空基线权威请求 0，客户端正常退出 |

Verity 创造模式有效场次确认自定义维度首次装配与重连中心回填正常。此前两次原生 Forge
登录超时仍保留为历史失败，本轮成功不等于已确定其根因。冒烟菜单连接增加资源覆盖层
消失后 1.5 秒稳定等待和当前 Screen 类诊断；`--trace-login` 仅由隔离测试 Agent 记录
包类型、解码字节数及断连原因，不改变载荷或生产逻辑。每场另固定 Agent JAR 和源码。

R3 原始启用列表已实际运行：`build/cache-bootstrap-r3-creative/r3` 的 `20261019_185039`
双端均报 Opticores 非法大写 modId，未加载 Hassium 冒烟入口，取证后中止，见
`source-diagnosis.json`。去掉 Opticores 的诊断副本继续发现原包
`kaleidoscope_compat-2.6.5-forge+mc1.20.1-Patch.jar` 的 MillstoneBlockEntityMixin 参数
不匹配（期望 LivingEntity、补丁为 Mob）。五个客户端组件 FastLaunch、FastLoading、
SimpleSorterR、WeatherRefind、IMBlocker 仅从专用服副本排除，完整客户端保留。

另建 `build/cache-bootstrap-r3-two-exclusions/r3`，通过显式 `--diagnostic-exclude` 在
双端测试副本排除上述 Opticores 和 Kaleidoscope 兼容补丁，原实例及 JAR 内容均未改。
该场 `20261019_185614` 的基础区块/缓存门禁为 PASS_WITH_EXCLUSIONS：两轮创造模式、
721 柱驻留、中心 3×3 全有地形，R2 命中 352，本地生成 0，双端退出 0。
**这不是 R3 整包兼容通过**：仍有 6 次 Treasure2 的
`ProximityMobSetSpawnerBlockEntity.getMobSizeRange()==null` 影子保存异常，以及一次
Mixin 局部变量分析栈大小异常。见该 run 的 `compatibility-audit.json`，最终兼容判断为
NOT_PASSED。未屏蔽错误、丢弃该 Mod 来凑通过，也未引入生产自动降级或自动禁用。
后续门禁已增加影子 BlockEntity 保存和 Mixin 局部变量分析错误检查；诊断排除场次只能
标 PASS_WITH_EXCLUSIONS，非法 modId 会在启动阶段直接失败，避免把空 Mod 列表误判成功。

生产后端的七锚点 3,227 项单测与 18 个加载器编译组合结果仍见前述验证目录；后续仅有
冒烟辅助调整，1.20.1、1.21.1、1.21.11 单测/编译及最终版本边界扫描再次通过。
Fabric 三份原测试配置已恢复并校验备份哈希。最终审计同时核对所选 1,045 个不同原始
第三方 JAR、各客户端副本（含显式禁用目录）、服务端保留 JAR，以及双端 Hassium class
与各场固定成品的一致性，无差异。未重跑全部 11 包，未验收全部模组玩法或移动屋檐/洞口。

### 按 R3 复现报告限定清单后的修复（2026-10-19 晚间）

本段更新上述 R3 遗留问题的当前状态，早先失败场次保留。清单依据用户指定的
`D:/MC/HassiumDiag211/R3-复现与定位报告.txt`，并以 `r3_2x_fixed/runtime-inventory.json`
逐 JAR 核对名称与 SHA-256：客户端 **229**、专用服 **193** 个 JAR（均含 Hassium）。
双端仅排除报告确认的 Opticores 与 Kaleidoscope 兼容补丁；SimpleSorter 及报告中的
客户端组件只从专用服副本移除。Punchy 恢复到服务端，未新增第三方 Mod 排除项。
FastJEI 保留 `workerThreads=14`、`parallelTooltipScan=true`。原 HMCL 实例和报告目录只读。

- **Treasure2 保存**：原报告成功日志也存在该异常。网络整柱、分段增量 BE 全表、单体
  更新均维护权威 NBT 快照，保存时不再让仅有客户端同步数据的 BE 执行完整服务端
  `saveAdditional`。空 tag 保留类型、位置及实体本身，不填造服务端玩法字段。快照经
  原版 `block_entities` 持久化并在缓存加载时恢复；真实服务端及本地生成柱保留原版保存。
- **LibX / Summoning Rituals 分析异常**：LibX `coremods/interact.js` 在 `useItemOn`
  的 ARETURN 前压入 5 个参数，原返回值仍在栈上，实际峰值为 6，原 `maxStack=5`。
  Summoning Rituals 的局部变量捕获早于最终 ClassWriter 修正而报错。兼容插件仅对
  确实含 LibX CoreInteract 调用的方法，在注入前重算栈元数据，不改指令或交互逻辑。
- 冒烟 G1 在客户端有效视距不大于服务端时不再强求 OVD 环带非空；这用于报告的
  VD20→20 对照，区块、地形、缓存、BE 恢复及错误检查仍为独立硬门禁。

最终 Forge 1.20.1 成品 SHA-256：
`6fc98f344a79b42af6b0ad66cfac15c7d5fc606e47bef2f765737d2aea505d9c`。

| 定向场次 | 结果 | 证据 |
|---|---|---|
| R3 同清单、VD20→20 冷启动与重连 | PASS_WITH_EXCLUSIONS | `build/cache-bootstrap-r3-reference-final/r3`，`20261019_210302`；两轮驻留 1529、中心非空 9/9、创造模式；重连命中 1435，1645 份 BE 快照从盘恢复，其中 Treasure2 刷怪器 3/3；保存与分析异常均为 0，双端退出 0 |
| R3 自定义维度 Alfheim 切换与重连 | PASS_WITH_EXCLUSIONS | `build/cache-bootstrap-r3-reference-alfheim/r3`，`20261019_210709`；两轮均为 `mythicbotany:alfheim`、创造模式、721 柱、中心非空 9/9；重连命中 384，本地生成 0，维度类型匹配，双端退出 0 |
| Fabric 1.21.11 缓存模式 | PASS | `r3_reference_fabric_cache`；驻留 1573→1089，重连命中 309，本地生成 0；覆盖新版 BE 保存接口及缓存恢复 |
| Fabric 1.21.11 本地生成模式 | PASS | `r3_reference_fabric_seedgen`；cacheOnly=false，本地生成 1367 柱、驻留 1534，客户端正常退出 |

新增回归测试覆盖空网络 tag 保留、NBT 深拷贝、原版 NBT 编解码后的快照恢复、普通保存
不被转成客户端快照，以及 LibX 栈修复后分析通过且指令不变。测试仍不等于整包所有玩法验收。
七锚点合计 3262 项测试及 18 个加载器编译组合通过；补充观察字段后的 1.20.1 / 1.21.11
单测和构建也通过。`build/cache-bootstrap-r3-reference-verification/audit.json` 固定报告与
清单哈希、四场 JAR 审计、原始结果及最终门禁；未重跑全部 11 包。
本轮 Fabric 冒烟修改的 6 个原配置路径已恢复并逐项核对备份哈希。

### 2.1.2 发布准备（2026-10-19）

用户要求所有 2.x 版本至少编译通过，本轮实际完成 **12 个 Minecraft 版本 ×
`builds_for` 共 33 个加载器组合的完整 build**，不只使用七个锚点替代段内版本。
全版本 common:test 合计 **5587 项**，失败、错误、跳过均为 0；各版本
`scanVersionBoundaries` 均通过。版本号已由 2.1.1 升为 2.1.2。

33 份最终 JAR 已逐项核对版本、Minecraft 依赖、ServiceLoader 实现、Mixin 类、
缓存装配工厂、Java class major、内嵌 ZSTD 与开发资源剥离；Modrinth 的版本标识、
加载器和实际上传任务目标均核对通过。1.20.1 Forge 成品按原有发布策略同时标记
Forge / NeoForge，其余组合各标记本加载器。检查没有调用上传任务。

完整证据位于 `build/release-prep/`：`build-results.json`、`build_<mc>.log`、
`test-summary.json`、`metadata_<mc>.log`、`artifact-manifest.json`。
移动屋檐/洞口专项使用新增 `lightacceptance` 场景，最终验收见下文。
本轮不扩大第三方整合包回归范围；上述 R3 等场次仍按其实际版本和排除清单解释。

最终 2.1.2 成品在 **R3 原生 Forge 47.4.23** 上完成光照专项，保留用户报告对应的
229 个客户端 / 193 个服务端 JAR，仅维持上述两项必要排除。场次为
`build/release-prep/r3-light/r3/dedicated-runs/20261019_232420`，结果
**PASS_WITH_EXCLUSIONS**，客户端与服务端均正常退出 0，全部 32 项门禁通过。
两段各 32 秒实际飞行分别 Z=-48→639、Z=640→-46，中间真实重连；两轮驻留
1529 / 1697，本地生成均为 0，重连命中 3544，R2 BE 快照从盘恢复 1351，
Treasure2 刷怪器快照保留 3/3、从盘恢复 3/3，黑块回归计数均为 0。

目视证据：用户明确确认 R3 移动洞口、洞内光源及缓存重连回程「正常」；
Agent 另观察并保存回程屋檐的 8 张运动采样截图，阴影边缘过渡正常，
未见区块边界亮缝或整柱黑块。截图与时间清单在 `build/release-prep/visual212/`。
本轮未拆除/旁路齐套门，未关闭区块核心或聚合来取得通过。
最终成品 SHA-256 为 `1b26583a257e740487de0bab1dff6c97528ca934201e414079c369b0cee09fa7`，
两端测试副本与原成品全部 `.class` 字节一致，仅注入开发场景资源及诊断 Agent。

验证范围与失败记录：`release212_light_visual` 的 Forge 47.4.21 Loom 开发启动
在 FML 登录阶段断开，未产生区块探针；未将该场算作通过，也未确认其根因。
Java 17 对照的几次启动受构建依赖或资源准备阻塞，亦未算通过。
本轮 Forge 成品运行结论来自上述原生 R3 / Forge 47.4.23，不声称开发启动故障已修复。
日志、失败结果及首场 debug 日志保留在 `build/release-prep/` 与 `build/smoke-test/`。

升版后旧、新 JAR 共存触发了整包工具的通配符歧义，本轮将候选包选择改为读取
`gradle.properties` 的准确 `mod_version`，不会误选旧 2.1.1；实际 setup 已核验
选中 2.1.2 及上述 SHA-256。57 份原运行配置已逐项恢复并核对 SHA-256，
原 HMCL 实例和成功参考清单未修改。未推送、打标签或发布。

### Forge 47.4.21 开发环境同条件复跑（2026-10-19）

按用户要求，在 `7751df7b` 上重跑原 Forge 47.4.21 / Oracle GraalVM Java 21.0.5
开发环境，未修改业务代码、加载器版本或 Java 版本。沿用 `lightacceptance`、
`-CleanWorld`、VD20→10、客户端 RD20、登录超时 240000ms、客户端超时 600s，
场次 `forge47421_repro_20261019` 最终 **PASS**，客户端退出 0、analyzer 通过，
日志审计、探针门禁和挂起检查均无失败。

首次登录与场景主动断开后的重连都完成 FML 注册表同步和 `Handshake complete!`；
Hassium `login hello answered` / `play init` 均各两次，两轮实际进入世界，
区块内容抽样均为 9/9 非空，本地生成和黑块回归计数均为 0。
本次用于确认登录复现性，不新增人工光照目视结论。

启动准备曾停在 Loom `downloadAssets`；按官方资源索引 SHA-1 和大小逐项校验后，
从现有 HMCL 资源只读补齐 2332 个缺失对象到开发资源缓存，原 HMCL 实例未修改。
此项只补齐游戏资源，不作为原登录失败根因的结论。

结论：**原登录断开本轮未复现，原始失败根因仍未确认**，不记作已修复。
原失败日志继续保留；本轮双端 debug 日志、启动配置、证据哈希与汇总见
`build/forge-login-repro/audit.json`，原始结果见
`build/smoke-test/results/result_forge47421_repro_20261019.json`。
57 份运行配置已按本轮独立备份恢复并核对 SHA-256；未改成品 JAR 或发布状态。
