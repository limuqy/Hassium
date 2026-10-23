package io.github.limuqy.mc.hassium.client;

import io.github.limuqy.mc.hassium.compat.LevelCompat;
import io.github.limuqy.mc.hassium.compat.PacketCodecCompat;
import io.github.limuqy.mc.hassium.compat.PullOrderingProbeCompat;
import io.github.limuqy.mc.hassium.compat.PullUpdatePacketCompat;
import io.github.limuqy.mc.hassium.protocol.ColumnSnapshotFence;
import io.github.limuqy.mc.hassium.protocol.ShadowPullClient;
import io.github.limuqy.mc.hassium.protocol.ShadowPullRequestC2SPacket;
import io.github.limuqy.mc.hassium.protocol.ShadowPullResponseS2CPacket;
import io.github.limuqy.mc.hassium.utils.DimensionKey;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.level.ChunkPos;

/** Render-owned native tails; the token table alone is safe for the shadow owner to query. */
public final class PullSnapshotTailClient {
    private static final ColumnSnapshotFence<PacketCodecCompat.CapturedPacket> fence =
            new ColumnSnapshotFence<>(8192, 128, 128L << 20);
    private static Object sourceLevel, sourceConnection;
    private static boolean replaying;
    private static final java.util.Map<Long, Long> started = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<Long> nativeOnly = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static Boolean engineState;
    private PullSnapshotTailClient() {}

