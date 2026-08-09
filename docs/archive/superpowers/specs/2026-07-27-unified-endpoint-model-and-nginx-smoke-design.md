> 归档：历史 superpowers 规格（已完成使命）（2026-08-09）
# 统一可达端点模型与 Nginx Failover 冒烟设计

## 目标

将当前由 `DataPlanePoCConfig` 硬编码的 UDP 地址/端口迁入 `hassium-server.toml`，明确区分**服务端监听地址**与**客户端可达地址**；TCP 控制面仅复用 `server.properties` 的单一监听端点，但可下发多个可达候选用于 cold reconnect。补齐跨进程冒烟，使六个 `UDP_FAILOVER` marker 都由真实生产链路产生。

本设计不修改协议版本号，不引入第二条并行 Minecraft Play Connection，不改变 `tryRouteBulk(UUID, int, byte[])` 的返回契约。

## 已验证现状与问题

- 当前 `DataPlanePoCConfig.ENDPOINTS` 固定为两个 UDP listener：广告 `127.0.0.1:25566/25567`，绑定 `0.0.0.0:25566/25567`。
- `DataPlaneUdpServer.BoundEndpoint.host` 曾错误保存 `bindHost`，Fabric 握手下发后客户端尝试连接 `0.0.0.0`；已由 `780cd20` 修复为广告 `Endpoint.address`。
- 默认 TCP Minecraft server 没有 UDP socket；同一 IP/端口上的 TCP 与 UDP 可以被操作系统分别监听。因此生产默认可使用 TCP 25565 + UDP 25565，不需要因 TCP 冲突另开 UDP 端口。
- `ControlEndpointManager`、`ControlReconnectOrchestrator` 和 `FabricControlReconnectLauncher` 已具备候选重连基础设施，但 Fabric 当前 S2C tail 下发的 control 列表为空。
- 当前 `UdpFailover` 跨进程冒烟已经验证 `UDP_BIND_OK`、`UDP_WRR_OK`、`FAILOVER_RECONNECT_OK`、`CACHE_RESUME_HIT`；没有真实驱动 control-stall permit 或候选耗尽 terminal，故 `FAILOVER_PERMIT_OK`、`FAILOVER_TERMINAL_OK` 尚未出现。原 rollout plan 要求六个 marker 全部出现，不能把四个 marker 的 PASS 当作最终验收。

## 术语与不变量

| 术语 | 含义 | 是否下发给客户端 |
|---|---|---|
| TCP listener | Minecraft vanilla server 依 `server.properties` 建立的唯一 TCP 监听地址 | 否；由 vanilla login 原路径决定 |
| control reachable endpoint | 客户端重新建立 vanilla TCP 连接时可用的 `host:port` 候选 | 是，作为 S2C control tail |
| UDP listener | Hassium 以 `bindHost:bindPort` 创建的一个 datagram socket；每个 listener 有稳定 `endpointId` 与 WRR `weight` | 间接；不下发 bind 地址 |
| UDP reachable endpoint | 某一个 UDP listener 对客户端可达的 `host:port` 地址；一个 listener 可有多个 | 是，属于该 listener |
| Primary | 现有 vanilla TCP Play Connection；唯一 master | 不变 |
| Data plane | 已协商、已 Bind 的 KCP-over-UDP session | `enabled=true` 时可用 |

必须保持：

1. `bindHost` 仅用于本机 bind，**绝不**作为 reachable host 下发；`0.0.0.0`、`::` 是非法可达地址。
2. TCP 不新增 Hassium listener 配置。生产 TCP 一律由 `server.properties` 管理，Hassium 只管理 control reachable endpoints。
3. 一个 UDP listener 可以有多个 reachable endpoints；它们是同一逻辑 listener 的地址候选，不是多个 WRR lane。WRR lane 由不同 `endpointId` 的 UDP listener 表示。
4. `network.dataPlane.enabled=false` 时，不 bind UDP、不在握手中下发 UDP listener、客户端不创建 UDP socket，`tryRouteBulk` 一律返回 `false`，由 Primary TCP 发送数据。control reachable endpoints 仍可下发并支持 TCP reconnect。
5. 一次恢复只会有一条 vanilla TCP Play Connection。候选地址按优先级/重试顺序串行尝试；不可因 UDP 可用而并行连接多个 Play server。
6. 所有 failover marker 必须来自生产路径；smoke 可以驱动事件，但不得伪造 marker 或直接设置 recovery state。

