# 冒烟黑块交接（2026-08-08）

> 本文档记录本次冒烟会话的完成项、未解问题与验证数据，供后续会话接手。
> 相关技能：`hassium-client-light-calibration`、`hassium-client-lag-stack-sampling`、`hassium-light-cache-writeback-timing`。

## 已完成（代码未提交，见文末）

### 1. 冒烟时长：R1 20s / R2 10s
- `ClientSmokeTest.java` 51/81 行 + 23 行注释、`runtime-smoke-test.ps1` 21 行 `$DelayMs = 10000` 已改。
- 内建语义：ROUND1 等待 = `delayMs×2`，ROUND2 = `delayMs`，单值实现，不引入第二个参数。
- `$JoinTimeoutMs` 必须 ≥ `delayMs×2`（classic 模式 ROUND1 超时从客户端启动算起）。
- 验证：1201f_delaycheck 等待日志 `waiting 20000/10000 ms`。

### 2. R2 视距 8→10（顺带）
- `ServerSmokeTest.java` 30/50/84 行 vd2=10；墙宽 [-128,128)→[-160,160)（VD10 半径）。
- 脚本 stats 文件名 round2_VD8→round2_VD10（749/750/847 行）、`switched to 8`→`to 10`（772 行）。
- 用户澄清「我说的是时间」后未要求回滚 VD，保留。

### 3. fabric R1 断连 dump 竞态修复（R2 光照 0%）
- 根因：R1 断连（ch.close() 模拟被动断连）→ Netty 线程 fabric DISCONNECT → cleanupOnDisconnect 排队 cleanupMain（mc.execute + latch.await 15s）→ 主线程 tick 链（onDisconnect→clearLevel TAIL）同步抢先执行 finalize（storage close + dirty clearAll）→ pollTask 的 cleanupMain 因 storage 已关 dump 全丢 → R2 光照 0%。
- 修复：`ClientLifecycleHelper` 新增 `CLEANUP_MAIN_PENDING_NANO`（AtomicLong）；Netty 分支排队前置位、cleanupMain finally 清除；`finalizeDisconnectIfTerminal()` 开头 pending<15s 时让位 return，由断连方 latch 后排队的 finalizeIfTerminal 收尾（fabric/forge/neoforge 三端 157/170 行均有 `client.execute(ClientLifecycleHelper::finalizeDisconnectIfTerminal)`）。
- 15s 兜底防主线程永不执行；非 Netty 排队路径（手动登出）不设 PENDING，行为不变。
- 验证（R2 光照命中率）：1.20.1 fabric 0%→54.6%（dump queued=78）、1.21.11 fabric 0%→24.1%、1.20.1 neoforge 10.6%→56.4%（dump queued=75，原 queued=2 问题同步解决）、1.21.1 fabric 24.2%（dump queued=79）。

### 4. 卡顿根因实证（用户观察冒烟客户端卡顿）
- 1201n_stack 场 8 次 jcmd 采样：Render thread = `HassiumLightHooks.safeRunLightUpdates ← ClientLightRecomputeService.applyLightEngine(153) ← ClientLightBufferQueue.consume(176) ← drainFrameSync(163) ← drainFrame(99) ← MixinMinecraft onTick`。
- **safeRunLightUpdates = `lightEngine.runLightUpdates()` 无预算全量传播**；cpu=16296ms/elapsed=23.71s（69% 满载）。
- fabric/neoforge 同机制同量级（R1 进服主线程 9-13s 光照传播），非加载器特有。
- 已知修复方向（技能 hassium-client-lag-stack-sampling）：①批尾传播加帧预算/迭代上限（官方每 tick 分片，中途退出安全）②capture/assemble 后台化 ③SNAPSHOT_CACHE_MAX evict 放大。
- 注意：帧预算修复缓解 R1 卡顿但**加重 R2 黑块窗口**（传播更慢），不可直接套用。

## 5. R2 黑块根因闭环：R1 覆盖 → 卸载写光 → R2 命中（当晚场，用户目标=命中率，未动帧预算）

> 下方「进行中：R2 黑块」一节为旧诊断链，已被本节闭环修正（is_light_on=0 结论为解析器误报）。

### 根因链（修正后，全链路实证）
1. **磁盘光写盘机制一直正常**：v1 解析器在 `block_entities` list 处 bail 误报 0/488；v3 正则
   `\x01\x00\x0bis_light_on(.)` 审计 **488/488 全 is_light_on=1**（1211f_fix 会话终态，4 个 r.*.mca）。
