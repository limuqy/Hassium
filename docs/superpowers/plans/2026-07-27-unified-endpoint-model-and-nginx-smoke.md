# 统一可达端点模型与 Nginx Failover 冒烟 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `subagent-driven-development` (recommended) or `executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 UDP Data Plane 的硬编码端点迁入服务端配置，发布可恢复的 TCP/UDP 可达端点，并通过隔离的 Nginx 三场景跨进程 smoke 验证六个生产 failover marker。

**Architecture:** 服务端配置将区分本机 UDP `bindHost:bindPort` 与仅对客户端下发的 reachable `host:port`；TCP 仍由 `server.properties` 建立唯一 listener，仅把 cold reconnect 候选写入 S2C tail。握手保留旧的每 listener 一条 legacy UDP projection，并在末尾 append-only 增加按 `endpointId` 分组的 reachable candidates；新客户端每 listener 只保留一个 KCP session。Nginx 仅在开发 smoke 中代理 TCP 以真实注入主控断链，不进入生产数据路径。

**Tech Stack:** Java 17、Netty `ByteBuf`、KCP-Netty、NightConfig TOML、ForgeConfigSpec / NeoForge ModConfigSpec、Fabric 1.20.1、NeoForge 多版本预处理、PowerShell、Nginx stream。

## Global Constraints

- `common` MUST NOT import Fabric、Forge、NeoForge 或 Minecraft API；`UdpDataPlaneHandshakeTail` 只能依赖 Netty `ByteBuf` 与 JDK。
- TCP Primary 是唯一 master；一次恢复只能建立一条 vanilla Play Connection。`tryRouteBulk(UUID, int, byte[])` 的 `true=已被 Data 消费或丢弃 / false=Primary 发送` 契约不变。
- `bindHost` 仅用于本机 UDP bind，绝不能进入 S2C tail；`0.0.0.0` 与 `::` 绝不能作为 reachable host。
- `network.dataPlane.enabled=false` 时不得绑定 UDP、不得下发 UDP tail、客户端不得创建 UDP socket，所有 bulk 必须回落 Primary；control reachable candidates 仍可下发。
- 一个 UDP listener 对应一个稳定 `endpointId`、一个 WRR `weight` 和至多一个活跃客户端 KCP session；同 listener 的多个 reachable endpoint 只是顺序 bind candidates，不能被当作多个 WRR lane。
- 不 bump `UdpDataPlaneHandshakeTail.PROTOCOL_VERSION`；新 group 段必须 append-only，旧客户端可忽略而继续使用 legacy projection。
- `ReliableDatagramSession` 是唯一直接调用 KCP vendor API 的类；保持既有 `ByteBuf` 所有权与 release 规则。
- Failover marker 必须由生产路径产生。smoke 只能制造断链、发真实认证帧、或布置不可连接候选，不能直接设置 recovery state 或伪造 marker。
- Fabric 优先落地；NeoForge 必须使用同一运行时 `HassiumConfig.ServerNetworkConfig` schema 并完成 1.20.5、1.21.11 编译锚点；Forge 保持既有支持范围且不接入新的 failover smoke。
- 常规构建统一使用：`cmd /c "gradlew.bat --no-daemon <task> -Pmc_ver=<version> --console=plain"`；每轮 Java/Gradle 验证后执行 `taskkill /F /IM java.exe`。

## Current-State Facts

- `DataPlanePoCConfig.ENDPOINTS` 仍硬编码 `127.0.0.1:25566/25567` 广告地址与 `0.0.0.0` bind 地址；`DataPlaneUdpServer.bind()` 直接读取它。
- `DataPlaneUdpServer.BoundEndpoint(String host, int bindPort, int endpointId, int weight)` 的 `host` 已不再是可靠语义，必须替换为 reachable candidates。
- `UdpDataPlaneHandshakeTail.S2CTail` 当前只包含 `controlEndpoints` 与唯一 endpointId 的 `udpEndpoints`；decoder 已拒绝重复 `endpointId`。
- `FabricNetworkManager` 当前从 S2C tail 解码后无条件调用 `ControlReconnectOrchestrator.onHandshakeAccepted()`；首次进服因此错误记录 `FAILOVER_RECONNECT_OK epoch=0`。
- 所有六个 marker 已存在于 production code；当前 runtime smoke 没有驱动 permit/terminal，且客户端可能在 `WAIT_JOIN_1` 卡住，不能将现有单场景结果当作验收。
- `HassiumConfigSpec` 是 Forge/NeoForge 的 ConfigSpec adapter，不是独立 TomlConfigIO；Fabric 用 `FabricTomlConfigIO`。两端必须共用不可变配置 records 和相同 validation，不可复制业务默认值。

## File Structure and Responsibility Map

| Path | Responsibility |
|---|---|
| `common/.../config/HassiumConfig.java` | 不可变 `ReachableEndpoint`、`UdpListenerConfig`、`DataPlaneConfig` records；扩展 `ServerNetworkConfig`。 |
| `common/.../config/DataPlaneEndpointConfig.java` (new) | 无 loader 依赖的 endpoint 验证、去重、排序与 ConfigSpec compact-value 解析；Fabric/NeoForge 共享。 |
| `common/.../config/HassiumConfigService.java` | 暴露一次读取的 endpoint/data-plane config snapshot，避免热路径重新解析。 |
| `common/.../config/FabricTomlConfigIO.java` | 读取/写入 `[[network.controlReachableEndpoints]]`、`[network.dataPlane]`、`[[network.dataPlane.udpListeners]]` 和 nested reachable lists。 |
| `common/.../config/HassiumConfigSpec.java` | 将 Forge/NeoForge 的 scalar/list config 映射至同一 records；不得让 loader 配置格式泄漏进入网络层。 |
| `common/.../network/dataplane/DataPlaneUdpServer.java` | 用 config listeners bind；发布每 listener 的 reachable group；保留 test injection seam。 |
| `common/.../network/dataplane/ControlFailoverHandler.java` | 从 immutable config 取得 stall/permit TTL。 |
| `common/.../network/dataplane/ControlReconnectOrchestrator.java` | 从 immutable config 取得 recovery window，并仅在实际 recovery 成功时记录 reconnect marker。 |
| `common/.../network/dataplane/UdpDataPlaneHandshakeTail.java` | append-only `UdpListenerGroup` codec 与严格长度/重复/host 校验。 |
| `common/.../network/dataplane/DataPlaneClientLifecycle.java` / `DataPlaneClientBundle.java` | 每个 endpointId 依 priority 串行尝试 reachable candidates；每 listener 至多一个 bound session。 |
| `fabric/.../network/FabricNetworkManager.java` | 从服务端 snapshot 构造 S2C control list、legacy UDP projection 与 groups；恢复态 gate `onHandshakeAccepted()`。 |
| `neoforge/.../network/NeoForgeNetworkManager.java` | 以版本段安全方式镜像 Fabric handshake publish/consume 接线。 |
| `scripts/runtime-smoke-test.ps1` | 生成隔离 Nginx stream 配置，依次运行 recovery、permit、terminal、disabled 四个子场景并汇总 marker。 |
| `common/src/test/.../config/*` (new) | config validation、canonicalization、Fabric TOML round-trip、ConfigSpec compact mapping。 |
| `common/src/test/.../network/dataplane/*` | endpoint group codec、server bind projection、candidate fallback、recovery marker gate。 |

---

### Task 1: 建立统一 endpoint 值对象、canonicalization 与 validation

**Files:**
- Create: `common/src/main/java/io/github/limuqy/mc/hassium/config/DataPlaneEndpointConfig.java`
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/config/HassiumConfig.java:124-188`
- Create: `common/src/test/java/io/github/limuqy/mc/hassium/config/DataPlaneEndpointConfigTest.java`

**Interfaces:**
- Consumes: 无 loader API；`HassiumConfig.ServerNetworkConfig.DEFAULT` 是所有默认值唯一来源。
- Produces:

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

public static List<ReachableEndpoint> normalizeReachableEndpoints(
        List<ReachableEndpoint> endpoints, int maxEntries, String fieldName);
public static List<UdpListenerConfig> normalizeUdpListeners(
        boolean enabled, List<UdpListenerConfig> listeners);
```

- [ ] **Step 1: 写 endpoint validation 的失败测试**

```java
class DataPlaneEndpointConfigTest {
    @Test
    void rejectsWildcardAndInvalidReachableHosts() {
        assertThrows(IllegalArgumentException.class,
                () -> new HassiumConfig.ReachableEndpoint("0.0.0.0", 25565, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new HassiumConfig.ReachableEndpoint("::", 25565, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new HassiumConfig.ReachableEndpoint("", 25565, 1));
    }

    @Test
    void keepsHighestPriorityDuplicateAndSortsDescending() {
        var normalized = DataPlaneEndpointConfig.normalizeReachableEndpoints(List.of(
                new HassiumConfig.ReachableEndpoint("b.example", 25565, 10),
                new HassiumConfig.ReachableEndpoint("a.example", 25565, 30),
                new HassiumConfig.ReachableEndpoint("b.example", 25565, 40)), 8, "test");

        assertEquals(List.of(
                new HassiumConfig.ReachableEndpoint("b.example", 25565, 40),
                new HassiumConfig.ReachableEndpoint("a.example", 25565, 30)), normalized);
    }

    @Test
    void enabledDataPlaneRequiresUniqueBoundListenersWithReachableCandidates() {
        var endpoint = new HassiumConfig.ReachableEndpoint("play.example", 25565, 1);
        var listener = new HassiumConfig.UdpListenerConfig("0.0.0.0", 25565, 50, List.of(endpoint));

        assertThrows(IllegalArgumentException.class,
                () -> DataPlaneEndpointConfig.normalizeUdpListeners(true, List.of(listener, listener)));
        assertThrows(IllegalArgumentException.class,
                () -> DataPlaneEndpointConfig.normalizeUdpListeners(true, List.of(
                        new HassiumConfig.UdpListenerConfig("0.0.0.0", 25565, 50, List.of()))));
    }
}
```

