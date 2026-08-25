package io.github.limuqy.mc.hassium.compat;

#if MC_VER < MC_1_21_1
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * ShadowRegistryGate 读写门互斥语义：读（影子序列化）与写（clearLevel 世界拆除 +
 * revertToFrozen）不得重叠——这正是消除 forge 注册表重建窗口内
 * {@code Unknown registry element} 的结构保证。
 */
class ShadowRegistryGateTest {

    @Test
    void readAccessReturnsValue() {
        assertEquals("ok", ShadowRegistryGate.withReadAccess(() -> "ok"));
    }

    @Test
    void writerBlocksNewReaderUntilRelease() throws Exception {
        CountDownLatch writeHeld = new CountDownLatch(1);
        CountDownLatch releaseNow = new CountDownLatch(1);
        CountDownLatch readerStarted = new CountDownLatch(1);
        AtomicBoolean readerDone = new AtomicBoolean(false);
        Thread writer = new Thread(() -> {
            ShadowRegistryGate.acquireWrite();
            writeHeld.countDown();
            try {
                releaseNow.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            ShadowRegistryGate.releaseWrite();
        });
        writer.start();
        assertTrue(writeHeld.await(5, TimeUnit.SECONDS));

        Thread reader = new Thread(() -> {
            readerStarted.countDown();
            ShadowRegistryGate.withReadAccess(() -> {
                readerDone.set(true);
                return null;
            });
        });
        reader.start();
        assertTrue(readerStarted.await(2, TimeUnit.SECONDS));
        Thread.sleep(100); // 给 reader 机会错误地在写锁下进入
        // 写锁被持有时 reader 必须仍阻塞（互斥生效）
        assertTrue(!readerDone.get(), "reader entered while writer held the gate");

        releaseNow.countDown();
        reader.join(5000);
        assertTrue(readerDone.get(), "reader should proceed after write release");
    }

    @Test
    void readersOverlapButNotWithWriter() throws Exception {
        AtomicInteger insideReaders = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        CountDownLatch bothIn = new CountDownLatch(2);
        Runnable readBody = () -> {
            int now = insideReaders.incrementAndGet();
            maxConcurrent.accumulateAndGet(now, Math::max);
            bothIn.countDown();
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
            }
            insideReaders.decrementAndGet();
        };
        Thread r1 = new Thread(() -> ShadowRegistryGate.withReadAccess(() -> {
            readBody.run();
            return null;
        }));
        Thread r2 = new Thread(() -> ShadowRegistryGate.withReadAccess(() -> {
            readBody.run();
            return null;
        }));
        r1.start();
        r2.start();
        assertTrue(bothIn.await(2, TimeUnit.SECONDS), "two readers must overlap (shared lock)");
        r1.join(5000);
        r2.join(5000);
        assertEquals(2, maxConcurrent.get());
    }

    @Test
    void writerIsNotStarvedByReaderStream() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread reader = new Thread(() -> {
            while (!stop.get()) {
                ShadowRegistryGate.withReadAccess(() -> null);
            }
        }, "gate-reader-stream");
        reader.start();
        Thread.sleep(20L);
        long t0 = System.nanoTime();
        ShadowRegistryGate.acquireWrite();
        long waitedMs = (System.nanoTime() - t0) / 1_000_000L;
        ShadowRegistryGate.releaseWrite();
        stop.set(true);
        reader.join(2_000L);
        assertTrue(waitedMs < 1_000L, "fair lock: writer must not wait a full encode burst, waited=" + waitedMs);
    }

    @Test
    void writeLockNeededQueryDoesNotThrow() {
        // 单测无 Fabric/Forge loader 时保守为 true；不得因查 platform 抛错。
        assertTrue(ShadowRegistryGate.shouldHoldWriteLockDuringClearLevel());
    }
}
#endif
