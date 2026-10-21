package io.github.limuqy.mc.hassium.client.sleep;

/** A continuous stationary window. Attack/use are deliberately not activity signals. */
public final class AutoSleepTimer {
    private boolean tracking;
    private long since, last;
    private double x, y, z;
    private float yaw, pitch;

    public void reset() { tracking=false; }

    public boolean update(long now, long delayNanos, boolean eligible,
                          double x, double y, double z, float yaw, float pitch) {
        if (!eligible || delayNanos<=0 || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            reset(); return false;
        }
        // Compare to the start of the window so slow cumulative drift cannot look stationary.
        double dx=x-this.x, dy=y-this.y, dz=z-this.z;
        float turn=Math.abs((yaw-this.yaw)%360);
        if (!tracking || now<last || now-last>1_000_000_000L
                || dx*dx+dy*dy+dz*dz>0.0001 || Math.min(turn,360-turn)>0.1f || Math.abs(pitch-this.pitch)>0.1f) {
            tracking=true; since=now;
            this.x=x; this.y=y; this.z=z; this.yaw=yaw; this.pitch=pitch;
        }
        last=now;
        if (now-since<delayNanos) return false;
        reset(); return true;
    }
}
