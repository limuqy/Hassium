package io.github.limuqy.mc.hassium.isolation;

import io.github.limuqy.mc.hassium.compression.HassiumCompression;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.mixin.server.MixinLevelTicks;
import io.github.limuqy.mc.hassium.mixin.shadow.*;
import io.github.limuqy.mc.hassium.server.RuntimeServerContext;
import io.github.limuqy.mc.hassium.platform.client.ShadowClientApi;
import io.github.limuqy.mc.hassium.platform.client.ShadowClientBridge;
import io.github.limuqy.mc.hassium.shadow.server.SeedGenLevelCompat;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.ticks.ScheduledTick;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.asm.mixin.injection.callback.*;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Regression probes execute the actual injected handlers without launching a game. */
public class ClientServerIsolationRegressionTest {
    static {
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();
    }
    @TempDir Path directory;
    private static final ChunkPos POS = new ChunkPos(0, 0);
    @AfterEach void reset() {
        RuntimeServerContext.setShadowServer(false);
        RuntimeServerContext.setDedicatedServer(false);
        ShadowClientBridge.unregister();
        RuntimeServerContext.setActiveServer(null);
        io.github.limuqy.mc.hassium.server.ServerHandshakeActivation.removePlayer(new java.util.UUID(0L, 42L));
        io.github.limuqy.mc.hassium.server.PlayerCompressionTracker.clear();
    }
    private static Method handler(Class<?> owner, String suffix, Class<?>... args) throws Exception {
        Method method = owner.getDeclaredMethod("hassium" + (char) 36 + suffix, args);
        method.setAccessible(true);
        return method;
    }
    @Test void coldIntegratedContextDoesNotCancelScheduledTick() throws Exception {
        RuntimeServerContext.setShadowServer(false);
        CallbackInfo callback = new CallbackInfo("schedule", true);
        handler(MixinLevelTicks.class, "skipScheduleOnShadow", ScheduledTick.class, CallbackInfo.class)
                .invoke(new MixinLevelTicks(), null, callback);
        assertFalse(callback.isCancelled());
    }
    @Test void onePlayerEpochCannotInvalidateAnotherPlayersPullLedger() {
        var ledger = new io.github.limuqy.mc.hassium.protocol.ShadowPullRequestLedger();
        var first = new java.util.UUID(0L, 101L);
        var second = new java.util.UUID(0L, 102L);
        assertEquals(io.github.limuqy.mc.hassium.protocol.ShadowPullRequestLedger.Decision.ACCEPT,
                ledger.accept(first, 0L, 1L, 111L));
        assertEquals(io.github.limuqy.mc.hassium.protocol.ShadowPullRequestLedger.Decision.ACCEPT,
                ledger.accept(second, 1L, 1L, 222L));
        assertEquals(io.github.limuqy.mc.hassium.protocol.ShadowPullRequestLedger.Decision.ACCEPT,
                ledger.accept(first, 0L, 2L, 333L));
    }
    @Test void shadowAssemblyCannotCancelUnownedScheduledTicks() throws Exception {
        Thread assembly = new Thread(RuntimeServerContext::beginShadowServer, "audit-shadow-assembly");
        assembly.start();
        assembly.join();
        CallbackInfo callback = new CallbackInfo("schedule", true);
        handler(MixinLevelTicks.class, "skipScheduleOnShadow", ScheduledTick.class, CallbackInfo.class)
                .invoke(new MixinLevelTicks(), null, callback);
        assertFalse(callback.isCancelled(), "Unowned ticks must retain vanilla scheduling during shadow assembly");
    }
    @Test void shadowFlagDoesNotSuppressNonChunkBroadcast() throws Exception {
        RuntimeServerContext.beginShadowServer();
        CallbackInfo callback = new CallbackInfo("broadcast", true);
        handler(MixinChunkHolder.class, "onBroadcast", List.class,
                net.minecraft.network.protocol.Packet.class, CallbackInfo.class)
                .invoke(new MixinChunkHolder(), List.of(), null, callback);
        assertFalse(callback.isCancelled());
    }
#if MC_VER >= MC_1_21_1
    @Test void compressionWithoutPullModePreservesWholeChunk() throws Exception {
        RuntimeServerContext.setShadowServer(false);
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) field.get(null);
        var player = (FakePlayer) unsafe.allocateInstance(FakePlayer.class);
        var listener = (FakeListener) unsafe.allocateInstance(FakeListener.class);
        player.connection = listener;
        listener.player = player;
        bindRealServerLevel(player, unsafe);
        var id = player.getUUID();
        io.github.limuqy.mc.hassium.server.ServerHandshakeActivation.removePlayer(id);
        io.github.limuqy.mc.hassium.server.PlayerCompressionTracker.clear();
        io.github.limuqy.mc.hassium.server.PlayerCompressionTracker.markNegotiatedCaps(id,
                io.github.limuqy.mc.hassium.protocol.handshake.LoginCaps.SHADOW_PULL);
        try {
            io.github.limuqy.mc.hassium.server.ServerHandshakeActivation.onPlayerInit(player);
            assertTrue(io.github.limuqy.mc.hassium.server.PlayerCompressionTracker.isCompressionEnabled(player));
            assertFalse(io.github.limuqy.mc.hassium.server.ServerHandshakeActivation.hasCaps(id,
                    io.github.limuqy.mc.hassium.protocol.handshake.LoginCaps.PULL_MODE));
            var packet = (net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket)
                    unsafe.allocateInstance(net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket.class);
            handler(io.github.limuqy.mc.hassium.mixin.server.MixinPlayerChunkSender.class,
                    "onChunkPacketSend", net.minecraft.server.network.ServerGamePacketListenerImpl.class,
                    net.minecraft.network.protocol.Packet.class).invoke(null, listener, packet);
            assertEquals(1, listener.sent, "No PULL_MODE: send the original chunk");
        } finally {
            io.github.limuqy.mc.hassium.server.ServerHandshakeActivation.removePlayer(id);
            io.github.limuqy.mc.hassium.server.PlayerCompressionTracker.clear();
        }
    }
