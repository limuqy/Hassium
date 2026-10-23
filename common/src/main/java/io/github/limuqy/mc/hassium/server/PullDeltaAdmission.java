package io.github.limuqy.mc.hassium.server;

import io.github.limuqy.mc.hassium.protocol.sectiondelta.SectionDeltaPlanner;
import io.github.limuqy.mc.hassium.protocol.sectiondelta.SectionPlaneSyndrome;
import java.util.List;

/** Hash-only lower bound on FULL sections; never predicts a successful DELTA. */
final class PullDeltaAdmission {
    private PullDeltaAdmission() {}

    static boolean mustUseFull(List<Long> serverHashes, List<Long> clientHashes, int[][] clientPlanes) {
        int nonEmpty = 0;
        int guaranteedFull = 0;
        for (int i = 0; i < serverHashes.size(); i++) {
            long server = serverHashes.get(i);
            if (server == 0L) continue;
            nonEmpty++;
            long client = i < clientHashes.size() ? clientHashes.get(i) : 0L;
            if (client == server) continue;
            int[] planes = clientPlanes != null && i < clientPlanes.length ? clientPlanes[i] : null;
            if (client == 0L || planes == null || planes.length != SectionPlaneSyndrome.PLANE_COUNT) guaranteedFull++;
        }
        return nonEmpty > 0 && guaranteedFull > 0
                && guaranteedFull * 100 / nonEmpty >= SectionDeltaPlanner.FALLBACK_THRESHOLD_PCT;
    }
}
