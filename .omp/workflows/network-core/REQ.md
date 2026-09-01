# REQ — 网络核心（客户端进程内 MC 协议代理）

> 状态：已确认，待按新 TASKS 实现
> 日期：2026-08-31
> 取代：2026-08-09 的 `25565 + 25566` Gateway sidecar、handler 直调注入、UDP 数据面和跨主控热备设计

## 1. 问题与结论

旧实现不是完整的 Minecraft 网络代理：客户端先保留原版 `25565` 壳连接，再由 `gateway_info` 建立独立 `25566` Gateway 连接，PLAY 数据经自有帧到达后直接调用客户端 handler。该拓扑造成两个连接共同拥有一个玩家会话，并衍生出壳 keep-alive、登录桥、占位玩家、额外 listener、续流重建和 UDP 降级等补丁。

本次重构采用一个明确模型：

1. NetworkCore 是**客户端进程内的完整 MC 协议代理**。
2. 原版客户端只连接 NetworkCore 的一次性 loopback TCP listener；对客户端仍是一条普通 MC Connection。
3. NetworkCore 代表客户端连接服务端标准 MC listener；服务端进程只监听 `server-port`（默认 `25565`），不新增 Gateway/UDP 端口。
4. 服务端可公布多个外部 `host:port` 路由；这些路由可经 DNS、NAT、随机端口映射或四层 TCP 隧道进入同一个 MC listener、同一个服务端 JVM。
5. 任一时刻只有一条活动 Control Session；传输故障时只替换上游 route，下游 Connection、客户端世界和 `ServerPlayer` 不重建。
6. Hassium 增强 PLAY 流使用统一 Envelope：恢复原版 packet body 前完成有界聚合、RAW/ZSTD 解码、排序、ACK 和重放。
7. ShadowPull 可用同一组 endpoint 建立附属 Pull lanes；lane 不创建玩家、不拥有登录状态，只承载幂等 Compare+Pull。
8. UDP、旧 `25566` Gateway wire、handler 直调主路径和旧聚合/ZSTD pipeline 全部删除，不保留兼容层。

## 2. 目标

- 客户端获得一个稳定的本地 MC 连接端点；上游 IP/端口变化不传播为客户端断线或世界重载。
- 同一个标准 MC listener 同时服务原版客户端和 Hassium 客户端。
- Hassium 客户端未启用核心、服务端未声明能力或双方无共同协议版本时，完全走原版直连。
- 增强会话中，服务端原版 PLAY 包统一封装；NetworkCore 解压、解聚合后在原版字节管线恢复，第三方 mod 看到正常原版包。
- 同一服务端 JVM 的多入口发生 TCP 故障或黑洞时，10 秒内可无感切换 route；非区块 PLAY 包无静默丢失、无重复执行。
- ShadowPull 在多个 route 上并行利用带宽，且 lane 故障不触发 Control Session 迁移。
- 网络压缩、聚合、可靠性和路由逻辑收口于 NetworkCore/Envelope，不再散落在原版 Connection pipeline 与 loader 平台接收器。

## 3. 非目标

- 不处理服务端 JVM、主机或世界进程崩溃；所有 route 必须落到同一运行中的服务端进程。
- 不实现 A/B 主控、世界热备、跨进程 `ServerPlayer`/tick/实体状态复制或写权迁移。
- 不按 RTT、丢包率或吞吐退化自动迁移；首版只记录线路质量。
- 不实现按 packet type、距离或 LOD 的优先级超车；该能力另行设计。
- 不主动实现 ViaFabric 协议翻译，也不把跨版本 ViaFabric 运行验证列为本轮验收项。
- 不保证 Velocity、BungeeCord 等七层 Minecraft 代理兼容；只保证字节透明的四层 TCP 入口。
- 不自动迁移旧按地址命名的客户端影子缓存。
- 不兼容旧 `25566` Gateway wire、旧 UDP wire 或旧配置键。

## 4. 拓扑与术语

```text
原版客户端 / 第三方客户端 MOD
        │ 标准 MC 协议；单一稳定 Connection
        ▼
127.0.0.1:<ephemeral>  一次性 loopback listener
        │
        ▼
NetworkCore（客户端进程内）
        ├─ Control Session：恰好 1 条活动上游 TCP
        │      ├─ a.com:1111 ─┐
        │      └─ b.com:2222 ─┴─→ server-port 25565 / 同一 MinecraftServer
        │
        └─ Pull lanes：每 endpoint 最多 1 条，全局最多 4 条
               ├─ lane(a.com:1111)
               └─ lane(b.com:2222)
```

- **bootstrap address**：用户服务器列表中填写的地址；首次 STATUS 和无缓存恢复入口。
- **reachable endpoint**：服务端公布的外部 `host:port`；外部端口不要求等于 `server-port`。
- **logicalServerId**：服务端首次生成并持久化的 UUID；端点、resume、ShadowPull 和客户端缓存身份均绑定该值。
- **Control Session**：完整 MC 会话的唯一所有者，承载登录后所有非 Pull PLAY 流和迁移状态。
- **Pull lane**：Control Session 签发、仅承载 ShadowPull 请求/响应的附属 TCP；不创建 `ServerPlayer`。
- **Envelope**：Hassium 增强 PLAY 的可靠传输单元；可装一个或多个原版 packet body。
- **packet body**：目标服务端协议版本下的 `packetId + payload`，不含外层 MC frame length；NetworkCore 不按具体 packet class 重编码未知包。

