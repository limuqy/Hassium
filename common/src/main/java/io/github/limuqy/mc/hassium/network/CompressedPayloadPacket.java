package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.compression.CompressionAlgorithmId;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * 压缩区块数据包
 * <p>
 * 用于传输压缩后的区块数据。
 */
public record CompressedPayloadPacket(
        int chunkX,
        int chunkZ,
        String dimension,
        String algorithmId,
        String dictionaryId,
        int uncompressedSize,
        byte[] compressedData
) {
    /**
     * 序列化为字节数组
     */
    public byte[] encode() {
        byte[] algorithmIdBytes = algorithmId.getBytes(StandardCharsets.UTF_8);
        byte[] dictionaryIdBytes = dictionaryId != null ? dictionaryId.getBytes(StandardCharsets.UTF_8) : new byte[0];
        byte[] dimensionBytes = dimension.getBytes(StandardCharsets.UTF_8);

        int totalSize = 4 + 4 // chunkX, chunkZ
                + 4 + dimensionBytes.length // dimension
                + 4 + algorithmIdBytes.length // algorithmId
                + 4 + dictionaryIdBytes.length // dictionaryId
                + 4 // uncompressedSize
                + 4 + compressedData.length; // compressedData

        ByteBuffer buffer = ByteBuffer.allocate(totalSize);
        buffer.order(ByteOrder.BIG_ENDIAN);

        // 区块坐标
        buffer.putInt(chunkX);
        buffer.putInt(chunkZ);

        // 维度
        buffer.putInt(dimensionBytes.length);
        buffer.put(dimensionBytes);

        // 算法 ID
        buffer.putInt(algorithmIdBytes.length);
        buffer.put(algorithmIdBytes);

        // 字典 ID
        buffer.putInt(dictionaryIdBytes.length);
        if (dictionaryIdBytes.length > 0) {
            buffer.put(dictionaryIdBytes);
        }

        // 未压缩大小
        buffer.putInt(uncompressedSize);

        // 压缩数据
        buffer.putInt(compressedData.length);
        buffer.put(compressedData);

        return buffer.array();
    }

    /**
     * 从字节数组反序列化
     */
    public static CompressedPayloadPacket decode(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.BIG_ENDIAN);

        // 区块坐标
        int chunkX = buffer.getInt();
        int chunkZ = buffer.getInt();

        // 维度
        int dimensionLength = buffer.getInt();
        byte[] dimensionBytes = new byte[dimensionLength];
        buffer.get(dimensionBytes);
        String dimension = new String(dimensionBytes, StandardCharsets.UTF_8);

        // 算法 ID
        int algorithmIdLength = buffer.getInt();
        byte[] algorithmIdBytes = new byte[algorithmIdLength];
        buffer.get(algorithmIdBytes);
        String algorithmId = new String(algorithmIdBytes, StandardCharsets.UTF_8);

        // 字典 ID
        int dictionaryIdLength = buffer.getInt();
        String dictionaryId = null;
        if (dictionaryIdLength > 0) {
            byte[] dictionaryIdBytes = new byte[dictionaryIdLength];
            buffer.get(dictionaryIdBytes);
            dictionaryId = new String(dictionaryIdBytes, StandardCharsets.UTF_8);
        }

        // 未压缩大小
        int uncompressedSize = buffer.getInt();

        // 压缩数据
        int compressedDataLength = buffer.getInt();
        byte[] compressedData = new byte[compressedDataLength];
        buffer.get(compressedData);

        return new CompressedPayloadPacket(
                chunkX,
                chunkZ,
                dimension,
                algorithmId,
                dictionaryId,
                uncompressedSize,
                compressedData
        );
    }

    /**
     * 计算压缩率
     */
    public double compressionRatio() {
        if (uncompressedSize == 0) return 0;
        return (double) compressedData.length / uncompressedSize * 100.0;
    }

    /**
     * 获取压缩算法 ID
     */
    public CompressionAlgorithmId getAlgorithmId() {
        return CompressionAlgorithmId.parse(algorithmId);
    }
}
