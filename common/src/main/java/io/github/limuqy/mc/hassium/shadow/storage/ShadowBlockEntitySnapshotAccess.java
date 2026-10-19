package io.github.limuqy.mc.hassium.shadow.storage;

/** Per-column ownership: snapshots disappear with the actual chunk, without a global side table. */
public interface ShadowBlockEntitySnapshotAccess {
    ShadowBlockEntitySnapshots hassium$blockEntitySnapshots();
}