## 5. 硬性不变量

1. 服务端模组不得监听除原版 MC listener 外的 TCP/UDP 端口。
2. 客户端对游戏世界只有一条 loopback 下游 Connection；不得保留原版远端壳连接。
3. Control Session 任一时刻最多一个 active route；双候选竞速成功后必须关闭输家。
4. 多 endpoint 必须指向同一服务端 JVM；否则 resume 必须失败，禁止伪造跨进程无感切换。
5. 增强会话的全部原版 PLAY C2S/S2C 包必须进入 Envelope；不得旁路为第二套自定义业务通道。
6. Envelope 自身和 ACK/heartbeat 控制帧必须带 bypass 标记，禁止递归封装。
7. 非增强连接不得进入 Envelope/ZSTD/聚合/重放代码；保持原版编码、Zlib 和登录行为。
8. 增强链路不存在 Zlib 套 ZSTD；上游增强登录不启用 vanilla `SetCompression`，loopback 下游也不压缩。
9. 每个 ZSTD Envelope 必须独立可解压；禁止跨 Envelope 的流式/上下文压缩状态。
10. S2C 聚合不得改变原版 packet 全局顺序；首版不允许 packet 类型级重排。
11. 通用 8 MiB/1 MiB replay 不缓存区块 Pull payload；区块可靠性只由 `epoch + requestId + deliveryId` 和重新拉取保证。
12. 迁移期间不得触发客户端 `handleLogin`、configuration 重放、`setLevel` 或原版断线界面。
13. 显式 kick/disconnect、鉴权失败、封禁、客户端主动退出和服务端正常关闭不得触发故障转移。
14. NetworkCore 不决定区块 admission、缓存命中、本地生成、渲染或卸载；这些仍属于区块核心/影子端。

## 6. 能力发现、直连与登录

### 6.1 STATUS 能力发现

- 服务器列表 STATUS 由 NetworkCore 的轻量探测器执行；它不需要先启动完整 loopback 代理。
- 用户通过 Direct Connect/Quick Connect 且本次会话没有 STATUS 结果时，连接前补一次 STATUS probe。
- Hassium 服务端在标准 STATUS JSON 中增加原版客户端可忽略的能力字段，至少包含：
  - `networkProtocolVersions`
  - `logicalServerId`
  - `reachableEndpoints`
  - `envelope`、`resume`、`shadowPullLanes` 能力位
- `net.enabled=false`、`master.enabled=false`、STATUS 无 Hassium 字段或双方协议版本无交集时，客户端完全直连用户填写的远端地址，NetworkCore 不进入 PLAY 路径。
- 新客户端连接旧服务端、旧客户端连接新服务端，默认都走原版 wire；不启动旧 Gateway 兼容路径。
- 服务器仍保留 `compat.requireClientMod` 管理开关。关闭时原版客户端可登录；开启时服务端可在标准登录阶段明确拒绝未响应 Hassium query 的客户端。

### 6.2 STATUS 与 login query 一致性

- STATUS 声明存在共同 Envelope 版本后，客户端才启动 loopback 代理。
- 标准 login query 是最终能力确认和参数协商点。
- 若 STATUS 声明兼容，但 login query 未确认、超时或返回不一致版本，本次连接必须中止并提示：服务端 Hassium 能力已变化，请刷新服务器列表后重连。
- 不允许在已经建立 loopback 下游后偷偷进行第二次原版直连或双登录。

### 6.3 Loopback 下游

- 每次加入服务器创建仅绑定 loopback 的随机端口 listener；只接受本次预期连接，接入后立即停止 accept。
- 原版 Connect 流程改连 loopback，但必须保留 bootstrap 地址、所选 route、目标协议版本和虚拟主机信息。
- NetworkCore 充当下游本地 MC server，独立终止本地 handshake/login/configuration/play；本地链路不执行 Mojang session 验证，不启用压缩。
- listener 生命周期绑定本次连接；失败、取消和退出必须关闭，不得留下后台端口。

### 6.4 上游登录

- NetworkCore 充当标准 MC client，代表当前账号连接远端。服务端收到 Login Start 后，在任何 `SetCompression` 决策前发送无秘密的 `HASSIUM_HELLO` query；响应只声明共同版本和 `FRESH_CONTROL`、`RESUME_CONTROL`、`PULL_LANE` mode。
- 未响应/无共同版本时恢复原版登录状态机：在线模式执行 encryption/session auth，随后按原版配置发送 `SetCompression`；`compat.requireClientMod=true` 时可改为明确拒绝。
- `FRESH_CONTROL` 在线模式继续标准 encryption 与 `joinServer`/session auth，但跳过 `SetCompression`；之后标准 LoginSuccess/configuration/play。离线模式同样不启用压缩。
- `RESUME_CONTROL`/`PULL_LANE` 在线模式先完成标准 encryption handshake，再通过加密的 `HASSIUM_BIND` query 提交 ticket；服务端在 Mojang session auth、玩家数据加载和玩家创建前验证并切换专用状态。离线模式缺少原版加密，安全性不高于原版离线服。
- `RESUME_CONTROL` 成功后双方用 `HASSIUM_BIND_OK` 直接安装既有 PLAY session codec；不得发送或转交 LoginSuccess/configuration。`PULL_LANE` 成功后安装 Pull lane codec，不进入 PLAY。
- 正版首次登录使用客户端进程内 access token 执行 `joinServer`；secure profile、signed chat/command、registry/configuration 和未知 custom payload 必须按原始协议数据转发，不得重新生成业务内容。
- NetworkCore 只消费上述 Hassium query；所有第三方 login query、configuration custom payload 和 loader 握手必须按顺序转交下游客户端。
- loopback 下游使用独立标准登录状态机且始终无压缩；Envelope 只在 fresh login 完成或 resume bind 成功后的上游 PLAY 状态启用。

