package io.github.limuqy.mc.hassium.protocol.egress;

import com.google.gson.Gson;
import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.EgressFixtureCompat;
import io.github.limuqy.mc.hassium.compat.PlayerCompat;
import io.github.limuqy.mc.hassium.protocol.HassiumConnectionRegistry;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import java.util.*;

/** Two real loader clients; deterministic per-connection sequences under steady and burst traffic. */
final class EgressLoadBenchmark {
    static final int EXPECTED = 36_480;
    private static final Map<Connection, State> states = new IdentityHashMap<>();
    private static int tick, phase = -1;
    private static boolean started;
    private static final class State { final long session = System.nanoTime(); int sequence, callbacks; }
    static void tickServer(MinecraftServer server) {
        var players = server.getPlayerList().getPlayers().stream()
                .filter(player -> HassiumConnectionRegistry.isEnabled(PlayerCompat.getConnection(player))).toList();
        if (!started) {
            if (players.size() != 2) return;
            started = true; EgressProfiler.reset(); EgressPipeline.METRICS.reset();
            for (var player : players) states.put(PlayerCompat.getConnection(player), new State());
        }
        int nextPhase = tick < 200 ? 0 : tick < 400 ? 1 : tick < 600 ? 2 : tick < 640 ? 3 : 4;
        if (nextPhase != phase) {
            phase = nextPhase;
            log(switch (phase) { case 0 -> "warmup"; case 1 -> "steady"; case 2 -> "burst"; case 3 -> "drain"; default -> "done"; }, players.size());
        }
        if (phase == 4) return;
        if (players.size() != 2) throw new IllegalStateException("Egress load lost a concurrent client");
        int count = phase == 0 ? 16 : phase == 1 ? 64 : phase == 2 && tick % 5 == 0 ? 512 : 0;
        for (var player : players) {
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
        tick++;
    }
    private static void log(String phase, int connections) {
        List<Map<String, Integer>> sent = new ArrayList<>();
        for (State state : states.values()) synchronized (state) {
            if (phase.equals("done") && (state.sequence != EXPECTED || state.callbacks != EXPECTED / 257))
                throw new IllegalStateException("Load send/callback count mismatch " + state.sequence + "/" + state.callbacks);
            sent.add(Map.of("packets", state.sequence, "callbacks", state.callbacks));
        }
        if (phase.equals("done") && EgressPipeline.METRICS.errors.sum() != 0) throw new IllegalStateException("Load protocol errors");
        Constants.LOG.info("[EGRESS_LOAD] {}", new Gson().toJson(Map.of("phase", phase, "connections", connections,
                "sent", sent, "metrics", EgressPipeline.METRICS.snapshot(), "profile", EgressProfiler.snapshot())));
    }
    private EgressLoadBenchmark() {}
}
