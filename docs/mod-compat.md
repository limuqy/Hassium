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
| ViaFabric（ViaVersion 系） | **有条件**（见 §5） |
| Sodium / Iris / Lithium / FerriteCore 等热门优化 | **冒烟通过**（Fabric 1.20.1，见 §6 / §11） |
| C2ME | **Soft Compatible**（默认模块冒烟通过，见 §7） |
| 文件级服务端备份（含 InstantBackup） | **兼容**（见 §8）；语义级解压 Anvil 的工具不兼容 |

## 2. 侵入面摘要

Hassium 主要改动：

1. **存档**：`RegionFile` payload type **126**（ZSTD+字典），`.mca` 外壳不变  
2. **区块下发**：握手后取消原版全量包，改走 `chunkHash` 元数据 + Hassium 区块数据（2.0.0 经网关帧中继）  
3. **网络**：可选全局 ZSTD 替换 Zlib、可选包聚合——均为**主控侧 vanilla 路径**行为（客户端不聚合；网关通道仅复用 ZSTD）  
4. **网关化（2.0.0）**：客户端↔世界侧为**纯原版协议**——壳连接仅 keep-alive 响应走 vanilla TCP，PLAY 期 C2S 其余全经网络核心网关路由（MixinConnection → `NetworkCore.routeC2S`），S2C 由网关注入原版监听器（`NetworkCore.dispatchS2C`）后按原版包形态进游戏；Hassium 私有数据走网关帧 / UDP 数据面，第三方不可见

**第三方可见性**：区块核心（含超视渲染 OVD）的拉取与下发均以原版包形态经网关帧中继——OVD 超视渲染 miss 的 `ChunkDataRequestC2S` 与真实区块包在中继后仍以原版包出现（反透视 / 记录类第三方按原版协议可见、可拦截）；Hassium 私有帧与数据面流量第三方不可见。

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

## 5. ViaFabric（ViaVersion 系）兼容桥

2.0.0 网络核心内置 ViaFabric 兼容桥（`network/core/viafabric/`：`ViaFabricCompat` + `ViaDecodeBridge`，T9 REQ A8）：检测到 ViaFabric / ViaForge / ViaFabricPlus / ViaVersion 时（classpath + mod 列表双通道探测），客户端 S2C 注入点（`NetworkCore.dispatchS2C`）改挂 ViaFabric 取包处（pipeline `via-decoder`），先做协议转换再注入原版监听器；任何一步失败或 ViaFabric 吞包 → 退回原包直接注入，不崩（会话内降级）。同版本部署（Hassium 默认）时 decode 链为透传，转换正确且保持 ViaFabric 连接状态一致。

| 拓扑 | 结论 |
|------|------|
| 同版本双端均装 Hassium | 正常；客户端装 ViaFabric 时经 `via-decoder` 透传（兼容桥保持其连接状态一致，未装则直接注入） |
| 服务端 Hassium + Via，客户端**无** Hassium | **支持**：客户端走原版路径，Via 正常翻译原版协议 |
| 双端 Hassium 但 MC 版本不同（靠 Via 桥） | **有条件**：S2C 经网关 outbound 解码后由 `ViaDecodeBridge` 转换到客户端版本再注入；跨版本场景的编码侧（服务端协议编解码器）仍有开放点（`ViaDecodeBridge.java:42-45` 注释），不作承诺 |
| 服务端进程内 Via 与 `globalPacketCompression` | 管线 ZSTD 仅对完成 Hassium 握手的玩家安装，未握手（含 Via 翻译目标）玩家仍走原版 Zlib，无帧假设冲突；若自测出现压缩帧干扰，关 `network.globalPacketCompression` 逃生口仍有效 |

客户端侧壳连接为零数据面流量（不承载 Hassium 压缩/聚合），2.0.0 不再有"客户端进程内 Via 与管线 ZSTD 叠用"的干扰面。

## 6. 热门优化 mod

Sodium / Iris / Lithium / FerriteCore / EntityCulling / ImmediatelyFast 等通常不抢 Region 压缩类型与区块广播接管。