- [ ] **Step 2: 运行测试，确认尚不存在类型/校验逻辑**

Run:

```bat
cmd /c "gradlew.bat --no-daemon common:test --tests io.github.limuqy.mc.hassium.config.DataPlaneEndpointConfigTest -Pmc_ver=1.20.1 --console=plain"
```

Expected: FAIL，缺少 `ReachableEndpoint`、`UdpListenerConfig`、`DataPlaneConfig` 或 `DataPlaneEndpointConfig`。

- [ ] **Step 3: 在 `HassiumConfig` 定义 immutable records 与 production defaults**

在 `ServerNetworkConfig` 前定义三个 nested records。每个构造器必须调用 `DataPlaneEndpointConfig`，使手写 config、Fabric TOML、ConfigSpec 生成的 records 使用同一规则；列表一律 `List.copyOf`。

```java
public record ReachableEndpoint(String host, int port, int priority) {
    public ReachableEndpoint {
        host = DataPlaneEndpointConfig.validateReachableHost(host, "reachable endpoint");
        DataPlaneEndpointConfig.validatePort(port, "reachable endpoint");
        DataPlaneEndpointConfig.validateNonNegative(priority, "reachable endpoint priority");
    }
}

public record UdpListenerConfig(String bindHost, int bindPort, int weight,
                                List<ReachableEndpoint> reachableEndpoints) {
    public UdpListenerConfig {
        bindHost = DataPlaneEndpointConfig.validateBindHost(bindHost);
        DataPlaneEndpointConfig.validatePort(bindPort, "UDP bind port");
        DataPlaneEndpointConfig.validateNonNegative(weight, "UDP listener weight");
        reachableEndpoints = DataPlaneEndpointConfig.normalizeReachableEndpoints(
                reachableEndpoints, 8, "UDP listener reachableEndpoints");
    }
}

public record DataPlaneConfig(boolean enabled, List<UdpListenerConfig> udpListeners,
                              long controlStallMs, long failoverExpiryMs, long recoveryWindowMs) {
    public DataPlaneConfig {
        udpListeners = DataPlaneEndpointConfig.normalizeUdpListeners(enabled, udpListeners);
        DataPlaneEndpointConfig.validatePositive(controlStallMs, "controlStallMs");
        DataPlaneEndpointConfig.validatePositive(failoverExpiryMs, "failoverExpiryMs");
        DataPlaneEndpointConfig.validatePositive(recoveryWindowMs, "recoveryWindowMs");
    }
}
```

扩展 `ServerNetworkConfig` 的尾部字段，避免插入既有 19 个参数之间：

```java
boolean lightStrip,
List<ReachableEndpoint> controlReachableEndpoints,
DataPlaneConfig dataPlane
```

默认值采用一个 listener，UDP bind `0.0.0.0:25565`，开发可达 `127.0.0.1:25565`，weight `100`；control list 空；`controlStallMs=6000`、`failoverExpiryMs=30000`、`recoveryWindowMs=60000`。在 record 注释明确 `127.0.0.1` 仅是开发默认而非公网部署建议。

- [ ] **Step 4: 实现 `DataPlaneEndpointConfig` 的 validation/canonicalization**

实现如下不变量：

```java
private static final Set<String> WILDCARD_HOSTS = Set.of("0.0.0.0", "::", "[::]");
private static final int MAX_HOST_UTF8_BYTES = 255;

// normalizeReachableEndpoints:
// 1. 逐条重新构造 ReachableEndpoint，以确保所有 validation 生效。
// 2. 按 host + port key 去重；保留 priority 更高者；同 priority 保留首次出现者。
// 3. priority 降序，再 host 升序、port 升序，返回 List.copyOf。
// 4. 条数超过 maxEntries 抛 IllegalArgumentException，不允许静默截断。

// normalizeUdpListeners:
// enabled=false 时允许空 listener list；enabled=true 时必须至少一个。
// 对每个 listener 的 bindHost + bindPort 建 LinkedHashSet key；重复即抛异常。
```

`validateReachableHost` 必须拒绝 wildcard、空白、UTF-8 大于 255 bytes；但不做 DNS 解析，也不因 bind port 与 reachable port 不同拒绝配置。`validateBindHost` 允许 wildcard，因为它只用于本地 socket bind。

- [ ] **Step 5: 运行定向测试并编译 common**

Run:

```bat
cmd /c "gradlew.bat --no-daemon common:test --tests io.github.limuqy.mc.hassium.config.DataPlaneEndpointConfigTest -Pmc_ver=1.20.1 --console=plain"
cmd /c "gradlew.bat --no-daemon common:compileJava -Pmc_ver=1.20.1 --console=plain"
taskkill /F /IM java.exe
```

Expected: 新测试 PASS，`common:compileJava` BUILD SUCCESSFUL。

- [ ] **Step 6: 提交独立值对象与校验**

```bat
git add common/src/main/java/io/github/limuqy/mc/hassium/config/HassiumConfig.java common/src/main/java/io/github/limuqy/mc/hassium/config/DataPlaneEndpointConfig.java common/src/test/java/io/github/limuqy/mc/hassium/config/DataPlaneEndpointConfigTest.java
git commit -m "feat(config): add unified dataplane endpoint model"
```

---


### Task 2: 为 Fabric TOML 与 Forge/NeoForge ConfigSpec 持久化同一配置模型

**Files:**
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/config/FabricTomlConfigIO.java:264-311,351-411`
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/config/HassiumConfigSpec.java:55-123,510-599`
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/config/HassiumConfigService.java`
- Create: `common/src/test/java/io/github/limuqy/mc/hassium/config/FabricTomlDataPlaneConfigTest.java`
- Create: `common/src/test/java/io/github/limuqy/mc/hassium/config/DataPlaneConfigSpecCodecTest.java`

**Interfaces:**
- Consumes: Task 1 的 immutable records 与 `DataPlaneEndpointConfig` validation。
- Produces:

```java
// HassiumConfigService — 仅返回 immutable records，不重新解析配置。
public HassiumConfig.DataPlaneConfig getDataPlaneConfig();
public List<HassiumConfig.ReachableEndpoint> getControlReachableEndpoints();

// DataPlaneEndpointConfig — ConfigSpec 独有的紧凑 list codec。
public static String encodeListener(HassiumConfig.UdpListenerConfig listener);
public static HassiumConfig.UdpListenerConfig decodeListener(String encoded);
public static String encodeReachable(HassiumConfig.ReachableEndpoint endpoint);
public static HassiumConfig.ReachableEndpoint decodeReachable(String encoded);
```

- [ ] **Step 1: 写 Fabric TOML round-trip 的失败测试**

测试必须在临时目录运行，不能读取或覆盖开发目录 `config/hassium`。在测试 setup 中为 `FabricTomlConfigIO` 提供 package-private `loadServer(Path configRoot)` / `saveServer(Path configRoot, HassiumConfig config)` seam；production `load()` / `save()` 继续通过 `Services.PLATFORM` 计算根目录。

```java
@Test
void serverTomlRoundTripsControlAndGroupedUdpEndpoints(@TempDir Path root) {
    var dataPlane = new HassiumConfig.DataPlaneConfig(true, List.of(
            new HassiumConfig.UdpListenerConfig("0.0.0.0", 31001, 60, List.of(
                    new HassiumConfig.ReachableEndpoint("edge-a.example", 41001, 100),
                    new HassiumConfig.ReachableEndpoint("edge-b.example", 42001, 80))),
            new HassiumConfig.UdpListenerConfig("10.0.0.10", 31002, 40, List.of(
                    new HassiumConfig.ReachableEndpoint("edge-a.example", 43001, 100)))),
            6_000L, 30_000L, 60_000L);
    var network = replaceDataPlane(HassiumConfig.ServerNetworkConfig.DEFAULT,
            List.of(new HassiumConfig.ReachableEndpoint("play.example", 25565, 100),
                    new HassiumConfig.ReachableEndpoint("backup.example", 25565, 80)), dataPlane);

    FabricTomlConfigIO.saveServer(root, configWithServerNetwork(network));
    var loaded = FabricTomlConfigIO.loadServer(root).serverNetwork();

    assertEquals(network.controlReachableEndpoints(), loaded.controlReachableEndpoints());
    assertEquals(network.dataPlane(), loaded.dataPlane());
    String toml = Files.readString(root.resolve("hassium/hassium-server.toml"));
    assertTrue(toml.contains("[[network.controlReachableEndpoints]]"));
    assertTrue(toml.contains("[[network.dataPlane.udpListeners]]"));
    assertTrue(toml.contains("[[network.dataPlane.udpListeners.reachableEndpoints]]"));
}

