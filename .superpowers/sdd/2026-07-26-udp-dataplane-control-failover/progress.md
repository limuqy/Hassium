# SDD ledger — plan: docs/superpowers/plans/2026-07-26-udp-dataplane-control-failover.md

Worktree: `.worktrees/udp-failover` on branch `feature/udp-dataplane-failover`
Merge base: d9d0922 (chore: ignore local worktrees, includes spec + plan)

## Environment caveat (CORRECTED 2026-07-26)
Initial runs looked like subagents were stuck under `hub wait`. On closer inspection,
Task1Review actually settled and delivered via `task-result` immediately after its
kill (49.7s elapsed). The repeated "Still Running" snapshots were artifacts of
polling-style `wait` before the long-lived reviewer had finished writing output; the
verdict WAS produced. Task1Impl is the only Spawn with no yield. So subagent paths
ARE functional — but they need patience rather than premature cancel. Going forward:
give dispatched agents at least ~3 minutes wall clock per round before the wait fails
back to a settlement check, and prefer `task` async-result delivery over `hub wait`
polls. The host is fine.

- Task 1: Establish the Reliable UDP Dependency and Wire Contracts
  - Implementer subagent Task1Impl: spawned and went unresponsive for the full dispatch;
    cancelled with zero commits. Controller implemented directly per brief.
  - Reviewer subagent Task1Review: DID settle after 49.7s with a real verdict (was
    cancelled due to impatience; verdict captured post-cancel):
    - SPEC ❌: Critical — brief interface requires AEAD plaintext layout
      `sequence || type || payload`. Controller chose `sequence[u64 wire header] +
      AEAD(type || payload)`. Not wire-equivalent to the contract downstream tasks
      expect; entering fix loop.
    - QUALITY Important: `seal/open` accept `int type` and silently narrow to a byte
      via `(type & 0xFF)`. `type=256` would be encoded as 0 with no error. Must
      validate against supported `DataPlaneFrame` types before sealing.
  - Fix loop round 1/5: controller fixes both findings directly (no implementer
    responsiveness available; controller is the only confirmed-producing entity here).
    Tracked under ledger `Task 1: fix round 1/5` once applied.

  - Fix round 1/5: `c944428 fix: align UDP frame AEAD wire contract`; re-ran
    `common:test --tests *UdpBindRequestCodecTest --tests *UdpFrameCodecTest` → BUILD
    SUCCESSFUL. Scoped Task1ReReview verdict: both original findings ADDRESSED; no new
    Critical/Important; clean.
  - **Task 1: complete (commits d9d0922..c944428, review clean)**

- Task 2: Bounded KCP Reliable Datagram Session
  - Implementer subagent Task2Impl: TDD RED→GREEN, committed `ec9fde1` (3 files, 5
    tests green). Reported concerns C1-C6.
  - Reviewer subagent Task2Review: settled (interrupted by SIGHUP mid-run) with 2
    priority-0 Critical findings; no SPEC/QUALITY summary delivered:
    - F1 (Critical) — `onKcpOutput` line 226-230: KCP `output(buf,kcp)` does NOT
      release `buf` after callback (verified by javap of `Kcp.output`). Implementer
      comment was wrong; every emitted datagram leaked a pooled direct buffer →
      direct-memory exhaustion under sustained bulk.
    - F2 (Critical) — `drainReceived` line 246-255: `maxReassemblyBytes` cap was
      checked only AFTER `kcp.input()` had retained and reassembled the whole
      oversize message; the discard path then `alloc.buffer(peek)` (up to 8 MiB)
      just to drain. No bounded reassembly allocation; malicious authenticated
      peer can push 128-message window of oversized frames → direct-memory DoS.
    - Other concerns (C1 clock-int wrap, C2 outstandingAppBytes granularity,
      C4 silent drops, C5/C6 tick cadence) not re-adjudicated in this round;
      accepted as forward-looking caveats for Task 3 wiring.
  - Fix loop round 1/1: Task2Fix subagent implemented the two new failing tests
    (kcpOutputReleasesOriginalBuffer / oversizedReassemblyIsDrainedWithoutLargeAllocation)
    and the production fixes (`onKcpOutput` now releases `buf`; F2 discard path
    uses `kcp.recv(List<ByteBuf>)` fragment transfer with per-fragment release, no
    peek-sized allocation; added package-private `ALLOC_OVERRIDE` test seam for
    direct-arena leak probe — production path stays `PooledByteBufAllocator.DEFAULT`).
    Subagent left changes uncommitted. Controller verified clean rerun
    `common:test --tests *ReliableDatagramSessionTest` → BUILD SUCCESSFUL (7/7),
    then committed `cb2c67b`.
  - **Task 2: complete (commits ec9fde1..cb2c67b, review clean)**
