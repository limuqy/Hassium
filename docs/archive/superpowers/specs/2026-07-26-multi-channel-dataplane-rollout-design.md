> 归档：历史 superpowers 规格（已完成使命）（2026-08-09）
# Multi-channel Data Plane Rollout Design

Date: 2026-07-26  
Parent PoC: [`2026-07-25-multi-channel-dataplane-poc-design.md`](2026-07-25-multi-channel-dataplane-poc-design.md)  
Parent research: [`docs/archive/multi-channel_network_research.md`](../../multi-channel_network_research.md)  
Scope: **Post-PoC 全面铺开**（Fabric + NeoForge × 九段锚点）

## 1. Goal and non-goals

### Goal

把 1.20.1 Fabric 已通过的数据面多通道能力推广到：

- **加载器**：Fabric + NeoForge（不含 Forge）
- **版本**：九段全部锚点（1.20.1 / 1.20.2 / 1.20.5 / 1.21.1 / 1.21.2 / 1.21.5 / 1.21.6 / 1.21.9 / 1.21.11）
- **能力**：两端 bind → Bind 握手 → share/exclusive bulk 路由 + WRR → exclusive 3×drop degraded → Primary fallback
- **安全增量**：服务端启动随机 16-byte session token，经握手尾部字段下发；端点仍读 PoC 静态配置
- **验证**：每个版本×加载器全跑 DataplanePhase（含 degraded 硬断言）

### Non-goals（明确不做）

| Item | 理由 |
|------|------|
| Forge 接入 | 用户锁定仅 Fabric+NeoForge；Forge 1.21+ 已弃用 |
| `server.toml [network.dataPlane]` 正式配置段 | 仍用 `DataPlanePoCConfig` 静态常量；正式配置另期 |
| SectionDelta 接入 BulkRouter | 父设计 §14 第 3 步，本轮不碰 |
| 真异步 exclusive 排队 | 保持 PoC 的 drop+degraded 简化 |
| 改 `MixinConnection` 全包分流 | 永不做 |
| 改 `ServerChunkPushManager` 方法体 | 路由仍在各加载器 `ChunkSender` 入口 |
| ~~bump `CURRENT_PROTOCOL_VERSION`~~ → **[SUPERSEDED 2026-07-26]** 本轮改为 Goal：见 §14 supersede 项；硬切换不兼容，protocol 1→2 | 后续 §14 第 2 步要做硬版本协商，兼容靠双方都升 |
| ~~真实玩家 UUID 绑定~~ → **[SUPERSEDED 2026-07-26]** 本轮改为 Goal：见 §14 supersede 项；BindRequest 携带 16B UUID，`Bundle key` 用真实玩家 UUID | 后续 §14 第 2 步要做多玩家 bundle 隔离 |

## 2. Locked decisions（用户拍板）

| Item | Decision |
|------|----------|
| 范围矩阵 | 仅 Fabric + NeoForge 九段锚点（约 18 组合） |
| 端点协商 | 端点仍 `DataPlanePoCConfig.ENDPOINTS` 静态；token 服务端启动随机生成，经 HandshakeS2C 尾部下发 |
| 冒烟覆盖 | **全版本全跑 DataplanePhase**（bind / WRR / kill / degraded / Primary fallback） |
| 协议兼容 | ~~不 bump~~ protocolVersion ~~；HandshakeC2S/S2C 仅尾部 append~~ **[SUPERSEDED 2026-07-26]**：本轮 bump `Constants.CURRENT_PROTOCOL_VERSION` 1→2，HandshakeC2S/S2C 仍尾部 append；新 BindRequest payload 含真实玩家 UUID。旧客户端（protocol=1）握手被拒。见 §14 supersede 项 |
| Primary 路径 | `BulkRouter` / `tryRouteBulk` 返回 false → 继续原版 Primary 发送，行为不变 |

## 3. Architecture

### 3.1 已就位（common，跨版本共用）

| 组件 | 状态 | 说明 |
|------|------|------|
| `DataPlaneFrame` / `Hkdf` / `DataPlaneCodec` | ✅ | 纯 JDK，无 loader 依赖 |
| `PlayerChannelBundle` / `BulkRouter` | ✅ | WRR + exclusive degrade |
| `DataPlaneServer` | ✅ | bind/shutdown/tryRouteBulk/kill；`MixinMinecraftServer` 已接入 |
| `DataPlaneClientBundle` | ✅ | connect/Bind/demux → `ClientChunkHandler` |
| `DataPlanePoCConfig` | ✅ | 端点/权重/mode；`BIND_TOKEN` 需迁出生产路径 |
| `MixinMinecraftServer` bind/shutdown | ✅ | common Mixin，所有 loader×版本已生效 |

