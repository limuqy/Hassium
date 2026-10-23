package io.github.limuqy.mc.hassium.compat;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.mixin.server.*;
import io.github.limuqy.mc.hassium.platform.Services;
import io.github.limuqy.mc.hassium.protocol.sleep.SleepControl;
import net.minecraft.server.MinecraftServer;

/** Dev-only two-client motion oracle; never drives production game actions. */
public final class SleepProbeCompat {
    private static long ticks,sequence,checks,failures;
    private static final java.util.List<GeometryTicker> geometryTickers=new java.util.ArrayList<>();
    private static final class GeometryTicker implements net.minecraft.world.level.block.entity.TickingBlockEntity {
        final net.minecraft.core.BlockPos pos;
        final boolean active;
        int count;
        boolean removed;
        GeometryTicker(int x,int z,boolean active) { pos=new net.minecraft.core.BlockPos(x*16+8,139,z*16+8);this.active=active; }
        @Override public void tick() { count++; }
        @Override public boolean isRemoved() { return removed; }
        @Override public net.minecraft.core.BlockPos getPos() { return pos; }
        @Override public String getType() { return "hassium:smoke_sleep_geometry"; }
    }
#if MC_VER < MC_1_21_2
    private static final java.util.Map<Integer,net.minecraft.network.protocol.game.VecDeltaCodec> OBSERVER_BASES=new java.util.HashMap<>();
#endif
    private SleepProbeCompat() {}
    public static void beginGeometry() {
        var mc=net.minecraft.client.Minecraft.getInstance();
        if(!io.github.limuqy.mc.hassium.client.ClientSmokeTest.isEnabled()) return;
        var w=io.github.limuqy.mc.hassium.client.sleep.SleepTerrainClient.activeWindow();
        if(w==null) throw new IllegalStateException("Geometry probe requires sleep");
        geometryTickers.clear();
        for(int x=w.x()-2;x<=w.x()+2;x++) for(int z=w.z()-2;z<=w.z()+2;z++) {
            var ticker=new GeometryTicker(x,z,w.visible(x,z));geometryTickers.add(ticker);mc.level.addBlockEntityTicker(ticker);
        }
    }
    public static void assertGeometry(boolean awake) {
        if(geometryTickers.size()!=25) throw new IllegalStateException("Missing geometry tick probes");
        for(var ticker:geometryTickers) {
            boolean shouldRun=awake || ticker.active;
            if((ticker.count>0)!=shouldRun) throw new IllegalStateException("Sleep BE tick at "+ticker.pos+" count="+ticker.count+" awake="+awake);
            if(awake) ticker.removed=true;
        }
        if(!awake) {
            io.github.limuqy.mc.hassium.client.sleep.SleepTerrainClient.assertRenderedWindow();
            capture("sleep-geometry");
        }
        Constants.LOG.info("HassiumSmokeTest:SLEEP geometry realTickLoop active=9 inactive=16 awake={}",awake);
    }
    public static void afterTerrainSnapshot(net.minecraft.server.level.ServerPlayer player,int x,int z) {
#if MC_VER < MC_1_21_2
        if(!io.github.limuqy.mc.hassium.server.ServerSmokeTest.isEnabled()
                || !"sleepresume".equals(System.getProperty("hassium.serverSmokeScenario")) || z!=0) return;
        if(x==3) PlayerCompat.getServerLevel(player).setBlock(new net.minecraft.core.BlockPos(50,140,6),
                net.minecraft.world.level.block.Blocks.DIAMOND_BLOCK.defaultBlockState(),3);
        if(x==4 && PlayerCompat.getServerLevel(player).getBlockEntity(new net.minecraft.core.BlockPos(64,140,4))
                instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign) {
            sign.setText(sign.getFrontText().setMessage(0,net.minecraft.network.chat.Component.literal("snapshot-tail")),true);
        }
#endif
    }
    public static void assertPausedWorld() {
#if MC_VER < MC_1_21_2
        var mc=net.minecraft.client.Minecraft.getInstance();
        if(!io.github.limuqy.mc.hassium.client.sleep.SleepTerrainClient.restricted()) throw new IllegalStateException("Sleep window missing");
        if(mc.level.getBlockState(new net.minecraft.core.BlockPos(48,140,6)).is(net.minecraft.world.level.block.Blocks.GLOWSTONE))
            throw new IllegalStateException("Distant terrain still synchronized during sleep");
        int visible=0, light=0;
        for(int x=-4;x<=4;x++) for(int z=-4;z<=4;z++) {
            if(io.github.limuqy.mc.hassium.client.sleep.SleepTerrainClient.visible(x,z)) visible++;
            if(io.github.limuqy.mc.hassium.client.sleep.SleepTerrainClient.allows(x,z)) light++;
        }
        if(visible!=9 || light!=25) throw new IllegalStateException("Sleep geometry visible="+visible+" light="+light);
        io.github.limuqy.mc.hassium.client.sleep.SleepTerrainClient.assertRenderedWindow();
        assertWindowLight();
        capture("sleep-window");
        Constants.LOG.info("HassiumSmokeTest:SLEEP window visible=9 light=25 distant terrain paused");
#endif
    }
    private static void assertWindowLight() {
#if MC_VER < MC_1_21_2
        var level=net.minecraft.client.Minecraft.getInstance().level;
        int sky=level.getBrightness(net.minecraft.world.level.LightLayer.SKY,new net.minecraft.core.BlockPos(31,140,3));
        int block=level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK,new net.minecraft.core.BlockPos(31,140,4));
        if(sky>=15 || block<14) throw new IllegalStateException("Sleep boundary light sky="+sky+" block="+block);
        Constants.LOG.info("HassiumSmokeTest:SLEEP roof/halo boundary light sky={} block={}",sky,block);
#endif
    }
    public static void assertWakeWorld() {
#if MC_VER < MC_1_21_2
        var mc=net.minecraft.client.Minecraft.getInstance();
        var pos=new net.minecraft.core.BlockPos(48,140,6);
        if(mc.level==null || !mc.level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.GLOWSTONE))
            throw new IllegalStateException("Distant block update lost during sleep");
        if(!(mc.level.getBlockEntity(new net.minecraft.core.BlockPos(64,140,4)) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign) || !sign.isWaxed())
            throw new IllegalStateException("Distant block entity NBT update lost during sleep");
        if(!sign.getFrontText().getMessage(0,false).getString().equals("snapshot-tail"))
            throw new IllegalStateException("Post-snapshot block entity update overwritten during recovery");
        if(!mc.level.getBlockState(new net.minecraft.core.BlockPos(50,140,6)).is(net.minecraft.world.level.block.Blocks.DIAMOND_BLOCK))
            throw new IllegalStateException("Post-snapshot block update overwritten during recovery");
        Constants.LOG.info("HassiumSmokeTest:SLEEP distant block and BE-only update current");
        assertWindowLight();
