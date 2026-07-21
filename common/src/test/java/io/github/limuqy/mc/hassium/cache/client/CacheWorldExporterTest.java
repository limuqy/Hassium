package io.github.limuqy.mc.hassium.cache.client;

import io.github.limuqy.mc.hassium.compat.CompoundTagCompat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheWorldExporterTest {

    @TempDir
    Path tempDir;

    @Test
    void writeNbtToFileShouldWriteReadableGzipNbt() throws Exception {
        CompoundTag expected = new CompoundTag();
        expected.putString("LevelName", "exported-world");
        expected.putInt("DataVersion", 3465);
        Path levelDat = tempDir.resolve("level.dat");

        CacheWorldExporter.writeNbtToFile(expected, levelDat);

        try (InputStream input = Files.newInputStream(levelDat)) {
            CompoundTag actual = NbtIo.readCompressed(input);
            assertEquals("exported-world", actual.getString("LevelName"));
            assertEquals(3465, actual.getInt("DataVersion"));
        }
        try (DataInputStream input = new DataInputStream(new GZIPInputStream(Files.newInputStream(levelDat)))) {
            CompoundTag actual = NbtIo.read(input);
            assertEquals("exported-world", actual.getString("LevelName"));
            assertEquals(3465, actual.getInt("DataVersion"));
        }
    }

    @Test
    void conversionShouldProduceVanillaStructureForEmptySections() throws Exception {
        CompoundTag cached = minimalCachedChunk();
        cached.put("sections", new ListTag());

        CompoundTag result = VanillaChunkNbtCompat.convert(cached, null, -4);

        assertEquals(4, CompoundTagCompat.getInt(result, "xPos", 0));
        assertEquals(-4, CompoundTagCompat.getInt(result, "yPos", 0));
        assertEquals(-2, CompoundTagCompat.getInt(result, "zPos", 0));
        assertEquals("minecraft:full", result.getString("Status"));
        assertTrue(result.contains("Heightmaps", 10));
        assertTrue(result.contains("block_entities", 9));
        assertTrue(result.contains("sections", 9));
        assertFalse(result.contains("isLightOn"));
    }

    @Test
    void conversionShouldRejectSectionWithoutPacketData() {
        CompoundTag cached = minimalCachedChunk();
        cached.put("sections", new ListTag());
        CompoundTag section = new CompoundTag();
        cached.getList("sections", 10).add(section);

        VanillaChunkNbtCompat.ConversionException failure = assertThrows(
                VanillaChunkNbtCompat.ConversionException.class,
                () -> VanillaChunkNbtCompat.convert(cached, null, -4));
        assertTrue(failure.getMessage().contains("data byte array"));
    }

    private static CompoundTag minimalCachedChunk() {
        CompoundTag cached = new CompoundTag();
        cached.putInt("x", 4);
        cached.putInt("z", -2);
        cached.put("heightmaps", new CompoundTag());
        cached.put("block_entities", new ListTag());
        return cached;
    }
    @Test
    void closeShouldPropagateFlushFailureAndCloseFile() throws Exception {
        VanillaRegionWriter writer = new VanillaRegionWriter(
                new FailingRandomAccessFile(tempDir.resolve("failure.mca")));

        IOException failure = assertThrows(IOException.class, writer::close);

        assertEquals("flush failed", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("close failed", failure.getSuppressed()[0].getMessage());
    }

    private static final class FailingRandomAccessFile extends java.io.RandomAccessFile {

        private FailingRandomAccessFile(Path path) throws IOException {
            super(path.toFile(), "rw");
        }

        @Override
        public void seek(long position) throws IOException {
            if (position == 0) {
                throw new IOException("flush failed");
            }
            super.seek(position);
        }

        @Override
        public void close() throws IOException {
            super.close();
            throw new IOException("close failed");
        }
    }
}