### 3.2 本轮缺口

```
common:
  DataPlaneServer.bind()                 → 启动时 SecureRandom 生成 sessionToken
  DataPlaneServer.getSessionToken()      → 握手下发用
  DataPlaneServer.deriveChannelKey       → ikm/salt 改用 sessionToken
  DataPlaneClientBundle                  → connectAndBind(token, endpoints) 接收握手下发值
  DataPlanePoCConfig.BIND_TOKEN          → 仅保留作单测默认

fabric:
  HassiumMod ChunkSender                 ✅ 已接 tryRouteBulk
  HassiumClientMod JOIN                  → 移除立即 connectAndBind；改由 HandshakeS2C 触发
  FabricNetworkManager 握手              → C2S 尾部 multiChannelSupported；S2C 尾部 hasDataPlane+endpoints+token

neoforge:  （全部新建接入）
  HassiumNeoForge / ChunkSender          → 同构 tryRouteBulk 拦截
  HassiumNeoForgeClient JOIN/DISCONNECT  → 握手后启动 / 断开关闭 ClientBundle
  NeoForgeNetworkManager 握手            → 与 fabric 同一线格式尾部字段（全部版本段）
```

### 3.3 数据流（推广后）

```
Server start
  DataPlaneServer.bind()
    → SecureRandom 生成 16-byte sessionToken
    → bind 两个 Data 端口（PoC 端点配置）

Client JOIN
  → sendHandshakeRequest（尾部 multiChannelSupported=true）
  → （不再立即 connectAndBind）

Server handle HandshakeC2S
  → 读旧 5 boolean；若 isReadable → 读 multiChannelSupported
  → completeServerHandshake(..., multiChannel)
      HandshakeS2C:
        [旧字段 protocol/accepted/useGlobal/useCompact]
        + hasDataPlane:bool
        + if true: endpointCount:VarInt
                   for each: host:Utf, port:int, weight:int
                   token:byte[16]

Client handle HandshakeS2C
  → 处理旧字段（ZSTD 等）
  → if isReadable && hasDataPlane:
       DataPlaneClientBundle.connectAndBind(token, endpoints)
     else:
       数据面关闭，仅 Primary

Bulk send（ChunkSender）
  → DataPlaneServer.tryRouteBulk(pseudoPlayerId, TYPE_BULK, payload)
  → true  → 已走 Data / exclusive-drop
  → false → Primary + recordBulkSentPrimary
```

## 4. Handshake wire extension（向后兼容）

### 4.1 HandshakeC2S（客户端→服务端）

**现有（不变）：**
```
VarInt protocolVersion
Utf    modVersion
VarInt algoCount
Utf×N  algorithms
bool   clientCacheSupported
bool   chunkRevisionSupported
bool   scheme127Supported
bool   globalPacketCompressionSupported
bool   compactHeaderSupported
```

**追加（新客户端写，旧服务端忽略 leftover）：**
```
bool   multiChannelSupported   // 客户端支持数据面时写 true
```

服务端读法：
```java
boolean multiChannel = false;
if (buf.isReadable()) {
    multiChannel = buf.readBoolean();
}
```

### 4.2 HandshakeS2C（服务端→客户端）

**现有（不变）：**
```
VarInt protocolVersion
bool   accepted
bool   useGlobalCompression
bool   useCompactHeader
```

**追加（新服务端写，旧客户端忽略 leftover）：**
```
bool   hasDataPlane
// 仅 hasDataPlane==true 时：
VarInt endpointCount
// 每个 endpoint：
  Utf  host
  int  port
  int  weight
byte[16] token
```

客户端读法：
```java
boolean hasDataPlane = false;
DataPlanePoCConfig.Endpoint[] eps = null;
byte[] token = null;
if (buf.isReadable()) {
    hasDataPlane = buf.readBoolean();
    if (hasDataPlane) {
        int n = buf.readVarInt();
        eps = new DataPlanePoCConfig.Endpoint[n];
        for (int i = 0; i < n; i++) {
            String host = buf.readUtf();
            int port = buf.readInt();
            int weight = buf.readInt();
            // Endpoint(address, port, weight, bindHost, bindPort)
            eps[i] = new DataPlanePoCConfig.Endpoint(host, port, weight, "0.0.0.0", port);
        }
        token = new byte[16];
        buf.readBytes(token);
    }
}
if (hasDataPlane && token != null) {
    startDataPlane(token, eps);
}
```

