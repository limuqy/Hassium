> 归档：历史 superpowers 计划（已完成使命）（2026-08-09）
# UDP Data Plane and TCP Control Failover Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the TCP data-plane PoC with authenticated KCP-over-UDP bulk transport and recover a failed Minecraft TCP control entrance by reconnecting through server-advertised backup endpoints while retaining the Hassium disk cache.

**Architecture:** `DataPlaneUdpServer` binds every configured UDP endpoint and owns KCP-backed `ReliableDatagramSession` instances keyed by player UUID, endpoint, epoch, and remote address. The existing loader `ChunkSender` call contract remains `tryRouteBulk(UUID, int, byte[])`; its transport changes from TCP `PlayerChannel` writes to `UdpBulkRouter` selection. TCP remains the only vanilla control path; client failover is a controlled normal re-login using a bounded UDP lease and the existing chunk-hash cache path.

**Tech Stack:** Java 17, Netty `DatagramChannel`, `moe.sdl.kcp:kcp-netty:1.6.2`, JDK `AES/GCM/NoPadding`, HKDF-SHA256, JUnit 5, Gradle, Fabric 1.20.1 runtime smoke.

## Global Constraints

- Start at the 1.20.1 Fabric anchor; do not start NeoForge or the other version segments until the Fabric smoke phase passes.
- Forge is out of scope.
- `common` MUST NOT import Fabric, Forge, or NeoForge API.
- MUST NOT alter `MixinConnection` for whole-packet routing or alter `ServerChunkPushManager` business logic.
- Loader `ChunkSender` adapters MUST retain `tryRouteBulk(UUID, int, byte[])`: `true` means data-plane consumed/dropped; `false` means Primary sends the payload.
- TCP remains the only Minecraft Login/Play/control transport. Backup TCP endpoints are cold candidates; never maintain concurrent Minecraft Play connections as “masters.”
- Bind every configured public UDP `(bindHost, bindPort)` pair. Logical multipath MUST NOT assume that an endpoint maps to a separate physical NIC.
- Use a KCP-compatible reliable message transport with congestion control, MTU-bounded fragmentation, bounded reassembly, retransmission, duplicate suppression, and backpressure. Raw UDP bulk is forbidden.
- After Bind, protect application frames with AES-GCM and an anti-replay sequence window. AES/CFB8 MUST NOT remain in the UDP data-plane path.
- A disconnected primary can retain UDP sessions only for `udpLeaseAfterPrimaryCloseMs`; drain only already accepted frames during the lease and do not create new player bulk until re-login.
- Automatic recovery MUST preserve disk cache infrastructure, not an old in-memory `ClientLevel`; terminal/user disconnect MUST still run existing cleanup exactly once.
- Handshake additions MUST be append-only and guarded by `isReadable()`. Old clients and feature-disabled servers remain Primary-only.
- Use `DebugLogger` for per-frame/per-endpoint hot-path diagnostics; INFO only for bind, lifecycle, and failover outcomes.
- Do not replace unrelated user work in the dirty tree.

---

## File Structure

| File | Responsibility |
|---|---|
| `common/build.gradle` | Adds the pinned KCP Netty implementation to common runtime/classpath. |
| `common/.../dataplane/ReliableDatagramSession.java` | Transport-neutral KCP session façade: message send/receive, metrics, backpressure, AEAD, frame sequence and replay window. |
| `common/.../dataplane/UdpFrameCodec.java` | Encodes/decodes KCP-carried frame payloads; AES-GCM sealing/opening; frame sequence validation. |
| `common/.../dataplane/UdpBindRequestCodec.java` | BindRequest/BindAck v3 wire codec containing UUID, connection epoch, token, protocol, channel id and endpoint id. |
| `common/.../dataplane/DataPlaneUdpServer.java` | Binds configured UDP endpoints, maps datagrams to sessions, performs Bind, dispatches KCP application frames and routes bulk. |
| `common/.../dataplane/DataPlaneSessionRegistry.java` | UUID/epoch/endpoint session table, token/epoch lifecycle, UDP lease and endpoint health ownership. |
| `common/.../dataplane/UdpBulkRouter.java` | Weighted selection of healthy UDP sessions and existing share/exclusive fallback semantics. |
| `common/.../dataplane/ControlFailoverHandler.java` | Server control-progress timestamps, request validation, permit issuance, old-primary close, bounded lease. |
| `common/.../dataplane/ControlEndpoint.java` | Immutable `host`, `port`, `priority` candidate record with input validation. |
| `common/.../dataplane/ControlEndpointManager.java` | Client candidate merge, ordered retries, recovery deadline, and current master identity. |
| `common/.../dataplane/ClientRecoveryState.java` | Thread-safe recovery state; gates final cleanup and exposes terminal transition. |
| `common/.../dataplane/UdpDataPlaneHandshakeTail.java` | Append-only UDP/control-failover C2S/S2C tail codecs. |
| `common/.../dataplane/ControlReconnectOrchestrator.java` | Owns loader-independent recovery state and candidate retry sequencing; delegates the actual standard login to an injected launcher. |
| `common/.../network/dataplane/DataPlaneServer.java` | Deleted or reduced to a source-compatible forwarding façade only during the single migration task; no TCP data listener remains. |
| `fabric/.../client/FabricControlReconnectLauncher.java` | Thin adapter from `ControlReconnectLauncher` to the existing Fabric multiplayer connect API. |
| `common/.../mixin/MixinMinecraftServer.java` | Starts/stops `DataPlaneUdpServer` instead of TCP DataPlaneServer. |
| `common/.../mixin/MixinServerGamePacketListenerImpl.java` | Registers control activity; hands primary disconnect into failover lease rather than immediately deleting UDP sessions. |
| `common/.../mixin/MixinMinecraft.java` | Uses `ClientRecoveryState` to suppress terminal cache finalization only during automatic recovery. |
| `common/.../cache/client/ClientLifecycleHelper.java` | Splits recoverable cleanup gate from one-time terminal cleanup. |
| `common/.../network/ClientMetadataHandler.java` | Retains persistent cache state during recovery and clears transient request state only at correct lifecycle boundaries. |
| `fabric/.../network/FabricNetworkManager.java` | Encodes/decodes new handshake tail and starts fresh UDP sessions on successful handshake. |
| `fabric/.../HassiumClientMod.java` | Detects primary disconnect, begins endpoint-manager recovery, invokes standard Minecraft reconnect, and marks terminal failure. |
| `fabric/.../HassiumMod.java` | Keeps the current `ChunkSender` insertion and redirects it to UDP routing. |
| `common/src/test/.../dataplane/*.java` | Unit and integration tests for reliable transport, AEAD, bind, routing, leases, failover and recovery state. |
| `scripts/runtime-smoke-test.ps1` | Adds a 1.20.1 Fabric UDP failover phase with master-close and backup recovery assertions. |

## Task 1: Establish the Reliable UDP Dependency and Wire Contracts