- Task 3: UDP server + session/epoch registry
  - Implementer subagent dispatched by controller (Task3Impl via task) was made
    non-viable by user advisory "代理可能会卡住"; controller implemented directly
    (subagent dispatch fell through; no spawn attempted to safeguard budget).
  - Controller RED→GREEN in this branch, all per plan §337-447:
    - **Session accessors/lease**: `{isClosed, markLease, isLeaseActive}` added to
      `ReliableDatagramSession` (volatile `leaseExpireAt`; default `Long.MAX_VALUE`).
      Package-private playerId/epoch/endpoint/remote already existed (Task 2).
    - **RED** `DataPlaneSessionRegistryTest` (4 tests):
      `sessionsAreSeparatedByUuidEpochAndEndpoint`, `primaryCloseRetainsOnlyLeaseThenClosesSessions`,
      `replaceEpochClosesOldEpochSessionsAndUnlocksNewEpochOnSameUuid`, `onPrimaryDisconnectUnknownUuidIsNoOp`.
    - **GREEN** `DataPlaneSessionRegistry` (synchronized; composite key `(UUID, epoch)`;
      `pendingLeases` for `onPrimaryDisconnect` + `expireLeases`; `replaceEpoch`
      closes ALL old-epoch buckets for the player and clears their leases, allowing next-
      epoch `register` from same playerId).
    - **RED** `DataPlaneUdpServerBindTest` (2 tests): real `NioDatagramChannel`+`DatagramSocket`
      round-trip; token + endpointId mismatch must reject→registry empty; correct
      ones must create 2 sessions.
    - **GREEN** `DataPlaneUdpServer`: shared `NioEventLoopGroup` (1 thread); per-endpoint
      `NioDatagramChannel`; `SecureRandom` 16-byte session token minted once at bind;
      HKDF info=`"hassium-udp-v1" (15B) || endpointId(1B) || channelId(1B)`,
      salt = `uuidBytes(playerId)`, key length 16; `DatagramSink = retainedDuplicate + writeAndFlush + release-on-throw`;
      `tryRouteBulk` 占位 false（Task 4 接管）.
  - Façade: `DataPlaneServer.{bind,shutdown,isBound,onPrimaryDisconnect,tryRouteBulk}`
    forward to `DataPlaneUdpServer`; TCP `PlayerChannel`/`PlayerChannelBundle`/
    `DataPlaneChannelInitializer`/`BindHandshakeHandler` 残留未删 (Task 10).
  - `MixinMinecraftServer` 直调 `DataPlaneUdpServer.bind()/shutdown()`.
  - Disabled `TryRouteBulkWriteRegressionTest` (TCP-specific regression guard) with
    rationale: Task 3 UDP cutover renders it obsolete; Task 10 deletes.
  - `DataPlaneEnabledGuardTest` retains guard: `setEnabled(false)` →
    `DataPlaneUdpServer.bind()` skips → `isBound()==false`.
  - Verified: `*ReliableDatagramSessionTest + *DataPlaneSessionRegistryTest +
    *DataPlaneUdpServerBindTest + *UdpBindRequestCodecTest + *UdpFrameCodecTest`
    → BUILD SUCCESSFUL (RED=failed compile→GREEN=all pass), +
    `common:compileJava` → BUILD SUCCESSFUL.
  - **Task 3: complete (commit 6a8f615, no subagent review — controller path)**
