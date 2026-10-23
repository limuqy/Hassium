package io.github.limuqy.mc.hassium.protocol;

import io.github.limuqy.mc.hassium.network.PreparedClientSend;
import io.github.limuqy.mc.hassium.platform.services.INetworkManagerService;
import io.netty.buffer.Unpooled;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PreparedClientSendTest {
    @Test void missingClosedReplacedAndThrowingConnectionsCannotReportAcceptance() {
        Object connection=new Object();var current=new AtomicReference<Object>(connection);
        var sends=new AtomicInteger();var failures=new AtomicInteger();
        assertFalse(PreparedClientSend.trySend(null,current::get,()->true,sends::incrementAndGet,e->failures.incrementAndGet()));
        assertFalse(PreparedClientSend.trySend(connection,current::get,()->false,sends::incrementAndGet,e->failures.incrementAndGet()));
        current.set(new Object());
        assertFalse(PreparedClientSend.trySend(connection,current::get,()->true,sends::incrementAndGet,e->failures.incrementAndGet()));
        current.set(connection);
        assertFalse(PreparedClientSend.trySend(connection,current::get,()->true,()->{throw new IllegalStateException("channel rejected");},e->failures.incrementAndGet()));
        assertEquals(0,sends.get());assertEquals(1,failures.get());
        assertTrue(PreparedClientSend.trySend(connection,current::get,()->true,sends::incrementAndGet,e->failures.incrementAndGet()));
        assertEquals(1,sends.get());
        assertFalse(PreparedClientSend.trySend(connection,current::get,()->true,()->current.set(new Object()),e->failures.incrementAndGet()),
                "A connection changed during local send must not borrow this recovery cooldown");
    }
    @Test void unsupportedSpiNeverSilentlyClaimsSuccessAndConsumesItsBuffer() {
        var spi=new INetworkManagerService(){};
        var buffer=new FriendlyByteBuf(Unpooled.buffer());buffer.writeByte(1);
        assertFalse(spi.trySendShadowPullRequest(buffer));assertEquals(0,buffer.refCnt());
        var legacy=new FriendlyByteBuf(Unpooled.buffer());legacy.writeByte(1);
        spi.sendShadowPullRequest(legacy);assertEquals(0,legacy.refCnt());
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    @Test void actualOldFailureCleanupCannotCancelTheSameKeyReplacement() throws Exception {
        var windowField=ShadowPullClient.class.getDeclaredField("RECEIVE_WINDOW");windowField.setAccessible(true);
        PullReceiveWindow window=(PullReceiveWindow)windowField.get(null);window.clear();
        var oldCancelled=new AtomicInteger();var newCancelled=new AtomicInteger();
        ShadowPullClient.DispatchGate oldGate=new ShadowPullClient.DispatchGate(){
            public boolean send(java.util.function.BooleanSupplier wire){return false;}
            public void cancelled(){oldCancelled.incrementAndGet();}
        };
        ShadowPullClient.DispatchGate newGate=new ShadowPullClient.DispatchGate(){
            public boolean send(java.util.function.BooleanSupplier wire){return false;}
            public void cancelled(){newCancelled.incrementAndGet();}
        };
        Class<?> intentType=java.util.Arrays.stream(ShadowPullClient.class.getDeclaredClasses())
                .filter(type->type.getSimpleName().equals("PullIntent")).findFirst().orElseThrow();
        var constructor=intentType.getDeclaredConstructors()[0];constructor.setAccessible(true);
        var pos=new net.minecraft.world.level.ChunkPos(-6,0);long key=1234;
        try {
            Object oldIntent=constructor.newInstance("minecraft:overworld",pos,true,false,oldGate);
            assertTrue(window.offer(key,oldIntent));var old=(PullReceiveWindow.Admission)window.drain().get(0);
            assertTrue(window.bind(key,old.token(),10));
            // The failing preparation observed its own credit, then another lifecycle replaced it.
            assertTrue(window.matchesAdmission(key,old.token()));window.cancel(key);
            Object newIntent=constructor.newInstance("minecraft:overworld",pos,true,false,newGate);
            assertTrue(window.offer(key,newIntent));var current=(PullReceiveWindow.Admission)window.drain().get(0);
            assertTrue(window.bind(key,current.token(),11));
            var cleanup=ShadowPullClient.class.getDeclaredMethod("discardUnsent",PullReceiveWindow.Admission.class);cleanup.setAccessible(true);
            assertEquals(false,cleanup.invoke(null,old));
            assertTrue(window.matches(key,11));assertEquals(1,oldCancelled.get());assertEquals(0,newCancelled.get());
        } finally { window.clear(); }
    }
    @Test void allRegisteredLoadersUseObservableAdmissionAndTheRecoveryUsesItsBoolean() throws Exception {
        Path root=Path.of("").toAbsolutePath();
        while(root!=null && !Files.isDirectory(root.resolve("common/src/main/java")))root=root.getParent();
        assertNotNull(root);
        for(String module:new String[]{"fabric","forge","neoforge"}) {
            String type=switch(module){case "fabric"->"Fabric";case "forge"->"Forge";default->"NeoForge";};
            String source=Files.readString(root.resolve(module+"/src/main/java/io/github/limuqy/mc/hassium/network/"+type+"NetworkManager.java"));
            assertTrue(source.contains("boolean trySendShadowPullRequest(FriendlyByteBuf buf)"));
            assertTrue(source.contains("PreparedClientSend.trySend(connection,minecraft::getConnection"));
            assertTrue(source.contains("connection.getConnection().isConnected()"));
            String registration=Files.readString(root.resolve(module+"/src/main/resources/META-INF/services/io.github.limuqy.mc.hassium.platform.services.INetworkManagerService"));
            assertTrue(registration.contains(type+"NetworkManager"));
        }
        String pull=Files.readString(root.resolve("common/src/main/java/io/github/limuqy/mc/hassium/protocol/ShadowPullClient.java"));
        assertTrue(pull.contains("Services.NETWORK_MANAGER.trySendShadowPullRequest(buffer)"));
        int start=pull.indexOf("catch (Throwable failure)",pull.indexOf("private static void drainQueuedRequests"));
        int end=pull.indexOf("private static boolean discardUnsent",start);
        String failure=pull.substring(start,end);
        assertTrue(failure.contains("discardUnsent(admission)"));
        assertFalse(failure.contains("notePullFailure("),"A stale preparation exception cannot cancel by column key");
    }
}