@Test
void invalidTomlEntryFallsBackOnlyThatFieldAndKeepsPrimaryConfiguration(@TempDir Path root) {
    Files.createDirectories(root.resolve("hassium"));
    Files.writeString(root.resolve("hassium/hassium-server.toml"), """
            [network]
            enabled = true
            [[network.controlReachableEndpoints]]
            host = "0.0.0.0"
            port = 25565
            priority = 100
            [network.dataPlane]
            enabled = true
            controlStallMs = -1
            """);

    var loaded = FabricTomlConfigIO.loadServer(root).serverNetwork();

    assertTrue(loaded.enabled());
    assertEquals(List.of(), loaded.controlReachableEndpoints());
    assertEquals(HassiumConfig.ServerNetworkConfig.DEFAULT.dataPlane().controlStallMs(),
            loaded.dataPlane().controlStallMs());
}
```

- [ ] **Step 2: 写 ConfigSpec compact codec 的失败测试**

Forge/NeoForge `ForgeConfigSpec` / `ModConfigSpec` 不表示 nested `[[array.of.tables]]`。它们必须保留与 Fabric 相同的 logical schema，但物理写入使用单一、可逆、无歧义的字符串列表：reachable 为 `host,port,priority`；listener 为 `bindHost,bindPort,weight;reachableHost,reachablePort,priority;...`。host 不能包含 `,` 或 `;`，IPv6 的 `:` 不受影响。格式是 loader adapter detail，禁止进入 handshake/network 类。

```java
@Test
void compactListenerCodecPreservesIpv6AndMultipleReachableCandidates() {
    var listener = new HassiumConfig.UdpListenerConfig("::", 25565, 50, List.of(
            new HassiumConfig.ReachableEndpoint("[2001:db8::1]", 41001, 100),
            new HassiumConfig.ReachableEndpoint("edge.example", 42001, 80)));

    assertEquals(listener, DataPlaneEndpointConfig.decodeListener(
            DataPlaneEndpointConfig.encodeListener(listener)));
}

@ParameterizedTest
@ValueSource(strings = {
        "host,not-a-port,1",
        "host,65536,1",
        "0.0.0.0,25565,1",
        "bind,25565,50;edge,25565,-1",
        "bind,25565,50;edge,25565,1;edge,25565,2"})
void compactCodecRejectsMalformedOrDuplicateValues(String raw) {
    assertThrows(IllegalArgumentException.class, () -> DataPlaneEndpointConfig.decodeListener(raw));
}
```

- [ ] **Step 3: 完成 Fabric TOML 读写与单字段回退**

在 `readServerNetwork` 的既有参数之后读取：

```java
List<ReachableEndpoint> control = readReachableList(
        cfg, "network.controlReachableEndpoints", "network.controlReachableEndpoints");
DataPlaneConfig dataPlane = readDataPlane(cfg, d.dataPlane());
```

`readReachableList` 必须把每一个 `CommentedConfig` 映射为 `host`、`port`、`priority`，调用 Task 1 records；坏条目记录一次带 field path 的 warning，丢弃该条目而不是废弃整张 `network` 表。`readDataPlane` 对 `enabled`、三个时长和 listeners 分别读取；任一 scalar 无效时使用该 scalar 的 default，listener 坏条目被丢弃。若 `enabled=true` 且过滤后 listener 为空，则回退完整 `d.dataPlane()`，并记录一次 warning。

`writeServerNetwork` 以现有标量写入模式追加：

```java
set(cfg, "network.dataPlane.enabled", n.dataPlane().enabled(), "是否启用 UDP/KCP Data Plane");
set(cfg, "network.dataPlane.controlStallMs", n.dataPlane().controlStallMs(), "控制 TCP 静默多久后允许申请 failover（ms）");
set(cfg, "network.dataPlane.failoverExpiryMs", n.dataPlane().failoverExpiryMs(), "服务端 failover permit 有效期（ms）");
set(cfg, "network.dataPlane.recoveryWindowMs", n.dataPlane().recoveryWindowMs(), "客户端候选重连窗口（ms）");
```

列表写入前必须 `cfg.remove("network.controlReachableEndpoints")` 与 `cfg.remove("network.dataPlane.udpListeners")`，再用 `List<CommentedConfig>` 生成 table arrays，确保旧 listener 不会遗留。每项写注释：bind 不会下发、reachable 才会下发、NAT/LB 映射由运维保证。

- [ ] **Step 4: 完成 ConfigSpec adapter，不复制 validation/defaults**

在 `HassiumConfigSpec.Server` 的 `network` section 新增以下 fields；所有默认值从 `HassiumConfig.ServerNetworkConfig.DEFAULT.dataPlane()` / `DEFAULT.controlReachableEndpoints()` 计算，禁止再次写 `6000`、`30000`、`60000` 的裸常量：

```java
private final ConfigValue<Boolean> networkDataPlaneEnabled;
private final ConfigValue<List<? extends String>> networkControlReachableEndpoints;
private final ConfigValue<List<? extends String>> networkDataPlaneUdpListeners;
private final ConfigValue<Long> networkDataPlaneControlStallMs;
private final ConfigValue<Long> networkDataPlaneFailoverExpiryMs;
private final ConfigValue<Long> networkDataPlaneRecoveryWindowMs;
```

`toHassiumConfig()` 必须 decode list entries。单条坏 entry 记录 warning 后丢弃；enabled data plane 若最终无 listener，整个 `DataPlaneConfig` 回退 `DEFAULT.dataPlane()`。将 decoded `controlReachableEndpoints` 与 `dataPlane` 追加至 `ServerNetworkConfig` 构造器尾部。

`applyFrom(HassiumConfig config)` 必须调用 `encodeReachable` / `encodeListener` 写回 list，保证 Fabric 与 NeoForge/Forge 的 runtime snapshot 结构完全相同。ConfigSpec 仅负责 adapter 格式，endpoint 验证始终由 Task 1 的 constructors 执行。

- [ ] **Step 5: 增加 `HassiumConfigService` snapshot accessors**

```java
public HassiumConfig.DataPlaneConfig getDataPlaneConfig() {
    return config.serverNetwork().dataPlane();
}

public List<HassiumConfig.ReachableEndpoint> getControlReachableEndpoints() {
    return config.serverNetwork().controlReachableEndpoints();
}
```

使用 volatile `config` 的一次 field read；不要在网络热路径调用 `reload()`、TOML reader 或 ConfigSpec。调用方持有的 records/list 已 immutable。

- [ ] **Step 6: 运行配置定向测试、common 编译与 Fabric 编译**

Run:

```bat
cmd /c "gradlew.bat --no-daemon common:test --tests io.github.limuqy.mc.hassium.config.DataPlaneEndpointConfigTest --tests io.github.limuqy.mc.hassium.config.FabricTomlDataPlaneConfigTest --tests io.github.limuqy.mc.hassium.config.DataPlaneConfigSpecCodecTest -Pmc_ver=1.20.1 --console=plain"
cmd /c "gradlew.bat --no-daemon common:compileJava fabric:compileJava -Pmc_ver=1.20.1 --console=plain"
taskkill /F /IM java.exe
```

Expected: 三个定向测试 PASS，两个 compile task BUILD SUCCESSFUL。

- [ ] **Step 7: 提交配置 adapter 完整改动**

```bat
git add common/src/main/java/io/github/limuqy/mc/hassium/config/FabricTomlConfigIO.java common/src/main/java/io/github/limuqy/mc/hassium/config/HassiumConfigSpec.java common/src/main/java/io/github/limuqy/mc/hassium/config/HassiumConfigService.java common/src/test/java/io/github/limuqy/mc/hassium/config/FabricTomlDataPlaneConfigTest.java common/src/test/java/io/github/limuqy/mc/hassium/config/DataPlaneConfigSpecCodecTest.java
git commit -m "feat(config): persist dataplane endpoint configuration"
```

---

### Task 3: 将 UDP bind 和 endpoint 发布迁移至 immutable 配置 snapshot

**Files:**
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneUdpServer.java:60-110,145-201,343-394`
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlanePoCConfig.java:66-98`
- Modify: `common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneUdpServerTest.java`
- Modify: `common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneEnabledGuardTest.java`

**Interfaces:**
- Consumes: `HassiumConfigService.getDataPlaneConfig()`；Task 1 的 `UdpListenerConfig` / `ReachableEndpoint`。
- Produces:

```java
public record BoundEndpoint(
        int endpointId,
        int weight,
        int boundPort,
        List<HassiumConfig.ReachableEndpoint> reachableEndpoints) {}

public static List<BoundEndpoint> boundEndpoints();
```

- [ ] **Step 1: 写服务端 bind projection 的失败测试**

```java
@Test
void boundEndpointPublishesReachableCandidatesButNeverBindHost() {
    var listener = new HassiumConfig.UdpListenerConfig("0.0.0.0", 0, 50, List.of(
            new HassiumConfig.ReachableEndpoint("edge-a.example", 41001, 100),
            new HassiumConfig.ReachableEndpoint("edge-b.example", 42001, 80)));
    DataPlaneUdpServer.forTest(List.of(listener));

    DataPlaneUdpServer.bind();
    var bound = assertDoesNotThrow(DataPlaneUdpServer::boundEndpoints);

    assertEquals(1, bound.size());
    assertEquals(0, bound.get(0).endpointId());
    assertEquals(50, bound.get(0).weight());
    assertTrue(bound.get(0).boundPort() > 0);
    assertEquals(listener.reachableEndpoints(), bound.get(0).reachableEndpoints());
    assertFalse(bound.get(0).reachableEndpoints().stream()
            .map(HassiumConfig.ReachableEndpoint::host).anyMatch("0.0.0.0"::equals));
}