## 7. 端点与逻辑服务身份

### 7.1 服务端配置与监听

- `server-port` 是唯一 MC listener，默认 `25565`。
- `master.reachableEndpoints` 只用于通告公网路由，不用于 bind；允许 `a.com:1111`、`b.com:2222` 等不同外部端口。
- route 只支持 DNS/NAT/端口映射/四层 TCP 隧道等字节透明入口，最终必须进入同一 listener/JVM。
- 清单为空时只使用当前 bootstrap address，视为单 endpoint，不具备 Control failover。

### 7.2 logicalServerId 与持久化

- `master.logicalServerId` 首次启动自动生成 UUID 并持久化；正常配置重载、服务重启和 route 变化不得改变。
- 客户端用 `logicalServerId` 作为影子世界、端点清单、健康统计和 resume 的稳定身份。
- 新目录不得自动合并旧 `hassium_cache/server_<address>`；旧目录原地保留且不读取，避免错误服务器缓存污染。
- 每次成功 STATUS 或 login 返回的清单都是当前权威清单；客户端原子替换持久化端点集，不要求服主维护单调 revision。
- bootstrap address 必须作为恢复入口保留；服务端配置应把它列入 `reachableEndpoints`，客户端也不得因异常清单永久失去用户原始入口。
- 清单不建立超出原版 MC 连接的 MITM 防护承诺。错误 endpoint 无法验证当前服务端的 session/resume 状态时，必须失败并尝试下一候选。

### 7.3 路由健康与选择

- 首次无历史状态时先用 bootstrap；已有清单时可依据最近成功、TCP 建连时延和失败冷却排序。
- Control 故障迁移取健康度最高的两个未冷却 endpoint 并发执行 TCP + resume 验证；首个完成验证者获胜，其余立即取消。
- 候选不足两个时使用剩余候选顺序回退。
- 质量退化只采集指标，不触发自动迁移。

## 8. Envelope 协议

### 8.1 边界

- Envelope 的 PLAY carrier 使用稳定语义 channel `hassium:network_envelope_s2c` 与 `hassium:network_envelope_c2s`；ACK/heartbeat 和 Control fallback Pull 通过同一 carrier 的不同 `frame kind` 表达，不新增第三条 PLAY carrier。
- channel Identifier 稳定，但外层 numeric packet id、custom-payload 类型/codec 和 pipeline hook 按七段协议 adapter 映射；禁止把某一 MC 版本的 numeric id 硬编码为全局 wire 常量。
- carrier 只在 login query 确认共同版本后发送；原版/无共同版本连接永远看不到 carrier。
- packet body 截获点严格位于原版 packet encoder 已写出 `packetId + payload` 之后、outer frame length/vanilla compression 之前；非 Hassium 连接直接进入原版 length/compression 发送链。
- 接收端由当前版本 adapter 识别标准 custom-payload carrier，再把完整 Envelope 恢复出的 packet body 注入目标版本 packet decoder 之前；未知业务 custom payload 仍作为普通 packet body 按字节透传。
- carrier header 固定包含 carrier version、direction、carrier messageId、fragment index/count、total length；单片上限 `256 KiB`。每个七段 adapter 必须提供该版本最大合法原版 packet-body 长度，codec 用 checked arithmetic 分别计算 uncompressed Envelope 上限与 `ZSTD_compressBound` wire 上限；不得用固定 8 MiB 截断接近原版上限的合法包。
- 一个连接每方向最多一个未完成 assembly；最大 fragment count 由该版本 wire 上限推导。同一 message 的 fragments 必须连续、index 严格递增且不得交错。assembly 最长存活 3 秒；缺片、冲突重复、越界或超时立即释放并 fail closed。完整重组并校验前不得推进 DATA sequence。
- 当前版本 adapter 只有在精确 semantic channel 且 channel 标记为内部发送时才能 bypass；单一 carrier writer 保证一个 message 的 fragments 连续写出。业务包伪装字段、超出 adapter 上限或 checked arithmetic 溢出均拒绝。

### 8.2 必备字段

每个 Envelope/控制帧按 frame kind 携带并校验：

- protocol version、frame kind、logicalServerId、sessionId、epoch
- carrier messageId 与分片后的 reassembled length
- `DATA`：direction sequence、cumulative ACK、packet count、每个 packet body 的有界长度前缀
- `PULL_REQUEST/PULL_RESPONSE`：worldEpoch、requestId、chunk key、deliveryId/terminal；使用 request ledger，不占 DATA sequence/replay
- `ACK/HEARTBEAT`：只携带对应控制字段，不包含原版业务 packet
- codec：`RAW` 或 `ZSTD`，以及 compressed/uncompressed length

