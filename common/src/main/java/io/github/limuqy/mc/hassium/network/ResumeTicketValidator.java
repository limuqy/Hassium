package io.github.limuqy.mc.hassium.network;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 续流票据验证器（服务端 B 侧）：共享密钥验签 + epoch 递增防重放。
 * <p>
 * 每玩家只接受严格递增的 epoch：旧连接票据（epoch ≤ 已接受值）一律拒绝，
 * 同票重放（epoch 相等）同样拒绝。纯 Java 可单测。
 * <p>
 * 注意：完整部署（T8）时，新会话自身 {@code DataPlaneUdpServer.beginControlConnection}
 * 的 per-player epoch 也应并入本表；当前骨架仅记录票据 epoch。
 */
public final class ResumeTicketValidator {

    private static final Map<UUID, Long> LAST_ACCEPTED_EPOCH = new ConcurrentHashMap<>();

    /** 验票结果 */
    public record Verification(boolean accepted, long epoch) {
        public static final Verification REJECTED = new Verification(false, Long.MIN_VALUE);

        public static Verification accepted(long epoch) {
            return new Verification(true, epoch);
        }
    }

    private ResumeTicketValidator() {}

    /**
     * 平台侧统一入口：解码票据 → 校验玩家 UUID 一致 → 验签 → epoch 防重放。
     * 任一环节失败返回 {@link Verification#REJECTED}。
     */
    public static Verification verifyRequest(UUID playerId, byte[] ticketBytes) {
        if (playerId == null || ticketBytes == null) {
            return Verification.REJECTED;
        }
        final ResumeTicket ticket;
        try {
            ticket = ResumeTicket.decode(ticketBytes);
        } catch (IllegalArgumentException e) {
            return Verification.REJECTED;
        }
        if (!playerId.equals(ticket.playerId())) {
            return Verification.REJECTED;
        }
        return verifyAndAccept(ticket) ? Verification.accepted(ticket.epoch()) : Verification.REJECTED;
    }

    /**
     * 验签 + 防重放；通过 → true 并记录 epoch。
     * epoch ≤ 该玩家已接受值（旧连接重放）→ false。
     */
    public static boolean verifyAndAccept(ResumeTicket ticket) {
        if (ticket == null || !ticket.verify()) {
            return false;
        }
        UUID id = ticket.playerId();
        long epoch = ticket.epoch();
        while (true) {
            Long prev = LAST_ACCEPTED_EPOCH.get(id);
            if (prev != null && epoch <= prev) {
                return false; // 旧 epoch / 同票重放
            }
            if (prev == null) {
                if (LAST_ACCEPTED_EPOCH.putIfAbsent(id, epoch) == null) {
                    return true;
                }
            } else if (LAST_ACCEPTED_EPOCH.replace(id, prev, epoch)) {
                return true;
            }
        }
    }

    public static long lastAcceptedEpoch(UUID playerId) {
        return LAST_ACCEPTED_EPOCH.getOrDefault(playerId, Long.MIN_VALUE);
    }

    public static void clear(UUID playerId) {
        LAST_ACCEPTED_EPOCH.remove(playerId);
    }

    public static int size() {
        return LAST_ACCEPTED_EPOCH.size();
    }
}
