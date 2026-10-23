package io.github.limuqy.mc.hassium.compat;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.protocol.ShadowPullResponseS2CPacket;
import io.github.limuqy.mc.hassium.shadow.light.LightNeighborhoodGate;
import io.github.limuqy.mc.hassium.shadow.light.ShadowLightCompute;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;

/** Isolated smoke-world oracle. Both startup gates and the exact scenario are mandatory. */
public final class PullOrderingProbeCompat {
    private static final Set<String> SCENARIOS = Set.of("pullorder", "pullorderlegacy", "pullorderoff", "pullorderfail", "pullordersame", "pullorderhalo");
    private static final boolean SERVER = io.github.limuqy.mc.hassium.server.ServerSmokeTest.isEnabled()
            && SCENARIOS.contains(System.getProperty("hassium.serverSmokeScenario", ""));
    private static final Map<Integer, Probe> probes = new ConcurrentHashMap<>();
    private static final class Probe {
        final ServerPlayer player;
        final int x;
        final long request;
        final CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        int stage = 1;
        Probe(ServerPlayer player, int x, long request) { this.player = player; this.x = x; this.request = request; }
    }
    private PullOrderingProbeCompat() {}
    public static boolean legacyServer() {
        return SERVER && "pullorderlegacy".equals(System.getProperty("hassium.serverSmokeScenario"));
    }
    public static boolean legacyClient() { return Client.LEGACY; }
    private static BlockPos block(int x) { return new BlockPos(x * 16 + 2, 140, 6); }
    private static BlockPos sign(int x) { return new BlockPos(x * 16 + 3, 140, 6); }
    public static void beforeCompare(ServerPlayer player, LevelChunk chunk) {
#if MC_VER < MC_1_21_2
        int x = chunk.getPos().x;
        if (!SERVER || x != 4 || chunk.getPos().z != 0 || probes.containsKey(x)
                || !player.getTags().contains("pull_order_arm_" + x)) return;
        // Deliberately change one cell without broadcasting before the requested comparison.
        PlayerCompat.getServerLevel(player).setBlock(block(x),
                net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState(), 0);
#endif
    }
    public static void captured(ServerPlayer player, int x, int z, long request) {
        if (!SERVER || z != 0 || x < 3 || x > 4 || !player.getTags().contains("pull_order_arm_" + x)) return;
        Probe probe = new Probe(player, x, request);
        if (probes.putIfAbsent(x, probe) == null)
            Constants.LOG.info("HassiumSmokeTest:PULL_ORDER captured col={} request={} legacy={}", x, request, legacyServer());
    }
    public static void awaitWorker(int x, int z, long request) {
        if (!SERVER || z != 0) return;
        Probe probe = probes.get(x);
        if (probe == null || probe.request != request) return;
        probe.started.countDown();
        try {
            if (!probe.release.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("PULL_ORDER worker latch timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("PULL_ORDER worker interrupted", interrupted);
        }
    }
    public static void tick(MinecraftServer server) {
#if MC_VER < MC_1_21_2
        if (!SERVER) return;
        for (Probe probe : probes.values()) {
            var player = probe.player;
            int x = probe.x;
            if (player.hasDisconnected()) { probe.release.countDown(); continue; }
            var level = PlayerCompat.getServerLevel(player);
            if (probe.stage == 1 && probe.started.getCount() == 0) {
                if (!"pullordersame".equals(System.getProperty("hassium.serverSmokeScenario"))) {
                    level.setBlock(block(x), net.minecraft.world.level.block.Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
                    player.connection.send(new net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket(level, block(x)));
                }
                updateSign(player, x, "tail-before-" + x);
                probe.stage = 2;
                Constants.LOG.info("HassiumSmokeTest:PULL_ORDER native-before col={} request={}", x, probe.request);
            } else if (probe.stage == 2 && player.getTags().contains("pull_order_before_" + x)) {
                probe.release.countDown(); probe.stage = 3;
                Constants.LOG.info("HassiumSmokeTest:PULL_ORDER released col={} native receipt acknowledged", x);
            } else if (probe.stage == 3 && player.getTags().contains("pull_order_response_" + x)) {
                updateSign(player, x, "tail-after-" + x); probe.stage = 4;
                Constants.LOG.info("HassiumSmokeTest:PULL_ORDER native-after col={} BE-only", x);
            } else if (probe.stage == 4 && player.getTags().contains("pull_order_after_" + x)) {
                probe.stage = 5;
                Constants.LOG.info("HassiumSmokeTest:PULL_ORDER native-after acknowledged col={}", x);
            }
        }
#endif
    }
#if MC_VER < MC_1_21_2
    private static void updateSign(ServerPlayer player, int x, String message) {
        var level = PlayerCompat.getServerLevel(player);
        var sign = (net.minecraft.world.level.block.entity.SignBlockEntity) level.getBlockEntity(sign(x));
        sign.setText(sign.getFrontText().setMessage(0, net.minecraft.network.chat.Component.literal(message)), true);
        sign.setChanged();
        player.connection.send(net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(sign));
    }
#endif
    public static void sent(ServerPlayer player, int x, int z, long request,
                            ShadowPullResponseS2CPacket.Kind kind, int tail) {
        if (!SERVER || z != 0) return;
        Probe probe = probes.get(x);
        if (probe == null || probe.request != request) return;
        Constants.LOG.info("HassiumSmokeTest:PULL_ORDER response col={} kind={} tail={} request={}", x, kind, tail, request);
        if (kind != (x == 3 ? ShadowPullResponseS2CPacket.Kind.FULL : ShadowPullResponseS2CPacket.Kind.DELTA))
            throw new IllegalStateException("PULL_ORDER fixture expected " + (x == 3 ? "FULL" : "DELTA") + " got " + kind);
    }
    public static void clear() { probes.values().forEach(p -> p.release.countDown()); probes.clear(); }

    /** Client classes are resolved only by the physical client fixture. */
    public static final class Client {
        private static final boolean ENABLED = io.github.limuqy.mc.hassium.client.ClientSmokeTest.isEnabled()
                && SCENARIOS.contains(System.getProperty("hassium.smokeScenario", ""));
        private static final boolean LEGACY = ENABLED && "pullorderlegacy".equals(System.getProperty("hassium.smokeScenario"));
        private static final boolean FAIL = ENABLED && "pullorderfail".equals(System.getProperty("hassium.smokeScenario"));
        private static final boolean OFF = FAIL || ENABLED && "pullorderoff".equals(System.getProperty("hassium.smokeScenario"));
        private static final boolean SAME = ENABLED && "pullordersame".equals(System.getProperty("hassium.smokeScenario"));
        private static final boolean HALO = ENABLED && "pullorderhalo".equals(System.getProperty("hassium.smokeScenario"))
                && "Forge".equals(io.github.limuqy.mc.hassium.platform.Services.PLATFORM.getPlatformName());
        private static final io.github.limuqy.mc.hassium.client.scenario.HaloLightProbe halo=
                new io.github.limuqy.mc.hassium.client.scenario.HaloLightProbe();
        private static long nextHaloLog;
        private static final Set<Object> official = java.util.Collections.newSetFromMap(
                java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>()));
        private static final Set<Integer> armed = ConcurrentHashMap.newKeySet(), received = ConcurrentHashMap.newKeySet(),
                after = ConcurrentHashMap.newKeySet(), checked = ConcurrentHashMap.newKeySet(), applyCompletions = ConcurrentHashMap.newKeySet(),
                verified = ConcurrentHashMap.newKeySet(), recoveryVerified = ConcurrentHashMap.newKeySet();
        private static volatile int passes, failures, officialLanded, officialAttempts, sameHashFull, nativeFulls;
        private static final ThreadLocal<Boolean> exerciseBuild = ThreadLocal.withInitial(()->false);
        private static volatile java.lang.ref.WeakReference<Object> exercisedPacket = new java.lang.ref.WeakReference<>(null);
        private static volatile int officialExerciseLanded;
        private static volatile long expectedSleepGeneration;
        private static final Set<Long> publishedSleep=ConcurrentHashMap.newKeySet();
        private static boolean officialExercised;
        private static String waitPhase;
        private static long nextWaitLog;
        private static final io.github.limuqy.mc.hassium.client.scenario.PullOrderProbeScope scope=
                new io.github.limuqy.mc.hassium.client.scenario.PullOrderProbeScope();
        private static final Set<Long> nativeColumns=ConcurrentHashMap.newKeySet();
        private static final io.github.limuqy.mc.hassium.client.scenario.StableQuiescence quiescence=
                new io.github.limuqy.mc.hassium.client.scenario.StableQuiescence();
        private static volatile boolean resourcesArmed;
        private static final java.util.concurrent.atomic.AtomicLong repairs=new java.util.concurrent.atomic.AtomicLong(),
                expiredRepairs=new java.util.concurrent.atomic.AtomicLong();
        private static long repairBaseline,expiredBaseline;
        private static final java.util.concurrent.atomic.AtomicInteger resourceLogCount=new java.util.concurrent.atomic.AtomicInteger();
        private static final java.util.concurrent.atomic.AtomicInteger phaseLogCount=new java.util.concurrent.atomic.AtomicInteger();
        private Client() {}
        private static boolean target(long key) {
            var mc=net.minecraft.client.Minecraft.getInstance();
            return scope.target(key,mc.level,mc.getConnection(),io.github.limuqy.mc.hassium.server.RuntimeServerContext.getShadowGeneration());
        }
        /** Detailed packet/state evidence exists only in the explicitly enabled smoke fixture. */
        public static void packetStage(String stage,String dimension,net.minecraft.network.protocol.Packet<?> packet) {
            if(!ENABLED) return;
            var column=PullUpdatePacketCompat.column(packet);
            if(column==null || column.z!=0 || !armed.contains(column.x)) return;
            if(dimension!=null && !target(io.github.limuqy.mc.hassium.utils.DimensionKey.key(dimension,column.x,column.z))) return;
            String detail="";
            if(packet instanceof net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket update) {
                if(!update.getPos().equals(block(column.x))) return;
                detail=update.getPos()+"="+update.getBlockState();
            } else if(packet instanceof net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket update) {
                var target=block(column.x); var value=new StringBuilder();
                update.runUpdates((pos,state)->{if(pos.equals(target)) value.append(pos).append('=').append(state);});
                if(value.isEmpty()) return;
                detail=value.toString();
            } else if(packet instanceof net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket update) {
                if(!update.getPos().equals(sign(column.x))) return;
                detail=String.valueOf(update.getTag());
            }
            Constants.LOG.info("HassiumSmokeTest:PULL_ORDER packet stage={} col={} type={} value={} thread={}",
                    stage,column.x,packet.getClass().getSimpleName(),detail,Thread.currentThread().getName());
        }
        public static void ownerState(String stage,String dimension,io.github.limuqy.mc.hassium.shadow.server.ShadowSeedServer shadow) {
            if(!ENABLED || !shadow.isSameThread()) return;
            for(int x=3;x<=4;x++) {
                if(!armed.contains(x)) continue;
                if(!target(io.github.limuqy.mc.hassium.utils.DimensionKey.key(dimension,x,0))) continue;
                var chunk=shadow.injectedChunk(dimension,x,0);
                long key=io.github.limuqy.mc.hassium.utils.DimensionKey.key(dimension,x,0);
                Constants.LOG.info("HassiumSmokeTest:PULL_ORDER owner-state stage={} col={} chunk={} block={} key={} request={} token={} shadow={} generation={}",
                        stage,x,chunk==null?"missing":System.identityHashCode(chunk),chunk==null?"missing":chunk.getBlockState(block(x)),
                        key,scope.request(key),scope.token(key),System.identityHashCode(shadow),shadow.shadowGeneration());
            }
        }
        public static void deltaStage(String stage,String dimension,net.minecraft.world.level.ChunkPos pos,long token,
                                      io.github.limuqy.mc.hassium.shadow.server.ShadowSeedServer shadow) {
            if(!ENABLED || pos.z!=0 || !armed.contains(pos.x)) return;
            long key=io.github.limuqy.mc.hassium.utils.DimensionKey.key(dimension,pos.x,pos.z);
            if(!target(key)) return;
            Constants.LOG.info("HassiumSmokeTest:PULL_ORDER delta stage={} col={} token={} current={} lightCurrent={} owner={} workThread={}",
                    stage,pos.x,token,io.github.limuqy.mc.hassium.client.sleep.SleepTerrainClient.acceptPending(key,token),
                    io.github.limuqy.mc.hassium.client.sleep.SleepTerrainClient.acceptLight(key,token),
                    shadow.isSameThread(),Thread.currentThread().getName());
            ownerState(stage,dimension,shadow);
        }
        public static void nativeUpdate(net.minecraft.network.protocol.Packet<?> packet) {
#if MC_VER < MC_1_21_2
            if (!ENABLED) return;
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (packet instanceof net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket update) {
                int x = update.getPos().getX() >> 4;
                if (!armed.contains(x) || !update.getPos().equals(sign(x)) || update.getTag() == null) return;
                if(!target(io.github.limuqy.mc.hassium.utils.DimensionKey.key(LevelCompat.getDimensionId(mc.level),x,0))) return;
                String tag = update.getTag().toString();
                if (tag.contains("tail-before-" + x)) mc.getConnection().sendCommand("tag @s add pull_order_before_" + x);
                if (tag.contains("tail-after-" + x) && after.add(x)) {
                    Constants.LOG.info("HassiumSmokeTest:PULL_ORDER native-after-received col={}",x);
                    mc.getConnection().sendCommand("tag @s add pull_order_after_" + x);
                }
            }
#endif
        }
        /** Event-driven fixture progression; no sleep or native packet shape assumptions. */
        public static boolean scenarioStep(String action,int x,long elapsedMs,long timeoutMs) {
            if(!ENABLED) throw new IllegalStateException("Pull ordering fixture is not enabled");
            if(x<3 || x>4 || timeoutMs<=0) throw new IllegalArgumentException("Invalid Pull ordering step");
            var mc=net.minecraft.client.Minecraft.getInstance();
            if(!mc.isSameThread() || mc.level==null || mc.getConnection()==null)
                throw new IllegalStateException("Pull ordering step requires the connected client owner");
            String dimension=LevelCompat.getDimensionId(mc.level);
            if(HALO && halo.failure()!=null) throw new IllegalStateException("Halo phase fixture: "+halo.snapshot());
            boolean done;
            switch(action) {
                case "b" -> {
                    if(!resourcesArmed) { repairBaseline=repairs.get(); expiredBaseline=expiredRepairs.get(); resourcesArmed=true; }
                    long key=io.github.limuqy.mc.hassium.utils.DimensionKey.key(dimension,3,0);
                    if(!officialExercised && !io.github.limuqy.mc.hassium.client.PullSnapshotTailClient.active(key)
                            && ShadowLightCompute.clientHasChunk(3,0)) {
                        officialExercised=true; exerciseOfficial();
                    }
                    if(failures>0) throw new IllegalStateException("Controlled official B ingress failed");
                    done=officialExerciseLanded>0;
                }
                case "ready" -> {
                    for(int cx=3;cx<=(SAME?3:4);cx++) {
                        long key=io.github.limuqy.mc.hassium.utils.DimensionKey.key(dimension,cx,0);
                        if(io.github.limuqy.mc.hassium.client.sleep.SleepTerrainClient.completedSleepColumn(key) || publishedSleep.contains(key))
                            recoveryVerified.add(cx);
                    }
                    done=recoveryVerified.contains(3) && (SAME || recoveryVerified.contains(4));
                }
                case "request" -> {
                    if(!OFF && !recoveryVerified.contains(x)) throw new IllegalStateException("Request precedes COLUMN_READY for " + x);
                    if(armed.add(x)) {
                        scope.arm(io.github.limuqy.mc.hassium.utils.DimensionKey.key(dimension,x,0),mc.level,mc.getConnection(),io.github.limuqy.mc.hassium.server.RuntimeServerContext.getShadowGeneration());
                        mc.getConnection().sendCommand("tag @s add pull_order_arm_"+x);
                        var column=new net.minecraft.world.level.ChunkPos(x,0);
                        Constants.LOG.info("HassiumSmokeTest:PULL_ORDER ordinary-after-sleep col={} recovered={} off={} explicitFullRepair={}",
                                x,recoveryVerified.contains(x),OFF,x==3 && !SAME);
                        io.github.limuqy.mc.hassium.protocol.ShadowPullClient.clearCompareRequested(dimension,column);
                        if(x==3 && !SAME) io.github.limuqy.mc.hassium.protocol.ShadowPullClient.requestNbtRepairFull(dimension,java.util.List.of(column));
                        else if(x==3) io.github.limuqy.mc.hassium.protocol.ShadowPullClient.requestAuthoritativeFull(dimension,java.util.List.of(column));
                        else io.github.limuqy.mc.hassium.protocol.ShadowPullClient.requestFull(dimension,java.util.List.of(column));
                    }
                    done=true;
                }
                case "chain" -> done=received.contains(x) && after.contains(x) && applyCompletions.contains(x) && verified.contains(x);
                case "haloArm" -> {
                    if(!HALO || !resourcesArmed || !recoveryVerified.contains(3) || !recoveryVerified.contains(4))
                        throw new IllegalStateException("Forge halo fixture must follow real B and sleep recovery");
                    halo.arm(mc.level,mc.getConnection(),io.github.limuqy.mc.hassium.server.RuntimeServerContext.getShadowGeneration(),System.nanoTime());
                    Constants.LOG.info("HassiumSmokeTest:PULL_ORDER halo-phase stage=armed timeoutMs=15000");done=true;
                }
                case "halo" -> {
                    if(!HALO || timeoutMs>15000) throw new IllegalArgumentException("Invalid Forge halo phase wait");
                    tickHalo();
                    if(halo.failure()!=null) throw new IllegalStateException("Halo phase fixture: "+halo.snapshot());
                    done=halo.complete() && elapsedMs<timeoutMs;
                }
                case "quiescent" -> {
                    if(timeoutMs>15000) throw new IllegalArgumentException("Resource wait exceeds 15 seconds");
                    int targets=targetInflight();
                    int global=io.github.limuqy.mc.hassium.client.PullSnapshotTailClient.activeCount();
                    done=quiescence.update(elapsedMs,targets,global,
                            io.github.limuqy.mc.hassium.client.PullSnapshotTailClient.reservedBytes());
                    if(elapsedMs>=timeoutMs) done=false; // A stalled tick cannot turn a late zero into a pass.
                    if(!("quiescent:"+x).equals(waitPhase) || elapsedMs>=nextWaitLog || elapsedMs>=timeoutMs || done)
                        Constants.LOG.info("HassiumSmokeTest:PULL_ORDER resources targetActive={} globalActive={} reservedBytes={} elapsedMs={} stable={} repairs={} expiredRepairs={} active={}",
                                targets,global,io.github.limuqy.mc.hassium.client.PullSnapshotTailClient.reservedBytes(),elapsedMs,done,
                                repairs.get()-repairBaseline,expiredRepairs.get()-expiredBaseline,
                                io.github.limuqy.mc.hassium.client.PullSnapshotTailClient.activeDiagnostics());
                    if((SAME || HALO) && repairs.get()!=repairBaseline)
                        throw new IllegalStateException("Fixture resources required repair: repairs="+(repairs.get()-repairBaseline)
                                +" expired="+(expiredRepairs.get()-expiredBaseline));
                }
                default -> throw new IllegalArgumentException("Unknown Pull ordering action "+action);
            }
            String phase=action+":"+x;
            if(done) {
                Constants.LOG.info("HassiumSmokeTest:PULL_ORDER stage-complete phase={} elapsedMs={}",phase,elapsedMs);
                waitPhase=null; return true;
            }
            if(elapsedMs>=timeoutMs) throw new IllegalStateException("Pull ordering stage timeout "+phase
                    +" received="+received+" after="+after+" applied="+applyCompletions+" verified="+verified+" recovered="+recoveryVerified);
            if(!phase.equals(waitPhase) || elapsedMs>=nextWaitLog) {
                waitPhase=phase; nextWaitLog=elapsedMs+5000;
                Constants.LOG.info("HassiumSmokeTest:PULL_ORDER stage-wait phase={} elapsedMs={} recovered={}",phase,elapsedMs,recoveryVerified);
                if("ready".equals(action)) for(int cx=3;cx<=(SAME?3:4);cx++)
                    sleepEvent(io.github.limuqy.mc.hassium.utils.DimensionKey.key(dimension,cx,0),"ready-wait");
            }
            return false;
        }
        public static void response(String dimension,int x,int z,long request,ShadowPullResponseS2CPacket.Kind kind,long token) {
            if (!ENABLED || z != 0 || !armed.contains(x)) return;
            var mc=net.minecraft.client.Minecraft.getInstance();
            long key=io.github.limuqy.mc.hassium.utils.DimensionKey.key(dimension,x,z);
            if(!scope.response(key,request,token,mc.level,mc.getConnection(),io.github.limuqy.mc.hassium.server.RuntimeServerContext.getShadowGeneration())) return;
            received.add(x);
            Constants.LOG.info("HassiumSmokeTest:PULL_ORDER envelope col={} request={} kind={} legacy={} key={} token={}",x,request,kind,LEGACY,key,token);
            mc.getConnection().sendCommand("tag @s add pull_order_response_" + x);
            if (OFF) {
                if (FAIL) io.github.limuqy.mc.hassium.shadow.server.ShadowServerRegistry.getInstance().failShadowServer();
                else {
                    var service = io.github.limuqy.mc.hassium.config.HassiumConfigService.getInstance();
                    var values = io.github.limuqy.mc.hassium.config.ConfigSnapshotAdapter.toValues(service.getConfig())
                            .with(io.github.limuqy.mc.hassium.config.ConfigSchema.CHUNK_ENABLED, false);
                    // Runtime snapshot only; never persist a fixture setting to the user's config files.
                    service.updateConfig(io.github.limuqy.mc.hassium.config.ConfigSnapshotAdapter.fromValues(values, true));
                }
                Constants.LOG.info("HassiumSmokeTest:PULL_ORDER engine-disabled active={} failed={}",
                        io.github.limuqy.mc.hassium.client.PullSnapshotTailClient.activeCount(), FAIL);
                io.github.limuqy.mc.hassium.client.PullSnapshotTailClient.expire();
            }
        }
        public static void officialBuilt(Object packet) {
            if (!ENABLED) return;
            if (official.size()<8192) official.add(packet);
            if (exerciseBuild.get()) exercisedPacket=new java.lang.ref.WeakReference<>(packet);
        }
        public static void officialAttempt() { if (ENABLED) officialAttempts++; }
        public static void officialApplied(Object packet) {
            if (ENABLED && official.remove(packet)) {
                officialLanded++;
                if(exercisedPacket.get()==packet) officialExerciseLanded++;
                Constants.LOG.info("HassiumSmokeTest:PULL_ORDER B-applied count={} exercised={}",officialLanded,officialExerciseLanded);
            }
        }
        /** Explicit real B ingress: the network-injection A path need not visit vanilla holder callbacks. */
        private static void exerciseOfficial() {
            if (!ENABLED) return;
            var mc=net.minecraft.client.Minecraft.getInstance(); var level=mc.level; var connection=mc.getConnection();
            var shadow=io.github.limuqy.mc.hassium.shadow.server.ShadowServerRegistry.getInstance().get();
            if(level==null || connection==null || shadow==null) { failures++; return; }
            String dimension=LevelCompat.getDimensionId(level);
            shadow.execute(()->{
                if(mc.level!=level || mc.getConnection()!=connection) return;
                var chunk=shadow.injectedChunk(dimension,3,0);
                long key=io.github.limuqy.mc.hassium.utils.DimensionKey.key(dimension,3,0);
                int natural=officialAttempts;
                Constants.LOG.info("HassiumSmokeTest:PULL_ORDER light-confirmations {}",shadow.lightConfirmationSnapshot());
                lightDiagnostics(shadow,dimension,3);
                lightDiagnostics(shadow,dimension,4);
                boolean offered;
                exerciseBuild.set(true);
                try { offered=io.github.limuqy.mc.hassium.shadow.track.ShadowOfficialPacketBridge.forwardChunkToRealClient(chunk,dimension); }
                finally { exerciseBuild.remove(); }
                Constants.LOG.info("HassiumSmokeTest:PULL_ORDER B-exercise col=3 naturalAttempts={} owner={} current={} fenced={} offered={} gate3={} gate4={}",
                        natural,shadow.isSameThread(),chunk!=null && shadow.injectedChunk(dimension,3,0)==chunk,
                        io.github.limuqy.mc.hassium.client.PullSnapshotTailClient.active(key),offered,
                        io.github.limuqy.mc.hassium.shadow.track.ShadowTrackingSession.lightGateDetail(dimension,new net.minecraft.world.level.ChunkPos(3,0)),
                        io.github.limuqy.mc.hassium.shadow.track.ShadowTrackingSession.lightGateDetail(dimension,new net.minecraft.world.level.ChunkPos(4,0)));
                if(!offered) mc.execute(()->failures++);
            });
        }
        private static void lightDiagnostics(io.github.limuqy.mc.hassium.shadow.server.ShadowSeedServer shadow,String dimension,int x) {
            var pos=new net.minecraft.world.level.ChunkPos(x,0);
            long key=io.github.limuqy.mc.hassium.utils.DimensionKey.key(dimension,x,0);
            StringBuilder neighbors=new StringBuilder();
            for(int dx=-1;dx<=1;dx++) for(int dz=-1;dz<=1;dz++) {
                int nx=x+dx,nz=dz;
                long neighbor=io.github.limuqy.mc.hassium.utils.DimensionKey.key(dimension,nx,nz);
                neighbors.append('(').append(nx).append(',').append(nz).append(":window=")
                        .append(LightNeighborhoodGate.isInNeighborhoodWindow(nx,nz)).append(",injected=")
                        .append(shadow.injectedChunk(dimension,nx,nz)!=null).append(",init=")
                        .append(ShadowLightCompute.isLightInitPassed(neighbor)).append(") ");
            }
            Constants.LOG.info("HassiumSmokeTest:PULL_ORDER light-column col={} clean={} awaiting={} full3x3={} readyNeighbors={} confirmationBlocked={} neighbors={}",
                    x,LightNeighborhoodGate.wasPromotedClean(key),LightNeighborhoodGate.isAwaiting(key),
                    LightNeighborhoodGate.isNeighborhoodFullyReady(dimension,pos),LightNeighborhoodGate.inWindowReadyNeighborCount(dimension,pos),
                    ShadowLightCompute.isLightConfirmationBlocked(key),neighbors);
        }
        public static int officialLanded() { return officialLanded; }
        public static void beginSleep(long generation) {
            if(!ENABLED) return;
            expectedSleepGeneration=generation; publishedSleep.clear(); recoveryVerified.clear();
        }
        public static void sleepPublished(long key,long generation) {
            if(!ENABLED || generation!=expectedSleepGeneration || io.github.limuqy.mc.hassium.utils.DimensionKey.chunkZOf(key)!=0) return;
            int x=io.github.limuqy.mc.hassium.utils.DimensionKey.chunkXOf(key);
            if(x!=3 && x!=4) return;
            publishedSleep.add(key); sleepEvent(key,"COLUMN_READY-observed generation="+generation);
        }
        public static void sleepEvent(long key,String event) {
            if(!ENABLED || io.github.limuqy.mc.hassium.utils.DimensionKey.chunkZOf(key)!=0) return;
            int x=io.github.limuqy.mc.hassium.utils.DimensionKey.chunkXOf(key);
            if(x!=3 && x!=4) return;
            Constants.LOG.info("HassiumSmokeTest:PULL_ORDER sleep-state col={} event={} state={}",x,event,
                    io.github.limuqy.mc.hassium.client.sleep.SleepTerrainClient.snapshotState(key));
        }
        public static void sleepNbtAdmission(long key,long wire,long opaque) {
            if(!ENABLED) return;
            sleepEvent(key,"UNCHANGED-admission wire="+wire+" opaque="+opaque);
        }
        public static int officialExerciseLanded() { return officialExerciseLanded; }
        public static int responses() { return received.size(); }
        public static int afterReceipts() { return after.size(); }
        public static int applications() { return applyCompletions.size(); }
        public static int checks() { return verified.size(); }
        public static int targetInflight() {
            if(!ENABLED) return 0;
            int n=0; for(long key:scope.keys())
                if(target(key) && io.github.limuqy.mc.hassium.client.PullSnapshotTailClient.active(key)) n++;
            return n;
        }
        public static void resourceEvent(long key,long token,String reason) {
            if(!ENABLED) return;
            if(reason.endsWith("repair")) repairs.incrementAndGet();
            if(reason.equals("expired-repair")) expiredRepairs.incrementAndGet();
            if(resourcesArmed && resourceLogCount.getAndIncrement()<512)
                Constants.LOG.info("HassiumSmokeTest:PULL_ORDER resource-event reason={} key={} dim={} pos=({}, {}) token={} thread={}",
                        reason,key,io.github.limuqy.mc.hassium.utils.DimensionKey.dimensionOf(key),
                        io.github.limuqy.mc.hassium.utils.DimensionKey.chunkXOf(key),io.github.limuqy.mc.hassium.utils.DimensionKey.chunkZOf(key),
                        token,Thread.currentThread().getName());
        }
        public static void lightPhase(long key,long token,String stage,Object baseline) {
            if(!ENABLED || !resourcesArmed) return;
            boolean pending=io.github.limuqy.mc.hassium.client.sleep.SleepTerrainClient.acceptPending(key,token);
            boolean light=io.github.limuqy.mc.hassium.client.sleep.SleepTerrainClient.acceptLight(key,token);
            if(!pending && light && phaseLogCount.getAndIncrement()<128)
                Constants.LOG.info("HassiumSmokeTest:PULL_ORDER light-phase stage={} key={} pos=({}, {}) token={} pending={} lightCurrent={} chunk={} thread={}",
                        stage,key,io.github.limuqy.mc.hassium.utils.DimensionKey.chunkXOf(key),io.github.limuqy.mc.hassium.utils.DimensionKey.chunkZOf(key),
                        token,pending,light,System.identityHashCode(baseline),Thread.currentThread().getName());
            if(HALO) {
                var server=io.github.limuqy.mc.hassium.shadow.server.ShadowServerRegistry.getInstance().get();
                if(server==null) { tickHalo();return; }
                var event=haloIdentity(key,token,server,baseline);
                if(!halo.observes(event)) { tickHalo();return; }
                // Nonblocking even when the LIGHT caller holds its engine mutex. Baseline replacement
                // and this final observation are serialized on the actual shadow owner.
                try { server.execute(()->haloPhaseOnOwner(event,stage)); }
                catch(java.util.concurrent.RejectedExecutionException rejected) {
                    halo.cancel(key,token,"phase owner rejected callback");haloLog("cancelled",false,false);
                }
            }
        }
        private static void haloPhaseOnOwner(io.github.limuqy.mc.hassium.client.scenario.HaloLightProbe.Identity event,String stage) {
            var expected=(io.github.limuqy.mc.hassium.shadow.server.ShadowSeedServer)event.server();
            if(!expected.isMainLoopThread()) {
                halo.cancel(event.key(),event.token(),"phase callback is not on shadow owner");haloLog("cancelled",false,false);return;
            }
            var mc=net.minecraft.client.Minecraft.getInstance();
            var current=io.github.limuqy.mc.hassium.shadow.server.ShadowServerRegistry.getInstance().get();
            Object currentBaseline=current==null?null:current.injectedChunk(
                    io.github.limuqy.mc.hassium.utils.DimensionKey.dimensionOf(event.key()),
                    io.github.limuqy.mc.hassium.utils.DimensionKey.chunkXOf(event.key()),io.github.limuqy.mc.hassium.utils.DimensionKey.chunkZOf(event.key()));
            boolean pending=io.github.limuqy.mc.hassium.client.sleep.SleepTerrainClient.acceptPending(event.key(),event.token());
            boolean light=io.github.limuqy.mc.hassium.client.sleep.SleepTerrainClient.acceptLight(event.key(),event.token());
            String before=halo.failure();
            if(halo.phaseCurrent(event,stage,mc.level,mc.getConnection(),current,
                    io.github.limuqy.mc.hassium.server.RuntimeServerContext.getShadowGeneration(),currentBaseline,pending,light,System.nanoTime()))
                haloLog(stage,pending,light);
            else if(before==null && halo.failure()!=null) haloLog("cancelled",pending,light);
        }
        private static io.github.limuqy.mc.hassium.client.scenario.HaloLightProbe.Identity haloIdentity(long key,long token,
                io.github.limuqy.mc.hassium.shadow.server.ShadowSeedServer server,Object baseline) {
            var mc=net.minecraft.client.Minecraft.getInstance();
            return new io.github.limuqy.mc.hassium.client.scenario.HaloLightProbe.Identity(key,token,server,
                    server.shadowGeneration(),baseline,mc.level,mc.getConnection());
        }
        public static void haloCommitted(long key,long token,io.github.limuqy.mc.hassium.shadow.server.ShadowSeedServer server,Object baseline) {
            if(!HALO || !resourcesArmed) return;
            if(halo.select(haloIdentity(key,token,server,baseline),System.nanoTime()))
                haloLog("content-committed",true,io.github.limuqy.mc.hassium.client.sleep.SleepTerrainClient.acceptLight(key,token));
        }
        /** Called outside owner/chunk/light locks. True only delays the selected smoke work's LIGHT. */
        public static boolean deferHaloLight(long key,long token,io.github.limuqy.mc.hassium.shadow.server.ShadowSeedServer server,Object baseline) {
            if(!HALO) return false;
            tickHalo();
            boolean pending=io.github.limuqy.mc.hassium.client.sleep.SleepTerrainClient.acceptPending(key,token);
            boolean light=io.github.limuqy.mc.hassium.client.sleep.SleepTerrainClient.acceptLight(key,token);
            var decision=halo.beforeLight(haloIdentity(key,token,server,baseline),pending,light,System.nanoTime());
            if(decision==io.github.limuqy.mc.hassium.client.scenario.HaloLightProbe.Decision.DEFER) {
                long now=System.nanoTime();
                if(now>=nextHaloLog) { nextHaloLog=now+500_000_000L;haloLog("deferred-light",pending,light); }
                return true;
            }
            if(decision==io.github.limuqy.mc.hassium.client.scenario.HaloLightProbe.Decision.RELEASE)
                haloLog("released-light",pending,light);
            return false;
        }
        public static void haloRequeueLost(long key,long token) {
            if(!HALO) return;
            halo.cancel(key,token,"newer work occupied the bounded retry slot");haloLog("cancelled",false,false);
        }
        public static void haloTailFinished(long key,long token) {
            if(!HALO) return;
            tickHalo();
            if(halo.tailFinished(key,token,System.nanoTime())) haloLog("tail-finished",false,
                    io.github.limuqy.mc.hassium.client.sleep.SleepTerrainClient.acceptLight(key,token));
        }
        private static void haloLog(String stage,boolean pending,boolean light) {
            long key=halo.key();
            Constants.LOG.info("HassiumSmokeTest:PULL_ORDER halo-phase stage={} pending={} lightCurrent={} {} dim={} pos=({}, {}) sleepState={} thread={}",
                    stage,pending,light,halo.snapshot(),io.github.limuqy.mc.hassium.utils.DimensionKey.dimensionOf(key),
                    io.github.limuqy.mc.hassium.utils.DimensionKey.chunkXOf(key),io.github.limuqy.mc.hassium.utils.DimensionKey.chunkZOf(key),
                    io.github.limuqy.mc.hassium.client.sleep.SleepTerrainClient.snapshotState(key),Thread.currentThread().getName());
        }
        private static void tickHalo() {
            if(!HALO) return;
            var mc=net.minecraft.client.Minecraft.getInstance();
            var selected=halo.selected();var server=io.github.limuqy.mc.hassium.shadow.server.ShadowServerRegistry.getInstance().get();
            Object baseline=selected==null || server==null?null:server.injectedChunk(
                    io.github.limuqy.mc.hassium.utils.DimensionKey.dimensionOf(selected.key()),
                    io.github.limuqy.mc.hassium.utils.DimensionKey.chunkXOf(selected.key()),io.github.limuqy.mc.hassium.utils.DimensionKey.chunkZOf(selected.key()));
            String before=halo.failure();
            halo.validate(mc.level,mc.getConnection(),server,io.github.limuqy.mc.hassium.server.RuntimeServerContext.getShadowGeneration(),baseline,
                    selected==null || io.github.limuqy.mc.hassium.client.sleep.SleepTerrainClient.acceptLight(selected.key(),selected.token()),System.nanoTime());
            if(before==null && halo.failure()!=null) haloLog("cancelled",false,false);
        }
        public static int haloPhases() { return HALO && halo.complete()?1:0; }
        public static int sameHashFull() { return sameHashFull; }
        public static int nativeFulls() { return nativeFulls; }
        public static void nativeFullApplied(long key) { if (OFF && target(key)) { nativeColumns.add(key); nativeFulls++; } }
        public static void legacySnapshotApplied(long key,long token,io.github.limuqy.mc.hassium.platform.client.TraceOrigin origin) {
            if(!LEGACY) return;
            int x=io.github.limuqy.mc.hassium.utils.DimensionKey.chunkXOf(key);
            if((x==3 && origin==io.github.limuqy.mc.hassium.platform.client.TraceOrigin.REMOTE_PULL)
                    || (x==4 && origin==io.github.limuqy.mc.hassium.platform.client.TraceOrigin.SECTION_DELTA)) completed(key,token);
        }
        public static boolean observesSameHashFull() { return SAME; }
        public static void fullIngress(long key,long token, boolean matches, boolean repair, boolean replace) {
            if(!ENABLED) return;
            var mc=net.minecraft.client.Minecraft.getInstance();
            if (SAME && scope.matches(key,token,mc.level,mc.getConnection(),io.github.limuqy.mc.hassium.server.RuntimeServerContext.getShadowGeneration()) && received.contains(3)) {
                if (matches && !repair && replace) sameHashFull++;
                Constants.LOG.info("HassiumSmokeTest:PULL_ORDER same-hash-full matches={} nbtRepair={} replace={}",matches,repair,replace);
            }
        }
        public static void tick() {
            if(HALO) tickHalo();
#if MC_VER < MC_1_21_2
            if (!OFF || nativeFulls==0 || io.github.limuqy.mc.hassium.client.PullSnapshotTailClient.activeCount() != 0) return;
            var level = net.minecraft.client.Minecraft.getInstance().level;
            if (level == null) return;
            for (int x : after) {
                if (level.getBlockState(block(x)).is(net.minecraft.world.level.block.Blocks.DIAMOND_BLOCK)
                        && level.getBlockEntity(sign(x)) instanceof net.minecraft.world.level.block.entity.SignBlockEntity be
                        && be.getFrontText().getMessage(0, false).getString().equals("tail-after-" + x))
                    {
                        long key=io.github.limuqy.mc.hassium.utils.DimensionKey.key(LevelCompat.getDimensionId(level),x,0);
                        if(nativeColumns.contains(key)) completed(key,scope.token(key));
                    }
            }
#endif
        }
        public static boolean deferApply(long key) {
            if (!ENABLED) return false;
            int x = io.github.limuqy.mc.hassium.utils.DimensionKey.chunkXOf(key);
            return target(key) && received.contains(x) && !after.contains(x);
        }
        public static void completed(long key,long token) {
#if MC_VER < MC_1_21_2
            if (!ENABLED) return;
            int x = io.github.limuqy.mc.hassium.utils.DimensionKey.chunkXOf(key);
            var mc = net.minecraft.client.Minecraft.getInstance(); var source = mc.level;
            if(io.github.limuqy.mc.hassium.client.PullSnapshotTailClient.active(key)
                    || !scope.matches(key,token,source,mc.getConnection(),io.github.limuqy.mc.hassium.server.RuntimeServerContext.getShadowGeneration())) return;
            if(OFF && (nativeFulls==0 || io.github.limuqy.mc.hassium.client.PullSnapshotTailClient.activeCount()!=0)) return;
            if(!after.contains(x) || !scope.complete(key,token,source,mc.getConnection(),io.github.limuqy.mc.hassium.server.RuntimeServerContext.getShadowGeneration())) return;
            if (armed.contains(x) && received.contains(x) && applyCompletions.add(x))
                Constants.LOG.info("HassiumSmokeTest:PULL_ORDER apply-completion col={} afterReceived={} legacy={} key={} request={} token={}",
                        x,after.contains(x),LEGACY,key,scope.request(key),token);
            if (!armed.contains(x) || !received.contains(x) || !after.contains(x) || !checked.add(x)) return;
            if (OFF) {
                boolean nativeOk = source != null && source.getBlockState(block(x)).is(net.minecraft.world.level.block.Blocks.DIAMOND_BLOCK)
                        && source.getBlockEntity(sign(x)) instanceof net.minecraft.world.level.block.entity.SignBlockEntity be
                        && be.getFrontText().getMessage(0, false).getString().equals("tail-after-" + x)
                        && !io.github.limuqy.mc.hassium.shadow.light.ShadowLightCompute.shouldInterceptVanillaChunks()
                        && nativeFulls>0
                        && io.github.limuqy.mc.hassium.client.PullSnapshotTailClient.activeCount() == 0;
                if (nativeOk) passes++; else failures++;
                verified.add(x);
                Constants.LOG.info("HassiumSmokeTest:PULL_ORDER native-fallback col={} visible={} passes={} failures={} inflight={}",
                        x, nativeOk, passes, failures, io.github.limuqy.mc.hassium.client.PullSnapshotTailClient.activeCount());
                return;
            }
            var shadow = io.github.limuqy.mc.hassium.shadow.server.ShadowServerRegistry.getInstance().get();
            if (shadow == null) { failures++; return; }
            String dimension = LevelCompat.getDimensionId(source);
            Object sourceConnection=mc.getConnection();
            int sourceGeneration=io.github.limuqy.mc.hassium.server.RuntimeServerContext.getShadowGeneration();
            shadow.execute(() -> {
                if(!scope.matches(key,token,mc.level,mc.getConnection(),io.github.limuqy.mc.hassium.server.RuntimeServerContext.getShadowGeneration())
                        || mc.level!=source || mc.getConnection()!=sourceConnection
                        || shadow.shadowGeneration()!=sourceGeneration) return;
                var chunk = shadow.injectedChunk(dimension, x, 0);
                Constants.LOG.info("HassiumSmokeTest:PULL_ORDER check-owner col={} key={} request={} token={} shadow={} generation={} chunk={}",
                        x,key,scope.request(key),token,System.identityHashCode(shadow),shadow.shadowGeneration(),
                        chunk==null?"missing":System.identityHashCode(chunk));
                String ownerBlock=chunk==null?"MISSING_CHUNK":chunk.getBlockState(block(x)).toString();
                String ownerText=chunk==null?"MISSING_CHUNK":text(chunk,x);
                boolean ownerOk = chunk != null && chunk.getBlockState(block(x)).is(SAME
                        ? net.minecraft.world.level.block.Blocks.STONE : net.minecraft.world.level.block.Blocks.DIAMOND_BLOCK)
                        && text(chunk, x).equals("tail-after-" + x);
                mc.execute(() -> {
                    if(!scope.matches(key,token,mc.level,mc.getConnection(),io.github.limuqy.mc.hassium.server.RuntimeServerContext.getShadowGeneration())
                            || mc.level!=source || mc.getConnection()!=sourceConnection) return;
                    boolean visibleChunk=mc.level==source && ShadowLightCompute.clientHasChunk(x,0);
                    String visibleBlock=visibleChunk?source.getBlockState(block(x)).toString():"MISSING_CHUNK";
                    String visibleText=visibleChunk && source.getBlockEntity(sign(x)) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign
                            ?sign.getFrontText().getMessage(0,false).getString():"MISSING_BE";
                    boolean visibleOk = mc.level == source && source.getBlockState(block(x)).is(SAME
                            ? net.minecraft.world.level.block.Blocks.STONE : net.minecraft.world.level.block.Blocks.DIAMOND_BLOCK)
                            && source.getBlockEntity(sign(x)) instanceof net.minecraft.world.level.block.entity.SignBlockEntity be
                            && be.getFrontText().getMessage(0, false).getString().equals("tail-after-" + x);
                    if (ownerOk && visibleOk) passes++; else failures++;
                    verified.add(x);
                    Constants.LOG.info("HassiumSmokeTest:PULL_ORDER checked col={} visible={} shadow={} passes={} failures={} legacy={}",
                            x, visibleOk, ownerOk, passes, failures, LEGACY);
                    Constants.LOG.info("HassiumSmokeTest:PULL_ORDER fingerprint col={} legacy={} expectedBlock={} visibleChunk={} visibleBlock={} shadowChunk={} shadowBlock={} expectedBE={} visibleBE={} shadowBE={}",
                            x,LEGACY,(SAME?net.minecraft.world.level.block.Blocks.STONE:net.minecraft.world.level.block.Blocks.DIAMOND_BLOCK).defaultBlockState(),
                            visibleChunk,visibleBlock,chunk!=null,ownerBlock,"tail-after-"+x,visibleText,ownerText);
                });
            });
#endif
        }
#if MC_VER < MC_1_21_2
        private static String text(LevelChunk chunk, int x) {
            return chunk.getBlockEntity(sign(x)) instanceof net.minecraft.world.level.block.entity.SignBlockEntity be
                    ? be.getFrontText().getMessage(0, false).getString() : "MISSING";
        }
#endif
        public static int passes() { return passes; }
        public static int failures() { return failures; }
    }
}
