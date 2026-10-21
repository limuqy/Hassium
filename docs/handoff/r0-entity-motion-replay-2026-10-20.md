# R0：实体运动流回放与恢复基线验证

日期：2026-10-20。分支：`2.0.0`。上游：[AFK 实体同步方案](research-2026-10-20-afk-entity-sync.md)。

结论：**1.20.1 Forge、1.21.1 NeoForge 来源的原生流均通过 R0 离线回放及各自 MC 原生运动 codec 核对**。正常观察者保持原包字节、顺序和捕获时间戳；模拟 AFK 观察者经快照/分批恢复后，位置解码基线、身体/头部朝向、速度及 ground 与完整流参考状态一致。错误基线和错误恢复顺序的反例被拒绝。

本轮仅新增离线回放脚本、测试源码和测试任务，**未安装游戏钩子，未实现 AFK 识别或实际降频，没有修改线上发包策略**。R1/R2/R3 尚未开始。

## 1. 输入与验证范围

复用[首轮流量画像](traffic-profile-2026-10-20.md)已通过完整检查的两个专用服—单客户端采集：

| MC / 加载器 | 来源场次 | 原生 JSONL |
|---|---|---:|
| 1.20.1 Forge 47.4.21 | `traffic_1201_forge_20261020_d` | 135,907,083 B |
| 1.21.1 NeoForge 21.1.236 | `traffic_1211_neoforge_20261020_a` | 141,427,415 B |

读取 `native_v3` 完整原生包字节，先检查原测量场次的版本/加载器、冒烟 PASS、画像检查和采集 SHA256，不读取聚合载体来冒充原生包。未启动新游戏，未操作用户世界或运行配置；1.20.1 仍不使用用户 1.21.1 存档。

在每条原捕获连接之上建立两个虚拟混合观察者组合：“正常近端＋AFK 远端”和“AFK 近端＋正常远端”，使用不同模拟挂机/唤醒相位。这里的近/远是交换角色的标记，**不是新增的距离/视锥判断实现，也不是真实双客户端联调**。

## 2. 两层独立检查

### 离线接收模型

入口：[entity_motion_replay.py](../../scripts/smoke/entity_motion_replay.py)。

- 全量原生流推进参考接收状态；模拟 AFK 分支只在离线模型中合并运动，定期快照，恢复未轮到的实体继续隔离。
- 正常分支逐包校验原始字节、顺序和时间戳轨迹 SHA256，并逐步比较已配对实体状态；最后核对所有观察者状态。
- 出生、销毁、换世界、实体 ID/UUID、生命周期代与独立跟踪代参与恢复令牌校验。
- Bundle 作为原子组处理，不将恢复项插入配对 Bundle 内；运动流恢复边界置于原子组之前。
- 源码中的时间表与 HOLD 仅是回放刺激，不接客户端或服务端实际连接，不属于 AFK 识别器。

### 对应版本原生 codec

入口：[原生核对程序](../../common/src/test/java/io/github/limuqy/mc/hassium/protocol/replay/EntityMotionReplayNativeCheck.java)与[测试适配层](../../common/src/test/java/io/github/limuqy/mc/hassium/compat/EntityMotionReplayNativeCompat.java)，均位于 `src/test`。

对全部六类运动包及模型生成的快照，调用该 MC 版本真实原生 decoder/encoder，检查字段和逐字节往返。再用该版本真实 `VecDeltaCodec` 检查每个已有配对状态的前/后基线，包含非位置包不改变基线、零轴保留原始非网格坐标的规则。

六类为位置、身体旋转、位置＋旋转、绝对传送、速度、头部旋转。旧版 `pos` / `pos_rot` 等类型名被统一识别，数值 packet ID 保留原始字节，不猜测协议编号。补充夹具的 ID=0 仅作为不透明头部，按显式指定的运动类型调用 codec，不宣称其为真实注册表 packet ID。

最终合并必须同时满足模型与原生检查 PASS、版本/加载器/来源一致、包数/种类覆盖一致、原生请求流 SHA256 一致，以及原生反例检查通过。只有模型通过而原生检查未执行时仍是 PENDING。

## 3. 正式结果

仅纳入最终 `_c` 回放；开发期 `_a` / `_b` 留在忽略目录，不作为最终结论。小型可追溯结果见[结果 JSON](r0-entity-motion-replay-2026-10-20-results.json)，含输入与代码哈希。

| 指标 | 1.20.1 Forge 来源 | 1.21.1 NeoForge 来源 |
|---|---:|---:|
| 实际采集的已配对运动包 | 443,563 | 537,709 |
| 实际采集出生 / 销毁包 | 20,188 / 19,963 | 20,300 / 20,102 |
| 实际采集 Bundle delimiter | 40,376 | 40,600 |
| 原生 codec 字节往返检查总数 | **683,870** | **815,111** |
| 其中模拟快照包 | 239,892 | 276,987 |
| 其中额外线格式夹具运动包 | 415 | 415 |
| 接收基线状态检查 | 523,942 | 630,453 |
| 原生错误恢复反例 | 2 个均识别 | 2 个均识别 |
| 模型 / 原生 / 最终合并 | PASS / PASS / PASS | PASS / PASS / PASS |

“接收基线状态检查”包含速度/旋转等包应保留原位置基线的核对，不等于这么多位置包。快照包数属于离线测试，不是新的实际上传统计。

两个正常观察者轨迹分别与相应输入一致；两版输入轨迹 SHA256 为：

- 1.20.1：`120b8da6680b811a7f7803e8be71f42def6237d7fd340ba690a271744dfb482e`
- 1.21.1：`f3c1fee9f9700bb798ca3c58314a0322b3c3d15baf3725b3b1d968fe75e6dc86`

