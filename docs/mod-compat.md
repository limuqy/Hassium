# 多 Mod 兼容性

本文档描述 Hassium 与其他模组并存时的预期行为、已知边界与配置逃生口。跨版本 API 桥接见 [`version-segments.md`](version-segments.md)；区块推送见 [`chunk-cache.md`](chunk-cache.md)。

## 1. 兼容边界

| 目标 | 策略 |
|------|------|
| Bobby / 同类客户端视距外缓存 | **不兼容**；Hassium 自研超视渲染，勿与 Bobby 同装 |
| Immersive Portals | **不兼容** |
| 同类压缩 / 协议替换（改 Netty Zlib 等） | **不兼容**；与 `globalPacketCompression` 冲突 |
| Starlight | **不考虑**（已并入原版光照） |
| 包聚合导致第三方包异常 | 关 `network.enablePacketAggregation`，或把包 ID 加入 `network.compressionBlacklist` |
| 反透视（改 chunk 发包内容） | **希望兼容**（miss 路径复用已构建包字节，见 §3） |
| Distant Horizons / Voxy | **希望兼容**（独立 LOD 通道；见 §4） |
| ViaVersion | **有条件**（见 §5） |
| Sodium / Iris / Lithium / FerriteCore 等热门优化 | **冒烟通过**（Fabric 1.20.1，见 §6 / §11） |
| C2ME | **Soft Compatible**（默认模块冒烟通过，见 §7） |
| 文件级服务端备份（含 InstantBackup） | **兼容**（见 §8）；语义级解压 Anvil 的工具不兼容 |

## 2. 侵入面摘要

Hassium 主要改动：

1. **存档**：`RegionFile` payload type **126**（ZSTD+字典），`.mca` 外壳不变  
2. **区块下发**：握手后取消原版全量包，改走 `chunkHash` + `hassium:*` 通道  
3. **网络**：可选全局 ZSTD 替换 Zlib；可选包聚合  

未握手 / 无 Hassium 客户端仍走原版 `ClientboundLevelChunkWithLightPacket`（`compat.requireClientMod` 默认 `false`）。

## 3. 反透视

反透视通常修改「即将发送的区块包」而非世界本身。

- **broadcast 路径**：拦截时持有的 `ClientboundLevelChunkWithLightPacket` 若已被混淆，Hassium 会**编码并缓存该包字节**；客户端 miss 全量请求时优先发送缓存字节，避免 `new Packet(LevelChunk)` 旁路混淆。  
- **PlayerChunkSender / 无现成包路径**：在主线程按与原版相同构造路径构建一次包后再算 hash / 缓存，供后续 miss 复用。  
- 若某反透视仅在 `Connection.send` 上改写且发生在 Hassium 取消之后，则仍可能旁路——此类实现需对方适配或关 Hassium 网络通道。

## 4. Distant Horizons / Voxy

- DH 2.3+、Voxy 服务端伴生 mod 使用**独立 LOD / 自定义通道**，一般不依赖原版全量区块包，与 Hassium 区块劫持正交。  
- 风险：包聚合可能拖延其控制/数据通道。处理方式：  
  - `network.enablePacketAggregation = false`，或  
  - 将通道 ID 加入 `network.compressionBlacklist`（示例前缀，以实际包 ID 为准）：`distant_horizons:`、伴生 mod 的 `namespace:`）。  
- LOD 若经 `RegionFile.getChunkDataInputStream` 读盘：type 126 可由 Hassium Mixin 解压。若工具**裸解析 `.mca`** 且不认 126，会失败——属存档格式约束。

## 5. ViaVersion

| 拓扑 | 结论 |
|------|------|
| 同版本双端均装 Hassium | Via 不参与；正常 |
| 服务端 Hassium + Via，客户端**无** Hassium | **支持意图**：握手失败 → 原版包下发 → Via 翻译原版协议 |
| 双端都装 Hassium 但 MC 版本不同（靠 Via 桥） | **不支持**（线格式随 `MC_VER` 绑定） |

进程内 Via 与 `globalPacketCompression` 叠用可能干扰压缩帧假设：建议同进程 Via 时关闭全局压缩；聚合问题同 §1。