协议必须拒绝未知版本、方向错误、epoch 错误、sequence 越界、长度溢出、包数超限、ZSTD 解压尺寸不符和尾随垃圾。解压前先验证协商上限，禁止压缩炸弹式无界分配。

### 8.3 压缩

- 增强链路唯一压缩算法为 ZSTD，但小 Envelope 允许 `RAW`。
- 默认 `uncompressedLength < 256 B` 使用 RAW；达到阈值使用独立 ZSTD frame。
- 不使用 Zlib、跨帧 streaming context、动态字典同步或依赖前帧的压缩状态。
- 聚合批次整体压缩一次；解压后按长度表恢复原版 packet body。

### 8.4 S2C 有界自适应聚合

- 以**每玩家**为单位维护 bulk batch，不得使用跨玩家共享 batch。
- 每个 Control Session 只有一个 S2C egress arbiter。所有 vanilla、loader、平台和异步发送先在统一 `Connection.send` admission gate 原子预留有界 slot 并分配 `packetOrdinal`；普通包编码后的 `packetId + payload` 回填该 slot，chunk/light 则转换为同 ordinal 的 fence 事件。
- batch timer、small、oversized、disconnect、writability 和编码完成只能向 arbiter 投递事件；不得从其他线程直接写业务 carrier。Envelope sequence 只由 arbiter 按实际 emit 顺序分配。
- 编码后 `< 4 KiB` 的原版包视为 small：独立 Envelope、立即发送，不与 bulk 同批。
- 编码后 `>= 4 KiB` 的原版包进入 bulk batch。
- batch 在累计 `256 KiB`、`64` 个 packet 或最老 packet 等待 `50 ms` 任一条件满足时立即 flush。
- 单个合法 packet 超过 `256 KiB` 时作为 ordering barrier：先 flush 所有更早 bulk，再独立 Envelope 发送，不因超过 batch 目标而拒绝。
- small 和服务端显式 disconnect 都是 ordering barrier：发送前必须先 flush 更早 bulk，不得越过先到业务包。
- ACK-only/heartbeat 不恢复为原版业务包，可以越过 pending bulk；协议错误可直接 fail closed，不承诺先发送 pending 业务包。

### 8.5 C2S

- 每个原版 C2S packet 独立一个 Envelope，不做客户端聚合。
- C2S 仍使用 RAW/ZSTD 阈值和 sequence/ACK；不得以聚合吞吐为由延迟移动、交互、聊天或命令。

## 9. Control Session 可靠性与迁移

### 9.1 状态机

```text
IDLE → PROBING → CONNECTING → HANDSHAKING → ACTIVE
                                            │
                     transport failure ─────┤
                                            ▼
                                        MIGRATING
                                         │     │
                              resume ok ──┘     └─ deadline/overflow → CLOSED
```

- DIRECT 原版直连不是 NetworkCore 的 ACTIVE 状态，不运行 heartbeat、Envelope 或 resume。
- DIRECT 模式同时关闭 Envelope、resume 和 ShadowPull lanes；不得只绕过 Control 却保留第二条优化通道。
- 单 endpoint 会话发生传输故障时从 ACTIVE 直接 CLOSED，并关闭附属 Pull lane；不得进入 10 秒保留窗口。

### 9.2 触发与不触发

必须触发迁移：

- TCP FIN/RST、connectivity exception 或 channel 非预期关闭。
- 空闲时每 1 秒发送 heartbeat；连续 3 秒没有任何有效上游入站数据时视为静默故障。
- 开发/验收用手动迁移命令。

不得触发迁移：

- 服务端显式 disconnect/kick、封禁、白名单或鉴权失败。
- 客户端主动退出。
- 服务端正常关闭并发送明确原因。
- 仅 RTT/吞吐变差但连接仍有有效入站。

### 9.3 Session、ticket、epoch

- 初次 Mojang-authenticated fresh login 创建唯一 `sessionId`、双向 sequence 和串行 session owner；服务端在已认证加密通道内下发随机 `sessionBindKey` 与 5 分钟 HMAC resume ticket，二者绑定 `logicalServerId + player UUID + sessionId + epoch`。离线服保持原版离线安全边界。
- ACTIVE 期间服务端最迟在 ticket 到期前 60 秒通过 ACK/heartbeat 控制帧下发下一张 ticket；客户端原子替换，新旧 ticket 只重叠 30 秒。会话持续时间不得使首个 failover 因 ticket 过期失效。
- 每个候选生成唯一 `attemptId`。`HASSIUM_BIND` 携带 server nonce、attemptId、ticket，以及 `HMAC(sessionBindKey, nonce + transcript + mode + sessionId + epoch + attemptId)` proof；bearer ticket 单独不足以接管 resume/lane。
- session owner 以 epoch/CAS 只提交一个 attempt，并在 10 秒窗口内保存 `attemptId → COMMITTED/REJECTED` 结果。成功顺序为：旧 transport 标记 superseded、新 channel 绑定、既有 PLAY codec 安装、epoch/ticket 轮换、发送 BIND_OK。
- 若 COMMITTED 后 BIND_OK/新 ticket 丢失，客户端可用 predecessor ticket、相同 attemptId 和新 nonce/proof 查询并恢复同一提交结果；predecessor 仅允许该 attempt 的幂等恢复，不允许新接管。旧 FIN/packet 不得 teardown 玩家，其他 attempt 关闭自身。
- HMAC secret/sessionBindKey 只存在当前进程/会话；JVM 重启后失效。resume 不执行 Mojang auth、玩家数据、LoginSuccess/configuration、玩家创建或 join/quit。

