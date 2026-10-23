package io.github.limuqy.mc.hassium.protocol;

import io.github.limuqy.mc.hassium.concurrent.AsyncSnapshotCommit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShadowPullUnchangedTokenTest {
    @Test void unchangedNbtUsesCapturedOpaqueVersionToReachOwnerCommitAndClientAck() {
        // Wire ids and process-wide versions deliberately differ, as after a real sleep recovery.
        for(int i=0;i<64;i++) SnapshotTokens.next();
        long wire=1;
        var versions=new ColumnSnapshotFence<String>(4,1,ColumnSnapshotFence.RESERVATION_BYTES);
        long captured=versions.incoming(3);
        assertNotEquals(wire,captured);
        ArrayDeque<Runnable> owner=new ArrayDeque<>(),client=new ArrayDeque<>();
        List<String> events=new ArrayList<>();
        // This was the production bug: AsyncSnapshotCommit discards immediately and no ACK follows.
        AsyncSnapshotCommit.submit(owner::add,client::add,()->versions.pending(3,wire),
                ()->fail("Wire id admitted as an opaque version"),()->fail(),()->fail(),t->fail(t),()->events.add("discarded"));
        assertEquals(List.of("discarded"),events); assertTrue(owner.isEmpty());
        events.clear();
        AsyncSnapshotCommit.submit(owner::add,client::add,()->versions.pending(3,captured),
                ()->events.add("visible NBT"),()->events.add("owner NBT"),()->{
                    events.add("COLUMN_APPLIED wire="+wire); events.add("COLUMN_READY");
                },t->fail(t),()->fail("Current UNCHANGED commit was cancelled"));
        assertEquals(List.of("visible NBT"),events);
        owner.remove().run();
        assertEquals(List.of("visible NBT","owner NBT"),events,"No ACK ahead of the owner commit");
        client.remove().run();
        assertEquals(List.of("visible NBT","owner NBT","COLUMN_APPLIED wire=1","COLUMN_READY"),events);
    }
    @Test void replacementOrDisconnectStillCancelsTheCapturedUnchangedCommitWithoutAck() {
        for(boolean disconnect:new boolean[]{false,true}) {
            var versions=new ColumnSnapshotFence<String>(4,1,ColumnSnapshotFence.RESERVATION_BYTES);
            long captured=versions.incoming(3);
            ArrayDeque<Runnable> owner=new ArrayDeque<>(),client=new ArrayDeque<>();
            List<String> events=new ArrayList<>();
            AsyncSnapshotCommit.submit(owner::add,client::add,()->versions.pending(3,captured),
                    ()->{},()->fail("Stale NBT committed"),()->fail("Stale ACK"),t->fail(t),()->events.add("released"));
            if(disconnect) versions.clear(); else assertTrue(versions.begin(3,2));
            owner.remove().run(); client.remove().run();
            assertEquals(List.of("released"),events);
            versions.clear(); assertEquals(0,versions.activeCount()); assertEquals(0,versions.reservedBytes());
        }
    }
    @Test void actualUnchangedReceiverCannotPassWireRequestIdToTheOpaquePendingGate() throws Exception {
        Path current=Path.of("").toAbsolutePath(),source=null;
        while(current!=null) {
            Path candidate=current.resolve("common/src/main/java/io/github/limuqy/mc/hassium/protocol/ShadowPullClient.java");
            if(Files.exists(candidate)) { source=candidate; break; }
            current=current.getParent();
        }
        assertNotNull(source,"Locate the actual receiver wiring, rather than test an unused model");
        String text=Files.readString(source);
        assertFalse(Pattern.compile("acceptPending\\(\\s*key\\s*,\\s*response\\.requestId\\(\\)\\s*\\)").matcher(text).find(),
                "UNCHANGED stillCurrent must capture opaque identity before asynchronous owner work");
    }
}
