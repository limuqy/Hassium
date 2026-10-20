# OVD 缺盘本地生成恢复（2026-10-20）

## 实现与使用

用户要求恢复 OVD 缺失区块的本地生成，复用统一生成链，并同步 master / 2.0.0。两条工作区均已完成修改。

- 仍需双端 `chunk.seedGenEnabled` 开启、服务端许可与真实种子可用、影子引擎正常；客户端开启 OVD 且 clientRD > serverVD 才有环带。配置默认值没有改变，旧 `ovdLocalGeneration` 键保持删除。
- OVD 先用 injected / type126 盘。缺盘后复用 `submitLocalGeneration → ShadowSeedServer.generateChunkAsync → onChunkMaterialized`，共用 `sweepInFlight`、`generationInFlight` 和 `localGenExecutor`，没有增加独立队列、执行器或开关。
- 权威 acquire 先驱动；OVD 最多用共享容量的一半，为权威生成留槽位。OVD 失败仅本地冷却重试，不从该路径回退远端 FULL。进入权威窗后仍先 compare，再交付权威柱。
- 旧实例 / 非当前维度的回调不写新会话。光照复用、3×3 齐套门与 `pushReady` 门保留现有路径。
- 补修负小数坐标先向零截断造成的扫描偏移：`trackingPosition` 先 floor 再换算区块；`(-32.5, 0.5)` 必须得到 `(-3, 0)`，防止整列漏扫。
- 2.x 额外补齐 3.x 已有的 `generateChunkAsync` cacheOnly 前置拒绝，以及 Forge / NeoForge 冒烟 VD1/VD2 的 JVM 参数映射。

## 专项冒烟

`-Scenario ovdgen`：默认 VD4 / clientRD8、双端 SeedGen、清世界与客户端缓存，单轮退出。最新场景固定到负坐标边界 `(-32.5, 100, 0.5)`。`ovdGenerated` 只记录 OVD 缺盘生成入口成功的目标柱，不把权威生成 / 缓存回放混入。

门禁要求权威形状及一圈算光光环之外有新生成目标实际 applied 且驻留；整片外圈必须覆盖完整，不能仅生成一柱就通过。对应外圈生成目标不得有 networkReceived 内容来源。新增 Java 回归验证共享容量预留、同柱去重、失败本地重试和负坐标；Python 回归覆盖缓存 / 权威 / 光环伪命中、整列缺失、未驻留、网络来源、负坐标与无双窗。

`-Scenario ovdlight` 复用既有移动屋檐 / 洞口设施及双轮往返、重连路径，开启 SeedGen / OVD，默认 VD8→6、clientRD12；R1 断言 OVD 生成发生，双轮检查回黑。该场景需要另做实际运动画面目视验收。

## 静态验证

- 当前 26.x 三锚点各 475 项测试，共 1425；旧七锚点 1.20.1 为 485 项，其余各 478，共 3353。合计 **4778 项 Java 测试，0 失败 / 错误 / 跳过**。
- 两线 scanVersionBoundaries 通过；修复后的矩阵日志为各工作区 `build/ovdlight21-<版本下划线化>-matrix.log`。运行中不切换同工作区版本编译：Gradle 会删除上一输出目录中的 class，造成延迟加载 ClassNotFound。
- Python 门禁：master 63 项、2.x 57 项通过；日志 `build/ovdlight20-python.log`。
- 两工作区 git diff --check 通过。

## 冷场运行验证

| 版本线 | Session | OVD 新生成 / 已应用 | 采样驻留柱 | 结果 |
|---|---|---|---|---|
| master | `26.1.2_fabric_I_ovdgen2_20261020` | 128 / 128 | 289 | PASS |
| master | `26.1.2_neoforge_I_ovdgen2_20261020` | 124 / 124 | 289 | PASS |
| master | `26.2_fabric_I_ovdgen2_20261020` | 160 / 160 | 289 | PASS |
| master | `26.2_neoforge_I_ovdgen2_20261020` | 136 / 136 | 289 | PASS |
| master | `26.3_fabric_I_ovdgen_20261020` | 144 / 144 | 289 | PASS |
| master | `26.3_neoforge_I_ovdgen2_20261020` | 140 / 140 | 289 | PASS |
| 2.0.0 | `1.20.1_forge_I_ovdgen3_20261020` | 131 / 131 | 289 | PASS |
| 2.0.0 | `1.21.1_neoforge_I_ovdgen3_20261020` | 149 / 149 | 289 | PASS |
| 2.0.0 | `1.21.11_fabric_I_ovdgen3_20261020` | 149 / 149 | 289 | PASS |

