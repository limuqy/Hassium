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
        data.getBlockEntitiesTagsConsumer(chunk.getPos().x, chunk.getPos().z).accept((pos, type, tag) ->
                snapshots.put(pos, BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type).toString(), tag));
    }

    /** Caller owns the chunk's execution thread; preserves existing BE instances where possible. */
    public static void applySnapshot(LevelChunk chunk,
            java.util.List<io.github.limuqy.mc.hassium.protocol.SectionDeltaS2CPacket.BlockEntityData> entries) {
        var positions = new java.util.HashSet<net.minecraft.core.BlockPos>();
        // Validate the entire projection before changing either map.
        for (var entry : entries) {
            var type = BuiltInRegistries.BLOCK_ENTITY_TYPE.getOptional(ResourceLocationCompat.create(entry.type()));
            if (entry.pos().getX() >> 4 != chunk.getPos().x || entry.pos().getZ() >> 4 != chunk.getPos().z
                    || !positions.add(entry.pos()) || type.isEmpty() || !type.get().isValid(chunk.getBlockState(entry.pos())))
                throw new IllegalArgumentException("Block entity snapshot does not match block baseline");
        }
        for (var pos : new java.util.ArrayList<>(chunk.getBlockEntitiesPos()))
            if (!positions.contains(pos)) chunk.removeBlockEntity(pos);
        ((io.github.limuqy.mc.hassium.mixin.shadow.LevelChunkPendingBeAccessor) chunk)
                .hassium$getPendingBlockEntities().keySet().removeIf(pos -> !positions.contains(pos));
        snapshots(chunk).clear();
        for (var entry : entries) {
            var be = chunk.getBlockEntity(entry.pos(), LevelChunk.EntityCreationType.IMMEDIATE);
            if (be != null && !entry.type().equals(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()).toString())) {
                chunk.removeBlockEntity(entry.pos());
                be = chunk.getBlockEntity(entry.pos(), LevelChunk.EntityCreationType.IMMEDIATE);
            }
            if (be == null) throw new IllegalStateException("Cannot create snapshot block entity");
            if (entry.nbt() != null) BlockEntityCompat.loadFromTag(be, entry.nbt(), chunk.getLevel().registryAccess());
            snapshots(chunk).put(entry.pos(), entry.type(), entry.nbt());
        }
    }

    /** Marked entries survive native chunk read/promotion; old complete server NBT keeps its own path. */
    public static void restore(LevelChunk chunk, CompoundTag root) {
        restore(snapshots(chunk), root);
    }

    public static void restore(ShadowBlockEntitySnapshots snapshots, CompoundTag root) {
        if (!(root.get("block_entities") instanceof ListTag list)) return;
        for (Tag entry : list) {
            if (!(entry instanceof CompoundTag tag) || !tag.contains(ShadowBlockEntitySnapshots.MARKER)) continue;
#if MC_VER < MC_1_21_5
            BlockPos pos = new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
            String type = tag.getString("id");
#else
            BlockPos pos = new BlockPos(tag.getIntOr("x", 0), tag.getIntOr("y", 0), tag.getIntOr("z", 0));
            String type = tag.getStringOr("id", "");
#endif
            snapshots.restore(pos, type, tag);
        }
    }
}