### 9.4 ACK、重放与去重

- 两端 high-watermark 属于 `sessionId`，不随 route/epoch 重置。NetworkCore 保存 `downstreamCommittedS2c`；服务端保存 `acceptedC2s`。
- S2C 只有完整 Envelope 按序且 loopback write future 成功后才推进 watermark/ACK；写失败或下游关闭结束整个会话。C2S 只有服务端去重并接受进原会话执行队列后才推进 watermark/ACK。
- resume bind 双向交换 high-watermark；声明值超出本端已发送/保留范围时拒绝。发送端只释放累计 ACK 覆盖的 replay，并从 `peerHighWatermark + 1` 重放。
- replay 存储与 route header 解耦的 DATA payload。rebind 后用**新 epoch + 原 session sequence + 新 carrier messageId**重新封装；接收端允许当前新 epoch 的 replay，并在 packet decoder 前按 session high-watermark 丢弃已提交 sequence、重新 ACK。只拒绝旧 transport/旧 epoch frame。
- ACK 可 piggyback，单向空闲时独立发送。ACK 丢失不得重复执行业务；下游 keep-alive 在 MIGRATING 期间由 NetworkCore 终止，不进入上游 C2S replay。

### 9.5 10 秒保留窗口

- 仅当去重后的 `reachableEndpoints` 至少有两个时启用。
- 客户端下游保持连接、画面停留当前世界，并按序缓存非 Pull C2S；不得显示登录或重载界面。
- 服务端保留**同一个** `ServerPlayer` 和 entity，世界继续正常 tick；不提供冻结、无敌或时间暂停。
- 服务端暂停该玩家新的区块 Pull/bulk 生产；非区块 S2C 进入有界 replay。
- resume 在 10 秒内成功则重绑原会话并按 ACK 续传；失败则执行一次标准断线，包含明确失败原因。

### 9.6 缓存与背压

- 服务端每玩家非区块 S2C 总 retention budget 默认 `8 MiB`；客户端非 Pull C2S 默认 `1 MiB`；双向同时限制 `4096` 个 outstanding reservation/Envelope。
- admission gate 在安排编码前预留 count slot；编码后按实际 bytes 调整 reservation。arbiter queue、pending batch、carrier/Netty 待写和未 ACK replay 全部计入同一逻辑预算，不得只限制 replay 容器。
- 已知 bulk producer 在接近上限时暂停；不可暂停的 vanilla/loader producer 不得阻塞世界线程，reservation 失败时由 session owner 受控断线。Netty writability 只是信号，不是唯一内存边界。
- ACK/flush 释放 reservation；达到上限不得继续排队、丢旧包、覆盖 sequence 或无限扩容。
- ShadowPull payload 不计入 DATA budget，但受 lane/request/payload 独立上限；lane 故障或 epoch 变化按 ledger 重拉。

## 10. ShadowPull lanes

### 10.1 建立与身份

- Control ACTIVE 后为健康 endpoint 建立 Pull lane：每 endpoint 最多一条，全局硬上限 4 条。
- 即使只有一个 endpoint，也建立一条独立 Pull lane，以隔离区块 bulk 与 Control 拥塞；但 Control 故障仍立即断线，不启用 failover。
- lane 使用相同外部 `host:port` 和标准 MC handshake。服务端发出的第一项 Hassium login query 要求客户端声明 `FRESH_CONTROL`、`RESUME_CONTROL` 或 `PULL_LANE`；在线模式先建立连接加密，但该分支必须位于 Mojang session 校验、玩家数据加载和 `ServerPlayer` 创建之前。
- `PULL_LANE` 提交 Control 签发的短期 lane-scope ticket；验证成功后立即把 login listener 替换为 Pull lane state，绝不进入 vanilla login success/config/play。query 不支持或验证失败时，普通连接按原版登录/管理开关处理，lane 尝试只关闭自身。
- lane ticket 绑定 `logicalServerId + sessionId + epoch + endpoint/lane scope`；成功 lane 不进入玩家列表、玩家数据或世界状态。

### 10.2 请求与可靠性

