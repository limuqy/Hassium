package io.github.limuqy.mc.hassium.network.seedgen;

/** Internal on-disk policy; unlike ShadowChunkRole it is never serialized on the wire. */
enum ShadowChunkPersistenceRole {
    VISIBLE_FULL_LIGHT,
    HALO_BLOCKS_ONLY
}
