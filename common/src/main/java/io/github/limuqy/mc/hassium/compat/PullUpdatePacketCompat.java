package io.github.limuqy.mc.hassium.compat;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.world.level.ChunkPos;

/** The three native state mutations mirrored into the shadow owner on every supported version. */
public final class PullUpdatePacketCompat {
    private PullUpdatePacketCompat() {}
    public static ChunkPos column(Packet<?> packet) {
        if (packet instanceof ClientboundBlockUpdatePacket block) return new ChunkPos(block.getPos());
        if (packet instanceof ClientboundBlockEntityDataPacket be) return new ChunkPos(be.getPos());
        if (packet instanceof ClientboundSectionBlocksUpdatePacket section) {
            ChunkPos[] column = {null};
            section.runUpdates((pos, state) -> { if (column[0] == null) column[0] = new ChunkPos(pos); });
            return column[0];
        }
        return null;
    }
    public static Iterable<? extends Packet<?>> children(Packet<?> packet) {
        return packet instanceof ClientboundBundlePacket bundle ? bundle.subPackets() : null;
    }
}