## 6. 热门优化 mod

Sodium / Iris / Lithium / FerriteCore / EntityCulling / ImmediatelyFast 等通常不抢 Region 压缩类型与区块广播接管。

常见摩擦：默认 `network.lightStripEnabled = true`（客户端本地重算光照）。出现光照异常时关闭即可。

## 7. C2ME

- C2ME **不**引入自定义 region compression type；与 Hassium type 126 无「双格式抢写」设计冲突。  
- Hassium 仅拦截 `RegionFile` 的流式读写；若 C2ME 仍委托该 API → 可共存。  
- 若开启 **chunkio rewrite** 且自实现 MCA 读写、只认 type 1/2/3 → 读 126 可能失败。  
- 并发 IO 存在理论竞态；默认模块下 Fabric 1.20.1 冒烟已通过（见 §11），**不承诺** chunkio rewrite 全开时的官方兼容。  

**逃生：** `storage.enabled = false`（保留网络优化）。

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
| **Forge Config API Port** | Fabric **不使用**（Night Config 自管 toml）；仅 Forge **1.20.6** jiJ（ModConfigSpec 桥接）；NeoForge 原生 Spec |

配置文件：物理客户端为 `config/hassium/hassium-client.toml` + `hassium-common.toml`；专用服仅 `hassium-common.toml`。

| 配置 | 作用 |
|------|------|
| `storage.enabled` | 关存档 type 126 |
| `network.enabled` | 关自定义通道 / 推送 |
| `network.globalPacketCompression` | 关全局 ZSTD |
| `network.enablePacketAggregation` | 关包聚合 |
| `network.compressionBlacklist` | 排除指定包 ID（第三方通道） |
| `network.lightStripEnabled` | 关光照剥离 |
| `clientCache.sectionDeltaEnabled` | 关分段增量（过期改走全量） |
| `clientCache.viewDistanceExtensionEnabled` | 关 超视渲染（恢复原版 RD 钳制） |
| `clientCache.maxRenderDistance` | 超视渲染 / 有效 RD 上限（2–64） |
| `clientCache.ovdUnloadDelaySecs` | 离开超视渲染环带后延迟卸载（秒） |
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
| 启动与进服 | 通过；握手 `accepted=true`，`globalCompression=true` |
| 客户端缓存 | Bloom / heat / CacheSaveQueue 正常；断开清理正常 |
| 运行时统计 | `/hassiumc stats` 有压缩节省与缓存命中（示例会话约节省 83%、命中率约 73%） |
| `latest.log` 中 Hassium | 无 ERROR / Exception；仅有开发环境 refmap WARN（§10） |
| 第三方 ERROR | Debugify / CITResewn / Controlify 等与 Hassium 无关 |

本地联测目录：`fabric/run/client/mods`（勿把第三方 jar 提交进 git）。

### 仍建议覆盖

- [ ] 反透视 + Hassium 客户端：矿石仍应被混淆  
- [ ] DH 双端 / Voxy+伴生：LOD 正常；必要时关聚合或加黑名单  
- [ ] Via：无 Hassium 旧客户端能进服；同版本 Hassium 客户端功能完整；同进程 Via 时关 `globalPacketCompression`  
- [ ] C2ME **chunkio rewrite** 开/关与 `storage` 开/关对照  
- [ ] Sodium + `lightStrip` 开/关（光照异常时）  
- [ ] 文件级备份：热备份 → 改区块 → 导出还原 → 带 Hassium 进服可读  
- [ ] Forge / NeoForge 同等优化包冒烟
- [ ] **超视渲染**：多人服 `view-distance=8`、客户端 RD=16，曾走过的地形环带可见；F3 无大量视距外 `ChunkDataRequestC2S` / `BlockEntityRequestC2S`；关闭 `clientCache.viewDistanceExtensionEnabled` 后恢复原版钳制；断连重连无残留 renderOnly 标记
- [ ] **超视渲染 + Sodium**：ViewArea 扩大后超视渲染区块正常 mesh（多数 Sodium 版本可跟，必要时补条件 Mixin）
- [ ] **超视渲染边界替换**：真实区块到达 renderOnly pos 后无闪烁、无重复 enqueue  
