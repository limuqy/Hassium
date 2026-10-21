package io.github.limuqy.mc.hassium.compat;

#if MC_VER >= MC_1_21_1
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
#endif

/** Typed modern carriers; legacy Forge uses its existing SimpleChannel envelope. */
public final class SleepPayloadCompat {
#if MC_VER >= MC_1_21_1
    public record ToClient(byte[] bytes) implements CustomPacketPayload {
        public static final Type<ToClient> TYPE = new Type<>(ResourceLocationCompat.vanilla(HassiumChannels.SLEEP_S2C));
        public static final StreamCodec<FriendlyByteBuf,ToClient> CODEC = StreamCodec.of((b,p)->b.writeByteArray(p.bytes()),b->new ToClient(b.readByteArray(1024)));
        public Type<ToClient> type() { return TYPE; }
    }
    public record ToServer(byte[] bytes) implements CustomPacketPayload {
        public static final Type<ToServer> TYPE = new Type<>(ResourceLocationCompat.vanilla(HassiumChannels.SLEEP_C2S));
        public static final StreamCodec<FriendlyByteBuf,ToServer> CODEC = StreamCodec.of((b,p)->b.writeByteArray(p.bytes()),b->new ToServer(b.readByteArray(1024)));
        public Type<ToServer> type() { return TYPE; }
    }
#endif
    private SleepPayloadCompat() {}
}