- 请求逻辑身份是 `epoch + requestId + chunk key`；同一 epoch 的 lane 重派必须复用 requestId，服务端 ledger 返回同一权威终态。
- 每个终态携带服务端分配的 `deliveryId`。同 epoch 重试复用原 terminal/deliveryId；客户端以 `epoch + chunk key + deliveryId` 做 apply/ACK CAS，重复响应不得再次 apply。
- Control rebind 后提升 epoch；未完成 Pull 用新 requestId 重新发起并获得新 deliveryId，所有旧 epoch 终态无条件拒绝。
- 一个请求在任一 lane 只产生一个权威终态：`UNCHANGED`、`DELTA`、`FULL`、`ERROR`；`UNCHANGED` 明确授权使用已提交本地基线，零差异不得伪装成空 DELTA。
- lane 断开时只重派未完成请求；迟到响应、重复终态和已 ACK delivery 必须幂等丢弃。
- Control rebind 后关闭并重建全部 lanes。
- 所有 lanes 暂不可用且 Control 仍为 ACTIVE 时，请求回退到 Control 内**按同一 request ledger 记账的 Pull carrier**；它不是普通 vanilla chunk 流，也不进入通用 replay。MIGRATING 期间禁止 fallback 生产/发送，resume 后以新 epoch 重派。
- Pull 响应使用独立 RAW/ZSTD frame 和严格长度上限；区块 payload 不进入通用 Control replay。

### 10.3 与区块核心边界

- 所有 S2C（含 chunk/light）先在统一 admission gate 获得 packetOrdinal。普通包编码后进入 DATA slot；chunk/light 不进入通用 encoder/replay，而向同一 arbiter 提交 `CHUNK_FENCE` slot。
- arbiter 处理 `CHUNK_FENCE` 时先 flush 所有更早 bulk，等待其 emit sequence 确定，再以此前最后一个 DATA sequence 完成 fence future。只有 future 完成后区块核心才能派发对应 Pull，`controlFenceSequence` 必须取该结果，禁止采样瞬时 sequence/ACK。
- Pull 请求/终态携带 `worldEpoch + requestId + deliveryId + controlFenceSequence + chunkGeneration`。客户端仅在对应 Control fence 已提交、worldEpoch/chunkGeneration 匹配时 apply。
- respawn/维度切换提升 worldEpoch；forget/unload 或本地基线失效提升 chunkGeneration。迟到终态丢弃或新代重拉。缓存/本地生成、hash、FULL/DELTA 和 vanilla chunk/light apply 仍由区块核心负责。

## 11. ViaFabric 边界

- NetworkCore 不实现协议翻译，不维护旧专用 ViaFabric bridge。
- 设计顺序必须允许 ViaFabric 理论上自然工作：
  - STATUS 保留真实上游 protocol version。
  - loopback 重定向保留原始目标地址/版本元数据。
  - S2C Envelope 解封后，把目标服务端版本 packet body 放在 ViaFabric inbound translation 之前。
  - C2S 在 ViaFabric outbound translation 之后封装。
- 首版不检测、不强制直连 ViaFabric 跨版本连接，也不做跨版本运行验证；行为标记为 best-effort、低优先级后续项，不得宣称已兼容。

## 12. 配置清理

### 12.1 保留或新增

```text
net.enabled = true
net.heartbeatIntervalMs = 1000
net.silentTimeoutMs = 3000
net.migrationC2sBufferBytes = 1048576

master.enabled = true
master.logicalServerId = <首次生成并持久化 UUID>
master.reachableEndpoints = [host:port, ...]
master.envelopeZstdLevel = 3
master.envelopeZstdThresholdBytes = 256
master.envelopeBulkThresholdBytes = 4096
master.envelopeMaxBatchBytes = 262144
master.envelopeMaxBatchPackets = 64
master.envelopeMaxWaitMs = 50
master.resumeWindowMs = 10000
master.resumeS2cReplayBytes = 8388608

compat.requireClientMod = false
```

- `master.maxChunksPerTick`、ShadowPull、区块核心、存储和 debug/metrics 相关有效键不因本次网络重构自动删除。
- replay 同时存在协议硬上限 `4096 envelopes`，不得通过配置解除。

### 12.2 完全删除且不做 alias

```text
master.controlReachableEndpoints
master.bindHost
master.authToken
master.compressionLevel
master.magiclessZstd
master.globalCompressionLevel
master.globalCompressionThreshold
master.globalPacketCompression
master.useContextCompression
master.enablePacketAggregation
master.aggregationMinBatchSize
master.aggregationMaxWaitTimeMs
master.aggregationMaxSize
master.enableCompactHeader
master.compressionBlacklist
旧 migration TPS/load/maintenance/idle/prewarm 策略键
dataplane.*
```

schema、配置读写、GUI、语言文件、默认配置、文档和测试必须同步删除；不读取旧键、不静默映射旧语义。

## 13. 代码清理边界

必须删除或彻底替换：

- 独立 `25566` `GatewayServer`/`GatewayChannel` listener 和 `gateway_info` 二次建连。
- `ControlFrameCodec`/旧 outbound sidecar wire、`GatewayPlayerBridge` 占位玩家/二会话附着路径。
- handler 直调 S2C、`MixinConnection` PLAY 吞包式 C2S 主路径和“原版壳只留 keep-alive”逻辑。
- `ZstdPipelineSwitcher`、旧 Zstd encoder/decoder/context、旧 `HassiumAggregationManager`、compact header、compression blacklist 和客户端平台聚合接收器。
- UDP/KCP/DataPlane listener、客户端 bundle、握手尾、router、加密、fallback、配置、指标、测试和文档。
- 旧 TPS/负载/维护窗口/预热迁移策略、跨主控会话同步和恢复 UI 补丁。
- 已被新 Envelope/Pull lane 覆盖的 dead API、ServiceLoader 方法、packet id、mixin、资源注册和兼容注释。

必须保留并迁移调用方：

