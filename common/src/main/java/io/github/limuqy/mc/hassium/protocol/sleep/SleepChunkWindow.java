package io.github.limuqy.mc.hassium.protocol.sleep;

/** Column geometry, independent of the server's simulation and view distances. */
public record SleepChunkWindow(int x, int z) {
    public boolean visible(int cx, int cz) { return contains(cx, cz, 1); }
    public boolean light(int cx, int cz) { return contains(cx, cz, 2); }
    private boolean contains(int cx, int cz, int radius) {
        return Math.abs((long) cx-x)<=radius && Math.abs((long) cz-z)<=radius;
    }
}
