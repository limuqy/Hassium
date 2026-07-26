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
- Task 4: Health-aware UDP WRR — pending
- Task 5: Handshake tail + client UDP lifecycle — pending
- Task 6: Control failover handler + lease — pending
- Task 7: Client recovery state + endpoint manager — pending
- Task 8: SectionDelta over UDP — pending
- Task 9: Fabric reconnect orchestrator + smoke — pending
- Task 10: TCP dataplane removal + docs — pending
