package io.github.limuqy.mc.hassium.network.dataplane;

/** Main-thread marker for the one synthetic ConnectScreen failover attempt. */
public final class ClientFailoverAttemptMarker {
    private static String primaryAddress;
    private static ControlEndpoint endpoint;
    private static boolean primaryAttempt;

    private ClientFailoverAttemptMarker() {}

    public static synchronized void mark(String primary, ControlEndpoint candidate) {
        primaryAddress = primary;
        endpoint = candidate;
        primaryAttempt = false;
    }

    public static synchronized void markPrimary(String primary) {
        primaryAddress = primary;
        endpoint = null;
        primaryAttempt = true;
    }

    public static synchronized boolean isMarked() {
        return primaryAttempt || endpoint != null;
    }

    public static synchronized String primaryAddress() {
        return primaryAddress;
    }

    public static synchronized ControlEndpoint endpoint() {
        return endpoint;
    }

    public static synchronized boolean isPrimaryAttempt() {
        return primaryAttempt;
    }

    public static synchronized void clear() {
        primaryAddress = null;
        endpoint = null;
        primaryAttempt = false;
    }
}
