package io.github.limuqy.mc.hassium.protocol.egress;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.PacketId;
import io.github.limuqy.mc.hassium.compat.PlayerCompat;
import io.github.limuqy.mc.hassium.compat.ResourceLocationCompat;
import io.github.limuqy.mc.hassium.protocol.HassiumConnectionRegistry;
import io.github.limuqy.mc.hassium.platform.Services;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Explicit development-only typed Mod fixture, sharing one channel across two message structures. */
public final class EgressFixture {
    public static final int COUNT = 512;
    public static final CustomPacketPayload.Type<Payload> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocationCompat.vanilla(new PacketId("egress_fixture", "typed_state")));
    public sealed interface Payload extends CustomPacketPayload permits State, Event {
        long session(); int sequence();
        default CustomPacketPayload.Type<Payload> type() { return TYPE; }
    }
    public record State(long session, int sequence, Holder<Biome> biome, String text, List<Integer> values) implements Payload {}
    public record Event(long session, int sequence, long value) implements Payload {}
    private static final StreamCodec<RegistryFriendlyByteBuf, Holder<Biome>> BIOME = ByteBufCodecs.holderRegistry(Registries.BIOME);
    private static final Set<String> ENCODED = ConcurrentHashMap.newKeySet();
    public static final StreamCodec<RegistryFriendlyByteBuf, Payload> CODEC = StreamCodec.of((buf, payload) -> {
        if (!ENCODED.add(payload.session() + ":" + payload.sequence())) throw new IllegalStateException("Mod fixture codec executed twice");
        buf.writeByte(payload instanceof State ? 0 : 1); buf.writeLong(payload.session()); buf.writeVarInt(payload.sequence());
        if (payload instanceof State state) {
            BIOME.encode(buf, state.biome); buf.writeUtf(state.text, 4096); buf.writeVarInt(state.values.size());
            for (int value : state.values) buf.writeVarInt(value);
        } else buf.writeLong(((Event)payload).value);
    }, buf -> {
        int kind = buf.readUnsignedByte(); long session = buf.readLong(); int sequence = buf.readVarInt();
        if (kind == 1) return new Event(session, sequence, buf.readLong());
        if (kind != 0) throw new IllegalArgumentException("Unknown fixture message kind");
        Holder<Biome> biome = BIOME.decode(buf); String text = buf.readUtf(4096);
        int n = buf.readVarInt(); if (n < 0 || n > 16) throw new IllegalArgumentException("Fixture list length");
        List<Integer> values = new ArrayList<>(); for (int i = 0; i < n; i++) values.add(buf.readVarInt());
        return new State(session, sequence, biome, text, List.copyOf(values));
    });
    public static boolean enabled() {
        return Services.PLATFORM.isDevelopmentEnvironment() && (Set.of("egress", "egressbench").contains(System.getProperty("hassium.smokeScenario", ""))
                || Set.of("egress", "egressbench").contains(System.getProperty("hassium.serverSmokeScenario", "")));
    }
    private static String text(int sequence) { return "opaque-Mod-state-" + "x".repeat(sequence % 150); }
    private static List<Integer> values(int sequence) { List<Integer> values = new ArrayList<>(); for (int i = 0; i < sequence % 9; i++) values.add(sequence + i); return List.copyOf(values); }
    private static final Map<net.minecraft.network.Connection, Integer> SENT = new WeakHashMap<>();
    private static final Map<net.minecraft.network.Connection, Long> SESSIONS = new WeakHashMap<>();
    public static void tickServer(MinecraftServer server) {
        if (!enabled()) return;
        if ("egressbench".equals(System.getProperty("hassium.serverSmokeScenario"))) { EgressBenchmark.tickServer(server); return; }
        for (var player : server.getPlayerList().getPlayers()) {
            var connection = PlayerCompat.getConnection(player);
            if (!HassiumConnectionRegistry.isEnabled(connection)) continue;
            int next = SENT.getOrDefault(connection, 0); if (next >= COUNT) continue;
            long session = SESSIONS.computeIfAbsent(connection, ignored -> System.nanoTime());
            List<net.minecraft.network.protocol.Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener>> bundle = new ArrayList<>();
            for (int i = 0; i < 4 && next < COUNT; i++, next++) {
                Payload payload = next % 2 == 0 ? new State(session, next,
                        player.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS), text(next), values(next))
                        : new Event(session, next, next * 100003L);
                var packet = new ClientboundCustomPayloadPacket(payload);
                if ((next / 4) % 3 == 0) bundle.add(packet);
                else if (next % 17 == 0) {
                    int sequence = next;
                    connection.send(packet, f -> {
                        if (!f.isSuccess()) throw new IllegalStateException("Fixture send callback failed", f.cause());
                        Constants.LOG.info("[EGRESS_FIXTURE] callback success sequence={}", sequence);
                    });
                } else connection.send(packet);
            }
            if (!bundle.isEmpty()) {
                var packet = new ClientboundBundlePacket(bundle);
                if (next % 24 == 4) connection.send(packet, f -> {
                    if (!f.isSuccess()) throw new IllegalStateException("Fixture bundle callback failed", f.cause());
                    Constants.LOG.info("[EGRESS_FIXTURE] bundle callback success");
                });
                else connection.send(packet);
            }
            SENT.put(connection, next);
            if (next == COUNT) Constants.LOG.info("[EGRESS_FIXTURE] SENT count={} metrics={}", COUNT, EgressPipeline.METRICS.snapshot());
        }
    }
    static Payload sample(long session, int sequence, Holder<Biome> biome) {
        return sequence % 2 == 0 ? new State(session, sequence, biome, text(sequence), values(sequence))
                : new Event(session, sequence, sequence * 100003L);
    }
    private static long clientSession;
    private static int received;
    public static synchronized int receivedCount() { return received; }
    public static synchronized void receive(Payload payload) {
        if (!enabled()) throw new IllegalStateException("Fixture outside test scenario");
        if (payload.session() != clientSession) { if (payload.sequence() != 0) throw new IllegalStateException("Fixture session did not start at zero"); clientSession = payload.session(); received = 0; }
        if (payload.sequence() != received) throw new IllegalStateException("Fixture order/execution mismatch " + received + "/" + payload.sequence());
        if (payload instanceof State state) {
            if (!state.biome.is(Biomes.PLAINS) || !state.text.equals(text(received)) || !state.values.equals(values(received))) throw new IllegalStateException("Fixture registry/string/list mismatch");
        } else if (((Event)payload).value != received * 100003L) throw new IllegalStateException("Fixture event mismatch");
        received++;
        if (received == COUNT) Constants.LOG.info("[EGRESS_FIXTURE] PASS count={} typed state/event, registry, strings, lists, bundles, exactly-once", received);
    }
    private EgressFixture() {}
}
