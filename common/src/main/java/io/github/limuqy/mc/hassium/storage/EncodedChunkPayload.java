package io.github.limuqy.mc.hassium.storage;

/**
 * 编码后的区块 payload
 * <p>
 * 格式：[compressionType(1 byte)][compressedData]
 */
public record EncodedChunkPayload(
        byte[] encodedData,
        int uncompressedLength,
        int compressedLength
) {
    /**
     * 压缩类型：126 = ZSTD 字典压缩
     */
    public static final byte COMPRESSION_TYPE = (byte) 126;

    /**
     * 获取编码数据大小
     */
    public int encodedSize() {
        return encodedData.length;
    }

    /**
     * 计算压缩率（压缩后/压缩前）
     */
    public double compressionRatio() {
        if (uncompressedLength == 0) return 1.0;
        return (double) compressedLength / uncompressedLength;
    }

    /**
     * 获取压缩数据（不包含压缩类型字节）
     */
    public byte[] getCompressedData() {
        if (encodedData.length <= 1) {
            return new byte[0];
        }
        byte[] compressed = new byte[encodedData.length - 1];
        System.arraycopy(encodedData, 1, compressed, 0, compressed.length);
        return compressed;
    }

    /**
     * 检查是否为 Hassium 压缩
     */
    public boolean isHassiumCompressed() {
        return encodedData.length > 0 && encodedData[0] == COMPRESSION_TYPE;
    }

    /**
     * 创建 EncodedChunkPayload
     *
     * @param compressedData   压缩后的数据（不含压缩类型字节）
     * @param uncompressedLength 原始数据长度
     * @return EncodedChunkPayload
     */
    public static EncodedChunkPayload create(byte[] compressedData, int uncompressedLength) {
        byte[] encodedData = new byte[1 + compressedData.length];
        encodedData[0] = COMPRESSION_TYPE;
        System.arraycopy(compressedData, 0, encodedData, 1, compressedData.length);

        return new EncodedChunkPayload(
                encodedData,
                uncompressedLength,
                compressedData.length
        );
    }
}
