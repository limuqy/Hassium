package io.github.limuqy.mc.hassium.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-player authoritative chunk-delivery admission state. Server-tick, ACK and timeout transitions
 * run on the server thread; concurrent queue producers use this instance's narrow monitor only.
 */
final class ChunkAdmissionController {

    static final int INITIAL_DESIRED_PER_TICK = 9;
    static final int POST_ACK_MAX_UNACKNOWLEDGED_BATCHES = 10;
    static final int MAX_PENDING_PER_PLAYER = 384;

    /**
     * 首个 ACK 前的慢回程探测间隔。fabric 网关的 CHUNK_APPLY_ACK 回程（客户端 tick 尾
     * flush → 网关帧 → 服务端泵入）比 forge/neoforge 直连慢一个量级，若窗口完全依赖
     * 首个 apply-ACK 解冻，admission 会以单批滴灌并触发 8s 超时 requeue 风暴。
     * 最老批在途超过该间隔视为回程慢，经两个相邻 server tick 确认后放行新批。
     */
    static final long FIRST_ACK_PROBE_INTERVAL_NANOS = 500_000_000L;

    record ChunkDeliveryKey(String dimension, int chunkX, int chunkZ) {}

    record Reservation(ChunkDeliveryKey key, long deliveryId) {}
    record ExpiredDelivery(ChunkDeliveryKey key, long deliveryId) {}

    private final Set<ChunkDeliveryKey> pendingByKey = new HashSet<>();
    private final Map<Long, InFlightDelivery> inFlightById = new HashMap<>();
    private final Map<ChunkDeliveryKey, Long> inFlightByKey = new HashMap<>();
    private final Map<Long, Batch> batchesById = new HashMap<>();

    private long nextDeliveryId = 1L;
    private long nextBatchId = 1L;
    private long currentBatchId;
    private int sentThisTick;
    private int maxChunksPerTick;
    private int unacknowledgedBatches;
    private double desiredPerTick;
    private double quota;
    private boolean hasReceivedFirstAck;
    /** 首 ACK 前慢回程探测：连续观察到超时在途的 server tick 数。 */
    private int slowAckProbeTicks;
    /**
     * 本 tick 的探测信用（0 或 1）：beginTick 时若最老批已超时在途且上一 tick 也
     * 观察到超时在途则授予；admit 消费后本 tick 不再放行。
     */
    private int probeCredits;
    /** 本 tick 已消费探测信用：探测授权每 tick 仅放行一笔投递。 */
    private boolean probeConsumedThisTick;
    /** 首 ACK 前经探测放行的批数（不含首批），防回程断链时无界开窗。 */
    private int probeOpenedBatches;
    /** 探测放行上限：与 POST_ACK 窗口同量级，足够灌满影子光管道。 */
    private static final int MAX_PROBE_OPENED_BATCHES = 8;
    private double ewmaApplyPerTick;

    /** Adds a pending key unless it is already pending/in-flight or this player's pending bound is full. */
    synchronized boolean offer(ChunkDeliveryKey key) {
        if (pendingByKey.contains(key) || inFlightByKey.containsKey(key)
                || pendingByKey.size() >= MAX_PENDING_PER_PLAYER) {
            return false;
        }
        pendingByKey.add(key);
        return true;
    }

    /** Removes only a not-yet-admitted pending key after its queue offer could not be committed. */
    synchronized void withdrawPending(ChunkDeliveryKey key) {
        pendingByKey.remove(key);
    }

    /** Returns whether a key is queued or awaiting authoritative application. */
    synchronized boolean contains(ChunkDeliveryKey key) {
        return pendingByKey.contains(key) || inFlightByKey.containsKey(key);
    }

    /** True only for a not-yet-admitted pending key (excludes in-flight). */
    synchronized boolean isPending(ChunkDeliveryKey key) {
        return pendingByKey.contains(key);
    }

    /** Starts one server tick only when the transport can accept a full delivery. */
    synchronized boolean beginTick(int configuredMaxChunksPerTick, boolean writable) {
        if (!writable) {
            return false;
        }
        beginTick(configuredMaxChunksPerTick);
        return true;
    }

