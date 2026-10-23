package io.github.limuqy.mc.hassium.compat;

import io.github.limuqy.mc.hassium.mixin.client.MixinSleepChunkStorage;
import io.github.limuqy.mc.hassium.protocol.sleep.SleepChunkWindow;
import java.util.*;
import java.util.concurrent.atomic.AtomicReferenceArray;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class SleepChunkResidencyTest {
    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();
    }
    @Test void retainsTheNativeLightAndMeshingHaloButUnloadsEveryDistantSlot() throws Exception {
        var fixture=fixture();
        var window=new SleepChunkWindow(-4,-3);
        List<LevelChunk> retained=new ArrayList<>();
        int slot=0;
        for(int x=-7;x<=-1;x++) for(int z=-6;z<=0;z++) {
            var chunk=chunk(x,z); fixture.array.set(slot++,chunk);
            if(window.light(x,z)) retained.add(chunk);
        }
        fixture.array.set(99,chunk(2000,-2000)); // Outside even the old native view: must still unload.
        assertEquals(25,fixture.hassium$trim(window));
        assertEquals(25,fixture.unloaded.size());
        assertEquals(retained,values(fixture.array));
        assertEquals(0,fixture.hassium$trim(window));
        assertEquals(25,fixture.unloaded.size(),"No duplicate native unload callbacks");
    }
    @Test void aLateRefillIsRemovedWhileTheNineRetainedInstancesStayIdentical() throws Exception {
        var fixture=fixture();var window=new SleepChunkWindow(0,0);
        var center=chunk(0,0);fixture.array.set(0,center);
        assertEquals(0,fixture.hassium$trim(window));
        var late=chunk(3,0);fixture.array.set(80,late);
        assertEquals(1,fixture.hassium$trim(window));
        assertSame(center,fixture.array.get(0));assertNull(fixture.array.get(80));
        assertEquals(List.of(late),fixture.unloaded);
    }
    private static List<LevelChunk> values(AtomicReferenceArray<LevelChunk> array) {
        List<LevelChunk> result=new ArrayList<>();
        for(int i=0;i<array.length();i++) if(array.get(i)!=null) result.add(array.get(i));
        return result;
    }
    private static Fixture fixture() throws Exception {
        var field=Arrays.stream(MixinSleepChunkStorage.class.getDeclaredFields())
                .filter(f->f.getName().equals("chunks")).findFirst();
        Assumptions.assumeTrue(field.isPresent(),"Sleep residency is implemented on the two supported anchors");
        var fixture=new Fixture();field.get().setAccessible(true);field.get().set(fixture,fixture.array);return fixture;
    }
    private static LevelChunk chunk(int x,int z) throws Exception {
        var field=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);
        var chunk=(LevelChunk)((sun.misc.Unsafe)field.get(null)).allocateInstance(LevelChunk.class);
        var pos=ChunkAccess.class.getDeclaredField("chunkPos");pos.setAccessible(true);pos.set(chunk,new ChunkPos(x,z));return chunk;
    }
    private static final class Fixture extends MixinSleepChunkStorage {
        final AtomicReferenceArray<LevelChunk> array=new AtomicReferenceArray<>(100);
        final List<LevelChunk> unloaded=new ArrayList<>();
        protected LevelChunk replace(int index,LevelChunk expected,LevelChunk replacement) {
            assertNull(replacement);assertTrue(array.compareAndSet(index,expected,null));unloaded.add(expected);return expected;
        }
    }
}
