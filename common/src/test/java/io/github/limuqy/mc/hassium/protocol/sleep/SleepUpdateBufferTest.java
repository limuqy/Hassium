package io.github.limuqy.mc.hassium.protocol.sleep;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SleepUpdateBufferTest {
    @Test void preservesSnapshotTailOrder() {
        var buffer=new SleepUpdateBuffer<String>(3,9);
        buffer.add("block",4); buffer.add("light",3); buffer.add("nbt",2);
        assertEquals(List.of("block","light","nbt"),buffer.values());
        assertEquals(9,buffer.bytes()); assertFalse(buffer.overflowed());
    }
    @Test void overflowDiscardsTheEntireTailAndCannotResumeAppending() {
        var buffer=new SleepUpdateBuffer<String>(10,5);
        buffer.add("before",3); buffer.add("too large",3); buffer.add("later",1);
        assertTrue(buffer.overflowed()); assertTrue(buffer.values().isEmpty()); assertEquals(0,buffer.bytes());
    }
    @Test void zeroLengthPacketsStillRespectTheCountLimit() {
        var buffer=new SleepUpdateBuffer<String>(1,100);
        buffer.add("first",0); buffer.add("second",0);
        assertTrue(buffer.overflowed()); assertEquals(0,buffer.size());
    }
}
