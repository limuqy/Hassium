# REQ — 网络核心（进程内网关 + 纯原版客户端协议 + 无感主控切换）

> 状态：已确认（2026-08-09 多轮拍板汇总）
> 日期：2026-08-09

## 背景与目标

现状客户端网络侵入面：压缩/聚合/字典/握手编解码器挂原版 Connection pipeline（MixinConnection 注入），UDP 数据面直灌影子端 apply，failover 断连重连走完整 login → `handleLogin` → `setLevel`（世界重载，freeze 定格仅掩盖视觉）。三大问题：

1. **第三方 mod 兼容性**：UDP 区块数据绕开 `handleLevelChunkWithLight`，拦截 handler 的 mod 看不到；聚合包破坏管道层 mod；缓存命中只发 hash 不发区块，依赖区块数据的 mod 缺数据
2. **主控切换导致世界重载**：客户端断连重连必重走 login 流程，MC 协议无绕过
3. **网络逻辑散落**：状态分 4 个容器（ClientChunkPipeline/ClientMetadataHandler/NetworkManager 接口/各 Service），入口分散

目标：**客户端引入"网络核心"（进程内网关）完全接管客户端↔主控的网络收发**，客户端世界侧只见纯原版包对象（handler 层注入，管道无任何 Hassium 编解码器）；主控切换在网关内部完成（B 侧无 login 续流），客户端原版 Connection 不断、世界零重载零感知；并叠加 L1 负载均衡（策略驱动整池/分组主动迁移）。

## 当前基线与重构边界（2026-08-31）

当前 `25565 + 25566` 双连接 sidecar 实现不符合本需求的目标拓扑，已暂停：客户端先直连服务端 `25565`，再由 `gateway_info` 引导 MOD 连接 `25566` 的模型不得继续作为网络核心验收路径。

### Phase 0：单 `25565` 原版基线

- 服务端仅暴露原版 Minecraft `25565`；旧 `GatewayServer` 不监听 `25566`。
- 客户端不启动旧 NetworkCore outbound，不接收 `gateway_info` 以建立第二条连接，C2S/S2C 保持原版 Connection。
- UDP、迁移、续流、GatewayPlayerBridge 与旧网关 hash/delta 通路均不参与本阶段。
- 区块缓存、影子端、原版 `handleLevelChunkWithLight` 落地、实体镜像与指标开发继续以此基线验证。

### 后续代理核心重构目标

`controlReachableEndpoints` 的目标语义是同一服务端的候选路由地址；每条 route 使用原版 `25565`，而不是独立 gateway listener。客户端世界侧单点接入 NetworkCore，NetworkCore 作为代理选择 `route-A:25565` / `route-B:25565`；链路失效时切换 route，客户端世界无断开、无 login、无重载。

重构首先必须定案 NetworkCore 的 login 前入口（本地 loopback proxy 或 virtual transport）。在该入口、会话所有权与 `25565` 上游连接模型落地前，不恢复旧 `25566` sidecar。

## 架构（已拍板）

```
客户端进程
├─ 原版客户端世界 / 第三方 MOD      ← 看到一台普通原版服务器
│   └─ 原版 Connection（状态保持，管道纯原版，无 Hassium 编解码器）
└─ Hassium MOD
    ├─ 网络核心（网关）: 接管客户端↔主控收发
    │   ├─ 对客户端世界: 纯原版 Packet 对象 handler 层注入（入站）/ C2S 截获转交（出站）
    │   ├─ 对区块核心: 进程内对象直传（命中判定/OVD 需求/实体镜像）
    │   └─ 对主控: 自有通道（ZSTD/聚合/自定义协议/UDP 数据面/握手，全部优化保留）
    └─ 区块核心（影子端）: OVD 生成 / 算光 / 缓存 / 实体镜像
            └─ 生成结果 → 原版区块加载通道（handleLevelChunkWithLight）→ 客户端世界

主控 A/B（同世界热备，Hassium mod 服务端）
    └─ 续流协议: 票据+epoch 验签、无 login 直续 play 流、会话/hash 表同步、负载上报
```

## 需求明细

### A. 客户端网络核心（网关）

