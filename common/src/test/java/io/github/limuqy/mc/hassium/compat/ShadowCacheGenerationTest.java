package io.github.limuqy.mc.hassium.compat;

import io.github.limuqy.mc.hassium.shadow.server.ShadowSeedServer;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ShadowCacheGenerationTest {
    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test void immutableCacheModeRejectsDirectGenerationBeforeTouchingWorldOrTickets() throws Exception {
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var shell = (ShadowSeedServer) ((sun.misc.Unsafe) field.get(null)).allocateInstance(ShadowSeedServer.class);
        var mode = ShadowSeedServer.class.getDeclaredField("cacheOnly");
        mode.setAccessible(true);
        mode.setBoolean(shell, true);
        assertTrue(shell.isCacheOnly());
        // All world/executor fields are absent: reaching them would fail this test.
        assertNull(shell.generateChunk("fixture:custom", new ChunkPos(0, 0)));
        var completed = new AtomicInteger();
        shell.generateChunkAsync("fixture:custom", new ChunkPos(0, 0), (dimension, chunk) -> {
            assertEquals("fixture:custom", dimension);
            assertNull(chunk);
            completed.incrementAndGet();
        });
        assertEquals(1, completed.get());
    }
}
