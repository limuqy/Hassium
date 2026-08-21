package io.github.limuqy.mc.hassium.network.sectiondelta;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端快照 vs 服务端快照的分段增量规划。
 * <p>
 * 柱级：{@code changed * 100 / nonEmpty >= 75} → 整块 skipped（全量回退）。
 * 段级：hash 相等跳过；无平面 / 空↔非空 / {@code n==0} → FULL；{@code n >= 400} → FULL；
 * 否则试编码方块列表 vs {@code section.write()}，谁小发谁。
 */
public final class SectionDeltaPlanner {

    /** 变更 section 占服务端非空 section 的百分比达到此值时回退全量。 */
    public static final int FALLBACK_THRESHOLD_PCT = 75;
    /** AABB 候选格数达到此值时不再发方块列表（9×9×9 炸坑 = 729）。 */
    public static final int AABB_FULL_THRESHOLD = 400;

    public enum Kind {
        SKIP,
        FULL,
        BLOCKS
    }

    public record SectionDecision(int sectionIndex, Kind kind, int[] candidates) {
        public SectionDecision {
            if (candidates == null) {
                candidates = new int[0];
            }
        }
    }

    public record ChunkDecision(boolean skipWholeChunk, List<SectionDecision> sections) {
        public ChunkDecision {
            if (sections == null) {
                sections = List.of();
            }
        }
    }

    private SectionDeltaPlanner() {}

    /**
     * 镜像原 {@code ServerChunkPushManager} / {@code SeedGenExecutor} 的 75% 判定：
     * 按完整索引比对（0 = 空 section），分母为<b>服务端</b>非空 section 数。
     * 客户端哈希为空数组时返回 false（维持原 delta 路径由服务端自行判定）。
     */
    public static boolean shouldFallbackFullChunk(long[] clientHashes, long[] serverHashes) {
        if (serverHashes == null || serverHashes.length == 0) {
            return false;
        }
        if (clientHashes == null) {
            clientHashes = new long[0];
        }
        int len = Math.max(serverHashes.length, clientHashes.length);
        int changed = 0;
        int nonEmpty = 0;
        for (int idx = 0; idx < len; idx++) {
            long serverHash = idx < serverHashes.length ? serverHashes[idx] : 0L;
            long clientHash = idx < clientHashes.length ? clientHashes[idx] : 0L;
            if (serverHash != 0L) {
                nonEmpty++;
            }
            if (serverHash != clientHash) {
                changed++;
            }
        }
        return nonEmpty > 0 && changed > 0
                && changed * 100 / nonEmpty >= FALLBACK_THRESHOLD_PCT;
    }

    public static ChunkDecision plan(SectionDeltaSnapshot client, SectionDeltaSnapshot server) {
        if (client == null || server == null) {
            return new ChunkDecision(true, List.of());
        }
        if (shouldFallbackFullChunk(client.sectionHashes(), server.sectionHashes())) {
            return new ChunkDecision(true, List.of());
        }
        int sectionCount = Math.max(client.sectionCount(), server.sectionCount());
        List<SectionDecision> sections = new ArrayList<>(sectionCount);
        for (int idx = 0; idx < sectionCount; idx++) {
            sections.add(planSection(idx, client.sectionHash(idx), client.planes(idx),
                    server.sectionHash(idx), server.planes(idx)));
        }
        return new ChunkDecision(false, sections);
    }

    /**
     * 在已有候选格上试编码：方块列表 vs 整段 {@code section.write()} 字节，谁小选谁。
     * {@code n==0} / {@code n >= 400} 由 {@link #planSection} 已收成 FULL，本方法只处理 BLOCKS 候选。
     */
    public static Kind pickSmaller(int[] candidates, int[] stateIds, byte[] fullSectionBytes) {
        if (candidates == null || candidates.length == 0) {
            return Kind.FULL;
        }
        if (candidates.length >= AABB_FULL_THRESHOLD) {
            return Kind.FULL;
        }
        int blocksSize = SectionPlaneSyndrome.encodedBlockListSize(candidates, stateIds);
        int fullSize = fullSectionBytes != null ? fullSectionBytes.length : Integer.MAX_VALUE;
        return blocksSize <= fullSize ? Kind.BLOCKS : Kind.FULL;
    }

    static SectionDecision planSection(int index, long clientHash, int[] clientPlanes,
                                       long serverHash, int[] serverPlanes) {
        if (clientHash == serverHash) {
            return new SectionDecision(index, Kind.SKIP, new int[0]);
        }
        boolean clientAir = clientHash == 0L;
        boolean serverAir = serverHash == 0L;
        if (clientAir != serverAir
                || !SectionPlaneSyndrome.validPlanes(clientPlanes)
                || !SectionPlaneSyndrome.validPlanes(serverPlanes)) {
            return new SectionDecision(index, Kind.FULL, new int[0]);
        }
        int[] candidates = SectionPlaneSyndrome.candidates(clientPlanes, serverPlanes);
        if (candidates.length == 0 || candidates.length >= AABB_FULL_THRESHOLD) {
            return new SectionDecision(index, Kind.FULL, new int[0]);
        }
        return new SectionDecision(index, Kind.BLOCKS, candidates);
    }
}