@Test
void disabledConfiguredDataPlaneNeverBindsAndBulkFallsBackPrimary() {
    installServerConfig(dataPlane(false, List.of()));
    DataPlaneUdpServer.bind();

    assertFalse(DataPlaneUdpServer.isBound());
    assertTrue(DataPlaneUdpServer.boundEndpoints().isEmpty());
    assertFalse(DataPlaneUdpServer.tryRouteBulk(UUID.randomUUID(), 3, new byte[] {1}));
}
```

- [ ] **Step 2: 运行测试，确认当前 `Endpoint[]` API 无法满足 group projection**

Run:

```bat
cmd /c "gradlew.bat --no-daemon common:test --tests io.github.limuqy.mc.hassium.network.dataplane.DataPlaneUdpServerTest --tests io.github.limuqy.mc.hassium.network.dataplane.DataPlaneEnabledGuardTest -Pmc_ver=1.20.1 --console=plain"
```

Expected: FAIL，`forTest(List<UdpListenerConfig>)`、新的 `BoundEndpoint` fields 或 config-driven disabled guard 尚不存在。

- [ ] **Step 3: 使 production bind 仅读取 `DataPlaneConfig`，保留显式测试 seam**

将 `Instance.configured` 的类型改为 `List<HassiumConfig.UdpListenerConfig>`。production `bind()` 必须按如下分支构造 listener list：

```java
List<UdpListenerConfig> listeners = testListeners != null
        ? testListeners
        : HassiumConfigService.getInstance().getDataPlaneConfig().udpListeners();
boolean enabled = testListeners != null
        || HassiumConfigService.getInstance().getDataPlaneConfig().enabled();
if (!enabled) {
    LOGGER.info("DataPlaneUdpServer: disabled by network.dataPlane.enabled");
    return;
}
```

`forTest` 只接受 immutable `List<UdpListenerConfig>`，在 `shutdown()` 清空测试注入；不要让测试覆盖 production singleton config。每个 listener 依序分配 `endpointId`，`NioDatagramChannel.bind(listener.bindHost(), listener.bindPort())`，并以实际 bound port 构造新的 `BoundEndpoint`。只把 `listener.reachableEndpoints()` 放入发布对象，绝不派生或传播 `bindHost`。

`router()` 不再读取 `DataPlanePoCConfig.ENDPOINTS`；其 `hardRttMs` 仍维持 1000ms，删除无意义的 endpoint array 分支。`tryRouteBulk` 在 INSTANCE 为 null 时返回 false，确保 disabled 行为由既有 Primary callsite 处理。

- [ ] **Step 4: 将 `DataPlanePoCConfig` 收缩为测试/静态调试常量**

删除 production `ENDPOINTS`、`endpointsSummary()`、`Endpoint` 以及 `setEnabled()`；`isEnabled()` 应改为只返回 `HassiumConfigService.getInstance().getDataPlaneConfig().enabled()`，或被所有 production callsites 直接替换为该 accessor。保留的常量仅限协议独立且尚未配置化的 `BIND_TOKEN`、`FRAME_KEY_INFO_TAG`、keepalive/read timeout、route mode 和 debug logging gate。测试需要动态配置时，使用 Task 3 的 `forTest` 和 config service test fixture，不再通过静态全局 endpoint 数组。

- [ ] **Step 5: 运行定向测试、common/Fabric 编译与 transport cutover 回归**

Run:

```bat
cmd /c "gradlew.bat --no-daemon common:test --tests io.github.limuqy.mc.hassium.network.dataplane.DataPlaneUdpServerTest --tests io.github.limuqy.mc.hassium.network.dataplane.DataPlaneEnabledGuardTest --tests io.github.limuqy.mc.hassium.network.dataplane.DataPlaneTransportCutoverTest -Pmc_ver=1.20.1 --console=plain"
cmd /c "gradlew.bat --no-daemon common:compileJava fabric:compileJava -Pmc_ver=1.20.1 --console=plain"
taskkill /F /IM java.exe
```

Expected: 定向测试 PASS；transport 测试仍只观察到 `NioDatagramChannel`；编译成功。

- [ ] **Step 6: 提交 UDP bind 迁移**

```bat
git add common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneUdpServer.java common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlanePoCConfig.java common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneUdpServerTest.java common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneEnabledGuardTest.java
git commit -m "feat(dataplane): bind configured UDP listener groups"
```

---

### Task 4: 用统一配置驱动 permit、recovery deadline 与 control candidates

**Files:**
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/ControlFailoverHandler.java:73-76,179-205`
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/ControlReconnectOrchestrator.java:35-37,76-117,166-175`
- Modify: `common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/ControlFailoverHandlerTest.java`
- Modify: `common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/ControlReconnectOrchestratorTest.java`

**Interfaces:**
- Consumes: `HassiumConfig.DataPlaneConfig.controlStallMs()`、`failoverExpiryMs()`、`recoveryWindowMs()`。
- Produces: `ControlFailoverHandler` 和 `ControlReconnectOrchestrator` 每次开始一个新 server/client session 时读取同一 immutable config 值；测试可用构造参数注入，不能修改 static production defaults。

- [ ] **Step 1: 写 config duration 的失败测试**

```java
@Test
void permitUsesConfiguredStallAndExpiry() {
    var handler = ControlFailoverHandler.forTest(new DataPlaneConfig(true, LISTENERS,
            50L, 700L, 900L));
    UUID player = UUID.randomUUID();
    handler.registerControlConnection(player, 9L, () -> {});
    handler.onUdpSessionEstablished(player, 9L);
    handler.recordControlActivity(player, 9L, 1_000L);

    assertEquals(REJECTED_ACTIVE, handler.requestFailover(player, 9L, 0, 1_049L));
    assertEquals(PERMITTED, handler.requestFailover(player, 9L, 0, 1_050L));
    assertEquals(700L, handler.failoverPermitTtlMs());
}

@Test
void reconnectUsesConfiguredRecoveryWindow() {
    var launcher = new RecordingLauncher();
    var config = new DataPlaneConfig(true, LISTENERS, 6_000L, 30_000L, 1234L);
    var orchestrator = ControlReconnectOrchestrator.forTest(launcher, List.of(BACKUP), config);

    orchestrator.onPrimaryDisconnected(PRIMARY, "closed");

    assertEquals(1_234L, orchestrator.recoveryDeadlineMs() - orchestrator.recoveryStartedAtMs());
}
```

- [ ] **Step 2: 运行测试并确认旧的 6s/30s/60s defaults 不满足注入接口**

Run:

```bat
cmd /c "gradlew.bat --no-daemon common:test --tests io.github.limuqy.mc.hassium.network.dataplane.ControlFailoverHandlerTest --tests io.github.limuqy.mc.hassium.network.dataplane.ControlReconnectOrchestratorTest -Pmc_ver=1.20.1 --console=plain"
```

Expected: FAIL，缺少配置构造 seam 或仍使用硬编码窗口。

- [ ] **Step 3: 修改 handler/orchestrator 的 production 与 test construction**

`ControlFailoverHandler` 生产单例在 `beginControlConnection` 前读取当前 `HassiumConfigService.getInstance().getDataPlaneConfig()` 并为该 player/epoch snapshot `controlStallMs` 与 `failoverExpiryMs`，不能在同一恢复周期因 `/reload` 改变 permit 语义。`forTest(DataPlaneConfig)` 存储该 immutable config；保留已有测试 factory 但让它 delegate 至默认 config。

`ControlReconnectOrchestrator` 删除 `DEFAULT_RECOVERY_WINDOW_MS` 作为 production truth。构造器接受一个 `LongSupplier recoveryWindowMs`；Fabric client 使用 `() -> HassiumConfigService.getInstance().getDataPlaneConfig().recoveryWindowMs()`，`forTest` 传常量 supplier。`onPrimaryDisconnected` / `beginRecoveryForTest` 在进入恢复时只取一次 supplier 值、调用 `manager.startRecovery(windowMs)`，并保存测试可观察的 start/deadline 时间戳。

不可通过在 `onFailoverPermit` 接到 permit 后改变 recovery window；permit expiry 与 client recovery deadline 是不同语义。

- [ ] **Step 4: 用同一 snapshot 发布 control candidate 列表**

添加 server-side helper：

```java
public static List<UdpDataPlaneHandshakeTail.ControlEndpoint> advertisedControlEndpoints() {
    return HassiumConfigService.getInstance().getControlReachableEndpoints().stream()
            .map(e -> new ControlEndpoint(e.host(), e.port(), e.priority()))
            .toList();
}
```

该 helper 不加入当前 bootstrap remote address；bootstrap 由客户端 `ControlEndpointManager.mergeBootstrapAndAdvertised` 取得。空列表合法，表示只可依正常 initial vanilla address 连接。

- [ ] **Step 5: 运行定向测试并提交**

Run:

```bat
cmd /c "gradlew.bat --no-daemon common:test --tests io.github.limuqy.mc.hassium.network.dataplane.ControlFailoverHandlerTest --tests io.github.limuqy.mc.hassium.network.dataplane.ControlReconnectOrchestratorTest -Pmc_ver=1.20.1 --console=plain"
cmd /c "gradlew.bat --no-daemon common:compileJava -Pmc_ver=1.20.1 --console=plain"
taskkill /F /IM java.exe
git add common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/ControlFailoverHandler.java common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/ControlReconnectOrchestrator.java common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/ControlFailoverHandlerTest.java common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/ControlReconnectOrchestratorTest.java
git commit -m "feat(failover): read timing and candidates from config"
```

Expected: tests PASS、common 编译成功、提交仅包含 timing/candidate 配置接线。

---

## Phase 3：握手协议与加载器接线

### Task 5: 在 S2C tail append-only 编码 listener group

**Files:**
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/UdpDataPlaneHandshakeTail.java:19-32,60-201`
- Modify: `common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/UdpDataPlaneHandshakeTailTest.java`

