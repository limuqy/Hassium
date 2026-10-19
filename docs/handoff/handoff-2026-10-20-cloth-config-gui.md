# 3.x Cloth 配置界面恢复（2026-10-20）

## 1. 改动与边界

Fabric / NeoForge 恢复编译 `cloth-ui/src/main/java`，配置工厂重新调用
`HassiumClothConfigScreen.create(parent)`。客户端界面沿用原有三分类及草稿保存逻辑，
保存时从当前快照保留服务端配置，没有增加配置键或修改网络、存档、影子端与光照逻辑。

Cloth Config 仍为可选依赖，不内嵌进生产 JAR。Gradle 使用 `compileOnly` 编译接口、
`runtimeOnly` 为开发运行提供库；Fabric 开发运行同时带入 Mod Menu。
不使用 26.x 构建链已不提供的 `modCompileOnly` / `modLocalRuntime` / `localRuntime` DSL。
Cloth 不传递其 Maven 依赖，避免改变已选的加载器与 Fabric API；发行 JAR 自带的
basic-math 嵌套依赖由相应加载器发现。

| Minecraft | Fabric Loader | Mod Menu | NeoForge | Cloth Config（两个加载器） |
|---|---|---|---|---|
| 26.1.2 | 0.19.3 | 18.0.2 | 26.1.2.112 | 26.1.154 |
| 26.2 | 0.19.3 | 20.0.3 | 26.2.0.90 | 26.2.155 |
| 26.3 | 0.19.5 | 21.0.0 | 26.3.0.76 | 26.3.159 |

版本值继续来自 `versionProperties`，本轮未改动版本号。

## 2. 构建与产物验证

三个版本分别完成 `build scanVersionBoundaries`：六个加载器组合全部成功；每锚点
462 项 Java 测试，共 1386 项，失败、错误、跳过均为 0。
生产 JAR 均包含配置屏及 `Draft`，未打入 Cloth Config 类或 Cloth Config 嵌套 JAR。
原有 zstd-jni 包装检查通过。

证据根目录：`build/gui-config-20261020/`。

- `build-26.1.2.log`、`build-26.2.log`、`build-26.3.log`：完整构建与扫描。
- `tests.json`：从三份版本目录的 JUnit XML 汇总。
- `artifacts.json`：六份 JAR 的路径、SHA-256 与配置屏/可选库内容检查。
- `package-<mc>.log`：实际 GUI 验证结束后的最终 JAR 打包与原生库复核。

## 3. 实际 GUI 验证

每个组合启动独立客户端目录，从真实 Mod Menu / NeoForge 模组列表打开配置屏，
将 `chunk.enabled` 从 true 改为 false，保存，重新打开确认显示 false，并按 F2 留存截图。
六场均完成后正常退出，Gradle wrapper 退出码均为 0。

| Minecraft | Fabric | NeoForge | 保存后服务端文件 SHA-256 |
|---|---|---|---|
| 26.1.2 | 通过 | 通过 | 两场均不变 |
| 26.2 | 通过 | 通过 | 两场均不变 |
| 26.3 | 通过 | 通过 | 两场均不变 |

服务端配置特意预设 `master.maxChunksPerTick=7`、`master.enabledOnLan=true`、
`storage.zstdLevel=5`、服务端 `chunk.seedGenEnabled=true`、`chunk.lightStrip=false`、
`compat.requireClientMod=true` 和 `compat.autoDowngradeOnError=false`。
初始化完成后记录文件哈希，保存 GUI 后逐场核对，防止重置隐藏服务端字段。
客户端落盘的 `chunk.enabled=false` 另有独立文件检查；NeoForge 26.3 额外打开渲染、生成
与调试分类验证渲染正常。

- `client-<loader>-<mc>.log`、`exit-<loader>-<mc>.json`：启动、保存、正常退出。
- `server-before-<loader>-<mc>.json`、`save-<loader>-<mc>.json`：保存前哈希与核验结果。
- `run/<loader>/<mc 下划线化>/screenshots/`：六场游戏内截图。
- `run-client.ps1` 与 `runtime.init.gradle`：本地验证启动器，隔离运行目录；未改动原有
  `fabric/run/client` / `neoforge/run/client` 的配置与世界。

初次 Fabric 26.2 离线启动缺少资源文件，在启动游戏前失败；补齐资源后重跑通过。
原失败保留于 `client-fabric-26.2-offline-assets-failed.log`，不计为界面通过。
NeoForge 26.2/26.3 的 `logoFile` 弃用警告属于元数据提示，继续进入主菜单后完成测试；
没有将该警告页本身作为配置屏验证。开发账户的 Realms 授权提示与 26.3 OSHI 系统信息
警告也不作为 GUI 故障；本轮没有改动这些独立问题。

## 4. 可选依赖回退

通过验证专用 init script，仅从运行依赖中排除 Cloth，保留已编译的配置屏类，模拟玩家
没有安装可选库的情况；没有删除 Gradle 缓存或修改生产依赖声明。

| 26.3 场景 | 结果 |
|---|---|
| Fabric + Mod Menu，无 Cloth | 正常启动；点击 Hassium 配置入口后保留模组列表，记录 `Cloth Config not available`，无崩溃 |
| NeoForge，无 Cloth | 正常启动；记录 `Cloth Config 未安装，跳过配置屏注册`，Hassium 配置按钮不可用 |

两场均正常关闭，wrapper 退出码为 0；结合六场 GUI，共八场客户端正常退出。
日志为 `client-<loader>-26.3-no-cloth.log`，退出码为对应 `exit-*.json`，截图位于
`run/<loader>/26_3-no-cloth/screenshots/`。GUI 不可用时仍可手改 TOML。

## 5. 交付范围

同步更新两份 README、`docs/mod-compat.md` 与 `docs/version-segments.md` 的配置入口和
可选依赖说明，清除构建脚本、工厂及共享 UI 中过时的停用、返回 null、默认覆盖服务端字段
和分类数量注释。IMBlocker 接入继续保持关闭。

本轮验证针对配置界面与可选依赖启动，没有启动服务器、加载世界或重跑网络/光照场景。
不变更 2.x 分支，不推送、打标签或发布。
