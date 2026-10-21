package io.github.limuqy.mc.hassium.protocol;

import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;

/** Complete client-visible BE projection accompanying a block-cache hit. Empty is authoritative. */
public final class BlockEntitySnapshot {
    private static final int MAX_ENTRIES = 4096;
    private BlockEntitySnapshot() {}

    public static byte[] encode(List<SectionDeltaS2CPacket.BlockEntityData> entries) {
        if (entries.size() > MAX_ENTRIES) throw new IllegalArgumentException("Too many block entities");
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeVarInt(entries.size());
            for (var entry : entries) {
                buf.writeBlockPos(entry.pos());
                buf.writeUtf(entry.type(), 256);
                buf.writeNbt(entry.nbt());
                if (buf.readableBytes() > ShadowPullResponseS2CPacket.MAX_PAYLOAD_BYTES)
                    throw new IllegalArgumentException("Block entity snapshot exceeds payload limit");
            }
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            return data;
        } finally { buf.release(); }
    }

    public static List<SectionDeltaS2CPacket.BlockEntityData> decode(byte[] data, ChunkPos column) {
        if (data.length == 0 || data.length > ShadowPullResponseS2CPacket.MAX_PAYLOAD_BYTES)
            throw new IllegalArgumentException("Missing or oversized block entity snapshot");
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
        try {
            int count = buf.readVarInt();
            if (count < 0 || count > MAX_ENTRIES) throw new IllegalArgumentException("Invalid BE count");
            var entries = new ArrayList<SectionDeltaS2CPacket.BlockEntityData>(count);
            var positions = new HashSet<net.minecraft.core.BlockPos>();
            for (int i = 0; i < count; i++) {
                var pos = buf.readBlockPos();
                if (pos.getX() >> 4 != column.x || pos.getZ() >> 4 != column.z || !positions.add(pos))
                    throw new IllegalArgumentException("Foreign or duplicate block entity position");
                entries.add(new SectionDeltaS2CPacket.BlockEntityData(pos, buf.readUtf(256), buf.readNbt()));
            }
            if (buf.isReadable()) throw new IllegalArgumentException("Trailing block entity data");
            return List.copyOf(entries);
        } finally { buf.release(); }
    }
}
