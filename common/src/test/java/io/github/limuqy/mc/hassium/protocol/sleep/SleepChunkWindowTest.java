package io.github.limuqy.mc.hassium.protocol.sleep;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SleepChunkWindowTest {
    @Test void exactlyNineVisibleAndTwentyFiveLightColumnsIncludingNegativeCoordinates() {
        var window=new SleepChunkWindow(-1,-7);
        int visible=0,light=0;
        for(int x=-8;x<=8;x++) for(int z=-15;z<=1;z++) {
            if(window.visible(x,z)) {
                visible++;
                for(int dx=-1;dx<=1;dx++) for(int dz=-1;dz<=1;dz++) assertTrue(window.light(x+dx,z+dz));
            }
            if(window.light(x,z)) light++;
        }
        assertEquals(9,visible); assertEquals(25,light);
        assertFalse(window.visible(1,-7)); assertTrue(window.light(1,-7));
    }
    @Test void coordinateOverflowCannotEnterTheWindow() {
        var window=new SleepChunkWindow(Integer.MIN_VALUE,0);
        assertFalse(window.light(Integer.MAX_VALUE,0));
    }
}