**Files:**
- Modify: `common/build.gradle:6-35`
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneFrame.java:8-18`
- Create: `common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/UdpBindRequestCodec.java`
- Create: `common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/UdpFrameCodec.java`
- Test: `common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/UdpBindRequestCodecTest.java`
- Test: `common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/UdpFrameCodecTest.java`

**Interfaces:**
- Consumes: `DataPlaneFrame.encode(int, byte[])`, `DataPlaneFrame.decodeType(byte[])`, `Hkdf.extractAndExpand(byte[], byte[], byte[], int)`.
- Produces: `UdpBindRequestCodec.encodeRequest(byte[] token, UUID playerId, long epoch, int endpointId, int channelId)`, `UdpBindRequestCodec.decodeRequest(byte[] bytes)`, `UdpFrameCodec.seal(byte[] key, Direction direction, long sequence, int type, byte[] payload)`, `UdpFrameCodec.open(byte[] key, Direction direction, long expectedMinimumSequence, byte[] sealed)`.

- [ ] **Step 1: Add the exact KCP dependency and compile it alone**

```groovy
// common/build.gradle, inside dependencies
implementation 'moe.sdl.kcp:kcp-netty:1.6.2'
```

Run: `./gradlew --no-daemon common:compileJava "-Pmc_ver=1.20.1"`

Expected: `BUILD SUCCESSFUL`; no loader API appears in common.

- [ ] **Step 2: Write failing BindRequest v3 codec tests**

```java
@Test
void requestRoundTripsTokenUuidEpochAndEndpoint() {
    UUID player = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    byte[] token = new byte[16];
    token[0] = 9;

    byte[] encoded = UdpBindRequestCodec.encodeRequest(token, player, 42L, 3, 7);
    UdpBindRequestCodec.Request decoded = UdpBindRequestCodec.decodeRequest(encoded);

    assertArrayEquals(token, decoded.token());
    assertEquals(player, decoded.playerId());
    assertEquals(42L, decoded.connectionEpoch());
    assertEquals(3, decoded.endpointId());
    assertEquals(7, decoded.channelId());
}

@Test
void requestRejectsTruncatedAndWrongProtocol() {
    assertThrows(IllegalArgumentException.class,
            () -> UdpBindRequestCodec.decodeRequest(new byte[33]));
    assertThrows(IllegalArgumentException.class,
            () -> UdpBindRequestCodec.decodeRequest(new byte[] { 0 }));
}
```

- [ ] **Step 3: Run the codec tests to confirm absence/failure**

Run: `./gradlew --no-daemon common:test --tests "*UdpBindRequestCodecTest" "-Pmc_ver=1.20.1"`

Expected: FAIL because `UdpBindRequestCodec` does not exist.

- [ ] **Step 4: Implement the fixed v3 Bind codec**

```java
public final class UdpBindRequestCodec {
    public static final int PROTOCOL_VERSION = 3;
    private static final int TOKEN_BYTES = 16;

    public record Request(byte[] token, UUID playerId, long connectionEpoch,
                          int endpointId, int channelId) {}

    public static byte[] encodeRequest(byte[] token, UUID playerId, long epoch,
                                       int endpointId, int channelId) {
        if (token == null || token.length != TOKEN_BYTES || playerId == null
                || endpointId < 0 || channelId < 0) {
            throw new IllegalArgumentException("invalid UDP BindRequest");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(48);
        out.writeBytes(token);
        writeLong(out, playerId.getMostSignificantBits());
        writeLong(out, playerId.getLeastSignificantBits());
        writeLong(out, epoch);
        DataPlaneFrame.writeVarInt(out, PROTOCOL_VERSION);
        DataPlaneFrame.writeVarInt(out, endpointId);
        DataPlaneFrame.writeVarInt(out, channelId);
        return out.toByteArray();
    }
}
```

Implement strict decode length and VarInt bounds. Extend `DataPlaneFrame` with only the new frame type constants `TYPE_FAILOVER_REQUEST = 8` and `TYPE_FAILOVER_PERMIT = 9`; keep old types unchanged for the transition test suite.

- [ ] **Step 5: Write failing AEAD tests**

```java
@Test
void sealedFrameRoundTripsAndPreservesSequence() {
    byte[] key = new byte[16];
    Arrays.fill(key, (byte) 7);
    byte[] sealed = UdpFrameCodec.seal(key, Direction.SERVER_TO_CLIENT,
            11L, DataPlaneFrame.TYPE_BULK_COMPRESSED_CHUNK, new byte[] {1, 2});

    UdpFrameCodec.Opened opened = UdpFrameCodec.open(
            key, Direction.SERVER_TO_CLIENT, 0L, sealed);

    assertEquals(11L, opened.sequence());
    assertEquals(DataPlaneFrame.TYPE_BULK_COMPRESSED_CHUNK, opened.type());
    assertArrayEquals(new byte[] {1, 2}, opened.payload());
}

@Test
void sealRejectsModifiedCiphertextAndReplay() {
    byte[] key = new byte[16];
    byte[] sealed = UdpFrameCodec.seal(key, Direction.CLIENT_TO_SERVER,
            5L, DataPlaneFrame.TYPE_KEEPALIVE_ACK, new byte[0]);
    sealed[sealed.length - 1] ^= 1;
    assertThrows(SecurityException.class,
            () -> UdpFrameCodec.open(key, Direction.CLIENT_TO_SERVER, 0L, sealed));

    byte[] valid = UdpFrameCodec.seal(key, Direction.CLIENT_TO_SERVER,
            5L, DataPlaneFrame.TYPE_KEEPALIVE_ACK, new byte[0]);
    assertThrows(SecurityException.class,
            () -> UdpFrameCodec.open(key, Direction.CLIENT_TO_SERVER, 6L, valid));
}
```

- [ ] **Step 6: Run the AEAD test to confirm it fails**

Run: `./gradlew --no-daemon common:test --tests "*UdpFrameCodecTest" "-Pmc_ver=1.20.1"`

Expected: FAIL because `UdpFrameCodec` and `Direction` do not exist.

- [ ] **Step 7: Implement AEAD framing with deterministic nonce construction**

```java
public enum Direction { CLIENT_TO_SERVER((byte) 0), SERVER_TO_CLIENT((byte) 1); }

public static byte[] seal(byte[] key, Direction direction, long sequence,
                          int type, byte[] payload) {
    byte[] nonce = nonce(direction, sequence); // 12 bytes: direction + 11-byte unsigned sequence
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
            new GCMParameterSpec(128, nonce));
    return cipher.doFinal(plaintext(sequence, type, payload));
}
```

Encode `sequence` in plaintext before `type`; reject sequence `< expectedMinimumSequence`; use a `SecurityException` for authentication or replay failures. Task 1 receives a 16-byte test key as an explicit input. Task 3 owns the only production HKDF derivation: `HKDF(token, uuidBytes || epochBytes, "hassium-udp-v1" || endpointId || channelId, 16)`.

- [ ] **Step 8: Run all new codec tests and compile common**

Run: `./gradlew --no-daemon common:test --tests "*UdpBindRequestCodecTest" --tests "*UdpFrameCodecTest" "-Pmc_ver=1.20.1" && ./gradlew --no-daemon common:compileJava "-Pmc_ver=1.20.1"`

Expected: both test classes pass and compile succeeds.

- [ ] **Step 9: Commit the wire foundation**

```bash
git add common/build.gradle \
  common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneFrame.java \
  common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/UdpBindRequestCodec.java \
  common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/UdpFrameCodec.java \
  common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/UdpBindRequestCodecTest.java \
  common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/UdpFrameCodecTest.java
git commit -m "feat: add authenticated UDP data-plane wire codec"
```

## Task 2: Build a Bounded Reliable Datagram Session

**Files:**
- Create: `common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/ReliableDatagramSession.java`
- Create: `common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/UdpEndpoint.java`
- Test: `common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/ReliableDatagramSessionTest.java`

**Interfaces:**
- Consumes: `UdpFrameCodec.seal/open`, `DataPlaneFrame` type constants, KCP-Netty dependency.
- Produces: `ReliableDatagramSession(UUID playerId, long epoch, UdpEndpoint endpoint, InetSocketAddress remote, byte[] key, DatagramSink sink)`, `receive(ByteBuf datagram, long nowMs)`, `enqueueAuthenticated(int type, byte[] payload)`, `tick(long nowMs)`, `isWritable()`, `isHealthy()`, `metrics()`, `close()`.

- [ ] **Step 1: Write failing session tests for fragmentation, order, and backpressure**

```java
@Test
void fragmentedMessageIsDeliveredOnceInOrder() {
    TestDatagramLink link = new TestDatagramLink();
    ReliableDatagramSession server = session(link.serverSink());
    ReliableDatagramSession client = session(link.clientSink());
    link.connect(client, server);

    byte[] payload = new byte[16 * 1024];
    new Random(1).nextBytes(payload);
    server.enqueueAuthenticated(DataPlaneFrame.TYPE_BULK_COMPRESSED_CHUNK, payload);
    link.pumpUntilIdle(0L, 10_000L);

    assertEquals(1, client.received().size());
    assertArrayEquals(payload, client.received().getFirst().payload());
}

