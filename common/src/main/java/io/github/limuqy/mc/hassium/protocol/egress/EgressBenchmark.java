package io.github.limuqy.mc.hassium.protocol.egress;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.PlayerCompat;
import io.github.limuqy.mc.hassium.protocol.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import java.util.ArrayList;
import java.util.List;

/** Deterministic stationary current-version sampling, enabled only by the explicit dev smoke scenario. */
final class EgressBenchmark {
    private static long started, session;
    private static int phase = -1, sequence;
    private static final List<Pig> pigs = new ArrayList<>();
    static void tickServer(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayers().stream().filter(p -> HassiumConnectionRegistry.isEnabled(PlayerCompat.getConnection(p))).findFirst().orElse(null);
        if (player == null) return;
        long now = System.currentTimeMillis();
        if (started == 0) { started = now; session = System.nanoTime(); }
        long elapsed = now - started;
        int nextPhase = elapsed < 25000 ? 0 : elapsed < 45000 ? 1 : elapsed < 65000 ? 2 : elapsed < 85000 ? 3 : 4;
        if (nextPhase != phase) {
            phase = nextPhase;
            String name = switch (phase) { case 0 -> "warmup"; case 1 -> "idle"; case 2 -> "entities"; case 3 -> "mods"; default -> "done"; };
            Constants.LOG.info("[EGRESS_BENCH] phase={} timestampMs={} metrics={}", name, now, EgressPipeline.METRICS.snapshot());
            AggregatedPacketExporter.exportPhase(name);
            if (phase == 2) {
                for (int i = 0; i < 128; i++) {
                    Pig pig = io.github.limuqy.mc.hassium.compat.EgressFixtureCompat.pig(player.level()); pig.setNoAi(true);
                    pig.setPos(player.getX() + i % 16 - 8, player.getY() + 2, player.getZ() + i / 16 - 4);
                    player.level().addFreshEntity(pig); pigs.add(pig);
                }
            } else if (phase == 3 || phase == 4) { pigs.forEach(Pig::discard); pigs.clear(); }
        }
        if (phase == 2) {
            for (int i = 0; i < pigs.size(); i++) pigs.get(i).setPos(player.getX() + i % 16 - 8 + Math.sin(elapsed / 300.0),
                    player.getY() + 2, player.getZ() + i / 16 - 4 + Math.cos(elapsed / 300.0));
        } else if (phase == 3) {
            var biome = player.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
            for (int i = 0; i < 100; i++) PlayerCompat.getConnection(player).send(new ClientboundCustomPayloadPacket(EgressFixture.sample(session, sequence++, biome)));
        }
    }
    private EgressBenchmark() {}
}