模拟恢复阶段仍被隔离、等调度器修复的运动包：1.20.1 两个角色分别为 1605 / 1315 个，1.21.1 为 2142 / 2355 个。它们没有因为玩家已逻辑唤醒就提前进入相对流；调度到该实体时读取最新账本，而非入队时旧基线。

## 4. 反例、边界与修正

16 项 Python 回归检查通过，覆盖：

- 负数半网格的 Java 取整与零轴保留；signed short 边界、负身体/头部角度、速度归零和原地转身。
- 长短两种类型命名、截断/尾部异常、未知 Mod payload 不误判为原生运动。
- 错把实体当前 x=1.25 当作广播基线，接收以 x=1.00 为基准的 +0.50 增量会得到 1.75，而正确值为 1.50；模型与原生 codec 都检测出这一差异。
- 把应先于相对包的旧快照延后应用，导致已正确推进的基线被覆盖；完整状态检查拒绝该结果。
- 排队后实体继续移动，恢复时重新取最新基线；正常观察者受全局门影响时轨迹校验会失败。
- 实体 ID 复用、同 UUID 换世界、单个观察者离开后重新跟踪：旧令牌不能修复新代，也不能清除新代的待恢复记录。
- Bundle 边界之前对齐；分批恢复预算耗尽时，余下实体继续隔离，而非遇到新相对包就绕过预算。
- 最终报告拒绝模型/原生检查使用不同请求流的情况。

原生核对抓到并修正了测试适配层的一处真实 API 差异：**1.20.1 `ClientboundSetEntityMotionPacket.getXa/Y/Z` 返回编码整数，客户端除以 8000；1.21.1 getter 已返回实际速度**。统一读取而不适配会出错。修正集中在测试 compat，未更改游戏代码。

还修正了回放模型“逻辑唤醒后，新包可绕过恢复预算”的问题；新增检查要求该实体一直隔离到恢复调度完成。最终结果全部来自修正后的模型。

## 5. 结论能支持到哪里

这次结果支持继续采用“正常分支保留原流，AFK 分支合并运动，在确定边界对齐基线后接回”的方向。在两份采集和补充夹具范围内，未出现恢复后相对位移偏移或正常分支轨迹改变。

仍需在后续阶段验证：

- R0 账本来自**原生接收流重建**，没有新增钩子采集 `ServerEntity` 的内部 B。未来真实服务端快照接口必须比较其量化基线与本参考模型，不能直接用 Entity 当前坐标代替。
- 未运行真实的两个观察者客户端，没有验证混合玩家的现有压力反馈、距离/视锥、Netty/主线程任务排序、客户端插值、实际恢复时延或带宽收益。
- 出生/销毁按已核对的原生线格式和模型生命周期处理；六类运动才使用原生 codec 核对。未构造完整注册表世界，也没有执行实际 `ClientPacketListener` / Mod 实体副作用。
- 实际配对 Bundle / 回调 / 模组异步发送、能力协商、识别误判和长时容量压力仍属于后续验收，不能用本次离线 PASS 替代。

本轮不估算省流百分比；没有把模拟截留包数当成实际上传节省。

## 6. 复现

PowerShell 7、Python 3.11+，在仓库根目录执行。先用新的输出目录生成回放请求，再串行调用对应版本的原生检查，最后合并验证。以下两版使用已保存的真实采集；若采集不在本机，先按画像文档生成目标版本的合格采集。

```powershell
python -m unittest scripts.smoke.test_entity_motion_replay -v
$runs = @(
    @{Ver='1.20.1'; Loader='forge'; Source='traffic_1201_forge_20261020_d'},
    @{Ver='1.21.1'; Loader='neoforge'; Source='traffic_1211_neoforge_20261020_a'}
)
foreach ($run in $runs) {
    $id = 'r0_' + ($run.Ver -replace '\.', '_') + '_' + (Get-Date -Format 'yyyyMMdd_HHmmss')
    $out = Join-Path (Get-Location).Path "build/entity-motion-replay/$id"
    python scripts/smoke/entity_motion_replay.py "build/traffic-profile/$($run.Source)" --version $run.Ver --loader $run.Loader --output $out
    if ($LASTEXITCODE -ne 0) { throw 'R0 model failed' }
    .\gradlew.bat common:entityMotionReplayNativeCheck "-Pmc_ver=$($run.Ver)" "-Pr0Requests=$out/native-requests.jsonl.gz" "-Pr0Report=$out/native-result.json" --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'R0 native codec failed' }
    python scripts/smoke/entity_motion_replay.py $out --finalize
    if ($LASTEXITCODE -ne 0) { throw 'R0 final evidence mismatch' }
}
.\gradlew.bat scanVersionBoundaries
```

实际验证环境使用随工作区提供的 Python 3.12 与 JDK 21。两版原生任务及版本边界扫描通过；没有为本轮启动 `runClient` / `runServer`，也未将这次结果记成运行时冒烟。

最终目录：`build/entity-motion-replay/r0_1201_forge_20261020_c/`、`build/entity-motion-replay/r0_1211_neoforge_20261020_c/`。每场保存 `model-result.json`、`native-requests.jsonl.gz`、`native-result.json`、`r0-result.json`；大文件留在忽略目录，结果 JSON 和本报告可随代码保存。

前置 XERJ 社区/已有参考语料检索未得到可直接采用的实体运动回放实现；本轮依据原版 codec、仓库现有原生字节导出和 BO 的生命周期设计参考独立实现测试工具，没有复制外部源码或向外提交项目资料。
