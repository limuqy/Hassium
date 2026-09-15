# 区块管线跨版本移植报告（2026-08-30）

## 结论

- 七个有效锚点的编译矩阵已通过：`compile-anchors.ps1` 最终输出 `compileAnchors: all anchors OK`。
- 1.20.1 Fabric 的 shadow pull、光照应用与 Round2 缓存命中门禁已通过。
- 尚未运行完整 34 格游戏冒烟矩阵；本报告不将编译通过等同于全矩阵运行时通过。

## 编译矩阵

脚本按每个 loader 独立 Gradle invocation 执行，避免多个 loader 共用一次 `common:compileJava` 时发生 Manifold/Loom classpath 串扰。

| 锚点 | 结果 |
|---|---|
| 1.20.1 | fabric / forge / neoforge PASS |
| 1.21.1 | fabric / forge / neoforge PASS |
| 1.21.2 | fabric / neoforge PASS |
| 1.21.5 | fabric / forge / neoforge PASS |
| 1.21.6 | fabric / forge / neoforge PASS |
| 1.21.9 | fabric / forge / neoforge PASS |
| 1.21.11 | fabric / neoforge PASS |

## 本轮代码修正
- 服务端 shadow pull range 校验允许配置的超视渲染范围，仍保留 server view distance + 1 的下限。
- 修复并补充 shadow pull 响应诊断；`FULL` 响应会记录应用结果并完成 loader ticket。
- shadow pull 请求携带客户端已知 `chunkHash`；服务端允许仅凭非零 chunk hash 命中 `UNCHANGED`。
- `UNCHANGED` 响应计入 `cacheHitFullChunkCount`，`FULL` 响应成功后回填本地 shadow hash。
- Smoke analyzer 仅在 Round2 无任何缓存命中且仍发生 full transfer 时报告退化。
- `scripts/compile-anchors.ps1` 改为按 loader 隔离编译。

## 运行时证据
会话：`1.20.1_fabric_shadow_cache_analyzer`

- Round1：`joined=true`，`stats=true`。
- 客户端日志出现多条 `SHADOW_PULL apply result kind=FULL ... success=true`。
- Round2：`stats=true`，缓存命中统计已出现，analyzer 通过。
- 脚本输出：`=== RESULT: PASS ===`，`Python analyzer: exit=0`，客户端退出码 `0`，`ServerSwitched: True`。
- 结果 JSON：`build/smoke-test/results/result_1.20.1_fabric_shadow_cache_analyzer.json`。

Round2 现在已验证新 shadow pull 路径的 hash 命中契约。剩余风险是其他版本/加载器尚未进行游戏内冒烟；锚点编译通过只证明源码适配，不证明运行时行为完全一致。
## 客户端统计数据

统计来源：各会话 `build/smoke-test/stats/*_round1_VD20.txt` 与 `*_round2_VD10.txt`。字段含义：`BW`=带宽压缩；`Cache`=区块缓存命中率；`Load`=区块加载数；`Light`=光照缓存命中率；`OVD`=超视渲染；`Save`=相对无 MOD 流量节省。

本表收录 2026-08-29 全矩阵 31 个已生成统计会话，并以 2026-08-30 通过的 `1.20.1 Fabric` 会话替换旧失败会话；`1.21.2 Forge` 与 `1.21.11 Forge` 为项目定义的 SKIP。

