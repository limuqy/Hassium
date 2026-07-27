# FAQ

---

> **English**: [FAQ-en](FAQ-en) · 中文

## 存储

### Q: 启用存储会修改存档格式吗？

A: 会。`storage.enabled = true` 时区块落盘改为 ZSTD type 126 payload，`.mca` 外壳不变。**首次启用前务必备份世界**。

### Q: 卸载 Hassium 后存档还能读吗？

A: 存档本身仍是 type 126，**需要重新安装匹配 MC 版本的 Hassium** 才能读取。如果不希望被绑定：先在配置里把 `storage.enabled = false` 关掉（保留网络优化），等 chunk 全部用原版 Zlib 落盘覆盖后再卸载。

### Q: 回档后存档读不了？

A: 需要重新安装与该存档兼容的 Hassium 版本。压缩资源由 Hassium 内置管理，用户无需单独安装或配置。

### Q: 升 MC 大版本后客户端缓存还能用吗？

A: 自 1.21.5 起客户端缓存**不保证跨 MC 大版本兼容**。旧缓存可懒覆盖（MISS → 重拉 → persist），不会启动时整库作废，但首次出现会较多 MISS。详见 [Compatibility](Compatibility)。

---

## 网络

### Q: 客户端没装 Hassium 能连我装了 Hassium 的服务器吗？

A: 默认可以。`compat.requireClientMod = false`（默认）时未装模组的客户端通过原版协议连接，仅享受服务端压缩；客户端缓存、协商压缩等高级特性需要双端都装。

### Q: 我装了同类压缩 mod，能和 Hassium 共存吗？

A: 不行。与 `network.globalPacketCompression` 冲突。逃生口：`network.globalPacketCompression = false` 或 `network.enabled = false`（仅享受客户端缓存）。

### Q: 第三方 mod 的包被 Hassium 聚合后报错？

A: 逃生：(1) `network.enablePacketAggregation = false`，或 (2) 把该包 ID 加进 `network.compressionBlacklist`。

---

## 超视渲染

### Q: 我用 Bobby 想试试 Hassium 超视渲染怎么办？

A: **二选一**。Hassium 与 Bobby 不兼容；先把 Bobby 在客户端移除再启动 Hassium 的超视渲染。

### Q: 单人服能开超视渲染吗？

A: 不能。超视渲染仅在多人服启用，单人服单端 `view-distance` 不受限。

### Q: 渲染距离拉到 48 后雾后面突然冒出区块？

A: 这是已知限制。Fog Mixin 跨 9 段版本签名差异大未实现；RD > 32 时雾距跟随 `getEffectiveRenderDistance` 扩大，远端区块可能突然显现（穿帮）。建议保持 RD ≤ 32。

### Q: 超视渲染环带内存占用大吗？

A: 环带规模由客户端 RD 与服务端视距之差决定；可通过降低 `clientCache.maxRenderDistance` 或关闭 `clientCache.viewDistanceExtensionEnabled` 限制资源占用。超视渲染复用现有缓存淘汰机制，不新增专用内存池。

---

## 数据面

### Q: 数据面 UDP 默认开吗？

A: 默认关。`network.dataPlane.enabled = false`。启用前请跑 6 个 smoke marker，详见 [Data-Plane-and-Failover](Data-Plane-and-Failover)。

### Q: TCP 主控卡顿后客户端会断连吗？

A: 卡顿超过 `controlStallMs`（默认 6 秒）且 UDP 数据面健康时，服务端下发 `FailoverPermit`，客户端不主动断当前 master 连接，而是按 permit 切换到下一候选。候选耗尽才真正 finalize。

---

## 导出

### Q: 导出的世界能进单机吗？

A: 能。`/hassiumc export` 生成原版 Anvil 存档（type 2 Zlib），单机主菜单可见并可直接进入。

### Q: 导出的世界含实体吗？

A: **不含**。缓存仅含方块状态与方块实体 NBT，无玩家背包/成就/普通实体。导出限制详见 [World-Export](World-Export)。

---

## 排查

### Q: 我看到 `latest.log` 有 refmap 加载失败的 WARN？

A: 仅出现在开发环境（Loom 运行时），可忽略，不影响功能。正式客户端/服务端的 jar 内已带 refmap，正常解析。

### Q: 热路径没有日志怎么办？

A: 热路径默认安静。排查时按需打开 `debug.*`：`debug.metadataLogging` / `debug.networkLogging` / `debug.cacheLogging` / `debug.chunkApplyLogging` 等。详见 [Troubleshooting](Troubleshooting)。

---

[← Data-Plane-and-Failover](Data-Plane-and-Failover) · [Home](Home) · [→ Troubleshooting](Troubleshooting)