    /** Begins one server tick. Quota never accumulates beyond the hard per-tick cap. */
    synchronized void beginTick(int configuredMaxChunksPerTick) {
        maxChunksPerTick = Math.max(1, configuredMaxChunksPerTick);
        if (desiredPerTick == 0.0) {
            desiredPerTick = Math.min(INITIAL_DESIRED_PER_TICK, maxChunksPerTick);
        } else {
            desiredPerTick = Math.min(desiredPerTick, maxChunksPerTick);
        }
        sentThisTick = 0;
        currentBatchId = 0L;
        probeCredits = 0;
        probeConsumedThisTick = false;
        if (oldestBatchOutstandingOverProbeInterval()) {
            slowAckProbeTicks++;
            // 跨 tick 确认：上一 tick 已观察到超时在途，本 tick 再次确认才放行
            if (slowAckProbeTicks >= 2 && probeOpenedBatches < MAX_PROBE_OPENED_BATCHES) {
                probeCredits = 1;
                probeOpenedBatches++;
            }
        } else {
            slowAckProbeTicks = 0;
        }
        if (!pendingByKey.isEmpty() && hasBatchCredit()) {
            quota = Math.min(maxChunksPerTick, quota + desiredPerTick);
        }
    }

    synchronized boolean canAdmit() {
        return sentThisTick < maxChunksPerTick && quota >= 1.0 && hasBatchCredit();
    }

    /** Moves a queued key to reserved in-flight state; delivery timing starts only at transport handoff. */
    synchronized Reservation admit(ChunkDeliveryKey key) {
        return admit(key, 0L);
    }

    /** Test seam retaining an explicit handoff timestamp. */
    synchronized Reservation admit(ChunkDeliveryKey key, long sentAtNanos) {
        return admit(key, sentAtNanos, sentAtNanos > 0L ? sentAtNanos : System.nanoTime());
    }

    /** Test seam: {@code reservedAtNanos} is the timeout clock until {@link #markSent}. */
    synchronized Reservation admit(ChunkDeliveryKey key, long sentAtNanos, long reservedAtNanos) {
        if (!canAdmit() || !pendingByKey.remove(key)) {
            return null;
        }
        if (currentBatchId == 0L) {
            // 仅当本批由慢回程探测信用资助（首 ACK 前、已有未确认批、信用可用）时，
            // 开批即消费信用并关闭本 tick 窗口；常规首批/配额批不触碰探测状态。
            boolean probeFunded = !hasReceivedFirstAck
                    && unacknowledgedBatches >= 1
                    && probeCredits > 0;
            currentBatchId = nextBatchId++;
            unacknowledgedBatches++;
            batchesById.put(currentBatchId, new Batch());
            if (probeFunded) {
                probeCredits = 0;
                probeConsumedThisTick = true;
            }
        }
        long deliveryId = nextDeliveryId++;
        if (deliveryId <= 0L) {
            throw new IllegalStateException("Chunk delivery id overflow");
        }
        long reservedAt = reservedAtNanos > 0L ? reservedAtNanos : System.nanoTime();
        inFlightById.put(deliveryId, new InFlightDelivery(key, sentAtNanos, currentBatchId, reservedAt));
        inFlightByKey.put(key, deliveryId);
        batchesById.get(currentBatchId).remaining++;
        quota -= 1.0;
        sentThisTick++;
        if (sentAtNanos > 0L) {
            recordTransportHandoff(currentBatchId, sentAtNanos);
        }
        return new Reservation(key, deliveryId);
    }

    /** Records the one timing anchor: immediately before the transport accepts this delivery. */
    synchronized boolean markSent(long deliveryId, long sentAtNanos) {
        InFlightDelivery delivery = inFlightById.get(deliveryId);
        if (delivery == null || delivery.sentAtNanos() != 0L || sentAtNanos <= 0L) {
            return false;
        }
        inFlightById.put(deliveryId, delivery.withSentAtNanos(sentAtNanos));
        recordTransportHandoff(delivery.batchId(), sentAtNanos);
        return true;
    }

    /**
     * Acknowledges one reserved delivery. Client apply can beat {@link #markSent} on localhost
     * (skip-redundant hash hit, or pushPool still compressing); rejecting that ACK leaks the
     * 10-batch window until a 30s timeout.
     */
    synchronized boolean acknowledge(long deliveryId, long acknowledgedAtNanos) {
        InFlightDelivery delivery = inFlightById.get(deliveryId);
        if (delivery == null) {
            return false;
        }
        inFlightById.remove(deliveryId);
        inFlightByKey.remove(delivery.key(), deliveryId);
        hasReceivedFirstAck = true;
        finishBatchMember(delivery.batchId(), true, acknowledgedAtNanos);
        return true;
    }

