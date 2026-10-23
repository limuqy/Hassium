package io.github.limuqy.mc.hassium.client.sleep;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.*;
import io.github.limuqy.mc.hassium.platform.Services;
import io.github.limuqy.mc.hassium.protocol.handshake.*;
import io.github.limuqy.mc.hassium.protocol.sleep.SleepControl;
import io.github.limuqy.mc.hassium.protocol.sleep.SleepControl.*;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;

/** 3x3 sleep and same-connection recovery preserve native input. */
public final class SleepClient {
    public enum State { IDLE, ENTERING, SLEEPING, RESUMING }
    private static volatile State state=State.IDLE;
    private static long generation, sequence, received;
    private static float sleepYaw,sleepPitch;
    private static boolean previousPause, heldAttack, heldUse;
    private static boolean automaticEntry;
    private static final AutoSleepTimer AUTO_TIMER=new AutoSleepTimer();
    private static Connection preferenceConnection;
    private static Object autoLevel;
    private static int autoSeconds;
    private static long testAutoDelayNanos;
    private static Connection bound;
    private SleepClient() {}
    public static State state() { return state; }
    public static boolean supported() { return LoginCaps.has(ClientLoginNegotiation.current(),LoginCaps.SLEEP); }
    public static boolean sleeping() { return state==State.SLEEPING; }
    public static void reloadPreferences() { preferenceConnection=null; AUTO_TIMER.reset(); }
    public static void testAutoDelay(long millis) {
        if(io.github.limuqy.mc.hassium.client.ClientSmokeTest.isEnabled()) {
            testAutoDelayNanos=Math.max(0,Math.min(30_000,millis))*1_000_000L; AUTO_TIMER.reset();
        }
    }
    public static boolean allowsChunk(int x,int z) { return SleepTerrainClient.allows(x,z); }
    public static void captureHeldInputs() {
        if(state!=State.IDLE) return;
        Minecraft mc=Minecraft.getInstance(); heldAttack=mc.options.keyAttack.isDown(); heldUse=mc.options.keyUse.isDown();
    }
    public static void enter() { enter(false); }
    private static void enter(boolean automatic) {
        Minecraft mc=Minecraft.getInstance();
        if(state!=State.IDLE || !supported() || mc.getConnection()==null || mc.player==null) return;
        if(!SleepTerrainClient.canEnter(mc.player.chunkPosition().x,mc.player.chunkPosition().z)) return;
        generation=0; sequence=received=0; bound=mc.getConnection().getConnection();
        if(mc.getCurrentServer()==null) return;
        if(automatic && (mc.screen!=null || mc.player.containerMenu!=mc.player.inventoryMenu)) return;
        if(mc.player.containerMenu!=mc.player.inventoryMenu) mc.player.closeContainer();
        previousPause=mc.options.pauseOnLostFocus; state=State.ENTERING; automaticEntry=automatic; AUTO_TIMER.reset();
        // Only the explicit menu entry restores its saved hold, immediately after closing the UI.
        // The network acknowledgement never replays a stale press or steals focus.
        if(!automatic && mc.screen!=null) {
            mc.setScreen(null);
            mc.options.keyAttack.setDown(heldAttack); mc.options.keyUse.setDown(heldUse);
        }
        send(Op.ENTER);
    }
    public static void receive(Connection source,byte[] data) {
        Minecraft mc=Minecraft.getInstance();
        if(mc.getConnection()==null || mc.getConnection().getConnection()!=source) return;
        Message m=SleepControl.decode(data);
        if(m.op()==Op.COLUMN_READY || m.op()==Op.COLUMN_RETRY) { SleepTerrainClient.receive(m); return; }
        if(m.op()==Op.PROBE) { SleepProbeCompat.receive(m.detail()); return; }
        Connection connection=mc.getConnection().getConnection();
        if(connection!=bound || m.sequence()<=received) return;
        if(m.op()!=Op.PREPARED && m.generation()!=generation) return;
        received=m.sequence();
        switch(m.op()) {
            case PREPARED -> { if(state!=State.ENTERING) return; generation=m.generation(); send(Op.CONFIRM); }
            case SLEEPING -> {
                state=State.SLEEPING; mc.options.pauseOnLostFocus=false;
                if(mc.player!=null) { sleepYaw=mc.player.getYRot(); sleepPitch=mc.player.getXRot(); }
                String[] parts=m.detail().split(",");
                if(parts.length!=3 || !parts[0].equals("window")) throw new IllegalArgumentException("Missing sleep window");
                SleepTerrainClient.enter(generation,Integer.parseInt(parts[1]),Integer.parseInt(parts[2]));
                Constants.LOG.info("[SLEEP_CLIENT] entered generation={} automatic={}",generation,automaticEntry);
            }
            case ACTIVE -> {
                if(state!=State.RESUMING && state!=State.SLEEPING) return;
                Constants.LOG.info("[SLEEP_CLIENT] active");
                SleepTerrainClient.wake();
                reset();
            }
            case ERROR -> {
                if(state!=State.ENTERING) return;
                reset();
                if(mc.player!=null) mc.player.displayClientMessage(Component.translatable("hassium.sleep.entry_rejected",m.detail()),false);
            }
            default -> { }
        }
    }
    public static void wake() {
        if(state!=State.SLEEPING) return;
        state=State.RESUMING; send(Op.RESUME);
    }
    public static void tick() {
        Minecraft mc=Minecraft.getInstance();
        tickAutoSleep(mc);
        if(sleeping()) SleepChunkResidency.enforce(mc);
        if(state==State.SLEEPING && mc.player!=null && SleepTerrainClient.moved(mc.player.chunkPosition().x,mc.player.chunkPosition().z)) wake();
        if(state==State.SLEEPING && mc.player!=null && mc.screen==null && mc.getOverlay()==null
                && SleepWakeTrigger.shouldWake(movementInput(mc),sleepYaw,sleepPitch,mc.player.getYRot(),mc.player.getXRot())) wake();
        if(state!=State.IDLE && mc.getConnection()==null) reset();
        if(sleeping() && mc.player!=null) SleepClientCompat.pick();
    }
    private static void tickAutoSleep(Minecraft mc) {
        Connection current=mc.getConnection()==null ? null : mc.getConnection().getConnection();
        if(current==null || mc.player==null || mc.level==null || !supported()) {
            preferenceConnection=null; autoLevel=null; AUTO_TIMER.reset(); return;
        }
        if(current!=preferenceConnection) {
            preferenceConnection=current; autoSeconds=SleepProfiles.autoSeconds();
            AUTO_TIMER.reset();
        }
        if(mc.level!=autoLevel) { autoLevel=mc.level; AUTO_TIMER.reset(); }
        boolean eligible=state==State.IDLE && mc.screen==null && mc.getOverlay()==null && mc.player.isAlive()
                && SleepTerrainClient.canEnter(mc.player.chunkPosition().x,mc.player.chunkPosition().z)
                && !mc.player.isPassenger() && !mc.player.isSleeping() && mc.player.containerMenu==mc.player.inventoryMenu
                && !movementInput(mc);
        long delay=testAutoDelayNanos>0 ? testAutoDelayNanos : autoSeconds*1_000_000_000L;
        if(AUTO_TIMER.update(System.nanoTime(),delay,eligible,mc.player.getX(),mc.player.getY(),mc.player.getZ(),mc.player.getYRot(),mc.player.getXRot()))
            enter(true);
    }
    private static boolean movementInput(Minecraft mc) {
        return mc.options.keyUp.isDown() || mc.options.keyDown.isDown() || mc.options.keyLeft.isDown() || mc.options.keyRight.isDown()
                || mc.options.keyJump.isDown() || mc.options.keyShift.isDown() || mc.options.keySprint.isDown();
    }
    private static void send(Op op) {
        Services.NETWORK_MANAGER.sendSleepControlToServer(SleepControl.encode(new Message(op,generation,++sequence,"")));
    }
    private static void reset() {
        Minecraft.getInstance().options.pauseOnLostFocus=previousPause;
        state=State.IDLE; generation=sequence=received=0; bound=null;
        automaticEntry=false; AUTO_TIMER.reset();
    }
}
