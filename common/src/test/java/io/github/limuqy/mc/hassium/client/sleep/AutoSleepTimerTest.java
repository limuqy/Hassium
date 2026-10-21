package io.github.limuqy.mc.hassium.client.sleep;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AutoSleepTimerTest {
    private final AutoSleepTimer timer=new AutoSleepTimer();
    private boolean sample(long millis,boolean eligible,double x,float yaw) {
        return timer.update(millis*1_000_000L,2_000_000_000L,eligible,x,64,0,yaw,0);
    }
    @Test void continuousStationaryWindowFiresOnceAndStartsFresh() {
        assertFalse(sample(0,true,0,0));
        assertFalse(sample(1000,true,0,0));
        assertTrue(sample(2000,true,0,0));
        assertFalse(sample(2050,true,0,0));
    }
    @Test void slowCumulativeDriftRestartsWindow() {
        assertFalse(sample(0,true,0,0));
        assertFalse(sample(900,true,.009,0));
        assertFalse(sample(1800,true,.018,0));
        assertFalse(sample(2700,true,.018,0));
        assertFalse(sample(3600,true,.018,0));
        assertTrue(sample(3800,true,.018,0));
    }
    @Test void turnResetsButEquivalentWrappedYawDoesNot() {
        assertFalse(sample(0,true,0,179.98f));
        assertFalse(sample(1000,true,0,-180.02f));
        assertTrue(sample(2000,true,0,179.98f));
        assertFalse(sample(3000,true,0,0));
        assertFalse(sample(4000,true,0,1));
        assertFalse(sample(5000,true,0,1));
        assertTrue(sample(6000,true,0,1));
    }
    @Test void screenMovementOrHealthIneligibilityDiscardsPriorIdleTime() {
        assertFalse(sample(0,true,0,0));
        assertFalse(sample(1000,false,0,0));
        assertFalse(sample(2000,true,0,0));
        assertFalse(sample(3000,true,0,0));
        assertTrue(sample(4000,true,0,0));
    }
    @Test void suspendOrClockReversalCannotTriggerImmediateSleep() {
        sample(0,true,0,0); sample(1000,true,0,0);
        assertFalse(sample(10000,true,0,0));
        assertFalse(sample(9999,true,0,0));
        assertFalse(sample(10999,true,0,0));
        assertTrue(sample(11999,true,0,0));
    }
    @Test void disabledAndInvalidPoseNeverAccumulateTime() {
        assertFalse(timer.update(0,0,true,0,64,0,0,0));
        assertFalse(sample(1000,true,Double.NaN,0));
        assertFalse(sample(2000,true,0,Float.NaN));
        assertFalse(sample(3000,true,0,0));
        assertFalse(sample(4000,true,0,0));
        assertTrue(sample(5000,true,0,0));
    }
    @Test void disconnectOrWorldChangeResetStartsWholeWindowAgain() {
        sample(0,true,0,0); sample(1000,true,0,0); timer.reset();
        assertFalse(sample(2000,true,0,0));
        assertFalse(sample(3000,true,0,0));
        assertTrue(sample(4000,true,0,0));
    }
}
