package io.github.limuqy.mc.hassium.client.sleep;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SleepWakeTriggerTest {
    @Test void movementWakesWithoutChangingView() {
        assertTrue(SleepWakeTrigger.shouldWake(true,0,0,0,0));
        assertFalse(SleepWakeTrigger.shouldWake(false,0,0,0,0));
    }
    @Test void smallTurnsStayAsleepUntilCumulativeEntryOffsetReachesThreshold() {
        assertFalse(SleepWakeTrigger.shouldWake(false,10,5,29.9f,5));
        assertTrue(SleepWakeTrigger.shouldWake(false,10,5,30,5));
        assertTrue(SleepWakeTrigger.shouldWake(false,10,5,-10,5));
        assertTrue(SleepWakeTrigger.shouldWake(false,10,5,10,25));
        assertTrue(SleepWakeTrigger.shouldWake(false,10,5,25,20));
    }
    @Test void yawWrapAndMultipleRevolutionsAreNotFalseTurns() {
        assertFalse(SleepWakeTrigger.shouldWake(false,179,0,-179,0));
        assertFalse(SleepWakeTrigger.shouldWake(false,0,0,720,0));
        assertTrue(SleepWakeTrigger.shouldWake(false,179,0,-160,0));
    }
    @Test void invalidViewDoesNotAccidentallyWakeButMovementStillWorks() {
        assertFalse(SleepWakeTrigger.shouldWake(false,0,0,Float.NaN,0));
        assertFalse(SleepWakeTrigger.shouldWake(false,0,0,0,Float.POSITIVE_INFINITY));
        assertTrue(SleepWakeTrigger.shouldWake(true,0,0,Float.NaN,0));
    }
}
