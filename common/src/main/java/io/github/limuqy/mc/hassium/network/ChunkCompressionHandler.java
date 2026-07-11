package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compression.CompressionOptions;
import io.github.limuqy.mc.hassium.compression.CompressionService;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.LevelChunk;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

/**
 * 处理区块数据的压缩和解压
 */
public class ChunkCompressionHandler {

    private static final CompressionService compressionService = CompressionService.getInstance();

    /**
     * 压缩区块数据
     *
     * @param chunkData 原始区块数据
     * @return 压缩后的数据包
     */
    public static CompressedChunkData compressChunkData(byte[] chunkData, int chunkX, int chunkZ) {
        long startTime = System.nanoTime();

        try {
            // 获取压缩配置
            int compressionLevel = HassiumConfigService.getNetworkCompressionLevel();
            String algorithm = HassiumConfigService.getNetworkCompressionAlgorithm();

            // 执行压缩
            byte[] compressed = compressionService.compress(chunkData, algorithm, compressionLevel);

            long endTime = System.nanoTime();
            double durationMs = (endTime - startTime) / 1_000_000.0;
            double compressionRatio = (double) chunkData.length / compressed.length;

            Constants.LOG.debug("Hassium/Network: Compressed chunk [{}, {}] {} bytes -> {} bytes (ratio: {}, time: {} ms)",
                    chunkX, chunkZ, chunkData.length, compressed.length,
                    String.format("%.2f", compressionRatio), String.format("%.2f", durationMs));

            return new CompressedChunkData(chunkX, chunkZ, compressed, chunkData.length, algorithm);

        } catch (Exception e) {
            Constants.LOG.error("Hassium/Network: Failed to compress chunk [{}, {}]", chunkX, chunkZ, e);
            return null;
        }
    }

    /**
     * 解压区块数据
     *
     * @param compressed 压缩的数据包
     * @return 原始区块数据
     */
    public static byte[] decompressChunkData(CompressedChunkData compressed) {
        long startTime = System.nanoTime();

        try {
            byte[] decompressed = compressionService.decompress(
                    compressed.compressedData,
                    compressed.algorithm
            );

            long endTime = System.nanoTime();
            double durationMs = (endTime - startTime) / 1_000_000.0;

            Constants.LOG.debug("Hassium/Network: Decompressed chunk [{}, {}] {} bytes -> {} bytes (time: {} ms)",
                    compressed.chunkX, compressed.chunkZ,
                    compressed.compressedData.length, decompressed.length,
                    String.format("%.2f", durationMs));

            return decompressed;

        } catch (Exception e) {
            Constants.LOG.error("Hassium/Network: Failed to decompress chunk [{}, {}]",
                    compressed.chunkX, compressed.chunkZ, e);
            return null;
        }
    }

    /**
     * 直接从原始数据解压（用于异步解压场景，无需 CompressedChunkData 对象）
     *
     * @param chunkX          区块X坐标（仅用于日志）
     * @param chunkZ          区块Z坐标（仅用于日志）
     * @param compressedData  压缩后的数据
     * @param algorithm       压缩算法名称
     * @return 原始区块数据
     */
    public static byte[] decompressChunkDataFromRaw(int chunkX, int chunkZ, byte[] compressedData, String algorithm) {
        long startTime = System.nanoTime();

        try {
            byte[] decompressed = compressionService.decompress(compressedData, algorithm);

            long endTime = System.nanoTime();
            double durationMs = (endTime - startTime) / 1_000_000.0;

            Constants.LOG.debug("Hassium/Network: Decompressed chunk [{}, {}] {} bytes -> {} bytes (time: {} ms)",
                    chunkX, chunkZ,
                    compressedData.length, decompressed.length,
                    String.format("%.2f", durationMs));

            return decompressed;

        } catch (Exception e) {
            Constants.LOG.error("Hassium/Network: Failed to decompress chunk [{}, {}]", chunkX, chunkZ, e);
            return null;
        }
    }

    /**
     * 直接从原始数据解压（使用压缩数据中携带的算法信息）
     *
     * @param chunkX          区块X坐标
     * @param chunkZ          区块Z坐标
     * @param compressedData  压缩后的数据
     * @return 原始区块数据
     */
    public static byte[] decompressChunkDataFromRaw(int chunkX, int chunkZ, byte[] compressedData) {
        // 从配置获取默认算法
        String algorithm = HassiumConfigService.getNetworkCompressionAlgorithm();
        return decompressChunkDataFromRaw(chunkX, chunkZ, compressedData, algorithm);
    }

    /**
     * 压缩的区块数据
     */
    public static class CompressedChunkData {
        public final int chunkX;
        public final int chunkZ;
        public final byte[] compressedData;
        public final int originalSize;
        public final String algorithm;

        public CompressedChunkData(int chunkX, int chunkZ, byte[] compressedData,
                                   int originalSize, String algorithm) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.compressedData = compressedData;
            this.originalSize = originalSize;
            this.algorithm = algorithm;
        }

        public byte[] encode() {
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                 DataOutputStream dos = new DataOutputStream(baos)) {

                dos.writeInt(chunkX);
                dos.writeInt(chunkZ);
                dos.writeInt(originalSize);
                dos.writeUTF(algorithm);
                dos.writeInt(compressedData.length);
                dos.write(compressedData);

                return baos.toByteArray();
            } catch (Exception e) {
                Constants.LOG.error("Failed to encode compressed chunk data", e);
                return new byte[0];
            }
        }

        public static CompressedChunkData decode(byte[] data) {
            try (java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(data);
                 java.io.DataInputStream dis = new java.io.DataInputStream(bais)) {

                int chunkX = dis.readInt();
                int chunkZ = dis.readInt();
                int originalSize = dis.readInt();
                String algorithm = dis.readUTF();
                int compressedLength = dis.readInt();
                byte[] compressedData = new byte[compressedLength];
                dis.readFully(compressedData);

                return new CompressedChunkData(chunkX, chunkZ, compressedData, originalSize, algorithm);
            } catch (Exception e) {
                Constants.LOG.error("Failed to decode compressed chunk data", e);
                return null;
            }
        }
    }
}