### 4.3 兼容矩阵

| 客户端 | 服务端 | 行为 |
|--------|--------|------|
| 旧 | 新 | 客户端不读尾部 → 不上数据面；Primary 正常 |
| 新 | 旧 | 服务端不写尾部 → `isReadable()==false` → 不上数据面；Primary 正常 |
| 新 | 新 + multiChannel 双方 true | 数据面启用 |
| 新 | 新 + 任一方 false / enabled=false | hasDataPlane=false → Primary only |

~~**不 bump `Constants.CURRENT_PROTOCOL_VERSION`。** 旧字段顺序与语义零改动。~~

**[SUPERSEDED 2026-07-26]** 本轮 bump `Constants.CURRENT_PROTOCOL_VERSION` 1→2，旧端 protocol=1 被 Handshake 双向版本协商拒掉。HandshakeC2S/S2C 字段顺序维持不变（尾部 append 不破坏旧主连接握手前 5 字段），但 BindRequest payload schema 改为 `token[16] + uuid[16] + protocol(VarInt=2) + channelId(VarInt)`——旧 Data 客户端不知道写 UUID、服务端按短长度直接 BindFail 关 Data。仅「新版客户端 + 新版服务端」可上数据面；旧↔旧、旧↔新、新↔旧都退化为「无数据面 + Primary」。见 §14 supersede 项。

### 4.4 hasDataPlane 判定

```
hasDataPlane =
    accepted
    && DataPlanePoCConfig.isEnabled()
    && DataPlanePoCConfig.CLIENT_ENABLE_DATA_PLANE   // 服务端侧配置门（与客户端 multiChannel AND）
    && multiChannelSupported                        // 来自 C2S
    && DataPlaneServer.isBound()
    && sessionToken != null
```

若 bind 部分端口失败：只下发成功 bind 的端点；若全部失败则 `hasDataPlane=false`。

## 5. Token lifecycle

```
DataPlaneServer.bind():
  sessionToken = SecureRandom.nextBytes(16)
  // Bind 校验 + HKDF 均用 sessionToken

DataPlaneServer.getSessionToken(): byte[16]   // completeServerHandshake 读取
DataPlaneServer.setSessionTokenForTest(byte[]) // 单测注入固定值

DataPlaneServer.deriveChannelKey(portIdx, reqChannelId):
  // ikm = salt = sessionToken（不再读 DataPlanePoCConfig.BIND_TOKEN）

DataPlaneServer.shutdown():
  sessionToken = null
```

**必须同步迁出静态 `BIND_TOKEN` 的调用点：**

| 位点 | 现状 | 推广后 |
|------|------|--------|
| `DataPlaneServer` Bind 校验 | `Arrays.equals(token, BIND_TOKEN)` | `sessionToken` |
| `DataPlaneServer.deriveChannelKey` | `Hkdf(BIND_TOKEN, BIND_TOKEN, …)` | `Hkdf(sessionToken, sessionToken, …)` |
| `DataPlaneClientBundle.sendBindRequest` | 写 `BIND_TOKEN` | 写握手下发的 `token` |
| `DataPlaneClientBundle` 客户端密钥派生 | 调 `DataPlaneServer.deriveChannelKey`（读静态） | `deriveChannelKey(token, portIdx, id)` 重载，用下发 token |

`DataPlanePoCConfig.BIND_TOKEN` 保留为**单测默认**（全零）。  
生产 / 冒烟路径一律走 session token。脚本无需注入 token。

## 6. Loader integration points

### 6.1 Fabric（增量改）

| 位点 | 改动 |
|------|------|
| `FabricNetworkManager.sendHandshakeRequest` | 尾部 `writeBoolean(true)` multiChannelSupported |
| 服务端 HANDSHAKE_C2S receiver（两段 `#if`） | `isReadable` 读 multiChannel；传入 `completeServerHandshake` |
| `completeServerHandshake` | 写 hasDataPlane + endpoints + sessionToken |
| 客户端 HANDSHAKE_S2C receiver（两段 `#if`） | 读尾部 → 启动 `DataPlaneClientBundle` |
| `HassiumClientMod` JOIN | **移除**立即 `connectAndBind`；改由握手响应触发 |
| `HassiumClientMod` DISCONNECT | 保持 shutdown ClientBundle |
| `HassiumMod` ChunkSender | 已接 tryRouteBulk，不改 |

### 6.2 NeoForge（新建接入）

