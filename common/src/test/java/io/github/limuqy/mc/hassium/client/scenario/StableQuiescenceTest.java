package io.github.limuqy.mc.hassium.client.scenario;

import io.github.limuqy.mc.hassium.protocol.ColumnSnapshotFence;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StableQuiescenceTest {
    @Test void theRealFixtureKeepsTheHardDeadlineAndRejectsRepairAssistedSameHashOrHaloCleanup() throws Exception {
        java.nio.file.Path root=java.nio.file.Path.of("").toAbsolutePath();
        while(root!=null && !java.nio.file.Files.isDirectory(root.resolve("common/src/main/java"))) root=root.getParent();
        assertNotNull(root);
        String source=java.nio.file.Files.readString(root.resolve(
                "common/src/main/java/io/github/limuqy/mc/hassium/compat/PullOrderingProbeCompat.java"));
        assertTrue(source.contains("if(elapsedMs>=timeoutMs) done=false"));
        assertTrue(source.contains("if((SAME || HALO) && repairs.get()!=repairBaseline)"));
        assertTrue(source.contains("resourceLogCount.getAndIncrement()<512"));
    }
    @Test void anyTargetGlobalOrByteActivityRestartsTheFullStablePeriod() {
        var quiet=new StableQuiescence();
        assertFalse(quiet.update(0,0,0,0));
        assertFalse(quiet.update(249,0,0,0)); assertTrue(quiet.update(250,0,0,0));
        assertFalse(quiet.update(251,1,0,0));
        assertFalse(quiet.update(300,0,0,0)); assertFalse(quiet.update(549,0,0,0));
        assertTrue(quiet.update(550,0,0,0));
        assertFalse(quiet.update(551,0,1,0));
        assertFalse(quiet.update(600,0,0,1));
        assertFalse(quiet.update(700,0,0,0)); assertTrue(quiet.update(950,0,0,0));
    }
    @Test void sixOtherColumnsRemainVisibleUntilTheirActualDrainsReleaseEveryLease() {
        var fence=new ColumnSnapshotFence<String>(10,6,6*ColumnSnapshotFence.RESERVATION_BYTES);
        var quiet=new StableQuiescence();
        for(long key=1;key<=6;key++) {
            assertTrue(fence.begin(key,100+key)); fence.injected(key,fence.incoming(key),new Object());
            fence.append(key,"native",6);
        }
        assertFalse(quiet.update(0,0,fence.activeCount(),fence.reservedBytes()));
        var snapshot=fence.activeStates(); assertEquals(6,snapshot.size());
        for(var state:snapshot) {
            assertEquals(100+state.key(),state.request()); assertTrue(state.bound());
            assertFalse(state.draining()); assertEquals(1,state.packets()); assertEquals(6,state.bytes());
            assertEquals(state.version(),fence.incoming(state.key()),"Observation cannot change a version");
            long token=fence.startDrain(state.key()); assertEquals(1,fence.takeTail(state.key(),token).size());
            assertTrue(fence.finishDrain(state.key(),token));
        }
        assertEquals(0,fence.activeCount()); assertEquals(0,fence.reservedBytes()); assertTrue(fence.activeStates().isEmpty());
        assertFalse(quiet.update(1000,0,fence.activeCount(),fence.reservedBytes()));
        assertFalse(quiet.update(1249,0,0,0)); assertTrue(quiet.update(1250,0,0,0));
        assertEquals(6,snapshot.size(),"Diagnostic snapshot is detached from subsequent cleanup");
        assertThrows(UnsupportedOperationException.class,()->snapshot.clear());
    }
}
