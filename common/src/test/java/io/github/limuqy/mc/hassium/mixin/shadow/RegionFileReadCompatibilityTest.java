package io.github.limuqy.mc.hassium.mixin.shadow;

import io.github.limuqy.mc.hassium.compression.HassiumCompression;
import io.github.limuqy.mc.hassium.server.RuntimeServerContext;
import io.github.limuqy.mc.hassium.shadow.storage.HassiumType126Codec;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.asm.mixin.injection.callback.*;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class RegionFileReadCompatibilityTest {
    @TempDir Path directory;
    private static final ChunkPos POS = new ChunkPos(0, 0);
    private static String injection(String name) { return "hassium".concat(Character.toString(36)).concat(name); }

    @Test
    void existingType126IsReadableWithStorageDisabledOnClient() throws Exception {
        byte[] raw = "persisted world with storage disabled".getBytes();
        checkRead(raw, false, false);
    }

    @Test
    void oversizedExternalType126KeepsItsTypeAndRoundTrips() throws Exception {
        byte[] raw = new byte[1_100_000];
        new Random(42).nextBytes(raw);
        checkRead(raw, true, false);
    }

    @Test
    void legacyExternalWriteWithIncorrectZlibStubIsStillReadable() throws Exception {
        checkRead("legacy mislabeled external payload".getBytes(), true, true);
    }

    private void checkRead(byte[] raw, boolean external, boolean legacy) throws Exception {
        if (!HassiumCompression.isInitialized()) HassiumCompression.initialize();
        RuntimeServerContext.setShadowServer(false);
        RuntimeServerContext.setDedicatedServer(false);
        byte[] sector = HassiumType126Codec.encodeSector(raw, 123L, 3);
        try (FakeRegion region = new FakeRegion(directory)) {
            if (external) {
                if (!legacy) assertTrue(sector.length > 255 * 4096);
                var write = MixinRegionFile.class.getDeclaredMethod(injection("captureExternalCompressionType"),
                        ChunkPos.class, ByteBuffer.class, CallbackInfo.class);
                write.setAccessible(true);
                write.invoke(region, POS, ByteBuffer.wrap(sector), new CallbackInfo("write", true));
                ByteBuffer stub = ByteBuffer.allocate(5).putInt(1).put((byte) 130);
                var callback = new CallbackInfoReturnable<ByteBuffer>("createExternalStub", false, stub);
                var patch = MixinRegionFile.class.getDeclaredMethod(injection("preserveExternalCompressionType"),
                        CallbackInfoReturnable.class);
                patch.setAccessible(true);
                patch.invoke(region, callback);
                assertEquals(254, stub.get(4) & 255);
                if (legacy) stub.put(4, (byte) 130);
                stub.flip();
                region.channel.write(stub, 8192);
                Files.write(region.invokeGetExternalChunkPath(POS), HassiumType126Codec.payloadAfterType(sector));
            } else {
                region.channel.write(ByteBuffer.wrap(sector), 8192);
            }
            var read = MixinRegionFile.class.getDeclaredMethod(injection("onGetChunkDataInputStream"),
                    ChunkPos.class, CallbackInfoReturnable.class);
            read.setAccessible(true);
            var callback = new CallbackInfoReturnable<DataInputStream>("getChunkDataInputStream", true);
            read.invoke(region, POS, callback);
            assertTrue(callback.isCancelled(), "Hassium must handle an existing type126 irrespective of write gates");
            try (var stream = callback.getReturnValue()) {
                assertNotNull(stream);
                assertArrayEquals(raw, stream.readAllBytes());
            }
        }
    }

    private static final class FakeRegion extends MixinRegionFile implements RegionFileAccessor, AutoCloseable {
        final FileChannel channel;
        final Path directory;
        FakeRegion(Path directory) throws IOException {
            this.directory = directory;
            channel = FileChannel.open(directory.resolve("r.0.0.mca"), StandardOpenOption.CREATE,
                    StandardOpenOption.READ, StandardOpenOption.WRITE);
        }
        public void invokeWrite(ChunkPos pos, ByteBuffer buffer) {}
        public int invokeGetOffset(ChunkPos pos) { return (2 << 8) | 1; }
        public Path invokeGetExternalChunkPath(ChunkPos pos) { return directory.resolve("c.0.0.mcc"); }
        public FileChannel getFileChannel() { return channel; }
        public IntBuffer getOffsets() { return IntBuffer.allocate(1024); }
        public Path hassium$getExternalFileDir() { return directory; }
        public void close() throws IOException { channel.close(); }
    }
}