| 位点 | 改动 |
|------|------|
| 服务端入口设置 `ChunkSender` | 同构 fabric：tryRouteBulk → Primary fallback + `recordBulkSentPrimary` |
| `HassiumNeoForgeClient` JOIN | 只发握手；不立即 connect |
| `HassiumNeoForgeClient` DISCONNECT | shutdown ClientBundle |
| `NeoForgeNetworkManager` 握手收发 | 与 fabric **同一线格式**尾部字段 |

NeoForge 网络按 version-segments 已有 4 段实现块（`<1.20.2` / `1.20.2–1.20.3` / `1.20.4` / `≥1.20.5`）。**每段握手 encode/decode 都要追加同一尾部字段**，禁止只改一段。

### 6.3 不改

- `MixinMinecraftServer`（bind/shutdown 已 universal）
- `MixinConnection`
- `ServerChunkPushManager` 方法体
- Forge 模块任何文件

## 7. Client connect timing

**PoC 现状：** `HassiumClientMod` JOIN 立即 `connectAndBind()`，用全零 token。  
**推广后：** 必须等 HandshakeS2C 带 token 到达后再连。

```
JOIN
  → sendHandshakeRequest（含 multiChannelSupported）
  → （等待）
HandshakeS2C(hasDataPlane=true, token, endpoints)
  → DataPlaneClientBundle.connectAndBind(token, endpoints)
  → BindRequest(token) → BindAck → ready
```

若握手被拒 / network disabled / hasDataPlane=false：永不启动 ClientBundle。  
DISCONNECT：shutdown + null 引用（保持现状）。

建议把「启动 ClientBundle」抽到 common 或各 loader 的小 helper（如 `DataPlaneClientLifecycle.start(token, endpoints)` / `stop()`），避免 fabric/neoforge 复制粘贴状态机。

## 8. Endpoint encoding

HandshakeS2C 下发**客户端连接地址** `(address, port, weight)`，不是 bindHost。  
服务端 bind 仍用本地 `bindHost` + `bindPort`。

`Endpoint` 构造签名（现状，勿改顺序）：
```java
new Endpoint(String address, int port, int weight, String bindHost, int bindPort)
```

线格式只编码三元组；客户端重建时 `bindHost`/`bindPort` 填占位（客户端不 bind）。

当前 PoC：`127.0.0.1:25566/25567`。本轮不引入公网地址发现。

## 9. Smoke test requirements

### 9.1 矩阵

每个锚点 × {fabric, neoforge} 全跑 DataplanePhase：

| 段 | 锚点 | fabric | neoforge |
|----|------|--------|----------|
| A | 1.20.1 | ✅ | ✅ |
| B | 1.20.2 | ✅ | ✅ |
| C | 1.20.5 | ✅ | ✅ |
| D | 1.21.1 | ✅ | ✅ |
| E | 1.21.2 | ✅ | ✅ |
| F | 1.21.5 | ✅ | ✅ |
| G | 1.21.6 | ✅ | ✅ |
| H | 1.21.9 | ✅ | ✅ |
| I | 1.21.11 | ✅ | ✅ |

Forge 不在矩阵内。

### 9.2 DataplanePhase 硬断言（沿用 1.20.1 已有）

| Step | Signal |
|------|--------|
| 1 | Server bound two Data ports |
| 2 | BindAck ok；bundle size=2 |
| 3 | share WRR：Data 通道有 bulk 流量 |
| 4 | Kill 一条 Data → bundle size=1；路由继续 |
| 5 | exclusive + 双 Data down → 3 drops → degraded → bulk 经 Primary 到达（**硬断言**） |
| 6 | Primary disconnect → Data 全关 |
| 7 | `enabled=false` → 零数据面业务行为 |

### 9.3 脚本与端口

- 复用 `scripts/runtime-smoke-test.ps1` + batch；确认 DataplanePhase 在非 1.20.1 同样触发
- **并行端口冲突**：fabric 主 25565 + Data 25566/25567；neoforge 并行时主端口与 Data 端口必须 per-loader 偏移（batch `-Parallel` 时 Data 端口也要错开，否则 bind 失败）
- token 经握手下发，脚本无需注入

### 9.4 编译前置

```bash
./gradlew --no-daemon fabric:compileJava neoforge:compileJava "-Pmc_ver=<anchor>"
# 或
./gradlew --no-daemon compileAnchors
```

九锚点 × fabric/neoforge 全部 `compileJava` 通过是冒烟前置。

## 10. Implementation order

