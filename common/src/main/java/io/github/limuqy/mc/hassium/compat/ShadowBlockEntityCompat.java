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
import net.minecraft.world.level.chunk.ChunkAccess;

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

    /**
     * Completes vanilla removeBlockEntity for shadow columns that are not loaded into a ticking world.
     * Call AFTER vanilla so loaded columns still unregister their listeners and tickers normally.
     * Caller must own the shadow loop/chunk mutation context; no world lookup or locking here.
     */
    public static void finishRemoval(LevelChunk chunk, BlockPos pos) {
        var removed = chunk.getBlockEntities().remove(pos);
        if (removed != null) removed.setRemoved();
        ((io.github.limuqy.mc.hassium.mixin.shadow.LevelChunkPendingBeAccessor) chunk)
                .hassium$getPendingBlockEntities().remove(pos);
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
        // The authority supplies the WHOLE projection. Even retained positions must not promote
        // a stale local-generation pending tag before the new type/tag is applied.
        ((io.github.limuqy.mc.hassium.mixin.shadow.LevelChunkPendingBeAccessor) chunk)
                .hassium$getPendingBlockEntities().clear();
        snapshots(chunk).clear();
        for (var entry : entries) {
            var be = chunk.getBlockEntity(entry.pos(), LevelChunk.EntityCreationType.IMMEDIATE);
            if (be != null && !entry.type().equals(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()).toString())) {
                chunk.removeBlockEntity(entry.pos());
                be = chunk.getBlockEntity(entry.pos(), LevelChunk.EntityCreationType.IMMEDIATE);
            }
            if (be == null) throw new IllegalStateException("Cannot create snapshot block entity");
            be.setBlockState(chunk.getBlockState(entry.pos()));
            if (entry.nbt() != null) BlockEntityCompat.loadFromTag(be, entry.nbt(), chunk.getLevel().registryAccess());
            snapshots(chunk).put(entry.pos(), entry.type(), entry.nbt());
        }
    }

    /** Repair old cache NBT against the decoded block baseline, before any post-load BE creation. */
    public static int pruneInconsistentSavedTags(ChunkAccess chunk, CompoundTag root) {
        if (!(root.get("block_entities") instanceof ListTag list)) return 0;
        int removed = 0;
        for (int i = list.size() - 1; i >= 0; i--) {
            Tag entry = list.get(i);
            if (!(entry instanceof CompoundTag tag)) continue;
            BlockPos pos = savedPosition(tag);
            if (pos.getX() >> 4 != chunk.getPos().x || pos.getZ() >> 4 != chunk.getPos().z
                    || !BlockEntityCompat.canPromote(tag, chunk.getBlockState(pos))) {
                list.remove(i);
                removed++;
            }
        }
        return removed;
    }

    private static BlockPos savedPosition(CompoundTag tag) {
#if MC_VER < MC_1_21_5
        return new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
#else
        return new BlockPos(tag.getIntOr("x", 0), tag.getIntOr("y", 0), tag.getIntOr("z", 0));
#endif
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
            String type = tag.getString("id");
#else
            String type = tag.getStringOr("id", "");
#endif
            snapshots.restore(savedPosition(tag), type, tag);
        }
    }
}