所有上表样本均以最终完整外圈门禁重新核对，缺口为 0。26.x 六组合在 OVD 接入阶段完成；随后负坐标修复的最终源码经十锚点单测 / 编译，并在旧线运行验证。旧线后两场含最新固定负坐标步骤。结果 / 探针 / 日志分别在各工作区 `build/smoke-test/results`、`probe`、`logs`。

## 移动光照故障与修复过程

用户确认了实际光照故障。`ovdlight2`–`ovdlight10` 的自动与目视记录均保留；停稳时内容正确或旧门禁 PASS 不能代替运动验收。新增每 tick 地板、屋檐天光、深洞天光和萤石采样，任何异常都判 FAIL。

已修复并同步两线：OVD 已投递不代表权威确认；保存优先当前注入柱，拒绝迟到 native save 覆盖；DELTA 离线换节、逐格 checkBlock 后重过 INITIALIZE/3×3/LIGHT；临时生成票卸载不清仍驻留柱光层，真正摘表时释放；重连先等待旧影子端关闭；飞行固定 Y298。第十轮加入权威进入边沿重验后，R1 movementFloorMissing=0，但仍有漏天光，仍为 FAIL。

进一步定位：生成门开启时 scheduleChunkLoad 提前放行，依赖票可读旧 native 存档并重装光层。已补注入驻留柱优先返回 Imposter 的入口。第十二轮仍 FAIL：R1 缺块 14 / 漏光 173 / 暗光源 6 次，R2 漏光 35 次；FULL 日志证明服务端返回了不同内容，但客户端光复用快路径跳过替换。

最新修复：FULL 始终使用响应快照并携带权威 hash，以 REMOTE_PULL 投入既有 pending 消费队列；排队项保存自己的 hash，同柱旧光任务结束后替换；FULL 与 DELTA 保留旧光，通过 checkBlock 传播减光，变更后重新过 INITIALIZE / 3×3 / LIGHT。第十三轮因远端 Gradle 元数据等待未启动；第十四轮被同时切换版本编译清理类目录中断，不列为功能结果。第十五至十八轮仍抓到动态漏光，最终修复及数值证据见下方“数值先行验收”；目视与提交号在通过后补录。

## 首轮失败与修复证据

- `26.1.2_fabric_I_ovdgen_20261020`：功能断言通过，128 个 OVD 生成柱全应用；Mod Menu 联网更新检查 ERROR 挡日志门禁。测试目录关闭可选 update_checker 后，ovdgen2 冷场通过，原记录保留，未放宽产品错误门禁。
- 新增 2.x 单测最初使用 tryDetectVersion，与既有 BUILT_IN 初始化顺序冲突，夹具已按旧线惯例修正；保留 `build/ovdgen-1_20_1-bootstrap-failure.log`。
- `1.20.1_forge_I_ovdgen_20261020`：实际 VD20 / RD20，OVD 为空；修复 run 配置漏传 VD 参数。
- `1.20.1_forge_I_ovdgen2_20261020`：VD4 / RD8 生效，123 个目标全 applied，但完整门禁抓到西侧 17 柱漏扫；修复负坐标向零截断，ovdgen3 通过。两次失败的 result / log 保留。

## XERJ 设计核对

