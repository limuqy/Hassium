package io.github.limuqy.mc.hassium.shadow.server;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.channels.OverlappingFileLockException;

import static org.junit.jupiter.api.Assertions.*;

class ShadowLifecycleRebuildTest {
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