**Interfaces:**
- Consumes: Task 3 的 `BoundEndpoint` 与 Task 1 的 reachable endpoint 语义。
- Produces:

```java
public record UdpListenerGroup(
        int endpointId, int weight, List<UdpReachableEndpoint> reachableEndpoints) {}
public record UdpReachableEndpoint(String host, int port, int priority) {}

public record S2CTail(
        boolean hasUdpDataplane, boolean hasControlFailover, long connectionEpoch,
        int protocol, byte[] token, List<ControlEndpoint> controlEndpoints,
        List<UdpEndpointInfo> udpEndpoints, List<UdpListenerGroup> udpListenerGroups) {}
```

`UdpEndpointInfo` 保留为旧 projection：每个 group 仅投影其 priority 最高 reachable candidate；新 client 只在 groups 非空时使用 groups。`S2CTail` 必须继续接受七参数构造调用（新增 overload 代理至 `List.of()`），避免同一提交里无关 callsite 大面积改动。

- [ ] **Step 1: 写 append-only codec 的失败测试**

```java
@Test
void roundTripsGroupsWhileKeepingLegacyProjection() {
    var tail = enabledTail(List.of(
            new UdpEndpointInfo("legacy-a.example", 41001, 60, 0),
            new UdpEndpointInfo("legacy-b.example", 43001, 40, 1)),
            List.of(
                new UdpListenerGroup(0, 60, List.of(
                    new UdpReachableEndpoint("edge-a.example", 41001, 100),
                    new UdpReachableEndpoint("edge-b.example", 42001, 80))),
                new UdpListenerGroup(1, 40, List.of(
                    new UdpReachableEndpoint("edge-c.example", 43001, 100)))));
    ByteBuf wire = Unpooled.buffer();

    UdpDataPlaneHandshakeTail.writeS2C(wire, tail);

    assertEquals(tail, UdpDataPlaneHandshakeTail.readS2C(wire));
}

@Test
void readsPreGroupPayloadAsEmptyGroups() {
    ByteBuf preGroupWire = encodeUsingExistingS2CLayout(enabledTail(LEGACY_ENDPOINTS));

    var decoded = UdpDataPlaneHandshakeTail.readS2C(preGroupWire);

    assertEquals(LEGACY_ENDPOINTS, decoded.udpEndpoints());
    assertTrue(decoded.udpListenerGroups().isEmpty());
}

@Test
void rejectsDuplicateGroupIdsAndWildcardReachableHost() {
    assertThrows(IllegalArgumentException.class, () -> new UdpListenerGroup(2, 1, List.of(
            new UdpReachableEndpoint("0.0.0.0", 25565, 1))));
    assertThrows(IllegalArgumentException.class, () -> enabledTail(LEGACY_ENDPOINTS, List.of(
            new UdpListenerGroup(2, 1, List.of(new UdpReachableEndpoint("a", 1, 1))),
            new UdpListenerGroup(2, 1, List.of(new UdpReachableEndpoint("b", 1, 1))))));
}
```

- [ ] **Step 2: 运行定向测试，确认 group segment 尚未编码**

Run:

```bat
cmd /c "gradlew.bat --no-daemon common:test --tests io.github.limuqy.mc.hassium.network.dataplane.UdpDataPlaneHandshakeTailTest -Pmc_ver=1.20.1 --console=plain"
```

Expected: FAIL，缺少 `UdpListenerGroup`/`UdpReachableEndpoint` 或 `udpListenerGroups()`。

- [ ] **Step 3: 实现严格的 group records 和 append-only wire segment**

在 legacy `udpCount` 循环后追加：

```text
udpGroupCount(varint ≤ 8)
  group: endpointId(varint) + weight(varint) + candidateCount(varint 1..8)
    candidate: host(varstr UTF-8 ≤255) + port(u16) + priority(varint)
```

`writeS2C` 必须先验证：group 数 ≤ 8；`endpointId` 在 group 内唯一且与 `udpEndpoints` 相同 endpointId 时 `weight` 一致；candidate host/port 不重复；每个 group 至少一条 candidate。若 groups 非空，legacy projection 必须为每 group 的首条 candidate，且二者的 endpointId/weight 一一匹配。由 helper `validateCrossProjection` 执行，拒绝不一致 input，而非偷偷修正。

`readS2C` 在 legacy UDP section 解完后使用 `if (!in.isReadable())` 直接构造 groups 空的 tail，达成 pre-group 字节流兼容。若有一部分 group segment 但长度、varint、count、duplicate endpointId 或 candidate 校验不合法，必须抛 `IllegalArgumentException`，不可降级并继续一个半截 tail。

`UdpReachableEndpoint` 复用等价 wildcard/长度/port/priority 规则；该 common network codec 不依赖 config package。允许 DNS host 与 bracketed IPv6，不解析 DNS。

- [ ] **Step 4: 运行 codec 测试和 common 编译**

Run:

```bat
cmd /c "gradlew.bat --no-daemon common:test --tests io.github.limuqy.mc.hassium.network.dataplane.UdpDataPlaneHandshakeTailTest -Pmc_ver=1.20.1 --console=plain"
cmd /c "gradlew.bat --no-daemon common:compileJava -Pmc_ver=1.20.1 --console=plain"
taskkill /F /IM java.exe
```

Expected: PASS；未 bump `PROTOCOL_VERSION`。

- [ ] **Step 5: 提交 codec 演进**

```bat
git add common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/UdpDataPlaneHandshakeTail.java common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/UdpDataPlaneHandshakeTailTest.java
git commit -m "feat(dataplane): append grouped UDP endpoints to handshake"
```

---

### Task 6: 在 Fabric 与 NeoForge 以同一 snapshot 构造和消费 endpoint tail

**Files:**
- Modify: `fabric/src/main/java/io/github/limuqy/mc/hassium/network/FabricNetworkManager.java`（构造 S2C tail 的两个版本段和 299-426 客户端消费段）
- Modify: `neoforge/src/main/java/io/github/limuqy/mc/hassium/network/NeoForgeNetworkManager.java`（对应 server/client tail 接线）
- Modify: `common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/UdpDataPlaneHandshakeTailTest.java`
- Modify: 对应 Fabric/NeoForge handshake 测试（若现有测试类按 loader 分段存在则扩展；否则在 common 以纯 `S2CTail` factory 测试 projection）

**Interfaces:**
- Server input: `DataPlaneUdpServer.boundEndpoints()`、`ControlReconnectOrchestrator.advertisedControlEndpoints()`、client C2S capabilities。
- Server output: control candidates、legacy UDP projection、完整 UDP listener groups；只有 client 声明相应 capability 才设置对应 flag。
- Client input: `S2CTail`；client output: `ControlReconnectOrchestrator.configureCandidates` 与 `DataPlaneClientLifecycle.startUdp`。

- [ ] **Step 1: 写 Fabric 侧 projection factory 的失败测试**

将无 Minecraft 依赖的 projection 转入 package-private common helper（例如 `DataPlaneHandshakeAdvertisement`），使测试不需要 loader runtime：

```java
@Test
void advertisesControlEndpointsAndOneLegacyEndpointPerListener() {
    var bound = List.of(
        new BoundEndpoint(0, 60, 31001, List.of(
            reachable("edge-a.example", 41001, 100), reachable("edge-b.example", 42001, 80))),
        new BoundEndpoint(1, 40, 31002, List.of(reachable("edge-c.example", 43001, 100))));

    var advertisement = DataPlaneHandshakeAdvertisement.create(CONTROL, bound, TOKEN, 7L, true, true);

    assertEquals(CONTROL, advertisement.controlEndpoints());
    assertEquals(List.of(udp("edge-a.example", 41001, 60, 0), udp("edge-c.example", 43001, 40, 1)),
            advertisement.udpEndpoints());
    assertEquals(2, advertisement.udpListenerGroups().size());
}

@Test
void disabledUdpProducesNoUdpFlagOrTokenButKeepsControlCandidates() {
    var ad = DataPlaneHandshakeAdvertisement.create(CONTROL, List.of(), TOKEN, 7L, false, true);
    assertFalse(ad.hasUdpDataplane());
    assertTrue(ad.udpEndpoints().isEmpty());
    assertTrue(ad.udpListenerGroups().isEmpty());
    assertTrue(ad.hasControlFailover());
}
```

- [ ] **Step 2: 创建纯 common `DataPlaneHandshakeAdvertisement` helper**

从 `BoundEndpoint` 产生 deterministic output：groups 按 `endpointId` 升序，reachable candidates 已在 Task 1 canonicalize 后按 priority 降序；legacy endpoint 取 group 首 candidate。control candidates 同样调用 config 的 normalized list 后转换为 codec `ControlEndpoint`。

当 data plane disabled、UDP 未 bind、或 C2S `udpDataplaneSupported=false` 时：`hasUdpDataplane=false`、UDP lists empty、token 为 16 byte zero token。control flag 独立判断 `controlFailoverSupported` 和 server feature enabled；不能因为 UDP bind 失败而隐式关闭控制 failover。

- [ ] **Step 3: 在 Fabric 两个版本段接线**

