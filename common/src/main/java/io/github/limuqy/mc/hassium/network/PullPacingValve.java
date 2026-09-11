package io.github.limuqy.mc.hassium.network;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pull 源头阀门的纯逻辑（无 Minecraft 类型）：每 tick 取哪几柱去 hash / 发票。
 * <p>
 * FULL/DELTA 配额 = {@code maxChunksPerTick}；UNCHANGED 另额 {@link #UNCHANGED_PER_TICK}；
 * 未就绪发票 lookahead = N×{@link #LOOKAHEAD_MULTIPLIER}。取柱按 chebyshev 近优先。
 */
final class PullPacingValve {

    /** UNCHANGED 不占 FULL/DELTA 配额（对齐旧 HASH_SENDS_PER_TICK）。 */
    static final int UNCHANGED_PER_TICK = 32;
    static final int LOOKAHEAD_MULTIPLIER = 8;

    private PullPacingValve() {
    }

    static int fullDeltaBudget(int configured) {
        return configured > 0 ? configured : 5;
    }

    static int hashBudget(int configured) {
        return fullDeltaBudget(configured) + UNCHANGED_PER_TICK;
    }

    static int lookaheadLimit(int configured) {
        return fullDeltaBudget(configured) * LOOKAHEAD_MULTIPLIER;
    }

    /**
     * 客户端是否带了可比较基线。无基线的权威 FULL 不可能变成 UNCHANGED，
     * FULL 配额用尽后不应再为其做 section hash。
     */
    static boolean hasBaseline(long chunkHash, List<Long> sectionHashes) {
        return chunkHash != 0L || (sectionHashes != null && !sectionHashes.isEmpty());
    }

    /**
     * 本 tick 是否跳过 hash：两边配额都尽，或 FULL 已尽且该柱不可能 UNCHANGED。
     */
    static boolean skipHash(int fullLeft, int unchangedLeft, boolean hasBaseline) {
        if (fullLeft <= 0 && unchangedLeft <= 0) {
            return true;
        }
        return fullLeft <= 0 && !hasBaseline;
    }

    record Candidate(int index, int chunkX, int chunkZ, boolean ready, boolean ticketed) {
    }

    static int chebyshev(int ax, int az, int bx, int bz) {
        return Math.max(Math.abs(ax - bx), Math.abs(az - bz));
    }

    /**
     * 已就绪柱：距中心近者优先，最多 {@code hashBudget} 根（随后主线程才 hash）。
     *
     * @return {@link Candidate#index()} 列表
     */
    static List<Integer> selectReadyToHash(List<Candidate> candidates, int centerX, int centerZ,
                                           int hashBudget) {
        if (hashBudget <= 0 || candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<Candidate> ready = new ArrayList<>();
        for (Candidate c : candidates) {
            if (c.ready()) {
                ready.add(c);
            }
        }
        ready.sort(nearest(centerX, centerZ));
        int n = Math.min(hashBudget, ready.size());
        List<Integer> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(ready.get(i).index());
        }
        return out;
    }

    /**
     * 未就绪且尚未持票：近优先，补到 lookahead 上限（只装载，不算 hash）。
     */
    static List<Integer> selectLookaheadTickets(List<Candidate> candidates, int centerX, int centerZ,
                                                int alreadyTicketed, int lookahead) {
        int room = lookahead - alreadyTicketed;
        if (room <= 0 || candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<Candidate> unready = new ArrayList<>();
        for (Candidate c : candidates) {
            if (!c.ready() && !c.ticketed()) {
                unready.add(c);
            }
        }
        unready.sort(nearest(centerX, centerZ));
        int n = Math.min(room, unready.size());
        List<Integer> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(unready.get(i).index());
        }
        return out;
    }

    private static Comparator<Candidate> nearest(int centerX, int centerZ) {
        return Comparator
                .comparingInt((Candidate c) -> chebyshev(c.chunkX(), c.chunkZ(), centerX, centerZ))
                .thenComparingInt(Candidate::chunkX)
                .thenComparingInt(Candidate::chunkZ)
                .thenComparingInt(Candidate::index);
    }
}