@Test
void boundedQueuedBytesMakesSessionNotWritable() {
    ReliableDatagramSession session = session(DatagramSink.discarding());
    session.setMaxQueuedBytesForTest(64);
    session.enqueueAuthenticated(DataPlaneFrame.TYPE_BULK_COMPRESSED_CHUNK, new byte[80]);
    assertFalse(session.isWritable());
}
```

- [ ] **Step 2: Run the session test to verify it fails**

Run: `./gradlew --no-daemon common:test --tests "*ReliableDatagramSessionTest" "-Pmc_ver=1.20.1"`

Expected: FAIL because `ReliableDatagramSession` and `UdpEndpoint` do not exist.

- [ ] **Step 3: Implement the transport façade, not KCP calls in routing code**

```java
public final class ReliableDatagramSession implements AutoCloseable {
    public interface DatagramSink { void send(ByteBuf datagram); }
    public record Received(long sequence, int type, byte[] payload) {}
    public record Metrics(long srttMs, long packetsLost, int queuedBytes, boolean writable) {}

    public boolean enqueueAuthenticated(int type, byte[] payload) { /* queue bounded KCP message */ }
    public void receive(ByteBuf datagram, long nowMs) { /* KCP input then UdpFrameCodec.open */ }
    public void tick(long nowMs) { /* KCP update/flush */ }
    public boolean isWritable() { return queuedBytes.get() < maxQueuedBytes && !closed; }
    public boolean isHealthy() { return bound && isWritable() && metrics.srttMs() <= hardRttMs; }
}
```

Configure MTU to `1200`, congestion control enabled, receive/send windows bounded by constants, and a maximum application reassembly allocation. Deliver decoded frames to a supplied `Consumer<Received>`; never invoke Minecraft handlers from the UDP event loop.

- [ ] **Step 4: Add deterministic loss/reorder coverage**

```java
@Test
void oneDroppedDatagramRetransmitsWithoutDuplicateDelivery() {
    TestDatagramLink link = new TestDatagramLink().dropExactly(2).reorderExactly(3, 4);
    ReliableDatagramSession server = session(link.serverSink());
    ReliableDatagramSession client = session(link.clientSink());
    link.connect(client, server);

    server.enqueueAuthenticated(DataPlaneFrame.TYPE_BULK_COMPRESSED_CHUNK, new byte[] {4, 5, 6});
    link.pumpUntilIdle(0L, 20_000L);

    assertEquals(1, client.received().size());
    assertArrayEquals(new byte[] {4, 5, 6}, client.received().getFirst().payload());
    assertTrue(server.metrics().packetsLost() >= 1L);
}
```

- [ ] **Step 5: Run transport tests and inspect heap-bound behavior**

Run: `./gradlew --no-daemon common:test --tests "*ReliableDatagramSessionTest" "-Pmc_ver=1.20.1"`

Expected: PASS; tests prove KCP retransmits a lost datagram, does not duplicate delivery, fragments the 16KiB message, and applies queue backpressure.

- [ ] **Step 6: Commit the reliable session boundary**

```bash
git add common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/ReliableDatagramSession.java \
  common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/UdpEndpoint.java \
  common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/ReliableDatagramSessionTest.java
git commit -m "feat: add bounded KCP reliable datagram sessions"
```

## Task 3: Replace TCP Data-Plane Server Lifecycle with UDP Endpoint Binding

**Files:**
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlanePoCConfig.java`
- Create: `common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneSessionRegistry.java`
- Create: `common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneUdpServer.java`
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneServer.java:24-461`
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/mixin/MixinMinecraftServer.java`
- Test: `common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneSessionRegistryTest.java`
- Test: `common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneUdpServerBindTest.java`

**Interfaces:**
- Consumes: `ReliableDatagramSession`, `UdpBindRequestCodec`, `DataPlanePoCConfig.Endpoint` fields `address`, `port`, `weight`, `bindHost`, `bindPort`.
- Produces: `DataPlaneUdpServer.bind()`, `shutdown()`, `isBound()`, `getSessionToken()`, `getBoundEndpoints()`, `tryRouteBulk(UUID, int, byte[])`, `onPrimaryDisconnect(UUID, long)`, and `DataPlaneSessionRegistry.createBoundSession(...)`.

- [ ] **Step 1: Write failing session-registry lifecycle tests**

```java
@Test
void sessionsAreSeparatedByUuidEpochAndEndpoint() {
    DataPlaneSessionRegistry registry = new DataPlaneSessionRegistry();
    UUID player = UUID.randomUUID();
    ReliableDatagramSession one = testSession(player, 10L, 1);
    ReliableDatagramSession two = testSession(player, 10L, 2);

    registry.register(one);
    registry.register(two);

    assertEquals(List.of(one, two), registry.sessions(player, 10L));
    assertTrue(registry.sessions(player, 11L).isEmpty());
}

@Test
void primaryCloseRetainsOnlyLeaseThenClosesSessions() {
    DataPlaneSessionRegistry registry = new DataPlaneSessionRegistry();
    ReliableDatagramSession session = testSession(UUID.randomUUID(), 1L, 1);
    registry.register(session);

    registry.onPrimaryDisconnect(session.playerId(), 1L, 100L, 1_000L);
    assertTrue(session.isLeaseActive(500L));
    registry.expireLeases(1_100L);
    assertTrue(session.isClosed());
}
```

- [ ] **Step 2: Run the registry tests to prove failure**

Run: `./gradlew --no-daemon common:test --tests "*DataPlaneSessionRegistryTest" "-Pmc_ver=1.20.1"`

Expected: FAIL because registry APIs do not exist.

- [ ] **Step 3: Implement token/epoch/session ownership**

```java
public final class DataPlaneSessionRegistry {
    public void register(ReliableDatagramSession session);
    public List<ReliableDatagramSession> sessions(UUID playerId, long epoch);
    public void replaceEpoch(UUID playerId, long newEpoch);
    public void onPrimaryDisconnect(UUID playerId, long epoch, long nowMs, long leaseMs);
    public void expireLeases(long nowMs);
}
```

Generate one 16-byte `SecureRandom` token at UDP server bind. Do not use `DataPlanePoCConfig.BIND_TOKEN` outside tests. Derive per-session key using `HKDF(token, uuidBytes || epochBytes, "hassium-udp-v1" || endpointId || channelId, 16)`.

- [ ] **Step 4: Write failing UDP bind integration test**

```java
@Test
void everyConfiguredEndpointBindsAndAcceptsOnlyMatchingBindEndpoint() {
    DataPlaneUdpServer server = DataPlaneUdpServer.forTest(endpoints(
            endpoint("127.0.0.1", 0, 10), endpoint("127.0.0.1", 0, 20)));
    server.bind();

    assertEquals(2, server.getBoundEndpoints().size());
    assertTrue(server.isBound());

    assertTrue(sendValidBind(server, 1, UUID.randomUUID(), 1L));
    assertFalse(sendBindWithWrongEndpointId(server, 2));
}
```

- [ ] **Step 5: Run the UDP binding test to prove failure**

Run: `./gradlew --no-daemon common:test --tests "*DataPlaneUdpServerBindTest" "-Pmc_ver=1.20.1"`

Expected: FAIL because `DataPlaneUdpServer` does not exist.

- [ ] **Step 6: Implement endpoint binding and first-frame bind dispatch**