服务端 handshake response 在既有原版字段之后 append `writeS2C`。当前 player 的 epoch 必须仍经 `DataPlaneUdpServer.beginControlConnection(playerId, masterClose)` 创建；调用 helper 构造 tail，来源只能是 current config snapshot 和当前 bound endpoints。

客户端消费同时覆盖 `<1.20.5` 与 `>=1.20.5` 分支：

```java
List<ControlEndpoint> candidates = tail.controlEndpoints().stream()
        .map(e -> new ControlEndpoint(e.host(), e.port(), e.priority())).toList();
orch.configureCandidates(candidates);
client.execute(() -> {
    if (tail.hasUdpDataplane() && Minecraft.getInstance().player != null) {
        DataPlaneClientLifecycle.getInstance().startUdp(playerId, tail.connectionEpoch(), tail);
    }
    if (ClientRecoveryState.getInstance().isRecovering()) {
        ClientRecoveryState.getInstance().markRecovered();
        orch.onHandshakeAccepted();
    }
});
```

后半段 marker gate 在 Task 8 完成；本任务只保证 groups/controls 均被传递，且 tail decode 失败不破坏原有 compression handshake。禁止吞没 `IllegalArgumentException` 后又把不完整 tail 当作 valid UDP configuration。

- [ ] **Step 4: 镜像 NeoForge 各版本段网络接线**

先定位 NeoForge 1.20.1、1.20.5+ 两类 handshake payload 接线点，按现有 `#if MC_VER` 风格插入相同 tail 构造/解码步骤；不从 Fabric import class。将 common helper 用于所有 loader，确保 tail bytes 一致。NeoForge client 调度必须使用其 payload context 的 main-thread executor，不能在 Netty callback 创建 UDP socket。

若 NeoForge 某版本段尚没有该 custom handshake，则本 task 的完成条件是补齐现有协议对应的 append/consume，而不是静默跳过；新增最小 structural compile guard 测试或可以编译的 common factory test，记录每个 `#if` 分支对应版本范围。

- [ ] **Step 5: 编译两个 Fabric 与两个 NeoForge 锚点**

Run:

```bat
cmd /c "gradlew.bat --no-daemon common:test --tests io.github.limuqy.mc.hassium.network.dataplane.UdpDataPlaneHandshakeTailTest -Pmc_ver=1.20.1 --console=plain"
cmd /c "gradlew.bat --no-daemon fabric:compileJava -Pmc_ver=1.20.1 --console=plain"
cmd /c "gradlew.bat --no-daemon fabric:compileJava -Pmc_ver=1.21.11 --console=plain"
cmd /c "gradlew.bat --no-daemon neoforge:compileJava -Pmc_ver=1.20.5 --console=plain"
cmd /c "gradlew.bat --no-daemon neoforge:compileJava -Pmc_ver=1.21.11 --console=plain"
taskkill /F /IM java.exe
```

Expected: 全部 BUILD SUCCESSFUL，且测试 PASS。

- [ ] **Step 6: 提交 handshake 接线**

```bat
git add common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneHandshakeAdvertisement.java common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/UdpDataPlaneHandshakeTail.java common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/UdpDataPlaneHandshakeTailTest.java fabric/src/main/java/io/github/limuqy/mc/hassium/network/FabricNetworkManager.java neoforge/src/main/java/io/github/limuqy/mc/hassium/network/NeoForgeNetworkManager.java
git commit -m "feat(handshake): advertise configured endpoint groups"
```

---

## Phase 4：客户端 listener 分组与候选回退

### Task 7: 客户端每个 listener 只建一个 session，并按候选顺序 bind

**Files:**
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneClientLifecycle.java:44-80,102-145`
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneClientBundle.java:31-47,147-251,290-370`
- Create: `common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneClientGroupSelectionTest.java`
- Modify: `common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneEnabledGuardTest.java`

**Interfaces:**
- Consumes: `S2CTail.udpListenerGroups()`；groups 空时兼容读取 `udpEndpoints()`。
- Produces: 一个 `endpointId` 一条 KCP session / 一条 UDP channel；候选按 priority 顺序串行发送 bind，只有明确 bind 成功后才成为可路由 session；所有候选失败时不抛至 handshake，保留 Primary。

- [ ] **Step 1: 把 bind 成功定义为有真实 ACK，而非仅 send BindRequest**

当前服务端对 `BindRequest` 创建 `ReliableDatagramSession`，但客户端把 `sessions.put()` 当作成功，没有 ACK 验证。这会把黑洞 reachable endpoint 错判为 bound，无法安全 fallback。先在 `UdpBindRequestCodec` / `ReliableDatagramSession` 检查现有密钥/keepalive 帧；增加最小 `TYPE_BIND_ACK`（或若已有等价认证 server reply，则复用它）并满足：

```text
server: token、epoch、endpointId 均验证后，创建 session，再回一个加密且绑定 endpointId 的 ACK。
client: 收到并验证 ACK 前，candidate 是 CONNECTING；超时/发送失败/非法回应 → 关闭该 candidate channel/session，尝试下一个。
```

该 ACK 是修复 “UDP connect/bind” 语义的协议实现，不是 smoke-only signal；必须有 replay/epoch/endpointId validation。若这里的协议版本受 `UdpBindRequestCodec.PROTOCOL_VERSION` 约束，按既有 codec 规则处理版本兼容；不要改 `UdpDataPlaneHandshakeTail.PROTOCOL_VERSION`。

- [ ] **Step 2: 写 group selection 的失败测试**

把 I/O 通过一个 package-private `CandidateBinder` seam 注入，使单元测试不需要 Netty socket、DNS 或等待真实时间：

```java
@Test
void oneSessionPerGroupUsesSecondReachableAfterFirstTimesOut() {
    var binder = new RecordingBinder(Map.of(
            key(0, "edge-a.example", 41001), TIMEOUT,
            key(0, "edge-b.example", 42001), BOUND,
            key(1, "edge-c.example", 43001), BOUND));
    var bundle = new DataPlaneClientBundle(binder, directDispatcher());

    bundle.connectAndBind(PLAYER, 7L, TOKEN, List.of(
            group(0, 60, endpoint("edge-a.example", 41001, 100), endpoint("edge-b.example", 42001, 80)),
            group(1, 40, endpoint("edge-c.example", 43001, 100))));

    assertEquals(List.of("0:edge-a.example:41001", "0:edge-b.example:42001", "1:edge-c.example:43001"),
            binder.attempts());
    assertEquals(Set.of(0, 1), bundle.boundEndpointIdsForTest());
    assertEquals("edge-b.example:42001", bundle.remoteForTest(0));
}

@Test
void allCandidatesFailLeavesGroupPrimaryOnlyWithoutLeakingChannels() {
    var binder = new RecordingBinder(Map.of(key(0, "bad.example", 41001), TIMEOUT));
    var bundle = new DataPlaneClientBundle(binder, directDispatcher());

    bundle.connectAndBind(PLAYER, 7L, TOKEN, List.of(group(0, 100, endpoint("bad.example", 41001, 1))));

    assertFalse(bundle.isBound());
    assertTrue(bundle.boundEndpointIdsForTest().isEmpty());
    assertEquals(0, bundle.openChannelCountForTest());
}

@Test
void oldTailFallsBackToOneGroupPerLegacyEndpoint() {
    var groups = DataPlaneClientLifecycle.groupsForTail(legacyTail(udp("a", 1, 60, 0), udp("b", 2, 40, 1)));
    assertEquals(List.of(group(0, 60, endpoint("a", 1, 0)), group(1, 40, endpoint("b", 2, 0))), groups);
}
```

- [ ] **Step 3: 运行测试，确认现有实现把每一个 legacy endpoint 立即标记 bound**

Run:

```bat
cmd /c "gradlew.bat --no-daemon common:test --tests io.github.limuqy.mc.hassium.network.dataplane.DataPlaneClientGroupSelectionTest -Pmc_ver=1.20.1 --console=plain"
```

Expected: FAIL，缺少 group API/ACK state/候选 binder seam。

- [ ] **Step 4: 将 bundle 输入和存储从 flat endpoints 改为 groups**

新增 `connectAndBind(UUID, long, byte[], List<UdpListenerGroup>)`，删除或降为 package-private legacy adapter 的 public flat API。`DataPlaneClientLifecycle.groupsForTail` 规则：

1. `tail.udpListenerGroups()` 非空时直接使用；
2. groups 为空、legacy `udpEndpoints()` 非空时按每 `UdpEndpointInfo` 构造只含一项的 synthetic group；
3. 两者都空则 Primary-only；
4. 同一 `endpointId` 不允许来自两种来源的重复 group。

map 仍以 `endpointId` 为 key。对每 group 仅执行一个 candidate chain：priority 已由 codec/config canonical；对候选逐个调用 binder，上一条返回 `TIMEOUT` / `REJECTED` / `IO_FAILURE` 后必须关闭其 channel/session再继续。成功时保存单个 `ReliableDatagramSession`、`Channel` 和 remote。禁止同时为同 group 多 candidate 开 socket，也禁止把 candidate 序号伪装成第二个 endpointId。

ACK deadline 设为 2 秒或 config 未来字段中的更短值，且用 event-loop scheduled task，不得阻塞 Minecraft main thread；连续多个 groups 可以并行 pending，但每个 group 内是严格顺序。所有 pending attempt 必须在 `shutdown()`/new epoch 时取消；late ACK 必须检查 epoch + group generation，过期则 release/close，不可复活旧 session。

- [ ] **Step 5: 修订可观察统计名称，避免把 endpointId 伪称为端口**

