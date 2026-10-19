package io.github.limuqy.mc.hassium.compat;

import io.github.limuqy.mc.hassium.shadow.storage.ShadowBlockEntitySnapshotAccess;
import io.github.limuqy.mc.hassium.shadow.storage.ShadowBlockEntitySnapshots;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.chunk.LevelChunk;

/** Retains received BE tags, including null/empty tags, without invoking gameplay save callbacks. */
public final class ShadowBlockEntityCompat {
    private ShadowBlockEntityCompat() {}

    public static ShadowBlockEntitySnapshots snapshots(LevelChunk chunk) {
        return ((ShadowBlockEntitySnapshotAccess) chunk).hassium$blockEntitySnapshots();
    }

    public static void capture(LevelChunk chunk, ClientboundLevelChunkPacketData data) {
        var snapshots = snapshots(chunk);
        snapshots.clear();
        ChunkPacketDataCompat.forEachBlockEntityTag(data, chunk.getPos().x(), chunk.getPos().z(), (pos, type, tag) ->
                snapshots.put(pos, BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type).toString(), tag));
    }

    /** Marked entries survive native chunk read/promotion; old complete server NBT keeps its own path. */
    public static void restore(LevelChunk chunk, CompoundTag root) {
        restore(snapshots(chunk), root);
    }

    public static void restore(ShadowBlockEntitySnapshots snapshots, CompoundTag root) {
        if (!(root.get("block_entities") instanceof ListTag list)) return;
        for (Tag entry : list) {
            if (!(entry instanceof CompoundTag tag) || !tag.contains(ShadowBlockEntitySnapshots.MARKER)) continue;
            BlockPos pos = new BlockPos(tag.getIntOr("x", 0), tag.getIntOr("y", 0), tag.getIntOr("z", 0));
            String type = tag.getStringOr("id", "");
            snapshots.restore(pos, type, tag);
        }
    }
}
