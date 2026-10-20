# 固定一环光照光环，删除用户配置

日期：2026-10-20。范围：`master` 与 `2.0.0`；未修改版本号。

## 决策与实现

用户要求删除 `chunk.lightHaloRadius` 配置项，内部固定为 1，并同步两条版本线。
光环用于补齐权威边界柱的 3×3 邻域，作为内部正确性约束保留。

- 删除 `ConfigSchema` 注册、`ChunkCoreConfig` 字段与默认值、快照转换和 Fabric TOML 读写。
- 删除 Cloth GUI 保存透传及中英文名称/提示；加载器原生配置界面从 schema 生成，因此同时移除该项。
- `ShadowTrackingSession` 的计算域、枚举盒与诊断直接使用 `ShadowPullRadii.LIGHT_HALO_RADIUS = 1`；删除配置读取/钳位方法及不再需要的可调半径上限常量。
- 旧 TOML 值（含 0）不再生效；Fabric schema 清理仍负责删除残留键。保存回归验证其他客户端设置不变。
- 更新配置审计、缓存域、光照流、冒烟说明及 analyzer 诊断；旧交接的参数化描述标明为历史记录。

当前 schema 共 **54 键**：CLIENT 25 / SERVER 29；前缀分布为 chunk 16、master 17、debug 17、storage 2、compat 2。

## 保留的约束

计算域仍是 `containsDilated(serverVD, 1)`，不用 `contains(serverVD + 1)` 近似；交付域仍为原版视距形状与 OVD 带的并集。
`LightNeighborhoodGate`、`ShadowLightCompute` 和 `ChunkShapeCompat` 本次均无代码修改；3×3 齐套门、缺邻不标光照完成、重触发与落盘判据，以及客户端算光完成推送门均保留。

## 验证

以下每锚点均运行 `common:test scanVersionBoundaries` 及该锚点全部 `builds_for` 加载器的 `compileJava`，Gradle wrapper 全部正常退出并报告 `BUILD SUCCESSFUL`。

| 分支 | MC 锚点 | 加载器 | Java 测试 |
|------|---------|--------|-----------|
| master | 26.1.2 | Fabric / NeoForge | 463 |
| master | 26.2 | Fabric / NeoForge | 463 |
| master | 26.3 | Fabric / NeoForge | 463 |
| 2.0.0 | 1.20.1 | Fabric / Forge | 473 |
| 2.0.0 | 1.21.1 | Fabric / NeoForge / Forge | 466 |
| 2.0.0 | 1.21.2 | Fabric / NeoForge | 466 |
| 2.0.0 | 1.21.5 | Fabric / NeoForge / Forge | 466 |
| 2.0.0 | 1.21.6 | Fabric / NeoForge / Forge | 466 |
| 2.0.0 | 1.21.9 | Fabric / NeoForge / Forge | 466 |
| 2.0.0 | 1.21.11 | Fabric / NeoForge | 466 |

合计 **4,658 项 Java 测试**，失败 / 错误 / 跳过均为 0，**24 个加载器编译组合**通过。
覆盖配置往返、旧光环键忽略/清理、schema 删键、固定光环签发边界及既有 `ChunkShapeDilationTest` 几何不变量。
`python -m unittest scripts.smoke.test_analyzer`：master 48 项、2.0.0 42 项均通过。
两分支中英文语言 JSON 均可解析，旧 GUI 翻译键已删除；`git diff --check` 通过。

各工作区构建日志：`build/light-halo-config/<mc_ver>.log`；JUnit 结果：`common/build/<mc_ver 下划线化>/test-results/test/`。
本次未启动游戏、未重新运行 GUI 或移动屋檐/洞口冒烟，不追加新的运行时目视结论。本次修改按用户要求分别本地提交到 master / 2.0.0，未推送或发布。
