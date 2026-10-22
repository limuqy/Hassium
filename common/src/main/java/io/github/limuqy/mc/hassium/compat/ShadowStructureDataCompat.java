package io.github.limuqy.mc.hassium.compat;

import io.github.limuqy.mc.hassium.shadow.server.ShadowSeedServer;
import io.github.limuqy.mc.hassium.shadow.storage.ShadowStructureDataAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;

/** Cache-only worlds preserve structure NBT without resolving worldgen registry IDs. */
public final class ShadowStructureDataCompat {
    private ShadowStructureDataCompat() {}

    public static boolean isCacheOnly(ServerLevel level) {
        return level.getServer() instanceof ShadowSeedServer shadow && shadow.isCacheOnly();
    }

    static CompoundTag forRead(CompoundTag root, boolean cacheOnly) {
        if (!cacheOnly || !root.contains("structures")) return root;
        // Only the root is copied. Sections, light and BE tags keep their native read semantics.
        var readable = new CompoundTag();
#if MC_VER < MC_1_21_5
        var keys = root.getAllKeys();
#else
        var keys = root.keySet();
#endif
        for (String key : keys) {
            if (!key.equals("structures")) readable.put(key, root.get(key));
        }
        return readable;
    }

    static void retain(ChunkAccess chunk, CompoundTag root) {
        if (chunk == null) return;
        Tag raw = root.get("structures");
        access(chunk).hassium$rawStructures(raw == null ? null : raw.copy());
    }

    /** Proto-to-FULL promotion must keep the same opaque metadata as an Imposter unwrap. */
    public static void transfer(ChunkAccess from, ChunkAccess to) {
        Tag raw = access(from).hassium$rawStructures();
        if (raw != null) access(to).hassium$rawStructures(raw.copy());
    }

    public static Tag snapshot(ChunkAccess chunk) {
        Tag raw = access(chunk).hassium$rawStructures();
        return raw == null ? null : raw.copy();
    }

    public static void writeBack(CompoundTag output, Tag raw) {
        if (raw != null) {
            output.put("structures", raw.copy());
            if (isModeProbe() && hasStructureEntries(raw)) {
                io.github.limuqy.mc.hassium.Constants.LOG.info(
                        "HassiumSmokeTest:CACHE_STRUCTURES write x={} z={} rawHash={}",
                        output.get("xPos"), output.get("zPos"), raw.hashCode());
            }
        }
    }

    static void traceRead(ChunkAccess chunk, CompoundTag root, boolean cacheOnly) {
        if (!isModeProbe() || chunk == null || !hasStructureEntries(root.get("structures"))) return;
        int starts = chunk.getAllStarts().size();
        int references = chunk.getAllReferences().size();
        if (cacheOnly && (starts != 0 || references != 0)) {
            throw new IllegalStateException("Cache-only chunk resolved structure metadata: " + chunk.getPos());
        }
        io.github.limuqy.mc.hassium.Constants.LOG.info(
                "HassiumSmokeTest:CACHE_STRUCTURES read pos={} cacheOnly={} starts={} references={} rawHash={}",
                chunk.getPos(), cacheOnly, starts, references, root.get("structures").hashCode());
    }

    private static boolean isModeProbe() {
        return "cachemodes".equals(System.getProperty("hassium.smokeScenario"));
    }

    private static boolean hasStructureEntries(Tag raw) {
        return raw instanceof CompoundTag data
                && ((data.get("starts") instanceof CompoundTag starts && !starts.isEmpty())
                || (data.get("References") instanceof CompoundTag refs && !refs.isEmpty()));
    }

    private static ShadowStructureDataAccess access(ChunkAccess chunk) {
        ChunkAccess target = chunk instanceof ImposterProtoChunk imposter ? imposter.getWrapped() : chunk;
        return (ShadowStructureDataAccess) target;
    }
}