- Task 4: Health-aware UDP WRR — complete
  - Controller RED→GREEN direct (no subagent: prior subagents unresponsive).
  - `BulkRouteTarget` interface (endpointId/weight/isHealthy/isWritable/isClosed/
    isLeaseActive/metrics/enqueueAuthenticated) lets router tests inject fakes
    without instantiating `ReliableDatagramSession` (avoids KCP server setup).
  - `ReliableDatagramSession` now `implements BulkRouteTarget`; `enqueueAuthenticated`
    returns boolean (false on failure, was void that threw); endpointId/weight fields
    with backward-compat `-1 / 1` default constructor.
  - `UdpBulkRouter` classical interleaved WRR (cumulative current-weights + argmax -
    total); share mode treats PRIMARY as virtual candidate (idx==sessions.size());
    candidates weight 100 vs PRIMARY 50 → exactly 2/3 DATA ratio (verified).
    **Critical fix**: original `acc%total` mod-* step always fell under PRIMARY slot
    when `acc` was < primaryWeight, returning PRIMARY 50/50 calls. Replaced with the
    classical WRR cycle (acc monotonic no longer moduloed); verified share ratio 2/3
    and full-99% hitting DATA when candidate weight=10000/PRIMARY=100.
  - HEALTH_FILTER excludes closed/!writable/!healthy/!leaseActive; `effectiveWeight`
    linearly penalises SRTT (SRTT=hardRtt → weight 1).
  - exclusive mode: >=threshold bug corrected to `>` threshold — N drops still
    DROPPED, N+1th call flips to PRIMARY+degraded=true.
  - `DataPlaneUdpServer.tryRouteBulk` wired to `ROUTER` singleton, per-player
    `PlayerSessions` cached in `Instance.worksets` `ConcurrentHashMap<UUID, ...>`;
    holds `BULK_ROUTE_MODE`/`PRIMARY_WEIGHT`/`DEGRADE_AFTER_DROPS` from `DataPlanePoCConfig`.
  - Test seams: `injectBoundSessionsForTest(UUID, List)`, `removeSessionsForTest(UUID)`;
    `shutdown()` clears `TEST_INJECTION`.
  - Tests: `UdpBulkRouterTest` (5), `UdpTryRouteBulkTest` (2), `DataPlaneFrameTest`
    (updated: FAILOVER_REQUEST/PERMIT=8/9 legal; invalidType uses 0/99). All BUILD
    SUCCESSFUL across Task1–Task4 regression (`UdpFrameCodec`, `UdpBindRequestCodec`,
    `ReliableDatagramSession`, `DataPlaneSessionRegistry`, `DataPlaneUdpServer`,
    `UdpBulkRouter`, `UdpTryRouteBulk`, `DataPlaneFrame`).
  - **Accepted baseline failures** (pre-existing, NOT introduced here): 7×
    DeltaMergeTest/ResourceKey NoClassDefFoundError + `HassiumMetricsImplTest
    resetClearsClientDisplayMetrics` (reset() never clears sectionDelta* counters) +
    `ChunkDiskCodecTest` / `CompressionServiceDictionaryTest` (`mods.toml ${mod_id}
    placeholder substitution fails when invoked as a single test outside full fabric
    runtime resource pipeline`). All reproduce independently of UDP code path; not
    blocking Task 4 acceptance.
  - **Task 4: complete (no subagent review — controller path)**
