package io.github.limuqy.mc.hassium.utils;

import io.github.limuqy.mc.hassium.Constants;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Opt-in process-local monotonic timings, independent of packet logging. */
public final class ChunkPipelineProfile {
    public static final boolean ENABLED = Boolean.getBoolean("hassium.chunkProfile");
    private static final int CAPACITY = 16384;
    public enum Source { FULL, DELTA, CACHE, LOCAL, OTHER, CLIENT, SERVER }
    public enum Stage {
        REQUEST_RESPONSE, INGEST_WAIT, GATE_WAIT, LIGHT_WALL, PACK_WAIT, PACK_WALL,
        READY_WAIT, INGEST_APPLY, APPLY_WALL, FIRST_MESH, DECODE_WAIT, DECODE_WALL,
        SERVER_QUEUE, HASH_COMPARE, NBT_SNAPSHOT, DELTA_SNAPSHOT, FULL_SNAPSHOT,
        ENCODE_WAIT, ENCODE_WALL, SEND_WAIT, SERVER_TICK, CLIENT_TICK, DRAIN_INTERVAL
    }
    public enum Counter { FULL, DELTA, UNCHANGED, ERROR, QUOTA_DEFERRED, EMPTY_BATCH,
        ELIGIBLE_AFTER_EMPTY, FLOW_EVICTED, FLOW_ABANDONED, REQUEST_EVICTED, READY_PEAK, DECODE_PEAK }
    private static final Bucket CLIENT = new Bucket("client"), SERVER = new Bucket("server");
    private static final LinkedHashMap<Long, Flow> FLOWS = new LinkedHashMap<>();
    private static final LinkedHashMap<Column, Long> MESH = new LinkedHashMap<>();
    private static final LinkedHashMap<Request, Long> REQUESTS = new LinkedHashMap<>();
    private static long sequence, epoch, lastFrame;
    private ChunkPipelineProfile() {}
    public static long now() { return ENABLED ? System.nanoTime() : 0; }
    public static Source source(Enum<?> origin) {
        if (origin == null) return Source.OTHER;
        return switch (origin.name()) {
            case "REMOTE_PULL", "SERVER_PUSH" -> Source.FULL;
            case "SECTION_DELTA" -> Source.DELTA;
            case "SHADOW_MEMORY_CACHE", "SHADOW_DISK_CACHE" -> Source.CACHE;
            case "LOCAL_GENERATION" -> Source.LOCAL;
            default -> Source.OTHER;
        };
    }
    public static void elapsed(Source source, Stage stage, long start) {
        if (ENABLED && start != 0) record(source, stage, System.nanoTime() - start);
    }
    public static void record(Source source, Stage stage, long nanos) {
        if (ENABLED) (source == Source.SERVER ? SERVER : CLIENT).record(source, stage, nanos);
    }
    public static void count(boolean server, Counter counter) { if (ENABLED) (server ? SERVER : CLIENT).count(counter, 1); }
    public static void peak(Counter counter, long value) { if (ENABLED) CLIENT.peak(counter, value); }
    /** IDs are never reused, including after disconnect/dimension changes. */
    public static long begin(String dimension, long key, Source source) {
        if (!ENABLED) return 0;
        synchronized (FLOWS) {
            if (FLOWS.size() >= CAPACITY) {
                FLOWS.remove(FLOWS.keySet().iterator().next());
                count(false, Counter.FLOW_EVICTED);
            }
            long id = ++sequence;
            FLOWS.put(id, new Flow(epoch, dimension, key, source));
            return id;
        }
    }
    public static void consume(long id) {
        if (!ENABLED) return;
        synchronized (FLOWS) {
            Flow f = FLOWS.get(id);
            if (f != null && f.consumed == 0) {
                f.consumed = System.nanoTime();
                record(f.source, Stage.INGEST_WAIT, f.consumed - f.begin);
            }
        }
    }
    public static long gate(long id) {
        if (!ENABLED) return id;
        synchronized (FLOWS) {
            Flow f = FLOWS.get(id);
            if (f != null && f.gate == 0) f.gate = System.nanoTime();
            return id;
        }
    }
    public static void promoted(long id) {
        if (!ENABLED) return;
        synchronized (FLOWS) {
            Flow f = FLOWS.get(id);
            if (f != null && f.gate != 0) { elapsed(f.source, Stage.GATE_WAIT, f.gate); f.gate = 0; }
        }
    }
    public static void duration(long id, Stage stage, long start) {
        if (!ENABLED) return;
        synchronized (FLOWS) {
            Flow f = FLOWS.get(id);
            if (f != null) elapsed(f.source, stage, start);
        }
    }
    public static long ready(long id) {
        if (!ENABLED) return id;
        synchronized (FLOWS) {
            Flow f = FLOWS.get(id);
            if (f != null && f.ready == 0) f.ready = System.nanoTime();
            return id;
        }
    }
    public static void applied(long id) {
        if (!ENABLED) return;
        synchronized (FLOWS) {
            Flow f = FLOWS.get(id);
            if (f == null || f.applied != 0) return;
            f.applied = System.nanoTime();
            record(f.source, Stage.INGEST_APPLY, f.applied - f.begin);
            if (MESH.size() >= CAPACITY) MESH.remove(MESH.keySet().iterator().next());
            Long previous = MESH.put(new Column(f.dimension, f.key), id);
            if (previous != null && previous != id) FLOWS.remove(previous);
        }
    }
    /** Queue wait ends at the first apply attempt, before invoking the vanilla handler. */
    public static void startApply(long id) {
        if (!ENABLED) return;
        synchronized (FLOWS) {
            Flow f = FLOWS.get(id);
            if (f != null && f.applyStart == 0) {
                f.applyStart = System.nanoTime();
                if (f.ready != 0) record(f.source, Stage.READY_WAIT, f.applyStart - f.ready);
            }
        }
    }
    /** First observed compile callback after apply, not completion of all section meshes. */
    public static void mesh(String dimension, long key) {
        if (!ENABLED) return;
        synchronized (FLOWS) {
            Long id = MESH.remove(new Column(dimension, key));
            Flow f = id == null ? null : FLOWS.remove(id);
            if (f != null && f.epoch == epoch) elapsed(f.source, Stage.FIRST_MESH, f.applied);
        }
    }
    public static void request(String dimension, long requestId, long pos) {
        if (!ENABLED) return;
        synchronized (FLOWS) {
            if (REQUESTS.size() >= CAPACITY) {
                REQUESTS.remove(REQUESTS.keySet().iterator().next());
                count(false, Counter.REQUEST_EVICTED);
            }
            REQUESTS.put(new Request(epoch, dimension, requestId, pos), System.nanoTime());
        }
    }
    public static void response(String dimension, long requestId, long pos, Source source) {
        if (!ENABLED) return;
        synchronized (FLOWS) {
            Long start = REQUESTS.remove(new Request(epoch, dimension, requestId, pos));
            if (start != null) elapsed(source, Stage.REQUEST_RESPONSE, start);
        }
    }
    public static void resetClient() {
        if (!ENABLED) return;
        synchronized (FLOWS) {
            CLIENT.count(Counter.FLOW_ABANDONED, FLOWS.size());
            CLIENT.flush("reset-" + epoch);
            FLOWS.clear(); MESH.clear(); REQUESTS.clear(); lastFrame = 0; epoch++;
            CLIENT.clear();
        }
    }
    public static void frame() {
        if (!ENABLED) return;
        synchronized (FLOWS) {
            long now = System.nanoTime();
            if (lastFrame != 0) record(Source.CLIENT, Stage.DRAIN_INTERVAL, now - lastFrame);
            lastFrame = now;
            CLIENT.periodic();
        }
    }
    public static void serverTick(long start) { elapsed(Source.SERVER, Stage.SERVER_TICK, start); if (ENABLED) SERVER.periodic(); }
    public static void flushClient() { if (ENABLED) CLIENT.flush("snapshot"); }
    public static void flushServer() { if (ENABLED) SERVER.flush("final"); }
    private record Column(String dimension, long key) {}
    private record Request(long epoch, String dimension, long requestId, long pos) {}
    private static final class Flow {
        final long epoch, key, begin = System.nanoTime();
        final String dimension;
        final Source source;
        long consumed, gate, ready, applyStart, applied;
        Flow(long epoch, String dimension, long key, Source source) {
            this.epoch = epoch; this.dimension = dimension; this.key = key; this.source = source;
        }
    }
    private static final class Bucket {
        final String side;
        final Map<Source, EnumMap<Stage, LatencyHistogram>> metrics = new EnumMap<>(Source.class);
        final Map<Counter, Long> counters = new EnumMap<>(Counter.class);
        long lastPrint = System.nanoTime();
        Bucket(String side) { this.side = side; }
        synchronized void record(Source source, Stage stage, long ns) {
            metrics.computeIfAbsent(source, k -> new EnumMap<>(Stage.class))
                    .computeIfAbsent(stage, k -> new LatencyHistogram()).record(ns);
        }
        synchronized void count(Counter c, long n) { counters.merge(c, n, Long::sum); }
        synchronized void peak(Counter c, long n) { counters.merge(c, n, Math::max); }
        synchronized void periodic() { if (System.nanoTime() - lastPrint >= 15_000_000_000L) flush("cumulative"); }
        synchronized void clear() { metrics.clear(); counters.clear(); }
        synchronized void flush(String tag) {
            lastPrint = System.nanoTime();
            metrics.forEach((source, stages) -> stages.forEach((stage, histogram) -> {
                var s = histogram.snapshot();
                Constants.LOG.info("[CHUNK-PROFILE] side={} epoch={} tag={} source={} stage={} n={} meanNs={} p50Ns={} p95Ns={} p99Ns={} maxNs={}",
                        side, side.equals("client") ? epoch : 0, tag, source, stage,
                        s.count(), (long) s.meanNs(), s.p50Ns(), s.p95Ns(), s.p99Ns(), s.maxNs());
            }));
            Constants.LOG.info("[CHUNK-PROFILE] side={} epoch={} tag={} counters={}", side,
                    side.equals("client") ? epoch : 0, tag, counters);
        }
    }
}
