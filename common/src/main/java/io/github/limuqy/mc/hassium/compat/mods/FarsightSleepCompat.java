package io.github.limuqy.mc.hassium.compat.mods;

import io.github.limuqy.mc.hassium.compat.SleepChunkResidency;
import io.github.limuqy.mc.hassium.protocol.sleep.SleepChunkWindow;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.world.level.chunk.LevelChunk;

/** Farsight replaces vanilla Storage. Use its own drop override, including renderer/unload callbacks. */
public final class FarsightSleepCompat {
    private static final String CACHE = "com.farsight.FarsightClientChunkManager";
    private static final ClassValue<Field> CHUNKS = new ClassValue<>() {
        @Override protected Field computeValue(Class<?> type) {
            try {
                Field field = type.getDeclaredField("chunks");
                field.setAccessible(true);
                return field;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Unsupported Farsight chunk storage", e);
            }
        }
    };
    private FarsightSleepCompat() {}

    public static boolean trim(ClientChunkCache cache, SleepChunkWindow window) {
        if (!CACHE.equals(cache.getClass().getName())) return false;
        try {
            Object storage = CHUNKS.get(cache.getClass()).get(cache);
            if (!(storage instanceof Map<?, ?> chunks)) throw new IllegalStateException("Unsupported Farsight chunk map");
            trim(chunks, window, chunk -> {
                var entities = Boolean.getBoolean("hassium.smokeTest")
                        ? new java.util.ArrayList<>(chunk.getBlockEntities().values()) : null;
#if MC_VER < MC_1_21_1
                cache.drop(chunk.getPos().x, chunk.getPos().z);
#else
                cache.drop(chunk.getPos());
#endif
                if (entities != null) {
                    for (var entity : entities) if (!entity.isRemoved())
                        throw new IllegalStateException("Farsight unload left a live block entity at " + entity.getBlockPos());
                    SleepChunkResidency.noteUnloaded(entities.size());
                }
            });
            return true;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot read Farsight chunk storage", e);
        }
    }

    static int trim(Map<?, ?> chunks, SleepChunkWindow window, Consumer<LevelChunk> unload) {
        int removed = 0;
        // The public drop operation mutates the map; visit a snapshot, including stale out-of-range columns.
        for (Object value : chunks.values().toArray()) {
            if (!(value instanceof LevelChunk chunk)) throw new IllegalStateException("Unexpected Farsight chunk value");
            if (!window.light(chunk.getPos().x, chunk.getPos().z)) {
                unload.accept(chunk);
                removed++;
            }
        }
        return removed;
    }
}