- 统一 ShadowPull/Compare+Pull、`ServerChunkPushManager` 中仍有效的区块限速与权威解析。
- 区块核心、影子端、缓存、OVD、SeedGen、光照、实体与 export 逻辑。
- 存档 `CompressionService`/type 126；网络 Envelope ZSTD 不得复用其有状态业务上下文。
- 原版客户端/服务端非增强路径。

采用 clean cutover：不得留下旧 wire shim、deprecated alias、双写、双收或“临时 fallback 到 25566”。

## 14. 安全、资源与线程

- loopback listener 只绑定本机地址、一次一连接、生命周期有界；不得监听 `0.0.0.0`。
- resume/lane ticket 必须 HMAC 校验、绑定 session/epoch/scope，并在成功 rebind 后通过 epoch 失效旧票据。
- 服务端所有 session rebind、sequence 接受和 `ServerPlayer` 连接替换必须在明确的串行所有权边界完成。
- packet body、packet count、聚合大小、压缩/解压长度、pending 请求、lane 数和 replay 都必须有硬上限。
- Netty event loop 不执行 MC 世界逻辑；恢复出的 packet 沿原版线程切换语义进入下游。
- 热路径避免不必要的 Packet 对象重建、byte[] 往返和重复压缩；优先有所有权清晰的 ByteBuf slice/composite buffer。
- 协议错误只关闭对应连接；Pull lane 错误不得误杀健康 Control，Control 身份/sequence 错误必须 fail closed。

## 15. 指标与诊断

至少提供以下可观察状态，且不依赖 debug 日志才能验收：

- 当前模式：`DIRECT` / `NETWORK_CORE`，选择原因和共同协议版本。
- `logicalServerId`、活动 Control route、候选健康/冷却、Pull lane 到 endpoint 的映射。
- Envelope 原始字节、RAW/ZSTD 字节、压缩比、small 数、bulk batch 数/包数/等待时间。
- 双向 sequence/ACK、未确认 Envelope 数、S2C/C2S replay bytes、背压和 overflow 次数。
- heartbeat RTT、静默触发、迁移开始/成功/失败/耗时/route。
- Pull 各 lane 请求数、在途数、重派数、四种终态、Control fallback 次数。
- login/config/setLevel 计数，用于证明迁移未重走客户端世界初始化。

日志不得输出 access token、resume/lane ticket、完整 HMAC、聊天内容或原始业务 payload。

## 16. 验收标准

### 16.1 拓扑与兼容

- [ ] 服务端进程只监听标准 MC `server-port`；不存在 `25566`、UDP/KCP 或额外 Hassium listener。
- [ ] `a.com:1111`、`b.com:2222` 两个四层入口均能进入同一标准 listener，并被 STATUS/login 原子下发。
- [ ] 原版客户端连接新服务端：标准 STATUS/login/config/play 成功，走原版 Zlib/packet 路径。
- [ ] 新客户端连接旧服务端、`net.enabled=false`、`master.enabled=false`、无共同 Envelope 版本：均完全原版直连。
- [ ] STATUS 宣称兼容但 login query 失配：一次明确错误并要求刷新，不发生自动双登录。
- [ ] `compat.requireClientMod=true` 时只改变管理准入，不启动旧 wire。

### 16.2 完整代理与 Envelope

- [ ] adapter 上限按合法 packet body 与 ZSTD worst-case checked 计算；接近版本最大值的不可压缩 packet 可分片 round-trip，不被固定 8 MiB 误拒绝。
- [ ] loopback listener 一次性、仅本机、取消/失败/退出后无残留；原版客户端 Connection 全生命周期正常。
- [ ] 正版与离线登录均通过代理完成；configuration registry、secure profile 与 signed chat/command 不被破坏。
- [ ] 在双方 MC 协议版本相同、无 ViaFabric 翻译时，任意原版 PLAY packet body 和未知 custom payload 经 C2S/S2C Envelope round-trip 后字节一致、顺序一致。
- [ ] 七段 adapter 对同一 semantic carrier 提供正确的 numeric packet/custom-payload codec 映射；单 message 分片连续，assembly 超时/冲突/超额立即释放，最多一个未完成 assembly。
- [ ] `HASSIUM_HELLO` 在 SetCompression 决策前完成；fresh、resume、lane、vanilla 四条登录状态转换均无 Zlib 时序错位。
- [ ] 增强连接无 vanilla Zlib；RAW 与独立 ZSTD frame 均可解码，随机单帧重放不依赖前帧 context。
- [ ] 所有 S2C 来源经过单一 egress arbiter；bulk 按 `256 KiB/64/50 ms` flush，small/oversized/disconnect barrier 无顺序反转，ACK/heartbeat 不携带业务包。
- [ ] C2S 不聚合；移动/交互/聊天在无拥塞时不等待 S2C 聚合窗口。
- [ ] 畸形长度、超限 packet count、错误 epoch/sequence、ZSTD 尺寸不符均被拒绝且无无界分配。

### 16.3 Control failover

