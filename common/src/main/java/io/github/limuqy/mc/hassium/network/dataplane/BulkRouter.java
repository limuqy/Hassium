package io.github.limuqy.mc.hassium.network.dataplane;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 服务端 bulk 路由。
 * 设计稿 §5/PoC: share = Primary + Data 按 WRR; exclusive = 仅 Data, drop+degrade。
 */
public class BulkRouter {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/BulkRouter");

    /**
     * 尝试通过 Data 通道发送 bulk。
     *
     * @return true = 已发送或已丢弃, caller 不应再走 Primary; false = 走 Primary
     */
    public static boolean sendBulk(PlayerChannelBundle bundle, String mode, int primaryWeight, int degradeAfterDrops) {
        if (bundle == null) return false;
        if (bundle.degraded) return false;

        // 收集候选
        List<Candidate> candidates = new ArrayList<>();
        if ("share".equals(mode)) {
            candidates.add(new Candidate(Candidate.Type.PRIMARY, primaryWeight, null));
        }
        for (PlayerChannel ch : bundle.getDataChannels()) {
            if (ch == null) continue; // 防御：COW 快照可能含历史残留
            if (ch.isActive() && ch.isWritable()) {
                candidates.add(new Candidate(Candidate.Type.DATA, ch.weight, ch));
            }
        }

        if (candidates.isEmpty()) {
            return handleNoCandidate(bundle, mode, degradeAfterDrops);
        }

        // WRR 选择
        Candidate target = weightedRoundRobin(candidates, bundle);
        if (target.type == Candidate.Type.PRIMARY) {
            return false; // caller 走 Primary
        }
        // 实际发送到 Data 通道
        if (target.channel != null) {
            bundle.consecutiveDrops = 0;
            // 写入操作在调用方完成, 此处只决策路由
            return true; // 告知 caller 已路由到 Data
        }
        return false;
    }

    /**
     * 选择一个 Data 通道用于 bulk 发送（与 {@link #sendBulk} 同逻辑，但返回选中的通道）。
     *
     * @return 选中的 Data {@link PlayerChannel}；null 表示 caller 应走 Primary。
     */
    public static PlayerChannel selectChannel(PlayerChannelBundle bundle, String mode, int primaryWeight, int degradeAfterDrops) {
        if (bundle == null) return null;
        if (bundle.degraded) return null;

        List<Candidate> candidates = new ArrayList<>();
        if ("share".equals(mode)) {
            candidates.add(new Candidate(Candidate.Type.PRIMARY, primaryWeight, null));
        }
        for (PlayerChannel ch : bundle.getDataChannels()) {
            if (ch == null) continue; // 防御：COW 快照可能含历史残留
            if (ch.isActive() && ch.isWritable()) {
                candidates.add(new Candidate(Candidate.Type.DATA, ch.weight, ch));
            }
        }

        if (candidates.isEmpty()) {
            boolean drop = handleNoCandidate(bundle, mode, degradeAfterDrops);
            // drop=true 已经丢弃（exclusive 阈值内），caller 不发 Primary，但也没有 channel 可写；
            // drop=false 走 Primary。两种情况都返回 null（无 Data channel 可写）。
            return null;
        }

        Candidate target = weightedRoundRobin(candidates, bundle);
        if (target.type == Candidate.Type.PRIMARY) {
            return null; // caller 走 Primary
        }
        if (target.channel != null) {
            bundle.consecutiveDrops = 0;
            return target.channel;
        }
        return null;
    }

    private static boolean handleNoCandidate(PlayerChannelBundle bundle, String mode, int degradeAfterDrops) {
        if ("share".equals(mode)) {
            return false; // Primary fallback
        }
        // exclusive: immediate drop
        bundle.consecutiveDrops++;
        if (bundle.consecutiveDrops >= degradeAfterDrops) {
            bundle.degraded = true;
            LOGGER.warn("BulkRouter: Player degraded after {} consecutive drops (exclusive, no Data channels)", degradeAfterDrops);
            return false; // degraded → Primary
        }
        LOGGER.debug("BulkRouter: Exclusive drop #{} for player", bundle.consecutiveDrops);
        return true; // caller 不要发 Primary
    }

    /** 标准 WRR (当前权重累加, 选最大, 再减 totalWeight) */
    private static Candidate weightedRoundRobin(List<Candidate> candidates, PlayerChannelBundle bundle) {
        int total = 0;
        for (Candidate c : candidates) total += c.weight;
        if (total == 0) return candidates.get(0);

        int accum = bundle.wrrAccum.addAndGet(1); // 简化 PoC: 每 call +1
        int idx = accum % candidates.size();
        return candidates.get(idx);
    }

    static class Candidate {
        enum Type { PRIMARY, DATA }
        final Type type;
        final int weight;
        final PlayerChannel channel;
        Candidate(Type type, int weight, PlayerChannel channel) {
            this.type = type; this.weight = weight; this.channel = channel;
        }
    }
}