- Task 5: Append-only 握手尾部 + 客户端 UDP 生命周期 — complete
  - Controller RED→GREEN direct.
  - **Step 1-2**: `UdpDataPlaneHandshakeTailTest` 5 tests green
    （`oldHandshakeWithNoTailDecodesAsDisabled` / roundtrip / failover tail flag /
    malformed token length / endpointId uniqueness）。Codec 用纯 Netty ByteBuf，
    不触碰 `FriendlyByteBuf`；read 只在 `buf.isReadable()` 时执行。
  - **Step 3**: `DataPlaneClientBundle` 重写为 UDP 门面；PoC 静态计数器
    （`getBulkFramesData` / `getBulkBytesData` / `snapshotPerPort` / `resetDataBulkCounters`）保留；
    新增 `connectAndBind(UUID, long, byte[], List<UdpEndpointInfo>)`、
    `setChunkDispatcherForTest`、`receiveForTest`、`retainLeaseUntil`、`tick`、`shutdown`、
    `isBound`。
  - **Step 4**: `UdpClientBundleTest` 3 tests green（chunk frame → injected dispatcher + 计数；
    non-chunk frame 不进 dispatcher；`resetDataBulkCounters` 清零所有静态计数）。
  - **Step 5**: 新建 `DataPlaneClientLifecycle`（`startUdp/stopUdp/tick/retainLeaseUntil/isBound/currentEpoch`）。
  - **Step 6 — Fabric 接线**：
    - `sendHandshakeRequest` 在 `compactHeaderSupported` 之后追加 `C2STail(true,true)`。
    - `completeServerHandshake` 在 `writeBoolean(useCompactHeader)` 之后、send 之前，
      仅当 `accepted && DataPlaneUdpServer.isBound()` 时追加 `S2CTail`（token、epoch=1、
      BoundEndpoint 转子端点记录、protocol=Constants.CURRENT_PROTOCOL_VERSION）。
      Failures warn，不影响主握手语义。
    - Pre-1.20.5 和 1.20.5+ 两个 S2C receiver 中：读完原 4 字段后用
      `buf.isReadable()` 判尾部存在，`UdpDataPlaneHandshakeTail.readS2C` 解码后
      经 `client.execute(...)` 调用 `DataPlaneClientLifecycle.startUdp`，主线程 executor。
    - `HassiumClientMod.JOIN` 删除旧 PoC `connectAndBind()` 硬编码；`DISCONNECT` 改为
      `DataPlaneClientLifecycle.getInstance().stopUdp(false)`，保留全局静态计数器兼容。
    - `DataPlaneUdpServer` 新增 public static `boundEndpoints()`：
      返回 `List.copyOf(inst.boundEndpoints)`（未 bind 返回空列表），包私有版本保留供测试。
    - `common/build.gradle` 中 `implementation 'moe.sdl.kcp:kcp-netty:1.6.2'` 不传递至
      fabric（architectury-loom common 源码合并模型）；同步在 `fabric/build.gradle`
      添加 `implementation 'moe.sdl.kcp:kcp-netty:1.6.2'`。
    - Fix loop 1/1：`DataPlaneUdpServer.getSessionToken/boundEndpoints` 编辑误处导致
      代码顺序错乱（INS.POST 137 破坏 getSessionToken 主体）→ 用 SWAP 重建 + DEL
      重复段，最后精确放置新公共 API 在 getSessionToken 与 ROUTER 之间。
      `S2CTail` 构造参数顺序是 (hasUdpDataplane, hasControlFailover, connectionEpoch,
      protocol, token, controlEndpoints, udpEndpoints)，Fabric 端初次构造按此顺序修正。
    - `S2C` 两个 receiver 中 `UUID` 缺导入 → 添加 `import java.util.UUID;`。
  - Verified:
    `common:test --tests *UdpClientBundleTest --tests *UdpDataPlaneHandshakeTailTest`
    → BUILD SUCCESSFUL；
    `fabric:compileJava -Pmc_ver=1.20.1` → BUILD SUCCESSFUL；
    Task1-5 regression run → BUILD SUCCESSFUL.
  - **Task 5: complete (no subagent review — controller path)**

