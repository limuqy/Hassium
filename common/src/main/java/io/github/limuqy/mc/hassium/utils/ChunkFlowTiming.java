package io.github.limuqy.mc.hassium.utils;

/** Render-drain timing facade. Enable with -Dhassium.chunkProfile=true. */
public final class ChunkFlowTiming {
    private ChunkFlowTiming() {}
    public static boolean enabled() { return ChunkPipelineProfile.ENABLED; }
    public static void noteFrame() { ChunkPipelineProfile.frame(); }
    public static void recordApplyWall(long ns, int readyBacklog) {
        ChunkPipelineProfile.record(ChunkPipelineProfile.Source.CLIENT, ChunkPipelineProfile.Stage.APPLY_WALL, ns);
        ChunkPipelineProfile.peak(ChunkPipelineProfile.Counter.READY_PEAK, readyBacklog);
    }
    public static void flush() { ChunkPipelineProfile.flushClient(); }
}