```java
public final class DataPlaneUdpServer {
    public static synchronized void bind();
    public static synchronized void shutdown();
    public static boolean isBound();
    public static byte[] getSessionToken();
    public static boolean tryRouteBulk(UUID playerId, int frameType, byte[] payload);

    private void onDatagram(int endpointId, DatagramPacket packet) {
        // Route by (remote address, endpoint id) to existing session.
        // Unknown peer accepts only a syntactically valid BindRequest.
    }
}
```

Use `NioDatagramChannel`, one channel per configured bind pair, with a shared `NioEventLoopGroup`. Route unknown packets only through bounded pre-bind parsing; do not allocate KCP state before token, epoch, endpoint id, and protocol validation pass. Schedule KCP `tick()` and lease expiry on the owning event loop.

Replace `MixinMinecraftServer` startup/shutdown calls to `DataPlaneUdpServer`. Convert `DataPlaneServer` into a package-private removal target or a forwarding façade whose static methods delegate only to `DataPlaneUdpServer`; remove `NioServerSocketChannel`, `SocketChannel`, `ReadTimeoutHandler`, and TCP `BindHandshakeHandler` from production.

- [ ] **Step 7: Run transport and server binding tests**

Run: `./gradlew --no-daemon common:test --tests "*ReliableDatagramSessionTest" --tests "*DataPlaneSessionRegistryTest" --tests "*DataPlaneUdpServerBindTest" "-Pmc_ver=1.20.1" && ./gradlew --no-daemon common:compileJava "-Pmc_ver=1.20.1"`

Expected: PASS; no TCP data-plane listener classes remain reachable from `DataPlaneUdpServer`.

- [ ] **Step 8: Commit UDP server lifecycle**

```bash
git add common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlanePoCConfig.java \
  common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneSessionRegistry.java \
  common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneUdpServer.java \
  common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneServer.java \
  common/src/main/java/io/github/limuqy/mc/hassium/mixin/MixinMinecraftServer.java \
  common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneSessionRegistryTest.java \
  common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneUdpServerBindTest.java
git commit -m "feat: bind authenticated UDP data-plane endpoints"
```

## Task 4: Route Bulk Through Health-Aware UDP WRR

**Files:**
- Create: `common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/UdpBulkRouter.java`
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneUdpServer.java`
- Modify: `fabric/src/main/java/io/github/limuqy/mc/hassium/HassiumMod.java`
- Test: `common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/UdpBulkRouterTest.java`
- Test: `common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/UdpTryRouteBulkTest.java`

**Interfaces:**
- Consumes: `DataPlaneSessionRegistry.sessions(UUID, long)`, `ReliableDatagramSession.isHealthy()`, `ReliableDatagramSession.isWritable()`, `ReliableDatagramSession.metrics()`.
- Produces: `UdpBulkRouter.select(DataPlaneSessionRegistry.PlayerSessions, String mode, int primaryWeight, int degradeAfterDrops)` and `DataPlaneUdpServer.tryRouteBulk(UUID, int, byte[])`.

- [ ] **Step 1: Write failing WRR health and fallback tests**

```java
@Test
void healthPenaltyRemovesHighRttSessionFromSelection() {
    ReliableDatagramSession fast = fakeSession(1, 50, 0);
    ReliableDatagramSession slow = fakeSession(2, 5_000, 0);
    UdpBulkRouter router = new UdpBulkRouter(1_000);

    for (int i = 0; i < 50; i++) {
        assertSame(fast, router.select(dataOnly(fast, slow), "exclusive", 100, 3));
    }
}

@Test
void exclusiveDegradesAfterThreeNoCandidateDrops() {
    UdpBulkRouter router = new UdpBulkRouter(1_000);
    PlayerSessions sessions = emptyPlayerSessions();

    assertEquals(RouteDecision.DROPPED, router.route(sessions, "exclusive", 100, 3));
    assertEquals(RouteDecision.DROPPED, router.route(sessions, "exclusive", 100, 3));
    assertEquals(RouteDecision.PRIMARY, router.route(sessions, "exclusive", 100, 3));
    assertTrue(sessions.degraded());
}
```

- [ ] **Step 2: Run the routing test to verify it fails**

Run: `./gradlew --no-daemon common:test --tests "*UdpBulkRouterTest" "-Pmc_ver=1.20.1"`

Expected: FAIL because `UdpBulkRouter` does not exist.

- [ ] **Step 3: Implement allocation-free health-weighted selection**

```java
public final class UdpBulkRouter {
    public RouteDecision route(PlayerSessions sessions, String mode,
                               int primaryWeight, int degradeAfterDrops);
    public ReliableDatagramSession select(PlayerSessions sessions, String mode,
                                          int primaryWeight, int degradeAfterDrops);
}
```

Keep WRR mutable counters inside `PlayerSessions`, not per-call collections. Exclude closed, lease-expired, non-writable, or `srttMs > hardRttMs` sessions. Use endpoint configured weight as the base; apply health penalty by integer effective weight. Reset consecutive drops after any UDP enqueue success. Record primary/data bytes exactly once at the send site.

- [ ] **Step 4: Write failing DataPlaneUdpServer routing test**

```java
@Test
void tryRouteBulkReturnsTrueForUdpAndFalseForPrimaryFallback() {
    DataPlaneUdpServer server = serverWithBoundSession(healthySession());
    assertTrue(server.tryRouteBulk(PLAYER, DataPlaneFrame.TYPE_BULK_COMPRESSED_CHUNK, new byte[] {1}));
    assertEquals(1, healthySession().sentFrames().size());

    server.removeSessions(PLAYER);
    assertFalse(server.tryRouteBulk(PLAYER, DataPlaneFrame.TYPE_BULK_COMPRESSED_CHUNK, new byte[] {2}));
}
```

- [ ] **Step 5: Implement routing in the existing Fabric ChunkSender insertion only**

```java
if (DataPlaneUdpServer.tryRouteBulk(player.getUUID(),
        DataPlaneFrame.TYPE_BULK_COMPRESSED_CHUNK, compressed)) {
    return;
}
// existing Primary payload send and NetworkStats.recordBulkSentPrimary stay unchanged
```

Do not modify `ServerChunkPushManager`. Keep `SectionDelta` out of this task unless an existing loader sender has the same byte payload boundary; otherwise add it only after its own encoding/receiver coverage in Task 6.

- [ ] **Step 6: Run router tests and Fabric compile**

Run: `./gradlew --no-daemon common:test --tests "*UdpBulkRouterTest" --tests "*UdpTryRouteBulkTest" "-Pmc_ver=1.20.1" && ./gradlew --no-daemon fabric:compileJava "-Pmc_ver=1.20.1"`

Expected: PASS; the existing Fabric primary path remains compiled and reachable.

- [ ] **Step 7: Commit health-aware bulk routing**

```bash
git add common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/UdpBulkRouter.java \
  common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneUdpServer.java \
  fabric/src/main/java/io/github/limuqy/mc/hassium/HassiumMod.java \
  common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/UdpBulkRouterTest.java \
  common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/UdpTryRouteBulkTest.java
git commit -m "feat: route chunk bulk through healthy UDP sessions"
```

## Task 5: Add Append-Only Handshake Discovery and Client UDP Lifecycle

**Files:**
- Create: `common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/UdpDataPlaneHandshakeTail.java`
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneClientLifecycle.java`
- Replace: `common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneClientBundle.java`
- Modify: `fabric/src/main/java/io/github/limuqy/mc/hassium/network/FabricNetworkManager.java`
- Test: `common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/UdpDataPlaneHandshakeTailTest.java`
- Test: `common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/UdpClientBundleTest.java`

