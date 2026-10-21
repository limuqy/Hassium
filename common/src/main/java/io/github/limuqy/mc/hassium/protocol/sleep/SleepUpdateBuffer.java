package io.github.limuqy.mc.hassium.protocol.sleep;

import java.util.ArrayList;
import java.util.List;

/** Bounded post-snapshot tail. Overflow requires a new snapshot, never partial replay. */
public final class SleepUpdateBuffer<T> {
    private final List<T> values=new ArrayList<>();
    private final int maxCount,maxBytes;
    private int bytes;
    private boolean overflow;
    public SleepUpdateBuffer(int maxCount,int maxBytes) { this.maxCount=maxCount; this.maxBytes=maxBytes; }
    public void add(T value,int size) {
        if(overflow) return;
        if(size<0 || values.size()>=maxCount || size>maxBytes-bytes) { overflow(); return; }
        values.add(value); bytes+=size;
    }
    public void overflow() { values.clear(); bytes=0; overflow=true; }
    public boolean overflowed() { return overflow; }
    public int size() { return values.size(); }
    public int bytes() { return bytes; }
    public List<T> values() { return List.copyOf(values); }
}
