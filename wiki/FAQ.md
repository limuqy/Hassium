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

A: 有条件。Hassium 通道压缩不触碰原版压缩层（管线级全局包压缩已退役），但同类 mod 若替换原版压缩管线仍有冲突面。建议二选一；或 `master.enabled = false`（仅享受客户端缓存）。

### Q: 第三方 mod 的包被 Hassium 聚合后报错？

A: 逃生：(1) `master.enablePacketAggregation = false`，或 (2) 把该**第三方**包 ID 加进 `master.compressionBlacklist`（Hassium 控制面已硬编码排除，本列表只用于第三方）。若是 Hassium 自有 `hassium:*` 包，请检查双端版本是否匹配，不要往黑名单塞。

### Q: 公网部署需要放行哪些端口？

A: 只需游戏端口（vanilla TCP）。直连拓扑下无网关/UDP 端口（均已退役）。

### Q: 断线重连会重新下载全部区块吗？

A: 不会。断连时影子端世界已落盘（`hassium_cache/<serverId>/world`），重连后未变更区块走缓存命中（UNCHANGED），变更区块走分段增量（DELTA），仅缺失区块走全量。

---

## 本地生成（SeedGen）

### Q: SeedGen 会不会泄露我的世界种子？

A: **会**。服务端开启 `chunk.seedGenEnabled` 时会向客户端下发世界种子，等同泄露服务端种子（探图/种子地图/导出存档均可利用）。公网服请权衡后决定。

### Q: 客户端和服务端版本不一样能用 SeedGen 吗？

A: 不能。本地生成要求双端同版本；版本不一致时自动回退全量请求。

---

## 导出

### Q: 导出的世界能直接进单机吗？

A: 目前不能直接进。2.0.0 的 `export` 是影子端世界目录整体拷贝，保留 type 126 + chunkHash 落盘格式（翻译为原版格式后续提供）；导出目录为 `<gameDir>/hassium_exports/<cacheId>/`。

### Q: 导出的世界含实体吗？

A: **不含**。影子端世界仅含区块/光照与方块实体数据，无玩家背包/成就/普通实体。导出限制详见 [World-Export](World-Export)。

---

## 超视渲染

### Q: 超视渲染现在能用吗？

A: **可以，默认开启**。影子双窗 OVD：权威窗（服务端视距内）走统一 Compare+Pull，视距外环带由影子端本地已有地形（盘 / 注入）回填，**仅参与渲染、不向服务端请求**。用 `chunk.viewDistanceExtensionEnabled = false` 关闭。详见 [Beyond-View-Render](Beyond-View-Render)。

### Q: 我用 Bobby 会冲突吗？

A: 会。Hassium 影子端自行管理缓存与重交付，与 Bobby 不兼容，勿同装。

---

## 排查

### Q: 我看到 `latest.log` 有 refmap 加载失败的 WARN？

A: 仅出现在开发环境（Loom 运行时），可忽略，不影响功能。正式客户端/服务端的 jar 内已带 refmap，正常解析。

### Q: 热路径没有日志怎么办？

A: 热路径默认安静。排查时按需打开 `debug.*`：`debug.metadataLogging` / `debug.networkLogging` / `debug.cacheLogging` / `debug.chunkApplyLogging` 等。详见 [Troubleshooting](Troubleshooting)。

---

[← Network-Architecture](Network-Architecture) · [Home](Home) · [→ Troubleshooting](Troubleshooting)