**Interfaces:**
- Consumes: `DataPlaneUdpServer.getSessionToken()`, `getBoundEndpoints()`, `ReliableDatagramSession`, `ClientChunkHandler.handleCompressedChunk(byte[])`.
- Produces: `UdpDataPlaneHandshakeTail.writeC2S/readC2S/writeS2C/readS2C`, `DataPlaneClientLifecycle.startUdp(UUID, UdpDataPlaneHandshakeTail.S2CTail)`, `DataPlaneClientLifecycle.stopUdp(boolean keepLease)`.

- [ ] **Step 1: Write failing handshake-tail compatibility tests**

```java
@Test
void oldHandshakeWithNoTailDecodesAsDisabled() {
    FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
    assertFalse(UdpDataPlaneHandshakeTail.readS2C(buf).hasUdpDataplane());
}

@Test
void tailRoundTripsEndpointsTokenEpochAndControlCandidates() {
    UdpDataPlaneHandshakeTail.S2CTail expected = tail(77L,
            List.of(control("b.com", 25565, 80)),
            List.of(udp("a.com", 25565, 100, 1)), token());
    FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
    UdpDataPlaneHandshakeTail.writeS2C(buf, expected);

    assertEquals(expected, UdpDataPlaneHandshakeTail.readS2C(buf));
}
```

- [ ] **Step 2: Run the handshake-tail test to verify it fails**

Run: `./gradlew --no-daemon common:test --tests "*UdpDataPlaneHandshakeTailTest" "-Pmc_ver=1.20.1"`

Expected: FAIL because `UdpDataPlaneHandshakeTail` does not exist.

- [ ] **Step 3: Implement append-only S2C and C2S tail codecs**

```java
public record C2STail(boolean udpDataplaneSupported, boolean controlFailoverSupported) {}
public record S2CTail(boolean hasUdpDataplane, boolean hasControlFailover,
                      long connectionEpoch, List<ControlEndpoint> controlEndpoints,
                      List<UdpEndpoint> udpEndpoints, byte[] token, int protocol) {}
```

Read only when `buf.isReadable()`. Enforce endpoint count caps, nonempty host, port `1..65535`, unique endpoint ids, and exactly 16 token bytes. Do not alter pre-existing handshake field order.

- [ ] **Step 4: Write failing client demux test**

```java
@Test
void authenticatedUdpChunkUsesExistingClientChunkHandler() {
    AtomicReference<byte[]> handled = new AtomicReference<>();
    DataPlaneClientBundle bundle = bundleWithChunkConsumer(handled::set);
    bundle.receiveForTest(DataPlaneFrame.TYPE_BULK_COMPRESSED_CHUNK, new byte[] {9, 8});
    assertArrayEquals(new byte[] {9, 8}, handled.get());
}
```

- [ ] **Step 5: Implement the UDP client bundle and lifecycle**

```java
public final class DataPlaneClientBundle {
    public void connectAndBind(UUID playerId, long epoch, byte[] token, List<UdpEndpoint> endpoints);
    public void retainLeaseUntil(long deadlineMs);
    public void shutdown();
}
```

Use one client `NioDatagramChannel` per advertised endpoint, then one `ReliableDatagramSession` per endpoint. On decoded chunk payload, schedule `ClientChunkHandler.handleCompressedChunk(payload)` on the Minecraft main-thread dispatcher. `TYPE_BULK_SECTION_DELTA` is implemented and tested in Task 8 before the Fabric anchor smoke. Do not call client Minecraft APIs from Netty event loops.

Modify Fabric handshake send and receive in every current 1.20.1 conditional branch: C2S appends capability bits; server response appends S2C tail; client starts UDP only after accepted S2C tail.

- [ ] **Step 6: Run handshaking/client unit tests and Fabric compile**

Run: `./gradlew --no-daemon common:test --tests "*UdpDataPlaneHandshakeTailTest" --tests "*UdpClientBundleTest" "-Pmc_ver=1.20.1" && ./gradlew --no-daemon fabric:compileJava "-Pmc_ver=1.20.1"`

Expected: PASS; tail absence stays disabled and no old client code reads beyond its packet.

- [ ] **Step 7: Commit handshake and client UDP lifecycle**

```bash
git add common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/UdpDataPlaneHandshakeTail.java \
  common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneClientLifecycle.java \
  common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneClientBundle.java \
  fabric/src/main/java/io/github/limuqy/mc/hassium/network/FabricNetworkManager.java \
  common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/UdpDataPlaneHandshakeTailTest.java \
  common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/UdpClientBundleTest.java
git commit -m "feat: negotiate and bind UDP data-plane clients"
```

## Task 6: Keep the Data Plane Through TCP Master Loss and Implement Server-Permitted Failover

**Files:**
- Create: `common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/ControlFailoverHandler.java`
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneSessionRegistry.java`
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneUdpServer.java`
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/mixin/MixinServerGamePacketListenerImpl.java`
- Test: `common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/ControlFailoverHandlerTest.java`
- Test: `common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/UdpLeaseRoutingTest.java`

**Interfaces:**
- Consumes: `DataPlaneSessionRegistry.onPrimaryDisconnect`, active Minecraft `Connection` bound to `ServerPlayer`, authenticated KCP `TYPE_FAILOVER_REQUEST` frames.
- Produces: `ControlFailoverHandler.recordControlActivity(UUID, long epoch, long nowMs)`, `requestFailover(UUID, long epoch, int requestedEndpointId, long nowMs)`, `DataPlaneSessionRegistry.beginFailoverLease(UUID, long epoch, long expiryMs)`.

- [ ] **Step 1: Write failing failover authorization tests**

```java
@Test
void rejectsFailoverWhileControlMasterIsStillProgressing() {
    ControlFailoverHandler handler = handlerWithConnection(PLAYER, 7L);
    handler.recordControlActivity(PLAYER, 7L, 10_000L);

    assertEquals(FailoverResult.REJECTED_ACTIVE,
            handler.requestFailover(PLAYER, 7L, 2, 12_000L));
}

@Test
void stalledMasterIsClosedAndPermitIsIssued() {
    FakeConnection connection = new FakeConnection();
    ControlFailoverHandler handler = handlerWithConnection(PLAYER, 7L, connection);
    handler.recordControlActivity(PLAYER, 7L, 0L);

    assertEquals(FailoverResult.PERMITTED,
            handler.requestFailover(PLAYER, 7L, 2, 6_001L));
    assertTrue(connection.closed());
    assertEquals(1, handler.permits().size());
}
```

- [ ] **Step 2: Run the authorization test to prove failure**

Run: `./gradlew --no-daemon common:test --tests "*ControlFailoverHandlerTest" "-Pmc_ver=1.20.1"`

Expected: FAIL because `ControlFailoverHandler` does not exist.

- [ ] **Step 3: Implement server control-progress tracking and permits**

```java
public final class ControlFailoverHandler {
    public static final long DEFAULT_CONTROL_STALL_MS = 6_000L;
    public FailoverResult requestFailover(UUID playerId, long epoch,
                                          int requestedEndpointId, long nowMs);
    public void recordControlActivity(UUID playerId, long epoch, long nowMs);
    public void remove(UUID playerId);
}
```

Track the master `Connection`, player UUID, epoch, and last inbound control timestamp. `requestFailover` verifies matching authenticated UDP session, matching epoch, configured requested endpoint, and `now - lastControlActivity >= controlStallMs`; only then close the old connection, register a lease with `failoverPermitTtlMs`, and enqueue encrypted `TYPE_FAILOVER_PERMIT`. Do not infer TCP failure from UDP RTT.

Inject `recordControlActivity` at a server-side packet/tick hook that receives routine control traffic for the active `ServerGamePacketListenerImpl`; retain the existing disconnect hook but change it from `DataPlaneServer.onPrimaryDisconnect(uuid)` immediate deletion to `DataPlaneUdpServer.onPrimaryDisconnect(uuid, epoch)` lease creation. Version conditionals must cover all existing mixin method signatures.

- [ ] **Step 4: Write failing UDP lease test**

```java
@Test
void primaryDisconnectAllowsOnlyPreAcceptedFramesUntilLeaseExpires() {
    DataPlaneUdpServer server = serverWithSession(PLAYER, 5L);
    server.acceptFrameForTest(PLAYER, 5L, chunkFrame(new byte[] {1}));
    server.onPrimaryDisconnect(PLAYER, 5L, 100L);

    assertTrue(server.drainLeaseForTest(PLAYER, 500L));
    assertFalse(server.acceptFrameForTest(PLAYER, 5L, chunkFrame(new byte[] {2})));
    server.expireForTest(10_101L);
    assertTrue(server.sessionsForTest(PLAYER).isEmpty());
}
```

- [ ] **Step 5: Implement lease-drain semantics and run focused tests**

Run: `./gradlew --no-daemon common:test --tests "*ControlFailoverHandlerTest" --tests "*UdpLeaseRoutingTest" "-Pmc_ver=1.20.1"`

Expected: PASS; a lease drains accepted frames only, denies new player work, and closes all sessions on expiry.

- [ ] **Step 6: Commit master-loss transport continuity**

```bash
git add common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/ControlFailoverHandler.java \
  common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneSessionRegistry.java \
  common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneUdpServer.java \
  common/src/main/java/io/github/limuqy/mc/hassium/mixin/MixinServerGamePacketListenerImpl.java \
  common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/ControlFailoverHandlerTest.java \
  common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/UdpLeaseRoutingTest.java
