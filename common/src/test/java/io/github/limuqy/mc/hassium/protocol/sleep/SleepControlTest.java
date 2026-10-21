package io.github.limuqy.mc.hassium.protocol.sleep;

import org.junit.jupiter.api.Test;
import io.github.limuqy.mc.hassium.server.sleep.SleepCompatibility;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SleepControlTest {
    @Test void windowRequiresBothPeersAndPullAndInPlaceCapabilities() {
        int window=io.github.limuqy.mc.hassium.protocol.handshake.LoginCaps.SLEEP_WINDOW;
        int base=io.github.limuqy.mc.hassium.protocol.handshake.LoginCaps.SLEEP
                |io.github.limuqy.mc.hassium.protocol.handshake.LoginCaps.SLEEP_IN_PLACE;
        int pull=io.github.limuqy.mc.hassium.protocol.handshake.LoginCaps.PULL_MODE;
        assertEquals(base,io.github.limuqy.mc.hassium.protocol.handshake.LoginCaps.negotiate(base|window,base|window));
        assertEquals(base|pull,io.github.limuqy.mc.hassium.protocol.handshake.LoginCaps.negotiate(base|pull|window,base|pull));
        assertEquals(base|pull|window,io.github.limuqy.mc.hassium.protocol.handshake.LoginCaps.negotiate(base|pull|window,base|pull|window));
        assertEquals(12,SleepControl.Op.RESUME.ordinal());
    }
    @Test void inPlaceWakeRequiresBothPeersAndSleepCapability() {
        int sleep=io.github.limuqy.mc.hassium.protocol.handshake.LoginCaps.SLEEP;
        int hot=io.github.limuqy.mc.hassium.protocol.handshake.LoginCaps.SLEEP_IN_PLACE;
        assertEquals(0,io.github.limuqy.mc.hassium.protocol.handshake.LoginCaps.negotiate(hot,hot));
        assertEquals(sleep,io.github.limuqy.mc.hassium.protocol.handshake.LoginCaps.negotiate(sleep|hot,sleep));
        assertEquals(sleep|hot,io.github.limuqy.mc.hassium.protocol.handshake.LoginCaps.negotiate(sleep|hot,sleep|hot));
        assertEquals(4,SleepControl.Op.WAKE.ordinal());
        assertEquals(11,SleepControl.Op.PROBE.ordinal());
    }
    @Test void wakeContextRequiresSleepAndIsNotInheritedByOrdinaryLogin() {
        int sleep=io.github.limuqy.mc.hassium.protocol.handshake.LoginCaps.SLEEP;
        int wake=io.github.limuqy.mc.hassium.protocol.handshake.LoginCaps.SLEEP_WAKE_ATTEMPT;
        assertEquals(0,io.github.limuqy.mc.hassium.protocol.handshake.LoginCaps.negotiate(wake,wake));
        assertEquals(sleep,io.github.limuqy.mc.hassium.protocol.handshake.LoginCaps.negotiate(sleep|wake,sleep));
        assertEquals(sleep|wake,io.github.limuqy.mc.hassium.protocol.handshake.LoginCaps.negotiate(sleep|wake,sleep|wake));
    }
    @Test void everyOperationRoundTripsWithoutLosingSessionIdentity() {
        for(var op:SleepControl.Op.values()) {
            var message=new SleepControl.Message(op,Long.MAX_VALUE,Long.MAX_VALUE,6.5f,300,"恢复 / test");
            assertEquals(message,SleepControl.decode(SleepControl.encode(message)));
        }
    }
    @Test void malformedFramesNeverBecomeControls() {
        var bytes=SleepControl.encode(new SleepControl.Message(SleepControl.Op.ENTER,0,1,6,0,""));
        assertThrows(RuntimeException.class,()->SleepControl.decode(Arrays.copyOf(bytes,bytes.length-1)));
        assertThrows(RuntimeException.class,()->SleepControl.decode(Arrays.copyOf(bytes,bytes.length+1)));
        bytes[0]=127;
        assertThrows(IllegalArgumentException.class,()->SleepControl.decode(bytes));
        assertThrows(IllegalArgumentException.class,()->SleepControl.decode(new byte[1025]));
    }
    @Test void thresholdsRejectNonFiniteNegativeAndAboveMaximum() {
        for(float bad:new float[]{Float.NaN,Float.NEGATIVE_INFINITY,Float.POSITIVE_INFINITY,-1,20.1f})
            assertFalse(SleepControl.validThreshold(bad,20));
        assertTrue(SleepControl.validThreshold(0,20));
        assertTrue(SleepControl.validThreshold(6.5f,20));
        assertTrue(SleepControl.validThreshold(80,100));
    }
    @Test void equalThresholdTriggersAndZeroDisablesIt() {
        assertTrue(SleepControl.lowHealth(6,6));
        assertTrue(SleepControl.lowHealth(5.5f,6));
        assertFalse(SleepControl.lowHealth(6.5f,6));
        assertFalse(SleepControl.lowHealth(1,0));
        // Death is handled by the real lifecycle, never misrepresented as a rescued hit.
        assertFalse(SleepControl.lowHealth(0,6));
        assertTrue(SleepControl.lowHealth(1,6)); // actual post-totem health
    }
    @Test void controlBoundsRejectInvalidGenerationsSequencesAndFields() {
        assertThrows(IllegalArgumentException.class,()->new SleepControl.Message(SleepControl.Op.ENTER,-1,1,6,0,""));
        assertThrows(IllegalArgumentException.class,()->new SleepControl.Message(SleepControl.Op.ENTER,0,0,6,0,""));
        assertThrows(IllegalArgumentException.class,()->new SleepControl.Message(SleepControl.Op.ENTER,0,1,Float.NaN,0,""));
        assertThrows(IllegalArgumentException.class,()->new SleepControl.Message(SleepControl.Op.WAKE,1,1,6,3601,""));
        assertThrows(IllegalArgumentException.class,()->new SleepControl.Message(SleepControl.Op.ERROR,1,1,6,0,"x".repeat(257)));
        assertThrows(IllegalArgumentException.class,()->new SleepControl.Message(SleepControl.Op.ENTER,0,1,6,0,"",Float.NaN));
        assertThrows(IllegalArgumentException.class,()->new SleepControl.Message(SleepControl.Op.ENTER,0,1,6,0,"",1025));
    }
    @Test void unreviewedServerModsBlockSleepInsteadOfDroppingTheirState() {
        assertNull(SleepCompatibility.firstUnsupported(List.of("minecraft","neoforge","hassium")));
        assertTrue(SleepCompatibility.firstUnsupported(List.of("hassium","unknown_factory")).contains("unknown_factory"));
    }
}
