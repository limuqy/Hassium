package io.github.limuqy.mc.hassium.compat;

import io.github.limuqy.mc.hassium.protocol.egress.EgressFixture;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.*;
#if MC_VER >= MC_1_21_1
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
#endif

/** Loader/version differences for the development fixture, kept outside the byte algorithms. */
public final class EgressFixtureCompat {
    public static Mob pig(Level level) {
#if MC_VER >= MC_1_21_11
        return new net.minecraft.world.entity.animal.pig.Pig(net.minecraft.world.entity.EntityType.PIG, level);
#else
        return new net.minecraft.world.entity.animal.Pig(net.minecraft.world.entity.EntityType.PIG, level);
#endif
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener> fixturePacket(EgressFixture.Payload payload) {
        return (Packet)io.github.limuqy.mc.hassium.platform.Services.NETWORK_MANAGER.createEgressFixtureCarrier(payload);
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Packet<?> bundle(java.util.List<Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener>> packets) {
        return new net.minecraft.network.protocol.game.ClientboundBundlePacket((Iterable)packets);
    }
    public static Holder<Biome> biome(ServerPlayer player) {
        return RegistryCompat.getHolderOrThrow(RegistryCompat.getBiomeRegistry(PlayerCompat.getServerLevel(player).registryAccess()), Biomes.PLAINS);
    }
#if MC_VER >= MC_1_21_1
    public static final CustomPacketPayload.Type<NativePayload> TYPE = new CustomPacketPayload.Type<>(ResourceLocationCompat.vanilla(EgressFixture.CHANNEL));
    private static final StreamCodec<RegistryFriendlyByteBuf, Holder<Biome>> BIOME = ByteBufCodecs.holderRegistry(Registries.BIOME);
    public record NativePayload(EgressFixture.Payload value) implements CustomPacketPayload {
        public CustomPacketPayload.Type<NativePayload> type() { return TYPE; }
    }
    public static final StreamCodec<RegistryFriendlyByteBuf, NativePayload> CODEC = StreamCodec.of(
            (buf, payload) -> EgressFixture.write(buf, payload.value()), buf -> new NativePayload(EgressFixture.read(buf)));
#endif
    public static void receiveOnClient(EgressFixture.Payload payload) {
        var client = net.minecraft.client.Minecraft.getInstance();
        if (client.isSameThread()) EgressFixture.receive(payload);
        else client.execute(() -> EgressFixture.receive(payload));
    }
    public static void writeBiome(FriendlyByteBuf buf, Holder<Biome> holder) {
#if MC_VER >= MC_1_21_1
        if (buf instanceof RegistryFriendlyByteBuf registry) { buf.writeBoolean(true); BIOME.encode(registry, holder); return; }
#endif
        buf.writeBoolean(false);
#if MC_VER >= MC_1_21_11
        buf.writeUtf(holder.unwrapKey().orElseThrow().identifier().toString());
#else
        buf.writeUtf(holder.unwrapKey().orElseThrow().location().toString());
#endif
    }
    public static Holder<Biome> readBiome(FriendlyByteBuf buf) {
#if MC_VER >= MC_1_21_1
        if (buf.readBoolean()) {
            RegistryFriendlyByteBuf registry = buf instanceof RegistryFriendlyByteBuf r ? r : new RegistryFriendlyByteBuf(buf,
                    net.minecraft.client.Minecraft.getInstance().level.registryAccess());
            return BIOME.decode(registry);
        }
#else
        if (buf.readBoolean()) throw new IllegalArgumentException("Registry codec unavailable on legacy fixture");
#endif
        String[] name = buf.readUtf().split(":", 2);
        if (name.length != 2) throw new IllegalArgumentException("Invalid fixture registry key");
        var key = net.minecraft.resources.ResourceKey.create(Registries.BIOME, ResourceLocationCompat.create(name[0], name[1]));
        return RegistryCompat.getHolderOrThrow(RegistryCompat.getBiomeRegistry(net.minecraft.client.Minecraft.getInstance().level.registryAccess()), key);
    }
    public static Packet<?> packet(EgressFixture.Payload payload) {
#if MC_VER >= MC_1_21_1
        return new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(new NativePayload(payload));
#else
        FriendlyByteBuf body = new FriendlyByteBuf(Unpooled.buffer());
        try { EgressFixture.write(body, payload); byte[] data = new byte[body.readableBytes()]; body.readBytes(data); return PacketPayloadCompat.createClientboundPayload(EgressFixture.CHANNEL, data); }
        finally { body.release(); }
#endif
    }
    public static void send(Connection connection, Packet<?> packet, ChannelFutureListener callback) {
#if MC_VER >= MC_1_21_6
        connection.send(packet, callback);
#else
        connection.send(packet, new net.minecraft.network.PacketSendListener() {
            public void onSuccess() { try { callback.operationComplete(io.github.limuqy.mc.hassium.protocol.ConnectionChannelAccess.getConnectionChannel(connection).newSucceededFuture()); } catch (Exception e) { throw new IllegalStateException(e); } }
            public Packet<?> onFailure() { throw new IllegalStateException("Fixture send callback failed"); }
        });
#endif
    }
    public static void freeze(MinecraftServer server, boolean frozen) {
#if MC_VER >= MC_1_21_1
        server.tickRateManager().setFrozen(frozen);
#else
        throw new IllegalStateException("Factory tick acceleration requires Minecraft 1.21.1 or later; the 26.x ZIP cannot be loaded by 2.x Minecraft");
#endif
    }
    public static boolean frozen(MinecraftServer server) {
#if MC_VER >= MC_1_21_1
        return server.tickRateManager().isFrozen();
#else
        throw new IllegalStateException("Factory tick manager unavailable");
#endif
    }
    public static float tickRate(MinecraftServer server) {
#if MC_VER >= MC_1_21_1
        return server.tickRateManager().tickrate();
#else
        throw new IllegalStateException("Factory tick manager unavailable");
#endif
    }
    private EgressFixtureCompat() {}
}
