package io.github.limuqy.mc.hassium.compat;

import io.github.limuqy.mc.hassium.client.sleep.SleepTerrainClient;
import io.github.limuqy.mc.hassium.protocol.sleep.SleepChunkWindow;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;

/** Uses native Storage.replace/unload so all renderers observe the same chunk lifecycle. */
public final class SleepChunkResidency {
    public interface Storage { int hassium$trim(SleepChunkWindow window); }
    private static final Map<ClientChunkCache, WeakReference<Storage>> storages = new WeakHashMap<>();
    private static int removedColumns, removedBlockEntities;
    private SleepChunkResidency() {}

    public static synchronized void register(ClientChunkCache cache, Storage storage) {
        storages.put(cache, new WeakReference<>(storage));
    }
    public static void begin() { removedColumns=removedBlockEntities=0; }
    public static void noteUnloaded(int blockEntities) { removedColumns++; removedBlockEntities+=blockEntities; }
    public static String unloadEvidence() { return "unloadedColumns="+removedColumns+" removedBlockEntities="+removedBlockEntities; }
    private static synchronized Storage storage(ClientChunkCache cache) {
        var reference = storages.get(cache);
        return reference == null ? null : reference.get();
    }
    public static void enforce(Minecraft mc) {
        SleepChunkWindow window = SleepTerrainClient.activeWindow();
        if (window == null || mc.level == null) return;
        ClientChunkCache cache = mc.level.getChunkSource();
        if (!io.github.limuqy.mc.hassium.compat.mods.FarsightSleepCompat.trim(cache, window)) {
            Storage storage = storage(cache);
            if (storage == null) throw new IllegalStateException("Missing native client chunk storage lifecycle");
            storage.hassium$trim(window);
        }
        // Trim BEFORE shrinking: vanilla updateViewRadius drops old references without unload callbacks.
        cache.updateViewRadius(1);
    }
    /** Recreate inactive halo BEs from the wake snapshot before those columns become visible. */
    public static void releaseNeighbours(Minecraft mc) {
        SleepChunkWindow window=SleepTerrainClient.activeWindow();
        if(window==null || mc.level==null) return;
        ClientChunkCache cache=mc.level.getChunkSource();
        for(int x=window.x()-2;x<=window.x()+2;x++) for(int z=window.z()-2;z<=window.z()+2;z++) {
            if(window.visible(x,z)) continue;
#if MC_VER < MC_1_21_1
            cache.drop(x,z);
#else
            cache.drop(new net.minecraft.world.level.ChunkPos(x,z));
#endif
        }
    }
    public static int effectiveRenderDistance(int normal) {
        return SleepTerrainClient.restricted() ? 2 : normal;
    }
}