#endif
    @Test void unnegotiatedLightBroadcastIsSentExactlyOnce() throws Exception {
        RuntimeServerContext.setShadowServer(false);
        io.github.limuqy.mc.hassium.server.PlayerCompressionTracker.clear();
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) field.get(null);
        var player = (FakePlayer) unsafe.allocateInstance(FakePlayer.class);
        var listener = (FakeListener) unsafe.allocateInstance(FakeListener.class);
        player.connection = listener;
        var packet = (net.minecraft.network.protocol.game.ClientboundLightUpdatePacket)
                unsafe.allocateInstance(net.minecraft.network.protocol.game.ClientboundLightUpdatePacket.class);
        var callback = new CallbackInfo("broadcast", true);
        handler(MixinChunkHolder.class, "onBroadcast", List.class,
                net.minecraft.network.protocol.Packet.class, CallbackInfo.class)
                .invoke(new MixinChunkHolder(), List.of(player), packet, callback);
        assertFalse(callback.isCancelled());
        assertEquals(0, listener.sent);
        var broadcast = net.minecraft.server.level.ChunkHolder.class.getDeclaredMethod("broadcast", List.class,
                net.minecraft.network.protocol.Packet.class);
        broadcast.setAccessible(true);
        broadcast.invoke(unsafe.allocateInstance(net.minecraft.server.level.ChunkHolder.class), List.of(player), packet);
        assertEquals(1, listener.sent, "Vanilla body is the sole sender when nobody is filtered");
    }
    @Test void normalZlibRegionRemainsDelegatedDuringShadowAssembly() throws Exception {
        try (FakeRegion region = new FakeRegion(directory)) {
            ByteBuffer sector = ByteBuffer.allocate(8).putInt(4).put((byte) 2).put(new byte[]{1, 2, 3});
            sector.flip();
            region.channel.write(sector, 8192);
            Method read = handler(MixinRegionFile.class, "onGetChunkDataInputStream",
                    ChunkPos.class, CallbackInfoReturnable.class);
            RuntimeServerContext.setShadowServer(false);
            var cold = new CallbackInfoReturnable<DataInputStream>("read", true);
            read.invoke(region, POS, cold);
            assertFalse(cold.isCancelled(), "Normal region is delegated to vanilla with no shadow flag");
            RuntimeServerContext.beginShadowServer();
            var overlapping = new CallbackInfoReturnable<DataInputStream>("read", true);
            read.invoke(region, POS, overlapping);
            assertFalse(overlapping.isCancelled());
            assertNull(overlapping.getReturnValue(), "Unowned vanilla region is reported as a cache miss");
        }
    }
    @Test void normalRegionDoesNotWriteType126DuringShadowAssembly() throws Exception {
        if (!HassiumCompression.isInitialized()) HassiumCompression.initialize();
        RuntimeServerContext.setDedicatedServer(false);
        RuntimeServerContext.setShadowServer(false);
        assertFalse(HassiumConfigService.getInstance().isStorageEnabled());
        try (FakeRegion region = new FakeRegion(directory)) {
            Method write = handler(MixinRegionFile.class, "onGetChunkDataOutputStream",
                    ChunkPos.class, CallbackInfoReturnable.class);
            var cold = new CallbackInfoReturnable<DataOutputStream>("write", true);
            write.invoke(region, POS, cold);
            assertFalse(cold.isCancelled(), "Cold integrated write delegates to vanilla");
            RuntimeServerContext.beginShadowServer();
            var callback = new CallbackInfoReturnable<DataOutputStream>("write", true);
            write.invoke(region, POS, callback);
            assertFalse(callback.isCancelled());
            assertNull(region.written);
        }
    }
    @Test void worldRootResolutionFailureCleansAssemblyState() throws Exception {
        RuntimeServerContext.setShadowServer(false);
        ShadowClientApi api = (ShadowClientApi) java.lang.reflect.Proxy.newProxyInstance(
                ShadowClientApi.class.getClassLoader(), new Class<?>[]{ShadowClientApi.class},
                (proxy, method, args) -> method.getName().equals("getGameDir") ? directory : null);
        ShadowClientBridge.register(api);
        Method assemble = SeedGenLevelCompat.class.getDeclaredMethod("assembleShadowServer", long.class);
        assemble.setAccessible(true);
        var thrown = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> assemble.invoke(null, 0L));
        assertInstanceOf(IOException.class, thrown.getCause());
        assertFalse(RuntimeServerContext.isShadowServerContext(),
                "Root resolution fails before the cleanup catch block starts");
    }
    @Test void lightNullGuardPreservesVanillaNullForUnownedStorage() throws Exception {
        RuntimeServerContext.setShadowServer(false);
        var mixin = new MixinLayerLightSectionStorage() {};
        DataLayer layer = (DataLayer) handler(MixinLayerLightSectionStorage.class,
                "nullSafeGetStoredLevel", DataLayer.class).invoke(mixin, new Object[]{null});
        assertNull(layer);
        DataLayer normal = new DataLayer();
        assertSame(normal, handler(MixinLayerLightSectionStorage.class,
                "nullSafeGetStoredLevel", DataLayer.class).invoke(mixin, normal));
    }
    @Test void onlyOwnedShadowTicksAreSuppressed() throws Exception {
        var ticks = new MixinLevelTicks();
        ticks.hassium$setShadowOwner(true);
        CallbackInfo callback = new CallbackInfo("schedule", true);
        handler(MixinLevelTicks.class, "skipScheduleOnShadow", ScheduledTick.class, CallbackInfo.class)
                .invoke(ticks, null, callback);
        assertTrue(callback.isCancelled());
    }
    @Test void ownedShadowRegionStillWrites126WithStorageDisabled() throws Exception {
        if (!HassiumCompression.isInitialized()) HassiumCompression.initialize();
        try (var owner = io.github.limuqy.mc.hassium.shadow.server.ShadowServerOwnership.register(directory);
             FakeRegion region = new FakeRegion(directory)) {
            var callback = new CallbackInfoReturnable<DataOutputStream>("write", true);
            handler(MixinRegionFile.class, "onGetChunkDataOutputStream", ChunkPos.class,
                    CallbackInfoReturnable.class).invoke(region, POS, callback);
            assertTrue(callback.isCancelled());
            try (DataOutputStream output = callback.getReturnValue()) { output.write(new byte[]{10, 0, 0, 0}); }
            assertEquals(126, region.written[4] & 255);
            assertNull(io.github.limuqy.mc.hassium.shadow.server.ShadowServerOwnership.find(directory.resolveSibling("ordinary")));
        }
        assertNull(io.github.limuqy.mc.hassium.shadow.server.ShadowServerOwnership.find(directory));
    }
    @Test void resourceScopeCrossesOnlyExplicitExecutorsAndRestoresOnFailure() throws Exception {
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var wrapped = io.github.limuqy.mc.hassium.shadow.server.ShadowAssemblyScope.wrap(executor);
            var result = new java.util.concurrent.CompletableFuture<Boolean>();
            wrapped.execute(() -> result.complete(io.github.limuqy.mc.hassium.shadow.server.ShadowAssemblyScope.isActive()));
            assertTrue(result.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(executor.submit(io.github.limuqy.mc.hassium.shadow.server.ShadowAssemblyScope::isActive).get());
            assertThrows(IllegalStateException.class, () ->
                    io.github.limuqy.mc.hassium.shadow.server.ShadowAssemblyScope.call(() -> { throw new IllegalStateException(); }));
            assertFalse(io.github.limuqy.mc.hassium.shadow.server.ShadowAssemblyScope.isActive());
        } finally { executor.shutdownNow(); }
    }
    @Test void unauthorizedPullNeverTouchesThePlayersWorldOrQueuesWork() throws Exception {
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) field.get(null);
        var player = (FakePlayer) unsafe.allocateInstance(FakePlayer.class);
        var entry = new io.github.limuqy.mc.hassium.protocol.ShadowPullRequestC2SPacket.Entry(0, 0, 0L, List.of(), 1);
        var request = new io.github.limuqy.mc.hassium.protocol.ShadowPullRequestC2SPacket(
                "minecraft:overworld", 0L, 0L, List.of(entry));
        var response = io.github.limuqy.mc.hassium.protocol.ShadowPullServer.handleRequest(player, request);
        assertEquals("capability", response.results().get(0).error());
        assertEquals("permission", io.github.limuqy.mc.hassium.server.ServerChunkPushManager.getInstance()
                .resolveShadowPull(player, request, entry, request.dimension()).error());
    }
    private static void bindRealServerLevel(FakePlayer player, sun.misc.Unsafe unsafe) throws Exception {
        var server = (net.minecraft.server.dedicated.DedicatedServer) unsafe.allocateInstance(
                net.minecraft.server.dedicated.DedicatedServer.class);
        var level = (net.minecraft.server.level.ServerLevel) unsafe.allocateInstance(net.minecraft.server.level.ServerLevel.class);
        var serverField = net.minecraft.server.level.ServerLevel.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(level, server);
        var levelField = net.minecraft.world.entity.Entity.class.getDeclaredField("level");
        levelField.setAccessible(true);
        levelField.set(player, level);
        RuntimeServerContext.setActiveServer(server);
        RuntimeServerContext.setDedicatedServer(true);
    }
    @Test void mixedLightBroadcastFiltersOnlyPullRecipientsAndSendsVanillaOnce() throws Exception {
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) field.get(null);
        var pull = (FakePlayer) unsafe.allocateInstance(FakePlayer.class);
        var plain = (FakePlayer) unsafe.allocateInstance(FakePlayer.class);
        plain.id = new java.util.UUID(0, 43);
        var pullListener = (FakeListener) unsafe.allocateInstance(FakeListener.class);
        var plainListener = (FakeListener) unsafe.allocateInstance(FakeListener.class);
        pull.connection = pullListener;
        plain.connection = plainListener;
        bindRealServerLevel(pull, unsafe);
        io.github.limuqy.mc.hassium.server.PlayerCompressionTracker.markNegotiatedCaps(pull.getUUID(),
                io.github.limuqy.mc.hassium.protocol.handshake.LoginCaps.PULL_MODE
                        | io.github.limuqy.mc.hassium.protocol.handshake.LoginCaps.SHADOW_PULL);
        io.github.limuqy.mc.hassium.server.ServerHandshakeActivation.onPlayerInit(pull);
        assertTrue(io.github.limuqy.mc.hassium.server.ServerNetworkGate.shouldTakeOverChunks(pull));
        var packet = (net.minecraft.network.protocol.game.ClientboundLightUpdatePacket)
                unsafe.allocateInstance(net.minecraft.network.protocol.game.ClientboundLightUpdatePacket.class);
        var callback = new CallbackInfo("broadcast", true);
        handler(MixinChunkHolder.class, "onBroadcast", List.class, net.minecraft.network.protocol.Packet.class, CallbackInfo.class)
                .invoke(new MixinChunkHolder(), List.of(pull, plain), packet, callback);
        assertTrue(callback.isCancelled());
        assertEquals(0, pullListener.sent);
        assertEquals(1, plainListener.sent);
        var allPull = new CallbackInfo("broadcast", true);
        handler(MixinChunkHolder.class, "onBroadcast", List.class, net.minecraft.network.protocol.Packet.class, CallbackInfo.class)
                .invoke(new MixinChunkHolder(), List.of(pull), packet, allPull);
        assertTrue(allPull.isCancelled());
        assertEquals(0, pullListener.sent);
    }