2. **R2 命中率 ∝ R1 加载覆盖率**：MixinClientLevel unload → CacheSaveQueue.enqueue（fromLiveUnload=true）→
   processTask 313-316 无条件 clear + light-patch 写引擎光。1.20.1 R1 加载 969 块 > 客户端 RD12 环带 →
   890 次卸载写光 → R2 命中 885。1.21.x 只加载 479 块（≈RD 内无卸载）→ 会话中零写光 → 仅 dump 79 光 →
   命中 256。**旧 1.21.x 会话 32/s 供给是唯一上游变量。**
3. **服务端 pushPool 不是瓶颈（计时实证）**：新增 [SERVE-DIAG]（每 256 块打印 build/hash/encode/send 分段）：
   `build=0.02ms hash=0.05ms encode=0.01ms send=0.3ms` ≈ 0.4ms/块。旧「哈希/编码 60ms/块」假设错误。
   供给速率受客户端主线程请求回程限制：光照重算 12.45ms/块（旧场）时 32/s；2.7-4.5ms/块（新场）时 52-70/s。
   12.45→4.5ms 差异为机器/采样器状态（同世界同代码同 seed=42），非版本差异。
4. **修复：客户端 settle 写回**（`CacheSaveQueue.tickSettleWriteback` + `MixinClientTick` TAIL）：
   加载风暴停止（光照队列空 + authorityLoad==0 + readySize==0 + 安静≥2.5s）后每 20 tick 预算切片（10ms）
   light-patch 脏块；**跳过哈希复算**（磁盘 NBT 沿用 ingest 元数据 hash，客户端/服务端 chunkHash MISMATCH 自愈）。
   幂等，仅脏表非空时工作；风暴期光照队列非空天然跳过 → 不写未收敛光（38e297e 收敛语义保持）。
   空档期安全网：R1 断连 dump 仍是最终兜底。

### 结果（同配置同 seed=42）
| 场次 | R1 加载 | R2 缓存命中 | R2 光照命中 | R2 加载 | 说明 |
|---|---|---|---|---|---|
| 1211f_fix（旧） | 479 | 75.7% | 24.2%（256） | 88 | 基线 |
| settlefix_1211f | 1399 | 98.5% | 60.6%（905） | 0 | 暖世界（单会话不清档） |
| settlefix_1211f_cw | 1044 | 87.8% | 42.4%（673） | 0 | CleanWorld 预生成恢复（同旧场条件） |
| settlefix_1201f | 2028 | 98.0% | 55.9%（987） | 10 | 1.20.1 回归，无回归（旧 54.6%） |

### 剩余差异（下一课题，若需完全拉平）
- 1.20.1 R1 供给 ~100/s（trackChunk 直发单程）vs 1.21.x 52-70/s（hash→compare→request 双程、客户端回程限速，
  且 drain 4/tick 封顶 80/s）→ R1 覆盖 2028 vs 1044-1399 → R2 命中差 42-60% vs 56%。
- 可选方向（**不动帧预算**）：①空 bloom/冷缓存时 1.21.x 直发加速（跳过 hash 回程）②hash-compare/request 去主线程化
  ③R1 期间放宽卸载写光的收敛 gate。OVD 1.21.x 缺失 120 为独立课题（R1 覆盖不足的远端表现）。

## 进行中：R2 黑块（用户最新反馈「R2 出现非常多黑块，R1显示正常」）

### 根因链（旧链，已被 §5 闭环修正；is_light_on=0 为解析器误报）
1. R1 写盘块 `is_light_on=0`：apply 后实时序列化时引擎光未收敛/未重算 → 落盘无光。`consume` 重算后**只 markDirty 不写盘**（38e297e 设计：磁盘光仅由卸载/断连 dump 捕获收敛态，防海底 sky15 污染光）。
2. R2 读回 `hasLight=false`（`ClientCacheLoadQueue` 201/325 行）→ apply 前预提交重算入队（`ClientLightBufferQueue`）→ 排队期黑块，帧预算每帧 1-6 块 × 12.45ms → 逐帧点亮。
3. R1 断连 dump 只写 dirty 块（1211f_fix 场 queued=79 + dirtyLeft=38，但 R1 重算 753 次）→ R2 命中仅 24%。

### 未解差异（下一步重点）
- **1.20.1 命中 54-56% vs 1.21.1/1.21.11 仅 24%**（两 1.21.x 版本几乎相同 24.1%/24.2%，暗示共同机制非随机）：
  - hash MISMATCH 根因：R1 实时写盘的光收敛性？hash 计算版本差异？`dirtyLeft=38` 未入队？
  - R1 重算 753 次但 dirty 仅 117（79+38）：636 块 dirty 被谁清了——`CacheSaveQueue` 314/316 行写盘 clear（`fromLiveUnload` 或 `isLightOn`）是唯一非 clearAll 路径，但实时写盘 NBT 无光不应 clear；需查 enqueue 序列化时机与 live-unload 路径。