    /** Releases only the exact reserved delivery, so a stale fallback cannot free a replacement delivery. */
    synchronized boolean release(ChunkDeliveryKey key, long deliveryId) {
        Long currentDeliveryId = inFlightByKey.get(key);
        if (currentDeliveryId == null || currentDeliveryId != deliveryId) {
            return false;
        }
        return releaseDelivery(deliveryId);
    }

    /** Releases a reserved delivery after a build/compression/send/lifecycle failure. */
    synchronized boolean releaseDelivery(long deliveryId) {
        InFlightDelivery delivery = inFlightById.remove(deliveryId);
        if (delivery == null) {
            return false;
        }
        inFlightByKey.remove(delivery.key(), deliveryId);
        finishBatchMember(delivery.batchId(), false, 0L);
        return true;
    }

    /** Releases an in-flight key and returns its id so the caller can remove its paired task without scanning. */
    synchronized long releaseDeliveryId(ChunkDeliveryKey key) {
        Long deliveryId = inFlightByKey.get(key);
        return deliveryId != null && releaseDelivery(deliveryId) ? deliveryId : 0L;
    }

    /** Releases an out-of-view delivery or pending entry; late ACKs are then idempotently ignored. */
    synchronized boolean release(ChunkDeliveryKey key) {
        boolean removedPending = pendingByKey.remove(key);
        Long deliveryId = inFlightByKey.get(key);
        return deliveryId != null ? releaseDelivery(deliveryId) : removedPending;
    }

    /** Releases timed-out reservations; unsent deliveries clock from admit, not {@link #markSent}. */
    synchronized List<ExpiredDelivery> expire(long nowNanos, long timeoutNanos) {
        List<ExpiredDelivery> expired = new ArrayList<>();
        for (var iterator = inFlightById.entrySet().iterator(); iterator.hasNext(); ) {
            Map.Entry<Long, InFlightDelivery> entry = iterator.next();
            InFlightDelivery delivery = entry.getValue();
            long clock = delivery.timingAnchorNanos();
            if (clock <= 0L || nowNanos - clock < timeoutNanos) {
                continue;
            }
            iterator.remove();
            inFlightByKey.remove(delivery.key(), entry.getKey());
            finishBatchMember(delivery.batchId(), false, 0L);
            expired.add(new ExpiredDelivery(delivery.key(), entry.getKey()));
        }
        return expired;
    }

    synchronized void clear() {
        pendingByKey.clear();
        inFlightById.clear();
        inFlightByKey.clear();
        batchesById.clear();
        nextDeliveryId = 1L;
        nextBatchId = 1L;
        currentBatchId = 0L;
        sentThisTick = 0;
        unacknowledgedBatches = 0;
        desiredPerTick = 0.0;
        quota = 0.0;
        ewmaApplyPerTick = 0.0;
        slowAckProbeTicks = 0;
        probeCredits = 0;
        probeConsumedThisTick = false;
        probeOpenedBatches = 0;
        hasReceivedFirstAck = false;
    }

    synchronized int pendingCount() {
        return pendingByKey.size();
    }

    synchronized int inFlightCount() {
        return inFlightById.size();
    }

    synchronized int unacknowledgedBatches() {
        return unacknowledgedBatches;
    }

    synchronized boolean hasReceivedFirstAck() {
        return hasReceivedFirstAck;
    }

    synchronized double desiredPerTick() {
        return desiredPerTick;
    }

    synchronized String diagLine() {
        return "pending=" + pendingByKey.size()
                + " inFlight=" + inFlightById.size()
                + " unackedBatches=" + unacknowledgedBatches
                + " firstAck=" + hasReceivedFirstAck
                + " quota=" + String.format("%.1f", quota)
                + " desired=" + String.format("%.1f", desiredPerTick)
                + " canAdmit=" + canAdmit();
    }