git commit -m "feat: permit controlled TCP master failover over UDP"
```

## Task 7: Preserve Cache Infrastructure During Automatic Client Recovery

**Files:**
- Create: `common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/ClientRecoveryState.java`
- Create: `common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/ControlEndpoint.java`
- Create: `common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/ControlEndpointManager.java`
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/mixin/MixinMinecraft.java`
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/cache/client/ClientLifecycleHelper.java`
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/network/ClientMetadataHandler.java`
- Test: `common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/ClientRecoveryStateTest.java`
- Test: `common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/ControlEndpointManagerTest.java`

**Interfaces:**
- Consumes: existing `ClientLifecycleHelper.finalizeDisconnect()`, `ClientMetadataHandler.clearPendingState()`, negotiated `List<ControlEndpoint>` and `connectionEpoch`.
- Produces: `ClientRecoveryState.begin(long deadlineMs)`, `isRecovering()`, `markRecovered()`, `markTerminal()`, `ControlEndpointManager.mergeBootstrapAndAdvertised(...)`, `nextCandidate()`, `recordAttemptFailure(...)`.

- [ ] **Step 1: Write failing recovery-state cleanup tests**

```java
@Test
void recoverySkipsTerminalCleanupUntilFailure() {
    ClientRecoveryState state = new ClientRecoveryState();
    state.begin(10_000L);

    assertTrue(state.shouldSuppressFinalization());
    state.markTerminal();
    assertFalse(state.shouldSuppressFinalization());
    assertTrue(state.consumeTerminalCleanup());
    assertFalse(state.consumeTerminalCleanup());
}
```

- [ ] **Step 2: Run the recovery-state test to verify failure**

Run: `./gradlew --no-daemon common:test --tests "*ClientRecoveryStateTest" "-Pmc_ver=1.20.1"`

Expected: FAIL because `ClientRecoveryState` does not exist.

- [ ] **Step 3: Implement recovery state as a small atomic state machine**

```java
public final class ClientRecoveryState {
    public enum Phase { NONE, RECOVERING, RECOVERED, TERMINAL }
    public synchronized void begin(long deadlineMs);
    public boolean shouldSuppressFinalization();
    public synchronized void markRecovered();
    public synchronized void markTerminal();
    public synchronized boolean consumeTerminalCleanup();
}
```

Modify `MixinMinecraft` to call a new `ClientLifecycleHelper.finalizeDisconnectIfTerminal()` rather than unconditional finalization. That helper returns without closing `ClientHassiumStorage`, `CacheSaveQueue`, `HassiumTaskExecutor`, or clearing dirty/cache state while `ClientRecoveryState.isRecovering()` is true. It preserves the existing one-time `AtomicBoolean` terminal finalizer for normal logout and recovery exhaustion.

Do not retain the old `ClientLevel`; vanilla may destroy it. `ClientMetadataHandler` may clear transient pending packet maps for the old connection but MUST NOT delete disk-backed hashes/storage during recovery.

- [ ] **Step 4: Write failing endpoint manager priority and exhaustion tests**

```java
@Test
void userEndpointsWinAndAdvertisedDuplicatesAreRemoved() {
    ControlEndpointManager manager = new ControlEndpointManager();
    manager.mergeBootstrapAndAdvertised(
            List.of(endpoint("a.com", 25565, 100)),
            List.of(endpoint("b.com", 25565, 80), endpoint("a.com", 25565, 1)));

    assertEquals(endpoint("a.com", 25565, 100), manager.nextCandidate());
    manager.recordAttemptFailure(endpoint("a.com", 25565, 100));
    assertEquals(endpoint("b.com", 25565, 80), manager.nextCandidate());
}

@Test
void managerStopsWhenDeadlineOrCandidatesExhausted() {
    ControlEndpointManager manager = managerWithOneCandidate();
    manager.startRecovery(1_000L);
    manager.recordAttemptFailure(manager.nextCandidate());
    assertTrue(manager.nextCandidate().isEmpty());
}
```

- [ ] **Step 5: Implement endpoint validation, merge, and retry selection**

```java
public record ControlEndpoint(String host, int port, int priority) {
    public ControlEndpoint { /* host nonblank; port 1..65535; priority >= 0 */ }
}

public final class ControlEndpointManager {
    public void mergeBootstrapAndAdvertised(List<ControlEndpoint> bootstrap,
                                             List<ControlEndpoint> advertised);
    public void startRecovery(long deadlineMs);
    public Optional<ControlEndpoint> nextCandidate();
    public void recordAttemptFailure(ControlEndpoint endpoint);
    public void markConnected(ControlEndpoint endpoint);
}
```

Use a maximum of four control candidates. Do not mutate the vanilla multiplayer-server address. Persist advertised candidates only in Hassium metadata keyed to the current multiplayer entry after a successful handshake.

- [ ] **Step 6: Run client recovery unit tests**

Run: `./gradlew --no-daemon common:test --tests "*ClientRecoveryStateTest" --tests "*ControlEndpointManagerTest" "-Pmc_ver=1.20.1"`

Expected: PASS; automatic recovery retains only intended cache infrastructure and candidate retry order is deterministic.

- [ ] **Step 7: Commit recovery/cache foundations**

```bash
git add common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/ClientRecoveryState.java \
  common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/ControlEndpoint.java \
  common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/ControlEndpointManager.java \
  common/src/main/java/io/github/limuqy/mc/hassium/mixin/MixinMinecraft.java \
  common/src/main/java/io/github/limuqy/mc/hassium/cache/client/ClientLifecycleHelper.java \
  common/src/main/java/io/github/limuqy/mc/hassium/network/ClientMetadataHandler.java \
  common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/ClientRecoveryStateTest.java \
  common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/ControlEndpointManagerTest.java
git commit -m "feat: preserve client cache during control recovery"
```

## Task 8: Carry Section Delta Through the UDP Data Plane

**Files:**
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneUdpServer.java`
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneClientBundle.java`
- Modify: `fabric/src/main/java/io/github/limuqy/mc/hassium/HassiumMod.java`
- Test: `common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/UdpSectionDeltaDispatchTest.java`