- [ ] ACTIVE 超过 5 分钟后发生故障仍持有有效轮换 ticket；BIND commit 后丢失 BIND_OK 可用相同 attemptId 幂等恢复。
- [ ] 错误 endpoint/主动中间人只有 bearer ticket、没有 sessionBindKey proof 时无法接管 resume 或 lane。
- [ ] 双入口实测断开活动 TCP：3 秒静默或明确传输错误触发迁移，健康度前两名竞速，10 秒内恢复。
- [ ] 迁移前后是同一个服务端 `ServerPlayer`/entity/session；无第二玩家、无退出/加入广播。
- [ ] 客户端下游不断线，无 `handleLogin`、configuration、`setLevel`、世界重载或断线界面。
- [ ] 非区块 S2C/C2S 在故障点前后按 sequence 无静默丢失、无重复执行；ACK/replay 指标归零。
- [ ] ACK 丢失且 S2C 已写入 loopback、C2S 已入服务端队列时，resume 依靠 session high-watermark 丢弃重放，业务仍只执行一次。
- [ ] 迁移期间服务端玩家正常世界 tick，不获得冻结或无敌；恢复后状态收敛。
- [ ] replay 接近上限时背压，超过 `8 MiB/1 MiB/4096` 时明确断线而非丢包。
- [ ] 单 endpoint 传输故障立即标准断线，不创建 10 秒保留会话。
- [ ] kick/封禁/鉴权失败/客户端退出/服务端正常关闭不触发迁移。
- [ ] 同一 resume ticket 的双候选竞速只允许一个 epoch/CAS 成功；旧 epoch 重放被拒绝。

- [ ] pending bulk → chunk fence → small、respawn → chunk、forget/unload → 迟到 Pull 的顺序测试证明 fence 在 arbiter emit 后完成。
### 16.4 ShadowPull lanes

- [ ] 每 endpoint 最多一条、总数最多四条；单 endpoint 也有独立 lane，均只使用标准 MC listener。
- [ ] `RESUME_CONTROL` 与 lane query 均在玩家创建前分流；resume 不发送 LoginSuccess/configuration，不创建第二玩家；lane 无玩家/PLAY 副作用。
- [ ] 多 endpoint 下请求分布到多个 route；任一 lane 断开仅重派未完成 requestId，不迁移 Control。
- [ ] `UNCHANGED/DELTA/FULL/ERROR` 携带 deliveryId 且幂等；同 epoch 重派复用 terminal/deliveryId，旧 epoch/迟到响应不重复 apply。
- [ ] ACTIVE 下全 lane 不可用时，`PULL_*` frame kind 复用现有 S2C/C2S carrier但不占 DATA replay；MIGRATING 下不发送。
- [ ] respawn/维度切换/forget/unload 建立 worldEpoch、controlFenceSequence、chunkGeneration fence；旧 Pull 结果不能 apply 到新世界/已卸载区块。
- [ ] 区块 payload 不进入通用 replay；迁移期间暂停生产，恢复后由 request ledger 补齐。

### 16.5 身份、清理与回归

- [ ] route 切换前后 `logicalServerId` 和客户端缓存目录不变；服务重启后 UUID 不变。
- [ ] 旧地址缓存不自动迁移、不被新 UUID 目录读取。
- [ ] 旧 Gateway/UDP/handler 直调/Zstd pipeline/aggregation/migration 策略代码、配置、资源、测试和文档零可达残留。
- [ ] ShadowPull、缓存命中、本地生成、OVD、光照、实体、export、断连保存等区块核心行为不回归。
- [ ] 客户端/服务端关键指标足以区分 DIRECT、ACTIVE、MIGRATING、Pull lane 和失败原因，且无凭据泄漏。

### 16.6 版本与运行验证

- [ ] `common:test` 覆盖 Envelope、聚合顺序、ACK/replay、ticket epoch、endpoint 选择、Pull ledger 和畸形输入。
- [ ] 七段锚点编译通过；Fabric/Forge/NeoForge 按 `docs/version-segments.md` 支持矩阵编译通过。
- [ ] 至少 `1.20.1 Fabric` 完成 DIRECT、单 endpoint 增强、双 endpoint failover、Pull lane 断开四个真实运行场景。
- [ ] 至少一个较新锚点完成双 endpoint failover 运行验证，防止 configuration 阶段差异漏测。
- [ ] ViaFabric 跨版本不在本轮 PASS 门禁；文档必须明确“未验证、best-effort”，不得写成已支持。
- [ ] `docs/architecture.md`、`docs/runtime-smoke-test.md`、`docs/config-audit.md`、README/语言配置说明与最终代码一致。

## 17. 后续项

- ViaFabric 跨版本真实验证与必要的目标版本元数据适配。
- 基于 packet type、距离、LOD、加载阶段的可证明安全优先级与可重排流。
- 基于线路质量的主动切换；必须先解决 hysteresis 和抖动。
- 若未来要求服务端进程级容灾，另立共享世界/tick/玩家状态/单写协议，不复用本需求的“同 JVM 多入口”表述。

## 18. 参考

- `docs/handoff/handoff-2026-08-09-network-core.md`：旧方案决策历史，仅作问题背景，不再是目标架构。
- `docs/architecture.md`：实现完成后必须同步的新架构真相源。
- `docs/version-segments.md`：七段/加载器支持矩阵。
- `docs/runtime-smoke-test.md`：运行时场景与证据格式。
- `.cursor/skills/hassium-manifold/SKILL.md`：跨版本 Manifold 规则。