## 配置模型

`HassiumConfig.ServerNetworkConfig` 新增两个不可变嵌套配置记录，`FabricTomlConfigIO` 和 NeoForge 对应 IO 以同一 schema 读写。

```toml
# server.properties 仍负责唯一 TCP listener：server-ip / server-port。
# Hassium 只下发可被客户端重新连接的 TCP 地址候选。

[[network.controlReachableEndpoints]]
host = "play.example.com"
port = 25565
priority = 100

[[network.controlReachableEndpoints]]
host = "backup.example.com"
port = 25565
priority = 80

[network.dataPlane]
enabled = true
controlStallMs = 6000
failoverExpiryMs = 30000
recoveryWindowMs = 60000

# 以下示例特意让本地 UDP bind、NAT/LB 出口和客户端可达端口全部不同。
# 每个 udpListeners 条目只创建一个本地 UDP socket，并在握手中拥有一个 endpointId/WRR weight。
[[network.dataPlane.udpListeners]]
bindHost = "10.0.0.10"
bindPort = 31001
weight = 60

[[network.dataPlane.udpListeners.reachableEndpoints]]
host = "edge-a.example.com"
port = 41001
priority = 100

[[network.dataPlane.udpListeners.reachableEndpoints]]
host = "edge-b.example.com"
port = 42001
priority = 80

[[network.dataPlane.udpListeners]]
bindHost = "10.0.0.10"
bindPort = 31002
weight = 40

[[network.dataPlane.udpListeners.reachableEndpoints]]
host = "edge-a.example.com"
port = 43001
priority = 100

[[network.dataPlane.udpListeners.reachableEndpoints]]
host = "edge-b.example.com"
port = 44001
priority = 80

配置类型：

```java
public record ReachableEndpoint(String host, int port, int priority) {}

public record UdpListenerConfig(
        String bindHost,
        int bindPort,
        int weight,
        List<ReachableEndpoint> reachableEndpoints) {}

public record DataPlaneConfig(
        boolean enabled,
        List<UdpListenerConfig> udpListeners,
        long controlStallMs,
        long failoverExpiryMs,
        long recoveryWindowMs) {}
