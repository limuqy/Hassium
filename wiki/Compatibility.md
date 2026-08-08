# 兼容性

---

> **English**: [Compatibility-en](Compatibility-en) · 中文

Hassium 与常见优化 mod 的兼容性概览与配置逃生口。每条结论都附操作建议。

---

## 兼容总览

| 目标 | 结论 | 备注 |
| --- | --- | --- |
| **Bobby / 同类客户端视距外缓存** | ❌ **不兼容** | Hassium 内置超视渲染；同装会冲突 |
| **Immersive Portals** | ❌ **不兼容** | |
| **同类压缩 / 协议替换（改 Netty Zlib 等）** | ❌ **不兼容** | 与 `network.globalPacketCompression` 冲突 |
| **Starlight** | — **不考虑** | 已并入原版光照 |
| **包聚合导致第三方包异常** | ⚠️ 关聚合或加黑名单 | `network.enablePacketAggregation = false` 或 `network.compressionBlacklist` |
| **反透视（改即将发送的区块包）** | ✅ 希望兼容 | miss 路径复用已构建包字节；若只在 `Connection.send` 上改写且发生在 Hassium 取消之后可能旁路 |
| **Distant Horizons / Voxy** | ✅ 希望兼容 | 独立 LOD 通道；聚合误伤同上处理 |
| **ViaVersion** | ⚠️ 有条件 | 见下表 |
| **Sodium / Iris / Lithium / FerriteCore / EntityCulling / ImmediatelyFast** | ✅ 兼容测试通过 | Fabric 1.20.1 联调记录见 [Support-Matrix](Support-Matrix) |
| **C2ME** | ✅ Soft Compatible | 默认模块兼容测试通过；chunkio rewrite 全开时不承诺；关 `storage.enabled` 作为逃生 |
| **文件级服务端备份（含 InstantBackup）** | ✅ 兼容 | 126 对备份器透明 |
| **语义级解压 Anvil 工具** | ❌ 不兼容 | 不认 type 126 |

---

## ViaVersion 拓扑

| 拓扑 | 结论 |
| --- | --- |
| 同版本双端均装 Hassium | Via 不参与；正常 |
| 服务端 Hassium + Via，客户端**无** Hassium | 支持：握手失败回退原版，Via 翻译原版协议 |
| 双端都装 Hassium 但 MC 版本不同（靠 Via 桥） | ❌ 不支持（线格式随 `MC_VER` 绑定） |

> 同进程 Via 与 `globalPacketCompression` 叠用可能干扰压缩帧假设。建议同进程 Via 时关闭全局压缩。

---

## 配置逃生口速查

| 想要的效果 | 改动 |
| --- | --- |
| 关闭存储压缩、保留网络优化 | `storage.enabled = false` |
| 关闭自定义通道与推送 | `network.enabled = false` |
| 关闭全局 ZSTD（与同类协议替换共存） | `network.globalPacketCompression = false` |
| 关闭包聚合 | `network.enablePacketAggregation = false` |
| 排除第三方包不进压缩/聚合 | `network.compressionBlacklist` |
| 关闭 Hassium 引擎（服务端不剥光，光照随包自带） | `clientCache.hassiumEngineEnabled = false` |
| 关闭分段增量（过期走全量） | `clientCache.sectionDeltaEnabled = false` |
| 关闭超视渲染恢复原版 RD 钳制 | `clientCache.viewDistanceExtensionEnabled = false` |
| 强制客户端装模组 | `compat.requireClientMod = true` |

---

## 配置 GUI 兼容

| 模组 | 关系 |
| --- | --- |
| **Mod Menu**（Fabric） | 软兼容；单独安装即可打开 Cloth 配置 |
| **Cloth Config** | Fabric / Forge / NeoForge 均已 jiJ；配置屏主路径 |
| **Configured** | Forge/NeoForge 可选；Fabric 不依赖 |
| **Forge Config API Port** | Fabric **不使用**（Night Config 自管 toml）；仅 Forge 1.20.6 jiJ（ModConfigSpec 桥接） |

---

## 存档兼容须知

- Hassium type 126 是**磁盘上的 ZSTD payload**，外层 `.mca` 布局不变
- 卸载模组后存档仍为 126：需装回**匹配版本**的 Hassium 才能读取
- 跨 MC 大版本客户端缓存**不保证兼容**：升版本后旧缓存可懒覆盖（MISS → 重拉 → persist），不做启动时整库作废
- 回档后要读 126：必须装匹配 MC 版本的 Hassium
- 文件级备份（整文件/目录/zip/增量 blob，不解压 compression type）兼容；解压 chunk → 改 NBT → 再压 的工具不兼容

---

## 兼容测试记录（2026-07，Fabric 1.20.1）

环境：约 50 个优化向模组（FO 风格：Sodium / Iris / Lithium / FerriteCore / C2ME / EntityCulling / ImmediatelyFast / Mod Menu / Cloth 等；**未**装 Bobby / ViaFabric / Immersive Portals）。

| 检查项 | 结果 |
| --- | --- |
| 启动与进服 | 通过；握手 `accepted=true`，`globalCompression=true` |
| 客户端缓存 | Bloom / heat / CacheSaveQueue 正常；断开清理正常 |
| 运行时统计 | 可用 `/hassiumc stats` 查看压缩节省与缓存命中 |
| `latest.log` 中 Hassium | 无 ERROR / Exception；仅开发环境 refmap WARN（见 [Troubleshooting](Troubleshooting)） |

### 仍建议覆盖

- [ ] 反透视 + Hassium 客户端：矿石仍应被混淆
- [ ] Distant Horizons 双端 / Voxy + 伴生：LOD 正常
- [ ] Via：无 Hassium 旧客户端能进服；同版本 Hassium 客户端功能完整
- [ ] C2ME chunkio rewrite 开/关 × `storage` 开/关 对照
- [ ] Sodium + `hassiumEngineEnabled` 开/关（光照异常）
- [ ] Forge / NeoForge 同等优化包兼容测试
- [ ] 超视渲染实机：多人服 `view-distance=8`、客户端 RD=16，走过的环带地形可见；F3 无大量视距外请求
- [ ] 超视渲染 + Sodium：ViewArea 扩大后 mesh 正常

---

[← World-Export](World-Export) · [Home](Home) · [→ Support-Matrix](Support-Matrix)
