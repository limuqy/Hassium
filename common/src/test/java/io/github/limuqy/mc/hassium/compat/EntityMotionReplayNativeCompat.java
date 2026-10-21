package io.github.limuqy.mc.hassium.compat;

import com.google.gson.JsonObject;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
#if MC_VER < MC_1_21_2
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
#endif
import java.util.Arrays;

/** Test-tool only. Calls the actual anchor's native decoder AND encoder. No Mixin/game hook. */
public final class EntityMotionReplayNativeCompat {
    private EntityMotionReplayNativeCompat() {}

    public static String version() {
#if MC_VER < MC_1_21_1
        return "1.20.1";
#elif MC_VER < MC_1_21_2
        return "1.21.1";
#else
        throw new UnsupportedOperationException("R0 oracle supports only 1.20.1 / 1.21.1");
#endif
    }

    public static JsonObject decodeAndRoundTrip(String kind, byte[] raw) {
#if MC_VER >= MC_1_21_2
        throw new UnsupportedOperationException("R0 oracle supports only 1.20.1 / 1.21.1");
#else
        FriendlyByteBuf in = new FriendlyByteBuf(Unpooled.wrappedBuffer(raw));
        FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer());
        try {
            int packetId = in.readVarInt();
            int bodyStart = in.readerIndex();
            int entityId = in.readVarInt();
            in.readerIndex(bodyStart);
            Packet<?> packet;
#if MC_VER < MC_1_21_1
            packet = switch (kind) {
                case "position" -> ClientboundMoveEntityPacket.Pos.read(in);
                case "rotation" -> ClientboundMoveEntityPacket.Rot.read(in);
                case "position_rotation" -> ClientboundMoveEntityPacket.PosRot.read(in);
                case "teleport" -> new ClientboundTeleportEntityPacket(in);
                case "velocity" -> new ClientboundSetEntityMotionPacket(in);
                case "head" -> new ClientboundRotateHeadPacket(in);
                default -> throw new IllegalArgumentException(kind);
            };
            out.writeVarInt(packetId);
            packet.write(out);
#else
            packet = switch (kind) {
                case "position" -> ClientboundMoveEntityPacket.Pos.STREAM_CODEC.decode(in);
                case "rotation" -> ClientboundMoveEntityPacket.Rot.STREAM_CODEC.decode(in);
                case "position_rotation" -> ClientboundMoveEntityPacket.PosRot.STREAM_CODEC.decode(in);
                case "teleport" -> ClientboundTeleportEntityPacket.STREAM_CODEC.decode(in);
                case "velocity" -> ClientboundSetEntityMotionPacket.STREAM_CODEC.decode(in);
                case "head" -> ClientboundRotateHeadPacket.STREAM_CODEC.decode(in);
                default -> throw new IllegalArgumentException(kind);
            };
            out.writeVarInt(packetId);
            switch (kind) {
                case "position" -> ClientboundMoveEntityPacket.Pos.STREAM_CODEC.encode(out, (ClientboundMoveEntityPacket.Pos) packet);
                case "rotation" -> ClientboundMoveEntityPacket.Rot.STREAM_CODEC.encode(out, (ClientboundMoveEntityPacket.Rot) packet);
                case "position_rotation" -> ClientboundMoveEntityPacket.PosRot.STREAM_CODEC.encode(out, (ClientboundMoveEntityPacket.PosRot) packet);
                case "teleport" -> ClientboundTeleportEntityPacket.STREAM_CODEC.encode(out, (ClientboundTeleportEntityPacket) packet);
                case "velocity" -> ClientboundSetEntityMotionPacket.STREAM_CODEC.encode(out, (ClientboundSetEntityMotionPacket) packet);
                case "head" -> ClientboundRotateHeadPacket.STREAM_CODEC.encode(out, (ClientboundRotateHeadPacket) packet);
                default -> throw new IllegalArgumentException(kind);
            }
#endif
            if (in.isReadable()) throw new IllegalStateException("Native decoder left a packet tail");
            byte[] encoded = new byte[out.readableBytes()];
            out.getBytes(out.readerIndex(), encoded);
            if (!Arrays.equals(raw, encoded)) throw new IllegalStateException("Native codec round trip changed bytes");
            JsonObject fields = new JsonObject();
            fields.addProperty("id", entityId);
            if (packet instanceof ClientboundMoveEntityPacket move) {
                if (move.hasPosition()) {
                    fields.addProperty("dx", move.getXa()); fields.addProperty("dy", move.getYa()); fields.addProperty("dz", move.getZa());
                }
                if (move.hasRotation()) {
                    fields.addProperty("yaw", move.getyRot()); fields.addProperty("pitch", move.getxRot());
                }
                fields.addProperty("ground", move.isOnGround());
            } else if (packet instanceof ClientboundTeleportEntityPacket teleport) {
                if (entityId != teleport.getId()) throw new IllegalStateException("Teleport entity ID");
                fields.addProperty("x", teleport.getX()); fields.addProperty("y", teleport.getY()); fields.addProperty("z", teleport.getZ());
                fields.addProperty("yaw", teleport.getyRot()); fields.addProperty("pitch", teleport.getxRot());
                fields.addProperty("ground", teleport.isOnGround());
            } else if (packet instanceof ClientboundSetEntityMotionPacket velocity) {
                if (entityId != velocity.getId()) throw new IllegalStateException("Velocity entity ID");
#if MC_VER < MC_1_21_1
                // Legacy getters expose encoded integers; the client divides by 8000.
                fields.addProperty("vx", velocity.getXa() / 8000.0); fields.addProperty("vy", velocity.getYa() / 8000.0); fields.addProperty("vz", velocity.getZa() / 8000.0);
#else
                fields.addProperty("vx", velocity.getXa()); fields.addProperty("vy", velocity.getYa()); fields.addProperty("vz", velocity.getZa());
#endif
            } else if (packet instanceof ClientboundRotateHeadPacket head) {
                fields.addProperty("head", head.getYHeadRot());
            }
            return fields;
        } finally { in.release(); out.release(); }
#endif
    }
}
