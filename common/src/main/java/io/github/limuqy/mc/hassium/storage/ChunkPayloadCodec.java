package io.github.limuqy.mc.hassium.storage;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDictCompress;
import com.github.luben.zstd.ZstdDictDecompress;
import io.github.limuqy.mc.hassium.compression.CompressionException;
import io.github.limuqy.mc.hassium.compression.CompressionService;
import io.github.limuqy.mc.hassium.compression.DictionaryRegistry;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;

/**
 * 区块 payload 编解码器
 * <p>
 * 负责将 Minecraft chunk NBT 与压缩后的 payload 互转。
 * 格式：[compressionType(1 byte)][compressedData]
 * 压缩类型：126 = ZSTD 字典压缩
 */
public class ChunkPayloadCodec {

    private static final byte HASSIUM_COMPRESSION_TYPE = (byte) 126;

    private final DictionaryRegistry dictionaryRegistry;
    private final int compressionLevel;
    private final String dictionaryId;

    public ChunkPayloadCodec(int compressionLevel) {
        this.compressionLevel = compressionLevel;
        this.dictionaryId = HassiumConfigService.getInstance().getConfig().storage().zstdDictionaryId();
        CompressionService service = CompressionService.getInstance();
        this.dictionaryRegistry = service.getDictionaryRegistry()
                .orElseThrow(() -> new RuntimeException("Dictionary registry not available"));
    }

    /**
     * 解码 payload
     *
     * @param compressionType 压缩类型标识
     * @param payloadBytes    压缩后的数据（包含压缩类型字节）
     * @param metadata        存储元数据
     * @return 解码后的区块 payload
     * @throws PayloadCodecException 解码异常
     */
    public ChunkPayload decode(int compressionType, byte[] payloadBytes, ChunkStorageMetadata metadata)
            throws PayloadCodecException {
        if (payloadBytes == null || payloadBytes.length == 0) {
            throw new PayloadCodecException("Empty payload data");
        }

        byte type = payloadBytes[0];
        if (type != HASSIUM_COMPRESSION_TYPE) {
            throw new PayloadCodecException("Unsupported compression type: " + type);
        }

        // 提取压缩数据（跳过压缩类型字节）
        byte[] compressedData = new byte[payloadBytes.length - 1];
        System.arraycopy(payloadBytes, 1, compressedData, 0, compressedData.length);

        try {
            // 使用 ZSTD 字典解压
            byte[] decompressed = decompressWithDictionary(compressedData);
            return new ChunkPayload(decompressed, ChunkPayload.CompressionType.HASSIUM_ZSTD, metadata);
        } catch (CompressionException e) {
            throw new PayloadCodecException("Decompression failed", e);
        }
    }

    /**
     * 编码 payload
     *
     * @param payload     原始区块数据
     * @return 编码后的 payload
     * @throws PayloadCodecException 编码异常
     */
    public EncodedChunkPayload encode(ChunkPayload payload) throws PayloadCodecException {
        if (payload == null || payload.data() == null) {
            throw new PayloadCodecException("Empty payload data");
        }

        try {
            // 使用 ZSTD 字典压缩
            byte[] compressed = compressWithDictionary(payload.data(), compressionLevel);
            return EncodedChunkPayload.create(compressed, payload.data().length);
        } catch (CompressionException e) {
            throw new PayloadCodecException("Compression failed", e);
        }
    }

    /**
     * 检查是否支持指定的压缩类型
     *
     * @param compressionType 压缩类型标识
     * @return 如果支持返回 true
     */
    public boolean supportsCompressionType(int compressionType) {
        return compressionType == HASSIUM_COMPRESSION_TYPE;
    }

    /**
     * 使用 ZSTD 字典压缩数据
     */
    private byte[] compressWithDictionary(byte[] data, int level) throws CompressionException {
        byte[] dictionary = dictionaryRegistry.findDictionary(dictionaryId)
                .orElseThrow(() -> new CompressionException("Dictionary not found: " + dictionaryId));

        ZstdDictCompress dict = new ZstdDictCompress(dictionary, level);
        return Zstd.compress(data, dict);
    }

    /**
     * 使用 ZSTD 字典解压数据
     */
    private byte[] decompressWithDictionary(byte[] compressedData) throws CompressionException {
        byte[] dictionary = dictionaryRegistry.findDictionary(dictionaryId)
                .orElseThrow(() -> new CompressionException("Dictionary not found: " + dictionaryId));

        ZstdDictDecompress dict = new ZstdDictDecompress(dictionary);
        // 使用推荐的解压方法：decompressFastDict
        int decompressedSize = (int) Zstd.decompressedSize(compressedData);
        if (decompressedSize <= 0) {
            decompressedSize = compressedData.length * 4; // 估算值
        }
        byte[] result = new byte[decompressedSize];
        long actualSize = Zstd.decompressFastDict(result, 0, compressedData, 0, compressedData.length, dict);
        if (actualSize <= 0) {
            throw new CompressionException.DecompressionFailedException("ZSTD dictionary decompression failed: invalid output");
        }
        // 如果实际大小与预估不同，截取实际大小
        if (actualSize < decompressedSize) {
            byte[] trimmed = new byte[(int) actualSize];
            System.arraycopy(result, 0, trimmed, 0, (int) actualSize);
            return trimmed;
        }
        return result;
    }

    /**
     * payload 编解码异常
     */
    public static class PayloadCodecException extends Exception {
        public PayloadCodecException(String message) {
            super(message);
        }

        public PayloadCodecException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