1. **common token 运行时化**  
   - `DataPlaneServer` 生成/暴露 sessionToken；Bind + HKDF 改用它  
   - `DataPlaneClientBundle.connectAndBind(byte[] token, Endpoint[] endpoints)`  
   - 单测：token mismatch reject；match accept；test hook 注入固定 token

2. **Fabric 握手尾部 + 客户端时序**  
   - C2S/S2C encode/decode（两段 `#if` 都改）  
   - JOIN 不再立即 connect；HandshakeS2C 触发  
   - **1.20.1 fabric 冒烟回归 DATAPLANE_PASS**（防回归闸门）

3. **NeoForge 接入**  
   - ChunkSender tryRouteBulk  
   - Client JOIN/DISCONNECT + 握手尾部（全部版本段）  
   - **1.20.1 neoforge 冒烟 DATAPLANE_PASS**

4. **九锚点编译**  
   - `compileAnchors`；修版本 API 断裂  
   - 网络适配器整段 `#if` 同步，禁止碎片边界

5. **全矩阵冒烟**  
   - 18 组合 DataplanePhase  
   - 失败按 `hassium-smoke-test-triage` 修  
   - **禁止关功能换绿；禁止覆盖 1.20.1 已验证路径**

6. **收尾**  
   - 更新 `docs/version-segments.md` 附录  
   - 更新 `hassium-network` skill（token 下发 + 客户端时序）

## 11. Failure / fallback table

| Scenario | Behavior |
|----------|----------|
| `DataPlanePoCConfig.isEnabled()==false` | 不 bind；握手 hasDataPlane=false |
| 客户端 multiChannelSupported=false | 握手 hasDataPlane=false |
| token 未下发 / Bind mismatch | Bind fail → close Data channel；Primary 继续 |
| 全部 Data down + share | tryRouteBulk false → Primary |
| ~~旧客户端连新服务端~~ → **[SUPERSEDED]** protocol=1 被新服务端 HandshakeVersionCheck 拒，握手失败 | ~~忽略尾部；无数据面；Primary 正常~~ |
| ~~新客户端连旧服务端~~ → **[SUPERSEDED]** protocol=2 被旧服务端拒绝，握手失败 | ~~无尾部；无数据面；Primary 正常~~ |
| Data 端口全部 bind 失败 | hasDataPlane=false；Primary only |

## 12. Red lines（继承项目硬约束）

1. **功能不降级**：不得为过冒烟关闭 lightCache / sectionDelta / viewDistanceExtension / globalPacketCompression / 数据面本身  
2. **已验证路径不覆盖**：1.20.1 fabric 已通过的 Primary / ZSTD / 聚合 / 缓存路径禁止改写；差异用叠加分支  
3. **common 无 loader API**  
4. **网络适配器整段 `#if`**，禁止 send/receive 碎片边界  
5. **热路径**用 `DebugLogger`，INFO 仅生命周期

## 13. Done criteria

- [ ] 1.20.1 fabric DataplanePhase 仍 PASS（握手改时序后回归）
- [ ] 1.20.1 neoforge DataplanePhase PASS
- [ ] 九锚点 × fabric/neoforge `compileJava` 全绿
- [ ] 九锚点 × fabric/neoforge DataplanePhase 全 PASS
- [ ] 无 multiChannel 尾部时 Primary 路径行为与改前一致
- [ ] `DataPlanePoCConfig.isEnabled()==false` 时零数据面副作用

## 14. Open items（本轮明确不做）

- Forge 同构
- `server.toml [network.dataPlane]` 正式配置
- ~~真实 player UUID ↔ bundle 绑定~~ **[SUPERSEDED 2026-07-26，本轮已纳入 Goal]**
- SectionDelta → BulkRouter
- 公网 endpoint 发现 / NAT
- ~~protocol version bump 与严格版本协商~~ **[SUPERSEDED 2026-07-26，本轮已纳入 Goal]**
- exclusive 真异步排队

---

## Approval trail

Brainstorming 2026-07-26:

1. 范围：仅 Fabric + NeoForge 九段  
4. ~~协议：尾部 append，不 bump protocolVersion~~ **[SUPERSEDED 2026-07-26]**：本轮 bump ~Constants.CURRENT_PROTOCOL_VERSION~ 1→2 + BindRequest payload 含真实玩家 UUID（硬切换不兼容）；详见后续 §14 supersede 设计差分文档 `2026-07-26-multi-channel-dataplane-uuid-rollout-design.md` |
3. 冒烟：全版本全跑 DataplanePhase  
5. HKDF/Bind 全链路迁出静态 `BIND_TOKEN`，统一 sessionToken  
