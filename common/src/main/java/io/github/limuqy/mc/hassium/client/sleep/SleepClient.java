package io.github.limuqy.mc.hassium.client.sleep;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.*;
import io.github.limuqy.mc.hassium.platform.Services;
import io.github.limuqy.mc.hassium.protocol.handshake.*;
import io.github.limuqy.mc.hassium.protocol.sleep.SleepControl;
import io.github.limuqy.mc.hassium.protocol.sleep.SleepControl.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;

/** Negotiated in-place wake preserves input; legacy reconnect is armed only by WAKE_READY. */
public final class SleepClient {
    public enum State { IDLE, ENTERING, SLEEPING, WAKING, RECONNECTING, RECOVERING, RESUMING }
    private static volatile State state=State.IDLE;
    private static volatile double centerX,centerZ,radius=8;
    private static volatile long[] relatedChunks=new long[0];
    private static long generation, sequence, received, deadline;
    private static float threshold;
    private static float sleepYaw,sleepPitch;
    private static boolean previousPause, heldAttack, heldUse, finishSent;
    private static boolean automaticWake;
    private static boolean automaticEntry;
    private static final AutoSleepTimer AUTO_TIMER=new AutoSleepTimer();
    private static Connection preferenceConnection;
    private static Object autoLevel;
    private static int autoSeconds;
    private static float autoThreshold;
    private static long testAutoDelayNanos;
    private static boolean reconnectLatch;
    private static String latchServer;
    private static int readyTicks;
    private static Connection bound;
    private static ServerData target;
    private static String detail="";
    private static long testDelayMillis,reconnectAt;
    public static void testReconnectDelay(long millis) {
        if(io.github.limuqy.mc.hassium.client.ClientSmokeTest.isEnabled()) testDelayMillis=Math.max(0,Math.min(30_000,millis));
    }
    public static void testDuplicateWake() {
        if(io.github.limuqy.mc.hassium.client.ClientSmokeTest.isEnabled() && state==State.WAKING) {
            Services.NETWORK_MANAGER.sendSleepControlToServer(SleepControl.encode(new Message(Op.WAKE,generation+1,++sequence,threshold,0,"")));
            send(Op.WAKE); send(Op.WAKE);
        }
    }
    private SleepClient() {}
    public static State state() { return state; }
    public static boolean automaticWake() { return automaticWake; }
    public static boolean needsManualConfirmation(ServerData server) { return reconnectLatch && server!=null && server.ip.equalsIgnoreCase(latchServer); }
    public static void allowManualConnection() { reconnectLatch=false; automaticWake=false; }
    public static void reconnectManually() {
        allowManualConnection();
        if(target!=null) SleepClientCompat.connect(Minecraft.getInstance().screen,target);
    }
    private static void latch() { reconnectLatch=true; if(target!=null) latchServer=target.ip; }
    public static String detail() { return detail; }
    public static boolean supported() { return LoginCaps.has(ClientLoginNegotiation.current(),LoginCaps.SLEEP); }
    public static boolean inPlaceSupported() { return LoginCaps.has(ClientLoginNegotiation.current(),LoginCaps.SLEEP_IN_PLACE); }
    public static boolean sleeping() { return state==State.SLEEPING || state==State.WAKING; }
    public static boolean blocksInput() { return state==State.WAKING || state==State.RECONNECTING || state==State.RECOVERING; }
    public static void reloadPreferences() { preferenceConnection=null; AUTO_TIMER.reset(); }
    public static void testAutoDelay(long millis) {
        if(io.github.limuqy.mc.hassium.client.ClientSmokeTest.isEnabled()) {
            testAutoDelayNanos=Math.max(0,Math.min(30_000,millis))*1_000_000L; AUTO_TIMER.reset();
        }
    }
    public static boolean allowsChunk(int x,int z) {
        if(SleepTerrainClient.supported()) return SleepTerrainClient.allows(x,z);
        if(inPlaceSupported() || !sleeping() || Math.abs(x*16+8-centerX)<=radius+24 && Math.abs(z*16+8-centerZ)<=radius+24) return true;
        for(long packed:relatedChunks) {
            var pos=new net.minecraft.world.level.ChunkPos(packed);
            if(Math.abs(x-pos.x)<=1 && Math.abs(z-pos.z)<=1) return true;
        }
        return false;
    }
    public static void captureHeldInputs() {
        if(state!=State.IDLE) return;
        Minecraft mc=Minecraft.getInstance(); heldAttack=mc.options.keyAttack.isDown(); heldUse=mc.options.keyUse.isDown();
    }
    public static void enter(float value) {
        enter(value,false);
    }
    private static void enter(float value,boolean automatic) {
        Minecraft mc=Minecraft.getInstance();
        if(state!=State.IDLE || !supported() || mc.getConnection()==null || mc.player==null || !SleepControl.validThreshold(value,mc.player.getMaxHealth())) return;
        if(!SleepTerrainClient.canEnter(mc.player.chunkPosition().x,mc.player.chunkPosition().z)) return;
        threshold=value; generation=0; sequence=received=0; detail=""; bound=mc.getConnection().getConnection(); target=mc.getCurrentServer();
        if(target==null) { detail="Remote server address is unavailable"; return; }
        if(automatic && (mc.screen!=null || mc.player.containerMenu!=mc.player.inventoryMenu)) return;
        if(mc.player.containerMenu!=mc.player.inventoryMenu) mc.player.closeContainer();
        if(!automatic) SleepProfiles.save(value);
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
        if(m.op()==Op.RECOVERING && connection!=bound) {
            if(state!=State.RECOVERING || !automaticWake || m.generation()!=generation) return;
            bound=connection; received=sequence=0;
        }
        if(connection!=bound || m.sequence()<=received) return;
        if(m.op()!=Op.PREPARED && m.generation()!=generation) return;
        received=m.sequence(); detail=m.detail();
        radius=m.interactionRadius();
        switch(m.op()) {
            case PREPARED -> { if(state!=State.ENTERING) return; generation=m.generation(); threshold=m.threshold(); send(Op.CONFIRM); }
            case SLEEPING -> {
                state=State.SLEEPING; threshold=m.threshold(); mc.options.pauseOnLostFocus=false;
                if(mc.player!=null) { centerX=mc.player.getX(); centerZ=mc.player.getZ(); sleepYaw=mc.player.getYRot(); sleepPitch=mc.player.getXRot(); }
                if(mc.player!=null && m.detail().startsWith("window,")) {
                    String[] parts=m.detail().split(",");
                    if(parts.length==3) SleepTerrainClient.enter(generation,Integer.parseInt(parts[1]),Integer.parseInt(parts[2]));
                }
                Constants.LOG.info("[SLEEP_CLIENT] entered generation={} automatic={}",generation,automaticEntry);
            }
            case WAKE_READY -> {
                if(state!=State.WAKING) return;
                deadline=System.nanoTime()+m.holdSeconds()*1_000_000_000L;
                reconnectAt=System.nanoTime()+testDelayMillis*1_000_000L; testDelayMillis=0;
                state=State.RECONNECTING; KeyMapping.releaseAll();
                automaticWake=true;
                bound.disconnect(Component.translatable("hassium.sleep.reconnecting"));
            }
            case RECOVERING -> { generation=m.generation(); threshold=m.threshold(); state=State.RECOVERING; readyTicks=0; finishSent=false; KeyMapping.releaseAll(); }
            case ACTIVE -> {
                if(state!=State.RESUMING && state!=State.RECOVERING) return;
                Constants.LOG.info("[SLEEP_CLIENT] active inPlace={}",state==State.RESUMING);
                if(state==State.RESUMING) SleepTerrainClient.wake();
                reset(); if(mc.screen instanceof SleepPanelScreen) mc.setScreen(null);
            }
            case SAFETY -> { latch(); deadline=0; reset(); detail=m.detail(); KeyMapping.releaseAll(); }
            case ERROR -> {
                boolean quiet=state==State.ENTERING && automaticEntry;
                if(state==State.ENTERING) reset(); else if(state==State.WAKING) state=State.SLEEPING;
                if(quiet) {
                    if(mc.player!=null) mc.player.displayClientMessage(Component.translatable("hassium.sleep.auto_rejected",detail),false);
                } else mc.setScreen(new SleepPanelScreen());
            }
            default -> { }
        }
    }
    public static void wake() {
        if(state!=State.SLEEPING) return;
        if(inPlaceSupported()) { state=State.RESUMING; send(Op.RESUME); return; }
        wakeWithReconnect();
    }
    /** Existing lease/expiry scenarios explicitly exercise the legacy recovery path. */
    public static void testLegacyWake() {
        if(io.github.limuqy.mc.hassium.client.ClientSmokeTest.isEnabled()) wakeWithReconnect();
    }
    private static void wakeWithReconnect() {
        if(state!=State.SLEEPING) return;
        var mc=Minecraft.getInstance();
        if(mc.gameMode!=null) mc.gameMode.stopDestroyBlock();
        if(mc.player!=null) mc.player.stopUsingItem();
        state=State.WAKING; KeyMapping.releaseAll(); send(Op.WAKE);
    }
    public static void continueSleep() { if(state==State.SLEEPING) {
        Minecraft mc=Minecraft.getInstance(); KeyMapping.releaseAll(); mc.setScreen(null); mc.options.keyAttack.setDown(heldAttack); mc.options.keyUse.setDown(heldUse); mc.mouseHandler.grabMouse();
    } }
    public static void showPanel() {
        Minecraft mc=Minecraft.getInstance(); heldAttack=mc.options.keyAttack.isDown(); heldUse=mc.options.keyUse.isDown();
        KeyMapping.releaseAll(); mc.setScreen(new SleepPanelScreen());
    }
    public static void disconnect() {
        latch(); deadline=0; reset();
        var connection=Minecraft.getInstance().getConnection();
        if(connection!=null) connection.getConnection().disconnect(Component.translatable("disconnect.quitting"));
    }
    public static void tick() {
        Minecraft mc=Minecraft.getInstance();
        tickAutoSleep(mc);
        if(state==State.SLEEPING && mc.player!=null && SleepTerrainClient.moved(mc.player.chunkPosition().x,mc.player.chunkPosition().z)) wake();
        if(state==State.SLEEPING && inPlaceSupported() && mc.player!=null && mc.screen==null && mc.getOverlay()==null
                && SleepWakeTrigger.shouldWake(movementInput(mc),sleepYaw,sleepPitch,mc.player.getYRot(),mc.player.getXRot())) wake();
        if(mc.player!=null) { centerX=mc.player.getX(); centerZ=mc.player.getZ(); }
        if(sleeping() && mc.level!=null) {
            java.util.ArrayList<Long> positions=new java.util.ArrayList<>();
            for(var entity:mc.level.entitiesForRendering()) if(entity instanceof net.minecraft.world.entity.projectile.Projectile p && p.getOwner()==mc.player)
                positions.add(p.chunkPosition().toLong());
            relatedChunks=positions.stream().mapToLong(Long::longValue).toArray();
        }
        if(state==State.RECONNECTING) {
            if(System.nanoTime()>=deadline || target==null) {
                latch(); var failed=mc.screen; var retry=target; long until=deadline;
                reset(); mc.setScreen(new SleepReconnectFailureScreen(failed,retry,until)); return;
            }
            if(System.nanoTime()>=reconnectAt && mc.getConnection()==null && mc.level==null && mc.screen instanceof DisconnectedScreen) {
                ServerData destination=target; state=State.RECOVERING; readyTicks=0; finishSent=false;
                SleepClientCompat.connect(mc.screen,destination);
            }
        } else if(state==State.RECOVERING && deadline>0 && System.nanoTime()>=deadline && mc.getConnection()!=null) {
            latch(); mc.getConnection().getConnection().disconnect(Component.literal("Hassium reconnect hold expired; reconnect manually"));
        } else if(state==State.RECOVERING && bound!=null && mc.getConnection()!=null && mc.getConnection().getConnection()==bound) {
            if(SleepClientCompat.ready()) readyTicks++; else readyTicks=0;
            if(readyTicks>=10 && !finishSent) { finishSent=true; send(Op.FINISH); }
        } else if(state==State.RECOVERING && mc.getConnection()==null && mc.screen instanceof DisconnectedScreen) {
            latch(); Screen failed=mc.screen; ServerData retry=target; long until=deadline;
            reset(); mc.setScreen(new SleepReconnectFailureScreen(failed,retry,until));
        } else if((state==State.SLEEPING || state==State.ENTERING || state==State.WAKING || state==State.RESUMING) && mc.getConnection()==null) {
            latch(); reset(); // Accidental disconnect never arms a reconnect.
        }
        if(sleeping() && mc.player!=null) SleepClientCompat.pick();
        if(blocksInput()) KeyMapping.releaseAll();
    }
    private static void tickAutoSleep(Minecraft mc) {
        Connection current=mc.getConnection()==null ? null : mc.getConnection().getConnection();
        if(current==null || mc.player==null || mc.level==null || !supported()) {
            preferenceConnection=null; autoLevel=null; AUTO_TIMER.reset(); return;
        }
        if(current!=preferenceConnection) {
            preferenceConnection=current; autoSeconds=SleepProfiles.autoSeconds(); autoThreshold=SleepProfiles.threshold();
            AUTO_TIMER.reset();
        }
        if(mc.level!=autoLevel) { autoLevel=mc.level; AUTO_TIMER.reset(); }
        boolean eligible=state==State.IDLE && mc.screen==null && mc.getOverlay()==null && mc.player.isAlive()
                && SleepTerrainClient.canEnter(mc.player.chunkPosition().x,mc.player.chunkPosition().z)
                && !mc.player.isPassenger() && !mc.player.isSleeping() && mc.player.containerMenu==mc.player.inventoryMenu
                && SleepControl.validThreshold(autoThreshold,mc.player.getMaxHealth()) && !SleepControl.lowHealth(mc.player.getHealth(),autoThreshold)
                && !movementInput(mc);
        long delay=testAutoDelayNanos>0 ? testAutoDelayNanos : autoSeconds*1_000_000_000L;
        if(AUTO_TIMER.update(System.nanoTime(),delay,eligible,mc.player.getX(),mc.player.getY(),mc.player.getZ(),mc.player.getYRot(),mc.player.getXRot()))
            enter(autoThreshold,true);
    }
    private static boolean movementInput(Minecraft mc) {
        return mc.options.keyUp.isDown() || mc.options.keyDown.isDown() || mc.options.keyLeft.isDown() || mc.options.keyRight.isDown()
                || mc.options.keyJump.isDown() || mc.options.keyShift.isDown() || mc.options.keySprint.isDown();
    }
    private static void send(Op op) {
        Services.NETWORK_MANAGER.sendSleepControlToServer(SleepControl.encode(new Message(op,generation,++sequence,threshold,0,"")));
    }
    private static void reset() {
        Minecraft.getInstance().options.pauseOnLostFocus=previousPause;
        state=State.IDLE; finishSent=false; readyTicks=0; generation=sequence=received=0; bound=null;
        automaticWake=false;
        automaticEntry=false; AUTO_TIMER.reset();
        relatedChunks=new long[0];
    }
}
