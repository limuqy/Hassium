package io.github.limuqy.mc.hassium.shadow.storage;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

/** Authoritative client NBT is a projection, not a complete server BlockEntity save. */
public final class ShadowBlockEntitySnapshots {
    public static final String MARKER = "hassium:client_nbt";
    private record Snapshot(String type, CompoundTag tag, boolean restored) {}
    private final Map<BlockPos, Snapshot> tags = new HashMap<>();

    public void put(BlockPos pos, String type, CompoundTag updateTag) {
        put(pos, type, updateTag, false);
    }

    public void restore(BlockPos pos, String type, CompoundTag updateTag) {
        put(pos, type, updateTag, true);
    }

    private void put(BlockPos pos, String type, CompoundTag updateTag, boolean restored) {
        CompoundTag tag = updateTag == null ? new CompoundTag() : updateTag.copy();
        tag.putString("id", type);
        tag.putInt("x", pos.getX());
        tag.putInt("y", pos.getY());
        tag.putInt("z", pos.getZ());
        tag.putBoolean("keepPacked", false);
        tag.putBoolean(MARKER, true);
        tags.put(pos.immutable(), new Snapshot(type, tag, restored));
    }

    public boolean contains(BlockPos pos) { return tags.containsKey(pos); }
    public CompoundTag get(BlockPos pos) {
        Snapshot snapshot = tags.get(pos);
        return snapshot == null ? null : snapshot.tag().copy();
    }
    public int size() { return tags.size(); }
    public long restoredCount() { return tags.values().stream().filter(Snapshot::restored).count(); }
    public long restoredCount(String type) { return tags.values().stream().filter(s -> s.restored() && s.type().equals(type)).count(); }
    public long countType(String type) { return tags.values().stream().filter(s -> s.type().equals(type)).count(); }
    public void remove(BlockPos pos) { tags.remove(pos); }
    public void clear() { tags.clear(); }
}