    /**
     * Credit is in-flight <em>chunks</em> after the first ACK, not incomplete
     * tick-batches. A batch is one server tick ({@code maxChunksPerTick}
     * members). If one member is slow (shadow light) the batch stays
     * unacknowledged; 10 such batches used to freeze the window even after
     * 80/90 ACKs. Before the first ACK, still only one in-flight batch
     * (unknown RTT) — unless the oldest batch has been outstanding for more
     * than {@link #FIRST_ACK_PROBE_INTERVAL_NANOS} for two consecutive ticks
     * (slow fabric-gateway ACK return path): then one probe batch per tick is
     * allowed instead of freezing into the 8s expire/requeue storm.
     */
    private boolean hasBatchCredit() {
        if (probeConsumedThisTick) {
            // 探测授权仅放行一笔投递；本 tick 窗口随即关闭
            return false;
        }
        if (currentBatchId != 0L) {
            return true;
        }
        if (!hasReceivedFirstAck) {
            return unacknowledgedBatches < 1
                    || (probeCredits > 0 && !probeConsumedThisTick);
        }
        int maxInFlight = POST_ACK_MAX_UNACKNOWLEDGED_BATCHES * Math.max(1, maxChunksPerTick);
        return inFlightById.size() < maxInFlight;
    }

    /** 最老未确认批是否已超时在途（仅统计有 transport handoff 锚点的批）。 */
    private boolean oldestBatchOutstandingOverProbeInterval() {
        long now = System.nanoTime();
        long oldest = Long.MAX_VALUE;
        for (Batch batch : batchesById.values()) {
            if (batch.firstSentAtNanos > 0L) {
                oldest = Math.min(oldest, batch.firstSentAtNanos);
            }
        }
        return oldest != Long.MAX_VALUE && now - oldest >= FIRST_ACK_PROBE_INTERVAL_NANOS;
    }

    private void recordTransportHandoff(long batchId, long sentAtNanos) {
        Batch batch = batchesById.get(batchId);
        if (batch != null && (batch.firstSentAtNanos == 0L || sentAtNanos < batch.firstSentAtNanos)) {
            batch.firstSentAtNanos = sentAtNanos;
        }
    }

    private void finishBatchMember(long batchId, boolean acknowledged, long acknowledgedAtNanos) {
        Batch batch = batchesById.get(batchId);
        if (batch == null) {
            return;
        }
        batch.remaining--;
        if (acknowledged) {
            batch.acknowledgedCount++;
            batch.lastAcknowledgedAtNanos = Math.max(batch.lastAcknowledgedAtNanos, acknowledgedAtNanos);
        }
        if (batch.remaining != 0) {
            return;
        }
        batchesById.remove(batchId);
        if (batchId == currentBatchId) {
            // 批次在本 tick 内提前完成（切维时旧维度投递被批量 release/expire、
            // 新维度 ACK 快速回收，小配额批次可中途清零）：必须复位 currentBatchId，
            // 否则同 tick 后续 admit 走「已有当前批」分支，batchesById.get 拿到已移除
            // 的批次 → :121 remaining++ NPE（dim6 e2e 服务端崩溃实证）。
            // 复位后同 tick 下一次 admit 开新批并重新计入 unacknowledgedBatches，
            // 与跨 tick 的既有曲线语义一致。
            currentBatchId = 0L;
        }
        unacknowledgedBatches--;
        if (batch.acknowledgedCount == 0 || batch.firstSentAtNanos == 0L) {
            return;
        }
        long elapsedNanos = Math.max(1L, batch.lastAcknowledgedAtNanos - batch.firstSentAtNanos);
        double observedPerTick = batch.acknowledgedCount * 20_000_000.0 / elapsedNanos;
        ewmaApplyPerTick = ewmaApplyPerTick == 0.0
                ? observedPerTick
                : ewmaApplyPerTick * 0.75 + observedPerTick * 0.25;
        if (ewmaApplyPerTick < desiredPerTick) {
            // Floor at INITIAL (capped by this tick's hard max). A slow first batch
            // (shadow light RTT) must not collapse quota to 1/tick like vanilla
            // PlayerChunkSender starting at 9.
            double floor = Math.min(INITIAL_DESIRED_PER_TICK, maxChunksPerTick);
            desiredPerTick = Math.max(floor, Math.min(desiredPerTick, ewmaApplyPerTick));
        } else {
            desiredPerTick = Math.min(maxChunksPerTick, desiredPerTick + 1.0);
        }
    }

    private record InFlightDelivery(ChunkDeliveryKey key, long sentAtNanos, long batchId, long reservedAtNanos) {
        private InFlightDelivery withSentAtNanos(long value) {
            return new InFlightDelivery(key, value, batchId, reservedAtNanos);
        }

        private long timingAnchorNanos() {
            return sentAtNanos > 0L ? sentAtNanos : reservedAtNanos;
        }
    }

    private static final class Batch {
        private int remaining;
        private int acknowledgedCount;
        private long firstSentAtNanos;
        private long lastAcknowledgedAtNanos;
    }
}
