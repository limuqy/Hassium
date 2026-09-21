package io.github.limuqy.mc.hassium.protocol;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.shadow.light.SeedGenChunkCodec;
import io.github.limuqy.mc.hassium.shadow.server.ShadowSeedServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * FULL compare 的一次性二进制取证。
 * <p>
 * 仅在 JVM 属性 {@code hassium.compareFullDumpDir} 指向目录时写入：每个 FULL 将服务端
 * 解压后的原始区块包与当时影子端的本地基线按同一 vanilla codec 编码后并排落盘。该属性
 * 未设置时没有目录创建、编码、哈希或 I/O。
 */
final class CompareFullDump {
    private static final String DUMP_ROOT = System.getProperty("hassium.compareFullDumpDir");
    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();


    private CompareFullDump() {
    }

    static void write(String dimension, ChunkPos pos, byte[] serverPacket, ShadowSeedServer shadow) {
        if (DUMP_ROOT == null || DUMP_ROOT.isBlank() || dimension == null || pos == null
                || serverPacket == null || shadow == null) {
            return;
        }
        try {
            LevelChunk localChunk = shadow.injectedChunk(dimension, pos.x, pos.z);
            ServerLevel level = shadow.level(dimension);
            if (localChunk == null || level == null) {
                return;
            }
            byte[] localPacket = SeedGenChunkCodec.encode(
                    SeedGenChunkCodec.buildPacket(localChunk, level), level.registryAccess());
            Path directory = Path.of(DUMP_ROOT)
                    .resolve(sanitize(dimension))
                    .resolve(pos.x + "_" + pos.z + "_" + System.nanoTime());
            Files.createDirectories(directory);
            Files.write(directory.resolve("server-full.bin"), serverPacket,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Files.write(directory.resolve("local-baseline.bin"), localPacket,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Files.writeString(directory.resolve("metadata.txt"),
                    "dimension=" + dimension + System.lineSeparator()
                            + "chunkX=" + pos.x + System.lineSeparator()
                            + "chunkZ=" + pos.z + System.lineSeparator()
                            + "serverBytes=" + serverPacket.length + System.lineSeparator()
                            + "localBytes=" + localPacket.length + System.lineSeparator()
                            + "serverSha256=" + sha256(serverPacket) + System.lineSeparator()
                            + "localSha256=" + sha256(localPacket) + System.lineSeparator(),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException | RuntimeException exception) {
            Constants.LOG.warn("[SHADOW_PULL] compare FULL dump failed ({}, {}) dim={}",
                    pos.x, pos.z, dimension, exception);
        }
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            char[] hex = new char[digest.length * 2];
            final char[] digits = HEX_DIGITS;
            for (int i = 0; i < digest.length; i++) {
                int value = digest[i] & 0xff;
                hex[i * 2] = digits[value >>> 4];
                hex[i * 2 + 1] = digits[value & 0x0f];
            }
            return new String(hex);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
