package io.github.limuqy.mc.hassium.protocol.sleep;

import org.junit.jupiter.api.Test;
import io.github.limuqy.mc.hassium.server.sleep.SleepCompatibility;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SleepControlTest {
    @Test void sleepRequiresBothPeersAndCompleteCacheRecovery() {
        int sleep=io.github.limuqy.mc.hassium.protocol.handshake.LoginCaps.SLEEP;
        int required=io.github.limuqy.mc.hassium.protocol.handshake.LoginCaps.PULL_MODE
                |io.github.limuqy.mc.hassium.protocol.handshake.LoginCaps.SHADOW_PULL
                |io.github.limuqy.mc.hassium.protocol.handshake.LoginCaps.PULL_NBT;
        int complete=sleep|required;
        assertEquals(complete,io.github.limuqy.mc.hassium.protocol.handshake.LoginCaps.negotiate(complete,complete));
        assertEquals(required,io.github.limuqy.mc.hassium.protocol.handshake.LoginCaps.negotiate(complete,required));
        for(int dependency:new int[]{io.github.limuqy.mc.hassium.protocol.handshake.LoginCaps.PULL_MODE,
                io.github.limuqy.mc.hassium.protocol.handshake.LoginCaps.SHADOW_PULL,
                io.github.limuqy.mc.hassium.protocol.handshake.LoginCaps.PULL_NBT}) {
            assertEquals(required & ~dependency,io.github.limuqy.mc.hassium.protocol.handshake.LoginCaps.negotiate(complete,complete & ~dependency));
            assertEquals(required & ~dependency,io.github.limuqy.mc.hassium.protocol.handshake.LoginCaps.negotiate(complete & ~dependency,complete));
        }
        assertEquals(0,io.github.limuqy.mc.hassium.protocol.handshake.LoginCaps.negotiate(sleep,sleep));
    }
    @Test void everyOperationRoundTripsWithoutLosingSessionIdentity() {
        for(var op:SleepControl.Op.values()) {
            var message=new SleepControl.Message(op,Long.MAX_VALUE,Long.MAX_VALUE,"恢复 / test");
            assertEquals(message,SleepControl.decode(SleepControl.encode(message)));
        }
    }
    @Test void malformedFramesNeverBecomeControls() {
        var bytes=SleepControl.encode(new SleepControl.Message(SleepControl.Op.ENTER,0,1,""));
        assertThrows(RuntimeException.class,()->SleepControl.decode(Arrays.copyOf(bytes,bytes.length-1)));
        assertThrows(RuntimeException.class,()->SleepControl.decode(Arrays.copyOf(bytes,bytes.length+1)));
        bytes[0]=127;
        assertThrows(IllegalArgumentException.class,()->SleepControl.decode(bytes));
        assertThrows(IllegalArgumentException.class,()->SleepControl.decode(new byte[1025]));
    }
    @Test void controlBoundsRejectInvalidGenerationsSequencesAndFields() {
        assertThrows(IllegalArgumentException.class,()->new SleepControl.Message(SleepControl.Op.ENTER,-1,1,""));
        assertThrows(IllegalArgumentException.class,()->new SleepControl.Message(SleepControl.Op.ENTER,0,0,""));
        assertThrows(IllegalArgumentException.class,()->new SleepControl.Message(SleepControl.Op.ERROR,1,1,"x".repeat(257)));
    }
    @Test void unreviewedServerModsBlockSleepInsteadOfDroppingTheirState() {
        assertNull(SleepCompatibility.firstUnsupported(List.of("minecraft","neoforge","hassium")));
        assertTrue(SleepCompatibility.firstUnsupported(List.of("hassium","unknown_factory")).contains("unknown_factory"));
    }
}
