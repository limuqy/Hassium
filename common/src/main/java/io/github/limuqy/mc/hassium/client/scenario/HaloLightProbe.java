package io.github.limuqy.mc.hassium.client.scenario;

/** One smoke-only committed work identity. No world reads, scheduling or protocol actions. */
public final class HaloLightProbe {
    public static final long TIMEOUT_NANOS=15_000_000_000L;
    public record Identity(long key,long token,Object server,long generation,Object baseline,Object level,Object connection) {
        boolean same(Identity other) {
            return other!=null && key==other.key && token==other.token && server==other.server
                    && generation==other.generation && baseline==other.baseline && level==other.level && connection==other.connection;
        }
    }
    public enum Decision { NONE, DEFER, RELEASE }
    private Identity selected;
    private Object level,connection;
    private long generation,deadline,selectedKey;
    private boolean armed,tail,released,admitted,complete;
    private String failure,description="none";
    public synchronized void arm(Object level,Object connection,long generation,long now) {
        if(armed) throw new IllegalStateException("Halo phase fixture already armed");
        if(level==null || connection==null) throw new IllegalArgumentException("Halo fixture requires an active session");
        this.level=level;this.connection=connection;this.generation=generation;deadline=now+TIMEOUT_NANOS;armed=true;
    }
    public synchronized boolean select(Identity identity,long now) {
        expire(now);
        if(!armed || complete || failure!=null || selected!=null) return false;
        if(identity.token<=0 || identity.server==null || identity.baseline==null
                || identity.level!=level || identity.connection!=connection || identity.generation!=generation) return false;
        selected=identity;selectedKey=identity.key;
        description="key="+identity.key+" token="+identity.token+" server="+System.identityHashCode(identity.server)
                +" generation="+identity.generation+" baseline="+System.identityHashCode(identity.baseline)
                +" level="+System.identityHashCode(identity.level)+" connection="+System.identityHashCode(identity.connection);
        return true;
    }
    public synchronized Identity selected() { return selected; }
    public synchronized boolean observes(Identity identity) {
        return selected!=null && selected.same(identity) && failure==null && !complete;
    }
    /** The caller samples the current injected baseline on its owner immediately before this decision. */
    public synchronized boolean phaseCurrent(Identity event,String stage,Object level,Object connection,Object server,
            long generation,Object baseline,boolean pending,boolean light,long now) {
        if(!observes(event)) return false;
        validate(level,connection,server,generation,baseline,light,now);
        return phase(event,stage,pending,light,now);
    }
    public synchronized void validate(Object level,Object connection,Object server,long generation,Object baseline,boolean light,long now) {
        expire(now);
        if(!armed || complete || failure!=null) return;
        if(this.level!=level || this.connection!=connection || this.generation!=generation) fail("session/generation changed");
        else if(selected!=null && (selected.server!=server || selected.baseline!=baseline || !light))
            fail("server/baseline/token invalidated");
    }
    public synchronized boolean tailFinished(long key,long token,long now) {
        expire(now);
        if(selected==null || failure!=null || selected.key!=key || selected.token!=token || tail) return false;
        tail=true;return true;
    }
    public synchronized Decision beforeLight(Identity identity,boolean pending,boolean light,long now) {
        expire(now);
        if(selected==null || !selected.same(identity) || failure!=null || complete) return Decision.NONE;
        if(!light) { fail("LIGHT credential invalidated");return Decision.NONE; }
        if(!tail || pending) return Decision.DEFER;
        if(released) return Decision.NONE;
        released=true;return Decision.RELEASE;
    }
    public synchronized boolean phase(Identity identity,String stage,boolean pending,boolean light,long now) {
        expire(now);
        if(selected==null || !selected.same(identity) || failure!=null || complete) return false;
        if(!tail || !released || pending || !light) { fail("phase preceded actual tail completion");return false; }
        if("admitted".equals(stage)) {
            if(admitted) return false;
            admitted=true;return true;
        }
        if("engine-complete".equals(stage)) {
            if(!admitted) { fail("engine completion preceded admission");return false; }
            complete=true;selected=null;level=null;connection=null;return true;
        }
        return false;
    }
    public synchronized void expire(long now) {
        if(armed && !complete && failure==null && now>=deadline) fail(selected==null?"no real halo DELTA within 15s":"halo phase exceeded 15s");
    }
    private void fail(String reason) { failure=reason;selected=null;level=null;connection=null; }
    public synchronized boolean complete() { return complete; }
    public synchronized long key() { return selectedKey; }
    public synchronized String failure() { return failure; }
    public synchronized void cancel(long key,long token,String reason) {
        if(selected!=null && selected.key==key && selected.token==token && !complete && failure==null) fail(reason);
    }
    public synchronized String snapshot() {
        return description+" armed="+armed+" tail="+tail+" released="+released+" admitted="+admitted
                +" complete="+complete+" failure="+failure;
    }
}
