package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.network.core.outbound.ChunkApplyAck;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 单个客户端会话的 authoritative apply ACK 缓冲。
 *
 * <p>队列保持投递完成顺序；发送失败时不移除，因此下次 tick 会重试同一批。断连必须调用
 * {@link #clear()}，避免旧会话的 deliveryId 泄漏到新连接。
 */
final class ClientChunkApplyAckAggregator {

    private static final int BATCH_CAPACITY = ChunkApplyAck.MAX_DELIVERY_IDS;
    /** 服务端配置上限 256 × 未确认窗口 10；满载时 fail-fast，绝不静默丢确认。 */
    private static final int MAX_PENDING_DELIVERY_IDS = 256 * 10;

    private final ArrayDeque<Long> deliveryIds = new ArrayDeque<>(BATCH_CAPACITY);
    private final Set<Long> queuedDeliveryIds = new HashSet<>(BATCH_CAPACITY);
    private final Predicate<ChunkApplyAck> sender;

    ClientChunkApplyAckAggregator(Predicate<ChunkApplyAck> sender) {
        this.sender = java.util.Objects.requireNonNull(sender, "sender");
    }

    synchronized void recordApplied(long deliveryId) {
        if (deliveryId <= 0 || queuedDeliveryIds.contains(deliveryId)) {
            return;
        }
        if (deliveryIds.size() == MAX_PENDING_DELIVERY_IDS) {
            flush();
            if (deliveryIds.size() == MAX_PENDING_DELIVERY_IDS) {
                throw new IllegalStateException("Chunk apply ACK backlog is full");
            }
        }
        queuedDeliveryIds.add(deliveryId);
        deliveryIds.addLast(deliveryId);
        if (deliveryIds.size() >= BATCH_CAPACITY) {
            flush();
        }
    }

    synchronized void flush() {
        if (deliveryIds.isEmpty()) {
            return;
        }
        int batchSize = Math.min(deliveryIds.size(), BATCH_CAPACITY);
        long[] batch = new long[batchSize];
        int index = 0;
        for (long deliveryId : deliveryIds) {
            batch[index++] = deliveryId;
            if (index == batchSize) {
                break;
            }
        }
        if (!sender.test(new ChunkApplyAck(batch))) {
            return;
        }
        for (int removed = 0; removed < batchSize; removed++) {
            queuedDeliveryIds.remove(deliveryIds.removeFirst());
        }
    }

    synchronized void clear() {
        deliveryIds.clear();
        queuedDeliveryIds.clear();
    }

    synchronized int size() {
        return deliveryIds.size();
    }
}