- 无 HASH-DIAG 开关（已搜）；DebugConfig 在 `HassiumConfig.java:298-330`（`cacheLogging` 等），可考虑开 debug 重跑（90s/场）定位 hash MISMATCH。
- 1.21.1 R2 统计 1056（命中256+重算800）> 区块处理 811——口径可能跨阶段，需核对。

### 缓解选项（需评估）
- R2 期间放大光照消费预算（黑块窗口缩短，代价主线程占用）。
- hash 匹配块直接命中不重算（改动 apply 路径）。
- 恢复受控缓存光预灌（原注释理由：磁盘光可能未收敛/空光字段，灌入显示错误亮度；重算完成原子落地新光——恢复需严格 gate）。

## 验证数据总表（新参数 + 让位修复后）

| 场次 | 版本/加载器 | R2 光照命中 | 备注 |
|---|---|---|---|
| 1201f_delaycheck | 1.20.1 fabric | — | 旧 jar（vd2=8），delayMs 生效确认 |
| 1201f_delaycheck2 | 1.20.1 fabric | 0% | 竞态命中（修复前） |
| 1201f_diag2 | 1.20.1 fabric | 58.6% | DIAG 证据场，dump queued=115 |
| 1201f_fix | 1.20.1 fabric | 54.6% | dump queued=78；R1 重算 1355/12868.8ms/9.50ms |
| 12111f_fix | 1.21.11 fabric | 24.1% | 命中 193/重算 607；R1 重算 652/9046.9ms/13.88ms |
| 12111f_stack | 1.21.11 fabric | — | R1 重算 596/8424.1ms/14.13ms（与 fix 场几乎一致） |
| 1201n_fix | 1.20.1 neoforge | 56.4% | dump queued=75；R1 重算 1337/9482ms/7.09ms、区块 949 |
| 1201n_stack | 1.20.1 neoforge | — | 抓栈场（主线程 safeRunLightUpdates 实证） |
| 1211f_fix | 1.21.1 fabric | 24.2% | 命中 256/重算 800；R1 重算 753/9373ms/12.45ms、区块 479；R1 dump queued=79、dirtyLeft=38；R2 段 LIGHT-FLUSH nodes=0、无 LIGHT-SEG |
| settlefix_1211f | 1.21.1 fabric | 60.6% | 命中 905/重算 588；R1 加载 1399/重算 1170/4.26ms；R2 缓存 98.5%、加载 0、OVD 缺 120；settle +53、dump queued=70 |
| settlefix_1211f_cw | 1.21.1 fabric | 42.4% | CleanWorld；命中 673/重算 916；R1 加载 1044/重算 1283/4.48ms；R2 缓存 87.8%、加载 0、OVD 缺 120；settle +96、dump 92+52 |
| settlefix_1201f | 1.20.1 fabric | 55.9% | CleanWorld；命中 987/重算 779；R1 加载 2028/重算 1771/3.63ms；R2 缓存 98.0%、加载 10、OVD 缺 7；dump 151+45（卸载写光 ~1300 主导，无 settle 行） |

## 未提交改动（19 文件，与提交 b515eb3 无关）
```
M buildSrc/src/main/groovy/minecraft.gradle           （common 依赖 compileOnly + runtime 透传，§6 修复）
M forge/.../platform/ForgeNetworkManagerService.java  （sendPreHandshake 1.20.2+ 分段，§6 VSCode 小节）
M .vscode/launch.json                                 （modFolders → forge/build/classes/java/main，§6）
M common/.../cache/client/ClientLifecycleHelper.java   （让位修复）
M common/.../client/ClientSmokeTest.java               （delayMs）
M common/.../mixin/MixinClientConfigurationPacketListenerImpl.java（pre-handshake Bug 2/3/4 之一）
M common/.../platform/services/INetworkManagerService.java（pre-handshake）
M common/.../server/ServerSmokeTest.java               （vd2=10）
M common/.../cache/client/CacheSaveQueue.java          （settle 写回 tickSettleWriteback/enqueueSettled）
M common/.../client/ClientMainThreadBudget.java        （lastApplyNano）
M common/.../client/ClientLightBufferQueue.java        （isEmpty）
M common/.../client/ClientChunkDirtyTracker.java       （snapshot）
M common/.../mixin/MixinClientTick.java                （onTick TAIL → tickSettleWriteback）
M common/.../network/ServerChunkPushManager.java       （[SERVE-DIAG] 计时，保留：供给瓶颈归因证据）
M docs/runtime-smoke-test.md  M docs/stats-analysis.md
M forge/.../ForgeNetworkManager.java  M forge/.../ForgeNetworkManagerService.java（pre-handshake 三/四）
M scripts/runtime-smoke-test.ps1  M scripts/runtime-smoke-test-batch.ps1
```
pre-handshake 修复（Bug 2/3/4，git diff +92/-19，4 文件）是否提交待确认。

