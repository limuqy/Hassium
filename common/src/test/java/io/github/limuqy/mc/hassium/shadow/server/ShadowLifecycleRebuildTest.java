package io.github.limuqy.mc.hassium.shadow.server;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.channels.OverlappingFileLockException;

import static org.junit.jupiter.api.Assertions.*;

class ShadowLifecycleRebuildTest {
    private static ShadowSeedServer uninitializedOwner() throws Exception {
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (ShadowSeedServer) ((sun.misc.Unsafe) field.get(null)).allocateInstance(ShadowSeedServer.class);
    }

    @Test void retiringLoopCannotWaitForItsOwnShutdownOrCreateAReplacement() throws Exception {
        var registry = registryWithLoginPermit();
        var complete = ShadowServerRegistry.class.getDeclaredField("previousShutdownComplete");
        complete.setAccessible(true);
        complete.setBoolean(registry, false);
        var old = uninitializedOwner();
        assertTimeout(java.time.Duration.ofSeconds(1), () ->
                ShadowSeedServer.runWithMainLoopOwner(old, () -> assertNull(registry.getOrCreate())));
        assertFalse(complete.getBoolean(registry), "The old owner must not bypass the storage close gate");
        assertNull(ShadowSeedServer.currentMainLoopOwner());
    }

    @Test void ownerScopeRestoresNestedIdentityAndClearsAfterFailure() throws Exception {
        var old = uninitializedOwner();
        var replacement = uninitializedOwner();
        ShadowSeedServer.runWithMainLoopOwner(old, () -> {
            assertSame(old, ShadowSeedServer.currentMainLoopOwner());
            assertThrows(IllegalStateException.class, () -> ShadowSeedServer.runWithMainLoopOwner(replacement, () -> {
                assertSame(replacement, ShadowSeedServer.currentMainLoopOwner());
                throw new IllegalStateException("fixture failure");
            }));
            assertSame(old, ShadowSeedServer.currentMainLoopOwner());
        });
        assertNull(ShadowSeedServer.currentMainLoopOwner());
    }

    private ShadowServerRegistry registryWithLoginPermit() throws Exception {
        var constructor = ShadowServerRegistry.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        var registry = constructor.newInstance();
        registry.permitUnparkForLogin();
        return registry;
    }

    private boolean loginPermitted(ShadowServerRegistry registry) throws Exception {
        var field = ShadowServerRegistry.class.getDeclaredField("unparkPermitted");
        field.setAccessible(true);
        return field.getBoolean(registry);
    }

    @Test
    void rebuildingSameConnectionKeepsLoginPermitEvenWhenAlreadyDetached() throws Exception {
        var registry = registryWithLoginPermit();
        registry.shutdown(true);
        assertTrue(loginPermitted(registry));
        registry.shutdown(true);
        assertTrue(loginPermitted(registry));
    }

    @Test
    void disconnectRevokesLoginPermit() throws Exception {
        var registry = registryWithLoginPermit();
        registry.shutdown();
        assertFalse(loginPermitted(registry));
    }

    @Test
    void retriesLocalizedWindowsFailureWhileAcquiringSessionLock() {
        var error = new IOException("另一个程序已锁定文件的一部分，进程无法访问。");
        error.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("sun.nio.ch.FileDispatcherImpl", "write0", null, -2),
                new StackTraceElement(net.minecraft.util.DirectoryLock.class.getName(), "create", "DirectoryLock.java", 37)
        });
        assertTrue(ShadowServerRegistry.isLockBusy(error));
        assertTrue(ShadowServerRegistry.isLockBusy(new IOException("assembly failed", error)));
    }

    @Test
    void retainsExistingLockSignalsAndRejectsUnrelatedIoFailures() {
        assertTrue(ShadowServerRegistry.isLockBusy(new OverlappingFileLockException()));
        assertTrue(ShadowServerRegistry.isLockBusy(new IOException("session.lock: already locked")));
        assertFalse(ShadowServerRegistry.isLockBusy(new IOException("disk full")));
        assertFalse(ShadowServerRegistry.isLockBusy(new IOException("另一个程序已锁定文件的一部分，进程无法访问。")));
    }
}