#endif
    }
    public static void capture(String name) {
#if MC_VER < MC_1_21_2
        String directory=System.getProperty("hassium.smokeTest.probeDir");
        if(directory==null) return;
        try(var image=net.minecraft.client.Screenshot.takeScreenshot(net.minecraft.client.Minecraft.getInstance().getMainRenderTarget())) {
            var path=java.nio.file.Path.of(directory,name+".png");
            java.nio.file.Files.createDirectories(path.getParent()); image.writeToFile(path);
        } catch(java.io.IOException e) { throw new IllegalStateException("Sleep screenshot failed",e); }
#endif
    }
    public static void beforeTick(MinecraftServer server) {
#if MC_VER < MC_1_21_2
        if(!io.github.limuqy.mc.hassium.server.ServerSmokeTest.isEnabled()
                || !"sleepmixed".equals(System.getProperty("hassium.serverSmokeScenario"))) return;
        ticks++;
        for(var e:server.overworld().getAllEntities()) if(e.getTags().contains("sleep_probe_motion")) {
            double anchor=e.getX()>24 ? 48.5 : e.getX()>=8 ? 9.5 : 6.5;
            e.setPos(anchor+Math.sin(ticks*0.09)*0.2,140,3);
        }
#endif
    }
    public static void source(net.minecraft.server.level.ServerPlayer player,net.minecraft.network.protocol.Packet<?> packet) {
#if MC_VER < MC_1_21_2
        if(!io.github.limuqy.mc.hassium.server.ServerSmokeTest.isEnabled() || !player.getGameProfile().getName().equals("SleepObserver")) return;
        var children=SleepPacketCompat.children(packet);
        if(children!=null) { for(var child:children) source(player,child); return; }
        if(packet instanceof net.minecraft.network.protocol.game.ClientboundAddEntityPacket p) {
            var codec=new net.minecraft.network.protocol.game.VecDeltaCodec(); codec.setBase(new net.minecraft.world.phys.Vec3(p.getX(),p.getY(),p.getZ())); OBSERVER_BASES.put(p.getId(),codec);
        } else if(packet instanceof net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket p) {
            var codec=OBSERVER_BASES.get(p.getId()); if(codec!=null) codec.setBase(new net.minecraft.world.phys.Vec3(p.getX(),p.getY(),p.getZ()));
        } else if(packet instanceof net.minecraft.network.protocol.game.ClientboundMoveEntityPacket p && p.hasPosition()) {
            var entity=p.getEntity(PlayerCompat.getServerLevel(player));
            var codec=entity==null?null:OBSERVER_BASES.get(entity.getId());
            if(codec!=null) codec.setBase(codec.decode(p.getXa(),p.getYa(),p.getZa()));
        }
#endif
    }
    public static void afterTick(MinecraftServer server) {
#if MC_VER < MC_1_21_2
        if(ticks==0 || ticks%20!=0) return;
        for(var p:server.getPlayerList().getPlayers()) {
            boolean observer=p.getGameProfile().getName().equals("SleepObserver");
            if(!observer && !io.github.limuqy.mc.hassium.server.sleep.SleepServer.filtering(p)) continue;
            for(var e:SleepEntityCompat.observed(p)) if(e.getTags().contains("sleep_probe_motion")) {
                if(!observer && !io.github.limuqy.mc.hassium.server.sleep.SleepServer.relevant(p,e)) continue;
                var tracked=((SleepChunkMapAccess)server.overworld().getChunkSource().chunkMap).hassium$sleepEntityMap().get(e.getId());
                var codec=observer ? OBSERVER_BASES.get(e.getId())
                        : ((SleepServerEntityAccess)((SleepTrackedEntityAccess)tracked).hassium$sleepTracker()).hassium$sleepPositionCodec();
                if(codec==null) continue;
                var base=((SleepVecDeltaAccess)codec).hassium$sleepBase();
                String detail=e.getId()+","+base.x+","+base.y+","+base.z;
                Services.NETWORK_MANAGER.sendSleepControl(p,SleepControl.encode(new SleepControl.Message(SleepControl.Op.PROBE,0,++sequence,detail)));
            }
        }
#endif
    }
    public static void receive(String detail) {
#if MC_VER < MC_1_21_2
        if(!io.github.limuqy.mc.hassium.client.ClientSmokeTest.isEnabled()
                || !java.util.Set.of("sleepobserver","sleepmixed").contains(System.getProperty("hassium.smokeScenario",""))) return;
        String[] parts=detail.split(",");
        var mc=net.minecraft.client.Minecraft.getInstance();
        var entity=mc.level==null?null:mc.level.getEntity(Integer.parseInt(parts[0]));
        checks++;
        var base=entity==null?null:((SleepVecDeltaAccess)entity.getPositionCodec()).hassium$sleepBase();
        boolean ok=base!=null && Math.abs(base.x-Double.parseDouble(parts[1]))<0.0003
                && Math.abs(base.y-Double.parseDouble(parts[2]))<0.0003 && Math.abs(base.z-Double.parseDouble(parts[3]))<0.0003;
        if(!ok) failures++;
        Constants.LOG.info("[SLEEP_OBSERVER] checks={} failures={} id={} expected={} client={}",checks,failures,parts[0],detail,base);
#endif
    }
    public static void assertObserver() {
        if(checks<10 || failures!=0) throw new IllegalStateException("Mixed observer checks="+checks+" failures="+failures);
    }
    public static void assertAfk() {
        if(checks<2 || failures!=0) throw new IllegalStateException("AFK observer checks="+checks+" failures="+failures);
    }
}
