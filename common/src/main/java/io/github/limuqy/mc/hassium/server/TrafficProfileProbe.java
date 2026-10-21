package io.github.limuqy.mc.hassium.server;

import com.google.gson.JsonObject;
import io.github.limuqy.mc.hassium.protocol.AggregatedPacketExporter;
import io.github.limuqy.mc.hassium.protocol.egress.EgressPipeline;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dev smoke only: cumulative counters, never resets metrics or changes packet scheduling.
 * One connected player is required. Scoreboard tags delimit the scripted sample windows.
 */
public final class TrafficProfileProbe {
    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/TrafficProfile");
    private static String phase = "setup";
    private static long ticks;
    private static long lastSample;
    private static boolean factoryInitialized;
    private static boolean realFactory;

    public static void tickServer(MinecraftServer server) {
        String scenario = System.getProperty("hassium.serverSmokeScenario", "");
        if (!ServerSmokeTest.isEnabled()
                || !java.util.Set.of("trafficprofile", "trafficfactory").contains(scenario)) return;
        realFactory = "trafficfactory".equals(scenario);
        if (realFactory && !factoryInitialized) {
            io.github.limuqy.mc.hassium.compat.EgressFixtureCompat.freeze(server, true);
            factoryInitialized = true;
        }
        if (!realFactory || !io.github.limuqy.mc.hassium.compat.EgressFixtureCompat.frozen(server)) ticks++;
        if (server.getPlayerList().getPlayerCount() != 1) return;
        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
        String next = player.getTags().stream().filter(t -> t.startsWith("traffic_"))
                .sorted().findFirst().orElse("setup");
        long now = System.nanoTime();
        if (!next.equals(phase)) {
            sample(server, player, now, "end");
            phase = next;
            AggregatedPacketExporter.exportPhase(phase);
            sample(server, player, now, "start");
            lastSample = now;
        } else if (now - lastSample >= 1_000_000_000L) {
            sample(server, player, now, "sample");
            lastSample = now;
        }
    }

    private static void sample(MinecraftServer server, ServerPlayer player, long now, String edge) {
        var m = EgressPipeline.METRICS.snapshot();
        JsonObject row = new JsonObject();
        row.addProperty("phase", phase);
        row.addProperty("edge", edge);
        row.addProperty("fixture", realFactory ? "user_factory_1.21.1" : "generated_control");
        row.addProperty("frozen", realFactory && io.github.limuqy.mc.hassium.compat.EgressFixtureCompat.frozen(server));
        row.addProperty("timestampMs", System.currentTimeMillis());
        row.addProperty("nanoTime", now);
        row.addProperty("ticks", ticks);
        row.addProperty("wireBytes", m.wireBytes());
        row.addProperty("originalBytes", m.originalBytes());
        row.addProperty("aggregateBytes", m.aggregateBytes());
        row.addProperty("bypassBytes", m.bypassBytes());
        row.addProperty("packets", m.packets());
        row.addProperty("frames", m.frames());
        row.addProperty("compressionNanos", m.compressionNanos());
        row.addProperty("compressionCpuNanos", m.compressionCpuNanos());
        row.addProperty("errors", m.errors());
        row.addProperty("x", player.getX());
        row.addProperty("y", player.getY());
        row.addProperty("z", player.getZ());
        row.addProperty("yaw", player.getYRot());
        row.addProperty("pitch", player.getXRot());
        // The fixture has 16 independent dropper -> hopper -> chest production lanes.
        // Read containers on the real server thread; this is never a shadow-server path.
        long output = 0;
        long supply = 0;
        int stockedDroppers = 0;
        for (int i = 0; !realFactory && i < 16; i++) {
            BlockPos pos = new BlockPos(26 + (i % 4) * 7, 139, 12 + (i / 4) * 7);
            output += countItems(server, pos);
            long dropper = countItems(server, pos.above(3));
            if (dropper > 0) stockedDroppers++;
            supply += dropper + countItems(server, pos.above(4)) + countItems(server, pos.above(5));
        }
        row.addProperty("outputItems", output);
        row.addProperty("supplyItems", supply);
        row.addProperty("stockedDroppers", stockedDroppers);
        LOGGER.info("[TRAFFIC_PROFILE] {}", row);
    }

    private static long countItems(MinecraftServer server, BlockPos pos) {
        long total = 0;
        if (server.overworld().getBlockEntity(pos) instanceof Container container) {
            for (int slot = 0; slot < container.getContainerSize(); slot++)
                total += container.getItem(slot).getCount();
        }
        return total;
    }

    private TrafficProfileProbe() {}
}