| 客户端 | Round1（VD20） | Round2（VD10） |
|---|---|---|
| `1.20.1_fabric_shadow_cache_analyzer` | BW 0.0%；Cache 0.0%；Load 1089；Light 0.0%；OVD OFF；Save 0.0% | BW 0.0%；Cache 99.8%；Load 2；Light 99.7%；OVD ON（渲染 16/10，已加载 636，缺失 0，影子复用 1223，环带服务 1859）；Save 98.6% |
| `1.20.1_forge_I_20260829` | BW 13.5%；Cache 0.0%；Load 1529；Light 0.0%；OVD OFF；Save 51.1% | BW 27.2%；Cache 99.5%；Load 2；Light 98.9%；OVD ON（渲染 16/10，已加载 632，缺失 0，影子复用 441，环带服务 1073）；Save 99.4% |
| `1.20.1_neoforge_I_20260829` | BW 13.0%；Cache 0.0%；Load 1529；Light 0.0%；OVD OFF；Save 50.5% | BW 67.1%；Cache 98.8%；Load 5；Light 94.6%；OVD ON（渲染 16/10，已加载 632，缺失 0，影子复用 394，环带服务 1026）；Save 98.7% |
| `1.21.10_fabric_I_20260829` | BW 23.4%；Cache 0.0%；Load 1529；Light 0.0%；OVD OFF；Save 51.0% | BW 69.2%；Cache 100.0%；Load 0；Light 98.2%；OVD ON（渲染 16/10，已加载 632，缺失 0，影子复用 477，环带服务 1109）；Save 99.6% |
| `1.21.10_forge_I_20260829` | BW 25.2%；Cache 0.0%；Load 1529；Light 0.0%；OVD OFF；Save 51.9% | BW 67.7%；Cache 100.0%；Load 0；Light 98.5%；OVD ON（渲染 16/10，已加载 632，缺失 0，影子复用 457，环带服务 1089）；Save 99.6% |
| `1.21.10_neoforge_I_20260829` | BW 12.2%；Cache 0.0%；Load 1529；Light 0.0%；OVD OFF；Save 50.0% | BW 56.8%；Cache 100.0%；Load 0；Light 98.7%；OVD ON（渲染 16/10，已加载 632，缺失 0，影子复用 474，环带服务 1106）；Save 99.6% |
| `1.21.11_fabric_I_20260829` | BW 31.6%；Cache 0.0%；Load 1622；Light 0.0%；OVD OFF；Save 55.4% | BW 61.5%；Cache 100.0%；Load 0；Light 98.8%；OVD ON（渲染 16/10，已加载 636，缺失 0，影子复用 428，环带服务 1064）；Save 99.6% |
| `1.21.11_neoforge_I_20260829` | BW 12.2%；Cache 0.0%；Load 1529；Light 0.0%；OVD OFF；Save 49.9% | BW 68.1%；Cache 100.0%；Load 0；Light 98.2%；OVD ON（渲染 16/10，已加载 632，缺失 0，影子复用 434，环带服务 1066）；Save 99.6% |
| `1.21.1_fabric_I_20260829` | BW 28.4%；Cache 0.0%；Load 1529；Light 0.0%；OVD OFF；Save 53.3% | BW 78.2%；Cache 100.0%；Load 0；Light 96.6%；OVD ON（渲染 16/10，已加载 632，缺失 0，影子复用 416，环带服务 1048）；Save 99.5% |
| `1.21.1_forge_I_20260829` | BW 28.8%；Cache 0.0%；Load 1529；Light 0.0%；OVD OFF；Save 53.6% | BW 63.8%；Cache 100.0%；Load 0；Light 98.4%；OVD ON（渲染 16/10，已加载 632，缺失 0，影子复用 436，环带服务 1068）；Save 99.6% |
| `1.21.1_neoforge_I_20260829` | BW 13.1%；Cache 0.0%；Load 1529；Light 0.0%；OVD OFF；Save 51.0% | BW 6.1%；Cache 100.0%；Load 0；Light 99.5%；OVD ON（渲染 16/10，已加载 632，缺失 0，影子复用 448，环带服务 1080）；Save 99.7% |
| `1.21.3_fabric_I_20260829` | BW 24.3%；Cache 0.0%；Load 1529；Light 0.0%；OVD OFF；Save 53.3% | BW 30.2%；Cache 100.0%；Load 0；Light 99.6%；OVD ON（渲染 16/10，已加载 632，缺失 0，影子复用 449，环带服务 1081）；Save 99.7% |
| `1.21.3_forge_I_20260829` | BW 26.4%；Cache 0.0%；Load 1529；Light 0.0%；OVD OFF；Save 53.9% | BW 55.9%；Cache 100.0%；Load 0；Light 99.0%；OVD ON（渲染 16/10，已加载 632，缺失 0，影子复用 442，环带服务 1074）；Save 99.7% |
| `1.21.3_neoforge_I_20260829` | BW 13.0%；Cache 0.0%；Load 1529；Light 0.0%；OVD OFF；Save 50.9% | BW 71.9%；Cache 100.0%；Load 0；Light 97.9%；OVD ON（渲染 16/10，已加载 632，缺失 0，影子复用 430，环带服务 1062）；Save 99.6% |
| `1.21.4_fabric_I_20260829` | BW 22.9%；Cache 0.0%；Load 1529；Light 0.0%；OVD OFF；Save 51.9% | BW 69.4%；Cache 100.0%；Load 0；Light 98.1%；OVD ON（渲染 16/10，已加载 632，缺失 0，影子复用 452，环带服务 1084）；Save 99.6% |
| `1.21.4_forge_I_20260829` | BW 25.4%；Cache 0.0%；Load 1573；Light 0.0%；OVD OFF；Save 53.6% | BW 53.0%；Cache 100.0%；Load 0；Light 98.9%；OVD ON（渲染 16/10，已加载 636，缺失 0，影子复用 462，环带服务 1098）；Save 99.6% |
| `1.21.4_neoforge_I_20260829` | BW 13.1%；Cache 0.0%；Load 1529；Light 0.0%；OVD OFF；Save 50.9% | BW 65.9%；Cache 100.0%；Load 0；Light 98.4%；OVD ON（渲染 16/10，已加载 632，缺失 0，影子复用 436，环带服务 1068）；Save 99.6% |
| `1.21.5_fabric_I_20260829` | BW 28.8%；Cache 0.0%；Load 1622；Light 0.0%；OVD OFF；Save 54.0% | BW 0.0%；Cache 100.0%；Load 0；Light 99.7%；OVD ON（渲染 16/10，已加载 636，缺失 0，影子复用 495，环带服务 1131）；Save 99.7% |
| `1.21.5_forge_I_20260829` | BW 27.2%；Cache 0.0%；Load 1529；Light 0.0%；OVD OFF；Save 52.3% | BW 73.3%；Cache 100.0%；Load 0；Light 97.6%；OVD ON（渲染 16/10，已加载 632，缺失 0，影子复用 446，环带服务 1078）；Save 99.5% |
| `1.21.5_neoforge_I_20260829` | BW 12.1%；Cache 0.0%；Load 1529；Light 0.0%；OVD OFF；Save 49.8% | BW 63.4%；Cache 100.0%；Load 0；Light 98.5%；OVD ON（渲染 16/10，已加载 632，缺失 0，影子复用 437，环带服务 1069）；Save 99.6% |
| `1.21.6_fabric_I_20260829` | BW 22.9%；Cache 0.0%；Load 1529；Light 0.0%；OVD OFF；Save 51.0% | BW 69.9%；Cache 100.0%；Load 0；Light 98.2%；OVD ON（渲染 16/10，已加载 632，缺失 0，影子复用 452，环带服务 1084）；Save 99.6% |
| `1.21.6_forge_I_20260829` | BW 23.0%；Cache 0.0%；Load 1529；Light 0.0%；OVD OFF；Save 51.4% | BW 69.9%；Cache 100.0%；Load 0；Light 98.2%；OVD ON（渲染 16/10，已加载 632，缺失 0，影子复用 479，环带服务 1111）；Save 99.6% |
| `1.21.6_neoforge_I_20260829` | BW 12.1%；Cache 0.0%；Load 1514；Light 0.0%；OVD OFF；Save 50.1% | BW 27.9%；Cache 100.0%；Load 0；Light 99.3%；OVD ON（渲染 16/10，已加载 622，缺失 0，影子复用 481，环带服务 1103）；Save 99.7% |
| `1.21.7_fabric_I_20260829` | BW 28.1%；Cache 0.0%；Load 1573；Light 0.0%；OVD OFF；Save 53.4% | BW 56.6%；Cache 99.9%；Load 0；Light 98.7%；OVD ON（渲染 16/10，已加载 636，缺失 0，影子复用 458，环带服务 1094）；Save 99.5% |
| `1.21.7_forge_I_20260829` | BW 24.8%；Cache 0.0%；Load 1529；Light 0.0%；OVD OFF；Save 51.4% | BW 68.0%；Cache 100.0%；Load 0；Light 98.2%；OVD ON（渲染 16/10，已加载 632，缺失 0，影子复用 433，环带服务 1065）；Save 99.6% |
| `1.21.7_neoforge_I_20260829` | BW 12.4%；Cache 0.0%；Load 1529；Light 0.0%；OVD OFF；Save 50.1% | BW 64.1%；Cache 100.0%；Load 0；Light 98.5%；OVD ON（渲染 16/10，已加载 632，缺失 0，影子复用 437，环带服务 1069）；Save 99.6% |
| `1.21.8_fabric_I_20260829` | BW 27.7%；Cache 0.0%；Load 1573；Light 0.0%；OVD OFF；Save 53.5% | BW 62.5%；Cache 100.0%；Load 0；Light 98.6%；OVD ON（渲染 16/10，已加载 636，缺失 0，影子复用 459，环带服务 1095）；Save 99.6% |
| `1.21.8_forge_I_20260829` | BW 24.7%；Cache 0.0%；Load 1529；Light 0.0%；OVD OFF；Save 51.4% | BW 63.4%；Cache 100.0%；Load 0；Light 98.8%；OVD ON（渲染 16/10，已加载 632，缺失 0，影子复用 484，环带服务 1116）；Save 99.6% |
| `1.21.8_neoforge_I_20260829` | BW 12.1%；Cache 0.0%；Load 1529；Light 0.0%；OVD OFF；Save 49.9% | BW 61.8%；Cache 100.0%；Load 0；Light 98.6%；OVD ON（渲染 16/10，已加载 632，缺失 0，影子复用 438，环带服务 1070）；Save 99.6% |
| `1.21.9_fabric_I_20260829` | BW 27.6%；Cache 0.0%；Load 1573；Light 0.0%；OVD OFF；Save 53.4% | BW 51.8%；Cache 100.0%；Load 0；Light 98.9%；OVD ON（渲染 16/10，已加载 636，缺失 0，影子复用 459，环带服务 1095）；Save 99.5% |
| `1.21.9_forge_I_20260829` | BW 27.7%；Cache 0.0%；Load 1573；Light 0.0%；OVD OFF；Save 53.3% | BW 3.7%；Cache 100.0%；Load 0；Light 99.6%；OVD ON（渲染 16/10，已加载 636，缺失 0，影子复用 468，环带服务 1104）；Save 99.7% |
| `1.21.9_neoforge_I_20260829` | BW 12.1%；Cache 0.0%；Load 1529；Light 0.0%；OVD OFF；Save 49.9% | BW 62.5%；Cache 100.0%；Load 0；Light 98.6%；OVD ON（渲染 16/10，已加载 632，缺失 0，影子复用 438，环带服务 1070）；Save 99.6% |

| `1.21.2_fabric_I_20260829_fix` | BW 24.4%；Cache 0.0%；Load 1529；Light 0.0%；OVD OFF；Save 53.3% | BW 0.0%；Cache 100.0%；Load 0；Light 99.7%；OVD ON（渲染 16/10，已加载 632，缺失 0，影子复用 450，环带服务 1082）；Save 99.7% |
| `1.21.2_neoforge_I_20260829_fix` | BW 13.0%；Cache 0.0%；Load 1529；Light 0.0%；OVD OFF；Save 50.9% | BW 71.3%；Cache 100.0%；Load 0；Light 97.8%；OVD ON（渲染 16/10，已加载 632，缺失 0，影子复用 429，环带服务 1061）；Save 99.6% |

### 统计文件索引

- 最新 1.20.1 Fabric：`build/smoke-test/stats/1.20.1_fabric_shadow_cache_analyzer_round1_VD20.txt`、`1.20.1_fabric_shadow_cache_analyzer_round2_VD10.txt`。
- 其余矩阵会话：`build/smoke-test/stats/<version>_<loader>_I_20260829_round{1,2}_VD{20,10}.txt`。
- 最新结果：`build/smoke-test/results/result_1.20.1_fabric_shadow_cache_analyzer.json`。
