package io.github.limuqy.mc.hassium.network.sectiondelta;

import io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil;
import io.github.limuqy.mc.hassium.compat.LevelChunkSectionCompat;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * 每柱分段增量快照：{@code long[] sectionHashes} + 非空段 {@code int[48]} 平面。
 * 空气段 hash=0、不带平面。section hash 仍走 {@link ChunkContentHashUtil#computeSectionHash}
 * （保住 5 条路径等价）；平面另扫 4096 格 {@code Block.getId}。
 */
public final class SectionDeltaSnapshot {

    private final long[] sectionHashes;
    private final int[][] planes;

    public SectionDeltaSnapshot(long[] sectionHashes, int[][] planes) {
        this.sectionHashes = sectionHashes != null ? sectionHashes : new long[0];
        this.planes = planes != null ? planes : new int[this.sectionHashes.length][];
    }

    public static SectionDeltaSnapshot capture(LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        int count = sections.length;
        long[] hashes = new long[count];
        int[][] planes = new int[count][];
        for (int i = 0; i < count; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir()) {
                continue;
            }
            long hash = ChunkContentHashUtil.computeSectionHash(section);
            if (hash == 0L) {
                hash = 1L;
            }
            hashes[i] = hash;
            planes[i] = SectionPlaneSyndrome.compute(readCells(section));
        }
        return new SectionDeltaSnapshot(hashes, planes);
    }

    public static int[] readCells(LevelChunkSection section) {
        int[] cells = new int[SectionPlaneSyndrome.CELLS];
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    cells[SectionPlaneSyndrome.index(x, y, z)] =
                            LevelChunkSectionCompat.blockStateId(section.getBlockState(x, y, z));
                }
            }
        }
        return cells;
    }

    public long[] sectionHashes() {
        return sectionHashes;
    }

    public int[][] planes() {
        return planes;
    }

    public int sectionCount() {
        return sectionHashes.length;
    }

    public long sectionHash(int index) {
        return index >= 0 && index < sectionHashes.length ? sectionHashes[index] : 0L;
    }

    public int[] planes(int index) {
        if (index < 0 || index >= planes.length) {
            return null;
        }
        return planes[index];
    }
}
