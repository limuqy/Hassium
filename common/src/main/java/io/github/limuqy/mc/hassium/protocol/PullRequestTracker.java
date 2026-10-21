package io.github.limuqy.mc.hassium.protocol;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Empty queue acknowledgements and split responses must not consume terminal-result metadata. */
final class PullRequestTracker {
    record Mode(boolean comparedBaseline, boolean nbtRepair, long baselineHash) {
        boolean requiresNbtRepair(long serverHash) {
            return nbtRepair || comparedBaseline && baselineHash != 0L && baselineHash == serverHash;
        }
    }
    private record Pending(boolean baseline, boolean repair, Map<Long, Long> columns) {}
    private final int limit;
    private final LinkedHashMap<Long, Pending> requests = new LinkedHashMap<>(256, 0.75f, true);

    PullRequestTracker(int limit) { this.limit = limit; }

    synchronized void register(long id, Map<Long, Long> columns, boolean baseline, boolean repair) {
        if (columns.isEmpty()) return;
        requests.put(id, new Pending(baseline, repair, new HashMap<>(columns)));
        while (requests.size() > limit) requests.remove(requests.keySet().iterator().next());
    }

    /** Called only for an accepted terminal column result, after any main-thread handoff. */
    synchronized Mode take(long id, long column) {
        Pending pending = requests.get(id);
        if (pending == null) return null;
        Long hash = pending.columns.remove(column);
        if (hash == null) return null;
        if (pending.columns.isEmpty()) requests.remove(id);
        return new Mode(pending.baseline, pending.repair, hash);
    }

    synchronized void clear() { requests.clear(); }
}