```

`ServerNetworkConfig` 持有 `List<ReachableEndpoint> controlReachableEndpoints` 与 `DataPlaneConfig dataPlane`。默认值为：control list 空（正常首次连接仍由 vanilla server address 决定）；data plane 启用，一个 `0.0.0.0:25565` UDP listener，listener 的可达地址以当前 server 的公开可达地址为运维必填值。开发运行默认可使用 `127.0.0.1:25565`，但不得把该值写成公网部署建议。

`bindHost:bindPort`、NAT/LB 的 UDP 出口 `source host:port`、reachable `host:port` 是三个独立层次，允许全部不同：服务端只 bind 前者；客户端只使用后者；中间映射（DNAT、端口转发、四层 LB）由运维保证将每个 reachable 地址定向到所属 listener 的 bind 地址。Hassium 不配置、不猜测、也不在协议中传播 NAT 出口端口；若映射错误，Bind 超时并尝试同一 listener 的下一 reachable 候选，Primary TCP 不受影响。

校验规则：

- `host` 非空、UTF-8 长度不超过握手 codec 上限；不得为 wildcard 地址 `0.0.0.0`、`::`。
- `port` 取值 `[1, 65535]`；`weight >= 0`，`priority >= 0`。
- `udpListeners` 启用时非空；每一 listener 的 reachable list 非空。
- 同一 `bindHost:bindPort` 不得重复；不同 listener 可广告相同或不同 reachable `host:port`，但每个 listener 内 reachable `host:port` 去重并保留优先级最高项。
- 同一 listener 的 bind port、NAT/LB 出口 port 与每条 reachable port 均允许不同；不因端口不相等而拒绝配置。
- `controlStallMs > 0`、`failoverExpiryMs > 0`、`recoveryWindowMs > 0`；读取错误回退该字段默认值并记录一次配置警告.

## 握手发布与客户端选择

### TCP control candidates

S2C tail 直接下发 `controlReachableEndpoints`，转为既有 `ControlEndpoint(host, port, priority)`。客户端总是将当前实际连接 remote address 作为 bootstrap candidate；恢复时 `ControlEndpointManager.mergeBootstrapAndAdvertised(bootstrap, advertised)` 保持既有 bootstrap-wins、优先级排序、至多四个候选规则。

正常首次连接不会由这些配置自动重定向。它仍通过 vanilla `ServerAddress` / `server.properties` 原路径完成。

### UDP listeners

一个逻辑 UDP listener 有一个 `endpointId` 和 WRR `weight`，以及多个可达地址。客户端为每个 listener 至多保留一个活跃 `ReliableDatagramSession`；依 priority 逐个尝试该 listener 的 reachable endpoint，成功 Bind 后停止尝试其余候选。后续地址候选仅在 bind / transport 初始化失败时使用，不能导致同一 listener 被重复加入 WRR。

既有 `UdpDataPlaneHandshakeTail.UdpEndpointInfo(host, port, weight, endpointId)` 无法表示“一个 endpointId 下多个 reachable 地址”，且 decoder 目前禁止重复 `endpointId`。新格式采用 append-only 扩展：

1. 保留当前 S2C UDP endpoint list 作为 legacy projection：每个 listener 仅放第一条 reachable endpoint。
2. 在当前 tail 末尾追加有长度边界的新 `udpListenerGroups` 段；新客户端优先读取 groups，旧客户端安全忽略剩余 tail 并使用 legacy projection。
3. group 元素包含 `endpointId`、`weight` 与 reachable endpoint list；客户端验证后按 endpointId 分组。
4. 新段解码失败必须关闭数据面协商并保留 Primary TCP，不得破坏原 handshake 或主动断 TCP。

此路线不 bump `protocolVersion`，也避免旧客户端把同一 listener 的多个地址错误理解为多个 WRR lane。

## 生命周期与路由

- `DataPlanePoCConfig` 不再是生产真相源。其静态常量仅可保留作 unit-test fixture 或兼容入口；运行时 `isEnabled()`、listeners、stall/lease 值一律从 `HassiumConfigService` 的不可变 snapshot 读取。
- server startup 读取 `DataPlaneConfig`，每个 `UdpListenerConfig` 创建一个 NIO datagram channel；`BoundEndpoint` 对外暴露 listener id、weight、**reachable endpoints**，不再以单个 `host` 表示 bind 或广告地址。
- `FabricNetworkManager` / NeoForge 对应 handshake 代码从 snapshot 组装 control list 与 UDP legacy projection + groups；`enabled=false` 时发送 hasUdpDataplane=false，但仍发送 control candidates。
- `ControlFailoverHandler` 的 `controlStallMs`、permit TTL 与 `ControlReconnectOrchestrator` 的 recovery window 都从相同 snapshot 取值；不得继续用硬编码 6s/30s/60s 覆盖 toml。
- `DataPlaneClientLifecycle` 以 `endpointId -> listener candidate group` 维护 UDP bundle；关闭、重连和 epoch replace 必须回收每 listener 的唯一 session/channel。

## Nginx 跨进程 smoke 设计

Nginx 仅用于**本机 TCP 故障注入**，不代理 UDP。生产不依赖 Nginx。

为避免与 UDP 25565 冲突，smoke 临时把 Minecraft TCP upstream 写为 `server.properties:server-port=25566`；Hassium UDP listener 仍直接 bind `127.0.0.1:25565`。Nginx `stream` listener 把 TCP `127.0.0.1:25565` 转发到 `127.0.0.1:25566`。Windows `D:/app/nginx-1.31.3/nginx.exe` 启动前必须通过 `nginx -V` 和 `nginx -t -p <isolated-prefix> -c <generated-conf>` 验证包含 `stream` 支持。

由于一条 TCP 断开、permit 与 terminal 是不同生产路径，`-Phase UdpFailover` 必须运行三个隔离子场景，最后合并日志 marker；不允许把互斥状态硬塞进同一个 Minecraft client 生命周期。

| 子场景 | 真实触发 | 应得到的 marker |
|---|---|---|
| `recovery` | 两个独立 nginx instance：primary `25565 -> 25566`，backup `25567 -> 25566`。客户端先连 primary；停止 primary instance 断当前 TCP，保留 backup instance；S2C control candidates 含 `127.0.0.1:25567`。 | `UDP_BIND_OK`、`UDP_WRR_OK`、`FAILOVER_RECONNECT_OK`、`CACHE_RESUME_HIT` |
| `permit` | UDP Bind 后，dev-only smoke driver 经已认证 KCP 发送真实 `TYPE_FAILOVER_REQUEST`；它不伪造结果。服务端按真实 `ControlFailoverHandler.requestFailover` 校验 stall/epoch/session，回 `TYPE_FAILOVER_PERMIT` 并关闭 master。 | `FAILOVER_PERMIT_OK`；若随后重连也可重复得到 recovery marker |
| `terminal` | primary nginx 同 recovery，但 S2C only advertises 无 listener 的 control candidates（如 `127.0.0.1:25568`）；停止 primary 后 vanilla reconnect 按候选失败直至穷尽。 | `FAILOVER_TERMINAL_OK`，且 terminal finalization 恰为一次 |

PowerShell harness 对每个子场景独立清理 world / cache / nginx prefix / server / client，收集 server+client logs 到一个 session result。Nginx 停止必须按 instance-specific prefix/PID，而非全局 `taskkill nginx.exe`，以避免误杀用户已有实例。失败路径总在 `finally` 清理本 harness 创建的进程与临时文件。

最终 `UdpFailover` PASS 必须同时满足：三子场景的 client process 均按预期退出、六个 marker 均至少出现一次、terminal marker 正好一次、disabled 子场景中 UDP listener/Bind/WRR/permit markers 均为零。

## 非目标

- 不实现 Minecraft server 玩家状态跨物理服务端 handoff。
- 不把 Nginx 作为生产组件、UDP proxy 或公网 endpoint discovery 服务。
- 不新增 QUIC、多条并行 vanilla Play connection、`MixinConnection` 全包路由。
- 不改变 Forge 支持范围；本次实现先 Fabric + NeoForge，Forge 保持不变。
- 不在本轮处理 token lifecycle；token 继续由 `DataPlaneUdpServer.Instance` 每次 bind 时生成并通过现有 S2C tail 下发。

## 验收

1. 默认配置在同一机器可同时监听 Minecraft TCP 25565 和 Hassium UDP 25565；没有 25566/25567 的生产默认依赖。
2. 配置两个 UDP listener、每 listener 两个 reachable endpoints 时，握手可表达该分组；客户端每 listener 只建立一个活跃 session，WRR 只按 listener weight 计数。
3. wildcard bind host 永不下发；非法 reachable endpoint 被拒绝/回退且 Primary TCP 保持可用。
4. `dataPlane.enabled=false` 时无 UDP listener、无 UDP tail、bulk 走 Primary TCP；control candidate 恢复功能不被关闭。
5. `UdpFailover` 三场景全部通过，六个 marker 全到；terminal 一次；nginx 只承担 TCP 断链注入。
6. 1.20.1 Fabric 单测、编译、runtime smoke 通过；完成后在 1.20.5 与 1.21.11 先编译锚点，再进行 NeoForge 对应接线与锚点 smoke。
