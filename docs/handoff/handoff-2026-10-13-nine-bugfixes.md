# 2.0.4：九项 BUG 修复与 3.0.0 移植

用户要求先在 `2.0.0` 分支修复，再移植到 `master`。2.x 从 `c589088a` 开始，版本号由 2.0.3 改为 **2.0.4**；保留原有七锚点和加载器矩阵。3.x 保持 **3.0.0**，按 26.x 的 Mixin 字段类型适配同一修复。未推送、未发布。

## 修复

| 审查项 | 最终行为 |
|--------|----------|
| A01：关闭存储后旧柱不可读 | type 126 读取独立于新格式写开关；客户端单人也能读取旧柱 |
| A02：超大区块 external stub 类型错误 | stub 在兼容层包头补丁之后取真实类型，外置 126 使用 254；读取支持正确 stub，也识别旧版错误 vanilla stub 下的 Hassium ZSTD 载荷 |
| A03：LAN 种子开关串域 | 快照分别保留 client/server SeedGen 值；保存、debug 更新和客户端 GUI 编辑均保留服务器值 |
| A04：Bundle 越过玩家信息 | Bundle 进入发送 FIFO；PENDING 不出线，激活后先行聚合帧先发送；客户端 Bundle 与聚合帧共享工人队列，再按序提交主线程 |
| A05：旧端清新端状态 | 新实例装配分配新代际，实例保存自己的代际；关停检查与共享状态清理同下一轮装配原子互斥 |
| A06：旧端关闭新端池 | worldgen/light 池按影子实例代际持有；旧端只释放自身池，主循环检查自身池 |
| A07：聚合帧超过 4096 子包 | 按条数和已有字节预算拆批；8193 小包回归用实际编解码验证零丢失 |
| A08：豁免实体失去强制同步 | 没有周期更新、或周期超过降帧上限的实体保留 needsSync/hasImpulse；3.x 同时处理 UpdateInterval.NEVER |
| A09：热更期间新玩家拿不到候选字典 | ACTIVE 与 OFFER ACK 均进入 rollout，ACTIVE ACK 触发补发进行中的候选 |

聚合 wire 格式不变；Bundle 应用顺序的完整修复需要客户端和服务端均更新。新增 **11 项回归测试**，覆盖上述边界、旧错误 external stub 和异步解码顺序。

## 2.x 验证

- 七锚点 `common:test`、`scanVersionBoundaries` 和 `builds_for` 中全部加载器编译通过：1.20.1 / 1.21.1 / 1.21.2 / 1.21.5 / 1.21.6 / 1.21.9 / 1.21.11，合计 18 个加载器组合。
- 单测：1.20.1 **394 项**，其余六锚点各 **387 项**；失败、错误、跳过均为 0。本地汇总在 `build/bugfix-unit-counts.json`、`build/bugfix-final-matrix.json`。
- 1.21.1 Fabric / Forge / NeoForge 的 2.0.4 打包与内嵌 zstd-jni 检查通过。
- Phase I classic 双轮连服/断连/重连：`bugfix9_211_2x_coldfix_20261013`（Fabric 1.21.1）和 `bugfix9_1201_forge_2x_20261013`（Forge 1.20.1）均 PASS。
- 首次新工作树的 `bugfix9_211_2x_20261013` 因脚本在创建运行目录前写 eula.txt 而未启动服务端。脚本补上目录创建；首次 FAIL 日志/结果保留，重跑使用独立 SessionId。

3.x 的提交与验证见 master 同名交接及 `docs/mc26-upgrade-plan.md`。本次 classic 冒烟没有替代移动屋檐/洞口目视验收。