## 6. neoforge runClient/runServer 启动崩溃（2026-08-08 下午，用户报障）

### 现象
- `:neoforge:runClient`（1.21.1 / 1.20.1）启动即崩：`InvalidInjectionException` — `hassium$freezeDisconnectWithProgress` 找不到
  `disconnectWithProgressScreen`（1.21.1 的 Minecraft 无此方法）等。
- `:neoforge:runServer -Pmc_ver=1.20.1` 也崩：`Invalid descriptor on onPlayerDisconnect()V`（期望 (Component, CallbackInfo)）。

### 根因（实锤链）
1. `common/build/libs/hassium-common-*.jar` 是 **remap 产物**（jar 内 `MixinServerGamePacketListenerImpl.onPlayerDisconnect(net.minecraft.class_9812, ...)` —
   intermediary 映射实锤）。loom 对 `minecraft.gradle` 的 `implementation(project(":common"))` 在 dev classpath/module path
   解析为该 jar。
2. dev 环境期望 named（mojmap）字节 → mixin 注解/签名与 1.20.1/1.21.1 目标错配 → 崩。
3. 崩溃 handler 显示"1.21.6+ 段"的假象：加载的是 remap 版 jar 里**别的版本段/映射**的字节（bin/main 的 IDE 输出
   manifold 未预处理全段共存是另一污染源，本次未涉及）。
4. 服务端尤甚：runServer 的 module path 只有 common jar（无 neoforge/build/classes 合并产物），必中招；
   runClient 因 neoforge/build/classes 在前而侥幸正常（此前 1.20.1 冒烟正常的原因）。

### 修复
- `buildSrc/src/main/groovy/minecraft.gradle`：`implementation(project(":common"))` → `compileOnly(project(":common"))`
  + 显式透传 common 的 runtime 依赖（zstd-jni / lz4-java / night-config core+toml，与 common/build.gradle 同坐标；
  fabric 自声明同坐标自动去重）。运行期类由 loader 模块合并编译产物提供（srcDirs 合并 + jar from output 不变）。
- 原因：loom project 依赖解析成 build/libs 的 remap 产物；compileOnly 只进编译 classpath，dev runtime 用
  neoforge/build/classes（named 合并版）。

### 验证（全部 PASS）
- neoforge 1.21.1 runClient：Setting user: Dev / 0 FATAL
- neoforge 1.20.1 runClient：同
- neoforge 1.20.1 runServer：Done (2.105s)
- fabric 1.20.1 Phase R 冒烟 depfix2_1201f：PASS
- 踩坑：首次修复只改 compileOnly → fabric server `NoClassDefFoundError: night-config`（common 传递依赖丢失）→
  补显式透传解决；跑冒烟前需清残留 java（端口占用假崩）。

### VSCode 处理（同一根因的 IDE 路径）
- 现状：`.vscode/launch.json` 的 forge F5 配置把 `-Dfml.modFolders` / `MOD_CLASSES` 指向 `forge\bin\main`
  （VSCode Java 插件输出目录，manifold 预处理不生效 → 全段混合 class → 启动崩）。
- 已改：6 处全部指向 `forge\build\classes\java\main`（gradle 合并编译产物，named + 分段正确）。
- 已删：`bin/`、`fabric/bin/`、`forge/bin/`（陈旧污染源；.gitignore 已有 bin）。
- **F5 调试暂不可用**：launch.json 引用 `forge\build\moddev\*.txt`（devlaunchinjector 产物，neoforge 机制），
  forge 1.20.1 loom 不生成 → 找不到 argfile。VSCode 跑 forge dev = 终端 `./gradlew.bat :forge:runClient
  "-Pmc_ver=1.20.1"`（已验证正常）；F5 调试需另配 loom 生成的 dev 参数或 Java Attach。
- 顺带修复：`forge/.../platform/ForgeNetworkManagerService.java` — sendPreHandshake 覆写加 `#if MC_VER >= MC_1_20_2`
  （ForgeNetworkManager 的方法定义在 1.20.2+ 外层块，1.20.1 编译找不到符号 → forge 1.20.1 首次编译失败）。
- 验证：forge 1.20.1 runClient（Setting user: Dev）+ runServer（Done 1.78s）均 PASS。


- 冒烟全串行（用户「并行太卡」）；同项目 gradle 不可并行。
- 抓栈：进程匹配 `launch.cfg` + `env=client`；jcmd = `D:\app\graalvm-jdk-21.0.5\bin\jcmd.exe`；输出 UTF-16 需 iconv。
- 冒烟脚本经 gradle 增量编译，改 Java 后需 `common:compileJava`。
- Windows 共享机器，`sd` 命令不存在（批量替换用 python）。
- 临时诊断日志（[DIAG-CLEANUP]）已删。
