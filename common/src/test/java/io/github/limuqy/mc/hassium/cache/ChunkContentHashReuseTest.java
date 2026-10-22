package io.github.limuqy.mc.hassium.cache;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChunkContentHashReuseTest {
    @Test
    void snapshotArrayMatchesCanonicalMapIncludingAirAndSectionGaps() {
        Random random = new Random(345L);
        for (int sample = 0; sample < 500; sample++) {
            long[] hashes = new long[random.nextInt(65)];
            Map<Integer, Long> canonical = new HashMap<>();
            for (int i = 0; i < hashes.length; i++) {
                if (random.nextBoolean()) {
                    long hash = random.nextLong();
                    hashes[i] = hash == 0 ? 1 : hash;
                    canonical.put(i, hashes[i]);
                }
            }
            assertEquals(ChunkContentHashUtil.combineSectionHashes(canonical),
                    ChunkContentHashUtil.combineSectionHashesFromArray(hashes));
        }
        assertEquals(1L, ChunkContentHashUtil.combineSectionHashesFromArray(new long[24]));
        assertNotEquals(ChunkContentHashUtil.combineSectionHashesFromArray(new long[] {7, 0}),
                ChunkContentHashUtil.combineSectionHashesFromArray(new long[] {0, 7}));
    }
}
