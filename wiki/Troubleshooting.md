# 排查

---

> **English**: [Troubleshooting-en](Troubleshooting-en) · 中文

## 日志位置

| 环境 | 路径 |
| --- | --- |
| 客户端（生产） | `.minecraft/logs/latest.log` |
| 客户端（Loom 开发） | `fabric/run/client/logs/latest.log`、`forge/run/client/logs/latest.log`、`neoforge/run/client/logs/latest.log` |
| 服务端 | `<server>/logs/latest.log` |

> 历史日志：同目录下 `latest.log` 之外按 `yyyy-MM-dd-N.log.gz` 滚动。

---

## 启动后检查

启动客户端或服务端后，在 `latest.log` 中搜索 `Hassium`、`ERROR` 与 `Exception`。出现 ERROR / Exception 时，连同相关时间窗日志一并排查或上报。

---

## 调试日志开关

`config/hassium/hassium-client.toml` 或 `config/hassium/hassium-server.toml` 末尾的 `debug.*`：

| 配置键 | 含义 |
| --- | --- |
| `debug.metadataLogging` | chunkHash / 元数据比对 |
| `debug.dispatcherLogging` | 主线程调度 |
| `debug.asyncLogging` | 异步任务 |
| `debug.compressionLogging` | 压缩/解压 |
| `debug.chunkApplyLogging` | 区块 apply |
| `debug.networkLogging` | 网络收发 |
| `debug.cacheLogging` | 缓存读写 |

按需打开某一类；热路径默认安静，全员开启会显著影响 FPS。`ERROR` / `WARN` 始终输出。

---

## 常见症状与排查

| 症状 | 可能原因 | 处理 |
| --- | --- | --- |
| 进服卡顿更严重了 | 客户端缓存目录满或硬盘慢 | `/hassiumc stats` 看缓存命中；检查 `hassium_cache` 目录大小与磁盘 IO |
| 区块在远处闪烁 | 超视渲染与真实区块交接异常 | 关 `clientCache.viewDistanceExtensionEnabled` 验证；升级到近期版本 |
| 光照异常 | `clientCache.hassiumEngineEnabled` 与 Sodium 兼容问题 | 关 `clientCache.hassiumEngineEnabled`（服务端不再剥光，光照随包自带） |
| 客户端启动报 refmap WARN | Loom 开发环境常态 | 忽略；正式 jar 不复现 |
| 服务端连接被踢 | `compat.requireClientMod = true` 且客户端未装 | 客户端装 Hassium；或服务端 `requireClientMod = false` |
| 存档读不出来 | 卸载/降级 Hassium 后残留 type 126 | 重新安装与存档兼容的 Hassium 版本 |
| 聚合把第三方包搞坏 | 包聚合误伤 | 关 `network.enablePacketAggregation` 或加 `network.compressionBlacklist` |
| 雾距过大、远端区块穿帮 | RD > 32 且 Fog Mixin 未实现 | 保持 RD ≤ 32 |
| 同进程 Via 桥出错 | `globalPacketCompression` 与压缩帧假设冲突 | 关 `network.globalPacketCompression` |

---

## 数据面排查

启用 `network.dataPlane.enabled` 后，依次确认 6 个自检标记：

1. `UDP_BIND_OK` 失败：检查 UDP 端口是否被占用 / 防火墙是否放行
2. `UDP_WRR_OK` 失败：检查 `weight` 配置是否合法
3. `FAILOVER_PERMIT_OK` 失败：检查服务端 `controlStallMs` 是否设得过短
4. `FAILOVER_RECONNECT_OK` 失败：检查候选 endpoint 公网可达性
5. `CACHE_RESUME_HIT` 失败：检查 `ClientRecoveryState` 是否真能阻止 finalize（dirty 保留检查）
6. `FAILOVER_TERMINAL_OK` 失败：候选耗尽后 `consumeTerminalCleanup` 未 exactly-once

详见 [Data-Plane-and-Failover](Data-Plane-and-Failover)。

---

## 重置客户端缓存

完全重置客户端缓存（仅该服务器）：

1. 退出该服务器
2. 关闭游戏
3. 删除 `.minecraft/hassium_cache/<server-id>/`（目录名通常含服务器 IP 与端口）
4. 重新进入

> **谨慎操作**：删除会丢失该服务器的所有缓存命中率。`hassium_cache` 是按服务器隔离的，删除一个不影响其它服务器。

---

## 反馈与上报

如果排查后仍找不到原因，到 GitHub Issues 上报时请同时附：

- MC 版本
- 加载器与版本（Fabric / Forge / NeoForge）
- Hassium 版本
- 客户端 / 服务端日志摘录（相关时间窗 `latest.log`）
- `/hassium stats` 或 `/hassiumc stats` 输出
- 复现步骤（最简）

仓库：https://github.com/limuqy/Hassium/issues

---

[← FAQ](FAQ) · [Home](Home)
