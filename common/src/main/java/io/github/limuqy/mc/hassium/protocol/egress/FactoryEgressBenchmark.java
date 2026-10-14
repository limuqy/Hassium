package io.github.limuqy.mc.hassium.protocol.egress;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.PlayerCompat;
import io.github.limuqy.mc.hassium.protocol.AggregatedPacketExporter;
import io.github.limuqy.mc.hassium.protocol.HassiumConnectionRegistry;
import net.minecraft.server.MinecraftServer;

/** Explicit dev fixture: freeze while the native factory loads, then measure one fresh world at one rate. */
public final class FactoryEgressBenchmark {
    private static final String SCENARIO = System.getProperty("hassium.serverSmokeScenario", "");
    private static final boolean ENABLED = java.util.Set.of("factoryegress20", "factoryegress100", "factoryegress200").contains(SCENARIO);
    private static long joined, measuring, lastSample, ticks, sampledTicks;
    private static boolean done, prepared;
    public static void tickServer(MinecraftServer server) {
        if (!ENABLED || done) return;
        if (server.getPlayerList() == null) return;
        if (!prepared) { io.github.limuqy.mc.hassium.compat.EgressFixtureCompat.freeze(server, true); prepared = true; }
        long now = System.currentTimeMillis();
        var player = server.getPlayerList().getPlayers().stream()
                .filter(p -> HassiumConnectionRegistry.isEnabled(PlayerCompat.getConnection(p))).findFirst().orElse(null);
        if (player == null) return;
        if (joined == 0) {
            joined = now;
            AggregatedPacketExporter.exportPhase("warmup");
            Constants.LOG.info("[FACTORY_BENCH] phase=warmup timestampMs={} pos={},{},{} frozen=true metrics={}",
                    now, player.getX(), player.getY(), player.getZ(), EgressPipeline.METRICS.snapshot());
        }
        // The client scenario unfreezes only after native join, camera placement and chunk warmup.
        if (measuring == 0 && !io.github.limuqy.mc.hassium.compat.EgressFixtureCompat.frozen(server)) {
            int rate = Integer.parseInt(SCENARIO.substring("factoryegress".length()));
            if (Math.abs(player.getX() - 72.5) > 1 || Math.abs(player.getY() - 78) > 8 || Math.abs(player.getZ() - 79.5) > 1)
                throw new IllegalStateException("Factory benchmark camera was not ready: " + player.position());
            if (io.github.limuqy.mc.hassium.compat.EgressFixtureCompat.tickRate(server) != rate) throw new IllegalStateException("Factory tick rate mismatch");
            measuring = lastSample = now;
            Constants.LOG.info("[FACTORY_BENCH] phase=factory_{} timestampMs={} ticks=0 pos={},{},{} metrics={}",
                    rate, now, player.getX(), player.getY(), player.getZ(), EgressPipeline.METRICS.snapshot());
            AggregatedPacketExporter.exportPhase("factory_" + rate);
        } else if (measuring != 0) {
            ticks++;
            if (now - lastSample >= 1000) {
                double actualTps = (ticks - sampledTicks) * 1000.0 / (now - lastSample);
                Constants.LOG.info("[FACTORY_BENCH] sample timestampMs={} ticks={} actualTPS={} metrics={}",
                        now, ticks, actualTps, EgressPipeline.METRICS.snapshot());
                sampledTicks = ticks; lastSample = now;
            }
            if (now - measuring >= 20000) {
                done = true;
                Constants.LOG.info("[FACTORY_BENCH] phase=done timestampMs={} ticks={} elapsedMs={} actualTPS={} metrics={}",
                        now, ticks, now - measuring, ticks * 1000.0 / (now - measuring), EgressPipeline.METRICS.snapshot());
                AggregatedPacketExporter.exportPhase("done");
                io.github.limuqy.mc.hassium.compat.EgressFixtureCompat.freeze(server, true);
            }
        }
    }
    private FactoryEgressBenchmark() {}
}
