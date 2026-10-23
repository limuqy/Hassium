package io.github.limuqy.mc.hassium.compat.mods;

import io.github.limuqy.mc.hassium.protocol.sleep.SleepChunkWindow;
import java.util.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class FarsightSleepCompatTest {
    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();
    }
    @Test void dropsEveryDistantMapEntryThroughTheBackendCallbackAndPreservesNearbyIdentity() throws Exception {
        var chunks = new LinkedHashMap<Long, LevelChunk>();
        var retained = new ArrayList<LevelChunk>();
        var window = new SleepChunkWindow(-4, -3);
        for (int x = -7; x <= -1; x++) for (int z = -6; z <= 0; z++) {
            var chunk = chunk(x, z); chunks.put(ChunkPos.asLong(x, z), chunk);
            if (window.light(x, z)) retained.add(chunk);
        }
        chunks.put(ChunkPos.asLong(2000, -2000), chunk(2000, -2000));
        var unloaded = new ArrayList<LevelChunk>();
        java.util.function.Consumer<LevelChunk> drop = chunk -> {
            assertSame(chunk, chunks.remove(chunk.getPos().toLong())); unloaded.add(chunk);
        };
        assertEquals(25, FarsightSleepCompat.trim(chunks, window, drop));
        assertEquals(retained, new ArrayList<>(chunks.values()));
        assertEquals(25, unloaded.size());
        assertEquals(0, FarsightSleepCompat.trim(chunks, window, drop));
        var late = chunk(-1, -3); chunks.put(late.getPos().toLong(), late);
        assertEquals(1, FarsightSleepCompat.trim(chunks, window, drop));
        assertEquals(retained, new ArrayList<>(chunks.values()));
        assertEquals(26, unloaded.size());
    }
    private static LevelChunk chunk(int x, int z) throws Exception {
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        var chunk = (LevelChunk)((sun.misc.Unsafe)field.get(null)).allocateInstance(LevelChunk.class);
        var pos = ChunkAccess.class.getDeclaredField("chunkPos"); pos.setAccessible(true); pos.set(chunk, new ChunkPos(x,z));
        return chunk;
    }
}
