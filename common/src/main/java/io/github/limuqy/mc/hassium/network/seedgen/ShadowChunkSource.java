package io.github.limuqy.mc.hassium.network.seedgen;

/**
 * Source of a shadow chunk before the vanilla light pipeline.
 *
 * <p>This is internal provenance, not a wire value. Packet-backed snapshots
 * enter through {@link ShadowSeedServer#injectPreLight}; an already materialized
 * cache or SeedGen chunk enters through {@link ShadowLightCompute#submitPreLight}.
 * All three paths converge before light computation and client publication.</p>
 */
public enum ShadowChunkSource {
    /**剥光的权威服务端 full snapshot。*/
    REMOTE_FULL(true, false),
    /**影子端 block+light 完整缓存快照。*/
    CACHE_SNAPSHOT(true, true),
    /**满足能力与 pristine 条件的本地 SeedGen 区块。*/
    SEEDGEN(false, true);

    private final boolean packetSnapshot;
    private final boolean localChunk;

    ShadowChunkSource(boolean packetSnapshot, boolean localChunk) {
        this.packetSnapshot = packetSnapshot;
        this.localChunk = localChunk;
    }

    public boolean isPacketSnapshot() {
        return packetSnapshot;
    }

    public boolean isLocalChunk() {
        return localChunk;
    }
}
