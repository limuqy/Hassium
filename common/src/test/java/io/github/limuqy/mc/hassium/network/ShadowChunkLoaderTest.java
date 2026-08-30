package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.network.seedgen.ShadowChunkSource;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShadowChunkLoaderTest {

    @Test
    @DisplayName("desired set contains visible chunks plus one light halo")
    void buildsVisibleAndHaloWorkset() {
        ShadowChunkLoader loader = new ShadowChunkLoader();
        loader.updateView("minecraft:overworld", 0, 0, 1);

        assertEquals(9, countRole(loader, ShadowChunkRole.VISIBLE));
        assertEquals(16, countRole(loader, ShadowChunkRole.HALO));
        assertEquals(25, loader.desired().size());
    }

    @Test
    @DisplayName("movement preserves completed overlap chunks")
    void preservesReadyOverlapOnMovement() {
        ShadowChunkLoader loader = new ShadowChunkLoader();
        loader.updateView("minecraft:overworld", 0, 0, 1);
        ShadowChunkLoader.ChunkKey overlap =
                new ShadowChunkLoader.ChunkKey("minecraft:overworld", 0, 0);
        ShadowChunkLoader.LoadTicket ticket = loader.beginLoad(overlap, false, false, false).orElseThrow();
        assertEquals(ShadowChunkLoader.Completion.READY, loader.complete(ticket, true));

        loader.updateView("minecraft:overworld", 1, 0, 1);

        assertEquals(ShadowChunkLoader.State.READY, loader.state(overlap).orElseThrow());
        assertEquals(0, loader.inFlight());
        assertEquals(ShadowChunkLoader.State.DESIRED,
                loader.state(new ShadowChunkLoader.ChunkKey("minecraft:overworld", 3, 0)).orElseThrow());
    }

    @Test
    @DisplayName("source priority prefers complete cache then pristine SeedGen then remote full")
    void choosesSourceByCandidateValidity() {
        assertEquals(ShadowChunkSource.CACHE_SNAPSHOT,
                ShadowChunkLoader.chooseSource(true, true, true));
        assertEquals(ShadowChunkSource.SEEDGEN,
                ShadowChunkLoader.chooseSource(false, true, true));
        assertEquals(ShadowChunkSource.REMOTE_FULL,
                ShadowChunkLoader.chooseSource(false, true, false));
        assertEquals(ShadowChunkSource.REMOTE_FULL,
                ShadowChunkLoader.chooseSource(false, false, true));
    }

    @Test
    @DisplayName("old epoch completion is stale and cancellation frees bounded capacity")
    void invalidatesOldEpochAndCancelsLoad() {
        ShadowChunkLoader loader = new ShadowChunkLoader(1);
        loader.updateView("minecraft:overworld", 0, 0, 0);
        ShadowChunkLoader.ChunkKey key = new ShadowChunkLoader.ChunkKey("minecraft:overworld", 0, 0);
        ShadowChunkLoader.LoadTicket ticket = loader.beginLoad(key, false, false, false).orElseThrow();
        assertEquals(1, loader.inFlight());

        loader.updateView("minecraft:overworld", 10, 10, 0);
        assertEquals(ShadowChunkLoader.Completion.STALE, loader.complete(ticket, true));
        assertEquals(0, loader.inFlight());

        ShadowChunkLoader.ChunkKey newKey = new ShadowChunkLoader.ChunkKey("minecraft:overworld", 10, 10);
        ShadowChunkLoader.LoadTicket newTicket = loader.beginLoad(newKey, false, false, false).orElseThrow();
        assertTrue(loader.cancel(newKey));
        assertEquals(0, loader.inFlight());
        assertEquals(ShadowChunkLoader.Completion.DUPLICATE, loader.complete(newTicket, true));
        assertEquals(ShadowChunkLoader.State.CANCELLED, loader.state(newKey).orElseThrow());
    }

    @Test
    @DisplayName("reset invalidates outstanding tickets and clears the desired set")
    void resetInvalidatesOutstandingTickets() {
        ShadowChunkLoader loader = new ShadowChunkLoader(1);
        long oldEpoch = loader.updateView("minecraft:overworld", 0, 0, 0);
        ShadowChunkLoader.ChunkKey key = new ShadowChunkLoader.ChunkKey("minecraft:overworld", 0, 0);
        ShadowChunkLoader.LoadTicket ticket = loader.beginLoad(key, false, false, false).orElseThrow();

        loader.reset();

        assertTrue(loader.epoch() > oldEpoch);
        assertTrue(loader.desired().isEmpty());
        assertEquals(0, loader.inFlight());
        assertEquals(ShadowChunkLoader.Completion.STALE, loader.complete(ticket, true));
    }

    @Test
    @DisplayName("failed load retries once and duplicate completion cannot publish")
    void retriesFailedLoadAndRejectsDuplicateCompletion() {
        ShadowChunkLoader loader = new ShadowChunkLoader();
        loader.updateView("minecraft:overworld", 0, 0, 0);
        ShadowChunkLoader.ChunkKey key = new ShadowChunkLoader.ChunkKey("minecraft:overworld", 0, 0);
        ShadowChunkLoader.LoadTicket ticket = loader.beginLoad(key, true, false, false).orElseThrow();

        assertEquals(ShadowChunkLoader.Completion.RETRY, loader.complete(ticket, false));
        assertEquals(ShadowChunkLoader.State.DESIRED, loader.state(key).orElseThrow());
        ShadowChunkLoader.LoadTicket retry = loader.beginLoad(key, true, false, false).orElseThrow();
        assertEquals(ShadowChunkLoader.Completion.READY, loader.complete(retry, true));
        assertEquals(ShadowChunkLoader.Completion.DUPLICATE, loader.complete(retry, true));
        assertEquals(ShadowChunkLoader.State.READY, loader.state(key).orElseThrow());
    }

    @Test
    @DisplayName("unload has an explicit cancellation boundary")
    void unloadRequiresExplicitFinish() {
        ShadowChunkLoader loader = new ShadowChunkLoader();
        loader.updateView("minecraft:overworld", 0, 0, 0);
        ShadowChunkLoader.ChunkKey key = new ShadowChunkLoader.ChunkKey("minecraft:overworld", 0, 0);

        assertTrue(loader.requestUnload(key));
        assertFalse(loader.requestUnload(key));
        assertEquals(ShadowChunkLoader.State.UNLOAD_PENDING, loader.state(key).orElseThrow());
        assertTrue(loader.finishUnload(key));
        assertTrue(loader.state(key).isEmpty());
    }

    private static long countRole(ShadowChunkLoader loader, ShadowChunkRole role) {
        return loader.desired().stream()
                .map(loader::role)
                .map(Optional::orElseThrow)
                .filter(role::equals)
                .count();
    }
}