1. **独立连接栈**：mod 自建与主控的连接（自有通道：ZSTD 压缩/包聚合/自定义帧/UDP 数据面/握手，现有一切优化保留在此段），**不碰原版 Connection 的 Netty pipeline**
2. **入站注入**：自有通道收包 → 解压/解聚合 → 还原原版 `Packet` 对象 → **handler 层注入**（`ClientPacketListener.handleXxx` 等，含线程语义对齐）。区块数据（服务端来 / 本地命中 / OVD 生成）统一经原版区块加载通道，第三方 handler 注入 mod 全可见
3. **出站截获**：原版 C2S 经截获点转自有通道编码（压缩/聚合），管道内无自定义字节
4. **原版 Connection 状态保持**：`isConnected`/断开检测/`Minecraft.getConnection()` 等既有依赖全部正常工作；注入工程形态（handler 直调 vs LocalChannel 替换）由 T0 调研定案
5. **登录桥接**：status/login/加密/压缩协商/configuration/play 全生命周期经网关桥接；客户端会话 token 转发主控（正版/离线都成立）
6. **删除**：管道编解码器（MixinConnection 编解码逻辑、ZstdPacketDecoder/Encoder 管道挂载、HassiumAggregationManager 客户端侧、ZstdNegotiationTracker 管道侧）、UDP 数据面客户端全套、failover 全套（ClientFailover*/ClientRecoveryState/ControlReconnect*/ControlEndpoint*/ServerListBackupPing）、MixinVanillaChunkApplyBudget、预握手 mixin（MixinClientConfigurationPacketListenerImpl）、MixinLightRecompute（服务端不剥光后无空光包）
7. **保留**：区块核心全套（影子端/ShadowLightCompute/ShadowCacheEviction/OVD/实体转发）、MixinClientPacketListener（实体转发 7 处 + OVD 半径 + login 生命周期）、MixinClientCommonPacketListenerImpl（onDisconnect）、MixinClientLevel/MixinLevel（renderOnly）、MixinOptions（RD 钳制）、MixinClientTick（帧尾预算/drain）
8. **ViaFabric 兼容**：检测 ViaFabric 存在（类/入口探测）→ 注入点改挂 ViaFabric 取包处（先经其协议转换再进 handler），其余路径不变

### B. 主控侧无 login 续流（B 侧扩展）

9. **续流握手**：自有通道握手扩展「续流模式」分支——续流标记 + 玩家会话票据（网关签名，共享密钥验签）+ epoch 递增防重放（旧连接重放拒绝）
10. **会话上下文同步**：玩家会话（位置/状态/区块 hash 表 ShadowStorageHashes 同源数据）随 A/B 热备同步到 B；B 知道玩家已有哪些区块 → **只发增量**（避免全量重发/客户端重复 apply）
11. **无 login 续流**：B 跳过 login/config/维度初始化，直接回「续流就绪」→ 从同步状态续发 play 流（区块增量/实体/光 delta），与 A 最后推送状态连续
12. **负载上报**：主控经自有通道周期上报 CPU/TPS/内存/玩家数（迁移策略输入）

### C. 网关侧迁移引擎（L1）

13. **触发**：故障（心跳超时，沿用 recoveryWindow 语义）+ 策略（负载阈值/维护窗口/切换演练）
14. **整池/分组迁移**：复用续流机制；主动迁移附加质量优化——预热（迁移前 B 预同步玩家周边区块 + hash 表，续流后增量趋近零）、空闲窗口（玩家静止/区块 hash 稳定时切）
15. **单写安全**：整池迁移天然单写（A 写 → B 写，写权移交），无多写冲突
16. **远期（不做）**：L2 逐玩家自由路由（需区块级写同步/租约，工程大，另行立项）

## 约束

- **客户端↔世界侧零自定义字节**：管道无 Hassium 编解码器、无聚合包、无自定义包；所有区块数据经原版区块加载通道（handler 层可见性 = 第三方 handler mod 兼容）
- **网关↔主控段自由**：自有通道，全部优化保留（压缩/聚合/UDP/hash/delta/剥光算光——网关内嵌引擎）
- **进程内直传**：网关↔区块核心对象直传，禁止序列化往返（项目既有约定）
- **原版 Connection 不可断**：主控切换期间客户端原版连接状态零变化（无 handleLogin、无 setLevel、无重载）
- **九段适配**：1.20.1–1.21.11 差异收敛 `common/.../compat/`，禁止业务散落新 `#if MC_VER`（hassium-dev/hassium-mixin skill）
- **Mixin 规范**：`@Unique` + `hassium$` 前缀、登记 `hassium.mixins.json`
- **网络红线**：网络入口查开关 + 握手状态（hassium-network skill）
- **服务端主控 B**：仅扩展续流/负载上报，原推送链路（ServerChunkPushManager/握手/数据面）复用不重写

