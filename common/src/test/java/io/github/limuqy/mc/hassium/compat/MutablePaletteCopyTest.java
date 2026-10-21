package io.github.limuqy.mc.hassium.compat;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MutablePaletteCopyTest {
#if MC_VER < MC_1_21_9
    @Test void detachedDeltaCanCrossPaletteBitBoundariesWithoutResizingItsSource() {
        var ids=new net.minecraft.core.IdMapper<String>();
        for(int i=0;i<128;i++) ids.add("state_"+i);
        var original=new net.minecraft.world.level.chunk.PalettedContainer<>(ids,ids.byId(0),net.minecraft.world.level.chunk.PalettedContainer.Strategy.SECTION_STATES);
        for(int i=0;i<16;i++) original.set(i,0,0,ids.byId(i));
        var copy=LevelChunkSectionCompat.copyMutablePalette(original);
        for(int i=16;i<65;i++) copy.set(i%16,i/16,0,ids.byId(i));
        for(int i=0;i<65;i++) assertEquals("state_"+i,copy.get(i%16,i/16,0));
        for(int i=0;i<16;i++) assertEquals("state_"+i,original.get(i,0,0));
        assertEquals("state_0",original.get(0,1,0));
    }
    @Test void initiallySingleValuedCopyCanExpandIndependently() {
        var ids=new net.minecraft.core.IdMapper<String>(); ids.add("air"); ids.add("stone");
        var original=new net.minecraft.world.level.chunk.PalettedContainer<>(ids,"air",net.minecraft.world.level.chunk.PalettedContainer.Strategy.SECTION_STATES);
        var copy=LevelChunkSectionCompat.copyMutablePalette(original); copy.set(3,4,5,"stone");
        assertEquals("stone",copy.get(3,4,5)); assertEquals("air",original.get(3,4,5));
    }
#endif
}
