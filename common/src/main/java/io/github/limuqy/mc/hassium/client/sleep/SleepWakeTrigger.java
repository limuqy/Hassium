package io.github.limuqy.mc.hassium.client.sleep;

/** Compare with the entry view, so small deliberate turns accumulate and yaw wraps safely. */
public final class SleepWakeTrigger {
    public static final float TURN_DEGREES=20;
    private SleepWakeTrigger() {}
    public static boolean shouldWake(boolean movement, float entryYaw, float entryPitch, float yaw, float pitch) {
        if(movement) return true;
        if(!Float.isFinite(entryYaw) || !Float.isFinite(entryPitch) || !Float.isFinite(yaw) || !Float.isFinite(pitch)) return false;
        double horizontal=Math.IEEEremainder((double)yaw-entryYaw,360);
        double vertical=(double)pitch-entryPitch;
        return Math.hypot(horizontal,vertical)>=TURN_DEGREES;
    }
}
