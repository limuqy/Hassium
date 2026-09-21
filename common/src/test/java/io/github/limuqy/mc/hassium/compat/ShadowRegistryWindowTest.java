package io.github.limuqy.mc.hassium.compat;

#if MC_VER < MC_1_21_1
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ShadowRegistryWindow} 握手语义：开窗后注册表访问被**跳过**（不阻塞、不入队），
 * 开窗前已在途的访问被有界排空——这正是替代原读写锁后仍能消除 forge 注册表重建窗口内
 * {@code Unknown registry element} 的结构保证，同时不产生任何锁序（原锁曾致退出卡满 10s）。
 */
class ShadowRegistryWindowTest {

    @AfterEach
    void closeWindow() {
        ShadowRegistryWindow.close();
    }

    @Test
    void accessReturnsValueWhenWindowClosed() {
        assertEquals("ok", ShadowRegistryWindow.withAccess(() -> "ok"));
    }

    @Test
    void accessIsSkippedWhileWindowOpen() {
        ShadowRegistryWindow.open(100L);
        AtomicBoolean ran = new AtomicBoolean(false);
        Object result = ShadowRegistryWindow.withAccess(() -> {
            ran.set(true);
            return "should-not-run";
        });
        assertNull(result, "窗口内访问必须被跳过（返回 null），不得执行");
        assertFalse(ran.get(), "窗口内访问体不得执行");
    }

    @Test
    void accessResumesAfterWindowClosed() {
        ShadowRegistryWindow.open(100L);
        assertNull(ShadowRegistryWindow.withAccess(() -> "skipped"));
        ShadowRegistryWindow.close();
        assertEquals("back", ShadowRegistryWindow.withAccess(() -> "back"));
    }

    @Test
    void openDrainsInFlightAccessBeforeReturning() throws Exception {
        CountDownLatch accessEntered = new CountDownLatch(1);
        CountDownLatch releaseAccess = new CountDownLatch(1);
        AtomicBoolean openReturned = new AtomicBoolean(false);
        Thread reader = new Thread(() -> ShadowRegistryWindow.withAccess(() -> {
            accessEntered.countDown();
            try {
                releaseAccess.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            return null;
        }));
        reader.start();
        assertTrue(accessEntered.await(5, TimeUnit.SECONDS));

        Thread opener = new Thread(() -> {
            ShadowRegistryWindow.open(5_000L);
            openReturned.set(true);
        });
        opener.start();

        // 开窗方必须等在途访问退出：此刻 open 不应返回
        Thread.sleep(150L);
        assertFalse(openReturned.get(), "开窗必须等在途注册表访问退出后才返回");

        releaseAccess.countDown();
        opener.join(5_000L);
        assertTrue(openReturned.get(), "在途访问退出后开窗应完成");
        assertEquals(0, ShadowRegistryWindow.inFlight());
    }

    @Test
    void openTimesOutWhenAccessStuck() throws Exception {
        CountDownLatch accessEntered = new CountDownLatch(1);
        CountDownLatch releaseAccess = new CountDownLatch(1);
        Thread reader = new Thread(() -> ShadowRegistryWindow.withAccess(() -> {
            accessEntered.countDown();
            try {
                releaseAccess.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            return null;
        }));
        reader.start();
        assertTrue(accessEntered.await(5, TimeUnit.SECONDS));

        long start = System.currentTimeMillis();
        boolean drained = ShadowRegistryWindow.open(200L);
        long elapsed = System.currentTimeMillis() - start;
        assertFalse(drained, "在途访问卡住时开窗应有界超时（不得无限等）");
        assertTrue(elapsed >= 150L, "应实际等待过在途访问，实际 " + elapsed + "ms");

        releaseAccess.countDown();
        reader.join(5_000L);
    }

    @Test
    void allConcurrentAccessesSkippedOnceWindowOpen() throws Exception {
        ShadowRegistryWindow.open(2_000L);
        AtomicInteger executed = new AtomicInteger();
        Thread[] readers = new Thread[8];
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < readers.length; i++) {
            readers[i] = new Thread(() -> {
                try {
                    start.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
                if (ShadowRegistryWindow.withAccess(() -> "v") != null) {
                    executed.incrementAndGet();
                }
            });
            readers[i].start();
        }
        start.countDown();
        for (Thread reader : readers) {
            reader.join(5_000L);
        }
        assertEquals(0, executed.get(), "窗口打开后所有并发访问都必须被跳过");
        assertEquals(0, ShadowRegistryWindow.inFlight(), "在途计数必须归零");
    }

    @Test
    void guardQueryDoesNotThrow() {
        ShadowRegistryWindow.shouldGuard();
    }
}
#endif
