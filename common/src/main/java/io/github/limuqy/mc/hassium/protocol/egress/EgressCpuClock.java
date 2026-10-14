package io.github.limuqy.mc.hassium.protocol.egress;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/** CPU time is reported separately from wall time and queue latency; unsupported platforms return zero. */
final class EgressCpuClock {
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    static long now() { return THREADS.isCurrentThreadCpuTimeSupported() ? Math.max(0, THREADS.getCurrentThreadCpuTime()) : 0; }
    private EgressCpuClock() {}
}