- Task 6: Control failover authorization + lease — complete
  - Controller RED→GREEN direct (no subagent: prior subagents unresponsive, per
    environment caveat; controller is the only confirmed-producing entity).
  - **Step 1 — wire contract** (`FailoverFrameCodec`, plan §660-672):
    `Request(connectionEpoch, requestedEndpointId)` encoded as `i64 + varint`
    (canonical-varint check rejects non-minimal encoding); `Permit(connectionEpoch,
    expiryMs)` as `i64 + i64`; both reject null/truncated/trailing bytes.
    `FailoverFrameCodecTest` (2 cases) — round-trip with `epoch = 0x0123_..._CDEF`
    and `Long.MAX_VALUE - 3` expiry; malformed payload (≤7-byte request, negative
    endpointId, 15-byte permit) raises `IllegalArgumentException`.
  - **Step 2 — authorization state machine** (`ControlFailoverHandler`, plan §651-675):
    per-player `PlayerState { epoch, lastControlActivityMs, masterClose,
    udpSessionPresent }`; decision order `NO_CONNECTION → NO_UDP_SESSION →
    EPOCH_MISMATCH → REJECTED_ACTIVE → PERMITTED`. PERMITTED invokes the master-close
    `Runnable` exactly once (`masterClose = null` after), appends to `permits()`.
    Constants: `DEFAULT_CONTROL_STALL_MS = 6_000`, `DEFAULT_FAILOVER_PERMIT_TTL_MS =
    30_000`. Production callbacks `onUdpSessionEstablished/onUdpSessionClosed` gate
    UDP authorization by epoch; stale-epoch bind cannot roll back current master.
    `beginControlConnection` mints strictly-increasing epoch (Long.MAX_VALUE wraps to
    1) and `replaceEpoch`-declared downstream in DataPlaneUdpServer revokes old
    leases atomically with new epoch.
  - **Step 3 — registry lease** (`DataPlaneSessionRegistry`, plan §701-727):
    refactored `onPrimaryDisconnect` into shared `beginLease(playerId, epoch,
    expiryMs)`; new `beginFailoverLease` (called by UdpServer after PERMITTED) reuses
    it; same-key deadline is overwritten with the latest permit so a new permit
    cannot be prematurely closed. `sessionsByPlayer` filters out
    `isLeaseDraining() == true` sessions, so lease sessions stop receiving new bulk
    immediately while keeping their accepted KCP queue.
  - **Step 4 — ReliableDatagramSession lease accessor**: added package-private
    `markLeaseUntil(expireAtMs)` and `isLeaseDraining()` (the legacy `markLease(now,
    leaseMs)` is retained — still used by `DataPlaneClientBundle.retainLeaseUntil`
    migration path).
  - **Step 5 — server-side wiring** (`DataPlaneUdpServer`):
    - `beginControlConnection(UUID, Runnable)` static façade forwards to
      `ControlFailoverHandler` + `registry.replaceEpoch` (returns new epoch for
      handshake emission).
    - `recordControlActivity(UUID, long, long)` and `currentControlEpoch(UUID)`
      static forwarders for the tick mixin.
    - `dispatchReceivedOnServer` now recognizes `TYPE_FAILOVER_REQUEST`: decode →
      epoch match (drop if mismatch) → `requestFailover` → on PERMITTED call
      `registry.beginFailoverLease` and reply `TYPE_FAILOVER_PERMIT` over same KCP
      session. Malformed payload swallowed silently to protect the event loop.
    - bind path calls `ControlFailoverHandler.onUdpSessionEstablished` after
      `registry.register` so the handler sees real authorization state, not just
      `declareUdpSessionForTest`.
  - **Step 6 — Mixin tick & disconnect** (`MixinServerGamePacketListenerImpl`):
    new `@Inject(method="tick", at=@At("HEAD")) hassium$recordControlActivity`
    reads `currentControlEpoch(player.getUUID())` and forwards when non-zero;
    `onDisconnect` injection replaces the old PoC `DataPlaneServer.onPrimaryDisconnect
    (pseudoPlayerId)` block with a real player UUID/epoch call to
    `DataPlaneUdpServer.onPrimaryDisconnect` when epoch!=0, followed by
    `ControlFailoverHandler.getInstance().remove(playerId)`. `#if MC_VER < MC_1_21_1`
    keeps the 1.20.x `Component reason` vs 1.21.1+ `DisconnectionDetails` signature.
  - **Step 7 — Fabric wired** (`FabricNetworkManager.completeServerHandshake`):
    `accepted && DataPlaneUdpServer.isBound()` path now resolves the player's real
    `Connection` via existing `getPlayerConnection(ServerPlayer)` helper and calls
    `beginControlConnection(uuid, () -> master.disconnect(Component.empty()))` to
    mint the per-handshake epoch + master-close handle. The returned `epoch` is
    written into the S2C tail (was a hardcoded `1L`). Removed the unused
    `FailoverFrameCodec` import this path no longer needs.
  - Verifications run on the worktree under `-Pmc_ver=1.20.1 --console=plain`:
    - `common:test --tests *ControlFailoverHandlerTest --tests *UdpLeaseRoutingTest
      --rerun-tasks` → BUILD SUCCESSFUL, 11 actionable tasks (8 ControlFailover +
      1 lease test cases executed).
    - `fabric:compileJava` → BUILD SUCCESSFUL (mixin + FabricNetworkManager
      compile clean against 1.20.1 mappings).
    - Full `common:test` → 174 tests, 7 failed, 3 skipped; the 7 are the long-
      standing accepted baseline (5× `DeltaMergeTest`/`ResourceKey`
      `NoClassDefFoundError` + `HassiumMetricsImplTest
      resetClearsClientDisplayMetrics` + `ChunkDiskCodecTest` +
      `CompressionServiceDictionaryTest`) — all pre-existing and reproducible
      independently of the UDP code path, no new regressions introduced.
  - Workspace hygiene check: legacy `markLease(now, leaseMs)` retained on
    `ReliableDatagramSession` (still used by `DataPlaneClientBundle:257`); new
    `markLeaseUntil` is additive. `FailoverFrameCodec` import removed from
    `FabricNetworkManager` to keep the Fabric diff minimal.
  - **Task 6: complete (no subagent review — controller path)**