## 依赖

- 现有网络资产（改造为独立连接栈）：ZstdPacketDecoder/Encoder、SkipAwareZstdEncoder、HassiumAggregationManager、DictionaryManager、IndexSyncManager、HassiumHandshake、PacketCompressionBlacklist、CompactHeaderCodec、HassiumPacketIds、NetworkManager 接口 + 三端实现、dataplane 服务端（DataPlaneServer/UdpBulkRouter 等）
- 服务端复用：ServerChunkPushManager、握手响应、数据面服务端、PlayerCompressionTracker
- 区块核心（不动）：影子端全套、OVD、实体转发（handoff-2026-08-09-entity-shadow 落地）
- docs：version-segments.md（九段）、architecture.md、chunk-cache.md（§9.1 数据面）、runtime-smoke-test.md（udp-failover）
- 技能：hassium-network、hassium-mixin、hassium-dev

## 实现细节（调研定稿，2026-08-09 T0-T3）

- **注入形态 = handler 直调**（T0）：网关在主线程直调官方 `ClientPacketListener.handleXxx`；C2S 走 `connection.send` + MixinConnection 拦截收口。线程语义由 `ensureRunningOnSameThread` 免费保证；重入标志体系（BUDGETED_APPLY / hassiumApplyInProgress / KeyedPriorityQueue REPLACE）沿用。**主控切换 = 网关换 outbound 连接，vanilla Connection/listener 链零改动**（vanilla 侧无可断连接，B 侧续流天然落地）。LocalChannel 全替换仅作备选（Phase 2 评估，1.21.11 配置任务链复杂度高）
- **登录桥接**（T1）：网关持 `Minecraft.getInstance().getUser().getAccessToken()` 代表玩家对主控复刻登录链（hello → joinServer → key → 压缩 → config → play）；hasJoinedServer 只认 name+serverHash 不认连接来源 → 正版/离线双模式可行；加密/压缩每连接独立协商
- **自有通道**（T2）：dataplane transport（纯 Netty 零 MC 依赖）升级为网关 outbound 传输层；业务包接收从平台 CustomPayload receiver 改为网关注入；聚合/压缩/字典移入网关 outbound；客户端管道退役（ZstdPipelineSwitcher/ZstdNegotiationTracker/SkipAware/Context* 编解码器）；ZstdPacketDecoder/Encoder 死代码删
- **续流**（T3）：B 侧重算 hash（服务端无持久 hash 缓存，XXH64 确定性）；票据挂 HassiumHandshake append-only 尾；位置上报扩展 y/yaw/pitch/维度；per-player 状态 UUID-keyed 进程内存，B 侧 removePlayer 后重建；影子端目录 key 稳定性由"客户端只连网关固定地址"自动保证
- **顺手修**：ForgeNetworkManager:616 epoch 用 System.nanoTime() 与三端口径不一致；docs/version-segments.md 预握手 neoforge 描述与代码矛盾

## 验收标准

- [ ] **管道净化**：客户端 pipeline 无 Hassium 编解码器/聚合包（grep + mixins.json 审查）
- [ ] **handler 可见性**：所有区块数据（服务端/命中/OVD）经原版区块加载通道；模拟第三方 handler 注入（测试 mixin）能拦截全部区块包
- [ ] **无感主控切换（实测）**：主控切换期间客户端无 handleLogin/setLevel 触发、画面与世界连续（冒烟：切换前后区块/实体/光连续，无黑块/无重载）
- [ ] **B 侧续流**：无 login 重发、play 流连续、hash 表连续（增量不重复、无全量重发）
- [ ] **L1 迁移**：策略触发（负载阈值/维护窗口）整池/分组迁移成功，预热生效（迁移后增量≈0）
- [ ] **登录桥接**：正版/离线两种模式登录成功，token 转发主控验证通过
- [ ] **ViaFabric 兼容**：装 ViaFabric 时注入点切换生效，协议转换正常
- [ ] **功能不回归**：缓存命中/OVD/export/实体镜像/断连 saveAll 冒烟通过
- [ ] **全版本编译**：common 九段 + 三加载器编译绿
