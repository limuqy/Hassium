package io.github.limuqy.mc.hassium.protocol.egress;

import com.google.gson.Gson;
import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.EgressFixtureCompat;
import io.github.limuqy.mc.hassium.compat.PlayerCompat;
import io.github.limuqy.mc.hassium.protocol.HassiumConnectionRegistry;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import java.lang.management.ManagementFactory;
import java.util.*;

/** Two real loader clients; deterministic per-connection sequences under steady and burst traffic. */
final class EgressLoadBenchmark {
    static final int EXPECTED = 36_480;
    private static final EgressStressWorkload STRESS = "egressstress".equals(System.getProperty("hassium.serverSmokeScenario"))
            ? EgressStressWorkload.configured() : null;
    private static final Map<Connection, State> states = new IdentityHashMap<>();
    private static int tick, phase = -1;
    private static boolean started;
    private static final class State { final long session = System.nanoTime(); int sequence, callbacks; }
    static void tickServer(MinecraftServer server) {
        if (STRESS != null) {
            if (!started || phase == 4) return;
            send(server, STRESS.afterFlushCount(tick));
            tick++;
            return;
        }
        prepare(server);
        if (!started || phase == 4) return;
        send(server, phase == 0 ? 16 : phase == 1 ? 64 : phase == 2 && tick % 5 == 0 ? 512 : 0);
        tick++;
    }
    static void beforeFlush(MinecraftServer server) {
        if (STRESS == null) return;
        prepare(server);
        if (started && phase != 4) send(server, STRESS.beforeFlushCount(tick));
    }
    private static void prepare(MinecraftServer server) {
        var players = server.getPlayerList().getPlayers().stream()
                .filter(player -> HassiumConnectionRegistry.isEnabled(PlayerCompat.getConnection(player))).toList();
        if (!started) {
            if (players.size() != 2) return;
            if (STRESS != null) EgressStressWorkload.text(0);
            started = true; EgressProfiler.reset(); EgressPipeline.METRICS.reset();
            for (var player : players) states.put(PlayerCompat.getConnection(player), new State());
        }
        int nextPhase = STRESS != null ? STRESS.phase(tick)
                : tick < 200 ? 0 : tick < 400 ? 1 : tick < 600 ? 2 : tick < 640 ? 3 : 4;
        if (nextPhase != phase) {
            phase = nextPhase;
            log(switch (phase) { case 0 -> "warmup"; case 1 -> "steady"; case 2 -> "burst"; case 3 -> "drain"; default -> "done"; }, players.size());
        }
        if (phase == 4) return;
        if (players.size() != 2) throw new IllegalStateException("Egress load lost a concurrent client");
    }
    private static void send(MinecraftServer server, int count) {
        if (count == 0) return;
        for (var player : server.getPlayerList().getPlayers()) {
            Connection connection = PlayerCompat.getConnection(player); State state = states.get(connection);
            if (state == null) throw new IllegalStateException("Egress load connection changed");
            var biome = EgressFixtureCompat.biome(player);
            for (int i = 0; i < count; i++) {
                var packet = EgressFixtureCompat.fixturePacket(EgressFixture.sample(state.session, state.sequence++, biome));
                if (state.sequence % 257 == 0) EgressFixtureCompat.send(connection, packet, future -> {
                    if (!future.isSuccess()) throw new IllegalStateException("Load callback failed", future.cause());
                    synchronized (state) { state.callbacks++; }
                });
                else connection.send(packet);
            }
        }
    }
    private static void log(String phase, int connections) {
        List<Map<String, Integer>> sent = new ArrayList<>();
        for (State state : states.values()) synchronized (state) {
            int expected = STRESS == null ? EXPECTED : STRESS.expected();
            if (phase.equals("done") && (state.sequence != expected || state.callbacks != expected / 257))
                throw new IllegalStateException("Load send/callback count mismatch " + state.sequence + "/" + state.callbacks);
            sent.add(Map.of("packets", state.sequence, "callbacks", state.callbacks));
        }
        if (phase.equals("done") && EgressPipeline.METRICS.errors.sum() != 0) throw new IllegalStateException("Load protocol errors");
        var config = io.github.limuqy.mc.hassium.config.HassiumConfigService.getInstance();
        var report = new LinkedHashMap<String, Object>(Map.of("phase", phase, "connections", connections,
                "sent", sent, "metrics", EgressPipeline.METRICS.snapshot(), "profile", EgressProfiler.snapshot(),
                "cpu", cpuSnapshot(), "profileBuckets", EgressProfiler.histogramSnapshot(),
                "settings", Map.of("waitMs", config.getAggregationMaxWaitTimeMs(),
                        "maxSize", config.getAggregationMaxSize(), "compressionLevel", config.getCompressionLevel())));
        if (STRESS != null) report.put("workload", Map.of("rate", STRESS.rate(), "warmupTicks", STRESS.warmupTicks(),
                "measureTicks", STRESS.measureTicks(), "drainTicks", STRESS.drainTicks(), "expected", STRESS.expected(),
                "stateBytes", 3072, "steadyPlacement", "before-flush", "burstPlacement", "after-flush", "dictionaryFrozen", true));
        Constants.LOG.info("[EGRESS_LOAD] {}", new Gson().toJson(report));
    }
    private record CpuSnapshot(long nanoTime, long processCpuNs, long workerCpuNs, int workerThreads, boolean threadCpuSupported) {}
    private static CpuSnapshot cpuSnapshot() {
        var threads = ManagementFactory.getThreadMXBean();
        boolean supported = threads.isThreadCpuTimeSupported();
        if (supported && !threads.isThreadCpuTimeEnabled()) threads.setThreadCpuTimeEnabled(true);
        long workers = 0; int count = 0;
        for (long id : threads.getAllThreadIds()) {
            var info = threads.getThreadInfo(id);
            if (info != null && info.getThreadName().equals("Hassium-EgressV3")) {
                count++;
                if (supported) workers += Math.max(0, threads.getThreadCpuTime(id));
            }
        }
        long process = ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os
                ? os.getProcessCpuTime() : -1;
        return new CpuSnapshot(System.nanoTime(), process, supported ? workers : -1, count, supported);
    }
    private EgressLoadBenchmark() {}
}