常见摩擦：默认 `clientCache.hassiumEngineEnabled = true`（进服启动影子端统一算光，客户端不再本地算光）。出现光照异常时关闭即可（关闭后服务端不剥光，光照随包自带）。

## 7. C2ME

- C2ME **不**引入自定义 region compression type；与 Hassium type 126 无「双格式抢写」设计冲突。  
- Hassium 仅拦截 `RegionFile` 的流式读写；若 C2ME 仍委托该 API → 可共存。  
- 若开启 **chunkio rewrite** 且自实现 MCA 读写、只认 type 1/2/3 → 读 126 可能失败。  
- 并发 IO 存在理论竞态；默认模块下 Fabric 1.20.1 冒烟已通过（见 §11），**不承诺** chunkio rewrite 全开时的官方兼容。  

**逃生：** `storage.enabled = false`（默认已关；保留网络优化）。

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

配置文件（双文件模型）：物理客户端读 `config/hassium/hassium-client.toml`；专用服读 `config/hassium/hassium-server.toml`（旧三文件 `common.toml` 模型已废弃）。

| 配置 | 作用 |
|------|------|
| `storage.enabled` | 关存档 type 126 |
| `network.enabled` | 关自定义通道 / 推送 |
| `network.globalPacketCompression` | 关全局 ZSTD |
| `network.enablePacketAggregation` | 关包聚合 |
| `network.compressionBlacklist` | 排除指定包 ID（第三方通道） |
| `network.dataPlane.enabled` | 关 UDP/KCP 数据面（网关↔主控通道 bulk 载体；默认已关）；用于隔离 UDP 防火墙、NAT 或代理问题，控制/握手仍走网关帧连接 |
| `clientCache.hassiumEngineEnabled` | 关 Hassium 引擎（服务端不剥光，光照随包自带；影子端相关功能关闭） |
| `clientCache.sectionDeltaEnabled` | 关分段增量（过期改走全量） |
| `clientCache.viewDistanceExtensionEnabled` | 关 超视渲染（恢复原版 RD 钳制） |
| `clientCache.maxRenderDistance` | 超视渲染 / 有效 RD 上限（2–64） |
| `clientCache.ovdUnloadDelaySecs` | 离开超视渲染环带后延迟卸载（秒） |
| `compat.requireClientMod` | 是否强制客户端装模组 |

UDP 数据面部署时，`udpListeners[*].bindHost` 只用于服务器本机监听，`reachableEndpoints` 才下发给客户端。公网部署必须填写客户端可达地址并放行 UDP 端口；禁止把 `0.0.0.0`、`::` 或内网 bind 地址写为 reachable endpoint。网关监听地址使用独立的 `network.controlReachableEndpoints`（主控核心；`endpoints[0]` 即网关端口，兜底 25566），不与 UDP 列表混用。详见 [`architecture.md`](architecture.md)。

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
- [ ] Via：无 Hassium 旧客户端能进服；同版本 Hassium 客户端 + ViaFabric 兼容桥转换完整；跨版本 Hassium 双端（服务端协议 ≠ 客户端版本）场景验证
- [ ] C2ME **chunkio rewrite** 开/关与 `storage` 开/关对照  
- [ ] Sodium + `hassiumEngineEnabled` 开/关（光照异常时）
- [ ] 文件级备份：热备份 → 改区块 → 导出还原 → 带 Hassium 进服可读  
- [ ] Forge / NeoForge 同等优化包冒烟
- [ ] **超视渲染**：多人服 `view-distance=8`、客户端 RD=16，曾走过的地形环带可见；F3 无大量视距外 `ChunkDataRequestC2S` / `BlockEntityRequestC2S`；关闭 `clientCache.viewDistanceExtensionEnabled` 后恢复原版钳制；断连重连无残留 renderOnly 标记
- [ ] **超视渲染 + Sodium**：ViewArea 扩大后超视渲染区块正常 mesh（多数 Sodium 版本可跟，必要时补条件 Mixin）
- [ ] **超视渲染边界替换**：真实区块到达 renderOnly pos 后无闪烁、无重复 enqueue  
