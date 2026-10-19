# 影子端同连接重建误取消与 Windows 文件锁重试

## 问题与修复

R3 社区模组重建组合的 1.20.1 Forge 双端实测中，客户端收到自定义维度清单后会重建影子端。连接界面先前留下的 `speculativeAbandoned` 由登录后的 `unparkPermitted` 抵消，但重建调用普通 `shutdown()` 又撤销该许可，新影子端创建后便被误判为取消，随即关停。另一条方块更新路径可能在旧 `session.lock` 释放前再次创建；Windows 在写锁标记时抛出的中文 `IOException` 不匹配原英文重试规则，最终永久停用本会话影子引擎。服务端仍处于协商过的 Pull 接管，客户端因此一直没有地形。

- 同连接种子/维度重建使用 `shutdown(true)` 保留登录许可；普通断连、换服和投机取消仍通过 `shutdown()` 撤销许可。
- 锁获取路径的 `IOException` 按 `DirectoryLock` 实际运行时类名识别，兼容映射和本地化信息；保留原有 50 次、200ms 的有界重试。
- 可选 JVM 属性 `hassium.debug.shadowLifecycle=true` 记录创建/关停实例和调用路径，默认关闭。
- 不改变区块/光照门禁、传输协议、缓存格式或版本号。

## 2.0.0 分支验证

- `forge:remapJar`、1.20.1 的 `ShadowLifecycleRebuildTest`（4 项）、`ShadowSeedRebuildTest`（3 项）、`ClientServerIsolationRegressionTest`（17 项）通过。
- R3 运行 Forge 47.4.23，Java 21，客户端 229 个 JAR、专用服 193 个 JAR。先排除非法 ID 的 Opticores、Mixin 签名不匹配的 Kaleidoscope Compat；SimpleSorter 及客户端用途模组不放专用服。已安装启动器目录未改动。
- 发布版 2.1.1 与隔离修复基线 `677bcbcb` 加只读诊断日志均复现：进服 60 秒，驻留/请求/应用均为 0。
- 仅关闭 Bons 后台保存仍复现。FastJEI 14 线程并行提示扫描保持开启。
- 本次修复后冷启动：驻留 1529、请求/应用 1705，附近 9/9 采样含非空气方块，PASS。
- 保留世界与缓存重连：驻留 1529、应用 1606、完整缓存命中 1418，9/9 采样含非空气方块；驻留、内容、应用量、缓存命中四项显式断言通过。
- 对照前后其他模组及 FastJEI 配置相同，Hassium 中仅 `ShadowServerRegistry.class` 改变。

缓存重连首次误用冷启动“必须网络加载整柱”的统计门禁，记录为 FAIL；其地形探针正常，后续改用明确的缓存重连断言复核通过。JDWP 在本机 GraalVM 21.0.5 出现的原生崩溃单独保留，不计为虚空复现。

## 适用边界

上述游戏实测仅属于 1.20.1 Forge。主分支是否共有该缺陷、以及 26.x 的验证，须独立核对，不借用 R3 结果。原社区日志没有 Play 激活记录且存在 FastJEI/Spell Power 提示线程异常，本地本次则有双端激活日志；不能据此断言原社区用户必定同根。
