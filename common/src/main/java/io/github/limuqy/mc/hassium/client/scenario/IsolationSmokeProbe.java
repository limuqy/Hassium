package io.github.limuqy.mc.hassium.client.scenario;

import io.github.limuqy.mc.hassium.server.RuntimeServerContext;
import io.github.limuqy.mc.hassium.shadow.server.ShadowServerRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Dev smoke only: keep the old process flag armed through real integrated-world loading and saving. */
public final class IsolationSmokeProbe {
    private static volatile int scheduledChecks;
    private IsolationSmokeProbe() {}

    public static void arm() {
        // This test JVM exits after normal server shutdown. Do not clear before asynchronous saves finish.
        RuntimeServerContext.beginShadowServer();
    }

    public static long scheduleTicks(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) return 0;
        var id = mc.player.getUUID();
        var result = new CompletableFuture<Long>();
        server.execute(() -> {
            try {
                var player = server.getPlayerList().getPlayer(id);
                if (player == null) { result.complete(0L); return; }
                var level = io.github.limuqy.mc.hassium.compat.PlayerCompat.getServerLevel(player);
                var pos = player.blockPosition();
                level.scheduleTick(pos, Blocks.STONE, 1);
                level.scheduleTick(pos, Fluids.WATER, 1);
                int accepted = (level.getBlockTicks().hasScheduledTick(pos, Blocks.STONE) ? 1 : 0)
                        + (level.getFluidTicks().hasScheduledTick(pos, Fluids.WATER) ? 1 : 0);
                scheduledChecks = accepted;
                result.complete((long) accepted);
            } catch (Throwable t) { result.completeExceptionally(t); }
        });
        try { return result.get(5, TimeUnit.SECONDS); }
        catch (Exception e) { throw new IllegalStateException("Integrated tick scheduling probe failed", e); }
    }

    public static void appendJson(StringBuilder out, Minecraft mc) {
        var shadow = ShadowServerRegistry.getInstance().get();
        long[] beSnapshots = shadow == null ? new long[4] :
                io.github.limuqy.mc.hassium.compat.ShadowPoiGate.callExclusive(shadow, () -> {
                    long[] counts = new long[4];
                    for (var entry : shadow.injectedChunkEntries()) {
                        io.github.limuqy.mc.hassium.shadow.light.ShadowLightCompute.withChunkLock(entry.getValue().getPos(), () -> {
                            var snapshots = io.github.limuqy.mc.hassium.compat.ShadowBlockEntityCompat.snapshots(entry.getValue());
                            counts[0] += snapshots.size();
                            counts[1] += snapshots.restoredCount();
                            counts[2] += snapshots.countType("treasure2:proximity_mobset_spawner");
                            counts[3] += snapshots.restoredCount("treasure2:proximity_mobset_spawner");
                            return null;
                        });
                    }
                    return counts;
                });
        var level = shadow == null || mc.level == null ? null
                : shadow.level(io.github.limuqy.mc.hassium.compat.LevelCompat.getDimensionId(mc.level));
        out.append("  \"isolation\": {\"integrated\": ").append(mc.getSingleplayerServer() != null)
                .append(", \"creativeMode\": ").append(mc.player != null && mc.player.isCreative())
                .append(", \"shadowRunning\": ").append(shadow != null)
                .append(", \"shadowCacheOnly\": ").append(shadow != null && shadow.isCacheOnly())
                .append(", \"shadowBeSnapshots\": ").append(beSnapshots[0])
                .append(", \"shadowBeSnapshotsRestored\": ").append(beSnapshots[1])
                .append(", \"shadowTreasureSpawnerSnapshots\": ").append(beSnapshots[2])
                .append(", \"shadowTreasureSpawnerSnapshotsRestored\": ").append(beSnapshots[3])
                .append(", \"shadowDimensionTypeMatches\": ").append(level != null && level.dimensionType().equals(mc.level.dimensionType()))
                .append(", \"processShadowFlag\": ").append(RuntimeServerContext.isShadowServerContext())
                .append(", \"scheduledChecks\": ").append(scheduledChecks).append("},\n");
    }
}