community-* 中无 Minecraft 项目；仅在 XERJ 参考目录加载 [Johni0702/bobby@241f973106ee](https://github.com/Johni0702/bobby/tree/241f973106ee4e5552a5950d0a12433c07921c93)（74 文件，391151 B），检索 `FakeChunkManager.java:302` 的主线程消费与 `:544` 的后台读取 / 取消检查；已读取 LICENSE.md（GPL-3.0）。仅核对异步完成返回所属主循环及生命周期检查的设计，没有复制其队列或源代码，未提交任何反馈或项目资料。

## 交付状态

代码、测试、文档已同步到 master 与现有 2.0.0 工作区。两线数值验收通过后，用户目视确认“我肉眼看也没有异常光照了”；本次按两条分支独立提交交付，未推送或发布。与本任务无关的 logo-16x10.png、video/ 原状保留。

## 数值先行验收（2026-10-20 追加）

用户要求先导出测试位置和光照强度，数值通过后再目视。运行 `python scripts/smoke/export_ovd_light.py <SessionId>`，生成 `build/smoke-test/light-data/<SessionId>/test-positions.csv`、`round1-light.csv`、`round2-light.csv` 与 `summary.json`。计划路径含 753 个坐标 `(0,299,z)`，z=-56..696；地板检查 `(0,297,z)`。去重坐标日志覆盖屋檐和洞内，逐 tick 探针同时检查全部飞行过程，不能用最终站定画面替代。

- 屋檐核心 z=16..176：原版侧面入光为 6；封闭洞内 z=280..488：天光必须 0。光源中心 z=320/448 的方块光必须 10，前后 3 格按曼哈顿距离递减到 7。
- `26.3_neoforge_I_ovdlight19_20261020`：往返各 640 次连续采样；导出 472/471 个去重坐标，两端漏光/欠光/缺地板均 0，数值 PASS。此轮按最新指示没有做目视。
- 最后定位的遗漏：clean epoch 记在 Promote、早于 LIGHT 和打包，不能作为内容改变后邻柱快照已刷新的凭据；同柱屏障原先还在实际整柱打包前释放。现在屏障保留至影子主循环实际 pack/offer 之后；FULL/DELTA 内容变化把邻柱光标脏，并在既有 generated 队列中合并一次重算/重交付（忙碌也保留工作）。降级修复事件继续使用原有有界 epoch 规则，新增内容事件不再错误跳过邻柱。
- 新增回归验证忙碌柱不丢排队替换，以及无 clean epoch / 邻柱忙碌时仍登记邻柱刷新、清除旧光复用资格。
- `1.20.1_forge_I_ovdlight20_20261020`：640/641 次连续采样，导出 471/471 个去重坐标；双端屋檐天光范围 [6,6]、洞内 [0,0]，光源中心方块光 10；全部异常数为 0，数值 PASS。
- 两线最新 R1 探针另外复核 `_check_ovd_generation_probe`：完整 OVD 外圈已应用并驻留、无网络内容来源，均无失败项；R2 是读盘回程，不要求重新生成。
- 用户在两线数值通过后确认目视无异常（原话：“我肉眼看也没有异常光照了”）。此确认作为最终目视验收记录；不把先前失败轮的截图或旧自动门禁冒充目视通过。

## 验收后的补丁升版（2026-10-20）

用户要求升一个小版本，两条维护线分别从 3.0.2 升到 **3.0.3**、2.1.2 升到 **2.1.3**。同步 `gradle.properties` 的 `mod_version` 与 `Constants.MOD_VERSION`（运行时常量此前仍为 3.0.1 / 2.1.1），加载器元数据继续由现有 Gradle `processResources` 展开。对应修复提交为 master `921efae6`、2.0.0 `1ec84336`；OVD 与光照验收证据沿用上述记录。

升版验证通过：26.3 与 1.20.1 的 `common:compileJava` 成功，编译后的 Constants.class 分别包含 3.0.3 / 2.1.3；26.3 Fabric/NeoForge、1.20.1 Fabric/Forge 与 1.21.11 NeoForge 的资源展开版本逐项核对一致。日志为 `build/version-3_0_3-check.log`、旧线 `build/version-2_1_3-check.log` 及 `build/version-2_1_3-neoforge-resources.log`。
