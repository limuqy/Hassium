package io.github.limuqy.mc.hassium.server.sleep;

import io.github.limuqy.mc.hassium.Constants;
import java.util.*;

/** Dev-only successful TCP writes, factory output observations. */
public final class SleepRuntimeProbe {
    public static final boolean ENABLED=io.github.limuqy.mc.hassium.server.ServerSmokeTest.isEnabled();
    private static final java.util.concurrent.atomic.LongAdder TCP_BYTES=new java.util.concurrent.atomic.LongAdder();
    private static String trafficPhase="setup";
    private static long trafficSample;
    private static boolean factoryInitialized;
    private SleepRuntimeProbe() {}
    public static void installTcpMeter(io.netty.channel.Channel channel) {
        if(!ENABLED || channel.pipeline().get("prepender")==null || channel.pipeline().get("sleep_tcp_probe")!=null) return;
        channel.pipeline().addBefore("prepender","sleep_tcp_probe",new io.netty.channel.ChannelOutboundHandlerAdapter() {
            @Override public void write(io.netty.channel.ChannelHandlerContext ctx,Object message,io.netty.channel.ChannelPromise promise) {
                int bytes=message instanceof io.netty.buffer.ByteBuf buffer ? buffer.readableBytes():0;
                var observed=promise.isVoid()?ctx.newPromise():promise;
                observed.addListener(f->{if(f.isSuccess()) TCP_BYTES.add(bytes);});
                ctx.write(message,observed);
            }
        });
    }
    public static void traffic(net.minecraft.server.MinecraftServer server) {
        String scenario=System.getProperty("hassium.serverSmokeScenario","");
        if(!ENABLED || !Set.of("sleepfactory","sleepwindowtraffic").contains(scenario)) return;
        boolean realFactory=scenario.equals("sleepfactory");
        if(realFactory && !factoryInitialized) { io.github.limuqy.mc.hassium.compat.EgressFixtureCompat.freeze(server,true); factoryInitialized=true; }
        String next=trafficPhase;
        if(!server.getPlayerList().getPlayers().isEmpty()) next=server.getPlayerList().getPlayers().get(0).getTags().stream()
                .filter(t->t.startsWith("sleep_traffic_")).sorted().findFirst().orElse(trafficPhase);
        long now=System.nanoTime();
        if(!next.equals(trafficPhase) || now-trafficSample>=1_000_000_000L) {
            long output=0;
            for(int i=0;!realFactory && i<16;i++) {
                var pos=new net.minecraft.core.BlockPos(26+(i%4)*7,139,12+(i/4)*7);
                if(server.overworld().getBlockEntity(pos) instanceof net.minecraft.world.Container container)
                    for(int slot=0;slot<container.getContainerSize();slot++) output+=container.getItem(slot).getCount();
            }
            Constants.LOG.info("[SLEEP_TRAFFIC] phase={} next={} nanoTime={} tcpBytes={} players={} timestampMs={} outputItems={} worldTicks={} frozen={}",trafficPhase,next,now,TCP_BYTES.sum(),server.getPlayerList().getPlayerCount(),System.currentTimeMillis(),realFactory?"n/a":Long.toString(output),server.overworld().getGameTime(),realFactory && io.github.limuqy.mc.hassium.compat.EgressFixtureCompat.frozen(server));
            trafficSample=now; trafficPhase=next;
        }
    }

}
