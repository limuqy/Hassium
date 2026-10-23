package io.github.limuqy.mc.hassium.client.scenario;

/** A continuously empty resource window; activity restarts the observation period. */
public final class StableQuiescence {
    private long since=-1;
    public boolean update(long elapsedMs,int targetActive,int globalActive,long reservedBytes) {
        if(targetActive!=0 || globalActive!=0 || reservedBytes!=0) { since=-1; return false; }
        if(since<0) since=elapsedMs;
        return elapsedMs-since>=250;
    }
}