#if MC_VER < MC_1_21_1
    @Test void legacyCompressionWithoutPullCannotSubstituteAnotherColumnsDummyPacket() throws Exception {
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) field.get(null);
        var player = (FakePlayer) unsafe.allocateInstance(FakePlayer.class);
        bindRealServerLevel(player, unsafe);
        io.github.limuqy.mc.hassium.server.PlayerCompressionTracker.markNegotiatedCaps(player.getUUID(),
                io.github.limuqy.mc.hassium.protocol.handshake.LoginCaps.SHADOW_PULL);
        io.github.limuqy.mc.hassium.server.ServerHandshakeActivation.onPlayerInit(player);
        var holder = new org.apache.commons.lang3.mutable.MutableObject<net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket>();
        Object result = handler(MixinChunkMap.class, "skipVanillaPacketBuild",
                org.apache.commons.lang3.mutable.MutableObject.class, net.minecraft.server.level.ServerPlayer.class,
                org.apache.commons.lang3.mutable.MutableObject.class, net.minecraft.world.level.chunk.LevelChunk.class)
                .invoke(new MixinChunkMap(), holder, player, holder, null);
        assertSame(holder, result);
    }
#endif
    @Test void earlyExternalIoConvertsOnlyOwnedShadowSectorsAndDoesNotRecurse() throws Exception {
        if (!HassiumCompression.isInitialized()) HassiumCompression.initialize();
        byte[] raw = {10, 0, 0, 0};
        var compressed = new ByteArrayOutputStream();
        try (var zlib = new java.util.zip.DeflaterOutputStream(compressed)) { zlib.write(raw); }
        byte[] payload = compressed.toByteArray();
        ByteBuffer sector = ByteBuffer.allocate(5 + payload.length).putInt(1 + payload.length).put((byte)2).put(payload);
        sector.flip();
        Method write = handler(MixinRegionFile.class, "adoptShadowWrite", ChunkPos.class, ByteBuffer.class, CallbackInfo.class);
        try (FakeRegion ordinary = new FakeRegion(directory)) {
            var callback = new CallbackInfo("write", true);
            write.invoke(ordinary, POS, sector.duplicate(), callback);
            assertFalse(callback.isCancelled());
            assertNull(ordinary.written);
        }
        try (var owner = io.github.limuqy.mc.hassium.shadow.server.ShadowServerOwnership.register(directory);
             FakeRegion shadow = new FakeRegion(directory)) {
            var callback = new CallbackInfo("write", true);
            write.invoke(shadow, POS, sector.duplicate(), callback);
            assertTrue(callback.isCancelled());
            assertEquals(126, shadow.written[4] & 255);
            assertArrayEquals(raw, io.github.limuqy.mc.hassium.shadow.storage.HassiumType126Codec.decode(
                    io.github.limuqy.mc.hassium.shadow.storage.HassiumType126Codec.payloadAfterType(shadow.written)).nbt());
            var reentry = new CallbackInfo("write", true);
            write.invoke(shadow, POS, ByteBuffer.wrap(shadow.written), reentry);
            assertFalse(reentry.isCancelled(), "The converted sector must reach the original writer exactly once");
        }
    }
    private static final class LifecycleSlot {
        private static net.minecraft.server.MinecraftServer currentServer;
    }
    @Test void loaderServerSlotNeverOverwritesOrClearsARealServer() throws Exception {
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) field.get(null);
        var shadow = (io.github.limuqy.mc.hassium.shadow.server.ShadowSeedServer) unsafe.allocateInstance(
                io.github.limuqy.mc.hassium.shadow.server.ShadowSeedServer.class);
        var real = (net.minecraft.server.dedicated.DedicatedServer) unsafe.allocateInstance(net.minecraft.server.dedicated.DedicatedServer.class);
        Class<?> compat = io.github.limuqy.mc.hassium.compat.ServerLifecycleHooksCompat.class;
        var fields = compat.getDeclaredField("resolvedFields");
        var resolved = compat.getDeclaredField("resolved");
        fields.setAccessible(true);
        resolved.setAccessible(true);
        Object previousFields = fields.get(null);
        boolean previousResolved = resolved.getBoolean(null);
        try {
            fields.set(null, List.of(LifecycleSlot.class.getDeclaredField("currentServer")));
            resolved.setBoolean(null, true);
            RuntimeServerContext.setActiveServer(null);
            LifecycleSlot.currentServer = null;
            io.github.limuqy.mc.hassium.compat.ServerLifecycleHooksCompat.adoptCurrentServer(shadow);
            assertSame(shadow, LifecycleSlot.currentServer);
            io.github.limuqy.mc.hassium.compat.ServerLifecycleHooksCompat.releaseCurrentServer(shadow);
            assertNull(LifecycleSlot.currentServer);
            LifecycleSlot.currentServer = real;
            io.github.limuqy.mc.hassium.compat.ServerLifecycleHooksCompat.adoptCurrentServer(shadow);
            io.github.limuqy.mc.hassium.compat.ServerLifecycleHooksCompat.releaseCurrentServer(shadow);
            assertSame(real, LifecycleSlot.currentServer);
            LifecycleSlot.currentServer = null;
            RuntimeServerContext.setActiveServer(real);
            io.github.limuqy.mc.hassium.compat.ServerLifecycleHooksCompat.adoptCurrentServer(shadow);
            assertNull(LifecycleSlot.currentServer);
        } finally {
            LifecycleSlot.currentServer = null;
            fields.set(null, previousFields);
            resolved.setBoolean(null, previousResolved);
        }
    }
    private static final class FakeRegion extends MixinRegionFile implements RegionFileAccessor, AutoCloseable {
        final FileChannel channel;
        final Path directory;
        byte[] written;
        FakeRegion(Path directory) throws IOException {
            this.directory = directory;
            channel = FileChannel.open(directory.resolve("r.0.0.mca"), StandardOpenOption.CREATE,
                    StandardOpenOption.READ, StandardOpenOption.WRITE);
        }
        public void invokeWrite(ChunkPos pos, ByteBuffer buffer) {
            ByteBuffer copy = buffer.duplicate();
            written = new byte[copy.remaining()];
            copy.get(written);
        }
        public int invokeGetOffset(ChunkPos pos) { return (2 << 8) | 1; }
        public Path invokeGetExternalChunkPath(ChunkPos pos) { return directory.resolve("c.0.0.mcc"); }
        public FileChannel getFileChannel() { return channel; }
        public IntBuffer getOffsets() { return IntBuffer.allocate(1024); }
        public Path hassium$getExternalFileDir() { return directory; }
        public void close() throws IOException { channel.close(); }
    }
    private static final class FakePlayer extends net.minecraft.server.level.ServerPlayer {
#if MC_VER < MC_1_21_1
        private FakePlayer() { super(null, null, null); }
#else
        private FakePlayer() { super(null, null, null, null); }
#endif
        java.util.UUID id;
        @Override public java.util.UUID getUUID() { return id == null ? new java.util.UUID(0L, 42L) : id; }
    }
    private static final class FakeListener extends net.minecraft.server.network.ServerGamePacketListenerImpl {
        int sent;
#if MC_VER < MC_1_21_1
        private FakeListener() { super(null, null, null); }
#else
        private FakeListener() { super(null, null, null, null); }
#endif
        @Override public void send(net.minecraft.network.protocol.Packet<?> packet) { sent++; }
    }
}