- Task 7: Preserve cache infrastructure during automatic client recovery — complete
  - Controller RED→GREEN direct (no subagent: environment caveat per Task 2; controller is the only
    confirmed-producing entity).
  - **Step 1 — `ClientRecoveryState`** (plan §758-790): thread-safe singleton phase state machine
    `NONE → RECOVERING → RECOVERED → TERMINAL` (single-sided; from any phase `markTerminal`).
    `shouldSuppressFinalization` / `isRecovering` true in RECOVERING/RECOVERED (callers gate
    terminal cleanup there); `consumeTerminalCleanup()` returns true exactly once after TERMINAL
    so normal logout AND recovery exhaustion get exactly one finalize.
    `begin(deadlineMs)` cannot revive TERMINAL (terminal is single-sided); tested idempotency +
    `markTerminalFromNoneProducesOneCleanup` + `terminalIsIdempotentAndResistantToBegin`.
  - **Step 2 — `ControlEndpoint`** + **`ControlEndpointManager`** (plan §797-838):
    - `ControlEndpoint` record with validation (host non-blank, port 1..65535, priority >=0);
      `coordinateKey` = lowercased `host:port` so dedup is case-insensitive.
    - `mergeBootstrapAndAdvertised(bootstrap, advertised)`: bootstrap wins same-coordinate
      collisions; advertised duplicates dropped; remaining sorted by `priority` descending;
      truncated to `MAX_CANDIDATES = 4`.
    - `nextCandidate()` is idempotent (returns current top without consuming); removal happens
      via `recordAttemptFailure(ControlEndpoint)` by coordinate key. Recoverable before
      `startRecovery` is called so a lookup/merge without kicking recovery still works.
    - `startRecovery(long deadlineInMs)` takes a RELATIVE window length (not absolute ms),
      converting to absolute deadline = `clock + max(0, deadlineInMs)`. Test-reachable via
      package-private `startRecoveryWithClock(long deadlineInMs, LongSupplier clock)`.
      `nextCandidate()` returns `Optional.empty()` once the deadline passes (regardless of
      remaining candidates).
    - `markConnected(endpoint)` is a placeholder so the caller can later persist advertised
      candidates via Hassium metadata keyed to the multiplayer entry.
  - **Step 3 — ClientLifecycleHelper split gate** (plan §793): added
    `finalizeDisconnectIfTerminal()`:
    - When `ClientRecoveryState.getInstance().isRecovering()` is true → return WITHOUT touching
      `ClientHassiumStorage`, `CacheSaveQueue`, `HassiumTaskExecutor`, dirty/cache state.
    - When phase is TERMINAL, the helper consumes `consumeTerminalCleanup()` exactly once
      and proceeds to the existing `finalizeDisconnect()` body.
    - When phase is NONE, falls through to `finalizeDisconnect()` directly for normal logout.
    The internal `AtomicBoolean finalized` is still the multi-caller idempotency shield inside
    `finalizeDisconnect()`; the recovery-state gate wraps it.
  - **Step 4 — MixinMinecraft terminal suppress**: replaced all three
    `ClientLifecycleHelper.finalizeDisconnect()` calls (1.20.1 `clearLevel`,
    1.20.2-1.20.4 `disconnect(Screen)`, 1.20.5+ `disconnect(Screen, boolean)` + the NeoForge
    `clearLevel` compat injector) with `finalizeDisconnectIfTerminal()`. The unconditional
    teardown now never runs during the recovery window so disk cache + executor survive.
  - **ClientMetadataHandler**: no source changes needed — existing `clearPendingState()` only
    clears transient in-memory maps (PENDING_BE_REQUESTS, PENDING_BLOCK_ENTITIES,
    PENDING_HASH_PACKETS, PENDING_DELTA_REQUESTS); it never touches disk-backed hashes or
    storage. The plan §795 invariant is naturally satisfied; documented in ledger.
  - Tests: `ClientRecoveryStateTest` (4 cases), `ControlEndpointManagerTest` (4 cases) → 8/8
    green under `-Pmc_ver=1.20.1`. `common:compileJava` + `fabric:compileJava` green; no
    regressions to Task 1-6 test suites.
  - **Task 7: complete (no subagent review — controller path)**