**Interfaces:**
- Consumes: `SectionDeltaS2CPacket.encode(FriendlyByteBuf)`, `SectionDeltaS2CPacket.decode(FriendlyByteBuf)`, `ClientMetadataHandler.handleSectionDeltaPacket(SectionDeltaS2CPacket)`, and `DataPlaneUdpServer.tryRouteBulk(UUID, int, byte[])`.
- Produces: S2C `TYPE_BULK_SECTION_DELTA` dispatch on the same main-thread path as the existing Primary receiver.

- [ ] **Step 1: Write the failing end-to-end dispatch test**

```java
@Test
void authenticatedUdpSectionDeltaDecodesAndUsesExistingMetadataHandler() {
    AtomicReference<SectionDeltaS2CPacket> handled = new AtomicReference<>();
    DataPlaneClientBundle bundle = bundleWithSectionDeltaConsumer(handled::set);
    SectionDeltaS2CPacket original = fixtureDeltaPacket();
    FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer());
    original.encode(out);

    bundle.receiveForTest(DataPlaneFrame.TYPE_BULK_SECTION_DELTA, bytes(out));

    assertEquals(original, handled.get());
}

@Test
void sectionDeltaUsesUdpWhenHealthyAndPrimaryWhenNoSessionExists() {
    DataPlaneUdpServer server = serverWithBoundSession(healthySession());
    assertTrue(server.tryRouteBulk(PLAYER, DataPlaneFrame.TYPE_BULK_SECTION_DELTA,
            encodedFixtureDelta()));
    server.removeSessions(PLAYER);
    assertFalse(server.tryRouteBulk(PLAYER, DataPlaneFrame.TYPE_BULK_SECTION_DELTA,
            encodedFixtureDelta()));
}
```

- [ ] **Step 2: Run the test to prove it fails**

Run: `./gradlew --no-daemon common:test --tests "*UdpSectionDeltaDispatchTest" "-Pmc_ver=1.20.1"`

Expected: FAIL because `DataPlaneClientBundle` does not dispatch type 4 and server routing has no section-delta coverage.

- [ ] **Step 3: Implement byte-boundary routing and main-thread decode**

```java
private void dispatchReceived(int type, byte[] payload) {
    if (type == DataPlaneFrame.TYPE_BULK_COMPRESSED_CHUNK) {
        mainThread.execute(() -> ClientChunkHandler.handleCompressedChunk(payload));
        return;
    }
    if (type == DataPlaneFrame.TYPE_BULK_SECTION_DELTA) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(payload));
        SectionDeltaS2CPacket packet = SectionDeltaS2CPacket.decode(buf);
        mainThread.execute(() -> ClientMetadataHandler.handleSectionDeltaPacket(packet));
        return;
    }
    closeUnexpectedType(type);
}
```

At the existing Fabric section-delta send byte boundary, call `DataPlaneUdpServer.tryRouteBulk(player.getUUID(), TYPE_BULK_SECTION_DELTA, encodedPayload)` first and retain the existing Primary send when it returns `false`. Do not change `ServerChunkPushManager`.

- [ ] **Step 4: Run the focused test and Fabric compile**

Run: `./gradlew --no-daemon common:test --tests "*UdpSectionDeltaDispatchTest" "-Pmc_ver=1.20.1" && ./gradlew --no-daemon fabric:compileJava "-Pmc_ver=1.20.1"`

Expected: PASS; section deltas use UDP only with a bound healthy session and otherwise execute the unchanged Primary path.

- [ ] **Step 5: Commit section-delta transport support**

```bash
git add common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneUdpServer.java \
  common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneClientBundle.java \
  fabric/src/main/java/io/github/limuqy/mc/hassium/HassiumMod.java \
  common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/UdpSectionDeltaDispatchTest.java
git commit -m "feat: deliver section deltas over UDP data plane"
```

## Task 9: Drive Fabric Automatic Reconnect and Validate the 1.20.1 End-to-End Path

**Files:**
- Modify: `fabric/src/main/java/io/github/limuqy/mc/hassium/HassiumClientMod.java`
- Modify: `fabric/src/main/java/io/github/limuqy/mc/hassium/network/FabricNetworkManager.java`
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/client/ClientSmokeTest.java`
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/server/ServerSmokeTest.java`
- Modify: `scripts/runtime-smoke-test.ps1`
- Create: `common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/ControlReconnectOrchestrator.java`
- Create: `fabric/src/main/java/io/github/limuqy/mc/hassium/client/FabricControlReconnectLauncher.java`
- Test: `common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/ControlReconnectOrchestratorTest.java`

**Interfaces:**
- Consumes: `ClientRecoveryState`, `ControlEndpointManager`, `DataPlaneClientLifecycle`, `UdpDataPlaneHandshakeTail`, Fabric standard server-connect entry point.
- Produces: `ControlReconnectOrchestrator.onPrimaryDisconnected(...)`, `onFailoverPermit(...)`, `onHandshakeAccepted(...)`, `onReconnectExhausted(...)`.

- [ ] **Step 1: Write failing reconnect orchestrator tests against a connection launcher seam**

```java
@Test
void hardPrimaryCloseLaunchesNextCandidateWithoutTerminalCleanup() {
    RecordingLauncher launcher = new RecordingLauncher();
    ControlReconnectOrchestrator orchestrator = orchestrator(launcher,
            endpoints("a.com", "b.com"));

    orchestrator.onPrimaryDisconnected("a.com", 25565, "closed");

    assertEquals(List.of(endpoint("b.com", 25565, 80)), launcher.launched());
    assertTrue(orchestrator.recoveryState().isRecovering());
}

@Test
void exhaustedCandidatesPerformsOneTerminalFinalization() {
    RecordingLauncher launcher = new RecordingLauncher();
    ControlReconnectOrchestrator orchestrator = orchestrator(launcher, endpoints("a.com"));

    orchestrator.onPrimaryDisconnected("a.com", 25565, "closed");
    orchestrator.onReconnectFailed(endpoint("a.com", 25565, 100));

    assertEquals(1, orchestrator.terminalFinalizations());
}
```

- [ ] **Step 2: Run the orchestrator test to prove failure**
Run: `./gradlew --no-daemon common:test --tests "*ControlReconnectOrchestratorTest" "-Pmc_ver=1.20.1"`

Expected: FAIL because the orchestrator/launcher seam does not exist.

- [ ] **Step 3: Implement Fabric reconnect only through a testable launcher adapter**
```java
public interface ControlReconnectLauncher {
    void connect(ControlEndpoint endpoint, Runnable onFailure);
}

public final class ControlReconnectOrchestrator {
    public void onPrimaryDisconnected(ControlEndpoint active, String reason);
    public void onFailoverPermit(long epoch, long expiryMs);
    public void onHandshakeAccepted(UdpDataPlaneHandshakeTail.S2CTail tail);
    public void onReconnectFailed(ControlEndpoint endpoint);
}
```

`ControlReconnectOrchestrator` is common and only calls the injected `ControlReconnectLauncher`; its unit test uses `RecordingLauncher` under `common:test`. `FabricControlReconnectLauncher` is the one Fabric-specific adapter and calls the existing Fabric multiplayer connect invocation; it must not reflectively duplicate login protocol. On hard channel close, it immediately begins recovery. On `FailoverPermit`, it begins recovery only if epoch matches and the permit has not expired. During recovery it shows a recovery-only screen/overlay, not the final disconnect UI. Once handshake succeeds, it calls `ClientRecoveryState.markRecovered()` and starts fresh UDP sessions with the new epoch/token.

- [ ] **Step 4: Extend smoke control for the new assertions**

Add a `UdpFailoverPhase` that:

