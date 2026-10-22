package io.github.limuqy.mc.hassium.protocol;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.concurrent.WorkBudget;
import java.util.concurrent.LinkedBlockingQueue;
import static io.github.limuqy.mc.hassium.utils.ChunkPipelineProfile.*;

/** Single FIFO worker per connection generation; receive threads never wait for decoder capacity. */
public final class PullResponseDecodeQueue {
    private record Work(byte[] data, long queued, WorkBudget<Boolean>.Lease lease) {}
    private static final Work POISON = new Work(new byte[0], 0, null);
    private static Session current;
    private PullResponseDecodeQueue() {}

    private static final class Session {
        final LinkedBlockingQueue<Work> queue = new LinkedBlockingQueue<>(129);
        final WorkBudget<Boolean> budget = new WorkBudget<>(128, 128, 64L << 20, 64L << 20);
        volatile boolean closed;
    }

    public static void enqueue(byte[] data) {
        if (data == null || data.length == 0) return;
        boolean accepted = false;
        synchronized (PullResponseDecodeQueue.class) {
            if (current == null) {
                Session session = current = new Session();
                Thread worker = new Thread(() -> loop(session), "Hassium-PullDecode");
                worker.setDaemon(true);
                worker.start();
            }
            var lease = current.budget.tryAcquire(Boolean.TRUE, data.length);
            if (lease != null) {
                // Copy only after byte/count admission. The active worker retains its credit too.
                try { accepted = current.queue.offer(new Work(data.clone(), now(), lease)); }
                finally { if (!accepted) lease.close(); }
                peak(Counter.DECODE_PEAK, current.budget.jobs());
            }
        }
        if (!accepted) ShadowPullClient.retryDecodeResponse(data);
    }

    /** Old workers own only their old queue and can never consume a reconnected session's work. */
    public static synchronized void discard() {
        Session session = current;
        current = null;
        if (session == null) return;
        session.closed = true;
        Work work;
        while ((work = session.queue.poll()) != null) if (work.lease() != null) work.lease().close();
        session.queue.offer(POISON);
    }

    private static void loop(Session session) {
        try {
            while (!session.closed) {
                Work work = session.queue.take();
                if (work == POISON) return;
                try {
                    if (session.closed) continue;
                    elapsed(Source.CLIENT, Stage.DECODE_WAIT, work.queued());
                    long start = now();
                    try { PayloadHandlers.processShadowPullResponse(work.data(), () -> !session.closed); }
                    finally { elapsed(Source.CLIENT, Stage.DECODE_WALL, start); }
                } catch (Throwable failure) {
                    Constants.LOG.error("Hassium: pull response decode worker failed", failure);
                    if (!session.closed) ShadowPullClient.retryDecodeResponse(work.data());
                } finally { work.lease().close(); }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
