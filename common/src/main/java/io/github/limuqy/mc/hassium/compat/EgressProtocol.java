package io.github.limuqy.mc.hassium.compat;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
#if MC_VER >= MC_1_21_1
import net.minecraft.network.ProtocolInfo;
#endif

/** Immutable view of the actual encoder/decoder binding retained by an ordered byte batch. */
public interface EgressProtocol {
    ConnectionProtocol id();
    PacketFlow flow();
    void encode(ByteBuf output, Packet<?> packet);
#if MC_VER >= MC_1_21_1
    static EgressProtocol bound(ProtocolInfo<?> info) { return new Modern(info); }
    record Modern(ProtocolInfo<?> info) implements EgressProtocol {
        public ConnectionProtocol id() { return info.id(); }
        public PacketFlow flow() { return info.flow(); }
        @SuppressWarnings({"rawtypes", "unchecked"})
        public void encode(ByteBuf output, Packet<?> packet) { ((net.minecraft.network.codec.StreamCodec)info.codec()).encode(output, packet); }
    }
#else
    static EgressProtocol bound(ConnectionProtocol protocol, PacketFlow flow) { return protocol == null ? null : new Legacy(protocol, flow); }
    record Legacy(ConnectionProtocol id, PacketFlow flow) implements EgressProtocol {
        public void encode(ByteBuf output, Packet<?> packet) {
            int numericId = id.getPacketId(flow, packet);
            if (numericId < 0) throw new IllegalArgumentException("Unregistered native carrier");
            FriendlyByteBuf buffer = new FriendlyByteBuf(output); buffer.writeVarInt(numericId); packet.write(buffer);
        }
    }
#endif
    static boolean terminal(Packet<?> packet) {
#if MC_VER >= MC_1_21_1
        return packet.isTerminal();
#else
        return ConnectionProtocol.getProtocolForPacket(packet) != ConnectionProtocol.PLAY;
#endif
    }
}