```text
1. Starts server with three UDP endpoint ports and at least two TCP candidates.
2. Connects through TCP candidate A and verifies at least two KCP binds.
3. Forces a chunk bulk transfer and asserts data bytes on UDP endpoints.
4. Closes master TCP A from the smoke server while retaining UDP lease.
5. Asserts client launches TCP candidate B, receives accepted handshake, and binds a new epoch of UDP sessions.
6. Asserts a post-reconnect ChunkHashS2C causes at least one cache hit and does not open final disconnect UI.
7. Forces all TCP candidates to fail and asserts exactly one terminal cleanup plus visible terminal failure.
8. Re-runs with `network.dataPlane.enabled=false` and asserts no UDP listener/bind/failover behavior.
```

The smoke should use explicit log markers: `UDP_BIND_OK`, `UDP_WRR_OK`, `FAILOVER_PERMIT_OK`, `FAILOVER_RECONNECT_OK`, `CACHE_RESUME_HIT`, and `FAILOVER_TERMINAL_OK`.

- [ ] **Step 5: Run unit tests, compile, then execute the Fabric smoke phase**

Run:

```bash
./gradlew --no-daemon common:test --tests "*ControlReconnectOrchestratorTest" "-Pmc_ver=1.20.1"
./gradlew --no-daemon common:compileJava fabric:compileJava "-Pmc_ver=1.20.1"
powershell -ExecutionPolicy Bypass -File ./scripts/runtime-smoke-test.ps1 -Loader fabric -McVer 1.20.1 -Phase UdpFailover
```

Expected: unit test pass, both compile tasks pass, and smoke contains all six success markers without a terminal disconnect before the intentional exhaustion subcase.

- [ ] **Step 6: Commit the Fabric anchor and smoke proof**

```bash
git add fabric/src/main/java/io/github/limuqy/mc/hassium/HassiumClientMod.java \
  fabric/src/main/java/io/github/limuqy/mc/hassium/network/FabricNetworkManager.java \
  fabric/src/main/java/io/github/limuqy/mc/hassium/client/FabricControlReconnectLauncher.java \
  common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/ControlReconnectOrchestrator.java \
  common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/ControlReconnectOrchestratorTest.java \
  common/src/main/java/io/github/limuqy/mc/hassium/client/ClientSmokeTest.java \
  common/src/main/java/io/github/limuqy/mc/hassium/server/ServerSmokeTest.java \
  scripts/runtime-smoke-test.ps1
git commit -m "feat: recover Fabric control connection through backup endpoint"
```

## Task 10: Remove TCP Data-Plane Leftovers, Expand Coverage, and Document Operations

**Files:**
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneServer.java` or delete after all callers migrate
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneCodec.java` or delete after all callers migrate
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/VarIntLengthFrameSplitter.java` or delete if no non-test references remain
- Modify: `common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/*Test.java`
- Modify: `docs/chunk-cache.md`
- Modify: `docs/architecture.md`
- Modify: `docs/version-segments.md`
- Modify: `docs/runtime-smoke-test.md`

**Interfaces:**
- Consumes: all prior tasks and 1.20.1 Fabric smoke output.
- Produces: no TCP data-plane runtime implementation; documented UDP ports, endpoint configuration, failure semantics, and smoke markers.

- [ ] **Step 1: Write the failing absence test before deleting transitional TCP transport**

```java
@Test
void dataPlaneRuntimeUsesNoTcpServerBootstrap() {
    assertFalse(DataPlaneUdpServer.runtimeTransportNamesForTest()
            .stream().anyMatch(name -> name.contains("NioServerSocketChannel")));
}
```

- [ ] **Step 2: Run it to confirm it fails while the old transport remains reachable**

Run: `./gradlew --no-daemon common:test --tests "*DataPlaneTransportCutoverTest" "-Pmc_ver=1.20.1"`

Expected: FAIL until the last TCP data-plane listener reference is removed.

- [ ] **Step 3: Remove obsolete TCP-only classes and tests only after migrating all callers**

Use LSP references for `DataPlaneServer`, `DataPlaneCodec`, `PlayerChannel`, and `VarIntLengthFrameSplitter`. Migrate every production caller to `DataPlaneUdpServer`/`UdpFrameCodec` first. Delete each old class only when LSP reports no production references. Keep pure `DataPlaneFrame` helpers only if still shared by UDP logical frames.

- [ ] **Step 4: Update operational documentation with concrete behavior**

Document:

```text
- Every [[network.dataPlane.udpEndpoints]] entry needs a public UDP firewall/NAT rule.
- TCP control endpoints and UDP endpoints are separate lists and may have different public ports.
- A TCP failure re-logins through cold backup addresses; it is not server-side player-state handoff.
- During a 10-second UDP lease only in-flight data drains; no new player data is produced until login completes.
- controlStallMs requires a server-issued FailoverPermit; clients never create a second live Play master due to latency alone.
- UdpFailover smoke markers and the disabled-mode expectation.
```

- [ ] **Step 5: Run the complete anchor verification**

Run:

```bash
./gradlew --no-daemon common:test "-Pmc_ver=1.20.1"
./gradlew --no-daemon common:compileJava fabric:compileJava "-Pmc_ver=1.20.1"
powershell -ExecutionPolicy Bypass -File ./scripts/runtime-smoke-test.ps1 -Loader fabric -McVer 1.20.1 -Phase UdpFailover
```

Expected: all common tests pass, both compiles pass, smoke passes both enabled and disabled subcases. Do not proceed to NeoForge/version rollout until this evidence exists.

- [ ] **Step 6: Commit cleanup and docs**

```bash
git add common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane \
  common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane \
  docs/chunk-cache.md docs/architecture.md docs/version-segments.md docs/runtime-smoke-test.md
git commit -m "refactor: complete UDP data-plane transport cutover"
```

## Spec Coverage Review

- §1 Goal: Tasks 3-9 replace TCP data-plane transport, retain TCP control, negotiate endpoints, and execute a backup reconnection.
- §2 Boundaries: Tasks 6-9 use bounded lease plus normal login; Task 7 preserves disk infrastructure only, not `ClientLevel`.
- §3 Topology: Task 3 binds each configured UDP endpoint; Task 5 advertises UDP/control lists; Task 9 exercises multiple candidates.
- §4 Discovery: Task 7 manages bootstrap plus advertised control endpoints without rewriting the vanilla server address.
- §5 Transport/security: Tasks 1-2 cover KCP, fragmentation, backpressure, AES-GCM, HKDF, and replay rejection.
- §6 Election: Task 6 covers hard disconnect and server-permitted stall failover; Task 8 covers client retry/exhaustion.
- §7 Cache recovery: Task 7 gates finalization and keeps disk cache; Task 8 confirms post-login hash cache hit.
- §8 Routing: Task 4 retains share/exclusive semantics with health-aware UDP WRR.
- §9 Handshake: Task 5 implements append-only C2S/S2C tails and compatibility behavior.
- §10 boundaries: Tasks 1-8 create every named component and preserve the `tryRouteBulk` call contract.
- §11 config: Task 3 adds endpoint/token lifecycle configuration; Task 9 documents operations.
- §12 verification: Tasks 1-9 carry focused tests plus the 1.20.1 Fabric end-to-end smoke.
- §13 non-goals: no Forge, no player-state handoff, no `MixinConnection` routing, no QUIC migration task.

## Plan Self-Review

- Placeholder scan completed: no TBD/TODO/“implement later” steps remain; each task gives exact files, interfaces, test cases, commands, and commit scope.
- Type consistency: `ReliableDatagramSession`, `DataPlaneSessionRegistry`, `DataPlaneUdpServer`, `UdpBulkRouter`, `ClientRecoveryState`, `ControlEndpointManager`, and `ControlFailoverHandler` names/signatures are introduced before use.
- Dependency boundary: only `ReliableDatagramSession` speaks KCP-Netty. Router, registry, handshake codecs, loader code, and Minecraft hooks do not reference KCP vendor APIs.
- Migration safety: deletion occurs last and only after LSP production-reference checks; no runtime dual TCP/UDP fallback is retained.