保留 `snapshotPerPort()` 作为兼容 wrapper，但新增 `snapshotPerEndpoint()`；`ClientSmokeTest` 的打印改为 `endpoint<id>=...`，不能继续输出 `port<endpointId>`。不改变 `NetworkStats` 统计口径，且不为每个 candidate 产生单独数据通道计数；一个 group 对应一个 endpointId 的计数。

- [ ] **Step 6: 运行定向测试和 common/Fabric 编译**

Run:

```bat
cmd /c "gradlew.bat --no-daemon common:test --tests io.github.limuqy.mc.hassium.network.dataplane.DataPlaneClientGroupSelectionTest --tests io.github.limuqy.mc.hassium.network.dataplane.DataPlaneEnabledGuardTest --tests io.github.limuqy.mc.hassium.network.dataplane.DataPlaneTransportCutoverTest -Pmc_ver=1.20.1 --console=plain"
cmd /c "gradlew.bat --no-daemon common:compileJava fabric:compileJava -Pmc_ver=1.20.1 --console=plain"
taskkill /F /IM java.exe
```

Expected: 三个测试 PASS；bundle 在无 ACK/disabled 情况下不被误标为 bound；两个 compile task 成功。

- [ ] **Step 7: 提交客户端 group/fallback 实现**

```bat
git add common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneClientLifecycle.java common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneClientBundle.java common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/UdpBindRequestCodec.java common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/ReliableDatagramSession.java common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneClientGroupSelectionTest.java common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneEnabledGuardTest.java common/src/main/java/io/github/limuqy/mc/hassium/client/ClientSmokeTest.java
git commit -m "feat(dataplane): bind one session per endpoint group"
```

---

## Phase 5：恢复 marker 状态门与生产路径断言

### Task 8: 仅在真实 control recovery 成功时记录 reconnect marker

**Files:**
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/ControlReconnectOrchestrator.java:75-117,195-203`
- Modify: `fabric/src/main/java/io/github/limuqy/mc/hassium/network/FabricNetworkManager.java:321-359,386-422`
- Modify: `neoforge/src/main/java/io/github/limuqy/mc/hassium/network/NeoForgeNetworkManager.java`（Task 6 新增的 S2C tail consumer）
- Modify: `common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/ControlReconnectOrchestratorTest.java`
- Create: `common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/FailoverSmokeMarkerTest.java`

**Interfaces:**
- Consumes: `ClientRecoveryState.isRecovering()` 和 `ControlReconnectOrchestrator.onPrimaryDisconnected(...)` 建立的 `recovering=true` 状态。
- Produces: `onHandshakeAccepted()` 返回 `boolean`；仅返回 `true` 的真实恢复完成路径记录 `FAILOVER_RECONNECT_OK`。首次登录 epoch=0 不产生该 marker；terminal 仍恰好一次产生 `FAILOVER_TERMINAL_OK`。

- [ ] **Step 1: 写 marker 状态门的失败测试**

```java
@Test
void initialHandshakeDoesNotClaimReconnect() {
    var logs = new CapturingSmokeLog();
    var orchestrator = ControlReconnectOrchestrator.forTest(new RecordingLauncher(), List.of(BACKUP), logs);

    assertFalse(orchestrator.onHandshakeAccepted());

    assertThat(logs.messages()).noneMatch(m -> m.contains("FAILOVER_RECONNECT_OK"));
    assertEquals(0L, orchestrator.connectionEpoch());
}

@Test
void recoveredHandshakeEmitsReconnectMarkerExactlyOnce() {
    var logs = new CapturingSmokeLog();
    var launcher = new RecordingLauncher();
    var orchestrator = ControlReconnectOrchestrator.forTest(launcher, List.of(BACKUP), logs);

    orchestrator.onPrimaryDisconnected(PRIMARY, "nginx closed stream");
    long recoveryEpoch = orchestrator.connectionEpoch();

    assertTrue(orchestrator.onHandshakeAccepted());
    assertFalse(orchestrator.onHandshakeAccepted());
    assertThat(logs.messages()).containsExactly("HassiumSmokeTest:UDP_FAILOVER FAILOVER_RECONNECT_OK epoch=" + recoveryEpoch);
}

@Test
void candidateExhaustionEmitsOneTerminalMarkerAndConsumesCleanupOnce() {
    var logs = new CapturingSmokeLog();
    var launcher = new RecordingLauncher();
    var orchestrator = ControlReconnectOrchestrator.forTest(launcher, List.of(BACKUP), logs);
    orchestrator.onPrimaryDisconnected(PRIMARY, "nginx closed stream");

    orchestrator.onReconnectFailed(BACKUP);
    orchestrator.onReconnectFailed(BACKUP);

    assertEquals(1, orchestrator.terminalFinalizations());
    assertThat(logs.messages()).filteredOn(m -> m.contains("FAILOVER_TERMINAL_OK")).hasSize(1);
}
```

`CapturingSmokeLog` 是 package-private `Consumer<String>` test seam；生产日志仍保持 `SMOKE_LOG.info(...)`，不让测试依赖 SLF4J appender 配置。若现有 test factory 参数过多，新 factory 必须 delegate 至 production constructor 配置，不能只为测试复制恢复算法。

- [ ] **Step 2: 运行测试，确认当前 initial handshake 会错误记录 epoch=0**

Run:

```bat
cmd /c "gradlew.bat --no-daemon common:test --tests io.github.limuqy.mc.hassium.network.dataplane.ControlReconnectOrchestratorTest --tests io.github.limuqy.mc.hassium.network.dataplane.FailoverSmokeMarkerTest -Pmc_ver=1.20.1 --console=plain"
```

Expected: FAIL；当前 `onHandshakeAccepted()` 无条件打印 `FAILOVER_RECONNECT_OK epoch=0`，且返回类型为 `void`。

- [ ] **Step 3: 将 `onHandshakeAccepted` 改为明确状态转换**

按以下等价实现收束状态机：

```java
public synchronized boolean onHandshakeAccepted() {
    if (!recovering) {
        return false;
    }
    recovering = false;
    terminalFinalized = false;
    current = null;
    smokeLog.accept("HassiumSmokeTest:UDP_FAILOVER FAILOVER_RECONNECT_OK epoch=" + connectionEpoch);
    return true;
}
```

`terminalFinalized=false` 只能发生在已确认的一次 recovery handshake，不能在初始连接悄悄清掉 terminal 状态。`connectionEpoch` 必须保留该 recovery epoch；不得在 `onHandshakeAccepted` 递增。`onFailoverPermit` 仍必须严格要求 `recovering && epoch==connectionEpoch && !expired`；其现有 `FAILOVER_PERMIT_OK` 记录应保持在 permit 被实际接受的分支，拒绝/过期/错 epoch 不得记录。

`DataPlaneUdpServer` 的 `UDP_BIND_OK` 记录须保持在所有 configured listener bind 成功且创建 `BoundEndpoint` 后；`UdpBulkRouter` 的 `UDP_WRR_OK` 仅在 `enqueue()` 成功的第一个真实 bulk packet 后；`ClientMetadataHandler` 的 `CACHE_RESUME_HIT` 仅在 `hitChunks` 非空。这些 marker 不新增 smoke shortcut，只在本 Task 的 test 中锁定其 guard。

- [ ] **Step 4: 两个 loader 的 S2C consumer 只在 recovery 时确认恢复**

Task 6 所有 Fabric/NeoForge S2C tail consumer 都按下面顺序在客户端主线程执行：

```java
boolean recovering = ClientRecoveryState.getInstance().isRecovering();
if (tail.hasUdpDataplane() && Minecraft.getInstance().player != null) {
    DataPlaneClientLifecycle.getInstance().startUdp(playerId, tail.connectionEpoch(), tail);
}
if (recovering) {
    ClientRecoveryState.getInstance().markRecovered();
    if (orchestrator != null) {
        orchestrator.onHandshakeAccepted();
    }
}
```

初始握手的 `Phase.NONE` 既不调用 `markRecovered` 也不调用 `onHandshakeAccepted`；无 UDP 的 control-only tail 也遵循相同 `recovering` gate。先读取 `recovering`，再启动 UDP，避免 `startUdp` 异步回调改变状态导致该握手被误判。tail 解码异常只禁用本次 optional data plane，绝不能调用 `onHandshakeAccepted`。

- [ ] **Step 5: 增加 production guard 的纯单元测试**

`FailoverSmokeMarkerTest` 必须另外覆盖：

```java
@Test
void noUdpBindMarkerWhenDataPlaneDisabled() {
    DataPlaneUdpServer.forTest(dataPlane(false, List.of()), CapturingSmokeLog.INSTANCE);
    DataPlaneUdpServer.bind();
    assertThat(CapturingSmokeLog.INSTANCE.messages()).noneMatch(m -> m.contains("UDP_BIND_OK"));
}