    /** Runs in native receive order before terrain is handed to the decode worker. */
    public static void envelope(byte[] data) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) throw new IllegalStateException("Pull envelope requires the client owner");
        if (mc.level == null || mc.getConnection() == null) return;
        if (sourceLevel != mc.level || sourceConnection != mc.getConnection()) {
            if (sourceLevel != null) { fence.clear(); started.clear(); nativeOnly.clear(); }
            sourceLevel = mc.level; sourceConnection = mc.getConnection();
        }
        if (engineState == null) engineState = engineAvailable();
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(data));
        try {
            String dimension = buf.readUtf(ShadowPullRequestC2SPacket.MAX_DIMENSION_LENGTH);
            buf.readVarLong(); long request = buf.readVarLong();
            int count = buf.readVarInt();
            if (count < 0 || count > ShadowPullResponseS2CPacket.MAX_RESULTS) return;
            for (int i = 0; i < count; i++) {
                int x = buf.readVarInt(), z = buf.readVarInt();
                var kind = ShadowPullResponseS2CPacket.Kind.fromWire(buf.readVarInt());
                buf.readLong(); int sections = buf.readVarInt();
                if (sections < 0 || sections > 64) return;
                buf.skipBytes(sections * Long.BYTES);
                int bytes = buf.readVarInt();
                if (bytes < 0 || bytes > ShadowPullResponseS2CPacket.MAX_PAYLOAD_BYTES) return;
                buf.skipBytes(bytes); buf.readUtf(ShadowPullResponseS2CPacket.MAX_ERROR_LENGTH);
                if ((kind != ShadowPullResponseS2CPacket.Kind.FULL && kind != ShadowPullResponseS2CPacket.Kind.DELTA)
                        || !ShadowPullClient.matchesEnvelope(dimension, x, z, request)) continue;
                long key = DimensionKey.key(dimension, x, z);
                if (!PullOrderingProbeCompat.legacyClient()) {
                    if (fence.begin(key, request)) {
                        started.put(key, System.nanoTime());
                        if (!engineAvailable()) nativeOnly.add(key);
                    }
                    else repair(key);
                } else fence.cancel(key); // Gated legacy control rejects prior-transaction ready work.
                PullOrderingProbeCompat.Client.response(dimension,x,z,request,kind,fence.incoming(key));
            }
        } catch (RuntimeException malformed) {
            // The regular decoder owns malformed envelope diagnostics and repair.
        } finally { buf.release(); }
    }
    public static boolean responseCurrent(long key, long request) {
        return PullOrderingProbeCompat.legacyClient() || fence.request(key, request);
    }
    public static boolean intercept(Packet<?> packet) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread() || mc.level == null || mc.getConnection() == null || replaying) return false;
        PullOrderingProbeCompat.Client.nativeUpdate(packet);
        if (PullOrderingProbeCompat.legacyClient()) return false;
        var pos = PullUpdatePacketCompat.column(packet);
        if (pos == null) return false;
        long key = DimensionKey.key(LevelCompat.getDimensionId(mc.level), pos.x, pos.z);
        if (!fence.active(key)) return false;
        try {
            PullOrderingProbeCompat.Client.packetStage("held",LevelCompat.getDimensionId(mc.level),packet);
            var captured = PacketCodecCompat.captureClientbound(packet, mc.level.registryAccess());
            fence.append(key, captured, captured.body().length);
        } catch (Throwable failure) { fence.overflow(key); }
        if (fence.overflowed(key)) { repair(key); return false; }
        return true;
    }
    public static long incoming(long key) { return fence.incoming(key); }
    public static boolean pending(long key, long token) { return fence.pending(key, token); }
    public static boolean lightCurrent(long key,long token) { return fence.lightCurrent(key,token); }
    public static void injected(long key, long token, Object chunk) { fence.injected(key, token, chunk); }
    public static void injectedRequest(long key, long request, Object chunk) { fence.injectedRequest(key, request, chunk); }
    public static long ready(long key, Object chunk) { return fence.ready(key, chunk); }
    public static boolean acceptReady(long key, long token) { return fence.acceptsReady(key, token); }
    public static boolean forceReplace(long key) { return fence.requiresFullReplacement(key); }
    public static boolean active(long key) { return fence.active(key); }
    public static String columnDiagnostics(long key) { return fence.diagnostics(key); }
    private static boolean engineAvailable() {
        return io.github.limuqy.mc.hassium.shadow.light.ShadowLightCompute.shouldInterceptVanillaChunks();
    }
    public static boolean mustApplyDirect(long key) { return nativeOnly.contains(key) || !engineAvailable(); }

    /** Called after actual vanilla FULL application when shadow ingestion is unavailable. */
    public static void directlyApplied(long key) {
        if (!fence.active(key)) return;
        nativeOnly.add(key);
        PullOrderingProbeCompat.Client.nativeFullApplied(key);
        fence.injected(key, fence.incoming(key), sourceLevel);
        io.github.limuqy.mc.hassium.client.sleep.SleepTerrainClient.directlyApplied(key);
    }

    /** Cancel stale shadow work, restore held native updates, then request one current native FULL. */
    public static void fallbackToNative(long key) {
        nativeOnly.add(key);
        io.github.limuqy.mc.hassium.client.sleep.SleepTerrainClient.cancelSnapshot(key);
        if (fence.active(key)) {
            long version = fence.incoming(key);
            fence.injected(key, version, sourceLevel);
            long drain = fence.startDrain(key);
            drain(key, drain < 0 ? version : drain, () -> repair(key,"native-fallback-repair"));
        } else repair(key,"native-fallback-repair");
    }

    /** True transfers the caller's ACK/completion to the final ordered shadow-owner boundary. */
    public static boolean applied(long key, Runnable complete) {
        long version = fence.startDrain(key);
        if (version < 0) return false;
        drain(key, version, complete); return true;
    }
    private static void drain(long key, long version, Runnable complete) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != sourceLevel || mc.getConnection() != sourceConnection) { cancel(key); return; }
        List<PacketCodecCompat.CapturedPacket> tail = fence.takeTail(key, version);
        if (tail == null) { if (fence.overflowed(key)) repair(key); return; }
        try {
            replaying = true;
            for (var captured : tail) {
                Packet<?> packet=captured.restore(mc.level.registryAccess());
                PullOrderingProbeCompat.Client.packetStage("replay",LevelCompat.getDimensionId(mc.level),packet);
                handle(packet, mc.getConnection());
            }
        } catch (Throwable failed) { repair(key); return; }
        finally { replaying = false; }
        if (nativeOnly.contains(key) || !engineAvailable()) {
            if (fence.finishDrain(key, version)) {
                started.remove(key); nativeOnly.remove(key);
                PullOrderingProbeCompat.Client.resourceEvent(key,version,"finished");
                PullOrderingProbeCompat.Client.haloTailFinished(key,version);
                PullOrderingProbeCompat.Client.completed(key,version);
                complete.run();
            } else if (fence.overflowed(key)) repair(key);
            return;
        }
        var shadow = io.github.limuqy.mc.hassium.shadow.server.ShadowServerRegistry.getInstance().get();
        if (shadow == null) { repair(key); return; }
        try {
            shadow.execute(() -> mc.execute(() -> {
                if (mc.level != sourceLevel || mc.getConnection() != sourceConnection) { cancel(key); return; }
                if (fence.finishDrain(key, version)) {
                    started.remove(key);
                    nativeOnly.remove(key);
                    PullOrderingProbeCompat.Client.resourceEvent(key,version,"finished");
                    PullOrderingProbeCompat.Client.haloTailFinished(key,version);
                    PullOrderingProbeCompat.Client.completed(key,version);
                    complete.run();
                } else if (fence.overflowed(key)) repair(key);
                else drain(key, version, complete);
            }));
        } catch (RuntimeException stopped) { repair(key); }
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void handle(Packet packet, net.minecraft.client.multiplayer.ClientPacketListener listener) {
        packet.handle(listener);
    }
    private static void repair(long key) {
        repair(key,"repair");
    }
    private static void repair(long key,String reason) {
        PullOrderingProbeCompat.Client.resourceEvent(key,-1,reason);
        cancel(key);
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == sourceLevel && mc.getConnection() == sourceConnection)
            ShadowPullClient.requestNbtRepairFull(DimensionKey.dimensionOf(key),
                    List.of(new ChunkPos(DimensionKey.chunkXOf(key), DimensionKey.chunkZOf(key))));
    }
    public static void cancel(long key) {
        if(fence.active(key)) PullOrderingProbeCompat.Client.resourceEvent(key,-1,"cancelled");
        fence.cancel(key); started.remove(key); nativeOnly.remove(key);
    }
    public static void expire() {
        boolean available = engineAvailable();
        if (Boolean.TRUE.equals(engineState) && !available) {
            io.github.limuqy.mc.hassium.client.sleep.SleepTerrainClient.invalidateReadySnapshots();
            for (long key : List.copyOf(started.keySet())) fallbackToNative(key);
            // Invalidate idle ready/pending work too; it must not overwrite newly native state.
            fence.clear(); started.clear(); nativeOnly.clear();
        }
        engineState = available;
        long now = System.nanoTime();
        for (var entry : List.copyOf(started.entrySet()))
            if (now - entry.getValue() > 30_000_000_000L) repair(entry.getKey(),"expired-repair");
        PullOrderingProbeCompat.Client.tick();
    }
    public static void reset() { fence.clear(); started.clear(); nativeOnly.clear(); engineState = null; sourceLevel = null; sourceConnection = null; }
    public static int activeCount() { return fence.activeCount(); }
    public static long reservedBytes() { return fence.reservedBytes(); }
    public static String activeDiagnostics() {
        var result=new StringBuilder(); long now=System.nanoTime();
        for(var state:fence.activeStates()) {
            Long start=started.get(state.key());
            result.append("[key=").append(state.key()).append(" dim=").append(DimensionKey.dimensionOf(state.key()))
                    .append(" pos=").append(DimensionKey.chunkXOf(state.key())).append(',').append(DimensionKey.chunkZOf(state.key()))
                    .append(" request=").append(state.request()).append(" token=").append(state.version())
                    .append(" ageMs=").append(start==null?"unknown":(now-start)/1_000_000L)
                    .append(" bound=").append(state.bound()).append(" draining=").append(state.draining())
                    .append(" packets=").append(state.packets()).append(" bytes=").append(state.bytes())
                    .append(" overflow=").append(state.overflow()).append("] ");
        }
        return result.toString();
    }
}
