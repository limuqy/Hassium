package io.github.limuqy.mc.hassium.client.scenario;

import java.util.HashMap;
import java.util.Map;

/** Exact transaction identities for the two explicitly armed smoke columns. */
public final class PullOrderProbeScope {
    private record Target(Object level,Object connection,int generation,long request,long token,boolean completed) {}
    private final Map<Long,Target> targets=new HashMap<>();
    public synchronized void arm(long key,Object level,Object connection,int generation) {
        if(!targets.containsKey(key) && targets.size()==2) throw new IllegalStateException("Too many probe columns");
        targets.put(key,new Target(level,connection,generation,0,0,false));
    }
    public synchronized boolean target(long key,Object level,Object connection,int generation) {
        Target t=targets.get(key); return t!=null && t.level==level && t.connection==connection && t.generation==generation;
    }
    public synchronized boolean response(long key,long request,long token,Object level,Object connection,int generation) {
        Target t=targets.get(key);
        if(!target(key,level,connection,generation) || request<=t.request || token<=0) return false;
        targets.put(key,new Target(level,connection,generation,request,token,false)); return true;
    }
    public synchronized boolean matches(long key,long token,Object level,Object connection,int generation) {
        Target t=targets.get(key);
        return target(key,level,connection,generation) && t.request>0 && t.token==token;
    }
    public synchronized boolean complete(long key,long token,Object level,Object connection,int generation) {
        Target t=targets.get(key);
        if(!matches(key,token,level,connection,generation) || t.completed) return false;
        targets.put(key,new Target(level,connection,generation,t.request,t.token,true)); return true;
    }
    public synchronized long token(long key) { Target t=targets.get(key); return t==null?-1:t.token; }
    public synchronized long request(long key) { Target t=targets.get(key); return t==null?-1:t.request; }
    public synchronized java.util.List<Long> keys() { return java.util.List.copyOf(targets.keySet()); }
}
