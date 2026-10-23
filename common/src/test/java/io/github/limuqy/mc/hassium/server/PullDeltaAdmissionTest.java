package io.github.limuqy.mc.hassium.server;

import io.github.limuqy.mc.hassium.protocol.sectiondelta.SectionDeltaPlanner;
import io.github.limuqy.mc.hassium.protocol.sectiondelta.SectionDeltaSnapshot;
import io.github.limuqy.mc.hassium.protocol.sectiondelta.SectionPlaneSyndrome;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PullDeltaAdmissionTest {
    @Test void skipsOnlyWhenKnownFullSectionsReachExistingThreshold() {
        assertTrue(PullDeltaAdmission.mustUseFull(List.of(1L, 2L, 3L, 4L), List.of(0L), null));
        assertTrue(PullDeltaAdmission.mustUseFull(List.of(1L, 2L, 3L, 4L), List.of(1L, 8L, 8L, 8L), null));
        assertFalse(PullDeltaAdmission.mustUseFull(List.of(1L, 2L, 3L, 4L), List.of(1L, 2L, 8L, 8L), null));
        assertFalse(PullDeltaAdmission.mustUseFull(List.of(0L, 0L), List.of(1L, 1L), null));
        assertFalse(PullDeltaAdmission.mustUseFull(List.of(1L, 2L), List.of(1L, 2L, 0L), null));
    }

    @Test void admissionNeverSkipsAPotentialDeltaAccordingToTheActualPlanner() {
        Random random = new Random(2310);
        int[] emptyPlanes = SectionPlaneSyndrome.compute(new int[SectionPlaneSyndrome.CELLS]);
        for (int sample = 0; sample < 1000; sample++) {
            int count = 1 + random.nextInt(24);
            long[] client = new long[count], server = new long[count];
            int[][] clientPlanes = new int[count][], serverPlanes = new int[count][];
            List<Long> clientList = new ArrayList<>(), serverList = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                server[i] = random.nextInt(4); client[i] = random.nextInt(4);
                clientList.add(client[i]); serverList.add(server[i]);
                if (random.nextBoolean()) clientPlanes[i] = emptyPlanes;
                if (random.nextBoolean()) serverPlanes[i] = emptyPlanes;
            }
            if (PullDeltaAdmission.mustUseFull(serverList, clientList, clientPlanes)) {
                assertTrue(SectionDeltaPlanner.plan(new SectionDeltaSnapshot(client, clientPlanes),
                        new SectionDeltaSnapshot(server, serverPlanes)).skipWholeChunk());
            }
        }
    }
}