@Test
void noWrrMarkerWhenRouterFallsBackPrimary() {
    var router = UdpBulkRouter.forTestNoSessions(CapturingSmokeLog.INSTANCE);
    assertEquals(RouteDecision.PRIMARY, router.route(PLAYER, TYPE_CHUNK, payload()));
    assertThat(CapturingSmokeLog.INSTANCE.messages()).noneMatch(m -> m.contains("UDP_WRR_OK"));
}
```

测试必须调用 production route/bind 分支；不得直接调用 marker emitter 或 mock `PlayerSessions.dataSentMarkerEmitted`。

- [ ] **Step 6: 运行 marker 与回归测试、编译双 loader**

Run:

```bat
cmd /c "gradlew.bat --no-daemon common:test --tests io.github.limuqy.mc.hassium.network.dataplane.ControlReconnectOrchestratorTest --tests io.github.limuqy.mc.hassium.network.dataplane.FailoverSmokeMarkerTest --tests io.github.limuqy.mc.hassium.network.dataplane.ControlFailoverHandlerTest -Pmc_ver=1.20.1 --console=plain"
cmd /c "gradlew.bat --no-daemon fabric:compileJava -Pmc_ver=1.20.1 --console=plain"
cmd /c "gradlew.bat --no-daemon neoforge:compileJava -Pmc_ver=1.20.5 --console=plain"
taskkill /F /IM java.exe
```

Expected: 首次握手无 reconnect marker；真正恢复恰有一个 reconnect marker；terminal 恰有一个 terminal marker；全部 compile task 成功。

- [ ] **Step 7: 提交恢复 marker 状态门**

```bat
git add common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/ControlReconnectOrchestrator.java common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/ControlReconnectOrchestratorTest.java common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/FailoverSmokeMarkerTest.java fabric/src/main/java/io/github/limuqy/mc/hassium/network/FabricNetworkManager.java neoforge/src/main/java/io/github/limuqy/mc/hassium/network/NeoForgeNetworkManager.java
git commit -m "fix(failover): emit reconnect marker only after recovery"
```

---

## Phase 6：隔离 Nginx 冒烟编排与端到端验收

### Task 9: 让 runtime smoke harness 以 Nginx 注入 TCP control 断连

**Files:**
- Modify: `scripts/runtime-smoke-test.ps1`
- Create: `scripts/smoke/nginx-failover.conf.template`
- Modify: `docs/runtime-smoke-test.md`

**Interfaces:**
- 输入：`-Phase udp-failover`、服务端真实 `network.dataPlane` 配置、测试端口参数。
- 输出：临时 Nginx `stream` 配置；客户端只连接 Nginx listener；Minecraft server 仍只监听其既有 TCP 端口；UDP 不经过 Nginx。
- 观测：脚本只读取现有生产日志 marker，绝不写 marker 或模拟 `ControlReconnectOrchestrator` 状态。

- [ ] **Step 1: 先写 PowerShell helper 的 Pester/可调用函数测试**

将纯文本生成与 marker 判定抽为无副作用函数，并覆盖：

```powershell
$conf = New-UdpFailoverNginxConfig -ListenPort 25570 -PrimaryPort 25565
$conf | Should -Match 'listen 127.0.0.1:25570'
$conf | Should -Match 'server 127.0.0.1:25565'

$markers = Get-UdpFailoverMarkers -ClientLog $client -ServerLog $server
$markers.UDP_BIND_OK | Should -BeTrue
$markers.FAILOVER_PERMIT_OK | Should -BeTrue
$markers.UDP_WRR_OK | Should -BeTrue
$markers.FAILOVER_RECONNECT_OK | Should -BeTrue
$markers.FAILOVER_TERMINAL_OK | Should -BeTrue
$markers.CACHE_RESUME_HIT | Should -BeTrue
```

配置模板只包含 `stream { upstream minecraft_primary { server 127.0.0.1:<primary>; } server { listen 127.0.0.1:<proxy>; proxy_pass minecraft_primary; } }`。明确不启用 HTTP，也不代理 UDP。

- [ ] **Step 2: 运行测试，确认 helper 尚不存在**

Run:

```powershell
Invoke-Pester scripts/smoke/runtime-smoke-test.Tests.ps1 -Output Detailed
```

Expected: FAIL，缺少 Nginx 配置/marker helper。

- [ ] **Step 3: 在 `udp-failover` 专属分支实现 Nginx 生命周期**

`runtime-smoke-test.ps1` 必须：

1. 在 server ready 后生成 session 专属 conf，启动测试目录内明确指定的 `nginx.exe -c <absolute-conf> -p <session-dir>`；启动失败立即终止 server 并失败，不能静默回退直连。
2. 把 `$effectiveHost` 改为 `127.0.0.1:$ProxyPort`，UDP listener 继续由 server.toml advertisement 指向 server 的 UDP port，不能把 UDP endpoint 改成 proxy port。
3. 等待 TCP proxy port 可连接后再启动 client；在 Round 1 后由 Nginx `-s reload` 配置切换到不可达 upstream 或直接 `-s quit`，以真实 TCP close 触发客户端 `channelInvalidated`。断连注入仅发生一次，且记录 harness 自己的时间线日志。
4. Round 2/reconnect 成功后恢复 upstream，等待 smoke 自行走到 terminal；`finally` 中无条件 `nginx -s quit`、删除 session conf、停止本次 server/client 子进程。
5. 保留 classic / global / cache phases 的现有直连路径，不让 Nginx 依赖进入非 failover phases。

不能靠 kill `java.exe`、修改 Minecraft 连接类、写入 `HassiumSmokeTest:*` marker 或检查 marker 后提前 PASS。

- [ ] **Step 4: 以静态配置测试与手工小场景验证编排**

Run:

```powershell
Invoke-Pester scripts/smoke/runtime-smoke-test.Tests.ps1 -Output Detailed
powershell -ExecutionPolicy Bypass -File scripts/runtime-smoke-test.ps1 -Phase udp-failover -DryRun
```

Expected: 配置中的 TCP proxy 端口与 UDP 可达端口不同；DryRun 展示 server → nginx-ready → client → inject-close → restore → cleanup 顺序，且不启动游戏。

- [ ] **Step 5: 更新运行手册并提交 harness**

`docs/runtime-smoke-test.md` 写明：本 phase 要求本机 Nginx 可执行文件，TCP 与 UDP 必须分别可用，使用临时 session 工作目录；六 marker 的生产来源分别是 bind、permit、route、recovered、terminal、cache-resume。说明任一 marker 缺失即失败，不把日志匹配等同于网络正确性。

```bat
git add scripts/runtime-smoke-test.ps1 scripts/smoke/nginx-failover.conf.template scripts/smoke/runtime-smoke-test.Tests.ps1 docs/runtime-smoke-test.md
git commit -m "test(failover): exercise control recovery through nginx"
```

---

### Task 10: 执行分层验证、跨版本编译与真实 failover smoke

**Files:**
- Modify only if verification finds a defect in its owning task; otherwise none.

- [ ] **Step 1: 运行 common 定向单元测试**

```bat
cmd /c "gradlew.bat --no-daemon common:test --tests io.github.limuqy.mc.hassium.config.FabricTomlConfigIOTest --tests io.github.limuqy.mc.hassium.network.dataplane.DataPlaneUdpServerTest --tests io.github.limuqy.mc.hassium.network.dataplane.ControlFailoverHandlerTest --tests io.github.limuqy.mc.hassium.network.dataplane.ControlReconnectOrchestratorTest --tests io.github.limuqy.mc.hassium.network.dataplane.UdpDataPlaneHandshakeTailTest --tests io.github.limuqy.mc.hassium.network.dataplane.DataPlaneClientGroupSelectionTest --tests io.github.limuqy.mc.hassium.network.dataplane.FailoverSmokeMarkerTest -Pmc_ver=1.20.1 --console=plain"
taskkill /F /IM java.exe
```

Expected: PASS。任何已有 baseline failure 必须单列并确认不在本次引用类的依赖链上，不能将其吞掉。

- [ ] **Step 2: 运行九段锚点 compile**

按 `docs/version-segments.md` 中的九段 anchor 逐段运行 `common:compileJava`、对应 loader `compileJava`。Forge 仅跑 1.20.1、1.20.6；NeoForge 与 Fabric 覆盖各自的所有 anchor。每次都使用 `--no-daemon` 和精确字符串 `-Pmc_ver=<version>`，批次后 `taskkill /F /IM java.exe`。

Expected: 所有任务 BUILD SUCCESSFUL；失败时按首个版本分段 API 差异修复，不通过跳过 version 或降级 endpoint 特性规避。

- [ ] **Step 3: 执行 Fabric 1.20.1 真实端到端 smoke**

```powershell
powershell -ExecutionPolicy Bypass -File scripts/runtime-smoke-test.ps1 -Phase udp-failover -Loader fabric -Ver 1.20.1 -SessionId udp-failover-final
```

Expected: client 经 Nginx 建立初始 TCP；server 成功 bind UDP；批量 chunk 流量经过任一 Data listener；Nginx 关闭 primary TCP 后 client 收到 permit 并恢复到唯一 Play connection；重连后的 cache hash 比较至少一个 hit；最终只出现一次 terminal marker，脚本结果六项 marker 都为 `True`。

- [ ] **Step 4: 审核数据面不变量和清理**

确认：

- `DataPlanePoCConfig.ENDPOINTS` 与任何硬编码 `25566/25567` 已删除；默认 endpoint 是 TCP/UDP 同端口 25565 的合法配置，而非 bind/advertise 混用。
- `0.0.0.0`、`::` 从不进入 S2C tail；每组只建立一条 `ReliableDatagramSession`，候选失败只在同组切换。
- 首次握手不会发 `FAILOVER_RECONNECT_OK`；不发生 recovery 时没有 terminal marker；marker 无法被 harness 伪造。
- `common` 没有 loader/Minecraft import；协议版本没有 bump；Primary fallback、`tryRouteBulk` 返回契约与普通烟测未回归。

然后按逻辑提交序列确认每个任务单独提交；仅在所有验证后更新本计划状态与 `docs/runtime-smoke-test.md`。
